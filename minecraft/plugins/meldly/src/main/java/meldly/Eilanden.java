package meldly;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Op welk eiland zit een speler? SuperiorSkyblock2 (skyblock) en BentoBox (OneBlock), via reflectie,
 * zodat Meldly ook werkt als die plugins er niet zijn. Alleen op de hoofdthread aanroepen.
 */
final class Eilanden {
    record Eiland(String sleutel, String naam) {}

    private boolean ssKlaar = false, bbKlaar = false;
    private Method ssGetPlayer, ssGetIsland, ssIslandId, ssIslandNaam, ssIslandEigenaar, ssPlayerNaam;
    private Method bbInstance, bbIslands, bbIwm, bbInWorld, bbGetIsland, bbGetIslandsUuid, bbId, bbNaam, bbEigenaar;

    Eiland van(Player p) {
        World w = p.getWorld();
        try {
            if (bentoBox() && (boolean) bbInWorld.invoke(bbIwm.invoke(bbInstance.invoke(null)), w)) {
                Eiland e = bbEiland(bbGetIsland.invoke(bbIslands.invoke(bbInstance.invoke(null)), w, p.getUniqueId()));
                if (e != null) return e;
            }
        } catch (Throwable ignored) { }
        try {
            if (superior()) {
                Object sp = ssGetPlayer.invoke(null, p.getUniqueId());
                Object island = sp == null ? null : ssGetIsland.invoke(sp);
                if (island != null) {
                    String naam = (String) ssIslandNaam.invoke(island);
                    if (naam == null || naam.isBlank()) {
                        Object eig = ssIslandEigenaar.invoke(island);
                        naam = "Eiland van " + (eig == null ? p.getName() : ssPlayerNaam.invoke(eig));
                    }
                    return new Eiland("ss2:" + ssIslandId.invoke(island), naam);
                }
            }
        } catch (Throwable ignored) { }
        try {
            if (bentoBox()) {
                Object lijst = bbGetIslandsUuid.invoke(bbIslands.invoke(bbInstance.invoke(null)), p.getUniqueId());
                if (lijst instanceof List<?> l && !l.isEmpty()) return bbEiland(l.get(0));
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private Eiland bbEiland(Object island) throws Exception {
        if (island == null) return null;
        String naam = (String) bbNaam.invoke(island);
        if (naam == null || naam.isBlank()) {
            UUID eig = (UUID) bbEigenaar.invoke(island);
            OfflinePlayer op = eig == null ? null : Bukkit.getOfflinePlayer(eig);
            naam = "Eiland van " + (op == null || op.getName() == null ? "?" : op.getName());
        }
        return new Eiland("bb:" + bbId.invoke(island), naam);
    }

    private static ClassLoader lader(String plugin) {
        Plugin pl = Bukkit.getPluginManager().getPlugin(plugin);
        return pl == null || !pl.isEnabled() ? null : pl.getClass().getClassLoader();
    }

    private boolean superior() {
        if (ssKlaar) return ssGetPlayer != null;
        ClassLoader cl = lader("SuperiorSkyblock2");
        if (cl == null) return false;
        ssKlaar = true;
        try {
            Class<?> api = Class.forName("com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI", true, cl);
            Class<?> sp = Class.forName("com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer", true, cl);
            Class<?> is = Class.forName("com.bgsoftware.superiorskyblock.api.island.Island", true, cl);
            ssGetIsland = sp.getMethod("getIsland");
            ssPlayerNaam = sp.getMethod("getName");
            ssIslandId = is.getMethod("getUniqueId");
            ssIslandNaam = is.getMethod("getName");
            ssIslandEigenaar = is.getMethod("getOwner");
            ssGetPlayer = api.getMethod("getPlayer", UUID.class);
        } catch (Throwable t) {
            ssGetPlayer = null;
            Bukkit.getLogger().warning("[Meldly] Eilanden van SuperiorSkyblock2 kunnen niet gelezen worden: " + t);
        }
        return ssGetPlayer != null;
    }

    private boolean bentoBox() {
        if (bbKlaar) return bbInstance != null;
        ClassLoader cl = lader("BentoBox");
        if (cl == null) return false;
        bbKlaar = true;
        try {
            Class<?> bb = Class.forName("world.bentobox.bentobox.BentoBox", true, cl);
            Class<?> im = Class.forName("world.bentobox.bentobox.managers.IslandsManager", true, cl);
            Class<?> iwm = Class.forName("world.bentobox.bentobox.managers.IslandWorldManager", true, cl);
            Class<?> is = Class.forName("world.bentobox.bentobox.database.objects.Island", true, cl);
            bbIslands = bb.getMethod("getIslands");
            bbIwm = bb.getMethod("getIWM");
            bbInWorld = iwm.getMethod("inWorld", World.class);
            bbGetIsland = im.getMethod("getIsland", World.class, UUID.class);
            bbGetIslandsUuid = im.getMethod("getIslands", UUID.class);
            bbId = is.getMethod("getUniqueId");
            bbNaam = is.getMethod("getName");
            bbEigenaar = is.getMethod("getOwner");
            bbInstance = bb.getMethod("getInstance");
        } catch (Throwable t) {
            bbInstance = null;
            Bukkit.getLogger().warning("[Meldly] Eilanden van BentoBox kunnen niet gelezen worden: " + t);
        }
        return bbInstance != null;
    }
}
