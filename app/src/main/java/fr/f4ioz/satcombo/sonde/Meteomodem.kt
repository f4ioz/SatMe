/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

/**
 * Meteomodem M10 and M20 decoding, the sondes Météo-France launches.
 *
 * Brest-Guipavas launches M20s on 404.000 MHz twice a day: the sonde most
 * likely to land in Finistère.
 *
 * What it took to decode the M10:
 *
 * 1. It is not Manchester. It is what rs1729 calls `psk_bpm`, biphase mark:
 *    two **identical** chips are a 0, two **different** chips a 1, and the
 *    level flips at every bit boundary. A Manchester decoder does the exact
 *    opposite, picking the phase that minimises flat pairs, while flat pairs
 *    are legitimate data here. A clean signal then gives garbage that looks
 *    like a good decode (4 % flat pairs: reassuring and wrong).
 *
 * 2. Alignment is on chips, not bytes. The M10 sends 400 alternating chips,
 *    then a 32-chip sync pattern, and the frame starts 31 chips after the
 *    pattern start, not 32: the last pattern chip is already the first
 *    half-bit of the frame. One chip off and everything reads `FF`.
 *
 * 3. It does not transmit continuously: on-off keyed, 209 ms of carrier per
 *    second. A dotted line on the waterfall is normal.
 *
 * 4. The M10 does carry a check: two bytes at 0x63, Meteomodem's algorithm.
 *    So an M10 frame is proven, not just plausible. 20/20 frames pass on the
 *    auto_rx reference recording.
 *
 * The M20 is plain 2-FSK at 9600 bit/s with no check we can reproduce:
 * plausibility is its only guard.
 */
object Meteomodem {

    /**
     * M10 on-air chip rate, chips/s.
     *
     * Trap: 9616 is the chip rate, not the bit rate. Two chips per bit, so the
     * payload rate is 4808 bit/s. Taking 9616 as the bit rate runs the
     * demodulator at 19232 chips/s, reading each chip twice. Measured on the
     * auto_rx reference recording: a transition line at 9614.7 Hz, 22 dB above
     * the floor, the bit line at 4807.5 Hz; 19228.5 Hz is only the 2nd harmonic.
     */
    const val M10_CHIP_RATE = 9616.0

    /** M10 payload bit rate: half the chip rate (biphase). */
    const val M10_BAUD = M10_CHIP_RATE / 2.0

    /** M20 bit rate, bit/s. */
    const val M20_BAUD = 9600.0

    /** Recommended filter width for both, Hz. */
    const val BANDWIDTH_HZ = 22_000

    /**
     * M10 frame length, bytes.
     *
     * Misleading: the first byte is 0x64 (100), the declared length, but the
     * frame has one more byte; the two check bytes sit at 0x63 and 0x64.
     */
    const val M10_LEN = 101

    /** M20 frame length, bytes. */
    const val M20_LEN = 0x45

    /** M10 frame header: declared length, then type. */
    val M10_HEADER = intArrayOf(0x64, 0x9F, 0x20)

    /**
     * M10 sync pattern, in chips.
     *
     * Searched in the half-bit stream, in both polarities: depending on the FM
     * demodulation sense the stream may come out inverted. That doesn't affect
     * biphase decoding (identical pairs stay identical) but it does affect
     * pattern matching.
     */
    val M10_SYNC = byteArrayOf(
        1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1,
        0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1)

    /**
     * Chips from pattern start to the first frame half-bit. 31, not 32: the
     * last pattern chip already belongs to the frame.
     */
    const val M10_SYNC_TO_FRAME = 31

    /**
     * M10 coordinate scale: a full turn is 2^32.
     *
     * rs1729 divides by 0xB60B60, the integer rounding. Harmless in the field,
     * but enough for a 90° latitude to come out as 90.000004 and be rejected by
     * the plausibility check. So we keep the exact value.
     */
    const val M10_DEG = 4_294_967_296.0 / 360.0

    /**
     * The five serial bytes of a real M10 from the auto_rx reference recording,
     * read as "803-2-10732". Used by the test generator and the tests.
     */
    val M10_SERIAL_DEMO = byteArrayOf(0x02, 0x14, 0x83.toByte(), 0xDC.toByte(), 0x22)

