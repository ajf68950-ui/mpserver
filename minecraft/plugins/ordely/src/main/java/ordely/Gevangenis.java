package ordely;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** De gevangeniswereld, de gevangenen en alles wat ze wel en niet mogen. */
final class Gevangenis implements Listener {

    /** Wordt opgeslagen in gevangenen.json (de website leest dit bestand ook). */
    static final class Gevangene {
        String uuid;
        String naam;
        String reden;
        String door;
        long sinds;
        long tot;            // 0 = onbepaalde tijd (bij alleen-online-tijd: niet gebruikt)
        long minuten;        // 0 = onbepaalde tijd
        long resterend;      // ms, alleen bij alleen-online-tijd
        int cel;             // 1..32
        String status;       // wacht (nog niet binnen, was offline) | binnen | vrij-wacht (vrijgelaten, spullen nog teruggeven)
        Plek terug;
        String gamemode;
        String inventaris;   // base64, null zolang hij nog niet binnen is
        String vrijReden;
        String vrijDoor;
    }

    static final class Plek {
        String wereld; double x, y, z; float yaw, pitch;
        static Plek van(Location l) {
            Plek p = new Plek();
            p.wereld = l.getWorld().getName(); p.x = l.getX(); p.y = l.getY(); p.z = l.getZ(); p.yaw = l.getYaw(); p.pitch = l.getPitch();
            return p;
        }
        Location naar() {
            World w = wereld == null ? null : Bukkit.getWorld(wereld);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    private final Ordely plugin;
    private final Map<UUID, Gevangene> gevangenen = new ConcurrentHashMap<>();
    private final Set<UUID> magTeleporteren = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> balken = new HashMap<>();
    private final File bestand;
    private World wereld;
    private String wereldNaam = "gevangenis";
    private List<Location> cellen = new ArrayList<>();
    private List<org.bukkit.util.BoundingBox> gebieden = new ArrayList<>();
    private Location bewaking;
    private BukkitTask taak;
    private int tellerOpslaan;

    Gevangenis(Ordely plugin) {
        this.plugin = plugin;
        this.bestand = new File(plugin.getDataFolder(), "gevangenen.json");
        laadConfig();
    }

    void laadConfig() { wereldNaam = plugin.getConfig().getString("gevangenis.wereld", "gevangenis"); }

    String wereldNaam() { return wereldNaam; }
    World wereld() { return wereld; }
    Location bewaking() { return bewaking; }

    boolean zitVast(UUID u) {
        Gevangene g = gevangenen.get(u);
        return g != null && !"vrij-wacht".equals(g.status);
    }

    Gevangene get(UUID u) { return gevangenen.get(u); }
    Collection<Gevangene> alle() { return gevangenen.values(); }

    // ------------------------------------------------------------ starten
    void start() {
        laad();
        maakWereld();
        taak = Bukkit.getScheduler().runTaskTimer(plugin, this::tik, 40L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) bijJoin(p);
    }

    void stop() {
        if (taak != null) taak.cancel();
        for (Map.Entry<UUID, BossBar> e : balken.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue());
        }
        balken.clear();
        bewaar();
    }

    private void maakWereld() {
        wereld = Bukkit.getWorld(wereldNaam);
        if (wereld == null) {
            WorldCreator wc = new WorldCreator(wereldNaam)
                    .generator(new LegeGenerator())
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.FLAT)
                    .generateStructures(false);
            wereld = wc.createWorld();
        }
        if (wereld == null) { plugin.getLogger().severe("De gevangeniswereld kon niet gemaakt worden."); return; }
        regels(wereld);
        cellen = Bouwer.cellen(wereld);
        gebieden = Bouwer.celGebieden();
        bewaking = Bouwer.bewaking(wereld);
        int gebouwd = plugin.data().has("gebouwd-versie") ? plugin.data().get("gebouwd-versie").getAsInt() : 0;
        if (gebouwd < Bouwer.VERSIE || wereld.getBlockAt(0, Bouwer.G, 0).getType().isAir()) {
            bouw(false);
        }
        wereld.setSpawnLocation(bewaking);
    }

