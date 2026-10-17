/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How an audio signal goes on the air. */
enum class ModulationAudio(val cle: String, val libelle: String) {
    NBFM("nbfm", "FM étroite"),
    USB("usb", "BLU haute (USB)"),
    LSB("lsb", "BLU basse (LSB)");

    val blu: Boolean get() = this != NBFM

    companion object {
        fun de(cle: String?): ModulationAudio? = entries.firstOrNull { it.cle == cle?.trim()?.lowercase() }
    }
}

/**
 * Audio (16-bit, [debitAudio]) into complex baseband at [debitAudio] ×
 * [facteur], as the transmitters take it ([PlutoEmission],
 * [BladerfEmission]: I, Q interleaved, 16-bit MSB-aligned).
 *
 * - **NBFM**: the audio (pre-emphasised if asked, 6 dB/octave from
 *   212 Hz, unity at [REFERENCE_PREACCENTUATION_HZ]) clipped to full
 *   scale, interpolated, then a phase accumulator: full scale is
 *   [deviationHz]. Constant envelope.
 * - **USB / LSB**: filter method. A complex band-pass (300–2800 Hz)
 *   at the audio rate keeps only the positive frequencies (the analytic
 *   signal), conjugated for LSB, then interpolated. The suppressed carrier
 *   is the dial frequency.
 *
 * Interpolation: polyphase, a Blackman low-pass of [TAPS_PAR_PHASE] taps
 * per phase; images of the audio at multiples of the audio rate ≥ 70 dB down.
 *
 * [source] fills its array and gives how many samples; 0 once finished:
 * the filters are then flushed with silence and [fini] becomes true (FM:
 * the bare carrier follows, SSB: nothing).
 */
