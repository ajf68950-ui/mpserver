package meldly;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/** Verbinding voor de website (Beheer > Discord), alleen op 127.0.0.1 en met een token (net als Vaulty en Muntly). */
final class WebApi {
    private final Meldly plugin;
    private HttpServer server;
    private ExecutorService pool;

    WebApi(Meldly plugin) { this.plugin = plugin; }

    void start() throws IOException {
        String host = plugin.getConfig().getString("web.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("web.poort", 8097);
        server = HttpServer.create(new InetSocketAddress(host, port), 16);
        pool = Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "Meldly-web"); t.setDaemon(true); return t; });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        plugin.getLogger().info("Verbinding voor de website staat klaar op " + host + ":" + port);
    }

    void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private static JsonObject fout(String m) { JsonObject o = new JsonObject(); o.addProperty("error", m); return o; }
    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : ""; }
    private static boolean bool(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean(); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String token = plugin.getConfig().getString("web.token", "");
            String given = ex.getRequestHeaders().getFirst("X-Token");
            if (token.isBlank() || given == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, fout("Geen toegang."));
                return;
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath().replaceAll("/+$", "");
            JsonObject b = new JsonObject();
            if (method.equals("POST")) {
                try (InputStream in = ex.getRequestBody()) {
                    String s = new String(in.readNBytes(65536), StandardCharsets.UTF_8);
                    if (!s.isBlank()) b = JsonParser.parseString(s).getAsJsonObject();
                }
            }
            JsonObject r;
            if (path.equals("/status")) {
                r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("version", plugin.getPluginMeta().getVersion());
            } else if (path.equals("/admin/state")) {
                r = state();
            } else if (!method.equals("POST")) {
                send(ex, 404, fout("Deze functie kent Meldly nog niet. Herstart de server zodat de nieuwste Meldly geladen wordt."));
                return;
            } else {
                r = switch (path) {
                    case "/admin/token" -> token(b);
                    case "/admin/herverbind" -> { plugin.bot().start(); yield wacht(CompletableFuture.completedFuture(null), "Opnieuw verbinden..."); }
                    case "/admin/categorie" -> categorie(b);
                    case "/admin/alles" -> alles(b);
                    case "/admin/koppelen" -> koppelen(b);
                    case "/admin/test" -> wacht(plugin.bot().test(str(b, "guild")), "Testberichten verstuurd.");
                    case "/admin/verlaat" -> wacht(plugin.bot().verlaat(str(b, "guild")), "De bot is uit de server gehaald.");
                    case "/admin/vergeet" -> { plugin.opslag().verwijder(str(b, "guild")); yield ok("Vergeten."); }
                    case "/admin/ontkoppel" -> ontkoppel(b);
                    case "/admin/instellingen" -> instellingen(b);
                    case "/admin/groepen" -> groepen(b);
                    case "/admin/team-opheffen" -> plugin.teams().hefOp(str(b, "naam")) ? ok("Team opgeheven.") : fout("Dat team bestaat niet (meer).");
                    default -> null;
                };
                if (r == null) { send(ex, 404, fout("Deze functie kent Meldly nog niet. Herstart de server zodat de nieuwste Meldly geladen wordt.")); return; }
            }
            send(ex, r.has("error") ? 400 : 200, r);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Fout in de website-verbinding", e);
            send(ex, 500, fout("Er ging iets mis in de plugin: " + e.getMessage()));
        }
    }

    private static JsonObject ok(String melding) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("melding", melding);
        return o;
    }

    /** Wacht hoogstens 9 seconden; daarna loopt het op de achtergrond door (bijv. veel kanalen maken). */
    private JsonObject wacht(CompletableFuture<?> f, String klaar) {
        try {
            f.get(9, TimeUnit.SECONDS);
            return ok(klaar);
        } catch (TimeoutException te) {
            JsonObject o = ok("Nog bezig in Discord. Vernieuw zo de pagina.");
            o.addProperty("bezig", true);
            return o;
        } catch (Exception e) {
            return fout(Rest.melding(e));
        }
    }

    // ------------------------------------------------------------------ acties
    private JsonObject token(JsonObject b) {
        String t = str(b, "token");
        if (t.startsWith("Bot ")) t = t.substring(4).trim();
        if (!t.matches("[A-Za-z0-9_.\\-]{50,100}")) return fout("Dit lijkt geen bot-token. Kopieer het in de Discord Developer Portal bij Bot > Reset Token.");
        final String nieuw = t;
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                plugin.getConfig().set("bot.bot-token", nieuw);
                plugin.saveConfig();
                return null;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fout("Opslaan lukt niet: " + e.getMessage());
        }
        plugin.bot().start();
        long tot = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < tot) {
            String s = plugin.bot().status;
            if (s.equals("verbonden") || s.startsWith("fout")) break;
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        String s = plugin.bot().status;
        if (s.startsWith("fout")) return fout(s.substring(6));
        return ok(s.equals("verbonden") ? "Verbonden als " + plugin.bot().botNaam + "." : "Opgeslagen, de bot maakt verbinding...");
    }

    private JsonObject categorie(JsonObject b) {
        Categorie c = Categorie.van(str(b, "categorie"));
        String gid = str(b, "guild");
        if (c == null || gid.isEmpty()) return fout("Onbekende categorie of server.");
        return wacht(plugin.bot().zetCategorie(gid, c, bool(b, "aan"), str(b, "kanaal")),
                c.label + (bool(b, "aan") ? " staat aan." : " staat uit."));
    }

    private JsonObject alles(JsonObject b) {
        String gid = str(b, "guild");
        boolean aan = bool(b, "aan");
        if (gid.isEmpty()) return fout("Onbekende server.");
        // na elkaar, zodat de kanalen in de goede volgorde in Discord komen
        CompletableFuture<Void> f = CompletableFuture.completedFuture(null);
        for (Categorie c : Categorie.values()) f = f.thenCompose(x -> plugin.bot().zetCategorie(gid, c, aan, ""));
        return wacht(f, aan ? "Alle categorieën staan aan." : "Alle categorieën staan uit.");
    }

    private JsonObject koppelen(JsonObject b) {
        String gid = str(b, "guild");
        Opslag.GuildInst gi = plugin.opslag().bestaand(gid);
        if (gi == null) return fout("Onbekende server.");
        boolean aan = bool(b, "aan");
        String rol = str(b, "rol");
        boolean bijnaam = bool(b, "bijnaam");
        CompletableFuture<String> rolF = rol.equals("nieuw") ? plugin.bot().maakKoppelRol(gid) : CompletableFuture.completedFuture(rol);
        CompletableFuture<Void> f = rolF.thenAccept(r -> {
            boolean wasAan = gi.koppelAan;
            String oudeRol = gi.koppelRol;
            gi.koppelAan = aan;
            gi.koppelRol = r;
            gi.bijnaam = bijnaam;
            plugin.opslag().bewaar();
            plugin.bot().commandosBijwerken(gid);
            // bestaande koppelingen meteen de rol geven
            if (aan && (!wasAan || !r.equals(oudeRol))) for (Koppelingen.Koppeling k : plugin.koppelingen().alle()) plugin.bot().rollenZetten(k);
        });
        return wacht(f, aan ? "Koppelen staat aan." : "Koppelen staat uit.");
    }

    private JsonObject groepen(JsonObject b) {
        String gid = str(b, "guild");
        if (gid.isEmpty()) return fout("Onbekende server.");
        boolean aan = bool(b, "aan");
        return wacht(plugin.bot().zetGroepen(gid, aan, str(b, "lobby"), bool(b, "buurt"), bool(b, "eiland"), bool(b, "team"), bool(b, "zichtbaar")),
                aan ? "Spraakgroepen staan aan." : "Spraakgroepen staan uit.");
    }

    private JsonObject ontkoppel(JsonObject b) {
        UUID u;
        try { u = UUID.fromString(str(b, "uuid")); } catch (Exception e) { return fout("Ongeldige speler."); }
        Koppelingen.Koppeling k = plugin.bot().ontkoppel(u, "het beheer");
        return k == null ? fout("Deze speler is niet gekoppeld.") : ok(k.mcNaam + " is ontkoppeld.");
    }

    private JsonObject instellingen(JsonObject b) {
        try {
            Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                var c = plugin.getConfig();
                if (b.has("tussenServers")) c.set("chat.tussen-servers", bool(b, "tussenServers"));
                if (b.has("inUitInChat")) c.set("chat.in-uit-in-chat", bool(b, "inUitInChat"));
                if (b.has("adres")) c.set("status.adres", str(b, "adres"));
                if (b.has("servernaam")) c.set("status.servernaam", str(b, "servernaam"));
                if (b.has("uitnodiging")) c.set("algemeen.uitnodiging", str(b, "uitnodiging"));
                if (b.has("afstand")) {
                    int af = 80;
                    try { af = Integer.parseInt(str(b, "afstand")); } catch (NumberFormatException ignored) { }
                    c.set("groepen.afstand.horizontaal", Math.max(5, Math.min(500, af)));
                }
                plugin.saveConfig();
                return null;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fout("Opslaan lukt niet: " + e.getMessage());
        }
        plugin.status().nu();
        return ok("Opgeslagen.");
    }

    // ------------------------------------------------------------------- stand
    private JsonObject state() {
        Bot bot = plugin.bot();
        JsonObject o = new JsonObject();
        o.addProperty("versie", plugin.getPluginMeta().getVersion());
        o.addProperty("status", bot.status);
        o.addProperty("tokenIngesteld", !bot.token().isEmpty());
        o.addProperty("botNaam", bot.botNaam);
        o.addProperty("botId", bot.botId);
        o.addProperty("botAvatar", bot.botAvatar.isEmpty() || bot.botId.isEmpty() ? "" : "https://cdn.discordapp.com/avatars/" + bot.botId + "/" + bot.botAvatar + ".png?size=64");
        o.addProperty("appId", bot.appId);
        o.addProperty("ping", bot.ping());
        o.addProperty("uitnodigLink", bot.uitnodigLink());
        o.addProperty("discordsrv", plugin.discordSrvAanwezig());

        var c = plugin.getConfig();
        JsonObject inst = new JsonObject();
        inst.addProperty("tussenServers", c.getBoolean("chat.tussen-servers", false));
        inst.addProperty("inUitInChat", c.getBoolean("chat.in-uit-in-chat", false));
        inst.addProperty("adres", c.getString("status.adres", ""));
        inst.addProperty("servernaam", c.getString("status.servernaam", ""));
        inst.addProperty("uitnodiging", c.getString("algemeen.uitnodiging", ""));
        inst.addProperty("afstand", c.getInt("groepen.afstand.horizontaal", 80));
        o.add("instellingen", inst);

        JsonArray cats = new JsonArray();
        for (Categorie cat : Categorie.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("sleutel", cat.sleutel);
            j.addProperty("label", cat.label);
            j.addProperty("emoji", cat.emoji);
            j.addProperty("uitleg", cat.uitleg);
            j.addProperty("prive", c.getBoolean("kanalen.prive." + cat.sleutel, cat.prive));
            j.addProperty("kanaalNaam", c.getString("kanalen.namen." + cat.sleutel, cat.kanaalNaam));
            cats.add(j);
        }
        o.add("categorieen", cats);

        JsonArray servers = new JsonArray();
        List<String> ids = new ArrayList<>();
        for (Opslag.GuildInst gi : plugin.opslag().alle()) ids.add(gi.id);
        for (String id : bot.guilds.keySet()) if (!ids.contains(id)) ids.add(id);
        for (String id : ids) {
            Bot.Guild g = bot.guilds.get(id);
            Opslag.GuildInst gi = plugin.opslag().guild(id);
            JsonObject s = new JsonObject();
            s.addProperty("id", id);
            s.addProperty("naam", g != null ? g.naam : gi.naam);
            s.addProperty("aanwezig", g != null);
            s.addProperty("icoon", g != null && g.icoon != null ? "https://cdn.discordapp.com/icons/" + id + "/" + g.icoon + ".png?size=64" : "");
            s.addProperty("leden", g != null ? g.leden : 0);
            JsonObject per = new JsonObject();
            for (Categorie cat : Categorie.values()) {
                Opslag.CatInst ci = gi.cat(cat);
                JsonObject j = new JsonObject();
                j.addProperty("aan", ci.aan);
                j.addProperty("kanaal", ci.kanaal);
                Bot.Kanaal k = g == null ? null : g.kanalen.get(ci.kanaal);
                j.addProperty("kanaalNaam", k == null ? "" : k.naam());
                per.add(cat.sleutel, j);
            }
            s.add("categorieen", per);
            JsonArray kanalen = new JsonArray();
            JsonArray rollen = new JsonArray();
            if (g != null) {
                g.kanalen.values().stream().filter(k -> k.type() == 0 || k.type() == 5)
                        .sorted(Comparator.comparing((Bot.Kanaal k) -> parentPos(g, k)).thenComparingInt(Bot.Kanaal::positie))
                        .forEach(k -> {
                            JsonObject j = new JsonObject();
                            j.addProperty("id", k.id());
                            j.addProperty("naam", k.naam());
                            Bot.Kanaal p = k.parent() == null ? null : g.kanalen.get(k.parent());
                            j.addProperty("groep", p == null ? "" : p.naam());
                            kanalen.add(j);
                        });
                g.rollen.values().stream().filter(r -> !r.beheerd() && !r.id().equals(id))
                        .sorted(Comparator.comparingInt(Bot.Rol::positie).reversed())
                        .forEach(r -> {
                            JsonObject j = new JsonObject();
                            j.addProperty("id", r.id());
                            j.addProperty("naam", r.naam());
                            rollen.add(j);
                        });
            }
            s.add("kanalen", kanalen);
            s.add("rollen", rollen);
            JsonArray spraakKanalen = new JsonArray();
            if (g != null) {
                g.kanalen.values().stream().filter(k -> k.type() == 2 && !gi.groepKanalen.containsKey(k.id()))
                        .sorted(Comparator.comparing((Bot.Kanaal k) -> parentPos(g, k)).thenComparingInt(Bot.Kanaal::positie))
                        .forEach(k -> {
                            JsonObject j = new JsonObject();
                            j.addProperty("id", k.id());
                            j.addProperty("naam", k.naam());
                            Bot.Kanaal p = k.parent() == null ? null : g.kanalen.get(k.parent());
                            j.addProperty("groep", p == null ? "" : p.naam());
                            spraakKanalen.add(j);
                        });
            }
            s.add("spraakKanalen", spraakKanalen);
            JsonObject gr = new JsonObject();
            gr.addProperty("aan", gi.groepenAan);
            gr.addProperty("lobby", gi.lobby);
            Bot.Kanaal lk = g == null ? null : g.kanalen.get(gi.lobby);
            gr.addProperty("lobbyNaam", lk == null ? "" : lk.naam());
            gr.addProperty("buurt", gi.groepBuurt);
            gr.addProperty("eiland", gi.groepEiland);
            gr.addProperty("team", gi.groepTeam);
            gr.addProperty("zichtbaar", gi.groepZichtbaar);
            Map<String, String> stemmen = bot.spraak.getOrDefault(id, Map.of());
            JsonArray actief = new JsonArray();
            for (Map.Entry<String, String> e : gi.groepKanalen.entrySet()) {
                Bot.Kanaal k = g == null ? null : g.kanalen.get(e.getKey());
                JsonObject j = new JsonObject();
                j.addProperty("naam", k == null ? e.getValue() : k.naam());
                j.addProperty("leden", stemmen.values().stream().filter(x -> x.equals(e.getKey())).count());
                actief.add(j);
            }
            gr.add("actief", actief);
            gr.addProperty("inLobby", gi.lobby.isEmpty() ? 0 : stemmen.values().stream().filter(x -> x.equals(gi.lobby)).count());
            s.add("groepen", gr);
            JsonObject kp = new JsonObject();
            kp.addProperty("aan", gi.koppelAan);
            kp.addProperty("rol", gi.koppelRol);
            kp.addProperty("bijnaam", gi.bijnaam);
            s.add("koppelen", kp);
            servers.add(s);
        }
        o.add("servers", servers);

        JsonArray kop = new JsonArray();
        for (Koppelingen.Koppeling k : plugin.koppelingen().alle()) {
            JsonObject j = new JsonObject();
            j.addProperty("uuid", k.uuid.toString());
            j.addProperty("naam", k.mcNaam);
            j.addProperty("discordId", k.discordId);
            j.addProperty("discordNaam", k.discordNaam);
            j.addProperty("sinds", k.sinds);
            kop.add(j);
        }
        o.add("koppelingen", kop);

        JsonArray teams = new JsonArray();
        for (Teams.Team t : plugin.teams().alle()) {
            JsonObject j = new JsonObject();
            j.addProperty("naam", t.naam);
            j.addProperty("eigenaar", Bot.spelerNaam(t.eigenaar));
            JsonArray leden = new JsonArray();
            for (UUID u : t.leden) leden.add(Bot.spelerNaam(u));
            j.add("leden", leden);
            teams.add(j);
        }
        o.add("teams", teams);

        JsonArray fouten = new JsonArray();
        for (Bot.Fout f : bot.fouten()) {
            JsonObject j = new JsonObject();
            j.addProperty("tijd", f.tijd());
            j.addProperty("tekst", f.tekst());
            fouten.add(j);
        }
        o.add("fouten", fouten);
        return o;
    }

    private static String parentPos(Bot.Guild g, Bot.Kanaal k) {
        Bot.Kanaal p = k.parent() == null ? null : g.kanalen.get(k.parent());
        return p == null ? "0000" : String.format("%04d", p.positie() + 1);
    }

    private static void send(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }
}
