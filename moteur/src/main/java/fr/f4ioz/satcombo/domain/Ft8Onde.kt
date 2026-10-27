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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The transmit side of FT8 and FT4: a message's 77 bits to its channel
 * tones, then to the audio the rig sends.
 *
 * **Provenance.** As for the rest of the chain: the QEX July–August 2020
 * protocol description (public domain), never the WSJT-X source.
 *
 * - **Tones.** FT8: CRC-14 over the 77 bits, LDPC (174, 91), Gray code,
 *   three 7×7 Costas arrays: 79 tones of 8. FT4: the 77 bits scrambled
 *   *first*, then the same CRC and LDPC, Gray on 4 tones, four 4×4 Costas
 *   arrays and a ramp symbol at each end: 105 tones of 4.
 * - **Waveform.** Continuous-phase GFSK: each tone change shaped by the
 *   Gaussian-filtered rectangular pulse of the article,
 *   p(t) = ½ [erf(k·BT·(t + ½)) − erf(k·BT·(t − ½))], k = π √(2 / ln 2),
 *   t in symbols — BT = 2 for FT8, BT = 1 for FT4. Without it each change
 *   clicks far beyond the signal's 50 Hz (83 Hz in FT4).
 * - **Ramps.** The amplitude rises and falls with a raised cosine: over
 *   the first and last eighth of a symbol in FT8, over the first and last
 *   (ramp) symbols in FT4. A key-down at full level splatters too.
 *
 * Synthesized directly at the sound card's rate (48 000 Hz usually, 12 000
 * Hz for the tests): one pass, no resampling to blur the tones.
 */
object Ft8Onde {

    /** The 77-bit message ([Ft8Pliage.plie]) to the channel tones of [ft4] or FT8. */
    fun tons(message: BooleanArray, ft4: Boolean): IntArray {
        require(message.size >= Ft8.BITS_MESSAGE) { "il faut ${Ft8.BITS_MESSAGE} bits" }
        return if (ft4) Ft4.bitsVersSymboles(Ldpc.encode(Ft4.avecControle(message)))
        else Ft8.bitsVersSymboles(Ldpc.encode(Ft8.avecControle(message)))
    }

    /** A text to its tones, or null when it cannot be packed. */
    fun tons(texte: String, ft4: Boolean): IntArray? = Ft8Pliage.plie(texte)?.let { tons(it, ft4) }

    /** Duration of one transmission, seconds (12.64 s FT8, 5.04 s FT4). */
    fun dureeS(ft4: Boolean): Double = if (ft4) Ft4.SYMBOLES * Ft4.DUREE_SYMBOLE_S else Ft8.DUREE_S

    /**
     * The audio of [tons] ([Ft8Signal.Mode] [mode]), lowest tone at
     * [frequenceHz], at [cadenceHz], peak [amplitude] (full scale 1).
     * Exactly the transmission: nothing before or after.
     */
    fun onde(tons: IntArray, mode: Ft8Signal.Mode, frequenceHz: Double, cadenceHz: Double, amplitude: Double = 0.5): FloatArray {
        require(tons.size == mode.symboles) { "il faut ${mode.symboles} tons" }
        require(cadenceHz > 4 * (frequenceHz + mode.tons * mode.ecartHz)) { "cadence trop basse pour cette fréquence" }
        val parSymbole = cadenceHz * mode.dureeSymbole
        val n = (tons.size * parSymbole).roundToInt()
        val k = PI * sqrt(2.0 / ln(2.0)) * mode.lissageBT
        val sortie = FloatArray(n)
        val ft4 = mode.tons == 4
        val rampe = if (ft4) parSymbole else parSymbole / 8.0
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / parSymbole                         // time in symbols
            val s0 = t.toInt()
            // The pulses that reach t: three symbols either side, the edge tones held beyond the ends.
            var d = 0.0
            for (j in s0 - 3..s0 + 3) {
                val ton = tons[j.coerceIn(0, tons.size - 1)]
                if (ton == 0) continue
                d += ton * impulsion(t - j - 0.5, k)
            }
            val f = frequenceHz + d * mode.ecartHz
            var a = amplitude
            if (i < rampe) a *= 0.5 * (1 - cos(PI * i / rampe))
            else if (i >= n - rampe) a *= 0.5 * (1 - cos(PI * (n - 1 - i) / rampe))
            sortie[i] = (a * sin(phase)).toFloat()
            phase += 2 * PI * f / cadenceHz
            if (phase > 2 * PI) phase -= 2 * PI
        }
        return sortie
    }

    /** The shaped pulse at [t] symbols from a symbol's centre; [k] already holds BT. */
    fun impulsion(t: Double, k: Double): Double = 0.5 * (erf(k * (t + 0.5)) - erf(k * (t - 0.5)))

    /** erf, Abramowitz & Stegun 7.1.26 (error below 1.5e-7): ample for a frequency trajectory. */
    fun erf(x: Double): Double {
        val s = if (x < 0) -1.0 else 1.0
        val a = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * a)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-a * a)
        return s * y
    }

    /** 16-bit samples of [onde] for a sound card. */
    fun pcm(onde: FloatArray): ShortArray = ShortArray(onde.size) { (onde[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
}
