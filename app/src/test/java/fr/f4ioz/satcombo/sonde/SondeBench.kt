/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.RxChain
import fr.f4ioz.satcombo.sdr.RxMode
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measurement bench for the radiosonde chain: at what signal level does
 * decoding drop out? (A noiseless test pattern always decodes.)
 *
 * Method copied from radiosonde_auto_rx: add noise of known power to a clean
 * signal until decoding collapses, and report Eb/N0 in dB — demodulator
 * quality only, independent of antenna and dongle. Their reference values:
 * RS41 10.2 dB, M10 8.9 dB, DFM 6.9 dB.
 *
 * Input is IQ in the dongle's exact format (unsigned bytes at 1058400 Hz)
 * fed to the app's full [RxChain], so 8-bit quantisation is included.
 * Sweeps: noise at perfect tuning, and mistuning at fixed noise (auto_rx
 * reports 2.5 dB lost at 5 kHz off on an RS41).
 */
object SondeBench {

    /** Dongle sample rate, as in the real chain. */
    const val RATE = Dsp.RTL_RATE

    /**
     * USB block size in IQ bytes, as the dongle returns per transfer. Not a
     * detail: the hub searches once per block and drops the excess, so feeding
     * a whole second at once leaves one chance per second and skews results.
     */
    const val BLOCK = 16 * 1024

    /**
     * Flight step in real seconds. The demo pattern compresses a two-hour
     * flight into a minute, which flight tracking rightly rejects — and the
     * bench would count as decode failures. So: one frame per real second.
     */
    const val STEP_SEC = 1.0

    /**
     * FM deviation in hertz. RS41 uses ±2.4 kHz, Meteomodem about twice that —
     * hence 15 kHz filters for one and 22 kHz for the other.
     */
    fun deviationHz(model: String): Double = when (model) {
        "RS41" -> 2_400.0
        else -> 4_800.0
    }

    /** One measurement point. */
    data class Point(
        val model: String,
        val ebn0Db: Double,
        val offsetHz: Double,
        val tuneHz: Double,
        val expected: Int,
        val frames: Int,
        val rejected: Int = 0,
        val swing: Int = 0,
        val perSecond: String = ""
    ) {
        /** Frame error rate, the quantity auto_rx measures. */
        val per: Double get() =
            if (expected <= 0) 1.0 else (1.0 - frames.toDouble() / expected).coerceIn(0.0, 1.0)

        override fun toString(): String =
            ("%-4s Eb/N0 %5.1f dB  écart %+6.0f Hz  accord %+6.0f Hz  %2d/%2d trames  " +
                "PER %5.1f %%  rejets %d  amplitude %d  [%s]")
                .format(model, ebn0Db, offsetHz, tuneHz, frames, expected, per * 100.0,
                    rejected, swing, perSecond)
    }

    /** One second of transmitted symbols, including alternating padding. */
    private fun slotOf(model: String, p: SondeMire.Point, frameNo: Int, symbols: Int): ByteArray {
        val body = SondeMire.chipsFor(model, p, frameNo)
        val out = ByteArray(maxOf(symbols, body.size))
        System.arraycopy(body, 0, out, 0, body.size)
        for (k in body.size until out.size) out[k] = ((k - body.size) and 1).toByte()
        return out
    }

    /** Unsigned 8-bit quantisation, exactly as the dongle does. */
    private fun q8(v: Double): Byte =
        (Math.round(v + 127.5).toInt().coerceIn(0, 255)).toByte()

    /**
     * Spectral centroid relative to the tuning. Calls [RxChain.centroidOffsetHz]
     * directly: a bench measuring its own copy measures nothing.
     */
    fun centroidHz(
        chain: RxChain,
        searchHz: Double = 25_000.0,
        thresholdDb: Double = 6.0,
        dcNotchHz: Double = 400.0,
        narrowHz: Double = 4_000.0
    ): Double = chain.centroidOffsetHz(searchHz, thresholdDb, dcNotchHz, narrowHz)

