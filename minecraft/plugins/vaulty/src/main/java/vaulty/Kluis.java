package vaulty;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** De kluis van een speler: een aantal dubbele kisten van 54 vakken, elk met een eigen naam. */
public final class Kluis {
    public static final int SIZE = 54;
    public static final int MAX_NAME = 24;

    private final UUID owner;
    private String name;
    private final List<Inventory> inventories = new ArrayList<>();
    /** Eigen naam per kist, of null voor "Kist 1", "Kist 2", ... */
    private final List<String> chestNames = new ArrayList<>();

    Kluis(UUID owner, String name) {
        this.owner = owner;
        this.name = name == null ? "?" : name;
    }

    public UUID owner() { return owner; }
    public String name() { return name; }
    void name(String n) { if (n != null && !n.isBlank() && !n.equals(this.name)) { this.name = n; retitle(); } }
    public int chests() { return inventories.size(); }
    public Inventory inventory(int index) { return inventories.get(index); }
    public List<Inventory> inventories() { return inventories; }

    /** De eigen naam van een kist, of null als hij geen eigen naam heeft. */
    public String chestName(int index) { return index >= 0 && index < chestNames.size() ? chestNames.get(index) : null; }

    /** Naam zoals spelers hem zien: de eigen naam of "Kist 3". */
    public String displayName(int index) {
        String n = chestName(index);
        return n != null ? n : "Kist " + (index + 1);
    }

    /** Zet de naam van een kist. Leeg of null = terug naar "Kist N". Geeft de opgeschoonde naam terug. */
    String chestName(int index, String raw) {
        String n = cleanName(raw);
        chestNames.set(index, n);
        retitle();
        return n;
    }

    /** Haalt kleurcodes en vreemde tekens weg en kort in tot MAX_NAME tekens. */
    static String cleanName(String raw) {
        if (raw == null) return null;
        String n = raw.replaceAll("[§&][0-9a-fk-orxA-FK-ORX]", "")
                .replaceAll("[^\\p{L}\\p{N} _\\-'!?.,:()#+]", "")
                .replaceAll("\\s+", " ").trim();
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME).trim();
        return n.isEmpty() ? null : n;
    }

    void addChest() { addChest(null, null); }

    void addChest(ItemStack[] contents, String chestName) {
        int index = inventories.size();
        chestNames.add(cleanName(chestName));
        Holder h = new Holder(this, index);
        Inventory inv = Bukkit.createInventory(h, SIZE, title(index));
        h.inventory = inv;
        if (contents != null) inv.setContents(contents);
        inventories.add(inv);
    }

    private Component title(int index) {
        String n = chestName(index);
        return Component.text(n != null ? n + " (kist " + (index + 1) + ")" : "Kist " + (index + 1) + " - " + name);
    }

    /**
     * Titels van een geopende kist kunnen niet veranderen. Daarom worden alleen kisten
     * waar niemand in kijkt opnieuw gemaakt; de rest volgt zodra ze gesloten zijn.
     */
    void retitle() {
        for (int i = 0; i < inventories.size(); i++) {
            Inventory old = inventories.get(i);
            if (!old.getViewers().isEmpty()) continue;
            Holder h = new Holder(this, i);
            Inventory inv = Bukkit.createInventory(h, SIZE, title(i));
            h.inventory = inv;
            inv.setContents(old.getContents());
            inventories.set(i, inv);
        }
    }

    public boolean hasViewers() {
        for (Inventory inv : inventories) if (!inv.getViewers().isEmpty()) return true;
        return false;
    }

    /**
     * Sorteert alle kisten samen: gelijke items worden samengevoegd tot volle stapels,
     * alles komt op volgorde (soort, naam, aantal) en kist 1 wordt eerst gevuld.
     * Geeft het aantal stapels voor en na terug.
     */
    int[] sort() {
        List<ItemStack> all = new ArrayList<>();
        for (Inventory inv : inventories) {
            for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir() && it.getAmount() > 0) all.add(it.clone());
        }
        int before = all.size();
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack it : all) {
            int max = Math.max(1, it.getMaxStackSize());
            int left = it.getAmount();
            for (ItemStack m : merged) {
                if (left <= 0) break;
                if (m.getAmount() < max && m.isSimilar(it)) {
                    int add = Math.min(left, max - m.getAmount());
                    m.setAmount(m.getAmount() + add);
                    left -= add;
                }
            }
            while (left > 0) {
                ItemStack c = it.clone();
                int amt = Math.min(left, max);
                c.setAmount(amt);
                merged.add(c);
                left -= amt;
            }
        }
        merged.sort(Comparator
                .comparingInt((ItemStack i) -> categoryOrder(i))
                .thenComparing(i -> groupKey(i))
                .thenComparing(Kluis::sortName)
                .thenComparing(i -> i.getEnchantments().isEmpty() ? 0 : 1)
                .thenComparing(i -> -i.getAmount()));
        int pos = 0;
        for (Inventory inv : inventories) {
            ItemStack[] contents = new ItemStack[SIZE];
            for (int s = 0; s < SIZE && pos < merged.size(); s++) contents[s] = merged.get(pos++);
            inv.setContents(contents);
        }
        return new int[] { before, merged.size() };
    }

    private static final String[] COLORS = { "white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow",
            "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink" };

    /**
     * Groepen in een vaste volgorde: bouwblokken, decoratie en planten, redstone, eten,
     * gereedschap/wapens/armor, drankjes en boeken, spawners en eieren, de rest.
     */
    private static int categoryOrder(ItemStack it) {
        org.bukkit.Material m = it.getType();
        String k = m.getKey().getKey();
        if (k.endsWith("spawn_egg") || k.equals("spawner") || k.equals("trial_spawner")) return 6;
        if (k.contains("potion") || k.endsWith("_book") || k.equals("book") || k.equals("tipped_arrow") || k.equals("experience_bottle")) return 5;
        if (m.getMaxDurability() > 0 || k.endsWith("_arrow") || k.equals("arrow") || k.endsWith("_smithing_template")) return 4;
        if (m.isEdible()) return 3;
        if (k.contains("redstone") || k.endsWith("piston") || k.endsWith("repeater") || k.endsWith("comparator") || k.endsWith("observer")
                || k.endsWith("hopper") || k.endsWith("dropper") || k.endsWith("dispenser") || k.endsWith("lever") || k.endsWith("_button")
                || k.endsWith("pressure_plate") || k.endsWith("rail") || k.endsWith("daylight_detector") || k.endsWith("target")) return 2;
        if (m.isBlock()) return m.isSolid() && m.isOccluding() ? 0 : 1;
        return 7;
    }

    /**
     * Sorteersleutel waarmee varianten bij elkaar komen: "red_wool" wordt "wool 05",
     * zodat alle wol samen staat in de kleurvolgorde van het spel.
     */
    private static String groupKey(ItemStack it) {
        String k = it.getType().getKey().getKey();
        for (int c = 0; c < COLORS.length; c++) {
            if (k.startsWith(COLORS[c] + "_")) return k.substring(COLORS[c].length() + 1) + " " + String.format("%02d", c);
        }
        return k;
    }

    private static String sortName(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName() && m.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(m.displayName()).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /** Herkent een kluis-inventory. */
    public static final class Holder implements InventoryHolder {
        final Kluis kluis;
        final int index;
        Inventory inventory;

        Holder(Kluis kluis, int index) { this.kluis = kluis; this.index = index; }

        public Kluis kluis() { return kluis; }
        public int index() { return index; }

        @Override
        public Inventory getInventory() { return inventory; }
    }
}
