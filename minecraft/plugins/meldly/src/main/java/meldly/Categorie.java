package meldly;

import java.util.Locale;

/**
 * De soorten berichten. Elke soort krijgt per Discord-server een eigen kanaal
 * in de categorie van de bot (bijv. "MPServer" met #mc-chat, #mc-doden, ...).
 */
enum Categorie {
    CHAT("chat", "Chat", "mc-chat", "💬", 0x5865F2, false, false,
            "Chat tussen Minecraft en Discord, in beide richtingen."),
    STATUS("status", "Status", "mc-status", "📊", 0x7DDC4A, false, false,
            "Live statusbericht (online, spelers, TPS) en meldingen als de server start of stopt."),
    SPELERS("spelers", "In- en uitloggen", "mc-in-uit", "👋", 0x8EC5F0, false, false,
            "Wie in- en uitlogt, en nieuwe spelers."),
    DODEN("doden", "Doden", "mc-doden", "💀", 0x9B9B9B, false, false,
            "Doodsberichten van spelers."),
    PRESTATIES("prestaties", "Prestaties", "mc-prestaties", "🏆", 0xF2C14E, false, false,
            "Advancements die spelers halen."),
    COMMANDOS("commandos", "Commando's", "mc-commandos", "⌨️", 0x6B6E75, true, true,
            "Commando's van spelers en de console (privéberichten en wachtwoorden niet)."),
    MODERATIE("moderatie", "Moderatie", "mc-moderatie", "🛡️", 0xE0503C, true, false,
            "Gevangenis, waarschuwingen, kicks, bans, OP en spelmodus."),
    ECONOMIE("economie", "Economie", "mc-economie", "💰", 0x2ECC71, true, true,
            "Geld en Munten uit het beheer en het logboek van Muntly."),
    FOUTEN("fouten", "Fouten en waarschuwingen", "mc-fouten", "⚠️", 0xE67E22, true, true,
            "WARN- en ERROR-regels uit het serverlog en lage TPS."),
    KOPPELINGEN("koppelingen", "Koppelingen", "mc-koppelingen", "🔗", 0x9B59B6, true, false,
            "Wie zijn Minecraft-account aan Discord koppelt of ontkoppelt.");

    final String sleutel;
    final String label;
    final String kanaalNaam;
    final String emoji;
    final int kleur;
    /** Standaard alleen zichtbaar voor beheerders (@everyone mag het kanaal niet zien). */
    final boolean prive;
    /** Veel regels: samenvoegen in één blok in plaats van een blok per gebeurtenis. */
    final boolean compact;
    final String uitleg;

    Categorie(String sleutel, String label, String kanaalNaam, String emoji, int kleur, boolean prive, boolean compact, String uitleg) {
        this.sleutel = sleutel;
        this.label = label;
        this.kanaalNaam = kanaalNaam;
        this.emoji = emoji;
        this.kleur = kleur;
        this.prive = prive;
        this.compact = compact;
        this.uitleg = uitleg;
    }

    static Categorie van(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT);
        for (Categorie c : values()) if (c.sleutel.equals(k)) return c;
        return null;
    }
}
