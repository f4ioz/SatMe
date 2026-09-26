/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The radio part: from audio to symbols — finding the signal in the noise,
 * its start time and frequency, and reading its tones.
 *
 * **All of it is testable without a radio.** Synthesize a signal from known
 * symbols, shift it in time and frequency, add noise, check the same symbols
 * come out. For this part the bench is stricter than the field, because the
 * truth is known.
 *
 * **Internal sample rate.** Audio is resampled so one symbol is exactly a
 * power of two: 2048 samples for FT8, 512 for FT4. Two gains:
 *
 * - a plain radix-2 FFT;
 * - tones land **exactly** on bins, with no leakage. Adjacent tones are
 *   strictly orthogonal only if the analysis spans exactly one symbol.
 *
 * The capture rate no longer matters (44 100, 48 000, 16 000), nor what
 * Android grants `AudioRecord` on a given phone.
 */
object Ft8Signal {

    /**
     * What differs between FT8 and FT4, gathered so the chain is shared.
     *
     * Only the physical layer differs; two demodulators would be the same one
     * written twice, and they would drift apart.
     */
    data class Mode(
        val nom: String,
        /** Number of tones: 8 for FT8, 4 for FT4. */
        val tons: Int,
        /** Samples per symbol — a power of two, by design. */
        val parSymbole: Int,
        /** Symbol duration, seconds. */
        val dureeSymbole: Double,
        /** Total channel symbols. */
        val symboles: Int,
        /** Sync symbol positions and expected tone. */
        val synchro: List<Pair<Int, Int>>,
        /**
         * Bandwidth-time product of the Gaussian shaping.
         *
         * **Not** the same for both modes: the K1JT/K9AN/G4WJS article gives
         * BT = 2 for FT8 and BT = 1 for FT4. FT4 synthesized at BT = 2 would be
         * too wide and disturb its neighbours.
         */
        val lissageBT: Double = 2.0
    ) {
        /** Internal rate: the one that makes [parSymbole] exact. */
        val cadenceHz: Double get() = parSymbole / dureeSymbole
        /** Tone spacing, equal to the symbol rate. */
        val ecartHz: Double get() = 1.0 / dureeSymbole
        val dureeS: Double get() = symboles * dureeSymbole
    }

    /** FT8 sync positions: three arrays of seven. */
    private fun synchroFt8(): List<Pair<Int, Int>> {
        val l = ArrayList<Pair<Int, Int>>(21)
        for (depart in intArrayOf(0, 36, 72)) {
            for (i in 0 until 7) l.add((depart + i) to Ft8.COSTAS[i])
        }
        return l
    }

    val FT8 = Mode(
        nom = "FT8",
        tons = 8,
        parSymbole = 2048,           // 2048 / 0,16 s = 12 800 Hz
        dureeSymbole = Ft8.DUREE_SYMBOLE_S,
        symboles = Ft8.SYMBOLES,
        synchro = synchroFt8(),
        lissageBT = 2.0
    )

    /**
     * FT4.
     *
     * Parameters and the four Costas arrays come from the public-domain QEX
     * protocol description (K9AN, G4WJS, K1JT). Nothing is taken from the
     * GPL WSJT-X code. BT = 1, see [Mode.lissageBT].
     */
    val FT4 = Mode(
        nom = "FT4",
        tons = 4,
        parSymbole = 512,            // 512 / 0,048 s = 10 666,67 Hz
        dureeSymbole = Ft4.DUREE_SYMBOLE_S,
        symboles = Ft4.SYMBOLES,
        synchro = Ft4.synchro(),
        lissageBT = 1.0
    )

    // ------------------------------------------------------------ resampling

    /**
     * Resamples an audio block by cubic interpolation.
     *
     * Not linear: linear interpolation of a 3 kHz signal sampled at 12 kHz
     * gives about 1 % distortion, which leaks between adjacent tones — exactly
     * what we are trying to tell apart.
     */
    fun reechantillonne(entree: FloatArray, deHz: Double, versHz: Double): FloatArray {
        if (entree.isEmpty()) return FloatArray(0)
        if (kotlin.math.abs(deHz - versHz) < 1e-9) return entree.copyOf()
        val pas = deHz / versHz
        val sortie = FloatArray(((entree.size / pas).toInt()).coerceAtLeast(0))
        for (n in sortie.indices) {
            val x = n * pas
            val i = x.toInt()
            val f = (x - i).toFloat()
            // Catmull-Rom, edges held by repetition rather than zeros: a zero
            // at the edge is a step, and a step spreads across the spectrum.
            val p0 = entree[(i - 1).coerceIn(0, entree.size - 1)]
            val p1 = entree[i.coerceIn(0, entree.size - 1)]
            val p2 = entree[(i + 1).coerceIn(0, entree.size - 1)]
            val p3 = entree[(i + 2).coerceIn(0, entree.size - 1)]
            sortie[n] = (0.5f * ((2f * p1) + (-p0 + p2) * f +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * f * f +
                (-p0 + 3f * p1 - 3f * p2 + p3) * f * f * f))
        }
        return sortie
    }

