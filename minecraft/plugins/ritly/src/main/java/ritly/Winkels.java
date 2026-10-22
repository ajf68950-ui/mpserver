package ritly;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** De winkels (winkels.yml) en het kopen. */
final class Winkels {

    record Aanbod(Product product, double prijs) {}

    static final class Winkel {
        final String id;
        String titel;
        final List<Aanbod> producten = new ArrayList<>();

        Winkel(String id, String titel) {
            this.id = id;
            this.titel = titel;
        }

        Aanbod zoek(String sleutel) {
            for (Aanbod a : producten) if (a.product().sleutel().equals(sleutel)) return a;
            return null;
        }
    }

    private final Ritly p;
    private final File bestand;
    private final Map<String, Winkel> winkels = new LinkedHashMap<>();

    Winkels(Ritly p) {
        this.p = p;
        this.bestand = new File(p.getDataFolder(), "winkels.yml");
    }

    void laad() {
        winkels.clear();
        if (!bestand.exists()) p.saveResource("winkels.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(bestand);
        ConfigurationSection s = y.getConfigurationSection("winkels");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection w = s.getConfigurationSection(id);
            if (w == null) continue;
            Winkel wk = new Winkel(id.toLowerCase(Locale.ROOT), w.getString("titel", id));
            for (Map<?, ?> map : w.getMapList("producten")) {
                Product pr = Product.lees(String.valueOf(map.get("product")));
                Object prijs = map.get("prijs");
                if (pr == null || !(prijs instanceof Number n)) {
                    p.getLogger().warning("Winkel " + id + ": product " + map + " klopt niet.");
                    continue;
                }
                if (pr.type == Product.Type.VOERTUIG && p.modellen().get(pr.waarde) == null)
                    p.getLogger().warning("Winkel " + id + ": model " + pr.waarde + " bestaat niet (staat wel in de winkel).");
                wk.producten.add(new Aanbod(pr, n.doubleValue()));
            }
            winkels.put(wk.id, wk);
        }
        p.getLogger().info(winkels.size() + " winkels geladen.");
    }

