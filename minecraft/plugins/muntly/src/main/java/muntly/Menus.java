package muntly;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Alle menu's: hoofdmenu, winkel, bevestigen, lootboxen, inhoud, openen met animatie en toplijst. */
final class Menus implements Listener {

    /** Een menu van Muntly. Klikken doet alleen wat in acties staat. */
    static final class Menu implements InventoryHolder {
        final Map<Integer, Consumer<ClickType>> acties = new HashMap<>();
        Inventory inv;
        Opening opening; // alleen bij het openen van een lootbox
        @Override public Inventory getInventory() { return inv; }
    }

    private final Muntly plugin;

    Menus(Muntly plugin) { this.plugin = plugin; }

    // ------------------------------------------------------------------ hulp
    private Menu menu(int rows, String titel) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, rows * 9, Winkel.tekst(titel));
        return m;
    }

    static ItemStack knop(Material mat, String naam, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Winkel.tekst(naam));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Winkel.tekst(s));
        if (!l.isEmpty()) meta.lore(l);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(meta);
        return it;
    }

    private static void vul(Inventory inv, Material glas) {
        ItemStack g = knop(glas, " ");
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, g);
    }

    private void zet(Menu m, int slot, ItemStack it, Consumer<ClickType> actie) {
        m.inv.setItem(slot, it);
        if (actie != null) m.acties.put(slot, actie);
    }

    private ItemStack saldoKnop(Player p) {
        Saldi.Speler sp = plugin.saldi().get(p.getUniqueId(), p.getName());
        return knop(Material.SUNFLOWER, plugin.kleur() + "&lSaldo: " + plugin.munten(sp.saldo),
                "&7Verdien Munten door te spelen", "&7en elke dag in te loggen.");
    }

    private static void klik(Player p) { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f); }

    // ------------------------------------------------------------- hoofdmenu
    void hoofdmenu(Player p) {
        Menu m = menu(3, plugin.kleur() + "&l" + plugin.muntNaam());
        Saldi.Speler sp = plugin.saldi().get(p.getUniqueId(), p.getName());
        zet(m, 4, saldoKnop(p), null);
        zet(m, 11, knop(Material.EMERALD, "&a&lMuntenwinkel", "&7Speciale wapens, gereedschap", "&7en armor voor Munten.", "", "&eKlik om te openen"), c -> { klik(p); winkel(p); });
        zet(m, 13, knop(Material.ENDER_CHEST, "&d&lLootboxen", "&7Waag een gok: wat zit erin?", "", "&eKlik om te openen"), c -> { klik(p); lootboxen(p); });
        zet(m, 15, knop(Material.GOLD_BLOCK, "&6&lToplijst", "&7Wie heeft de meeste Munten?", "", "&eKlik om te openen"), c -> { klik(p); toplijst(p); });
        Verdienen v = plugin.verdienen();
        List<String> info = new ArrayList<>();
        if (plugin.getConfig().getBoolean("verdienen.speeltijd.aan", true)) {
            info.add("&7Speeltijd: &f+" + plugin.munten(plugin.getConfig().getLong("verdienen.speeltijd.munten", 2))
                    + " &7per " + plugin.getConfig().getInt("verdienen.speeltijd.minuten", 30) + " min");
            info.add("&7Volgende over: &f" + v.minutenTotVolgende(sp) + " min" + (v.isAfk(p) ? " &c(AFK)" : ""));
        }
        if (plugin.getConfig().getBoolean("verdienen.dagelijks.aan", true)) {
            info.add("");
            info.add("&7Dagen op rij: &f" + sp.reeks);
            info.add("&7Morgen inloggen: &f+" + plugin.munten(v.beloningVoorDag(sp.reeks + 1)));
        }
        zet(m, 22, knop(Material.CLOCK, "&e&lMunten verdienen", info.toArray(new String[0])), null);
        vul(m.inv, Material.BLACK_STAINED_GLASS_PANE);
        p.openInventory(m.inv);
    }

    // ---------------------------------------------------------------- winkel
    void winkel(Player p) {
        Menu m = menu(6, "&2&lMuntenwinkel");
        int slot = 0;
        String vorige = null;
        for (Winkel.Artikel a : plugin.winkel().artikelen().values()) {
            if (!a.teKoop()) continue;
            if (vorige != null && !vorige.equals(a.categorie) && slot % 9 != 0) slot = (slot / 9 + 1) * 9; // nieuwe categorie op een nieuwe rij
            vorige = a.categorie;
            if (slot >= 45) break;
            ItemStack toon = a.item.clone();
            ItemMeta meta = toon.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(Winkel.tekst("&7Prijs: " + plugin.kleur() + "&l" + plugin.munten(a.prijs)));
            lore.add(Winkel.tekst("&eKlik om te kopen"));
            meta.lore(lore);
            toon.setItemMeta(meta);
            zet(m, slot++, toon, c -> { klik(p); bevestigKoop(p, a); });
        }
        zet(m, 45, saldoKnop(p), null);
        zet(m, 49, knop(Material.ARROW, "&7Terug"), c -> { klik(p); hoofdmenu(p); });
        zet(m, 53, knop(Material.ENDER_CHEST, "&d&lLootboxen", "&eKlik om te openen"), c -> { klik(p); lootboxen(p); });
        vul(m.inv, Material.GRAY_STAINED_GLASS_PANE);
        p.openInventory(m.inv);
    }

    private void bevestig(Player p, ItemStack toon, String vraag, Runnable ja, Runnable terug) {
        Menu m = menu(3, "&8" + vraag);
        zet(m, 13, toon, null);
        zet(m, 11, knop(Material.LIME_CONCRETE, "&a&lJa, doen"), c -> ja.run());
        zet(m, 15, knop(Material.RED_CONCRETE, "&c&lNee, terug"), c -> { klik(p); terug.run(); });
        vul(m.inv, Material.GRAY_STAINED_GLASS_PANE);
        p.openInventory(m.inv);
    }

    private void bevestigKoop(Player p, Winkel.Artikel a) {
        ItemStack toon = a.item.clone();
        ItemMeta meta = toon.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(Winkel.tekst("&7Prijs: " + plugin.kleur() + "&l" + plugin.munten(a.prijs)));
        meta.lore(lore);
        toon.setItemMeta(meta);
        bevestig(p, toon, "Kopen voor " + plugin.munten(a.prijs) + "?", () -> {
            String fout = plugin.koop(p, a);
            if (fout != null) {
                p.sendMessage(plugin.fout(fout));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            } else {
                p.sendMessage(plugin.bericht("&aGekocht: &f" + Winkel.naam(a.item) + "&a. Saldo: " + plugin.kleur() + plugin.munten(plugin.saldi().get(p.getUniqueId(), p.getName()).saldo)));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            }
            winkel(p);
        }, () -> winkel(p));
    }

    // ------------------------------------------------------------- lootboxen
    void lootboxen(Player p) {
        Menu m = menu(3, "&5&lLootboxen");
        List<Winkel.Lootbox> boxes = new ArrayList<>(plugin.winkel().lootboxen().values());
        int[] slots = boxes.size() == 1 ? new int[]{13} : boxes.size() == 2 ? new int[]{11, 15} : new int[]{10, 12, 14, 16, 11, 13, 15};
        for (int i = 0; i < boxes.size() && i < slots.length; i++) {
            Winkel.Lootbox lb = boxes.get(i);
            List<String> lore = new ArrayList<>();
            if (!lb.beschrijving.isBlank()) lore.add("&7" + lb.beschrijving);
            lore.add("");
            lore.add("&7Prijs: " + plugin.kleur() + "&l" + plugin.munten(lb.prijs));
            lore.add("");
            lore.add("&eLinksklik: openen");
            lore.add("&eRechtsklik: bekijk wat erin zit");
            zet(m, slots[i], knop(lb.icoon, lb.naam, lore.toArray(new String[0])), c -> {
                klik(p);
                if (c.isRightClick()) inhoud(p, lb);
                else bevestig(p, knop(lb.icoon, lb.naam, "&7Prijs: " + plugin.kleur() + plugin.munten(lb.prijs)), "Openen voor " + plugin.munten(lb.prijs) + "?",
                        () -> openLootbox(p, lb), () -> lootboxen(p));
            });
        }
        zet(m, 18, saldoKnop(p), null);
        zet(m, 22, knop(Material.ARROW, "&7Terug"), c -> { klik(p); hoofdmenu(p); });
        vul(m.inv, Material.PURPLE_STAINED_GLASS_PANE);
        p.openInventory(m.inv);
    }

    private ItemStack toonBeloning(Winkel.Beloning b, boolean metKans) {
        ItemStack it;
        if (b.item != null) it = b.item.clone();
        else if (b.munten > 0) it = knop(Material.SUNFLOWER, plugin.kleur() + "&l" + plugin.munten(b.munten));
        else it = knop(Material.GOLD_INGOT, "&6&l$" + Muntly.fmt(Math.round(b.geld)));
        if (metKans) {
            ItemMeta meta = it.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(Winkel.tekst(b.zeldzaamheid.kleur + b.zeldzaamheid.label + " &7- kans " + String.format(java.util.Locale.ROOT, b.procent < 1 ? "%.1f" : "%.0f", b.procent).replace('.', ',') + "%"));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private void inhoud(Player p, Winkel.Lootbox lb) {
        Menu m = menu(6, lb.naam);
        int slot = 0;
        for (Winkel.Beloning b : lb.beloningen) {
            if (slot >= 45) break;
            zet(m, slot++, toonBeloning(b, true), null);
        }
        zet(m, 49, knop(Material.ARROW, "&7Terug"), c -> { klik(p); lootboxen(p); });
        zet(m, 53, knop(lb.icoon, "&a&lOpenen voor " + plugin.munten(lb.prijs)), c -> { klik(p); openLootbox(p, lb); });
        vul(m.inv, Material.GRAY_STAINED_GLASS_PANE);
        p.openInventory(m.inv);
    }

    // ------------------------------------------------- lootbox openen (animatie)
    final class Opening {
        final Player p;
        final Winkel.Lootbox lb;
        final Winkel.Beloning prijs;
        final Menu m;
        final List<ItemStack> rol = new ArrayList<>();
        int stap, wacht;
        final int stappen;
        boolean klaar;
        BukkitRunnable taak;

        Opening(Player p, Winkel.Lootbox lb, Winkel.Beloning prijs, Menu m) {
            this.p = p; this.lb = lb; this.prijs = prijs; this.m = m;
            stappen = 28 + ThreadLocalRandom.current().nextInt(6);
            // willekeurige rol; de prijs valt na de laatste stap in het midden (slot 13)
            for (int i = 0; i < stappen + 9; i++) rol.add(toonBeloning(lb.trek(), false));
            rol.set(stappen + 4, toonBeloning(prijs, false));
        }

        void teken() {
            for (int i = 0; i < 9; i++) m.inv.setItem(9 + i, rol.get(stap + i));
        }

        void tik() {
            if (klaar) return;
            if (wacht > 0) { wacht--; return; }
            stap++;
            teken();
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.35f, 1.8f);
            if (stap >= stappen) { afronden(true); return; }
            int over = stappen - stap;
            wacht = over > 14 ? 0 : over > 8 ? 1 : over > 4 ? 2 : over > 2 ? 4 : 6;
        }

        /** Geeft de beloning. Ook als de speler het menu eerder sluit of uitlogt. */
        void afronden(boolean zichtbaar) {
            if (klaar) return;
            klaar = true;
            if (taak != null) taak.cancel();
            plugin.geefBeloning(p, lb, prijs);
            if (zichtbaar && p.isOnline()) {
                ItemStack groen = knop(prijs.zeldzaamheid == Winkel.Zeldzaamheid.LEGENDARISCH ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE, " ");
                for (int s : new int[]{3, 4, 5, 21, 22, 23}) m.inv.setItem(s, groen);
                m.acties.put(22, c -> { klik(p); lootboxen(p); });
                m.inv.setItem(22, knop(Material.ARROW, "&7Terug naar de lootboxen"));
                m.acties.put(26, c -> { klik(p); openLootbox(p, lb); });
                m.inv.setItem(26, knop(lb.icoon, "&a&lNog een keer (" + plugin.munten(lb.prijs) + ")"));
            }
        }
    }

    void openLootbox(Player p, Winkel.Lootbox lb) {
        String fout = plugin.kanLootboxOpenen(p, lb);
        if (fout != null) {
            p.sendMessage(plugin.fout(fout));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            p.closeInventory();
            return;
        }
        Winkel.Beloning prijs = lb.trek();
        if (!plugin.betaalLootbox(p, lb)) { p.sendMessage(plugin.fout("Je hebt niet genoeg " + plugin.muntNaam() + ".")); p.closeInventory(); return; }
        Menu m = menu(3, lb.naam + " &8wordt geopend…");
        ItemStack glas = knop(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) m.inv.setItem(i, glas);
        ItemStack pijl = knop(Material.YELLOW_STAINED_GLASS_PANE, "&e▼");
        m.inv.setItem(4, pijl);
        m.inv.setItem(22, knop(Material.YELLOW_STAINED_GLASS_PANE, "&e▲"));
        Opening o = new Opening(p, lb, prijs, m);
        m.opening = o;
        o.teken();
        p.openInventory(m.inv);
        o.taak = new BukkitRunnable() { @Override public void run() { o.tik(); } };
        o.taak.runTaskTimer(plugin, 2L, 1L);
    }

    // -------------------------------------------------------------- toplijst
    void toplijst(Player p) {
        Menu m = menu(3, "&6&lToplijst " + plugin.muntNaam());
        List<Saldi.Speler> top = plugin.saldi().top(10);
        int[] slots = {4, 12, 13, 14, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < top.size(); i++) {
            Saldi.Speler sp = top.get(i);
            ItemStack kop = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) kop.getItemMeta();
            OfflinePlayer op = Bukkit.getOfflinePlayer(sp.uuid);
            sm.setOwningPlayer(op);
            String kleur = i == 0 ? "&6" : i == 1 ? "&f" : i == 2 ? "&c" : "&7";
            sm.displayName(Winkel.tekst(kleur + "&l#" + (i + 1) + " " + sp.naam));
            sm.lore(List.of(Winkel.tekst(plugin.kleur() + plugin.munten(sp.saldo))));
            kop.setItemMeta(sm);
            zet(m, slots[i], kop, null);
        }
        if (top.isEmpty()) zet(m, 13, knop(Material.PAPER, "&7Nog niemand heeft Munten."), null);
        zet(m, 18, saldoKnop(p), null);
        zet(m, 26, knop(Material.ARROW, "&7Terug"), c -> { klik(p); hoofdmenu(p); });
        vul(m.inv, Material.GRAY_STAINED_GLASS_PANE);
        p.openInventory(m.inv);
    }

    // --------------------------------------------------------------- events
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Consumer<ClickType> a = m.acties.get(e.getRawSlot());
        if (a != null && e.getWhoClicked() instanceof Player) Bukkit.getScheduler().runTask(plugin, () -> a.accept(e.getClick()));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Menu m && m.opening != null) m.opening.afronden(false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Menu m && m.opening != null) m.opening.afronden(false);
    }
}