    // ------------------------------------------------------------------- FFT

    /** In-place radix-2 FFT; both arrays the same power-of-two length. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "la longueur doit être une puissance de deux" }
        // Bit reversal.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var longueur = 2
        while (longueur <= n) {
            val angle = -2.0 * PI / longueur
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until longueur / 2) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + longueur / 2]; val bi = im[i + k + longueur / 2]
                    val tr = br * cr - bi * ci
                    val ti = br * ci + bi * cr
                    re[i + k] = ar + tr; im[i + k] = ai + ti
                    re[i + k + longueur / 2] = ar - tr; im[i + k + longueur / 2] = ai - ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += longueur
            }
            longueur = longueur shl 1
        }
    }

    // ------------------------------------------------------------ spectrogram

    /**
     * Power per time step and per bin.
     *
     * Half a symbol in time, half a tone spacing in frequency: the usual
     * trade-off. Finer costs memory and time for nothing; coarser misses
     * off-centre signals — and on a satellite **nothing is ever centred**,
     * Doppler keeps moving the carrier during a pass.
     *
     * The half-spacing comes from zero-padding the transform to twice its
     * length. That interpolates, it does not add resolution, but it is enough
     * to find a tone sitting between two bins.
     */
    class Spectrogramme(
        val mode: Mode,
        /** Powers, indexed by time step then half-bin. */
        val puissances: Array<FloatArray>,
        /** Time step, samples. */
        val pasTemps: Int,
        /** Frequency of half-bin 0, Hz. */
        val basseHz: Double
    ) {
        val nbPas: Int get() = puissances.size
        val nbRaies: Int get() = if (puissances.isEmpty()) 0 else puissances[0].size
        /** Two half-bins between adjacent tones. */
        val raieParTon: Int get() = 2
        fun puissance(pas: Int, raie: Int): Float {
            if (pas < 0 || pas >= nbPas) return 0f
            if (raie < 0 || raie >= nbRaies) return 0f
            return puissances[pas][raie]
        }
    }

    /**
     * Spectrogram of an audio block already at the internal rate.
     *
     * [basseHz] and [hauteHz] bound the searched band, which cuts search time
     * accordingly.
     */
    fun spectrogramme(
        audio: FloatArray,
        mode: Mode,
        basseHz: Double = 200.0,
        hauteHz: Double = 3000.0
    ): Spectrogramme {
        val n = mode.parSymbole
        val nfft = n * 2                       // zero-padded: half-bins
        val pasTemps = n / 2                   // half a symbol
        val binHz = mode.cadenceHz / nfft      // = spacing / 2
        val raieBasse = (basseHz / binHz).toInt().coerceAtLeast(0)
        val raieHaute = (hauteHz / binHz).toInt().coerceAtMost(nfft / 2 - 1)
        val nbRaies = (raieHaute - raieBasse + 1).coerceAtLeast(1)

        val nbPas = if (audio.size < n) 0 else (audio.size - n) / pasTemps + 1
        val sortie = Array(nbPas) { FloatArray(nbRaies) }

        val re = FloatArray(nfft)
        val im = FloatArray(nfft)
        for (p in 0 until nbPas) {
            val debut = p * pasTemps
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            // Rectangular window, on purpose. Over exactly one symbol adjacent
            // tones are orthogonal; a tapered window would destroy that to
            // fight leakage that does not exist here.
            for (k in 0 until n) re[k] = audio[debut + k]
            fft(re, im)
            val ligne = sortie[p]
            for (r in 0 until nbRaies) {
                val b = raieBasse + r
                ligne[r] = re[b] * re[b] + im[b] * im[b]
            }
        }
        return Spectrogramme(mode, sortie, pasTemps, raieBasse * binHz)
    }

    // ------------------------------------------------------------------ sync

    /** A detected signal: start, bin, and how well the sync symbols match. */
    data class Candidat(
        /** Offset in time steps from the start of the block. */
        val pas: Int,
        /** Bin of tone 0, in half-bins. */
        val raie: Int,
        val score: Float
    ) {
        fun frequenceHz(spec: Spectrogramme): Double =
            spec.basseHz + raie * (spec.mode.ecartHz / 2.0)
        fun instantS(spec: Spectrogramme): Double =
            pas * spec.pasTemps / spec.mode.cadenceHz
    }

