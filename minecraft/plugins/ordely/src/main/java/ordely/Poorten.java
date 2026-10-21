package ordely;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Automatische poorten: een vlak (of blok) van blokken dat in een keer verdwijnt als er een speler dichtbij komt
 * en weer terugkomt als er niemand meer in de buurt is. Werkt met elk soort blok, ook 1 blok dik.
 * Opgeslagen in plugins/Ordely/poorten.json.
 */
final class Poorten implements Listener, CommandExecutor, TabCompleter {

    static final Component PREFIX = Component.text("[Poort] ", NamedTextColor.AQUA);
    private static final int MAX_BLOKKEN = 400;

    static final class Poort {
        String naam, wereld;
        int x1, y1, z1, x2, y2, z2;        // x1 <= x2 enz.
        List<String> blokken = new ArrayList<>(); // blokdata in volgorde y, z, x
        double afstand = 3.0;
        transient boolean open;
        transient boolean hersteld;
        transient long laatstIemand;

        int aantal() { return (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1); }
        boolean bevat(Block b) {
            return b.getWorld().getName().equals(wereld) && b.getX() >= x1 && b.getX() <= x2 && b.getY() >= y1 && b.getY() <= y2 && b.getZ() >= z1 && b.getZ() <= z2;
        }
        /** Afstand van een punt tot de rand van de poort (0 als je erin staat). */
        double afstandTot(Location l) {
            double dx = Math.max(Math.max(x1 - l.getX(), 0), l.getX() - (x2 + 1));
            double dy = Math.max(Math.max(y1 - (l.getY() + 1.8), 0), l.getY() - (y2 + 1)); // het hele lichaam: voeten tot hoofd
            double dz = Math.max(Math.max(z1 - l.getZ(), 0), l.getZ() - (z2 + 1));
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private final Ordely plugin;
    private final Map<String, Poort> poorten = new LinkedHashMap<>();
    private final Map<UUID, Block[]> hoeken = new HashMap<>();
    private BukkitTask taak;

    Poorten(Ordely plugin) { this.plugin = plugin; }

    private File bestand() { return new File(plugin.getDataFolder(), "poorten.json"); }

    void start() {
        laad();
        taak = Bukkit.getScheduler().runTaskTimer(plugin, this::tik, 40L, 4L);
    }

    void stop() {
        if (taak != null) taak.cancel();
        for (Poort p : poorten.values()) sluit(p, false); // de wereld wordt met dichte poorten opgeslagen
    }

    // ------------------------------------------------------------ opslag
    private void laad() {
        poorten.clear();
        File f = bestand();
        if (!f.isFile()) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("poorten")) {
                Poort p = Ordely.GSON.fromJson(e, Poort.class);
                if (p != null && p.naam != null && p.blokken != null && p.blokken.size() == p.aantal()) poorten.put(p.naam.toLowerCase(Locale.ROOT), p);
                else plugin.getLogger().warning("Poort overgeslagen (onvolledig): " + e);
            }
            plugin.getLogger().info(poorten.size() + " poort(en) geladen.");
        } catch (Exception ex) { plugin.getLogger().warning("poorten.json kon niet gelezen worden: " + ex.getMessage()); }
    }

    private void bewaar() {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        for (Poort p : poorten.values()) a.add(Ordely.GSON.toJsonTree(p));
        o.add("poorten", a);
        plugin.schrijfVeilig(bestand(), Ordely.GSON.toJson(o));
    }

    // ------------------------------------------------------------ open en dicht
    private void tik() {
        long nu = System.currentTimeMillis();
        long sluitNa = Math.max(0, plugin.getConfig().getLong("poorten.dicht-na-ms", 1000));
        for (Poort p : poorten.values()) {
            World w = Bukkit.getWorld(p.wereld);
            if (w == null || !w.isChunkLoaded(((p.x1 + p.x2) / 2) >> 4, ((p.z1 + p.z2) / 2) >> 4)) continue;
            if (!p.hersteld) { sluit(p, false); p.hersteld = true; }
            boolean iemand = false;
            for (Player pl : w.getPlayers()) {
                if (pl.getGameMode() == GameMode.SPECTATOR || pl.isDead()) continue;
                if (p.afstandTot(pl.getLocation()) <= p.afstand) { iemand = true; break; }
            }
            if (iemand) {
                p.laatstIemand = nu;
                if (!p.open) open(p, true);
            } else if (p.open && nu - p.laatstIemand >= sluitNa) {
                sluit(p, true);
            }
        }
    }