    void bouw(boolean opruimen) {
        long t = System.currentTimeMillis();
        new Bouwer(wereld).bouw(opruimen);
        plugin.data().addProperty("gebouwd-versie", Bouwer.VERSIE);
        plugin.bewaarData();
        plugin.getLogger().info("Gevangenis gebouwd in wereld " + wereldNaam + " (" + cellen.size() + " cellen, " + (System.currentTimeMillis() - t) + " ms).");
    }

    @SuppressWarnings({"deprecation", "removal"})
    private void regels(World w) {
        zet(w, GameRule.DO_MOB_SPAWNING, false);
        zet(w, GameRule.SPAWN_MONSTERS, false);
        zet(w, GameRule.DO_DAYLIGHT_CYCLE, false);
        zet(w, GameRule.DO_WEATHER_CYCLE, false);
        zet(w, GameRule.KEEP_INVENTORY, true);
        zet(w, GameRule.ANNOUNCE_ADVANCEMENTS, false);
        zet(w, GameRule.DO_FIRE_TICK, false);
        zet(w, GameRule.MOB_GRIEFING, false);
        zet(w, GameRule.FALL_DAMAGE, false);
        zet(w, GameRule.PVP, false);
        zet(w, GameRule.DO_INSOMNIA, false);
        zet(w, GameRule.DO_PATROL_SPAWNING, false);
        zet(w, GameRule.DO_TRADER_SPAWNING, false);
        zet(w, GameRule.DO_WARDEN_SPAWNING, false);
        zet(w, GameRule.DISABLE_RAIDS, true);
        try { w.setDifficulty(Difficulty.PEACEFUL); } catch (Throwable ignored) { }
        w.setTime(6000);
        w.setStorm(false);
        w.setThundering(false);
    }

