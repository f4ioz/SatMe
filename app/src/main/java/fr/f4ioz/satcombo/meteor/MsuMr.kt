/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The MSU-MR imager's pictures, as LRPT sends them: one packet per channel
 * (APID 64 to 69) carries 14 blocks of 8 × 8 pixels compressed like a JPEG
 * (the standard luminance Huffman and quantization tables, a quality factor
 * per packet). Fourteen packets make 8 lines 1568 pixels wide: one scan,
 * 1.23 s; all the channels of a scan carry the same time.
 *
 * Packet data (after the 6-byte CCSDS header): day (2), ms of the day (4),
 * µs (2), number of the first block (1), three bytes of tables (0, 0, FFF0),
 * the quality factor (1), then the compressed blocks.
 */
class MsuMr {

    companion object {
        const val LARGEUR = 1568
        const val BLOCS = 14

        private val ZIGZAG = intArrayOf(
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63)

        private val QUANT = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99)

        // JPEG luminance Huffman tables (counts per code length 1..16, then the symbols).
        private val DC_LONG = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        private val DC_VAL = IntArray(12) { it }
        private val AC_LONG = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
        internal val AC_VAL = intArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa)

        /** Canonical Huffman: for each length, the first code and where its symbols start. */
        private class Huffman(longueurs: IntArray, val valeurs: IntArray) {
            val premier = IntArray(17)
            val dernier = IntArray(17) { -1 }
            val index = IntArray(17)
            init {
                var code = 0; var k = 0
                for (l in 1..16) {
                    premier[l] = code; index[l] = k
                    code += longueurs[l - 1]; k += longueurs[l - 1]
                    dernier[l] = code - 1
                    code = code shl 1
                }
            }
        }
        private val DC = Huffman(DC_LONG, DC_VAL)
        private val AC = Huffman(AC_LONG, AC_VAL)

        /** cos((2x+1)uπ/16) · c(u), for the inverse transform. */
        private val COS = Array(8) { x -> DoubleArray(8) { u ->
            (if (u == 0) 1 / sqrt(2.0) else 1.0) * cos((2 * x + 1) * u * PI / 16) } }

        fun tableQuantif(qf: Int): IntArray {
            val f = if (qf in 20 until 50) 5000.0 / qf else 200.0 - 2.0 * qf
            return IntArray(64) { ((f / 100.0 * QUANT[it]) + 0.5).toInt().coerceAtLeast(1) }
        }
    }

    /** One channel: 8-line scans by time (ms). */
    class Canal {
        val balayages = java.util.TreeMap<Long, ByteArray>()
        fun balayage(t: Long): ByteArray = balayages.getOrPut(t) { ByteArray(8 * LARGEUR) }
    }

    val canaux = java.util.TreeMap<Int, Canal>()
    var segments = 0; private set
    var segmentsRates = 0; private set

    private class Bits(val d: ByteArray, var pos: Int, val fin: Int) {
        fun bit(): Int {
            if (pos >= fin * 8) throw IndexOutOfBoundsException()
            val b = (d[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return b
        }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun symbole(h: Huffman): Int {
            var code = 0
            for (l in 1..16) {
                code = (code shl 1) or bit()
                if (h.dernier[l] >= h.premier[l] && code <= h.dernier[l] && code >= h.premier[l])
                    return h.valeurs[h.index[l] + code - h.premier[l]]
            }
            throw IllegalStateException("code inconnu")
        }
    }

    private fun etendu(v: Int, s: Int): Int = if (s == 0) 0 else if (v < (1 shl (s - 1))) v - (1 shl s) + 1 else v

    /**
     * One image packet ([apid], data after the CCSDS header). Blocks decoded
     * before a fault are kept: a damaged packet loses its end, not its start.
     */
    fun paquet(apid: Int, d: ByteArray) {
        if (apid !in 64..69 || d.size <= 14) return
        val ms = ((d[2].toLong() and 0xFF) shl 24) or ((d[3].toLong() and 0xFF) shl 16) or
            ((d[4].toLong() and 0xFF) shl 8) or (d[5].toLong() and 0xFF)
        val jour = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        val mcu = d[8].toInt() and 0xFF
        val qf = d[13].toInt() and 0xFF
        if (mcu % BLOCS != 0 || mcu >= 196) { segmentsRates++; return }
        val t = jour * 86_400_000L + ms
        val ligne = canaux.getOrPut(apid) { Canal() }.balayage(t)
        val q = tableQuantif(qf)
        val bits = Bits(d, 14 * 8, d.size)
        val coef = IntArray(64)
        val pix = DoubleArray(64)
        var dc = 0
        try {
            for (b in 0 until BLOCS) {
                coef.fill(0)
                val s = bits.symbole(DC)
                dc += etendu(bits.bits(s), s)
                coef[0] = dc
                var k = 1
                while (k < 64) {
                    val rs = bits.symbole(AC)
                    if (rs == 0x00) break
                    val run = rs ushr 4; val size = rs and 0x0F
                    k += run
                    if (k > 63) break
                    coef[k] = etendu(bits.bits(size), size)
                    k++
                }
                idct(coef, q, pix)
                val x0 = (mcu + b) * 8
                for (y in 0 until 8) for (x in 0 until 8) {
                    ligne[y * LARGEUR + x0 + x] = (pix[y * 8 + x] + 128).roundToInt().coerceIn(0, 255).toByte()
                }
            }
            segments++
        } catch (_: RuntimeException) {
            segmentsRates++
        }
    }

    private val tmp = DoubleArray(64)

    /** Dequantised (zigzag order in [c]) and inverse-transformed into [out] (natural order). */
    private fun idct(c: IntArray, q: IntArray, out: DoubleArray) {
        val f = DoubleArray(64)
        for (k in 0 until 64) f[ZIGZAG[k]] = c[k].toDouble() * q[ZIGZAG[k]]
        // Rows then columns.
        for (y in 0 until 8) for (x in 0 until 8) {
            var s = 0.0
            for (u in 0 until 8) s += COS[x][u] * f[y * 8 + u]
            tmp[y * 8 + x] = s / 2
        }
        for (x in 0 until 8) for (y in 0 until 8) {
            var s = 0.0
            for (v in 0 until 8) s += COS[y][v] * tmp[v * 8 + x]
            out[y * 8 + x] = s / 2
        }
    }

    /**
     * A channel as a grey picture: its scans in time order, a missing scan
     * left black at its place (the period from the scans themselves).
     */
    fun image(apid: Int): Pair<Int, ByteArray>? {
        val c = canaux[apid] ?: return null
        val t = periodeEtOrigine() ?: return null
        val (periode, t0, nScans) = t
        val out = ByteArray(nScans * 8 * LARGEUR)
        for ((tm, l) in c.balayages) {
            val i = ((tm - t0) / periode).roundToInt()
            if (i in 0 until nScans) System.arraycopy(l, 0, out, i * 8 * LARGEUR, l.size)
        }
        return nScans * 8 to out
    }

    /** The scan period (median gap between successive scans), the first scan's time, how many scans. */
    fun periodeEtOrigine(): Triple<Double, Long, Int>? {
        val temps = java.util.TreeSet<Long>()
        for (c in canaux.values) temps.addAll(c.balayages.keys)
        if (temps.isEmpty()) return null
        val ecarts = temps.zipWithNext { a, b -> b - a }.filter { it in 500..3000 }.sorted()
        val periode = if (ecarts.isEmpty()) 1231.0 else ecarts[ecarts.size / 2].toDouble()
        val t0 = temps.first()
        val n = ((temps.last() - t0) / periode).roundToInt() + 1
        return Triple(periode, t0, n)
    }
}
