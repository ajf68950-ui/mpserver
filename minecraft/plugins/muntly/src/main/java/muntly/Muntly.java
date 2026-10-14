package muntly;

import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Muntly extends JavaPlugin {

    private Saldi saldi;
    private Winkel winkel;
    private Verdienen verdienen;
    private Menus menus;
    private WebApi web;
    private Economy economy;
    private boolean economyGezocht;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getString("web.token", "").isBlank()) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            getConfig().set("web.token", HexFormat.of().formatHex(b));
            saveConfig();
        }
        saldi = new Saldi(this);
        winkel = new Winkel(this);
        winkel.load();
        verdienen = new Verdienen(this);
        menus = new Menus(this);
        getServer().getPluginManager().registerEvents(verdienen, this);
        getServer().getPluginManager().registerEvents(menus, this);
        try {
            web = new WebApi(this);
            web.start();
        } catch (Exception e) {
            getLogger().severe("Kon de verbinding voor de website niet starten op poort " + getConfig().getInt("web.poort") + ": " + e.getMessage());
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new MuntlyExpansion(this).register();
                getLogger().info("PlaceholderAPI gevonden: %muntly_saldo% en andere placeholders zijn beschikbaar.");
            } catch (Throwable t) {
                getLogger().warning("Kon de placeholders niet aanmelden bij PlaceholderAPI: " + t.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        if (web != null) web.stop();
        if (saldi != null) saldi.saveNow();
    }

    Saldi saldi() { return saldi; }
    Winkel winkel() { return winkel; }
    Verdienen verdienen() { return verdienen; }

    Economy economy() {
        if (economy == null && !economyGezocht) {
            economyGezocht = true;
            if (getServer().getPluginManager().getPlugin("Vault") != null) {
                RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
                if (rsp != null) economy = rsp.getProvider();
            }
        }
        return economy;
    }

    // ------------------------------------------------------------ teksten
    String muntNaam() { return getConfig().getString("munt.naam", "Munten"); }
    String kleur() { return getConfig().getString("munt.kleur", "&e"); }

    /** "1 Munt" of "1.250 Munten". */
    String munten(long n) {
        return fmt(n) + " " + (n == 1 ? getConfig().getString("munt.enkelvoud", "Munt") : muntNaam());
    }

    static String fmt(long n) { return String.format(Locale.ROOT, "%,d", n).replace(',', '.'); }

    Component bericht(String s) { return Winkel.tekst("&6[" + muntNaam() + "] &7" + s); }
    Component fout(String s) { return Winkel.tekst("&6[" + muntNaam() + "] &c" + s); }

    // ---------------------------------------------------------- kopen in het spel
    /** Koopt een item in het spel. null = gelukt, anders de foutmelding. */
    String koop(Player p, Winkel.Artikel a) {
        if (!a.teKoop()) return "Dit item is niet te koop.";
        if (p.getInventory().firstEmpty() == -1) return "Maak eerst een vak vrij in je inventaris.";
        Saldi.Speler sp = saldi.get(p.getUniqueId(), p.getName());
        if (!saldi.take(sp, a.prijs, "winkel: " + a.id)) return "Je hebt niet genoeg " + muntNaam() + ". Je hebt er " + fmt(sp.saldo) + ", dit kost " + fmt(a.prijs) + ".";
        geef(p, a.item.clone());
        getLogger().info(p.getName() + " kocht " + a.id + " voor " + a.prijs + " " + muntNaam());
        return null;
    }

    String kanLootboxOpenen(Player p, Winkel.Lootbox lb) {
        if (p.getInventory().firstEmpty() == -1) return "Maak eerst een vak vrij in je inventaris.";
        Saldi.Speler sp = saldi.get(p.getUniqueId(), p.getName());
        if (sp.saldo < lb.prijs) return "Je hebt niet genoeg " + muntNaam() + ". Je hebt er " + fmt(sp.saldo) + ", deze kist kost " + fmt(lb.prijs) + ".";
        return null;
    }

    boolean betaalLootbox(Player p, Winkel.Lootbox lb) {
        return saldi.take(saldi.get(p.getUniqueId(), p.getName()), lb.prijs, "lootbox: " + lb.id);
    }

    /** Geeft een lootbox-beloning aan een speler in het spel. */
    void geefBeloning(Player p, Winkel.Lootbox lb, Winkel.Beloning b) {
        Saldi.Speler sp = saldi.get(p.getUniqueId(), p.getName());
        String wat = winkel.omschrijf(b);
        if (b.item != null) geef(p, b.item.clone());
        else if (b.munten > 0) saldi.add(sp, b.munten, "lootbox-prijs: " + lb.id);
        else if (b.geld > 0) {
            Economy eco = economy();
            if (eco != null) eco.depositPlayer(p, b.geld);
            else { saldi.add(sp, lb.prijs, "lootbox terug (geen economy): " + lb.id); wat = munten(lb.prijs) + " terug"; }
        }
        getLogger().info(p.getName() + " opende " + lb.id + " en kreeg " + wat);
        if (p.isOnline()) {
            // gewone items met de naam in de taal van de speler
            Component prijs = Winkel.tekst(b.zeldzaamheid.kleur + "&l" + wat);
            if (b.item != null && !b.item.getItemMeta().hasDisplayName()) {
                prijs = Winkel.tekst(b.zeldzaamheid.kleur + "&l" + (b.item.getAmount() > 1 ? b.item.getAmount() + "x " : ""))
                        .append(Component.translatable(b.item.translationKey()));
            }
            p.sendMessage(bericht("Uit de " + Winkel.PLAIN.serialize(Winkel.LEGACY.deserialize(lb.naam)) + ": ").append(prijs)
                    .append(Winkel.tekst(" &7(" + b.zeldzaamheid.label.toLowerCase(Locale.ROOT) + ")")));
            p.playSound(p.getLocation(), b.zeldzaamheid == Winkel.Zeldzaamheid.LEGENDARISCH ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        }
        meldLegendarisch(p.getName(), lb, b, wat);
    }

    void meldLegendarisch(String naam, Winkel.Lootbox lb, Winkel.Beloning b, String wat) {
        if (b.zeldzaamheid != Winkel.Zeldzaamheid.LEGENDARISCH || !getConfig().getBoolean("legendarisch-melden", true)) return;
        Bukkit.broadcast(bericht("&e" + naam + " &7haalde &6&l" + wat + " &7uit de " + Winkel.PLAIN.serialize(Winkel.LEGACY.deserialize(lb.naam)) + "!"));
    }

    /** Geeft een item; past het niet, dan valt het voor de voeten. */
    private void geef(Player p, ItemStack it) {
        Map<Integer, ItemStack> over = p.getInventory().addItem(it);
        for (ItemStack rest : over.values()) p.getWorld().dropItem(p.getLocation(), rest);
    }

    // ------------------------------------------------------------ commando's
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = sender.hasPermission("muntly.admin");
        if (!sender.hasPermission("muntly.use") && !admin) { sender.sendMessage(fout("Dat mag je niet.")); return true; }

        switch (sub) {
            case "geef", "pak", "zet" -> {
                if (!admin) { sender.sendMessage(fout("Dat mag alleen een beheerder.")); return true; }
                if (args.length < 3) { sender.sendMessage(fout("Gebruik: /munten " + sub + " <speler> <aantal>")); return true; }
                long n;
                try { n = Long.parseLong(args[2].replace(".", "")); } catch (NumberFormatException e) { sender.sendMessage(fout("Dat is geen geldig aantal.")); return true; }
                if (n < 0) { sender.sendMessage(fout("Gebruik een getal van 0 of meer.")); return true; }
                Saldi.Speler sp = zoek(args[1]);
                if (sp == null) { sender.sendMessage(fout("Speler " + args[1] + " is niet bekend op deze server.")); return true; }
                String door = "admin " + sender.getName() + ": " + sub;
                switch (sub) {
                    case "geef" -> saldi.add(sp, n, door);
                    case "pak" -> saldi.add(sp, -n, door);
                    default -> saldi.set(sp, n, door);
                }
                sender.sendMessage(bericht(sp.naam + " heeft nu " + kleur() + munten(sp.saldo) + "&7."));
                Player t = Bukkit.getPlayer(sp.uuid);
                if (t != null && t != sender && !sub.equals("zet") && n > 0) {
                    t.sendMessage(bericht(sub.equals("geef") ? "&aJe kreeg " + munten(n) + "&a van een beheerder." : "&cEr is " + munten(n) + "&c afgehaald door een beheerder."));
                }
                return true;
            }
            case "herladen", "reload" -> {
                if (!admin) { sender.sendMessage(fout("Dat mag alleen een beheerder.")); return true; }
                reloadConfig();
                winkel.load();
                sender.sendMessage(bericht("Config en winkel herladen: " + winkel.artikelen().size() + " items, " + winkel.lootboxen().size() + " lootboxen."));
                return true;
            }
            case "top" -> {
                if (sender instanceof Player p) { menus.toplijst(p); return true; }
                List<Saldi.Speler> top = saldi.top(10);
                sender.sendMessage(bericht("&6Toplijst " + muntNaam() + ":"));
                if (top.isEmpty()) sender.sendMessage(Winkel.tekst("&7  Nog niemand heeft " + muntNaam() + "."));
                for (int i = 0; i < top.size(); i++) sender.sendMessage(Winkel.tekst("&e  " + (i + 1) + ". &f" + top.get(i).naam + " &7- " + kleur() + munten(top.get(i).saldo)));
                return true;
            }
            case "saldo" -> {
                if (args.length >= 2) {
                    Saldi.Speler sp = zoek(args[1]);
                    if (sp == null) { sender.sendMessage(fout("Speler " + args[1] + " is niet bekend op deze server.")); return true; }
                    sender.sendMessage(bericht(sp.naam + " heeft " + kleur() + munten(sp.saldo) + "&7."));
                } else if (sender instanceof Player p) {
                    sender.sendMessage(bericht("Je hebt " + kleur() + munten(saldi.get(p.getUniqueId(), p.getName()).saldo) + "&7."));
                } else sender.sendMessage(fout("Gebruik: /munten saldo <speler>"));
                return true;
            }
            case "help", "?" -> { help(sender, admin); return true; }
            default -> { }
        }

        if (!(sender instanceof Player p)) { help(sender, admin); return true; }
        switch (sub) {
            case "" -> menus.hoofdmenu(p);
            case "winkel", "shop" -> menus.winkel(p);
            case "lootbox", "lootboxen", "kist" -> menus.lootboxen(p);
            case "dagelijks", "dag" -> {
                Saldi.Speler sp = saldi.get(p.getUniqueId(), p.getName());
                p.sendMessage(bericht("Dagen op rij: &f" + sp.reeks + "&7. Morgen inloggen geeft &e+" + munten(verdienen.beloningVoorDag(sp.reeks + 1))
                        + "&7. Volgende speeltijdbeloning over &f" + verdienen.minutenTotVolgende(sp) + " min&7."));
            }
            default -> help(sender, admin);
        }
        return true;
    }

    private void help(CommandSender s, boolean admin) {
        s.sendMessage(bericht("&f/munten &7menu, &f/munten saldo&7, &f/munten winkel&7, &f/munten lootbox&7, &f/munten top&7, &f/munten dagelijks"));
        if (admin) s.sendMessage(bericht("Beheer: &f/munten geef|pak|zet <speler> <aantal>&7, &f/munten herladen"));
    }

    /** Zoekt een speler: online, dan bekend bij Muntly, dan bekend bij de server. */
    Saldi.Speler zoek(String naam) {
        Player online = Bukkit.getPlayerExact(naam);
        if (online != null) return saldi.get(online.getUniqueId(), online.getName());
        Saldi.Speler sp = saldi.findByName(naam);
        if (sp != null) return sp;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(naam);
        if (op != null && op.getName() != null) return saldi.get(op.getUniqueId(), op.getName());
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("muntly.admin");
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("saldo", "winkel", "lootbox", "top", "dagelijks"));
            if (admin) opts.addAll(List.of("geef", "pak", "zet", "herladen"));
            for (String o : opts) if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(o);
        } else if (args.length == 2 && (admin && List.of("geef", "pak", "zet").contains(args[0].toLowerCase(Locale.ROOT)) || args[0].equalsIgnoreCase("saldo"))) {
            for (Player pl : Bukkit.getOnlinePlayers()) if (pl.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(pl.getName());
        }
        return out;
    }
}
