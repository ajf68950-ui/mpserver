package ritly;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Een voertuigmodel: de vorm (blokken), zitplaatsen en de rij-eigenschappen. */
final class Model {

    /** Een deel van het voertuig: een (uitgerekt) blok. */
    static final class Deel {
        final String blok;          // zoals in het bestand, bijv. white_concrete of redstone_lamp[lit=true]
        final Vector3f van;         // hoek met de laagste coordinaten
        final Vector3f maat;        // grootte
        final boolean lak;          // krijgt de lakkleur
        final char draai;           // 'x' = wiel, 'z' = propeller, 0 = niet
        final Quaternionf vast;     // vaste schuine stand (rond het midden van het deel)
        private final Map<DyeColor, BlockData> perKleur = new EnumMap<>(DyeColor.class);
        private BlockData basis;

        Deel(String blok, Vector3f van, Vector3f maat, char draai, Quaternionf vast) {
            this.blok = blok;
            this.van = van;
            this.maat = maat;
            this.draai = draai;
            this.vast = vast;
            this.lak = blok.startsWith("white_") || blok.startsWith("minecraft:white_");
        }

        BlockData data(DyeColor kleur) {
            if (!lak || kleur == null || kleur == DyeColor.WHITE) return basis();
            return perKleur.computeIfAbsent(kleur, k -> {
                String naam = blok.replaceFirst("white_", k.name().toLowerCase(Locale.ROOT) + "_");
                try {
                    return Bukkit.createBlockData(naam);
                } catch (IllegalArgumentException e) {
                    return basis();
                }
            });
        }

        BlockData basis() {
            if (basis == null) {
                try {
                    basis = Bukkit.createBlockData(blok);
                } catch (IllegalArgumentException e) {
                    basis = Material.STONE.createBlockData();
                }
            }
            return basis;
        }

        String alsTekst() {
            StringBuilder sb = new StringBuilder(blok);
            for (float f : new float[]{van.x, van.y, van.z, van.x + maat.x, van.y + maat.y, van.z + maat.z})
                sb.append(' ').append(rond(f));
            if (draai != 0) sb.append(" draai:").append(draai);
            return sb.toString();
        }

        static Deel lees(String regel) {
            String[] t = regel.trim().split("\\s+");
            if (t.length < 7) throw new IllegalArgumentException("te weinig getallen");
            float[] g = new float[6];
            for (int i = 0; i < 6; i++) g[i] = Float.parseFloat(t[i + 1].replace(',', '.'));
            Vector3f a = new Vector3f(Math.min(g[0], g[3]), Math.min(g[1], g[4]), Math.min(g[2], g[5]));
            Vector3f b = new Vector3f(Math.max(g[0], g[3]), Math.max(g[1], g[4]), Math.max(g[2], g[5]));
            char draai = 0;
            float rx = 0, ry = 0, rz = 0;
            for (int i = 7; i < t.length; i++) {
                String v = t[i].toLowerCase(Locale.ROOT);
                if (v.startsWith("draai:") && v.length() > 6) draai = v.charAt(6);
                else if (v.startsWith("rx:")) rx = Float.parseFloat(v.substring(3));
                else if (v.startsWith("ry:")) ry = Float.parseFloat(v.substring(3));
                else if (v.startsWith("rz:")) rz = Float.parseFloat(v.substring(3));
            }
            Quaternionf q = null;
            if (rx != 0 || ry != 0 || rz != 0)
                q = new Quaternionf().rotationYXZ((float) Math.toRadians(ry), (float) Math.toRadians(rx), (float) Math.toRadians(rz));
            String blok = t[0].toLowerCase(Locale.ROOT).replace("minecraft:", "");
            return new Deel(blok, a, b.sub(a, new Vector3f()), draai == 'x' || draai == 'z' ? draai : 0, q);
        }
    }

    final String id;
    String naam;
    final Soort soort;
    final List<Deel> delen;
    final List<Vector3f> zitten;
    final DyeColor kleur;
    final Material icoon;
    final double maxKmu, acceleratie, draaien, tank, verbruik, opstijgen, diepgang;
    final float breedte, lengte, hoogte, middenZ;
    final UUID eigenaar; // null = voor iedereen (winkels); anders zelf gebouwd door deze speler

