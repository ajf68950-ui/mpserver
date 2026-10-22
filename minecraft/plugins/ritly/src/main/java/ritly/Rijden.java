package ritly;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Houdt alle voertuigen in de wereld bij: elke tick bewegen, instappen, uitstappen, laden en weghalen. */
final class Rijden implements Listener {

    private final Ritly p;
    private final Map<UUID, ActiefVoertuig> actief = new HashMap<>();
    private int tik;

    Rijden(Ritly p) {
        this.p = p;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(p, this::tick, 1L, 1L);
    }

    Collection<ActiefVoertuig> actief() {
        return actief.values();
    }

    ActiefVoertuig actief(UUID voertuig) {
        return actief.get(voertuig);
    }

    ActiefVoertuig vanEntity(Entity e) {
        if (e == null) return null;
        String s = e.getPersistentDataContainer().get(p.sleutelVoertuig, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return actief.get(UUID.fromString(s));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        tik++;
        Iterator<ActiefVoertuig> it = actief.values().iterator();
        while (it.hasNext()) {
            ActiefVoertuig a = it.next();
            if (!a.geldig()) {
                a.weghalen();
                it.remove();
                continue;
            }
            if (a.slaapt) continue;
            try {
                a.tick();
            } catch (RuntimeException ex) {
                p.getLogger().warning("Fout bij voertuig " + a.v.id + ": " + ex);
                a.snelheid = 0;
                a.slaapt = true;
            }
        }
        if (tik % 40 == 0) laadGeparkeerde();
        if (tik % 1200 == 0) p.garage().opslaanAlsGewijzigd();
        if (tik % 6000 == 0) terugNaarGarage();
    }

    /** Geparkeerde voertuigen neerzetten als hun chunk geladen is. */
    void laadGeparkeerde() {
        for (Voertuig v : new ArrayList<>(p.garage().alle())) {
            if (v.inGarage() || actief.containsKey(v.id)) continue;
            World w = Bukkit.getWorld(v.wereld);
            if (w == null) continue;
            if (!w.isChunkLoaded((int) Math.floor(v.x) >> 4, (int) Math.floor(v.z) >> 4)) continue;
            Model m = p.modellen().get(v.model);
            if (m == null) continue;
            ActiefVoertuig a = new ActiefVoertuig(p, v, m, new Location(w, v.x, v.y, v.z, v.yaw, 0));
            a.spawn();
            actief.put(v.id, a);
        }
    }

    private void terugNaarGarage() {
        int dagen = p.getConfig().getInt("terug-naar-garage-na-dagen", 7);
        if (dagen <= 0) return;
        long grens = System.currentTimeMillis() - dagen * 86_400_000L;
        for (Voertuig v : p.garage().alle()) {
            if (v.inGarage() || v.gebruikt > grens) continue;
            ActiefVoertuig a = actief.get(v.id);
            if (a != null && !a.inzittenden().isEmpty()) continue;
            opbergen(v);
        }
    }

    // ------------------------------------------------------------------ uitrijden en opbergen

    /** Zet een voertuig bij de speler neer. Geeft een foutmelding of null. */
    String uitrijden(Player pl, Voertuig v) {
        Model m = p.modellen().get(v.model);
        if (m == null) return "Het model van dit voertuig bestaat niet meer.";
        if (p.geblokkeerd(pl.getWorld())) return "In deze wereld kun je geen voertuig neerzetten.";
        ActiefVoertuig oud = actief.get(v.id);
        if (oud != null && !oud.inzittenden().isEmpty()) return "Er zit iemand in dit voertuig.";
        int buiten = 0;
        for (Voertuig o : p.garage().van(v.eigenaar)) if (!o.inGarage() && !o.id.equals(v.id)) buiten++;
        int max = p.getConfig().getInt("max-tegelijk", 2);
        if (buiten >= max) return "Je hebt al " + buiten + " voertuig(en) buiten staan (max " + max + "). Berg er eerst een op.";
        Location plek = zoekPlek(pl, m, v);
        if (plek == null) {
            return m.soort == Soort.BOOT ? "Kijk naar water om je boot neer te zetten." : "Hier is geen ruimte. Kijk naar een vrije plek op de grond.";
        }
        if (oud != null) {
            oud.weghalen();
            actief.remove(v.id);
        }
        ActiefVoertuig a = new ActiefVoertuig(p, v, m, plek);
        if (v.brandstof <= 0 && !p.brandstofAan()) v.brandstof = v.tankInhoud(m, p);
        a.spawn();
        actief.put(v.id, a);
        v.parkeer(plek.getWorld().getName(), plek.getX(), plek.getY(), plek.getZ(), plek.getYaw());
        v.gebruikt = System.currentTimeMillis();
        p.garage().wijzig();
        pl.getWorld().playSound(plek, "block.piston.extend", 0.8f, 1.3f);
        return null;
    }

    private Location zoekPlek(Player pl, Model m, Voertuig v) {
        float yaw = ActiefVoertuig.norm(pl.getYaw());
        List<Location> opties = new ArrayList<>();
        Block doel = pl.getTargetBlockExact(10, FluidCollisionMode.ALWAYS);
        if (m.soort == Soort.BOOT) {
            if (doel == null || !ActiefVoertuig.isWater(doel)) return null;
            Block top = doel;
            int n = 0;
            while (ActiefVoertuig.isWater(top.getRelative(0, 1, 0)) && n++ < 30) top = top.getRelative(0, 1, 0);
            opties.add(new Location(pl.getWorld(), top.getX() + 0.5, top.getY() + 0.9 - m.diepgang, top.getZ() + 0.5, yaw, 0));
        } else {
            if (doel != null && !ActiefVoertuig.isWater(doel) && !doel.isPassable()) {
                double top = doel.getBoundingBox().getMaxY();
                opties.add(new Location(pl.getWorld(), doel.getX() + 0.5, top, doel.getZ() + 0.5, yaw, 0));
            }
            Vector dir = pl.getLocation().getDirection().setY(0);
            if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
            dir.normalize().multiply(m.lengte / 2 + 1.5);
            Location voor = pl.getLocation().add(dir);
            voor.setYaw(yaw);
            voor.setPitch(0);
            opties.add(voor);
        }
        for (Location l : opties) {
            if (l.distanceSquared(pl.getLocation()) > 14 * 14) continue;
            for (double dy : new double[]{0, 0.5, 1.0, 1.5}) {
                Location t = l.clone().add(0, dy, 0);
                ActiefVoertuig test = new ActiefVoertuig(p, v, m, t);
                if (test.pastHier()) return t;
            }
        }
        return null;
    }

    /** Uit de wereld halen en in de garage zetten. */
    void opbergen(Voertuig v) {
        ActiefVoertuig a = actief.remove(v.id);
        if (a != null) a.weghalen();
        v.naarGarage();
        p.garage().wijzig();
    }

    /** Alles weghalen (bij uitzetten van de server); de plek blijft bewaard. */
    void allesWeg() {
        for (ActiefVoertuig a : actief.values()) {
            if (a.geldig()) a.v.parkeer(a.w.getName(), a.x, a.y, a.z, a.yaw);
            a.weghalen();
        }
        actief.clear();
    }

    /** Een voertuig (bijv. na het inbouwen van een onderdeel of het wisselen van model) opnieuw neerzetten. */
    void herteken(Voertuig v) {
        ActiefVoertuig a = actief.get(v.id);
        if (a == null) return;
        a.herkleur();
    }

    // ------------------------------------------------------------------ tanken en lakken

    void tanken(Player pl, Voertuig v, ItemStack jerrycan) {
        Model m = p.modellen().get(v.model);
        Onderdelen.Item it = p.onderdelen().lees(jerrycan);
        if (m == null || it == null || !it.soort().equals("brandstof")) return;
        if (!m.soort.brandstof || m.tank <= 0) {
            Tekst.stuur(pl, "Dit voertuig heeft geen brandstof nodig.");
            return;
        }
        if (!p.brandstofAan()) {
            Tekst.stuur(pl, "Brandstof staat uit op deze server; je tank is altijd vol.");
            return;
        }
        double max = v.tankInhoud(m, p);
        if (v.brandstof >= max - 0.05) {
            Tekst.stuur(pl, "De tank is al vol.");
            return;
        }
        v.brandstof = Math.min(max, v.brandstof + it.niveau());
        jerrycan.setAmount(jerrycan.getAmount() - 1);
        p.garage().wijzig();
        pl.playSound(pl.getLocation(), "item.bucket.empty_lava", 1f, 1.2f);
        Tekst.stuur(pl, "Bijgetankt: &f" + Math.round(v.brandstof) + "&7/" + Math.round(max) + " liter.");
    }

    void lakken(Player pl, Voertuig v, DyeColor k, ItemStack lak) {
        if (k == null) return;
        v.kleur = k;
        lak.setAmount(lak.getAmount() - 1);
        p.garage().wijzig();
        herteken(v);
        pl.playSound(pl.getLocation(), "entity.villager.work_cartographer", 1f, 1f);
        Tekst.stuur(pl, "Je voertuig is nu &f" + Tekst.kleurNaam(k) + "&7.");
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void klik(PlayerInteractEntityEvent e) {
        ActiefVoertuig a = vanEntity(e.getRightClicked());
        if (a == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player pl = e.getPlayer();
        boolean eigenaar = pl.getUniqueId().equals(a.v.eigenaar) || pl.hasPermission("ritly.omzeil");
        ItemStack hand = pl.getInventory().getItemInMainHand();
        Onderdelen.Item it = p.onderdelen().lees(hand);
        if (it != null && it.soort().equals("brandstof")) {
            tanken(pl, a.v, hand);
            return;
        }
        if (it != null && it.soort().equals("lak")) {
            if (!eigenaar) Tekst.stuur(pl, "Alleen de eigenaar kan dit voertuig lakken.");
            else if (p.creativeGeblokkeerd(pl)) Tekst.stuur(pl, "Dat kan niet in creative.");
            else lakken(pl, a.v, it.kleur(), hand);
            return;
        }
        if (pl.isSneaking() && eigenaar) {
            if (!a.inzittenden().isEmpty()) {
                Tekst.stuur(pl, "Er zit nog iemand in.");
                return;
            }
            opbergen(a.v);
            pl.playSound(pl.getLocation(), "block.piston.contract", 0.8f, 1.2f);
            Tekst.stuur(pl, "Je voertuig staat weer in je garage (&f/garage&7).");
            return;
        }
        if (p.geblokkeerd(pl.getWorld())) {
            Tekst.stuur(pl, "In deze wereld kun je niet rijden.");
            return;
        }
        String fout = a.stapIn(pl);
        if (fout != null) Tekst.stuur(pl, fout);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void uitstappen(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player pl)) return;
        ActiefVoertuig a = vanEntity(e.getDismounted());
        if (a == null || a.bezigMetWeghalen) return;
        a.uitgestapt(pl);
        boolean inDeLucht = a.m.soort == Soort.VLIEGTUIG && !a.opGrond;
        if (inDeLucht) return;
        Location uit = a.uitstapPlek();
        Location bij = a.midden();
        Bukkit.getScheduler().runTask(p, () -> {
            if (!pl.isOnline() || pl.isInsideVehicle() || uit == null) return;
            // niet terugzetten als de speler juist ergens heen geteleporteerd is
            if (!pl.getWorld().equals(bij.getWorld()) || pl.getLocation().distanceSquared(bij) > 6 * 6) return;
            Location doel = uit.clone();
            doel.setYaw(pl.getYaw());
            doel.setPitch(pl.getPitch());
            pl.teleport(doel);
        });
    }

    @EventHandler
    public void weg(PlayerQuitEvent e) {
        Player pl = e.getPlayer();
        ActiefVoertuig a = vanEntity(pl.getVehicle());
        if (a == null) return;
        Location uit = a.uitstapPlek();
        pl.leaveVehicle();
        if (uit != null) pl.teleport(uit);
    }

    @EventHandler
    public void dood(PlayerDeathEvent e) {
        Player pl = e.getPlayer();
        if (vanEntity(pl.getVehicle()) != null) pl.leaveVehicle();
    }

    @EventHandler(ignoreCancelled = true)
    public void toeter(PlayerAnimationEvent e) {
        if (!p.getConfig().getBoolean("toeter", true)) return;
        ActiefVoertuig a = vanEntity(e.getPlayer().getVehicle());
        if (a != null && e.getPlayer().getVehicle() == a.wortel) a.toeter();
    }

    @EventHandler
    public void chunkWeg(ChunkUnloadEvent e) {
        Chunk c = e.getChunk();
        Iterator<ActiefVoertuig> it = actief.values().iterator();
        while (it.hasNext()) {
            ActiefVoertuig a = it.next();
            if (!a.w.equals(c.getWorld())) continue;
            if (((int) Math.floor(a.x) >> 4) != c.getX() || ((int) Math.floor(a.z) >> 4) != c.getZ()) continue;
            if (!a.inzittenden().isEmpty()) continue;
            a.v.parkeer(a.w.getName(), a.x, a.y, a.z, a.yaw);
            a.weghalen();
            it.remove();
        }
    }

    @EventHandler
    public void entitiesGeladen(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            String s = en.getPersistentDataContainer().get(p.sleutelVoertuig, PersistentDataType.STRING);
            if (s == null) continue;
            ActiefVoertuig a = vanEntity(en);
            if (a == null || !(a.wortel == en || a.klik == en || a.delen.contains(en) || a.stoelen.contains(en))) en.remove();
        }
    }

    @EventHandler
    public void wereldWeg(WorldUnloadEvent e) {
        Iterator<ActiefVoertuig> it = actief.values().iterator();
        while (it.hasNext()) {
            ActiefVoertuig a = it.next();
            if (!a.w.equals(e.getWorld())) continue;
            a.v.parkeer(a.w.getName(), a.x, a.y, a.z, a.yaw);
            a.weghalen();
            it.remove();
        }
    }
}
