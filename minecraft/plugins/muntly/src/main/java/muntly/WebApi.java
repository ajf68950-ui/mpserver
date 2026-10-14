package muntly;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Verbinding voor de website, alleen op 127.0.0.1 en met een token (net als Vaulty).
 * Wat je op de website koopt of wint, komt in je kluis van Vaulty.
 */
final class WebApi {
    private final Muntly plugin;
    private final Gson gson = new Gson();
    private HttpServer server;
    private ExecutorService pool;

    WebApi(Muntly plugin) { this.plugin = plugin; }

    void start() throws IOException {
        String host = plugin.getConfig().getString("web.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("web.poort", 8096);
        server = HttpServer.create(new InetSocketAddress(host, port), 16);
        pool = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "Muntly-web"); t.setDaemon(true); return t; });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        plugin.getLogger().info("Verbinding voor de website staat klaar op " + host + ":" + port);
    }

    void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String token = plugin.getConfig().getString("web.token", "");
            String given = ex.getRequestHeaders().getFirst("X-Token");
            if (token.isBlank() || given == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, error("Geen toegang."));
                return;
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            JsonObject body = new JsonObject();
            if (method.equals("POST")) {
                try (InputStream in = ex.getRequestBody()) {
                    String s = new String(in.readNBytes(65536), StandardCharsets.UTF_8);
                    if (!s.isBlank()) body = JsonParser.parseString(s).getAsJsonObject();
                }
            }
            String query = ex.getRequestURI().getRawQuery();
            if (query != null && !body.has("name")) {
                for (String q : query.split("&")) {
                    String[] kv = q.split("=", 2);
                    if (kv.length == 2 && kv[0].equals("name")) body.addProperty("name", java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                }
            }
            String[] parts = path.replaceAll("^/+|/+$", "").split("/");
            final JsonObject b = body;
            JsonObject result;
            if (path.equals("/status")) {
                result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("version", plugin.getPluginMeta().getVersion());
            } else if (path.equals("/shop")) {
                result = sync(this::shop);
            } else if (path.equals("/top")) {
                result = sync(() -> top(10));
            } else if (path.equals("/admin/spelers")) {
                result = sync(this::spelers);
            } else if (path.equals("/admin/geef") && method.equals("POST")) {
                result = sync(() -> adminGeef(b));
            } else if (parts.length >= 2 && parts[0].equals("player")) {
                UUID uuid;
                try { uuid = UUID.fromString(parts[1]); } catch (Exception e) { send(ex, 400, error("Ongeldige speler.")); return; }
                String action = parts.length >= 3 ? parts[2] : "";
                result = switch (action) {
                    case "" -> sync(() -> state(uuid, b));
                    case "buy" -> method.equals("POST") ? sync(() -> buy(uuid, b)) : null;
                    case "lootbox" -> method.equals("POST") ? sync(() -> lootbox(uuid, b)) : null;
                    default -> null;
                };
                if (result == null) { send(ex, 404, error("Deze functie kent Muntly nog niet. Herstart MeediaSMP zodat de nieuwste Muntly geladen wordt.")); return; }
            } else {
                send(ex, 404, error("Deze functie kent Muntly nog niet. Herstart MeediaSMP zodat de nieuwste Muntly geladen wordt."));
                return;
            }
            send(ex, result.has("error") ? 400 : 200, result);
        } catch (TimeoutException te) {
            send(ex, 503, error("De server is druk. Probeer het zo nog eens."));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Fout in de website-verbinding", e);
            send(ex, 500, error("Er ging iets mis in de plugin."));
        }
    }

    private JsonObject sync(Supplier<JsonObject> task) throws Exception {
        Future<JsonObject> f = Bukkit.getScheduler().callSyncMethod(plugin, task::get);
        try {
            return f.get(8, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            f.cancel(false);
            throw te;
        }
    }

    // ---------------------------------------------------------------- acties
    private JsonObject munt() {
        JsonObject m = new JsonObject();
        m.addProperty("naam", plugin.muntNaam());
        m.addProperty("enkelvoud", plugin.getConfig().getString("munt.enkelvoud", "Munt"));
        return m;
    }

    private JsonObject shop() {
        JsonObject o = plugin.winkel().toJson();
        o.add("munt", munt());
        JsonObject v = new JsonObject();
        v.addProperty("speeltijdAan", plugin.getConfig().getBoolean("verdienen.speeltijd.aan", true));
        v.addProperty("speeltijdMinuten", plugin.getConfig().getInt("verdienen.speeltijd.minuten", 30));
        v.addProperty("speeltijdMunten", plugin.getConfig().getLong("verdienen.speeltijd.munten", 2));
        v.addProperty("dagelijksAan", plugin.getConfig().getBoolean("verdienen.dagelijks.aan", true));
        JsonArray dag = new JsonArray();
        for (Long l : plugin.getConfig().getLongList("verdienen.dagelijks.beloningen")) dag.add(l);
        v.add("dagelijks", dag);
        o.add("verdienen", v);
        return o;
    }

    private JsonObject top(int n) {
        JsonObject o = new JsonObject();
        o.add("munt", munt());
        JsonArray arr = new JsonArray();
        for (Saldi.Speler sp : plugin.saldi().top(n)) {
            JsonObject e = new JsonObject();
            e.addProperty("naam", sp.naam);
            e.addProperty("saldo", sp.saldo);
            arr.add(e);
        }
        o.add("top", arr);
        return o;
    }

    private Saldi.Speler speler(UUID uuid, JsonObject b) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String n = op.getName();
        if (n == null) {
            String hint = str(b, "name");
            if (hint.matches("[A-Za-z0-9_.*\\-]{2,20}")) n = hint;
        }
        return plugin.saldi().get(uuid, n);
    }

    private JsonObject state(UUID uuid, JsonObject b) {
        Saldi.Speler sp = speler(uuid, b);
        JsonObject o = new JsonObject();
        o.addProperty("saldo", sp.saldo);
        o.addProperty("reeks", sp.reeks);
        o.addProperty("laatsteDag", sp.laatsteDag);
        o.addProperty("morgen", plugin.verdienen().beloningVoorDag(sp.reeks + 1));
        o.addProperty("minutenTotVolgende", plugin.verdienen().minutenTotVolgende(sp));
        o.addProperty("verdiend", sp.verdiend);
        o.add("munt", munt());
        return o;
    }

    private JsonObject buy(UUID uuid, JsonObject b) {
        Winkel.Artikel a = plugin.winkel().artikelen().get(str(b, "id"));
        if (a == null || !a.teKoop()) return error("Dit item bestaat niet (meer). Ververs de pagina.");
        if (b.has("prijs") && b.get("prijs").getAsLong() != a.prijs) return error("De prijs is veranderd. Ververs de pagina.");
        Saldi.Speler sp = speler(uuid, b);
        if (sp.saldo < a.prijs) return error("Je hebt niet genoeg " + plugin.muntNaam() + ". Je hebt er " + Muntly.fmt(sp.saldo) + ", dit kost " + Muntly.fmt(a.prijs) + ".");
        String fout = kluis(uuid, sp.naam, a.item.clone());
        if (fout != null) return error(fout);
        plugin.saldi().take(sp, a.prijs, "website-winkel: " + a.id);
        plugin.getLogger().info(sp.naam + " kocht via de website " + a.id + " voor " + a.prijs + " " + plugin.muntNaam());
        notify(uuid, "&aGekocht via de website: &f" + Winkel.naam(a.item) + "&a. Het ligt in je kluis.");
        JsonObject o = state(uuid, b);
        o.add("gekocht", plugin.winkel().describe(a.item));
        return o;
    }

    private JsonObject lootbox(UUID uuid, JsonObject b) {
        Winkel.Lootbox lb = plugin.winkel().lootboxen().get(str(b, "id"));
        if (lb == null) return error("Deze lootbox bestaat niet (meer). Ververs de pagina.");
        Saldi.Speler sp = speler(uuid, b);
        if (sp.saldo < lb.prijs) return error("Je hebt niet genoeg " + plugin.muntNaam() + ". Je hebt er " + Muntly.fmt(sp.saldo) + ", deze kist kost " + Muntly.fmt(lb.prijs) + ".");
        // eerst kijken of er plek is, zodat er niet opnieuw getrokken kan worden door een volle kluis
        String plek = kluisVrij(uuid, sp.naam);
        if (plek != null) return error(plek);
        Winkel.Beloning prijs = lb.trek();
        String wat = plugin.winkel().omschrijf(prijs);
        if (prijs.item != null) {
            String fout = kluis(uuid, sp.naam, prijs.item.clone());
            if (fout != null) return error(fout);
        }
        plugin.saldi().take(sp, lb.prijs, "website-lootbox: " + lb.id);
        if (prijs.munten > 0) plugin.saldi().add(sp, prijs.munten, "lootbox-prijs (website): " + lb.id);
        else if (prijs.item == null && prijs.geld > 0) {
            Economy eco = plugin.economy();
            if (eco != null) eco.depositPlayer(Bukkit.getOfflinePlayer(uuid), prijs.geld);
            else { plugin.saldi().add(sp, lb.prijs, "lootbox terug (geen economy): " + lb.id); wat = plugin.munten(lb.prijs) + " terug"; }
        }
        plugin.getLogger().info(sp.naam + " opende via de website " + lb.id + " en kreeg " + wat);
        plugin.meldLegendarisch(sp.naam, lb, prijs, wat);
        notify(uuid, "Lootbox via de website: " + prijs.zeldzaamheid.kleur + "&l" + wat + (prijs.item != null ? " &7(ligt in je kluis)" : ""));
        JsonObject o = state(uuid, b);
        JsonObject won = plugin.winkel().describe(prijs);
        won.addProperty("tekst", wat);
        o.add("beloning", won);
        o.addProperty("index", lb.beloningen.indexOf(prijs));
        return o;
    }

    private JsonObject spelers() {
        JsonObject o = new JsonObject();
        o.add("munt", munt());
        JsonArray arr = new JsonArray();
        for (Saldi.Speler sp : plugin.saldi().all()) {
            JsonObject e = new JsonObject();
            e.addProperty("uuid", sp.uuid.toString());
            e.addProperty("naam", sp.naam);
            e.addProperty("saldo", sp.saldo);
            e.addProperty("reeks", sp.reeks);
            e.addProperty("verdiend", sp.verdiend);
            e.addProperty("uitgegeven", sp.uitgegeven);
            e.addProperty("online", Bukkit.getPlayer(sp.uuid) != null);
            arr.add(e);
        }
        o.add("spelers", arr);
        return o;
    }

    private JsonObject adminGeef(JsonObject b) {
        String naam = str(b, "speler");
        String actie = str(b, "actie");
        long n;
        try { n = b.get("aantal").getAsLong(); } catch (Exception e) { return error("Vul een geldig aantal in."); }
        if (n < 0) return error("Vul een getal van 0 of meer in.");
        Saldi.Speler sp = plugin.zoek(naam);
        if (sp == null) return error("Speler " + naam + " is niet bekend op deze server.");
        String door = "admin website: " + actie;
        switch (actie) {
            case "geef" -> plugin.saldi().add(sp, n, door);
            case "pak" -> plugin.saldi().add(sp, -n, door);
            case "zet" -> plugin.saldi().set(sp, n, door);
            default -> { return error("Onbekende actie."); }
        }
        if (n > 0 && !actie.equals("zet")) notify(sp.uuid, actie.equals("geef") ? "&aJe kreeg " + plugin.munten(n) + "&a van een beheerder." : "&cEr is " + plugin.munten(n) + "&c afgehaald door een beheerder.");
        JsonObject o = new JsonObject();
        o.addProperty("naam", sp.naam);
        o.addProperty("saldo", sp.saldo);
        return o;
    }

    // ----------------------------------------------------------- kluis (Vaulty)
    private Plugin vaulty() {
        Plugin v = Bukkit.getPluginManager().getPlugin("Vaulty");
        return v != null && v.isEnabled() ? v : null;
    }

    /** Legt een item in de kluis via Vaulty. null = gelukt. */
    private String kluis(UUID uuid, String naam, ItemStack item) {
        Plugin v = vaulty();
        if (v == null) return "De kluis (Vaulty) is niet beschikbaar.";
        try {
            Method m = v.getClass().getMethod("deliverToKluis", UUID.class, String.class, List.class);
            Object r = m.invoke(v, uuid, naam, List.of(item));
            return r == null ? null : String.valueOf(r);
        } catch (NoSuchMethodException e) {
            return "Kopen met " + plugin.muntNaam() + " op de website werkt vanaf Vaulty 1.2.0. Werk Vaulty bij en herstart de server.";
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Kon niet leveren aan de kluis", e);
            return "Er ging iets mis met de kluis.";
        }
    }

    /** null als er minstens één vrij vak in de kluis is. */
    private String kluisVrij(UUID uuid, String naam) {
        Plugin v = vaulty();
        if (v == null) return "De kluis (Vaulty) is niet beschikbaar.";
        try {
            Method m = v.getClass().getMethod("freeSlots", UUID.class, String.class);
            int vrij = (Integer) m.invoke(v, uuid, naam);
            return vrij > 0 ? null : "Je kluis is vol. Maak eerst een vak vrij of koop een extra kist.";
        } catch (NoSuchMethodException e) {
            return "Lootboxen op de website werken vanaf Vaulty 1.2.0. Werk Vaulty bij en herstart de server.";
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Kon de kluis niet controleren", e);
            return "Er ging iets mis met de kluis.";
        }
    }

    private void notify(UUID uuid, String text) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(plugin.bericht(text));
    }

    // ---------------------------------------------------------------- hulp
    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : "";
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    private void send(HttpExchange ex, int status, JsonObject o) throws IOException {
        byte[] bytes = gson.toJson(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
