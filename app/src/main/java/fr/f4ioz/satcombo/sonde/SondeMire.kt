/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radiosonde test signal: a synthetic balloon made entirely by the phone.
 *
 * Launches happen twice a day and a sonde is in range only if the wind
 * agrees; when nothing decodes, the next try is twelve hours away. This
 * generates on demand what a discriminator outputs on a real sonde: real
 * frames in the manufacturer's exact format, scrambling and checks included.
 * The decoder cannot tell it from the air. Played by [SondeMirePlayer].
 *
 * The flight starts at the operator's grid square, climbs at 5 m/s drifting
 * on a westerly wind, bursts around 30 km and falls ever slower as the air
 * thickens: a real sonde profile, which exercises every case the UI must show.
 */
object SondeMire {

    /** Output sample rate, the sound card's. */
    const val RATE = 44_100

    /** Square-wave peak amplitude, well above the demodulator threshold. */
    const val AMPLITUDE = 9_000

    /**
     * Synthesis oversampling factor, before filtering and decimation.
     *
     * Picking the nearest symbol per sample at 44.1 kHz (4.6 samples per
     * half-bit) moves each edge by up to half a sample: 10 % symbol jitter made
     * by the generator, absent from any real sonde. Synthesising 8x faster then
     * filtering puts edges where they should be, rounded like a real
     * discriminator's.
     */
    const val OVERSAMPLE = 8

    /** Ambience noise, as a fraction of amplitude. */
    const val NOISE = 0.12

    /** Fading floor: the sonde weakens but never disappears. */
    const val FADE_FLOOR = 0.45

    /** Fading rate, Hz. */
    const val FADE_HZ = 0.07

    /** Frequency written into the frames, Hz. */
    const val DEMO_FREQ_HZ = 404_000_000L

    /** Offered durations, seconds: one frame per second, as on air. */
    val DURATIONS = listOf(30, 60, 120, 300)

    // ------------------------------------------------------------------ flight

    data class Point(
        val lat: Double, val lon: Double, val altM: Double,
        val east: Double, val north: Double, val up: Double,
        val sats: Int,
        /**
         * Flight time represented, seconds since launch.
         *
         * Not the frame number: a one-minute test signal tells a two-hour
         * flight. This value must go into the frame's GPS clock, otherwise the
         * signal contradicts itself (40 km moved in "one second") and the
         * flight continuity check rightly drops the point. Symptom: only one
         * frame in six retained, which looks like a receiver fault.
         */
        val tSec: Double = 0.0)

    /**
     * The full flight in [frames] evenly spaced points. Time is compressed:
     * nobody listens to a test signal for two hours, so one minute tells the
     * whole flight and the map track looks like the real thing.
     */
    fun flight(
        lat0: Double, lon0: Double, frames: Int,
        burstAltM: Double = 30_000.0, groundAltM: Double = 120.0,
        stepSec: Double = 0.0
    ): List<Point> {
        val out = ArrayList<Point>(frames)
        // Real time when a step is given: each frame covers exactly the flight
        // time it announces. The test bench needs this, since it counts
        // retained frames. The demo keeps the compressed flight.
        val realTime = stepSec > 0.0
        val step = if (realTime) stepSec
                   else (burstAltM - groundAltM) / 5.0 * 1.5 / frames
        // Two thirds ascent, one third descent, the usual ratio. In real time,
        // a few seconds of flight are only ascent.
        val up = if (realTime) frames else (frames * 2) / 3
        var lat = lat0
        var lon = lon0
        for (k in 0 until frames) {
            val t = (k + 1) * step
            val ascending = k < up
            val f = if (ascending) k.toDouble() / maxOf(1, up)
                    else (k - up).toDouble() / maxOf(1, frames - up)
            val alt = if (realTime) groundAltM + 5.0 * t
                      else if (ascending) groundAltM + (burstAltM - groundAltM) * f
                      else burstAltM - (burstAltM - groundAltM) * f
            // Wind grows with altitude: almost nothing on the ground, ~30 m/s
            // around 10 km.
            val windE = 4.0 + 26.0 * (alt / 12_000.0).coerceIn(0.0, 1.0)
            val windN = 2.0 + 6.0 * (alt / 12_000.0).coerceIn(0.0, 1.0)
            // 5 m/s up; descent slows as the air gets denser.
            val climb = if (ascending) 5.0
                        else -(4.0 + 26.0 * (alt / burstAltM).coerceIn(0.0, 1.0))
            // Move the sonde by the distance covered during the represented step.
            lat += windN * step / 111_320.0
            lon += windE * step / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
            out += Point(lat, lon, alt, windE, windN, climb,
                sats = if (alt > 1_000.0) 10 else 7,
                tSec = t)
        }
        return out
    }

