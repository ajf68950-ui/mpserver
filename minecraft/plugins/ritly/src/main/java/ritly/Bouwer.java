package ritly;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Een voertuig bouwen uit echte blokken: selecteren met de bouwstaf en omzetten naar een model. */
final class Bouwer implements Listener {

    static final class Selectie {
        World w;
        int[] a, b;
    }

    private final Ritly p;
    private final Map<UUID, Selectie> selecties = new HashMap<>();

    Bouwer(Ritly p) {
        this.p = p;
    }

    private boolean isStaf(org.bukkit.inventory.ItemStack is) {
        Onderdelen.Item it = p.onderdelen().lees(is);
        return it != null && it.soort().equals("staf");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void klik(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null || !isStaf(e.getItem())) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player pl = e.getPlayer();
        Block b = e.getClickedBlock();
        Selectie s = selecties.computeIfAbsent(pl.getUniqueId(), k -> new Selectie());
        if (s.w != null && !s.w.equals(b.getWorld())) {
            s.a = null;
            s.b = null;
        }
        s.w = b.getWorld();
        int[] c = {b.getX(), b.getY(), b.getZ()};
        boolean een = e.getAction() == Action.LEFT_CLICK_BLOCK;
        if (een) s.a = c;
        else s.b = c;
        String maat = "";
        if (s.a != null && s.b != null)
            maat = " &8(" + (Math.abs(s.a[0] - s.b[0]) + 1) + " x " + (Math.abs(s.a[1] - s.b[1]) + 1) + " x " + (Math.abs(s.a[2] - s.b[2]) + 1) + ")";
        Tekst.stuur(pl, "Hoek " + (een ? 1 : 2) + ": &f" + c[0] + " " + c[1] + " " + c[2] + maat);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void breek(BlockBreakEvent e) {
        if (isStaf(e.getPlayer().getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    Selectie selectie(Player pl) {
        Selectie s = selecties.get(pl.getUniqueId());
        if (s == null || s.a == null || s.b == null || s.w == null) return null;
        return s;
    }

    /** Resultaat van het omzetten: een model of een foutmelding. */
    record Resultaat(Model model, int blokken, String fout) {}

    Resultaat maakModel(Player pl, Selectie s, String id, Soort soort, float schaal, String naam, UUID eigenaar, boolean beheer) {
        int maxMaat = p.getConfig().getInt(beheer ? "bouwen.beheer-max-maat" : "bouwen.speler-max-maat", beheer ? 32 : 10);
        int maxBlokken = p.getConfig().getInt(beheer ? "bouwen.beheer-max-blokken" : "bouwen.speler-max-blokken", beheer ? 800 : 120);
        int x1 = Math.min(s.a[0], s.b[0]), x2 = Math.max(s.a[0], s.b[0]);
        int y1 = Math.min(s.a[1], s.b[1]), y2 = Math.max(s.a[1], s.b[1]);
        int z1 = Math.min(s.a[2], s.b[2]), z2 = Math.max(s.a[2], s.b[2]);
        if (x2 - x1 + 1 > maxMaat || y2 - y1 + 1 > maxMaat || z2 - z1 + 1 > maxMaat)
            return new Resultaat(null, 0, "De selectie is te groot (max " + maxMaat + " blokken per kant).");
        // blokken verzamelen
        int sx = x2 - x1 + 1, sy = y2 - y1 + 1, sz = z2 - z1 + 1;
        String[][][] raster = new String[sx][sy][sz];
        List<int[]> bestuurder = new ArrayList<>(), passagiers = new ArrayList<>();
        int aantal = 0;
        int mnX = Integer.MAX_VALUE, mxX = Integer.MIN_VALUE, mnY = Integer.MAX_VALUE, mnZ = Integer.MAX_VALUE, mxZ = Integer.MIN_VALUE;
        StructureRotation rot = rotatie(pl.getYaw());
        for (int x = x1; x <= x2; x++)
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++) {
                    Block b = s.w.getBlockAt(x, y, z);
                    Material t = b.getType();
                    if (t.isAir() || t == Material.STRUCTURE_VOID || t == Material.BARRIER || t == Material.LIGHT || t == Material.MOVING_PISTON)
                        continue;
                    if (t == Material.LIGHT_WEIGHTED_PRESSURE_PLATE) {
                        bestuurder.add(new int[]{x, y, z});
                        continue;
                    }
                    if (t == Material.HEAVY_WEIGHTED_PRESSURE_PLATE) {
                        passagiers.add(new int[]{x, y, z});
                        continue;
                    }
                    BlockData bd = b.getBlockData().clone();
                    if (rot != StructureRotation.NONE) bd.rotate(rot);
                    raster[x - x1][y - y1][z - z1] = bd.getAsString(true).replace("minecraft:", "");
                    aantal++;
                    mnX = Math.min(mnX, x); mxX = Math.max(mxX, x);
                    mnY = Math.min(mnY, y);
                    mnZ = Math.min(mnZ, z); mxZ = Math.max(mxZ, z);
                }
        if (aantal == 0) return new Resultaat(null, 0, "Er staan geen blokken in de selectie.");
        if (aantal > maxBlokken) return new Resultaat(null, aantal, "Te veel blokken: " + aantal + " (max " + maxBlokken + ").");
        if (bestuurder.isEmpty())
            return new Resultaat(null, aantal, "Zet een &fgouden drukplaat&7 neer als stoel voor de bestuurder.");
        // assen: de kijkrichting van de speler is de voorkant
        int[] f = voorkant(pl.getYaw());
        double cx = (mnX + mxX + 1) / 2.0, cz = (mnZ + mxZ + 1) / 2.0, by = mnY;
        // samenvoegen (greedy) van effen blokken
        List<String> samen = p.getConfig().getStringList("bouwen.samenvoegen");
        boolean[][][] klaar = new boolean[sx][sy][sz];
        List<Model.Deel> delen = new ArrayList<>();
        for (int x = 0; x < sx; x++)
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++) {
                    String k = raster[x][y][z];
                    if (k == null || klaar[x][y][z]) continue;
                    int ex = 1, ey = 1, ez = 1;
                    if (samenvoegbaar(k, samen)) {
                        while (x + ex < sx && vrij(raster, klaar, k, x + ex, y, z)) ex++;
                        boolean ok = true;
                        while (ok && z + ez < sz) {
                            for (int i = 0; i < ex; i++) if (!vrij(raster, klaar, k, x + i, y, z + ez)) { ok = false; break; }
                            if (ok) ez++;
                        }
                        ok = true;
                        while (ok && y + ey < sy) {
                            for (int i = 0; i < ex && ok; i++)
                                for (int j = 0; j < ez; j++) if (!vrij(raster, klaar, k, x + i, y + ey, z + j)) { ok = false; break; }
                            if (ok) ey++;
                        }
                    }
                    for (int i = 0; i < ex; i++) for (int j = 0; j < ey; j++) for (int l = 0; l < ez; l++) klaar[x + i][y + j][z + l] = true;
                    double wx1 = x1 + x, wy1 = y1 + y, wz1 = z1 + z;
                    Vector3f a = lokaal(wx1, wy1, wz1, cx, by, cz, f, schaal);
                    Vector3f b = lokaal(wx1 + ex, wy1 + ey, wz1 + ez, cx, by, cz, f, schaal);
                    Vector3f van = new Vector3f(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
                    Vector3f maat = new Vector3f(Math.abs(a.x - b.x), Math.abs(a.y - b.y), Math.abs(a.z - b.z));
                    delen.add(new Model.Deel(k, van, maat, (char) 0, null));
                }
        List<Vector3f> zitten = new ArrayList<>();
        for (int[] z : bestuurder.subList(0, 1)) zitten.add(lokaal(z[0] + 0.5, z[1] + 0.1, z[2] + 0.5, cx, by, cz, f, schaal));
        for (int i = 1; i < bestuurder.size(); i++) {
            int[] z = bestuurder.get(i);
            zitten.add(lokaal(z[0] + 0.5, z[1] + 0.1, z[2] + 0.5, cx, by, cz, f, schaal));
        }
        for (int[] z : passagiers) zitten.add(lokaal(z[0] + 0.5, z[1] + 0.1, z[2] + 0.5, cx, by, cz, f, schaal));
        ConfigurationSection st = p.getConfig().getConfigurationSection("soorten." + soort.sleutel());
        double max = st == null ? 50 : st.getDouble("max-snelheid", 50);
        double acc = st == null ? 20 : st.getDouble("acceleratie", 20);
        double dr = st == null ? 90 : st.getDouble("draaien", 90);
        double tank = st == null ? 40 : st.getDouble("tank", 40);
        double verbr = st == null ? 8 : st.getDouble("verbruik", 8);
        double op = st == null ? 55 : st.getDouble("opstijgen", 55);
        Model m = new Model(id, naam, soort, delen, zitten, DyeColor.WHITE, soort.icoon, max, acc, dr, tank, verbr, op, 0.3, null, eigenaar);
        return new Resultaat(m, aantal, null);
    }

    private static boolean vrij(String[][][] r, boolean[][][] klaar, String k, int x, int y, int z) {
        return k.equals(r[x][y][z]) && !klaar[x][y][z];
    }

    private static boolean samenvoegbaar(String k, List<String> samen) {
        if (k.contains("[")) return false;
        for (String s : samen) if (k.endsWith(s) || k.equals(s)) return true;
        return false;
    }

    /** Wereld-coordinaat naar model-coordinaat (x = links, z = voor). */
    private static Vector3f lokaal(double wx, double wy, double wz, double cx, double by, double cz, int[] f, float schaal) {
        double dx = wx - cx, dz = wz - cz;
        // links-as = (f.z, 0, -f.x)
        double lx = dx * f[2] + dz * -f[0];
        double lz = dx * f[0] + dz * f[2];
        return new Vector3f((float) (lx * schaal), (float) ((wy - by) * schaal), (float) (lz * schaal));
    }

    static int[] voorkant(float yaw) {
        float y = ActiefVoertuig.norm(yaw);
        if (y >= 315 || y < 45) return new int[]{0, 0, 1};      // zuid
        if (y < 135) return new int[]{-1, 0, 0};                // west
        if (y < 225) return new int[]{0, 0, -1};                // noord
        return new int[]{1, 0, 0};                              // oost
    }

    private static StructureRotation rotatie(float yaw) {
        float y = ActiefVoertuig.norm(yaw);
        if (y >= 315 || y < 45) return StructureRotation.NONE;
        if (y < 135) return StructureRotation.COUNTERCLOCKWISE_90;
        if (y < 225) return StructureRotation.CLOCKWISE_180;
        return StructureRotation.CLOCKWISE_90;
    }

    // ------------------------------------------------------------------ opslaan

    /** Beheer: model opslaan zodat het in winkels kan. */
    String opslaanBeheer(Player pl, String id, Soort soort, float schaal, String naam) {
        Selectie s = selectie(pl);
        if (s == null) return "Selecteer eerst twee hoeken met de bouwstaf (/ritly bouw staf).";
        id = id.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_]{2,32}")) return "Gebruik voor de id alleen kleine letters, cijfers en _ (2-32 tekens).";
        Model bestaand = p.modellen().get(id);
        if (bestaand != null && !p.modellen().isGebouwd(id)) return "Er staat al een model " + id + " in modellen.yml. Kies een andere naam.";
        Resultaat r = maakModel(pl, s, id, soort, schaal, naam == null ? "&f" + id : naam, null, true);
        if (r.fout() != null) return r.fout();
        if (!p.modellen().opslaanGebouwd(r.model())) return "Opslaan is mislukt (zie de console).";
        return null;
    }

    /** Speler: eigen voertuig maken (kost geld per blok) en in de garage zetten. */
    String opslaanSpeler(Player pl, Soort soort, float schaal, String naam) {
        Selectie s = selectie(pl);
        if (s == null) return "Selecteer eerst twee hoeken met de bouwstaf (/garage bouw staf).";
        if (!p.koopWereld(pl.getWorld())) return "Je kunt hier geen voertuig maken (geld werkt niet in deze wereld).";
        if (p.creativeGeblokkeerd(pl)) return "Dit kan niet in creative of als toeschouwer.";
        int max = p.maxVoertuigen(pl);
        if (p.garage().van(pl.getUniqueId()).size() >= max) return "Je garage is vol (max " + max + ").";
        String id = "p" + pl.getUniqueId().toString().substring(0, 8) + "_" + Long.toString(System.currentTimeMillis() % 10_000_000L, 36);
        Resultaat r = maakModel(pl, s, id, soort, schaal, naam == null ? "&fEigen " + soort.naam.toLowerCase(Locale.ROOT) : "&f" + naam, pl.getUniqueId(), false);
        if (r.fout() != null) return r.fout();
        double prijs = r.blokken() * p.getConfig().getDouble("bouwen.prijs-per-blok", 250);
        Economy eco = p.economie();
        if (prijs > 0) {
            if (eco == null) return "De economie werkt nu niet.";
            if (!eco.has(pl, prijs)) return "Dit kost " + Tekst.geld(prijs) + " (" + r.blokken() + " blokken), en zoveel heb je niet.";
            if (!eco.withdrawPlayer(pl, prijs).transactionSuccess()) return "Betalen is mislukt.";
        }
        if (!p.modellen().opslaanGebouwd(r.model())) {
            if (prijs > 0) eco.depositPlayer(pl, prijs);
            return "Opslaan is mislukt.";
        }
        Voertuig v = new Voertuig(UUID.randomUUID(), pl.getUniqueId(), r.model().id);
        v.kleur = DyeColor.WHITE;
        v.brandstof = v.tankInhoud(r.model(), p);
        p.garage().voegToe(v);
        p.garage().opslaan();
        p.winkels().log(pl.getName() + " bouwde " + soort.sleutel() + " " + r.model().id + " (" + r.blokken() + " blokken) voor " + prijs);
        Tekst.stuur(pl, "Je " + soort.naam.toLowerCase(Locale.ROOT) + " is klaar (" + r.blokken() + " blokken, " + r.model().delen.size() + " delen" +
                (prijs > 0 ? ", " + Tekst.geld(prijs) : "") + "). Hij staat in je &f/garage&7.");
        return null;
    }
}
