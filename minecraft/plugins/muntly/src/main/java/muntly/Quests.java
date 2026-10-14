package muntly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Quests: spelers starten een quest bij de NPC (/quests), doen de opdracht en krijgen Munten, geld en/of een item.
 * De quests staan in quests.yml en zijn aan te passen via het beheer van de website.
 */
final class Quests implements Listener {

    static final Map<String, String> SOORTEN = new LinkedHashMap<>();
    static {
        SOORTEN.put("breken", "Blokken breken");
        SOORTEN.put("plaatsen", "Blokken plaatsen");
        SOORTEN.put("doden", "Mobs doden");
        SOORTEN.put("vissen", "Vissen vangen");
        SOORTEN.put("craften", "Items maken (craften)");
        SOORTEN.put("inleveren", "Items inleveren bij de NPC");
    }
    static final List<String> HERHALEN = List.of("nooit", "dagelijks", "wekelijks");

    static final class Quest {
        String id, naam, beschrijving = "", soort, herhalen = "dagelijks";
        List<String> doel = new ArrayList<>(); // materiaal- of mobnamen, leeg = alles
        int aantal = 1;
        long munten;
        double geld;
        String item = ""; // materiaal van een item als extra beloning
        int itemAantal;
        boolean actief = true;
    }

    static final class Voortgang {
        int teller;
        boolean bezig;
        String laatsteKlaar = "";
        int keerKlaar;
    }

    enum Status { BESCHIKBAAR, BEZIG, KLAAR }

    private final Muntly plugin;
    private final File questFile, voortgangFile;
    private final Map<String, Quest> quests = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Voortgang>> voortgang = new HashMap<>();
    /** Door spelers geplaatste blokken (zodat plaatsen en weer breken niet telt). */
    private final LinkedHashSet<String> geplaatst = new LinkedHashSet<>();
    private boolean dirty;

