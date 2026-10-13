package vaulty;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Laadt en bewaart kluizen in plugins/Vaulty/kluizen/<uuid>.yml. Alleen gebruiken op de main thread. */
public final class KluisManager {
    private final Vaulty plugin;
    private final File dir;
    private final Map<UUID, Kluis> loaded = new HashMap<>();
    /** Eén schrijf-thread, zodat bestanden altijd in de goede volgorde worden weggeschreven. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Vaulty-opslaan");
        t.setDaemon(true);
        return t;
    });

    KluisManager(Vaulty plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "kluizen");
        if (!dir.exists() && !dir.mkdirs()) plugin.getLogger().warning("Kon map niet maken: " + dir);
    }

    public Kluis get(UUID uuid, String name) {
        Kluis k = loaded.get(uuid);
        if (k == null) {
            k = load(uuid, name);
            loaded.put(uuid, k);
        } else if (name != null && !name.equals(k.name())) {
            k.name(name);
        }
        return k;
    }

    private Kluis load(UUID uuid, String name) {
        File f = file(uuid);
        if (!f.exists()) {
            Kluis k = new Kluis(uuid, name);
            for (int i = 0; i < plugin.startChests(); i++) k.addChest();
            return k;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String storedName = y.getString("naam", name);
        Kluis k = new Kluis(uuid, name != null ? name : storedName);
        int chests = Math.max(plugin.startChests(), y.getInt("kisten", 1));
        for (int i = 0; i < chests; i++) {
            ItemStack[] contents = new ItemStack[Kluis.SIZE];
            ConfigurationSection sec = y.getConfigurationSection("kist." + i);
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(key);
                        if (slot < 0 || slot >= Kluis.SIZE) continue;
                        byte[] data = Base64.getDecoder().decode(sec.getString(key, ""));
                        contents[slot] = ItemStack.deserializeBytes(data);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "Kon een item in de kluis van " + storedName + " niet laden (kist " + (i + 1) + ", vak " + key + "). Het item blijft in het bestand staan.", e);
                        // voorkomen dat we bij opslaan het kapotte item overschrijven: maak een reservekopie
                        backupBroken(f);
                    }
                }
            }
            k.addChest(contents, y.getString("namen." + i));
        }
        return k;
    }

    private void backupBroken(File f) {
        try {
            File b = new File(f.getParentFile(), f.getName() + ".fout-" + System.currentTimeMillis());
            if (!b.exists()) Files.copy(f.toPath(), b.toPath());
        } catch (IOException ignored) { }
    }

    private File file(UUID uuid) { return new File(dir, uuid + ".yml"); }

    /** Maakt een momentopname op de main thread en schrijft die weg op de schrijf-thread. */
    public void save(Kluis k) {
        String text = snapshot(k);
        File f = file(k.owner());
        writer.execute(() -> write(f, text));
    }

    /** Schrijft een ander bestand (bijv. markt.yml) weg op dezelfde schrijf-thread. */
    void writeLater(File f, String text) {
        writer.execute(() -> write(f, text));
    }

    private String snapshot(Kluis k) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("naam", k.name());
        y.set("kisten", k.chests());
        for (int i = 0; i < k.chests(); i++) {
            if (k.chestName(i) != null) y.set("namen." + i, k.chestName(i));
            Inventory inv = k.inventory(i);
            for (int s = 0; s < Kluis.SIZE; s++) {
                ItemStack it = inv.getItem(s);
                if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
                y.set("kist." + i + "." + s, Base64.getEncoder().encodeToString(it.serializeAsBytes()));
            }
        }
        return y.saveToString();
    }

    private void write(File f, String text) {
        try {
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            Files.writeString(tmp.toPath(), text);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.writeString(f.toPath(), text);
            } catch (IOException e2) {
                plugin.getLogger().log(Level.SEVERE, "Kon kluis niet opslaan: " + f.getName(), e2);
            }
        }
    }

    public void saveOpen() {
        for (Kluis k : loaded.values()) if (k.hasViewers()) save(k);
    }

    // Kluizen blijven in het geheugen zolang de server draait: zo kan een bestand nooit
    // opnieuw ingelezen worden terwijl het nog weggeschreven wordt.

    void shutdown() {
        for (Kluis k : loaded.values()) {
            for (Inventory inv : k.inventories()) {
                for (var v : new java.util.ArrayList<>(inv.getViewers())) v.closeInventory();
            }
            save(k);
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(20, TimeUnit.SECONDS)) plugin.getLogger().warning("Opslaan van kluizen duurde te lang.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
