/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import fr.f4ioz.satcombo.sdr.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The receiver's front end, from the SDR's samples to one complex value per
 * symbol:
 *
 * - the DC removed, the carrier brought near zero by an oscillator ([ncoHz]),
 *   set at the start from the spectrum (the band of a raised-cosine signal
 *   searched over the whole capture, [acquiers]);
 * - the matched filter (RRC) evaluated at any instant between samples (a
 *   bank of [PHASES] sub-sample phases), two outputs a symbol: the symbol
 *   itself and the instant halfway before it;
 * - the symbol clock recovered from those (Gardner's detector, a
 *   proportional-integral loop on the instant and the period);
 * - the level held at one (a slow AGC on the symbols).
 *
 * [fs] samples/s in, [rs] symbols/s nominal; the period follows ±1 %.
 */
class FrontalS2(val fs: Double, val rs: Double, alpha: Double = 0.35, val plageHz: Double = fs / 2) {
    val spsNominal = fs / rs

    /** The oscillator: the frequency removed from the input (Hz). */
    @Volatile var ncoHz = 0.0; private set
    private var ncoR = 1.0; private var ncoI = 0.0
    private var pasR = 1.0; private var pasI = 0.0

    /** Bumped each time the oscillator jumps (an acquisition): the symbols before are of another carrier. */
    @Volatile var epoque = 0; private set
    /** Symbols given out so far. */
    var symboles = 0L; private set
    /** The symbol period found (samples). */
    val periode: Double get() = per

    var alpha = alpha; private set
    private var banc: Array<FloatArray> = Array(0) { FloatArray(0) }
    private var taps = 0

    // The input, I and Q apart; [t] is in samples from xr[0].
    private var xr = FloatArray(0)
    private var xi = FloatArray(0)
    private var n = 0
    private var t = 0.0
    private var per = spsNominal
    private var demi = true
    private var dcR = 0f; private var dcI = 0f

    private var precR = 0f; private var precI = 0f
    private var milR = 0f; private var milI = 0f
    private var puissance = -1f

    // Gardner's loop.
    private var kp = 0.0
    private var ki = 0.0
    var largeurBoucle = 0.0015
        set(v) { field = v; gains() }

    init { regleAlpha(alpha); gains() }

    private fun gains() {
        val zeta = 0.707
        val th = largeurBoucle / (zeta + 0.25 / zeta)
        val d = 1 + 2 * zeta * th + th * th
        // The detector's slope, measured: about 1.9 per symbol of error at unit level.
        val kd = 1.9
        kp = 4 * zeta * th / d / kd
        ki = 4 * th * th / d / kd
    }

    /** Another roll-off for the matched filter (the BBHEADER's), from the next sample on. */
    fun regleAlpha(a: Double) {
        if (banc.isNotEmpty() && abs(a - alpha) < 1e-6) return
        alpha = a
        val portee = PORTEE
        taps = 2 * kotlin.math.ceil(portee * spsNominal).toInt()
        banc = Array(PHASES + 1) { p ->
            val mu = p.toDouble() / PHASES
            val h = FloatArray(taps)
            var s = 0.0
            for (i in 0 until taps) {
                // Tap i is sample floor(t) − taps/2 + 1 + i; its distance to t in symbols.
                val dt = (mu + taps / 2 - 1 - i) / spsNominal
                val w = if (abs(dt) >= portee) 0.0 else 0.5 + 0.5 * cos(PI * dt / portee)
                val v = FiltreRrc.rrc(dt, a) * w
                h[i] = v.toFloat(); s += v
            }
            for (i in 0 until taps) h[i] = (h[i] / s).toFloat()
            h
        }
    }

    // ------------------------------------------------------------ the carrier's coarse search

    private var acqSpectre: DoubleArray? = null
    private var acqBlocs = 0
    private val acqRe = DoubleArray(ACQ_N)
    private val acqIm = DoubleArray(ACQ_N)
    private var acqPos = 0
    /** The last acquisition's result (Hz), and how far its peak stood out. */
    @Volatile var acqHz = 0.0; private set
    @Volatile var acqContraste = 0.0; private set
    val enAcquisition: Boolean get() = acqSpectre != null

