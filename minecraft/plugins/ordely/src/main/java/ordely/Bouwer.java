package ordely;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bouwt een gevangenis zoals in het echt: hoge buitenmuur met prikkeldraad en wachttorens,
 * celblok met 32 cellen op twee verdiepingen (bed, toilet, wasbak, tralies, ijzeren deur),
 * kantine, werkplaats, luchtplaats met basketbalveld, en een ontvangstgebouw met bezoekersruimte.
 * Alles rond (0, 64, 0) in een lege wereld.
 */
final class Bouwer {

    static final int VERSIE = 1;
    static final int G = 64;                       // bovenkant van de grond
    private static final int[] CEL_X = {-19, -14, -9, -4, 1, 6, 11, 16};

    private final World w;
    private final Map<String, BlockData> cache = new HashMap<>();

    Bouwer(World w) { this.w = w; }

    // ------------------------------------------------------------ vaste plekken
    /** Plek in elke cel waar de gevangene staat (cel 1..32). */
    static List<Location> cellen(World w) {
        List<Location> l = new ArrayList<>();
        for (int verd = 0; verd < 2; verd++) {
            int y0 = verd == 0 ? 65 : 71;
            for (int kant = 0; kant < 2; kant++) {
                boolean noord = kant == 0;
                for (int s : CEL_X) {
                    int voor = noord ? -18 : -6;
                    l.add(new Location(w, s + 1.5, y0 + 0.1, voor + 0.5, noord ? 0f : 180f, 0f));
                }
            }
        }
        return l;
    }

    /** Binnenkant van elke cel (cel 1..32), zelfde volgorde als cellen(). */
    static List<org.bukkit.util.BoundingBox> celGebieden() {
        List<org.bukkit.util.BoundingBox> l = new ArrayList<>();
        for (int verd = 0; verd < 2; verd++) {
            int y0 = verd == 0 ? 65 : 71, top = verd == 0 ? 69 : 74;
            for (int kant = 0; kant < 2; kant++) {
                boolean noord = kant == 0;
                int zMin = noord ? -22 : -6, zMax = noord ? -18 : -2;
                for (int s : CEL_X) l.add(new org.bukkit.util.BoundingBox(s, y0 - 0.5, zMin, s + 4, top + 1, zMax + 1));
            }
        }
        return l;
    }

    /** Plek voor beheerders (ontvangst). */
    static Location bewaking(World w) { return new Location(w, -6.5, 65.1, 33.5, 180f, 0f); }

    // ------------------------------------------------------------ hulpjes
    private BlockData bd(String s) {
        return cache.computeIfAbsent(s, k -> {
            try { return Bukkit.createBlockData(k.contains(":") ? k : "minecraft:" + k); }
            catch (IllegalArgumentException e) {
                // oudere/nieuwere naam (bijv. chain -> iron_chain)
                String alt = k.startsWith("iron_chain") ? k.replace("iron_chain", "chain") : k.startsWith("chain") ? k.replace("chain", "iron_chain") : null;
                if (alt != null) {
                    try { return Bukkit.createBlockData("minecraft:" + alt); } catch (IllegalArgumentException ignored) { }
                }
                Bukkit.getLogger().warning("[Ordely] Onbekend blok bij het bouwen: " + k);
                return Bukkit.createBlockData("minecraft:stone");
            }
        });
    }

