package vaulty;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Keuzemenu: welke kist open je, of koop je er een bij. */
public final class KluisMenu implements InventoryHolder {
    final Kluis kluis;
    private Inventory inventory;
    /** Meer kisten passen er niet in het menu (3 rijen van 7). */
    static final int MAX_IN_MENU = 21;
    /** vak -> kistnummer (0-based), of -2 = kopen */
    final int[] action;

    private KluisMenu(Kluis kluis, int size) { this.kluis = kluis; this.action = new int[size]; }

    @Override
    public Inventory getInventory() { return inventory; }

    static Inventory create(Vaulty plugin, Kluis k) {
        int max = Math.min(plugin.maxChests(), MAX_IN_MENU);
        int rows = Math.max(1, (max + 6) / 7);
        int size = 9 * (rows + 2);
        KluisMenu m = new KluisMenu(k, size);
        Inventory inv = Bukkit.createInventory(m, size, Component.text("Je kluis (" + k.chests() + " van " + plugin.maxChests() + " kisten)"));
        m.inventory = inv;
        java.util.Arrays.fill(m.action, -1);
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < size; i++) inv.setItem(i, pane);
        for (int n = 0; n < max; n++) {
            int row = 1 + n / 7;
            int inRow = Math.min(7, max - (row - 1) * 7);
            int slot = row * 9 + (9 - inRow) / 2 + n % 7;
            if (n < k.chests()) {
                int used = 0;
                for (ItemStack it : k.inventory(n).getContents()) if (it != null && !it.getType().isAir()) used++;
                inv.setItem(slot, item(Material.CHEST, k.displayName(n), List.of(
                        k.chestName(n) != null ? "Kist " + (n + 1) : "Naam geven: /kluis naam " + (n + 1) + " <naam>",
                        used + " van " + Kluis.SIZE + " vakken gebruikt", "", "Klik om te openen")));
                m.action[slot] = n;
            } else if (n == k.chests()) {
                double price = plugin.nextChestPrice(k);
                inv.setItem(slot, item(Material.GOLD_INGOT, "Kist " + (n + 1) + " kopen", List.of("Prijs: $" + Vaulty.fmt(price), "", "Klik om te kopen")));
                m.action[slot] = -2;
            } else {
                inv.setItem(slot, item(Material.GRAY_STAINED_GLASS_PANE, "Kist " + (n + 1), List.of("Koop eerst kist " + n)));
            }
        }
        // onderste rij: sorteren en uitleg over namen
        int last = size - 9;
        inv.setItem(last + 3, item(Material.HOPPER, "Alles sorteren", List.of("Voegt gelijke items samen en zet", "alles op volgorde in al je kisten.", "", "Klik om te sorteren")));
        m.action[last + 3] = -3;
        inv.setItem(last + 5, item(Material.NAME_TAG, "Kist een naam geven", List.of("/kluis naam <nummer> <naam>", "bijv. /kluis naam 2 Ertsen", "", "Of op de website bij Mijn kluis.")));
        return inv;
    }

    static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }
}
