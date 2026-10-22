package meldly;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spraakgroepen in Discord, zoals bij DiscordSRV: wie (gekoppeld) in het lobby-spraakkanaal zit, komt automatisch
 * in een tijdelijk spraakkanaal met zijn team, zijn eilandgenoten of de spelers die in het spel dichtbij zijn.
 * Lege groepskanalen verdwijnen vanzelf weer.
 */
final class Spraak {
    /** Momentopname van een gekoppelde speler die online is (gemaakt op de hoofdthread). */
    record Speler(UUID uuid, String naam, String discordId, String wereld, double x, double y, double z,
                  String team, String eilandSleutel, String eilandNaam) {}

    private static final long VIEW = 1L << 10, CONNECT = 1L << 20, SPEAK = 1L << 21, MOVE = 1L << 24;

    private final Meldly plugin;
    private final Bot bot;
    /** guild|discordId -> tijd tot wanneer we niet opnieuw verplaatsen (verplaatsing onderweg). */
    private final Map<String, Long> onderweg = new ConcurrentHashMap<>();
    /** guild|sleutel -> kanaal wordt gemaakt. */
    private final Set<String> maken = ConcurrentHashMap.newKeySet();
    /** kanaal -> sinds wanneer leeg. */
    private final Map<String, Long> leegSinds = new ConcurrentHashMap<>();
    /** kanaal -> Discord-gebruikers die er rechten hebben. */
    private final Map<String, Set<String>> toegang = new ConcurrentHashMap<>();
    /** speler -> laatste groep (voor de melding in het spel). */
    private final Map<UUID, String> laatsteGroep = new ConcurrentHashMap<>();
    private volatile boolean bezig = false;

    Spraak(Meldly plugin, Bot bot) {
        this.plugin = plugin;
        this.bot = bot;
    }

    boolean ergensAan() {
        for (Opslag.GuildInst gi : plugin.opslag().alle()) if (gi.groepenAan || !gi.groepKanalen.isEmpty()) return true;
        return false;
    }

    /** Wordt elke seconde aangeroepen (niet op de hoofdthread). */
    void tik(List<Speler> spelers) {
        if (bezig) return;
        bezig = true;
        try {
            Rest rest = bot.rest;
            if (rest == null || !bot.verbonden()) return;
            Map<String, Speler> perDiscord = new HashMap<>();
            for (Speler s : spelers) perDiscord.put(s.discordId(), s);
            Map<UUID, String> groepNu = new HashMap<>();
            for (Opslag.GuildInst gi : plugin.opslag().alle()) {
                Bot.Guild g = bot.guilds.get(gi.id);
                if (g == null) continue;
                try { guild(rest, gi, g, perDiscord, groepNu); } catch (Exception e) {
                    bot.fout("Spraakgroepen in " + gi.naam + ": " + e.getMessage());
                }
            }
            meldVeranderingen(spelers, groepNu);
        } finally {
            bezig = false;
        }
    }

    private String kanaalVan(Bot.Guild g, String user) {
        Map<String, String> v = bot.spraak.get(g.id);
        return v == null ? null : v.get(user);
    }

