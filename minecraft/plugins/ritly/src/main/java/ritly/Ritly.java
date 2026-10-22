package ritly;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ritly: voertuigen (fietsen, auto's, boten, vliegtuigen) kopen, samenstellen, bouwen en besturen. */
public final class Ritly extends JavaPlugin {

    NamespacedKey sleutelVoertuig, sleutelItem, sleutelShowroom, sleutelNpc;

    private Modellen modellen;
    private Garage garage;
    private Winkels winkels;
    private Rijden rijden;
    private Showrooms showrooms;
    private Npcs npcs;
    private Onderdelen onderdelen;
    private Menus menus;
    private Bouwer bouwer;
    private Economy economie;
    private final Map<String, List<Double>> upgrades = new HashMap<>();

    @Override
    public void onEnable() {
        sleutelVoertuig = new NamespacedKey(this, "voertuig");
        sleutelItem = new NamespacedKey(this, "item");
        sleutelShowroom = new NamespacedKey(this, "showroom");
        sleutelNpc = new NamespacedKey(this, "winkel");
        saveDefaultConfig();
        leesConfig();
        modellen = new Modellen(this);
        modellen.laad();
        garage = new Garage(this);
        garage.laad();
        winkels = new Winkels(this);
        winkels.laad();
        onderdelen = new Onderdelen(this);
        menus = new Menus(this);
        rijden = new Rijden(this);
        showrooms = new Showrooms(this);
        showrooms.laad();
        npcs = new Npcs(this);
        bouwer = new Bouwer(this);
        var pm = getServer().getPluginManager();
        pm.registerEvents(onderdelen, this);
        pm.registerEvents(menus, this);
        pm.registerEvents(rijden, this);
        pm.registerEvents(showrooms, this);
        pm.registerEvents(npcs, this);
        pm.registerEvents(bouwer, this);
        Commando cmd = new Commando(this);
        for (String c : new String[]{"garage", "ritly"}) {
            PluginCommand pc = getCommand(c);
            if (pc != null) {
                pc.setExecutor(cmd);
                pc.setTabCompleter(cmd);
            }
        }
        rijden.start();
        showrooms.start();
        npcs.start();
        Bukkit.getScheduler().runTask(this, () -> {
            economie();
            rijden.laadGeparkeerde();
        });
    }

    @Override
    public void onDisable() {
        if (rijden != null) rijden.allesWeg();
        if (showrooms != null) showrooms.allesWeg();
        if (garage != null) garage.opslaan();
    }

    void herlaad() {
        reloadConfig();
        leesConfig();
        rijden.allesWeg();
        showrooms.allesWeg();
        garage.opslaan();
        modellen.laad();
        winkels.laad();
        showrooms.laad();
        rijden.laadGeparkeerde();
    }

    private void leesConfig() {
        upgrades.clear();
        for (String o : Product.ONDERDELEN) {
            List<Double> l = new ArrayList<>();
            for (Object x : getConfig().getList("upgrades." + o, List.of())) if (x instanceof Number n) l.add(n.doubleValue());
            upgrades.put(o, l);
        }
    }

    // ------------------------------------------------------------------ onderdelen van de plugin

    Modellen modellen() { return modellen; }
    Garage garage() { return garage; }
    Winkels winkels() { return winkels; }
    Rijden rijden() { return rijden; }
    Showrooms showrooms() { return showrooms; }
    Npcs npcs() { return npcs; }
    Onderdelen onderdelen() { return onderdelen; }
    Menus menus() { return menus; }
    Bouwer bouwer() { return bouwer; }

    Economy economie() {
        if (economie == null && getServer().getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economie = rsp.getProvider();
        }
        return economie;
    }

    // ------------------------------------------------------------------ instellingen

    List<Double> upgrade(String o) {
        return upgrades.getOrDefault(o, List.of());
    }

    boolean past(Soort s, String onderdeel) {
        return getConfig().getStringList("past-op." + s.sleutel()).contains(onderdeel);
    }

    boolean brandstofAan() {
        return getConfig().getBoolean("brandstof", true);
    }

    boolean kijkSturen() {
        return getConfig().getBoolean("kijk-sturen", true);
    }

    boolean koopWereld(World w) {
        List<String> l = getConfig().getStringList("koop-werelden");
        if (l.isEmpty()) return true;
        for (String s : l) if (s.equalsIgnoreCase(w.getName())) return true;
        return false;
    }

    boolean geblokkeerd(World w) {
        for (String s : getConfig().getStringList("geblokkeerde-werelden")) if (s.equalsIgnoreCase(w.getName())) return true;
        return false;
    }

    boolean creativeGeblokkeerd(Player pl) {
        return getConfig().getBoolean("niet-in-creative", true)
                && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR);
    }

    int maxVoertuigen(Player pl) {
        int max = getConfig().getInt("max-voertuigen", 10);
        for (PermissionAttachmentInfo pai : pl.getEffectivePermissions()) {
            String perm = pai.getPermission();
            if (!pai.getValue() || !perm.startsWith("ritly.max.")) continue;
            try {
                max = Math.max(max, Integer.parseInt(perm.substring(10)));
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }
}