    /** Search the carrier from the spectrum over the next blocks; the oscillator is set at the end. */
    fun acquiers() { acqSpectre = DoubleArray(ACQ_N); acqBlocs = 0; acqPos = 0 }

    /** The oscillator moved by [hz] (the fine loops' share handed over): no jump, the phase continues. */
    fun decaleNco(hz: Double) { regleNco(ncoHz + hz) }

    fun regleNco(hz: Double) {
        ncoHz = hz
        val w = -2 * PI * hz / fs
        pasR = cos(w); pasI = sin(w)
    }

    private fun accumuleAcq(re: Float, im: Float) {
        val s = acqSpectre ?: return
        acqRe[acqPos] = re.toDouble(); acqIm[acqPos] = im.toDouble()
        if (++acqPos < ACQ_N) return
        acqPos = 0
        for (k in 0 until ACQ_N) { val w = FENETRE[k]; acqRe[k] *= w; acqIm[k] *= w }
        Fft.transform(acqRe, acqIm)
        for (k in 0 until ACQ_N) s[k] += acqRe[k] * acqRe[k] + acqIm[k] * acqIm[k]
        if (++acqBlocs < ACQ_BLOCS) return
        acqSpectre = null
        val (hz, c) = centre(s, fs, rs, alpha, plageHz)
        acqHz = hz; acqContraste = c
        regleNco(hz)
        ncoR = 1.0; ncoI = 0.0
        epoque++
        per = spsNominal
    }

    // ------------------------------------------------------------ samples in, symbols out

    /**
     * [count] complex samples from [iq] (interleaved, any scale); each symbol
     * goes to [sortie] (I, Q).
     */
    fun traite(iq: FloatArray, count: Int, sortie: (Float, Float) -> Unit) {
        if (xr.size < n + count + taps) {
            xr = xr.copyOf(n + count + taps + 4096); xi = xi.copyOf(xr.size)
        }
        var cr = ncoR; var ci = ncoI
        var pr = pasR; var pi = pasI
        for (k in 0 until count) {
            var a = iq[2 * k]; var b = iq[2 * k + 1]
            dcR += 1e-4f * (a - dcR); dcI += 1e-4f * (b - dcI)
            a -= dcR; b -= dcI
            if (acqSpectre != null) {
                accumuleAcq(a, b)
                // The oscillator just set: what came before is of another carrier, dropped.
                if (acqSpectre == null) { pr = pasR; pi = pasI; cr = 1.0; ci = 0.0; n = 0; t = 0.0; demi = true }
            }
            xr[n] = (a * cr - b * ci).toFloat()
            xi[n] = (a * ci + b * cr).toFloat()
            n++
            val nr = cr * pr - ci * pi
            ci = cr * pi + ci * pr; cr = nr
        }
        // Keep the oscillator's phasor at unit length.
        val g = 1.0 / sqrt(cr * cr + ci * ci)
        ncoR = cr * g; ncoI = ci * g

        val demiTaps = taps / 2
        while (true) {
            val f = floor(t)
            val i0 = f.toInt() - demiTaps + 1
            if (i0 < 0) { t = (demiTaps - 1).toDouble(); continue }
            if (i0 + taps > n) break
            var p = ((t - f) * PHASES + 0.5).toInt()
            var base = i0
            if (p >= PHASES) { p -= PHASES; base++ }
            if (base + taps > n) break
            val h = banc[p]
            var yr = 0f; var yi = 0f
            for (i in 0 until taps) { val c = h[i]; yr += c * xr[base + i]; yi += c * xi[base + i] }
            if (puissance < 0) puissance = (yr * yr + yi * yi).coerceAtLeast(1e-20f)
            val gn = 1f / sqrt(puissance)
            yr *= gn; yi *= gn
            if (demi) {
                milR = yr; milI = yi
                demi = false
                t += per / 2
            } else {
                demi = true
                // Gardner: positive when sampling late.
                val e = (milR * (yr - precR) + milI * (yi - precI)).toDouble().coerceIn(-1.0, 1.0)
                precR = yr; precI = yi
                puissance += 0.002f * ((yr * yr + yi * yi) * puissance - puissance)
                per -= ki * e * spsNominal
                per = per.coerceIn(spsNominal * 0.99, spsNominal * 1.01)
                t += per / 2 - kp * e * spsNominal
                symboles++
                sortie(yr, yi)
            }
        }
        // Drop what no output needs any more.
        val garde = (floor(t).toInt() - demiTaps - 2).coerceAtLeast(0)
        if (garde > 0) {
            System.arraycopy(xr, garde, xr, 0, n - garde)
            System.arraycopy(xi, garde, xi, 0, n - garde)
            n -= garde
            t -= garde
        }
    }

