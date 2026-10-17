package ordely;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/** Eenmalige correcties van geldsaldi uit config.yml (correcties:). Elke correctie wordt maar een keer uitgevoerd. */
final class Correcties {

    private final Ordely plugin;

    Correcties(Ordely plugin) { this.plugin = plugin; }

    void voerUit() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("correcties");
        if (sec == null || sec.getKeys(false).isEmpty()) return;
        JsonArray gedaan = plugin.data().getAsJsonArray("correcties-gedaan");
        Economy eco = economy();
        for (String id : sec.getKeys(false)) {
            if (isGedaan(gedaan, id)) continue;
            ConfigurationSection c = sec.getConfigurationSection(id);
            if (c == null || !c.contains("geld-zetten-op")) continue;
            if (eco == null) { plugin.getLogger().warning("Correctie " + id + " wacht: geen economy (Vault) gevonden."); return; }
            UUID u;
            try { u = UUID.fromString(c.getString("speler", "")); } catch (IllegalArgumentException e) { plugin.getLogger().warning("Correctie " + id + ": ongeldige UUID."); continue; }
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            String naam = op.getName() != null ? op.getName() : c.getString("naam", u.toString());
            double doel = Math.max(0, c.getDouble("geld-zetten-op"));
            String reden = c.getString("reden", "Correctie " + id);
            if (!eco.hasAccount(op)) eco.createPlayerAccount(op);
            double voor = eco.getBalance(op);
            boolean ok = doel >= voor ? eco.depositPlayer(op, doel - voor).transactionSuccess() : eco.withdrawPlayer(op, voor - doel).transactionSuccess();
            double na = eco.getBalance(op);
            if (!ok || Math.abs(na - doel) > 0.01) {
                plugin.getLogger().severe("Correctie " + id + " voor " + naam + " is NIET gelukt (saldo nu $" + bedrag(na) + "). Wordt bij de volgende start opnieuw geprobeerd.");
                continue;
            }
            gedaan.add(id);
            plugin.bewaarData();
            plugin.logboek().schrijf("CORRECTIE", naam, u, "geld van $" + bedrag(voor) + " naar $" + bedrag(na) + ": " + reden, -1, "Ordely (eenmalige correctie " + id + ")", "correctie");
            plugin.getLogger().info("Correctie " + id + ": geld van " + naam + " van $" + bedrag(voor) + " naar $" + bedrag(na) + ".");
            schrijfEconomieLog(naam, voor, na, id, reden);
        }
    }

    private static boolean isGedaan(JsonArray a, String id) {
        for (JsonElement e : a) if (id.equals(e.getAsString())) return true;
        return false;
    }

    private Economy economy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    static String bedrag(double v) {
        DecimalFormat f = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return f.format(v);
    }

    /** Zelfde logboek als Beheer > Economie op de website: <hoofdmap>\Website\logs\economie.log */
    private void schrijfEconomieLog(String naam, double voor, double na, String id, String reden) {
        try {
            File server = Bukkit.getWorldContainer().getAbsoluteFile();
            File hoofd = null;
            for (File f = server; f != null; f = f.getParentFile()) {
                if (new File(f, "Website").isDirectory()) { hoofd = f; break; }
            }
            if (hoofd == null) return;
            File log = new File(new File(hoofd, "Website"), "logs" + File.separator + "economie.log");
            log.getParentFile().mkdirs();
            StringBuilder sb = new StringBuilder();
            if (!log.isFile()) sb.append("# Economie-logboek van Beheer > Economie: datum tijd | GELD/MUNTEN | actie | bedrag | speler | saldo voor -> na | vanaf welk IP\r\n");
            sb.append(LocalDateTime.now().format(Logboek.TIJD)).append(" | GELD   | zet  | ")
              .append(String.format("%16s", "$" + bedrag(na))).append(" | ").append(String.format("%-16s", naam))
              .append(" | saldo $").append(bedrag(voor)).append(" -> $").append(bedrag(na))
              .append(" | eenmalige correctie ").append(id).append(" (Ordely): ").append(reden).append("\r\n");
            Files.writeString(log.toPath(), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            plugin.getLogger().warning("Kon economie.log niet bijwerken: " + e.getMessage());
        }
    }
}
