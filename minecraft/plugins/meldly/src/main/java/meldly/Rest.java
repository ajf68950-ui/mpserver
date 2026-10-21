package meldly;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord REST API (v10) met een eigen wachtrij.
 * Alles gaat op volgorde via één thread, zodat logregels in de juiste volgorde in Discord komen,
 * en de rate limits van Discord (X-RateLimit-* en 429) worden netjes gevolgd.
 */
final class Rest {
    /** Fout van Discord met status en Discord-foutcode (bijv. 10003 = kanaal bestaat niet meer, 50013 = rechten). */
    static final class DiscordFout extends RuntimeException {
        final int status;
        final int code;
        DiscordFout(int status, int code, String msg) { super(msg); this.status = status; this.code = code; }
    }

    private record Taak(String methode, String pad, JsonElement body, boolean metToken, CompletableFuture<JsonElement> klaar) {}
    private static final class Emmer { int over = 1; long vrijOp = 0; }

    private static final Pattern SNOWFLAKE = Pattern.compile("/(\\d{15,21})");
    private static final Pattern MAJOR = Pattern.compile("^/(channels|guilds|webhooks)/(\\d+)");

    private final Logger log;
    private final Supplier<String> basis;
    private final Supplier<String> token;
    private final HttpClient http;
    private final LinkedBlockingQueue<Taak> wachtrij = new LinkedBlockingQueue<>();
    private final Map<String, Emmer> emmers = new HashMap<>();
    private final Map<String, String> routeNaarEmmer = new HashMap<>();
    private volatile long globaalVrijOp = 0;
    private volatile boolean stoppen = false;
    private final Thread werker;

    Rest(Logger log, Supplier<String> basis, Supplier<String> token) {
        this.log = log;
        this.basis = basis;
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.werker = new Thread(this::werk, "Meldly-discord-rest");
        this.werker.setDaemon(true);
        this.werker.start();
    }

    HttpClient http() { return http; }

    CompletableFuture<JsonElement> get(String pad) { return zet("GET", pad, null, true); }
    CompletableFuture<JsonElement> post(String pad, JsonElement body) { return zet("POST", pad, body, true); }
    CompletableFuture<JsonElement> patch(String pad, JsonElement body) { return zet("PATCH", pad, body, true); }
    CompletableFuture<JsonElement> put(String pad, JsonElement body) { return zet("PUT", pad, body, true); }
    CompletableFuture<JsonElement> delete(String pad) { return zet("DELETE", pad, null, true); }
    /** Webhooks en antwoorden op slash-commando's hebben geen bot-token nodig. */
    CompletableFuture<JsonElement> postZonderToken(String pad, JsonElement body) { return zet("POST", pad, body, false); }

    int wachtend() { return wachtrij.size(); }

    private CompletableFuture<JsonElement> zet(String methode, String pad, JsonElement body, boolean metToken) {
        CompletableFuture<JsonElement> f = new CompletableFuture<>();
        if (stoppen) { f.completeExceptionally(new IllegalStateException("gestopt")); return f; }
        wachtrij.add(new Taak(methode, pad, body, metToken, f));
        return f;
    }

