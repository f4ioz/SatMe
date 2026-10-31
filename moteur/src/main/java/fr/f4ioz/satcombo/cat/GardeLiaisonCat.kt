/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

/**
 * Is the rig still answering, and what to do when it is not: the decision
 * alone, no line touched (the caller probes and reopens), so it can be
 * tested with a clock in hand.
 *
 * - **Alive.** The rig answered less than [muetMs] ago: the Doppler loop
 *   runs as before. Past that, one read checks ([Geste.SONDE]) before
 *   calling it silent — the loop may simply have had nothing to ask.
 * - **Silent.** The loop stops writing (no more frames into a rig that does
 *   not hear, ten times a second). A single read is tried after 2, 4, 8, 16
 *   then every 30 s ([paliersMs]); from the second try on, the port is
 *   closed and opened again first when it can be ([Geste.ROUVRE]) — by
 *   the caller, with RTS and DTR low: nothing unattended raises a line
 *   that could key a transmitter.
 * - **Gone.** The port vanished (USB unplugged, the rig switched off takes
 *   its own USB with it): nothing is tried until it is back, then at once.
 * - **Back.** The first answer: alive again, the caller sets the rig up
 *   again (frequencies, modes) — never its transmitter.
 *
 * A refusal (CI-V `FA`, Kenwood `?;`) is an answer: the rig is there and
 * said no. It is kept with its time for the status line, never taken for a
 * success.
 */