    companion object {
        const val PHASES = 128
        /** The matched filter's half-span, in symbols. */
        const val PORTEE = 7.0
        const val ACQ_N = 1024
        const val ACQ_BLOCS = 96
        private val FENETRE = DoubleArray(ACQ_N) { 0.5 - 0.5 * cos(2 * PI * it / ACQ_N) }

        /**
         * Where the raised-cosine band of [rs] lies in the power spectrum [s]
         * (FFT order, [fs] wide): the template slid over ±[plageHz], the best
         * place refined between bins. Returns its centre (Hz) and how far the
         * best stands out of the others (in their standard deviations).
         */
        fun centre(s: DoubleArray, fs: Double, rs: Double, alpha: Double, plageHz: Double): Pair<Double, Double> {
            val n = s.size
            val df = fs / n
            // The power, centred (bin n/2 = 0 Hz), relative to its median.
            val c = DoubleArray(n) { s[(it + n / 2) % n] }
            val med = c.sorted()[n / 2].coerceAtLeast(1e-30)
            for (k in 0 until n) c[k] = c[k] / med
            // The template: |H(f)|² of the raised cosine, zero mean over its support ± a margin.
            val demiLargeur = ((1 + alpha) * rs / 2 / df).toInt() + 2
            val marge = minOf(demiLargeur / 2 + 2, n / 2 - demiLargeur - 4).coerceAtLeast(1)
            val m = demiLargeur + marge
            val tpl = DoubleArray(2 * m + 1) { k ->
                val f = abs((k - m) * df) / rs
                when {
                    f <= (1 - alpha) / 2 -> 1.0
                    f <= (1 + alpha) / 2 -> 0.5 * (1 + cos(PI / alpha * (f - (1 - alpha) / 2)))
                    else -> 0.0
                }
            }
            val moy = tpl.average()
            for (k in tpl.indices) tpl[k] -= moy
            val lim = minOf((plageHz / df).toInt(), n / 2 - m - 1).coerceAtLeast(0)
            val corr = DoubleArray(2 * lim + 1)
            for (d in -lim..lim) {
                var v = 0.0
                val c0 = n / 2 + d - m
                for (k in tpl.indices) v += tpl[k] * c[c0 + k]
                corr[d + lim] = v
            }
            var best = 0
            for (k in corr.indices) if (corr[k] > corr[best]) best = k
            val y0 = corr[(best - 1).coerceAtLeast(0)]; val y1 = corr[best]; val y2 = corr[(best + 1).coerceAtMost(corr.size - 1)]
            val den = y0 - 2 * y1 + y2
            val fin = if (best in 1 until corr.size - 1 && den < 0) 0.5 * (y0 - y2) / den else 0.0
            // Its contrast: away from the peak (beyond the band's width), how many deviations above.
            val autres = corr.indices.filter { abs(it - best) > demiLargeur }.map { corr[it] }
            val mo = if (autres.isEmpty()) 0.0 else autres.average()
            val sd = if (autres.size < 2) 1.0 else sqrt(autres.sumOf { (it - mo) * (it - mo) } / autres.size).coerceAtLeast(1e-30)
            return ((best - lim + fin) * df) to (y1 - mo) / sd
        }
    }
}
