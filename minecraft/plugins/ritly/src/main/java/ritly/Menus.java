package ritly;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Alle menu's: winkel, kopen, garage, voertuig, werkplaats. */
final class Menus implements Listener {

    enum Type { WINKEL, BEVESTIG, GARAGE, VOERTUIG, WERKPLAATS, WEGGOOIEN }

    static final class Houder implements InventoryHolder {
        final Type type;
        String winkel, product;
        UUID voertuig, eigenaar;
        final List<UUID> lijst = new ArrayList<>();
        Inventory inv;

        Houder(Type type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final Ritly p;

    Menus(Ritly p) {
        this.p = p;
    }

    private static ItemStack knop(Material mat, String naam, String... lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta im = is.getItemMeta();
        im.displayName(Tekst.kleur(naam));
        if (lore.length > 0) im.lore(Tekst.regels(lore));
        im.addItemFlags(ItemFlag.values());
        is.setItemMeta(im);
        return is;
    }

    private static void vul(Inventory inv) {
        ItemStack glas = knop(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, glas);
    }

    // ------------------------------------------------------------------ winkel

    void openWinkel(Player pl, Winkels.Winkel w) {
        int n = Math.min(45, w.producten.size());
        int rijen = Math.max(2, Math.min(6, (n + 8) / 9 + 1));
        Houder h = new Houder(Type.WINKEL);
        h.winkel = w.id;
        Inventory inv = Bukkit.createInventory(h, rijen * 9, Tekst.kleur(w.titel));
        h.inv = inv;
        for (int i = 0; i < n; i++) inv.setItem(i, p.winkels().icoon(w.producten.get(i), true));
        int onder = (rijen - 1) * 9;
        Economy eco = p.economie();
        inv.setItem(onder + 3, knop(Material.MINECART, "&eJe garage", "&7Typ &f/garage &7om je voertuigen", "&7neer te zetten en samen te stellen."));
        inv.setItem(onder + 4, knop(Material.SUNFLOWER, "&6Je geld: &f" + (eco == null ? "?" : Tekst.geld(eco.getBalance(pl)))));
        inv.setItem(onder + 5, knop(Material.BARRIER, "&cSluiten"));
        for (int i = onder; i < onder + 9; i++) if (inv.getItem(i) == null) inv.setItem(i, knop(Material.BLACK_STAINED_GLASS_PANE, " "));
        pl.openInventory(inv);
    }

    private void openBevestig(Player pl, Winkels.Winkel w, Winkels.Aanbod a) {
        Houder h = new Houder(Type.BEVESTIG);
        h.winkel = w.id;
        h.product = a.product().sleutel();
        Inventory inv = Bukkit.createInventory(h, 27, Tekst.kleur("&8Kopen?"));
        h.inv = inv;
        inv.setItem(13, p.winkels().icoon(a, false));
        inv.setItem(11, knop(Material.LIME_CONCRETE, "&a&lKopen voor " + Tekst.geld(a.prijs())));
        inv.setItem(15, knop(Material.RED_CONCRETE, "&c&lAnnuleren"));
        vul(inv);
        pl.openInventory(inv);
    }

    /** Vanuit een showroom: meteen de bevestiging. */
    void openKoop(Player pl, Winkels.Winkel w, String sleutel) {
        Winkels.Aanbod a = w.zoek(sleutel);
        if (a == null) {
            Tekst.stuur(pl, "Dit is nu niet te koop.");
            return;
        }
        openBevestig(pl, w, a);
    }

    // ------------------------------------------------------------------ garage

    void openGarage(Player pl, UUID eigenaar) {
        Houder h = new Houder(Type.GARAGE);
        h.eigenaar = eigenaar;
        boolean eigen = eigenaar.equals(pl.getUniqueId());
        String naam = eigen ? "&8Je garage" : "&8Garage van " + Bukkit.getOfflinePlayer(eigenaar).getName();
        Inventory inv = Bukkit.createInventory(h, 54, Tekst.kleur(naam));
        h.inv = inv;
        List<Voertuig> lijst = p.garage().van(eigenaar);
        int i = 0;
        for (Voertuig v : lijst) {
            if (i >= 45) break;
            inv.setItem(i++, icoon(v, true));
            h.lijst.add(v.id);
        }
        if (lijst.isEmpty())
            inv.setItem(22, knop(Material.MINECART, "&7Je hebt nog geen voertuigen",
                    "&7Koop er een bij een winkel:", "&7de fietsenwinkel, autodealer,", "&7jachthaven of luchthaven."));
        inv.setItem(45, knop(Material.BLAZE_ROD, "&6Zelf bouwen",
                "&7Bouw een voertuig uit blokken:", "&f/garage bouw staf", "&7selecteer je bouwwerk en dan",
                "&f/garage bouw klaar <soort>", "&8soort: fiets, auto, boot, vliegtuig"));
        inv.setItem(49, knop(Material.BARRIER, "&cSluiten"));
        inv.setItem(53, knop(Material.BOOK, "&eUitleg",
                "&7Klik op een voertuig om het neer", "&7te zetten of samen te stellen.", "",
                "&7Instappen: &frechtsklik &7op het voertuig", "&7Opbergen: &fshift + rechtsklik",
                "&7Tanken: rechtsklik met een &fjerrycan", "&7Lakken: rechtsklik met &flak"));
        for (int s = 45; s < 54; s++) if (inv.getItem(s) == null) inv.setItem(s, knop(Material.BLACK_STAINED_GLASS_PANE, " "));
        pl.openInventory(inv);
    }

    private ItemStack icoon(Voertuig v, boolean klik) {
        Model m = p.modellen().get(v.model);
        ItemStack is = new ItemStack(m == null ? Material.BARRIER : m.icoon);
        ItemMeta im = is.getItemMeta();
        im.displayName(Tekst.kleur(v.titel(m)));
        List<String> lore = new ArrayList<>();
        if (m == null) lore.add("&cHet model " + v.model + " bestaat niet meer.");
        else {
            if (v.naam != null) lore.add("&7Model: " + m.naam);
            lore.add("&7Soort: &f" + m.soort.naam + (m.eigenaar != null ? " &8(zelf gebouwd)" : ""));
            lore.add("&7Kleur: &f" + Tekst.kleurNaam(v.kleur));
            lore.add("&7Topsnelheid: &f" + Math.round(v.maxKmu(m, p)) + " km/u");
            List<String> ond = new ArrayList<>();
            for (String o : Product.ONDERDELEN) {
                int n = o.equals("motor") ? v.motor : o.equals("stuur") ? v.stuur : v.tank;
                if (p.past(m.soort, o)) ond.add(Product.onderdeelNaam(o) + " " + (n == 0 ? "-" : n));
            }
            lore.add("&7Onderdelen: &f" + String.join(", ", ond));
            if (v.heeftBrandstofNodig(m, p))
                lore.add("&7Brandstof: &f" + Math.round(v.brandstof) + "&7/" + Math.round(v.tankInhoud(m, p)) + " L");
            lore.add("&7Op slot: &f" + (v.opSlot ? "ja" : "nee"));
            lore.add(v.inGarage() ? "&aStaat in de garage" : "&eStaat buiten: &f" + v.wereld + " " + (int) v.x + ", " + (int) v.y + ", " + (int) v.z);
        }
        if (klik) {
            lore.add("");
            lore.add("&eKlik voor opties");
        }
        im.lore(Tekst.regels(lore.toArray(new String[0])));
        im.addItemFlags(ItemFlag.values());
        is.setItemMeta(im);
        return is;
    }

    void openVoertuig(Player pl, Voertuig v) {
        Model m = p.modellen().get(v.model);
        Houder h = new Houder(Type.VOERTUIG);
        h.voertuig = v.id;
        h.eigenaar = v.eigenaar;
        Inventory inv = Bukkit.createInventory(h, 27, Tekst.kleur("&8" + Tekst.zonderKleur(v.titel(m))));
        h.inv = inv;
        inv.setItem(4, icoon(v, false));
        inv.setItem(10, knop(Material.LIME_DYE, v.inGarage() ? "&a&lNeerzetten" : "&a&lHierheen halen",
                "&7Zet het voertuig neer waar je kijkt.", m != null && m.soort == Soort.BOOT ? "&7Kijk naar water." : "&7Kijk naar een vrije plek."));
        if (!v.inGarage()) inv.setItem(11, knop(Material.CHEST, "&e&lOpbergen", "&7Haalt het voertuig weg en zet", "&7het in je garage."));
        inv.setItem(12, knop(Material.SMITHING_TABLE, "&6&lWerkplaats", "&7Onderdelen inbouwen en lakken."));
        if (m != null && v.heeftBrandstofNodig(m, p))
            inv.setItem(13, knop(Material.LAVA_BUCKET, "&6&lBijtanken", "&7Gebruikt een jerrycan uit je inventaris.",
                    "&7Nu: &f" + Math.round(v.brandstof) + "&7/" + Math.round(v.tankInhoud(m, p)) + " L"));
        inv.setItem(14, knop(v.opSlot ? Material.IRON_DOOR : Material.OAK_DOOR, v.opSlot ? "&c&lOp slot" : "&a&lOpen",
                v.opSlot ? "&7Alleen jij kunt rijden. Anderen kunnen" : "&7Iedereen mag rijden en instappen.",
                v.opSlot ? "&7alleen meerijden als jij er in zit." : "", "&eKlik om te wisselen"));
        inv.setItem(15, knop(Material.COMPASS, "&b&lWaar staat hij?", v.inGarage() ? "&7In de garage." :
                "&7" + v.wereld + " &f" + (int) v.x + ", " + (int) v.y + ", " + (int) v.z));
        inv.setItem(16, knop(Material.BARRIER, "&4&lWeggooien", "&7Voertuig verwijderen.", "&cDit kan niet ongedaan worden."));
        inv.setItem(22, knop(Material.ARROW, "&7Terug"));
        vul(inv);
        pl.openInventory(inv);
    }

    void openWerkplaats(Player pl, Voertuig v) {
        Model m = p.modellen().get(v.model);
        if (m == null) return;
        Houder h = new Houder(Type.WERKPLAATS);
        h.voertuig = v.id;
        h.eigenaar = v.eigenaar;
        Inventory inv = Bukkit.createInventory(h, 27, Tekst.kleur("&8Werkplaats"));
        h.inv = inv;
        inv.setItem(4, icoon(v, false));
        int[] slots = {10, 12, 14};
        for (int i = 0; i < 3; i++) {
            String o = Product.ONDERDELEN[i];
            if (!p.past(m.soort, o)) {
                inv.setItem(slots[i], knop(Material.BLACK_STAINED_GLASS_PANE, "&8" + Product.onderdeelNaam(o) + ": past niet"));
                continue;
            }
            int n = niveau(v, o);
            if (n > 0) {
                ItemStack is = p.onderdelen().onderdeel(o, n);
                ItemMeta im = is.getItemMeta();
                List<net.kyori.adventure.text.Component> l = new ArrayList<>(Tekst.regels("&aIngebouwd", "&7" + Onderdelen.uitlegOnderdeel(o, n, p), "", "&eKlik om eruit te halen"));
                im.lore(l);
                is.setItemMeta(im);
                inv.setItem(slots[i], is);
            } else {
                inv.setItem(slots[i], knop(Material.WHITE_STAINED_GLASS_PANE, "&7Geen " + Product.onderdeelNaam(o).toLowerCase() + "-upgrade",
                        "&7Klik op een " + Product.onderdeelNaam(o).toLowerCase() + " in je", "&7inventaris om hem in te bouwen."));
            }
        }
        Material dye = Material.matchMaterial((v.kleur == null ? "WHITE" : v.kleur.name()) + "_DYE");
        inv.setItem(16, knop(dye == null ? Material.WHITE_DYE : dye, "&eLak: &f" + Tekst.kleurNaam(v.kleur),
                "&7Klik op lak in je inventaris", "&7om de kleur te veranderen."));
        if (v.heeftBrandstofNodig(m, p))
            inv.setItem(18, knop(Material.LAVA_BUCKET, "&6Brandstof: &f" + Math.round(v.brandstof) + "&7/" + Math.round(v.tankInhoud(m, p)) + " L",
                    "&7Klik op een jerrycan in je inventaris."));
        inv.setItem(22, knop(Material.ARROW, "&7Terug"));
        vul(inv);
        pl.openInventory(inv);
    }

    private static int niveau(Voertuig v, String o) {
        return switch (o) {
            case "motor" -> v.motor;
            case "stuur" -> v.stuur;
            default -> v.tank;
        };
    }

    private static void zetNiveau(Voertuig v, String o, int n) {
        switch (o) {
            case "motor" -> v.motor = n;
            case "stuur" -> v.stuur = n;
            default -> v.tank = n;
        }
    }

    private void openWeggooien(Player pl, Voertuig v) {
        Houder h = new Houder(Type.WEGGOOIEN);
        h.voertuig = v.id;
        h.eigenaar = v.eigenaar;
        Inventory inv = Bukkit.createInventory(h, 27, Tekst.kleur("&4Echt weggooien?"));
        h.inv = inv;
        inv.setItem(13, icoon(v, false));
        inv.setItem(11, knop(Material.RED_CONCRETE, "&4&lJa, weggooien", "&cJe krijgt geen geld terug."));
        inv.setItem(15, knop(Material.LIME_CONCRETE, "&a&lNee, houden"));
        vul(inv);
        pl.openInventory(inv);
    }

    // ------------------------------------------------------------------ klikken

    @EventHandler
    public void sleep(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Houder) e.setCancelled(true);
    }

