package vaulty;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Spelersmarkt: spelers zetten spullen uit hun kluis te koop voor andere spelers.
 * Het item ligt zolang veilig bij de markt (niet meer in de kluis).
 * Bewaard in plugins/Vaulty/markt.yml. Alleen gebruiken op de main thread.
 */
final class MarketManager {

    static final class Listing {
        final String id;
        final UUID seller;
        String sellerName;
        final ItemStack item;
        final double price;
        final long created;

        Listing(String id, UUID seller, String sellerName, ItemStack item, double price, long created) {
            this.id = id; this.seller = seller; this.sellerName = sellerName; this.item = item; this.price = price; this.created = created;
        }
    }

    private final Vaulty plugin;
    private final File file;
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    /** Berichten voor verkopers die offline waren toen er iets verkocht werd. */
    private final Map<UUID, List<String>> mail = new HashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    MarketManager(Vaulty plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "markt.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("aanbod");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                try {
                    ConfigurationSection l = sec.getConfigurationSection(id);
                    UUID seller = UUID.fromString(l.getString("verkoper"));
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(l.getString("item", "")));
                    listings.put(id, new Listing(id, seller, l.getString("naam", "?"), item, l.getDouble("prijs"), l.getLong("sinds")));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Kon aanbod " + id + " op de markt niet laden. Het blijft in markt.yml staan.", e);
                    backup();
                }
            }
        }
        ConfigurationSection post = y.getConfigurationSection("post");
        if (post != null) {
            for (String u : post.getKeys(false)) {
                try { mail.put(UUID.fromString(u), new ArrayList<>(post.getStringList(u))); } catch (Exception ignored) { }
            }
        }
    }

    private void backup() {
        try {
            File b = new File(file.getParentFile(), "markt.yml.fout-" + System.currentTimeMillis());
            if (!b.exists()) java.nio.file.Files.copy(file.toPath(), b.toPath());
        } catch (Exception ignored) { }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String p = "aanbod." + l.id + ".";
            y.set(p + "verkoper", l.seller.toString());
            y.set(p + "naam", l.sellerName);
            y.set(p + "item", Base64.getEncoder().encodeToString(l.item.serializeAsBytes()));
            y.set(p + "prijs", l.price);
            y.set(p + "sinds", l.created);
        }
        for (Map.Entry<UUID, List<String>> e : mail.entrySet()) {
            if (!e.getValue().isEmpty()) y.set("post." + e.getKey(), e.getValue());
        }
        plugin.kluizen().writeLater(file, y.saveToString());
    }

    List<Listing> all() { return new ArrayList<>(listings.values()); }

    Listing get(String id) { return id == null ? null : listings.get(id); }

    int countOf(UUID seller) {
        int n = 0;
        for (Listing l : listings.values()) if (l.seller.equals(seller)) n++;
        return n;
    }

    Listing add(UUID seller, String sellerName, ItemStack item, double price) {
        String id;
        do {
            byte[] b = new byte[5];
            rnd.nextBytes(b);
            id = HexFormat.of().formatHex(b);
        } while (listings.containsKey(id));
        Listing l = new Listing(id, seller, sellerName, item, price, System.currentTimeMillis());
        listings.put(id, l);
        return l;
    }

    void remove(String id) { listings.remove(id); }

    void addMail(UUID who, String text) {
        List<String> list = mail.computeIfAbsent(who, k -> new ArrayList<>());
        list.add(text);
        while (list.size() > 30) list.remove(0);
    }

    List<String> takeMail(UUID who) {
        List<String> list = mail.remove(who);
        return list == null ? List.of() : list;
    }

    int maxPerPlayer() { return Math.max(1, plugin.getConfig().getInt("markt.max-per-speler", 10)); }

    double maxPrice() { return Math.max(1, plugin.getConfig().getDouble("markt.max-prijs", 100_000_000)); }

    /** Percentage dat de server inhoudt bij een verkoop (0 = niets). */
    double feePercent() { return Math.max(0, Math.min(50, plugin.getConfig().getDouble("markt.belasting-procent", 0))); }

    boolean enabled() { return plugin.getConfig().getBoolean("markt.aan", true); }
}
