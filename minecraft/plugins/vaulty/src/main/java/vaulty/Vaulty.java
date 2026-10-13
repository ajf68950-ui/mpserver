package vaulty;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class Vaulty extends JavaPlugin {

    private KluisManager kluizen;
    private LinkManager links;
    private MarketManager market;
    private WebApi web;
    private Economy economy;

    @Override
    public void onEnable() {
        migrateOldPlugin();
        saveDefaultConfig();
        if (getConfig().getString("web.token", "").isBlank()) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            getConfig().set("web.token", HexFormat.of().formatHex(b));
            saveConfig();
        }
        setupEconomy();
        kluizen = new KluisManager(this);
        links = new LinkManager(this);
        market = new MarketManager(this);
        getServer().getPluginManager().registerEvents(new KluisListener(this), this);
        try {
            web = new WebApi(this);
            web.start();
        } catch (Exception e) {
            getLogger().severe("Kon de verbinding voor de website niet starten op poort " + getConfig().getInt("web.poort") + ": " + e.getMessage());
        }
        // De oude versie van deze plugin heette MeediaKluis. Staat die er nog, zet hem dan uit.
        Bukkit.getScheduler().runTask(this, () -> {
            org.bukkit.plugin.Plugin old = getServer().getPluginManager().getPlugin("MeediaKluis");
            if (old != null) {
                if (old.isEnabled()) getServer().getPluginManager().disablePlugin(old);
                getLogger().warning("De oude plugin MeediaKluis staat nog in de plugins-map en is uitgezet. Verwijder MeediaKluis-1.1.0.jar en de map MeediaKluis.");
            }
        });
                // elke 2 minuten openstaande kluizen opslaan (voor als de server crasht)
        Bukkit.getScheduler().runTaskTimer(this, () -> kluizen.saveOpen(), 20L * 120, 20L * 120);
    }

    /** Neemt de kluizen en de markt over uit plugins/MeediaKluis (de oude naam van deze plugin). */
    private void migrateOldPlugin() {
        java.io.File oldDir = new java.io.File(getDataFolder().getParentFile(), "MeediaKluis");
        java.io.File newKluizen = new java.io.File(getDataFolder(), "kluizen");
        if (!oldDir.isDirectory() || (newKluizen.isDirectory() && newKluizen.list() != null && newKluizen.list().length > 0)) return;
        try {
            java.nio.file.Path from = oldDir.toPath(), to = getDataFolder().toPath();
            try (var walk = java.nio.file.Files.walk(from)) {
                for (java.nio.file.Path src : (Iterable<java.nio.file.Path>) walk::iterator) {
                    java.nio.file.Path dst = to.resolve(from.relativize(src).toString());
                    if (java.nio.file.Files.isDirectory(src)) java.nio.file.Files.createDirectories(dst);
                    else if (!java.nio.file.Files.exists(dst)) java.nio.file.Files.copy(src, dst);
                }
            }
            getLogger().info("Kluizen, markt en instellingen overgenomen uit plugins/MeediaKluis.");
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Kon de gegevens van MeediaKluis niet overnemen", e);
        }
    }

    @Override
    public void onDisable() {
        if (web != null) web.stop();
        if (market != null) market.save();
        if (kluizen != null) kluizen.shutdown();
    }

    private void setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
        if (economy == null) getLogger().warning("Geen economy gevonden via Vault. Kopen en verkopen werkt pas als er een economy-plugin is.");
    }

    public Economy economy() {
        if (economy == null) setupEconomy();
        return economy;
    }

    public KluisManager kluizen() { return kluizen; }
    public LinkManager links() { return links; }
    MarketManager market() { return market; }

    public static Component msg(String text) { return Component.text(text, NamedTextColor.GOLD); }
    public static Component err(String text) { return Component.text(text, NamedTextColor.RED); }

    // ------------------------------------------------------------ commando's
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("koppel")) return koppel(sender, args);
        if (name.equals("kluis")) return kluis(sender, args);
        return false;
    }

    private boolean koppel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen voor spelers."); return true; }
        if (!p.hasPermission("vaulty.koppel")) { p.sendMessage(err("Dat mag je niet.")); return true; }
        if (args.length != 1) { p.sendMessage(err("Gebruik: /koppel <code>. De code krijg je op de website.")); return true; }
        LinkManager.Result r = links.confirm(args[0], p);
        switch (r) {
            case OK -> p.sendMessage(msg("Gekoppeld! Ga terug naar de website, je bent nu ingelogd."));
            case WRONG_NAME -> p.sendMessage(err("Deze code is aangevraagd voor een andere Minecraft-naam."));
            case EXPIRED -> p.sendMessage(err("Deze code is verlopen. Vraag op de website een nieuwe aan."));
            default -> p.sendMessage(err("Deze code bestaat niet. Kijk of je hem goed hebt overgetypt."));
        }
        return true;
    }

    private boolean kluis(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Alleen voor spelers."); return true; }
        if (!p.hasPermission("vaulty.use")) { p.sendMessage(err("Dat mag je niet.")); return true; }

        // /kluis <speler> [nummer] voor beheerders
        // /kluis naam <nummer> [naam] en /kluis sorteer
        if (args.length >= 1 && args[0].equalsIgnoreCase("naam")) {
            Kluis k = kluizen.get(p.getUniqueId(), p.getName());
            if (args.length < 2 || !isNumber(args[1])) { p.sendMessage(err("Gebruik: /kluis naam <nummer> <naam>, bijv. /kluis naam 2 Ertsen. Zonder naam gaat hij terug naar Kist 2.")); return true; }
            int n = Integer.parseInt(args[1]);
            if (n < 1 || n > k.chests()) { p.sendMessage(err("Je hebt " + k.chests() + (k.chests() == 1 ? " kist." : " kisten."))); return true; }
            String newName = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : null;
            String set = k.chestName(n - 1, newName);
            kluizen.save(k);
            p.sendMessage(msg(set == null ? "Kist " + n + " heet weer gewoon Kist " + n + "." : "Kist " + n + " heet nu " + set + "."));
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("sorteer") || args[0].equalsIgnoreCase("sorteren"))) {
            Kluis k = kluizen.get(p.getUniqueId(), p.getName());
            int[] r = k.sort();
            kluizen.save(k);
            p.sendMessage(msg("Je kluis is gesorteerd (" + r[0] + " vakken werden er " + r[1] + ")."));
            return true;
        }

        if (args.length >= 1 && !args[0].equalsIgnoreCase("kopen") && !isNumber(args[0])) {
            if (!p.hasPermission("vaulty.admin")) { p.sendMessage(err("Gebruik: /kluis [nummer] of /kluis kopen")); return true; }
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null) { p.sendMessage(err("Speler " + args[0] + " is niet bekend op deze server.")); return true; }
            Kluis k = kluizen.get(target.getUniqueId(), target.getName());
            int n = args.length >= 2 && isNumber(args[1]) ? Integer.parseInt(args[1]) : 1;
            if (n < 1 || n > k.chests()) { p.sendMessage(err(target.getName() + " heeft " + k.chests() + " kist(en).")); return true; }
            p.openInventory(k.inventory(n - 1));
            return true;
        }

        if (isBlockedWorld(p)) { p.sendMessage(err("In deze wereld kun je je kluis niet openen.")); return true; }
        Kluis k = kluizen.get(p.getUniqueId(), p.getName());

        if (args.length >= 1 && args[0].equalsIgnoreCase("kopen")) {
            String res = buyChest(k, p);
            p.sendMessage(res == null ? msg("Je hebt nu " + k.chests() + " kisten in je kluis.") : err(res));
            return true;
        }
        // /kluis 2 Ertsen = kist 2 een naam geven (kort voor /kluis naam 2 Ertsen)
        if (args.length >= 2 && isNumber(args[0])) {
            int n = Integer.parseInt(args[0]);
            if (n < 1 || n > k.chests()) { p.sendMessage(err("Je hebt " + k.chests() + (k.chests() == 1 ? " kist." : " kisten."))); return true; }
            String set = k.chestName(n - 1, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            kluizen.save(k);
            p.sendMessage(msg(set == null ? "Kist " + n + " heet weer gewoon Kist " + n + "." : "Kist " + n + " heet nu " + set + "."));
            return true;
        }
        if (args.length >= 1) {
            int n = Integer.parseInt(args[0]);
            if (n < 1 || n > k.chests()) { p.sendMessage(err("Je hebt " + k.chests() + (k.chests() == 1 ? " kist." : " kisten."))); return true; }
            p.openInventory(k.inventory(n - 1));
            return true;
        }
        if (k.chests() == 1 && maxChests() <= 1) p.openInventory(k.inventory(0));
        else p.openInventory(KluisMenu.create(this, k));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("kluis") && args.length == 1 && sender instanceof Player p) {
            Kluis k = kluizen.get(p.getUniqueId(), p.getName());
            for (int i = 1; i <= k.chests(); i++) out.add(String.valueOf(i));
            out.add("kopen");
            out.add("naam");
            out.add("sorteer");
            if (p.hasPermission("vaulty.admin")) for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getName());
            out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    static boolean isNumber(String s) { return s.matches("\\d{1,3}"); }

    public boolean isBlockedWorld(Player p) {
        for (String w : getConfig().getStringList("kluis.geblokkeerde-werelden")) {
            if (p.getWorld().getName().equalsIgnoreCase(w)) return true;
        }
        return false;
    }

    public int startChests() { return Math.max(1, getConfig().getInt("kluis.start-kisten", 1)); }
    public int maxChests() { return Math.min(KluisMenu.MAX_IN_MENU, Math.max(startChests(), getConfig().getInt("kluis.max-kisten", 10))); }

    /** Prijs van de volgende kist, of -1 als het maximum bereikt is. */
    public double nextChestPrice(Kluis k) {
        if (k.chests() >= maxChests()) return -1;
        List<Double> prices = getConfig().getDoubleList("kluis.prijs-extra-kist");
        if (prices.isEmpty()) return 0;
        int idx = k.chests() - startChests();
        if (idx < 0) idx = 0;
        return prices.get(Math.min(idx, prices.size() - 1));
    }

    /** Koopt een extra kist. Geeft null terug als het gelukt is, anders een foutmelding. Alleen op de main thread. */
    public String buyChest(Kluis k, OfflinePlayer who) {
        double price = nextChestPrice(k);
        if (price < 0) return "Je hebt al het maximale aantal kisten (" + maxChests() + ").";
        Economy eco = economy();
        if (price > 0) {
            if (eco == null) return "De economy is niet beschikbaar.";
            if (!eco.has(who, price)) return "Je hebt niet genoeg geld. Een extra kist kost $" + fmt(price) + ".";
            if (!eco.withdrawPlayer(who, price).transactionSuccess()) return "Betalen is niet gelukt.";
        }
        k.addChest();
        kluizen.save(k);
        return null;
    }

    static String fmt(double d) {
        return d == Math.floor(d) ? String.format(Locale.ROOT, "%,d", (long) d).replace(',', '.') : String.format(Locale.ROOT, "%.2f", d);
    }

    public UUID uuidOf(String s) {
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
