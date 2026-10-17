package ordely;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Logboek van alles wat met regels en de gevangenis te maken heeft.
 * logboek.txt is voor mensen, logboek.jsonl leest de website (Beheer > Gevangenis).
 */
final class Logboek {

    static final DateTimeFormatter TIJD = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Gson GSON = new Gson();

    private final Ordely plugin;
    private final File txt;
    private final File jsonl;

    Logboek(Ordely plugin) {
        this.plugin = plugin;
        plugin.getDataFolder().mkdirs();
        this.txt = new File(plugin.getDataFolder(), "logboek.txt");
        this.jsonl = new File(plugin.getDataFolder(), "logboek.jsonl");
    }

    /**
     * @param actie  OPGESLOTEN, VRIJGELATEN, AANGEPAST, WAARSCHUWING, GEBLOKKEERD, CORRECTIE
     * @param minuten -1 als het niet van toepassing is, 0 = onbepaalde tijd
     */
    synchronized void schrijf(String actie, String speler, UUID uuid, String reden, long minuten, String door, String regel) {
        String tijd = LocalDateTime.now().format(TIJD);
        JsonObject o = new JsonObject();
        o.addProperty("tijd", tijd);
        o.addProperty("actie", actie);
        o.addProperty("speler", speler);
        if (uuid != null) o.addProperty("uuid", uuid.toString());
        o.addProperty("reden", reden == null ? "" : reden);
        if (minuten >= 0) o.addProperty("minuten", minuten);
        o.addProperty("door", door == null ? "" : door);
        if (regel != null) o.addProperty("regel", regel);
        StringBuilder sb = new StringBuilder();
        sb.append(tijd).append(" | ").append(String.format("%-12s", actie)).append(" | ").append(speler);
        if (minuten > 0) sb.append(" | ").append(minuten).append(" min");
        else if (minuten == 0) sb.append(" | onbepaalde tijd");
        sb.append(" | door ").append(door == null || door.isBlank() ? "-" : door);
        if (reden != null && !reden.isBlank()) sb.append(" | reden: ").append(reden);
        try {
            Files.writeString(txt.toPath(), sb.append(System.lineSeparator()).toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(jsonl.toPath(), GSON.toJson(o) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Logboek kon niet worden geschreven: " + e.getMessage());
        }
        plugin.getLogger().info("Logboek: " + sb.toString().trim());
    }

    /** De laatste n regels van het logboek, nieuwste eerst. */
    synchronized List<JsonObject> laatste(int n) {
        List<JsonObject> uit = new ArrayList<>();
        if (!jsonl.isFile()) return uit;
        try (RandomAccessFile raf = new RandomAccessFile(jsonl, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - 256L * 1024);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String[] regels = new String(buf, StandardCharsets.UTF_8).split("\n");
            for (int i = regels.length - 1; i >= 0 && uit.size() < n; i--) {
                String r = regels[i].trim();
                if (r.isEmpty() || (i == 0 && start > 0)) continue;
                try { uit.add(JsonParser.parseString(r).getAsJsonObject()); } catch (Exception ignored) { }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Logboek kon niet worden gelezen: " + e.getMessage());
        }
        return uit;
    }

    static List<JsonObject> omgekeerd(List<JsonObject> l) { List<JsonObject> c = new ArrayList<>(l); Collections.reverse(c); return c; }
}
