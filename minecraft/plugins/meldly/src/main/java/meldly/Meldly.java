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
    private Teams teams;
    private Spraak spraak;
    private final Eilanden eilanden = new Eilanden();
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
    Teams teams() { return teams; }

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
        teams = new Teams(getDataFolder(), getLogger());
        teams.laad();
        importeerDiscordSrv();
        importeerDiscordSrvSpraak();
        schakelDiscordSrvUit();

        bot = new Bot(this, opslag, kop);
        uitvoer = new Uitvoer(this, bot);
        status = new Status(this, bot);
        spraak = new Spraak(this, bot);
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
        long groepElke = Math.max(10L, getConfig().getLong("groepen.elke-ticks", 20));
        s.runTaskTimer(this, this::spraakTik, 100L, groepElke);
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
        teams.laad();
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

    /** Momentopname voor de spraakgroepen (hoofdthread), daarna verder op de achtergrond. */
    private void spraakTik() {
        if (!bot.verbonden() || !spraak.ergensAan()) return;
        List<Spraak.Speler> lijst = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Koppelingen.Koppeling k = kop.vanSpeler(p.getUniqueId());
            if (k == null || onzichtbaar(p)) continue;
            Teams.Team t = teams.teamVan(p.getUniqueId());
            Eilanden.Eiland e = eilanden.van(p);
            var l = p.getLocation();
            lijst.add(new Spraak.Speler(p.getUniqueId(), p.getName(), k.discordId, p.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                    t == null ? null : t.naam, e == null ? null : e.sleutel(), e == null ? null : e.naam()));
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> spraak.tik(lijst));
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

    /** Eenmalig: het lobby-spraakkanaal en de afstanden uit DiscordSRV/voice.yml overnemen. */
    private void importeerDiscordSrvSpraak() {
        if (getConfig().getBoolean("import.discordsrv-spraak-gedaan", false)) return;
        File f = new File(new File(getDataFolder().getParentFile(), "DiscordSRV"), "voice.yml");
        if (!f.exists()) return;
        try {
            String t = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Matcher aan = Pattern.compile("(?m)^Voice enabled:\\s*(true|false)").matcher(t);
            Matcher lobby = Pattern.compile("(?m)^Lobby channel:\\s*\"?(\\d{15,21})").matcher(t);
            if (lobby.find()) {
                getConfig().set("import.discordsrv-lobby", lobby.group(1));
                getConfig().set("import.discordsrv-spraak-aan", !aan.find() || aan.group(1).equals("true"));
            }
            Matcher zicht = Pattern.compile("(?m)^\\s*Channels are visible:\\s*(true|false)").matcher(t);
            if (zicht.find()) getConfig().set("import.discordsrv-spraak-zichtbaar", zicht.group(1).equals("true"));
            Matcher h = Pattern.compile("(?m)^\\s*Horizontal Strength:\\s*(\\d+)").matcher(t);
            Matcher v = Pattern.compile("(?m)^\\s*Vertical Strength:\\s*(\\d+)").matcher(t);
            Matcher fo = Pattern.compile("(?m)^\\s*Falloff:\\s*(\\d+)").matcher(t);
            if (h.find()) getConfig().set("groepen.afstand.horizontaal", Integer.parseInt(h.group(1)));
            if (v.find()) getConfig().set("groepen.afstand.verticaal", Integer.parseInt(v.group(1)));
            if (fo.find()) getConfig().set("groepen.afstand.speling", Integer.parseInt(fo.group(1)));
            getConfig().set("import.discordsrv-spraak-gedaan", true);
            saveConfig();
            getLogger().info("Spraakinstellingen van DiscordSRV overgenomen.");
        } catch (Exception e) {
            getLogger().warning("Kon de spraakinstellingen van DiscordSRV niet overnemen: " + e.getMessage());
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
            case "team", "party" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Alleen in het spel."); return true; }
                team(p, a);
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

    private void team(Player p, String[] a) {
        String sub = a.length < 2 ? "" : a[1].toLowerCase(Locale.ROOT);
        int max = getConfig().getInt("groepen.max-teamleden", 10);
        switch (sub) {
            case "maak", "create" -> {
                if (a.length < 3) { p.sendMessage(kop("Gebruik: /discord team maak <naam>")); return; }
                String f = teams.maak(p.getUniqueId(), a[2]);
                p.sendMessage(f != null ? kop(f).color(NamedTextColor.RED)
                        : kop("Team " + a[2] + " gemaakt. Nodig spelers uit met /discord team uitnodigen <speler>.").color(NamedTextColor.GREEN));
            }
            case "uitnodigen", "invite" -> {
                if (a.length < 3) { p.sendMessage(kop("Gebruik: /discord team uitnodigen <speler>")); return; }
                Player wie = Bukkit.getPlayerExact(a[2]);
                if (wie == null) { p.sendMessage(kop("Die speler is niet online.").color(NamedTextColor.RED)); return; }
                if (wie.equals(p)) { p.sendMessage(kop("Jezelf uitnodigen hoeft niet.").color(NamedTextColor.RED)); return; }
                String f = teams.nodigUit(p.getUniqueId(), wie.getUniqueId(), max);
                if (f != null) { p.sendMessage(kop(f).color(NamedTextColor.RED)); return; }
                Teams.Team t = teams.teamVan(p.getUniqueId());
                p.sendMessage(kop(wie.getName() + " is uitgenodigd voor team " + t.naam + ".").color(NamedTextColor.GREEN));
                wie.sendMessage(kop(p.getName() + " nodigt je uit voor team " + t.naam + ". ")
                        .append(Component.text("[Accepteren]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/discord team accepteer"))
                                .hoverEvent(HoverEvent.showText(Component.text("Klik om in het team te gaan"))))
                        .append(Component.text(" "))
                        .append(Component.text("[Weigeren]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/discord team weiger"))));
            }
            case "accepteer", "accept", "ja" -> {
                Teams.Uitnodiging u = teams.uitnodiging(p.getUniqueId());
                String naam = teams.accepteer(p.getUniqueId(), max);
                if (naam == null) { p.sendMessage(kop("Je hebt geen (geldige) uitnodiging.").color(NamedTextColor.RED)); return; }
                Teams.Team t = teams.team(naam);
                for (UUID l : t.leden) {
                    Player q = Bukkit.getPlayer(l);
                    if (q != null) q.sendMessage(kop(p.getName() + " zit nu in team " + naam + ".").color(NamedTextColor.GREEN));
                }
                if (u != null) getLogger().info(p.getName() + " zit nu in team " + naam + ".");
            }
            case "weiger", "deny", "nee" -> {
                teams.weiger(p.getUniqueId());
                p.sendMessage(kop("Uitnodiging geweigerd."));
            }
            case "verlaat", "leave" -> {
                Teams.Team t = teams.verlaat(p.getUniqueId());
                if (t == null) { p.sendMessage(kop("Je zit niet in een team.")); return; }
                p.sendMessage(kop("Je hebt team " + t.naam + " verlaten."));
                for (UUID l : t.leden) {
                    Player q = Bukkit.getPlayer(l);
                    if (q != null) q.sendMessage(kop(p.getName() + " heeft het team verlaten." + (t.eigenaar.equals(l) ? " Jij bent nu de eigenaar." : "")));
                }
            }
            case "verwijder", "opheffen", "disband" -> {
                Teams.Team t = teams.teamVan(p.getUniqueId());
                if (t == null) { p.sendMessage(kop("Je zit niet in een team.")); return; }
                if (!t.eigenaar.equals(p.getUniqueId()) && !p.hasPermission("meldly.admin")) { p.sendMessage(kop("Alleen de eigenaar kan het team opheffen.").color(NamedTextColor.RED)); return; }
                List<UUID> leden = new ArrayList<>(t.leden);
                teams.hefOp(t.naam);
                for (UUID l : leden) {
                    Player q = Bukkit.getPlayer(l);
                    if (q != null) q.sendMessage(kop("Team " + t.naam + " is opgeheven."));
                }
            }
            case "eruit", "kick" -> {
                Teams.Team t = teams.teamVan(p.getUniqueId());
                if (t == null || !t.eigenaar.equals(p.getUniqueId())) { p.sendMessage(kop("Alleen de eigenaar van een team kan iemand eruit zetten.").color(NamedTextColor.RED)); return; }
                if (a.length < 3) { p.sendMessage(kop("Gebruik: /discord team eruit <speler>")); return; }
                UUID wie = null;
                for (UUID l : t.leden) if (a[2].equalsIgnoreCase(Bot.spelerNaam(l))) wie = l;
                if (wie == null || wie.equals(p.getUniqueId())) { p.sendMessage(kop("Die speler zit niet in je team.").color(NamedTextColor.RED)); return; }
                teams.verlaat(wie);
                p.sendMessage(kop(a[2] + " zit niet meer in je team."));
                Player q = Bukkit.getPlayer(wie);
                if (q != null) q.sendMessage(kop("Je bent uit team " + t.naam + " gezet."));
            }
            default -> {
                Teams.Team t = teams.teamVan(p.getUniqueId());
                if (t != null) {
                    List<String> namen = new ArrayList<>();
                    for (UUID l : t.leden) namen.add(Bot.spelerNaam(l) + (l.equals(t.eigenaar) ? " (eigenaar)" : ""));
                    p.sendMessage(kop("Team " + t.naam + ": " + String.join(", ", namen)));
                } else p.sendMessage(kop("Je zit niet in een team."));
                p.sendMessage(Component.text(" /discord team maak <naam> | uitnodigen <speler> | accepteer | verlaat | eruit <speler> | opheffen", NamedTextColor.DARK_GRAY));
                p.sendMessage(Component.text(" Wie in hetzelfde team zit, praat in Discord samen (ga in het lobby-spraakkanaal zitten).", NamedTextColor.DARK_GRAY));
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 2 && a[0].equalsIgnoreCase("team"))
            return List.of("maak", "uitnodigen", "accepteer", "weiger", "verlaat", "eruit", "opheffen").stream().filter(o -> o.startsWith(a[1].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 3 && a[0].equalsIgnoreCase("team") && (a[1].equalsIgnoreCase("uitnodigen") || a[1].equalsIgnoreCase("eruit")))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(a[2].toLowerCase(Locale.ROOT))).toList();
        if (a.length != 1) return List.of();
        List<String> opties = new ArrayList<>(List.of("koppel", "ontkoppel", "team"));
        if (s.hasPermission("meldly.admin")) { opties.add("status"); opties.add("herladen"); }
        return opties.stream().filter(o -> o.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
    }
}
