package muntly;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** De muntenwinkel en de lootboxen uit winkel.yml. */
final class Winkel {

    static final List<String> CATEGORIEEN = List.of("wapens", "gereedschap", "armor", "overig");
    static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    enum Zeldzaamheid {
        GEWOON("gewoon", "&f", "Gewoon"), ZELDZAAM("zeldzaam", "&9", "Zeldzaam"),
        EPISCH("episch", "&5", "Episch"), LEGENDARISCH("legendarisch", "&6", "Legendarisch");
        final String id, kleur, label;
        Zeldzaamheid(String id, String kleur, String label) { this.id = id; this.kleur = kleur; this.label = label; }
        static Zeldzaamheid of(String s, Zeldzaamheid def) {
            if (s != null) for (Zeldzaamheid z : values()) if (z.id.equalsIgnoreCase(s.trim())) return z;
            return def;
        }
        /** Uit een kans in procenten. */
        static Zeldzaamheid uitKans(double procent) {
            if (procent >= 15) return GEWOON;
            if (procent >= 5) return ZELDZAAM;
            if (procent >= 1.5) return EPISCH;
            return LEGENDARISCH;
        }
    }

    static final class Artikel {
        final String id;
        final long prijs;           // -1 = niet te koop
        final String categorie;
        final ItemStack item;
        final Zeldzaamheid zeldzaamheid;
        Artikel(String id, long prijs, String categorie, ItemStack item, Zeldzaamheid z) {
            this.id = id; this.prijs = prijs; this.categorie = categorie; this.item = item; this.zeldzaamheid = z;
        }
        boolean teKoop() { return prijs >= 0; }
    }

    static final class Beloning {
        final double kans;
        final long munten;
        final double geld;
        final ItemStack item;      // null bij munten/geld
        final String artikelId;    // als het een item uit de winkel is
        Zeldzaamheid zeldzaamheid = Zeldzaamheid.GEWOON;
        double procent;
        Beloning(double kans, long munten, double geld, ItemStack item, String artikelId) {
            this.kans = kans; this.munten = munten; this.geld = geld; this.item = item; this.artikelId = artikelId;
        }
    }

    static final class Lootbox {
        final String id;
        final String naam;     // met kleurcodes
        final Material icoon;
        final long prijs;
        final String beschrijving;
        final List<Beloning> beloningen = new ArrayList<>();
        double totaal;
        Lootbox(String id, String naam, Material icoon, long prijs, String beschrijving) {
            this.id = id; this.naam = naam; this.icoon = icoon; this.prijs = prijs; this.beschrijving = beschrijving;
        }
        Beloning trek() {
            double r = ThreadLocalRandom.current().nextDouble() * totaal;
            for (Beloning b : beloningen) {
                r -= b.kans;
                if (r < 0) return b;
            }
            return beloningen.get(beloningen.size() - 1);
        }
    }

    private final Muntly plugin;
    private final NamespacedKey itemKey;
    private final Map<String, Artikel> artikelen = new LinkedHashMap<>();
    private final Map<String, Lootbox> lootboxen = new LinkedHashMap<>();

