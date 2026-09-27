/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reception conditions applied to the test pattern, to try the whole chain
 * (cable, sound card, levels, decoder) as a real pass would, without waiting
 * for the ISS.
 *
 * Modelled on an FM receiver: the limiter keeps the tone level steady, so a
 * weaker signal means **more hiss**, not a quieter tone. Everything is a
 * signal-to-noise ratio that moves with time.
 *
 * @param snrDb SNR in the receiver's audio band (300-3000 Hz); null for none.
 * @param fading slow, irregular fades that lower the SNR.
 * @param dropouts short gaps (30-120 ms) where only hiss remains, as when the
 *   antenna leaves the beam for a moment.
 * @param passProfile SNR follows the elevation: 6 dB lower at AOS and LOS
 *   than at the middle of the transmission.
 */
data class SstvConditions(
    val snrDb: Double? = null,
    val fading: Fading = Fading.NONE,
    val dropouts: Boolean = false,
    val passProfile: Boolean = false
) {
    enum class Fading(val depthDb: Double) { NONE(0.0), LIGHT(6.0), STRONG(15.0) }

    /** True when anything is applied. */
    val active: Boolean get() = snrDb != null || fading != Fading.NONE || dropouts || passProfile

    companion object {
        val CLEAN = SstvConditions()
        /** A usual ISS pass: moderate hiss, light fading, a few dropouts. */
        val ISS_TYPICAL = SstvConditions(15.0, Fading.LIGHT, dropouts = true, passProfile = true)
        /** SNR loss at AOS and LOS with [passProfile], dB. */
        const val PROFILE_DB = 6.0
        /** SNR assumed when only fading, dropouts or the profile are chosen. */
        const val DEFAULT_SNR_DB = 25.0
    }
}

/**
 * Applies [c] to a stream of [total] samples at [rate], chunk by chunk, in
 * place. Same settings and [seed], same result: two trials can be compared.
 */
class ReceptionSim(
    private val c: SstvConditions,
    private val rate: Int,
    private val total: Int,
    seed: Long = 20_260_927L
) {
    private val rnd = java.util.Random(seed)
    private var pos = 0L

    // Receiver audio band: 2nd-order high-pass 300 Hz, two low-pass 3000 Hz.
    private val hp = Biquad.highPass(300.0, rate)
    private val lp1 = Biquad.lowPass(3000.0, rate)
    private val lp2 = Biquad.lowPass(3000.0, rate)
    private val noiseScale: Double

    // Dropout schedule.
    private var nextDrop = 0L
    private var dropEnd = -1L

    init {
        // Filtered noise RMS, measured once on a throwaway generator so the
        // real one starts at the same point whatever the rate.
        val probe = java.util.Random(seed xor 0x5A5A)
        val h = Biquad.highPass(300.0, rate); val l1 = Biquad.lowPass(3000.0, rate); val l2 = Biquad.lowPass(3000.0, rate)
        var s2 = 0.0
        val n = rate
        repeat(n) { val v = l2.run(l1.run(h.run(probe.nextGaussian()))); s2 += v * v }
        noiseScale = 1.0 / sqrt(s2 / n)
        nextDrop = scheduleNext(0L)
    }

    private fun scheduleNext(from: Long): Long = from + ((4.0 + 8.0 * rnd.nextDouble()) * rate).toLong()

    /** SNR at sample [p], dB, before dropouts. */
    fun snrAt(p: Long): Double {
        var snr = c.snrDb ?: SstvConditions.DEFAULT_SNR_DB
        val t = p.toDouble() / rate
        if (c.passProfile && total > 0) {
            snr -= SstvConditions.PROFILE_DB * (1.0 - sin(PI * (p.toDouble() / total).coerceIn(0.0, 1.0)))
        }
        if (c.fading != SstvConditions.Fading.NONE) {
            // Two incommensurate periods: no audible cycle; squared, so fades
            // are deep and short, as behind trees or a turning antenna.
            val f = 0.5 + 0.3 * sin(2 * PI * t / 7.1) + 0.2 * sin(2 * PI * t / 2.7 + 1.3)
            snr -= c.fading.depthDb * f.coerceIn(0.0, 1.0).pow(2)
        }
        return snr
    }

    /** Degrades [n] samples of [buf] in place. */
    fun apply(buf: ShortArray, n: Int) {
        if (!c.active) { pos += n; return }
        var snrLin = 0.0
        for (i in 0 until n) {
            val p = pos + i
            // The SNR moves slowly: recomputed every millisecond.
            if (i == 0 || p % (rate / 1000) == 0L) snrLin = 10.0.pow(-snrAt(p) / 20.0)
            if (c.dropouts) {
                if (p >= nextDrop && dropEnd < p) {
                    dropEnd = p + ((0.03 + 0.09 * rnd.nextDouble()) * rate).toLong()
                    nextDrop = scheduleNext(dropEnd)
                }
            }
            val drop = p < dropEnd
            val noise = lp2.run(lp1.run(hp.run(rnd.nextGaussian()))) * noiseScale
            val sig = buf[i] * HEADROOM
            // Tone RMS of the encoder's sine; hiss scaled to it.
            val out = (if (drop) 0.0 else sig) + noise * SIGNAL_RMS * (if (drop) 1.0 else snrLin)
            buf[i] = out.coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        pos += n
    }

    companion object {
        /** Signal scale when degrading: room for the hiss before clipping. */
        const val HEADROOM = 0.55
        /** RMS of the encoder's tone after [HEADROOM] (26000 peak sine). */
        val SIGNAL_RMS = 26000.0 * HEADROOM / sqrt(2.0)
    }
}

/** Second-order section (RBJ cookbook), Butterworth Q. */
internal class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun run(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        private const val Q = 0.7071067811865476

        fun lowPass(f: Double, rate: Int): Biquad {
            val w = 2 * PI * f / rate; val al = sin(w) / (2 * Q); val cw = kotlin.math.cos(w)
            val a0 = 1 + al
            return Biquad((1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0, -2 * cw / a0, (1 - al) / a0)
        }

        fun highPass(f: Double, rate: Int): Biquad {
            val w = 2 * PI * f / rate; val al = sin(w) / (2 * Q); val cw = kotlin.math.cos(w)
            val a0 = 1 + al
            return Biquad((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - al) / a0)
        }
    }
}