    Quests(Muntly plugin) {
        this.plugin = plugin;
        questFile = new File(plugin.getDataFolder(), "quests.yml");
        voortgangFile = new File(plugin.getDataFolder(), "quests-voortgang.yml");
        load();
        loadVoortgang();
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 1200L, 1200L);
    }

    // ---------------------------------------------------------------- laden
    void load() {
        if (!questFile.exists()) plugin.saveResource("quests.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(questFile);
        quests.clear();
        ConfigurationSection s = y.getConfigurationSection("quests");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection q = s.getConfigurationSection(id);
            if (q == null) continue;
            Quest x = new Quest();
            x.id = id;
            x.naam = q.getString("naam", id);
            x.beschrijving = q.getString("beschrijving", "");
            x.soort = q.getString("soort", "breken").toLowerCase(Locale.ROOT);
            if (!SOORTEN.containsKey(x.soort)) { plugin.getLogger().warning("Quest " + id + ": onbekende soort '" + x.soort + "'. Overgeslagen."); continue; }
            for (String d : q.getStringList("doel")) if (!d.isBlank()) x.doel.add(d.trim().toLowerCase(Locale.ROOT).replace("minecraft:", ""));
            x.aantal = Math.max(1, q.getInt("aantal", 1));
            x.herhalen = q.getString("herhalen", "dagelijks").toLowerCase(Locale.ROOT);
            if (!HERHALEN.contains(x.herhalen)) x.herhalen = "dagelijks";
            x.munten = Math.max(0, q.getLong("beloning.munten"));
            x.geld = Math.max(0, q.getDouble("beloning.geld"));
            x.item = q.getString("beloning.item", "").toLowerCase(Locale.ROOT).replace("minecraft:", "");
            x.itemAantal = Math.max(0, q.getInt("beloning.item-aantal", 1));
            x.actief = q.getBoolean("actief", true);
            quests.put(id, x);
        }
        plugin.getLogger().info("Quests geladen: " + quests.size() + ".");
    }

    private void saveQuests() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Muntly - quests. Aan te passen in het beheer van de website (tabblad Quests) of hier; daarna /quests herladen.",
                "soort: " + String.join(", ", SOORTEN.keySet()) + "    herhalen: nooit, dagelijks of wekelijks",
                "doel: lijst met Minecraft-namen (blokken, items of mobs). Leeg = alles telt (niet bij inleveren)."));
        for (Quest q : quests.values()) {
            String k = "quests." + q.id + ".";
            y.set(k + "naam", q.naam);
            y.set(k + "beschrijving", q.beschrijving);
            y.set(k + "soort", q.soort);
            y.set(k + "doel", q.doel);
            y.set(k + "aantal", q.aantal);
            y.set(k + "herhalen", q.herhalen);
            y.set(k + "beloning.munten", q.munten);
            y.set(k + "beloning.geld", q.geld);
            if (!q.item.isBlank() && q.itemAantal > 0) {
                y.set(k + "beloning.item", q.item);
                y.set(k + "beloning.item-aantal", q.itemAantal);
            }
            y.set(k + "actief", q.actief);
        }
        try {
            Files.writeString(questFile.toPath(), y.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Kon quests.yml niet opslaan", e);
        }
    }

    private void loadVoortgang() {
        if (!voortgangFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(voortgangFile);
        for (String key : y.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException e) { continue; }
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            Map<String, Voortgang> m = new HashMap<>();
            for (String q : s.getKeys(false)) {
                Voortgang v = new Voortgang();
                v.teller = s.getInt(q + ".teller");
                v.bezig = s.getBoolean(q + ".bezig");
                v.laatsteKlaar = s.getString(q + ".laatste-klaar", "");
                v.keerKlaar = s.getInt(q + ".keer-klaar");
                m.put(q, v);
            }
            voortgang.put(id, m);
        }
    }

    private void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        String text = snapshot();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(text));
    }

    void saveNow() { dirty = false; write(snapshot()); }

    private String snapshot() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : voortgang.entrySet()) for (var q : e.getValue().entrySet()) {
            String k = e.getKey() + "." + q.getKey() + ".";
            Voortgang v = q.getValue();
            y.set(k + "teller", v.teller);
            y.set(k + "bezig", v.bezig);
            y.set(k + "laatste-klaar", v.laatsteKlaar);
            y.set(k + "keer-klaar", v.keerKlaar);
        }
        return y.saveToString();
    }

    private synchronized void write(String text) {
        try {
            plugin.getDataFolder().mkdirs();
            Files.writeString(voortgangFile.toPath(), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Kon quests-voortgang.yml niet opslaan", e);
        }
    }

    // ------------------------------------------------------------- status
    Map<String, Quest> quests() { return quests; }

    private Voortgang v(UUID uuid, String quest) {
        return voortgang.computeIfAbsent(uuid, u -> new HashMap<>()).computeIfAbsent(quest, q -> new Voortgang());
    }

    private static String week(LocalDate d) { return d.get(IsoFields.WEEK_BASED_YEAR) + "-" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR); }

    Status status(UUID uuid, Quest q) {
        Voortgang v = v(uuid, q.id);
        if (v.bezig) return Status.BEZIG;
        if (v.keerKlaar == 0 || v.laatsteKlaar.isBlank()) return Status.BESCHIKBAAR;
        LocalDate laatste;
        try { laatste = LocalDate.parse(v.laatsteKlaar); } catch (Exception e) { return Status.BESCHIKBAAR; }
        LocalDate nu = LocalDate.now();
        return switch (q.herhalen) {
            case "dagelijks" -> laatste.isBefore(nu) ? Status.BESCHIKBAAR : Status.KLAAR;
            case "wekelijks" -> week(laatste).equals(week(nu)) ? Status.KLAAR : Status.BESCHIKBAAR;
            default -> Status.KLAAR;
        };
    }

    int bezigAantal(UUID uuid) {
        int n = 0;
        for (Quest q : quests.values()) if (q.actief && v(uuid, q.id).bezig) n++;
        return n;
    }

    int max() { return Math.max(1, plugin.getConfig().getInt("quests.max-tegelijk", 3)); }

    /** Start een quest. null = gelukt, anders de foutmelding. */
    String start(Player p, Quest q) {
        if (!q.actief) return "Deze quest staat uit.";
        Status st = status(p.getUniqueId(), q);
        if (st == Status.BEZIG) return "Je bent al met deze quest bezig.";
        if (st == Status.KLAAR) return q.herhalen.equals("nooit") ? "Deze quest heb je al gedaan." : "Deze quest kun je " + (q.herhalen.equals("dagelijks") ? "morgen" : "volgende week") + " weer doen.";
        if (bezigAantal(p.getUniqueId()) >= max()) return "Je kunt maximaal " + max() + " quests tegelijk doen. Maak er eerst een af (of geef er een op met shift + rechtsklik).";
        Voortgang v = v(p.getUniqueId(), q.id);
        v.bezig = true;
        v.teller = 0;
        dirty = true;
        return null;
    }

    void opgeven(Player p, Quest q) {
        Voortgang v = v(p.getUniqueId(), q.id);
        v.bezig = false;
        v.teller = 0;
        dirty = true;
    }

    // ------------------------------------------------------- tellen (events)
    private boolean telt(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return false;
        for (String w : plugin.getConfig().getStringList("quests.niet-in-werelden")) if (w.equalsIgnoreCase(p.getWorld().getName())) return false;
        return true;
    }

    private void tel(Player p, String soort, String wat, int n) {
        if (n <= 0 || !telt(p)) return;
        Map<String, Voortgang> m = voortgang.get(p.getUniqueId());
        if (m == null) return;
        for (Quest q : quests.values()) {
            if (!q.actief || !q.soort.equals(soort)) continue;
            Voortgang v = m.get(q.id);
            if (v == null || !v.bezig) continue;
            if (!q.doel.isEmpty() && !q.doel.contains(wat)) continue;
            v.teller = Math.min(q.aantal, v.teller + n);
            dirty = true;
            if (v.teller >= q.aantal) voltooi(p, q);
            else p.sendActionBar(Winkel.tekst("&6" + q.naam + ": &f" + v.teller + "&7/&f" + q.aantal));
        }
    }

    private static String key(Block b) { return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        geplaatst.add(key(b));
        if (geplaatst.size() > 200_000) { Iterator<String> it = geplaatst.iterator(); for (int i = 0; i < 20_000 && it.hasNext(); i++) { it.next(); it.remove(); } }
        tel(e.getPlayer(), "plaatsen", b.getType().getKey().getKey(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (geplaatst.remove(key(b))) return; // zelf geplaatst: telt niet
        if (b.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge()) return; // gewassen moeten volgroeid zijn
        tel(e.getPlayer(), "breken", b.getType().getKey().getKey(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null || e.getEntity() instanceof Player) return;
        if (!plugin.getConfig().getBoolean("quests.spawner-mobs-tellen", false)
                && e.getEntity().getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        tel(p, "doden", e.getEntityType().getKey().getKey(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item it)) return;
        tel(e.getPlayer(), "vissen", it.getItemStack().getType().getKey().getKey(), Math.max(1, it.getItemStack().getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack res = e.getRecipe().getResult();
        int n = res.getAmount();
        if (e.isShiftClick()) {
            int keer = Integer.MAX_VALUE;
            for (ItemStack s : e.getInventory().getMatrix()) if (s != null && !s.getType().isAir()) keer = Math.min(keer, s.getAmount());
            if (keer == Integer.MAX_VALUE) keer = 1;
            n = res.getAmount() * keer;
        }
        tel(p, "craften", res.getType().getKey().getKey(), n);
    }

    /** Items inleveren uit de inventaris. null = gelukt, anders de foutmelding. */
    String inleveren(Player p, Quest q) {
        Map<Material, Integer> heeft = new LinkedHashMap<>();
        int totaal = 0;
        for (String d : q.doel) {
            Material m = Material.matchMaterial(d);
            if (m == null) continue;
            int c = 0;
            for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.isSimilar(new ItemStack(m))) c += it.getAmount();
            heeft.put(m, c);
            totaal += c;
        }
        if (totaal < q.aantal) return "Je hebt er " + totaal + " van de " + q.aantal + " bij je. Haal de rest en kom terug.";
        int over = q.aantal;
        for (var e : heeft.entrySet()) {
            if (over <= 0) break;
            int weg = Math.min(over, e.getValue());
            if (weg > 0) p.getInventory().removeItem(new ItemStack(e.getKey(), weg));
            over -= weg;
        }
        v(p.getUniqueId(), q.id).teller = q.aantal;
        voltooi(p, q);
        return null;
    }

    // ------------------------------------------------------------ belonen
    private void voltooi(Player p, Quest q) {
        Voortgang v = v(p.getUniqueId(), q.id);
        v.bezig = false;
        v.teller = 0;
        v.laatsteKlaar = LocalDate.now().toString();
        v.keerKlaar++;
        dirty = true;
        List<String> wat = new ArrayList<>();
        if (q.munten > 0) {
            plugin.saldi().add(plugin.saldi().get(p.getUniqueId(), p.getName()), q.munten, "quest: " + q.id);
            wat.add(plugin.munten(q.munten));
        }
        if (q.geld > 0) {
            Economy eco = plugin.economy();
            if (eco != null && eco.depositPlayer(p, q.geld).transactionSuccess()) wat.add("$" + Muntly.fmt(Math.round(q.geld)));
        }
        Material im = q.item.isBlank() ? null : Material.matchMaterial(q.item);
        if (im != null && im.isItem() && q.itemAantal > 0) {
            int left = q.itemAantal;
            while (left > 0) {
                ItemStack s = new ItemStack(im, Math.min(left, im.getMaxStackSize()));
                left -= s.getAmount();
                for (ItemStack r : p.getInventory().addItem(s).values()) p.getWorld().dropItem(p.getLocation(), r);
            }
            wat.add(q.itemAantal + "x " + Winkel.naam(new ItemStack(im)));
        }
        plugin.getLogger().info(p.getName() + " voltooide quest " + q.id + " (" + String.join(", ", wat) + ")");
        p.showTitle(Title.title(Winkel.tekst("&6&lQuest voltooid!"), Winkel.tekst("&f" + q.naam),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        p.sendMessage(plugin.bericht("&aQuest voltooid: &f" + q.naam + "&a. Beloning: &e" + (wat.isEmpty() ? "geen" : String.join("&a, &e", wat))));
    }

    // --------------------------------------------------------------- menu
    static Component naamVan(String key, boolean mob) {
        if (mob) {
            EntityType t = EntityType.fromName(key);
            if (t != null) return Component.translatable(t.translationKey());
        } else {
            Material m = Material.matchMaterial(key);
            if (m != null && (m.isItem() || m.isBlock())) return Component.translatable(m.translationKey());
        }
        return Component.text(key.replace('_', ' '));
    }

    Component doelTekst(Quest q) {
        String werk = switch (q.soort) {
            case "breken" -> "Breek ";
            case "plaatsen" -> "Plaats ";
            case "doden" -> "Dood ";
            case "vissen" -> "Vang ";
            case "craften" -> "Maak ";
            default -> "Lever in: ";
        };
        Component c = Component.text(werk + q.aantal + "x ", NamedTextColor.WHITE);
        if (q.doel.isEmpty()) return c.append(Component.text(q.soort.equals("doden") ? "mobs" : q.soort.equals("vissen") ? "vissen" : "blokken"));
        for (int i = 0; i < q.doel.size() && i < 3; i++) {
            if (i > 0) c = c.append(Component.text(i == q.doel.size() - 1 || i == 2 ? " of " : ", "));
            c = c.append(naamVan(q.doel.get(i), q.soort.equals("doden")));
        }
        if (q.doel.size() > 3) c = c.append(Component.text(" (of " + (q.doel.size() - 3) + " andere)"));
        return c;
    }

    String beloningTekst(Quest q) {
        List<String> w = new ArrayList<>();
        if (q.munten > 0) w.add(plugin.munten(q.munten));
        if (q.geld > 0) w.add("$" + Muntly.fmt(Math.round(q.geld)));
        Material im = q.item.isBlank() ? null : Material.matchMaterial(q.item);
        if (im != null && q.itemAantal > 0) w.add(q.itemAantal + "x " + Winkel.naam(new ItemStack(im)));
        return w.isEmpty() ? "geen" : String.join(", ", w);
    }

    Material icoon(Quest q) {
        if (q.soort.equals("doden")) {
            Material egg = q.doel.isEmpty() ? null : Material.matchMaterial(q.doel.get(0) + "_spawn_egg");
            return egg != null ? egg : Material.IRON_SWORD;
        }
        if (q.soort.equals("vissen") && q.doel.isEmpty()) return Material.FISHING_ROD;
        Material m = q.doel.isEmpty() ? null : Material.matchMaterial(q.doel.get(0));
        if (m != null && m.isItem()) return m;
        return switch (q.soort) { case "breken" -> Material.IRON_PICKAXE; case "plaatsen" -> Material.BRICKS; case "craften" -> Material.CRAFTING_TABLE; default -> Material.CHEST; };
    }

    ItemStack knop(Player p, Quest q) {
        Status st = status(p.getUniqueId(), q);
        Voortgang v = v(p.getUniqueId(), q.id);
        ItemStack it = new ItemStack(st == Status.KLAAR ? Material.LIME_DYE : icoon(q));
        ItemMeta meta = it.getItemMeta();
        String kleur = st == Status.BEZIG ? "&e" : st == Status.KLAAR ? "&a" : "&6";
        meta.displayName(Winkel.tekst(kleur + "&l" + q.naam));
        List<Component> lore = new ArrayList<>();
        if (!q.beschrijving.isBlank()) lore.add(Winkel.tekst("&7" + q.beschrijving));
        lore.add(Component.empty());
        lore.add(Component.text("Opdracht: ", NamedTextColor.GRAY).append(doelTekst(q)).decoration(TextDecoration.ITALIC, false));
        if (st == Status.BEZIG && !q.soort.equals("inleveren")) {
            int balk = 20, vol = (int) Math.round(balk * (double) v.teller / q.aantal);
            lore.add(Winkel.tekst("&7Voortgang: &a" + "|".repeat(vol) + "&8" + "|".repeat(balk - vol) + " &f" + v.teller + "/" + q.aantal));
        }
        lore.add(Winkel.tekst("&7Beloning: &e" + beloningTekst(q)));
        lore.add(Winkel.tekst("&7Herhalen: &f" + switch (q.herhalen) { case "dagelijks" -> "elke dag"; case "wekelijks" -> "elke week"; default -> "eenmalig"; }));
        lore.add(Component.empty());
        switch (st) {
            case BESCHIKBAAR -> lore.add(Winkel.tekst(q.soort.equals("inleveren") ? "&eKlik om in te leveren (neem de spullen mee)" : "&eKlik om te starten"));
            case BEZIG -> {
                if (q.soort.equals("inleveren")) lore.add(Winkel.tekst("&eKlik om in te leveren"));
                else lore.add(Winkel.tekst("&aBezig"));
                lore.add(Winkel.tekst("&8Shift + rechtsklik: opgeven"));
            }
            case KLAAR -> lore.add(Winkel.tekst(q.herhalen.equals("nooit") ? "&aVoltooid" : "&aVoltooid. Weer beschikbaar " + (q.herhalen.equals("dagelijks") ? "morgen" : "volgende week") + "."));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
        if (st == Status.BEZIG) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    /** Klik op een quest in het menu. */
    void klik(Player p, Quest q, ClickType c) {
        Status st = status(p.getUniqueId(), q);
        if (st == Status.BEZIG && c == ClickType.SHIFT_RIGHT) {
            opgeven(p, q);
            p.sendMessage(plugin.bericht("Je hebt de quest &f" + q.naam + " &7opgegeven."));
            return;
        }
        if (q.soort.equals("inleveren") && st != Status.KLAAR) {
            if (!q.actief) { p.sendMessage(plugin.fout("Deze quest staat uit.")); return; }
            String f = inleveren(p, q); // direct inleveren, zonder eerst te starten
            if (f != null) { p.sendMessage(plugin.fout(f)); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f); }
            return;
        }
        if (st == Status.BESCHIKBAAR) {
            String f = start(p, q);
            if (f != null) { p.sendMessage(plugin.fout(f)); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f); return; }
            p.sendMessage(Component.empty().append(plugin.bericht("&aQuest gestart: &f" + q.naam + "&7. ")).append(doelTekst(q).color(NamedTextColor.WHITE)));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        }
    }

    // ---------------------------------------------------- beheer (website)
    JsonObject toJson(Quest q) {
        JsonObject o = new JsonObject();
        o.addProperty("id", q.id);
        o.addProperty("naam", q.naam);
        o.addProperty("beschrijving", q.beschrijving);
        o.addProperty("soort", q.soort);
        JsonArray d = new JsonArray();
        q.doel.forEach(d::add);
        o.add("doel", d);
        o.addProperty("aantal", q.aantal);
        o.addProperty("herhalen", q.herhalen);
        o.addProperty("munten", q.munten);
        o.addProperty("geld", q.geld);
        o.addProperty("item", q.item);
        o.addProperty("itemAantal", q.itemAantal);
        o.addProperty("actief", q.actief);
        int bezig = 0, klaar = 0;
        for (Map<String, Voortgang> m : voortgang.values()) {
            Voortgang v = m.get(q.id);
            if (v == null) continue;
            if (v.bezig) bezig++;
            klaar += v.keerKlaar;
        }
        o.addProperty("bezig", bezig);
        o.addProperty("keerVoltooid", klaar);
        return o;
    }

    JsonObject alles() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Quest q : quests.values()) arr.add(toJson(q));
        o.add("quests", arr);
        JsonObject s = new JsonObject();
        SOORTEN.forEach(s::addProperty);
        o.add("soorten", s);
        o.addProperty("maxTegelijk", max());
        return o;
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : ""; }

    /** Quest opslaan vanaf de website. Geeft een foutmelding terug of null. */
    String opslaan(JsonObject b) {
        String naam = str(b, "naam");
        if (naam.isBlank() || naam.length() > 40) return "Geef de quest een naam (maximaal 40 tekens).";
        String soort = str(b, "soort");
        if (!SOORTEN.containsKey(soort)) return "Kies wat de speler moet doen.";
        List<String> doel = new ArrayList<>();
        if (b.has("doel") && b.get("doel").isJsonArray()) for (JsonElement e : b.getAsJsonArray("doel")) {
            String d = e.getAsString().trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
            if (d.isBlank()) continue;
            boolean ok = soort.equals("doden") ? EntityType.fromName(d) != null : Material.matchMaterial(d) != null;
            if (!ok) return "'" + d + "' is geen geldige " + (soort.equals("doden") ? "mob" : "blok of item") + ".";
            if (!doel.contains(d)) doel.add(d);
        }
        if (soort.equals("inleveren") && doel.isEmpty()) return "Kies welk item ingeleverd moet worden.";
        int aantal;
        long munten;
        double geld;
        int itemAantal;
        try {
            aantal = b.get("aantal").getAsInt();
            munten = b.has("munten") ? b.get("munten").getAsLong() : 0;
            geld = b.has("geld") ? b.get("geld").getAsDouble() : 0;
            itemAantal = b.has("itemAantal") ? b.get("itemAantal").getAsInt() : 0;
        } catch (Exception e) { return "Vul geldige getallen in."; }
        if (aantal < 1 || aantal > 100000) return "Het aantal moet tussen 1 en 100.000 liggen.";
        if (munten < 0 || geld < 0 || itemAantal < 0) return "Een beloning kan niet negatief zijn.";
        String item = str(b, "item").toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        if (!item.isBlank()) {
            Material m = Material.matchMaterial(item);
            if (m == null || !m.isItem()) return "'" + item + "' is geen geldig item voor de beloning.";
            if (itemAantal < 1) itemAantal = 1;
        }
        if (munten == 0 && geld == 0 && item.isBlank()) return "Geef minstens één beloning: Munten, geld of een item.";
        String herhalen = str(b, "herhalen");
        if (!HERHALEN.contains(herhalen)) herhalen = "dagelijks";

        String id = str(b, "id");
        Quest q = id.isBlank() ? null : quests.get(id);
        if (q == null) {
            String basis = naam.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            if (basis.isBlank()) basis = "quest";
            id = basis;
            for (int i = 2; quests.containsKey(id); i++) id = basis + "-" + i;
            q = new Quest();
            q.id = id;
            quests.put(id, q);
        }
        q.naam = naam;
        q.beschrijving = str(b, "beschrijving");
        if (q.beschrijving.length() > 120) q.beschrijving = q.beschrijving.substring(0, 120);
        q.soort = soort;
        q.doel = doel;
        q.aantal = aantal;
        q.herhalen = herhalen;
        q.munten = munten;
        q.geld = geld;
        q.item = item;
        q.itemAantal = item.isBlank() ? 0 : itemAantal;
        q.actief = !b.has("actief") || b.get("actief").getAsBoolean();
        saveQuests();
        plugin.getLogger().info("Quest opgeslagen via de website: " + q.id);
        return null;
    }

    boolean verwijder(String id) {
        if (quests.remove(id) == null) return false;
        for (Map<String, Voortgang> m : voortgang.values()) m.remove(id);
        dirty = true;
        saveQuests();
        plugin.getLogger().info("Quest verwijderd via de website: " + id);
        return true;
    }
}
