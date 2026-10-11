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
 * Vaisala RS41 frame decoding, the most common sonde in Europe.
 *
 * GFSK at 4800 bit/s, little-endian. The whole frame, header included, is
 * XORed with a repeating 64-byte mask. So we search for the header as it
 * appears on air (a constant) instead of descrambling everything first.
 *
 * Once descrambled, the frame is a series of blocks, each with its own CRC.
 * The 48 Reed-Solomon parity bytes are deliberately ignored: a full RS(255,231)
 * decoder only helps on half-lost frames, while a per-block CRC is enough to
 * never show a wrong position. Better to skip a frame than lie about a
 * coordinate.
 */
object Rs41 {

    /** Bit rate, bit/s. */
    const val BAUD = 4800.0

    /** Recommended demodulation filter width, Hz. */
    const val BANDWIDTH_HZ = 15_000

    /** Descrambling mask, repeated every 64 bytes. */
    val MASK = intArrayOf(
        0x96, 0x83, 0x3E, 0x51, 0xB1, 0x49, 0x08, 0x98,
        0x32, 0x05, 0x59, 0x0E, 0xF9, 0x44, 0xC6, 0x26,
        0x21, 0x60, 0xC2, 0xEA, 0x79, 0x5D, 0x6D, 0xA1,
        0x54, 0x69, 0x47, 0x0C, 0xDC, 0xE8, 0x5C, 0xF1,
        0xF7, 0x76, 0x82, 0x7F, 0x07, 0x99, 0xA2, 0x2C,
        0x93, 0x7C, 0x30, 0x63, 0xF5, 0x10, 0x2E, 0x61,
        0xD0, 0xBC, 0xB4, 0xB6, 0x06, 0xAA, 0xF4, 0x23,
        0x78, 0x6E, 0x3B, 0xAE, 0xBF, 0x7B, 0x4C, 0xC1)

    /**
     * Header as it goes over the air.
     *
     * Trap: these two constants were once swapped. The test generator and the
     * decoder agreed on the wrong convention, so every test passed and no real
     * sonde was ever recognised. auto_rx reference recordings settled it: 120
     * perfect headers in 120 s with this one, none with the other.
     */
    val HEADER_RAW = intArrayOf(0x10, 0xB6, 0xCA, 0x11, 0x22, 0x96, 0x12, 0xF8)

    /** Same header after descrambling. */
    val HEADER = IntArray(HEADER_RAW.size) { HEADER_RAW[it] xor MASK[it] }

    /** Standard frame length, bytes (type 0x0F). */
    const val LEN_STD = 320

    /** Extended frame length, bytes (type 0xF0). */
    const val LEN_EXT = 518

    /**
     * Type byte, just before the blocks.
     *
     * It comes after the 48 parity bytes, not right after the header. Reading
     * it at offset 8 (inside the parity) gives a random value and rejects every
     * real sonde.
     */
    const val TYPE_AT = 0x38

    /** First byte of the data blocks. */
    const val BLOCKS_AT = 0x39

    /** Status block: frame number, serial, battery voltage. */
    const val BLK_STATUS = 0x79

    /** GPS time block: week and time of week. */
    const val BLK_GPS_TIME = 0x7A

    /** GPS position block: ECEF position and velocity. */
    const val BLK_GPS_POS = 0x7B

    private fun b(f: ByteArray, i: Int) = f[i].toInt() and 0xff

    // Little-endian integer readers.
    fun u16(f: ByteArray, i: Int) = b(f, i) or (b(f, i + 1) shl 8)

