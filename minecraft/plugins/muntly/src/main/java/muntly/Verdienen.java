package muntly;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Munten verdienen met speeltijd (niet als je AFK bent) en een dagelijkse beloning met reeks. */
final class Verdienen implements Listener {

    private final Muntly plugin;
    private final Map<UUID, Long> actief = new ConcurrentHashMap<>();

    Verdienen(Muntly plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::elkeMinuut, 1200L, 1200L);
        long nu = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) actief.put(p.getUniqueId(), nu);
    }

    private void bezig(Player p) { actief.put(p.getUniqueId(), System.currentTimeMillis()); }

    boolean isAfk(Player p) {
        long afk = Math.max(1, plugin.getConfig().getLong("verdienen.speeltijd.afk-na-minuten", 5)) * 60_000L;
        Long t = actief.get(p.getUniqueId());
        return t == null || System.currentTimeMillis() - t > afk;
    }

    // ----------------------------------------------------------- activiteit
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if (f.getBlockX() != t.getBlockX() || f.getBlockZ() != t.getBlockZ() || f.getBlockY() != t.getBlockY()
                || Math.abs(f.getYaw() - t.getYaw()) > 1f || Math.abs(f.getPitch() - t.getPitch()) > 1f) bezig(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) { bezig(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) { bezig(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) { bezig(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { bezig(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { bezig(e.getPlayer()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        bezig(p);
        plugin.saldi().get(p.getUniqueId(), p.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) dagbeloning(p); }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { actief.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------- beloningen
    private void elkeMinuut() {
        boolean speeltijd = plugin.getConfig().getBoolean("verdienen.speeltijd.aan", true);
        int interval = Math.max(1, plugin.getConfig().getInt("verdienen.speeltijd.minuten", 30));
        long munten = Math.max(0, plugin.getConfig().getLong("verdienen.speeltijd.munten", 2));
        List<String> nietIn = plugin.getConfig().getStringList("verdienen.speeltijd.niet-in-werelden");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isAfk(p)) continue;
            dagbeloning(p); // voor wie over middernacht heen speelt
            if (!speeltijd || munten <= 0) continue;
            if (nietIn.stream().anyMatch(w -> w.equalsIgnoreCase(p.getWorld().getName()))) continue;
            Saldi.Speler sp = plugin.saldi().get(p.getUniqueId(), p.getName());
            sp.minuten++;
            plugin.saldi().touch();
            if (sp.minuten >= interval) {
                sp.minuten = 0;
                plugin.saldi().add(sp, munten, "speeltijd " + interval + " min");
                p.sendMessage(plugin.bericht("&a+" + plugin.munten(munten) + " &7voor " + interval + " minuten spelen. Saldo: " + plugin.kleur() + plugin.munten(sp.saldo)));
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
            }
        }
    }

    /** Minuten tot de volgende speeltijdbeloning. */
    int minutenTotVolgende(Saldi.Speler sp) {
        int interval = Math.max(1, plugin.getConfig().getInt("verdienen.speeltijd.minuten", 30));
        return Math.max(0, interval - sp.minuten);
    }

    /** Beloning voor dag n van de reeks (n >= 1). */
    long beloningVoorDag(int n) {
        List<Long> list = plugin.getConfig().getLongList("verdienen.dagelijks.beloningen");
        if (list.isEmpty()) return 0;
        return list.get(Math.min(Math.max(1, n), list.size()) - 1);
    }

    void dagbeloning(Player p) {
        if (!plugin.getConfig().getBoolean("verdienen.dagelijks.aan", true)) return;
        Saldi.Speler sp = plugin.saldi().get(p.getUniqueId(), p.getName());
        LocalDate vandaag = LocalDate.now();
        String v = vandaag.toString();
        if (v.equals(sp.laatsteDag)) return;
        int reeks = vandaag.minusDays(1).toString().equals(sp.laatsteDag) ? sp.reeks + 1 : 1;
        sp.laatsteDag = v;
        sp.reeks = reeks;
        long munten = beloningVoorDag(reeks);
        plugin.saldi().touch();
        if (munten <= 0) return;
        plugin.saldi().add(sp, munten, "dagbeloning dag " + reeks);
        p.sendMessage(plugin.bericht("&6Dagelijkse beloning: &a+" + plugin.munten(munten)
                + (reeks > 1 ? " &7(" + reeks + " dagen op rij)" : "") + "&7. Morgen weer inloggen: &e+" + plugin.munten(beloningVoorDag(reeks + 1)) + "&7."));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
    }
}
