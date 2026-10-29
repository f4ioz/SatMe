/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.aprs.AprsEmission
import fr.f4ioz.satcombo.data.Transmitter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Working a low-orbit satellite (« Trafic satellite »): the rules and the
 * texts behind SatMe-PC's panel, free of any rig so they can be tested.
 *
 *  - a preset per satellite: the access tone (automatic, none, or a chosen
 *    one), the lowest elevation SatMe lets the rig transmit at, a power;
 *  - the guards: elevation, uplink inside the transponder;
 *  - SO-50's arming tone (74.4 Hz for a couple of seconds starts its
 *    10-minute timer);
 *  - the operator's macros ({MOI}, {LUI}, {LOC}…);
 *  - APRS through a satellite digipeater, and the beacon armed for one
 *    pass only ([Balise]);
 *  - etiquette reminders for the QSO assistant.
 *
 * Nothing here transmits: callers ask, and the rig's own guards still apply.
 */
object TraficSatellite {

    /** The access tone follows the transmitter's description (« CTCSS 67.0 »). */
    const val AUTO = -1
    /** No access tone. */
    const val AUCUN = 0

    /**
     * A satellite's own TX settings. [ctcss] tenths of Hz ([AUTO], [AUCUN]
     * or a tone); [elevationMin] degrees (0: no guard); [puissancePct] the
     * power to apply on demand (0: none chosen).
     */
    data class Preset(val ctcss: Int = AUTO, val elevationMin: Int = 0, val puissancePct: Int = 0) {
        fun ecrit(): String = "ctcss=$ctcss;el=$elevationMin;p=$puissancePct"
    }

    /** A stored preset (`ctcss=670;el=10;p=25`); unknown or broken parts left at their default. */
    fun lit(s: String?): Preset {
        if (s.isNullOrBlank()) return Preset()
        val m = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        return Preset(
            ctcss = m["ctcss"]?.toIntOrNull()?.let { if (it == AUTO || it == AUCUN || it in TONS) it else null } ?: AUTO,
            elevationMin = (m["el"]?.toIntOrNull() ?: 0).coerceIn(0, 60),
            puissancePct = (m["p"]?.toIntOrNull() ?: 0).coerceIn(0, 100))
    }

    /** The 50 standard CTCSS tones, tenths of Hz. */
    val TONS: List<Int> = listOf(670, 693, 719, 744, 770, 797, 825, 854, 885, 915, 948, 974, 1000, 1035, 1072, 1109, 1148,
        1188, 1230, 1273, 1318, 1365, 1413, 1462, 1500, 1567, 1598, 1622, 1655, 1679, 1713, 1738, 1773, 1799, 1835, 1862,
        1899, 1928, 1966, 1995, 2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541)

    /** « 67,0 Hz ». */
    fun tonTexte(dixiemes: Int): String = String.format(Locale.FRANCE, "%.1f Hz", dixiemes / 10.0)

    // ------------------------------------------------------------ SO-50

    /** SaudiSat-1C (SO-50), NORAD 27607. */
    const val SO50 = 27607
    /** 74.4 Hz: starts SO-50's repeater timer. */
    const val TON_ARMEMENT = 744
    /** How long the arming carrier is held (2 s are needed, a margin for the rig keying). */
    const val DUREE_ARMEMENT_MS = 2_500L

    /** The arming tone a satellite asks for before its repeater opens, or null (SO-50 only today). */
    fun tonArmement(catnum: Int?, nom: String?): Int? {
        val n = (nom ?: "").uppercase().replace(" ", "")
        return if (catnum == SO50 || n.contains("SO-50") || n.contains("SAUDISAT-1C")) TON_ARMEMENT else null
    }

    // ------------------------------------------------------------ guards

    /** Why the elevation forbids transmitting (below [min] degrees), or null. [min] 0: no guard. */
    fun gardeElevation(nom: String, elevationDeg: Double?, min: Int): String? {
        if (min <= 0) return null
        val el = elevationDeg ?: return "« $nom » : élévation inconnue, émission refusée (préréglage : au-dessus de $min°)"
        if (el >= min) return null
        return "« $nom » à " + String.format(Locale.FRANCE, "%.0f", el) + "° : émission permise au-dessus de $min° (préréglage du satellite)"
    }

    /** The widest Doppler of a low orbit on [hz] (7.6 km/s), Hz. */
    fun dopplerMaxHz(hz: Long): Long = (hz * 7.6 / Doppler.C_KM_S).roundToLong()