    void opslaan() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Ritly winkels. Producten: voertuig:<model>, onderdeel:<motor|stuur|tank>:<1-3>, lak:<kleur>, brandstof:<liter>.",
                "Openen via een NPC (/ritly npc maak <winkel>), Citizens (/npc command add ritly winkel open <winkel> <p>)",
                "of een showroom-display (/ritly showroom zet <winkel> <nummer>). Na aanpassen: /ritly herladen."));
        for (Winkel w : winkels.values()) {
            y.set("winkels." + w.id + ".titel", w.titel);
            List<Map<String, Object>> l = new ArrayList<>();
            for (Aanbod a : w.producten) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("product", a.product().sleutel());
                m.put("prijs", a.prijs() == Math.floor(a.prijs()) ? (Object) (long) a.prijs() : a.prijs());
                l.add(m);
            }
            y.set("winkels." + w.id + ".producten", l);
        }
        try {
            y.save(bestand);
        } catch (IOException e) {
            p.getLogger().warning("winkels.yml opslaan mislukt: " + e.getMessage());
        }
    }

    Winkel get(String id) {
        return id == null ? null : winkels.get(id.toLowerCase(Locale.ROOT));
    }

    Map<String, Winkel> alle() {
        return winkels;
    }

    Winkel maak(String id, String titel) {
        Winkel w = new Winkel(id.toLowerCase(Locale.ROOT), titel);
        winkels.put(w.id, w);
        opslaan();
        return w;
    }

    void verwijder(String id) {
        winkels.remove(id.toLowerCase(Locale.ROOT));
        opslaan();
    }

    // ------------------------------------------------------------------ namen en iconen

    String naam(Product pr) {
        return switch (pr.type) {
            case VOERTUIG -> {
                Model m = p.modellen().get(pr.waarde);
                yield m == null ? "&c" + pr.waarde + " (bestaat niet)" : m.naam;
            }
            case ONDERDEEL -> "&e" + Product.onderdeelNaam(pr.waarde) + " &7niveau &f" + pr.niveau;
            case LAK -> "&eLak: &f" + Tekst.kleurNaam(pr.kleur());
            case BRANDSTOF -> "&6Jerrycan &7(" + pr.niveau + " L)";
        };
    }

    ItemStack icoon(Aanbod a, boolean klikTekst) {
        Product pr = a.product();
        ItemStack is;
        List<String> lore = new ArrayList<>();
        if (pr.type == Product.Type.VOERTUIG) {
            Model m = p.modellen().get(pr.waarde);
            is = new ItemStack(m == null ? Material.BARRIER : m.icoon);
            if (m != null) {
                lore.add("&7Soort: &f" + m.soort.naam);
                lore.add("&7Topsnelheid: &f" + Math.round(m.maxKmu) + " km/u");
                lore.add("&7Optrekken: &f" + Math.round(m.acceleratie) + " km/u per seconde");
                lore.add("&7Zitplaatsen: &f" + m.stoelen());
                if (m.soort.brandstof && m.tank > 0 && p.brandstofAan()) lore.add("&7Tank: &f" + Math.round(m.tank) + " L");
                if (m.soort == Soort.VLIEGTUIG) lore.add("&7Opstijgen vanaf: &f" + Math.round(m.opstijgen) + " km/u");
                lore.add("&7Kleur: &f" + Tekst.kleurNaam(m.kleur));
                lore.add("&8Komt in je garage (/garage)");
            }
        } else {
            is = p.onderdelen().product(pr);
            ItemMeta im0 = is.getItemMeta();
            if (im0.lore() != null) {
                // de uitleg van het item zelf houden we kort
                if (pr.type == Product.Type.ONDERDEEL) lore.add("&7" + Onderdelen.uitlegOnderdeel(pr.waarde, pr.niveau, p));
                if (pr.type == Product.Type.LAK) lore.add("&7Geeft je voertuig een nieuwe kleur.");
                if (pr.type == Product.Type.BRANDSTOF) lore.add("&7Tankt " + pr.niveau + " liter bij.");
            }
        }
        ItemMeta im = is.getItemMeta();
        im.displayName(Tekst.kleur(naam(pr)));
        lore.add("");
        lore.add("&7Prijs: &a" + Tekst.geld(a.prijs()));
        if (klikTekst) lore.add("&eKlik om te kopen");
        im.lore(Tekst.regels(lore.toArray(new String[0])));
        im.addItemFlags(ItemFlag.values());
        is.setItemMeta(im);
        return is;
    }

    // ------------------------------------------------------------------ kopen

    /** Koopt het product. Geeft true als het gelukt is. Meldingen gaan naar de speler. */
    boolean koop(Player pl, Winkel w, Aanbod a) {
        if (!pl.hasPermission("ritly.gebruik")) {
            Tekst.stuur(pl, "Je mag hier niets kopen.");
            return false;
        }
        if (!p.koopWereld(pl.getWorld())) {
            Tekst.stuur(pl, "Kopen kan niet in deze wereld.");
            return false;
        }
        if (p.creativeGeblokkeerd(pl)) {
            Tekst.stuur(pl, "Kopen kan niet in creative of als toeschouwer.");
            return false;
        }
        Product pr = a.product();
        Model m = null;
        if (pr.type == Product.Type.VOERTUIG) {
            m = p.modellen().get(pr.waarde);
            if (m == null) {
                Tekst.stuur(pl, "Dit voertuig bestaat niet meer.");
                return false;
            }
            int max = p.maxVoertuigen(pl);
            if (p.garage().van(pl.getUniqueId()).size() >= max) {
                Tekst.stuur(pl, "Je garage is vol (max " + max + " voertuigen).");
                return false;
            }
        } else if (pl.getInventory().firstEmpty() < 0) {
            Tekst.stuur(pl, "Je inventaris is vol.");
            return false;
        }
        Economy eco = p.economie();
        if (eco == null) {
            Tekst.stuur(pl, "De economie werkt nu niet (Vault ontbreekt).");
            return false;
        }
        if (!eco.has(pl, a.prijs())) {
            Tekst.stuur(pl, "Je hebt niet genoeg geld. Dit kost &f" + Tekst.geld(a.prijs()) + "&7, je hebt &f" + Tekst.geld(eco.getBalance(pl)) + "&7.");
            return false;
        }
        EconomyResponse r = eco.withdrawPlayer(pl, a.prijs());
        if (r == null || !r.transactionSuccess()) {
            Tekst.stuur(pl, "Betalen is mislukt" + (r != null && r.errorMessage != null ? ": " + r.errorMessage : "") + ".");
            return false;
        }
        if (m != null) {
            Voertuig v = new Voertuig(UUID.randomUUID(), pl.getUniqueId(), m.id);
            v.kleur = m.kleur;
            v.brandstof = v.tankInhoud(m, p);
            p.garage().voegToe(v);
            p.garage().opslaan();
            Tekst.stuur(pl, "Gekocht: " + m.naam + "&7 voor &a" + Tekst.geld(a.prijs()) + "&7. Hij staat in je garage: &f/garage");
        } else {
            pl.getInventory().addItem(p.onderdelen().product(pr));
            Tekst.stuur(pl, "Gekocht: " + naam(pr) + "&7 voor &a" + Tekst.geld(a.prijs()) + "&7.");
        }
        pl.playSound(pl.getLocation(), "entity.experience_orb.pickup", 1f, 1.2f);
        log(pl.getName() + " kocht " + pr.sleutel() + " in " + w.id + " voor " + a.prijs());
        return true;
    }

    void log(String regel) {
        File f = new File(p.getDataFolder(), "aankopen.log");
        String r = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "  " + regel + System.lineSeparator();
        try {
            Files.writeString(f.toPath(), r, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
