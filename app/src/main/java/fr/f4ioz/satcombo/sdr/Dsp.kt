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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SDR signal processing, no Android: IQ from the dongle in, audio out.
 *
 *   1 058 400 S/s IQ    <- dongle output (24 x 44 100, on purpose)
 *        | decimate 6, wide filter     "drop the useless band"
 *     176 400 S/s IQ    <- spectrum and waterfall computed here
 *        | decimate 4, channel filter  "keep only the station"
 *      44 100 S/s IQ
 *        | demodulation (NFM, USB, LSB, AM)
 *      44 100 S/s audio <- exactly what the SSTV engine expects
 *
 * Two stages because an 8 kHz channel filter at 1 MHz would need hundreds of
 * taps; after decimating by 6 it needs about sixty.
 */
object Dsp {

    /** Dongle sample rate: 24x the audio rate, so decimation is exact. */
    const val RTL_RATE = 1_058_400

    /** Output audio rate, as used by the SSTV engine and MP3 encoder. */
    const val AUDIO_RATE = 44_100

    const val DECIM_1 = 6
    const val DECIM_2 = 4

    /**
     * Spectrum bins. 4096 over 176 400 Hz = 43 Hz per bin, enough to place a
     * carrier on the waterfall. 1024 bins gave 172 Hz: on a 6 kHz scale, only
     * ~30 real points stretched across the screen.
     */
    const val SPECTRUM_SIZE = 4096

    /**
     * Panorama bins: the analyser on the raw stream.
     *
     * The regular spectrum covers 176 400 Hz; the QO-100 narrowband
     * transponder plus beacons is 500 000 Hz. On the raw 1 058 400 S/s stream
     * **the whole transponder always fits**: half-width 529 200 Hz, so wherever
     * the dongle is tuned inside it, the whole band plan stays visible with no
     * PLL retune and no sweep mode. Margin is only 29 kHz worst case and
     * tuner edges are soft: a station at the far edge may look weaker than it
     * is.
     *
     * 16384 bins = 64.6 Hz per bin: a CW note fills one bin, the middle BPSK
     * beacon two or three, and the parabolic interpolation in
     * [fr.f4ioz.satcombo.domain.MesureBalise] gets well below that.
     */
    const val PANORAMA_SIZE = 16384

    /**
     * Windowed-sinc low-pass (Blackman). [taps] must be odd for linear phase
     * with no fractional delay.
     */
    fun lowPass(taps: Int, cutoffHz: Double, sampleRate: Double): FloatArray {
        val n = if (taps % 2 == 0) taps + 1 else taps
        val out = FloatArray(n)
        val fc = cutoffHz / sampleRate          // normalised frequency (0..0.5)
        val mid = (n - 1) / 2
        var sum = 0.0
        for (i in 0 until n) {
            val k = i - mid
            val sinc = if (k == 0) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)
            val w = 0.42 - 0.5 * cos(2.0 * PI * i / (n - 1)) + 0.08 * cos(4.0 * PI * i / (n - 1))
            val v = sinc * w
            out[i] = v.toFloat()
            sum += v
        }
        // Unity DC gain, or every stage changes the level.
        if (sum != 0.0) for (i in 0 until n) out[i] = (out[i] / sum).toFloat()
        return out
    }
}

/** What the chain does with the baseband signal. */
enum class RxMode {
    /** Narrowband FM: satellite voice, telemetry, SSTV. */
    NFM,

    /** Upper sideband: linear transponders, inverting or not. */
    USB,

    /** Lower sideband. */
    LSB,

    /** AM: beacons, and later APT weather images. */
    AM
}

/**
 * Complex decimating filter. Taps are only evaluated at output instants, so
 * decimating by 6 divides the cost by 6: the point of combining both.
 */
class ComplexDecimator(private val taps: FloatArray, private val factor: Int) {

    private val n = taps.size
    private var bufI = FloatArray(n - 1)
    private var bufQ = FloatArray(n - 1)
    /** Index in the next block of the first sample that produces an output. */
    private var phase = 0

    /** Maximum outputs for [count] inputs. */
    fun maxOut(count: Int): Int = count / factor + 2

