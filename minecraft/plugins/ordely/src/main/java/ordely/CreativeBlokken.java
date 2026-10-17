package ordely;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Blokken die in creative zijn neergezet en mobs uit creative-spawneieren of /summon
 * leveren in survival niets op (geen drops), zodat er via creative geen geld te verdienen is.
 * Blokken worden onthouden in de chunk zelf (PersistentDataContainer), dus ook na een herstart.
 */
final class CreativeBlokken implements Listener {

    private final Ordely plugin;
    private final NamespacedKey sleutelBlokken;
    private final NamespacedKey sleutelEntiteit;

    CreativeBlokken(Ordely plugin) {
        this.plugin = plugin;
        this.sleutelBlokken = new NamespacedKey(plugin, "creative_blokken");
        this.sleutelEntiteit = new NamespacedKey(plugin, "creative");
    }

    private boolean blokkenAan() { return plugin.getConfig().getBoolean("creative.aan", true) && plugin.getConfig().getBoolean("creative.geplaatste-blokken-geven-niets", true); }
    private boolean mobsAan() { return plugin.getConfig().getBoolean("creative.aan", true) && plugin.getConfig().getBoolean("creative.gespawnde-mobs-geven-niets", true); }

    // ------------------------------------------------------------ opslag in de chunk
    private static int pak(Block b) { return ((b.getY() + 2048) << 8) | ((b.getX() & 15) << 4) | (b.getZ() & 15); }

    private int[] lees(Chunk c) {
        int[] a = c.getPersistentDataContainer().get(sleutelBlokken, PersistentDataType.INTEGER_ARRAY);
        return a == null ? new int[0] : a;
    }

    boolean gemarkeerd(Block b) {
        if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) return false;
        int[] a = lees(b.getChunk());
        if (a.length == 0) return false;
        int k = pak(b);
        for (int v : a) if (v == k) return true;
        return false;
    }

    void markeer(Block b) {
        Chunk c = b.getChunk();
        int[] a = lees(c);
        int k = pak(b);
        for (int v : a) if (v == k) return;
        int[] n = Arrays.copyOf(a, a.length + 1);
        n[a.length] = k;
        c.getPersistentDataContainer().set(sleutelBlokken, PersistentDataType.INTEGER_ARRAY, n);
    }

    void wis(Block b) {
        Chunk c = b.getChunk();
        int[] a = lees(c);
        if (a.length == 0) return;
        int k = pak(b);
        int[] n = Arrays.stream(a).filter(v -> v != k).toArray();
        if (n.length == a.length) return;
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        if (n.length == 0) pdc.remove(sleutelBlokken); else pdc.set(sleutelBlokken, PersistentDataType.INTEGER_ARRAY, n);
    }

    /** Het blok zelf of de andere helft (deur, bed, hoge plant). */
    private Block[] metAndereHelft(Block b) {
        BlockData d = b.getBlockData();
        if (d instanceof Bed bed) {
            BlockFace f = bed.getPart() == Bed.Part.HEAD ? bed.getFacing().getOppositeFace() : bed.getFacing();
            return new Block[]{b, b.getRelative(f)};
        }
        if (d instanceof Bisected bi) {
            return new Block[]{b, b.getRelative(bi.getHalf() == Bisected.Half.TOP ? BlockFace.DOWN : BlockFace.UP)};
        }
        return new Block[]{b};
    }

    private boolean ergensGemarkeerd(Block b) {
        for (Block x : metAndereHelft(b)) if (gemarkeerd(x)) return true;
        return false;
    }

    private void wisAlles(Block b) { for (Block x : metAndereHelft(b)) wis(x); }

    // ------------------------------------------------------------ plaatsen en slopen
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opPlaats(BlockPlaceEvent e) {
        if (!blokkenAan() || !plugin.regels().creativeBeperkt(e.getPlayer())) return;
        if (e instanceof BlockMultiPlaceEvent mp) {
            for (BlockState s : mp.getReplacedBlockStates()) markeer(s.getBlock());
        } else {
            markeer(e.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opSloop(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!ergensGemarkeerd(b)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.CREATIVE) {
            e.setDropItems(false);
            e.setExpToDrop(0);
        }
        wisAlles(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opExplosie(EntityExplodeEvent e) { explosie(e.blockList()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opBlokExplosie(BlockExplodeEvent e) { explosie(e.blockList()); }

    private void explosie(List<Block> blokken) {
        Iterator<Block> it = blokken.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (gemarkeerd(b)) {
                it.remove();
                wis(b);
                b.setType(Material.AIR, false);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opZuigerUit(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (gemarkeerd(b)) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opZuigerIn(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (gemarkeerd(b)) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opStroming(BlockFromToEvent e) {
        Block to = e.getToBlock();
        if (!to.getType().isAir() && gemarkeerd(to)) {
            wis(to);
            to.setType(Material.AIR, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opBladeren(LeavesDecayEvent e) {
        if (gemarkeerd(e.getBlock())) {
            e.setCancelled(true);
            wis(e.getBlock());
            e.getBlock().setType(Material.AIR, false);
        }
    }

    /** Zand/grind dat in creative is neergezet en valt, blijft gemarkeerd. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opVallen(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb)) return;
        Block b = e.getBlock();
        if (e.getTo().isAir()) {
            if (gemarkeerd(b)) {
                wis(b);
                fb.getPersistentDataContainer().set(sleutelEntiteit, PersistentDataType.BYTE, (byte) 1);
            }
        } else if (fb.getPersistentDataContainer().has(sleutelEntiteit)) {
            Bukkit.getScheduler().runTask(plugin, () -> markeer(b));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opValDrop(EntityDropItemEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(sleutelEntiteit)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ mobs
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opSpawn(CreatureSpawnEvent e) {
        if (!mobsAan()) return;
        LivingEntity ent = e.getEntity();
        switch (e.getSpawnReason()) {
            case COMMAND -> markeer(ent);
            case SPAWNER_EGG, DISPENSE_EGG -> {
                Location l = ent.getLocation();
                for (Player p : l.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(l) <= 64 && plugin.regels().creativeBeperkt(p)) { markeer(ent); break; }
                }
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opSpawner(SpawnerSpawnEvent e) {
        if (mobsAan() && e.getSpawner() != null && gemarkeerd(e.getSpawner().getBlock())) markeer(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opFokken(EntityBreedEvent e) {
        if (gemarkeerd(e.getMother()) || gemarkeerd(e.getFather())) markeer(e.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void opDood(EntityDeathEvent e) {
        if (!gemarkeerd(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opScheren(PlayerShearEntityEvent e) {
        if (gemarkeerd(e.getEntity())) e.setCancelled(true);
    }

    private void markeer(Entity e) { e.getPersistentDataContainer().set(sleutelEntiteit, PersistentDataType.BYTE, (byte) 1); }
    private boolean gemarkeerd(Entity e) { return e != null && e.getPersistentDataContainer().has(sleutelEntiteit); }
}
