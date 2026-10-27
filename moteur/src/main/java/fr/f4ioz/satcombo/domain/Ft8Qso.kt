/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs

/**
 * One FT8/FT4 contact, as WSJT-X's operators know it: six messages (Tx1
 * to Tx6) made from my callsign and grid and the station worked, and the
 * sequence through them — call, report, R and report, RR73, 73.
 *
 * **Attended, never robotic.** The names FT8 and FT4 come with a condition
 * that is not negotiable: no robotic or unattended QSO. So:
 *
 * - a contact **starts only from the operator**: [repond] (a decoded line
 *   chosen) or [appelleCq]. A station calling me while nothing was started
 *   is shown, never answered;
 * - within a contact the sequence may advance by itself ([recoit], « auto
 *   seq »), as in every FT8 program;
 * - an **attendance watchdog** ([geste], [chienExpire]): no operator action
 *   for [chienMin] minutes and the transmitter stops, CQ or not; the
 *   caller cuts it then;
 * - after RR73 or 73 is sent, nothing more is sent: the contact is offered
 *   to the log ([aNoter]), for the operator to confirm.
 *
 * Pure logic, the time given: everything here is tested without a radio.
 */
class Ft8Qso(moi: String = "", carre: String = "") {

    /** A decoded line, as the sequencer needs it. */
    data class Recu(
        val texte: String,
        val appelant: String?,
        val appele: String?,
        val locator: String?,
        /** Its SNR as heard here (the report to send it), dB. */
        val snrDb: Int,
        val frequenceHz: Int,
        /** Its slot's start (UTC ms). */
        val tranche: Long,
        /** Sent in an even slot. */
        val pair: Boolean,
    )

    enum class Etape(val libelle: String) {
        REPOS("au repos"),
        CQ("appel général (CQ)"),
        APPEL("appel de la station"),
        RAPPORT("envoi du rapport"),
        R_RAPPORT("envoi de R + rapport"),
        RR73("envoi de RR73"),
        FIN73("envoi de 73"),
        FINI("QSO terminé"),
    }

    /** A finished contact, offered to the log. */
    data class Fin(val indicatif: String, val carre: String, val rapportEnvoye: String, val rapportRecu: String, val quandMs: Long)

    var moi: String = moi.trim().uppercase()
        set(v) { field = v.trim().uppercase() }
    var carre: String = carre.trim().uppercase().take(4)
        set(v) { field = v.trim().uppercase().take(4) }

    var etape = Etape.REPOS; private set
    var partenaire: String? = null; private set
    var sonCarre: String? = null; private set
    var rapportEnvoye: Int? = null; private set
    var rapportRecu: Int? = null; private set
    /** The message to send next, 1 to 6; 0: nothing (the contact over, or not started). */
    var prochain = 0; private set
    /** Our slot parity: even slots when true. */
    var pair = true; private set
    /** « RRR » rather than « RR73 » in Tx4 (when the partner should still say 73). */
    var rrr = false
    /** The contact over: offered to the log until noted or dismissed. */
    var aNoter: Fin? = null; private set
    /** What the last event did, for the status line. */
    var dernier = ""; private set
    /** Slots already read (their start), so a slot is never read twice. */
    private var lues = HashSet<Long>()
    /** Texts typed by the operator over the generated ones (index 0 = Tx1). */
    private val modifies = arrayOfNulls<String>(6)

    // --------------------------------------------------------------- the watchdog

    /** The last operator action (ms), for the attendance watchdog. */
    var dernierGesteMs = 0L; private set
    /** The attendance watchdog, minutes (1 to 10). */
    var chienMin = 3
        set(v) { field = v.coerceIn(1, 10) }

    /** An operator action now: the watchdog starts again. */
    fun geste(t: Long) { dernierGesteMs = t }

    /** Ms left before the watchdog stops the transmitter. */
    fun chienRestantMs(t: Long): Long = maxOf(0L, dernierGesteMs + chienMin * 60_000L - t)

    /** No operator action for [chienMin] minutes. */
    fun chienExpire(t: Long): Boolean = chienRestantMs(t) == 0L