    /**
     * Finds signals by their Costas arrays.
     *
     * **The score is soft, not a count.** For each sync symbol we add the
     * expected tone's power minus the mean of the other tones. A count throws
     * away by how much the right tone wins — exactly what tells a weak signal
     * from a coincidence. The sum is divided by the mean power of the whole
     * spectrogram (see below).
     *
     * Returns candidates best first, at most [maximum].
     */
    fun candidats(
        spec: Spectrogramme,
        maximum: Int = 32,
        scoreMinimal: Float = 1.5f
    ): List<Candidat> {
        val mode = spec.mode
        if (mode.synchro.isEmpty()) return emptyList()
        val pasParSymbole = mode.parSymbole / spec.pasTemps
        val raieDernierTon = (mode.tons - 1) * spec.raieParTon
        val pasMaximum = spec.nbPas - mode.symboles * pasParSymbole
        if (pasMaximum < 0) return emptyList()

        // Noise floor, measured once over the whole spectrogram.
        //
        // **This is the reference, not the candidate's own power.** Dividing by
        // its own power looks neat, but a misaligned candidate catches almost
        // nothing, divides by almost nothing and comes out with a huge score:
        // on the bench, sixteen slots went to ghosts of one signal.
        var reference = 0.0
        var comptes = 0L
        for (ligne in spec.puissances) for (v in ligne) { reference += v; comptes++ }
        reference = if (comptes > 0) reference / comptes else 0.0
        if (reference <= 0.0) return emptyList()

        val trouves = ArrayList<Candidat>()
        for (pas in 0..pasMaximum) {
            for (raie in 0 until spec.nbRaies - raieDernierTon) {
                var somme = 0f
                for ((position, tonAttendu) in mode.synchro) {
                    val t = pas + position * pasParSymbole
                    var attendu = 0f
                    var autres = 0f
                    for (ton in 0 until mode.tons) {
                        val p = spec.puissance(t, raie + ton * spec.raieParTon)
                        if (ton == tonAttendu) attendu = p else autres += p
                    }
                    somme += attendu - autres / (mode.tons - 1)
                }
                val score = (somme / mode.synchro.size / reference).toFloat()
                if (score >= scoreMinimal) trouves.add(Candidat(pas, raie, score))
            }
        }
        trouves.sortByDescending { it.score }

        // Keep one candidate per frequency, **whatever the time offset**.
        //
        // Rejecting only near neighbours in time and frequency is not enough:
        // a Costas array shifted by several symbols still partly matches, and
        // the bench saw sixteen well-separated ghosts of one signal. Two
        // stations on the same frequency in the same slot interfere anyway, so
        // keeping the strongest loses nothing.
        //
        // The tolerance is the **signal width**: a candidate one tone off
        // still reads the real signal's tones. Overlapping candidates are
        // either the same signal or two this demodulator cannot separate.
        // About 44 Hz for FT8, 62.5 Hz for FT4.
        val gardes = ArrayList<Candidat>(maximum)
        for (c in trouves) {
            if (gardes.none { kotlin.math.abs(it.raie - c.raie) <= raieDernierTon }) {
                gardes.add(c)
                if (gardes.size >= maximum) break
            }
        }
        return gardes
    }

    /** Reads a candidate's symbols: the strongest tone at each position. */
    fun tons(spec: Spectrogramme, c: Candidat): IntArray {
        val mode = spec.mode
        val pasParSymbole = mode.parSymbole / spec.pasTemps
        return IntArray(mode.symboles) { s ->
            val t = c.pas + s * pasParSymbole
            var meilleur = 0
            var max = -1f
            for (ton in 0 until mode.tons) {
                val p = spec.puissance(t, c.raie + ton * spec.raieParTon)
                if (p > max) { max = p; meilleur = ton }
            }
            meilleur
        }
    }

    /**
     * Per-bit log-likelihood ratios, the input of [Ldpc.decode].
     *
     * Positive for a likely zero, negative for a one, larger when the tone
     * wins clearly. The sign convention must match [Ldpc].
     */
    fun vraisemblances(spec: Spectrogramme, c: Candidat): FloatArray {
        val mode = spec.mode
        val bitsParSymbole = when (mode.tons) { 8 -> 3; 4 -> 2; else -> 1 }
        val pasParSymbole = mode.parSymbole / spec.pasTemps
        val positionsDonnees = (0 until mode.symboles)
            .filter { s -> mode.synchro.none { it.first == s } }
        val sortie = FloatArray(positionsDonnees.size * bitsParSymbole)
        var indice = 0
        for (s in positionsDonnees) {
            val t = c.pas + s * pasParSymbole
            // Powers converted to amplitudes: a Gaussian log-likelihood is in
            // amplitude, not power.
            val amp = FloatArray(mode.tons) {
                sqrt(spec.puissance(t, c.raie + it * spec.raieParTon))
            }
            for (b in 0 until bitsParSymbole) {
                var maxZero = 0f
                var maxUn = 0f
                for (ton in 0 until mode.tons) {
                    val valeur = grayInverse(ton, mode.tons)
                    val bit = (valeur shr (bitsParSymbole - 1 - b)) and 1
                    if (bit == 0) maxZero = maxOf(maxZero, amp[ton])
                    else maxUn = maxOf(maxUn, amp[ton])
                }
                sortie[indice++] = ln((maxZero + 1e-9f) / (maxUn + 1e-9f))
            }
        }
        return sortie
    }

