package meldly;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Zelfgemaakte teams (/discord team): wie in hetzelfde team zit, praat in Discord samen in één spraakkanaal. */
final class Teams {
    static final class Team {
        final String naam;
        UUID eigenaar;
        final Set<UUID> leden = new LinkedHashSet<>();
        Team(String naam, UUID eigenaar) { this.naam = naam; this.eigenaar = eigenaar; leden.add(eigenaar); }
    }
    record Uitnodiging(String team, UUID door, long verloopt) {}

    private final File bestand;
    private final Logger log;
    private final Map<String, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, String> lidVan = new ConcurrentHashMap<>();
    private final Map<UUID, Uitnodiging> uitnodigingen = new ConcurrentHashMap<>();

    Teams(File map, Logger log) {
        this.bestand = new File(map, "teams.yml");
        this.log = log;
    }

    static boolean geldigeNaam(String n) { return n != null && n.matches("[A-Za-z0-9_]{2,16}"); }

    synchronized void laad() {
        teams.clear(); lidVan.clear();
        if (!bestand.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(bestand);
        ConfigurationSection s = y.getConfigurationSection("teams");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection t = s.getConfigurationSection(k);
            if (t == null) continue;
            try {
                Team team = new Team(t.getString("naam", k), UUID.fromString(t.getString("eigenaar", "")));
                for (String l : t.getStringList("leden")) try { team.leden.add(UUID.fromString(l)); } catch (IllegalArgumentException ignored) { }
                teams.put(team.naam.toLowerCase(Locale.ROOT), team);
                for (UUID u : team.leden) lidVan.put(u, team.naam.toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    synchronized void bewaar() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Meldly - teams voor Discord-spraak (/discord team). Wie in hetzelfde team zit, komt samen in een spraakkanaal."));
        for (Team t : teams.values()) {
            String p = "teams." + t.naam.toLowerCase(Locale.ROOT) + ".";
            y.set(p + "naam", t.naam);
            y.set(p + "eigenaar", t.eigenaar.toString());
            y.set(p + "leden", t.leden.stream().map(UUID::toString).toList());
        }
        try { y.save(bestand); } catch (Exception e) { log.warning("Kan teams.yml niet opslaan: " + e.getMessage()); }
    }

    Team teamVan(UUID u) {
        String k = lidVan.get(u);
        return k == null ? null : teams.get(k);
    }

    Team team(String naam) { return naam == null ? null : teams.get(naam.toLowerCase(Locale.ROOT)); }

    List<Team> alle() { return new ArrayList<>(teams.values()); }

    synchronized String maak(UUID u, String naam) {
        if (!geldigeNaam(naam)) return "Kies een naam van 2 tot 16 tekens (letters, cijfers of _).";
        if (teamVan(u) != null) return "Je zit al in team " + teamVan(u).naam + ". Verlaat dat eerst met /discord team verlaat.";
        if (team(naam) != null) return "Er is al een team met die naam.";
        Team t = new Team(naam, u);
        teams.put(naam.toLowerCase(Locale.ROOT), t);
        lidVan.put(u, naam.toLowerCase(Locale.ROOT));
        bewaar();
        return null;
    }

    synchronized String nodigUit(UUID door, UUID wie, int max) {
        Team t = teamVan(door);
        if (t == null) return "Je zit nog niet in een team. Maak er een met /discord team maak <naam>.";
        if (!t.eigenaar.equals(door)) return "Alleen de eigenaar van het team kan mensen uitnodigen.";
        if (t.leden.contains(wie)) return "Die speler zit al in je team.";
        if (teamVan(wie) != null) return "Die speler zit al in een ander team.";
        if (t.leden.size() >= max) return "Je team is vol (" + max + " spelers).";
        uitnodigingen.put(wie, new Uitnodiging(t.naam, door, System.currentTimeMillis() + 5 * 60_000L));
        return null;
    }

    /** Geeft de teamnaam terug, of null als er geen (geldige) uitnodiging is. */
    synchronized String accepteer(UUID wie, int max) {
        Uitnodiging uit = uitnodigingen.remove(wie);
        if (uit == null || uit.verloopt() < System.currentTimeMillis()) return null;
        Team t = team(uit.team());
        if (t == null || teamVan(wie) != null || t.leden.size() >= max) return null;
        t.leden.add(wie);
        lidVan.put(wie, t.naam.toLowerCase(Locale.ROOT));
        bewaar();
        return t.naam;
    }

    Uitnodiging uitnodiging(UUID wie) { return uitnodigingen.get(wie); }
    void weiger(UUID wie) { uitnodigingen.remove(wie); }

    /** Verlaat het team; de eigenaar geeft het door aan het volgende lid, of het team verdwijnt. Geeft het team terug (of null). */
    synchronized Team verlaat(UUID wie) {
        Team t = teamVan(wie);
        if (t == null) return null;
        t.leden.remove(wie);
        lidVan.remove(wie);
        if (t.leden.isEmpty()) teams.remove(t.naam.toLowerCase(Locale.ROOT));
        else if (t.eigenaar.equals(wie)) t.eigenaar = t.leden.iterator().next();
        bewaar();
        return t;
    }

    synchronized boolean hefOp(String naam) {
        Team t = teams.remove(naam.toLowerCase(Locale.ROOT));
        if (t == null) return false;
        for (UUID u : t.leden) lidVan.remove(u);
        uitnodigingen.values().removeIf(x -> x.team().equalsIgnoreCase(naam));
        bewaar();
        return true;
    }
}
