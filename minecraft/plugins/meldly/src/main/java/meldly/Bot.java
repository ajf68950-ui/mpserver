package meldly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** De Discord-bot: servers (guilds) bijhouden, kanalen maken, chat in twee richtingen, koppelen en slash-commando's. */
final class Bot {
    static final int COMMANDO_VERSIE = 1;
    static final long PERM_VIEW = 1L << 10, PERM_SEND = 1L << 11, PERM_EMBED = 1L << 14, PERM_FILES = 1L << 15,
            PERM_HISTORY = 1L << 16, PERM_CHANNELS = 1L << 4, PERM_MANAGE_MSG = 1L << 13, PERM_ROLES = 1L << 28,
            PERM_WEBHOOKS = 1L << 29, PERM_NICKS = 1L << 27;
    static final long UITNODIG_RECHTEN = PERM_VIEW | PERM_SEND | PERM_EMBED | PERM_FILES | PERM_HISTORY | PERM_CHANNELS
            | PERM_MANAGE_MSG | PERM_ROLES | PERM_WEBHOOKS | PERM_NICKS;

    record Kanaal(String id, String naam, int type, String parent, int positie) {}
    record Rol(String id, String naam, int positie, boolean beheerd, int kleur) {}
    static final class Guild {
        final String id;
        volatile String naam = "", icoon = null;
        volatile int leden = 0;
        final Map<String, Kanaal> kanalen = new ConcurrentHashMap<>();
        final Map<String, Rol> rollen = new ConcurrentHashMap<>();
        Guild(String id) { this.id = id; }
    }
    record Fout(long tijd, String tekst) {}

    private static final Pattern GEBRUIKER = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern ROL = Pattern.compile("<@&(\\d+)>");
    private static final Pattern KANAAL = Pattern.compile("<#(\\d+)>");
    private static final Pattern EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");

    final Meldly plugin;
    final Opslag opslag;
    final Koppelingen kop;
    final Map<String, Guild> guilds = new ConcurrentHashMap<>();
    private final Deque<Fout> fouten = new ArrayDeque<>();
    private final Map<String, Long> laatsteFoutTekst = new ConcurrentHashMap<>();

    volatile Rest rest;
    private volatile Gateway gateway;
    volatile String status = "uit";
    volatile String botId = "", botNaam = "", appId = "", botAvatar = "";
    private volatile boolean klaar = false;

    Bot(Meldly plugin, Opslag opslag, Koppelingen kop) {
        this.plugin = plugin;
        this.opslag = opslag;
        this.kop = kop;
    }

    String token() { return plugin.getConfig().getString("bot.bot-token", "").trim(); }
    String apiBasis() { return plugin.getConfig().getString("bot.api-url", "https://discord.com/api/v10"); }
    boolean verbonden() { return "verbonden".equals(status); }
    int ping() { Gateway g = gateway; return g == null ? -1 : g.ping; }

    // ------------------------------------------------------------- starten
    synchronized void start() {
        stop();
        guilds.clear();
        klaar = false;
        String t = token();
        if (t.isEmpty()) {
            status = "geen-token";
            plugin.getLogger().warning("Er is nog geen bot-token. Zet het in de website (Beheer > Discord) of in plugins/Meldly/config.yml (bot.bot-token).");
            return;
        }
        status = "verbinden";
        Rest r = new Rest(plugin.getLogger(), this::apiBasis, this::token);
        rest = r;
        Thread th = new Thread(() -> opstarten(r, t), "Meldly-start");
        th.setDaemon(true);
        th.start();
    }

    private void opstarten(Rest r, String t) {
        try {
            JsonObject ik = r.get("/users/@me").get(20, TimeUnit.SECONDS).getAsJsonObject();
            botId = ik.get("id").getAsString();
            botNaam = ik.get("username").getAsString();
            botAvatar = ik.has("avatar") && !ik.get("avatar").isJsonNull() ? ik.get("avatar").getAsString() : "";
            try {
                JsonObject app = r.get("/oauth2/applications/@me").get(20, TimeUnit.SECONDS).getAsJsonObject();
                appId = app.get("id").getAsString();
            } catch (Exception e) { appId = botId; }
            String url = plugin.getConfig().getString("bot.gateway-url", "").trim();
            if (url.isEmpty()) url = r.get("/gateway/bot").get(20, TimeUnit.SECONDS).getAsJsonObject().get("url").getAsString();
            if (rest != r) return;
            Gateway g = new Gateway(plugin.getLogger(), r.http(), t, url, this::opGebeurtenis, s -> {
                status = s;
                if (s.startsWith("fout")) fout(s.substring(Math.min(s.length(), 6)));
            });
            g.zetAanwezigheid(aanwezigheid(plugin.snapshot()));
            gateway = g;
            g.start();
        } catch (Exception e) {
            String m = Rest.melding(e);
            if (Rest.code(e) == 0 && m.contains("401")) m = "Het bot-token klopt niet.";
            if (e.getCause() instanceof Rest.DiscordFout d && d.status == 401) m = "Het bot-token klopt niet. Maak in de Discord Developer Portal een nieuw token (Bot > Reset Token) en zet het in Beheer > Discord.";
            status = "fout: " + m;
            fout(m);
            plugin.getLogger().severe("Discord: " + m);
        }
    }

    synchronized void stop() {
        Gateway g = gateway;
        gateway = null;
        if (g != null) g.stop();
        Rest r = rest;
        rest = null;
        if (r != null) r.stop(4000);
        status = "uit";
    }

