package meldly;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.advancement.AdvancementDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Wat er in het spel gebeurt, naar de juiste categorie. */
final class Gebeurtenissen implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Set<String> MODERATIE = Set.of("op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "tempban", "tempbanip",
            "mute", "unmute", "tempmute", "jail", "unjail", "whitelist", "banip", "unban", "unbanip", "warn", "gevangenis", "eco", "economy",
            "gamemode", "gm", "gmc", "gms", "gmsp", "gma", "vanish", "v", "invsee", "openinv", "openender", "sudo");

    private final Meldly plugin;
    private final Map<UUID, Long> ingelogd = new ConcurrentHashMap<>();

    Gebeurtenissen(Meldly plugin) { this.plugin = plugin; }

    private static String plat(Component c) { return c == null ? "" : PLAIN.serialize(c); }

    private boolean onzichtbaar(Player p) {
        return p.getMetadata("vanished").stream().anyMatch(m -> m.asBoolean());
    }

    private Uitvoer.Regel regel(Player p, String tekst) { return regel(p, tekst, null); }
    private Uitvoer.Regel regel(Player p, String tekst, Integer kleur) {
        return new Uitvoer.Regel(tekst, p.getName(), plugin.hoofd(p.getName(), p.getUniqueId()), kleur);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void opServerKlaar(ServerLoadEvent e) {
        plugin.serverKlaar();
    }

    // ----------------------------------------------------------------- chat
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("chat.minecraft-naar-discord", true)) return;
        List<String> geen = plugin.getConfig().getStringList("chat.niet-uit-werelden");
        if (geen.stream().anyMatch(w -> w.equalsIgnoreCase(p.getWorld().getName()))) return;
        if (onzichtbaar(p) && !plugin.getConfig().getBoolean("chat.ook-onzichtbaar", false)) return;
        String tekst = plat(e.message()).trim();
        if (tekst.isEmpty()) return;
        if (tekst.length() > 1900) tekst = tekst.substring(0, 1900);
        String naam = p.getName();
        String prefix = plugin.prefix(p);
        if (!prefix.isEmpty()) naam = prefix + " " + naam;
        plugin.bot().chatNaarDiscord(naam, plugin.hoofd(p.getName(), p.getUniqueId()), Bot.md(tekst), null);
    }

    // ------------------------------------------------------- in- en uitloggen
    @EventHandler(priority = EventPriority.MONITOR)
    public void opJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        ingelogd.put(p.getUniqueId(), System.currentTimeMillis());
        plugin.bot().bijInloggen(p);
        plugin.snapshotLater();
        if (e.joinMessage() == null && onzichtbaar(p)) return;
        if (onzichtbaar(p)) return;
        if (!p.hasPlayedBefore()) {
            plugin.uitvoer().meld(Categorie.SPELERS, regel(p, "🎉 **" + Bot.md(p.getName()) + "** is voor het eerst op de server. Welkom!", 0xF2C14E));
        } else {
            plugin.uitvoer().meld(Categorie.SPELERS, regel(p, "➡️ **" + Bot.md(p.getName()) + "** kwam online", 0x7DDC4A));
        }
        if (plugin.getConfig().getBoolean("chat.in-uit-in-chat", false))
            plugin.bot().chatNaarDiscordSysteem("➡️ **" + Bot.md(p.getName()) + "** kwam online");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void opQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Long sinds = ingelogd.remove(p.getUniqueId());
        plugin.snapshotLater();
        if (onzichtbaar(p)) return;
        String duur = sinds == null ? "" : " (speelde " + Status.duur(System.currentTimeMillis() - sinds) + ")";
        plugin.uitvoer().meld(Categorie.SPELERS, regel(p, "⬅️ **" + Bot.md(p.getName()) + "** ging offline" + duur, 0xE0503C));
        if (plugin.getConfig().getBoolean("chat.in-uit-in-chat", false))
            plugin.bot().chatNaarDiscordSysteem("⬅️ **" + Bot.md(p.getName()) + "** ging offline");
    }

    // ------------------------------------------------------------------ doden
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opDood(PlayerDeathEvent e) {
        Player p = e.getEntity();
        String bericht = plat(e.deathMessage());
        if (bericht.isBlank() || onzichtbaar(p)) return;
        String wereld = p.getWorld().getName();
        plugin.uitvoer().meld(Categorie.DODEN, regel(p, "💀 " + Bot.md(bericht) + "\n-# wereld " + Bot.md(wereld)
                + " • " + p.getLocation().getBlockX() + ", " + p.getLocation().getBlockY() + ", " + p.getLocation().getBlockZ()));
    }

    // -------------------------------------------------------------- prestaties
    @EventHandler(priority = EventPriority.MONITOR)
    public void opPrestatie(PlayerAdvancementDoneEvent e) {
        AdvancementDisplay d = e.getAdvancement().getDisplay();
        if (d == null || !d.doesAnnounceToChat() || onzichtbaar(e.getPlayer())) return;
        String soort = switch (d.frame()) {
            case CHALLENGE -> "voltooide de uitdaging";
            case GOAL -> "bereikte het doel";
            default -> "haalde de prestatie";
        };
        String titel = plat(d.title()), uitleg = plat(d.description());
        plugin.uitvoer().meld(Categorie.PRESTATIES, regel(e.getPlayer(), "🏆 **" + Bot.md(e.getPlayer().getName()) + "** " + soort + " **[" + Bot.md(titel) + "]**"
                + (uitleg.isBlank() ? "" : "\n-# " + Bot.md(uitleg)), d.frame() == AdvancementDisplay.Frame.CHALLENGE ? 0xA05EB5 : null));
    }

    // ------------------------------------------------------------- commando's
    private static String eersteWoord(String cmd) {
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        String w = c.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int i = w.indexOf(':');
        return i >= 0 ? w.substring(i + 1) : w;
    }

    private boolean genegeerd(String woord, String pad) {
        Set<String> s = new HashSet<>();
        for (String x : plugin.getConfig().getStringList(pad)) s.add(x.toLowerCase(Locale.ROOT));
        return s.contains(woord);
    }

    private void commando(String wie, String icoon, String cmd, boolean geblokkeerd, Player p) {
        String woord = eersteWoord(cmd);
        if (genegeerd(woord, "commandos.negeren")) return;
        String c = cmd.startsWith("/") ? cmd : "/" + cmd;
        if (c.length() > 300) c = c.substring(0, 300) + "…";
        String tekst = icoon + " **" + Bot.md(wie) + "** `" + c.replace("`", "'") + "`" + (geblokkeerd ? " _(geblokkeerd)_" : "");
        plugin.uitvoer().meld(Categorie.COMMANDOS, new Uitvoer.Regel(tekst));
        if (MODERATIE.contains(woord) || plugin.getConfig().getStringList("commandos.ook-naar-moderatie").contains(woord)) {
            Uitvoer.Regel r = p == null ? new Uitvoer.Regel("🛡️ **" + Bot.md(wie) + "** gebruikte `" + c.replace("`", "'") + "`")
                    : regel(p, "🛡️ gebruikte `" + c.replace("`", "'") + "`" + (geblokkeerd ? " _(geblokkeerd)_" : ""));
            plugin.uitvoer().meld(Categorie.MODERATIE, r);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void opCommando(PlayerCommandPreprocessEvent e) {
        if (onzichtbaar(e.getPlayer()) && !plugin.getConfig().getBoolean("commandos.ook-onzichtbaar", true)) return;
        commando(e.getPlayer().getName(), "👤", e.getMessage(), e.isCancelled(), e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void opRcon(RemoteServerCommandEvent e) {
        if (genegeerd(eersteWoord(e.getCommand()), "commandos.rcon-negeren")) return;
        commando("Website/RCON", "\uD83C\uDF10", e.getCommand(), e.isCancelled(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void opConsole(ServerCommandEvent e) {
        if (e instanceof RemoteServerCommandEvent) return;
        if (!plugin.getConfig().getBoolean("commandos.console", true)) return;
        commando("Console", "🖥️", e.getCommand(), e.isCancelled(), null);
    }

    // ---------------------------------------------------------------- moderatie
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opKick(PlayerKickEvent e) {
        String reden = plat(e.reason());
        String oorzaak = switch (e.getCause()) {
            case BANNED, IP_BANNED -> "🔨 is verbannen";
            case WHITELIST -> null;
            case PLUGIN -> "👢 is van de server gezet";
            case KICKED -> "👢 is gekickt";
            default -> null;
        };
        if (oorzaak == null || org.bukkit.Bukkit.isStopping()) return;
        plugin.uitvoer().meld(Categorie.MODERATIE, regel(e.getPlayer(), oorzaak.substring(0, 2) + " **" + Bot.md(e.getPlayer().getName()) + "** " + oorzaak.substring(3)
                + (reden.isBlank() ? "" : "\n**Reden:** " + Bot.md(reden)), 0xE0503C));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opSpelmodus(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (e.getCause() == PlayerGameModeChangeEvent.Cause.PLUGIN && !plugin.getConfig().getBoolean("moderatie.spelmodus-door-plugins", false)) return;
        String oud = p.getGameMode().name().toLowerCase(Locale.ROOT), nieuw = e.getNewGameMode().name().toLowerCase(Locale.ROOT);
        plugin.uitvoer().meld(Categorie.MODERATIE, regel(p, "🎮 **" + Bot.md(p.getName()) + "** ging van " + oud + " naar **" + nieuw + "**"
                + " • " + Bot.md(p.getWorld().getName())));
    }
}
