/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The baseline JPEG pieces SSDV needs (ITU T.81): the example Huffman and
 * quantisation tables of its annex K, Huffman coding of 8×8 blocks (DC as
 * a difference, AC as run/size pairs), the forward DCT, and a writer for a
 * whole baseline file. Blocks are kept as 64 quantised coefficients in
 * zig-zag order.
 */
object Jpeg {

    /** Zig-zag position → natural (row-major) index. */
    val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63)

    /** Annex K.1, natural order. */
    val Q_LUMINANCE = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99)
    val Q_CHROMINANCE = IntArray(64) { i ->
        val r = i / 8; val c = i % 8
        when {
            r == 0 -> intArrayOf(17, 18, 24, 47, 99, 99, 99, 99)[c]
            r == 1 -> intArrayOf(18, 21, 26, 66, 99, 99, 99, 99)[c]
            r == 2 -> intArrayOf(24, 26, 56, 99, 99, 99, 99, 99)[c]
            r == 3 -> intArrayOf(47, 66, 99, 99, 99, 99, 99, 99)[c]
            else -> 99
        }
    }

    /** A table scaled to a quality 1..100 (50: as printed), natural order, 1..255. */
    fun echelle(base: IntArray, qualite: Int): IntArray {
        val q = qualite.coerceIn(1, 100)
        val s = if (q < 50) 5000 / q else 200 - 2 * q
        return IntArray(64) { ((base[it] * s + 50) / 100).coerceIn(1, 255) }
    }

    /** A Huffman table from its code-length counts (16) and symbols, as in a DHT segment. */
    class Huffman(val longueurs: IntArray, val symboles: IntArray) {
        val code = IntArray(256); val taille = IntArray(256)
        // Decoding: per length, the first code, its index in [symboles], the last code.
        private val premier = IntArray(17); private val indice = IntArray(17); private val dernier = IntArray(17) { -1 }

        init {
            var c = 0; var k = 0
            for (l in 1..16) {
                premier[l] = c; indice[l] = k
                repeat(longueurs[l - 1]) { val s = symboles[k++]; code[s] = c++; taille[s] = l }
                dernier[l] = c - 1
                c = c shl 1
            }
        }

        /** The next symbol, or -1 for a code not in the table. */
        fun lis(b: Bits): Int {
            var c = 0
            for (l in 1..16) {
                c = (c shl 1) or b.bit()
                if (longueurs[l - 1] > 0 && c <= dernier[l] && c >= premier[l]) return symboles[indice[l] + c - premier[l]]
            }
            return -1
        }
    }

    val DC_LUMINANCE = Huffman(intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0), IntArray(12) { it })
    val DC_CHROMINANCE = Huffman(intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0), IntArray(12) { it })
    val AC_LUMINANCE = Huffman(intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D), octets(
        "01020300041105122131410613516107" +
        "227114328191a1082342b1c11552d1f0" +
        "2433627282090a161718191a25262728" +
        "292a3435363738393a43444546474849" +
        "4a535455565758595a63646566676869" +
        "6a737475767778797a83848586878889" +
        "8a92939495969798999aa2a3a4a5a6a7" +
        "a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5" +
        "c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2" +
        "e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8" +
        "f9fa"))
    val AC_CHROMINANCE = Huffman(intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77), octets(
        "00010203110405213106124151076171" +
        "1322328108144291a1b1c109233352f0" +
        "156272d10a162434e125f11718191a26" +
        "2728292a35363738393a434445464748" +
        "494a535455565758595a636465666768" +
        "696a737475767778797a828384858687" +
        "88898a92939495969798999aa2a3a4a5" +
        "a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3" +
        "c4c5c6c7c8c9cad2d3d4d5d6d7d8d9da" +
        "e2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8" +
        "f9fa"))

    private fun octets(h: String) = IntArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16) }

    /** Bits read MSB first; [bit] throws [Fin] past the end. */
    interface Bits { fun bit(): Int }
    class Fin : RuntimeException()

    fun lisEntier(b: Bits, n: Int): Int {
        var v = 0
        repeat(n) { v = (v shl 1) or b.bit() }
        return v
    }

    /** A magnitude category's [n] extra bits back to the value (F.2.2.1). */
    fun etend(v: Int, n: Int): Int = if (n == 0) 0 else if (v < (1 shl (n - 1))) v - (1 shl n) + 1 else v

    /** Reads one block: [dc] is the DC predictor, the block's DC returned in [bloc][0] as an absolute value. */
    fun lisBloc(b: Bits, dcT: Huffman, acT: Huffman, dc: Int): IntArray {
        val bloc = IntArray(64)
        val t = dcT.lis(b)
        if (t !in 0..11) throw Fin()
        bloc[0] = dc + etend(lisEntier(b, t), t)
        var k = 1
        while (k < 64) {
            val rs = acT.lis(b)
            if (rs < 0) throw Fin()
            val r = rs ushr 4; val s = rs and 15
            if (s == 0) { if (r == 15) { k += 16; continue } else break }
            k += r
            if (k > 63) throw Fin()
            bloc[k++] = etend(lisEntier(b, s), s)
        }
        if (k > 64) throw Fin()
        return bloc
    }

    /** Writes bits MSB first into bytes; [bourrage]: a 0x00 after every 0xFF (inside a JPEG's scan). */
    class Ecrivain(private val bourrage: Boolean, private val octet: (Int) -> Unit) {
        private var acc = 0; private var n = 0
        val enAttente: Int get() = n
        fun ecris(v: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                acc = (acc shl 1) or ((v ushr i) and 1)
                if (++n == 8) { sort(acc); acc = 0; n = 0 }
            }
        }
        private fun sort(o: Int) { octet(o); if (bourrage && o == 0xFF) octet(0) }
        /** Completes the byte with 1s. */
        fun aligne() { while (n != 0) ecris(1, 1) }
    }

    private fun categorie(v: Int): Int { var a = kotlin.math.abs(v); var n = 0; while (a > 0) { n++; a = a ushr 1 }; return n }

    /** Writes one block whose DC is [bloc][0] minus [dc] (the predictor). */
    fun ecrisBloc(w: Ecrivain, bloc: IntArray, dc: Int, dcT: Huffman, acT: Huffman) {
        val d = bloc[0] - dc
        val c = categorie(d)
        w.ecris(dcT.code[c], dcT.taille[c])
        if (c > 0) w.ecris(if (d < 0) d - 1 + (1 shl c) else d, c)
        var zeros = 0
        var dernier = 63
        while (dernier > 0 && bloc[dernier] == 0) dernier--
        for (k in 1..dernier) {
            val v = bloc[k]
            if (v == 0) { zeros++; continue }
            while (zeros > 15) { w.ecris(acT.code[0xF0], acT.taille[0xF0]); zeros -= 16 }
            val s = categorie(v).coerceAtMost(10)
            val rs = (zeros shl 4) or s
            w.ecris(acT.code[rs], acT.taille[rs])
            w.ecris(if (v < 0) v - 1 + (1 shl s) else v, s)
            zeros = 0
        }
        if (dernier < 63) w.ecris(acT.code[0x00], acT.taille[0x00])
    }

    private val COSINUS = Array(8) { x -> DoubleArray(8) { u -> cos((2 * x + 1) * u * PI / 16) } }

    /** Forward DCT of 64 samples (natural order, level-shifted), quantised by [q] (natural), in zig-zag order. */
    fun dct(px: DoubleArray, q: IntArray): IntArray {
        val tmp = DoubleArray(64)
        for (y in 0 until 8) for (u in 0 until 8) {
            var s = 0.0
            for (x in 0 until 8) s += px[y * 8 + x] * COSINUS[x][u]
            tmp[y * 8 + u] = s * (if (u == 0) Math.sqrt(0.125) else 0.5)
        }
        val out = IntArray(64)
        val nat = DoubleArray(64)
        for (u in 0 until 8) for (v in 0 until 8) {
            var s = 0.0
            for (y in 0 until 8) s += tmp[y * 8 + u] * COSINUS[y][v]
            nat[v * 8 + u] = s * (if (v == 0) Math.sqrt(0.125) else 0.5)
        }
        for (k in 0 until 64) out[k] = (nat[ZIGZAG[k]] / q[ZIGZAG[k]]).roundToInt()
        return out
    }

    /**
     * A whole baseline file: three components (Y with [hs]×[vs] sampling,
     * Cb, Cr at 1×1), the annex K Huffman tables, [mcus] in order, each
     * hs·vs luminance blocks then Cb then Cr.
     */
    fun fichier(largeur: Int, hauteur: Int, hs: Int, vs: Int, qY: IntArray, qC: IntArray, mcus: List<Array<IntArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun o(v: Int) = out.write(v and 0xFF)
        fun mot(v: Int) { o(v ushr 8); o(v) }
        o(0xFF); o(0xD8)
        for ((id, q) in listOf(0 to qY, 1 to qC)) {
            o(0xFF); o(0xDB); mot(67); o(id)
            for (k in 0 until 64) o(q[ZIGZAG[k]])
        }
        o(0xFF); o(0xC0); mot(17); o(8); mot(hauteur); mot(largeur); o(3)
        o(1); o((hs shl 4) or vs); o(0)
        o(2); o(0x11); o(1)
        o(3); o(0x11); o(1)
        for ((cl, t) in listOf(0x00 to DC_LUMINANCE, 0x10 to AC_LUMINANCE, 0x01 to DC_CHROMINANCE, 0x11 to AC_CHROMINANCE)) {
            o(0xFF); o(0xC4); mot(3 + 16 + t.symboles.size); o(cl)
            t.longueurs.forEach { o(it) }; t.symboles.forEach { o(it) }
        }
        o(0xFF); o(0xDA); mot(12); o(3)
        o(1); o(0x00); o(2); o(0x11); o(3); o(0x11)
        o(0); o(63); o(0)
        val w = Ecrivain(true) { o(it) }
        val dc = IntArray(3)
        val ny = hs * vs
        for (m in mcus) {
            for (i in m.indices) {
                val c = if (i < ny) 0 else i - ny + 1
                val (dt, at) = if (c == 0) DC_LUMINANCE to AC_LUMINANCE else DC_CHROMINANCE to AC_CHROMINANCE
                ecrisBloc(w, m[i], dc[c], dt, at)
                dc[c] = m[i][0]
            }
        }
        w.aligne()
        o(0xFF); o(0xD9)
        return out.toByteArray()
    }
}