    private void open(Poort p, boolean geluid) {
        World w = Bukkit.getWorld(p.wereld);
        if (w == null) return;
        for (int y = p.y1; y <= p.y2; y++) for (int z = p.z1; z <= p.z2; z++) for (int x = p.x1; x <= p.x2; x++)
            w.getBlockAt(x, y, z).setType(Material.AIR, false);
        p.open = true;
        if (geluid) speel(w, p, plugin.getConfig().getString("poorten.geluid-open", "block.iron_door.open"));
    }

    private void sluit(Poort p, boolean geluid) {
        World w = Bukkit.getWorld(p.wereld);
        if (w == null) return;
        int i = 0;
        for (int y = p.y1; y <= p.y2; y++) for (int z = p.z1; z <= p.z2; z++) for (int x = p.x1; x <= p.x2; x++) {
            BlockData d;
            try { d = Bukkit.createBlockData(p.blokken.get(i)); } catch (IllegalArgumentException e) { d = Material.AIR.createBlockData(); }
            i++;
            Block b = w.getBlockAt(x, y, z);
            if (!b.getBlockData().equals(d)) b.setBlockData(d, false);
        }
        boolean wasOpen = p.open;
        p.open = false;
        if (geluid && wasOpen) speel(w, p, plugin.getConfig().getString("poorten.geluid-dicht", "block.iron_door.close"));
    }

    private void speel(World w, Poort p, String geluid) {
        if (geluid == null || geluid.isBlank()) return;
        Location l = new Location(w, (p.x1 + p.x2 + 1) / 2.0, (p.y1 + p.y2 + 1) / 2.0, (p.z1 + p.z2 + 1) / 2.0);
        try { w.playSound(l, geluid, 1f, 1f); } catch (Exception ignored) { }
    }

    private Poort poortBij(Block b) {
        for (Poort p : poorten.values()) if (p.bevat(b)) return p;
        return null;
    }

    // ------------------------------------------------------------ bescherming
    // De poort zet zijn blokken steeds terug: zonder deze bescherming kun je ze slopen en zo blokken bijmaken.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void opBreken(BlockBreakEvent e) {
        Poort p = poortBij(e.getBlock());
        if (p == null) return;
        e.setCancelled(true);
        if (e.getPlayer().hasPermission("ordely.poort"))
            zeg(e.getPlayer(), "Dit blok hoort bij poort " + p.naam + ". Wil je de poort veranderen? Haal hem eerst weg met /poort weg " + p.naam + ".", NamedTextColor.YELLOW);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void opPlaatsen(BlockPlaceEvent e) {
        Poort p = poortBij(e.getBlockPlaced());
        if (p == null) return;
        e.setCancelled(true);
        if (e.getPlayer().hasPermission("ordely.poort")) zeg(e.getPlayer(), "Hier zit poort " + p.naam + ".", NamedTextColor.YELLOW);
    }