    /** Inverse Gray code, for 4 or 8 tones. */
    private fun grayInverse(ton: Int, tons: Int): Int {
        val table = if (tons == 8) intArrayOf(0, 1, 3, 2, 5, 6, 4, 7)
        else intArrayOf(0, 1, 3, 2)
        return table.indexOf(ton).coerceAtLeast(0)
    }

    // -------------------------------------------------------------- synthesis

    /**
     * Synthesizes the signal for a symbol sequence.
     *
     * First for the bench (without a known signal nothing can be proven about
     * the demodulator), but it is also what transmit would use as is.
     *
     * Continuous phase, Gaussian-smoothed frequency trajectory as in WSJT-X:
     * without smoothing, each tone change clicks far beyond the 50 Hz of the
     * signal and disturbs neighbours.
     */
    fun synthetise(
        tons: IntArray,
        mode: Mode,
        frequenceBasseHz: Double,
        decalageS: Double = 0.0,
        dureeTotaleS: Double = mode.dureeS + 2.0,
        amplitude: Float = 0.5f,
        lissageBT: Double = mode.lissageBT
    ): FloatArray {
        val cadence = mode.cadenceHz
        val total = (dureeTotaleS * cadence).toInt()
        val sortie = FloatArray(total)
        val debut = (decalageS * cadence).roundToInt()

        // Frequency trajectory, one point per sample.
        val nSignal = tons.size * mode.parSymbole
        val freq = DoubleArray(nSignal)
        for (i in 0 until nSignal) {
            val s = i / mode.parSymbole
            freq[i] = frequenceBasseHz + tons[s] * mode.ecartHz
        }
        // Gaussian smoothing of the trajectory.
        if (lissageBT > 0.0) {
            val sigma = mode.parSymbole / (2.0 * PI * lissageBT) * sqrt(ln(2.0))
            val demi = (3 * sigma).toInt().coerceAtLeast(1)
            val noyau = DoubleArray(2 * demi + 1)
            var somme = 0.0
            for (k in noyau.indices) {
                val x = (k - demi).toDouble()
                noyau[k] = exp(-x * x / (2 * sigma * sigma)); somme += noyau[k]
            }
            for (k in noyau.indices) noyau[k] /= somme
            val lisse = DoubleArray(nSignal)
            for (i in 0 until nSignal) {
                var acc = 0.0
                for (k in noyau.indices) {
                    acc += noyau[k] * freq[(i + k - demi).coerceIn(0, nSignal - 1)]
                }
                lisse[i] = acc
            }
            System.arraycopy(lisse, 0, freq, 0, nSignal)
        }

        var phase = 0.0
        for (i in 0 until nSignal) {
            val n = debut + i
            if (n in 0 until total) sortie[n] = (amplitude * sin(phase)).toFloat()
            phase += 2.0 * PI * freq[i] / cadence
            if (phase > 2.0 * PI) phase -= 2.0 * PI
        }
        return sortie
    }

    /**
     * Adds Gaussian noise at the requested SNR, referred to 2500 Hz like an FT8
     * report ("−21 dB"), so the bench speaks the same unit.
     */
    fun avecBruit(
        signal: FloatArray,
        mode: Mode,
        rapportDb: Double,
        graine: Long = 1
    ): FloatArray {
        var puissanceSignal = 0.0
        var comptes = 0
        for (v in signal) if (v != 0f) { puissanceSignal += v * v.toDouble(); comptes++ }
        if (comptes == 0) return signal.copyOf()
        puissanceSignal /= comptes
        // Noise fills the whole band while the SNR is given in 2500 Hz, hence
        // the scaling by the actual analysis bandwidth.

        val largeurAnalyse = mode.cadenceHz / 2.0
        val puissanceBruit = puissanceSignal / Math.pow(10.0, rapportDb / 10.0) *
            (largeurAnalyse / 2500.0)
        val ecart = sqrt(puissanceBruit)
        val alea = java.util.Random(graine)
        return FloatArray(signal.size) { i ->
            (signal[i] + ecart * alea.nextGaussian()).toFloat()
        }
    }
}