    /**
     * Why the uplink the rig is set to ([monteeHz], sky, Doppler included)
     * is not the satellite's, or null. Inside the uplink passband widened by
     * the largest Doppler and 5 kHz (an FM channel, a TX shift) is fine.
     */
    fun gardeMontee(t: Transmitter?, monteeHz: Long?): String? {
        val bas0 = t?.uplinkLowHz ?: return null
        val m = monteeHz ?: return null
        val haut0 = t.uplinkHighHz ?: bas0
        val bas = minOf(bas0, haut0); val haut = maxOf(bas0, haut0)
        val marge = dopplerMaxHz(haut) + 5_000L
        if (m in (bas - marge)..(haut + marge)) return null
        return "montée du poste (" + mhz(m) + ") hors de celle du satellite (" + mhz(bas) + (if (haut != bas) "–" + mhz(haut) else "") + " MHz) : émission refusée"
    }

    private fun mhz(hz: Long) = String.format(Locale.FRANCE, "%.4f", hz / 1e6)

    // ------------------------------------------------------------ macros

    /**
     * A macro's text with its fields filled: {MOI} our callsign, {LOC} our
     * square (4), {LOC6} (6), {LUI} the other station, {SAT} the satellite.
     * A field left empty is dropped with its spaces.
     */
    fun macro(modele: String, moi: String, locator: String, lui: String, sat: String): String =
        modele.replace("{MOI}", moi.trim().uppercase(), ignoreCase = true)
            .replace("{LOC6}", locator.trim().take(6).uppercase(), ignoreCase = true)
            .replace("{LOC}", locator.trim().take(4).uppercase(), ignoreCase = true)
            .replace("{LUI}", lui.trim().uppercase(), ignoreCase = true)
            .replace("{SAT}", sat.trim().uppercase(), ignoreCase = true)
            .replace(Regex("\\s+"), " ").trim()

    /** The longest CW memory sent at once (characters). */
    const val CW_MAX = 120

