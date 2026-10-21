package meldly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Meldly - Minecraft en Discord: chat in twee richtingen, logs per categorie in eigen kanalen,
 * live status, accounts koppelen, voor meerdere Discord-servers tegelijk. Vervangt DiscordSRV.
 */
public final class Meldly extends JavaPlugin implements TabCompleter {
    record Snapshot(List<String> namen, int max, double tps, String versie) {}

    private Opslag opslag;
    private Koppelingen kop;
    private Bot bot;
    private Uitvoer uitvoer;
    private Status status;
    private LogVolger logs;
    private WebApi web;
    private volatile Snapshot snap = new Snapshot(List.of(), 0, 20.0, "");
    private long gestart;
    private long laatsteTpsAlarm = 0;
    private boolean serverIsKlaar = false;

    Opslag opslag() { return opslag; }
    Bot bot() { return bot; }
    Uitvoer uitvoer() { return uitvoer; }
    Status status() { return status; }
    long gestart() { return gestart; }
    Snapshot snapshot() { return snap; }
    Koppelingen koppelingen() { return kop; }

    @Override
    public void onEnable() {
        gestart = System.currentTimeMillis();
        saveDefaultConfig();
        reloadConfig();
        if (getConfig().getString("web.token", "").isBlank()) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            getConfig().set("web.token", HexFormat.of().formatHex(b));
            saveConfig();
        }
        opslag = new Opslag(getDataFolder(), getLogger());
        opslag.laad();
        kop = new Koppelingen(getDataFolder(), getLogger());
        kop.laad();
        importeerDiscordSrv();
        schakelDiscordSrvUit();

        bot = new Bot(this, opslag, kop);
        uitvoer = new Uitvoer(this, bot);
        status = new Status(this, bot);
        logs = new LogVolger(this);
        logs.laad(false);

