package meldly;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verzamelt logregels per Discord-server en categorie en stuurt ze elke paar seconden in één bericht.
 * Zo blijft het overzichtelijk (één blok per soort) en blijft de bot ruim binnen de limieten van Discord.
 */
final class Uitvoer {
    /** Eén gebeurtenis. auteur/icoon = naam en hoofd van de speler (mag leeg). */
    record Regel(String tekst, String auteur, String icoon, Integer kleur, long tijd) {
        Regel(String tekst, String auteur, String icoon, Integer kleur) { this(tekst, auteur, icoon, kleur, System.currentTimeMillis()); }
        Regel(String tekst) { this(tekst, null, null, null, System.currentTimeMillis()); }
    }

    private static final class Wachtrij {
        final Deque<Regel> regels = new ArrayDeque<>();
        int weggelaten = 0;
    }

    private static final int MAX_WACHTEND = 300;
    private static final DateTimeFormatter UUR = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Meldly plugin;
    private final Bot bot;
    private final Map<String, Wachtrij> wacht = new ConcurrentHashMap<>();

    Uitvoer(Meldly plugin, Bot bot) {
        this.plugin = plugin;
        this.bot = bot;
    }

    /** Zet een gebeurtenis klaar voor alle Discord-servers waar deze categorie aan staat. */
    void meld(Categorie c, Regel r) {
        for (Opslag.GuildInst gi : plugin.opslag().alle()) {
            Opslag.CatInst ci = gi.cat(c);
            if (!ci.aan || ci.kanaal.isEmpty()) continue;
            Wachtrij w = wacht.computeIfAbsent(gi.id + "|" + c.name(), k -> new Wachtrij());
            synchronized (w) {
                if (w.regels.size() >= MAX_WACHTEND) w.weggelaten++;
                else w.regels.add(r);
            }
        }
    }

    /** Alles wat klaarstaat versturen (wordt elke paar seconden aangeroepen, en bij afsluiten). */
    void verstuur() {
        Rest rest = bot.rest;
        if (rest == null) return;
        for (Map.Entry<String, Wachtrij> e : wacht.entrySet()) {
            Wachtrij w = e.getValue();
            List<Regel> lijst;
            int weg;
            synchronized (w) {
                if (w.regels.isEmpty() && w.weggelaten == 0) continue;
                lijst = new ArrayList<>(w.regels);
                w.regels.clear();
                weg = w.weggelaten;
                w.weggelaten = 0;
            }
            String[] k = e.getKey().split("\\|");
            Opslag.GuildInst gi = plugin.opslag().bestaand(k[0]);
            Categorie c = Categorie.valueOf(k[1]);
            if (gi == null) continue;
            Opslag.CatInst ci = gi.cat(c);
            if (!ci.aan || ci.kanaal.isEmpty() || !bot.guilds.containsKey(gi.id)) continue;
            for (JsonObject bericht : berichten(c, lijst, weg)) {
                String kanaal = ci.kanaal;
                rest.post("/channels/" + kanaal + "/messages", bericht).whenComplete((j, ex) -> {
                    if (ex == null) return;
                    int code = Rest.code(ex);
                    if (code == 10003) {
                        ci.aan = false; ci.kanaal = "";
                        plugin.opslag().bewaar();
                        bot.fout("Het kanaal voor " + c.label + " in " + gi.naam + " bestaat niet meer; die categorie staat nu uit.");
                    } else bot.fout(c.label + " naar " + gi.naam + ": " + Rest.melding(ex));
                });
            }
        }
    }

    private List<JsonObject> berichten(Categorie c, List<Regel> lijst, int weggelaten) {
        List<JsonObject> uit = new ArrayList<>();
        if (c.compact) {
            StringBuilder b = new StringBuilder();
            for (Regel r : lijst) {
                String regel = "`" + UUR.format(Instant.ofEpochMilli(r.tijd())) + "` " + r.tekst();
                if (regel.length() > 1800) regel = regel.substring(0, 1800) + "…";
                if (b.length() + regel.length() + 1 > 3900) { uit.add(compactBericht(c, b.toString())); b.setLength(0); }
                if (!b.isEmpty()) b.append('\n');
                b.append(regel);
            }
            if (weggelaten > 0) {
                if (!b.isEmpty()) b.append('\n');
                b.append("_(nog ").append(weggelaten).append(" regels weggelaten, het was te druk)_");
            }
            if (!b.isEmpty()) uit.add(compactBericht(c, b.toString()));
            return uit;
        }
        JsonArray embeds = new JsonArray();
        int tekens = 0;
        for (Regel r : lijst) {
            JsonObject e = new JsonObject();
            String tekst = r.tekst().length() > 3900 ? r.tekst().substring(0, 3900) + "…" : r.tekst();
            e.addProperty("description", tekst);
            e.addProperty("color", r.kleur() != null ? r.kleur() : c.kleur);
            e.addProperty("timestamp", Instant.ofEpochMilli(r.tijd()).toString());
            if (r.auteur() != null && !r.auteur().isBlank()) {
                JsonObject a = new JsonObject();
                a.addProperty("name", r.auteur());
                if (r.icoon() != null && !r.icoon().isBlank()) a.addProperty("icon_url", r.icoon());
                e.add("author", a);
            }
            int lengte = tekst.length() + (r.auteur() == null ? 0 : r.auteur().length());
            if (embeds.size() == 10 || tekens + lengte > 5500) {
                uit.add(embedBericht(embeds));
                embeds = new JsonArray();
                tekens = 0;
            }
            embeds.add(e);
            tekens += lengte;
        }
        if (weggelaten > 0) {
            JsonObject e = new JsonObject();
            e.addProperty("description", "_(nog " + weggelaten + " gebeurtenissen weggelaten, het was te druk)_");
            e.addProperty("color", c.kleur);
            if (embeds.size() == 10) { uit.add(embedBericht(embeds)); embeds = new JsonArray(); }
            embeds.add(e);
        }
        if (!embeds.isEmpty()) uit.add(embedBericht(embeds));
        return uit;
    }

    private static JsonObject compactBericht(Categorie c, String tekst) {
        JsonObject e = new JsonObject();
        e.addProperty("description", tekst);
        e.addProperty("color", c.kleur);
        JsonArray a = new JsonArray();
        a.add(e);
        return embedBericht(a);
    }

    private static JsonObject embedBericht(JsonArray embeds) {
        JsonObject b = new JsonObject();
        b.add("embeds", embeds);
        b.add("allowed_mentions", Bot.geenMentions());
        return b;
    }
}