    @EventHandler(ignoreCancelled = true)
    public void opExplosie(EntityExplodeEvent e) { e.blockList().removeIf(b -> poortBij(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void opBlokExplosie(BlockExplodeEvent e) { e.blockList().removeIf(b -> poortBij(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void opZuiger(BlockPistonExtendEvent e) { if (e.getBlocks().stream().anyMatch(b -> poortBij(b) != null)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void opZuigerTerug(BlockPistonRetractEvent e) { if (e.getBlocks().stream().anyMatch(b -> poortBij(b) != null)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void opVerandering(EntityChangeBlockEvent e) { if (poortBij(e.getBlock()) != null) e.setCancelled(true); }

    // ------------------------------------------------------------ commando
    private static void zeg(CommandSender s, String tekst, NamedTextColor kleur) { s.sendMessage(PREFIX.append(Component.text(tekst, kleur))); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("ordely.poort")) { zeg(s, "Daar heb je geen toestemming voor.", NamedTextColor.RED); return true; }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "hulp";
        switch (sub) {
            case "hoek1", "hoek2" -> {
                if (!(s instanceof Player pl)) { zeg(s, "Dit kan alleen in het spel.", NamedTextColor.RED); return true; }
                Block b = pl.getTargetBlockExact(10);
                if (b == null || b.getType().isAir()) { zeg(s, "Kijk naar een blok van de poort (binnen 10 blokken).", NamedTextColor.RED); return true; }
                Block[] h = hoeken.computeIfAbsent(pl.getUniqueId(), k -> new Block[2]);
                h[sub.equals("hoek1") ? 0 : 1] = b;
                zeg(s, (sub.equals("hoek1") ? "Hoek 1" : "Hoek 2") + " gezet op " + b.getX() + " " + b.getY() + " " + b.getZ() + " (" + b.getType().getKey().getKey() + ").", NamedTextColor.GREEN);
                if (h[0] != null && h[1] != null && h[0].getWorld().equals(h[1].getWorld())) {
                    int n = (Math.abs(h[0].getX() - h[1].getX()) + 1) * (Math.abs(h[0].getY() - h[1].getY()) + 1) * (Math.abs(h[0].getZ() - h[1].getZ()) + 1);
                    zeg(s, "Samen " + n + " blokken. Maak de poort met /poort maak <naam>.", NamedTextColor.GRAY);
                }
            }
            case "maak" -> {
                if (!(s instanceof Player pl)) { zeg(s, "Dit kan alleen in het spel.", NamedTextColor.RED); return true; }
                if (a.length < 2 || !a[1].matches("[A-Za-z0-9_-]{1,32}")) { zeg(s, "Gebruik: /poort maak <naam> [afstand]  (naam: letters, cijfers, _ of -)", NamedTextColor.RED); return true; }
                Block[] h = hoeken.get(pl.getUniqueId());
                if (h == null || h[0] == null || h[1] == null) { zeg(s, "Zet eerst twee hoeken: kijk naar een hoek en typ /poort hoek1, dan de andere hoek met /poort hoek2.", NamedTextColor.RED); return true; }
                if (!h[0].getWorld().equals(h[1].getWorld())) { zeg(s, "De twee hoeken liggen in verschillende werelden.", NamedTextColor.RED); return true; }
                String sleutel = a[1].toLowerCase(Locale.ROOT);
                if (poorten.containsKey(sleutel)) { zeg(s, "Er is al een poort " + a[1] + ". Kies een andere naam of haal hem eerst weg.", NamedTextColor.RED); return true; }
                Poort p = new Poort();
                p.naam = a[1]; p.wereld = h[0].getWorld().getName();
                p.x1 = Math.min(h[0].getX(), h[1].getX()); p.x2 = Math.max(h[0].getX(), h[1].getX());
                p.y1 = Math.min(h[0].getY(), h[1].getY()); p.y2 = Math.max(h[0].getY(), h[1].getY());
                p.z1 = Math.min(h[0].getZ(), h[1].getZ()); p.z2 = Math.max(h[0].getZ(), h[1].getZ());
                if (p.aantal() > MAX_BLOKKEN) { zeg(s, "Te groot (" + p.aantal() + " blokken, hoogstens " + MAX_BLOKKEN + ").", NamedTextColor.RED); return true; }
                for (Poort q : poorten.values())
                    if (q.wereld.equals(p.wereld) && q.x1 <= p.x2 && p.x1 <= q.x2 && q.y1 <= p.y2 && p.y1 <= q.y2 && q.z1 <= p.z2 && p.z1 <= q.z2) {
                        zeg(s, "Deze poort overlapt met poort " + q.naam + ".", NamedTextColor.RED); return true;
                    }
                int lucht = 0;
                World w = h[0].getWorld();
                for (int y = p.y1; y <= p.y2; y++) for (int z = p.z1; z <= p.z2; z++) for (int x = p.x1; x <= p.x2; x++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isAir()) lucht++;
                    p.blokken.add(b.getBlockData().getAsString());
                }
                if (lucht == p.aantal()) { zeg(s, "Tussen de hoeken staan alleen maar lege blokken. Bouw de dichte poort eerst.", NamedTextColor.RED); return true; }
                if (a.length >= 3) { try { p.afstand = Math.max(1, Math.min(16, Double.parseDouble(a[2].replace(',', '.')))); } catch (NumberFormatException ignored) { } }
                p.hersteld = true;
                poorten.put(sleutel, p);
                bewaar();
                hoeken.remove(pl.getUniqueId());
                zeg(s, "Poort " + p.naam + " gemaakt (" + (p.x2 - p.x1 + 1) + "x" + (p.y2 - p.y1 + 1) + "x" + (p.z2 - p.z1 + 1) + ", " + p.aantal() + " blokken). Hij gaat open als iemand binnen " + fmt(p.afstand) + " blokken komt.", NamedTextColor.GREEN);
                if (lucht > 0) zeg(s, "Let op: " + lucht + " van de blokken zijn leeg; die blijven leeg.", NamedTextColor.YELLOW);
                plugin.getLogger().info("Poort " + p.naam + " gemaakt door " + s.getName() + " in " + p.wereld + " " + p.x1 + "," + p.y1 + "," + p.z1 + " - " + p.x2 + "," + p.y2 + "," + p.z2);
            }
            case "weg" -> {
                Poort p = a.length >= 2 ? poorten.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (p == null) { zeg(s, "Gebruik: /poort weg <naam>  (zie /poort lijst)", NamedTextColor.RED); return true; }
                sluit(p, false); // de dichte blokken blijven staan
                poorten.remove(p.naam.toLowerCase(Locale.ROOT));
                bewaar();
                zeg(s, "Poort " + p.naam + " weggehaald. De blokken staan er nog (dicht) en zijn nu gewone blokken.", NamedTextColor.GREEN);
            }
            case "afstand" -> {
                Poort p = a.length >= 3 ? poorten.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (p == null) { zeg(s, "Gebruik: /poort afstand <naam> <blokken>", NamedTextColor.RED); return true; }
                try { p.afstand = Math.max(1, Math.min(16, Double.parseDouble(a[2].replace(',', '.')))); }
                catch (NumberFormatException e) { zeg(s, "Dat is geen getal.", NamedTextColor.RED); return true; }
                bewaar();
                zeg(s, "Poort " + p.naam + " gaat nu open binnen " + fmt(p.afstand) + " blokken.", NamedTextColor.GREEN);
            }
            case "lijst" -> {
                if (poorten.isEmpty()) { zeg(s, "Er zijn nog geen poorten. Begin met /poort hoek1.", NamedTextColor.GRAY); return true; }
                for (Poort p : poorten.values())
                    zeg(s, p.naam + ": " + p.wereld + " " + p.x1 + " " + p.y1 + " " + p.z1 + " tot " + p.x2 + " " + p.y2 + " " + p.z2 + ", " + p.aantal() + " blokken, afstand " + fmt(p.afstand) + (p.open ? " (open)" : " (dicht)"), NamedTextColor.WHITE);
            }
            default -> {
                zeg(s, "Automatische poort maken (gaat vanzelf open en dicht):", NamedTextColor.WHITE);
                zeg(s, "1. Bouw de dichte poort, bijvoorbeeld 3x3 en 1 blok dik.", NamedTextColor.GRAY);
                zeg(s, "2. Kijk naar de ene hoek: /poort hoek1, de andere hoek: /poort hoek2", NamedTextColor.GRAY);
                zeg(s, "3. /poort maak <naam> [afstand]   (standaard 3 blokken)", NamedTextColor.GRAY);
                zeg(s, "Verder: /poort lijst, /poort afstand <naam> <blokken>, /poort weg <naam>", NamedTextColor.GRAY);
            }
        }
        return true;
    }

    private static String fmt(double d) { return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d); }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> uit = new ArrayList<>();
        if (!s.hasPermission("ordely.poort")) return uit;
        if (a.length == 1) { for (String o : List.of("hoek1", "hoek2", "maak", "lijst", "afstand", "weg")) if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) uit.add(o); }
        else if (a.length == 2 && (a[0].equalsIgnoreCase("weg") || a[0].equalsIgnoreCase("afstand"))) {
            for (Poort p : poorten.values()) if (p.naam.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) uit.add(p.naam);
        }
        return uit;
    }
}