    // ------------------------------------------------------------- geodesy

    /** Geodetic to ECEF, the form the RS41 transmits. */
    fun geodeticToEcef(latDeg: Double, lonDeg: Double, hM: Double): DoubleArray {
        val la = Math.toRadians(latDeg)
        val lo = Math.toRadians(lonDeg)
        val s = sin(la)
        val n = Geo.A / sqrt(1.0 - Geo.E2 * s * s)
        return doubleArrayOf(
            (n + hM) * cos(la) * cos(lo),
            (n + hM) * cos(la) * sin(lo),
            (n * (1.0 - Geo.E2) + hM) * s)
    }

    /** Local east/north/up velocity to ECEF. */
    fun enuToEcefVel(latDeg: Double, lonDeg: Double,
                     e: Double, n: Double, u: Double): DoubleArray {
        val la = Math.toRadians(latDeg)
        val lo = Math.toRadians(lonDeg)
        val sla = sin(la); val cla = cos(la)
        val slo = sin(lo); val clo = cos(lo)
        return doubleArrayOf(
            -slo * e - sla * clo * n + cla * clo * u,
            clo * e - sla * slo * n + cla * slo * u,
            cla * n + sla * u)
    }

    // -------------------------------------------------------------- writers

    private fun putU16(f: ByteArray, at: Int, v: Int) {
        f[at] = (v and 0xff).toByte()
        f[at + 1] = ((v shr 8) and 0xff).toByte()
    }