    fun reset() {
        bufI.fill(0f); bufQ.fill(0f); phase = 0
    }

    fun process(
        inI: FloatArray, inQ: FloatArray, count: Int,
        outI: FloatArray, outQ: FloatArray
    ): Int {
        val need = n - 1 + count
        if (bufI.size < need) {
            bufI = bufI.copyOf(need)
            bufQ = bufQ.copyOf(need)
        }
        System.arraycopy(inI, 0, bufI, n - 1, count)
        System.arraycopy(inQ, 0, bufQ, n - 1, count)

        var out = 0
        var k = phase
        while (k < count) {
            var si = 0f
            var sq = 0f
            val base = k + n - 1
            for (t in 0 until n) {
                val c = taps[t]
                si += c * bufI[base - t]
                sq += c * bufQ[base - t]
            }
            outI[out] = si
            outQ[out] = sq
            out++
            k += factor
        }
        phase = k - count

        System.arraycopy(bufI, count, bufI, 0, n - 1)
        System.arraycopy(bufQ, count, bufQ, 0, n - 1)
        return out
    }
}

/**
 * Complex-coefficient band-pass, the piece that makes SSB possible.
 *
 * In complex baseband, USB occupies only positive frequencies and LSB only
 * negative ones. A real filter can't tell +1500 Hz from -1500 Hz. Shifting a
 * low-pass by h[k] = lowpass[k]·e^{j2πf_c k/f_s} gives a band-pass on one side
 * of zero only. The output is m + j·m̂ (or m − j·m̂): the real part is the
 * message, so demodulation is just dropping the imaginary part.
 *
 * The phasing method, as in a transceiver, except the exact 90° shift comes by
 * construction rather than from matched RC networks.
 */
class ComplexBandpass(
    taps: Int,
    centerHz: Double,
    widthHz: Double,
    sampleRate: Double
) {
    private val hI: FloatArray
    private val hQ: FloatArray
    private val n: Int

    init {
        val lp = Dsp.lowPass(taps, widthHz / 2.0, sampleRate)
        n = lp.size
        hI = FloatArray(n)
        hQ = FloatArray(n)
        val mid = (n - 1) / 2
        val dp = 2.0 * PI * centerHz / sampleRate
        for (k in 0 until n) {
            val ph = dp * (k - mid)
            hI[k] = (lp[k] * cos(ph)).toFloat()
            hQ[k] = (lp[k] * sin(ph)).toFloat()
        }
    }

    private var bufI = FloatArray(n - 1)
    private var bufQ = FloatArray(n - 1)

    fun reset() { bufI.fill(0f); bufQ.fill(0f) }

    /** Filters [count] complex samples, writing only the real part (the SSB audio) to [out]. */
    fun process(inI: FloatArray, inQ: FloatArray, count: Int, out: FloatArray): Int {
        val need = n - 1 + count
        if (bufI.size < need) {
            bufI = bufI.copyOf(need)
            bufQ = bufQ.copyOf(need)
        }
        System.arraycopy(inI, 0, bufI, n - 1, count)
        System.arraycopy(inQ, 0, bufQ, n - 1, count)
        for (k in 0 until count) {
            var re = 0f
            val base = k + n - 1
            for (t in 0 until n) {
                re += hI[t] * bufI[base - t] - hQ[t] * bufQ[base - t]
            }
            out[k] = re
        }
        System.arraycopy(bufI, count, bufI, 0, n - 1)
        System.arraycopy(bufQ, count, bufQ, 0, n - 1)
        return count
    }
}

/**
 * Frequency discriminator: arg(z[n]·conj(z[n-1])) is the instantaneous
 * frequency deviation, independent of amplitude.
 */
class FmDiscriminator(private val sampleRate: Double, private val maxDeviationHz: Double) {

    private var prevI = 0f
    private var prevQ = 0f
    private val hzPerRad = sampleRate / (2.0 * PI)

    /** Mean level of the last block, dBFS (negative). */
    var levelDb: Float = -120f
        private set

    fun reset() { prevI = 0f; prevQ = 0f; levelDb = -120f }