    Model(String id, String naam, Soort soort, List<Deel> delen, List<Vector3f> zitten, DyeColor kleur, Material icoon,
          double maxKmu, double acceleratie, double draaien, double tank, double verbruik, double opstijgen, double diepgang,
          float[] botsing, UUID eigenaar) {
        this.id = id;
        this.naam = naam;
        this.soort = soort;
        this.delen = delen;
        this.zitten = zitten.isEmpty() ? List.of(new Vector3f(0, 0.6f, 0)) : zitten;
        this.kleur = kleur;
        this.icoon = icoon;
        this.maxKmu = maxKmu;
        this.acceleratie = acceleratie;
        this.draaien = draaien;
        this.tank = soort.brandstof ? tank : 0;
        this.verbruik = verbruik;
        this.opstijgen = opstijgen;
        this.diepgang = diepgang;
        this.eigenaar = eigenaar;
        float minX = 0, maxX = 0, minZ = 0, maxZ = 0, maxY = 1;
        boolean eerste = true;
        for (Deel d : delen) {
            if (eerste) {
                minX = d.van.x; maxX = d.van.x + d.maat.x; minZ = d.van.z; maxZ = d.van.z + d.maat.z; maxY = d.van.y + d.maat.y;
                eerste = false;
            }
            minX = Math.min(minX, d.van.x); maxX = Math.max(maxX, d.van.x + d.maat.x);
            minZ = Math.min(minZ, d.van.z); maxZ = Math.max(maxZ, d.van.z + d.maat.z);
            maxY = Math.max(maxY, d.van.y + d.maat.y);
        }
        if (botsing != null) {
            breedte = botsing[0];
            lengte = botsing[1];
            hoogte = botsing[2];
            middenZ = 0;
        } else {
            breedte = Math.max(0.5f, Math.min(maxX - minX, 6f));
            lengte = Math.max(0.5f, Math.min(maxZ - minZ, 12f));
            hoogte = Math.max(0.6f, Math.min(maxY, 3f));
            middenZ = (minZ + maxZ) / 2f;
        }
    }

    String naamZonderKleur() {
        return Tekst.zonderKleur(naam);
    }

    int stoelen() {
        return zitten.size();
    }

    static Model lees(String id, ConfigurationSection s, ConfigurationSection standaard, UUID eigenaar) {
        Soort soort = Soort.van(s.getString("soort", "auto"));
        if (soort == null) throw new IllegalArgumentException("onbekende soort " + s.getString("soort"));
        ConfigurationSection st = standaard == null ? null : standaard.getConfigurationSection(soort.sleutel());
        List<Deel> delen = new ArrayList<>();
        for (String regel : s.getStringList("delen")) {
            try {
                delen.add(Deel.lees(regel));
            } catch (RuntimeException e) {
                Bukkit.getLogger().warning("[Ritly] Model " + id + ": deel '" + regel + "' klopt niet (" + e.getMessage() + ")");
            }
        }
        List<Vector3f> zitten = new ArrayList<>();
        for (Object o : s.getList("zitplaatsen", List.of())) {
            if (o instanceof List<?> l && l.size() >= 3)
                zitten.add(new Vector3f(num(l.get(0)), num(l.get(1)), num(l.get(2))));
        }
        float[] botsing = null;
        List<?> b = s.getList("botsing");
        if (b != null && b.size() >= 3) botsing = new float[]{num(b.get(0)), num(b.get(1)), num(b.get(2))};
        Material icoon = Material.matchMaterial(s.getString("icoon", soort.icoon.name()));
        if (icoon == null || !icoon.isItem()) icoon = soort.icoon;
        DyeColor kleur = Tekst.dye(s.getString("kleur", "white"));
        return new Model(id, s.getString("naam", id), soort, delen, zitten, kleur == null ? DyeColor.WHITE : kleur, icoon,
                dbl(s, st, "max-snelheid", 50), dbl(s, st, "acceleratie", 20), dbl(s, st, "draaien", 90),
                dbl(s, st, "tank", 40), dbl(s, st, "verbruik", 8), dbl(s, st, "opstijgen", 55),
                dbl(s, st, "diepgang", 0.3), botsing, eigenaar);
    }

    void schrijf(ConfigurationSection s) {
        s.set("naam", naam);
        s.set("soort", soort.sleutel());
        s.set("kleur", kleur.name().toLowerCase(Locale.ROOT));
        s.set("icoon", icoon.name().toLowerCase(Locale.ROOT));
        if (eigenaar != null) s.set("eigenaar", eigenaar.toString());
        s.set("max-snelheid", maxKmu);
        s.set("acceleratie", acceleratie);
        s.set("draaien", draaien);
        s.set("tank", tank);
        s.set("verbruik", verbruik);
        if (soort == Soort.VLIEGTUIG) s.set("opstijgen", opstijgen);
        if (soort == Soort.BOOT) s.set("diepgang", diepgang);
        List<List<Float>> z = new ArrayList<>();
        for (Vector3f v : zitten) z.add(List.of(rond(v.x), rond(v.y), rond(v.z)));
        s.set("zitplaatsen", z);
        List<String> d = new ArrayList<>();
        for (Deel deel : delen) d.add(deel.alsTekst());
        s.set("delen", d);
    }

    private static double dbl(ConfigurationSection s, ConfigurationSection st, String k, double def) {
        if (s.contains(k)) return s.getDouble(k);
        if (st != null && st.contains(k)) return st.getDouble(k);
        return def;
    }

    private static float num(Object o) {
        if (o instanceof Number n) return n.floatValue();
        return Float.parseFloat(String.valueOf(o).replace(',', '.'));
    }

    static float rond(float f) {
        return Math.round(f * 1000f) / 1000f;
    }
}
