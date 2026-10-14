/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cw.DecodeurCw
import fr.f4ioz.satcombo.plateforme.Stockage

/**
 * The CW decoder on the PC's sound (the sound card, or the dongle when it is
 * the source): off unless chosen, its text kept for the Carnet's small window.
 */
class CwPc(private val stockage: Stockage) {

    var actif: Boolean
        get() = stockage.getBoolean("pc_cw", false)
        set(v) { stockage.edit().putBoolean("pc_cw", v).apply(); if (!v) decodeur = null }

    /** The tone to listen to (Hz); 0 = found by itself. */
    var tonChoisi: Int
        get() = stockage.getInt("pc_cw_ton", 0)
        set(v) { stockage.edit().putInt("pc_cw_ton", v).apply(); decodeur?.fixeTon(v) }

    @Volatile private var decodeur: DecodeurCw? = null
    @Volatile private var rateDecodeur = 0
    private val texte = StringBuilder()
    /** Bumps with every letter: the page knows to draw again. */
    @Volatile var version = 0L
        private set

    @Synchronized fun texte(): String = texte.toString()

    @Synchronized fun efface() { texte.setLength(0); version++ }

    fun ecoute(pcm: ShortArray, n: Int, rate: Int) {
        if (!actif) return
        val d = decodeur?.takeIf { rateDecodeur == rate } ?: DecodeurCw(rate, { ajoute(it) }, tonChoisi).also { decodeur = it; rateDecodeur = rate }
        d.ecoute(pcm, n)
    }

    @Synchronized private fun ajoute(s: String) {
        if (s == " " && (texte.isEmpty() || texte.last() == ' ')) return
        texte.append(s)
        if (texte.length > MAX) texte.delete(0, texte.length - MAX)
        version++
    }

    // ---------------------------------------------------- what the text holds

    /** The log's callsigns (their base): one decoded that is in the log is right. Read again each minute. */
    @Volatile var connus: () -> Set<String> = { emptySet() }
    @Volatile private var connusCache: Set<String> = emptySet()
    @Volatile private var connusMs = 0L

    /** The text word by word: callsigns that make sense, squares, reports, procedure ([fr.f4ioz.satcombo.cw.AnalyseCw]). */
    fun mots(): List<fr.f4ioz.satcombo.cw.AnalyseCw.Mot> {
        val maintenant = System.currentTimeMillis()
        if (maintenant - connusMs > 60_000L) { connusCache = runCatching(connus).getOrDefault(emptySet()); connusMs = maintenant }
        return fr.f4ioz.satcombo.cw.AnalyseCw.analyse(texte(), connusCache)
    }

    // ------------------------------------------------------------ the Doppler

    /** How the note is followed now: "poste" (the rig follows the Doppler), "prédit" (from the pass), "mesuré" (heard only). */
    @Volatile var suivi: String = "mesuré"
        private set
    private var dopplerPrec: Double? = null

    /**
     * Each second: the satellite's Doppler on the downlink now ([dopplerHz]).
     * When nothing corrects it before the sound (no CAT, Doppler paused, a
     * rig tuned by hand), the note slides with it: the decoder is told,
     * [signe] -1 in LSB (the note goes the other way).
     */
    fun ticDoppler(dopplerHz: Double?, corrigeAvant: Boolean, signe: Int) {
        val d = decodeur
        if (d == null || dopplerHz == null) { dopplerPrec = null; suivi = "mesuré"; return }
        if (corrigeAvant) { dopplerPrec = null; suivi = "poste"; return }
        dopplerPrec?.let { d.decaleTon(signe * (dopplerHz - it)) }
        dopplerPrec = dopplerHz
        suivi = "prédit"
    }

    val wpm: Int get() = decodeur?.wpm ?: 0
    val tonHz: Int get() = decodeur?.tonHz ?: tonChoisi
    val marque: Boolean get() = decodeur?.marque == true
    val ecartDb: Double get() = decodeur?.ecartDb ?: 0.0

    companion object { const val MAX = 4000 }
}