class GardeLiaisonCat(
    /** No answer for this long: one read checks. */
    val muetMs: Long = 4_000L,
    /** The waits between two tries while silent; the last one repeats. */
    val paliersMs: List<Long> = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L),
) {
    enum class Geste {
        /** Normal round: the rig answers. */
        TOUR,
        /** One read, then [resultat]. */
        SONDE,
        /** Close and reopen the port, then one read, then [resultat]. */
        ROUVRE,
        /** Nothing sent: waiting for the next try. */
        ATTENDS,
    }

    /** Silent since (ms of the last answer, or of the opening); null while alive. */
    @Volatile var muetDepuisMs: Long? = null; private set
    /** When the next try is due (ms), while silent. */
    @Volatile var prochainEssaiMs: Long = 0L; private set
    /** Tries made since the rig went silent. */
    @Volatile var essais: Int = 0; private set
    /** The port seen missing ([decide]'s portPresent false) since (ms), null when present or unknown. */
    @Volatile var absentDepuisMs: Long? = null; private set
    /** The rig answered again after a silence: when (ms), 0 never. */
    @Volatile var reprisMs: Long = 0L; private set
    /** How many times it came back. */
    @Volatile var reprises: Int = 0; private set

    @Volatile private var ouvertMs = 0L
    @Volatile private var derniereSondeMs = Long.MIN_VALUE / 2
    @Volatile private var sondeOkMs = 0L
    /** The last answer known when a check was asked while alive: the silence dates from it. */
    @Volatile private var refSonde = 0L

    /** The last refusal seen (in words) and when; "" none. */
    @Volatile var dernierRefus: String = ""; private set
    @Volatile var dernierRefusMs: Long = 0L; private set

    val muet: Boolean get() = muetDepuisMs != null

    /**
     * Added to the silent line when a reopening has been tried (set by the
     * caller): the automatic one opens with RTS and DTR low, which some rigs
     * need up to answer — then only a reconnection by hand brings them back.
     */
    @Volatile var noteReouverture: String = ""

    /** The link was (re)opened at [t]: counted from there. */
    fun ouvert(t: Long) {
        ouvertMs = t
        muetDepuisMs = null; essais = 0; absentDepuisMs = null
        derniereSondeMs = Long.MIN_VALUE / 2
    }

    /** The rig refused [quoi] at [t]: an answer, kept for the status line. */
    fun refus(quoi: String, t: Long) { dernierRefus = quoi; dernierRefusMs = t }

    /**
     * What to do at [t]. [reponduMs]: when the rig last answered anything (0:
     * never); [portPresent]: false when the port is gone, null when that
     * cannot be told (a network link); [rouvrable]: the port can be closed
     * and opened again here.
     */
    fun decide(t: Long, reponduMs: Long, portPresent: Boolean?, rouvrable: Boolean): Geste {
        val depuis = muetDepuisMs
        if (depuis == null) {
            val ref = maxOf(reponduMs, ouvertMs, sondeOkMs)
            if (t - ref < muetMs) return Geste.TOUR
            if (portPresent == false) { tombe(t, ref); absentDepuisMs = t; return Geste.ATTENDS }
            // The loop may have had nothing to ask: one read before saying silent.
            if (t - derniereSondeMs >= muetMs) { derniereSondeMs = t; refSonde = ref; return Geste.SONDE }
            return Geste.TOUR
        }
        if (portPresent == false) {
            if (absentDepuisMs == null) absentDepuisMs = t
            return Geste.ATTENDS
        }
        // Back after being gone: at once, the port opened again.
        if (absentDepuisMs != null) {
            absentDepuisMs = null
            return if (rouvrable) Geste.ROUVRE else Geste.SONDE
        }
        if (t < prochainEssaiMs) return Geste.ATTENDS
        return if (rouvrable && essais >= 1) Geste.ROUVRE else Geste.SONDE
    }

    /**
     * The read asked by [decide] gave [repondu] at [t]. True when the rig is
     * back after a silence: the caller sets it up again.
     */
    fun resultat(t: Long, repondu: Boolean): Boolean {
        if (repondu) {
            sondeOkMs = t
            val revient = muetDepuisMs != null
            muetDepuisMs = null; essais = 0; absentDepuisMs = null
            if (revient) { reprisMs = t; reprises++ }
            return revient
        }
        if (muetDepuisMs == null) tombe(t, refSonde)
        else essais++
        prochainEssaiMs = t + paliersMs[minOf(essais, paliersMs.size - 1)]
        return false
    }

    private fun tombe(t: Long, depuis: Long) {
        muetDepuisMs = depuis.coerceAtMost(t)
        essais = 0
        prochainEssaiMs = t + paliersMs.first()
    }

    /**
     * The status line: « poste muet depuis 2 min — nouvel essai dans 20 s »,
     * « port absent depuis 40 s (câble USB, poste éteint ?) — repris dès son
     * retour »; « » when all is well.
     */
    fun message(t: Long, nom: String = "poste"): String {
        val depuis = muetDepuisMs ?: return ""
        val absent = absentDepuisMs
        if (absent != null) return "$nom muet depuis ${duree(t - depuis)} : son port USB a disparu (câble débranché, poste éteint ?) — repris dès son retour" +
            (if (noteReouverture.isNotBlank()) " ($noteReouverture)" else "")
        val dans = (prochainEssaiMs - t).coerceAtLeast(0L)
        return "$nom muet depuis ${duree(t - depuis)} — " + (if (dans < 1_000L) "nouvel essai en cours" else "nouvel essai dans ${duree(dans)}") +
            (if (essais > 0) " ($essais essai" + (if (essais > 1) "s" else "") + " sans réponse)" else "") +
            (if (noteReouverture.isNotBlank() && essais > 0) " — $noteReouverture" else "")
    }

    /** The last refusal, dated, when recent ([fenetreMs]); "" otherwise. */
    fun messageRefus(t: Long, fenetreMs: Long = 60_000L): String =
        if (dernierRefus.isBlank() || t - dernierRefusMs > fenetreMs) "" else "refusé par le poste : $dernierRefus (${ilYa(t - dernierRefusMs)})"

    companion object {
        /** « 45 s », « 2 min », « 1 h 05 ». */
        fun duree(ms: Long): String {
            val s = (ms.coerceAtLeast(0L) + 500L) / 1000L
            return when {
                s < 60 -> "$s s"
                s < 3600 -> "${s / 60} min" + (if (s < 600 && s % 60 != 0L) " ${s % 60} s" else "")
                else -> "${s / 3600} h " + String.format("%02d", (s % 3600) / 60)
            }
        }

        /** « à l'instant », « il y a 12 s », « il y a 4 min ». */
        fun ilYa(ms: Long): String {
            val s = (ms.coerceAtLeast(0L) + 500L) / 1000L
            return when {
                ms < 3_000L -> "à l'instant"
                s < 60 -> "il y a $s s"
                s < 3600 -> "il y a ${s / 60} min"
                else -> "il y a ${s / 3600} h " + String.format("%02d", (s % 3600) / 60)
            }
        }
    }
}
