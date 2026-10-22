package ritly;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Alle voertuigen van alle spelers (voertuigen.yml). */
final class Garage {

    private final Ritly plugin;
    private final File bestand;
    private final Map<UUID, Voertuig> voertuigen = new LinkedHashMap<>();
    private boolean gewijzigd;

    Garage(Ritly plugin) {
        this.plugin = plugin;
        this.bestand = new File(plugin.getDataFolder(), "voertuigen.yml");
    }

    void laad() {
        voertuigen.clear();
        if (!bestand.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(bestand);
        ConfigurationSection s = y.getConfigurationSection("voertuigen");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                voertuigen.put(id, Voertuig.lees(id, s.getConfigurationSection(k)));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Voertuig " + k + " kon niet geladen worden: " + e.getMessage());
            }
        }
        plugin.getLogger().info(voertuigen.size() + " voertuigen van spelers geladen.");
    }

    void wijzig() {
        gewijzigd = true;
    }

    void opslaanAlsGewijzigd() {
        if (gewijzigd) opslaan();
    }

    void opslaan() {
        gewijzigd = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Voertuig v : voertuigen.values()) v.schrijf(y.createSection("voertuigen." + v.id));
        try {
            File tmp = new File(bestand.getParentFile(), "voertuigen.yml.tmp");
            y.save(tmp);
            Files.move(tmp.toPath(), bestand.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                y.save(bestand);
            } catch (IOException e2) {
                plugin.getLogger().severe("voertuigen.yml opslaan mislukt: " + e2.getMessage());
            }
        }
    }

    Voertuig get(UUID id) {
        return voertuigen.get(id);
    }

    void voegToe(Voertuig v) {
        voertuigen.put(v.id, v);
        wijzig();
    }

    void verwijder(Voertuig v) {
        voertuigen.remove(v.id);
        wijzig();
    }

    Collection<Voertuig> alle() {
        return voertuigen.values();
    }

    List<Voertuig> van(UUID speler) {
        List<Voertuig> l = new ArrayList<>();
        for (Voertuig v : voertuigen.values()) if (v.eigenaar.equals(speler)) l.add(v);
        l.sort(Comparator.comparingLong(v -> v.gekocht));
        return l;
    }

    long aantalMetModel(String model) {
        return voertuigen.values().stream().filter(v -> v.model.equals(model)).count();
    }
}
