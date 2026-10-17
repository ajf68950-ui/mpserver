package ordely;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Regels: geven alleen via de website, niets uit creative in de economie, schelden, en automatische straffen. */
final class Regels implements Listener {

    private final Ordely plugin;

    private final List<String[]> geblokkeerdGeven = new ArrayList<>();
    private boolean blokkeerItemsInCommando;
    private boolean blokkeerKluisVanAnderen;
    private boolean creativeAan;
    private final Set<String> vrijeWerelden = new HashSet<>();
    private final Set<String> creativeGeblokkeerd = new HashSet<>();
    private final Set<String> verkoopCommandos = new HashSet<>();
    private boolean geenItemsWegleggen;
    private final Set<String> gevangenenToegestaan = new HashSet<>();
    private volatile Pattern scheldPatroon;
    private final Set<String> vrijgesteld = new HashSet<>();

    /** speler -> regel -> tijdstippen van overtredingen */
    private final Map<UUID, Map<String, Deque<Long>>> overtredingen = new ConcurrentHashMap<>();

    private static final Set<String> KLUIS_SUB = Set.of("kopen", "sorteer", "naam", "help", "info", "upgrade");

    Regels(Ordely plugin) {
        this.plugin = plugin;
        laad();
    }

    void laad() {
        var c = plugin.getConfig();
        geblokkeerdGeven.clear();
        for (String s : c.getStringList("geven.geblokkeerde-commandos")) {
            String[] p = s.trim().toLowerCase(Locale.ROOT).split("\\s+");
            if (p.length > 0 && !p[0].isEmpty()) geblokkeerdGeven.add(p);
        }
        blokkeerItemsInCommando = c.getBoolean("geven.blokkeer-items-in-commandos", true);
        blokkeerKluisVanAnderen = c.getBoolean("geven.blokkeer-kluis-van-anderen", true);
        creativeAan = c.getBoolean("creative.aan", true);
        vrijeWerelden.clear();
        c.getStringList("creative.vrije-werelden").forEach(w -> vrijeWerelden.add(w.toLowerCase(Locale.ROOT)));
        creativeGeblokkeerd.clear();
        c.getStringList("creative.geblokkeerde-commandos").forEach(w -> creativeGeblokkeerd.add(w.toLowerCase(Locale.ROOT)));
        verkoopCommandos.clear();
        c.getStringList("creative.verkoop-commandos").forEach(w -> verkoopCommandos.add(w.toLowerCase(Locale.ROOT)));
        geenItemsWegleggen = c.getBoolean("creative.geen-items-wegleggen", true);
        gevangenenToegestaan.clear();
        c.getStringList("gevangenis.toegestane-commandos").forEach(w -> gevangenenToegestaan.add(w.toLowerCase(Locale.ROOT)));
        List<String> woorden = c.getStringList("automatisch.schelden.woorden");
        if (woorden.isEmpty()) scheldPatroon = null;
        else {
            try { scheldPatroon = Pattern.compile("(?iu)(?<![\\p{L}\\d])(" + String.join("|", woorden) + ")(?![\\p{L}\\d])"); }
            catch (Exception e) { plugin.getLogger().warning("Ongeldig scheldwoord in config.yml: " + e.getMessage()); scheldPatroon = null; }
        }
        vrijgesteld.clear();
        c.getStringList("automatisch.vrijgesteld").forEach(w -> vrijgesteld.add(w.toLowerCase(Locale.ROOT)));
    }

