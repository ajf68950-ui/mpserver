package muntly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.DisplayNameNode;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.WeightNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Rangen (LuckPerms-groepen) voor de website: lezen, zetten en de groepen zelf instellen.
 * Werkt via de LuckPerms-API, ook voor spelers die offline zijn. Niet op de main thread aanroepen:
 * de methodes wachten op LuckPerms (opslag).
 * Alleen gebruiken als LuckPerms aanstaat (anders bestaat deze klasse niet).
 */
final class Rangen {
    static final Pattern GROEP = Pattern.compile("[a-z0-9_\\-]{1,36}");
    static final Pattern RECHT = Pattern.compile("[A-Za-z0-9_.*\\-]{1,120}");
    static final Pattern WERELD = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");
    private static final long WACHT = 8;

    private final Muntly plugin;

    Rangen(Muntly plugin) { this.plugin = plugin; }

    private static LuckPerms lp() { return LuckPermsProvider.get(); }

    private static User laad(UUID uuid) throws Exception {
        return lp().getUserManager().loadUser(uuid).get(WACHT, TimeUnit.SECONDS);
    }

    private static boolean vast(Node n) { return n.getContexts().isEmpty() && !n.hasExpiry() && n.getValue(); }

    /** De groepen waar een speler direct in zit (overal geldig, niet tijdelijk). */
    List<String> groepen(UUID uuid) throws Exception {
        User u = laad(uuid);
        List<String> out = new ArrayList<>();
        for (InheritanceNode n : u.getNodes(NodeType.INHERITANCE)) if (vast(n)) out.add(n.getGroupName());
        out.sort(String::compareTo);
        return out;
    }

    /** Hoogste rang uit de ladder die de speler heeft, of -1. */
    static int hoogste(List<String> groepen, List<String> ladder) {
        int best = -1;
        for (String g : groepen) best = Math.max(best, ladder.indexOf(g));
        return best;
    }

    /** Geeft de speler rang {@code doel} en haalt de andere rangen uit de ladder weg. doel null = geen rang. */
    void zet(UUID uuid, String doel, List<String> ladder) throws Exception {
        User u = laad(uuid);
        for (InheritanceNode n : u.getNodes(NodeType.INHERITANCE)) {
            if (n.getContexts().isEmpty() && ladder.contains(n.getGroupName()) && !n.getGroupName().equals(doel)) u.data().remove(n);
        }
        if (doel != null) u.data().add(InheritanceNode.builder(doel).build());
        lp().getUserManager().saveUser(u).get(WACHT, TimeUnit.SECONDS);
    }

    /** Bestaat deze groep in LuckPerms? */
    boolean bestaat(String groep) throws Exception {
        return lp().getGroupManager().loadGroup(groep).get(WACHT, TimeUnit.SECONDS).isPresent();
    }