    private void guild(Rest rest, Opslag.GuildInst gi, Bot.Guild g, Map<String, Speler> perDiscord, Map<UUID, String> groepNu) {
        Map<String, String> stemmen = bot.spraak.getOrDefault(g.id, Map.of());
        boolean lobbyOk = gi.groepenAan && !gi.lobby.isEmpty() && g.kanalen.containsKey(gi.lobby);
        long nu = System.currentTimeMillis();

        // kanalen die in Discord al weg zijn, vergeten
        for (String k : new ArrayList<>(gi.groepKanalen.keySet())) {
            if (!g.kanalen.containsKey(k) && !maken.contains(gi.id + "|" + gi.groepKanalen.get(k))) { gi.groepKanalen.remove(k); plugin.opslag().bewaar(); }
        }
        Map<String, String> sleutelNaarKanaal = new HashMap<>();
        for (Map.Entry<String, String> e : gi.groepKanalen.entrySet()) sleutelNaarKanaal.put(e.getValue(), e.getKey());

        // deelnemers: gekoppelde spelers die online zijn en in de lobby of in een groepskanaal zitten
        List<Speler> deelnemers = new ArrayList<>();
        if (lobbyOk) {
            for (Map.Entry<String, String> e : stemmen.entrySet()) {
                String kanaal = e.getValue();
                if (!kanaal.equals(gi.lobby) && !gi.groepKanalen.containsKey(kanaal)) continue;
                Speler s = perDiscord.get(e.getKey());
                if (s != null) deelnemers.add(s);
            }
        }

        // groepen bepalen: eerst eigen team, dan eiland, dan in de buurt
        Map<String, List<Speler>> groepen = new LinkedHashMap<>();
        Set<UUID> ingedeeld = new HashSet<>();
        if (gi.groepTeam) verdeel(deelnemers, ingedeeld, groepen, s -> s.team() == null ? null : "team:" + s.team().toLowerCase());
        if (gi.groepEiland) verdeel(deelnemers, ingedeeld, groepen, s -> s.eilandSleutel() == null ? null : "eiland:" + s.eilandSleutel());
        if (gi.groepBuurt) buurt(g, gi, deelnemers, ingedeeld, groepen, sleutelNaarKanaal);

        // kanalen maken waar nodig
        Map<UUID, String> doel = new HashMap<>();
        for (Map.Entry<String, List<Speler>> e : groepen.entrySet()) {
            String sleutel = e.getKey();
            String kanaal = sleutelNaarKanaal.get(sleutel);
            if (kanaal == null) { maakKanaal(rest, gi, g, sleutel, e.getValue()); continue; }
            for (Speler s : e.getValue()) { doel.put(s.uuid(), kanaal); groepNu.put(s.uuid(), groepTekst(sleutel, e.getValue(), s)); }
        }

        // verplaatsen
        for (Speler s : deelnemers) {
            String nuIn = stemmen.get(s.discordId());
            String naar = doel.containsKey(s.uuid()) ? doel.get(s.uuid()) : (groepen.values().stream().anyMatch(l -> l.contains(s)) ? null : gi.lobby);
            if (naar == null || naar.equals(nuIn)) continue;
            verplaats(rest, gi, s.discordId(), naar);
            if (naar.equals(gi.lobby)) groepNu.putIfAbsent(s.uuid(), "lobby");
        }
        for (Speler s : deelnemers) groepNu.putIfAbsent(s.uuid(), "lobby");

        // wie in een groepskanaal zit maar er niet hoort (offline, niet gekoppeld, of groepen staan uit): terug naar de lobby
        Set<String> deelnemerIds = new HashSet<>();
        for (Speler s : deelnemers) deelnemerIds.add(s.discordId());
        boolean lobbyBestaat = !gi.lobby.isEmpty() && g.kanalen.containsKey(gi.lobby);
        for (Map.Entry<String, String> e : stemmen.entrySet()) {
            if (!gi.groepKanalen.containsKey(e.getValue()) || deelnemerIds.contains(e.getKey())) continue;
            if (lobbyBestaat) verplaats(rest, gi, e.getKey(), gi.lobby);
            else uitSpraak(rest, gi, e.getKey());
        }

        // lege groepskanalen opruimen (na 10 s, of na 60 s als de groep nog bestaat maar niemand er is)
        Set<String> inGebruik = new HashSet<>(stemmen.values());
        for (Map.Entry<String, String> e : new ArrayList<>(gi.groepKanalen.entrySet())) {
            String k = e.getKey();
            if (inGebruik.contains(k)) { leegSinds.remove(k); continue; }
            long sinds = leegSinds.computeIfAbsent(k, x -> nu);
            boolean nogNodig = groepen.containsKey(e.getValue());
            if (nu - sinds >= (nogNodig ? 60_000 : 10_000)) {
                gi.groepKanalen.remove(k);
                leegSinds.remove(k);
                toegang.remove(k);
                plugin.opslag().bewaar();
                rest.delete("/channels/" + k).exceptionally(x -> null);
            }
        }
    }

