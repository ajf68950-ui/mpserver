package toolwise;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/** Alle waarden uit config.yml, één keer ingelezen (en opnieuw bij /toolwise reload). */
final class Instellingen {

    enum WapenVoorkeur { ZWAARD, SCHADE, DPS }

    boolean gereedschapStandaard;
    boolean wapensStandaard;
    boolean alleenAlsOngeschikt;
    boolean ookUitInventaris;
    boolean nietBijSluipen;
    boolean wapensTegenSpelers;
    int beschermDuurzaamheid;
    WapenVoorkeur wapenVoorkeur;
    boolean melding;
    Set<String> uitgeschakeldeWerelden = new HashSet<>();

    void lees(FileConfiguration c, Logger log) {
        gereedschapStandaard = c.getBoolean("standaard.gereedschap", true);
        wapensStandaard = c.getBoolean("standaard.wapens", true);
        alleenAlsOngeschikt = c.getBoolean("alleen-als-ongeschikt", true);
        ookUitInventaris = c.getBoolean("ook-uit-inventaris", false);
        nietBijSluipen = c.getBoolean("niet-bij-sluipen", true);
        wapensTegenSpelers = c.getBoolean("wapens-tegen-spelers", true);
        beschermDuurzaamheid = Math.max(0, c.getInt("bescherm-duurzaamheid", 10));
        melding = c.getBoolean("melding", false);

        String v = c.getString("wapen-voorkeur", "zwaard").trim().toUpperCase(Locale.ROOT);
        try {
            wapenVoorkeur = WapenVoorkeur.valueOf(v);
        } catch (IllegalArgumentException e) {
            log.warning("Onbekende wapen-voorkeur '" + v + "' in config.yml, gebruik 'zwaard'. Kies zwaard, schade of dps.");
            wapenVoorkeur = WapenVoorkeur.ZWAARD;
        }

        uitgeschakeldeWerelden.clear();
        List<String> werelden = c.getStringList("uitgeschakelde-werelden");
        for (String w : werelden) uitgeschakeldeWerelden.add(w.toLowerCase(Locale.ROOT));
    }
}