    fun i16(f: ByteArray, i: Int): Int {
        val v = u16(f, i)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    fun i32(f: ByteArray, i: Int): Int =
        b(f, i) or (b(f, i + 1) shl 8) or (b(f, i + 2) shl 16) or (b(f, i + 3) shl 24)

    fun u32(f: ByteArray, i: Int): Long = i32(f, i).toLong() and 0xFFFF_FFFFL

    /**
     * CRC-16-CCITT: poly 0x1021, init 0xFFFF, no final XOR. Stored
     * little-endian at the end of each RS41 block.
     */
    fun crc16(data: ByteArray, off: Int, len: Int): Int {
        var crc = 0xFFFF
        for (k in off until off + len) {
            crc = crc xor ((data[k].toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                      else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** Applies (or removes, same operation) the scrambling mask. */
    fun descramble(frame: ByteArray) {
        for (k in frame.indices) {
            frame[k] = (frame[k].toInt() xor MASK[k % MASK.size]).toByte()
        }
    }

    /** Finds the on-air header in byte-aligned data. Returns the frame start, or -1. */
    fun findHeader(buf: ByteArray, from: Int = 0, to: Int = buf.size): Int {
        val last = to - HEADER_RAW.size
        var i = from
        while (i <= last) {
            var ok = true
            for (k in HEADER_RAW.indices) {
                if ((buf[i + k].toInt() and 0xff) != HEADER_RAW[k]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    /** Length for this type byte, or 0 if unknown. */
    fun frameLength(typeByte: Int): Int = when (typeByte and 0xff) {
        0x0F -> LEN_STD
        0xF0 -> LEN_EXT
        else -> 0
    }

    data class Block(val id: Int, val at: Int, val len: Int, val crcOk: Boolean)

    /**
     * Walks the blocks of a descrambled frame, stopping as soon as a declared
     * length overruns the frame: on a damaged frame a bogus length byte would
     * send the reader anywhere.
     */
    fun blocks(frame: ByteArray): List<Block> {
        val out = ArrayList<Block>(8)
        var pos = BLOCKS_AT
        while (pos + 4 <= frame.size) {
            val id = b(frame, pos)
            val len = b(frame, pos + 1)
            if (id == 0 && len == 0) break
            val data = pos + 2
            val crcAt = data + len
            if (crcAt + 2 > frame.size) break
            val want = u16(frame, crcAt)
            val got = crc16(frame, data, len)
            out += Block(id, data, len, want == got)
            pos = crcAt + 2
        }
        return out
    }

    /**
     * Decodes a complete descrambled frame. Null if the header doesn't match or
     * no valid position block was found: without coordinates the frame is
     * useless to a hunter.
     */
    fun parse(frame: ByteArray, freqHz: Long = 0L, nowMs: Long = 0L): SondeFrame? {
        if (frame.size < BLOCKS_AT + 4) return null
        for (k in HEADER.indices) if (b(frame, k) != HEADER[k]) return null

        var serial = ""
        var frameNo = 0
        var battery = 0.0
        var week = 0
        var itow = 0L
        var haveTime = false
        var fix: Geo.Fix? = null
        var enu: Geo.Enu? = null
        var sats = 0

        for (blk in blocks(frame)) {
            if (!blk.crcOk) continue
            when (blk.id) {
                BLK_STATUS -> if (blk.len >= 11) {
                    frameNo = u16(frame, blk.at)
                    val sb = StringBuilder()
                    for (k in 0 until 8) {
                        val c = b(frame, blk.at + 2 + k)
                        if (c in 0x20..0x7E) sb.append(c.toChar())
                    }
                    serial = sb.toString().trim()
                    battery = b(frame, blk.at + 10) / 10.0
                }
                BLK_GPS_TIME -> if (blk.len >= 6) {
                    week = u16(frame, blk.at)
                    itow = u32(frame, blk.at + 2)
                    haveTime = true
                }
                BLK_GPS_POS -> if (blk.len >= 21) {
                    // Position in cm, velocity in cm/s.
                    val x = i32(frame, blk.at).toDouble() / 100.0
                    val y = i32(frame, blk.at + 4).toDouble() / 100.0
                    val z = i32(frame, blk.at + 8).toDouble() / 100.0
                    if (x == 0.0 && y == 0.0 && z == 0.0) continue
                    val f = Geo.ecefToGeodetic(x, y, z)
                    val vx = i16(frame, blk.at + 12) / 100.0
                    val vy = i16(frame, blk.at + 14) / 100.0
                    val vz = i16(frame, blk.at + 16) / 100.0
                    fix = f
                    enu = Geo.ecefVelToEnu(f.lat, f.lon, vx, vy, vz)
                    sats = b(frame, blk.at + 18)
                }
            }
        }

        val f = fix ?: return null
        val v = enu ?: Geo.Enu(0.0, 0.0, 0.0)
        val timeMs = if (haveTime && week > 0) Geo.gpsToUnixMs(week, itow) else 0L
        val out = SondeFrame(
            type = "RS41",
            serial = serial,
            frameNo = frameNo,
            timeUtcMs = timeMs,
            lat = f.lat, lon = f.lon, altM = f.altM,
            speedMps = v.groundMps,
            headingDeg = v.headingDeg,
            climbMps = v.up,
            sats = sats,
            batteryV = battery,
            freqHz = freqHz,
            heardAtMs = nowMs)
        return if (out.plausible) out else null
    }

    /**
     * Finds, descrambles and decodes the first frame in a buffer. Returns it
     * with the index just past it, so the caller knows where to resume.
     */
    data class Hit(val frame: SondeFrame, val nextIndex: Int)

    fun scan(buf: ByteArray, from: Int, to: Int, freqHz: Long = 0L, nowMs: Long = 0L): Hit? {
        var at = from
        while (true) {
            val h = findHeader(buf, at, to)
            if (h < 0) return null
            // The type byte follows the parity and is still scrambled.
            val typeAt = h + TYPE_AT
            if (typeAt >= to) return null
            val type = (buf[typeAt].toInt() xor MASK[TYPE_AT % MASK.size]) and 0xff
            val len = frameLength(type)
            if (len == 0 || h + len > to) { at = h + 1; continue }
            val f = buf.copyOfRange(h, h + len)
            descramble(f)
            val parsed = parse(f, freqHz, nowMs)
            if (parsed != null) return Hit(parsed, h + len)
            at = h + 1
        }
    }
}
