package ordely;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Economie-regels:
 * - Verkopen kan alleen via de GUI (Opkoper en winkel) in de verkoop-werelden, in survival.
 *   Getypte verkoopcommando's (/sell enz.) werken nergens, ook niet voor OP.
 *   De GUI verkoopt via de console: "ordelyverkoop <speler> <inventory|hand>".
 * - Geld werkt alleen in de geld-werelden (survival, Skyblock, OneBlock). In andere werelden (plots, minigames...)
 *   werken geldcommando's niet.
 * - Vangnet: geld uit Essentials-verkopen die niet via de GUI gingen, wordt teruggedraaid; QuickShop-handel in
 *   creative of buiten de geld-werelden wordt tegengehouden.
 */
final class Economie implements Listener, CommandExecutor {

    private final Ordely plugin;
    private boolean aan;
    private final Set<String> verkoopWerelden = new HashSet<>();
    private final Set<String> geldWerelden = new HashSet<>();
    private final Set<String> verkoopCommandos = new HashSet<>();
    private final List<String[]> geldCommandos = new ArrayList<>();
    /** spelers die net via de GUI verkopen, met tijdstip tot wanneer Essentials-sell geld mag geven
     *  (Essentials kan het geld een fractie later boeken dan het commando) */
    private final java.util.Map<UUID, Long> bezigMetGuiVerkoop = new java.util.concurrent.ConcurrentHashMap<>();

    Economie(Ordely plugin) {
        this.plugin = plugin;
        laad();
    }

    void laad() {
        var c = plugin.getConfig();
        aan = c.getBoolean("economie.aan", true);
        verkoopWerelden.clear();
        c.getStringList("economie.verkoop-werelden").forEach(w -> verkoopWerelden.add(w.toLowerCase(Locale.ROOT)));
        geldWerelden.clear();
        c.getStringList("economie.geld-werelden").forEach(w -> geldWerelden.add(w.toLowerCase(Locale.ROOT)));
        geldWerelden.addAll(verkoopWerelden);
        verkoopCommandos.clear();
        c.getStringList("economie.verkoop-commandos").forEach(w -> verkoopCommandos.add(w.toLowerCase(Locale.ROOT)));
        verkoopTekst = c.getString("economie.tekst-verkopen", "de survivalwereld, Skyblock en OneBlock");
        geldTekst = c.getString("economie.tekst-geld", "de survivalwereld, Skyblock en OneBlock");
        geldCommandos.clear();
        for (String s : c.getStringList("economie.geld-commandos")) {
            String[] p = s.trim().toLowerCase(Locale.ROOT).split("\\s+");
            if (p.length > 0 && !p[0].isEmpty()) geldCommandos.add(p);
        }
    }

