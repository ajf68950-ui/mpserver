package toolwise;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Bepaalt welk vak in de inventaris het beste gereedschap of wapen bevat.
 * Geeft -1 terug als er niets beters is dan wat de speler al vasthoudt.
 */
final class Kiezer {

    private final Instellingen inst;

    Kiezer(Instellingen inst) {
        this.inst = inst;
    }

    // ------------------------------------------------------------ gereedschap --

    int besteGereedschap(PlayerInventory inv, Block blok) {
        BlockData data = blok.getBlockData();
        ItemStack inHand = inv.getItemInMainHand();
        float handSnelheid = data.getDestroySpeed(new ItemStack(Material.AIR), true);
        boolean juistNodig = data.requiresCorrectToolForDrops();

        float huidig = gereedschapScore(data, inHand, juistNodig, handSnelheid, false);
        if (huidig > 0 && inst.alleenAlsOngeschikt) return -1; // speler heeft al iets geschikts vast

        int beste = -1;
        float besteScore = Math.max(huidig, 0);
        int laatste = inst.ookUitInventaris ? 35 : 8;
        for (int slot = 0; slot <= laatste; slot++) {
            if (slot == inv.getHeldItemSlot()) continue;
            float score = gereedschapScore(data, inv.getItem(slot), juistNodig, handSnelheid, true);
            if (score > besteScore) {
                besteScore = score;
                beste = slot;
            }
        }
        return beste;
    }

    /** 0 = niet geschikt; hoger = sneller. */
    private float gereedschapScore(BlockData data, ItemStack item, boolean juistNodig, float handSnelheid, boolean bijnaKapotTelt) {
        if (item == null || item.getType().isAir()) return 0;
        if (bijnaKapotTelt && bijnaKapot(item)) return 0;
        // Bij blokken zonder gereedschapseis noemt Paper elk item "preferred", ook brood;
        // daarom telt alleen echt sneller dan met de hand.
        if (juistNodig && !data.isPreferredTool(item)) return 0; // anders valt er niets uit het blok
        float snelheid = data.getDestroySpeed(item, true);
        return snelheid > handSnelheid ? snelheid : 0;
    }

    // ------------------------------------------------------------------ wapens --

    int besteWapen(PlayerInventory inv, Entity doelwit) {
        ItemStack inHand = inv.getItemInMainHand();
        if (isWapen(inHand) && inst.alleenAlsOngeschikt) return -1;

        double huidig = isWapen(inHand) ? wapenScore(inHand, doelwit) : 0;
        int beste = -1;
        double besteScore = huidig;
        int laatste = inst.ookUitInventaris ? 35 : 8;
        for (int slot = 0; slot <= laatste; slot++) {
            if (slot == inv.getHeldItemSlot()) continue;
            ItemStack item = inv.getItem(slot);
            if (!isWapen(item) || bijnaKapot(item)) continue;
            double score = wapenScore(item, doelwit);
            if (score > besteScore) {
                besteScore = score;
                beste = slot;
            }
        }
        return beste;
    }

    static boolean isWapen(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material m = item.getType();
        return Tag.ITEMS_SWORDS.isTagged(m) || Tag.ITEMS_AXES.isTagged(m) || Tag.ITEMS_SPEARS.isTagged(m)
                || m == Material.MACE || m == Material.TRIDENT;
    }

    private double wapenScore(ItemStack item, Entity doelwit) {
        double schade = schade(item, doelwit);
        return switch (inst.wapenVoorkeur) {
            // Zwaarden altijd eerst (snel en met zwaai-aanval), daarna de rest op schade.
            case ZWAARD -> (Tag.ITEMS_SWORDS.isTagged(item.getType()) ? 1000 : 0) + schade;
            case SCHADE -> schade;
            case DPS -> schade * aanvalSnelheid(item);
        };
    }

    /** Schade per volle klap, inclusief Sharpness/Smite/Bane of Arthropods. */
    static double schade(ItemStack item, Entity doelwit) {
        double schade = 1.0 + som(item, Attribute.ATTACK_DAMAGE);
        int scherpte = item.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (scherpte > 0) schade += 0.5 * scherpte + 0.5;
        if (doelwit != null) {
            if (Tag.ENTITY_TYPES_SENSITIVE_TO_SMITE.isTagged(doelwit.getType()))
                schade += 2.5 * item.getEnchantmentLevel(Enchantment.SMITE);
            if (Tag.ENTITY_TYPES_SENSITIVE_TO_BANE_OF_ARTHROPODS.isTagged(doelwit.getType()))
                schade += 2.5 * item.getEnchantmentLevel(Enchantment.BANE_OF_ARTHROPODS);
        }
        return schade;
    }

    static double aanvalSnelheid(ItemStack item) {
        return Math.max(0.1, 4.0 + som(item, Attribute.ATTACK_SPEED));
    }

    private static double som(ItemStack item, Attribute attribuut) {
        ItemAttributeModifiers mods = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (mods == null) return 0;
        double som = 0;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (e.attribute() != attribuut) continue;
            EquipmentSlotGroup groep = e.getGroup();
            if (groep != EquipmentSlotGroup.MAINHAND && groep != EquipmentSlotGroup.HAND && groep != EquipmentSlotGroup.ANY) continue;
            if (e.modifier().getOperation() == AttributeModifier.Operation.ADD_NUMBER) som += e.modifier().getAmount();
        }
        return som;
    }

    // ------------------------------------------------------------- duurzaamheid --

    private boolean bijnaKapot(ItemStack item) {
        if (inst.beschermDuurzaamheid <= 0 || item.hasData(DataComponentTypes.UNBREAKABLE)) return false;
        Integer max = item.getData(DataComponentTypes.MAX_DAMAGE);
        if (max == null || max <= 0) return false;
        Integer schade = item.getData(DataComponentTypes.DAMAGE);
        int over = max - (schade == null ? 0 : schade);
        return over <= inst.beschermDuurzaamheid;
    }
}
