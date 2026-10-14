package muntly;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Munten per speler, bewaard in spelers.yml. Alleen op de main thread gebruiken. */
final class Saldi {

    static final class Speler {
        final UUID uuid;
        String naam;
        long saldo;
        int minuten;          // gespaarde speelminuten richting de volgende beloning
        String laatsteDag = ""; // datum van de laatste dagbeloning (jjjj-mm-dd)
        int reeks;            // aantal dagen achter elkaar
        long verdiend;        // totaal ooit gekregen
        long uitgegeven;      // totaal ooit uitgegeven

        Speler(UUID uuid, String naam) { this.uuid = uuid; this.naam = naam == null ? "" : naam; }
    }

    private static final DateTimeFormatter LOG_TIJD = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Muntly plugin;
    private final File file;
    private final File logFile;
    private final Map<UUID, Speler> spelers = new HashMap<>();
    private boolean dirty;

    Saldi(Muntly plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spelers.yml");
        this.logFile = new File(plugin.getDataFolder(), "logboek.txt");
        load();
        // elke minuut opslaan als er iets veranderd is
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 1200L, 1200L);
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException e) { continue; }
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            Speler sp = new Speler(id, s.getString("naam", ""));
            sp.saldo = Math.max(0, s.getLong("saldo"));
            sp.minuten = s.getInt("minuten");
            sp.laatsteDag = s.getString("laatste-dag", "");
            sp.reeks = s.getInt("reeks");
            sp.verdiend = s.getLong("verdiend");
            sp.uitgegeven = s.getLong("uitgegeven");
            spelers.put(id, sp);
        }
    }

    Speler get(UUID uuid, String naam) {
        Speler sp = spelers.computeIfAbsent(uuid, u -> { dirty = true; return new Speler(u, naam); });
        if (naam != null && !naam.isBlank() && !naam.equals(sp.naam)) { sp.naam = naam; dirty = true; }
        return sp;
    }

    Speler find(UUID uuid) { return spelers.get(uuid); }

    Speler findByName(String naam) {
        for (Speler sp : spelers.values()) if (sp.naam.equalsIgnoreCase(naam)) return sp;
        return null;
    }

    void touch() { dirty = true; }

    /** Geeft (of met een negatief getal: pakt) Munten. Saldo gaat nooit onder 0. */
    void add(Speler sp, long delta, String reden) {
        long oud = sp.saldo;
        sp.saldo = Math.max(0, sp.saldo + delta);
        long echt = sp.saldo - oud;
        if (echt > 0 && !reden.startsWith("admin")) sp.verdiend += echt;
        dirty = true;
        log(sp, echt, reden);
    }

    /** Haalt Munten af als er genoeg is. */
    boolean take(Speler sp, long bedrag, String reden) {
        if (bedrag < 0 || sp.saldo < bedrag) return false;
        sp.saldo -= bedrag;
        sp.uitgegeven += bedrag;
        dirty = true;
        log(sp, -bedrag, reden);
        return true;
    }

    void set(Speler sp, long nieuw, String reden) {
        long oud = sp.saldo;
        sp.saldo = Math.max(0, nieuw);
        dirty = true;
        log(sp, sp.saldo - oud, reden);
    }

    List<Speler> top(int n) {
        List<Speler> list = new ArrayList<>();
        for (Speler sp : spelers.values()) if (sp.saldo > 0) list.add(sp);
        list.sort(Comparator.comparingLong((Speler s) -> s.saldo).reversed().thenComparing(s -> s.naam.toLowerCase(Locale.ROOT)));
        return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
    }

    List<Speler> all() {
        List<Speler> list = new ArrayList<>(spelers.values());
        list.sort(Comparator.comparing(s -> s.naam.toLowerCase(Locale.ROOT)));
        return list;
    }

    // ---------------------------------------------------------------- opslaan
    private String snapshot() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Muntly: Munten per speler. Wordt door de plugin bijgehouden; pas aan met /munten zet <speler> <aantal>."));
        for (Speler sp : spelers.values()) {
            String k = sp.uuid.toString();
            y.set(k + ".naam", sp.naam);
            y.set(k + ".saldo", sp.saldo);
            y.set(k + ".minuten", sp.minuten);
            y.set(k + ".laatste-dag", sp.laatsteDag);
            y.set(k + ".reeks", sp.reeks);
            y.set(k + ".verdiend", sp.verdiend);
            y.set(k + ".uitgegeven", sp.uitgegeven);
        }
        return y.saveToString();
    }

    private void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        String text = snapshot();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(text));
    }

    void saveNow() {
        dirty = false;
        write(snapshot());
    }

    private synchronized void write(String text) {
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException e2) {
                plugin.getLogger().log(Level.SEVERE, "Kon spelers.yml niet opslaan", e2);
            }
        }
    }

    private void log(Speler sp, long delta, String reden) {
        if (delta == 0) return;
        String line = LocalDateTime.now().format(LOG_TIJD) + "  " + sp.naam + "  " + (delta > 0 ? "+" : "") + delta
                + "  (saldo " + sp.saldo + ")  " + reden + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (logFile) {
                try {
                    Files.writeString(logFile.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // logboek is niet belangrijk genoeg om op te stoppen
                }
            }
        });
    }
}
