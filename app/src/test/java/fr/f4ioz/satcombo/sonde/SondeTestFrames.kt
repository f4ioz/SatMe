/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic frames and signals for decoder tests.
 *
 * Stands in for the radiosonde: builds a well-formed frame, scrambles it as
 * the sonde would, and square-modulates it as an FM discriminator would
 * output it. The decoder is then tested end to end — bits, byte alignment,
 * descrambling, CRC, coordinates — on known values: the only honest way to
 * check a displayed position is the one transmitted.
 */
internal object SondeTestFrames {

    // ------------------------------------------------------------ geodesy

    /** Geodetic to ECEF, the inverse of [Geo.ecefToGeodetic]. */
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

    /** Local east/north/up velocity to ECEF, the inverse of [Geo.ecefVelToEnu]. */
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

    // ------------------------------------------------------------- writers

    fun putU16(f: ByteArray, at: Int, v: Int) {
        f[at] = (v and 0xff).toByte()
        f[at + 1] = ((v shr 8) and 0xff).toByte()
    }

    fun putI32(f: ByteArray, at: Int, v: Int) {
        f[at] = (v and 0xff).toByte()
        f[at + 1] = ((v shr 8) and 0xff).toByte()
        f[at + 2] = ((v shr 16) and 0xff).toByte()
        f[at + 3] = ((v shr 24) and 0xff).toByte()
    }

    fun putBe16(f: ByteArray, at: Int, v: Int) {
        f[at] = ((v shr 8) and 0xff).toByte()
        f[at + 1] = (v and 0xff).toByte()
    }

    fun putBe24(f: ByteArray, at: Int, v: Int) {
        f[at] = ((v shr 16) and 0xff).toByte()
        f[at + 1] = ((v shr 8) and 0xff).toByte()
        f[at + 2] = (v and 0xff).toByte()
    }

    fun putBe32(f: ByteArray, at: Int, v: Int) {
        f[at] = ((v shr 24) and 0xff).toByte()
        f[at + 1] = ((v shr 16) and 0xff).toByte()
        f[at + 2] = ((v shr 8) and 0xff).toByte()
        f[at + 3] = (v and 0xff).toByte()
    }

    // ------------------------------------------------------------ RS41

    /** Writes an id / length / data / CRC block; returns the next position. */
    private fun block(f: ByteArray, at: Int, id: Int, data: ByteArray): Int {
        f[at] = id.toByte()
        f[at + 1] = data.size.toByte()
        System.arraycopy(data, 0, f, at + 2, data.size)
        putU16(f, at + 2 + data.size, Rs41.crc16(f, at + 2, data.size))
        return at + 2 + data.size + 2
    }

    /**
     * A standard RS41 frame, descrambled, carrying the given values. Pass it
     * to [Rs41.descramble] to get the frame as sent over the air.
     */
    fun rs41(lat: Double, lon: Double, altM: Double,
             east: Double, north: Double, up: Double,
             sats: Int = 9, serial: String = "P1234567",
             frameNo: Int = 4242, week: Int = 2300,
             itowMs: Long = 43_200_000L, batteryTenthV: Int = 27): ByteArray {

        val f = ByteArray(Rs41.LEN_STD)
        for (k in Rs41.HEADER.indices) f[k] = Rs41.HEADER[k].toByte()
        f[Rs41.TYPE_AT] = 0x0F            // type: standard frame

        var pos = Rs41.BLOCKS_AT

        val status = ByteArray(11)
        putU16(status, 0, frameNo)
        for (k in 0 until 8) status[2 + k] = serial.getOrElse(k) { ' ' }.code.toByte()
        status[10] = batteryTenthV.toByte()
        pos = block(f, pos, Rs41.BLK_STATUS, status)

        val time = ByteArray(6)
        putU16(time, 0, week)
        putI32(time, 2, itowMs.toInt())
        pos = block(f, pos, Rs41.BLK_GPS_TIME, time)

        val ecef = geodeticToEcef(lat, lon, altM)
        val vel = enuToEcefVel(lat, lon, east, north, up)
        val gps = ByteArray(21)
        putI32(gps, 0, Math.round(ecef[0] * 100.0).toInt())
        putI32(gps, 4, Math.round(ecef[1] * 100.0).toInt())
        putI32(gps, 8, Math.round(ecef[2] * 100.0).toInt())
        putU16(gps, 12, Math.round(vel[0] * 100.0).toInt() and 0xffff)
        putU16(gps, 14, Math.round(vel[1] * 100.0).toInt() and 0xffff)
        putU16(gps, 16, Math.round(vel[2] * 100.0).toInt() and 0xffff)
        gps[18] = sats.toByte()
        block(f, pos, Rs41.BLK_GPS_POS, gps)

        return f
    }

