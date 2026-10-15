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
import kotlin.math.log10

/**
 * Full decode of one listening slot: from audio to messages.
 *
 * Chains `Ft8Signal` (reads tones) and `Ft8` (reads messages), and **decides**:
 * a candidate whose CRC fails is not shown, not even flagged.
 *
 * **The CRC is the last barrier; know what it is worth.** Random bits pass
 * the 14-bit CRC once in 16 384. At 32 candidates per slot and four slots a
 * minute, that is a false message about every two hours — rare, not never.
 * That is why [Decode] carries the SNR: a "valid" message at −20 dB from a
 * decoder that does not go below zero is not to be believed.
 */
object Ft8Decodeur {

    /**
     * A decoded message, with what is needed to judge whether to trust it.
     */
    data class Decode(
        val message: Ft8.Message,
        /** Audio frequency of the lowest tone, Hz. */
        val frequenceHz: Double,
        /** Start of the transmission within the slot, seconds. */
        val instantS: Double,
        /** SNR referred to 2500 Hz, like an FT8 report. */
        val rapportDb: Int,
        /** Sync score, for sorting and diagnostics. */
        val scoreSynchro: Float
    )

    /**
     * How hard to look at a slot.
     *
     * More candidates and a lower sync threshold reach weaker stations, more
     * LDPC rounds save a few that converge late; all of it costs time, and a
     * slot must be decoded before the next one closes. [LEGERE] is what a
     * phone can always afford; [PROFONDE] is for a PC, or a low satellite
     * whose stations are all weak.
     *
     * More candidates are also more chances for noise to pass the CRC: that
     * is why the hard-decision fallback is held to a clean sync (see
     * [decode]).
     */
    enum class Profondeur(val candidats: Int, val seuilSynchro: Float, val toursLdpc: Int) {
        LEGERE(32, 1.5f, 30),
        NORMALE(60, 1.3f, 30),
        PROFONDE(120, 1.1f, 40),
    }

    /**
     * Fewest sync symbols the hard decisions must get right before a word
     * read without LDPC is believed: random bits pass the 14-bit CRC once in
     * 16 384, and a deep search tries many candidates. 13 of 21 for FT8,
     * 10 of 16 for FT4 (noise candidates stay below 11 of 21 on the bench): a
     * real signal strong enough to be read without the
     * code clears it easily; noise almost never does.
     */
    private fun synchroFranche(tons: IntArray, mode: Ft8Signal.Mode): Boolean =
        if (mode.tons == 4) Ft4.scoreCostas(tons) >= 10 else Ft8.scoreCostas(tons) >= 13

    /**
     * Noise bandwidth correction.
     *
     * A transform over exactly one symbol has a noise bandwidth equal to the
     * inverse of its duration (6.25 Hz for FT8); FT8 reports are referred to
     * 2500 Hz, hence a constant offset.
     */
    private fun correctionDb(mode: Ft8Signal.Mode): Double =
        10.0 * log10(2500.0 / mode.ecartHz)

    /**
     * Decodes one slot of audio.
     *
     * [cadenceHz] is the capture rate, whatever it is; resampling to the
     * mode's internal rate is done here.
     *
     * [penteHzS] is the slot's known frequency drift (Hz/s, as heard in the
     * audio): the derivative of the satellite's downlink Doppler, from the
     * ephemeris, when nothing upstream follows it. It is removed before
     * anything else (see [Ft8Signal.retirePente]); zero leaves the audio as is.
     *
     * [profondeur] sets the search budget; without it, [maximumCandidats]
     * candidates at the usual threshold (the light budget).
     *
     * Returns messages strongest first. An empty list — nothing passed the
     * CRC — is the most common and normal case.
     */
    fun decode(
        audio: FloatArray,
        cadenceHz: Double,
        mode: Ft8Signal.Mode = Ft8Signal.FT8,
        basseHz: Double = 200.0,
        hauteHz: Double = 3000.0,
        maximumCandidats: Int = 32,
        penteHzS: Double = 0.0,
        profondeur: Profondeur? = null
    ): List<Decode> {
        if (mode.synchro.isEmpty()) return emptyList()
        val entree = Ft8Signal.reechantillonne(audio, cadenceHz, mode.cadenceHz)
        val ramene = if (penteHzS == 0.0) entree else Ft8Signal.retirePente(entree, mode.cadenceHz, penteHzS)
        if (ramene.size < mode.parSymbole * mode.symboles) return emptyList()

        val spec = Ft8Signal.spectrogramme(ramene, mode, basseHz, hauteHz)
        val candidats = Ft8Signal.candidats(spec,
            maximum = profondeur?.candidats ?: maximumCandidats,
            scoreMinimal = profondeur?.seuilSynchro ?: Profondeur.LEGERE.seuilSynchro,
            voisins = true)
        val tours = profondeur?.toursLdpc ?: Profondeur.LEGERE.toursLdpc
        val spectre = Ft8Fin.spectre(ramene, mode)

        val sortie = ArrayList<Decode>()
        val dejaVus = HashSet<String>()
        for (c in candidats) {
            val f0 = c.frequenceHz(spec)
            val t0 = c.instantS(spec)
            // A neighbour of a signal already read: the same signal again.
            if (sortie.any { abs(it.frequenceHz - f0) < mode.ecartHz && abs(it.instantS - t0) < mode.dureeSymbole }) continue
            val d = essaie(spectre, mode, f0, t0, c.score, tours) ?: continue
            // Neighbouring candidates can yield the same message; keep only
            // the first, the list is already sorted by score.
            if (!dejaVus.add(d.message.brut)) continue
            sortie.add(d)
        }
        return sortie
    }

