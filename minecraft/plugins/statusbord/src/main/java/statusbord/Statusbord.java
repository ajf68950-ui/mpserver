package statusbord;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Statusbord extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String OBJ = "statusbord";
    private static final DateTimeFormatter TIJD = DateTimeFormatter.ofPattern("HH:mm");

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private File hiddenFile;
    private BukkitTask task;
    private Economy economy;
    private Method papi;

    private String title;
    private List<String> lines;
    private Map<String, String> worldNames;
    private Set<String> offWorlds;
    private String noArmor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hiddenFile = new File(getDataFolder(), "uit.yml");
        loadHidden();
        loadSettings();
        hookEconomy();
        hookPapi();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) show(p);
        startTask();
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (boards.containsKey(p.getUniqueId())) p.setScoreboard(main);
        }
        boards.clear();
    }

    // ------------------------------------------------------------ instellingen

    private void loadSettings() {
        reloadConfig();
        title = getConfig().getString("titel", "&b&lServer");
        lines = new ArrayList<>(getConfig().getStringList("regels"));
        if (lines.size() > 15) lines = new ArrayList<>(lines.subList(0, 15));
        worldNames = new HashMap<>();
        var sec = getConfig().getConfigurationSection("wereldnamen");
        if (sec != null) for (String k : sec.getKeys(false)) worldNames.put(k.toLowerCase(Locale.ROOT), sec.getString(k, k));
        offWorlds = new HashSet<>();
        for (String w : getConfig().getStringList("uit-in-werelden")) offWorlds.add(w.toLowerCase(Locale.ROOT));
        noArmor = getConfig().getString("tekst.geen-armor", "&7geen");
    }

    private void startTask() {
        if (task != null) task.cancel();
        long ticks = Math.max(5, getConfig().getLong("verversen-ticks", 20));
        task = Bukkit.getScheduler().runTaskTimer(this, this::updateAll, ticks, ticks);
    }

    private void hookEconomy() {
        economy = null;
        if (getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void hookPapi() {
        papi = null;
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            papi = c.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (Exception e) {
            getLogger().warning("PlaceholderAPI gevonden, maar kon er niet mee verbinden: " + e.getMessage());
        }
    }

    private void loadHidden() {
        hidden.clear();
        if (!hiddenFile.exists()) return;
        for (String s : YamlConfiguration.loadConfiguration(hiddenFile).getStringList("uit")) {
            try { hidden.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveHidden() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (UUID u : hidden) l.add(u.toString());
        y.set("uit", l);
        try { y.save(hiddenFile); } catch (Exception e) { getLogger().warning("Kon uit.yml niet opslaan: " + e.getMessage()); }
    }

    // ------------------------------------------------------------ bord tonen

    private boolean wants(Player p) {
        if (hidden.contains(p.getUniqueId())) return false;
        if (!p.hasPermission("statusbord.zien")) return false;
        return !offWorlds.contains(p.getWorld().getName().toLowerCase(Locale.ROOT));
    }

    private void show(Player p) {
        if (!wants(p)) { hide(p); return; }
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = sb.registerNewObjective(OBJ, Criteria.DUMMY, LEGACY.deserialize(title));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            try { o.numberFormat(NumberFormat.blank()); } catch (Throwable ignored) { }
            boards.put(p.getUniqueId(), sb);
        }
        if (p.getScoreboard() != sb) p.setScoreboard(sb);
        render(p, sb);
    }

    private void hide(Player p) {
        if (boards.remove(p.getUniqueId()) != null) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    private void updateAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { show(p); } catch (Exception e) { getLogger().warning("Bord van " + p.getName() + " kon niet ververst worden: " + e.getMessage()); }
        }
    }

    private void render(Player p, Scoreboard sb) {
        Objective o = sb.getObjective(OBJ);
        if (o == null) return;
        o.displayName(LEGACY.deserialize(fill(p, title)));
        int n = lines.size();
        for (int i = 0; i < 15; i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            Team t = sb.getTeam("sb" + i);
            if (i >= n) {
                if (t != null) t.unregister();
                sb.resetScores(entry);
                continue;
            }
            if (t == null) {
                t = sb.registerNewTeam("sb" + i);
                t.addEntry(entry);
            }
            t.prefix(LEGACY.deserialize(fill(p, lines.get(i))));
            o.getScore(entry).setScore(n - i);
        }
        mirrorMainTeams(sb);
    }

    // Teams van het hoofd-scorebord (bijv. van Citizens voor NPC-namen) overnemen,
    // zodat die net zo blijven werken als zonder Statusbord.
    private void mirrorMainTeams(Scoreboard sb) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Set<String> keep = new HashSet<>();
        for (Team mt : main.getTeams()) {
            String name = mt.getName();
            if (name.startsWith("sb") && name.length() <= 4) continue;
            keep.add(name);
            Team t = sb.getTeam(name);
            if (t == null) t = sb.registerNewTeam(name);
            try {
                t.displayName(mt.displayName());
                t.prefix(mt.prefix());
                t.suffix(mt.suffix());
                if (mt.hasColor() && mt.color() instanceof net.kyori.adventure.text.format.NamedTextColor nc) t.color(nc);
                t.setAllowFriendlyFire(mt.allowFriendlyFire());
                t.setCanSeeFriendlyInvisibles(mt.canSeeFriendlyInvisibles());
                for (Team.Option opt : Team.Option.values()) t.setOption(opt, mt.getOption(opt));
            } catch (Exception ignored) { }
            Set<String> want = mt.getEntries();
            for (String e : new HashSet<>(t.getEntries())) if (!want.contains(e)) t.removeEntry(e);
            for (String e : want) if (!t.hasEntry(e)) t.addEntry(e);
        }
        for (Team t : new HashSet<>(sb.getTeams())) {
            String name = t.getName();
            if (name.startsWith("sb") && name.length() <= 4) continue;
            if (!keep.contains(name)) t.unregister();
        }
    }

    // ------------------------------------------------------------ tekens invullen

    private String fill(Player p, String s) {
        if (s.indexOf('{') >= 0) {
            s = s.replace("{speler}", p.getName())
                 .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                 .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()))
                 .replace("{wereld}", worldName(p.getWorld()))
                 .replace("{tijd}", LocalTime.now().format(TIJD));
            if (s.contains("{tps}")) s = s.replace("{tps}", tps());
            if (s.contains("{ping}")) s = s.replace("{ping}", ping(p.getPing()));
            if (s.contains("{geld}")) s = s.replace("{geld}", money(p));
            if (s.contains("{leven}")) s = s.replace("{leven}", health(p));
            if (s.contains("{x}") || s.contains("{y}") || s.contains("{z}")) {
                Location l = p.getLocation();
                s = s.replace("{x}", String.valueOf(l.getBlockX())).replace("{y}", String.valueOf(l.getBlockY())).replace("{z}", String.valueOf(l.getBlockZ()));
            }
            if (s.contains("{helm}")) s = s.replace("{helm}", armor(p.getInventory().getHelmet()));
            if (s.contains("{borst}")) s = s.replace("{borst}", armor(p.getInventory().getChestplate()));
            if (s.contains("{broek}")) s = s.replace("{broek}", armor(p.getInventory().getLeggings()));
            if (s.contains("{schoenen}")) s = s.replace("{schoenen}", armor(p.getInventory().getBoots()));
        }
        if (papi != null && s.indexOf('%') >= 0) {
            try { s = (String) papi.invoke(null, p, s); } catch (Exception ignored) { }
        }
        return s;
    }

    private String worldName(World w) {
        return worldNames.getOrDefault(w.getName().toLowerCase(Locale.ROOT), w.getName());
    }

    private String tps() {
        double t;
        try { t = Bukkit.getTPS()[0]; } catch (Throwable e) { return "&7?"; }
        t = Math.min(20.0, t);
        String c = t >= 18 ? "&a" : t >= 15 ? "&e" : "&c";
        return c + String.format(Locale.ROOT, "%.1f", t);
    }

    private String ping(int ms) {
        String c = ms < 80 ? "&a" : ms < 200 ? "&e" : "&c";
        return c + ms + "ms";
    }

    private String money(Player p) {
        if (economy == null) hookEconomy();
        if (economy == null) return "?";
        double v = economy.getBalance(p);
        DecimalFormat df = new DecimalFormat(v % 1 == 0 ? "#,##0" : "#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("nl-NL")));
        return df.format(v);
    }

    private String health(Player p) {
        double max = 20;
        try { var a = p.getAttribute(Attribute.MAX_HEALTH); if (a != null) max = a.getValue(); } catch (Throwable ignored) { }
        double h = p.getHealth();
        double r = h / max;
        String c = r > 0.6 ? "&a" : r > 0.3 ? "&e" : "&c";
        return c + String.format(Locale.ROOT, "%.0f", h / 2.0) + "&7/" + String.format(Locale.ROOT, "%.0f", max / 2.0) + " ❤";
    }

    private String armor(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return noArmor;
        String kind = kindName(item.getType());
        int max = item.getType().getMaxDurability();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable d && d.hasMaxDamage()) max = d.getMaxDamage();
        if (max <= 0 || (meta != null && meta.isUnbreakable())) return "&f" + kind + " &a100%";
        int dmg = meta instanceof Damageable d ? d.getDamage() : 0;
        int pct = (int) Math.round(100.0 * (max - dmg) / max);
        pct = Math.max(0, Math.min(100, pct));
        String c = pct > 60 ? "&a" : pct > 25 ? "&e" : "&c";
        return "&f" + kind + " " + c + pct + "%";
    }

    private static String kindName(Material m) {
        String n = m.name();
        if (n.equals("ELYTRA")) return "Elytra";
        if (n.equals("TURTLE_HELMET")) return "Schildpad";
        if (n.startsWith("LEATHER_")) return "Leer";
        if (n.startsWith("CHAINMAIL_")) return "Malien";
        if (n.startsWith("COPPER_")) return "Koper";
        if (n.startsWith("IRON_")) return "IJzer";
        if (n.startsWith("GOLDEN_")) return "Goud";
        if (n.startsWith("DIAMOND_")) return "Diamant";
        if (n.startsWith("NETHERITE_")) return "Netherite";
        if (n.endsWith("_HEAD") || n.endsWith("_SKULL") || n.equals("CARVED_PUMPKIN")) return "Kop";
        String s = n.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------ events en commando

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> { if (e.getPlayer().isOnline()) show(e.getPlayer()); }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("herladen")) {
            if (!sender.hasPermission("statusbord.admin")) { sender.sendMessage(Component.text("Daar heb je geen rechten voor.")); return true; }
            loadSettings();
            hookEconomy();
            hookPapi();
            for (Player p : Bukkit.getOnlinePlayers()) hide(p);
            updateAll();
            startTask();
            sender.sendMessage(LEGACY.deserialize(getConfig().getString("tekst.herladen", "&aStatusbord is herladen.")));
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen in het spel. Beheer: /statusbord herladen"); return true; }
        if (hidden.remove(p.getUniqueId())) {
            saveHidden();
            show(p);
            p.sendMessage(LEGACY.deserialize(getConfig().getString("tekst.aan", "&aJe scorebord staat aan.")));
        } else {
            hidden.add(p.getUniqueId());
            saveHidden();
            hide(p);
            p.sendMessage(LEGACY.deserialize(getConfig().getString("tekst.uit", "&eJe scorebord staat uit.")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("statusbord.admin") && "herladen".startsWith(args[0].toLowerCase(Locale.ROOT))) return List.of("herladen");
        return List.of();
    }
}