    private fun putI32(f: ByteArray, at: Int, v: Int) {
        f[at] = (v and 0xff).toByte()
        f[at + 1] = ((v shr 8) and 0xff).toByte()
        f[at + 2] = ((v shr 16) and 0xff).toByte()
        f[at + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun putBe16(f: ByteArray, at: Int, v: Int) {
        f[at] = ((v shr 8) and 0xff).toByte()
        f[at + 1] = (v and 0xff).toByte()
    }

    private fun putBe24(f: ByteArray, at: Int, v: Int) {
        f[at] = ((v shr 16) and 0xff).toByte()
        f[at + 1] = ((v shr 8) and 0xff).toByte()
        f[at + 2] = (v and 0xff).toByte()
    }

    private fun putBe32(f: ByteArray, at: Int, v: Int) {
        f[at] = ((v shr 24) and 0xff).toByte()
        f[at + 1] = ((v shr 16) and 0xff).toByte()
        f[at + 2] = ((v shr 8) and 0xff).toByte()
        f[at + 3] = (v and 0xff).toByte()
    }

    // ------------------------------------------------------------------ RS41

    /** Writes an id/length/data/CRC block, returns the next position. */
    private fun block(f: ByteArray, at: Int, id: Int, data: ByteArray): Int {
        f[at] = id.toByte()
        f[at + 1] = data.size.toByte()
        System.arraycopy(data, 0, f, at + 2, data.size)
        putU16(f, at + 2 + data.size, Rs41.crc16(f, at + 2, data.size))
        return at + 2 + data.size + 2
    }

    /**
     * A standard RS41 frame for this flight point, scrambled as on air.
     *
     * The 48 Reed-Solomon parity bytes stay zero: our decoder ignores them,
     * and this signal has no errors to correct anyway.
     */
    fun rs41Frame(p: Point, frameNo: Int, serial: String = "S1234567",
                  week: Int = 2380, itowMs: Long = 43_200_000L,
                  batteryTenthV: Int = 27): ByteArray {
        val f = ByteArray(Rs41.LEN_STD)
        for (k in Rs41.HEADER.indices) f[k] = Rs41.HEADER[k].toByte()
        f[Rs41.TYPE_AT] = 0x0F               // standard frame type
        var pos = Rs41.BLOCKS_AT

        val status = ByteArray(11)
        putU16(status, 0, frameNo and 0xffff)
        for (k in 0 until 8) status[2 + k] = serial.getOrElse(k) { ' ' }.code.toByte()
        status[10] = batteryTenthV.toByte()
        pos = block(f, pos, Rs41.BLK_STATUS, status)

        val time = ByteArray(6)
        putU16(time, 0, week)
        putI32(time, 2, (itowMs + Math.round(p.tSec * 1000.0)).toInt())
        pos = block(f, pos, Rs41.BLK_GPS_TIME, time)

        val ecef = geodeticToEcef(p.lat, p.lon, p.altM)
        val vel = enuToEcefVel(p.lat, p.lon, p.east, p.north, p.up)
        val gps = ByteArray(21)
        putI32(gps, 0, Math.round(ecef[0] * 100.0).toInt())
        putI32(gps, 4, Math.round(ecef[1] * 100.0).toInt())
        putI32(gps, 8, Math.round(ecef[2] * 100.0).toInt())
        putU16(gps, 12, Math.round(vel[0] * 100.0).toInt() and 0xffff)
        putU16(gps, 14, Math.round(vel[1] * 100.0).toInt() and 0xffff)
        putU16(gps, 16, Math.round(vel[2] * 100.0).toInt() and 0xffff)
        gps[18] = p.sats.toByte()
        block(f, pos, Rs41.BLK_GPS_POS, gps)

        // The frame goes out scrambled; otherwise the decoder won't even find
        // the header.
        Rs41.descramble(f)
        return f
    }

    // ------------------------------------------------------------ Meteomodem

    /** An M20 frame for this flight point. */
    fun m20Frame(p: Point, frameNo: Int, serial: Int = 4321): ByteArray {
        val f = ByteArray(Meteomodem.M20_LEN)
        f[0] = Meteomodem.M20_HEADER[0].toByte()
        f[1] = Meteomodem.M20_HEADER[1].toByte()
        putBe24(f, Meteomodem.M20.ALT, Math.round(p.altM * 100.0).toInt())
        putBe16(f, Meteomodem.M20.VE, Math.round(p.east * 100.0).toInt())
        putBe16(f, Meteomodem.M20.VN, Math.round(p.north * 100.0).toInt())
        putBe16(f, Meteomodem.M20.VU, Math.round(p.up * 100.0).toInt())
        putBe32(f, Meteomodem.M20.LAT, Math.round(p.lat * 1e6).toInt())
        putBe32(f, Meteomodem.M20.LON, Math.round(p.lon * 1e6).toInt())
        putBe16(f, Meteomodem.M20.SERIAL, serial)
        f[Meteomodem.M20.SATS] = p.sats.toByte()
        return f
    }

    /**
     * An M10 frame for this flight point, with offsets and scales taken from a
     * real recording. The checksum must be stamped last, or our own decoder
     * rejects the frame.
     */
    fun m10Frame(p: Point, frameNo: Int, week: Int = 2380,
                 itowMs: Long = 43_200_000L, serial: Int = 10732): ByteArray {
        val f = ByteArray(Meteomodem.M10_LEN)
        for (k in Meteomodem.M10_HEADER.indices) f[k] = Meteomodem.M10_HEADER[k].toByte()
        putBe16(f, Meteomodem.M10.VE, Math.round(p.east * 200.0).toInt())
        putBe16(f, Meteomodem.M10.VN, Math.round(p.north * 200.0).toInt())
        putBe16(f, Meteomodem.M10.VU, Math.round(p.up * 200.0).toInt())
        putBe32(f, Meteomodem.M10.TOW, (itowMs + Math.round(p.tSec * 1000.0)).toInt())
        putBe32(f, Meteomodem.M10.LAT, Math.round(p.lat * Meteomodem.M10_DEG).toInt())
        putBe32(f, Meteomodem.M10.LON, Math.round(p.lon * Meteomodem.M10_DEG).toInt())
        putBe32(f, Meteomodem.M10.ALT, Math.round(p.altM * 1000.0).toInt())
        putBe16(f, Meteomodem.M10.WEEK, week)
        // Serial encoded the way the decoder reads it, for a stable readable name.
        f[Meteomodem.M10.SN] = 0x02
        f[Meteomodem.M10.SN + 1] = 0x14
        f[Meteomodem.M10.SN + 2] = 0x83.toByte()
        val v = (2 shl 13) or (serial and 0x1FFF)
        f[Meteomodem.M10.SN + 3] = (v and 0xff).toByte()
        f[Meteomodem.M10.SN + 4] = ((v shr 8) and 0xff).toByte()
        f[Meteomodem.M10.CNT] = (frameNo and 0xff).toByte()
        Meteomodem.stampCheckM10(f)
        return f
    }

    /** Frame of the requested model for this point. */
    fun frameFor(model: String, p: Point, frameNo: Int): ByteArray = when (model) {
        "M20" -> m20Frame(p, frameNo)
        "M10" -> m10Frame(p, frameNo)
        else -> rs41Frame(p, frameNo)
    }

    // ------------------------------------------------------------ modulation

    /** Bits of a byte sequence, LSB or MSB first. */
    fun bitsOf(bytes: ByteArray, lsbFirst: Boolean): ByteArray {
        val out = ByteArray(bytes.size * 8)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            for (j in 0 until 8) {
                out[i * 8 + j] =
                    (if (lsbFirst) (v shr j) and 1 else (v shr (7 - j)) and 1).toByte()
            }
        }
        return out
    }

    /**
     * M10 biphase-mark encoding, the exact inverse of [Meteomodem.biphase].
     *
     * The level flips at each bit boundary; a 0 keeps both half-bits equal, a
     * 1 makes them differ. This is not Manchester: a Manchester encoder
     * produces a stream the decoder reads as all ones.
     */
    fun biphaseEncode(bits: ByteArray, firstChip: Int = 1): ByteArray {
        val out = ByteArray(bits.size * 2)
        var cur = firstChip and 1
        for (i in bits.indices) {
            val b = bits[i].toInt() and 1
            out[2 * i] = cur.toByte()
            val second = cur xor b
            out[2 * i + 1] = second.toByte()
            cur = second xor 1
        }
        return out
    }

    /**
     * M10 preamble: 1001 repeated, i.e. biphase-coded ones. The first 16
     * half-bits of the sync pattern continue it seamlessly, so sync detection
     * relies on the next 16 not to fire inside the preamble.
     */
    private fun m10Preamble(n: Int): ByteArray {
        val out = ByteArray(n)
        for (k in 0 until n) out[k] = (if ((k and 3) == 0 || (k and 3) == 3) 1 else 0).toByte()
        return out
    }

    /**
     * Symbols to transmit for one frame, preamble included.
     *
     * The preamble is not decoration: the demodulator's slow zero tracker and
     * edge-driven clock need ~100 alternations to settle, or the first bytes
     * come out wrong and the header is missed. Real sondes send one for the
     * same reason.
     */
    fun chipsFor(model: String, p: Point, frameNo: Int, preamble: Int = 128): ByteArray {
        val frame = frameFor(model, p, frameNo)
        if (model == "M10") {
            // The M10 aligns on half-bits: send the real sync pattern. The
            // frame starts after its 31st half-bit; the 32nd is already body.
            val head = m10Preamble(M10_PREAMBLE)
            val sync = Meteomodem.M10_SYNC
            val cut = Meteomodem.M10_SYNC_TO_FRAME
            val body = biphaseEncode(bitsOf(frame, lsbFirst = false),
                sync[cut].toInt() and 1)
            val out = ByteArray(head.size + cut + body.size)
            System.arraycopy(head, 0, out, 0, head.size)
            for (k in 0 until cut) out[head.size + k] = sync[k]
            System.arraycopy(body, 0, out, head.size + cut, body.size)
            return out
        }
        val body = when (model) {
            "M20" -> bitsOf(frame, lsbFirst = false)
            else -> bitsOf(frame, lsbFirst = true)
        }
        val out = ByteArray(preamble + body.size)
        for (k in 0 until preamble) out[k] = (k and 1).toByte()
        System.arraycopy(body, 0, out, preamble, body.size)
        return out
    }

    /** M10 preamble length in half-bits, as a real sonde sends. */
    const val M10_PREAMBLE = 400

    /** Model symbol rate, symbols/s. */
    fun chipRate(model: String): Double = SondeModel.byId(model).let {
        if (it.chipRate > 0.0) it.chipRate else Rs41.BAUD
    }

    /**
     * The test signal, generated in chunks.
     *
     * Five minutes is over 25 MB of PCM, so it is generated on the fly, as for
     * the SSTV test signal. Each second carries one frame followed by filler
     * alternations, like a real sonde whose carrier never stops between frames.
     */
    class Source(
        /** Model: "RS41", "M20" or "M10". */
        val model: String,
        /** Flight start point. */
        lat: Double, lon: Double,
        /** Duration, seconds. */
        val seconds: Int,
        val sampleRate: Int = RATE,
        val amplitude: Int = AMPLITUDE,
        /**
         * Flight step in real seconds; zero for the compressed demo flight.
         * The test bench uses one second so the clock agrees with the position.
         */
        val stepSec: Double = 0.0,
        /**
         * Realistic ambience: noise and fading, like a distant sonde. Off by
         * default: to test whether a cable works, only a clean signal gives an
         * unambiguous answer. Ambience is for club demos.
         */
        val ambience: Boolean = false
    ) {
        private val points = flight(lat, lon, maxOf(1, seconds), stepSec = stepSec)
        private val rate = chipRate(model)

        /** Samples in a one-second slot. */
        private val perSlot = sampleRate

        /** Symbols of the current slot, padded with alternations. */
        private var slot = ByteArray(0)
        private var slotIndex = -1
        private var sampleInSlot = 0

        /** Total length, samples. */
        val totalSamples: Int = points.size * perSlot

        private var produced = 0

        /** Progress, 0 to 1. */
        val progress: Float
            get() = if (totalSamples == 0) 1f else produced.toFloat() / totalSamples

        private fun buildSlot(i: Int) {
            val body = chipsFor(model, points[i], i + 1)
            // Symbols in one second: frame first, alternations fill the rest.
            val n = Math.round(rate).toInt()
            val out = ByteArray(maxOf(n, body.size))
            System.arraycopy(body, 0, out, 0, body.size)
            if (model == "M10") {
                // Fill with the preamble pattern, as the sonde does between
                // frames.
                val fill = m10Preamble(out.size - body.size)
                System.arraycopy(fill, 0, out, body.size, fill.size)
            } else {
                for (k in body.size until out.size) out[k] = ((k - body.size) and 1).toByte()
            }
            slot = out
            slotIndex = i
            sampleInSlot = 0
        }

        /** Fills [chunk]; returns samples written, or 0 when finished. */
        fun read(chunk: ShortArray): Int {
            if (produced >= totalSamples) return 0
            var n = 0
            while (n < chunk.size && produced < totalSamples) {
                val i = produced / perSlot
                if (i != slotIndex) buildSlot(i)
                val posInSlot = produced % perSlot
                // Oversample: look at the symbol at OVERSAMPLE instants, filter,
                // keep the last output. This rounds the edges.
                var v = 0.0
                for (sub in 0 until OVERSAMPLE) {
                    val fine = posInSlot.toLong() * OVERSAMPLE + sub
                    val idx = (fine * slot.size / (perSlot.toLong() * OVERSAMPLE)).toInt()
                    val b = slot[idx.coerceIn(0, slot.size - 1)].toInt()
                    val raw = if (b != 0) amplitude.toDouble() else -amplitude.toDouble()
                    lp1 += alpha * (raw - lp1)
                    lp2 += alpha * (lp1 - lp2)
                    v = lp2
                }
                if (ambience) {
                    // Fading first, then noise: receiver noise doesn't fade
                    // with the signal.
                    val t = produced.toDouble() / sampleRate
                    v *= FADE_FLOOR + (1.0 - FADE_FLOOR) *
                        (0.5 + 0.5 * kotlin.math.cos(2.0 * Math.PI * FADE_HZ * t))
                    v += rnd.nextGaussian() * amplitude * NOISE
                }
                chunk[n++] = v.coerceIn(-32_000.0, 32_000.0)
                    .let { Math.round(it).toInt().toShort() }
                produced++
            }
            return n
        }

        /** The two cascaded first-order low-pass stages. */
        private var lp1 = 0.0
        private var lp2 = 0.0

        /**
         * Stage coefficient, tuned to 0.9x the chip rate.
         *
         * Looks like a regression but isn't: the peak no longer quite reaches
         * the amplitude setting; that's the filter working. A test requires the
         * peak to stay *below* the setting (or the WAV clips) and *above 75 %*
         * (or the signal has melted).
         */
        private val alpha: Double = run {
            val fc = 0.9 * rate
            val fs = sampleRate.toDouble() * OVERSAMPLE
            (1.0 - Math.exp(-2.0 * Math.PI * fc / fs)).coerceIn(1e-4, 0.999)
        }

        /** Fixed seed: the signal must be reproducible, ambience included. */
        private val rnd = java.util.Random(20_240_907L)
    }

    /**
     * Whole signal at once, for tests and the demo. Short durations only: one
     * second is already 88 KB.
     */
    fun render(model: String, lat: Double, lon: Double, seconds: Int,
               ambience: Boolean = false): ShortArray {
        val src = Source(model, lat, lon, seconds, ambience = ambience)
        val out = ShortArray(src.totalSamples)
        val chunk = ShortArray(8192)
        var at = 0
        while (at < out.size) {
            val n = src.read(chunk)
            if (n <= 0) break
            System.arraycopy(chunk, 0, out, at, minOf(n, out.size - at))
            at += n
        }
        return out
    }
}
