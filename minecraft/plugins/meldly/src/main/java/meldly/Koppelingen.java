package meldly;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Koppelingen tussen Minecraft-accounts en Discord-accounts (koppelingen.yml), plus de tijdelijke koppelcodes. */
final class Koppelingen {
    static final class Koppeling {
        final UUID uuid;
        String mcNaam;
        final String discordId;
        String discordNaam;
        final long sinds;
        long laatsteSync = 0;
        Koppeling(UUID uuid, String mcNaam, String discordId, String discordNaam, long sinds) {
            this.uuid = uuid; this.mcNaam = mcNaam; this.discordId = discordId; this.discordNaam = discordNaam; this.sinds = sinds;
        }
    }

    /** Code die in het spel is gemaakt (wacht op /koppel in Discord) of in Discord (wacht op /discord koppel in het spel). */
    record Code(String code, UUID uuid, String mcNaam, String discordId, String discordNaam, long verloopt) {}

    private static final String TEKENS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RND = new SecureRandom();

    private final File bestand;
    private final Logger log;
    private final Map<UUID, Koppeling> perSpeler = new LinkedHashMap<>();
    private final Map<String, Koppeling> perDiscord = new LinkedHashMap<>();
    private final Map<String, Code> codes = new ConcurrentHashMap<>();

    Koppelingen(File map, Logger log) {
        this.bestand = new File(map, "koppelingen.yml");
        this.log = log;
    }

    synchronized void laad() {
        perSpeler.clear(); perDiscord.clear();
        if (!bestand.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(bestand);
        ConfigurationSection s = y.getConfigurationSection("koppelingen");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                UUID u = UUID.fromString(k);
                ConfigurationSection c = s.getConfigurationSection(k);
                if (c == null) continue;
                Koppeling kp = new Koppeling(u, c.getString("naam", "?"), c.getString("discord", ""), c.getString("discord-naam", ""), c.getLong("sinds", 0));
                if (kp.discordId.isEmpty()) continue;
                perSpeler.put(u, kp);
                perDiscord.put(kp.discordId, kp);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    synchronized void bewaar() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Meldly - gekoppelde accounts (Minecraft-UUID -> Discord). Beheer: website > Beheer > Discord, of /discord ontkoppel."));
        for (Koppeling k : perSpeler.values()) {
            String p = "koppelingen." + k.uuid + ".";
            y.set(p + "naam", k.mcNaam);
            y.set(p + "discord", k.discordId);
            y.set(p + "discord-naam", k.discordNaam);
            y.set(p + "sinds", k.sinds);
        }
        try { y.save(bestand); } catch (Exception e) { log.warning("Kan koppelingen.yml niet opslaan: " + e.getMessage()); }
    }

    synchronized Koppeling vanSpeler(UUID u) { return perSpeler.get(u); }
    synchronized Koppeling vanDiscord(String id) { return perDiscord.get(id); }
    synchronized List<Koppeling> alle() { return new ArrayList<>(perSpeler.values()); }
    synchronized int aantal() { return perSpeler.size(); }

    synchronized Koppeling koppel(UUID u, String mcNaam, String discordId, String discordNaam) {
        Koppeling oud = perSpeler.remove(u);
        if (oud != null) perDiscord.remove(oud.discordId);
        Koppeling oudD = perDiscord.remove(discordId);
        if (oudD != null) perSpeler.remove(oudD.uuid);
        Koppeling k = new Koppeling(u, mcNaam, discordId, discordNaam, System.currentTimeMillis());
        perSpeler.put(u, k);
        perDiscord.put(discordId, k);
        bewaar();
        return k;
    }

    synchronized Koppeling ontkoppel(UUID u) {
        Koppeling k = perSpeler.remove(u);
        if (k != null) { perDiscord.remove(k.discordId); bewaar(); }
        return k;
    }

    // ------------------------------------------------------------------ codes
    private String nieuweCode() {
        String c;
        do {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(TEKENS.charAt(RND.nextInt(TEKENS.length())));
            c = b.toString();
        } while (codes.containsKey(c));
        return c;
    }

    private void ruimOp() {
        long nu = System.currentTimeMillis();
        for (Iterator<Code> it = codes.values().iterator(); it.hasNext(); ) if (it.next().verloopt() < nu) it.remove();
    }

    /** Code voor een speler in het spel; daarna /koppel <code> in Discord. */
    String codeVoorSpeler(UUID u, String naam) {
        ruimOp();
        codes.values().removeIf(c -> u.equals(c.uuid()));
        String c = nieuweCode();
        codes.put(c, new Code(c, u, naam, null, null, System.currentTimeMillis() + 10 * 60_000L));
        return c;
    }

    /** Code voor iemand in Discord; daarna /discord koppel <code> in het spel. */
    String codeVoorDiscord(String discordId, String discordNaam) {
        ruimOp();
        codes.values().removeIf(c -> discordId.equals(c.discordId()));
        String c = nieuweCode();
        codes.put(c, new Code(c, null, null, discordId, discordNaam, System.currentTimeMillis() + 10 * 60_000L));
        return c;
    }

    Code neemCode(String code) {
        if (code == null) return null;
        ruimOp();
        return codes.remove(code.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
