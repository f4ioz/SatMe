/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import kotlin.math.abs

/**
 * From FM-demodulated audio to radiosonde bytes.
 *
 * The SDR chain already outputs the FM discriminator. Two-level FSK then looks
 * like a noisy square wave, and two things remain: where the middle is (the
 * zero) and when to sample (the clock).
 *
 * The zero is a slow moving average that absorbs tuning offset: 500 Hz off
 * and the square is off-centre, making every other bit wrong. Slow on purpose,
 * so a long run of identical bits doesn't drag it.
 *
 * The clock is a phase accumulator nudged at each transition. With only ~9
 * samples per bit at 4800 baud (barely 4 at 9600), nothing fancier makes
 * sense: a fractional samples-per-bit count and a gentle re-sync on edges.
 */
class SondeDemod(
    /** Input sample rate, Hz. */
    private val sampleRate: Double,
    /** Expected bit rate, baud. */
    private val baud: Double,
    /** Bytes kept in the frame search buffer. */
    bufferBytes: Int = 4096
) {

    /** Samples per bit, usually not an integer. */
    val samplesPerBit: Double = sampleRate / baud

    /** True if the sample rate is too low for this bit rate. */
    val marginal: Boolean get() = samplesPerBit < SondeModel.MIN_SAMPLES_PER_SYMBOL

    private var dcLevel = 0.0
    private var smooth = 0.0
    private var amp = 1.0
    private var phase = 0.0
    private var last = 0
    private var haveLast = false
    private var corrected = false

    /** Received bits, one per byte, in arrival order. */
    private val chips = ByteArray(bufferBytes * 8 + 64)
    private var chipCount = 0

    /** Rebuilt bytes. */
    val bytes = ByteArray(bufferBytes)
    var byteCount = 0
        private set

    private var bitInByte = 0
    private var acc = 0

    /** Time constant of the moving average tracking the discriminator zero. */
    private val dcAlpha = (baud / sampleRate / 400.0).coerceIn(1e-5, 1e-2)

    /**
     * Input smoothing, about one symbol wide: a poor man's matched filter, and
     * it changes everything on a real signal. Without it, noise crosses zero
     * several times per symbol and each false crossing pushes the clock. On the
     * auto_rx reference recordings that meant 99.1 % of bits, i.e. ~25 bits
     * lost per 320-byte frame and no complete frame at all. With smoothing,
     * hysteresis and one re-sync per symbol: 100.00 %.
     */
    private val smoothK = (2.0 / samplesPerBit).coerceIn(0.05, 1.0)

    /** Amplitude tracking, to size the hysteresis. */
    private val ampK = (dcAlpha * 20.0).coerceIn(1e-4, 0.2)

    /** Hysteresis width, as a fraction of observed amplitude. */
    private val hysteresis = 0.30

    fun reset() {
        dcLevel = 0.0
        smooth = 0.0
        amp = 1.0
        corrected = false
        phase = 0.0
        haveLast = false
        chipCount = 0
        byteCount = 0
        bitInByte = 0
        acc = 0
    }

    /**
     * Consumes an audio block, returns the number of bits produced. Bits also go
     * to [chipsOut] when given: biphase decoding (M10) works on half-bits.
     */
    fun feedBits(pcm: ShortArray, count: Int, chipsOut: ByteArray?): Int {
        var produced = 0
        for (k in 0 until count) {
            val x = pcm[k].toDouble()
            smooth += smoothK * (x - smooth)
            dcLevel += dcAlpha * (smooth - dcLevel)
            val d = smooth - dcLevel
            amp += ampK * (abs(d) - amp)
            val th = hysteresis * amp
            // Inside the dead band, keep the previous state, so centred noise
            // no longer creates transitions.
            val bit = if (d > th) 1 else if (d < -th) 0 else last

            if (haveLast && bit != last && !corrected) {
                // Gently pull the clock towards mid-bit: too hard and the loop
                // oscillates on noise, not at all and it drifts. Only once per
                // symbol, or a soft edge triggers three corrections and the
                // clock lags until it skips a bit.
                phase += (samplesPerBit / 2.0 - phase) * 0.20
                corrected = true
            }
            last = bit
            haveLast = true

            phase += 1.0
            if (phase >= samplesPerBit) {
                phase -= samplesPerBit
                corrected = false
                if (chipsOut != null && produced < chipsOut.size) chipsOut[produced] = bit.toByte()
                if (chipCount < chips.size) chips[chipCount++] = bit.toByte()
                produced++
            }
        }
        return produced
    }

    /**
     * Packs accumulated bits into LSB-first bytes from [offsetBits]. The caller
     * picks the alignment by searching for a header, shifting one bit per
     * unsuccessful try.
     */
    fun packBytes(offsetBits: Int = 0): Int {
        byteCount = 0
        var k = offsetBits
        while (k + 8 <= chipCount && byteCount < bytes.size) {
            var v = 0
            for (j in 0 until 8) if (chips[k + j].toInt() != 0) v = v or (1 shl j)
            bytes[byteCount++] = v.toByte()
            k += 8
        }
        return byteCount
    }

    /** Packs bits into MSB-first bytes (Meteomodem). */
    fun packBytesMsb(source: ByteArray, count: Int, offsetBits: Int = 0): Int {
        byteCount = 0
        var k = offsetBits
        while (k + 8 <= count && byteCount < bytes.size) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or (source[k + j].toInt() and 1)
            bytes[byteCount++] = v.toByte()
            k += 8
        }
        return byteCount
    }

    val bitsAvailable: Int get() = chipCount

    /** Copy of accumulated bits, for biphase decoding. */
    fun chipsCopy(): ByteArray = chips.copyOf(chipCount)

    /**
     * Drops consumed bits. Callers must always keep a whole frame minus one
     * bit, or a frame straddling two audio blocks is lost every time.
     */
    fun consumeBits(n: Int) {
        val keep = chipCount - n
        if (keep <= 0) { chipCount = 0; return }
        System.arraycopy(chips, n, chips, 0, keep)
        chipCount = keep
    }

    /** Keeps only the last [keep] bits. */
    fun trimTo(keep: Int) {
        if (chipCount > keep) consumeBits(chipCount - keep)
    }

    /** Peak-to-peak discriminator swing, a presence indicator. */
    fun swing(pcm: ShortArray, count: Int): Int {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (k in 0 until count) {
            val v = pcm[k].toInt()
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return if (count == 0) 0 else abs(hi - lo)
    }
}
