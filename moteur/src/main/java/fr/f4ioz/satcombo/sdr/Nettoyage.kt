/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cleaning the received sound: what [RxChain] can do besides demodulating.
 * All off by default, all cheap enough for a phone:
 *
 *  - [NoiseBlanker] on the raw IQ: the clicks of a switching supply or the
 *    LNB feed, short and far above everything else, cut out before any
 *    filter smears them over a millisecond;
 *  - [AutoNotch]: a steady carrier whistling in the voice band found by its
 *    persistence and notched, up to three at once;
 *  - [NotchFilter]: the same notch, at a frequency the operator names;
 *  - [NoiseReduction]: the hiss of a weak SSB station lowered between and
 *    under the words (short-time spectrum, Wiener gain);
 *  - the audio AGC's speed ([AgcSpeed]).
 */

/** Audio AGC speed: attack, hang, recovery. [MOYENNE] is the historical one. */
enum class AgcSpeed(val attackS: Double, val hangS: Double, val recoveryDbS: Double) {
    LENTE(0.004, 1.0, 6.0),
    MOYENNE(0.002, 0.3, 12.0),
    RAPIDE(0.001, 0.08, 40.0);

    companion object {
        fun de(s: String?): AgcSpeed = values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: MOYENNE
    }
}

/** The cleaning settings of one demodulation mode. */
data class RxCleaning(
    /** Noise blanker on IQ. */
    val nb: Boolean = false,
    /** An impulse is a sample this many times above the average magnitude (3–20). */
    val nbThreshold: Float = NB_SEUIL,
    /** Blanked width around it, µs. */
    val nbWidthUs: Int = NB_LARGEUR_US,
    val autoNotch: Boolean = false,
    val notch: Boolean = false,
    /** Manual notch, audio Hz. */
    val notchHz: Int = 1_000,
    val notchWidthHz: Int = 80,
    /** Noise reduction strength: 0 off, 1 light, 2 medium, 3 strong. */
    val nr: Int = 0,
    val agc: AgcSpeed = AgcSpeed.MOYENNE,
) {
    /** Values brought back into their ranges. */
    fun borne(): RxCleaning = copy(
        nbThreshold = nbThreshold.coerceIn(3f, 20f), nbWidthUs = nbWidthUs.coerceIn(2, 500),
        notchHz = notchHz.coerceIn(50, 5_000), notchWidthHz = notchWidthHz.coerceIn(10, 500), nr = nr.coerceIn(0, 3))

    fun json(): JSONObject = JSONObject().put("nb", nb).put("nbSeuil", nbThreshold.toDouble()).put("nbLargeur", nbWidthUs)
        .put("notchAuto", autoNotch).put("notch", notch).put("notchHz", notchHz).put("notchLargeur", notchWidthHz)
        .put("nr", nr).put("cag", agc.name.lowercase())

    /** These settings with the fields of [q] (a query: "nb=1&nr=2…") applied. */
    fun avec(q: Map<String, String>): RxCleaning {
        fun b(k: String, d: Boolean) = q[k]?.let { it == "1" || it == "true" } ?: d
        return copy(
            nb = b("nb", nb), nbThreshold = q["nbSeuil"]?.replace(',', '.')?.toFloatOrNull() ?: nbThreshold,
            nbWidthUs = q["nbLargeur"]?.toIntOrNull() ?: nbWidthUs,
            autoNotch = b("notchAuto", autoNotch), notch = b("notch", notch),
            notchHz = q["notchHz"]?.toDoubleOrNull()?.roundToInt() ?: notchHz,
            notchWidthHz = q["notchLargeur"]?.toIntOrNull() ?: notchWidthHz,
            nr = q["nr"]?.toIntOrNull() ?: nr, agc = q["cag"]?.let { AgcSpeed.de(it) } ?: agc,
        ).borne()
    }

    companion object {
        const val NB_SEUIL = 8f
        const val NB_LARGEUR_US = 20
        val OFF = RxCleaning()

        fun de(o: JSONObject?): RxCleaning = if (o == null) OFF else RxCleaning(
            nb = o.optBoolean("nb"), nbThreshold = o.optDouble("nbSeuil", NB_SEUIL.toDouble()).toFloat(),
            nbWidthUs = o.optInt("nbLargeur", NB_LARGEUR_US), autoNotch = o.optBoolean("notchAuto"),
            notch = o.optBoolean("notch"), notchHz = o.optInt("notchHz", 1_000), notchWidthHz = o.optInt("notchLargeur", 80),
            nr = o.optInt("nr"), agc = AgcSpeed.de(o.optString("cag")),
        ).borne()

        /** The per-mode settings kept as one JSON text ({"USB": {…}, …}). */
        fun parMode(texte: String?): Map<RxMode, RxCleaning> {
            val o = runCatching { JSONObject(texte ?: "{}") }.getOrNull() ?: return emptyMap()
            return RxMode.values().mapNotNull { m -> o.optJSONObject(m.name)?.let { m to de(it) } }.toMap()
        }

        fun texte(m: Map<RxMode, RxCleaning>): String =
            JSONObject().apply { m.forEach { (k, v) -> put(k.name, v.json()) } }.toString()
    }
}