    /** Demodulates [count] complex samples to 16-bit audio. */
    fun process(inI: FloatArray, inQ: FloatArray, count: Int, out: ShortArray): Int {
        var mag = 0.0
        for (k in 0 until count) {
            val i = inI[k]
            val q = inQ[k]
            val re = i * prevI + q * prevQ
            val im = q * prevI - i * prevQ
            prevI = i; prevQ = q
            val hz = atan2(im.toDouble(), re.toDouble()) * hzPerRad
            val v = (hz / maxDeviationHz).coerceIn(-1.0, 1.0)
            out[k] = (v * 26_000).toInt().toShort()
            mag += sqrt((i * i + q * q).toDouble())
        }
        if (count > 0) {
            val avg = mag / count
            levelDb = if (avg <= 1e-9) -120f else (20.0 * log10(avg)).toFloat()
        }
        return count
    }
}

/**
 * Audio de-emphasis. Broadcast and amateur FM pre-emphasise the highs; without
 * the inverse filter reception hisses. SSTV doesn't care (its discriminator
 * only looks at frequency). Can be turned off: telemetry and beacons read
 * better flat.
 */
class Deemphasis(tauMicros: Double, sampleRate: Double) {
    private val a = kotlin.math.exp(-1.0 / (sampleRate * tauMicros * 1e-6)).toFloat()

    /**
     * Make-up gain, computed rather than guessed.
     *
     * H(f) = (1 − a) / |1 − a·e^{−jω}|. A fixed x3 left 1 kHz 11 dB too low: a
     * 1750 Hz tone-burst became inaudible on a perfectly received signal. So we
     * normalise at 1 kHz, the modulation reference, so voice comes out at the
     * same level as flat.
     */
    private val makeup: Float = run {
        val ad = a.toDouble()
        val g = 1.0 - ad
        val w = 2.0 * PI * 1_000.0 / sampleRate
        val x = 1.0 - ad * cos(w)
        val y0 = ad * sin(w)
        val h = g / sqrt(x * x + y0 * y0)
        if (h <= 1e-9) 1f else (1.0 / h).coerceIn(1.0, 64.0).toFloat()
    }

    /** Make-up gain actually applied (for tests). */
    val makeupGain: Float get() = makeup

    private var y = 0f
    fun reset() { y = 0f }
    fun process(buf: ShortArray, count: Int) {
        val g = 1f - a
        for (k in 0 until count) {
            y = a * y + g * buf[k]
            buf[k] = (y * makeup).coerceIn(-32000f, 32000f).toInt().toShort()
        }
    }
}

/** Removes residual discriminator DC (mistuning). */
class DcBlock(private val alpha: Float = 0.9995f) {
    private var xPrev = 0f
    private var yPrev = 0f
    fun reset() { xPrev = 0f; yPrev = 0f }
    fun process(buf: ShortArray, count: Int) {
        for (k in 0 until count) {
            val x = buf[k].toFloat()
            val y = x - xPrev + alpha * yPrev
            xPrev = x; yPrev = y
            buf[k] = y.coerceIn(-32000f, 32000f).toInt().toShort()
        }
    }
}

/**
 * Audio AGC.
 *
 * In FM the audio level doesn't depend on signal strength; in SSB and AM it
 * does, massively: 40 dB between a station at zenith and one on the horizon.
 * Without AGC you spend the pass chasing the volume knob.
 *
 * Fast attack (don't saturate on a syllable), slow release (don't let noise
 * pump up between words).
 */
class AudioAgc(
    private val target: Float = 8_000f,
    private val maxGain: Float = 20_000_000f
) {
    private var env = 0f
    private val attack = 0.02f
    private val release = 0.0003f

    fun reset() { env = 0f }

    /** Converts [count] float samples to levelled 16-bit audio. */
    fun process(buf: FloatArray, count: Int, out: ShortArray) {
        for (k in 0 until count) {
            val x = buf[k]
            val a = abs(x)
            env += if (a > env) attack * (a - env) else release * (a - env)
            val g = if (env <= 1e-7f) maxGain else (target / env).coerceIn(1f, maxGain)
            out[k] = (x * g).coerceIn(-32000f, 32000f).toInt().toShort()
        }
    }
}

/**
 * Squelch, with hysteresis so noise doesn't flap the gate on every syllable.
 * A −120 dB threshold keeps it always open ("squelch off").
 */
