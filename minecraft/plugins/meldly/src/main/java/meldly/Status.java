package meldly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Het live statusbericht in het statuskanaal (wordt steeds aangepast, niet opnieuw geplaatst) en de status van de bot. */
final class Status {
    private final Meldly plugin;
    private final Bot bot;
    private final Map<String, String> laatst = new ConcurrentHashMap<>();
    private final Map<String, Long> laatstTijd = new ConcurrentHashMap<>();
    private final Map<String, Boolean> bezig = new ConcurrentHashMap<>();

    Status(Meldly plugin, Bot bot) {
        this.plugin = plugin;
        this.bot = bot;
    }

    static String duur(long ms) {
        long min = Math.max(0, ms / 60_000);
        long d = min / 1440, u = (min % 1440) / 60, m = min % 60;
        if (d > 0) return d + "d " + u + "u";
        if (u > 0) return u + "u " + m + "m";
        return m + "m";
    }

    /** Meteen bijwerken (bijv. nadat het statuskanaal is aangezet). */
    void nu() {
        laatst.clear();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> werkBij(plugin.snapshot(), true));
    }

    void werkBij(Meldly.Snapshot s, boolean online) {
        bot.updateAanwezigheid(s);
        Rest rest = bot.rest;
        if (rest == null) return;
        for (Opslag.GuildInst gi : plugin.opslag().alle()) {
            Opslag.CatInst ci = gi.cat(Categorie.STATUS);
            if (!ci.aan || ci.kanaal.isEmpty() || !bot.guilds.containsKey(gi.id)) continue;
            JsonObject embed = embed(s, online);
            String sleutel = gi.id + "|" + ci.kanaal;
            String inhoud = online + "|" + s.namen() + "|" + Math.round(s.tps() / 2) + "|" + s.max();
            long nu = System.currentTimeMillis();
            boolean gelijk = inhoud.equals(laatst.get(sleutel)) && nu - laatstTijd.getOrDefault(sleutel, 0L) < 10 * 60_000L;
            if (online && gelijk) continue;
            if (bezig.putIfAbsent(sleutel, true) != null) continue;
            laatst.put(sleutel, inhoud);
            laatstTijd.put(sleutel, nu);
            JsonObject b = new JsonObject();
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            b.add("embeds", embeds);
            b.add("allowed_mentions", Bot.geenMentions());
            var f = gi.statusBericht.isEmpty() ? plaats(rest, gi, ci, b)
                    : rest.patch("/channels/" + ci.kanaal + "/messages/" + gi.statusBericht, b)
                        .handle((j, e) -> {
                            if (e == null) return java.util.concurrent.CompletableFuture.completedFuture(j);
                            if (Rest.code(e) == 10008 || Rest.code(e) == 50005) { gi.statusBericht = ""; return plaats(rest, gi, ci, b); }
                            return java.util.concurrent.CompletableFuture.<JsonElement>failedFuture(e);
                        }).thenCompose(x -> x);
            f.whenComplete((j, e) -> {
                bezig.remove(sleutel);
                if (e != null) { laatst.remove(sleutel); bot.fout("Statusbericht in " + gi.naam + ": " + Rest.melding(e)); }
            });
            if (!online) {
                try { f.get(4, TimeUnit.SECONDS); } catch (Exception ignored) { }
            }
        }
    }

    private java.util.concurrent.CompletableFuture<JsonElement> plaats(Rest rest, Opslag.GuildInst gi, Opslag.CatInst ci, JsonObject b) {
        return rest.post("/channels/" + ci.kanaal + "/messages", b).thenApply(j -> {
            String id = j.getAsJsonObject().get("id").getAsString();
            gi.statusBericht = id;
            plugin.opslag().bewaar();
            if (plugin.getConfig().getBoolean("status.vastpinnen", true)) rest.put("/channels/" + ci.kanaal + "/pins/" + id, null).exceptionally(e -> null);
            return j;
        });
    }

    private JsonObject embed(Meldly.Snapshot s, boolean online) {
        JsonObject e = new JsonObject();
        String naam = plugin.getConfig().getString("status.servernaam", "Minecraft-server");
        e.addProperty("title", (online ? "🟢 " : "🔴 ") + naam + (online ? " is online" : " is offline"));
        e.addProperty("color", online ? 0x7DDC4A : 0xE0503C);
        JsonArray velden = new JsonArray();
        if (online) {
            veld(velden, "Spelers", s.namen().size() + " / " + s.max(), true);
            veld(velden, "TPS", String.format(Locale.ROOT, "%.1f", s.tps()), true);
            veld(velden, "Aan", "aan sinds " + duur(System.currentTimeMillis() - plugin.gestart()), true);
            List<String> namen = s.namen();
            String lijst = namen.isEmpty() ? "_Niemand online_" : Bot.md(String.join(", ", namen.size() > 60 ? namen.subList(0, 60) : namen))
                    + (namen.size() > 60 ? " en nog " + (namen.size() - 60) : "");
            veld(velden, "Online", lijst, false);
        } else {
            veld(velden, "Spelers", "0", true);
        }
        String adres = plugin.getConfig().getString("status.adres", "");
        if (!adres.isBlank()) veld(velden, "Adres", "`" + adres + "`", true);
        veld(velden, "Versie", s.versie(), true);
        e.add("fields", velden);
        JsonObject voet = new JsonObject();
        voet.addProperty("text", "Bijgewerkt");
        e.add("footer", voet);
        e.addProperty("timestamp", Instant.now().toString());
        return e;
    }

    private static void veld(JsonArray a, String naam, String waarde, boolean inline) {
        JsonObject v = new JsonObject();
        v.addProperty("name", naam);
        v.addProperty("value", waarde.length() > 1000 ? waarde.substring(0, 1000) + "…" : waarde);
        v.addProperty("inline", inline);
        a.add(v);
    }
}