    /**
     * Groepen instellen vanuit het beheer. Per groep: naam, prefix, gewicht, ouder (alleen bij rangen)
     * en rechten. Rechten die niet meer in de lijst staan maar vorige keer wel, komen in "weg".
     * Andere rechten (bijv. uit de LuckPerms-editor) blijven staan.
     */
    JsonObject instellen(JsonObject b) throws Exception {
        if (!b.has("groepen") || !b.get("groepen").isJsonArray()) return fout("Geen groepen meegestuurd.");
        int groepen = 0, rechten = 0, weg = 0;
        List<String> meldingen = new ArrayList<>();
        // eerst alle groepen aanmaken, zodat ouders bestaan
        for (JsonElement e : b.getAsJsonArray("groepen")) {
            String id = tekst(e.getAsJsonObject(), "id");
            if (!GROEP.matcher(id).matches()) return fout("Ongeldige groepsnaam: " + id + " (alleen kleine letters, cijfers, - en _).");
            lp().getGroupManager().createAndLoadGroup(id).get(WACHT, TimeUnit.SECONDS);
        }
        for (JsonElement e : b.getAsJsonArray("groepen")) {
            JsonObject g = e.getAsJsonObject();
            String id = tekst(g, "id");
            Group group = lp().getGroupManager().createAndLoadGroup(id).get(WACHT, TimeUnit.SECONDS);
            boolean isDefault = id.equals("default");
            if (!isDefault) {
                String naam = tekst(g, "naam");
                group.data().clear(NodeType.DISPLAY_NAME::matches);
                if (!naam.isEmpty()) group.data().add(DisplayNameNode.builder(naam).build());
                int gewicht = g.has("gewicht") ? g.get("gewicht").getAsInt() : 0;
                group.data().clear(NodeType.WEIGHT::matches);
                if (gewicht > 0) group.data().add(WeightNode.builder(gewicht).build());
                String prefix = tekst(g, "prefix");
                group.data().clear(NodeType.PREFIX::matches);
                if (!prefix.isEmpty()) group.data().add(PrefixNode.builder(prefix, Math.max(1, gewicht)).build());
                String ouder = tekst(g, "ouder");
                group.data().clear(n -> n instanceof InheritanceNode && n.getContexts().isEmpty());
                if (!ouder.isEmpty()) {
                    if (!GROEP.matcher(ouder).matches()) return fout("Ongeldige ouder bij " + id + ": " + ouder);
                    group.data().add(InheritanceNode.builder(ouder).build());
                }
            }
            if (g.has("weg") && g.get("weg").isJsonArray()) {
                for (JsonElement r : g.getAsJsonArray("weg")) {
                    Recht x = recht(r.getAsJsonObject());
                    if (x == null) continue;
                    group.data().clear(n -> !(n instanceof InheritanceNode) && n.getKey().equalsIgnoreCase(x.node) && n.getContexts().equals(x.ctx));
                    weg++;
                }
            }
            if (g.has("rechten") && g.get("rechten").isJsonArray()) {
                for (JsonElement r : g.getAsJsonArray("rechten")) {
                    Recht x = recht(r.getAsJsonObject());
                    if (x == null) { meldingen.add("Overgeslagen bij " + id + ": " + r); continue; }
                    group.data().clear(n -> !(n instanceof InheritanceNode) && n.getKey().equalsIgnoreCase(x.node) && n.getContexts().equals(x.ctx));
                    group.data().add(Node.builder(x.node).value(x.waarde).context(x.ctx).build());
                    rechten++;
                }
            }
            lp().getGroupManager().saveGroup(group).get(WACHT, TimeUnit.SECONDS);
            groepen++;
        }
        lp().runUpdateTask().get(WACHT * 2, TimeUnit.SECONDS);
        plugin.getLogger().info("Rangen ingesteld via de website: " + groepen + " groepen, " + rechten + " rechten, " + weg + " weggehaald.");
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("groepen", groepen);
        o.addProperty("rechten", rechten);
        o.addProperty("weg", weg);
        JsonArray m = new JsonArray();
        meldingen.forEach(m::add);
        o.add("meldingen", m);
        return o;
    }

    private record Recht(String node, boolean waarde, ImmutableContextSet ctx) { }

    private static Recht recht(JsonObject r) {
        String node = tekst(r, "node");
        if (!RECHT.matcher(node).matches() || node.toLowerCase().startsWith("group.")) return null;
        boolean waarde = !r.has("waarde") || r.get("waarde").getAsBoolean();
        String wereld = tekst(r, "wereld");
        ImmutableContextSet ctx;
        if (wereld.isEmpty()) ctx = ImmutableContextSet.empty();
        else if (WERELD.matcher(wereld).matches()) ctx = ImmutableContextSet.of("world", wereld.toLowerCase());
        else return null;
        return new Recht(node.toLowerCase(), waarde, ctx);
    }

    private static String tekst(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : "";
    }

    private static JsonObject fout(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }
}