class Squelch(var thresholdDb: Float = -120f, private val hysteresisDb: Float = 4f) {

    var open: Boolean = true
        private set

    fun reset() { open = true }

    /** Updates the gate for a block at [levelDb]; returns its state. */
    fun update(levelDb: Float): Boolean {
        open = if (open) levelDb > thresholdDb - hysteresisDb else levelDb > thresholdDb
        return open
    }
}

/**
 * In-place radix-2 FFT. Written here rather than pulled from a library: the
 * whole SDR chain stays pure Kotlin, testable on a desktop, and an FFT is
 * twenty lines.
 */
object Fft {

    /** Transforms [re]/[im] in place. Size must be a power of two. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return
        require(n and (n - 1) == 0) { "taille non puissance de deux : $n" }

        // Bit-reversal permutation.
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

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Spectrum analyser: accumulates complex samples and, once the window is full,
 * outputs power per bin in dBFS.
 *
 * Output is already shifted: index 0 is −f_s/2, the middle is the tuned
 * frequency, the last +f_s/2, so the display needs no reordering.
 */
class SpectrumAnalyzer(val size: Int = 1024) {

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val win = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }
    private var fill = 0

    /** Power per bin, dBFS, from −f_s/2 to +f_s/2. */
    val magDb = FloatArray(size) { -120f }

    /** Complete frames computed since the last [reset]. */
    var frames: Long = 0
        private set

    fun reset() { fill = 0; frames = 0; magDb.fill(-120f) }

    /**
     * Starts an empty frame without clearing the display.
     *
     * The caller only feeds the analyser ten times a second. Without this, a
     * frame got stitched from pieces 90 ms apart, and the FFT of such a signal
     * is meaningless: the carrier smeared instead of making a line.
     */
    fun begin() { fill = 0 }

    /** Pushes [count] complex samples. True if a frame was just computed ([magDb] updated). */
    fun push(inI: FloatArray, inQ: FloatArray, count: Int): Boolean {
        var done = false
        var k = 0
        while (k < count) {
            val take = minOf(size - fill, count - k)
            for (t in 0 until take) {
                re[fill + t] = inI[k + t].toDouble()
                im[fill + t] = inQ[k + t].toDouble()
            }
            fill += take
            k += take
            if (fill == size) {
                compute()
                fill = 0
                done = true
            }
        }
        return done
    }

    private fun compute() {
        for (i in 0 until size) {
            re[i] *= win[i]
            im[i] *= win[i]
        }
        Fft.transform(re, im)
        val half = size / 2
        val norm = 1.0 / (size * 0.5)   // 0.5: mean gain of the Hann window
        for (i in 0 until size) {
            // fftshift: FFT bin 0 (the tuned frequency) goes to the middle;
            // bins above size/2 are the negative frequencies.
            val src = if (i < half) i + half else i - half
            val m = sqrt(re[src] * re[src] + im[src] * im[src]) * norm
            magDb[i] = if (m <= 1e-9) -120f else (20.0 * log10(m)).toFloat()
        }
        frames++
    }
}

/**
 * Full chain: raw dongle IQ bytes to 44 100 Hz mono audio, in the chosen mode.
 *
 * [offsetHz] shifts the listening frequency inside the received band without
 * touching the tuner PLL: it compensates the synthesiser step (~100 Hz,
 * invisible in FM but not in SSB), follows a station drifting in the
 * transponder, and lets the user tap the waterfall to tune. The shift is
 * applied after the first decimator; the target station lands on 0 Hz and the
 * rest of the chain works as usual.
 */
