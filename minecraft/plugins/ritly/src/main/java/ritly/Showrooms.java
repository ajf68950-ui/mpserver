package ritly;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Displays in een showroom: een voertuig of item dat je ziet staan en kunt kopen door erop te klikken. */
final class Showrooms implements Listener {

    static final class Plaats {
        int id;
        String wereld;
        double x, y, z;
        float yaw;
        String winkel, product;
        // in de wereld
        final List<Entity> entities = new ArrayList<>();
        final List<BlockDisplay> delen = new ArrayList<>();
        ItemDisplay item;
        Model model;
        float hoek;
        boolean staat;
    }

    private final Ritly p;
    private final File bestand;
    private final Map<Integer, Plaats> plaatsen = new LinkedHashMap<>();
    private int tik;

    Showrooms(Ritly p) {
        this.p = p;
        this.bestand = new File(p.getDataFolder(), "showrooms.yml");
    }

    void laad() {
        allesWeg();
        plaatsen.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(bestand);
        ConfigurationSection s = y.getConfigurationSection("showrooms");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            if (c == null) continue;
            Plaats pl = new Plaats();
            try {
                pl.id = Integer.parseInt(k);
            } catch (NumberFormatException e) {
                continue;
            }
            pl.wereld = c.getString("wereld");
            pl.x = c.getDouble("x");
            pl.y = c.getDouble("y");
            pl.z = c.getDouble("z");
            pl.yaw = (float) c.getDouble("yaw");
            pl.winkel = c.getString("winkel");
            pl.product = c.getString("product");
            pl.hoek = pl.yaw;
            plaatsen.put(pl.id, pl);
        }
    }

    void opslaan() {
        YamlConfiguration y = new YamlConfiguration();
        for (Plaats pl : plaatsen.values()) {
            String k = "showrooms." + pl.id + ".";
            y.set(k + "wereld", pl.wereld);
            y.set(k + "x", pl.x);
            y.set(k + "y", pl.y);
            y.set(k + "z", pl.z);
            y.set(k + "yaw", (double) pl.yaw);
            y.set(k + "winkel", pl.winkel);
            y.set(k + "product", pl.product);
        }
        try {
            y.save(bestand);
        } catch (IOException e) {
            p.getLogger().warning("showrooms.yml opslaan mislukt: " + e.getMessage());
        }
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(p, this::tick, 20L, 2L);
    }

    private void tick() {
        tik++;
        if (tik % 20 == 0) {
            for (Plaats pl : plaatsen.values()) {
                if (pl.staat && !geldig(pl)) weg(pl);
                if (!pl.staat) {
                    World w = Bukkit.getWorld(pl.wereld);
                    if (w != null && w.isChunkLoaded((int) Math.floor(pl.x) >> 4, (int) Math.floor(pl.z) >> 4)) zet(pl, w);
                }
            }
        }
        if (!p.getConfig().getBoolean("showroom.draaien", true)) return;
        float stap = (float) (p.getConfig().getDouble("showroom.graden-per-seconde", 15) / 10.0);
        for (Plaats pl : plaatsen.values()) {
            if (!pl.staat) continue;
            pl.hoek = ActiefVoertuig.norm(pl.hoek + stap);
            draai(pl, 2);
        }
    }

    private boolean geldig(Plaats pl) {
        for (Entity e : pl.entities) if (!e.isValid()) return false;
        return true;
    }

    private void tag(Entity e, Plaats pl) {
        e.setPersistent(false);
        e.getPersistentDataContainer().set(p.sleutelShowroom, PersistentDataType.INTEGER, pl.id);
    }

    private void zet(Plaats pl, World w) {
        Winkels.Winkel wk = p.winkels().get(pl.winkel);
        Winkels.Aanbod a = wk == null ? null : wk.zoek(pl.product);
        Product pr = Product.lees(pl.product);
        if (pr == null) return;
        Location basis = new Location(w, pl.x, pl.y, pl.z, 0, 0);
        double hoogte;
        float breed;
        if (pr.type == Product.Type.VOERTUIG) {
            Model m = p.modellen().get(pr.waarde);
            if (m == null) return;
            pl.model = m;
            for (Model.Deel d : m.delen) {
                BlockDisplay bd = w.spawn(basis, BlockDisplay.class, b -> {
                    b.setBlock(d.data(m.kleur));
                    b.setTransformation(vorm(d, rot(pl.hoek), 0));
                    tag(b, pl);
                });
                pl.delen.add(bd);
                pl.entities.add(bd);
            }
            hoogte = m.hoogte + 0.4;
            breed = (float) Math.max(1, Math.min(4, Math.max(m.breedte, m.lengte)));
        } else {
            ItemStack is = p.onderdelen().product(pr);
            pl.item = w.spawn(basis.clone().add(0, 0.9, 0), ItemDisplay.class, it -> {
                it.setItemStack(is);
                it.setTransformation(new Transformation(new Vector3f(), rot(pl.hoek), new Vector3f(0.7f), new Quaternionf()));
                tag(it, pl);
            });
            pl.entities.add(pl.item);
            hoogte = 1.5;
            breed = 0.9f;
        }
        String tekst = (a == null ? p.winkels().naam(pr) + "\n&cNiet te koop" :
                p.winkels().naam(pr) + "\n&a" + Tekst.geld(a.prijs()) + "\n&eRechtsklik om te kopen");
        TextDisplay td = w.spawn(basis.clone().add(0, hoogte, 0), TextDisplay.class, t -> {
            t.text(Tekst.kleur(tekst));
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            tag(t, pl);
        });
        pl.entities.add(td);
        final float hb = breed;
        final double hh = hoogte;
        Interaction ia = w.spawn(basis, Interaction.class, i -> {
            i.setInteractionWidth(hb);
            i.setInteractionHeight((float) Math.max(1.0, hh - 0.2));
            i.setResponsive(true);
            tag(i, pl);
        });
        pl.entities.add(ia);
        pl.staat = true;
    }

    private static Quaternionf rot(float yaw) {
        return new Quaternionf().rotationY((float) -Math.toRadians(yaw));
    }

    private static Transformation vorm(Model.Deel d, Quaternionf q, float spin) {
        Quaternionf l = new Quaternionf(q);
        if (d.vast != null) l.mul(d.vast);
        Vector3f c = new Vector3f(d.van).add(d.maat.x / 2f, d.maat.y / 2f, d.maat.z / 2f);
        q.transform(c);
        Vector3f half = l.transform(new Vector3f(d.maat).mul(0.5f));
        return new Transformation(c.sub(half), l, new Vector3f(d.maat), new Quaternionf());
    }

    private void draai(Plaats pl, int duur) {
        Quaternionf q = rot(pl.hoek);
        if (pl.model != null) {
            for (int i = 0; i < pl.delen.size() && i < pl.model.delen.size(); i++) {
                BlockDisplay bd = pl.delen.get(i);
                bd.setInterpolationDelay(0);
                bd.setInterpolationDuration(duur);
                bd.setTransformation(vorm(pl.model.delen.get(i), q, 0));
            }
        } else if (pl.item != null) {
            pl.item.setInterpolationDelay(0);
            pl.item.setInterpolationDuration(duur);
            pl.item.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(0.7f), new Quaternionf()));
        }
    }

    private void weg(Plaats pl) {
        for (Entity e : pl.entities) e.remove();
        pl.entities.clear();
        pl.delen.clear();
        pl.item = null;
        pl.model = null;
        pl.staat = false;
    }

    void allesWeg() {
        for (Plaats pl : plaatsen.values()) weg(pl);
    }

    /** Alles opnieuw neerzetten (bijv. na een prijswijziging). */
    void vernieuw() {
        allesWeg();
    }

    // ------------------------------------------------------------------ beheer

    String plaats(Player pl, String winkel, String product) {
        Location l;
        Block doel = pl.getTargetBlockExact(6);
        if (doel != null && !doel.isPassable()) l = new Location(pl.getWorld(), doel.getX() + 0.5, doel.getBoundingBox().getMaxY(), doel.getZ() + 0.5);
        else l = pl.getLocation();
        Plaats s = new Plaats();
        int id = 1;
        while (plaatsen.containsKey(id)) id++;
        s.id = id;
        s.wereld = l.getWorld().getName();
        s.x = l.getX();
        s.y = l.getY();
        s.z = l.getZ();
        s.yaw = ActiefVoertuig.norm(pl.getYaw() + 180);
        s.hoek = s.yaw;
        s.winkel = winkel;
        s.product = product;
        plaatsen.put(id, s);
        opslaan();
        zet(s, l.getWorld());
        return "Display #" + id + " staat er.";
    }

    String verwijderBij(Player pl) {
        Plaats beste = null;
        double afst = 36;
        for (Plaats s : plaatsen.values()) {
            if (!s.wereld.equals(pl.getWorld().getName())) continue;
            double d = pl.getLocation().distanceSquared(new Location(pl.getWorld(), s.x, s.y, s.z));
            if (d < afst) {
                afst = d;
                beste = s;
            }
        }
        if (beste == null) return "Er staat geen display binnen 6 blokken.";
        weg(beste);
        plaatsen.remove(beste.id);
        opslaan();
        return "Display #" + beste.id + " (" + beste.product + ") is weg.";
    }

    List<String> lijst() {
        List<String> l = new ArrayList<>();
        for (Plaats s : plaatsen.values())
            l.add("#" + s.id + " " + s.winkel + " / " + s.product + " - " + s.wereld + " " + (int) s.x + " " + (int) s.y + " " + (int) s.z);
        return l;
    }

    boolean isShowroom(Entity e) {
        return e != null && e.getPersistentDataContainer().has(p.sleutelShowroom, PersistentDataType.INTEGER);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void klik(PlayerInteractEntityEvent e) {
        Integer id = e.getRightClicked().getPersistentDataContainer().get(p.sleutelShowroom, PersistentDataType.INTEGER);
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Plaats s = plaatsen.get(id);
        if (s == null) return;
        Winkels.Winkel w = p.winkels().get(s.winkel);
        if (w == null) {
            Tekst.stuur(e.getPlayer(), "Deze winkel bestaat niet meer.");
            return;
        }
        p.menus().openKoop(e.getPlayer(), w, s.product);
    }

    @EventHandler
    public void chunkWeg(ChunkUnloadEvent e) {
        Chunk c = e.getChunk();
        for (Plaats s : plaatsen.values()) {
            if (!s.staat || !s.wereld.equals(c.getWorld().getName())) continue;
            if (((int) Math.floor(s.x) >> 4) == c.getX() && ((int) Math.floor(s.z) >> 4) == c.getZ()) weg(s);
        }
    }

    @EventHandler
    public void geladen(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            Integer id = en.getPersistentDataContainer().get(p.sleutelShowroom, PersistentDataType.INTEGER);
            if (id == null) continue;
            Plaats s = plaatsen.get(id);
            if (s == null || !s.entities.contains(en)) en.remove();
        }
    }
}