        getServer().getPluginManager().registerEvents(new Gebeurtenissen(this), this);
        PluginCommand cmd = getCommand("discord");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
            // /discord van ons, ook als DiscordSRV nog in de map staat
            try { getServer().getCommandMap().getKnownCommands().put("discord", cmd); } catch (Exception ignored) { }
        }

        web = new WebApi(this);
        try { web.start(); } catch (Exception e) { getLogger().severe("De verbinding voor de website kon niet starten: " + e.getMessage()); }

        maakSnapshot();
        bot.start();

        var s = getServer().getScheduler();
        s.runTaskTimer(this, this::maakSnapshot, 100L, 100L);
        long elke = Math.max(20L, getConfig().getLong("logs.verzend-elke-seconden", 2) * 20L);
        s.runTaskTimerAsynchronously(this, () -> uitvoer.verstuur(), elke, elke);
        s.runTaskTimerAsynchronously(this, () -> logs.lees(), 60L, 60L);
        long statusElke = Math.max(30L, getConfig().getLong("status.elke-seconden", 60)) * 20L;
        s.runTaskTimerAsynchronously(this, () -> { if (bot.verbonden()) status.werkBij(snap, true); }, 200L, statusElke);
        s.runTaskTimer(this, this::controleerTps, 1200L, 600L);
        getLogger().info("Meldly " + getPluginMeta().getVersion() + " gestart. Instellen: website > Beheer > Discord.");
    }

    @Override
    public void onDisable() {
        if (uitvoer == null) return;
        try {
            uitvoer.meld(Categorie.STATUS, new Uitvoer.Regel("🔴 **De server stopt.**", null, null, 0xE0503C));
            logs.lees();
            uitvoer.verstuur();
            status.werkBij(new Snapshot(List.of(), snap.max(), 0, snap.versie()), false);
        } catch (Exception e) {
            getLogger().fine("Afsluiten: " + e.getMessage());
        }
        bot.afsluiten();
        if (web != null) web.stop();
    }

    /** Aangeroepen als de server helemaal opgestart is. */
    void serverKlaar() {
        if (serverIsKlaar) return;
        serverIsKlaar = true;
        logs.laad(true);
        uitvoer.meld(Categorie.STATUS, new Uitvoer.Regel("🟢 **De server is gestart** en klaar voor spelers.", null, null, 0x7DDC4A));
        if (bot.verbonden()) status.nu();
    }

    void herlaad() {
        reloadConfig();
        opslag.laad();
        kop.laad();
        logs.laad(serverIsKlaar);
        bot.start();
    }

    // ------------------------------------------------------------------ hulp
    private boolean onzichtbaar(Player p) { return p.getMetadata("vanished").stream().anyMatch(m -> m.asBoolean()); }

    private void maakSnapshot() {
        List<String> namen = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (!onzichtbaar(p)) namen.add(p.getName());
        namen.sort(String.CASE_INSENSITIVE_ORDER);
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        snap = new Snapshot(List.copyOf(namen), Bukkit.getMaxPlayers(), tps, Bukkit.getMinecraftVersion());
    }

    void snapshotLater() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            maakSnapshot();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> bot.updateAanwezigheid(snap));
        }, 2L);
    }

    private void controleerTps() {
        double grens = getConfig().getDouble("fouten.tps-alarm", 15.0);
        if (grens <= 0) return;
        double tps = Bukkit.getTPS()[0];
        long nu = System.currentTimeMillis();
        if (tps < grens && nu - laatsteTpsAlarm > 10 * 60_000L && nu - gestart > 120_000L) {
            laatsteTpsAlarm = nu;
            uitvoer.meld(Categorie.FOUTEN, new Uitvoer.Regel("🐢 **TPS laag:** " + String.format(Locale.ROOT, "%.1f", tps)
                    + " (grens " + grens + ") met " + Bukkit.getOnlinePlayers().size() + " spelers online"));
        }
    }

    String hoofd(String naam, UUID u) {
        String f = getConfig().getString("uiterlijk.hoofd-url", "https://mc-heads.net/avatar/{naam}/64");
        return Bot.hoofdUrl(f, naam == null ? "Steve" : naam, u);
    }

    /** Prefix uit LuckPerms (zonder kleurcodes), bijv. [VIP]. */
    String prefix(Player p) {
        if (!getConfig().getBoolean("chat.rang-voor-naam", true)) return "";
        try {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return "";
            net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
            String pre = lp.getPlayerAdapter(Player.class).getMetaData(p).getPrefix();
            if (pre == null) return "";
            return pre.replaceAll("(?i)[&§]([0-9a-fk-orx]|#[0-9a-f]{6})", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // --------------------------------------------------------- DiscordSRV
    /** Eenmalig: bot-token, chatkanaal en koppelingen van DiscordSRV overnemen. */
    private void importeerDiscordSrv() {
        if (getConfig().getBoolean("import.discordsrv-gedaan", false)) return;
        File map = new File(getDataFolder().getParentFile(), "DiscordSRV");
        File cfg = new File(map, "config.yml");
        if (!cfg.exists()) return;
        try {
            String tekst = Files.readString(cfg.toPath(), StandardCharsets.UTF_8);
            if (getConfig().getString("bot.bot-token", "").isBlank()) {
                Matcher m = Pattern.compile("(?m)^BotToken:\\s*\"?([A-Za-z0-9_.\\-]{30,})\"?\\s*$").matcher(tekst);
                if (m.find()) { getConfig().set("bot.bot-token", m.group(1)); getLogger().info("Bot-token van DiscordSRV overgenomen."); }
            }
            Matcher k = Pattern.compile("(?m)^Channels:\\s*\\{\\s*\"global\"\\s*:\\s*\"(\\d{15,21})\"").matcher(tekst);
            if (k.find()) getConfig().set("import.discordsrv-chatkanaal", k.group(1));
            File aof = new File(map, "accounts.aof");
            int n = 0;
            if (aof.exists()) {
                for (String r : Files.readAllLines(aof.toPath(), StandardCharsets.UTF_8)) {
                    String[] d = r.trim().split("\\s+");
                    if (d.length < 2 || !d[0].matches("\\d{15,21}")) continue;
                    try {
                        UUID u = UUID.fromString(d[1]);
                        if (kop.vanSpeler(u) != null) continue;
                        kop.koppel(u, Bot.spelerNaam(u), d[0], "");
                        n++;
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            if (n > 0) getLogger().info(n + " gekoppelde account(s) van DiscordSRV overgenomen.");
            getConfig().set("import.discordsrv-gedaan", true);
            saveConfig();
        } catch (Exception e) {
            getLogger().warning("Kon de instellingen van DiscordSRV niet overnemen: " + e.getMessage());
        }
    }

    boolean discordSrvAanwezig() { return getServer().getPluginManager().getPlugin("DiscordSRV") != null; }

    private void schakelDiscordSrvUit() {
        Plugin d = getServer().getPluginManager().getPlugin("DiscordSRV");
        if (d == null || !getConfig().getBoolean("import.discordsrv-uitschakelen", true)) return;
        if (d.isEnabled()) getServer().getPluginManager().disablePlugin(d);
        getLogger().warning("DiscordSRV is uitgeschakeld: Meldly vervangt het. Haal DiscordSRV-*.jar uit de map plugins (de map DiscordSRV mag blijven als reservekopie).");
    }

    // ------------------------------------------------------------- commando
    private static Component kop(String t) { return Component.text("[Discord] ", NamedTextColor.BLUE).append(Component.text(t, NamedTextColor.GRAY)); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "koppel", "link" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Alleen in het spel."); return true; }
                if (a.length >= 2) {
                    String naam = bot.koppelVanuitSpel(p, a[1]);
                    if (naam == null) p.sendMessage(kop("Die code klopt niet of is verlopen. Typ in Discord /koppel voor een nieuwe code.").color(NamedTextColor.RED));
                    else p.sendMessage(kop("Gelukt! Je bent gekoppeld aan Discord-account " + naam + ".").color(NamedTextColor.GREEN));
                    return true;
                }
                Koppelingen.Koppeling k = kop.vanSpeler(p.getUniqueId());
                if (k != null) {
                    p.sendMessage(kop("Je bent al gekoppeld aan Discord-account " + (k.discordNaam.isBlank() ? k.discordId : k.discordNaam) + ". Weghalen: /discord ontkoppel"));
                    return true;
                }
                String code = kop.codeVoorSpeler(p.getUniqueId(), p.getName());
                p.sendMessage(kop("Je koppelcode is ").append(Component.text(code, NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.copyToClipboard("/koppel code:" + code))
                        .hoverEvent(HoverEvent.showText(Component.text("Klik om te kopiëren")))));
                p.sendMessage(kop("Typ in Discord: ").append(Component.text("/koppel code:" + code, NamedTextColor.WHITE))
                        .append(Component.text(" (10 minuten geldig)", NamedTextColor.DARK_GRAY)));
                return true;
            }
            case "ontkoppel", "unlink" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Alleen in het spel."); return true; }
                Koppelingen.Koppeling k = bot.ontkoppel(p.getUniqueId(), p.getName());
                p.sendMessage(kop(k == null ? "Je account is niet gekoppeld aan Discord." : "De koppeling met Discord is weggehaald."));
                return true;
            }
            case "herladen", "reload" -> {
                if (!s.hasPermission("meldly.admin")) { s.sendMessage(kop("Daar heb je geen rechten voor.").color(NamedTextColor.RED)); return true; }
                herlaad();
                s.sendMessage(kop("Instellingen herladen, de bot verbindt opnieuw."));
                return true;
            }
            case "status" -> {
                if (!s.hasPermission("meldly.admin")) { s.sendMessage(kop("Daar heb je geen rechten voor.").color(NamedTextColor.RED)); return true; }
                s.sendMessage(kop("Bot: " + bot.status + (bot.botNaam.isEmpty() ? "" : " (" + bot.botNaam + ")") + ", ping " + bot.ping() + " ms"));
                for (Opslag.GuildInst gi : opslag.alle()) {
                    List<String> aan = new ArrayList<>();
                    for (Categorie cat : Categorie.values()) if (gi.cat(cat).aan) aan.add(cat.sleutel);
                    s.sendMessage(Component.text(" - " + gi.naam + (bot.guilds.containsKey(gi.id) ? "" : " (bot zit er niet meer in)") + ": "
                            + (aan.isEmpty() ? "niets aan" : String.join(", ", aan)) + (gi.koppelAan ? ", koppelen" : ""), NamedTextColor.GRAY));
                }
                s.sendMessage(Component.text(" Gekoppelde accounts: " + kop.aantal(), NamedTextColor.GRAY));
                return true;
            }
            default -> {
                if (s instanceof Player p) {
                    Koppelingen.Koppeling k = kop.vanSpeler(p.getUniqueId());
                    p.sendMessage(kop(k == null ? "Je account is nog niet gekoppeld. Koppelen: /discord koppel" :
                            "Gekoppeld aan " + (k.discordNaam.isBlank() ? "Discord-id " + k.discordId : k.discordNaam) + ". Weghalen: /discord ontkoppel"));
                } else s.sendMessage("/discord status | herladen");
                String link = getConfig().getString("algemeen.uitnodiging", "");
                if (!link.isBlank()) s.sendMessage(kop("Kom ook op onze Discord: ").append(Component.text(link, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(link))));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length != 1) return List.of();
        List<String> opties = new ArrayList<>(List.of("koppel", "ontkoppel"));
        if (s.hasPermission("meldly.admin")) { opties.add("status"); opties.add("herladen"); }
        return opties.stream().filter(o -> o.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
    }
}