    // --------------------------------------------------------------- messages

    /** Tx1 to Tx6 (empty when it cannot be made: no partner, no callsign). */
    fun messages(): List<String> = List(6) { i -> modifies[i] ?: genere(i + 1) }

    /** The message [n] (1 to 6) as generated, without the operator's edits. */
    fun genere(n: Int): String {
        val m = moi.takeIf { it.isNotBlank() } ?: return ""
        val g = carre.takeIf { Ft8Pliage.grille(it) != null }
        val mStd = Ft8.indicatifVers28(m) != null
        if (n == 6) return if (mStd) listOfNotNull("CQ", m, g).joinToString(" ") else "CQ $m"
        val p = partenaire ?: return ""
        val pStd = Ft8.indicatifVers28(p) != null
        val r = rapport(rapportEnvoye ?: -10)
        // Two standard callsigns: type 1 throughout. A compound one goes in clear when it can
        // (type 4 has no room for a grid or a report: those messages hash it instead).
        return when {
            mStd && pStd -> when (n) {
                1 -> listOfNotNull(p, m, g).joinToString(" "); 2 -> "$p $m $r"; 3 -> "$p $m R$r"
                4 -> "$p $m " + (if (rrr) "RRR" else "RR73"); else -> "$p $m 73"
            }
            mStd -> when (n) {
                1 -> listOfNotNull("<$p>", m, g).joinToString(" "); 2 -> "<$p> $m $r"; 3 -> "<$p> $m R$r"
                4 -> "$p <$m> " + (if (rrr) "RRR" else "RR73"); else -> "$p <$m> 73"
            }
            pStd -> when (n) {
                1 -> "<$p> $m"; 2 -> "$p <$m> $r"; 3 -> "$p <$m> R$r"
                4 -> "<$p> $m " + (if (rrr) "RRR" else "RR73"); else -> "<$p> $m 73"
            }
            else -> when (n) {
                1 -> "<$p> $m"; 2 -> "<$p> <$m> $r"; 3 -> "<$p> <$m> R$r"
                4 -> "<$p> $m " + (if (rrr) "RRR" else "RR73"); else -> "<$p> $m 73"
            }
        }
    }

    /** The text to send now, or null (nothing to send, or it cannot be packed). */
    fun aEnvoyer(): String? {
        if (prochain !in 1..6) return null
        return messages()[prochain - 1].takeIf { it.isNotBlank() && Ft8Pliage.plie(it) != null }
    }

    /** The operator's own text for Tx[n]; blank: back to the generated one. */
    fun edite(n: Int, texte: String, t: Long) {
        if (n !in 1..6) return
        modifies[n - 1] = Ft8Pliage.normalise(texte).ifBlank { null }
        geste(t)
    }

    /** The operator picks the next message. */
    fun choisit(n: Int, t: Long) {
        if (n !in 0..6) return
        prochain = n
        if (n == 6) { etape = Etape.CQ } else if (n in 1..5) etape = etapeDe(n)
        geste(t)
    }

    // --------------------------------------------------------------- the operator starts

    /** « Appeler CQ »: in even slots ([pair]) or odd ones. */
    fun appelleCq(pair: Boolean, t: Long) {
        nouveau(t)
        this.pair = pair
        etape = Etape.CQ
        prochain = 6
        dernier = "appel général"
    }

    /**
     * Answer [r], chosen by the operator (a double-click on the line): its
     * station becomes the partner, in the slots opposite to its own; the
     * message follows from what it sent (a CQ or another's contact: Tx1;
     * it calling me: the next step, as if the sequence had read it).
     */
    fun repond(r: Recu, t: Long): String? {
        val lui = r.appelant ?: return "indicatif haché inconnu : impossible de répondre à cette ligne"
        if (base(lui) == base(moi)) return "c'est ta propre ligne"
        nouveau(t)
        partenaire = lui
        sonCarre = r.locator?.take(4)
        rapportEnvoye = borne(r.snrDb)
        pair = !r.pair
        prochain = 1; etape = Etape.APPEL
        lues.add(r.tranche)
        if (pourMoi(r)) avance(r, true)
        dernier = "réponse à $lui"
        return null
    }

