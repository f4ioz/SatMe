/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * The sound the decoders hear (SSTV, FT8, CW, APRS, phonie): which source to
 * start when one is switched on with nothing feeding it, what to say when
 * they hear nothing (with the one gesture that mends it), and what a pass
 * needs. Receive only: a capture card or the receiver, nothing that sends.
 */
object SourceAudio {

    /** The receiver (SDR) as a source: it feeds the decoders while it runs. */
    const val SDR = "sdr"
    /** Below this level the sound is silence (a stopped AF gain, a muted card). */
    const val SILENCE = 0.003
    /** Above this level the sound clips. */
    const val SATURE = 0.95
    /** Silence said only after this long (a quiet transponder between two overs is not a fault). */
    const val SILENCE_MS = 20_000L

    /**
     * The card started for a decoder switched on with nothing feeding: the one
     * chosen last time this satellite was followed, else the last one used,
     * else the rig's own. Never a guess among the others (a microphone, the
     * motherboard's input): null then.
     */
    fun carteAPrendre(cartes: List<String>, pourSatellite: String?, derniere: String?, duPoste: String?): String? =
        listOf(pourSatellite, derniere, duPoste).firstOrNull { !it.isNullOrBlank() && it != SDR && it in cartes }

    /** « SSTV », « SSTV et FT8 », « SSTV, FT8 et CW ». */
    fun liste(noms: List<String>): String = when (noms.size) {
        0 -> ""
        1 -> noms[0]
        else -> noms.dropLast(1).joinToString(", ") + " et " + noms.last()
    }

    /** What the decoders' state calls for: a line, and the gesture that mends it ([action] empty: none). */
    class Conseil(val texte: String, val action: String = "", val bouton: String = "")

    /**
     * Why the decoders on ([decodeurs]) hear nothing, or badly; null when all
     * is well or none listens. [carte]: the card to start ([carteAPrendre]),
     * [libelle] its name for the operator; [silenceMs]: how long the source
     * running has been silent.
     */
    fun conseil(decodeurs: List<String>, actif: Boolean, niveau: Double, silenceMs: Long,
                carte: String?, libelle: String, sdrPresent: Boolean, source: String): Conseil? {
        if (decodeurs.isEmpty()) return null
        val qui = liste(decodeurs)
        val entend = if (decodeurs.size > 1) "n'entendent" else "n'entend"
        if (!actif) return when {
            carte != null -> Conseil("$qui $entend rien : aucune source audio en marche.", "carte:$carte", "▶ Démarrer ${libelle.ifBlank { carte }}")
            sdrPresent -> Conseil("$qui $entend rien : la carte son et le récepteur SDR sont arrêtés.", SDR, "▶ Démarrer le récepteur SDR")
            else -> Conseil("$qui $entend rien : aucune source audio. Choisir une carte son.")
        }
        if (niveau >= SATURE) return Conseil("Le son de $source sature : baisser le volume (AF) du poste ou le niveau de la carte.")
        if (niveau < SILENCE && silenceMs >= SILENCE_MS)
            return Conseil("$source ne donne aucun son depuis ${silenceMs / 1000} s : volume (AF) du poste, ou carte son muette ?")
        return null
    }

    /** The decoders a transmitter calls for, from its mode and its description (SatNOGS' words). */
    fun decodeursPour(mode: String, description: String): List<String> {
        val m = mode.uppercase()
        val d = description.uppercase()
        val l = mutableListOf<String>()
        if ("SSTV" in m || "SSTV" in d) l += "SSTV"
        if ("APRS" in m || "APRS" in d || "AFSK" in m || Regex("\\b1K2\\b|1200 ?BD|1200 ?BAUD").containsMatchIn(d)) l += "APRS"
        if (m == "CW" || "CW" in d.split(Regex("[^A-Z0-9]+"))) l += "CW"
        if ("FT8" in m || "FT8" in d || "FT4" in m || "FT4" in d) l += "FT8"
        return l
    }

    /**
     * A pass made ready, as the operator would do it by hand: the source that
     * hears it, the decoders its transmitter calls for, the recording.
     * [source]: a card's name, [SDR], or null (keep what feeds now).
     */
    class Plan(val source: String?, val decodeurs: List<String>, val enregistrer: Boolean)

    /**
     * The source for a pass: the one remembered for this satellite, else the
     * rig's own sound when the rig is in CAT, else the receiver when it runs
     * (it feeds the decoders already), else the last card used.
     */
    fun plan(mode: String, description: String, cartes: List<String>, pourSatellite: String?, derniere: String?,
             duPoste: String?, catConnecte: Boolean, sdrActif: Boolean, enregistrer: Boolean = true): Plan {
        val source = when {
            pourSatellite == SDR -> SDR
            !pourSatellite.isNullOrBlank() && pourSatellite in cartes -> pourSatellite
            catConnecte && !duPoste.isNullOrBlank() && duPoste in cartes -> duPoste
            sdrActif -> null
            !derniere.isNullOrBlank() && derniere in cartes -> derniere
            !duPoste.isNullOrBlank() && duPoste in cartes -> duPoste
            else -> null
        }
        return Plan(source, decodeursPour(mode, description), enregistrer)
    }
}