    // ------------------------------------------------------------ hulp
    boolean creativeBeperkt(Player p) {
        if (!creativeAan) return false;
        GameMode gm = p.getGameMode();
        if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) return false;
        String w = p.getWorld().getName().toLowerCase(Locale.ROOT);
        if (vrijeWerelden.contains(w)) return false;
        return !w.equals(plugin.gevangenis().wereldNaam().toLowerCase(Locale.ROOT));
    }

    private static String zonderNamespace(String s) {
        int i = s.indexOf(':');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** Alle namen waaronder dit commando bekend is: wat getypt is, de echte naam en de aliassen. */
    private static Set<String> namen(String label) {
        Set<String> n = new HashSet<>();
        n.add(zonderNamespace(label));
        Command cmd = Bukkit.getCommandMap().getCommand(label);
        if (cmd != null) {
            n.add(zonderNamespace(cmd.getName().toLowerCase(Locale.ROOT)));
            String lbl = cmd.getLabel();
            if (lbl != null) n.add(zonderNamespace(lbl.toLowerCase(Locale.ROOT)));
        }
        return n;
    }

    // ------------------------------------------------------------ commando's
    @EventHandler(priority = EventPriority.LOWEST)
    public void opCommando(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String raw = e.getMessage();
        if (raw.length() < 2 || raw.charAt(0) != '/') return;
        String[] delen = raw.substring(1).trim().split("\\s+");
        if (delen.length == 0 || delen[0].isEmpty()) return;
        String label = delen[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(delen, 1, delen.length);
        Set<String> namen = namen(label);
        String kort = raw.length() > 80 ? raw.substring(0, 80) + "..." : raw;

        // 1. Gevangenen: alleen een paar commando's
        if (plugin.gevangenis().zitVast(p.getUniqueId())) {
            boolean ok = namen.stream().anyMatch(gevangenenToegestaan::contains);
            if (ok && namen.contains("gevangenis") && args.length > 0 && !args[0].equalsIgnoreCase("info")) ok = false;
            if (!ok) {
                e.setCancelled(true);
                Ordely.zeg(p, "Dat kan niet in de gevangenis. Je mag alleen /msg, /r en /gevangenis info gebruiken.", net.kyori.adventure.text.format.NamedTextColor.RED);
            }
            return;
        }

        // 2. Geld en items geven kan alleen via de website
        if (verbodenGeven(namen, args, raw)) {
            e.setCancelled(true);
            Ordely.regel(p, "Geld en items geven kan alleen via de website (Beheer). Dit commando is in het spel geblokkeerd.");
            overtreding(p, "verboden-geven", "probeerde " + kort + " (geld/items geven kan alleen via de website)");
            return;
        }

        // 3. Creative: niet verkopen en niets in de kluis
        if (creativeBeperkt(p) && namen.stream().anyMatch(creativeGeblokkeerd::contains)) {
            e.setCancelled(true);
            if (namen.stream().anyMatch(verkoopCommandos::contains)) {
                Ordely.regel(p, "Verkopen kan niet in creative of als toeschouwer. Ga eerst naar survival.");
                overtreding(p, "creative-verkopen", "probeerde te verkopen in " + p.getGameMode().name().toLowerCase(Locale.ROOT) + " (" + kort + ")");
            } else {
                Ordely.regel(p, "Dit werkt niet in creative of als toeschouwer. Ga eerst naar survival.");
            }
        }
    }

    private boolean verbodenGeven(Set<String> namen, String[] args, String raw) {
        for (String[] r : geblokkeerdGeven) {
            if (!namen.contains(r[0])) continue;
            if (r.length == 1) return true;
            if (args.length > 0 && zonderNamespace(args[0].toLowerCase(Locale.ROOT)).equals(r[1])) return true;
        }
        if (blokkeerItemsInCommando) {
            String l = raw.toLowerCase(Locale.ROOT).replace(" ", "");
            if (l.contains("items:[") || l.contains("item:{") || l.contains("\"items\":") || l.contains("container=[")) return true;
        }
        if (blokkeerKluisVanAnderen && (namen.contains("kluis") || namen.contains("vaulty") || namen.contains("vault")) && args.length > 0) {
            String a = args[0].toLowerCase(Locale.ROOT);
            if (!KLUIS_SUB.contains(a) && !a.matches("\\d+")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ overtredingen
    /** Telt een overtreding; na genoeg waarschuwingen gaat de speler automatisch de gevangenis in. Altijd op de hoofdthread aanroepen. */
    void overtreding(Player p, String regel, String wat) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("automatisch." + regel);
        if (c == null || !c.getBoolean("aan", true)) return;
        if (plugin.gevangenis().zitVast(p.getUniqueId())) return;
        int waarschuwingen = Math.max(0, c.getInt("waarschuwingen", 1));
        long minuten = Math.max(1, c.getLong("minuten", 15));
        long venster = Math.max(1, plugin.getConfig().getLong("automatisch.vergeet-na-uren", 24)) * 3600_000L;
        long nu = System.currentTimeMillis();
        Deque<Long> d = overtredingen.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(regel, k -> new ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && nu - d.peekFirst() > venster) d.pollFirst();
            d.addLast(nu);
            int n = d.size();
            boolean vrij = vrijgesteld.contains(p.getName().toLowerCase(Locale.ROOT)) || vrijgesteld.contains(p.getUniqueId().toString());
            if (n <= waarschuwingen || vrij) {
                plugin.logboek().schrijf("WAARSCHUWING", p.getName(), p.getUniqueId(), wat, -1, "systeem", regel);
                if (vrij) Ordely.regel(p, "Waarschuwing: dit mag niet.");
                else Ordely.regel(p, "Waarschuwing " + n + " van " + waarschuwingen + ". Nog een keer en je gaat " + minuten + " minuten de gevangenis in.");
                return;
            }
            d.clear();
        }
        plugin.gevangenis().stuur(p, minuten, "Automatisch: " + wat, "systeem (" + regel + ")");
    }

    // ------------------------------------------------------------ chat
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Pattern pat = scheldPatroon;
        if (pat != null && plugin.getConfig().getBoolean("automatisch.schelden.aan", true)) {
            String tekst = PlainTextComponentSerializer.plainText().serialize(e.message());
            String genormaliseerd = tekst.toLowerCase(Locale.ROOT)
                    .replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('@', 'a').replace('$', 's');
            if (pat.matcher(tekst).find() || pat.matcher(genormaliseerd).find()) {
                e.setCancelled(true);
                String kort = tekst.length() > 60 ? tekst.substring(0, 60) + "..." : tekst;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    Ordely.regel(p, "Let op je taal. Je bericht is niet verstuurd.");
                    overtreding(p, "schelden", "schelden in de chat: \"" + kort + "\"");
                });
                return;
            }
        }
        if (!plugin.getConfig().getBoolean("gevangenis.chat-apart", true)) return;
        Gevangenis g = plugin.gevangenis();
        if (g.zitVast(p.getUniqueId())) {
            e.viewers().removeIf(a -> a instanceof Player q && !g.zitVast(q.getUniqueId()) && !q.hasPermission("ordely.beheer"));
        } else {
            e.viewers().removeIf(a -> a instanceof Player q && g.zitVast(q.getUniqueId()));
        }
    }

    // ------------------------------------------------------------ creative: items niet wegleggen
    private boolean wegleggenBeperkt(Player p) { return geenItemsWegleggen && creativeBeperkt(p); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opDrop(PlayerDropItemEvent e) {
        if (!wegleggenBeperkt(e.getPlayer())) return;
        e.setCancelled(true);
        Ordely.regel(e.getPlayer(), "In creative kun je geen items weggooien.");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opCreativeMenu(InventoryCreativeEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !wegleggenBeperkt(p)) return;
        if (e.getSlotType() == InventoryType.SlotType.OUTSIDE) e.setCancelled(true);
    }

    private static boolean isOpslag(Inventory inv) {
        if (inv == null) return false;
        InventoryType t = inv.getType();
        if (t == InventoryType.MERCHANT || t == InventoryType.ENDER_CHEST) return true;
        InventoryHolder h = inv.getHolder(false);
        if (h instanceof Player) return false;
        return h instanceof BlockInventoryHolder || h instanceof BlockState || h instanceof DoubleChest || h instanceof Entity;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opKlik(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !wegleggenBeperkt(p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!isOpslag(top)) return;
        boolean inTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);
        if (inTop || e.isShiftClick() || e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            Ordely.regel(p, "In creative kun je geen items in of uit kisten, ovens of handelaren halen.");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opSlepen(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !wegleggenBeperkt(p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!isOpslag(top)) return;
        int size = top.getSize();
        if (e.getRawSlots().stream().anyMatch(s -> s < size)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opEntiteit(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!wegleggenBeperkt(p)) return;
        Entity t = e.getRightClicked();
        if (t instanceof ItemFrame || t instanceof Allay) {
            e.setCancelled(true);
            Ordely.regel(p, "In creative kun je hier geen items in doen.");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opStandaard(PlayerArmorStandManipulateEvent e) {
        if (!wegleggenBeperkt(e.getPlayer())) return;
        e.setCancelled(true);
        Ordely.regel(e.getPlayer(), "In creative kun je geen items op een standaard zetten.");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void opBlokKlik(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (!wegleggenBeperkt(p)) return;
        ItemStack hand = e.getItem();
        if (hand == null || hand.getType().isAir()) return;
        Material m = e.getClickedBlock().getType();
        String n = m.name();
        boolean houder = n.equals("DECORATED_POT") || n.equals("CHISELED_BOOKSHELF") || n.equals("JUKEBOX") || n.equals("LECTERN")
                || n.equals("FLOWER_POT") || n.equals("COMPOSTER") || n.endsWith("CAMPFIRE") || n.equals("VAULT")
                || n.endsWith("_SHELF") || n.equals("SPAWNER") || n.equals("TRIAL_SPAWNER");
        if (houder) {
            e.setCancelled(true);
            Ordely.regel(p, "In creative kun je hier geen items in doen.");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opPlaatsMetInhoud(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!wegleggenBeperkt(p)) return;
        ItemStack it = e.getItemInHand();
        if (it.getItemMeta() instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof Container c && !c.getInventory().isEmpty()) {
            e.setCancelled(true);
            Ordely.regel(p, "In creative kun je geen kisten of shulkers met inhoud neerzetten.");
        }
    }

    // gebruikt door Commando voor /gevangenis stuur met een offline speler
    static OfflinePlayer zoekSpeler(String naam) {
        Player online = Bukkit.getPlayerExact(naam);
        if (online != null) return online;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(naam);
        if (op != null) return op;
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) if (o.getName() != null && o.getName().equalsIgnoreCase(naam)) return o;
        try { return Bukkit.getOfflinePlayer(UUID.fromString(naam)); } catch (IllegalArgumentException ignored) { }
        return null;
    }

}
