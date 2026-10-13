package vaulty;

import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Koppelcodes: de website vraagt een code aan voor een naam, de speler typt /koppel <code>. Alleen op de main thread. */
final class LinkManager {
    enum Result { OK, UNKNOWN, EXPIRED, WRONG_NAME }

    static final class Code {
        final String nameLower;
        final long expires;
        UUID uuid;
        String name;
        Code(String nameLower, long expires) { this.nameLower = nameLower; this.expires = expires; }
    }

    private final Vaulty plugin;
    private final Map<String, Code> codes = new HashMap<>();
    private final SecureRandom rnd = new SecureRandom();

    LinkManager(Vaulty plugin) { this.plugin = plugin; }

    String create(String name) {
        cleanup();
        long minutes = Math.max(1, plugin.getConfig().getLong("koppelen.geldig-minuten", 10));
        String code;
        do { code = String.valueOf(100000 + rnd.nextInt(900000)); } while (codes.containsKey(code));
        codes.put(code, new Code(name.toLowerCase(Locale.ROOT), System.currentTimeMillis() + minutes * 60_000L));
        return code;
    }

    Code get(String code) {
        cleanup();
        return codes.get(code);
    }

    Result confirm(String code, Player p) {
        Code c = codes.get(code.trim());
        if (c == null) return Result.UNKNOWN;
        if (c.expires < System.currentTimeMillis()) { codes.remove(code); return Result.EXPIRED; }
        if (!c.nameLower.equals(p.getName().toLowerCase(Locale.ROOT))) return Result.WRONG_NAME;
        c.uuid = p.getUniqueId();
        c.name = p.getName();
        return Result.OK;
    }

    void consume(String code) { codes.remove(code); }

    private void cleanup() {
        long now = System.currentTimeMillis();
        for (Iterator<Code> it = codes.values().iterator(); it.hasNext(); ) {
            // na verloop nog 10 minuten bewaren zodat de website de uitkomst kan ophalen
            if (it.next().expires + 600_000L < now) it.remove();
        }
    }
}