    /** M20 frame header. */
    val M20_HEADER = intArrayOf(0x45, 0x20)

    private fun b(f: ByteArray, i: Int) = f[i].toInt() and 0xff

    /** Signed 32-bit big-endian integer (Meteomodem is big-endian). */
    fun be32(f: ByteArray, i: Int): Int =
        (b(f, i) shl 24) or (b(f, i + 1) shl 16) or (b(f, i + 2) shl 8) or b(f, i + 3)

    /** Signed 16-bit big-endian. */
    fun be16(f: ByteArray, i: Int): Int {
        val v = (b(f, i) shl 8) or b(f, i + 1)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    /** Unsigned 16-bit big-endian. */
    fun beu16(f: ByteArray, i: Int): Int = (b(f, i) shl 8) or b(f, i + 1)

    /**
     * M10 biphase-mark decoding: identical chips = 0, different = 1.
     *
     * Nothing is rejected, on purpose: unlike Manchester no chip combination is
     * illegal, so counting flat pairs cannot tell the right phase. The sync
     * pattern picks the phase, the checksum validates the frame.
     *
     * Returns the number of bits written.
     */
    fun biphase(chips: ByteArray, from: Int, count: Int, out: ByteArray): Int {
        var n = 0
        var k = from
        while (k + 1 < count && n < out.size) {
            out[n++] = if (chips[k] == chips[k + 1]) 0 else 1
            k += 2
        }
        return n
    }

    /**
     * Finds the M10 sync pattern in a chip stream, both polarities in one pass.
     *
     * [maxErrors] wrong chips are tolerated, enough for a soft edge without
     * letting noise in: over 32 chips, 2 errors give one false alarm per ~4
     * million positions, and the checksum catches the rest. Returns the index
     * of the first pattern chip, or -1.
     */
    fun findSync(chips: ByteArray, count: Int, from: Int, maxErrors: Int = 2): Int {
        val n = M10_SYNC.size
        var i = if (from < 0) 0 else from
        val last = count - n
        while (i <= last) {
            var wrong = 0
            var right = 0
            for (k in 0 until n) {
                if ((chips[i + k].toInt() and 1) != M10_SYNC[k].toInt()) wrong++ else right++
                if (wrong > maxErrors && right > maxErrors) break
            }
            if (wrong <= maxErrors || right <= maxErrors) return i
            i++
        }
        return -1
    }

    /**
     * Builds an M10 frame into [out] from a chip stream aligned on its pattern.
     * False if the stream is too short or the checksum fails.
     */
    fun frameFromChips(chips: ByteArray, syncAt: Int, out: ByteArray): Boolean {
        val start = syncAt + M10_SYNC_TO_FRAME
        if (out.size < M10_LEN) return false
        if (start + M10_LEN * 16 > chips.size) return false
        var p = start
        for (i in 0 until M10_LEN) {
            var v = 0
            for (j in 0 until 8) {
                v = (v shl 1) or (if (chips[p] == chips[p + 1]) 0 else 1)
                p += 2
            }
            out[i] = v.toByte()
        }
        return checkOkM10(out)
    }

    // ------------------------------------------------------ checksum

    /**
     * Adds one byte to the M10 checksum. Direct port of rs1729's
     * `update_checkM10`: neither a known CRC nor a plain sum, nothing to
     * understand, it must match bit for bit ([MeteomodemM10Test] checks it).
     */
    fun updateCheckM10(c: Int, byteIn: Int): Int {
        val c1 = c and 0xFF
        var b = ((byteIn shr 1) or ((byteIn and 1) shl 7)) and 0xFF
        b = b xor ((b shr 2) and 0xFF)
        val t6 = (c and 1) xor ((c shr 2) and 1) xor ((c shr 4) and 1)
        val t7 = ((c shr 1) and 1) xor ((c shr 3) and 1) xor ((c shr 5) and 1)
        val t = (c and 0x3F) or (t6 shl 6) or (t7 shl 7)
        var sh = (c shr 7) and 0xFF
        sh = sh xor ((sh shr 2) and 0xFF)
        val c0 = (b xor t xor sh) and 0xFF
        return ((c1 shl 8) or c0) and 0xFFFF
    }

    /** Checksum of the first [n] bytes of an M10 frame. */
    fun checkM10(f: ByteArray, n: Int): Int {
        var c = 0
        for (i in 0 until n) c = updateCheckM10(c, f[i].toInt() and 0xff)
        return c and 0xFFFF
    }

    fun checkOkM10(f: ByteArray): Boolean =
        f.size >= M10_LEN &&
            checkM10(f, M10.CHECK) == ((b(f, M10.CHECK) shl 8) or b(f, M10.CHECK + 1))

    /** Writes the correct checksum into a synthetic frame (test generator, tests). */
    fun stampCheckM10(f: ByteArray) {
        val c = checkM10(f, M10.CHECK)
        f[M10.CHECK] = ((c shr 8) and 0xff).toByte()
        f[M10.CHECK + 1] = (c and 0xff).toByte()
    }

    /**
     * Printed serial, rebuilt from the five bytes at 0x5D. rs1729's format
     * without the spaces, which don't sit well in a log file.
     */
    fun serialM10(f: ByteArray): String {
        if (f.size < M10.SN + 5) return ""
        val s2 = b(f, M10.SN + 2)
        val v = b(f, M10.SN + 3) or (b(f, M10.SN + 4) shl 8)
        return "%X%02d-%X-%d%04d".format(
            (s2 shr 4) and 0xF, s2 and 0xF,
            b(f, M10.SN) and 0xF, (v shr 13) and 0x7, v and 0x1FFF)
    }

    /**
     * M20 field offsets. Kept in one table because they depend on the firmware
     * version: when Meteomodem changes something, only this table needs fixing.
     */
    object M20 {
        const val ALT = 0x08          // altitude, 3 bytes, cm
        const val VE = 0x0C           // east velocity, 2 bytes, 0.01 m/s
        const val VN = 0x0E           // north velocity
        const val VU = 0x10           // vertical velocity
        const val LAT = 0x1C          // latitude, 4 bytes, 1e-6 degree
        const val LON = 0x20          // longitude
        const val SERIAL = 0x2C       // serial, 2 bytes
        const val SATS = 0x30
    }

    /** M10 field offsets, same reasoning as for the M20. */
    object M10 {
        const val VE = 0x04           // east velocity, 2 bytes, 1/200 m/s
        const val VN = 0x06           // north velocity
        const val VU = 0x08           // vertical velocity
        const val TOW = 0x0A          // GPS time of week, 4 bytes, ms
        const val LAT = 0x0E          // latitude, 4 bytes, full turn = 2^32
        const val LON = 0x12          // longitude, same scale
        const val ALT = 0x16          // altitude, 4 bytes, mm
        const val WEEK = 0x20         // GPS week, 2 bytes
        const val SN = 0x5D           // serial, 5 bytes
        const val CNT = 0x62          // frame counter, 1 byte
        const val CHECK = 0x63        // checksum, 2 bytes
    }

    /** Decodes a header-aligned M20 frame. Null if the numbers don't make sense. */
    fun parseM20(f: ByteArray, freqHz: Long = 0L, nowMs: Long = 0L): SondeFrame? {
        if (f.size < M20_LEN) return null
        val altCm = (b(f, M20.ALT) shl 16) or (b(f, M20.ALT + 1) shl 8) or b(f, M20.ALT + 2)
        val alt = altCm / 100.0
        val ve = be16(f, M20.VE) / 100.0
        val vn = be16(f, M20.VN) / 100.0
        val vu = be16(f, M20.VU) / 100.0
        val lat = be32(f, M20.LAT) * 1e-6
        val lon = be32(f, M20.LON) * 1e-6
        val enu = Geo.Enu(ve, vn, vu)
        val sats = b(f, M20.SATS) and 0x3F
        val serial = "%d".format(beu16(f, M20.SERIAL))
        val out = SondeFrame(
            type = "M20", serial = serial,
            lat = lat, lon = lon, altM = alt,
            speedMps = enu.groundMps, headingDeg = enu.headingDeg, climbMps = vu,
            sats = sats, freqHz = freqHz, heardAtMs = nowMs)
        return if (out.plausible) out else null
    }

    /**
     * Decodes a header-aligned M10 frame. The checksum is mandatory: a frame
     * failing it is lucky noise.
     */
    fun parseM10(f: ByteArray, freqHz: Long = 0L, nowMs: Long = 0L): SondeFrame? {
        if (f.size < M10_LEN) return null
        if (!checkOkM10(f)) return null
        val lat = (be32(f, M10.LAT) / M10_DEG).coerceIn(-90.0, 90.0)
        val lon = (be32(f, M10.LON) / M10_DEG).coerceIn(-180.0, 180.0)
        val alt = be32(f, M10.ALT) / 1000.0
        val ve = be16(f, M10.VE) / 200.0
        val vn = be16(f, M10.VN) / 200.0
        val vu = be16(f, M10.VU) / 200.0
        val enu = Geo.Enu(ve, vn, vu)
        val tow = (be32(f, M10.TOW).toLong() and 0xFFFF_FFFFL)
        val week = beu16(f, M10.WEEK)
        val time = if (week in 1..4095) Geo.gpsToUnixMs(week, tow) else 0L
        val out = SondeFrame(
            type = "M10",
            serial = serialM10(f),
            frameNo = b(f, M10.CNT),
            timeUtcMs = time,
            lat = lat, lon = lon, altM = alt,
            speedMps = enu.groundMps, headingDeg = enu.headingDeg, climbMps = vu,
            sats = 0, satsUnknown = true, freqHz = freqHz, heardAtMs = nowMs)
        return if (out.plausible) out else null
    }

    /** Finds a given header in a byte buffer. */
    fun find(buf: ByteArray, header: IntArray, from: Int, to: Int): Int {
        val last = to - header.size
        var i = from
        while (i <= last) {
            var ok = true
            for (k in header.indices) {
                if ((buf[i + k].toInt() and 0xff) != header[k]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    /**
     * Finds and decodes the first M20 frame in a buffer.
     *
     * Separate from the M10 because they demodulate differently (plain 2-FSK
     * vs one bit per half-bit pair). One shared demodulator sacrificed one of
     * them, and it was the M20, the one Météo-France launches.
     */
    fun scanM20(buf: ByteArray, from: Int, to: Int,
                freqHz: Long = 0L, nowMs: Long = 0L): Rs41.Hit? {
        var at = from
        while (at < to) {
            val h = find(buf, M20_HEADER, at, to)
            if (h < 0) return null
            if (h + M20_LEN > to) return null
            val parsed = parseM20(buf.copyOfRange(h, h + M20_LEN), freqHz, nowMs)
            if (parsed != null) return Rs41.Hit(parsed, h + M20_LEN)
            at = h + 1
        }
        return null
    }

    /** Finds and decodes the first M10 frame in a buffer. */
    fun scanM10(buf: ByteArray, from: Int, to: Int,
                freqHz: Long = 0L, nowMs: Long = 0L): Rs41.Hit? {
        var at = from
        while (at < to) {
            val h = find(buf, M10_HEADER, at, to)
            if (h < 0) return null
            if (h + M10_LEN > to) return null
            val parsed = parseM10(buf.copyOfRange(h, h + M10_LEN), freqHz, nowMs)
            if (parsed != null) return Rs41.Hit(parsed, h + M10_LEN)
            at = h + 1
        }
        return null
    }

    /**
     * Finds and decodes the first Meteomodem frame (M20 or M10, whichever
     * header comes first). Returns the frame and the resume index.
     */
    fun scan(buf: ByteArray, from: Int, to: Int, freqHz: Long = 0L, nowMs: Long = 0L): Rs41.Hit? {
        var at = from
        while (at < to) {
            val h20 = find(buf, M20_HEADER, at, to)
            val h10 = find(buf, M10_HEADER, at, to)
            val first = when {
                h20 < 0 && h10 < 0 -> return null
                h20 < 0 -> h10
                h10 < 0 -> h20
                else -> minOf(h20, h10)
            }
            val isM20 = first == h20 && h20 >= 0
            val len = if (isM20) M20_LEN else M10_LEN
            if (first + len > to) return null
            val f = buf.copyOfRange(first, first + len)
            val parsed = if (isM20) parseM20(f, freqHz, nowMs) else parseM10(f, freqHz, nowMs)
            if (parsed != null) return Rs41.Hit(parsed, first + len)
            at = first + 1
        }
        return null
    }
}
