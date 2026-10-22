package ritly;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** De items: onderdelen, lak, jerrycans en de bouwstaf. Ze kunnen niet geplaatst, gebruikt of gecraft worden. */
final class Onderdelen implements Listener {

    /** Wat een item is: soort = motor/stuur/tank/lak/brandstof/staf. */
    record Item(String soort, int niveau, DyeColor kleur) {}

    private final Ritly p;

    Onderdelen(Ritly p) {
        this.p = p;
    }

    static Material materiaal(String onderdeel) {
        return switch (onderdeel) {
            case "motor" -> Material.PISTON;
            case "stuur" -> Material.LEVER;
            case "tank" -> Material.CAULDRON;
            default -> Material.PAPER;
        };
    }

    static String uitlegOnderdeel(String o, int niveau, Ritly p) {
        double b = Voertuig.bonus(p.upgrade(o), niveau) * 100;
        return switch (o) {
            case "motor" -> "+" + Math.round(b) + "% snelheid en optrekken";
            case "stuur" -> "+" + Math.round(b) + "% beter draaien";
            case "tank" -> "+" + Math.round(b) + "% grotere tank";
            default -> "";
        };
    }

    ItemStack onderdeel(String o, int niveau) {
        ItemStack is = new ItemStack(materiaal(o));
        ItemMeta im = is.getItemMeta();
        im.displayName(Tekst.kleur("&e" + Product.onderdeelNaam(o) + " &7niveau &f" + niveau));
        List<String> lore = new ArrayList<>();
        lore.add("&7" + uitlegOnderdeel(o, niveau, p));
        lore.add("&8Past in: " + pastIn(o));
        lore.add("");
        lore.add("&7Inbouwen: &f/garage &7> voertuig > &fWerkplaats");
        im.lore(Tekst.regels(lore.toArray(new String[0])));
        im.getPersistentDataContainer().set(p.sleutelItem, PersistentDataType.STRING, o + ":" + niveau);
        is.setItemMeta(im);
        return is;
    }

    private String pastIn(String o) {
        List<String> l = new ArrayList<>();
        for (Soort s : Soort.values()) if (p.past(s, o)) l.add(s.naam.toLowerCase(Locale.ROOT));
        return String.join(", ", l);
    }

    ItemStack lak(DyeColor k) {
        Material mat = Material.matchMaterial(k.name() + "_DYE");
        ItemStack is = new ItemStack(mat == null ? Material.WHITE_DYE : mat);
        ItemMeta im = is.getItemMeta();
        im.displayName(Tekst.kleur("&eLak: &f" + Tekst.kleurNaam(k)));
        im.lore(Tekst.regels("&7Geeft je voertuig een nieuwe kleur.", "",
                "&7Rechtsklik op je voertuig, of", "&7in de &fWerkplaats &7(/garage)."));
        im.getPersistentDataContainer().set(p.sleutelItem, PersistentDataType.STRING, "lak:" + k.name().toLowerCase(Locale.ROOT));
        is.setItemMeta(im);
        return is;
    }

    ItemStack jerrycan(int liter) {
        ItemStack is = new ItemStack(Material.LAVA_BUCKET);
        ItemMeta im = is.getItemMeta();
        im.displayName(Tekst.kleur("&6Jerrycan &7(" + liter + " L brandstof)"));
        im.lore(Tekst.regels("&7Tankt " + liter + " liter bij.", "",
                "&7Rechtsklik op je voertuig, of", "&fBijtanken &7in /garage."));
        im.getPersistentDataContainer().set(p.sleutelItem, PersistentDataType.STRING, "brandstof:" + liter);
        is.setItemMeta(im);
        return is;
    }

    ItemStack staf(boolean beheer) {
        ItemStack is = new ItemStack(Material.BLAZE_ROD);
        ItemMeta im = is.getItemMeta();
        im.displayName(Tekst.kleur("&6&lBouwstaf"));
        im.lore(Tekst.regels("&7Linksklik op een blok: &fhoek 1", "&7Rechtsklik op een blok: &fhoek 2", "",
                "&7Gouden drukplaat = stoel bestuurder", "&7IJzeren drukplaat = stoel passagier",
                "&7Witte blokken krijgen de lakkleur.", "",
                beheer ? "&7Opslaan: &f/ritly bouw opslaan <id> <soort>" : "&7Klaar: &f/garage bouw klaar <soort>"));
        im.getPersistentDataContainer().set(p.sleutelItem, PersistentDataType.STRING, "staf:" + (beheer ? 1 : 0));
        is.setItemMeta(im);
        return is;
    }

    ItemStack product(Product pr) {
        return switch (pr.type) {
            case ONDERDEEL -> onderdeel(pr.waarde, pr.niveau);
            case LAK -> lak(pr.kleur());
            case BRANDSTOF -> jerrycan(pr.niveau);
            default -> null;
        };
    }

    Item lees(ItemStack is) {
        if (is == null || is.getType().isAir() || !is.hasItemMeta()) return null;
        String s = is.getItemMeta().getPersistentDataContainer().get(p.sleutelItem, PersistentDataType.STRING);
        if (s == null) return null;
        String[] t = s.split(":");
        try {
            if (t[0].equals("lak")) return new Item("lak", 0, Tekst.dye(t[1]));
            return new Item(t[0], t.length > 1 ? Integer.parseInt(t[1]) : 0, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    boolean isOnsItem(ItemStack is) {
        return lees(is) != null;
    }

    // ------------------------------------------------------------------ bescherming

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void plaats(BlockPlaceEvent e) {
        if (isOnsItem(e.getItemInHand())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void gebruik(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        Item it = lees(e.getItem());
        if (it == null || it.soort().equals("staf")) return;
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opEntity(PlayerInteractEntityEvent e) {
        if (p.rijden().vanEntity(e.getRightClicked()) != null) return;
        if (p.showrooms().isShowroom(e.getRightClicked()) || p.npcs().winkelVan(e.getRightClicked()) != null) return;
        ItemStack hand = e.getPlayer().getInventory().getItem(e.getHand());
        if (isOnsItem(hand)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void emmer(PlayerBucketEmptyEvent e) {
        if (isOnsItem(e.getPlayer().getInventory().getItem(e.getHand()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void eten(PlayerItemConsumeEvent e) {
        if (isOnsItem(e.getItem())) e.setCancelled(true);
    }

    @EventHandler
    public void craft(PrepareItemCraftEvent e) {
        for (ItemStack is : e.getInventory().getMatrix())
            if (isOnsItem(is)) {
                e.getInventory().setResult(null);
                return;
            }
    }

    @EventHandler
    public void aambeeld(PrepareAnvilEvent e) {
        for (ItemStack is : e.getInventory().getContents())
            if (isOnsItem(is)) {
                e.setResult(null);
                return;
            }
    }

    @EventHandler(ignoreCancelled = true)
    public void oven(FurnaceBurnEvent e) {
        if (isOnsItem(e.getFuel())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void dispenser(BlockDispenseEvent e) {
        if (isOnsItem(e.getItem())) e.setCancelled(true);
    }
}
