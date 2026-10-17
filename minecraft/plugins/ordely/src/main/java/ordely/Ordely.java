package ordely;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Ordely: regels (geld en items alleen via de website, geen creative-verkoop) en de gevangenis. */
public final class Ordely extends JavaPlugin {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Logboek logboek;
    private Gevangenis gevangenis;
    private Regels regels;
    private JsonObject data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        laadData();
        logboek = new Logboek(this);
        gevangenis = new Gevangenis(this);
        regels = new Regels(this);
        getServer().getPluginManager().registerEvents(gevangenis, this);
        getServer().getPluginManager().registerEvents(regels, this);
        getServer().getPluginManager().registerEvents(new CreativeBlokken(this), this);
        Commando cmd = new Commando(this);
        PluginCommand pc = getCommand("gevangenis");
        if (pc != null) { pc.setExecutor(cmd); pc.setTabCompleter(cmd); }
        gevangenis.start();
        // Geld-correcties pas als de economy (Essentials via Vault) klaar is
        Bukkit.getScheduler().runTaskLater(this, () -> new Correcties(this).voerUit(), 100L);
        Bukkit.getScheduler().runTaskLater(this, this::stelMultiverseInventoriesIn, 60L);
    }

    /**
     * Creative en survival elk een eigen inventaris (Multiverse-Inventories), en bij inloggen de juiste
     * inventaris laden. Zonder dat neem je spullen uit creative mee naar survival, ook door uit te loggen
     * in creative (force-gamemode zet je bij inloggen weer in survival).
     */
    private void stelMultiverseInventoriesIn() {
        if (!getConfig().getBoolean("creative.multiverse-inventories-instellen", true)) return;
        var mv = getServer().getPluginManager().getPlugin("Multiverse-Inventories");
        if (mv == null || !mv.isEnabled()) return;
        File f = new File(mv.getDataFolder(), "config.yml");
        var yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        String[][] nodig = {{"share-handling.enable-gamemode-share-handling", "enable-gamemode-share-handling"},
                {"performance.apply-playerdata-on-join", "apply-playerdata-on-join"}};
        for (String[] n : nodig) {
            if (yml.getBoolean(n[0], false)) continue;
            getLogger().info("Multiverse-Inventories: " + n[1] + " wordt aangezet (creative en survival elk een eigen inventaris).");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvinv config " + n[1] + " true");
        }
    }

    @Override
    public void onDisable() {
        if (gevangenis != null) gevangenis.stop();
    }

    void herlaad() {
        reloadConfig();
        regels.laad();
        gevangenis.laadConfig();
    }

    Logboek logboek() { return logboek; }
    Gevangenis gevangenis() { return gevangenis; }
    Regels regels() { return regels; }

    // ------------------------------------------------------------ data.json
    JsonObject data() { return data; }

    private void laadData() {
        File f = new File(getDataFolder(), "data.json");
        data = new JsonObject();
        if (f.isFile()) {
            try { data = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (Exception e) { getLogger().warning("data.json kon niet gelezen worden: " + e.getMessage()); }
        }
        if (!data.has("correcties-gedaan")) data.add("correcties-gedaan", new JsonArray());
    }

    void bewaarData() { schrijfVeilig(new File(getDataFolder(), "data.json"), GSON.toJson(data)); }

    /** Schrijft eerst naar een .tmp-bestand en zet dat daarna op zijn plek. */
    void schrijfVeilig(File f, String tekst) {
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), tekst, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.writeString(f.toPath(), tekst, StandardCharsets.UTF_8); }
            catch (IOException e2) { getLogger().severe("Kon " + f.getName() + " niet opslaan: " + e2.getMessage()); }
        }
    }

    // ------------------------------------------------------------ berichten
    static final Component PREFIX = Component.text("[Gevangenis] ", NamedTextColor.GOLD);
    static final Component PREFIX_REGELS = Component.text("[Regels] ", NamedTextColor.RED);

    static void zeg(CommandSender s, String tekst, NamedTextColor kleur) { s.sendMessage(PREFIX.append(Component.text(tekst, kleur))); }
    static void regel(CommandSender s, String tekst) { s.sendMessage(PREFIX_REGELS.append(Component.text(tekst, NamedTextColor.YELLOW))); }

    static String duur(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, u = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return u > 0 ? String.format("%d:%02d:%02d", u, m, sec) : String.format("%d:%02d", m, sec);
    }
}
