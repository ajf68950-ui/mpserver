package ritly;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Een voertuig dat in de wereld staat. Het bestaat uit:
 * - een onzichtbare "wortel" (ItemDisplay) op de stoel van de bestuurder; de bestuurder en alle delen rijden daarop mee,
 * - een BlockDisplay per deel (de vorm), gedraaid met een transformatie,
 * - een onzichtbare ItemDisplay per extra stoel,
 * - een Interaction als klikvlak.
 * Elke tick wordt de wortel verplaatst (met interpolatie, zodat het vloeiend beweegt).
 */
final class ActiefVoertuig {

    private static final double ZWAARTEKRACHT = 0.08;
    private static final int INTERPOLATIE = 2;

    final Ritly p;
    final Voertuig v;
    final Model m;
    final World w;

    double x, y, z;
    float yaw, pitch, roll;
    double snelheid;  // blokken per tick (negatief = achteruit)
    double vy;        // verticale snelheid
    double gas;       // vliegtuig: 0..1
    double valV;      // vliegtuig: zakken door te weinig snelheid
    boolean opGrond, opWater;

    ItemDisplay wortel;
    final List<BlockDisplay> delen = new ArrayList<>();
    final List<ItemDisplay> stoelen = new ArrayList<>();
    Interaction klik;
    private final float[] hoek;

    private int stil;      // aantal ticks dat hij stilstaat zonder bestuurder
    boolean slaapt;
    boolean weg;
    boolean bezigMetWeghalen;
    private int tik;
    private float gezYaw = Float.NaN, gezPitch, gezRoll;
    private double gezX = Double.NaN, gezY, gezZ;
    long laatsteToeter;
    private double verbruikt;
    Input autopiloot;      // alleen voor /ritly testrit
    private String melding;
    private int meldingTicks;

    ActiefVoertuig(Ritly p, Voertuig v, Model m, Location plek) {
        this.p = p;
        this.v = v;
        this.m = m;
        this.w = plek.getWorld();
        this.x = plek.getX();
        this.y = plek.getY();
        this.z = plek.getZ();
        this.yaw = plek.getYaw();
        this.hoek = new float[m.delen.size()];
    }

    // ------------------------------------------------------------------ neerzetten en weghalen

