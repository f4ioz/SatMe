/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.polarisation

/**
 * An antenna's polarisation, as a WiMo remote switch (18080 to 18083) gives
 * it: +12 V on one pin of its 6-pin socket — 1 right-hand circular, 2
 * left-hand circular, 5 horizontal — and none at all for vertical.
 */
enum class Pol(val nom: String, val court: String) {
    V("verticale", "V"), H("horizontale", "H"), D("circulaire droite", "D"), G("circulaire gauche", "G");

    /** Right and left swapped (the sense depends on the wiring and on which way the antenna looks). */
    fun inverse(oui: Boolean): Pol = if (!oui) this else when (this) { D -> G; G -> D; else -> this }

    companion object {
        fun de(s: String?): Pol? = s?.trim()?.uppercase()?.let { k -> entries.firstOrNull { it.name == k } }
    }
}

/** The two antennas: UHF (70 cm) and VHF (2 m). */
enum class Bande(val nom: String) {
    U("UHF"), V("VHF");

    companion object {
        fun de(s: String?): Bande? = s?.trim()?.uppercase()?.let { k -> entries.firstOrNull { it.name == k } }

        /** The antenna a sky frequency goes through; null outside the 2 m and 70 cm bands. */
        fun deFrequence(hz: Long?): Bande? = when (hz) {
            null -> null
            in 144_000_000L..148_000_000L -> V
            in 420_000_000L..450_000_000L -> U
            else -> null
        }
    }
}

/** What switches a band's polarisation: nothing, a WiMo box (four positions), or one coaxial relay (H or V). */
enum class Equipement(val nom: String) {
    AUCUN("aucun"), WIMO("WiMo 4 positions"), HV("relais H / V");

    fun permet(p: Pol): Boolean = when (this) { AUCUN -> false; WIMO -> true; HV -> p == Pol.H || p == Pol.V }

    companion object { fun de(s: String?): Equipement? = s?.trim()?.uppercase()?.let { k -> entries.firstOrNull { it.name == k } } }
}

/**
 * The front panel's rotary selector. Off AUTO it drives the box itself and
 * the ESP32 can impose nothing; [INCONNU]: between two positions, or a
 * wire off (read as manual: nothing commanded).
 */
enum class Selecteur(val code: String) {
    AUTO("AUTO"), V("V"), H("H"), D("D"), G("G"), INCONNU("?");

    val manuel: Boolean get() = this != AUTO
    /** The polarisation it gives by hand, null in AUTO or unknown. */
    val pol: Pol? get() = Pol.de(code)

    companion object { fun de(s: String?): Selecteur? = s?.trim()?.uppercase()?.let { k -> entries.firstOrNull { it.code == k } } }
}

/**
 * The box as it says it is: the selector, each antenna's real polarisation
 * ([u]: the selector's own off AUTO), the rig's SEND line (true:
 * transmitting), its firmware.
 */
data class EtatBoitier(
    val selecteur: Selecteur = Selecteur.INCONNU,
    val u: Pol = Pol.V,
    val v: Pol = Pol.V,
    val tx: Boolean = false,
    val fw: String = "",
) {
    fun pol(b: Bande): Pol = if (b == Bande.U) u else v
}

/**
 * The relay tree of the box (KA → KB → KC, and KA′ KB′ KC′ for VHF): the
 * +12 V goes through KA, then KB, then KC, so it can only reach one pin
 * whatever the software does — the WiMo rule held by the wiring.
 */
object ArbreRelais {

    /** KA, KB, KC for [p] (true: energised); those « without effect » left at rest. */
    fun relais(p: Pol): List<Boolean> = when (p) {
        Pol.V -> listOf(false, false, false)
        Pol.H -> listOf(true, false, false)
        Pol.D -> listOf(true, true, false)
        Pol.G -> listOf(true, true, true)
    }

    /** The WiMo pin under +12 V for relays [k] (KA, KB, KC), null: none (vertical). Never two: the tree's wiring. */
    fun broche(k: List<Boolean>): Int? = when {
        !k[0] -> null
        !k[1] -> 5
        !k[2] -> 1
        else -> 2
    }

    /** What relays [k] give. */
    fun pol(k: List<Boolean>): Pol = when (broche(k)) { 5 -> Pol.H; 1 -> Pol.D; 2 -> Pol.G; else -> Pol.V }

