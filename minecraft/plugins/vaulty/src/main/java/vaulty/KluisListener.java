package vaulty;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.InventoryHolder;

final class KluisListener implements Listener {
    private final Vaulty plugin;

    KluisListener(Vaulty plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder(false);
        if (!(h instanceof KluisMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        if (slot < 0 || slot >= menu.action.length) return;
        int a = menu.action[slot];
        if (a >= 0 && a < menu.kluis.chests()) {
            Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(menu.kluis.inventory(a)));
        } else if (a == -3) {
            int[] r = menu.kluis.sort();
            plugin.kluizen().save(menu.kluis);
            p.sendMessage(Vaulty.msg("Je kluis is gesorteerd (" + r[0] + " vakken werden er " + r[1] + ")."));
            Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(KluisMenu.create(plugin, menu.kluis)));
        } else if (a == -2) {
            String res = plugin.buyChest(menu.kluis, p);
            if (res == null) {
                p.sendMessage(Vaulty.msg("Gekocht! Je hebt nu " + menu.kluis.chests() + " kisten."));
                Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(KluisMenu.create(plugin, menu.kluis)));
            } else {
                p.sendMessage(Vaulty.err(res));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof KluisMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Kluis.Holder h) {
            plugin.kluizen().save(h.kluis());
            // een nieuwe naam toepassen zodra niemand meer kijkt
            Bukkit.getScheduler().runTask(plugin, () -> h.kluis().retitle());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Kluis.Holder && plugin.isBlockedWorld(p)) {
            p.closeInventory();
        }
    }

    /** Berichten van de markt die binnenkwamen terwijl de speler offline was. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        java.util.List<String> mail = plugin.market().takeMail(p.getUniqueId());
        if (mail.isEmpty()) return;
        plugin.market().save();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(Vaulty.msg("Terwijl je weg was, is er op de markt verkocht:"));
            for (String m : mail) p.sendMessage(Vaulty.msg(" - " + m));
        }, 60L);
    }
}