    @EventHandler
    public void klik(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Houder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player pl)) return;
        int slot = e.getRawSlot();
        boolean boven = slot >= 0 && slot < e.getView().getTopInventory().getSize();
        Voertuig v = h.voertuig == null ? null : p.garage().get(h.voertuig);
        if (h.voertuig != null && (v == null || !magBeheren(pl, v))) {
            pl.closeInventory();
            return;
        }
        switch (h.type) {
            case WINKEL -> {
                if (!boven) return;
                Winkels.Winkel w = p.winkels().get(h.winkel);
                if (w == null) {
                    pl.closeInventory();
                    return;
                }
                int onder = e.getView().getTopInventory().getSize() - 9;
                if (slot == onder + 5) pl.closeInventory();
                else if (slot < Math.min(45, w.producten.size())) openBevestig(pl, w, w.producten.get(slot));
            }
            case BEVESTIG -> {
                if (!boven) return;
                Winkels.Winkel w = p.winkels().get(h.winkel);
                if (w == null) {
                    pl.closeInventory();
                    return;
                }
                if (slot == 11) {
                    Winkels.Aanbod a = w.zoek(h.product);
                    if (a == null) Tekst.stuur(pl, "Dit is nu niet te koop.");
                    else if (p.winkels().koop(pl, w, a)) {
                        openWinkel(pl, w);
                        return;
                    }
                    pl.closeInventory();
                } else if (slot == 15) openWinkel(pl, w);
            }
            case GARAGE -> {
                if (!boven) return;
                if (slot == 49) pl.closeInventory();
                else if (slot < h.lijst.size()) {
                    Voertuig gekozen = p.garage().get(h.lijst.get(slot));
                    if (gekozen != null) openVoertuig(pl, gekozen);
                }
            }
            case VOERTUIG -> {
                if (!boven || v == null) return;
                switch (slot) {
                    case 10 -> {
                        pl.closeInventory();
                        String fout = p.rijden().uitrijden(pl, v);
                        if (fout != null) Tekst.stuur(pl, fout);
                        else Tekst.stuur(pl, "Daar staat hij! &fRechtsklik &7om in te stappen.");
                    }
                    case 11 -> {
                        if (v.inGarage()) return;
                        ActiefVoertuig a = p.rijden().actief(v.id);
                        if (a != null && !a.inzittenden().isEmpty()) {
                            Tekst.stuur(pl, "Er zit nog iemand in.");
                            return;
                        }
                        p.rijden().opbergen(v);
                        Tekst.stuur(pl, "Opgeborgen in je garage.");
                        openVoertuig(pl, v);
                    }
                    case 12 -> openWerkplaats(pl, v);
                    case 13 -> {
                        ItemStack jc = zoekItem(pl, "brandstof");
                        if (jc == null) Tekst.stuur(pl, "Je hebt geen jerrycan. Koop er een bij een tankstation of winkel.");
                        else p.rijden().tanken(pl, v, jc);
                        openVoertuig(pl, v);
                    }
                    case 14 -> {
                        v.opSlot = !v.opSlot;
                        p.garage().wijzig();
                        openVoertuig(pl, v);
                    }
                    case 15 -> {
                        if (!v.inGarage()) Tekst.stuur(pl, "Je " + Tekst.zonderKleur(v.titel(p.modellen().get(v.model))) + " staat in &f" + v.wereld + "&7 bij &f" + (int) v.x + " " + (int) v.y + " " + (int) v.z);
                    }
                    case 16 -> openWeggooien(pl, v);
                    case 22 -> openGarage(pl, v.eigenaar);
                    default -> {}
                }
            }
            case WERKPLAATS -> {
                if (v == null) return;
                Model m = p.modellen().get(v.model);
                if (m == null) return;
                if (boven) {
                    if (slot == 22) {
                        openVoertuig(pl, v);
                        return;
                    }
                    int idx = slot == 10 ? 0 : slot == 12 ? 1 : slot == 14 ? 2 : -1;
                    if (idx < 0) return;
                    String o = Product.ONDERDELEN[idx];
                    int n = niveau(v, o);
                    if (n <= 0 || !p.past(m.soort, o)) return;
                    if (pl.getInventory().firstEmpty() < 0) {
                        Tekst.stuur(pl, "Maak eerst ruimte in je inventaris.");
                        return;
                    }
                    zetNiveau(v, o, 0);
                    if (o.equals("tank")) v.brandstof = Math.min(v.brandstof, v.tankInhoud(m, p));
                    pl.getInventory().addItem(p.onderdelen().onderdeel(o, n));
                    p.garage().wijzig();
                    pl.playSound(pl.getLocation(), "block.anvil.use", 0.6f, 1.4f);
                    openWerkplaats(pl, v);
                    return;
                }
                ItemStack is = e.getCurrentItem();
                Onderdelen.Item it = p.onderdelen().lees(is);
                if (it == null) return;
                if (it.soort().equals("brandstof")) {
                    p.rijden().tanken(pl, v, is);
                    openWerkplaats(pl, v);
                    return;
                }
                if (p.creativeGeblokkeerd(pl)) {
                    Tekst.stuur(pl, "Inbouwen kan niet in creative of als toeschouwer.");
                    return;
                }
                if (it.soort().equals("lak")) {
                    p.rijden().lakken(pl, v, it.kleur(), is);
                    openWerkplaats(pl, v);
                    return;
                }
                if (!List.of(Product.ONDERDELEN).contains(it.soort())) return;
                String o = it.soort();
                if (!p.past(m.soort, o)) {
                    Tekst.stuur(pl, "Een " + Product.onderdeelNaam(o).toLowerCase() + " past niet in een " + m.soort.naam.toLowerCase() + ".");
                    return;
                }
                int oud = niveau(v, o);
                if (oud == it.niveau()) {
                    Tekst.stuur(pl, "Dit onderdeel zit er al in.");
                    return;
                }
                if (oud > 0 && is.getAmount() > 1 && pl.getInventory().firstEmpty() < 0) {
                    Tekst.stuur(pl, "Maak eerst ruimte in je inventaris voor het oude onderdeel.");
                    return;
                }
                is.setAmount(is.getAmount() - 1);
                zetNiveau(v, o, it.niveau());
                if (o.equals("tank")) v.brandstof = Math.min(v.brandstof, v.tankInhoud(m, p));
                if (oud > 0) pl.getInventory().addItem(p.onderdelen().onderdeel(o, oud));
                p.garage().wijzig();
                pl.playSound(pl.getLocation(), "block.smithing_table.use", 1f, 1f);
                Tekst.stuur(pl, Product.onderdeelNaam(o) + " niveau " + it.niveau() + " ingebouwd.");
                openWerkplaats(pl, v);
            }
            case WEGGOOIEN -> {
                if (!boven || v == null) return;
                if (slot == 11) {
                    ActiefVoertuig a = p.rijden().actief(v.id);
                    if (a != null && !a.inzittenden().isEmpty()) {
                        Tekst.stuur(pl, "Er zit nog iemand in.");
                        pl.closeInventory();
                        return;
                    }
                    p.rijden().opbergen(v);
                    p.garage().verwijder(v);
                    p.garage().opslaan();
                    Model m = p.modellen().get(v.model);
                    if (m != null && m.eigenaar != null && p.garage().aantalMetModel(m.id) == 0) p.modellen().verwijderGebouwd(m.id);
                    p.winkels().log(pl.getName() + " gooide voertuig " + v.model + " (" + v.id + ") weg");
                    Tekst.stuur(pl, "Het voertuig is weggegooid.");
                    openGarage(pl, h.eigenaar);
                } else if (slot == 15) openVoertuig(pl, v);
            }
        }
    }

    private boolean magBeheren(Player pl, Voertuig v) {
        return v.eigenaar.equals(pl.getUniqueId()) || pl.hasPermission("ritly.beheer");
    }

    private ItemStack zoekItem(Player pl, String soort) {
        for (ItemStack is : pl.getInventory().getStorageContents()) {
            Onderdelen.Item it = p.onderdelen().lees(is);
            if (it != null && it.soort().equals(soort)) return is;
        }
        return null;
    }
}
