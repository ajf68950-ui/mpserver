package ritly;

import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /garage voor spelers en /ritly voor beheer. */
final class Commando implements CommandExecutor, TabCompleter {

    private record Wacht(Soort soort, float schaal, String naam, long tijd) {}

    private final Ritly p;
    private final Map<UUID, Wacht> wachtend = new HashMap<>();

    Commando(Ritly p) {
        this.p = p;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("garage")) garage(s, a);
        else ritly(s, a);
        return true;
    }

    // ================================================================== /garage

    private void garage(CommandSender s, String[] a) {
        if (!(s instanceof Player pl)) {
            Tekst.stuur(s, "Alleen in het spel.");
            return;
        }
        if (!pl.hasPermission("ritly.gebruik")) {
            Tekst.stuur(pl, "Je mag de garage niet gebruiken.");
            return;
        }
        if (a.length == 0) {
            p.menus().openGarage(pl, pl.getUniqueId());
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "help" -> hulpGarage(pl);
            case "opbergen" -> {
                int n = 0;
                for (Voertuig v : p.garage().van(pl.getUniqueId())) {
                    if (v.inGarage()) continue;
                    ActiefVoertuig av = p.rijden().actief(v.id);
                    if (av != null && !av.inzittenden().isEmpty()) continue;
                    p.rijden().opbergen(v);
                    n++;
                }
                Tekst.stuur(pl, n == 0 ? "Er stond niets buiten (of er zit iemand in)." : n + " voertuig(en) terug in je garage.");
            }
            case "naam" -> {
                ActiefVoertuig av = p.rijden().vanEntity(pl.getVehicle());
                if (av == null || !av.v.eigenaar.equals(pl.getUniqueId())) {
                    Tekst.stuur(pl, "Ga eerst in je eigen voertuig zitten.");
                    return;
                }
                if (a.length < 2) {
                    av.v.naam = null;
                    Tekst.stuur(pl, "De eigen naam is weg.");
                } else {
                    String naam = Tekst.zonderKleur(String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
                    if (naam.length() > 24) naam = naam.substring(0, 24);
                    av.v.naam = naam;
                    Tekst.stuur(pl, "Je voertuig heet nu &f" + naam + "&7.");
                }
                p.garage().wijzig();
            }
            case "bouw" -> bouwSpeler(pl, a);
            default -> {
                if (pl.hasPermission("ritly.beheer")) {
                    OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[0]);
                    if (op != null) {
                        p.menus().openGarage(pl, op.getUniqueId());
                        return;
                    }
                }
                hulpGarage(pl);
            }
        }
    }

    private void hulpGarage(Player pl) {
        Tekst.stuur(pl, "&6Garage:");
        pl.sendMessage(Tekst.kleur("&f/garage &7- je voertuigen (neerzetten, werkplaats, tanken)"));
        pl.sendMessage(Tekst.kleur("&f/garage opbergen &7- alles wat buiten staat terug naar je garage"));
        pl.sendMessage(Tekst.kleur("&f/garage naam <naam> &7- je voertuig een naam geven (zit erin)"));
        if (pl.hasPermission("ritly.bouw.eigen")) {
            pl.sendMessage(Tekst.kleur("&f/garage bouw staf &7- bouwstaf om je bouwwerk te selecteren"));
            pl.sendMessage(Tekst.kleur("&f/garage bouw klaar <soort> [schaal] [naam] &7- er een voertuig van maken"));
        }
        pl.sendMessage(Tekst.kleur("&7Instappen: rechtsklik. Opbergen: shift + rechtsklik. Toeter: linksklik."));
    }

    private void bouwSpeler(Player pl, String[] a) {
        if (!pl.hasPermission("ritly.bouw.eigen")) {
            Tekst.stuur(pl, "Je mag geen eigen voertuigen bouwen.");
            return;
        }
        if (a.length < 2) {
            Tekst.stuur(pl, "&f/garage bouw staf &7of &f/garage bouw klaar <fiets|auto|boot|vliegtuig> [schaal 0.25-1] [naam]");
            return;
        }
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "staf" -> geefStaf(pl, false);
            case "klaar" -> {
                if (a.length < 3) {
                    Tekst.stuur(pl, "Welke soort? &f/garage bouw klaar <fiets|auto|boot|vliegtuig> [schaal] [naam]");
                    return;
                }
                Soort soort = Soort.van(a[2]);
                if (soort == null) {
                    Tekst.stuur(pl, "Soort moet fiets, auto, boot of vliegtuig zijn.");
                    return;
                }
                float schaal = 1f;
                int naamVan = 3;
                if (a.length > 3) {
                    try {
                        schaal = Float.parseFloat(a[3].replace(',', '.'));
                        naamVan = 4;
                    } catch (NumberFormatException ignored) {}
                }
                schaal = Math.max(0.25f, Math.min(1f, schaal));
                String naam = a.length > naamVan ? Tekst.zonderKleur(String.join(" ", Arrays.copyOfRange(a, naamVan, a.length))) : null;
                if (naam != null && naam.length() > 24) naam = naam.substring(0, 24);
                Bouwer.Selectie sel = p.bouwer().selectie(pl);
                if (sel == null) {
                    Tekst.stuur(pl, "Selecteer eerst twee hoeken met de bouwstaf (&f/garage bouw staf&7).");
                    return;
                }
                Bouwer.Resultaat r = p.bouwer().maakModel(pl, sel, "test", soort, schaal, "test", pl.getUniqueId(), false);
                if (r.fout() != null) {
                    Tekst.stuur(pl, r.fout());
                    return;
                }
                double prijs = r.blokken() * p.getConfig().getDouble("bouwen.prijs-per-blok", 250);
                wachtend.put(pl.getUniqueId(), new Wacht(soort, schaal, naam, System.currentTimeMillis()));
                Tekst.stuur(pl, "Je " + soort.naam.toLowerCase(Locale.ROOT) + " heeft " + r.blokken() + " blokken en kost &a" + Tekst.geld(prijs)
                        + "&7. Typ &f/garage bouw bevestig &7om hem te maken. De voorkant is de kant waar je nu naar kijkt.");
            }
            case "bevestig" -> {
                Wacht w = wachtend.remove(pl.getUniqueId());
                if (w == null || System.currentTimeMillis() - w.tijd() > 120_000) {
                    Tekst.stuur(pl, "Gebruik eerst &f/garage bouw klaar <soort>&7.");
                    return;
                }
                String fout = p.bouwer().opslaanSpeler(pl, w.soort(), w.schaal(), w.naam());
                if (fout != null) Tekst.stuur(pl, fout);
            }
            default -> Tekst.stuur(pl, "&f/garage bouw staf &7of &f/garage bouw klaar <soort>");
        }
    }

    private void geefStaf(Player pl, boolean beheer) {
        if (pl.getInventory().firstEmpty() < 0) {
            Tekst.stuur(pl, "Je inventaris is vol.");
            return;
        }
        pl.getInventory().addItem(p.onderdelen().staf(beheer));
        Tekst.stuur(pl, "Linksklik = hoek 1, rechtsklik = hoek 2. Gouden drukplaat = stoel van de bestuurder, ijzeren = passagier.");
    }

    // ================================================================== /ritly

    private void ritly(CommandSender s, String[] a) {
        boolean console = s instanceof ConsoleCommandSender || !(s instanceof Player);
        // winkel open mag ook vanaf de console (Citizens-NPC's)
        if (a.length >= 2 && a[0].equalsIgnoreCase("winkel") && a[1].equalsIgnoreCase("open")) {
            if (!console && !s.hasPermission("ritly.beheer")) {
                Tekst.stuur(s, "Winkels open je bij hun NPC.");
                return;
            }
            winkelOpen(s, a);
            return;
        }
        if (!console && !s.hasPermission("ritly.beheer")) {
            Tekst.stuur(s, "Dit is alleen voor beheerders. Typ &f/garage&7 voor je voertuigen.");
            return;
        }
        if (a.length == 0) {
            hulp(s);
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "herladen", "reload" -> {
                p.herlaad();
                Tekst.stuur(s, "Ritly is opnieuw geladen.");
            }
            case "winkel" -> winkel(s, a);
            case "npc" -> npc(s, a);
            case "showroom" -> showroom(s, a);
            case "bouw" -> bouwBeheer(s, a);
            case "modellen" -> {
                Tekst.stuur(s, "Modellen:");
                for (Model m : p.modellen().alle()) {
                    if (m.eigenaar != null) continue;
                    s.sendMessage(Tekst.kleur("&f" + m.id + " &7- " + m.naam + " &7(" + m.soort.naam.toLowerCase(Locale.ROOT) + ", "
                            + Math.round(m.maxKmu) + " km/u, " + m.stoelen() + " stoelen, " + m.delen.size() + " delen)"));
                }
                long eigen = p.modellen().alle().stream().filter(m -> m.eigenaar != null).count();
                if (eigen > 0) s.sendMessage(Tekst.kleur("&8+ " + eigen + " zelf gebouwde voertuigen van spelers"));
            }
            case "geef" -> {
                if (!console) {
                    Tekst.stuur(s, "Voertuigen geven kan alleen via de console of de website.");
                    return;
                }
                if (a.length < 3) {
                    Tekst.stuur(s, "ritly geef <speler> <model>");
                    return;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[1]);
                Model m = p.modellen().get(a[2]);
                if (op == null || m == null) {
                    Tekst.stuur(s, op == null ? "Speler niet gevonden." : "Model niet gevonden.");
                    return;
                }
                Voertuig v = new Voertuig(UUID.randomUUID(), op.getUniqueId(), m.id);
                v.kleur = m.kleur;
                v.brandstof = v.tankInhoud(m, p);
                p.garage().voegToe(v);
                p.garage().opslaan();
                p.winkels().log("console gaf " + m.id + " aan " + op.getName());
                Tekst.stuur(s, op.getName() + " heeft nu een " + m.naamZonderKleur() + ".");
                if (op.getPlayer() != null) Tekst.stuur(op.getPlayer(), "Je hebt een " + m.naam + "&7 gekregen. Kijk in je &f/garage&7.");
            }
            case "opruimen" -> {
                int n = 0;
                for (World w : Bukkit.getWorlds())
                    for (Entity e : w.getEntities()) {
                        var pdc = e.getPersistentDataContainer();
                        if (pdc.has(p.sleutelVoertuig, PersistentDataType.STRING) && p.rijden().vanEntity(e) == null) {
                            e.remove();
                            n++;
                        }
                    }
                Tekst.stuur(s, n + " losse voertuig-onderdelen opgeruimd.");
            }
            case "testrit" -> testrit(s, a);
            default -> hulp(s);
        }
    }

    private void hulp(CommandSender s) {
        Tekst.stuur(s, "&6Ritly beheer:");
        String[] r = {
                "&f/ritly winkel lijst | producten <winkel> | maak <id> <titel> | weg <id> | titel <id> <titel>",
                "&f/ritly winkel toevoegen <winkel> <product> <prijs> &7- bijv. voertuig:gezinsauto 25000",
                "&f/ritly winkel verwijder <winkel> <nr> | prijs <winkel> <nr> <prijs> | open <winkel> [speler]",
                "&f/ritly npc maak <winkel> [mob] [naam] | weg | lijst",
                "&f/ritly showroom zet <winkel> <nr of product> | weg | lijst",
                "&f/ritly bouw staf | opslaan <id> <soort> [schaal] [naam] | verwijder <id>",
                "&f/ritly modellen | opruimen | herladen",
                "&7Console: &fritly geef <speler> <model>&7, Citizens: &f/npc command add ritly winkel open <winkel> <p>"};
        for (String x : r) s.sendMessage(Tekst.kleur(x));
    }

    private void winkelOpen(CommandSender s, String[] a) {
        if (a.length < 3) {
            Tekst.stuur(s, "ritly winkel open <winkel> [speler]");
            return;
        }
        Winkels.Winkel w = p.winkels().get(a[2]);
        if (w == null) {
            Tekst.stuur(s, "Winkel " + a[2] + " bestaat niet.");
            return;
        }
        Player doel = a.length >= 4 ? Bukkit.getPlayerExact(a[3]) : (s instanceof Player pl ? pl : null);
        if (doel == null) {
            Tekst.stuur(s, "Speler niet gevonden.");
            return;
        }
        p.menus().openWinkel(doel, w);
    }

    private void winkel(CommandSender s, String[] a) {
        if (a.length < 2) {
            hulp(s);
            return;
        }
        String sub = a[1].toLowerCase(Locale.ROOT);
        if (sub.equals("lijst")) {
            Tekst.stuur(s, "Winkels:");
            for (Winkels.Winkel w : p.winkels().alle().values())
                s.sendMessage(Tekst.kleur("&f" + w.id + " &7- " + w.titel + " &7(" + w.producten.size() + " producten)"));
            return;
        }
        if (a.length < 3) {
            hulp(s);
            return;
        }
        String id = a[2].toLowerCase(Locale.ROOT);
        Winkels.Winkel w = p.winkels().get(id);
        switch (sub) {
            case "maak" -> {
                if (w != null) {
                    Tekst.stuur(s, "Die winkel bestaat al.");
                    return;
                }
                if (!id.matches("[a-z0-9_]{2,32}")) {
                    Tekst.stuur(s, "Gebruik voor de id kleine letters, cijfers en _.");
                    return;
                }
                String titel = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : "&8&l" + id;
                p.winkels().maak(id, titel);
                Tekst.stuur(s, "Winkel " + id + " gemaakt. Voeg producten toe met &f/ritly winkel toevoegen " + id + " <product> <prijs>");
            }
            case "weg" -> {
                if (w == null) {
                    Tekst.stuur(s, "Die winkel bestaat niet.");
                    return;
                }
                p.winkels().verwijder(id);
                p.showrooms().vernieuw();
                Tekst.stuur(s, "Winkel " + id + " is weg. (NPC's ervan doen niets meer; haal ze weg met /ritly npc weg.)");
            }
            case "titel" -> {
                if (w == null || a.length < 4) {
                    Tekst.stuur(s, "/ritly winkel titel <winkel> <titel>");
                    return;
                }
                w.titel = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
                p.winkels().opslaan();
                Tekst.stuur(s, "Titel aangepast.");
            }
            case "producten" -> {
                if (w == null) {
                    Tekst.stuur(s, "Die winkel bestaat niet.");
                    return;
                }
                Tekst.stuur(s, "Producten van " + id + ":");
                for (int i = 0; i < w.producten.size(); i++) {
                    Winkels.Aanbod an = w.producten.get(i);
                    s.sendMessage(Tekst.kleur("&f" + (i + 1) + ". &7" + an.product().sleutel() + " &8- " + p.winkels().naam(an.product()) + " &a" + Tekst.geld(an.prijs())));
                }
            }
            case "toevoegen" -> {
                if (w == null || a.length < 5) {
                    Tekst.stuur(s, "/ritly winkel toevoegen <winkel> <product> <prijs>  &8(product: voertuig:<model>, onderdeel:motor:2, lak:red, brandstof:20)");
                    return;
                }
                Product pr = Product.lees(a[3]);
                Double prijs = getal(a[4]);
                if (pr == null || prijs == null || prijs < 0) {
                    Tekst.stuur(s, "Product of prijs klopt niet.");
                    return;
                }
                if (pr.type == Product.Type.VOERTUIG && (p.modellen().get(pr.waarde) == null || p.modellen().get(pr.waarde).eigenaar != null)) {
                    Tekst.stuur(s, "Model " + pr.waarde + " bestaat niet. Zie /ritly modellen.");
                    return;
                }
                if (w.zoek(pr.sleutel()) != null) {
                    Tekst.stuur(s, "Dit product staat al in de winkel. Pas de prijs aan met /ritly winkel prijs.");
                    return;
                }
                w.producten.add(new Winkels.Aanbod(pr, prijs));
                p.winkels().opslaan();
                p.showrooms().vernieuw();
                Tekst.stuur(s, "Toegevoegd als nummer " + w.producten.size() + ": " + p.winkels().naam(pr) + " &7voor &a" + Tekst.geld(prijs));
            }
            case "verwijder", "prijs" -> {
                if (w == null || a.length < 4) {
                    Tekst.stuur(s, "/ritly winkel " + sub + " <winkel> <nr>" + (sub.equals("prijs") ? " <prijs>" : ""));
                    return;
                }
                Double nr = getal(a[3]);
                if (nr == null || nr < 1 || nr > w.producten.size()) {
                    Tekst.stuur(s, "Nummer klopt niet (zie /ritly winkel producten " + id + ").");
                    return;
                }
                int i = nr.intValue() - 1;
                if (sub.equals("verwijder")) {
                    Winkels.Aanbod weg = w.producten.remove(i);
                    Tekst.stuur(s, "Verwijderd: " + p.winkels().naam(weg.product()));
                } else {
                    Double prijs = a.length > 4 ? getal(a[4]) : null;
                    if (prijs == null || prijs < 0) {
                        Tekst.stuur(s, "Geef een prijs.");
                        return;
                    }
                    Winkels.Aanbod oud = w.producten.get(i);
                    w.producten.set(i, new Winkels.Aanbod(oud.product(), prijs));
                    Tekst.stuur(s, p.winkels().naam(oud.product()) + " &7kost nu &a" + Tekst.geld(prijs));
                }
                p.winkels().opslaan();
                p.showrooms().vernieuw();
            }
            default -> hulp(s);
        }
    }

    private void npc(CommandSender s, String[] a) {
        if (!(s instanceof Player pl)) {
            Tekst.stuur(s, "Alleen in het spel.");
            return;
        }
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "maak" -> {
                if (a.length < 3 || p.winkels().get(a[2]) == null) {
                    Tekst.stuur(pl, "/ritly npc maak <winkel> [mob] [naam]  &8(winkels: " + String.join(", ", p.winkels().alle().keySet()) + ")");
                    return;
                }
                Winkels.Winkel w = p.winkels().get(a[2]);
                String type = a.length > 3 ? a[3] : null;
                String naam = a.length > 4 ? String.join(" ", Arrays.copyOfRange(a, 4, a.length)) : w.titel;
                String fout = p.npcs().maak(pl, w.id, type, naam);
                Tekst.stuur(pl, fout == null ? "NPC voor " + w.id + " staat er. Spelers klikken erop om te kopen." : fout);
            }
            case "weg" -> Tekst.stuur(pl, p.npcs().verwijder(pl));
            case "lijst" -> {
                Tekst.stuur(pl, "Winkel-NPC's (geladen):");
                for (String r : p.npcs().lijst()) pl.sendMessage(Tekst.kleur("&7" + r));
            }
            default -> Tekst.stuur(pl, "/ritly npc maak <winkel> [mob] [naam] | weg | lijst");
        }
    }

    private void showroom(CommandSender s, String[] a) {
        if (!(s instanceof Player pl)) {
            Tekst.stuur(s, "Alleen in het spel.");
            return;
        }
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "zet" -> {
                if (a.length < 4 || p.winkels().get(a[2]) == null) {
                    Tekst.stuur(pl, "/ritly showroom zet <winkel> <nr of product>  &8(nummers: /ritly winkel producten <winkel>)");
                    return;
                }
                Winkels.Winkel w = p.winkels().get(a[2]);
                String sleutel;
                Double nr = getal(a[3]);
                if (nr != null) {
                    if (nr < 1 || nr > w.producten.size()) {
                        Tekst.stuur(pl, "Nummer klopt niet.");
                        return;
                    }
                    sleutel = w.producten.get(nr.intValue() - 1).product().sleutel();
                } else {
                    Product pr = Product.lees(a[3]);
                    if (pr == null || w.zoek(pr.sleutel()) == null) {
                        Tekst.stuur(pl, "Dat product staat niet in deze winkel.");
                        return;
                    }
                    sleutel = pr.sleutel();
                }
                Tekst.stuur(pl, p.showrooms().plaats(pl, w.id, sleutel));
            }
            case "weg" -> Tekst.stuur(pl, p.showrooms().verwijderBij(pl));
            case "lijst" -> {
                Tekst.stuur(pl, "Displays:");
                for (String r : p.showrooms().lijst()) pl.sendMessage(Tekst.kleur("&7" + r));
            }
            default -> Tekst.stuur(pl, "/ritly showroom zet <winkel> <nr of product> | weg | lijst");
        }
    }

    private void bouwBeheer(CommandSender s, String[] a) {
        if (!(s instanceof Player pl)) {
            if (a.length >= 3 && a[1].equalsIgnoreCase("verwijder")) verwijderModel(s, a[2]);
            else Tekst.stuur(s, "Alleen in het spel.");
            return;
        }
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "staf" -> geefStaf(pl, true);
            case "opslaan" -> {
                if (a.length < 4) {
                    Tekst.stuur(pl, "/ritly bouw opslaan <id> <fiets|auto|boot|vliegtuig> [schaal] [naam]");
                    return;
                }
                Soort soort = Soort.van(a[3]);
                if (soort == null) {
                    Tekst.stuur(pl, "Soort moet fiets, auto, boot of vliegtuig zijn.");
                    return;
                }
                float schaal = 1f;
                int naamVan = 4;
                if (a.length > 4) {
                    Double sc = getal(a[4]);
                    if (sc != null) {
                        schaal = (float) Math.max(0.1, Math.min(2.0, sc));
                        naamVan = 5;
                    }
                }
                String naam = a.length > naamVan ? String.join(" ", Arrays.copyOfRange(a, naamVan, a.length)) : null;
                String fout = p.bouwer().opslaanBeheer(pl, a[2], soort, schaal, naam);
                if (fout != null) Tekst.stuur(pl, fout);
                else {
                    Model m = p.modellen().get(a[2]);
                    Tekst.stuur(pl, "Model &f" + m.id + "&7 opgeslagen (" + m.delen.size() + " delen, " + m.stoelen() + " stoelen). "
                            + "Verkopen: &f/ritly winkel toevoegen <winkel> voertuig:" + m.id + " <prijs>&7. Snelheid enz. aanpassen in plugins/Ritly/gebouwd/" + m.id + ".yml");
                }
            }
            case "verwijder" -> {
                if (a.length < 3) Tekst.stuur(pl, "/ritly bouw verwijder <id>");
                else verwijderModel(pl, a[2]);
            }
            default -> Tekst.stuur(pl, "/ritly bouw staf | opslaan <id> <soort> [schaal] [naam] | verwijder <id>");
        }
    }

    private void verwijderModel(CommandSender s, String id) {
        if (!p.modellen().isGebouwd(id.toLowerCase(Locale.ROOT))) {
            Tekst.stuur(s, "Alleen gebouwde modellen kunnen zo weg. Modellen uit modellen.yml haal je daar weg.");
            return;
        }
        long n = p.garage().aantalMetModel(id.toLowerCase(Locale.ROOT));
        if (n > 0) {
            Tekst.stuur(s, n + " speler(s) hebben dit voertuig nog. Het model blijft bestaan.");
            return;
        }
        p.modellen().verwijderGebouwd(id.toLowerCase(Locale.ROOT));
        Tekst.stuur(s, "Model " + id + " is verwijderd.");
    }

    /** Console-test: zet een voertuig neer en laat het een tijd vooruit rijden. */
    private void testrit(CommandSender s, String[] a) {
        if (a.length < 8) {
            Tekst.stuur(s, "ritly testrit <model> <wereld> <x> <y> <z> <yaw> <ticks> [vooruit|links|rechts|gas]");
            return;
        }
        Model m = p.modellen().get(a[1]);
        World w = Bukkit.getWorld(a[2]);
        if (m == null || w == null) {
            Tekst.stuur(s, "Model of wereld bestaat niet.");
            return;
        }
        double x = Double.parseDouble(a[3]), y = Double.parseDouble(a[4]), z = Double.parseDouble(a[5]);
        float yaw = Float.parseFloat(a[6]);
        int ticks = Integer.parseInt(a[7]);
        String richting = a.length > 8 ? a[8] : "vooruit";
        Voertuig v = new Voertuig(UUID.randomUUID(), UUID.randomUUID(), m.id);
        v.kleur = m.kleur;
        v.brandstof = v.tankInhoud(m, p);
        ActiefVoertuig av = new ActiefVoertuig(p, v, m, new Location(w, x, y, z, yaw, 0));
        av.autopiloot = invoer(richting.contains("vooruit") || richting.contains("gas"), richting.contains("links"), richting.contains("rechts"));
        av.spawn();
        Tekst.stuur(s, "Testrit " + m.id + " gestart (" + m.delen.size() + " delen).");
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(p, task -> {
            t[0]++;
            av.tick();
            if (t[0] % 20 == 0 || t[0] >= ticks)
                s.sendMessage("[testrit] t=" + t[0] + " pos=" + Tekst.getal(av.x, 2) + " " + Tekst.getal(av.y, 2) + " " + Tekst.getal(av.z, 2)
                        + " yaw=" + Tekst.getal(av.yaw, 1) + " pitch=" + Tekst.getal(av.pitch, 1) + " v=" + Tekst.getal(av.snelheid * 72, 1) + "km/u"
                        + " grond=" + av.opGrond + " water=" + av.opWater + " geldig=" + av.geldig()
                        + " delen=" + av.delen.size() + " brandstof=" + Tekst.getal(av.v.brandstof, 2));
            if (t[0] >= ticks) {
                task.cancel();
                av.weghalen();
            }
        }, 1L, 1L);
    }

    private static Input invoer(boolean vooruit, boolean links, boolean rechts) {
        return new Input() {
            public boolean isForward() { return vooruit; }
            public boolean isBackward() { return false; }
            public boolean isLeft() { return links; }
            public boolean isRight() { return rechts; }
            public boolean isJump() { return false; }
            public boolean isSneak() { return false; }
            public boolean isSprint() { return false; }
        };
    }

    private static Double getal(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ================================================================== tab

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> opties = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("garage")) {
            if (a.length == 1) opties.addAll(List.of("opbergen", "naam", "bouw", "help"));
            else if (a.length == 2 && a[0].equalsIgnoreCase("bouw")) opties.addAll(List.of("staf", "klaar", "bevestig"));
            else if (a.length == 3 && a[0].equalsIgnoreCase("bouw") && a[1].equalsIgnoreCase("klaar")) soorten(opties);
            else if (a.length == 4 && a[0].equalsIgnoreCase("bouw") && a[1].equalsIgnoreCase("klaar")) opties.addAll(List.of("1", "0.75", "0.5"));
            return filter(opties, a[a.length - 1]);
        }
        if (!s.hasPermission("ritly.beheer")) return opties;
        if (a.length == 1) opties.addAll(List.of("winkel", "npc", "showroom", "bouw", "modellen", "opruimen", "herladen", "geef"));
        else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "winkel" -> opties.addAll(List.of("lijst", "producten", "maak", "weg", "titel", "toevoegen", "verwijder", "prijs", "open"));
                case "npc" -> opties.addAll(List.of("maak", "weg", "lijst"));
                case "showroom" -> opties.addAll(List.of("zet", "weg", "lijst"));
                case "bouw" -> opties.addAll(List.of("staf", "opslaan", "verwijder"));
                case "geef" -> Bukkit.getOnlinePlayers().forEach(pl -> opties.add(pl.getName()));
                default -> {}
            }
        } else if (a.length == 3) {
            String h = a[0].toLowerCase(Locale.ROOT);
            if ((h.equals("winkel") && !a[1].equalsIgnoreCase("maak") && !a[1].equalsIgnoreCase("lijst"))
                    || (h.equals("npc") && a[1].equalsIgnoreCase("maak")) || (h.equals("showroom") && a[1].equalsIgnoreCase("zet")))
                opties.addAll(p.winkels().alle().keySet());
            else if (h.equals("geef")) opties.addAll(p.modellen().openbareIds());
            else if (h.equals("bouw") && a[1].equalsIgnoreCase("verwijder")) {
                for (Model m : p.modellen().alle()) if (m.eigenaar == null && p.modellen().isGebouwd(m.id)) opties.add(m.id);
            }
        } else if (a.length == 4) {
            String h = a[0].toLowerCase(Locale.ROOT);
            if (h.equals("winkel") && a[1].equalsIgnoreCase("toevoegen")) {
                for (String id : p.modellen().openbareIds()) opties.add("voertuig:" + id);
                for (String o : Product.ONDERDELEN) for (int i = 1; i <= 3; i++) opties.add("onderdeel:" + o + ":" + i);
                for (org.bukkit.DyeColor c : org.bukkit.DyeColor.values()) opties.add("lak:" + c.name().toLowerCase(Locale.ROOT));
                opties.addAll(List.of("brandstof:20", "brandstof:50"));
            } else if (h.equals("showroom") && a[1].equalsIgnoreCase("zet")) {
                Winkels.Winkel w = p.winkels().get(a[2]);
                if (w != null) for (int i = 0; i < w.producten.size(); i++) opties.add(String.valueOf(i + 1));
            } else if (h.equals("npc") && a[1].equalsIgnoreCase("maak")) {
                opties.addAll(List.of("villager", "wandering_trader", "piglin", "zombie_villager", "iron_golem", "allay", "fox", "cat"));
            } else if (h.equals("bouw") && a[1].equalsIgnoreCase("opslaan")) soorten(opties);
            else if (h.equals("winkel") && a[1].equalsIgnoreCase("open")) Bukkit.getOnlinePlayers().forEach(pl -> opties.add(pl.getName()));
        }
        return filter(opties, a[a.length - 1]);
    }

    private static void soorten(List<String> l) {
        for (Soort s : Soort.values()) l.add(s.sleutel());
    }

    private static List<String> filter(List<String> l, String begin) {
        String b = begin.toLowerCase(Locale.ROOT);
        List<String> r = new ArrayList<>();
        for (String s : l) if (s.toLowerCase(Locale.ROOT).startsWith(b)) r.add(s);
        return r;
    }
}