    /** Wacht tot de REST-wachtrij leeg is (bij afsluiten). */
    void afsluiten() {
        Gateway g = gateway;
        gateway = null;
        if (g != null) g.stop();
        Rest r = rest;
        if (r != null) r.stop(5000);
        rest = null;
        status = "uit";
    }

    void fout(String tekst) {
        if (tekst == null || tekst.isBlank()) return;
        long nu = System.currentTimeMillis();
        Long vorige = laatsteFoutTekst.get(tekst);
        if (vorige != null && nu - vorige < 60_000) return;
        laatsteFoutTekst.put(tekst, nu);
        if (laatsteFoutTekst.size() > 200) laatsteFoutTekst.clear();
        synchronized (fouten) {
            fouten.addFirst(new Fout(nu, tekst));
            while (fouten.size() > 30) fouten.removeLast();
        }
    }

    List<Fout> fouten() { synchronized (fouten) { return new ArrayList<>(fouten); } }

    String uitnodigLink() {
        String id = appId.isEmpty() ? botId : appId;
        if (id.isEmpty()) return "";
        return "https://discord.com/oauth2/authorize?client_id=" + id + "&scope=bot%20applications.commands&permissions=" + UITNODIG_RECHTEN;
    }

    JsonObject aanwezigheid(Meldly.Snapshot s) {
        JsonObject d = new JsonObject();
        d.add("since", com.google.gson.JsonNull.INSTANCE);
        d.addProperty("status", "online");
        d.addProperty("afk", false);
        JsonArray act = new JsonArray();
        String tekst = plugin.getConfig().getString("status.bot-tekst", "{spelers} van de {max} online")
                .replace("{spelers}", String.valueOf(s.namen().size())).replace("{max}", String.valueOf(s.max()));
        if (!tekst.isBlank()) {
            JsonObject a = new JsonObject();
            a.addProperty("name", "Custom Status");
            a.addProperty("state", tekst);
            a.addProperty("type", 4);
            act.add(a);
        }
        d.add("activities", act);
        return d;
    }

    private String laatsteAanwezigheid = "";
    void updateAanwezigheid(Meldly.Snapshot s) {
        Gateway g = gateway;
        if (g == null || !verbonden()) return;
        JsonObject a = aanwezigheid(s);
        String k = a.toString();
        if (k.equals(laatsteAanwezigheid)) return;
        laatsteAanwezigheid = k;
        g.zetAanwezigheid(a);
    }

