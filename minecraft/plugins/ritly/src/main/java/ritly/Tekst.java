package ritly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.DyeColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Teksten en kleuren. */
final class Tekst {

    static final String PREFIX = "&8[&6Rit&8] &7";
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private Tekst() {}

    static Component kleur(String s) {
        return AMP.deserialize(s == null ? "" : s).decoration(TextDecoration.ITALIC, false);
    }

    static List<Component> regels(String... r) {
        List<Component> l = new ArrayList<>();
        for (String s : r) if (s != null) l.add(kleur(s));
        return l;
    }

    static void stuur(CommandSender s, String bericht) {
        s.sendMessage(kleur(PREFIX + bericht));
    }

    static String zonderKleur(String s) {
        return s == null ? "" : s.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    static String geld(double bedrag) {
        return "$" + String.format(Locale.forLanguageTag("nl-NL"), "%,.0f", bedrag);
    }

    static String getal(double d, int dec) {
        return String.format(Locale.forLanguageTag("nl-NL"), "%." + dec + "f", d);
    }

    private static final Map<DyeColor, String> KLEUREN = Map.ofEntries(
            Map.entry(DyeColor.WHITE, "Wit"), Map.entry(DyeColor.ORANGE, "Oranje"),
            Map.entry(DyeColor.MAGENTA, "Magenta"), Map.entry(DyeColor.LIGHT_BLUE, "Lichtblauw"),
            Map.entry(DyeColor.YELLOW, "Geel"), Map.entry(DyeColor.LIME, "Limoen"),
            Map.entry(DyeColor.PINK, "Roze"), Map.entry(DyeColor.GRAY, "Grijs"),
            Map.entry(DyeColor.LIGHT_GRAY, "Lichtgrijs"), Map.entry(DyeColor.CYAN, "Cyaan"),
            Map.entry(DyeColor.PURPLE, "Paars"), Map.entry(DyeColor.BLUE, "Blauw"),
            Map.entry(DyeColor.BROWN, "Bruin"), Map.entry(DyeColor.GREEN, "Groen"),
            Map.entry(DyeColor.RED, "Rood"), Map.entry(DyeColor.BLACK, "Zwart"));

    static String kleurNaam(DyeColor c) {
        return c == null ? "Standaard" : KLEUREN.getOrDefault(c, c.name().toLowerCase(Locale.ROOT));
    }

    static DyeColor dye(String s) {
        if (s == null) return null;
        try {
            return DyeColor.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            for (Map.Entry<DyeColor, String> en : KLEUREN.entrySet())
                if (en.getValue().equalsIgnoreCase(s.trim())) return en.getKey();
            return null;
        }
    }
}
