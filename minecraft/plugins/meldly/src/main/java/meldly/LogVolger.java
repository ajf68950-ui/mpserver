package meldly;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leest nieuwe regels uit logbestanden en zet ze in de juiste categorie:
 * het serverlog (WARN/ERROR naar Fouten), het logboek van Ordely (Moderatie) en tekstlogs (bijv. Economie).
 * Begint altijd aan het eind van het bestand: oude regels worden niet opnieuw gestuurd.
 */
final class LogVolger {
    private static final class Volg {
        final File bestand;
        final String soort;
        final Categorie categorie;
        long positie = -1;
        final StringBuilder rest = new StringBuilder();
        Volg(File bestand, String soort, Categorie categorie) { this.bestand = bestand; this.soort = soort; this.categorie = categorie; }
    }

    private static final Pattern SERVERLOG = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)] \\[([^\\]]*)/(WARN|ERROR|FATAL)]: (.*)$");
    private static final Pattern NIEUWE_REGEL = Pattern.compile("^\\[\\d\\d:\\d\\d:\\d\\d] \\[");

    private final Meldly plugin;
    private final List<Volg> volgers = new ArrayList<>();
    private final List<Pattern> negeren = new ArrayList<>();
    private int perMinuut;
    private long minuutStart = 0;
    private int ditMinuut = 0, overgeslagen = 0;
    // de foutmelding die nog stack-regels kan krijgen
    private StringBuilder open = null;
    private String openNiveau = null;
    private int openRegels = 0;

    LogVolger(Meldly plugin) { this.plugin = plugin; }

    synchronized void laad(boolean serverlogMee) {
        volgers.clear();
        negeren.clear();
        File basis = plugin.getServer().getWorldContainer().getAbsoluteFile();
        for (Map<?, ?> m : plugin.getConfig().getMapList("logbestanden")) {
            Object b = m.get("bestand"), s = m.get("soort"), c = m.get("categorie");
            if (b == null) continue;
            String soort = s == null ? "tekst" : s.toString();
            if (soort.equals("serverlog") && !serverlogMee) continue;
            Categorie cat = Categorie.van(c == null ? null : c.toString());
            if (cat == null) cat = switch (soort) {
                case "serverlog" -> Categorie.FOUTEN;
                case "ordely" -> Categorie.MODERATIE;
                default -> Categorie.ECONOMIE;
            };
            File f = new File(b.toString());
            if (!f.isAbsolute()) f = new File(basis, b.toString());
            try { f = f.getCanonicalFile(); } catch (Exception ignored) { }
            Volg v = new Volg(f, soort, cat);
            v.positie = f.exists() ? f.length() : 0;
            volgers.add(v);
        }
        ConfigurationSection fs = plugin.getConfig().getConfigurationSection("fouten");
        perMinuut = fs == null ? 20 : fs.getInt("max-per-minuut", 20);
        for (String r : plugin.getConfig().getStringList("fouten.negeren")) {
            try { negeren.add(Pattern.compile(r)); } catch (Exception e) { plugin.getLogger().warning("Ongeldige regel in fouten.negeren: " + r); }
        }
    }

    /** Wordt elke paar seconden aangeroepen (niet op de hoofdthread). */
    synchronized void lees() {
        for (Volg v : volgers) {
            try { leesBestand(v); } catch (Exception e) {
                plugin.getLogger().warning("Fout bij het lezen van " + v.bestand.getName() + ": " + e);
            }
        }
        sluitOpen();
    }

    private void leesBestand(Volg v) throws Exception {
        if (!v.bestand.exists()) { v.positie = 0; return; }
        long lengte = v.bestand.length();
        if (v.positie < 0) v.positie = lengte;
        if (lengte < v.positie) { v.positie = 0; v.rest.setLength(0); } // nieuw bestand (bijv. na herstart)
        if (lengte == v.positie) return;
        long max = Math.min(lengte - v.positie, 1024 * 1024);
        byte[] buf = new byte[(int) max];
        try (RandomAccessFile raf = new RandomAccessFile(v.bestand, "r")) {
            raf.seek(v.positie);
            raf.readFully(buf);
        }
        v.positie += max;
        v.rest.append(new String(buf, StandardCharsets.UTF_8));
        int nl;
        while ((nl = v.rest.indexOf("\n")) >= 0) {
            String regel = v.rest.substring(0, nl).replace("\r", "");
            v.rest.delete(0, nl + 1);
            if (regel.isBlank()) continue;
            try { verwerk(v, regel); } catch (Exception e) {
                plugin.getLogger().warning("Regel uit " + v.bestand.getName() + " overgeslagen: " + e);
            }
        }
        if (v.rest.length() > 64 * 1024) v.rest.setLength(0);
    }

    private void verwerk(Volg v, String regel) {
        switch (v.soort) {
            case "serverlog" -> serverlog(regel);
            case "ordely" -> ordely(v.categorie, regel);
            default -> {
                if (regel.startsWith("#")) return;
                String r = regel.length() > 500 ? regel.substring(0, 500) + "\u2026" : regel;
                plugin.uitvoer().meld(v.categorie, new Uitvoer.Regel("`" + r.replace("`", "'") + "`"));
            }
        }
    }

    private void serverlog(String regel) {
        Matcher m = SERVERLOG.matcher(regel);
        if (m.matches()) {
            sluitOpen();
            String tekst = m.group(4);
            if (tekst.contains("[Meldly]")) return;
            for (Pattern p : negeren) if (p.matcher(tekst).find()) return;
            open = new StringBuilder(tekst);
            openNiveau = m.group(3);
            openRegels = 0;
            return;
        }
        if (NIEUWE_REGEL.matcher(regel).find()) { sluitOpen(); return; }
        if (open != null) {
            if (openRegels < 8) open.append('\n').append(regel);
            else if (openRegels == 8) open.append("\n\u2026");
            openRegels++;
        }
    }

    private void sluitOpen() {
        if (open == null) return;
        String tekst = open.toString();
        String niveau = openNiveau;
        open = null;
        long nu = System.currentTimeMillis();
        if (nu - minuutStart > 60_000) {
            if (overgeslagen > 0) plugin.uitvoer().meld(Categorie.FOUTEN, new Uitvoer.Regel("_(" + overgeslagen + " meldingen overgeslagen, het waren er te veel)_"));
            minuutStart = nu; ditMinuut = 0; overgeslagen = 0;
        }
        if (++ditMinuut > perMinuut) { overgeslagen++; return; }
        String icoon = niveau.equals("WARN") ? "\uD83D\uDFE0" : "\uD83D\uDD34";
        if (tekst.length() > 1500) tekst = tekst.substring(0, 1500) + "\u2026";
        String blok = tekst.contains("\n") ? icoon + " **" + niveau + "**\n```\n" + tekst.replace("```", "'''") + "\n```"
                : icoon + " **" + niveau + "** " + Bot.md(tekst);
        plugin.uitvoer().meld(Categorie.FOUTEN, new Uitvoer.Regel(blok));
    }

    private void ordely(Categorie c, String regel) {
        JsonObject o;
        try { o = JsonParser.parseString(regel).getAsJsonObject(); } catch (Exception e) { return; }
        String actie = s(o, "actie"), speler = s(o, "speler"), reden = s(o, "reden"), door = s(o, "door");
        String icoon = switch (actie) {
            case "OPGESLOTEN" -> "\uD83D\uDD12";
            case "VRIJGELATEN" -> "\uD83D\uDD13";
            case "WAARSCHUWING" -> "\u26A0\uFE0F";
            case "CORRECTIE" -> "\uD83D\uDCB8";
            case "AANGEPAST" -> "\u270F\uFE0F";
            default -> "\uD83D\uDEE1\uFE0F";
        };
        String wat = switch (actie) {
            case "OPGESLOTEN" -> "is opgesloten in de gevangenis";
            case "VRIJGELATEN" -> "is vrijgelaten";
            case "WAARSCHUWING" -> "kreeg een waarschuwing";
            case "CORRECTIE" -> "kreeg een geld-correctie";
            case "AANGEPAST" -> "kreeg een aangepaste straf";
            default -> actie.toLowerCase();
        };
        StringBuilder t = new StringBuilder(icoon + " **" + Bot.md(speler) + "** " + wat);
        if (o.has("minuten") && actie.equals("OPGESLOTEN")) {
            long min = o.get("minuten").getAsLong();
            t.append(min <= 0 ? " (tot vrijlating)" : " (" + (min >= 60 && min % 60 == 0 ? (min / 60) + " uur" : min + " min") + ")");
        }
        if (!reden.isBlank()) t.append("\n**Reden:** ").append(Bot.md(reden));
        if (!door.isBlank()) t.append("\n**Door:** ").append(Bot.md(door));
        Integer kleur = null;
        if (actie.equals("VRIJGELATEN")) kleur = 0x7DDC4A;
        else if (actie.equals("WAARSCHUWING")) kleur = 0xF2C14E;
        String uuid = s(o, "uuid");
        java.util.UUID u = null;
        try { if (!uuid.isBlank()) u = java.util.UUID.fromString(uuid); } catch (IllegalArgumentException ignored) { }
        plugin.uitvoer().meld(c, new Uitvoer.Regel(t.toString(), speler, plugin.hoofd(speler, u), kleur));
    }

    private static String s(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}