    boolean isVerkoopWereld(World w) { return w != null && verkoopWerelden.contains(w.getName().toLowerCase(Locale.ROOT)); }
    boolean isGeldWereld(World w) { return w != null && geldWerelden.contains(w.getName().toLowerCase(Locale.ROOT)); }
    private static boolean survival(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    private String verkoopTekst = "de survivalwereld, Skyblock en OneBlock";
    private String geldTekst = "de survivalwereld, Skyblock en OneBlock";
    private String verkoopWereldenTekst() { return verkoopTekst; }

    // ------------------------------------------------------------ getypte commando's (na de gevangenis- en geef-regels)
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void opCommando(PlayerCommandPreprocessEvent e) {
        if (!aan) return;
        Player p = e.getPlayer();
        String raw = e.getMessage();
        if (raw.length() < 2 || raw.charAt(0) != '/') return;
        String[] delen = raw.substring(1).trim().split("\\s+");
        if (delen.length == 0 || delen[0].isEmpty()) return;
        String label = delen[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(delen, 1, delen.length);
        Set<String> namen = Regels.namen(label);

        // 1. Verkopen met een commando: nergens, voor niemand
        if (namen.stream().anyMatch(verkoopCommandos::contains)) {
            e.setCancelled(true);
            if (plugin.regels().creativeBeperkt(p)) {
                Ordely.regel(p, "Verkopen kan niet in creative of als toeschouwer.");
                plugin.regels().overtreding(p, "creative-verkopen", "probeerde te verkopen in " + p.getGameMode().name().toLowerCase(Locale.ROOT) + " (" + kort(raw) + ")");
            } else {
                Ordely.regel(p, "Verkopen met een commando kan niet. Verkoop via de Opkoper of de winkel (/shop), in " + verkoopWereldenTekst() + ".");
            }
            return;
        }
        // 2. Geldcommando's alleen in de geld-werelden
        if (!isGeldWereld(p.getWorld()) && isGeldCommando(namen, args)) {
            e.setCancelled(true);
            Ordely.regel(p, "Geld werkt niet in deze wereld. Dat kan alleen in " + geldTekst + ".");
            return;
        }
        // 3. Jezelf betalen heeft geen zin (Essentials staat het toe en meldt dan "verstuurd" en "ontvangen")
        if (namen.contains("pay") && args.length > 0 && betaaltZichzelf(p, args[0])) {
            e.setCancelled(true);
            Ordely.regel(p, "Je kunt jezelf geen geld betalen.");
        }
    }

    /** Staat de speler zelf tussen de ontvangers? Ook bij een deel van de naam, @s of * (iedereen). */
    private static boolean betaaltZichzelf(Player p, String doel) {
        for (String d : doel.split(",")) {
            d = d.trim();
            if (d.isEmpty()) continue;
            if (d.equalsIgnoreCase(p.getName()) || d.equalsIgnoreCase("@s") || d.equals("*") || d.equals("**") || d.startsWith("@a") || d.startsWith("@p")) return true;
            Player gevonden = Bukkit.getPlayer(d); // Bukkit zoekt ook op het begin van de naam, net als Essentials
            if (gevonden != null && gevonden.getUniqueId().equals(p.getUniqueId())) return true;
            String bijnaam = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(p.displayName());
            if (bijnaam.replaceFirst("^~", "").equalsIgnoreCase(d)) return true;
        }
        return false;
    }

    private boolean isGeldCommando(Set<String> namen, String[] args) {
        for (String[] r : geldCommandos) {
            if (!namen.contains(r[0])) continue;
            if (r.length == 1) return true;
            if (args.length > 0 && args[0].equalsIgnoreCase(r[1])) return true;
        }
        return false;
    }

    private static String kort(String s) { return s.length() > 80 ? s.substring(0, 80) + "..." : s; }

    // ------------------------------------------------------------ /ordelyverkoop (alleen console, voor de GUI)
    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        if (s instanceof Player) {
            Ordely.regel(s, "Dit commando is alleen voor de verkoop-knoppen in het menu.");
            return true;
        }
        if (args.length < 2) { s.sendMessage("Gebruik: ordelyverkoop <speler> <inventory|hand> of ordelyverkoop <speler> item <materiaal> <aantal> <bedrag>"); return true; }
        Player p = Bukkit.getPlayerExact(args[0]);
        if (p == null) { s.sendMessage("Speler " + args[0] + " is niet online."); return true; }
        String wat = args[1].toLowerCase(Locale.ROOT);
        if (!wat.equals("inventory") && !wat.equals("hand") && !wat.equals("item")) { s.sendMessage("Kies inventory, hand of item."); return true; }
        Material mat = null; int aantal = 0; double bedrag = 0;
        if (wat.equals("item")) {
            if (args.length < 5) { s.sendMessage("Gebruik: ordelyverkoop <speler> item <materiaal> <aantal> <bedrag>"); return true; }
            mat = Material.matchMaterial(args[2]);
            try { aantal = Integer.parseInt(args[3]); bedrag = Double.parseDouble(args[4]); } catch (NumberFormatException x) { s.sendMessage("Aantal en bedrag moeten getallen zijn."); return true; }
            if (mat == null || mat.isAir() || aantal < 1 || aantal > 2304 || bedrag < 0 || bedrag > 1_000_000) { s.sendMessage("Ongeldig item, aantal of bedrag."); return true; }
        }
        if (plugin.gevangenis().zitVast(p.getUniqueId())) { Ordely.regel(p, "Dat kan niet in de gevangenis."); return true; }
        if (!survival(p)) {
            Ordely.regel(p, "Verkopen kan alleen in survival.");
            if (plugin.regels().creativeBeperkt(p) || p.getGameMode() == GameMode.CREATIVE)
                plugin.regels().overtreding(p, "creative-verkopen", "probeerde te verkopen in " + p.getGameMode().name().toLowerCase(Locale.ROOT) + " (Opkoper)");
            return true;
        }
        if (!isVerkoopWereld(p.getWorld())) {
            Ordely.regel(p, "Verkopen kan alleen in " + verkoopWereldenTekst() + ".");
            return true;
        }
        if (wat.equals("item")) { verkoopItem(p, mat, aantal, bedrag); return true; }
        PermissionAttachment pa = p.addAttachment(plugin);
        pa.setPermission("essentials.sell", true);
        pa.setPermission("essentials.sell.hand", true);
        pa.setPermission("essentials.sell.bulk", true);
        bezigMetGuiVerkoop.put(p.getUniqueId(), System.currentTimeMillis() + 3000);
        try {
            Bukkit.dispatchCommand(p, "essentials:sell " + wat);
        } finally {
            // rechten en vrijstelling na een paar ticks weer weg (Essentials boekt soms een tick later)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.removeAttachment(pa);
                bezigMetGuiVerkoop.remove(p.getUniqueId());
            }, 40L);
        }
        return true;
    }

    /** Winkel (shop_sell): een vast aantal van een item verkopen voor een vast bedrag. */
    private void verkoopItem(Player p, Material mat, int aantal, double bedrag) {
        String naam = mat.getKey().getKey().replace('_', ' ');
        PlayerInventory inv = p.getInventory();
        int heeft = 0;
        for (ItemStack it : inv.getContents()) if (it != null && it.getType() == mat) heeft += it.getAmount();
        if (heeft < aantal) { Ordely.regel(p, "Je hebt geen " + aantal + "x " + naam + "."); return; }
        if (!stort(p, bedrag)) { Ordely.regel(p, "Verkopen lukt nu niet (geen economie gevonden)."); return; }
        int over = aantal;
        ItemStack[] inhoud = inv.getContents();
        for (int i = 0; i < inhoud.length && over > 0; i++) {
            ItemStack it = inhoud[i];
            if (it == null || it.getType() != mat) continue;
            int weg = Math.min(over, it.getAmount());
            over -= weg;
            if (weg == it.getAmount()) inv.setItem(i, null);
            else { ItemStack rest = it.clone(); rest.setAmount(it.getAmount() - weg); inv.setItem(i, rest); }
        }
        Ordely.regel(p, "Verkocht: " + aantal + "x " + naam + " voor $" + (bedrag == Math.rint(bedrag) ? String.valueOf((long) bedrag) : String.valueOf(bedrag)) + ".");
    }

    /** Geld storten via Vault (met reflectie, zodat Ordely niet van Vault afhangt). */
    private boolean stort(Player p, double bedrag) {
        try {
            Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
            if (vault == null) return false;
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy", true, vault.getClass().getClassLoader());
            var reg = Bukkit.getServicesManager().getRegistration(eco);
            if (reg == null) return false;
            Object prov = reg.getProvider();
            Object res = eco.getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class).invoke(prov, p, bedrag);
            Object ok = res.getClass().getMethod("transactionSuccess").invoke(res);
            return Boolean.TRUE.equals(ok);
        } catch (Throwable t) {
            plugin.getLogger().warning("Storten mislukt: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------ menu's dicht bij een andere gamemode of wereld
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opSpelmodus(PlayerGameModeChangeEvent e) { sluitMenu(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void opWereld(PlayerChangedWorldEvent e) { sluitMenu(e.getPlayer()); }

    private void sluitMenu(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            InventoryType t = p.getOpenInventory().getTopInventory().getType();
            if (t != InventoryType.CRAFTING && t != InventoryType.CREATIVE) p.closeInventory();
        });
    }

    // ------------------------------------------------------------ vangnetten (via reflectie, geen harde afhankelijkheid)
    void registreerVangnetten() {
        registreer("Essentials", "net.ess3.api.events.UserBalanceUpdateEvent", this::opEssentialsSaldo);
        registreer("QuickShop-Hikari", "com.ghostchu.quickshop.api.event.economy.ShopPurchaseEvent", this::opQuickShopHandel);
    }

    private interface Handler { void handle(Event e) throws Exception; }

    @SuppressWarnings("unchecked")
    private void registreer(String pluginNaam, String klasse, Handler h) {
        Plugin pl = Bukkit.getPluginManager().getPlugin(pluginNaam);
        if (pl == null) return;
        try {
            Class<?> c = Class.forName(klasse, true, pl.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(c)) return;
            Class<? extends Event> ec = (Class<? extends Event>) c;
            EventExecutor ex = (l, ev) -> {
                if (!ec.isInstance(ev)) return;
                try { h.handle(ev); } catch (Exception x) { plugin.getLogger().warning("Vangnet " + pluginNaam + ": " + x); }
            };
            Bukkit.getPluginManager().registerEvent(ec, this, EventPriority.HIGHEST, ex, plugin, false);
            plugin.getLogger().info("Economie-vangnet actief voor " + pluginNaam + ".");
        } catch (Throwable t) {
            plugin.getLogger().warning("Economie-vangnet voor " + pluginNaam + " niet actief: " + t);
        }
    }

    /** Geld uit Essentials /sell dat niet via de GUI ging (of in de verkeerde wereld/gamemode): terugdraaien. */
    private void opEssentialsSaldo(Event e) throws Exception {
        if (!aan) return;
        Object cause = e.getClass().getMethod("getCause").invoke(e);
        if (cause == null || !"COMMAND_SELL".equals(cause.toString())) return;
        Player p = (Player) e.getClass().getMethod("getPlayer").invoke(e);
        BigDecimal oud = (BigDecimal) e.getClass().getMethod("getOldBalance").invoke(e);
        BigDecimal nieuw = (BigDecimal) e.getClass().getMethod("getNewBalance").invoke(e);
        if (p == null || oud == null || nieuw == null || nieuw.compareTo(oud) <= 0) return;
        Long tot = bezigMetGuiVerkoop.get(p.getUniqueId());
        boolean ok = tot != null && tot >= System.currentTimeMillis() && survival(p) && isVerkoopWereld(p.getWorld());
        if (ok) return;
        Method set = e.getClass().getMethod("setNewBalance", BigDecimal.class);
        set.invoke(e, oud);
        Ordely.regel(p, "Verkopen kan alleen via de Opkoper of de winkel, in " + verkoopWereldenTekst() + ". Je krijgt hier geen geld voor.");
        plugin.logboek().schrijf("WAARSCHUWING", p.getName(), p.getUniqueId(),
                "verkoop buiten de GUI tegengehouden in " + p.getWorld().getName() + " (" + p.getGameMode().name().toLowerCase(Locale.ROOT) + "), $" + nieuw.subtract(oud).toPlainString() + " niet uitbetaald", -1, "systeem", "economie");
    }

    /** QuickShop: geen handel in creative/toeschouwer of buiten de geld-werelden. */
    private void opQuickShopHandel(Event e) throws Exception {
        if (!aan) return;
        Object q = e.getClass().getMethod("getPurchaser").invoke(e);
        if (q == null) return;
        Class<?> qu = Class.forName("com.ghostchu.quickshop.api.obj.QUser", true, e.getClass().getClassLoader());
        Object op = qu.getMethod("getBukkitPlayer").invoke(q);
        if (!(op instanceof Optional<?> o) || o.isEmpty() || !(o.get() instanceof Player p)) return;
        String reden = null;
        if (!survival(p) && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) return;
        if (!survival(p)) reden = "Handelen in winkels kan niet in creative of als toeschouwer.";
        else if (!isGeldWereld(p.getWorld())) reden = "Geld werkt niet in deze wereld.";
        if (reden == null) return;
        boolean gedaan = false;
        try {
            Class<?> qc = Class.forName("com.ghostchu.quickshop.api.event.QSCancellable", true, e.getClass().getClassLoader());
            qc.getMethod("setCancelled", boolean.class, net.kyori.adventure.text.Component.class)
                    .invoke(e, true, net.kyori.adventure.text.Component.text(reden));
            gedaan = true;
        } catch (ReflectiveOperationException ignored) { }
        if (!gedaan && e instanceof org.bukkit.event.Cancellable c) c.setCancelled(true);
        Ordely.regel(p, reden);
        if (!survival(p)) plugin.logboek().schrijf("WAARSCHUWING", p.getName(), p.getUniqueId(),
                "QuickShop-handel in " + p.getGameMode().name().toLowerCase(Locale.ROOT) + " tegengehouden", -1, "systeem", "economie");
    }
}