    /** Stopt na het afwerken van wat er al in de wachtrij staat (hoogstens zoveel ms wachten). */
    void stop(long maxMs) {
        stoppen = true;
        long tot = System.currentTimeMillis() + maxMs;
        while (!wachtrij.isEmpty() && System.currentTimeMillis() < tot) {
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        werker.interrupt();
        Taak t;
        while ((t = wachtrij.poll()) != null) t.klaar.completeExceptionally(new IllegalStateException("gestopt"));
    }

    private void werk() {
        while (true) {
            Taak t;
            try { t = wachtrij.take(); } catch (InterruptedException e) { return; }
            try {
                t.klaar.complete(voerUit(t));
            } catch (InterruptedException e) {
                t.klaar.completeExceptionally(e);
                return;
            } catch (Throwable e) {
                t.klaar.completeExceptionally(e);
            }
        }
    }

    private static String routeSleutel(String methode, String pad) {
        String zonderQuery = pad.split("\\?", 2)[0];
        Matcher m = MAJOR.matcher(zonderQuery);
        String major = "";
        String rest = zonderQuery;
        if (m.find()) { major = m.group(0); rest = zonderQuery.substring(m.end()); }
        // berichten verwijderen heeft een eigen limiet; verder alle id's na de major gelijk behandelen
        return methode + " " + major + SNOWFLAKE.matcher(rest).replaceAll("/{id}");
    }

    private JsonElement voerUit(Taak t) throws InterruptedException {
        String route = routeSleutel(t.methode, t.pad);
        int pogingen = 0;
        while (true) {
            pogingen++;
            long nu = System.currentTimeMillis();
            if (globaalVrijOp > nu) Thread.sleep(globaalVrijOp - nu);
            String emmerId = routeNaarEmmer.get(route);
            Emmer e = emmerId == null ? null : emmers.get(emmerId + "|" + majorVan(t.pad));
            if (e != null && e.over <= 0) {
                long wacht = e.vrijOp - System.currentTimeMillis();
                if (wacht > 0) Thread.sleep(Math.min(wacht, 60_000));
            }

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(basis.get() + t.pad))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "DiscordBot (https://github.com/ajf68950-ui/mpserver, 1.0) Meldly");
            if (t.metToken) b.header("Authorization", "Bot " + token.get());
            if (t.body != null) {
                b.header("Content-Type", "application/json");
                b.method(t.methode, HttpRequest.BodyPublishers.ofString(t.body.toString(), StandardCharsets.UTF_8));
            } else if (t.methode.equals("PUT") || t.methode.equals("POST")) {
                b.method(t.methode, HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8));
            } else {
                b.method(t.methode, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r;
            try {
                r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (java.io.IOException io) {
                if (pogingen < 3) { Thread.sleep(1000L * pogingen); continue; }
                throw new DiscordFout(0, 0, "Discord niet bereikbaar: " + io.getMessage());
            }
            leesLimieten(route, t.pad, r);
            int s = r.statusCode();
            if (s == 429) {
                double na = 1.0;
                boolean globaal = false;
                try {
                    JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                    if (o.has("retry_after")) na = o.get("retry_after").getAsDouble();
                    globaal = o.has("global") && o.get("global").getAsBoolean();
                } catch (Exception ignored) { }
                long ms = (long) Math.ceil(na * 1000) + 50;
                if (globaal) globaalVrijOp = System.currentTimeMillis() + ms;
                if (pogingen > 6) throw new DiscordFout(429, 0, "Te vaak een rate limit van Discord.");
                Thread.sleep(Math.min(ms, 60_000));
                continue;
            }
            if (s >= 500 && pogingen < 3) { Thread.sleep(1000L * pogingen); continue; }
            String body = r.body();
            JsonElement j = JsonNull.INSTANCE;
            if (body != null && !body.isBlank()) {
                try { j = JsonParser.parseString(body); } catch (Exception ignored) { }
            }
            if (s >= 400) {
                int code = 0;
                String msg = "HTTP " + s;
                if (j.isJsonObject()) {
                    JsonObject o = j.getAsJsonObject();
                    if (o.has("code")) try { code = o.get("code").getAsInt(); } catch (Exception ignored) { }
                    if (o.has("message")) msg = o.get("message").getAsString();
                }
                throw new DiscordFout(s, code, msg);
            }
            return j;
        }
    }

    private static String majorVan(String pad) {
        Matcher m = MAJOR.matcher(pad.split("\\?", 2)[0]);
        return m.find() ? m.group(0) : "";
    }

    private void leesLimieten(String route, String pad, HttpResponse<String> r) {
        String bucket = r.headers().firstValue("X-RateLimit-Bucket").orElse(null);
        if (bucket == null) return;
        routeNaarEmmer.put(route, bucket);
        Emmer e = emmers.computeIfAbsent(bucket + "|" + majorVan(pad), k -> new Emmer());
        try { e.over = Integer.parseInt(r.headers().firstValue("X-RateLimit-Remaining").orElse("1")); } catch (NumberFormatException ignored) { }
        try {
            double na = Double.parseDouble(r.headers().firstValue("X-RateLimit-Reset-After").orElse("0"));
            e.vrijOp = System.currentTimeMillis() + (long) Math.ceil(na * 1000);
        } catch (NumberFormatException ignored) { }
        if (emmers.size() > 2000) emmers.clear();
    }

    static String melding(Throwable t) {
        while (t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException) {
            if (t.getCause() == null) break;
            t = t.getCause();
        }
        if (t instanceof DiscordFout d) {
            return switch (d.code) {
                case 50013 -> "De bot mist rechten in deze Discord-server (" + d.getMessage() + ").";
                case 50001 -> "De bot kan dit kanaal niet zien.";
                case 10003 -> "Het kanaal bestaat niet meer.";
                case 10004 -> "De bot zit niet (meer) in deze Discord-server.";
                case 10007 -> "Die persoon zit niet in deze Discord-server.";
                case 10011 -> "Die rol bestaat niet meer.";
                case 0 -> d.getMessage();
                default -> d.getMessage() + " (code " + d.code + ")";
            };
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    static int code(Throwable t) {
        while (t != null && !(t instanceof DiscordFout)) t = t.getCause();
        return t instanceof DiscordFout d ? d.code : -1;
    }
}
