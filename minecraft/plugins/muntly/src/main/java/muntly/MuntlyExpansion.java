package muntly;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;

/**
 * Placeholders voor PlaceholderAPI (bijv. voor het statusbord):
 * %muntly_saldo% (1.250), %muntly_saldo_kaal% (1250), %muntly_munt% (Munten), %muntly_reeks%,
 * %muntly_top_1_naam%, %muntly_top_1_saldo% (t/m 10).
 */
final class MuntlyExpansion extends PlaceholderExpansion {
    private final Muntly plugin;

    MuntlyExpansion(Muntly plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "muntly"; }
    @Override public String getAuthor() { return "Meedia SMP"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(java.util.Locale.ROOT);
        if (p.equals("munt")) return plugin.muntNaam();
        if (p.startsWith("top_")) {
            String[] parts = p.split("_");
            if (parts.length != 3) return null;
            int n;
            try { n = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return null; }
            if (n < 1 || n > 10) return null;
            List<Saldi.Speler> top = plugin.saldi().top(10);
            if (n > top.size()) return parts[2].equals("naam") ? "-" : "0";
            Saldi.Speler sp = top.get(n - 1);
            return parts[2].equals("naam") ? sp.naam : Muntly.fmt(sp.saldo);
        }
        if (player == null) return "";
        Saldi.Speler sp = plugin.saldi().find(player.getUniqueId());
        long saldo = sp == null ? 0 : sp.saldo;
        return switch (p) {
            case "saldo" -> Muntly.fmt(saldo);
            case "saldo_kaal" -> String.valueOf(saldo);
            case "reeks" -> String.valueOf(sp == null ? 0 : sp.reeks);
            default -> null;
        };
    }
}
