package pocketly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pocketly: drops van blokken en mobs gaan automatisch in de inventaris van de speler,
 * alleen in de ingestelde werelden (skyblock en OneBlock). Per speler aan/uit met /pickup.
 */
public final class Pocketly extends JavaPlugin implements Listener, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Set<String> worlds = new HashSet<>();
    /** Spelers die het zelf anders hebben ingesteld dan standaard-aan: true = aan, false = uit. */
    private final Map<UUID, Boolean> choices = new HashMap<>();
    private final Map<UUID, Long> fullWarned = new HashMap<>();
    private final Map<UUID, Long> lastSound = new HashMap<>();

    private boolean defaultOn, blocks, mobs, xpDirect, extraCatch, sound, fullMessage;
    private File playersFile;
    private NamespacedKey skipKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        skipKey = new NamespacedKey(this, "laten-liggen");
        playersFile = new File(getDataFolder(), "spelers.yml");
        loadSettings();
        loadPlayers();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("pickup");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // Werelden worden door Multiverse/BentoBox soms pas na ons geladen: even wachten met controleren.
        Bukkit.getScheduler().runTaskLater(this, this::checkWorlds, 100L);
    }

    @Override
    public void onDisable() {
        savePlayers();
    }

    // ------------------------------------------------------------------ instellingen

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        worlds.clear();
        for (String w : c.getStringList("werelden")) worlds.add(w.trim());
        defaultOn = c.getBoolean("standaard-aan", true);
        blocks = c.getBoolean("blokken", true);
        mobs = c.getBoolean("mobs", true);
        xpDirect = c.getBoolean("xp-direct", true);
        extraCatch = c.getBoolean("extra-opvang", true);
        sound = c.getBoolean("geluid", true);
        fullMessage = c.getBoolean("vol-melding", true);
    }

    private void checkWorlds() {
        List<String> missing = new ArrayList<>();
        for (String w : worlds) if (Bukkit.getWorld(w) == null) missing.add(w);
        List<String> active = new ArrayList<>(worlds);
        active.removeAll(missing);
        getLogger().info("Auto-pickup actief in: " + (active.isEmpty() ? "(geen)" : String.join(", ", active)));
        if (!missing.isEmpty()) {
            List<String> all = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) all.add(w.getName());
            getLogger().info("Niet (nog) geladen werelden uit config.yml: " + String.join(", ", missing)
                    + ". Bestaande werelden: " + String.join(", ", all));
        }
    }

    private void loadPlayers() {
        choices.clear();
        if (!playersFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(playersFile);
        for (String key : y.getKeys(false)) {
            try {
                choices.put(UUID.fromString(key), y.getBoolean(key));
            } catch (IllegalArgumentException ignored) {
                // geen geldige uuid, overslaan
            }
        }
    }

    private void savePlayers() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Pocketly: per speler aan (true) of uit (false). Wordt door de plugin bijgehouden."));
        for (var e : choices.entrySet()) y.set(e.getKey().toString(), e.getValue());
        try {
            getDataFolder().mkdirs();
            y.save(playersFile);
        } catch (IOException ex) {
            getLogger().warning("Kon spelers.yml niet opslaan: " + ex.getMessage());
        }
    }

    private boolean isOn(Player p) {
        return choices.getOrDefault(p.getUniqueId(), defaultOn);
    }

    private void setOn(Player p, boolean on) {
        choices.put(p.getUniqueId(), on);
        savePlayers();
    }

    private boolean active(Player p, World w) {
        return p != null && p.isOnline() && w != null && worlds.contains(w.getName())
                && p.getGameMode() != GameMode.SPECTATOR && isOn(p);
    }

    // ------------------------------------------------------------------ oppakken

    /** Stopt de stack in de inventaris; geeft terug wat er niet meer in paste (of null). */
    private ItemStack give(Player p, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) return null;
        Map<Integer, ItemStack> rest = p.getInventory().addItem(stack.clone());
        if (rest.isEmpty()) return null;
        ItemStack left = null;
        for (ItemStack r : rest.values()) {
            if (left == null) left = r;
            else left.setAmount(left.getAmount() + r.getAmount());
        }
        return left;
    }

    private void feedback(Player p, boolean pickedUp, boolean full) {
        long now = System.currentTimeMillis();
        if (pickedUp && sound) {
            Long last = lastSound.get(p.getUniqueId());
            if (last == null || now - last > 150) {
                lastSound.put(p.getUniqueId(), now);
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.25f, 1.6f);
            }
        }
        if (full && fullMessage) {
            Long last = fullWarned.get(p.getUniqueId());
            if (last == null || now - last > 3000) {
                fullWarned.put(p.getUniqueId(), now);
                p.sendActionBar(msg("vol"));
            }
        }
    }

    private void markLeftOnGround(Item item) {
        item.getPersistentDataContainer().set(skipKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** Gewone blok-drops: komen direct uit het spel, nog voordat ze op de grond vallen. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent e) {
        if (!blocks) return;
        Player p = e.getPlayer();
        if (!active(p, e.getBlock().getWorld())) return;
        boolean picked = false, full = false;
        Iterator<Item> it = e.getItems().iterator();
        while (it.hasNext()) {
            Item item = it.next();
            ItemStack left = give(p, item.getItemStack());
            if (left == null) {
                it.remove();
                picked = true;
            } else {
                if (left.getAmount() < item.getItemStack().getAmount()) picked = true;
                item.setItemStack(left);
                markLeftOnGround(item);
                full = true;
            }
        }
        feedback(p, picked, full);
    }

    /** XP van blokken direct naar de speler, en extra opvang voor drops die plugins zelf laten vallen. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!blocks || !active(p, e.getBlock().getWorld())) return;
        if (xpDirect && e.getExpToDrop() > 0) {
            int xp = e.getExpToDrop();
            e.setExpToDrop(0);
            p.giveExp(xp, true);
        }
        if (extraCatch) {
            Location center = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
            UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTaskLater(this, () -> sweep(id, center), 1L);
        }
    }

    /**
     * Pakt verse item-drops (net gespawnd, door niemand gegooid) vlak bij het gebroken blok op.
     * Zo komen ook drops binnen die een plugin zelf liet vallen (bijv. OneBlock-loot of drops die
     * naar boven het blok zijn verplaatst).
     */
    private void sweep(UUID playerId, Location center) {
        Player p = Bukkit.getPlayer(playerId);
        if (!active(p, center.getWorld())) return;
        boolean picked = false, full = false;
        for (Entity ent : center.getWorld().getNearbyEntities(center, 1.5, 2.0, 1.5)) {
            if (!(ent instanceof Item item) || !item.isValid()) continue;
            if (item.getTicksLived() > 3) continue;
            if (item.getThrower() != null) continue;
            if (item.getPersistentDataContainer().has(skipKey)) continue;
            ItemStack stack = item.getItemStack();
            ItemStack left = give(p, stack);
            if (left == null) {
                item.remove();
                picked = true;
            } else {
                if (left.getAmount() < stack.getAmount()) picked = true;
                item.setItemStack(left);
                markLeftOnGround(item);
                full = true;
            }
        }
        feedback(p, picked, full);
    }

    /** Mob-drops en mob-XP van mobs die de speler heeft gedood. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent e) {
        if (!mobs) return;
        LivingEntity dead = e.getEntity();
        if (dead instanceof Player) return; // spelersdood: spullen blijven gewoon liggen
        Player p = dead.getKiller();
        if (!active(p, dead.getWorld())) return;
        boolean picked = false, full = false;
        List<ItemStack> drops = e.getDrops();
        List<ItemStack> keep = new ArrayList<>();
        for (ItemStack stack : drops) {
            ItemStack left = give(p, stack);
            if (left == null) {
                picked = true;
            } else {
                if (left.getAmount() < stack.getAmount()) picked = true;
                keep.add(left);
                full = true;
            }
        }
        drops.clear();
        drops.addAll(keep);
        if (xpDirect && e.getDroppedExp() > 0) {
            int xp = e.getDroppedExp();
            e.setDroppedExp(0);
            p.giveExp(xp, true);
        }
        feedback(p, picked, full);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        fullWarned.remove(e.getPlayer().getUniqueId());
        lastSound.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ command

    private Component msg(String key) {
        return LEGACY.deserialize(getConfig().getString("berichten." + key, key));
    }

    private Component msg(String key, String player) {
        return LEGACY.deserialize(getConfig().getString("berichten." + key, key).replace("{speler}", player));
    }

    private void sendState(Player p, boolean on, boolean changed) {
        p.sendMessage(msg(changed ? (on ? "aan" : "uit") : (on ? "status-aan" : "status-uit")));
        if (on && !worlds.contains(p.getWorld().getName())) p.sendMessage(msg("alleen-hier"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = sender.hasPermission("pocketly.admin");

        if (sub.equals("reload")) {
            if (!admin) { sender.sendMessage(msg("geen-recht")); return true; }
            loadSettings();
            loadPlayers();
            checkWorlds();
            sender.sendMessage(msg("herladen"));
            return true;
        }

        if (sub.equals("help") || sub.equals("?")) {
            sender.sendMessage(msg("gebruik"));
            if (admin) sender.sendMessage(msg("gebruik-admin"));
            return true;
        }

        Boolean want = switch (sub) {
            case "aan", "on" -> Boolean.TRUE;
            case "uit", "off" -> Boolean.FALSE;
            default -> null;
        };

        // Voor een andere speler (admin of console)
        if (want != null && args.length >= 2) {
            if (!admin) { sender.sendMessage(msg("geen-recht")); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { sender.sendMessage(msg("niet-gevonden", args[1])); return true; }
            setOn(target, want);
            sender.sendMessage(msg(want ? "ander-aan" : "ander-uit", target.getName()));
            if (target != sender) sendState(target, want, true);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(msg("gebruik-admin"));
            return true;
        }

        switch (sub) {
            case "" -> { boolean on = !isOn(p); setOn(p, on); sendState(p, on, true); }
            case "aan", "on", "uit", "off" -> { setOn(p, want); sendState(p, want, true); }
            case "status" -> sendState(p, isOn(p), false);
            default -> {
                p.sendMessage(msg("gebruik"));
                if (admin) p.sendMessage(msg("gebruik-admin"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("aan", "uit", "status"));
            if (sender.hasPermission("pocketly.admin")) opts.add("reload");
            for (String o : opts) if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(o);
        } else if (args.length == 2 && sender.hasPermission("pocketly.admin")
                && (args[0].equalsIgnoreCase("aan") || args[0].equalsIgnoreCase("uit"))) {
            for (Player pl : Bukkit.getOnlinePlayers())
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(pl.getName());
        }
        return out;
    }
}