class ModulateurAudio(
    val modulation: ModulationAudio,
    val debitAudio: Int,
    val facteur: Int,
    val deviationHz: Double = 5_000.0,
    val preaccentuation: Boolean = false,
    /** Output level, fraction of full scale (constant envelope in NBFM and with one tone in SSB). */
    val niveau: Double = 0.5,
    private val source: (ShortArray) -> Int,
) {
    init {
        require(debitAudio in 8_000..192_000 && facteur in 1..512)
        require(niveau > 0.0 && niveau <= 0.9)
        require(deviationHz > 0.0 && deviationHz <= 15_000.0)
    }

    /** I/Q pairs per second. */
    val debit: Long = debitAudio.toLong() * facteur

    @Volatile var fini = false; private set

    /** Audio samples read from the source. */
    @Volatile var lus = 0L; private set

    /** Samples clipped at the output (SSB peaks). */
    @Volatile var ecretages = 0L; private set

    private val noyau: FloatArray = run {
        val n = TAPS_PAR_PHASE * facteur
        val lp = Dsp.lowPass(n - 1, 0.4 * debitAudio, debit.toDouble())
        FloatArray(n) { if (it < lp.size) lp[it] * facteur else 0f }
    }
    private val histI = FloatArray(TAPS_PAR_PHASE)
    private val histQ = FloatArray(TAPS_PAR_PHASE)

    // SSB: the complex band-pass at the audio rate.
    private val bpI: FloatArray
    private val bpQ: FloatArray
    private val bpHist: FloatArray

    init {
        if (modulation.blu) {
            val lp = Dsp.lowPass(TAPS_BLU, (BLU_HAUT_HZ - BLU_BAS_HZ) / 2.0, debitAudio.toDouble())
            val centre = (BLU_HAUT_HZ + BLU_BAS_HZ) / 2.0
            val mid = (lp.size - 1) / 2
            val dp = 2.0 * PI * centre / debitAudio
            bpI = FloatArray(lp.size) { (2.0 * lp[it] * cos(dp * (it - mid))).toFloat() }
            bpQ = FloatArray(lp.size) { (2.0 * lp[it] * sin(dp * (it - mid))).toFloat() }
            bpHist = FloatArray(lp.size)
        } else {
            bpI = FloatArray(0); bpQ = FloatArray(0); bpHist = FloatArray(0)
        }
    }
    private var bpPos = 0

    // Pre-emphasis: y = (x − a·x[n−1]) / g, unity at the reference frequency.
    private val pa = exp(-1.0 / (TAU_PREACCENTUATION_S * debitAudio))
    private val pg = run {
        val w = 2.0 * PI * REFERENCE_PREACCENTUATION_HZ / debitAudio
        sqrt((1 - pa * cos(w)) * (1 - pa * cos(w)) + (pa * sin(w)) * (pa * sin(w)))
    }
    private var precedent = 0.0

    private var phase = 0
    private val pasPhase = deviationHz / debit * 4_294_967_296.0
    private val plein = niveau * PLEIN

    private val audio = ShortArray(BLOC)
    private var vidange = 0
    private val sortieI = FloatArray(BLOC * facteur)
    private val sortieQ = FloatArray(BLOC * facteur)
    private var nSortie = 0
    private var posSortie = 0

    /** Fills [paires] I/Q pairs of [dst]. */
    fun remplis(dst: ShortArray, paires: Int = dst.size / 2) {
        var o = 0
        val fin = 2 * paires
        while (o < fin) {
            if (posSortie == nSortie) produit()
            val k = posSortie++
            val i: Int
            val q: Int
            if (modulation == ModulationAudio.NBFM) {
                phase += Math.round(sortieI[k] * pasPhase).toInt()
                val idx = phase ushr (32 - TABLE_BITS)
                i = (COS[idx] * plein).roundToInt(); q = (SIN[idx] * plein).roundToInt()
            } else {
                i = borne((sortieI[k] * plein).roundToInt()); q = borne((sortieQ[k] * plein).roundToInt())
            }
            dst[o++] = i.toShort(); dst[o++] = q.toShort()
        }
    }

    private fun borne(v: Int): Int {
        if (v > MAX || v < -MAX) { ecretages++; return v.coerceIn(-MAX, MAX) }
        return v
    }

    /** One block of audio in, [facteur] times as many baseband samples out. */
    private fun produit() {
        var n = if (vidange == 0) source(audio) else 0
        if (n <= 0) {
            // The source finished: silence through the filters, then the end.
            n = BLOC
            audio.fill(0, 0, n)
            vidange += n
            if (vidange >= 4 * (TAPS_BLU + TAPS_PAR_PHASE)) fini = true
        } else lus += n
        var o = 0
        for (s in 0 until n) {
            var x = audio[s] / 32768.0
            var y = 0.0
            if (modulation == ModulationAudio.NBFM) {
                if (preaccentuation) { val e = (x - pa * precedent) / pg; precedent = x; x = e }
                x = x.coerceIn(-1.0, 1.0)
            } else {
                // The analytic signal: the band-pass keeps the positive side only.
                bpHist[bpPos] = x.toFloat()
                var re = 0f; var im = 0f
                val m = bpHist.size
                var j = bpPos
                for (t in 0 until m) {
                    val h = bpHist[j]
                    re += bpI[t] * h; im += bpQ[t] * h
                    if (--j < 0) j = m - 1
                }
                bpPos = if (bpPos + 1 == m) 0 else bpPos + 1
                x = re.toDouble()
                y = if (modulation == ModulationAudio.LSB) -im.toDouble() else im.toDouble()
            }
            // Polyphase interpolation: [facteur] outputs for this input.
            System.arraycopy(histI, 0, histI, 1, TAPS_PAR_PHASE - 1); histI[0] = x.toFloat()
            if (modulation.blu) { System.arraycopy(histQ, 0, histQ, 1, TAPS_PAR_PHASE - 1); histQ[0] = y.toFloat() }
            for (p in 0 until facteur) {
                var a = 0f; var b = 0f
                var t = p
                for (k in 0 until TAPS_PAR_PHASE) {
                    val h = noyau[t]
                    a += h * histI[k]
                    if (modulation.blu) b += h * histQ[k]
                    t += facteur
                }
                sortieI[o] = a; sortieQ[o] = b; o++
            }
        }
        nSortie = o; posSortie = 0
    }

    companion object {
        const val TAPS_PAR_PHASE = 12
        /** The SSB band-pass at the audio rate. */
        const val TAPS_BLU = 255
        const val BLU_BAS_HZ = 300.0
        const val BLU_HAUT_HZ = 2_800.0
        const val TAU_PREACCENTUATION_S = 750e-6
        const val REFERENCE_PREACCENTUATION_HZ = 2_300.0
        const val PLEIN = 32_752.0
        const val MAX = 32_752
        private const val BLOC = 256
        private const val TABLE_BITS = 16
        private val COS = FloatArray(1 shl TABLE_BITS) { cos(2.0 * PI * it / (1 shl TABLE_BITS)).toFloat() }
        private val SIN = FloatArray(1 shl TABLE_BITS) { sin(2.0 * PI * it / (1 shl TABLE_BITS)).toFloat() }

        /** The band the signal occupies around the dial frequency (Hz, low and high edge offsets). */
        fun bandeOccupee(m: ModulationAudio, deviationHz: Double, audioHautHz: Double = 2_300.0): Pair<Double, Double> = when (m) {
            // Carson's rule.
            ModulationAudio.NBFM -> (-(deviationHz + audioHautHz)) to (deviationHz + audioHautHz)
            ModulationAudio.USB -> BLU_BAS_HZ to BLU_HAUT_HZ
            ModulationAudio.LSB -> -BLU_HAUT_HZ to -BLU_BAS_HZ
        }
    }
}