class RxChain(
    private val rtlRate: Double = Dsp.RTL_RATE.toDouble(),
    maxDeviationHz: Double = 5_000.0
) {
    private val stage1Rate = rtlRate / Dsp.DECIM_1
    private val stage2Rate = stage1Rate / Dsp.DECIM_2

    // Stage 1: not about selectivity, only avoiding aliasing when decimating
    // by 6. Wide and steep, because the fine shift happens after this stage:
    // anything the user can tap on screen must survive it. With a divisor of
    // 2.9 the usable band stopped at ±30 kHz.
    private val dec1 = ComplexDecimator(
        Dsp.lowPass(95, rtlRate / Dsp.DECIM_1 / 2.4, rtlRate), Dsp.DECIM_1)

    // Stage 2: channel filter, rebuilt when the width changes.
    private var dec2Cutoff = 8_000.0
    private var dec2 = ComplexDecimator(
        Dsp.lowPass(63, dec2Cutoff, stage1Rate), Dsp.DECIM_2)

    private val disc = FmDiscriminator(stage2Rate, maxDeviationHz)
    private val dc = DcBlock()
    private val deemph = Deemphasis(750.0, stage2Rate)
    private val agc = AudioAgc()

    /** Sideband filter, rebuilt when mode or width changes. */
    private var ssb: ComplexBandpass? = null
    private var ssbKey = ""

    var mode: RxMode = RxMode.NFM

    /**
     * De-emphasis. Off by default: it only makes sense for wideband (broadcast)
     * FM; on a repeater or SSTV image it just crushes the highs.
     */
    var deemphasis: Boolean = false

    /** Software fine offset, Hz. */
    var offsetHz: Double = 0.0

    /**
     * Software Doppler shift, Hz, on top of [offsetHz].
     *
     * Kept separate because they have different owners: [offsetHz] is the
     * operator's finger on the waterfall, this is automatic tracking. One
     * shared variable would wipe the manual setting in the first second of
     * the pass.
     */
    var dopplerFineHz: Double = 0.0

    /**
     * Requested channel width, Hz. Zero means "mode default": 16 kHz NFM,
     * 2.4 kHz SSB, 6 kHz AM.
     */
    var bandwidthHz: Double = 0.0

    /** Squelch, dBFS. −120 dB means off. */
    val squelch = Squelch()

    /** Spectrum analyser, fed on demand from the first stage output. */
    val spectrum = SpectrumAnalyzer(Dsp.SPECTRUM_SIZE)

    /** True while filling a started spectrum frame. */
    private var collecting = false

    /**
     * Analyser on the raw stream before any decimation: everything the dongle
     * receives, edge to edge. See [Dsp.PANORAMA_SIZE] for why.
     */
    val panorama = SpectrumAnalyzer(Dsp.PANORAMA_SIZE)

    /** True while filling a started panorama frame. */
    private var collectingPan = false

    /** Spectrum span, Hz (first stage rate). */
    val spectrumSpanHz: Double get() = stage1Rate

    /** Panorama span, Hz: the dongle rate. */
    val panoramaSpanHz: Double get() = rtlRate

    private var nco = 0.0
    private var level = -120f
    private var amDc = 0f
    private var clip = 0f
    private var peak = 0f

    /**
     * Fraction of raw samples stuck at the ADC limits. Above a few per mille
     * the dongle front end is saturated: noise disappears and modulation with
     * it, typically a nearby transmitter flooding the dongle. No software fix:
     * lower the gain or move the antenna.
     */
    val clipRatio: Float get() = clip

    /** Output audio peak, 0..1, for the VU meter. */
    val audioPeak: Float get() = peak

    /**
     * Offset from the tuned frequency (Hz) of the strongest bin between
     * [fromHz] and [toHz] in the last spectrum frame.
     */
    fun peakOffsetHz(fromHz: Double, toHz: Double): Double {
        val n = spectrum.size
        if (spectrum.frames == 0L) return 0.0
        val hzPerBin = stage1Rate / n
        var best = -1
        var bestV = -400f
        for (i in 0 until n) {
            val f = (i - n / 2) * hzPerBin
            if (f < fromHz || f > toHz) continue
            val v = spectrum.magDb[i]
            if (v > bestV) { bestV = v; best = i }
        }
        if (best < 0) return 0.0
        return (best - n / 2) * hzPerBin
    }

    /**
     * Power-weighted centre of the signal in the spectrum, Hz relative to the
     * dongle tuning. The auto-tune, born on the radiosonde test bench.
     *
     * The strongest bin ([peakOffsetHz]) suits a carrier and nothing else.
     * FSK has no carrier, just two humps one deviation apart; aiming at the
     * higher one always tunes off to the side. The power-weighted mean falls
     * between the humps, where the carrier would be.
     *
     * Three precautions, each learned from a measurement:
     *  - [thresholdDb] above the floor rejects noise. Otherwise thousands of
     *    empty bins pull the mean towards the window centre.
     *  - [dcNotchHz] skips bins near zero. That line comes from the dongle, not
     *    the station (every direct-conversion receiver has a DC spike) and it
     *    shaved about a sixth off the estimate: 1600 Hz reported for 2000.
     *  - Two passes: a wide one for a rough position, then one within
     *    [narrowHz] of it where only the station remains. Residual noise in a
     *    wide window always pulls towards its centre.
     *
     * The spectrum is taken before the fine-tuning mixer, so the result is
     * absolute relative to the dongle frequency and is *written* to
     * [offsetHz], not added. That is what makes continuous re-centring
     * harmless: once tuned, it returns zero.
     *
     * Returns zero when nothing clears the threshold: no signal, no tuning,
     * and above all no drift towards noise.
     */
    fun centroidOffsetHz(
        searchHz: Double = 25_000.0,
        thresholdDb: Double = 6.0,
        dcNotchHz: Double = 400.0,
        narrowHz: Double = 4_000.0,
        /**
         * Search window centre, Hz relative to tuning.
         *
         * Zero searches around the dongle frequency: right for catching a
         * radiosonde in an empty band. To lock onto the station already being
         * heard, search around where you are **parked**, or the measurement
         * jumps to a stronger neighbour at the first silence.
         */
        centreHz: Double = 0.0
    ): Double {
        if (spectrum.frames == 0L) return 0.0
        val n = spectrum.size
        val hzPerBin = stage1Rate / n
        val mag = spectrum.magDb

        val basHz = centreHz - searchHz
        val hautHz = centreHz + searchHz

        var floorDb = 0.0
        var count = 0
        for (i in 0 until n) {
            val f = (i - n / 2) * hzPerBin
            if (f < basHz || f > hautHz) continue
            floorDb += mag[i]; count++
        }
        if (count == 0) return 0.0
        floorDb /= count

        fun pass(loHz: Double, hiHz: Double): Double {
            var num = 0.0
            var den = 0.0
            for (i in 0 until n) {
                val f = (i - n / 2) * hzPerBin
                if (f < loHz || f > hiHz) continue
                if (f > -dcNotchHz && f < dcNotchHz) continue
                val d = mag[i] - floorDb
                if (d < thresholdDb) continue
                val w = 10.0.pow(d / 10.0)
                num += w * f; den += w
            }
            return if (den <= 0.0) Double.NaN else num / den
        }

        val rough = pass(basHz, hautHz)
        if (rough.isNaN()) return 0.0
        val fine = pass(rough - narrowHz, rough + narrowHz)
        return if (fine.isNaN()) rough else fine
    }

    /** In-channel signal level, dBFS. */
    val levelDb: Float get() = level

    /** Output audio rate, rounded. */
    val audioRate: Int get() = Math.round(stage2Rate).toInt()

    /** Channel width actually applied, Hz. */
    val effectiveBandwidthHz: Double
        get() {
            val asked = bandwidthHz
            if (asked > 0.0) return asked.coerceIn(500.0, 24_000.0)
            return when (mode) {
                RxMode.NFM -> 16_000.0
                RxMode.USB, RxMode.LSB -> 2_400.0
                RxMode.AM -> 6_000.0
            }
        }

    private var aI = FloatArray(0)
    private var aQ = FloatArray(0)
    private var bI = FloatArray(0)
    private var bQ = FloatArray(0)
    private var cI = FloatArray(0)
    private var cQ = FloatArray(0)
    private var fl = FloatArray(0)

    fun reset() {
        dec1.reset(); dec2.reset(); disc.reset(); dc.reset(); deemph.reset()
        agc.reset(); squelch.reset(); ssb?.reset(); spectrum.reset()
        nco = 0.0; level = -120f; amDc = 0f
        clip = 0f; peak = 0f; collecting = false
    }

    /** Audio buffer size needed for [iqBytes] input bytes. */
    fun maxAudio(iqBytes: Int): Int = iqBytes / 2 / (Dsp.DECIM_1 * Dsp.DECIM_2) + 4

    /** Rebuilds filters if mode or width changed. */
    private fun retune() {
        val bw = effectiveBandwidthHz
        // In SSB the channel filter stays wide: the complex band-pass does the
        // selectivity and works better with some margin.
        val wanted = when (mode) {
            RxMode.NFM -> (bw / 2.0).coerceIn(2_500.0, 20_000.0)
            RxMode.USB, RxMode.LSB -> 6_000.0
            RxMode.AM -> (bw / 2.0).coerceIn(1_500.0, 20_000.0)
        }
        if (abs(wanted - dec2Cutoff) > 1.0) {
            dec2Cutoff = wanted
            dec2 = ComplexDecimator(Dsp.lowPass(63, dec2Cutoff, stage1Rate), Dsp.DECIM_2)
        }
        if (mode == RxMode.USB || mode == RxMode.LSB) {
            val sign = if (mode == RxMode.USB) 1.0 else -1.0
            // Passband 300 Hz to 300 + width: lows add nothing to voice and
            // cost a lot of noise.
            val center = sign * (300.0 + bw / 2.0)
            val key = "$center/$bw"
            if (key != ssbKey) {
                ssbKey = key
                ssb = ComplexBandpass(255, center, bw, stage2Rate)
            }
        } else if (ssbKey.isNotEmpty()) {
            ssbKey = ""
            ssb = null
        }
    }

    /**
     * Processes [len] IQ bytes (I then Q, unsigned 8-bit) into audio in [out].
     * Returns the number of audio samples.
     *
     * [feedSpectrum] also sends the first stage output to the spectrum
     * analyser. The caller asks ten times a second; an FFT per received block
     * would be wasted work.
     *
     * [feedPanorama] does the same with the raw stream for the wide analyser,
     * even less often (a few times a second): a 16k FFT costs about four
     * spectrum FFTs, and station layout in a transponder changes by the minute.
     */
    fun process(
        iq: ByteArray,
        len: Int,
        out: ShortArray,
        feedSpectrum: Boolean = false,
        feedPanorama: Boolean = false,
    ): Int {
        val n = len / 2
        if (n == 0) return 0
        retune()
        if (aI.size < n) { aI = FloatArray(n); aQ = FloatArray(n) }

        // Convert raw bytes, counting clipping along the way.
        var clipped = 0
        for (k in 0 until n) {
            val bi = iq[2 * k].toInt() and 0xff
            val bq = iq[2 * k + 1].toInt() and 0xff
            if (bi <= 2 || bi >= 253) clipped++
            if (bq <= 2 || bq >= 253) clipped++
            aI[k] = (bi - 127.5f) / 127.5f
            aQ[k] = (bq - 127.5f) / 127.5f
        }
        clip = clipped.toFloat() / (2 * n)

        // Panorama taken here, on the raw stream, before the fine-tuning mixer:
        // anchored to the PLL frequency alone, so an absolute frequency scale
        // with fixed markers can be drawn. Taken after the mixer, it would
        // slide under the markers at every cursor move.
        if (feedPanorama && !collectingPan) { panorama.begin(); collectingPan = true }
        if (collectingPan && panorama.push(aI, aQ, n)) collectingPan = false

        val m1 = dec1.maxOut(n)
        if (bI.size < m1) { bI = FloatArray(m1); bQ = FloatArray(m1) }
        val n1 = dec1.process(aI, aQ, n, bI, bQ)

        // Spectrum taken BEFORE the fine shift. Taken after, the waterfall
        // slid with the cursor and the displayed offset accumulated instead of
        // shrinking, making tuning impossible. Here the waterfall stays
        // anchored to the dongle frequency and the cursor points at a real
        // place in the spectrum.
        if (feedSpectrum && !collecting) { spectrum.begin(); collecting = true }
        if (collecting && spectrum.push(bI, bQ, n1)) collecting = false

        // Fine shift: bring the target station to zero. "+2000 Hz" means
        // "listen 2 kHz above the displayed frequency".
        val shiftHz = offsetHz + dopplerFineHz
        if (shiftHz != 0.0) {
            val dp = 2.0 * PI * shiftHz / stage1Rate
            for (k in 0 until n1) {
                val i = bI[k]; val q = bQ[k]
                val c = cos(nco).toFloat()
                val sn = sin(nco).toFloat()
                bI[k] = i * c + q * sn
                bQ[k] = q * c - i * sn
                nco += dp
                if (nco > PI) nco -= 2.0 * PI else if (nco < -PI) nco += 2.0 * PI
            }
        }

        val m2 = dec2.maxOut(n1)
        if (cI.size < m2) { cI = FloatArray(m2); cQ = FloatArray(m2) }
        val n2 = dec2.process(bI, bQ, n1, cI, cQ)

        val count = minOf(n2, out.size)
        if (count <= 0) return 0

        // Level measured on baseband, valid in every mode.
        var mag = 0.0
        for (k in 0 until count) {
            val i = cI[k]; val q = cQ[k]
            mag += sqrt((i * i + q * q).toDouble())
        }
        val avg = mag / count
        level = if (avg <= 1e-9) -120f else (20.0 * log10(avg)).toFloat()

        val produced = when (mode) {
            RxMode.NFM -> {
                val p = disc.process(cI, cQ, count, out)
                dc.process(out, p)
                if (deemphasis) deemph.process(out, p)
                p
            }
            RxMode.USB, RxMode.LSB -> {
                if (fl.size < count) fl = FloatArray(count)
                val f = ssb ?: ComplexBandpass(255, 1_500.0, 2_400.0, stage2Rate).also { ssb = it }
                f.process(cI, cQ, count, fl)
                agc.process(fl, count, out)
                count
            }
            RxMode.AM -> {
                if (fl.size < count) fl = FloatArray(count)
                for (k in 0 until count) {
                    val i = cI[k]; val q = cQ[k]
                    val a = sqrt(i * i + q * q)
                    // Remove the carrier (DC after envelope detection), or it
                    // saturates the audio stage for nothing.
                    amDc += 0.0005f * (a - amDc)
                    fl[k] = a - amDc
                }
                agc.process(fl, count, out)
                count
            }
        }

        if (!squelch.update(level)) {
            java.util.Arrays.fill(out, 0, produced, 0)
        }
        var mx = 0
        for (k in 0 until produced) {
            val v = kotlin.math.abs(out[k].toInt())
            if (v > mx) mx = v
        }
        peak = mx / 32768f
        return produced
    }
}

