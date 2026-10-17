package ordely;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /gevangenis info | stuur <speler> <minuten> <reden> | vrij <speler> [reden] | lijst | log [aantal] | tp | herbouw | herladen
 * De website gebruikt dit via RCON en geeft dan --door=Naam mee.
 */
final class Commando implements TabExecutor {

    private final Ordely plugin;

    Commando(Ordely plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("info") && !s.hasPermission("ordely.beheer")) {
            Ordely.zeg(s, "Dat mag alleen een beheerder.", NamedTextColor.RED);
            return true;
        }
        // --door=... (alleen voor console/RCON, de website zet hier de beheerder en het IP in)
        String door = s instanceof Player p ? p.getName() : s.getName();
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].toLowerCase(Locale.ROOT).startsWith("--door=")) {
                if (!(s instanceof Player)) door = args[i].substring(7).replace('_', ' ');
            } else rest.add(args[i]);
        }
        Gevangenis gev = plugin.gevangenis();
        switch (sub) {
            case "info" -> {
                if (rest.isEmpty() && s instanceof Player p) {
                    Gevangenis.Gevangene g = gev.get(p.getUniqueId());
                    if (g == null || !gev.zitVast(p.getUniqueId())) Ordely.zeg(s, "Je zit niet in de gevangenis.", NamedTextColor.GREEN);
                    else toon(s, g);
                } else if (!rest.isEmpty() && s.hasPermission("ordely.beheer")) {
                    OfflinePlayer op = Regels.zoekSpeler(rest.get(0));
                    Gevangenis.Gevangene g = op == null ? null : gev.get(op.getUniqueId());
                    if (g == null) Ordely.zeg(s, rest.get(0) + " zit niet in de gevangenis.", NamedTextColor.GREEN);
                    else toon(s, g);
                } else {
                    hulp(s);
                }
            }
            case "stuur" -> {
                if (rest.size() < 3) { Ordely.zeg(s, "Gebruik: /gevangenis stuur <speler> <minuten (0 = onbepaald)> <reden>", NamedTextColor.RED); return true; }
                OfflinePlayer op = Regels.zoekSpeler(rest.get(0));
                if (op == null) { Ordely.zeg(s, "Speler " + rest.get(0) + " is niet bekend op deze server.", NamedTextColor.RED); return true; }
                long min;
                try { min = Long.parseLong(rest.get(1)); } catch (NumberFormatException e) { Ordely.zeg(s, "Vul het aantal minuten in als getal (0 = onbepaalde tijd).", NamedTextColor.RED); return true; }
                if (min < 0 || min > 525_600) { Ordely.zeg(s, "Kies 0 tot 525600 minuten.", NamedTextColor.RED); return true; }
                String reden = String.join(" ", rest.subList(2, rest.size())).trim();
                if (reden.length() < 3) { Ordely.zeg(s, "Geef een reden op (minstens 3 tekens).", NamedTextColor.RED); return true; }
                if (reden.length() > 300) reden = reden.substring(0, 300);
                String fout = gev.stuur(op, min, reden, door);
                if (fout != null) Ordely.zeg(s, fout, NamedTextColor.RED);
                else Ordely.zeg(s, "OK: " + (op.getName() == null ? rest.get(0) : op.getName()) + " zit in de gevangenis (" + (min > 0 ? min + " minuten" : "onbepaalde tijd") + ")" + (op.isOnline() ? "." : ". Hij is offline en gaat erin zodra hij inlogt."), NamedTextColor.GREEN);
            }
            case "vrij" -> {
                if (rest.isEmpty()) { Ordely.zeg(s, "Gebruik: /gevangenis vrij <speler> [reden]", NamedTextColor.RED); return true; }
                OfflinePlayer op = Regels.zoekSpeler(rest.get(0));
                if (op == null) { Ordely.zeg(s, "Speler " + rest.get(0) + " is niet bekend op deze server.", NamedTextColor.RED); return true; }
                String reden = rest.size() > 1 ? String.join(" ", rest.subList(1, rest.size())).trim() : "Vrijgelaten door beheer";
                Gevangenis.Gevangene voor = gev.get(op.getUniqueId());
                boolean nogNietBinnen = voor != null && "wacht".equals(voor.status);
                String fout = gev.vrij(op.getUniqueId(), reden, door);
                if (fout != null) Ordely.zeg(s, fout, NamedTextColor.RED);
                else if (nogNietBinnen) Ordely.zeg(s, "OK: " + op.getName() + " is vrijgelaten (hij was nog niet binnen geweest).", NamedTextColor.GREEN);
                else Ordely.zeg(s, "OK: " + op.getName() + " is vrijgelaten" + (op.isOnline() ? "." : " en krijgt zijn spullen terug zodra hij inlogt."), NamedTextColor.GREEN);
            }
            case "lijst" -> {
                var l = gev.alle();
                if (l.isEmpty()) { Ordely.zeg(s, "De gevangenis is leeg.", NamedTextColor.GREEN); return true; }
                Ordely.zeg(s, l.size() + " gevangene(n):", NamedTextColor.YELLOW);
                for (Gevangenis.Gevangene g : l) toon(s, g);
            }
            case "log" -> {
                int n = 10;
                if (!rest.isEmpty()) try { n = Math.max(1, Math.min(100, Integer.parseInt(rest.get(0)))); } catch (NumberFormatException ignored) { }
                var log = Logboek.omgekeerd(plugin.logboek().laatste(n));
                if (log.isEmpty()) { Ordely.zeg(s, "Het logboek is nog leeg.", NamedTextColor.GRAY); return true; }
                for (JsonObject o : log) {
                    s.sendMessage(Ordely.PREFIX.append(net.kyori.adventure.text.Component.text(
                            str(o, "tijd") + " " + str(o, "actie") + " " + str(o, "speler")
                                    + (o.has("minuten") ? " (" + (o.get("minuten").getAsLong() == 0 ? "onbepaald" : o.get("minuten").getAsLong() + " min") + ")" : "")
                                    + " door " + str(o, "door") + ": " + str(o, "reden"), NamedTextColor.GRAY)));
                }
            }
            case "tp" -> {
                if (!(s instanceof Player p)) { Ordely.zeg(s, "Alleen in het spel.", NamedTextColor.RED); return true; }
                gev.bezoek(p);
            }
            case "herbouw" -> {
                gev.bouw(true);
                Ordely.zeg(s, "De gevangenis is opnieuw gebouwd.", NamedTextColor.GREEN);
            }
            case "herladen" -> {
                plugin.herlaad();
                Ordely.zeg(s, "Ordely-instellingen herladen.", NamedTextColor.GREEN);
            }
            default -> hulp(s);
        }
        return true;
    }

    private void toon(CommandSender s, Gevangenis.Gevangene g) {
        long over = plugin.gevangenis().resterend(g);
        String status = switch (g.status) { case "wacht" -> " (nog offline)"; case "vrij-wacht" -> " (vrij, krijgt spullen terug)"; default -> ""; };
        Ordely.zeg(s, g.naam + status + " - cel " + g.cel + " - " + (over < 0 ? "tot vrijlating" : "nog " + Ordely.duur(over)) + " - reden: " + g.reden + " - door " + g.door, NamedTextColor.YELLOW);
    }

    private void hulp(CommandSender s) {
        Ordely.zeg(s, "/gevangenis info - je eigen straf", NamedTextColor.GRAY);
        if (!s.hasPermission("ordely.beheer")) return;
        Ordely.zeg(s, "/gevangenis stuur <speler> <minuten> <reden> - opsluiten (0 minuten = onbepaald)", NamedTextColor.GRAY);
        Ordely.zeg(s, "/gevangenis vrij <speler> [reden] - vrijlaten", NamedTextColor.GRAY);
        Ordely.zeg(s, "/gevangenis lijst | log [aantal] | tp | herbouw | herladen", NamedTextColor.GRAY);
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> subs = s.hasPermission("ordely.beheer") ? List.of("info", "stuur", "vrij", "lijst", "log", "tp", "herbouw", "herladen") : List.of("info");
            return subs.stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && s.hasPermission("ordely.beheer") && Arrays.asList("stuur", "vrij", "info").contains(args[0].toLowerCase(Locale.ROOT))) {
            if (args[0].equalsIgnoreCase("vrij")) return plugin.gevangenis().alle().stream().map(g -> g.naam).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("stuur")) return List.of("15", "30", "60", "1440", "0");
        return List.of();
    }
}
