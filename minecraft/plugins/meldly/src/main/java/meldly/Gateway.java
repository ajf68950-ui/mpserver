package meldly;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Verbinding met de Discord-gateway (websocket, API v10, JSON).
 * Doet heartbeats, identify, resume na een onderbreking en opnieuw verbinden met wachttijd.
 */
final class Gateway implements WebSocket.Listener {
    /** GUILDS, GUILD_MESSAGES, MESSAGE_CONTENT */
    static final int INTENTS = 1 | (1 << 9) | (1 << 15);

    private final Logger log;
    private final HttpClient http;
    private final String token;
    private final String startUrl;
    private final BiConsumer<String, JsonObject> opGebeurtenis;
    private final Consumer<String> opStatus;
    private final ScheduledExecutorService klok = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Meldly-discord-gateway");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket ws;
    private volatile String sessieId;
    private volatile String hervatUrl;
    private volatile Integer volgnummer;
    private volatile boolean ackOntvangen = true;
    private volatile boolean gestopt = false;
    private volatile int wachtMs = 1000;
    private volatile JsonObject aanwezigheid;
    private ScheduledFuture<?> hartslag;
    private final StringBuilder buffer = new StringBuilder();
    private volatile long laatsteAck = 0, laatsteBeat = 0;
    volatile int ping = -1;

    Gateway(Logger log, HttpClient http, String token, String startUrl,
            BiConsumer<String, JsonObject> opGebeurtenis, Consumer<String> opStatus) {
        this.log = log;
        this.http = http;
        this.token = token;
        this.startUrl = startUrl;
        this.opGebeurtenis = opGebeurtenis;
        this.opStatus = opStatus;
    }

    void start() { klok.execute(() -> verbind(false)); }

    void stop() {
        gestopt = true;
        if (hartslag != null) hartslag.cancel(false);
        WebSocket w = ws;
        if (w != null) {
            try { w.sendClose(1000, "afsluiten").get(3, TimeUnit.SECONDS); } catch (Exception ignored) { }
            w.abort();
        }
        klok.shutdownNow();
    }

    void zetAanwezigheid(JsonObject d) {
        aanwezigheid = d;
        stuur(3, d);
    }

    private static String metParams(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return u + "/?v=10&encoding=json";
    }