    /**
     * The relay states to go through from [de] to [vers]: KA released first
     * (no voltage, vertical), KB and KC set behind it, KA last — never two
     * pins, and « no voltage » between two positions.
     */
    fun sequence(de: List<Boolean>, vers: Pol): List<List<Boolean>> {
        val cible = relais(vers)
        if (de == cible) return emptyList()
        val l = mutableListOf<List<Boolean>>()
        var k = de
        if (k[0]) { k = listOf(false, k[1], k[2]); l += k }
        if (k[1] != cible[1] || k[2] != cible[2]) { k = listOf(false, cible[1], cible[2]); l += k }
        if (cible[0]) { k = cible; l += k }
        return l
    }
}

/**
 * The line protocol of the ESP32 (USB serial, 115200 baud, one command a
 * line, answers likewise):
 *
 *  - `POL U H|V|D|G`, `POL V H|V|D|G` → `OK POL U H`, or `ERR MANUEL`
 *    (selector off AUTO), `ERR TX` (SEND active), `ERR SYNTAXE`;
 *  - `ETAT?` → `ETAT SEL=AUTO|V|H|D|G|? U=x V=x TX=0|1 FW=…`, also sent
 *    by itself when something changes (selector, SEND, a switch);
 *  - `ID?` → `ID SATME-POL …`.
 */
object ProtocolePolarisation {
    const val VITESSE = 115_200
    const val ETAT = "ETAT?"
    const val ID = "ID?"
    /** What the firmware says first in its answer to `ID?`. */
    const val SIGNATURE = "SATME-POL"

    sealed class Reponse {
        data class Etat(val etat: EtatBoitier) : Reponse()
        data class Ok(val texte: String) : Reponse()
        data class Err(val code: String, val texte: String) : Reponse()
        data class Id(val texte: String) : Reponse()
        data class Autre(val texte: String) : Reponse()
    }

    fun commande(b: Bande, p: Pol): String = "POL ${b.name} ${p.name}"

    /** A command line read by the firmware (or the virtual ESP32): its band and polarisation, null when it is not one. */
    fun litCommande(ligne: String): Pair<Bande, Pol>? {
        val m = ligne.trim().uppercase().split(Regex("\\s+"))
        if (m.size != 3 || m[0] != "POL") return null
        return (Bande.de(m[1]) ?: return null) to (Pol.de(m[2]) ?: return null)
    }

    /** One line received; null for an empty one. */
    fun lit(ligne: String): Reponse? {
        val l = ligne.trim()
        if (l.isEmpty()) return null
        val mot = l.substringBefore(' ').uppercase()
        val reste = l.substringAfter(' ', "").trim()
        return when (mot) {
            "ETAT" -> litEtat(reste)?.let { Reponse.Etat(it) } ?: Reponse.Autre(l)
            "OK" -> Reponse.Ok(reste)
            "ERR" -> Reponse.Err(reste.substringBefore(' ').uppercase(), reste.substringAfter(' ', "").trim())
            "ID" -> Reponse.Id(reste)
            else -> Reponse.Autre(l)
        }
    }

    /** `SEL=AUTO U=D V=V TX=0 FW=1.0`; null when SEL or a polarisation does not read. */
    fun litEtat(s: String): EtatBoitier? {
        val kv = s.split(Regex("\\s+")).filter { '=' in it }.associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
        val sel = Selecteur.de(kv["SEL"] ?: return null) ?: return null
        val u = kv["U"]?.let { Pol.de(it) ?: return null } ?: Pol.V
        val v = kv["V"]?.let { Pol.de(it) ?: return null } ?: Pol.V
        val tx = when (kv["TX"]) { null, "0" -> false; "1" -> true; else -> return null }
        return EtatBoitier(sel, u, v, tx, kv["FW"].orEmpty())
    }

    fun etat(e: EtatBoitier): String =
        "ETAT SEL=${e.selecteur.code} U=${e.u.name} V=${e.v.name} TX=${if (e.tx) 1 else 0}" + (if (e.fw.isNotBlank()) " FW=${e.fw}" else "")
}

/**
 * The antennas SatMe itself transmits through: no command ever goes to
 * them while it does (the rig's SEND line holds the box too). A
 * transmission whose frequency is not known holds both.
 */
object VerrouEmission {
    /** [emissions]: one entry per transmission going on, its sky frequency when known. */
    fun bandes(emissions: List<Long?>): Set<Bande> =
        emissions.fold(emptySet()) { acc, f -> if (f == null) acc + Bande.entries else acc + listOfNotNull(Bande.deFrequence(f)) }
}