    private fun nouveau(t: Long) {
        partenaire = null; sonCarre = null; rapportEnvoye = null; rapportRecu = null
        aNoter = null; modifies.fill(null); lues.clear()
        depuisMs = t
        geste(t)
    }

    /** FT4 (7.5 s slots) rather than FT8. */
    var ft4 = false
    /** When the contact was started: only slots that closed after it are read. */
    private var depuisMs = 0L

    // --------------------------------------------------------------- the sequence

    /**
     * The decodes of a slot that just closed. Only lines for me count;
     * from the partner once there is one, from the first station to answer
     * my CQ otherwise. [auto] false: the sequence does not move, what was
     * heard is only noted. True when something changed.
     */
    fun recoit(lignes: List<Recu>, auto: Boolean): Boolean {
        if (etape == Etape.REPOS) return false
        val nouvelles = lignes.filter { it.tranche !in lues && it.tranche + dureeTrancheMs(ft4) > depuisMs }
        nouvelles.map { it.tranche }.toSet().forEach { lues.add(it) }
        if (lues.size > 64) lues = HashSet(lues.sortedDescending().take(32))
        // Only the other slots: ours hold, on a satellite, our own signal coming back.
        val pourMoi = nouvelles.filter { pourMoi(it) && it.pair != pair }
        val p = partenaire
        val r = if (p != null) pourMoi.firstOrNull { it.appelant != null && base(it.appelant) == base(p) }
            else if (etape == Etape.CQ) pourMoi.firstOrNull { it.appelant != null } else null
        r ?: return false
        if (p == null) {
            partenaire = r.appelant
            rapportEnvoye = borne(r.snrDb)
        }
        return avance(r, auto)
    }

    /** What [r] (from the partner, for me) moves the sequence to. */
    private fun avance(r: Recu, auto: Boolean): Boolean {
        val b = bout(r.texte)
        val lui = partenaire ?: return false
        if (rapportEnvoye == null) rapportEnvoye = borne(r.snrDb)
        val avant = prochain
        when (b) {
            is Bout.Grille -> { sonCarre = b.carre; if (auto && etape in setOf(Etape.CQ, Etape.APPEL)) va(2) }
            is Bout.Rien -> if (auto && etape in setOf(Etape.CQ, Etape.APPEL)) va(2)
            is Bout.Rapport -> { rapportRecu = b.db; if (auto && etape.ordinal <= Etape.RAPPORT.ordinal) va(3) }
            is Bout.RRapport -> { rapportRecu = b.db; if (auto && etape != Etape.FIN73 && etape != Etape.FINI) va(4) }
            is Bout.Accuse -> if (auto && etape != Etape.FINI) {
                // RRR or RR73: our 73 closes it. A 73: over, once reports were exchanged.
                if (b.mot != "73") va(5)
                else if (rapportRecu != null || etape == Etape.RR73 || etape == Etape.FIN73) termine(r.tranche)
            }
            is Bout.Autre -> Unit
        }
        dernier = "$lui : " + r.texte + (if (!auto) " (séquence manuelle : choisir le message)" else "")
        return prochain != avant || !auto
    }

    private fun va(n: Int) { prochain = n; etape = etapeDe(n) }

    private fun etapeDe(n: Int) = when (n) {
        1 -> Etape.APPEL; 2 -> Etape.RAPPORT; 3 -> Etape.R_RAPPORT; 4 -> Etape.RR73; 5 -> Etape.FIN73; 6 -> Etape.CQ; else -> Etape.REPOS
    }

    /**
     * Message [n] went out at [t]. After RR73 (or RRR) or 73, nothing more
     * is sent and the contact is offered to the log.
     */
    fun envoye(n: Int, t: Long) {
        if (n == 4 || n == 5) {
            if (aNoter == null) aNoter = fin(t)
            prochain = 0
            if (n == 5 || !rrr) etape = Etape.FINI
            dernier = if (n == 4) "RR73 envoyé : QSO à noter" else "73 envoyé : QSO terminé"
        }
    }