    Winkel(Muntly plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "item");
    }

    void load() {
        File f = new File(plugin.getDataFolder(), "winkel.yml");
        if (!f.exists()) plugin.saveResource("winkel.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        artikelen.clear();
        lootboxen.clear();

        ConfigurationSection items = y.getConfigurationSection("items");
        List<Artikel> list = new ArrayList<>();
        if (items != null) {
            for (String id : items.getKeys(false)) {
                ConfigurationSection s = items.getConfigurationSection(id);
                if (s == null) continue;
                ItemStack it = bouw(s, id);
                if (it == null) continue;
                long prijs = s.getBoolean("alleen-lootbox") || !s.contains("prijs") ? -1 : Math.max(0, s.getLong("prijs"));
                String cat = s.getString("categorie", "overig").toLowerCase(Locale.ROOT);
                if (!CATEGORIEEN.contains(cat)) cat = "overig";
                list.add(new Artikel(id, prijs, cat, it, Zeldzaamheid.of(s.getString("zeldzaamheid"), Zeldzaamheid.ZELDZAAM)));
            }
        }
        // op categorie sorteren, binnen de categorie de volgorde uit het bestand
        for (String c : CATEGORIEEN) for (Artikel a : list) if (a.categorie.equals(c)) artikelen.put(a.id, a);

        ConfigurationSection boxes = y.getConfigurationSection("lootboxen");
        if (boxes != null) {
            for (String id : boxes.getKeys(false)) {
                ConfigurationSection s = boxes.getConfigurationSection(id);
                if (s == null) continue;
                Material icoon = Material.matchMaterial(s.getString("icoon", "chest"));
                if (icoon == null || !icoon.isItem()) icoon = Material.CHEST;
                Lootbox lb = new Lootbox(id, s.getString("naam", id), icoon, Math.max(0, s.getLong("prijs", 50)), s.getString("beschrijving", ""));
                for (Map<?, ?> m : s.getMapList("beloningen")) {
                    Beloning b = beloning(m, id);
                    if (b != null && b.kans > 0) { lb.beloningen.add(b); lb.totaal += b.kans; }
                }
                if (lb.beloningen.isEmpty()) { plugin.getLogger().warning("Lootbox " + id + " heeft geen geldige beloningen en wordt overgeslagen."); continue; }
                for (Beloning b : lb.beloningen) {
                    b.procent = b.kans * 100.0 / lb.totaal;
                    b.zeldzaamheid = Zeldzaamheid.uitKans(b.procent);
                }
                lootboxen.put(id, lb);
            }
        }
        plugin.getLogger().info("Muntenwinkel geladen: " + artikelen.size() + " items, " + lootboxen.size() + " lootboxen.");
    }

    private Beloning beloning(Map<?, ?> m, String box) {
        double kans = num(m.get("kans"), 1);
        if (m.containsKey("munten")) return new Beloning(kans, (long) num(m.get("munten"), 0), 0, null, null);
        if (m.containsKey("geld")) return new Beloning(kans, 0, num(m.get("geld"), 0), null, null);
        if (m.containsKey("item")) {
            String id = String.valueOf(m.get("item"));
            Artikel a = artikelen.get(id);
            if (a == null) { plugin.getLogger().warning("Lootbox " + box + ": item '" + id + "' staat niet in de lijst met items."); return null; }
            return new Beloning(kans, 0, 0, a.item, id);
        }
        if (m.containsKey("materiaal")) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("materiaal")));
            if (mat == null || !mat.isItem() || mat.isAir()) { plugin.getLogger().warning("Lootbox " + box + ": onbekend materiaal " + m.get("materiaal")); return null; }
            int aantal = (int) Math.max(1, Math.min(mat.getMaxStackSize(), num(m.get("aantal"), 1)));
            return new Beloning(kans, 0, 0, new ItemStack(mat, aantal), null);
        }
        plugin.getLogger().warning("Lootbox " + box + ": beloning zonder munten, geld, item of materiaal: " + m);
        return null;
    }

    private static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? def : Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return def; }
    }

    /** Maakt een item uit een stuk winkel.yml. */
    private ItemStack bouw(ConfigurationSection s, String id) {
        Material mat = Material.matchMaterial(s.getString("materiaal", ""));
        if (mat == null || !mat.isItem() || mat.isAir()) {
            plugin.getLogger().warning("Item " + id + ": onbekend materiaal '" + s.getString("materiaal") + "'. Overgeslagen.");
            return null;
        }
        ItemStack it = new ItemStack(mat, Math.max(1, Math.min(mat.getMaxStackSize(), s.getInt("aantal", 1))));
        ItemMeta meta = it.getItemMeta();
        Zeldzaamheid z = Zeldzaamheid.of(s.getString("zeldzaamheid"), Zeldzaamheid.ZELDZAAM);
        String naam = s.getString("naam");
        if (naam != null && !naam.isBlank()) meta.displayName(tekst(naam.contains("&") ? naam : z.kleur + naam));
        List<Component> lore = new ArrayList<>();
        for (String l : s.getStringList("lore")) lore.add(tekst(l));
        if (!lore.isEmpty()) meta.lore(lore);
        ConfigurationSection en = s.getConfigurationSection("enchants");
        if (en != null) {
            var reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            for (String k : en.getKeys(false)) {
                Enchantment e = reg.get(NamespacedKey.minecraft(k.toLowerCase(Locale.ROOT)));
                if (e == null) { plugin.getLogger().warning("Item " + id + ": onbekende enchant '" + k + "'."); continue; }
                meta.addEnchant(e, Math.max(1, en.getInt(k)), true);
            }
        }
        ConfigurationSection tr = s.getConfigurationSection("trim");
        if (tr != null && meta instanceof ArmorMeta am) {
            TrimMaterial tm = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).get(NamespacedKey.minecraft(tr.getString("materiaal", "gold").toLowerCase(Locale.ROOT)));
            TrimPattern tp = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).get(NamespacedKey.minecraft(tr.getString("patroon", "silence").toLowerCase(Locale.ROOT)));
            if (tm != null && tp != null) am.setTrim(new ArmorTrim(tm, tp));
            else plugin.getLogger().warning("Item " + id + ": onbekende trim " + tr.getValues(false));
        }
        String kleur = s.getString("kleur");
        if (kleur != null && meta instanceof LeatherArmorMeta lm) {
            try { lm.setColor(Color.fromRGB(Integer.parseInt(kleur.replace("#", ""), 16))); } catch (Exception ignored) { }
        }
        if (s.getBoolean("onbreekbaar")) meta.setUnbreakable(true);
        meta.setRarity(switch (z) {
            case GEWOON -> ItemRarity.COMMON;
            case ZELDZAAM -> ItemRarity.UNCOMMON;
            case EPISCH -> ItemRarity.RARE;
            case LEGENDARISCH -> ItemRarity.EPIC;
        });
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    static Component tekst(String legacy) {
        return LEGACY.deserialize(legacy).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    Map<String, Artikel> artikelen() { return artikelen; }
    Map<String, Lootbox> lootboxen() { return lootboxen; }

    /** Korte omschrijving van een beloning, bijv. "20 Munten" of "16x Diamond". */
    String omschrijf(Beloning b) {
        if (b.item != null) return (b.item.getAmount() > 1 ? b.item.getAmount() + "x " : "") + naam(b.item);
        if (b.munten > 0) return plugin.munten(b.munten);
        return "$" + Muntly.fmt((long) Math.round(b.geld));
    }

    static String naam(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) return PLAIN.serialize(m.displayName());
        String k = it.getType().getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    // ---------------------------------------------------------- voor de website
    JsonObject describe(ItemStack it) {
        JsonObject o = new JsonObject();
        o.addProperty("material", it.getType().getKey().getKey());
        o.addProperty("amount", it.getAmount());
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) o.addProperty("name", PLAIN.serialize(meta.displayName()));
            if (meta.hasLore() && meta.lore() != null) {
                JsonArray lore = new JsonArray();
                for (Component c : meta.lore()) lore.add(PLAIN.serialize(c));
                o.add("lore", lore);
            }
            JsonArray ench = new JsonArray();
            for (Map.Entry<Enchantment, Integer> e : it.getEnchantments().entrySet()) ench.add(e.getKey().getKey().getKey() + " " + e.getValue());
            if (!ench.isEmpty()) o.add("enchants", ench);
            if (meta.isUnbreakable()) o.addProperty("unbreakable", true);
            if (meta instanceof ArmorMeta am && am.hasTrim()) {
                NamespacedKey mk = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).getKey(am.getTrim().getMaterial());
                NamespacedKey pk = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).getKey(am.getTrim().getPattern());
                if (mk != null && pk != null) o.addProperty("trim", mk.getKey() + " " + pk.getKey());
            }
        }
        return o;
    }

    JsonObject describe(Beloning b) {
        JsonObject o = new JsonObject();
        o.addProperty("tekst", omschrijf(b));
        o.addProperty("kans", Math.round(b.procent * 100.0) / 100.0);
        o.addProperty("zeldzaamheid", b.zeldzaamheid.id);
        if (b.item != null) o.add("item", describe(b.item));
        else if (b.munten > 0) o.addProperty("munten", b.munten);
        else o.addProperty("geld", b.geld);
        return o;
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray items = new JsonArray();
        for (Artikel a : artikelen.values()) {
            if (!a.teKoop()) continue;
            JsonObject io = new JsonObject();
            io.addProperty("id", a.id);
            io.addProperty("prijs", a.prijs);
            io.addProperty("categorie", a.categorie);
            io.addProperty("zeldzaamheid", a.zeldzaamheid.id);
            io.add("item", describe(a.item));
            items.add(io);
        }
        o.add("items", items);
        JsonArray boxes = new JsonArray();
        for (Lootbox lb : lootboxen.values()) {
            JsonObject bo = new JsonObject();
            bo.addProperty("id", lb.id);
            bo.addProperty("naam", PLAIN.serialize(LEGACY.deserialize(lb.naam)));
            bo.addProperty("icoon", lb.icoon.getKey().getKey());
            bo.addProperty("prijs", lb.prijs);
            bo.addProperty("beschrijving", lb.beschrijving);
            JsonArray bel = new JsonArray();
            for (Beloning b : lb.beloningen) bel.add(describe(b));
            bo.add("beloningen", bel);
            boxes.add(bo);
        }
        o.add("lootboxen", boxes);
        return o;
    }
}