    private interface Sleutel { String van(Speler s); }

    private static void verdeel(List<Speler> deelnemers, Set<UUID> ingedeeld, Map<String, List<Speler>> groepen, Sleutel f) {
        Map<String, List<Speler>> m = new LinkedHashMap<>();
        for (Speler s : deelnemers) {
            if (ingedeeld.contains(s.uuid())) continue;
            String k = f.van(s);
            if (k != null) m.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<String, List<Speler>> e : m.entrySet()) {
            if (e.getValue().size() < 2) continue;
            groepen.put(e.getKey(), e.getValue());
            for (Speler s : e.getValue()) ingedeeld.add(s.uuid());
        }
    }

    /** Spelers die dichtbij elkaar zijn (via elkaar telt ook). Wie al samen in een kanaal zit, mag iets verder uit elkaar (speling). */
    private void buurt(Bot.Guild g, Opslag.GuildInst gi, List<Speler> deelnemers, Set<UUID> ingedeeld, Map<String, List<Speler>> groepen,
                       Map<String, String> sleutelNaarKanaal) {
        List<Speler> over = new ArrayList<>();
        for (Speler s : deelnemers) if (!ingedeeld.contains(s.uuid())) over.add(s);
        if (over.size() < 2) return;
        double h = plugin.getConfig().getDouble("groepen.afstand.horizontaal", 80);
        double v = plugin.getConfig().getDouble("groepen.afstand.verticaal", 40);
        double speling = plugin.getConfig().getDouble("groepen.afstand.speling", 5);
        int n = over.size();
        int[] ouder = new int[n];
        for (int i = 0; i < n; i++) ouder[i] = i;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            Speler a = over.get(i), b = over.get(j);
            if (!a.wereld().equals(b.wereld())) continue;
            String ka = kanaalVan(g, a.discordId()), kb = kanaalVan(g, b.discordId());
            boolean samen = ka != null && ka.equals(kb) && gi.groepKanalen.containsKey(ka);
            double hh = h + (samen ? speling : 0), vv = v + (samen ? speling : 0);
            double dx = a.x() - b.x(), dz = a.z() - b.z(), dy = Math.abs(a.y() - b.y());
            if (dx * dx + dz * dz <= hh * hh && dy <= vv) {
                int ra = wortel(ouder, i), rb = wortel(ouder, j);
                if (ra != rb) ouder[ra] = rb;
            }
        }
        Map<Integer, List<Speler>> comp = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) comp.computeIfAbsent(wortel(ouder, i), x -> new ArrayList<>()).add(over.get(i));
        // groepen van 2 of meer; eerst houdt elke groep het buurt-kanaal waar de meesten al in zitten
        List<List<Speler>> groepenLijst = new ArrayList<>();
        for (List<Speler> c : comp.values()) if (c.size() >= 2) groepenLijst.add(c);
        Set<String> gebruikt = new HashSet<>();
        Map<List<Speler>, String> keuze = new LinkedHashMap<>();
        for (List<Speler> c : groepenLijst) {
            Map<String, Integer> tel = new HashMap<>();
            for (Speler s : c) {
                String k = kanaalVan(g, s.discordId());
                String sl = k == null ? null : gi.groepKanalen.get(k);
                if (sl != null && sl.startsWith("buurt:") && !gebruikt.contains(sl)) tel.merge(sl, 1, Integer::sum);
            }
            String sleutel = tel.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (sleutel != null) { gebruikt.add(sleutel); keuze.put(c, sleutel); }
        }
        // daarna: een bestaand (leeg of vrij) buurt-kanaal hergebruiken, dan een kanaal dat al gemaakt wordt, en pas dan een nieuw
        List<String> vrij = new ArrayList<>();
        for (String sl : sleutelNaarKanaal.keySet()) if (sl.startsWith("buurt:") && !gebruikt.contains(sl)) vrij.add(sl);
        for (String k : maken) if (k.startsWith(gi.id + "|buurt:")) { String sl = k.substring(gi.id.length() + 1); if (!gebruikt.contains(sl) && !vrij.contains(sl)) vrij.add(sl); }
        vrij.sort((a, b) -> Integer.compare(Integer.parseInt(a.substring(6)), Integer.parseInt(b.substring(6))));
        for (List<Speler> c : groepenLijst) {
            if (keuze.containsKey(c)) continue;
            String sleutel = null;
            while (!vrij.isEmpty() && sleutel == null) { String sl = vrij.remove(0); if (!gebruikt.contains(sl)) sleutel = sl; }
            if (sleutel == null) {
                int nr = 1;
                while (sleutelNaarKanaal.containsKey("buurt:" + nr) || gebruikt.contains("buurt:" + nr) || maken.contains(gi.id + "|buurt:" + nr)) nr++;
                sleutel = "buurt:" + nr;
            }
            gebruikt.add(sleutel);
            keuze.put(c, sleutel);
        }
        for (Map.Entry<List<Speler>, String> e : keuze.entrySet()) {
            groepen.put(e.getValue(), e.getKey());
            for (Speler s : e.getKey()) ingedeeld.add(s.uuid());
        }
    }

    private static int wortel(int[] o, int i) {
        while (o[i] != i) { o[i] = o[o[i]]; i = o[i]; }
        return i;
    }

    private String kanaalNaam(String sleutel, List<Speler> leden) {
        var c = plugin.getConfig();
        if (sleutel.startsWith("team:")) {
            String t = leden.get(0).team();
            return c.getString("groepen.namen.team", "👥 Team {naam}").replace("{naam}", t == null ? "?" : t);
        }
        if (sleutel.startsWith("eiland:")) return c.getString("groepen.namen.eiland", "🏝️ {naam}").replace("{naam}", leden.get(0).eilandNaam());
        return c.getString("groepen.namen.in-de-buurt", "🔊 In de buurt {nr}").replace("{nr}", sleutel.substring(sleutel.indexOf(':') + 1));
    }

    private void maakKanaal(Rest rest, Opslag.GuildInst gi, Bot.Guild g, String sleutel, List<Speler> leden) {
        if (!maken.add(gi.id + "|" + sleutel)) return;
        Bot.Kanaal lobby = g.kanalen.get(gi.lobby);
        JsonObject b = new JsonObject();
        String naam = kanaalNaam(sleutel, leden);
        b.addProperty("name", naam.length() > 95 ? naam.substring(0, 95) : naam);
        b.addProperty("type", 2);
        if (lobby != null && lobby.parent() != null) b.addProperty("parent_id", lobby.parent());
        JsonArray rechten = new JsonArray();
        rechten.add(recht(gi.id, 0, 0, gi.groepZichtbaar ? CONNECT : (VIEW | CONNECT)));
        if (!bot.botId.isEmpty()) rechten.add(recht(bot.botId, 1, VIEW | CONNECT | MOVE, 0));
        Set<String> mag = ConcurrentHashMap.newKeySet();
        for (Speler s : leden) { rechten.add(recht(s.discordId(), 1, VIEW | CONNECT | SPEAK, 0)); mag.add(s.discordId()); }
        b.add("permission_overwrites", rechten);
        rest.post("/guilds/" + gi.id + "/channels", b).whenComplete((j, e) -> {
            maken.remove(gi.id + "|" + sleutel);
            if (e != null) { bot.fout("Spraakkanaal maken in " + gi.naam + " lukt niet: " + Rest.melding(e)); return; }
            JsonObject o = j.getAsJsonObject();
            String id = o.get("id").getAsString();
            g.kanalen.put(id, new Bot.Kanaal(id, o.get("name").getAsString(), 2, lobby == null ? null : lobby.parent(), 0));
            toegang.put(id, mag);
            gi.groepKanalen.put(id, sleutel);
            plugin.opslag().bewaar();
        });
    }

    private static JsonObject recht(String id, int type, long allow, long deny) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        o.addProperty("allow", String.valueOf(allow));
        o.addProperty("deny", String.valueOf(deny));
        return o;
    }

    private void verplaats(Rest rest, Opslag.GuildInst gi, String user, String kanaal) {
        String k = gi.id + "|" + user;
        long nu = System.currentTimeMillis();
        Long tot = onderweg.get(k);
        if (tot != null && tot > nu) return;
        onderweg.put(k, nu + 4000);
        if (gi.groepKanalen.containsKey(kanaal)) {
            Set<String> mag = toegang.computeIfAbsent(kanaal, x -> ConcurrentHashMap.newKeySet());
            if (mag.add(user)) {
                JsonObject r = new JsonObject();
                r.addProperty("type", 1);
                r.addProperty("allow", String.valueOf(VIEW | CONNECT | SPEAK));
                r.addProperty("deny", "0");
                rest.put("/channels/" + kanaal + "/permissions/" + user, r).exceptionally(x -> null);
            }
        }
        JsonObject b = new JsonObject();
        b.addProperty("channel_id", kanaal);
        rest.patch("/guilds/" + gi.id + "/members/" + user, b).whenComplete((j, e) -> {
            if (e == null) return;
            onderweg.remove(k);
            int code = Rest.code(e);
            if (code == 40032) return; // zit niet (meer) in spraak
            bot.fout("Iemand verplaatsen in " + gi.naam + " lukt niet: " + Rest.melding(e) + " (de bot heeft 'Leden verplaatsen' nodig)");
        });
    }

    private void uitSpraak(Rest rest, Opslag.GuildInst gi, String user) {
        String k = gi.id + "|" + user;
        Long tot = onderweg.get(k);
        if (tot != null && tot > System.currentTimeMillis()) return;
        onderweg.put(k, System.currentTimeMillis() + 4000);
        JsonObject b = new JsonObject();
        b.add("channel_id", com.google.gson.JsonNull.INSTANCE);
        rest.patch("/guilds/" + gi.id + "/members/" + user, b).exceptionally(x -> null);
    }

    private static String groepTekst(String sleutel, List<Speler> leden, Speler ik) {
        List<String> anderen = new ArrayList<>();
        for (Speler s : leden) if (!s.uuid().equals(ik.uuid())) anderen.add(s.naam());
        String soort = sleutel.startsWith("team:") ? "je team" : sleutel.startsWith("eiland:") ? "je eiland" : "in de buurt";
        return soort + "|" + String.join(", ", anderen);
    }

    private void meldVeranderingen(List<Speler> spelers, Map<UUID, String> groepNu) {
        if (!plugin.getConfig().getBoolean("groepen.meldingen", true)) return;
        Map<UUID, String> melden = new HashMap<>();
        for (Speler s : spelers) {
            String nu = groepNu.get(s.uuid());
            String was = laatsteGroep.get(s.uuid());
            if (nu == null) { laatsteGroep.remove(s.uuid()); continue; }
            if (nu.equals(was)) continue;
            laatsteGroep.put(s.uuid(), nu);
            if (was == null && nu.equals("lobby")) continue;
            melden.put(s.uuid(), nu);
        }
        if (melden.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Map.Entry<UUID, String> e : melden.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null) continue;
                Component c;
                if (e.getValue().equals("lobby")) c = Component.text("Je bent in Discord terug in de lobby.", NamedTextColor.GRAY);
                else {
                    String[] d = e.getValue().split("\\|", 2);
                    c = Component.text("Je praat in Discord nu met " + d[1] + " (" + d[0] + ").", NamedTextColor.GRAY);
                }
                p.sendActionBar(c);
                p.sendMessage(Component.text("[Discord] ", NamedTextColor.BLUE).append(c));
            }
        });
    }
}