    /**
     * Same measurement with no radio: the pattern goes straight into the
     * decoder. This is the control — whatever it does not reach is not the
     * receiver's fault.
     */
    fun measureAudio(model: String, seconds: Int = 6): Point {
        SondeHub.stop()
        SondeHub.start(null, SondeMire.RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = model, log = false)
        val src = SondeMire.Source(model, 48.2, -4.5, seconds, stepSec = STEP_SEC)
        val chunk = ShortArray(341)
        val trace = StringBuilder()
        var fed = 0
        var mark = SondeMire.RATE
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            SondeHub.feedLive(chunk, n)
            fed += n
            while (fed >= mark) {
                if (mark > SondeMire.RATE) trace.append(' ')
                trace.append(SondeHub.state.value.frames)
                mark += SondeMire.RATE
            }
        }
        val st = SondeHub.state.value
        SondeHub.stop()
        return Point(model, 99.0, 0.0, 0.0, seconds, st.frames,
            st.rejected, st.swing, trace.toString())
    }

    /**
     * Sends [seconds] frames with the given noise and mistuning and counts what
     * comes out. [tuneHz] is the manual software tuning; [autoTune] computes it
     * from the first second.
     */
    fun measure(
        model: String,
        ebn0Db: Double,
        seconds: Int = 6,
        offsetHz: Double = 0.0,
        tuneHz: Double = 0.0,
        autoTune: Boolean = false,
        seed: Long = 20_260_731L
    ): Point {
        val flight = SondeMire.flight(48.2, -4.5, seconds, stepSec = STEP_SEC)
        val chipRate = SondeMire.chipRate(model)
        val dev = deviationHz(model)
        val baud = SondeModel.byId(model).baud
        require(baud > 0.0) { "le banc veut un modèle précis, pas le mode automatique" }

        // Complex white noise. Carrier power is one; noise density follows
        // from the target Eb/N0 and the data bit rate.
        val gamma = 10.0.pow(ebn0Db / 10.0)
        val sigma = sqrt(RATE / (2.0 * baud * gamma))
        // Fit signal plus noise into the ADC range without hitting the rails:
        // beyond that the dongle clips and the measurement is meaningless.
        val scale = 100.0 / (1.0 + 3.0 * sigma)
        val rnd = Random(seed)

        SondeHub.stop()
        SondeHub.start(null, Dsp.AUDIO_RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = model, log = false)

        val chain = RxChain(maxDeviationHz = dev)
        chain.mode = RxMode.NFM
        chain.bandwidthHz = SondeModel.bandwidthFor(model).toDouble()
        chain.offsetHz = tuneHz

        val n = RATE
        val iq = ByteArray(2 * n)
        val block = ByteArray(BLOCK)
        val audio = ShortArray(chain.maxAudio(BLOCK))
        var phase = 0.0
        var applied = tuneHz
        val trace = StringBuilder()

        for (s in 0 until seconds) {
            val slot = slotOf(model, flight[s], s + 1, Math.round(chipRate).toInt())
            for (k in 0 until n) {
                val idx = (k.toLong() * slot.size / n).toInt().coerceIn(0, slot.size - 1)
                val f = offsetHz + if (slot[idx].toInt() != 0) dev else -dev
                phase += 2.0 * PI * f / RATE
                if (phase > PI) phase -= 2.0 * PI
                if (phase < -PI) phase += 2.0 * PI
                iq[2 * k] = q8((cos(phase) + sigma * rnd.nextGaussian()) * scale)
                iq[2 * k + 1] = q8((sin(phase) + sigma * rnd.nextGaussian()) * scale)
            }
            // Split into USB blocks: what the decoder sees for real.
            var at = 0
            while (at < iq.size) {
                val len = minOf(BLOCK, iq.size - at)
                System.arraycopy(iq, at, block, 0, len)
                val wantSpectrum = autoTune && s == 0 && at == 0
                val got = chain.process(block, len, audio, feedSpectrum = wantSpectrum)
                SondeHub.feedLive(audio, got)
                at += len
            }
            if (autoTune && s == 0) {
                applied = centroidHz(chain)
                chain.offsetHz = applied
            }
            if (s > 0) trace.append(' ')
            trace.append(SondeHub.state.value.frames)
        }

        val st = SondeHub.state.value
        SondeHub.stop()
        return Point(model, ebn0Db, offsetHz, applied, seconds, st.frames,
            st.rejected, st.swing, trace.toString())
    }
}