    private void verbind(boolean hervatten) {
        if (gestopt) return;
        String url = hervatten && hervatUrl != null ? hervatUrl : startUrl;
        if (!hervatten) { sessieId = null; volgnummer = null; }
        synchronized (buffer) { buffer.setLength(0); }
        try {
            ws = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(metParams(url)), this)
                    .get(20, TimeUnit.SECONDS);
            opStatus.accept("verbinden");
        } catch (Exception e) {
            log.warning("Kan geen verbinding maken met Discord: " + Rest.melding(e) + ". Opnieuw over " + (wachtMs / 1000) + " s.");
            opStatus.accept("fout: " + Rest.melding(e));
            planOpnieuw(false);
        }
    }

    private void planOpnieuw(boolean hervatten) {
        if (gestopt) return;
        if (hartslag != null) hartslag.cancel(false);
        int w = wachtMs;
        wachtMs = Math.min(wachtMs * 2, 60_000);
        try { klok.schedule(() -> verbind(hervatten), w, TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
    }

    private void stuur(int op, JsonElement d) {
        WebSocket w = ws;
        if (w == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.add("d", d);
        synchronized (this) {
            try { w.sendText(o.toString(), true).get(10, TimeUnit.SECONDS); } catch (Exception e) {
                log.fine("Versturen naar gateway mislukt: " + e.getMessage());
            }
        }
    }

    private void beat() {
        if (!ackOntvangen) {
            log.info("Discord antwoordt niet op de hartslag, opnieuw verbinden.");
            WebSocket w = ws;
            if (w != null) w.abort();
            planOpnieuw(true);
            return;
        }
        ackOntvangen = false;
        laatsteBeat = System.currentTimeMillis();
        JsonElement seq = volgnummer == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(volgnummer);
        stuur(1, seq);
    }

    // ---------------------------------------------------------------- websocket
    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        String tekst = null;
        synchronized (buffer) {
            buffer.append(data);
            if (last) { tekst = buffer.toString(); buffer.setLength(0); }
        }
        if (tekst != null) {
            final String t = tekst;
            klok.execute(() -> verwerk(t));
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int code, String reden) {
        if (webSocket != ws) return null;
        ws = null;
        if (hartslag != null) hartslag.cancel(false);
        if (gestopt) return null;
        String fataal = switch (code) {
            case 4004 -> "Het bot-token klopt niet. Zet een nieuw token in Beheer > Discord.";
            case 4010, 4011 -> "Discord weigert de verbinding (shards).";
            case 4012 -> "Verouderde gateway-versie.";
            case 4013 -> "Ongeldige intents.";
            case 4014 -> "Zet in de Discord Developer Portal bij je bot de 'Message Content Intent' aan (Bot > Privileged Gateway Intents) en sla op.";
            default -> null;
        };
        if (fataal != null) {
            log.severe("Discord sloot de verbinding (" + code + "): " + fataal);
            opStatus.accept("fout: " + fataal);
            return null;
        }
        boolean hervat = code != 4007 && code != 4009 && code != 1000 && sessieId != null;
        log.info("Verbinding met Discord verbroken (" + code + (reden == null || reden.isBlank() ? "" : ", " + reden) + "), opnieuw verbinden.");
        opStatus.accept("verbinden");
        planOpnieuw(hervat);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (webSocket != ws && ws != null) return;
        ws = null;
        if (gestopt) return;
        log.warning("Fout in de verbinding met Discord: " + error.getMessage());
        opStatus.accept("verbinden");
        planOpnieuw(sessieId != null);
    }

    private void verwerk(String tekst) {
        JsonObject o;
        try { o = JsonParser.parseString(tekst).getAsJsonObject(); } catch (Exception e) { return; }
        int op = o.get("op").getAsInt();
        if (o.has("s") && !o.get("s").isJsonNull()) volgnummer = o.get("s").getAsInt();
        switch (op) {
            case 10 -> { // Hello
                long interval = o.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                ackOntvangen = true;
                if (hartslag != null) hartslag.cancel(false);
                long eerste = (long) (interval * Math.random());
                hartslag = klok.scheduleAtFixedRate(this::beat, eerste, interval, TimeUnit.MILLISECONDS);
                if (sessieId != null && volgnummer != null) {
                    JsonObject d = new JsonObject();
                    d.addProperty("token", token);
                    d.addProperty("session_id", sessieId);
                    d.addProperty("seq", volgnummer);
                    stuur(6, d);
                } else {
                    identify();
                }
            }
            case 11 -> { ackOntvangen = true; laatsteAck = System.currentTimeMillis(); ping = (int) (laatsteAck - laatsteBeat); }
            case 1 -> beat();
            case 7 -> { // Reconnect
                WebSocket w = ws;
                ws = null;
                if (w != null) w.abort();
                wachtMs = 1000;
                planOpnieuw(true);
            }
            case 9 -> { // Invalid session
                boolean hervatbaar = o.has("d") && o.get("d").isJsonPrimitive() && o.get("d").getAsBoolean();
                if (!hervatbaar) { sessieId = null; volgnummer = null; }
                try { Thread.sleep(1000 + (long) (Math.random() * 4000)); } catch (InterruptedException ignored) { }
                if (hervatbaar && sessieId != null) {
                    JsonObject d = new JsonObject();
                    d.addProperty("token", token);
                    d.addProperty("session_id", sessieId);
                    d.addProperty("seq", volgnummer);
                    stuur(6, d);
                } else identify();
            }
            case 0 -> {
                String t = o.get("t").getAsString();
                JsonObject d = o.has("d") && o.get("d").isJsonObject() ? o.getAsJsonObject("d") : new JsonObject();
                if (t.equals("READY")) {
                    sessieId = d.get("session_id").getAsString();
                    if (d.has("resume_gateway_url")) hervatUrl = d.get("resume_gateway_url").getAsString();
                    wachtMs = 1000;
                    opStatus.accept("verbonden");
                } else if (t.equals("RESUMED")) {
                    wachtMs = 1000;
                    opStatus.accept("verbonden");
                }
                try { opGebeurtenis.accept(t, d); } catch (Throwable e) {
                    log.warning("Fout bij Discord-gebeurtenis " + t + ": " + e);
                }
            }
            default -> { }
        }
    }

    private void identify() {
        JsonObject d = new JsonObject();
        d.addProperty("token", token);
        d.addProperty("intents", INTENTS);
        JsonObject p = new JsonObject();
        p.addProperty("os", System.getProperty("os.name", "windows").toLowerCase());
        p.addProperty("browser", "Meldly");
        p.addProperty("device", "Meldly");
        d.add("properties", p);
        if (aanwezigheid != null) d.add("presence", aanwezigheid);
        stuur(2, d);
    }
}