    private fun termine(t: Long) {
        prochain = 0; etape = Etape.FINI
        if (aNoter == null) aNoter = fin(t)
    }

    private fun fin(t: Long): Fin? {
        val p = partenaire ?: return null
        return Fin(p, sonCarre.orEmpty(), rapportEnvoye?.let { rapport(it) }.orEmpty(), rapportRecu?.let { rapport(it) }.orEmpty(), t)
    }

    /** The contact offered to the log, noted or dismissed. */
    fun oublieFin() { aNoter = null }

    /** Halt: nothing more to send; the partner and the messages stay on screen. */
    fun arrete() { if (etape == Etape.CQ) etape = Etape.REPOS; prochain = 0 }

    /** Everything forgotten (a new mode, a new callsign). */
    fun efface() { nouveau(dernierGesteMs); etape = Etape.REPOS; prochain = 0; dernier = "" }

    private fun pourMoi(r: Recu): Boolean {
        if (moi.isBlank()) return false
        val premier = r.texte.trim().split(Regex("\\s+")).firstOrNull()?.removePrefix("<")?.removeSuffix(">") ?: return false
        val a = r.appele ?: premier
        return base(a) == base(moi) && (r.appelant == null || base(r.appelant) != base(moi))
    }

    // --------------------------------------------------------------- what a message ends with

    sealed class Bout {
        data class Grille(val carre: String) : Bout()
        data class Rapport(val db: Int) : Bout()
        data class RRapport(val db: Int) : Bout()
        data class Accuse(val mot: String) : Bout()
        object Rien : Bout()
        object Autre : Bout()
    }

    companion object {
        /** What follows the two callsigns of [texte]. */
        fun bout(texte: String): Bout {
            val mots = texte.trim().uppercase().split(Regex("\\s+"))
            if (mots.size < 2 || mots[0] == "CQ") return Bout.Autre
            val reste = mots.drop(2)
            if (reste.isEmpty()) return Bout.Rien
            val m = reste.joinToString(" ")
            return when {
                m == "RRR" || m == "RR73" || m == "73" -> Bout.Accuse(m)
                Regex("R[+-]\\d{1,2}").matches(m) -> Bout.RRapport(m.substring(1).toInt())
                Regex("[+-]\\d{1,2}").matches(m) -> Bout.Rapport(m.toInt())
                Regex("R [A-R]{2}\\d\\d").matches(m) -> Bout.Grille(m.substring(2))
                Regex("[A-R]{2}\\d\\d").matches(m) -> Bout.Grille(m)
                else -> Bout.Autre
            }
        }

        /** A report as sent: "-07", "+05". */
        fun rapport(db: Int): String { val d = borne(db); return (if (d >= 0) "+" else "-") + abs(d).toString().padStart(2, '0') }

        fun borne(db: Int): Int = db.coerceIn(Ft8Pliage.RAPPORT_MIN, Ft8Pliage.RAPPORT_MAX)

        fun base(c: String): String = Ft8Veille.base(c.removePrefix("<").removeSuffix(">"))

        // ----------------------------------------------------------- slots and timing

        fun dureeTrancheMs(ft4: Boolean): Long = if (ft4) 7_500L else 15_000L

        /** The slot number holding [t]. */
        fun tranche(t: Long, ft4: Boolean): Long = Math.floorDiv(t, dureeTrancheMs(ft4))

        fun estPaire(numero: Long): Boolean = Math.floorMod(numero, 2L) == 0L

        /** A station on time starts sending this far into its slot. */
        const val DEBUT_MS = 500L

        /**
         * The latest start (ms into the slot): later, an FT8 transmission
         * would run into the next slot (12.64 s from 2 s ends at 14.8 s),
         * and an FT4 one beyond what the stations' decoders search.
         */
        fun debutMaxMs(ft4: Boolean): Long = if (ft4) 1_300L else 2_000L

        /** The clock's limit for transmitting (s): FT4 half a second, FT8 one. */
        fun horlogeMaxS(ft4: Boolean): Double = if (ft4) 0.5 else 1.0

        /** Why our clock forbids transmitting ([decalageS] read from the stations' DT, null: unknown), or null. */
        fun refusHorloge(decalageS: Double?, ft4: Boolean): String? {
            val d = decalageS ?: return null
            val max = horlogeMaxS(ft4)
            if (abs(d) <= max) return null
            return "horloge du PC décalée de " + (if (d > 0) "+" else "−") + "%.1f".format(java.util.Locale.FRANCE, abs(d)) +
                " s (au-delà de ±" + "%.1f".format(java.util.Locale.FRANCE, max) + " s en " + (if (ft4) "FT4" else "FT8") +
                ") : la synchroniser (NTP) avant d'émettre"
        }

        /** Bandwidth of one transmission (Hz): 8 × 6.25 for FT8, 4 × 20.83 for FT4. */
        fun largeurHz(ft4: Boolean): Int = if (ft4) 84 else 50

        /**
         * A clear audio frequency to transmit on: [voulue] when no station
         * decoded lately ([occupees], their lowest tones) overlaps it,
         * otherwise the nearest that is clear within [bas]..[haut] (10 Hz
         * steps). Null when the whole range is taken.
         */
        fun frequenceLibre(occupees: Collection<Int>, voulue: Int, ft4: Boolean, bas: Int = 300, haut: Int = 2_700): Int? {
            val l = largeurHz(ft4)
            val marge = 10
            fun libre(f: Int) = f >= bas && f + l <= haut && occupees.none { o -> f < o + l + marge && o < f + l + marge }
            if (libre(voulue)) return voulue
            for (d in 10..(haut - bas) step 10) {
                if (libre(voulue - d)) return voulue - d
                if (libre(voulue + d)) return voulue + d
            }
            return null
        }
    }
}

