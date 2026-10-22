package ritly;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Alle modellen: uit modellen.yml en de zelf gebouwde in gebouwd/. */
final class Modellen {

    private final Ritly plugin;
    private final Map<String, Model> modellen = new LinkedHashMap<>();

    Modellen(Ritly plugin) {
        this.plugin = plugin;
    }

    void laad() {
        modellen.clear();
        File f = new File(plugin.getDataFolder(), "modellen.yml");
        if (!f.exists()) plugin.saveResource("modellen.yml", false);
        ConfigurationSection standaard = plugin.getConfig().getConfigurationSection("soorten");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection s = y.getConfigurationSection("modellen");
        if (s != null) for (String id : s.getKeys(false)) laadEen(id, s.getConfigurationSection(id), standaard, null);
        File map = new File(plugin.getDataFolder(), "gebouwd");
        File[] lijst = map.listFiles((d, n) -> n.endsWith(".yml"));
        if (lijst != null) for (File g : lijst) {
            YamlConfiguration gy = YamlConfiguration.loadConfiguration(g);
            String id = g.getName().substring(0, g.getName().length() - 4);
            UUID eig = null;
            try {
                if (gy.isString("eigenaar")) eig = UUID.fromString(gy.getString("eigenaar"));
            } catch (IllegalArgumentException ignored) {}
            laadEen(id, gy, standaard, eig);
        }
        plugin.getLogger().info(modellen.size() + " modellen geladen.");
    }

    private void laadEen(String id, ConfigurationSection s, ConfigurationSection standaard, UUID eigenaar) {
        if (s == null) return;
        try {
            Model m = Model.lees(id.toLowerCase(Locale.ROOT), s, standaard, eigenaar);
            if (m.delen.isEmpty()) {
                plugin.getLogger().warning("Model " + id + " heeft geen delen en wordt overgeslagen.");
                return;
            }
            modellen.put(m.id, m);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Model " + id + " kon niet geladen worden: " + e.getMessage());
        }
    }

    Model get(String id) {
        return id == null ? null : modellen.get(id.toLowerCase(Locale.ROOT));
    }

    Collection<Model> alle() {
        return modellen.values();
    }

    List<String> openbareIds() {
        List<String> l = new ArrayList<>();
        for (Model m : modellen.values()) if (m.eigenaar == null) l.add(m.id);
        return l;
    }

    boolean opslaanGebouwd(Model m) {
        File map = new File(plugin.getDataFolder(), "gebouwd");
        if (!map.exists() && !map.mkdirs()) return false;
        YamlConfiguration y = new YamlConfiguration();
        m.schrijf(y);
        try {
            y.save(new File(map, m.id + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Model " + m.id + " opslaan mislukt: " + e.getMessage());
            return false;
        }
        modellen.put(m.id, m);
        return true;
    }

    boolean verwijderGebouwd(String id) {
        File f = new File(new File(plugin.getDataFolder(), "gebouwd"), id + ".yml");
        modellen.remove(id);
        return f.exists() && f.delete();
    }

    boolean isGebouwd(String id) {
        return new File(new File(plugin.getDataFolder(), "gebouwd"), id + ".yml").exists();
    }
}