    // ------------------------------------------------------------ Meteomodem

    /** An M20 frame carrying the given values. */
    fun m20(lat: Double, lon: Double, altM: Double,
            east: Double, north: Double, up: Double,
            sats: Int = 9, serial: Int = 1234): ByteArray {
        val f = ByteArray(Meteomodem.M20_LEN)
        f[0] = Meteomodem.M20_HEADER[0].toByte()
        f[1] = Meteomodem.M20_HEADER[1].toByte()
        putBe24(f, Meteomodem.M20.ALT, Math.round(altM * 100.0).toInt())
        putBe16(f, Meteomodem.M20.VE, Math.round(east * 100.0).toInt())
        putBe16(f, Meteomodem.M20.VN, Math.round(north * 100.0).toInt())
        putBe16(f, Meteomodem.M20.VU, Math.round(up * 100.0).toInt())
        putBe32(f, Meteomodem.M20.LAT, Math.round(lat * 1e6).toInt())
        putBe32(f, Meteomodem.M20.LON, Math.round(lon * 1e6).toInt())
        putBe16(f, Meteomodem.M20.SERIAL, serial)
        f[Meteomodem.M20.SATS] = sats.toByte()
        return f
    }

    /**
     * An M10 frame carrying the given values, checksum included. The decoder
     * requires the checksum, so a test frame without it would be rejected and
     * prove nothing.
     */
    fun m10(lat: Double, lon: Double, altM: Double,
            east: Double, north: Double, up: Double,
            count: Int = 157, week: Int = 2300,
            itowMs: Long = 43_200_000L): ByteArray {
        val f = ByteArray(Meteomodem.M10_LEN)
        for (k in Meteomodem.M10_HEADER.indices) f[k] = Meteomodem.M10_HEADER[k].toByte()
        putBe16(f, Meteomodem.M10.VE, Math.round(east * 200.0).toInt())
        putBe16(f, Meteomodem.M10.VN, Math.round(north * 200.0).toInt())
        putBe16(f, Meteomodem.M10.VU, Math.round(up * 200.0).toInt())
        putBe32(f, Meteomodem.M10.TOW, itowMs.toInt())
        putBe32(f, Meteomodem.M10.LAT, Math.round(lat * Meteomodem.M10_DEG).toInt())
        putBe32(f, Meteomodem.M10.LON, Math.round(lon * Meteomodem.M10_DEG).toInt())
        putBe32(f, Meteomodem.M10.ALT, Math.round(altM * 1000.0).toInt())
        putBe16(f, Meteomodem.M10.WEEK, week)
        for (k in Meteomodem.M10_SERIAL_DEMO.indices) {
            f[Meteomodem.M10.SN + k] = Meteomodem.M10_SERIAL_DEMO[k]
        }
        f[Meteomodem.M10.CNT] = (count and 0xff).toByte()
        Meteomodem.stampCheckM10(f)
        return f
    }

    // ------------------------------------------------------------ modulation

    /** Bits of a byte sequence, LSB or MSB first. */
    fun bitsOf(bytes: ByteArray, lsbFirst: Boolean): ByteArray {
        val out = ByteArray(bytes.size * 8)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            for (j in 0 until 8) {
                val bit = if (lsbFirst) (v shr j) and 1 else (v shr (7 - j)) and 1
                out[i * 8 + j] = bit.toByte()
            }
        }
        return out
    }

    /**
     * The square wave an FM discriminator would output for these bits.
     *
     * No noise, no drift: the point is that in the ideal case the decoder
     * recovers exactly what was sent. A decoder failing here has no chance
     * on air.
     */
    // A tail is appended too: the demodulator's matched filter delays the
    // signal by half a symbol, so a buffer ending exactly on the last data
    // symbol never lets it out. Real recordings never stop there.
    fun modulate(bits: ByteArray, sampleRate: Double, baud: Double,
                 amplitude: Int = 10_000, leadingBits: Int = 64,
                 trailingBits: Int = 16): ShortArray {
        val all = ByteArray(leadingBits + bits.size + trailingBits)
        for (k in 0 until leadingBits) all[k] = (k and 1).toByte()
        System.arraycopy(bits, 0, all, leadingBits, bits.size)
        for (k in 0 until trailingBits)
            all[leadingBits + bits.size + k] = (k and 1).toByte()
        val n = ceil(all.size * sampleRate / baud).toInt()
        val pcm = ShortArray(n)
        for (k in 0 until n) {
            val idx = (k * baud / sampleRate).toInt()
            val b = if (idx < all.size) all[idx].toInt() else 0
            pcm[k] = (if (b != 0) amplitude else -amplitude).toShort()
        }
        return pcm
    }
}
