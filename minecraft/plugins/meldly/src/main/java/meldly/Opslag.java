package meldly;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Instellingen per Discord-server (servers.yml): welke categorieën aan staan, in welk kanaal,
 * en het koppelen van accounts. Wordt bijgehouden door de plugin en het beheer van de website.
 */
final class Opslag {
    static final class CatInst {
        boolean aan;
        String kanaal = "";
        String webhookId = "";
        String webhookToken = "";
    }

    static final class GuildInst {
        final String id;
        String naam = "";
        String categorieKanaal = "";
        String statusBericht = "";
        boolean koppelAan = false;
        String koppelRol = "";
        boolean bijnaam = false;
        int commandoVersie = 0;
        // spraakgroepen
        boolean groepenAan = false;
        String lobby = "";
        boolean groepBuurt = true, groepEiland = true, groepTeam = true, groepZichtbaar = true;
        /** Tijdelijke spraakkanalen die Meldly maakte: kanaal-id -> groepsleutel (bijv. team:draken). */
        final Map<String, String> groepKanalen = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<Categorie, CatInst> cats = new EnumMap<>(Categorie.class);
        GuildInst(String id) {
            this.id = id;
            for (Categorie c : Categorie.values()) cats.put(c, new CatInst());
        }
        CatInst cat(Categorie c) { return cats.get(c); }
        boolean ietsAan() {
            if (koppelAan || groepenAan) return true;
            for (CatInst c : cats.values()) if (c.aan) return true;
            return false;
        }
    }

    private final File bestand;
    private final Logger log;
    final Map<String, GuildInst> guilds = new LinkedHashMap<>();

    Opslag(File map, Logger log) {
        this.bestand = new File(map, "servers.yml");
        this.log = log;
    }

    synchronized GuildInst guild(String id) {
        return guilds.computeIfAbsent(id, GuildInst::new);
    }

    synchronized GuildInst bestaand(String id) { return guilds.get(id); }

    synchronized java.util.List<GuildInst> alle() { return new java.util.ArrayList<>(guilds.values()); }

    synchronized void verwijder(String id) { guilds.remove(id); bewaar(); }

    synchronized void laad() {
        guilds.clear();
        if (!bestand.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(bestand);
        ConfigurationSection s = y.getConfigurationSection("servers");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection g = s.getConfigurationSection(id);
            if (g == null) continue;
            GuildInst gi = new GuildInst(id);
            gi.naam = g.getString("naam", "");
            gi.categorieKanaal = g.getString("categorie-kanaal", "");
            gi.statusBericht = g.getString("status-bericht", "");
            gi.koppelAan = g.getBoolean("koppelen.aan", false);
            gi.koppelRol = g.getString("koppelen.rol", "");
            gi.bijnaam = g.getBoolean("koppelen.bijnaam", false);
            gi.commandoVersie = g.getInt("commando-versie", 0);
            gi.groepenAan = g.getBoolean("groepen.aan", false);
            gi.lobby = g.getString("groepen.lobby", "");
            gi.groepBuurt = g.getBoolean("groepen.in-de-buurt", true);
            gi.groepEiland = g.getBoolean("groepen.eiland", true);
            gi.groepTeam = g.getBoolean("groepen.team", true);
            gi.groepZichtbaar = g.getBoolean("groepen.zichtbaar", true);
            ConfigurationSection gk = g.getConfigurationSection("groepen.kanalen");
            if (gk != null) for (String k : gk.getKeys(false)) gi.groepKanalen.put(k, gk.getString(k, ""));
            for (Categorie c : Categorie.values()) {
                ConfigurationSection cs = g.getConfigurationSection("categorieen." + c.sleutel);
                if (cs == null) continue;
                CatInst ci = gi.cat(c);
                ci.aan = cs.getBoolean("aan", false);
                ci.kanaal = cs.getString("kanaal", "");
                ci.webhookId = cs.getString("webhook-id", "");
                ci.webhookToken = cs.getString("webhook-token", "");
            }
            guilds.put(id, gi);
        }
    }

    synchronized void bewaar() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of(
                "Meldly - instellingen per Discord-server. Aanpassen kan het makkelijkst via de website: Beheer > Discord.",
                "Pas je dit bestand zelf aan, doe dat dan terwijl de server uit staat (of daarna /discord herladen)."));
        for (GuildInst g : guilds.values()) {
            String p = "servers." + g.id + ".";
            y.set(p + "naam", g.naam);
            y.set(p + "categorie-kanaal", g.categorieKanaal);
            y.set(p + "status-bericht", g.statusBericht);
            y.set(p + "commando-versie", g.commandoVersie);
            y.set(p + "koppelen.aan", g.koppelAan);
            y.set(p + "koppelen.rol", g.koppelRol);
            y.set(p + "koppelen.bijnaam", g.bijnaam);
            y.set(p + "groepen.aan", g.groepenAan);
            y.set(p + "groepen.lobby", g.lobby);
            y.set(p + "groepen.in-de-buurt", g.groepBuurt);
            y.set(p + "groepen.eiland", g.groepEiland);
            y.set(p + "groepen.team", g.groepTeam);
            y.set(p + "groepen.zichtbaar", g.groepZichtbaar);
            for (Map.Entry<String, String> e : g.groepKanalen.entrySet()) y.set(p + "groepen.kanalen." + e.getKey(), e.getValue());
            for (Categorie c : Categorie.values()) {
                CatInst ci = g.cat(c);
                String q = p + "categorieen." + c.sleutel + ".";
                y.set(q + "aan", ci.aan);
                y.set(q + "kanaal", ci.kanaal);
                if (!ci.webhookId.isEmpty()) {
                    y.set(q + "webhook-id", ci.webhookId);
                    y.set(q + "webhook-token", ci.webhookToken);
                }
            }
        }
        try {
            File tmp = new File(bestand.getParentFile(), "servers.yml.tmp");
            y.save(tmp);
            java.nio.file.Files.move(tmp.toPath(), bestand.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try { y.save(bestand); } catch (Exception e2) { log.warning("Kan servers.yml niet opslaan: " + e2.getMessage()); }
        }
    }
}
