package ritly;

import org.bukkit.Material;

import java.util.Locale;

/** Soort voertuig: bepaalt hoe het rijdt, vaart of vliegt. */
enum Soort {
    FIETS("Fiets", Material.SADDLE, false, "block.note_block.bell", 1.6f),
    AUTO("Auto", Material.MINECART, true, "block.note_block.didgeridoo", 1.3f),
    BOOT("Boot", Material.OAK_BOAT, true, "item.goat_horn.sound.6", 1.0f),
    VLIEGTUIG("Vliegtuig", Material.ELYTRA, true, "block.note_block.didgeridoo", 0.8f);

    final String naam;
    final Material icoon;
    final boolean brandstof;
    final String toeter;
    final float toonhoogte;

    Soort(String naam, Material icoon, boolean brandstof, String toeter, float toonhoogte) {
        this.naam = naam;
        this.icoon = icoon;
        this.brandstof = brandstof;
        this.toeter = toeter;
        this.toonhoogte = toonhoogte;
    }

    String sleutel() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Soort van(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
