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
        val scoreSynchro: Float,
        /** The pass that found it: 1, or later once stronger signals were taken out. */
        val passe: Int = 1,
        /** Its frequency drift across the transmission, Hz/s: the slot's known one plus its own. */
        val penteHzS: Double = 0.0,
        /**
         * Read with what was expected ([Apriori]) rather than found by the
         * code alone: right in all likelihood, but to be shown as such.
         */
        val ap: Boolean = false,
    )

    /**
     * What a QSO in progress lets us expect: my callsign and my partner's.
     *
     * A weak partner's next message is one of a few dozen (a report, R and a
     * report, RRR, RR73, 73, or its CQ); when the code fails on a candidate,
     * each is compared with what was received bit by bit, and one that agrees
     * far beyond chance is taken. Acknowledgements are expected only once
     * reports have been exchanged ([rapportsEchanges]): a 73 never comes out
     * of a QSO that has not happened.
     */
    data class Apriori(
        val moi: String,
        val partenaire: String,
        val rapportsEchanges: Boolean = false,
        /** The partner's grid (4 characters) when known: its CQ and its call with the grid. */
        val carrePartenaire: String? = null,
    )

    /**
     * Least agreement between the received likelihoods and an expected
     * codeword, Σ llr·s / Σ |llr| (1 = every bit as sent, 0 = chance), and
     * the least lead over the next expected message. Set on the bench: over
     * 1400 noise candidates the best of 125 expected messages never passed
     * 0.40 (about five standard deviations); a partner at −22 dB gives
     * 0.6–0.8, at −23 dB 0.5–0.75.
     */
    private const val ACCORD_AP = 0.55
    /** Sync threshold of the extra spots searched during a QSO. */
    private const val SEUIL_AP = 0.7f
    private const val ECART_AP = 0.10

    /** The expected messages' codewords and their texts' 77 bits. */
    private class Attendu(val bits77: BooleanArray, val mot: BooleanArray)

    private fun attendus(ap: Apriori, mode: Ft8Signal.Mode): List<Attendu> {
        val moi = Ft8.indicatifVers28(ap.moi) ?: return emptyList()
        val lui = Ft8.indicatifVers28(ap.partenaire) ?: return emptyList()
        val l = ArrayList<Attendu>()
        fun ajoute(c1: Long, c2: Long, r: Boolean, g15: Int) {
            val m = BooleanArray(Ft8.BITS_MESSAGE)
            Ft8.ecritEntier(m, 0, 28, c1)
            Ft8.ecritEntier(m, 29, 28, c2)
            m[58] = r
            Ft8.ecritEntier(m, 59, 15, g15.toLong())
            Ft8.ecritEntier(m, 74, 3, 1L)
            val utiles = if (mode.tons == 4) Ft4.avecControle(m) else Ft8.avecControle(m)
            l += Attendu(m, Ldpc.encode(utiles))
        }
        for (r in -30..30) {
            ajoute(moi, lui, false, 32_435 + r)
            ajoute(moi, lui, true, 32_435 + r)
        }
        if (ap.rapportsEchanges) for (g in 32_402..32_404) ajoute(moi, lui, false, g)
        ap.carrePartenaire?.trim()?.uppercase()?.takeIf { Regex("[A-R]{2}[0-9]{2}").matches(it) }?.let { c ->
            val g = (c[0] - 'A') * 1800 + (c[1] - 'A') * 100 + (c[2] - '0') * 10 + (c[3] - '0')
            ajoute(moi, lui, false, g)
            ajoute(2L, lui, false, g)
        }
        return l
    }

    /** Σ llr·s / Σ |llr|, s = +1 where the codeword has a zero. */
    fun accord(douces: FloatArray, mot: BooleanArray): Double {
        var num = 0.0; var den = 0.0
        for (i in 0 until minOf(douces.size, mot.size)) {
            val v = douces[i].toDouble()
            num += if (mot[i]) -v else v
            den += abs(v)
        }
        return if (den > 0.0) num / den else 0.0
    }

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
    enum class Profondeur(
        val candidats: Int,
        val seuilSynchro: Float,
        val toursLdpc: Int,
        /** Passes: after each, what was decoded is taken out and the rest searched again. */
        val passes: Int,
        /** Own drift searched, Hz/s each side, for candidates that failed without. */
        val penteMaxHzS: Double,
        /**
         * How many failed candidates get that search, per slot, best first:
         * it costs ten readings each, and a band full of QRM would otherwise
         * spend the slot on it.
         */
        val essaisPente: Int,
    ) {
        LEGERE(32, 1.5f, 30, 1, 0.0, 0),
        NORMALE(60, 1.3f, 30, 2, 2.0, 25),
        PROFONDE(120, 1.1f, 40, 3, 4.0, 60),
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
     * [apriori], during a QSO, lets a candidate the code cannot read be
     * matched against what the partner is expected to send (see [Apriori]);
     * such decodes carry [Decode.ap].
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
        profondeur: Profondeur? = null,
        apriori: Apriori? = null
    ): List<Decode> {
        if (mode.synchro.isEmpty()) return emptyList()
        val entree = Ft8Signal.reechantillonne(audio, cadenceHz, mode.cadenceHz)
        val ramene = if (penteHzS == 0.0) entree else Ft8Signal.retirePente(entree, mode.cadenceHz, penteHzS)
        if (ramene.size < mode.parSymbole * mode.symboles) return emptyList()

        val tours = profondeur?.toursLdpc ?: Profondeur.LEGERE.toursLdpc
        val passes = profondeur?.passes ?: 1
        // Taken out pass after pass (already a copy: the caller's audio stays as given).
        val reste = ramene

        val sortie = ArrayList<Decode>()
        val dejaVus = HashSet<String>()
        // Drift searches spent so far, against the budget's.
        val essaisPente = IntArray(1)
        val attendus = apriori?.let { attendus(it, mode) }.orEmpty()
        for (passe in 1..passes) {
            val spec = Ft8Signal.spectrogramme(reste, mode, basseHz, hauteHz)
            val seuil = profondeur?.seuilSynchro ?: Profondeur.LEGERE.seuilSynchro
            // During a QSO, fainter spots too: the partner may be below what
            // the code alone reads, and its expected messages are a far
            // stronger test than the sync.
            val candidats = Ft8Signal.candidats(spec,
                maximum = (profondeur?.candidats ?: maximumCandidats) + (if (attendus.isEmpty()) 0 else 60),
                scoreMinimal = if (attendus.isEmpty()) seuil else minOf(seuil, SEUIL_AP),
                voisins = true)
            val spectre = Ft8Fin.spectre(reste, mode)
            val nouveaux = ArrayList<Lu>()
            for (c in candidats) {
                val f0 = c.frequenceHz(spec)
                val t0 = c.instantS(spec)
                // A neighbour of a signal already read in this pass: the same signal again.
                if (nouveaux.any { abs(it.decode.frequenceHz - f0) < mode.ecartHz &&
                        abs(it.decode.instantS - t0) < mode.dureeSymbole }) continue
                val pente = if (c.score >= seuil && essaisPente[0] < (profondeur?.essaisPente ?: 0))
                    profondeur?.penteMaxHzS ?: 0.0 else 0.0
                val lu = essaie(spectre, mode, f0, t0, c.score, tours, pente, essaisPente, attendus) ?: continue
                // Neighbouring candidates can yield the same message; keep only
                // the first, the list is already sorted by score.
                if (!dejaVus.add(lu.decode.message.brut)) continue
                nouveaux.add(lu)
            }
            sortie.addAll(nouveaux.map { it.decode.copy(passe = passe, penteHzS = penteHzS + it.penteHzS) })
            if (nouveaux.isEmpty() || passe == passes) break
            // Each signal read is rebuilt and taken out: the next pass hears
            // what it was hiding.
            for (lu in nouveaux) {
                Ft8Signal.soustrait(reste, lu.tons, mode, lu.decode.frequenceHz, lu.decode.instantS, lu.penteHzS)
            }
        }
        return sortie
    }

    /** A decode and what is needed to take it out of the audio. */
    private class Lu(val decode: Decode, val tons: IntArray, val penteHzS: Double)

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
        penteMaxHzS: Double = 0.0,
        /** Counts the drift searches made. */
        essaisPente: IntArray? = null,
        attendus: List<Attendu> = emptyList(),
    ): Lu? {
        val e = mode.ecartHz
        val canal = Ft8Fin.canal(spectre, f0, -2.5 * e, (mode.tons + 1.5) * e)
        // A rough start is within half a symbol, a rough frequency within a
        // quarter of a tone: search a little wider than that.
        val pas = e / 16
        val decalages = DoubleArray(13) { (it - 6) * pas }
        val debut = Math.round(t0 * canal.cadence).toInt()
        val l = Ft8Fin.synchronise(canal, mode, debut, Ft8Fin.PAR_SYMBOLE / 2, decalages, doubleArrayOf(0.0))
        lis(l, canal, mode, score, tours)?.let { return it }
        if (penteMaxHzS <= 0.0) return parAttente(l, canal, mode, score, attendus)
        essaisPente?.let { it[0]++ }

        // --- a drift of its own ---
        //
        // A station whose own Doppler nobody removes (its uplink, a rig
        // that does not follow), or a slot whose drift was not known: the
        // tones slide by a tone or more across the transmission and the
        // reading above failed. A few drifts each side, 1 Hz/s apart, then
        // the best refined to a quarter: half a hertz per second left over
        // is still under a tone at the ends.
        val marge = penteMaxHzS * mode.dureeS / 2
        val large = Ft8Fin.canal(spectre, f0, -2.5 * e - marge, (mode.tons + 1.5) * e + marge)
        val n = penteMaxHzS.toInt()
        val pentes = (-n..n).filter { it != 0 }.map { it.toDouble() }.toDoubleArray()
        val grossier = Ft8Fin.synchronise(large, mode, debut, Ft8Fin.PAR_SYMBOLE / 2, decalages, pentes)
        val fin = Ft8Fin.synchronise(large, mode, grossier.debut, 4,
            DoubleArray(5) { grossier.decalageHz + (it - 2) * pas / 2 },
            DoubleArray(5) { grossier.penteHzS + (it - 2) * 0.25 })
        return lis(fin, large, mode, score, tours)
            ?: parAttente(l, canal, mode, score, attendus)
    }

    /**
     * The last chance of a candidate during a QSO: the expected message
     * that agrees best with what was received, if it agrees far beyond
     * chance, far ahead of the next one, at a believable SNR.
     */
    private fun parAttente(l: Ft8Fin.Lecture, canal: Ft8Fin.Canal, mode: Ft8Signal.Mode, score: Float,
                           attendus: List<Attendu>): Lu? {
        if (attendus.isEmpty()) return null
        val douces = Ft8Fin.vraisemblances(l, mode)
        var meilleur: Attendu? = null
        var a1 = -1.0; var a2 = -1.0
        for (a in attendus) {
            val v = accord(douces, a.mot)
            if (v > a1) { a2 = a1; a1 = v; meilleur = a } else if (v > a2) a2 = v
        }
        val m = meilleur ?: return null
        if (a1 < ACCORD_AP || a1 - a2 < ECART_AP) return null
        val message = Ft8.deplie(m.bits77) ?: return null
        val envoyes = if (mode.tons == 4) Ft4.bitsVersSymboles(m.mot) else Ft8.bitsVersSymboles(m.mot)
        val rapport = Ft8Fin.rapportDb(l, envoyes, mode)
        if (rapport < -28) return null
        return Lu(Decode(message, canal.origineHz + l.decalageHz, l.debut / canal.cadence, rapport, score,
            ap = true), envoyes, l.penteHzS)
    }

    /** A candidate's tones, read where it sits, to a message or nothing. */
    private fun lis(l: Ft8Fin.Lecture, canal: Ft8Fin.Canal, mode: Ft8Signal.Mode, score: Float, tours: Int): Lu? {

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
        return Lu(Decode(
            message = message,
            frequenceHz = canal.origineHz + l.decalageHz,
            instantS = l.debut / canal.cadence,
            rapportDb = Ft8Fin.rapportDb(l, envoyes, mode),
            scoreSynchro = score,
            penteHzS = l.penteHzS
        ), envoyes, l.penteHzS)
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
