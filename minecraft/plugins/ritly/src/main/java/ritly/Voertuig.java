package ritly;

import org.bukkit.DyeColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.UUID;

/** Een voertuig van een speler (in de garage of ergens geparkeerd). */
final class Voertuig {

    final UUID id;
    final UUID eigenaar;
    String model;
    DyeColor kleur;
    int motor, stuur, tank;
    double brandstof;
    String naam;          // eigen naam, of null
    boolean opSlot = true;
    // plek waar hij geparkeerd staat; wereld == null = in de garage
    String wereld;
    double x, y, z;
    float yaw;
    long gekocht, gebruikt;

    Voertuig(UUID id, UUID eigenaar, String model) {
        this.id = id;
        this.eigenaar = eigenaar;
        this.model = model;
        this.gekocht = System.currentTimeMillis();
        this.gebruikt = gekocht;
    }

    boolean inGarage() {
        return wereld == null;
    }

    void parkeer(String wereld, double x, double y, double z, float yaw) {
        this.wereld = wereld;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    void naarGarage() {
        wereld = null;
    }

    String titel(Model m) {
        if (naam != null && !naam.isBlank()) return "&f" + naam;
        return m == null ? "&f" + model : m.naam;
    }

    // ---- eigenschappen met onderdelen ----

    static double bonus(List<Double> lijst, int niveau) {
        if (niveau <= 0 || lijst == null || lijst.isEmpty()) return 0;
        return lijst.get(Math.min(niveau, lijst.size()) - 1);
    }

    double maxKmu(Model m, Ritly p) {
        return m.maxKmu * (1 + bonus(p.upgrade("motor"), motor));
    }

    double acceleratie(Model m, Ritly p) {
        return m.acceleratie * (1 + bonus(p.upgrade("motor"), motor));
    }

    double draaien(Model m, Ritly p) {
        return m.draaien * (1 + bonus(p.upgrade("stuur"), stuur));
    }

    double tankInhoud(Model m, Ritly p) {
        return m.tank * (1 + bonus(p.upgrade("tank"), tank));
    }

    boolean heeftBrandstofNodig(Model m, Ritly p) {
        return p.brandstofAan() && m.soort.brandstof && m.tank > 0;
    }

    // ---- opslaan ----

    void schrijf(ConfigurationSection s) {
        s.set("eigenaar", eigenaar.toString());
        s.set("model", model);
        s.set("kleur", kleur == null ? null : kleur.name());
        s.set("motor", motor);
        s.set("stuur", stuur);
        s.set("tank", tank);
        s.set("brandstof", Math.round(brandstof * 100) / 100.0);
        s.set("naam", naam);
        s.set("op-slot", opSlot);
        s.set("gekocht", gekocht);
        s.set("gebruikt", gebruikt);
        if (wereld != null) {
            s.set("plek.wereld", wereld);
            s.set("plek.x", Math.round(x * 1000) / 1000.0);
            s.set("plek.y", Math.round(y * 1000) / 1000.0);
            s.set("plek.z", Math.round(z * 1000) / 1000.0);
            s.set("plek.yaw", (double) Math.round(yaw * 10) / 10.0);
        }
    }

    static Voertuig lees(UUID id, ConfigurationSection s) {
        Voertuig v = new Voertuig(id, UUID.fromString(s.getString("eigenaar")), s.getString("model"));
        v.kleur = Tekst.dye(s.getString("kleur"));
        v.motor = s.getInt("motor");
        v.stuur = s.getInt("stuur");
        v.tank = s.getInt("tank");
        v.brandstof = s.getDouble("brandstof");
        v.naam = s.getString("naam");
        v.opSlot = s.getBoolean("op-slot", true);
        v.gekocht = s.getLong("gekocht", System.currentTimeMillis());
        v.gebruikt = s.getLong("gebruikt", v.gekocht);
        if (s.isConfigurationSection("plek")) {
            v.wereld = s.getString("plek.wereld");
            v.x = s.getDouble("plek.x");
            v.y = s.getDouble("plek.y");
            v.z = s.getDouble("plek.z");
            v.yaw = (float) s.getDouble("plek.yaw");
        }
        return v;
    }
}