    private static <T> void zet(World w, GameRule<T> r, T v) {
        try { w.setGameRule(r, v); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ opslaan
    private void laad() {
        gevangenen.clear();
        if (!bestand.isFile()) return;
        try {
            String t = Files.readString(bestand.toPath(), StandardCharsets.UTF_8);
            JsonObject o = com.google.gson.JsonParser.parseString(t).getAsJsonObject();
            List<Gevangene> l = Ordely.GSON.fromJson(o.get("gevangenen"), new TypeToken<List<Gevangene>>() { }.getType());
            if (l != null) for (Gevangene g : l) if (g != null && g.uuid != null) gevangenen.put(UUID.fromString(g.uuid), g);
        } catch (Exception e) {
            plugin.getLogger().severe("gevangenen.json kon niet gelezen worden: " + e.getMessage());
        }
    }

    void bewaar() {
        JsonObject o = new JsonObject();
        o.addProperty("bijgewerkt", System.currentTimeMillis());
        o.addProperty("alleenOnlineTijd", plugin.getConfig().getBoolean("gevangenis.alleen-online-tijd", false));
        o.add("gevangenen", Ordely.GSON.toJsonTree(new ArrayList<>(gevangenen.values())));
        plugin.schrijfVeilig(bestand, Ordely.GSON.toJson(o));
    }

    // ------------------------------------------------------------ opsluiten
    /** Stuurt een speler (online of offline) naar de gevangenis. Geeft een foutmelding terug of null. */
    String stuur(OfflinePlayer op, long minuten, String reden, String door) {
        if (wereld == null) return "De gevangeniswereld is niet geladen.";
        if (op == null || (op.getName() == null && !op.hasPlayedBefore() && !op.isOnline())) return "Deze speler is niet bekend op deze server.";
        UUID u = op.getUniqueId();
        String naam = op.getName() == null ? u.toString() : op.getName();
        long nu = System.currentTimeMillis();
        boolean alleenOnline = plugin.getConfig().getBoolean("gevangenis.alleen-online-tijd", false);
        Gevangene g = gevangenen.get(u);
        if (g != null && !"vrij-wacht".equals(g.status)) {
            g.reden = reden; g.door = door; g.minuten = minuten; g.sinds = nu;
            g.tot = minuten > 0 ? nu + minuten * 60_000L : 0;
            g.resterend = minuten * 60_000L;
            bewaar();
            plugin.logboek().schrijf("AANGEPAST", naam, u, reden, minuten, door, null);
            Player p = op.getPlayer();
            if (p != null) Ordely.zeg(p, "Je straf is aangepast: " + (minuten > 0 ? minuten + " minuten" : "onbepaalde tijd") + ". Reden: " + reden, NamedTextColor.RED);
            return null;
        }
        if (g != null) {
            // was net vrijgelaten maar heeft zijn spullen nog niet terug: gewoon weer binnen houden
            g.status = "binnen";
        } else {
            g = new Gevangene();
            g.uuid = u.toString();
            g.status = "wacht";
            g.cel = vrijeCel();
        }
        g.naam = naam; g.reden = reden; g.door = door; g.sinds = nu; g.minuten = minuten;
        g.tot = minuten > 0 ? nu + minuten * 60_000L : 0;
        g.resterend = minuten * 60_000L;
        g.vrijReden = null; g.vrijDoor = null;
        gevangenen.put(u, g);
        bewaar();
        plugin.logboek().schrijf("OPGESLOTEN", naam, u, reden, minuten, door, null);
        if (plugin.getConfig().getBoolean("gevangenis.aankondigen", true)) {
            Component m = Ordely.PREFIX.append(Component.text(naam + " is naar de gevangenis gestuurd" + (minuten > 0 ? " voor " + minuten + " minuten." : "."), NamedTextColor.YELLOW));
            for (Player q : Bukkit.getOnlinePlayers()) if (!q.getUniqueId().equals(u)) q.sendMessage(m);
        }
        Player p = op.getPlayer();
        if (p != null && "wacht".equals(g.status)) opsluitenNu(p, g);
        if (alleenOnline && p == null) { /* tijd loopt pas als hij online komt */ }
        return null;
    }

    private int vrijeCel() {
        Set<Integer> bezet = new HashSet<>();
        for (Gevangene g : gevangenen.values()) bezet.add(g.cel);
        for (int i = 1; i <= cellen.size(); i++) if (!bezet.contains(i)) return i;
        return 1 + (int) (Math.random() * Math.max(1, cellen.size()));
    }

    Location cel(Gevangene g) {
        if (cellen.isEmpty()) return bewaking;
        int i = Math.max(1, Math.min(g.cel, cellen.size()));
        return cellen.get(i - 1).clone();
    }

    /** Staat deze plek binnen de cel van de gevangene? */
    boolean inCel(Gevangene g, Location l) {
        if (l == null || wereld == null || !wereld.equals(l.getWorld())) return false;
        if (gebieden.isEmpty()) return true;
        int i = Math.max(1, Math.min(g.cel, gebieden.size()));
        return gebieden.get(i - 1).contains(l.toVector());
    }

    private void terugInCel(Player p, Gevangene g, String melding) {
        Location cel = cel(g);
        magTeleporteren.add(p.getUniqueId());
        p.teleportAsync(cel, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, err) -> magTeleporteren.remove(p.getUniqueId()));
        p.setFallDistance(0);
        if (melding != null) Ordely.zeg(p, melding, NamedTextColor.RED);
    }

    private void opsluitenNu(Player p, Gevangene g) {
        if (!p.getWorld().equals(wereld)) g.terug = Plek.van(p.getLocation());
        else if (g.terug == null) g.terug = Plek.van(Bukkit.getWorlds().get(0).getSpawnLocation());
        if (g.inventaris == null) {
            g.gamemode = p.getGameMode().name();
            p.closeInventory();
            ItemStack[] inhoud = p.getInventory().getContents();
            g.inventaris = codeer(inhoud);
        }
        g.status = "binnen";
        if (plugin.getConfig().getBoolean("gevangenis.alleen-online-tijd", false) && g.minuten > 0 && g.resterend <= 0) g.resterend = g.minuten * 60_000L;
        bewaar();
        p.getInventory().clear();
        p.setFireTicks(0);
        p.setFallDistance(0);
        if (p.isInsideVehicle()) p.leaveVehicle();
        naarCel(p, g, true);
        p.showTitle(Title.title(
                Component.text("Gevangenis", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text(g.reden == null ? "" : g.reden, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800))));
        Ordely.zeg(p, "Je zit in de gevangenis (cel " + g.cel + "). Reden: " + g.reden, NamedTextColor.RED);
        Ordely.zeg(p, g.minuten > 0 ? "Je straf duurt " + g.minuten + " minuten. Je spullen worden bewaard en krijg je terug als je vrij bent." : "Je zit vast tot een beheerder je vrijlaat. Je spullen worden bewaard.", NamedTextColor.GRAY);
    }

    private void naarCel(Player p, Gevangene g, boolean pakGeven) {
        Location cel = cel(g);
        magTeleporteren.add(p.getUniqueId());
        p.teleportAsync(cel, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            magTeleporteren.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && zitVast(p.getUniqueId()) && p.getWorld().equals(wereld) && p.getGameMode() != GameMode.ADVENTURE) p.setGameMode(GameMode.ADVENTURE);
            }, 2L);
            if (pakGeven) Bukkit.getScheduler().runTaskLater(plugin, () -> geefPak(p), 40L);
            toonBalk(p, g);
        }));
    }

    void geefPak(Player p) {
        Gevangene g = gevangenen.get(p.getUniqueId());
        if (g == null || !"binnen".equals(g.status) || !p.isOnline() || !p.getWorld().equals(wereld)) return;
        p.getInventory().clear();
        if (!plugin.getConfig().getBoolean("gevangenis.gevangenispak", true)) return;
        p.getInventory().setChestplate(pak(Material.LEATHER_CHESTPLATE, g));
        p.getInventory().setLeggings(pak(Material.LEATHER_LEGGINGS, g));
        p.getInventory().setBoots(pak(Material.LEATHER_BOOTS, g));
    }

    private static ItemStack pak(Material m, Gevangene g) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(0xF9801D));
            meta.displayName(Component.text("Gevangenispak", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Cel " + g.cel, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.setUnbreakable(true);
            meta.addEnchant(Enchantment.BINDING_CURSE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ------------------------------------------------------------ vrijlaten
    /** Laat een speler vrij. Geeft een foutmelding terug of null. */
    String vrij(UUID u, String reden, String door) {
        Gevangene g = gevangenen.get(u);
        if (g == null || "vrij-wacht".equals(g.status)) return "Deze speler zit niet in de gevangenis.";
        g.vrijReden = reden; g.vrijDoor = door;
        plugin.logboek().schrijf("VRIJGELATEN", g.naam, u, reden, -1, door, null);
        if ("wacht".equals(g.status) || g.inventaris == null) {
            // was nog niet binnen geweest
            gevangenen.remove(u);
            bewaar();
            return null;
        }
        g.status = "vrij-wacht";
        bewaar();
        Player p = Bukkit.getPlayer(u);
        if (p != null) vrijlatenNu(p, g);
        return null;
    }

    private void vrijlatenNu(Player p, Gevangene g) {
        verbergBalk(p);
        p.closeInventory();
        GameMode gm;
        try { gm = GameMode.valueOf(g.gamemode == null ? "SURVIVAL" : g.gamemode); } catch (IllegalArgumentException e) { gm = GameMode.SURVIVAL; }
        if (p.getWorld().equals(wereld)) {
            p.getInventory().clear();
            if (p.getGameMode() != gm) p.setGameMode(gm);
            Location terug = g.terug == null ? null : g.terug.naar();
            if (terug == null) terug = Bukkit.getWorlds().get(0).getSpawnLocation();
            magTeleporteren.add(p.getUniqueId());
            p.teleportAsync(terug, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                magTeleporteren.remove(p.getUniqueId());
                Bukkit.getScheduler().runTaskLater(plugin, () -> geefTerug(p, 0), 40L);
            }));
        } else {
            if (p.getGameMode() != gm) p.setGameMode(gm);
            Bukkit.getScheduler().runTaskLater(plugin, () -> geefTerug(p, 0), 40L);
        }
    }

    private void geefTerug(Player p, int poging) {
        if (!p.isOnline()) return;
        Gevangene g = gevangenen.get(p.getUniqueId());
        if (g == null || !"vrij-wacht".equals(g.status)) return;
        if (p.getWorld().equals(wereld)) { vrijlatenNu(p, g); return; }
        GameMode gm;
        try { gm = GameMode.valueOf(g.gamemode == null ? "SURVIVAL" : g.gamemode); } catch (IllegalArgumentException e) { gm = GameMode.SURVIVAL; }
        ItemStack[] terug = decodeer(g.inventaris);
        if (p.getGameMode() != gm) {
            // eerst de juiste spelmodus (Multiverse-Inventories wisselt dan de inventaris), daarna pas de spullen
            if (poging < 3) {
                p.setGameMode(gm);
                Bukkit.getScheduler().runTaskLater(plugin, () -> geefTerug(p, poging + 1), 20L);
                return;
            }
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
                // spullen uit creative nooit in een survival-inventaris zetten
                plugin.getLogger().info(g.naam + " zat in creative toen hij werd opgesloten; die creative-spullen worden niet in survival teruggezet.");
                terug = null;
            }
        }
        archiveer(g);
        p.getInventory().clear();
        if (terug != null) {
            ItemStack[] passend = new ItemStack[p.getInventory().getSize()];
            System.arraycopy(terug, 0, passend, 0, Math.min(terug.length, passend.length));
            p.getInventory().setContents(passend);
        }
        gevangenen.remove(p.getUniqueId());
        bewaar();
        p.showTitle(Title.title(Component.text("Je bent vrij", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("Je spullen heb je terug.", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
        Ordely.zeg(p, "Je bent vrijgelaten" + (g.vrijReden != null && !g.vrijReden.isBlank() ? " (" + g.vrijReden + ")" : "") + ". Je spullen heb je terug.", NamedTextColor.GREEN);
    }

    /** Bewaart een kopie van de spullen in herstel/, voor het geval er iets misgaat. */
    private void archiveer(Gevangene g) {
        try {
            File dir = new File(plugin.getDataFolder(), "herstel");
            dir.mkdirs();
            String ts = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            plugin.schrijfVeilig(new File(dir, g.naam + "-" + ts + ".json"), Ordely.GSON.toJson(g));
            File[] oud = dir.listFiles();
            long grens = System.currentTimeMillis() - 30L * 24 * 3600_000L;
            if (oud != null) for (File f : oud) if (f.lastModified() < grens) f.delete();
        } catch (Exception e) {
            plugin.getLogger().warning("Kon de spullen van " + g.naam + " niet archiveren: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ elke seconde
    private void tik() {
        long nu = System.currentTimeMillis();
        boolean alleenOnline = plugin.getConfig().getBoolean("gevangenis.alleen-online-tijd", false);
        boolean veranderd = false;
        for (Gevangene g : new ArrayList<>(gevangenen.values())) {
            if (!"binnen".equals(g.status) && !"wacht".equals(g.status)) continue;
            UUID u = UUID.fromString(g.uuid);
            Player p = Bukkit.getPlayer(u);
            if (g.minuten > 0) {
                long over;
                if (alleenOnline) {
                    if (p != null && "binnen".equals(g.status)) { g.resterend -= 1000; veranderd = true; }
                    over = g.resterend;
                } else {
                    over = g.tot - nu;
                }
                if (over <= 0) { vrij(u, "Straf uitgezeten", "systeem"); continue; }
            }
            if (p == null || !"binnen".equals(g.status)) continue;
            if (!p.getWorld().equals(wereld)) {
                if (!magTeleporteren.contains(u)) naarCel(p, g, true);
                continue;
            }
            if (p.getGameMode() != GameMode.ADVENTURE && !magTeleporteren.contains(u)) p.setGameMode(GameMode.ADVENTURE);
            if (!magTeleporteren.contains(u) && !inCel(g, p.getLocation())) terugInCel(p, g, "Je kunt je cel niet uit.");
            if (plugin.getConfig().getBoolean("gevangenis.gevangenispak", true) && p.getInventory().getChestplate() == null && !magTeleporteren.contains(u)) geefPak(p);
            if (p.getFoodLevel() < 20) p.setFoodLevel(20);
            toonBalk(p, g);
        }
        if (veranderd && ++tellerOpslaan >= 30) { tellerOpslaan = 0; bewaar(); }
    }

    long resterend(Gevangene g) {
        if (g.minuten <= 0) return -1;
        if (plugin.getConfig().getBoolean("gevangenis.alleen-online-tijd", false)) return Math.max(0, g.resterend);
        return Math.max(0, g.tot - System.currentTimeMillis());
    }

    private void toonBalk(Player p, Gevangene g) {
        long over = resterend(g);
        Component tekst = Component.text("Gevangenis", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text("  cel " + g.cel + "  |  ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text(over < 0 ? "tot vrijlating" : "nog " + Ordely.duur(over), NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false));
        float voortgang = over < 0 ? 1f : (float) Math.max(0, Math.min(1, over / (double) (g.minuten * 60_000L)));
        BossBar b = balken.get(p.getUniqueId());
        if (b == null) {
            b = BossBar.bossBar(tekst, voortgang, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            balken.put(p.getUniqueId(), b);
            p.showBossBar(b);
        } else {
            b.name(tekst);
            b.progress(voortgang);
            p.showBossBar(b);
        }
    }

    private void verbergBalk(Player p) {
        BossBar b = balken.remove(p.getUniqueId());
        if (b != null) p.hideBossBar(b);
    }

    // ------------------------------------------------------------ events
    @EventHandler(priority = EventPriority.MONITOR)
    public void opJoin(PlayerJoinEvent e) { bijJoin(e.getPlayer()); }

    private void bijJoin(Player p) {
        Gevangene g = gevangenen.get(p.getUniqueId());
        if (g == null) {
            // niet (meer) gevangen maar nog wel in de gevangeniswereld? Terug naar de spawn.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && p.getWorld().equals(wereld) && !p.hasPermission("ordely.beheer")) {
                    magTeleporteren.add(p.getUniqueId());
                    p.teleportAsync(Bukkit.getWorlds().get(0).getSpawnLocation()).whenComplete((a, b) -> magTeleporteren.remove(p.getUniqueId()));
                }
            }, 40L);
            return;
        }
        // even wachten zodat Multiverse-Inventories eerst de spullen laadt
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Gevangene h = gevangenen.get(p.getUniqueId());
            if (h == null) return;
            switch (h.status) {
                case "wacht" -> opsluitenNu(p, h);
                case "binnen" -> {
                    if (!p.getWorld().equals(wereld)) naarCel(p, h, true);
                    else { if (p.getGameMode() != GameMode.ADVENTURE) p.setGameMode(GameMode.ADVENTURE); toonBalk(p, h); Bukkit.getScheduler().runTaskLater(plugin, () -> geefPak(p), 20L); }
                    Ordely.zeg(p, "Je zit nog in de gevangenis. Reden: " + h.reden, NamedTextColor.RED);
                }
                case "vrij-wacht" -> vrijlatenNu(p, h);
                default -> { }
            }
        }, 40L);
    }

    @EventHandler
    public void opQuit(PlayerQuitEvent e) {
        verbergBalk(e.getPlayer());
        magTeleporteren.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void opTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (magTeleporteren.contains(u) || e.getTo() == null) return;
        World naar = e.getTo().getWorld();
        if (zitVast(u)) {
            Gevangene g = gevangenen.get(u);
            if (naar != null && !naar.equals(wereld)) {
                e.setCancelled(true);
                Ordely.zeg(p, "Je kunt de gevangenis niet uit.", NamedTextColor.RED);
            } else if (g != null && "binnen".equals(g.status) && !inCel(g, e.getTo())) {
                e.setCancelled(true);
                Ordely.zeg(p, "Je kunt je cel niet uit.", NamedTextColor.RED);
            }
            return;
        }
        if (naar != null && naar.equals(wereld) && !p.getWorld().equals(wereld) && !p.hasPermission("ordely.beheer")) {
            e.setCancelled(true);
            Ordely.zeg(p, "De gevangenis is niet open voor bezoekers.", NamedTextColor.RED);
        }
    }

    /** Gevangenen kunnen geen andere spelmodus kiezen (ook niet met F3+F4 of als OP). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void opSpelmodus(org.bukkit.event.player.PlayerGameModeChangeEvent e) {
        if (!zitVast(e.getPlayer().getUniqueId()) || e.getNewGameMode() == GameMode.ADVENTURE) return;
        Gevangene g = gevangenen.get(e.getPlayer().getUniqueId());
        if (g != null && "wacht".equals(g.status)) return;
        e.setCancelled(true);
        Ordely.zeg(e.getPlayer(), "In de gevangenis kun je niet van spelmodus wisselen.", NamedTextColor.RED);
    }

    /** Uit de cel lopen of vliegen kan niet; wie in de leegte valt, komt terug. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opBeweeg(org.bukkit.event.player.PlayerMoveEvent e) {
        Location to = e.getTo();
        if (wereld == null || to == null || !wereld.equals(to.getWorld())) return;
        Location from = e.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (magTeleporteren.contains(u)) return;
        Gevangene g = gevangenen.get(u);
        if (g != null && "binnen".equals(g.status)) {
            if (!inCel(g, to)) {
                e.setTo(inCel(g, from) ? from : cel(g));
                p.setFallDistance(0);
            }
            return;
        }
        if (to.getY() < 40) {
            // beheerder of bezoeker die van de rand valt
            e.setTo(bewaking.clone());
            p.setFallDistance(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void opRespawn(PlayerRespawnEvent e) {
        Gevangene g = gevangenen.get(e.getPlayer().getUniqueId());
        if (g != null && "binnen".equals(g.status)) e.setRespawnLocation(cel(g));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opSchade(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && wereld != null && p.getWorld().equals(wereld)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opSlaan(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && zitVast(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opHonger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && zitVast(p.getUniqueId())) { e.setCancelled(true); p.setFoodLevel(20); }
    }

    private boolean magNiets(Player p) {
        if (zitVast(p.getUniqueId())) return true;
        return wereld != null && p.getWorld().equals(wereld) && !p.hasPermission("ordely.beheer");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opSloop(BlockBreakEvent e) { if (magNiets(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opPlaats(BlockPlaceEvent e) { if (magNiets(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH)
    public void opGebruik(PlayerInteractEvent e) { if (zitVast(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opGebruikEntiteit(PlayerInteractEntityEvent e) { if (zitVast(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opGooi(PlayerDropItemEvent e) { if (zitVast(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opOppakken(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && zitVast(p.getUniqueId())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opInventaris(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && zitVast(p.getUniqueId())) e.setCancelled(true); }

    // ------------------------------------------------------------ bezoek voor beheerders
    void bezoek(Player p) {
        magTeleporteren.add(p.getUniqueId());
        p.teleportAsync(bewaking, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((a, b) -> magTeleporteren.remove(p.getUniqueId()));
    }

    // ------------------------------------------------------------ voor de website / lijst
    JsonArray lijstJson() {
        JsonArray a = new JsonArray();
        for (Gevangene g : gevangenen.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("naam", g.naam);
            o.addProperty("reden", g.reden);
            o.addProperty("door", g.door);
            o.addProperty("cel", g.cel);
            o.addProperty("status", g.status);
            o.addProperty("sinds", Instant.ofEpochMilli(g.sinds).atZone(ZoneId.systemDefault()).toLocalDateTime().format(Logboek.TIJD));
            o.addProperty("resterend", resterend(g));
            a.add(o);
        }
        return a;
    }

    // ------------------------------------------------------------ inventaris (de)coderen
    static String codeer(ItemStack[] items) {
        ItemStack[] c = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) c[i] = items[i] == null ? ItemStack.empty() : items[i];
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(c));
    }

    static ItemStack[] decodeer(String s) {
        if (s == null || s.isEmpty()) return null;
        ItemStack[] it = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(s));
        for (int i = 0; i < it.length; i++) if (it[i] != null && it[i].getType().isAir()) it[i] = null;
        return it;
    }

    static String kort(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
