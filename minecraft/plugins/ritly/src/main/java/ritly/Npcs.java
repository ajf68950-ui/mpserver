package ritly;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Eigen winkel-NPC's: een mob die niet beweegt; klikken opent de winkel. */
final class Npcs implements Listener {

    private final Ritly p;
    private final Set<UUID> geladen = new HashSet<>();

    Npcs(Ritly p) {
        this.p = p;
    }

    void start() {
        for (World w : Bukkit.getWorlds())
            for (LivingEntity le : w.getLivingEntities()) if (winkelVan(le) != null) geladen.add(le.getUniqueId());
        Bukkit.getScheduler().runTaskTimer(p, this::kijk, 40L, 10L);
    }

    String winkelVan(Entity e) {
        return e == null ? null : e.getPersistentDataContainer().get(p.sleutelNpc, PersistentDataType.STRING);
    }

    String maak(Player pl, String winkel, String type, String naam) {
        EntityType et = EntityType.VILLAGER;
        if (type != null) {
            et = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(type.toLowerCase(Locale.ROOT)));
            if (et == null || !et.isAlive() || !et.isSpawnable()) return "Onbekend mob-type: " + type;
        }
        Class<? extends Entity> cls = et.getEntityClass();
        if (cls == null || !LivingEntity.class.isAssignableFrom(cls)) return "Dit type kan geen NPC zijn: " + type;
        Location l = pl.getLocation();
        Entity e = pl.getWorld().spawn(l, cls, en -> {
            LivingEntity le = (LivingEntity) en;
            le.setAI(false);
            le.setInvulnerable(true);
            le.setSilent(true);
            le.setPersistent(true);
            le.setRemoveWhenFarAway(false);
            le.setCollidable(false);
            le.setCanPickupItems(false);
            le.customName(Tekst.kleur(naam));
            le.setCustomNameVisible(true);
            if (le instanceof Ageable a) a.setAdult();
            if (le instanceof Mob m) m.setAware(false);
            le.getPersistentDataContainer().set(p.sleutelNpc, PersistentDataType.STRING, winkel);
        });
        geladen.add(e.getUniqueId());
        return null;
    }

    String verwijder(Player pl) {
        Entity doel = pl.getTargetEntity(6);
        if (winkelVan(doel) == null) {
            doel = null;
            double best = 16;
            for (Entity e : pl.getNearbyEntities(4, 3, 4)) {
                if (winkelVan(e) == null) continue;
                double d = e.getLocation().distanceSquared(pl.getLocation());
                if (d < best) {
                    best = d;
                    doel = e;
                }
            }
        }
        if (doel == null) return "Kijk naar een winkel-NPC (of ga er vlak bij staan).";
        String w = winkelVan(doel);
        geladen.remove(doel.getUniqueId());
        doel.remove();
        return "NPC van winkel " + w + " is weg.";
    }

    List<String> lijst() {
        List<String> l = new ArrayList<>();
        for (UUID id : geladen) {
            Entity e = Bukkit.getEntity(id);
            if (e == null) continue;
            Location o = e.getLocation();
            l.add(winkelVan(e) + " - " + e.getType().name().toLowerCase(Locale.ROOT) + " in " + o.getWorld().getName() + " " + o.getBlockX() + " " + o.getBlockY() + " " + o.getBlockZ());
        }
        return l;
    }

    private void kijk() {
        if (!p.getConfig().getBoolean("npc.kijken", true)) return;
        geladen.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        for (UUID id : geladen) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof LivingEntity le)) continue;
            Player dichtst = null;
            double best = 64;
            for (Player pl : le.getWorld().getPlayers()) {
                double d = pl.getLocation().distanceSquared(le.getLocation());
                if (d < best) {
                    best = d;
                    dichtst = pl;
                }
            }
            if (dichtst == null) continue;
            Location a = le.getEyeLocation(), b = dichtst.getEyeLocation();
            double dx = b.getX() - a.getX(), dy = b.getY() - a.getY(), dz = b.getZ() - a.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            le.setRotation(yaw, pitch);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void klik(PlayerInteractEntityEvent e) {
        String w = winkelVan(e.getRightClicked());
        if (w == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Winkels.Winkel wk = p.winkels().get(w);
        if (wk == null) {
            Tekst.stuur(e.getPlayer(), "Deze winkel bestaat niet (meer).");
            return;
        }
        p.menus().openWinkel(e.getPlayer(), wk);
    }

    @EventHandler(ignoreCancelled = true)
    public void schade(EntityDamageEvent e) {
        if (winkelVan(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void brand(EntityCombustEvent e) {
        if (winkelVan(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void verander(EntityTransformEvent e) {
        if (winkelVan(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void geladen(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (winkelVan(en) != null) geladen.add(en.getUniqueId());
    }
}