/**
 * Where an FT8/FT4 transmission lands through a linear transponder: the
 * audio offset added to the rig's uplink dial (taken off on an inverting
 * transponder, the uplink then in LSB), and heard back on the downlink at
 * the same offset above its dial (USB). The rig's dials are the Doppler
 * loop's, already corrected (and shifted by the satellite's own TX offset).
 */
object Ft8Montee {

    /** The uplink frequency (sky Hz) the tones start on, the dial at [monteeHz]. */
    fun montee(monteeHz: Long, audioHz: Int, inverse: Boolean): Long = if (inverse) monteeHz - audioHz else monteeHz + audioHz

    /** The downlink frequency (sky Hz) our lowest tone comes back on, the RX dial at [descenteHz] (USB). */
    fun descente(descenteHz: Long, audioHz: Int): Long = descenteHz + audioHz

    /**
     * Why the signal falls outside the transponder's uplink passband
     * [bas]..[haut] (rest frequencies, the signal brought back to rest by
     * [rangeRateKmS]), or null when inside.
     */
    fun horsBande(monteeHz: Long, audioHz: Int, largeurHz: Int, inverse: Boolean, rangeRateKmS: Double, bas: Long, haut: Long): String? {
        val f1 = montee(monteeHz, audioHz, inverse)
        val f2 = if (inverse) f1 - largeurHz else f1 + largeurHz
        // Back to what the satellite receives: the uplink was raised by the Doppler, the satellite hears it at rest.
        val r1 = Math.round(f1 / (1.0 + rangeRateKmS / 299_792.458))
        val r2 = Math.round(f2 / (1.0 + rangeRateKmS / 299_792.458))
        val lo = minOf(bas, haut); val hi = maxOf(bas, haut)
        return if (minOf(r1, r2) < lo || maxOf(r1, r2) > hi)
            "le signal sortirait de la bande passante montante du transpondeur (" + "%.3f".format(java.util.Locale.FRANCE, lo / 1e6) +
                " – " + "%.3f".format(java.util.Locale.FRANCE, hi / 1e6) + " MHz) : rapprocher l'accord du milieu ou changer la fréquence audio"
        else null
    }
}