    private void set(int x, int y, int z, String s) { w.getBlockAt(x, y, z).setBlockData(bd(s), false); }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String s) {
        BlockData d = bd(s);
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    w.getBlockAt(x, y, z).setBlockData(d, false);
    }

    /** Rand (muren) van een rechthoek. */
    private void ring(int x1, int z1, int x2, int z2, int y1, int y2, String s) {
        fill(x1, y1, z1, x2, y2, z1, s);
        fill(x1, y1, z2, x2, y2, z2, s);
        fill(x1, y1, z1, x1, y2, z2, s);
        fill(x2, y1, z1, x2, y2, z2, s);
    }

    private static final String BARS_X = "iron_bars[east=true,west=true]";
    private static final String BARS_Z = "iron_bars[north=true,south=true]";
    private static final String PANE_X = "glass_pane[east=true,west=true]";
    private static final String PANE_Z = "glass_pane[north=true,south=true]";

    private void wandBord(int x, int y, int z, String facing, String... regels) {
        set(x, y, z, "oak_wall_sign[facing=" + facing + "]");
        tekst(w.getBlockAt(x, y, z), false, regels);
    }

    private void staandBord(int x, int y, int z, int rotatie, String... regels) {
        set(x, y, z, "oak_sign[rotation=" + rotatie + "]");
        tekst(w.getBlockAt(x, y, z), false, regels);
    }

    private void hangBord(int x, int y, int z, int rotatie, String... regels) {
        set(x, y, z, "oak_hanging_sign[rotation=" + rotatie + ",attached=false]");
        tekst(w.getBlockAt(x, y, z), true, regels);
    }

    private void tekst(Block b, boolean beideKanten, String... regels) {
        if (!(b.getState() instanceof Sign sign)) return;
        for (Side side : beideKanten ? new Side[]{Side.FRONT, Side.BACK} : new Side[]{Side.FRONT}) {
            SignSide ss = sign.getSide(side);
            for (int i = 0; i < 4; i++) {
                String r = i < regels.length ? regels[i] : "";
                Component c = Component.text(r, i == 0 ? NamedTextColor.DARK_RED : NamedTextColor.BLACK);
                if (i == 0) c = c.decorate(TextDecoration.BOLD);
                ss.line(i, c);
            }
        }
        sign.setWaxed(true);
        sign.update(true, false);
    }

    // ------------------------------------------------------------ bouwen
    void bouw(boolean opruimen) {
        if (opruimen) fill(-50, 56, -50, 50, 90, 50, "air");
        grond();
        buitenmuur();
        for (int cx : new int[]{-40, 40}) for (int cz : new int[]{-40, 40}) toren(cx, cz);
        celblok();
        kantine();
        werkplaats();
        luchtplaats();
        ontvangst();
        paden();
    }

    private void grond() {
        fill(-48, 56, -48, 48, 60, 48, "stone");
        fill(-48, 61, -48, 48, 63, 48, "dirt");
        fill(-48, G, -48, 48, G, 48, "grass_block[snowy=false]");
    }

    private void buitenmuur() {
        ring(-40, -40, 40, 40, 65, 76, "stone_bricks");
        ring(-39, -39, 39, 39, 65, 76, "stone_bricks");
        ring(-40, -40, 40, 40, 70, 70, "polished_andesite");
        // prikkeldraad bovenop
        fill(-40, 77, -40, 40, 77, -40, "stone_brick_wall[east=low,west=low,up=true]");
        fill(-40, 77, 40, 40, 77, 40, "stone_brick_wall[east=low,west=low,up=true]");
        fill(-40, 77, -40, -40, 77, 40, "stone_brick_wall[north=low,south=low,up=true]");
        fill(40, 77, -40, 40, 77, 40, "stone_brick_wall[north=low,south=low,up=true]");
        fill(-40, 78, -40, 40, 78, -40, BARS_X);
        fill(-40, 78, 40, 40, 78, 40, BARS_X);
        fill(-40, 78, -40, -40, 78, 40, BARS_Z);
        fill(40, 78, -40, 40, 78, 40, BARS_Z);
        // hoofdpoort (zuid) met tralies
        fill(-3, 65, 39, 3, 70, 40, "air");
        fill(-3, 65, 40, 3, 70, 40, BARS_X);
        fill(-4, 71, 39, 4, 71, 40, "polished_andesite");
        wandBord(0, 73, 41, "south", "Penitentiaire", "Inrichting", "MPServer", "Geen toegang");
        set(-4, 72, 41, "lantern[hanging=false]");
        set(4, 72, 41, "lantern[hanging=false]");
        set(-4, 71, 41, "stone_bricks");
        set(4, 71, 41, "stone_bricks");
        fill(-1, G, 41, 1, G, 48, "gravel");
    }

    private void toren(int cx, int cz) {
        fill(cx - 2, 65, cz - 2, cx + 2, 80, cz + 2, "stone_bricks");
        fill(cx - 2, 80, cz - 2, cx + 2, 80, cz + 2, "polished_andesite");
        fill(cx - 2, 81, cz - 2, cx + 2, 83, cz + 2, "air");
        for (int y = 81; y <= 83; y++) {
            for (int dx = -2; dx <= 2; dx += 4) for (int dz = -2; dz <= 2; dz += 4) set(cx + dx, y, cz + dz, "stone_bricks");
        }
        fill(cx - 1, 81, cz - 2, cx + 1, 82, cz - 2, PANE_X);
        fill(cx - 1, 81, cz + 2, cx + 1, 82, cz + 2, PANE_X);
        fill(cx - 2, 81, cz - 1, cx - 2, 82, cz + 1, PANE_Z);
        fill(cx + 2, 81, cz - 1, cx + 2, 82, cz + 1, PANE_Z);
        fill(cx - 3, 84, cz - 3, cx + 3, 84, cz + 3, "deepslate_tile_slab[type=bottom]");
        set(cx, 84, cz, "sea_lantern");
        set(cx, 81, cz, "lantern[hanging=false]");
    }

    private void celblok() {
        int x1 = -20, x2 = 20, z1 = -23, z2 = -1;
        fill(x1, G, z1, x2, G, z2, "polished_andesite");
        ring(x1, z1, x2, z2, 65, 74, "light_gray_concrete");
        fill(x1, 75, z1, x2, 75, z2, "smooth_stone");
        ring(x1, z1, x2, z2, 76, 76, "stone_brick_wall[up=true]");
        fill(x1 + 1, 65, z1 + 1, x2 - 1, 74, z2 - 1, "air");
        fill(-19, G, -12, 19, G, -12, "yellow_concrete");
        // tweede verdieping: vloeren van de cellen en de galerijen
        fill(-19, 70, -22, 19, 70, -17, "polished_andesite");
        fill(-19, 70, -7, 19, 70, -2, "polished_andesite");
        fill(-19, 70, -16, 19, 70, -14, "polished_andesite");
        fill(-19, 70, -10, 19, 70, -8, "polished_andesite");
        fill(18, 70, -16, 19, 70, -8, "polished_andesite");
        fill(-1, 70, -13, 1, 70, -11, "polished_andesite");
        // leuningen
        for (int x = -19; x <= 17; x++) {
            if (x >= -1 && x <= 1) continue;
            set(x, 71, -14, BARS_X);
            set(x, 71, -10, BARS_X);
        }
        for (int z = -13; z <= -11; z++) { set(-2, 71, z, BARS_Z); set(2, 71, z, BARS_Z); }
        // trap naar boven
        for (int i = 0; i <= 4; i++) {
            for (int z = -13; z <= -11; z++) {
                if (i > 0) fill(13 + i, 65, z, 13 + i, 64 + i, z, "polished_andesite");
                set(13 + i, 65 + i, z, "polished_andesite_stairs[facing=east]");
            }
        }
        // cellen
        int nr = 1;
        for (int verd = 0; verd < 2; verd++) {
            int y0 = verd == 0 ? 65 : 71, top = verd == 0 ? 69 : 74;
            for (int kant = 0; kant < 2; kant++) for (int s : CEL_X) cel(s, y0, top, kant == 0, nr++);
        }
        // verlichting
        for (int x = -17; x <= 17; x += 5) {
            set(x, 69, -15, "lantern[hanging=true]");
            set(x, 69, -9, "lantern[hanging=true]");
        }
        for (int x : new int[]{-15, -5, 5, 15}) {
            set(x, 74, -12, "iron_chain[axis=y]");
            set(x, 73, -12, "iron_chain[axis=y]");
            set(x, 72, -12, "lantern[hanging=true]");
        }
        // ingang aan de westkant
        fill(-20, 65, -13, -20, 67, -11, BARS_Z);
        set(-20, 65, -12, "iron_door[half=lower,facing=west,hinge=left,open=false]");
        set(-20, 66, -12, "iron_door[half=upper,facing=west,hinge=left,open=false]");
        wandBord(-21, 69, -12, "west", "Celblok A", "32 cellen", "", "Alleen personeel");
        hangBord(-18, 69, -15, 4, "Celblok A", "Begane grond", "Cel 1 - 16");
    }

    private void cel(int s, int y0, int top, boolean noord, int nr) {
        int achter = noord ? -22 : -2;
        int voor = noord ? -18 : -6;
        int tralie = noord ? -17 : -7;
        int richting = noord ? 1 : -1;
        int buitenmuur = noord ? -23 : -1;
        String naarGang = noord ? "south" : "north";
        String naarAchter = noord ? "north" : "south";
        // tussenmuren
        for (int xd : new int[]{s - 1, s + 4}) {
            if (xd > -20 && xd < 20) fill(xd, y0, Math.min(achter, tralie), xd, top, Math.max(achter, tralie), "white_concrete");
        }
        // tralies en deur
        fill(s, y0, tralie, s + 3, top, tralie, BARS_X);
        set(s + 1, y0, tralie, "iron_door[half=lower,facing=" + naarGang + ",hinge=left,open=false]");
        set(s + 1, y0 + 1, tralie, "iron_door[half=upper,facing=" + naarGang + ",hinge=left,open=false]");
        // raampje met tralies in de buitenmuur
        fill(s + 1, y0 + 2, buitenmuur, s + 2, y0 + 3, buitenmuur, BARS_X);
        // bed tegen de achtermuur
        set(s, y0, achter, "white_bed[facing=" + naarAchter + ",part=head,occupied=false]");
        set(s, y0, achter + richting, "white_bed[facing=" + naarAchter + ",part=foot,occupied=false]");
        // toilet en wasbak
        set(s + 3, y0, achter, "quartz_stairs[facing=" + naarAchter + ",half=bottom,shape=straight]");
        set(s + 3, y0, achter + richting, "cauldron");
        // kleedje en lamp
        set(s + 1, y0, voor, "gray_carpet");
        set(s + 2, y0, voor, "gray_carpet");
        set(s + 2, top, achter + richting, "lantern[hanging=true]");
        // nummer boven de deur, in de gang
        hangBord(s + 1, top, tralie + richting, noord ? 0 : 8, "Cel " + nr);
    }

    private void kantine() {
        int x1 = 24, x2 = 37, z1 = -22, z2 = -2;
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++)
            set(x, G, z, Math.floorMod(x + z, 2) == 0 ? "white_concrete" : "light_gray_concrete");
        ring(x1, z1, x2, z2, 65, 69, "light_gray_concrete");
        fill(x1, 70, z1, x2, 70, z2, "smooth_stone");
        fill(x1 + 1, 65, z1 + 1, x2 - 1, 69, z2 - 1, "air");
        fill(x1, 65, -13, x1, 67, -11, "air");
        for (int x = 26; x <= 35; x += 3) { set(x, 67, z1, PANE_X); set(x, 68, z1, PANE_X); set(x, 67, z2, PANE_X); set(x, 68, z2, PANE_X); }
        for (int tz : new int[]{-19, -15, -9, -5}) {
            for (int x = 27; x <= 32; x++) {
                set(x, 65, tz, "spruce_slab[type=top]");
                set(x, 65, tz - 1, "spruce_stairs[facing=north,half=bottom,shape=straight]");
                set(x, 65, tz + 1, "spruce_stairs[facing=south,half=bottom,shape=straight]");
            }
        }
        // keuken
        fill(34, 65, -21, 34, 65, -3, "smooth_stone");
        set(34, 65, -18, "smoker[facing=west,lit=false]");
        set(34, 65, -15, "smoker[facing=west,lit=false]");
        set(34, 65, -12, "water_cauldron[level=3]");
        set(34, 65, -9, "barrel[facing=up,open=false]");
        set(34, 65, -6, "barrel[facing=up,open=false]");
        for (int z = -21; z <= -3; z += 2) set(36, 65, z, "barrel[facing=up,open=false]");
        for (int x : new int[]{27, 31}) for (int z : new int[]{-19, -12, -5}) set(x, 69, z, "lantern[hanging=true]");
        wandBord(23, 68, -12, "west", "Kantine", "Ontbijt 07:30", "Lunch 12:30", "Avondeten 17:30");
    }

    private void werkplaats() {
        int x1 = -37, x2 = -24, z1 = -22, z2 = -2;
        fill(x1, G, z1, x2, G, z2, "spruce_planks");
        ring(x1, z1, x2, z2, 65, 69, "stone_bricks");
        fill(x1, 70, z1, x2, 70, z2, "dark_oak_planks");
        fill(x1 + 1, 65, z1 + 1, x2 - 1, 69, z2 - 1, "air");
        fill(x2, 65, -13, x2, 67, -11, "air");
        String[] stations = {"crafting_table", "crafting_table", "stonecutter[facing=east]", "loom[facing=east]", "smithing_table",
                "anvil[facing=north]", "grindstone[face=floor,facing=east]", "cartography_table", "fletching_table", "barrel[facing=up,open=false]"};
        for (int i = 0; i < stations.length; i++) set(-35, 65, -20 + i * 2, stations[i]);
        for (int z : new int[]{-18, -14, -10, -6}) { set(-30, 65, z, "crafting_table"); set(-29, 65, z, "crafting_table"); }
        for (int x : new int[]{-33, -28}) for (int z : new int[]{-18, -12, -6}) set(x, 69, z, "lantern[hanging=true]");
        for (int z = -20; z <= -4; z += 4) { set(x1, 67, z, PANE_Z); set(x1, 68, z, PANE_Z); }
        wandBord(-23, 68, -12, "east", "Werkplaats", "Werk = loon", "", "Ma t/m vr");
    }

    private void luchtplaats() {
        // basketbalveld
        fill(1, G, 6, 17, G, 16, "gray_concrete");
        ring(1, 6, 17, 16, G, G, "white_concrete");
        fill(1, G, 11, 17, G, 11, "white_concrete");
        for (int hz : new int[]{5, 17}) {
            for (int y = 65; y <= 67; y++) set(9, y, hz, "dark_oak_fence");
            fill(8, 68, hz, 10, 69, hz, "white_concrete");
            set(9, 68, hz == 5 ? 6 : 16, "hopper[facing=down,enabled=true]");
        }
        // bankjes
        for (int x = -18; x <= -12; x++) set(x, 65, 3, "spruce_stairs[facing=north,half=bottom,shape=straight]");
        for (int x = -18; x <= -12; x++) set(x, 65, 19, "spruce_stairs[facing=south,half=bottom,shape=straight]");
        // optrekstang
        for (int y = 65; y <= 67; y++) { set(13, y, 3, "oak_fence"); set(16, y, 3, "oak_fence"); }
        set(14, 67, 3, BARS_X);
        set(15, 67, 3, BARS_X);
        // boom
        int bx = -15, bz = 12;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = 68; y <= 71; y++) {
            int r = y >= 70 ? 1 : 2;
            if (Math.abs(dx) > r || Math.abs(dz) > r || (Math.abs(dx) == 2 && Math.abs(dz) == 2)) continue;
            set(bx + dx, y, bz + dz, "oak_leaves[persistent=true,distance=1]");
        }
        for (int y = 65; y <= 70; y++) set(bx, y, bz, "oak_log[axis=y]");
        // hek rond de luchtplaats
        fill(-21, 65, 1, -21, 67, 21, BARS_Z);
        fill(21, 65, 1, 21, 67, 21, BARS_Z);
        for (int x = -21; x <= 21; x++) {
            if (x >= -7 && x <= -5) continue;
            fill(x, 65, 21, x, 67, 21, BARS_X);
        }
        staandBord(-4, 65, 20, 0, "Luchtplaats", "1 uur per dag", "", "Niet over het hek");
    }

    private void ontvangst() {
        int x1 = -12, x2 = 12, z1 = 24, z2 = 36;
        fill(x1, G, z1, x2, G, z2, "polished_andesite");
        ring(x1, z1, x2, z2, 65, 69, "white_concrete");
        ring(x1, z1, x2, z2, 69, 69, "light_blue_concrete");
        fill(x1, 70, z1, x2, 70, z2, "smooth_stone");
        fill(x1 + 1, 65, z1 + 1, x2 - 1, 69, z2 - 1, "air");
        fill(-7, 65, z1, -5, 67, z1, "air");
        fill(-7, 65, z2, -5, 67, z2, "air");
        // tussenmuur bewaking | bezoek
        fill(0, 65, 25, 0, 69, 35, "white_concrete");
        fill(0, 65, 27, 0, 66, 28, "air");
        // balie van de bewaking
        fill(-3, 65, 26, -3, 65, 32, "polished_blackstone");
        fill(-3, 66, 26, -3, 66, 32, "polished_blackstone_slab[type=bottom]");
        for (int z : new int[]{27, 29, 31}) set(-2, 65, z, "dark_oak_stairs[facing=east,half=bottom,shape=straight]");
        // kluisjes voor de spullen van nieuwe gevangenen
        for (int z = 26; z <= 34; z++) { set(-11, 65, z, "barrel[facing=east,open=false]"); set(-11, 66, z, "barrel[facing=east,open=false]"); }
        // wachtbankje
        for (int x = -9; x <= -7; x++) set(x, 65, 35, "oak_stairs[facing=south,half=bottom,shape=straight]");
        // bezoekersruimte met glazen wand
        fill(1, 65, 30, 11, 65, 30, "white_concrete");
        fill(1, 66, 30, 11, 68, 30, PANE_X);
        for (int x : new int[]{2, 5, 8, 11}) {
            set(x, 65, 28, "birch_stairs[facing=north,half=bottom,shape=straight]");
            set(x, 65, 32, "birch_stairs[facing=south,half=bottom,shape=straight]");
        }
        for (int x : new int[]{-6, 6}) for (int z : new int[]{27, 33}) set(x, 69, z, "lantern[hanging=true]");
        wandBord(-9, 68, 23, "north", "Ontvangst", "Bewaking", "& Bezoek", "");
        wandBord(-1, 67, 31, "west", "Balie", "Meld je hier", "", "");
    }

    private void paden() {
        String p = "dirt_path";
        fill(-6, G, 1, -6, G, 23, p);
        fill(-22, G, 0, 22, G, 0, p);
        fill(-22, G, -22, -22, G, 0, p);
        set(-21, G, -12, p);
        set(-23, G, -12, p);
        fill(22, G, -12, 22, G, 0, p);
        set(23, G, -12, p);
        fill(-6, G, 37, -6, G, 38, p);
        fill(-6, G, 38, 0, G, 38, p);
        fill(-3, G, 39, 3, G, 39, p);
        // lantaarns langs het pad
        for (int z = 4; z <= 20; z += 8) { set(-8, 65, z, "oak_fence"); set(-8, 66, z, "lantern[hanging=false]"); }
    }
}
