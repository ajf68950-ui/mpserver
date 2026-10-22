package ritly;

import org.bukkit.DyeColor;

import java.util.Locale;

/** Iets wat je in een winkel kunt kopen: een voertuig, onderdeel, lak of brandstof. */
final class Product {

    enum Type { VOERTUIG, ONDERDEEL, LAK, BRANDSTOF }

    static final String[] ONDERDELEN = {"motor", "stuur", "tank"};

    final Type type;
    final String waarde;   // model-id, onderdeel (motor/stuur/tank), kleur of liters
    final int niveau;      // onderdeel: 1..3; brandstof: liters

    private Product(Type type, String waarde, int niveau) {
        this.type = type;
        this.waarde = waarde;
        this.niveau = niveau;
    }

    static Product lees(String s) {
        if (s == null) return null;
        String[] t = s.trim().toLowerCase(Locale.ROOT).split(":");
        try {
            switch (t[0]) {
                case "voertuig":
                    return t.length >= 2 ? new Product(Type.VOERTUIG, t[1], 0) : null;
                case "onderdeel":
                    if (t.length < 3) return null;
                    for (String o : ONDERDELEN)
                        if (o.equals(t[1])) return new Product(Type.ONDERDEEL, o, Math.max(1, Math.min(3, Integer.parseInt(t[2]))));
                    return null;
                case "lak":
                    DyeColor k = t.length >= 2 ? Tekst.dye(t[1]) : null;
                    return k == null ? null : new Product(Type.LAK, k.name().toLowerCase(Locale.ROOT), 0);
                case "brandstof":
                    int l = t.length >= 2 ? Integer.parseInt(t[1]) : 20;
                    return new Product(Type.BRANDSTOF, String.valueOf(Math.max(1, l)), Math.max(1, l));
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    String sleutel() {
        return switch (type) {
            case VOERTUIG -> "voertuig:" + waarde;
            case ONDERDEEL -> "onderdeel:" + waarde + ":" + niveau;
            case LAK -> "lak:" + waarde;
            case BRANDSTOF -> "brandstof:" + niveau;
        };
    }

    DyeColor kleur() {
        return type == Type.LAK ? Tekst.dye(waarde) : null;
    }

    static String onderdeelNaam(String o) {
        return switch (o) {
            case "motor" -> "Motor";
            case "stuur" -> "Stuur";
            case "tank" -> "Tank";
            default -> o;
        };
    }
}
