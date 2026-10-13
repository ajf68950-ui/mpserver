package toolwise;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public final class Toolwise extends JavaPlugin implements Listener, TabCompleter {

    private final Instellingen inst = new Instellingen();
    private final Kiezer kiezer = new Kiezer(inst);
    private NamespacedKey sleutelGereedschap;
    private NamespacedKey sleutelWapens;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        inst.lees(getConfig(), getLogger());
        sleutelGereedschap = new NamespacedKey(this, "gereedschap");
        sleutelWapens = new NamespacedKey(this, "wapens");
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("toolwise");
        if (cmd != null) cmd.setTabCompleter(this);
        getLogger().info("Toolwise staat aan: gereedschap en wapens wisselen automatisch.");
    }

    // ------------------------------------------------------------------ events --

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opBlokSlaan(BlockDamageEvent e) {
        if (e.getInstaBreak()) return;
        Player p = e.getPlayer();
        if (!magWisselen(p) || !staatAan(p, sleutelGereedschap, inst.gereedschapStandaard)) return;
        int slot = kiezer.besteGereedschap(p.getInventory(), e.getBlock());
        if (slot >= 0) pak(p, slot);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void opAanvallen(PrePlayerAttackEntityEvent e) {
        if (!e.willAttack()) return;
        Entity doel = e.getAttacked();
        if (!(doel instanceof LivingEntity) || doel instanceof ArmorStand) return;
        if (doel instanceof Player && !inst.wapensTegenSpelers) return;
        Player p = e.getPlayer();
        if (!magWisselen(p) || !staatAan(p, sleutelWapens, inst.wapensStandaard)) return;
        int slot = kiezer.besteWapen(p.getInventory(), doel);
        if (slot >= 0) pak(p, slot);
    }

    private boolean magWisselen(Player p) {
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
        if (!p.hasPermission("toolwise.use")) return false;
        if (inst.nietBijSluipen && p.isSneaking()) return false;
        return !inst.uitgeschakeldeWerelden.contains(p.getWorld().getName().toLowerCase(Locale.ROOT));
    }

    /** Neemt het item uit {@code slot} in de hand. */
    private void pak(Player p, int slot) {
        PlayerInventory inv = p.getInventory();
        if (slot <= 8) {
            inv.setHeldItemSlot(slot);
        } else {
            // Uit de inventaris: liefst naar een leeg vak in de hotbar, anders ruilen met het vak in de hand.
            ItemStack item = inv.getItem(slot);
            int leeg = -1;
            for (int i = 0; i <= 8; i++) {
                ItemStack h = inv.getItem(i);
                if (h == null || h.getType().isAir()) { leeg = i; break; }
            }
            if (leeg >= 0) {
                inv.setItem(leeg, item);
                inv.setItem(slot, null);
                inv.setHeldItemSlot(leeg);
            } else {
                int hand = inv.getHeldItemSlot();
                inv.setItem(slot, inv.getItem(hand));
                inv.setItem(hand, item);
            }
        }
        if (inst.melding) {
            ItemStack nu = inv.getItemInMainHand();
            p.sendActionBar(Component.text("Gewisseld naar ", NamedTextColor.GRAY).append(nu.effectiveName().colorIfAbsent(NamedTextColor.YELLOW)));
        }
    }

    // ---------------------------------------------------------- instellingen speler --

    private boolean staatAan(Player p, NamespacedKey sleutel, boolean standaard) {
        Byte b = p.getPersistentDataContainer().get(sleutel, PersistentDataType.BYTE);
        return b == null ? standaard : b == 1;
    }

    private void zet(Player p, NamespacedKey sleutel, boolean aan) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.set(sleutel, PersistentDataType.BYTE, (byte) (aan ? 1 : 0));
    }

    // ---------------------------------------------------------------- commando --

    private static Component tekst(String s) {
        return Component.text("[Toolwise] ", NamedTextColor.GOLD).append(Component.text(s, NamedTextColor.YELLOW));
    }

    private static String aanUit(boolean b) {
        return b ? "aan" : "uit";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("toolwise.admin")) {
                sender.sendMessage(tekst("Dat mag je niet."));
                return true;
            }
            reloadConfig();
            inst.lees(getConfig(), getLogger());
            sender.sendMessage(tekst("config.yml opnieuw geladen."));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(tekst("Gebruik vanaf de console alleen /toolwise reload."));
            return true;
        }
        if (!p.hasPermission("toolwise.use")) {
            p.sendMessage(tekst("Automatisch wisselen staat voor jou niet open."));
            return true;
        }

        boolean g = staatAan(p, sleutelGereedschap, inst.gereedschapStandaard);
        boolean w = staatAan(p, sleutelWapens, inst.wapensStandaard);

        if (args.length == 0) {
            // Zonder argument: alles omzetten (staat iets aan, dan alles uit; anders alles aan).
            boolean nieuw = !(g || w);
            zet(p, sleutelGereedschap, nieuw);
            zet(p, sleutelWapens, nieuw);
            p.sendMessage(tekst("Automatisch wisselen staat nu " + aanUit(nieuw) + "."));
            return true;
        }

        String a = args[0].toLowerCase(Locale.ROOT);
        switch (a) {
            case "aan", "on", "uit", "off" -> {
                boolean aan = a.equals("aan") || a.equals("on");
                zet(p, sleutelGereedschap, aan);
                zet(p, sleutelWapens, aan);
                p.sendMessage(tekst("Automatisch wisselen staat nu " + aanUit(aan) + "."));
            }
            case "gereedschap", "tools" -> {
                boolean aan = args.length >= 2 ? isAan(args[1]) : !g;
                zet(p, sleutelGereedschap, aan);
                p.sendMessage(tekst("Gereedschap wisselen staat nu " + aanUit(aan) + "."));
            }
            case "wapens", "wapen", "weapons" -> {
                boolean aan = args.length >= 2 ? isAan(args[1]) : !w;
                zet(p, sleutelWapens, aan);
                p.sendMessage(tekst("Wapens wisselen staat nu " + aanUit(aan) + "."));
            }
            case "status", "info" -> {
                p.sendMessage(tekst("Gereedschap: " + aanUit(g) + ", wapens: " + aanUit(w) + "."));
                if (inst.nietBijSluipen) p.sendMessage(tekst("Houd shift ingedrukt om even niet te wisselen."));
            }
            default -> p.sendMessage(tekst("Gebruik: /toolwise [aan | uit | gereedschap [aan|uit] | wapens [aan|uit] | status]"));
        }
        return true;
    }

    private static boolean isAan(String s) {
        String a = s.toLowerCase(Locale.ROOT);
        return a.equals("aan") || a.equals("on") || a.equals("true") || a.equals("ja");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> opties;
        if (args.length == 1) {
            opties = sender.hasPermission("toolwise.admin")
                    ? List.of("aan", "uit", "gereedschap", "wapens", "status", "reload")
                    : List.of("aan", "uit", "gereedschap", "wapens", "status");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("gereedschap") || args[0].toLowerCase(Locale.ROOT).startsWith("wapen"))) {
            opties = List.of("aan", "uit");
        } else {
            return List.of();
        }
        String begin = args[args.length - 1].toLowerCase(Locale.ROOT);
        return opties.stream().filter(o -> o.startsWith(begin)).toList();
    }
}