    void spawn() {
        Location wl = wortelPlek();
        wortel = w.spawn(wl, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(INTERPOLATIE);
            d.setViewRange(2f);
            tag(d);
        });
        Quaternionf q = draaiing();
        for (int i = 0; i < m.delen.size(); i++) {
            Model.Deel deel = m.delen.get(i);
            BlockData data = deel.data(v.kleur);
            final int idx = i;
            BlockDisplay bd = w.spawn(wl, BlockDisplay.class, b -> {
                b.setPersistent(false);
                b.setBlock(data);
                b.setViewRange(2f);
                b.setTransformation(transformatie(deel, q, hoek[idx]));
                tag(b);
            });
            wortel.addPassenger(bd);
            delen.add(bd);
        }
        for (int i = 1; i < m.zitten.size(); i++) {
            ItemDisplay s = w.spawn(stoelPlek(i, q), ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setTeleportDuration(INTERPOLATIE);
                tag(d);
            });
            stoelen.add(s);
        }
        klik = w.spawn(klikPlek(), Interaction.class, it -> {
            it.setPersistent(false);
            it.setInteractionWidth((float) Math.max(0.8, Math.min(4.0, (m.breedte + m.lengte) / 2.0)));
            it.setInteractionHeight((float) Math.max(0.8, Math.min(3.0, m.hoogte)));
            it.setResponsive(true);
            tag(it);
        });
        gezYaw = yaw;
        gezPitch = pitch;
        gezRoll = roll;
        gezX = x;
        gezY = y;
        gezZ = z;
    }

    private void tag(Entity e) {
        e.getPersistentDataContainer().set(p.sleutelVoertuig, PersistentDataType.STRING, v.id.toString());
    }

    /** Alles weghalen. Spelers stappen uit. */
    void weghalen() {
        if (weg) return;
        weg = true;
        bezigMetWeghalen = true;
        List<Entity> alles = new ArrayList<>();
        if (wortel != null) alles.add(wortel);
        alles.addAll(delen);
        alles.addAll(stoelen);
        if (klik != null) alles.add(klik);
        for (Entity e : alles) {
            if (e == null) continue;
            for (Entity pass : new ArrayList<>(e.getPassengers())) {
                if (pass instanceof Player pl) {
                    e.removePassenger(pl);
                    Location uit = uitstapPlek();
                    if (uit != null) pl.teleport(uit);
                }
            }
            e.remove();
        }
        bezigMetWeghalen = false;
    }

    boolean geldig() {
        if (weg || wortel == null || !wortel.isValid() || klik == null || !klik.isValid()) return false;
        for (BlockDisplay b : delen) if (!b.isValid()) return false;
        for (ItemDisplay s : stoelen) if (!s.isValid()) return false;
        return true;
    }

    // ------------------------------------------------------------------ inzittenden

    Player bestuurder() {
        if (wortel == null) return null;
        for (Entity e : wortel.getPassengers()) if (e instanceof Player pl) return pl;
        return null;
    }

    List<Player> inzittenden() {
        List<Player> l = new ArrayList<>();
        Player b = bestuurder();
        if (b != null) l.add(b);
        for (ItemDisplay s : stoelen)
            for (Entity e : s.getPassengers()) if (e instanceof Player pl) l.add(pl);
        return l;
    }

    boolean isStoel(Entity e) {
        return e == wortel || stoelen.contains(e);
    }

    boolean eigenaarZit() {
        for (Player pl : inzittenden()) if (pl.getUniqueId().equals(v.eigenaar)) return true;
        return false;
    }

    /** Instappen: bestuurder of passagier. Geeft een melding terug als het niet kan, anders null. */
    String stapIn(Player pl) {
        if (pl.isInsideVehicle()) return "Je zit al ergens in.";
        boolean eigenaar = pl.getUniqueId().equals(v.eigenaar) || pl.hasPermission("ritly.omzeil");
        boolean magRijden = eigenaar || !v.opSlot;
        if (bestuurder() == null && magRijden) {
            wortel.addPassenger(pl);
            wakker();
            v.gebruikt = System.currentTimeMillis();
            p.garage().wijzig();
            pl.sendActionBar(Tekst.kleur(uitleg()));
            return null;
        }
        if (v.opSlot && !eigenaar && !eigenaarZit()) return "Dit voertuig zit op slot.";
        for (ItemDisplay s : stoelen) {
            if (s.getPassengers().isEmpty()) {
                s.addPassenger(pl);
                pl.sendActionBar(Tekst.kleur("&7Shift = uitstappen"));
                return null;
            }
        }
        return bestuurder() != null ? "Er is geen plek meer vrij." : "Dit voertuig zit op slot.";
    }

    private String uitleg() {
        return switch (m.soort) {
            case VLIEGTUIG -> "&eW/S&7 gas  &eA/D&7 draaien  &eKijken&7 omhoog/omlaag  &eSpatie&7 rem  &eShift&7 uitstappen";
            case BOOT -> "&eW/S&7 vooruit/achteruit  &eA/D&7 sturen  &eShift&7 uitstappen  &eLinksklik&7 toeter";
            case FIETS -> "&eW&7 trappen  &eSprint&7 harder  &eA/D&7 sturen  &eShift&7 afstappen  &eLinksklik&7 bel";
            default -> "&eW/S&7 gas/rem  &eA/D&7 sturen  &eSpatie&7 handrem  &eShift&7 uitstappen  &eLinksklik&7 toeter";
        };
    }

    /** Een veilige plek naast het voertuig om uit te stappen. */
    Location uitstapPlek() {
        float yr = (float) Math.toRadians(yaw);
        double fx = -Math.sin(yr), fz = Math.cos(yr);   // vooruit
        double lx = fz, lz = -fx;                        // links
        double zij = m.breedte / 2 + 0.7;
        double[][] opties = {{lx * zij, lz * zij}, {-lx * zij, -lz * zij},
                {-fx * (m.lengte / 2 + 0.8), -fz * (m.lengte / 2 + 0.8)}, {fx * (m.lengte / 2 + 0.8), fz * (m.lengte / 2 + 0.8)}};
        for (double[] o : opties) {
            for (double dy : new double[]{0, 1, -1, 2}) {
                double px = x + o[0], py = Math.floor(y) + dy, pz = z + o[1];
                BoundingBox b = new BoundingBox(px - 0.3, py + 0.01, pz - 0.3, px + 0.3, py + 1.8, pz + 0.3);
                BoundingBox onder = new BoundingBox(px - 0.3, py - 0.6, pz - 0.3, px + 0.3, py - 0.01, pz + 0.3);
                if (!w.isChunkLoaded((int) Math.floor(px) >> 4, (int) Math.floor(pz) >> 4)) continue;
                if (!w.hasCollisionsIn(b) && (w.hasCollisionsIn(onder) || isWater(w.getBlockAt((int) Math.floor(px), (int) Math.floor(py) - 1, (int) Math.floor(pz)))))
                    return new Location(w, px, py, pz, yaw, 0);
            }
        }
        return new Location(w, x, y + m.hoogte + 0.1, z, yaw, 0);
    }

    // ------------------------------------------------------------------ plekken en draaiing

    Quaternionf draaiing() {
        return new Quaternionf().rotationY((float) -Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll));
    }

    private Location wortelPlek() {
        Vector3f o = draaiing().transform(new Vector3f(m.zitten.get(0)));
        return new Location(w, x + o.x, y + o.y, z + o.z, 0, 0);
    }

    private Location stoelPlek(int i, Quaternionf q) {
        Vector3f o = q.transform(new Vector3f(m.zitten.get(i)));
        return new Location(w, x + o.x, y + o.y, z + o.z, 0, 0);
    }

    private Location klikPlek() {
        double yr = Math.toRadians(yaw);
        return new Location(w, x - Math.sin(yr) * m.middenZ, y, z + Math.cos(yr) * m.middenZ);
    }

    Location midden() {
        return new Location(w, x, y, z, yaw, 0);
    }

    private Transformation transformatie(Model.Deel d, Quaternionf q, float spin) {
        Vector3f o = m.zitten.get(0);
        Quaternionf l = new Quaternionf(q);
        if (d.vast != null) l.mul(d.vast);
        if (d.draai == 'x') l.rotateX(spin);
        else if (d.draai == 'z') l.rotateZ(spin);
        Vector3f c = new Vector3f(d.van).add(d.maat.x / 2f, d.maat.y / 2f, d.maat.z / 2f).sub(o);
        q.transform(c);
        Vector3f half = l.transform(new Vector3f(d.maat).mul(0.5f));
        return new Transformation(c.sub(half), l, new Vector3f(d.maat), new Quaternionf());
    }

    /** Nieuwe lakkleur zichtbaar maken. */
    void herkleur() {
        for (int i = 0; i < delen.size() && i < m.delen.size(); i++) {
            Model.Deel d = m.delen.get(i);
            if (d.lak) delen.get(i).setBlock(d.data(v.kleur));
        }
    }

    // ------------------------------------------------------------------ tick

    void wakker() {
        slaapt = false;
        stil = 0;
    }

    void tick() {
        tik++;
        Player b = bestuurder();
        Input in = b == null ? autopiloot : b.getCurrentInput();
        double oudX = x, oudZ = z, oudY = y;
        switch (m.soort) {
            case VLIEGTUIG -> vliegen(b, in);
            case BOOT -> varen(b, in);
            default -> rijden(b, in);
        }
        double afstand = Math.sqrt((x - oudX) * (x - oudX) + (z - oudZ) * (z - oudZ) + (y - oudY) * (y - oudY));
        if (v.heeftBrandstofNodig(m, p) && afstand > 0) {
            double factor = m.soort == Soort.VLIEGTUIG ? 0.3 + 0.7 * gas : (in != null && (in.isForward() || in.isBackward()) ? 1 : 0.2);
            double d = afstand * m.verbruik / 1000.0 * factor;
            v.brandstof = Math.max(0, v.brandstof - d);
            verbruikt += d;
            if (verbruikt > 0.5) {
                verbruikt = 0;
                p.garage().wijzig();
            }
        }
        // wielen en propeller
        boolean draaitIets = false;
        for (int i = 0; i < m.delen.size(); i++) {
            Model.Deel d = m.delen.get(i);
            if (d.draai == 'x' && Math.abs(snelheid) > 0.001 && (opGrond || m.soort != Soort.VLIEGTUIG)) {
                float straal = Math.max(0.1f, d.maat.y / 2f);
                hoek[i] += (float) (snelheid / straal);
                draaitIets = true;
            } else if (d.draai == 'z' && (gas > 0.01 || (m.soort != Soort.VLIEGTUIG && Math.abs(snelheid) > 0.01))) {
                hoek[i] += (float) (0.35 + gas * 0.6);
                draaitIets = true;
            }
        }
        werkEntitiesBij(draaitIets);
        if (b != null && tik % 5 == 0) toonDashboard(b);
        // slapen als hij stilstaat zonder bestuurder
        if (b == null && Math.abs(snelheid) < 0.002 && Math.abs(vy) < 0.01 && Math.abs(valV) < 0.01 && (opGrond || opWater)) {
            if (++stil > 20) {
                slaapt = true;
                snelheid = 0;
                gas = 0;
                v.parkeer(w.getName(), x, y, z, yaw);
                p.garage().wijzig();
            }
        } else stil = 0;
        if (tik % 100 == 0) v.parkeer(w.getName(), x, y, z, yaw);
        if (meldingTicks > 0) meldingTicks--;
    }

    private void werkEntitiesBij(boolean draaitIets) {
        boolean gedraaid = Float.isNaN(gezYaw) || Math.abs(yaw - gezYaw) > 0.05 || Math.abs(pitch - gezPitch) > 0.05 || Math.abs(roll - gezRoll) > 0.05;
        boolean bewogen = Double.isNaN(gezX) || Math.abs(x - gezX) > 1e-4 || Math.abs(y - gezY) > 1e-4 || Math.abs(z - gezZ) > 1e-4;
        if (!gedraaid && !bewogen && !draaitIets) return;
        Quaternionf q = draaiing();
        if (gedraaid || bewogen) {
            wortel.teleport(wortelPlek(), TeleportFlag.EntityState.RETAIN_PASSENGERS);
            for (int i = 0; i < stoelen.size(); i++)
                stoelen.get(i).teleport(stoelPlek(i + 1, q), TeleportFlag.EntityState.RETAIN_PASSENGERS);
            klik.teleport(klikPlek());
        }
        for (int i = 0; i < delen.size(); i++) {
            Model.Deel d = m.delen.get(i);
            if (!gedraaid && d.draai == 0) continue;
            BlockDisplay bd = delen.get(i);
            bd.setInterpolationDelay(0);
            bd.setInterpolationDuration(INTERPOLATIE);
            bd.setTransformation(transformatie(d, q, hoek[i]));
        }
        gezYaw = yaw;
        gezPitch = pitch;
        gezRoll = roll;
        gezX = x;
        gezY = y;
        gezZ = z;
    }

    private void toonDashboard(Player b) {
        if (meldingTicks > 0 && melding != null) {
            b.sendActionBar(Tekst.kleur(melding));
            return;
        }
        int kmu = (int) Math.round(Math.abs(snelheid) * 72);
        StringBuilder sb = new StringBuilder("&f&l").append(kmu).append(" &7km/u");
        if (snelheid < -0.005) sb.append(" &c(achteruit)");
        if (m.soort == Soort.VLIEGTUIG) {
            sb.append("  &7Gas &e").append((int) Math.round(gas * 100)).append('%');
            sb.append("  &7Hoogte &b").append((int) Math.round(y)).append(" m");
            if (!opGrond && Math.abs(snelheid) * 72 < m.opstijgen) sb.append("  &c&lTE LANGZAAM");
        }
        if (v.heeftBrandstofNodig(m, p)) {
            double max = v.tankInhoud(m, p);
            double pct = max <= 0 ? 0 : v.brandstof / max;
            String kl = pct > 0.5 ? "&a" : pct > 0.2 ? "&e" : "&c";
            sb.append("  &7Brandstof ").append(kl).append((int) Math.round(v.brandstof)).append("&7/").append((int) Math.round(max)).append(" L");
            if (v.brandstof <= 0) sb.append(" &c&lLEEG");
        }
        b.sendActionBar(Tekst.kleur(sb.toString()));
    }

    void melding(String s) {
        melding = s;
        meldingTicks = 40;
        Player b = bestuurder();
        if (b != null) b.sendActionBar(Tekst.kleur(s));
    }

    private boolean leeg() {
        return v.heeftBrandstofNodig(m, p) && v.brandstof <= 0;
    }

    // ------------------------------------------------------------------ rijden (auto en fiets)

    private void rijden(Player b, Input in) {
        double max = v.maxKmu(m, p) / 72.0;
        double acc = v.acceleratie(m, p) / 72.0 / 20.0;
        double dr = v.draaien(m, p) / 20.0;
        boolean vooruit = in != null && in.isForward(), achteruit = in != null && in.isBackward();
        boolean links = in != null && in.isLeft(), rechts = in != null && in.isRight(), rem = in != null && in.isJump();
        Block voeten = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y + 0.3), (int) Math.floor(z));
        boolean inWater = isWater(voeten);
        if (inWater) max *= 0.3;
        if (m.soort == Soort.FIETS && in != null && in.isSprint()) max *= 1.3;
        boolean motorAan = !leeg();
        if (!motorAan && (vooruit || achteruit) && tik % 40 == 0) melding("&c&lDe tank is leeg! &7Tank bij met een jerrycan.");
        if (opGrond) {
            if (vooruit && motorAan) {
                snelheid = snelheid < 0 ? snelheid + acc * 3 : Math.min(max, snelheid + acc);
            } else if (achteruit && motorAan) {
                if (snelheid > 0.01) snelheid = Math.max(0, snelheid - acc * 3);
                else snelheid = Math.max(-max * 0.35, snelheid - acc * 0.7);
            } else {
                snelheid *= m.soort == Soort.FIETS ? 0.95 : 0.97;
            }
            if (rem) snelheid *= 0.8;
            if (snelheid > max) snelheid = Math.max(max, snelheid * 0.95);
        } else {
            snelheid *= 0.995;
        }
        if (Math.abs(snelheid) < 0.003 && !vooruit && !achteruit) snelheid = 0;

        // sturen
        double f = Math.min(1.0, Math.abs(snelheid) / Math.max(0.02, 0.15 * max));
        double stuur = (rechts ? 1 : 0) - (links ? 1 : 0);
        if (stuur == 0 && b != null && p.kijkSturen() && vooruit && snelheid > 0.02) {
            double verschil = wrap(b.getYaw() - yaw);
            stuur = clamp(verschil / (dr * 4), -1, 1);
        }
        if (stuur != 0 && f > 0) {
            float nieuw = (float) (yaw + stuur * dr * f * Math.signum(snelheid));
            if (!botst(x, y, z, nieuw)) yaw = norm(nieuw);
        }
        bewegen(1.0, true);
        roll = 0;
        pitch = 0;
    }

    // ------------------------------------------------------------------ varen

    private void varen(Player b, Input in) {
        double max = v.maxKmu(m, p) / 72.0;
        double acc = v.acceleratie(m, p) / 72.0 / 20.0;
        double dr = v.draaien(m, p) / 20.0;
        boolean vooruit = in != null && in.isForward(), achteruit = in != null && in.isBackward();
        boolean links = in != null && in.isLeft(), rechts = in != null && in.isRight();
        double opp = wateroppervlak();
        opWater = !Double.isNaN(opp) && opp - y > -0.8 && opp - y < 1.6;
        boolean motorAan = !leeg();
        if (!motorAan && (vooruit || achteruit) && tik % 40 == 0) melding("&c&lDe tank is leeg! &7Tank bij met een jerrycan.");
        double maxNu = opWater ? max : max * 0.04;
        if (vooruit && motorAan) snelheid = snelheid < 0 ? snelheid + acc * 2 : Math.min(maxNu, snelheid + acc);
        else if (achteruit && motorAan) {
            if (snelheid > 0.01) snelheid = Math.max(0, snelheid - acc * 2);
            else snelheid = Math.max(-maxNu * 0.4, snelheid - acc * 0.6);
        } else snelheid *= opWater ? 0.98 : 0.8;
        if (snelheid > maxNu) snelheid = Math.max(maxNu, snelheid * 0.9);
        if (Math.abs(snelheid) < 0.002 && !vooruit && !achteruit) snelheid = 0;

        double f = Math.min(1.0, Math.abs(snelheid) / Math.max(0.02, 0.2 * max) + 0.25);
        if (Math.abs(snelheid) < 0.005) f = (vooruit || achteruit) ? 0.3 : 0;
        double stuur = (rechts ? 1 : 0) - (links ? 1 : 0);
        if (stuur == 0 && b != null && p.kijkSturen() && vooruit && snelheid > 0.02) {
            stuur = clamp(wrap(b.getYaw() - yaw) / (dr * 4), -1, 1);
        }
        if (stuur != 0 && f > 0) {
            float nieuw = (float) (yaw + stuur * dr * f * (snelheid < 0 ? -1 : 1));
            if (!botst(x, y, z, nieuw)) yaw = norm(nieuw);
        }
        if (opWater) {
            double doel = opp - m.diepgang;
            double dy = (doel - y) * 0.25;
            if (!botst(x, y + dy, z, yaw)) y += dy;
            vy = 0;
            opGrond = false;
            // horizontaal bewegen zonder op te stappen
            horizontaal(0.0);
            pitch = (float) (-Math.min(1, Math.abs(snelheid) / Math.max(0.01, max)) * 4);
            roll = (float) (Math.sin(tik * 0.12) * 1.2 - clamp(stuur, -1, 1) * Math.min(1, Math.abs(snelheid) / Math.max(0.01, max)) * 6);
            if (Math.abs(snelheid) > 0.3 && tik % 3 == 0) {
                double yr = Math.toRadians(yaw);
                w.spawnParticle(Particle.SPLASH, x + Math.sin(yr) * m.lengte / 2, opp, z - Math.cos(yr) * m.lengte / 2, 4, 0.3, 0.05, 0.3, 0);
            }
        } else {
            bewegen(0.0, false);
            pitch = 0;
            roll = 0;
        }
    }

    // ------------------------------------------------------------------ vliegen

    private void vliegen(Player b, Input in) {
        double max = v.maxKmu(m, p) / 72.0;
        double acc = v.acceleratie(m, p) / 72.0 / 20.0;
        double dr = v.draaien(m, p) / 20.0;
        double opstijg = Math.max(0.05, m.opstijgen / 72.0);
        boolean vooruit = in != null && in.isForward(), achteruit = in != null && in.isBackward();
        boolean links = in != null && in.isLeft(), rechts = in != null && in.isRight(), rem = in != null && in.isJump();
        if (vooruit) gas = Math.min(1, gas + 0.015);
        if (achteruit) gas = Math.max(0, gas - 0.025);
        if (b == null) gas = Math.max(0, gas - 0.01);
        if (leeg()) {
            if (gas > 0 && tik % 40 == 0) melding("&c&lDe tank is leeg! &7De motor is uit.");
            gas = 0;
        }
        double doelSnelheid = gas * max;
        if (snelheid < doelSnelheid) snelheid = Math.min(doelSnelheid, snelheid + acc);
        else snelheid = Math.max(doelSnelheid, snelheid - acc * (opGrond ? 1.0 : 0.35));
        if (opGrond && (rem || (achteruit && gas <= 0))) snelheid = Math.max(0, snelheid - acc * 4);
        if (snelheid < 0.002 && gas <= 0) snelheid = 0;
        double lift = Math.min(1, snelheid / opstijg);

        // draaien
        double stuur = (rechts ? 1 : 0) - (links ? 1 : 0);
        if (stuur == 0 && b != null && p.kijkSturen() && !opGrond) stuur = clamp(wrap(b.getYaw() - yaw) / (dr * 4), -1, 1);
        double f = opGrond ? Math.min(1, snelheid / 0.1) * 0.7 : 1;
        if (stuur != 0 && f > 0) {
            float nieuw = (float) (yaw + stuur * dr * f);
            if (!botst(x, y, z, nieuw)) yaw = norm(nieuw);
        }
        float rolDoel = opGrond ? 0 : (float) (stuur * 30);
        roll += (rolDoel - roll) * 0.15f;

        // neus omhoog/omlaag
        float pitchDoel;
        if (b != null) pitchDoel = (float) clamp(b.getPitch(), -35, 35);
        else pitchDoel = autopiloot != null ? -15 : 12;
        if (opGrond) pitchDoel = lift >= 1 ? Math.min(0, pitchDoel) : 0;
        if (!opGrond && lift < 1) pitchDoel = (float) (pitchDoel * lift + 20 * (1 - lift));
        pitch += (float) clamp(pitchDoel - pitch, -2, 2);

        double pr = Math.toRadians(pitch);
        double hor = Math.cos(pr) * snelheid;
        double ver = -Math.sin(pr) * snelheid;
        if (ver > 0) ver *= lift;
        if (!opGrond) {
            valV = (valV - ZWAARTEKRACHT * 0.5 * (1 - lift)) * 0.98;
            if (lift >= 1) valV *= 0.85;
        } else valV = 0;
        double dy = ver + valV;
        if (opGrond && dy <= 0) dy = -ZWAARTEKRACHT;
        if (y > w.getMaxHeight() - 4 && dy > 0) dy = 0;

        // horizontaal
        double yr = Math.toRadians(yaw);
        double dx = -Math.sin(yr) * hor, dz = Math.cos(yr) * hor;
        int stappen = Math.max(1, (int) Math.ceil(Math.abs(hor) / 0.4));
        for (int i = 0; i < stappen; i++) {
            int r = schuif(dx / stappen, dz / stappen, opGrond ? 0.6 : 0);
            if (r == 2) {
                if (ongeladen) {
                    // de wereld is daar nog niet geladen: even wachten in plaats van crashen
                    melding("&eDe wereld laadt nog...");
                } else if (!opGrond && snelheid > 0.4) crash();
                else snelheid *= 0.3;
                break;
            }
        }
        // verticaal
        boolean wasInLucht = !opGrond;
        boolean geraakt = verticaal(dy);
        if (geraakt && dy < 0) {
            if (wasInLucht && dy < -0.55) crash();
            opGrond = true;
            valV = 0;
            if (pitch > 0) pitch = Math.max(0, pitch - 3);
        } else if (!geraakt) {
            opGrond = botst(x, y - 0.04, z, yaw);
        }
        if (opGrond && pitch > 0) pitch = Math.max(0, pitch - 3);
        if (!opGrond && snelheid > 0.8 && tik % 2 == 0) {
            w.spawnParticle(Particle.CLOUD, x + Math.sin(yr) * m.lengte / 2, y + m.hoogte * 0.4, z - Math.cos(yr) * m.lengte / 2, 1, 0.05, 0.05, 0.05, 0);
        }
    }

    private void crash() {
        snelheid *= 0.1;
        gas = 0;
        valV = 0;
        Location l = midden();
        w.spawnParticle(Particle.EXPLOSION, l.add(0, 1, 0), 2, 0.4, 0.4, 0.4, 0);
        w.playSound(l, "entity.generic.explode", 0.7f, 1.2f);
        melding("&c&lKRAK! &7Je bent gecrasht.");
    }

    // ------------------------------------------------------------------ beweging en botsing

    /** Rijden over de grond: horizontaal met opstappen, daarna zwaartekracht. */
    private void bewegen(double stapHoogte, boolean opstappen) {
        horizontaal(opstappen ? stapHoogte : 0);
        if (opGrond && botst(x, y - 0.03, z, yaw) && vy <= 0) {
            vy = 0;
            return;
        }
        vy = Math.max(-3, (vy - ZWAARTEKRACHT) * 0.98);
        boolean geraakt = verticaal(vy);
        if (geraakt) {
            if (vy < -0.9 && Math.abs(snelheid) > 0.05) w.playSound(midden(), "entity.generic.big_fall", 0.6f, 1f);
            if (vy < 0) opGrond = true;
            vy = 0;
        } else {
            opGrond = botst(x, y - 0.03, z, yaw);
        }
    }

    private void horizontaal(double stap) {
        if (Math.abs(snelheid) < 1e-5) return;
        double yr = Math.toRadians(yaw);
        double dx = -Math.sin(yr) * snelheid, dz = Math.cos(yr) * snelheid;
        int stappen = Math.max(1, (int) Math.ceil(Math.abs(snelheid) / 0.4));
        for (int i = 0; i < stappen; i++) {
            int r = schuif(dx / stappen, dz / stappen, opGrond || opWater ? stap : 0);
            if (r == 2) {
                if (Math.abs(snelheid) > 0.45 && !ongeladen) {
                    w.playSound(midden(), "entity.iron_golem.hurt", 0.7f, 0.6f);
                    melding("&c&lBOEM! &7Je bent ergens tegenaan gereden.");
                }
                snelheid = 0;
                break;
            } else if (r == 1) snelheid *= 0.92;
        }
    }

    /** 0 = vrij bewogen, 1 = langs iets geschoven, 2 = vast. */
    private int schuif(double dx, double dz, double stap) {
        if (!botst(x + dx, y, z + dz, yaw)) {
            x += dx;
            z += dz;
            return 0;
        }
        if (stap > 0) {
            for (double s = 0.25; s <= stap + 1e-6; s += 0.25) {
                if (!botst(x + dx, y + s, z + dz, yaw) && !botst(x, y + s, z, yaw)) {
                    x += dx;
                    z += dz;
                    y += s;
                    return 0;
                }
            }
        }
        if (Math.abs(dx) > 1e-4 && !botst(x + dx, y, z, yaw)) {
            x += dx;
            return 1;
        }
        if (Math.abs(dz) > 1e-4 && !botst(x, y, z + dz, yaw)) {
            z += dz;
            return 1;
        }
        return 2;
    }

    /** Verticaal bewegen. True als hij iets raakte (en dan zo ver mogelijk is gezakt/gestegen). */
    private boolean verticaal(double dy) {
        if (Math.abs(dy) < 1e-6) return false;
        if (y + dy < w.getMinHeight() - 10) {
            y = w.getMinHeight() - 10;
            return true;
        }
        if (!botst(x, y + dy, z, yaw)) {
            y += dy;
            return false;
        }
        double lo = 0, hi = dy;
        for (int i = 0; i < 7; i++) {
            double mid = (lo + hi) / 2;
            if (botst(x, y + mid, z, yaw)) hi = mid;
            else lo = mid;
        }
        y += lo;
        return true;
    }

    /** Botst het voertuig op deze plek met blokken? Een rij vierkante kisten over de lengte. */
    private boolean ongeladen;

    boolean botst(double cx, double cy, double cz, float yw) {
        ongeladen = false;
        double breed = Math.max(0.4, m.breedte);
        double half = breed / 2 * 0.9;
        double hoog = Math.min(m.hoogte, 2.6);
        double len = Math.max(breed, m.lengte);
        int n = len <= breed + 1e-3 ? 1 : (int) Math.ceil((len - breed) / (breed * 0.75)) + 1;
        double yr = Math.toRadians(yw);
        double fx = -Math.sin(yr), fz = Math.cos(yr);
        double bodem = cy + (m.soort == Soort.BOOT ? 0.05 : 0.01);
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : -(len - breed) / 2 + (len - breed) * i / (n - 1);
            double zz = m.middenZ + t;
            double px = cx + fx * zz, pz = cz + fz * zz;
            if (!w.isChunkLoaded((int) Math.floor(px) >> 4, (int) Math.floor(pz) >> 4)) {
                ongeladen = true;
                return true;
            }
            if (w.hasCollisionsIn(new BoundingBox(px - half, bodem, pz - half, px + half, bodem + hoog, pz + half))) return true;
        }
        return false;
    }

    /** Hoogte van het wateroppervlak onder/bij de boot, of NaN als er geen water is. */
    private double wateroppervlak() {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int start = (int) Math.floor(y + m.diepgang + 1);
        for (int by = start; by >= start - 3; by--) {
            Block bl = w.getBlockAt(bx, by, bz);
            if (isWater(bl) && !isWater(bl.getRelative(0, 1, 0))) return by + 0.9;
        }
        return Double.NaN;
    }

    static boolean isWater(Block b) {
        Material t = b.getType();
        if (t == Material.WATER || t == Material.BUBBLE_COLUMN || t == Material.KELP || t == Material.KELP_PLANT
                || t == Material.SEAGRASS || t == Material.TALL_SEAGRASS) return true;
        return b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged();
    }

    /** Valscherm als je uit een vliegend vliegtuig stapt. */
    void uitgestapt(Player pl) {
        if (m.soort == Soort.VLIEGTUIG && !opGrond) {
            int sec = p.getConfig().getInt("valscherm-seconden", 15);
            if (sec > 0) pl.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, sec * 20, 0, false, false, true));
        }
        wakker();
    }

    void toeter() {
        long nu = System.currentTimeMillis();
        if (nu - laatsteToeter < 900) return;
        laatsteToeter = nu;
        w.playSound(midden(), m.soort.toeter, 1.5f, m.soort.toonhoogte);
    }

    // ------------------------------------------------------------------ hulp

    static double wrap(double d) {
        d %= 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    static float norm(float f) {
        f %= 360f;
        if (f < 0) f += 360f;
        return f;
    }

    static double clamp(double d, double lo, double hi) {
        return Math.max(lo, Math.min(hi, d));
    }

    /** Past dit voertuig hier (voor het neerzetten)? */
    boolean pastHier() {
        return !botst(x, y, z, yaw);
    }
}