    /**
     * One candidate, from its rough place to a message or nothing: its own
     * channel, start, frequency and drift searched finely there
     * ([Ft8Fin.synchronise]), its tones read, LDPC, CRC, unpacking.
     */
    private fun essaie(
        spectre: Ft8Fin.Spectre,
        mode: Ft8Signal.Mode,
        f0: Double,
        t0: Double,
        score: Float,
        tours: Int,
    ): Decode? {
        val e = mode.ecartHz
        val canal = Ft8Fin.canal(spectre, f0, -2.5 * e, (mode.tons + 1.5) * e)
        // A rough start is within half a symbol, a rough frequency within a
        // quarter of a tone: search a little wider than that.
        val pas = e / 16
        val decalages = DoubleArray(13) { (it - 6) * pas }
        val l = Ft8Fin.synchronise(canal, mode, Math.round(t0 * canal.cadence).toInt(),
            Ft8Fin.PAR_SYMBOLE / 2, decalages, doubleArrayOf(0.0))

        // --- error correction first ---
        //
        // If LDPC does not converge, fall back to hard decisions: free, and
        // it saves very strong signals the decoder may reject when the
        // graph oscillates.
        val douces = Ft8Fin.vraisemblances(l, mode)
        val corrige = Ldpc.decode(douces, tours).bits
        val tons = Ft8Fin.tons(l)
        // The only place the two modes differ: FT4 packs two bits per
        // symbol instead of three and scrambles the 77 message bits before
        // the CRC. Past that, same CRC, format and unpacking.
        val quatreTons = mode.tons == 4
        if (corrige == null && !synchroFranche(tons, mode)) return null
        val bits = corrige ?: (if (quatreTons) Ft4.symbolesVersBits(tons)
                   else Ft8.symbolesVersBits(tons))
        val utiles = bits.copyOf(Ft8.BITS_UTILES)
        // CRC first: no point unpacking what is wrong.
        if (!Ft8.controleJuste(utiles)) return null
        val brut = if (quatreTons) Ft4.message(utiles)
                   else utiles.copyOf(Ft8.BITS_MESSAGE)
        val message = Ft8.deplie(brut) ?: return null
        // The tones actually sent, for the SNR: the codeword's when LDPC
        // gave one, else what was read.
        val envoyes = if (corrige == null) tons
            else if (quatreTons) Ft4.bitsVersSymboles(corrige) else Ft8.bitsVersSymboles(corrige)
        return Decode(
            message = message,
            frequenceHz = canal.origineHz + l.decalageHz,
            instantS = l.debut / canal.cadence,
            rapportDb = Ft8Fin.rapportDb(l, envoyes, mode),
            scoreSynchro = score
        )
    }

    /**
     * Estimates a candidate's SNR, in dB in 2500 Hz.
     *
     * At the sync positions the expected tone carries signal plus noise; noise
     * is measured beside the signal band (see below). The difference gives
     * the signal, then the ratio is referred to the reference bandwidth.
     *
     * **It saturates at the top.** The Gaussian smoothing of the transitions
     * spreads a little energy either side, indistinguishable from noise, so
     * above about 15 dB the estimate stops rising. That does not hurt its job
     * (trust is decided at the bottom end), but SatMe reports are not
     * comparable with WSJT-X for strong stations.
     */
    fun rapportDb(
        spec: Ft8Signal.Spectrogramme,
        c: Ft8Signal.Candidat,
        mode: Ft8Signal.Mode
    ): Int {
        val pasParSymbole = mode.parSymbole / spec.pasTemps
        val largeur = (mode.tons - 1) * spec.raieParTon

        var attendu = 0.0
        var bruitSomme = 0.0
        var bruitComptes = 0L
        for ((position, tonAttendu) in mode.synchro) {
            val t = c.pas + position * pasParSymbole
            attendu += spec.puissance(t, c.raie + tonAttendu * spec.raieParTon).toDouble()
            // Noise is measured **outside** the signal band, not on its other
            // tones. The Gaussian smoothing leaks into those, and taking the
            // leak for noise capped the estimate near −9 dB: a clean signal
            // looked as poor as a buried one. Six guard bins leave the skirt
            // behind.
            for (d in 6..24) {
                val bas = c.raie - d
                val haut = c.raie + largeur + d
                if (bas >= 0) { bruitSomme += spec.puissance(t, bas).toDouble(); bruitComptes++ }
                if (haut < spec.nbRaies) {
                    bruitSomme += spec.puissance(t, haut).toDouble(); bruitComptes++
                }
            }
        }
        if (bruitComptes == 0L) return -99
        val n = mode.synchro.size.toDouble()
        val bruit = bruitSomme / bruitComptes
        val signal = (attendu / n) - bruit
        if (bruit <= 0.0 || signal <= 0.0) return -99
        val brut = 10.0 * log10(signal / bruit) - correctionDb(mode)
        // FT8 reports span −24 to +30; beyond that the number means nothing
        // and a wild value would cast doubt on the rest.

        return brut.coerceIn(-30.0, 30.0).toInt()
    }
}