    /** What the keyer can send: letters, digits, « /?.,= » and spaces, upper case, [CW_MAX] at most. */
    fun texteCw(s: String): String =
        s.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it in " /?.,=" }.replace(Regex(" +"), " ").trim().take(CW_MAX)

    /** The CW memories offered at first (the operator rewrites them). */
    val CW_DEFAUT = listOf("CQ SAT CQ SAT DE {MOI} {MOI} K", "{LUI} DE {MOI} {LOC} {LOC} K", "{LUI} R R 73 TU DE {MOI} SK",
        "{MOI}", "QRZ? DE {MOI} K", "TU 73")

    // ------------------------------------------------------------ APRS

    /** Can this transmitter carry APRS from here: FM (or AFSK) with its uplink on 145.825 MHz's window. */
    fun aprsPossible(t: Transmitter?): Boolean {
        val ul = t?.uplinkLowHz ?: return false
        return ModeRadio.surFm(t.mode, t.baud) && ul in AprsEmission.FENETRES[0]
    }

    /** The ISS digipeater's path, and every satellite digipeater's (« ARISS » is answered by NO-44, SONATE-2… too). */
    val CHEMIN_SATELLITE = listOf("ARISS")

    /** A frame typed by the operator at most this often (the ISS digipeater is shared by a continent). */
    const val ECART_MANUEL_MS = 30_000L

    /**
     * The position beacon through a satellite digipeater, **armed by the
     * operator for one pass**: never from a stored setting, gone at LOS, at
     * most [max] frames a pass, one per [intervalleMs] (60 s at least: the
     * ARISS guidance for an attended station is about one a minute), and
     * only while the page says it is there.
     */
    class Balise {
        @Volatile var aos = 0L; private set
        @Volatile var los = 0L; private set
        @Volatile var catnum = 0; private set
        @Volatile var envoyees = 0; private set
        @Volatile var derniereMs = 0L; private set
        @Volatile var intervalleMs = 120_000L
            set(v) { field = v.coerceIn(INTERVALLE_MIN_MS, 600_000L) }
        @Volatile var max = 4
            set(v) { field = v.coerceIn(1, 10) }

        val armee: Boolean get() = los > 0L

        /** Armed for the pass [aos]..[los] of [catnum]; null when armed, else why not. */
        fun arme(catnum: Int, aos: Long, los: Long, maintenant: Long): String? {
            if (los <= maintenant) return "aucun passage en cours ou à venir pour armer la balise"
            if (aos - maintenant > 30 * 60_000L) return "le prochain passage est dans plus de 30 min : armer la balise juste avant"
            this.catnum = catnum; this.aos = aos; this.los = los; envoyees = 0; derniereMs = 0L
            return null
        }

        fun desarme() { aos = 0L; los = 0L; catnum = 0 }

        /** At or after LOS, or another satellite followed: disarmed. True when it just was. */
        fun expire(maintenant: Long, catnumSuivi: Int?): Boolean {
            if (!armee) return false
            if (maintenant >= los || catnumSuivi != catnum) { desarme(); return true }
            return false
        }

        /** A frame is due now: armed, inside the pass, its gap and count respected, the satellite [elevationDeg] high enough. */
        fun due(maintenant: Long, elevationDeg: Double?, elevationMin: Int): Boolean =
            armee && maintenant in aos until los && envoyees < max && maintenant - derniereMs >= intervalleMs &&
                (elevationDeg ?: -90.0) >= maxOf(ELEVATION_MIN_DEG, elevationMin).toDouble()

        fun emise(maintenant: Long) { envoyees++; derniereMs = maintenant; if (envoyees >= max) desarme() }

        companion object {
            const val INTERVALLE_MIN_MS = 60_000L
            /** Below this, a beacon is noise for nobody. */
            const val ELEVATION_MIN_DEG = 10
        }
    }

    // ------------------------------------------------------------ the assistant

    /** The kind of contact the chosen transmitter offers. */
    enum class Genre { FM, LINEAIRE, APRS, DONNEES, AUCUNE_MONTEE }

    fun genre(t: Transmitter?): Genre = when {
        t?.uplinkLowHz == null -> Genre.AUCUNE_MONTEE
        aprsPossible(t) && (t.mode ?: "").uppercase().let { it.contains("AFSK") || it.contains("APRS") || t.description.uppercase().contains("APRS") || t.description.uppercase().contains("DIGI") } -> Genre.APRS
        t.isTransponder -> Genre.LINEAIRE
        ModeRadio.surFm(t.mode, t.baud) && ModeRadio.donnees(t.mode, t.baud) -> Genre.DONNEES
        ModeRadio.surFm(t.mode, t.baud) -> Genre.FM
        else -> Genre.DONNEES
    }

    /** Short reminders for the contact (AMSAT's FM guidance, the ARISS APRS guidance), by kind. */
    fun conseils(g: Genre): List<String> = when (g) {
        Genre.FM -> listOf(
            "Si tu n’entends pas le satellite, n’émets pas.",
            "Écoute-toi : duplex intégral, ta voix doit revenir par la descente.",
            "La puissance la plus faible qui passe : plus de watts n’allonge pas la descente, ils écrasent les autres.",
            "Contact court : indicatifs et carré locator ; un contact par passage quand c’est chargé.",
            "Pas de CQ sur un relais FM chargé : donne ton indicatif, attends la fin d’un QSO.",
            "Laisse passer les stations /P, /M et les carrés rares.")
        Genre.LINEAIRE -> listOf(
            "Accorde la descente, la montée suit (Doppler des deux côtés : la fréquence reste fixe au satellite).",
            "Cherche ton propre signal en CW ou en sifflant brièvement, puis corrige le décalage TX.",
            "Puissance minimale : le transpondeur partage sa puissance entre tous.",
            "Contact court : indicatifs, report, carré locator.")
        Genre.APRS -> listOf(
            "Chemin ARISS seulement, jamais WIDE.",
            "Une trame par minute au plus pour une station tenue ; aucune balise sans opérateur.",
            "Trames courtes ; 5 W suffisent.",
            "Digipeater de l’ISS parfois arrêté (sorties, amarrages, contacts écoles) : vérifier ariss.net.")
        Genre.DONNEES -> listOf("Satellite de données : vérifier le mode et le débit avant d’émettre.")
        Genre.AUCUNE_MONTEE -> listOf("Cet émetteur n’a pas de montée : écoute seule.")
    }

    /** The exchange, as the assistant proposes it, for [genre]. */
    fun echange(g: Genre, moi: String, locator: String, lui: String): String {
        val m = moi.trim().uppercase().ifBlank { "MOI" }
        val l = locator.trim().take(4).uppercase()
        val o = lui.trim().uppercase()
        return when (g) {
            Genre.APRS -> if (o.isBlank()) "position, puis « $m $l 73 » en statut" else "message à $o : « $m $l 73 »"
            else -> if (o.isBlank()) "$m $l" else "$o de $m, $l, merci 73"
        }
    }

    /** Seconds rounded to « 4 min 05 s » for the assistant. */
    fun duree(ms: Long): String {
        val s = abs(ms) / 1000
        return if (s >= 60) String.format(Locale.FRANCE, "%d min %02d s", s / 60, s % 60) else "$s s"
    }
}
