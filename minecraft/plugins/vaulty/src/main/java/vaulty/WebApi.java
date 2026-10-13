package vaulty;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Kleine HTTP-verbinding voor de website, alleen op 127.0.0.1 en met een token.
 * Alle acties op kluizen en geld worden op de main thread uitgevoerd.
 */
final class WebApi {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final Vaulty plugin;
    private final Gson gson = new Gson();
    private HttpServer server;
    private ExecutorService pool;

    WebApi(Vaulty plugin) { this.plugin = plugin; }

    void start() throws IOException {
        String host = plugin.getConfig().getString("web.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("web.poort", 8095);
        server = HttpServer.create(new InetSocketAddress(host, port), 16);
        pool = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "Vaulty-web"); t.setDaemon(true); return t; });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        plugin.getLogger().info("Verbinding voor de website staat klaar op " + host + ":" + port);
    }

    void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    // ---------------------------------------------------------------- routing
    private void handle(HttpExchange ex) throws IOException {
        try {
            String token = plugin.getConfig().getString("web.token", "");
            String given = ex.getRequestHeaders().getFirst("X-Token");
            if (token.isBlank() || given == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, error("Geen toegang."));
                return;
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            JsonObject body = new JsonObject();
            if (method.equals("POST")) {
                try (InputStream in = ex.getRequestBody()) {
                    byte[] raw = in.readNBytes(65536);
                    String s = new String(raw, StandardCharsets.UTF_8);
                    if (!s.isBlank()) body = JsonParser.parseString(s).getAsJsonObject();
                }
            }
            String[] parts = path.replaceAll("^/+|/+$", "").split("/");
            JsonObject result;
            if (path.equals("/status")) {
                result = new JsonObject();
                result.addProperty("ok", true);
                result.addProperty("version", plugin.getPluginMeta().getVersion());
                result.addProperty("economy", plugin.economy() != null);
            } else if (path.equals("/link/start") && method.equals("POST")) {
                String name = str(body, "name");
                if (!name.matches("[A-Za-z0-9_.*\\-]{2,20}")) { send(ex, 400, error("Dat is geen geldige Minecraft-naam.")); return; }
                result = sync(() -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("code", plugin.links().create(name));
                    o.addProperty("minutes", plugin.getConfig().getLong("koppelen.geldig-minuten", 10));
                    Player online = Bukkit.getPlayerExact(name);
                    o.addProperty("online", online != null);
                    return o;
                });
            } else if (path.equals("/link/check")) {
                String code = queryParam(query, "code");
                result = sync(() -> {
                    JsonObject o = new JsonObject();
                    LinkManager.Code c = plugin.links().get(code);
                    if (c == null) o.addProperty("state", "unknown");
                    else if (c.uuid != null) {
                        o.addProperty("state", "done");
                        o.addProperty("uuid", c.uuid.toString());
                        o.addProperty("name", c.name);
                        plugin.links().consume(code);
                    } else if (c.expires < System.currentTimeMillis()) o.addProperty("state", "expired");
                    else o.addProperty("state", "pending");
                    return o;
                });
            } else if (path.equals("/market")) {
                result = sync(this::marketAll);
            } else if (parts.length >= 2 && parts[0].equals("player")) {
                UUID uuid = plugin.uuidOf(parts[1]);
                if (uuid == null) { send(ex, 400, error("Ongeldige speler.")); return; }
                String action = parts.length >= 3 ? parts[2] : "";
                final JsonObject b = body;
                result = switch (action) {
                    case "" -> sync(() -> playerState(uuid));
                    case "buy" -> sync(() -> buy(uuid, b));
                    case "sell" -> sync(() -> sell(uuid, b));
                    case "buy-chest" -> sync(() -> buyChest(uuid));
                    case "market-list" -> sync(() -> marketList(uuid, b));
                    case "rename" -> sync(() -> rename(uuid, b));
                    case "sort" -> sync(() -> sortKluis(uuid, b));
                    case "market-cancel" -> sync(() -> marketCancel(uuid, b));
                    case "market-buy" -> sync(() -> marketBuy(uuid, b));
                    default -> null;
                };
                if (result == null) { send(ex, 404, error("Deze functie kent de plugin nog niet. Herstart MeediaSMP zodat de nieuwste Vaulty geladen wordt.")); return; }
            } else {
                send(ex, 404, error("Deze functie kent de plugin nog niet. Herstart MeediaSMP zodat de nieuwste Vaulty geladen wordt."));
                return;
            }
            send(ex, result.has("error") ? 400 : 200, result);
        } catch (TimeoutException te) {
            send(ex, 503, error("De server is druk. Probeer het zo nog eens."));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Fout in de website-verbinding", e);
            send(ex, 500, error("Er ging iets mis in de plugin."));
        }
    }

    private JsonObject sync(Supplier<JsonObject> task) throws Exception {
        Future<JsonObject> f = Bukkit.getScheduler().callSyncMethod(plugin, task::get);
        try {
            return f.get(8, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            // Niet annuleren als hij al loopt: dan zou de website een fout tonen terwijl de actie wel lukt.
            f.cancel(false);
            throw te;
        }
    }

    // ---------------------------------------------------------------- acties
    private OfflinePlayer offline(UUID uuid) { return Bukkit.getOfflinePlayer(uuid); }

    private Kluis kluis(UUID uuid) {
        OfflinePlayer op = offline(uuid);
        return plugin.kluizen().get(uuid, op.getName());
    }

    /** Zoals kluis(uuid), maar gebruikt de naam van het website-account als de server de speler (nog) niet kent. */
    private Kluis kluis(UUID uuid, JsonObject b) {
        OfflinePlayer op = offline(uuid);
        String n = op.getName();
        if (n == null) {
            String hint = str(b, "name");
            if (hint.matches("[A-Za-z0-9_.*\\-]{2,20}")) n = hint;
        }
        return plugin.kluizen().get(uuid, n);
    }

    private JsonObject playerState(UUID uuid) {
        OfflinePlayer op = offline(uuid);
        Kluis k = kluis(uuid);
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", op.getName() != null ? op.getName() : k.name());
        o.addProperty("online", op.isOnline());
        Economy eco = plugin.economy();
        if (eco != null) o.addProperty("balance", eco.getBalance(op));
        o.addProperty("chests", k.chests());
        o.addProperty("maxChests", plugin.maxChests());
        double next = plugin.nextChestPrice(k);
        if (next >= 0) o.addProperty("nextChestPrice", next);
        JsonArray chests = new JsonArray();
        for (int i = 0; i < k.chests(); i++) {
            JsonArray items = new JsonArray();
            Inventory inv = k.inventory(i);
            for (int s = 0; s < Kluis.SIZE; s++) {
                ItemStack it = inv.getItem(s);
                if (it == null || it.getType().isAir()) continue;
                JsonObject io = describe(it);
                io.addProperty("slot", s);
                items.add(io);
            }
            chests.add(items);
        }
        o.add("vault", chests);
        JsonArray names = new JsonArray();
        for (int i = 0; i < k.chests(); i++) names.add(k.displayName(i));
        o.add("names", names);
        o.addProperty("listings", plugin.market().countOf(uuid));
        return o;
    }

    private JsonObject buy(UUID uuid, JsonObject b) {
        String give = str(b, "give");
        int amount = b.has("amount") ? b.get("amount").getAsInt() : 0;
        double price = b.has("price") ? b.get("price").getAsDouble() : -1;
        if (give.isBlank() || amount < 1 || amount > 64 * 54 || price < 0) return error("Ongeldige aankoop.");
        ItemStack proto;
        try {
            proto = Bukkit.getItemFactory().createItemStack(give);
        } catch (Exception e) {
            return error("Dit item kan niet via de website gekocht worden.");
        }
        if (proto.getType().isAir()) return error("Dit item kan niet via de website gekocht worden.");
        List<ItemStack> stacks = new ArrayList<>();
        int max = Math.max(1, proto.getMaxStackSize());
        int left = amount;
        while (left > 0) {
            ItemStack s = proto.clone();
            s.setAmount(Math.min(max, left));
            stacks.add(s);
            left -= s.getAmount();
        }
        Kluis k = kluis(uuid, b);
        if (!fits(k, stacks)) return error("Er is niet genoeg ruimte in je kluis. Maak ruimte of koop een extra kist.");
        OfflinePlayer op = offline(uuid);
        Economy eco = plugin.economy();
        if (price > 0) {
            if (eco == null) return error("De economy is niet beschikbaar.");
            if (!eco.has(op, price)) return error("Je hebt niet genoeg geld.");
            if (!eco.withdrawPlayer(op, price).transactionSuccess()) return error("Betalen is niet gelukt.");
        }
        for (ItemStack s : stacks) {
            ItemStack rest = s;
            for (Inventory inv : k.inventories()) {
                Map<Integer, ItemStack> over = inv.addItem(rest);
                if (over.isEmpty()) { rest = null; break; }
                rest = over.values().iterator().next();
            }
            if (rest != null) plugin.getLogger().severe("Onverwacht: geen ruimte meer bij aankoop voor " + who(op, k) + " (" + rest + ")");
        }
        plugin.kluizen().save(k);
        plugin.getLogger().info(who(op, k) + " kocht via de website " + amount + "x " + proto.getType().getKey().getKey() + " voor $" + Vaulty.fmt(price));
        notify(op, "Gekocht via de website: " + amount + "x " + niceName(proto) + ". Het ligt in je kluis.");
        return playerState(uuid);
    }

    private boolean fits(Kluis k, List<ItemStack> stacks) {
        List<Inventory> copies = new ArrayList<>();
        for (Inventory inv : k.inventories()) {
            Inventory c = Bukkit.createInventory(null, Kluis.SIZE);
            ItemStack[] cont = inv.getContents();
            for (int i = 0; i < cont.length; i++) if (cont[i] != null) cont[i] = cont[i].clone();
            c.setContents(cont);
            copies.add(c);
        }
        for (ItemStack s : stacks) {
            ItemStack rest = s.clone();
            for (Inventory c : copies) {
                Map<Integer, ItemStack> over = c.addItem(rest);
                if (over.isEmpty()) { rest = null; break; }
                rest = over.values().iterator().next();
            }
            if (rest != null) return false;
        }
        return true;
    }

    private JsonObject sell(UUID uuid, JsonObject b) {
        int chest = b.has("chest") ? b.get("chest").getAsInt() : -1;
        int slot = b.has("slot") ? b.get("slot").getAsInt() : -1;
        int amount = b.has("amount") ? b.get("amount").getAsInt() : 0;
        double unit = b.has("unitPrice") ? b.get("unitPrice").getAsDouble() : -1;
        String material = str(b, "material");
        Kluis k = kluis(uuid, b);
        if (chest < 0 || chest >= k.chests() || slot < 0 || slot >= Kluis.SIZE || amount < 1 || unit < 0) return error("Ongeldige verkoop.");
        Inventory inv = k.inventory(chest);
        ItemStack it = inv.getItem(slot);
        if (it == null || it.getType().isAir()) return error("Dit vak is leeg. Ververs de pagina.");
        if (!it.getType().getKey().getKey().equals(material)) return error("Er ligt iets anders in dit vak. Ververs de pagina.");
        Material mat = it.getType();
        if (!it.isSimilar(new ItemStack(mat))) return error("Dit item heeft een naam, enchant of andere extra's en kan niet verkocht worden.");
        if (amount > it.getAmount()) return error("Je hebt er maar " + it.getAmount() + " in dit vak.");
        Economy eco = plugin.economy();
        if (eco == null) return error("De economy is niet beschikbaar.");
        OfflinePlayer op = offline(uuid);
        double total = Math.round(unit * amount * 100.0) / 100.0;
        if (it.getAmount() == amount) inv.setItem(slot, null);
        else it.setAmount(it.getAmount() - amount);
        if (!eco.depositPlayer(op, total).transactionSuccess()) {
            // terugzetten
            ItemStack back = new ItemStack(mat, amount);
            ItemStack cur = inv.getItem(slot);
            if (cur == null) inv.setItem(slot, back); else cur.setAmount(cur.getAmount() + amount);
            return error("Uitbetalen is niet gelukt.");
        }
        plugin.kluizen().save(k);
        plugin.getLogger().info(who(op, k) + " verkocht via de website " + amount + "x " + material + " voor $" + Vaulty.fmt(total));
        notify(op, "Verkocht via de website: " + amount + "x " + niceName(new ItemStack(mat)) + " voor $" + Vaulty.fmt(total) + ".");
        return playerState(uuid);
    }

    private JsonObject buyChest(UUID uuid) {
        Kluis k = kluis(uuid);
        String res = plugin.buyChest(k, offline(uuid));
        if (res != null) return error(res);
        return playerState(uuid);
    }



    // ------------------------------------------------------- naam, sorteren
    private JsonObject rename(UUID uuid, JsonObject b) {
        Kluis k = kluis(uuid, b);
        int chest = b.has("chest") ? b.get("chest").getAsInt() : -1;
        if (chest < 0 || chest >= k.chests()) return error("Deze kist bestaat niet.");
        String set = k.chestName(chest, str(b, "chestName"));
        plugin.kluizen().save(k);
        notify(offline(uuid), set == null ? "Kist " + (chest + 1) + " heet weer gewoon Kist " + (chest + 1) + "." : "Kist " + (chest + 1) + " heet nu " + set + ".");
        return playerState(uuid);
    }

    private JsonObject sortKluis(UUID uuid, JsonObject b) {
        Kluis k = kluis(uuid, b);
        int[] r = k.sort();
        plugin.kluizen().save(k);
        JsonObject o = playerState(uuid);
        o.addProperty("sortedFrom", r[0]);
        o.addProperty("sortedTo", r[1]);
        return o;
    }

    // ---------------------------------------------------------------- markt
    private JsonObject marketAll() {
        MarketManager m = plugin.market();
        JsonObject o = new JsonObject();
        o.addProperty("enabled", m.enabled());
        o.addProperty("feePercent", m.feePercent());
        o.addProperty("maxPerPlayer", m.maxPerPlayer());
        o.addProperty("maxPrice", m.maxPrice());
        JsonArray arr = new JsonArray();
        for (MarketManager.Listing l : m.all()) {
            JsonObject io = describe(l.item);
            io.addProperty("id", l.id);
            io.addProperty("seller", l.seller.toString());
            OfflinePlayer sp = offline(l.seller);
            io.addProperty("sellerName", sp.getName() != null ? sp.getName() : l.sellerName);
            io.addProperty("price", l.price);
            io.addProperty("created", l.created);
            arr.add(io);
        }
        o.add("listings", arr);
        return o;
    }

    private JsonObject marketList(UUID uuid, JsonObject b) {
        MarketManager m = plugin.market();
        if (!m.enabled()) return error("De markt staat uit.");
        int chest = b.has("chest") ? b.get("chest").getAsInt() : -1;
        int slot = b.has("slot") ? b.get("slot").getAsInt() : -1;
        int amount = b.has("amount") ? b.get("amount").getAsInt() : 0;
        double price = b.has("price") ? b.get("price").getAsDouble() : -1;
        String material = str(b, "material");
        Kluis k = kluis(uuid, b);
        if (chest < 0 || chest >= k.chests() || slot < 0 || slot >= Kluis.SIZE || amount < 1) return error("Ongeldig aanbod.");
        if (!(price > 0) || Double.isInfinite(price)) return error("Vul een prijs in die hoger is dan 0.");
        price = Math.round(price * 100.0) / 100.0;
        if (price > m.maxPrice()) return error("De prijs mag maximaal $" + Vaulty.fmt(m.maxPrice()) + " zijn.");
        if (m.countOf(uuid) >= m.maxPerPlayer()) return error("Je hebt al " + m.maxPerPlayer() + " dingen te koop staan. Haal er eerst een terug.");
        Inventory inv = k.inventory(chest);
        ItemStack it = inv.getItem(slot);
        if (it == null || it.getType().isAir()) return error("Dit vak is leeg. Ververs de pagina.");
        if (!it.getType().getKey().getKey().equals(material)) return error("Er ligt iets anders in dit vak. Ververs de pagina.");
        if (amount > it.getAmount()) return error("Je hebt er maar " + it.getAmount() + " in dit vak.");
        ItemStack listed = it.clone();
        listed.setAmount(amount);
        if (it.getAmount() == amount) inv.setItem(slot, null);
        else it.setAmount(it.getAmount() - amount);
        OfflinePlayer op = offline(uuid);
        MarketManager.Listing l = m.add(uuid, who(op, k), listed, price);
        plugin.kluizen().save(k);
        m.save();
        plugin.getLogger().info(who(op, k) + " zette op de markt: " + amount + "x " + material + " voor $" + Vaulty.fmt(price) + " (" + l.id + ")");
        notify(op, "Op de markt gezet: " + amount + "x " + niceName(listed) + " voor $" + Vaulty.fmt(price) + ".");
        return playerState(uuid);
    }

    private JsonObject marketCancel(UUID uuid, JsonObject b) {
        MarketManager m = plugin.market();
        MarketManager.Listing l = m.get(str(b, "id"));
        if (l == null) return error("Dit aanbod bestaat niet meer. Misschien is het net verkocht.");
        if (!l.seller.equals(uuid)) return error("Dit is niet jouw aanbod.");
        Kluis k = kluis(uuid, b);
        if (!fits(k, List.of(l.item))) return error("Er is niet genoeg ruimte in je kluis om het terug te leggen.");
        addToKluis(k, l.item, offline(uuid));
        m.remove(l.id);
        plugin.kluizen().save(k);
        m.save();
        plugin.getLogger().info(who(offline(uuid), k) + " haalde van de markt terug: " + l.item.getAmount() + "x " + l.item.getType().getKey().getKey() + " (" + l.id + ")");
        return playerState(uuid);
    }

    private JsonObject marketBuy(UUID uuid, JsonObject b) {
        MarketManager m = plugin.market();
        if (!m.enabled()) return error("De markt staat uit.");
        MarketManager.Listing l = m.get(str(b, "id"));
        if (l == null) return error("Dit aanbod bestaat niet meer. Misschien is het net verkocht.");
        if (l.seller.equals(uuid)) return error("Dit is je eigen aanbod. Haal het terug als je het wilt houden.");
        if (b.has("price") && Math.abs(b.get("price").getAsDouble() - l.price) > 0.001) return error("De prijs is veranderd. Ververs de pagina.");
        Economy eco = plugin.economy();
        if (eco == null) return error("De economy is niet beschikbaar.");
        Kluis k = kluis(uuid, b);
        if (!fits(k, List.of(l.item))) return error("Er is niet genoeg ruimte in je kluis. Maak ruimte of koop een extra kist.");
        OfflinePlayer buyer = offline(uuid);
        OfflinePlayer seller = offline(l.seller);
        if (!eco.has(buyer, l.price)) return error("Je hebt niet genoeg geld.");
        if (!eco.withdrawPlayer(buyer, l.price).transactionSuccess()) return error("Betalen is niet gelukt.");
        double payout = Math.round(l.price * (100.0 - m.feePercent())) / 100.0;
        if (payout > 0 && !eco.depositPlayer(seller, payout).transactionSuccess()) {
            eco.depositPlayer(buyer, l.price);
            return error("Uitbetalen aan de verkoper is niet gelukt. Je geld is teruggezet.");
        }
        addToKluis(k, l.item, buyer);
        m.remove(l.id);
        String itemText = l.item.getAmount() + "x " + niceName(l.item);
        String buyerName = who(buyer, k);
        String sellerText = buyerName + " kocht " + itemText + " voor $" + Vaulty.fmt(l.price) + (payout != l.price ? " (je kreeg $" + Vaulty.fmt(payout) + ")" : "") + ".";
        Player sp = seller.getPlayer();
        if (sp != null) sp.sendMessage(Vaulty.msg("Markt: " + sellerText));
        else m.addMail(l.seller, sellerText);
        plugin.kluizen().save(k);
        m.save();
        plugin.getLogger().info(buyerName + " kocht op de markt " + itemText + " van " + l.sellerName + " voor $" + Vaulty.fmt(l.price) + " (" + l.id + ")");
        notify(buyer, "Gekocht op de markt: " + itemText + ". Het ligt in je kluis.");
        return playerState(uuid);
    }

    /** Legt een item in de eerste vrije plek(ken) van de kluis. Controleer eerst met fits(). */
    private void addToKluis(Kluis k, ItemStack item, OfflinePlayer op) {
        ItemStack rest = item.clone();
        for (Inventory inv : k.inventories()) {
            Map<Integer, ItemStack> over = inv.addItem(rest);
            if (over.isEmpty()) { rest = null; break; }
            rest = over.values().iterator().next();
        }
        if (rest != null) plugin.getLogger().severe("Onverwacht: geen ruimte in de kluis van " + who(op, k) + " voor " + rest);
    }

    private static String who(OfflinePlayer op, Kluis k) { return op.getName() != null ? op.getName() : k.name(); }

    private void notify(OfflinePlayer op, String text) {
        Player p = op.getPlayer();
        if (p != null) p.sendMessage(Vaulty.msg(text));
    }

    // ---------------------------------------------------------- beschrijven
    private JsonObject describe(ItemStack it) {
        JsonObject o = new JsonObject();
        o.addProperty("material", it.getType().getKey().getKey());
        o.addProperty("amount", it.getAmount());
        o.addProperty("plain", it.isSimilar(new ItemStack(it.getType())));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) o.addProperty("name", plain(meta.displayName()));
            else if (meta.hasItemName()) o.addProperty("name", plain(meta.itemName()));
            if (meta.hasLore() && meta.lore() != null) {
                JsonArray lore = new JsonArray();
                for (Component c : meta.lore()) lore.add(plain(c));
                o.add("lore", lore);
            }
            JsonArray ench = new JsonArray();
            for (Map.Entry<Enchantment, Integer> e : it.getEnchantments().entrySet()) ench.add(e.getKey().getKey().getKey() + " " + e.getValue());
            if (meta instanceof EnchantmentStorageMeta esm) {
                for (Map.Entry<Enchantment, Integer> e : esm.getStoredEnchants().entrySet()) ench.add(e.getKey().getKey().getKey() + " " + e.getValue());
            }
            if (!ench.isEmpty()) o.add("enchants", ench);
            if (meta instanceof PotionMeta pm && pm.getBasePotionType() != null) o.addProperty("potion", pm.getBasePotionType().getKey().getKey());
            if (meta instanceof Damageable d && d.hasDamage() && it.getType().getMaxDurability() > 0) {
                o.addProperty("durability", it.getType().getMaxDurability() - d.getDamage());
                o.addProperty("maxDurability", it.getType().getMaxDurability());
            }
        }
        return o;
    }

    private static String niceName(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) return plain(m.displayName());
        if (m != null && m.hasItemName()) return plain(m.itemName());
        String k = it.getType().getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    private static String plain(Component c) { return c == null ? "" : PLAIN.serialize(c); }

    // ---------------------------------------------------------------- hulp
    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : "";
    }

    private static String queryParam(String q, String key) {
        if (q == null) return "";
        for (String p : q.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return "";
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    private void send(HttpExchange ex, int status, JsonObject o) throws IOException {
        byte[] b = gson.toJson(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