/** Radix-2 complex FFT on floats with its tables computed once: the cleaning runs one every few milliseconds. */
class FftF(val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "taille non puissance de deux : $n" }
        var bits = 0
        while (1 shl bits < n) bits++
        for (i in 0 until n) {
            var r = 0
            for (b in 0 until bits) if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
            rev[i] = r
        }
    }

    /** e^{-j…}, unscaled. */
    fun forward(re: FloatArray, im: FloatArray) = run(re, im, -1f)

    /** e^{+j…}, divided by [n]. */
    fun inverse(re: FloatArray, im: FloatArray) {
        run(re, im, 1f)
        val s = 1f / n
        for (i in 0 until n) { re[i] *= s; im[i] *= s }
    }

    private fun run(re: FloatArray, im: FloatArray, sign: Float) {
        for (i in 0 until n) {
            val j = rev[i]
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var t = 0
                for (k in i until i + half) {
                    val wr = cosT[t]; val wi = sign * sinT[t]
                    val m = k + half
                    val vr = re[m] * wr - im[m] * wi
                    val vi = re[m] * wi + im[m] * wr
                    val ur = re[k]; val ui = im[k]
                    re[k] = ur + vr; im[k] = ui + vi
                    re[m] = ur - vr; im[m] = ui - vi
                    t += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Smallest power of two at or above [x]. */
private fun puissance2(x: Double): Int { var n = 16; while (n < x) n *= 2; return n }

/**
 * Impulse blanker on IQ, at the receiver's full rate.
 *
 * A switching-supply or LNB-feed click lasts a microsecond or two at the
 * antenna; after the channel filter it becomes a millisecond-long thump that
 * nothing can remove. Here, before any filter, it is still a few samples far
 * above everything else: a sample whose power exceeds [threshold]² times the
 * running mean is an impulse, and a short window around it ([widthUs]) is
 * faded to zero and back. A small delay line lets the fade start before the
 * impulse.
 *
 * The running mean is fed with the samples capped at the threshold, so an
 * impulse barely moves it, while a station that keys up much stronger lifts
 * it within half a millisecond: a steady signal is never blanked for long.
 */
class NoiseBlanker(private val rate: Double) {
    var threshold: Float = RxCleaning.NB_SEUIL
    var widthUs: Int = RxCleaning.NB_LARGEUR_US

    private val ramp = max(2, (rate * 2e-6).roundToInt())
    private val pre = ramp + 2
    private val dI = FloatArray(pre)
    private val dQ = FloatArray(pre)
    private var pos = 0
    private var avg = 0f
    private var hold = 0
    private var gain = 1f
    private val alpha = (1.0 - exp(-1.0 / (rate * 0.005))).toFloat()

    private var blanked = 0L
    private var seen = 0L

    /** Share of the samples blanked since the last call. */
    fun taux(): Float {
        val r = if (seen == 0L) 0f else blanked.toFloat() / seen
        blanked = 0; seen = 0
        return r
    }

    fun reset() { dI.fill(0f); dQ.fill(0f); pos = 0; avg = 0f; hold = 0; gain = 1f }

    fun process(i: FloatArray, q: FloatArray, n: Int) {
        val k2 = threshold * threshold
        val post = max(1, (widthUs * 1e-6 * rate).roundToInt())
        val step = 1f / ramp
        var g = gain; var h = hold; var a = avg; var p0 = pos
        var cut = 0
        for (s in 0 until n) {
            val xi = i[s]; val xq = q[s]
            val p = xi * xi + xq * xq
            if (a <= 0f) a = p
            val lim = k2 * a
            if (p > lim) { h = pre + post; a += alpha * (lim - a) } else a += alpha * (p - a)
            val oi = dI[p0]; val oq = dQ[p0]
            dI[p0] = xi; dQ[p0] = xq
            p0++; if (p0 == pre) p0 = 0
            if (h > 0) { h--; g -= step; if (g < 0f) g = 0f } else if (g < 1f) { g += step; if (g > 1f) g = 1f }
            if (g < 1f) cut++
            i[s] = oi * g; q[s] = oq * g
        }
        gain = g; hold = h; avg = a; pos = p0
        blanked += cut; seen += n
    }
}

/**
 * Notch (second-order, the usual band-reject biquad), direct form I so its
 * frequency can glide while it runs.
 */
class NotchFilter {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    var hz = 0.0; private set
    var widthHz = 0.0; private set

    fun set(f0: Double, bw: Double, fs: Double) {
        hz = f0; widthHz = bw
        val w = 2.0 * PI * f0 / fs
        // sin(w)/(2Q), Q = f0/bw: bw is the width at −3 dB.
        val alpha = sin(w) * bw / (2.0 * f0)
        val a0 = 1.0 + alpha
        val c = -2.0 * cos(w)
        b0 = (1.0 / a0).toFloat(); b1 = (c / a0).toFloat(); b2 = b0
        a1 = (c / a0).toFloat(); a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }

    fun step(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun process(buf: FloatArray, n: Int) { for (k in 0 until n) buf[k] = step(buf[k]) }
}

/**
 * Automatic notch: steady carriers in the audio found and notched.
 *
 * Every few milliseconds a short spectrum of the audio (about 40 Hz a bin)
 * gives the peaks standing well above their neighbours. Each is followed
 * from one spectrum to the next and its exact frequency read from the phase
 * turn of its bin between two spectra (a fraction of a hertz on a clean
 * carrier, far finer than the bin). A peak that stays put long enough
 * ([persistS]) is a carrier: a narrow notch is faded in on it, and follows
 * it if it drifts. A voice's harmonics wander by several hertz from one
 * spectrum to the next and never qualify; a CW note keyed by hand does not
 * last [persistS] when it is set to two seconds.
 *
 * At most [MAX] notches at once; the analysis runs on the input, so a notched
 * carrier is still seen and its notch stays.
 */
class AutoNotch(private val fs: Double) {
    /** How long a carrier must hold still before it is notched, s. */
    var persistS = 0.3

    private val n = run { var k = 64; while (k * 2 <= fs / 30.0) k *= 2; k }
    private val hop = n / 2
    private val fft = FftF(n)
    private val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
    private val ring = FloatArray(n)
    private var fill = 0
    private var sinceHop = 0
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val pw = FloatArray(n / 2 + 1)
    private val prevRe = FloatArray(n / 2 + 1)
    private val prevIm = FloatArray(n / 2 + 1)
    private var frames = 0L
    private val binHz = fs / n
    private val hopS = hop / fs
    private val kLo = max(10, (150.0 / binHz).toInt())
    private val kHi = min(n / 2 - 10, (4_000.0 / binHz).toInt())

    private class Track {
        var used = false
        var f = 0.0
        var age = 0.0
        var miss = 0.0
        var dev = 0.0
        var on = false
        var mix = 0f
        val filt = NotchFilter()
    }

    private val tracks = Array(8) { Track() }

    /** Frequencies notched now, Hz. */
    fun notched(): List<Double> = tracks.filter { it.on }.map { it.f }

    fun reset() {
        fill = 0; sinceHop = 0; frames = 0; ring.fill(0f)
        for (t in tracks) { t.used = false; t.on = false; t.mix = 0f; t.filt.reset() }
    }

    fun process(buf: FloatArray, count: Int) {
        val dMix = (1.0 / (0.04 * fs)).toFloat()
        for (k in 0 until count) {
            val x = buf[k]
            ring[fill] = x
            fill++; if (fill == n) fill = 0
            sinceHop++
            if (sinceHop == hop) { sinceHop = 0; analyse() }
            var y = x
            for (t in tracks) {
                if (t.mix <= 0f && !t.on) continue
                if (t.on) { if (t.mix < 1f) t.mix = min(1f, t.mix + dMix) } else t.mix = max(0f, t.mix - dMix)
                val z = t.filt.step(y)
                y += t.mix * (z - y)
            }
            buf[k] = y
        }
    }

    private fun analyse() {
        // Oldest sample first.
        for (i in 0 until n) { re[i] = ring[(fill + i) % n] * win[i]; im[i] = 0f }
        fft.forward(re, im)
        for (k in 0..n / 2) pw[k] = re[k] * re[k] + im[k] * im[k]
        val first = frames == 0L
        frames++
        // Peaks well above their neighbourhood (12 dB), with their instantaneous frequency.
        val pk = ArrayList<DoubleArray>()
        if (!first) for (k in kLo..kHi) {
            val p = pw[k]
            if (p <= pw[k - 1] || p < pw[k + 1]) continue
            var bg = 0f
            for (j in 4..8) bg += pw[k - j] + pw[k + j]
            bg /= 10f
            if (p < 16f * bg || p <= 1e-20f) continue
            // Phase turned since the last spectrum, minus what bin k turns by itself (π k for a half-frame hop).
            val dr = re[k] * prevRe[k] + im[k] * prevIm[k]
            val di = im[k] * prevRe[k] - re[k] * prevIm[k]
            var d = atan2(di.toDouble(), dr.toDouble()) - PI * k
            d -= 2.0 * PI * Math.floor((d + PI) / (2.0 * PI))
            val f = (k + d / PI) * binHz
            pk.add(doubleArrayOf(f, (p / bg).toDouble()))
        }
        for (k in 0..n / 2) { prevRe[k] = re[k]; prevIm[k] = im[k] }
        if (first) return

        val taken = BooleanArray(pk.size)
        for (t in tracks) {
            if (!t.used) continue
            var best = -1; var bd = 1.5 * binHz
            for (i in pk.indices) {
                if (taken[i]) continue
                val d = Math.abs(pk[i][0] - t.f)
                if (d < bd) { bd = d; best = i }
            }
            if (best >= 0) {
                taken[best] = true
                val fi = pk[best][0]
                t.dev = 0.7 * t.dev + 0.3 * Math.abs(fi - t.f)
                t.f += (if (t.on) 0.2 else 0.5) * (fi - t.f)
                t.miss = 0.0
                if (t.dev > MAX_DEV_HZ && !t.on) t.age = 0.0 else t.age += hopS
            } else {
                t.miss += hopS
                if (t.miss > (if (t.on) 0.3 else 0.1)) { t.used = false; t.on = false }
            }
        }
        // New peaks into free tracks (a fading notch's track waits until it has faded).
        val order = pk.indices.filter { !taken[it] }.sortedByDescending { pk[it][1] }
        for (i in order) {
            val t = tracks.firstOrNull { !it.used && it.mix <= 0f } ?: break
            t.used = true; t.on = false; t.f = pk[i][0]; t.age = 0.0; t.miss = 0.0; t.dev = 0.0; t.filt.reset()
        }
        // The steady ones become notches, at most MAX, the oldest first.
        var on = tracks.count { it.on }
        for (t in tracks.filter { it.used && !it.on && it.age >= persistS && it.dev <= MAX_DEV_HZ }.sortedByDescending { it.age }) {
            if (on >= MAX) break
            t.on = true; on++
        }
        for (t in tracks) if (t.used && (t.on || t.mix > 0f)) {
            if (Math.abs(t.filt.hz - t.f) > 0.05 || t.filt.widthHz == 0.0) t.filt.set(t.f, WIDTH_HZ, fs)
        }
    }

    companion object {
        const val MAX = 3
        /** Notch width at −3 dB, Hz. */
        const val WIDTH_HZ = 40.0
        /** A carrier's frequency moves less than this between two spectra, Hz. */
        const val MAX_DEV_HZ = 2.5
    }
}

/**
 * Noise reduction on the demodulated audio.
 *
 * Short spectra (about 23 ms, a new one every 6 ms) of the sound; in each
 * bin the noise power is estimated, the bin's gain is the Wiener one for
 * its estimated signal-to-noise ratio, and the spectra are put back
 * together (overlap-add, square-root Hann windows on both sides).
 *
 *  - Noise estimate: the minimum of the smoothed power over about 1.5 s
 *    tells whether speech is present in the bin; where it is not, the noise
 *    estimate follows the power, where it is the estimate holds. So it
 *    tracks a hiss that changes (the AGC, fading) within a second or two and
 *    is not pulled up by the words.
 *  - Signal-to-noise ratio "decision-directed": mostly what the previous
 *    spectrum kept, a little of the new excess. That is what keeps isolated
 *    noise peaks from popping out as tinkling tones.
 *  - A gain floor per strength: the hiss is lowered, not removed, which keeps
 *    it smooth.
 *
 * Two consecutive frames share one complex FFT (one in the real part, one in
 * the imaginary part) and one inverse: half the cost. Delay N + N/4 samples,
 * 29 ms at 44.1 kHz.
 */
class NoiseReduction(private val fs: Double) {
    /** 1 light, 2 medium, 3 strong. */
    var strength = 2

    val n = puissance2(fs * 0.023)
    private val hop = n / 4
    private val half = n / 2
    private val fft = FftF(n)
    private val win = FloatArray(n) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
    /** Last N + hop input samples. */
    private val inb = FloatArray(n + hop)
    /** Overlap-add accumulator, same span. */
    private val acc = FloatArray(n + hop)
    /** Finished output waiting to go out (2 hops). */
    private val outq = FloatArray(2 * hop)
    private var fillIn = 0
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val xar = FloatArray(half + 1); private val xai = FloatArray(half + 1)
    private val xbr = FloatArray(half + 1); private val xbi = FloatArray(half + 1)
    private val ga = FloatArray(half + 1); private val gb = FloatArray(half + 1)

    private val smooth = FloatArray(half + 1)
    private val noise = FloatArray(half + 1)
    private val prevS = FloatArray(half + 1)   // G² |X|² of the previous frame
    private val minCur = FloatArray(half + 1)
    private val minSub = Array(SUB) { FloatArray(half + 1) }
    private var subFrames = 0
    private var subIdx = 0
    private val subLen = max(1, (1.5 * fs / hop / SUB).roundToInt())
    private var frames = 0

    /** Delay added to the sound, samples. */
    val delay: Int get() = n + hop

    fun reset() {
        inb.fill(0f); acc.fill(0f); outq.fill(0f); fillIn = 0
        frames = 0; subFrames = 0; subIdx = 0
        smooth.fill(0f); noise.fill(0f); prevS.fill(0f)
    }

    fun process(buf: FloatArray, count: Int) {
        val base = n - hop   // two hops are gathered at inb[base until base + 2 hop]
        for (k in 0 until count) {
            val x = buf[k]
            val y = outq[fillIn]
            inb[base + fillIn] = x
            buf[k] = y
            fillIn++
            if (fillIn == 2 * hop) { fillIn = 0; pair() }
        }
    }

    private fun pair() {
        // Frame A = inb[0, n), frame B = inb[hop, n + hop).
        for (i in 0 until n) { re[i] = inb[i] * win[i]; im[i] = inb[i + hop] * win[i] }
        fft.forward(re, im)
        for (k in 0..half) {
            val j = if (k == 0) 0 else n - k
            val zr = re[k]; val zi = im[k]; val yr = re[j]; val yi = im[j]
            xar[k] = 0.5f * (zr + yr); xai[k] = 0.5f * (zi - yi)
            xbr[k] = 0.5f * (zi + yi); xbi[k] = -0.5f * (zr - yr)
        }
        gains(xar, xai, ga)
        gains(xbr, xbi, gb)
        for (k in 0..half) {
            val ar = ga[k] * xar[k]; val ai = ga[k] * xai[k]
            val br = gb[k] * xbr[k]; val bi = gb[k] * xbi[k]
            re[k] = ar - bi; im[k] = ai + br
            if (k in 1 until half) { re[n - k] = ar + bi; im[n - k] = -ai + br }
        }
        fft.inverse(re, im)
        // Sum of the squared windows at a quarter-frame hop: 2.
        for (i in 0 until n) { acc[i] += 0.5f * re[i] * win[i]; acc[i + hop] += 0.5f * im[i] * win[i] }
        System.arraycopy(acc, 0, outq, 0, 2 * hop)
        System.arraycopy(acc, 2 * hop, acc, 0, n - hop)
        java.util.Arrays.fill(acc, n - hop, n + hop, 0f)
        System.arraycopy(inb, 2 * hop, inb, 0, n - hop)
    }

    private fun gains(xr: FloatArray, xi: FloatArray, g: FloatArray) {
        val s = strength.coerceIn(1, 3)
        val gmin = GAIN_MIN[s - 1]
        val over = OVER[s - 1]
        val xiMin = gmin * gmin
        val start = frames < 8
        frames++
        for (k in 0..half) {
            val p = xr[k] * xr[k] + xi[k] * xi[k]
            // Smoothed power and its running minimum.
            val sm = if (frames == 1) p else 0.8f * smooth[k] + 0.2f * p
            smooth[k] = sm
            if (frames == 1 || sm < minCur[k]) minCur[k] = sm
            // Noise: follows the power where the bin holds no speech.
            var mn = minCur[k]
            for (u in 0 until SUB) if (frames > SUB * subLen || u < subIdx) mn = min(mn, minSub[u][k])
            val nz = noise[k]
            noise[k] = when {
                start -> if (frames == 1) p else nz + (p - nz) / frames
                sm < PRESENCE * mn -> 0.92f * nz + 0.08f * p
                else -> nz
            }
            val lam = over * noise[k] + 1e-20f
            val gamma = p / lam
            val prio = if (start) max(gamma - 1f, 0f) else ALPHA_DD * prevS[k] / lam + (1f - ALPHA_DD) * max(gamma - 1f, 0f)
            val xiv = max(prio, xiMin)
            val gv = max(xiv / (1f + xiv), gmin)
            g[k] = gv
            prevS[k] = gv * gv * p
        }
        subFrames++
        if (subFrames >= subLen) {
            subFrames = 0
            System.arraycopy(minCur, 0, minSub[subIdx], 0, half + 1)
            subIdx = (subIdx + 1) % SUB
            System.arraycopy(smooth, 0, minCur, 0, half + 1)
        }
    }

    companion object {
        private const val SUB = 6
        private const val ALPHA_DD = 0.98f
        /** Smoothed power below this many times the minimum: no speech in the bin. */
        private const val PRESENCE = 4f
        /** Gain floor per strength: −8, −13, −18 dB. */
        private val GAIN_MIN = floatArrayOf(0.4f, 0.22f, 0.125f)
        /** Noise over-estimated a little at the strongest only: more costs the voice more than it gains. */
        private val OVER = floatArrayOf(1f, 1f, 1.2f)
    }
}