    // ---------------------------------------------------------- gebeurtenissen
    private void opGebeurtenis(String type, JsonObject d) {
        switch (type) {
            case "READY" -> {
                JsonObject u = d.getAsJsonObject("user");
                botId = u.get("id").getAsString();
                botNaam = u.get("username").getAsString();
                if (d.has("application")) appId = d.getAsJsonObject("application").get("id").getAsString();
                if (!klaar) {
                    klaar = true;
                    plugin.getLogger().info("Verbonden met Discord als " + botNaam + ".");
                }
            }
            case "GUILD_CREATE" -> guildBinnen(d);
            case "GUILD_UPDATE" -> {
                Guild g = guilds.get(str(d, "id"));
                if (g != null) { g.naam = str(d, "name"); g.icoon = strOfNull(d, "icon"); naamOpslaan(g); }
            }
            case "GUILD_DELETE" -> {
                String id = str(d, "id");
                boolean storing = d.has("unavailable") && d.get("unavailable").getAsBoolean();
                Guild g = guilds.remove(id);
                if (!storing && g != null) plugin.getLogger().info("De bot is uit Discord-server " + g.naam + " gehaald.");
            }
            case "CHANNEL_CREATE", "CHANNEL_UPDATE" -> {
                Guild g = guilds.get(str(d, "guild_id"));
                if (g != null) { Kanaal k = kanaal(d); g.kanalen.put(k.id(), k); }
            }
            case "CHANNEL_DELETE" -> kanaalWeg(str(d, "guild_id"), str(d, "id"));
            case "GUILD_ROLE_CREATE", "GUILD_ROLE_UPDATE" -> {
                Guild g = guilds.get(str(d, "guild_id"));
                if (g != null && d.has("role")) { Rol r = rol(d.getAsJsonObject("role")); g.rollen.put(r.id(), r); }
            }
            case "GUILD_ROLE_DELETE" -> {
                String gid = str(d, "guild_id"), rid = str(d, "role_id");
                Guild g = guilds.get(gid);
                if (g != null) g.rollen.remove(rid);
                Opslag.GuildInst gi = opslag.bestaand(gid);
                if (gi != null && rid.equals(gi.koppelRol)) { gi.koppelRol = ""; opslag.bewaar(); }
            }
            case "MESSAGE_CREATE" -> berichtBinnen(d);
            case "INTERACTION_CREATE" -> interactie(d);
            default -> { }
        }
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static String strOfNull(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }

    private static Kanaal kanaal(JsonObject c) {
        return new Kanaal(str(c, "id"), str(c, "name"), c.has("type") ? c.get("type").getAsInt() : 0, strOfNull(c, "parent_id"),
                c.has("position") ? c.get("position").getAsInt() : 0);
    }

    private static Rol rol(JsonObject r) {
        return new Rol(str(r, "id"), str(r, "name"), r.has("position") ? r.get("position").getAsInt() : 0,
                r.has("managed") && r.get("managed").getAsBoolean(), r.has("color") ? r.get("color").getAsInt() : 0);
    }

    private void naamOpslaan(Guild g) {
        Opslag.GuildInst gi = opslag.guild(g.id);
        if (!g.naam.equals(gi.naam)) { gi.naam = g.naam; opslag.bewaar(); }
    }

    private void guildBinnen(JsonObject d) {
        if (d.has("unavailable") && d.get("unavailable").getAsBoolean()) return;
        Guild g = new Guild(str(d, "id"));
        g.naam = str(d, "name");
        g.icoon = strOfNull(d, "icon");
        g.leden = d.has("member_count") ? d.get("member_count").getAsInt() : 0;
        if (d.has("channels")) for (JsonElement e : d.getAsJsonArray("channels")) { Kanaal k = kanaal(e.getAsJsonObject()); g.kanalen.put(k.id(), k); }
        if (d.has("roles")) for (JsonElement e : d.getAsJsonArray("roles")) { Rol r = rol(e.getAsJsonObject()); g.rollen.put(r.id(), r); }
        guilds.put(g.id, g);
        boolean nieuw = opslag.bestaand(g.id) == null;
        Opslag.GuildInst gi = opslag.guild(g.id);
        gi.naam = g.naam;
        // overgenomen chatkanaal van DiscordSRV: chat meteen aan in die server, de rest kies je zelf
        String dsrv = plugin.getConfig().getString("import.discordsrv-chatkanaal", "");
        if (!dsrv.isEmpty() && g.kanalen.containsKey(dsrv)) {
            Opslag.CatInst c = gi.cat(Categorie.CHAT);
            if (c.kanaal.isEmpty()) { c.kanaal = dsrv; c.aan = true; }
            plugin.getConfig().set("import.discordsrv-chatkanaal", "");
            plugin.saveConfig();
            plugin.getLogger().info("Chatkanaal van DiscordSRV overgenomen in " + g.naam + ".");
        }
        // kanalen die verwijderd zijn terwijl de server uit stond
        boolean gewijzigd = false;
        for (Categorie c : Categorie.values()) {
            Opslag.CatInst ci = gi.cat(c);
            if (!ci.kanaal.isEmpty() && !g.kanalen.containsKey(ci.kanaal)) {
                plugin.getLogger().info("Kanaal voor " + c.label + " in " + g.naam + " bestaat niet meer; die categorie staat nu uit.");
                ci.kanaal = ""; ci.aan = false; ci.webhookId = ""; ci.webhookToken = "";
                gewijzigd = true;
            }
        }
        if (!gi.categorieKanaal.isEmpty() && !g.kanalen.containsKey(gi.categorieKanaal)) { gi.categorieKanaal = ""; gewijzigd = true; }
        if (!gi.koppelRol.isEmpty() && !g.rollen.containsKey(gi.koppelRol)) { gi.koppelRol = ""; gewijzigd = true; }
        opslag.bewaar();
        if (nieuw) plugin.getLogger().info("Nieuwe Discord-server: " + g.naam + ". Kies in Beheer > Discord wat daar moet komen.");
        if (gewijzigd) fout("In " + g.naam + " zijn kanalen verwijderd; die categorieën staan nu uit.");
        commandosBijwerken(g.id);
    }

    private void kanaalWeg(String gid, String kid) {
        Guild g = guilds.get(gid);
        if (g != null) g.kanalen.remove(kid);
        Opslag.GuildInst gi = opslag.bestaand(gid);
        if (gi == null) return;
        boolean gewijzigd = false;
        for (Categorie c : Categorie.values()) {
            Opslag.CatInst ci = gi.cat(c);
            if (kid.equals(ci.kanaal)) {
                ci.kanaal = ""; ci.aan = false; ci.webhookId = ""; ci.webhookToken = "";
                gewijzigd = true;
                plugin.getLogger().info("Kanaal voor " + c.label + " in " + gi.naam + " is in Discord verwijderd; die categorie staat nu uit.");
            }
        }
        if (kid.equals(gi.categorieKanaal)) { gi.categorieKanaal = ""; gewijzigd = true; }
        if (kid.equals(gi.cat(Categorie.STATUS).kanaal)) gi.statusBericht = "";
        if (gewijzigd) { opslag.bewaar(); commandosBijwerken(gid); }
    }

    // ------------------------------------------------------------- kanalen maken
    /** Zorgt dat de categorie een kanaal heeft. kanaalKeuze: "" of null = bestaand houden of nieuw maken, anders het id van een bestaand tekstkanaal. */
    CompletableFuture<String> zorgVoorKanaal(String gid, Categorie c, String kanaalKeuze) {
        Guild g = guilds.get(gid);
        Rest r = rest;
        if (g == null || r == null) return CompletableFuture.failedFuture(new IllegalStateException("De bot zit niet (meer) in deze Discord-server of is niet verbonden."));
        Opslag.GuildInst gi = opslag.guild(gid);
        Opslag.CatInst ci = gi.cat(c);
        if (kanaalKeuze != null && !kanaalKeuze.isBlank() && !kanaalKeuze.equals("nieuw")) {
            Kanaal k = g.kanalen.get(kanaalKeuze);
            if (k == null || (k.type() != 0 && k.type() != 5)) return CompletableFuture.failedFuture(new IllegalArgumentException("Dat kanaal bestaat niet of is geen tekstkanaal."));
            if (!kanaalKeuze.equals(ci.kanaal)) { ci.webhookId = ""; ci.webhookToken = ""; if (c == Categorie.STATUS) gi.statusBericht = ""; }
            ci.kanaal = kanaalKeuze;
            opslag.bewaar();
            return CompletableFuture.completedFuture(ci.kanaal);
        }
        if (!ci.kanaal.isEmpty() && g.kanalen.containsKey(ci.kanaal) && !"nieuw".equals(kanaalKeuze)) return CompletableFuture.completedFuture(ci.kanaal);
        return zorgVoorCategorie(g, gi).thenCompose(parent -> {
            JsonObject b = new JsonObject();
            b.addProperty("name", plugin.getConfig().getString("kanalen.namen." + c.sleutel, c.kanaalNaam));
            b.addProperty("type", 0);
            b.addProperty("topic", c.emoji + " " + c.uitleg + " (Meldly)");
            if (parent != null) b.addProperty("parent_id", parent);
            boolean prive = plugin.getConfig().getBoolean("kanalen.prive." + c.sleutel, c.prive);
            if (prive) b.add("permission_overwrites", priveRechten(gid));
            return r.post("/guilds/" + gid + "/channels", b);
        }).thenApply(j -> {
            Kanaal k = kanaal(j.getAsJsonObject());
            g.kanalen.put(k.id(), k);
            ci.kanaal = k.id();
            ci.webhookId = ""; ci.webhookToken = "";
            if (c == Categorie.STATUS) gi.statusBericht = "";
            opslag.bewaar();
            plugin.getLogger().info("Kanaal #" + k.naam() + " gemaakt in " + g.naam + " voor " + c.label + ".");
            return k.id();
        });
    }

    private JsonArray priveRechten(String gid) {
        JsonArray a = new JsonArray();
        JsonObject iedereen = new JsonObject();
        iedereen.addProperty("id", gid);
        iedereen.addProperty("type", 0);
        iedereen.addProperty("deny", String.valueOf(PERM_VIEW));
        iedereen.addProperty("allow", "0");
        a.add(iedereen);
        if (!botId.isEmpty()) {
            JsonObject bot = new JsonObject();
            bot.addProperty("id", botId);
            bot.addProperty("type", 1);
            bot.addProperty("allow", String.valueOf(PERM_VIEW | PERM_SEND | PERM_EMBED | PERM_HISTORY | PERM_WEBHOOKS | PERM_MANAGE_MSG));
            bot.addProperty("deny", "0");
            a.add(bot);
        }
        return a;
    }

    private CompletableFuture<String> zorgVoorCategorie(Guild g, Opslag.GuildInst gi) {
        if (!gi.categorieKanaal.isEmpty() && g.kanalen.containsKey(gi.categorieKanaal)) return CompletableFuture.completedFuture(gi.categorieKanaal);
        String naam = plugin.getConfig().getString("kanalen.categorie", "Minecraft");
        // bestaat er al een categorie met die naam (bijv. na opnieuw uitnodigen)? Dan die gebruiken.
        for (Kanaal k : g.kanalen.values()) {
            if (k.type() == 4 && k.naam().equalsIgnoreCase(naam)) { gi.categorieKanaal = k.id(); opslag.bewaar(); return CompletableFuture.completedFuture(k.id()); }
        }
        JsonObject b = new JsonObject();
        b.addProperty("name", naam);
        b.addProperty("type", 4);
        return rest.post("/guilds/" + g.id + "/channels", b).thenApply(j -> {
            Kanaal k = kanaal(j.getAsJsonObject());
            g.kanalen.put(k.id(), k);
            gi.categorieKanaal = k.id();
            opslag.bewaar();
            return k.id();
        });
    }

    /** Zet een categorie aan of uit in een Discord-server. */
    CompletableFuture<Void> zetCategorie(String gid, Categorie c, boolean aan, String kanaalKeuze) {
        Opslag.GuildInst gi = opslag.guild(gid);
        Opslag.CatInst ci = gi.cat(c);
        if (!aan) {
            ci.aan = false;
            opslag.bewaar();
            commandosBijwerken(gid);
            return CompletableFuture.completedFuture(null);
        }
        return zorgVoorKanaal(gid, c, kanaalKeuze).thenAccept(id -> {
            ci.aan = true;
            opslag.bewaar();
            commandosBijwerken(gid);
            if (c == Categorie.STATUS) plugin.status().nu();
        });
    }

    // --------------------------------------------------------- slash-commando's
    void commandosBijwerken(String gid) {
        Rest r = rest;
        Opslag.GuildInst gi = opslag.bestaand(gid);
        if (r == null || gi == null || appId.isEmpty() || !guilds.containsKey(gid)) return;
        boolean moet = gi.ietsAan();
        int doel = moet ? COMMANDO_VERSIE : 0;
        if (gi.commandoVersie == doel) return;
        JsonArray lijst = new JsonArray();
        if (moet) {
            lijst.add(commando("koppel", "Koppel je Minecraft-account aan Discord", true));
            lijst.add(commando("ontkoppel", "Haal de koppeling met je Minecraft-account weg", false));
            lijst.add(commando("spelers", "Wie is er nu online op de Minecraft-server?", false));
            lijst.add(commando("status", "Status van de Minecraft-server", false));
        }
        r.put("/applications/" + appId + "/guilds/" + gid + "/commands", lijst).whenComplete((j, e) -> {
            if (e != null) { fout("Slash-commando's in " + gi.naam + " niet gezet: " + Rest.melding(e)); return; }
            gi.commandoVersie = doel;
            opslag.bewaar();
        });
    }

    private static JsonObject commando(String naam, String uitleg, boolean metCode) {
        JsonObject o = new JsonObject();
        o.addProperty("name", naam);
        o.addProperty("description", uitleg);
        o.addProperty("type", 1);
        if (metCode) {
            JsonArray opts = new JsonArray();
            JsonObject code = new JsonObject();
            code.addProperty("type", 3);
            code.addProperty("name", "code");
            code.addProperty("description", "De code uit Minecraft (/discord koppel). Leeg laten = je krijgt een code voor in het spel.");
            code.addProperty("required", false);
            code.addProperty("max_length", 12);
            opts.add(code);
            o.add("options", opts);
        }
        return o;
    }

    private void interactie(JsonObject d) {
        if (!d.has("type") || d.get("type").getAsInt() != 2) return;
        JsonObject data = d.getAsJsonObject("data");
        String naam = str(data, "name");
        JsonObject user = d.has("member") ? d.getAsJsonObject("member").getAsJsonObject("user") : d.getAsJsonObject("user");
        if (user == null) return;
        String uid = str(user, "id");
        String dnaam = discordNaam(user, d.has("member") ? d.getAsJsonObject("member") : null);
        String antwoord;
        switch (naam) {
            case "koppel" -> {
                String code = null;
                if (data.has("options")) for (JsonElement e : data.getAsJsonArray("options")) {
                    JsonObject o = e.getAsJsonObject();
                    if ("code".equals(str(o, "name"))) code = str(o, "value");
                }
                antwoord = koppelVanuitDiscord(uid, dnaam, code);
            }
            case "ontkoppel" -> {
                Koppelingen.Koppeling k = kop.vanDiscord(uid);
                if (k == null) antwoord = "Je Discord-account is niet gekoppeld aan een Minecraft-account.";
                else {
                    ontkoppel(k.uuid, "Discord");
                    antwoord = "De koppeling met **" + md(k.mcNaam) + "** is weggehaald.";
                }
            }
            case "spelers" -> {
                Meldly.Snapshot s = plugin.snapshot();
                antwoord = s.namen().isEmpty() ? "Er is nu niemand online." :
                        "**" + s.namen().size() + " van de " + s.max() + " online:** " + md(String.join(", ", s.namen()));
            }
            case "status" -> {
                Meldly.Snapshot s = plugin.snapshot();
                antwoord = "🟢 **Online** • " + s.namen().size() + "/" + s.max() + " spelers • TPS " + String.format(Locale.ROOT, "%.1f", s.tps())
                        + " • aan sinds " + Status.duur(System.currentTimeMillis() - plugin.gestart());
                String adres = plugin.getConfig().getString("status.adres", "");
                if (!adres.isBlank()) antwoord += "\nAdres: `" + adres + "`";
            }
            default -> antwoord = "Onbekend commando.";
        }
        JsonObject cb = new JsonObject();
        cb.addProperty("type", 4);
        JsonObject cd = new JsonObject();
        cd.addProperty("content", antwoord);
        cd.addProperty("flags", 64);
        cd.add("allowed_mentions", geenMentions());
        cb.add("data", cd);
        Rest r = rest;
        if (r == null) return;
        // direct (niet achter in de wachtrij): Discord wil binnen 3 seconden antwoord
        r.http().sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(apiBasis() + "/interactions/" + str(d, "id") + "/" + str(d, "token") + "/callback"))
                        .header("Content-Type", "application/json").timeout(java.time.Duration.ofSeconds(5))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(cb.toString(), StandardCharsets.UTF_8)).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
    }

    static String discordNaam(JsonObject user, JsonObject member) {
        if (member != null && member.has("nick") && !member.get("nick").isJsonNull()) return member.get("nick").getAsString();
        if (user.has("global_name") && !user.get("global_name").isJsonNull()) return user.get("global_name").getAsString();
        return str(user, "username");
    }

    // ------------------------------------------------------------------ koppelen
    private String koppelVanuitDiscord(String uid, String dnaam, String code) {
        Koppelingen.Koppeling al = kop.vanDiscord(uid);
        if (code == null || code.isBlank()) {
            if (al != null) return "Je bent al gekoppeld aan **" + md(al.mcNaam) + "**. Met `/ontkoppel` haal je dat weg.";
            String c = kop.codeVoorDiscord(uid, dnaam);
            return "Typ in Minecraft: `/discord koppel " + c + "`\nDe code is 10 minuten geldig.";
        }
        Koppelingen.Code c = kop.neemCode(code);
        if (c == null || c.uuid() == null) return "Deze code klopt niet of is verlopen. Typ in Minecraft `/discord koppel` voor een nieuwe code.";
        Koppelingen.Koppeling k = koppel(c.uuid(), c.mcNaam(), uid, dnaam);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(c.uuid());
            if (p != null) p.sendMessage(Component.text("[Discord] ", NamedTextColor.BLUE)
                    .append(Component.text("Je Minecraft-account is gekoppeld aan " + dnaam + ".", NamedTextColor.GREEN)));
        });
        return "Gelukt! Je Discord-account is gekoppeld aan **" + md(k.mcNaam) + "**." + (al != null && !al.uuid.equals(c.uuid()) ? " (De oude koppeling met " + md(al.mcNaam) + " is weg.)" : "");
    }

    /** Koppelen vanuit het spel met een code uit Discord. Geeft een melding voor de speler terug. */
    String koppelVanuitSpel(Player p, String code) {
        Koppelingen.Code c = kop.neemCode(code);
        if (c == null || c.discordId() == null) return null;
        koppel(p.getUniqueId(), p.getName(), c.discordId(), c.discordNaam());
        return c.discordNaam();
    }

    Koppelingen.Koppeling koppel(UUID u, String mcNaam, String discordId, String discordNaam) {
        Koppelingen.Koppeling oud = kop.vanSpeler(u);
        Koppelingen.Koppeling oudD = kop.vanDiscord(discordId);
        if (oud != null && !oud.discordId.equals(discordId)) rollenWeg(oud);
        if (oudD != null && !oudD.uuid.equals(u)) rollenWeg(oudD);
        Koppelingen.Koppeling k = kop.koppel(u, mcNaam, discordId, discordNaam);
        rollenZetten(k);
        plugin.uitvoer().meld(Categorie.KOPPELINGEN, new Uitvoer.Regel("🔗 **" + md(mcNaam) + "** is gekoppeld aan <@" + discordId + "> (" + md(discordNaam) + ")",
                mcNaam, plugin.hoofd(mcNaam, u), null));
        plugin.getLogger().info(mcNaam + " is gekoppeld aan Discord-account " + discordNaam + ".");
        return k;
    }

    Koppelingen.Koppeling ontkoppel(UUID u, String door) {
        Koppelingen.Koppeling k = kop.ontkoppel(u);
        if (k == null) return null;
        rollenWeg(k);
        plugin.uitvoer().meld(Categorie.KOPPELINGEN, new Uitvoer.Regel("❌ **" + md(k.mcNaam) + "** is ontkoppeld van <@" + k.discordId + "> (" + md(k.discordNaam) + ") • door " + md(door),
                k.mcNaam, plugin.hoofd(k.mcNaam, k.uuid), null));
        return k;
    }

    void rollenZetten(Koppelingen.Koppeling k) {
        Rest r = rest;
        if (r == null) return;
        k.laatsteSync = System.currentTimeMillis();
        for (Opslag.GuildInst gi : opslag.alle()) {
            if (!gi.koppelAan || !guilds.containsKey(gi.id)) continue;
            if (!gi.koppelRol.isEmpty()) {
                r.put("/guilds/" + gi.id + "/members/" + k.discordId + "/roles/" + gi.koppelRol, null).whenComplete((j, e) -> {
                    if (e != null && Rest.code(e) != 10007) fout("Rol geven in " + gi.naam + " lukt niet: " + Rest.melding(e));
                });
            }
            if (gi.bijnaam) {
                JsonObject b = new JsonObject();
                b.addProperty("nick", k.mcNaam);
                r.patch("/guilds/" + gi.id + "/members/" + k.discordId, b).whenComplete((j, e) -> {
                    if (e != null && Rest.code(e) != 10007 && Rest.code(e) != 50013) fout("Bijnaam zetten in " + gi.naam + " lukt niet: " + Rest.melding(e));
                });
            }
        }
    }

    void rollenWeg(Koppelingen.Koppeling k) {
        Rest r = rest;
        if (r == null) return;
        for (Opslag.GuildInst gi : opslag.alle()) {
            if (!gi.koppelAan || !guilds.containsKey(gi.id)) continue;
            if (!gi.koppelRol.isEmpty()) r.delete("/guilds/" + gi.id + "/members/" + k.discordId + "/roles/" + gi.koppelRol).exceptionally(e -> null);
            if (gi.bijnaam) {
                JsonObject b = new JsonObject();
                b.add("nick", com.google.gson.JsonNull.INSTANCE);
                r.patch("/guilds/" + gi.id + "/members/" + k.discordId, b).exceptionally(e -> null);
            }
        }
    }

    /** Bij inloggen: rollen en bijnaam nog eens zetten (hoogstens eens per 6 uur), en een nieuwe Minecraft-naam overnemen. */
    void bijInloggen(Player p) {
        Koppelingen.Koppeling k = kop.vanSpeler(p.getUniqueId());
        if (k == null) return;
        boolean nieuweNaam = !p.getName().equals(k.mcNaam);
        if (nieuweNaam) { k.mcNaam = p.getName(); kop.bewaar(); }
        if (nieuweNaam || System.currentTimeMillis() - k.laatsteSync > 6 * 3600_000L) rollenZetten(k);
    }

    CompletableFuture<String> maakKoppelRol(String gid) {
        Rest r = rest;
        if (r == null) return CompletableFuture.failedFuture(new IllegalStateException("De bot is niet verbonden."));
        JsonObject b = new JsonObject();
        b.addProperty("name", plugin.getConfig().getString("koppelen.rol-naam", "Minecraft"));
        b.addProperty("color", 0x7DDC4A);
        b.addProperty("hoist", false);
        b.addProperty("mentionable", false);
        return r.post("/guilds/" + gid + "/roles", b).thenApply(j -> {
            Rol rol = rol(j.getAsJsonObject());
            Guild g = guilds.get(gid);
            if (g != null) g.rollen.put(rol.id(), rol);
            return rol.id();
        });
    }

    // ---------------------------------------------------------------- chat
    static String md(String s) {
        if (s == null) return "";
        return s.replaceAll("([\\\\*_~`|>])", "\\\\$1");
    }

    static JsonObject geenMentions() {
        JsonObject a = new JsonObject();
        a.add("parse", new JsonArray());
        return a;
    }

    /** Chat uit Minecraft naar alle Discord-servers met chat aan (behalve uitServer). */
    void chatNaarDiscord(String naam, String avatar, String tekst, String uitServer) {
        Rest r = rest;
        if (r == null) return;
        for (Opslag.GuildInst gi : opslag.alle()) {
            if (gi.id.equals(uitServer) || !guilds.containsKey(gi.id)) continue;
            Opslag.CatInst ci = gi.cat(Categorie.CHAT);
            if (!ci.aan || ci.kanaal.isEmpty()) continue;
            stuurChat(r, gi, ci, naam, avatar, tekst, true);
        }
    }

    /** Korte systeemregel (bijv. in- en uitloggen) als bericht van de bot in de chatkanalen. */
    void chatNaarDiscordSysteem(String tekst) {
        Rest r = rest;
        if (r == null) return;
        for (Opslag.GuildInst gi : opslag.alle()) {
            Opslag.CatInst ci = gi.cat(Categorie.CHAT);
            if (!ci.aan || ci.kanaal.isEmpty() || !guilds.containsKey(gi.id)) continue;
            JsonObject b = new JsonObject();
            b.addProperty("content", tekst);
            b.add("allowed_mentions", geenMentions());
            r.post("/channels/" + ci.kanaal + "/messages", b).exceptionally(e -> null);
        }
    }

    private void stuurChat(Rest r, Opslag.GuildInst gi, Opslag.CatInst ci, String naam, String avatar, String tekst, boolean opnieuw) {
        boolean webhooks = plugin.getConfig().getBoolean("chat.webhooks", true);
        if (webhooks && ci.webhookId.isEmpty()) {
            JsonObject b = new JsonObject();
            b.addProperty("name", "Minecraft-chat");
            r.post("/channels/" + ci.kanaal + "/webhooks", b).whenComplete((j, e) -> {
                if (e == null) {
                    JsonObject o = j.getAsJsonObject();
                    ci.webhookId = str(o, "id");
                    ci.webhookToken = str(o, "token");
                    opslag.bewaar();
                    stuurChat(r, gi, ci, naam, avatar, tekst, false);
                } else {
                    fout("Webhook maken in " + gi.naam + " lukt niet (" + Rest.melding(e) + "); chat gaat nu als gewoon bericht van de bot.");
                    stuurAlsBot(r, gi, ci, naam, tekst);
                }
            });
            return;
        }
        if (!webhooks || ci.webhookId.isEmpty()) { stuurAlsBot(r, gi, ci, naam, tekst); return; }
        JsonObject b = new JsonObject();
        b.addProperty("content", tekst);
        b.addProperty("username", veiligeNaam(naam));
        if (avatar != null) b.addProperty("avatar_url", avatar);
        b.add("allowed_mentions", geenMentions());
        r.postZonderToken("/webhooks/" + ci.webhookId + "/" + ci.webhookToken, b).whenComplete((j, e) -> {
            if (e == null) return;
            int code = Rest.code(e);
            if ((code == 10015 || code == 50027) && opnieuw) {
                ci.webhookId = ""; ci.webhookToken = "";
                opslag.bewaar();
                stuurChat(r, gi, ci, naam, avatar, tekst, false);
            } else if (code == 10003) {
                kanaalWeg(gi.id, ci.kanaal);
            } else fout("Chat naar " + gi.naam + " lukt niet: " + Rest.melding(e));
        });
    }

    private static String veiligeNaam(String naam) {
        String n = naam.replaceAll("(?i)discord", "Disc0rd").replaceAll("(?i)clyde", "Clyd3");
        if (n.length() > 80) n = n.substring(0, 80);
        return n.isBlank() ? "Speler" : n;
    }

    private void stuurAlsBot(Rest r, Opslag.GuildInst gi, Opslag.CatInst ci, String naam, String tekst) {
        JsonObject b = new JsonObject();
        b.addProperty("content", "**" + md(naam) + "** » " + tekst);
        b.add("allowed_mentions", geenMentions());
        r.post("/channels/" + ci.kanaal + "/messages", b).whenComplete((j, e) -> {
            if (e == null) return;
            if (Rest.code(e) == 10003) kanaalWeg(gi.id, ci.kanaal);
            else fout("Chat naar " + gi.naam + " lukt niet: " + Rest.melding(e));
        });
    }

    private void berichtBinnen(JsonObject d) {
        String kanaalId = str(d, "channel_id"), gid = str(d, "guild_id");
        if (gid.isEmpty()) return;
        Opslag.GuildInst gi = opslag.bestaand(gid);
        if (gi == null) return;
        Opslag.CatInst ci = gi.cat(Categorie.CHAT);
        if (!ci.aan || !kanaalId.equals(ci.kanaal)) return;
        if (d.has("webhook_id") && !d.get("webhook_id").isJsonNull()) return;
        JsonObject auteur = d.getAsJsonObject("author");
        if (auteur == null || (auteur.has("bot") && auteur.get("bot").getAsBoolean())) return;
        int type = d.has("type") ? d.get("type").getAsInt() : 0;
        if (type != 0 && type != 19) return;
        if (!plugin.getConfig().getBoolean("chat.discord-naar-minecraft", true)) return;

        Guild g = guilds.get(gid);
        String uid = str(auteur, "id");
        Koppelingen.Koppeling k = kop.vanDiscord(uid);
        String naam = k != null && plugin.getConfig().getBoolean("chat.minecraft-naam-als-gekoppeld", true) ? k.mcNaam
                : discordNaam(auteur, d.has("member") ? d.getAsJsonObject("member") : null);
        String tekst = str(d, "content");
        // mentions leesbaar maken
        Map<String, String> namen = new java.util.HashMap<>();
        if (d.has("mentions")) for (JsonElement e : d.getAsJsonArray("mentions")) {
            JsonObject m = e.getAsJsonObject();
            namen.put(str(m, "id"), discordNaam(m, m.has("member") ? m.getAsJsonObject("member") : null));
        }
        tekst = vervang(GEBRUIKER, tekst, id -> "@" + namen.getOrDefault(id, "iemand"));
        tekst = vervang(ROL, tekst, id -> { Rol rl = g == null ? null : g.rollen.get(id); return "@" + (rl == null ? "rol" : rl.naam()); });
        tekst = vervang(KANAAL, tekst, id -> { Kanaal kn = g == null ? null : g.kanalen.get(id); return "#" + (kn == null ? "kanaal" : kn.naam()); });
        tekst = EMOJI.matcher(tekst).replaceAll(":$1:");
        tekst = tekst.replace('§', ' ').replaceAll("[\\r\\n]+", " ").trim();
        int max = plugin.getConfig().getInt("chat.max-lengte", 256);
        if (tekst.length() > max) tekst = tekst.substring(0, max) + "...";
        List<String[]> bijlagen = new ArrayList<>();
        if (d.has("attachments")) for (JsonElement e : d.getAsJsonArray("attachments")) {
            JsonObject a = e.getAsJsonObject();
            bijlagen.add(new String[]{str(a, "filename"), str(a, "url")});
        }
        if (tekst.isEmpty() && bijlagen.isEmpty()) return;

        String serverNaam = gi.naam;
        String kanaalNaam = g != null && g.kanalen.get(kanaalId) != null ? g.kanalen.get(kanaalId).naam() : "chat";
        Component bericht = maakChatRegel(naam, serverNaam, kanaalNaam, tekst, bijlagen);
        final String tekstF = tekst;
        final String naamF = naam;
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getServer().broadcast(bericht));

        if (plugin.getConfig().getBoolean("chat.tussen-servers", false)) {
            String avatar = auteur.has("avatar") && !auteur.get("avatar").isJsonNull()
                    ? "https://cdn.discordapp.com/avatars/" + uid + "/" + auteur.get("avatar").getAsString() + ".png?size=128" : null;
            String inhoud = tekstF + (bijlagen.isEmpty() ? "" : " " + String.join(" ", bijlagen.stream().map(b -> b[1]).toList()));
            chatNaarDiscord(naamF + " (" + serverNaam + ")", avatar, inhoud, gid);
        }
    }

    private Component maakChatRegel(String naam, String server, String kanaal, String tekst, List<String[]> bijlagen) {
        String format = plugin.getConfig().getString("chat.naar-minecraft", "&9[Discord] &7{naam}&8: &f{bericht}");
        String schoneNaam = naam.replace('&', ' ').replace('§', ' ');
        String schoneServer = server.replace('&', ' ').replace('§', ' ');
        String[] delen = format.replace("{naam}", schoneNaam).replace("{server}", schoneServer).split("\\{bericht\\}", 2);
        LegacyComponentSerializer lcs = LegacyComponentSerializer.legacyAmpersand();
        Component links = lcs.deserialize(delen[0]);
        TextColor kleur = NamedTextColor.WHITE;
        Matcher m = Pattern.compile("&([0-9a-fA-F])").matcher(delen[0]);
        String laatste = null;
        while (m.find()) laatste = m.group(1);
        if (laatste != null) {
            Component c = lcs.deserialize("&" + laatste + "x");
            TextColor tc = c.color();
            if (tc == null && !c.children().isEmpty()) tc = c.children().get(0).color();
            if (tc != null) kleur = tc;
        }
        Component regel = links.append(Component.text(tekst, kleur));
        for (String[] b : bijlagen) {
            regel = regel.append(Component.text(" [" + (b[0].isBlank() ? "bijlage" : b[0]) + "]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.openUrl(b[1]))
                    .hoverEvent(HoverEvent.showText(Component.text("Klik om te openen"))));
        }
        if (delen.length > 1) regel = regel.append(lcs.deserialize(delen[1]));
        return regel.hoverEvent(HoverEvent.showText(Component.text("Vanuit Discord: " + server + " #" + kanaal, NamedTextColor.GRAY)));
    }

    private static String vervang(Pattern p, String s, java.util.function.Function<String, String> f) {
        Matcher m = p.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(f.apply(m.group(1))));
        m.appendTail(b);
        return b.toString();
    }

    // ----------------------------------------------------------------- beheer
    CompletableFuture<Void> verlaat(String gid) {
        Rest r = rest;
        if (r == null) return CompletableFuture.failedFuture(new IllegalStateException("De bot is niet verbonden."));
        return r.delete("/users/@me/guilds/" + gid).thenAccept(j -> {
            guilds.remove(gid);
            opslag.verwijder(gid);
            opslag.bewaar();
        });
    }

    CompletableFuture<Void> test(String gid) {
        Opslag.GuildInst gi = opslag.bestaand(gid);
        Rest r = rest;
        if (gi == null || r == null) return CompletableFuture.failedFuture(new IllegalStateException("De bot is niet verbonden."));
        List<CompletableFuture<JsonElement>> f = new ArrayList<>();
        String tijd = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        for (Categorie c : Categorie.values()) {
            Opslag.CatInst ci = gi.cat(c);
            if (!ci.aan || ci.kanaal.isEmpty()) continue;
            JsonObject e = new JsonObject();
            e.addProperty("title", c.emoji + " " + c.label);
            e.addProperty("description", "Testbericht vanuit het beheer (" + tijd + "). Hier komt: " + c.uitleg);
            e.addProperty("color", c.kleur);
            JsonArray embeds = new JsonArray();
            embeds.add(e);
            JsonObject b = new JsonObject();
            b.add("embeds", embeds);
            b.add("allowed_mentions", geenMentions());
            f.add(r.post("/channels/" + ci.kanaal + "/messages", b));
        }
        if (f.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("Er staat in deze server nog geen categorie aan."));
        return CompletableFuture.allOf(f.toArray(new CompletableFuture[0]));
    }

    static String hoofdUrl(String formaat, String naam, UUID u) {
        return formaat.replace("{naam}", URLEncoder.encode(naam, StandardCharsets.UTF_8)).replace("{uuid}", u == null ? naam : u.toString());
    }

    static String spelerNaam(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() == null ? u.toString().substring(0, 8) : op.getName();
    }
}