/** Old name from the NFM-only days, kept so existing callers and tests still build. */
typealias NfmChain = RxChain

/**
 * Band-based spectrum measurement without FFT. Kept because it works directly
 * on raw bytes and independently cross-checks [SpectrumAnalyzer]: two methods
 * agreeing on a carrier position beat one taken on trust.
 */
class SpectrumProbe(private val bins: Int = 64) {

    private val acc = FloatArray(bins)

    /** Power per band, dBFS, lowest to highest. */
    val bands = FloatArray(bins) { -120f }

    /**
     * Single-bin DFTs (Goertzel-style) on [bins] bands across the received
     * width, on a decimated excerpt ([stride]): the display refreshes ten
     * times a second, no need to process the whole stream.
     */
    fun analyse(iq: ByteArray, len: Int, stride: Int = 4) {
        val n = len / 2
        if (n < bins * 4) return
        acc.fill(0f)
        for (b in 0 until bins) {
            // Band b centred on (b/bins - 0.5) x the sample rate.
            val f = (b.toDouble() / bins) - 0.5
            var re = 0.0
            var im = 0.0
            var ph = 0.0
            val dp = -2.0 * PI * f * stride
            var k = 0
            var used = 0
            while (k < n) {
                val i = ((iq[2 * k].toInt() and 0xff) - 127.5)
                val q = ((iq[2 * k + 1].toInt() and 0xff) - 127.5)
                val c = cos(ph)
                val s = sin(ph)
                re += i * c - q * s
                im += i * s + q * c
                ph += dp
                k += stride
                used++
            }
            val mag = sqrt(re * re + im * im) / (used.coerceAtLeast(1) * 127.5)
            bands[b] = if (mag <= 1e-9) -120f else (20.0 * log10(mag)).toFloat()
        }
    }
}
