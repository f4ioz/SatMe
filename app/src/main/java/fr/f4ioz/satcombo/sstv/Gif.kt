/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A small animated GIF writer (GIF89a, looping), for an SSTV picture
 * arriving: one palette of 256 colours for the whole animation, and each
 * frame only the rows that changed — the picture is sent once, row by row.
 */
object Gif {

    /** The 256 colours that best cover [px] (median cut on a sample), plus [aussi]. */
    fun palette(px: IntArray, aussi: List<Int> = emptyList()): IntArray {
        val pas = (px.size / 60_000).coerceAtLeast(1)
        val ech = ArrayList<Int>(px.size / pas + aussi.size)
        var i = 0
        while (i < px.size) { ech += px[i] and 0xFFFFFF; i += pas }
        val boites = ArrayList<MutableList<Int>>()
        boites += ech
        val reserve = aussi.size
        while (boites.size < 256 - reserve) {
            // Split the box with the widest spread, at the median of that channel.
            var meilleure = -1; var etendue = 0; var canal = 0
            boites.forEachIndexed { b, l ->
                if (l.size < 2) return@forEachIndexed
                for (c in 0..2) {
                    val s = 16 - 8 * c
                    var mn = 255; var mx = 0
                    for (v in l) { val x = (v shr s) and 0xFF; if (x < mn) mn = x; if (x > mx) mx = x }
                    if (mx - mn > etendue) { etendue = mx - mn; meilleure = b; canal = s }
                }
            }
            if (meilleure < 0 || etendue == 0) break
            val l = boites[meilleure]
            l.sortBy { (it shr canal) and 0xFF }
            boites[meilleure] = l.subList(0, l.size / 2).toMutableList()
            boites += l.subList(l.size / 2, l.size).toMutableList()
        }
        val pal = IntArray(256)
        var n = 0
        for (l in boites) {
            if (l.isEmpty()) continue
            var r = 0L; var g = 0L; var b = 0L
            for (v in l) { r += (v shr 16) and 0xFF; g += (v shr 8) and 0xFF; b += v and 0xFF }
            pal[n++] = ((r / l.size).toInt() shl 16) or ((g / l.size).toInt() shl 8) or (b / l.size).toInt()
        }
        for (c in aussi) if (n < 256) pal[n++] = c and 0xFFFFFF
        return pal
    }

    /** Colour → palette index, through a 15-bit table: fast enough for every frame. */
    class Indexeur(private val pal: IntArray) {
        private val table = IntArray(32768) { -1 }
        fun index(c: Int): Int {
            val k = ((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)
            val t = table[k]
            if (t >= 0) return t
            val r = ((k shr 10) and 31) * 8 + 4; val g = ((k shr 5) and 31) * 8 + 4; val b = (k and 31) * 8 + 4
            var meilleur = 0; var d = Int.MAX_VALUE
            for (i in pal.indices) {
                val p = pal[i]
                val dr = ((p shr 16) and 0xFF) - r; val dg = ((p shr 8) and 0xFF) - g; val db = (p and 0xFF) - b
                val e = dr * dr * 3 + dg * dg * 4 + db * db * 2
                if (e < d) { d = e; meilleur = i }
            }
            table[k] = meilleur
            return meilleur
        }
    }

    /** Writes a GIF frame by frame. */
    class Ecrivain(private val out: OutputStream, private val w: Int, private val h: Int, private val pal: IntArray) {
        private val idx = Indexeur(pal)

        init {
            out.write("GIF89a".toByteArray())
            le16(w); le16(h)
            out.write(0xF7)          // global table, 8 bits, 256 entries
            out.write(0); out.write(0)
            for (c in pal) { out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF) }
            // Loops for ever.
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
            out.write("NETSCAPE2.0".toByteArray())
            out.write(byteArrayOf(3, 1, 0, 0, 0))
        }

        private fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

        /**
         * The rows [y0]..[y1) of [px] (a whole w×h frame), shown for
         * [delaiCs] hundredths of a second, left in place for the next.
         */
        fun trame(px: IntArray, y0: Int, y1: Int, delaiCs: Int) {
            val a = y0.coerceIn(0, h - 1); val b = y1.coerceIn(a + 1, h)
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04))   // disposal: leave in place
            le16(delaiCs.coerceIn(0, 65535)); out.write(0); out.write(0)
            out.write(0x2C); le16(0); le16(a); le16(w); le16(b - a); out.write(0)
            val ind = ByteArray(w * (b - a))
            for (y in a until b) for (x in 0 until w) ind[(y - a) * w + x] = idx.index(px[y * w + x]).toByte()
            out.write(8)
            ecritBlocs(lzw(ind, 8))
        }

        fun fin() { out.write(0x3B); out.flush() }

        private fun ecritBlocs(d: ByteArray) {
            var i = 0
            while (i < d.size) { val n = minOf(255, d.size - i); out.write(n); out.write(d, i, n); i += n }
            out.write(0)
        }
    }

    /** GIF's variable-width LZW, codes packed least significant bit first. */
    fun lzw(ind: ByteArray, tailleMin: Int): ByteArray {
        val clear = 1 shl tailleMin; val eoi = clear + 1
        val sortie = ByteArrayOutputStream()
        var acc = 0; var nbits = 0
        var largeur = tailleMin + 1
        fun emet(code: Int) {
            acc = acc or (code shl nbits); nbits += largeur
            while (nbits >= 8) { sortie.write(acc and 0xFF); acc = acc ushr 8; nbits -= 8 }
        }
        val dico = HashMap<Int, Int>(8192)
        var suivant = eoi + 1
        emet(clear)
        if (ind.isEmpty()) { emet(eoi); if (nbits > 0) sortie.write(acc and 0xFF); return sortie.toByteArray() }
        var prefixe = ind[0].toInt() and 0xFF
        for (i in 1 until ind.size) {
            val c = ind[i].toInt() and 0xFF
            val cle = (prefixe shl 8) or c
            val trouve = dico[cle]
            if (trouve != null) { prefixe = trouve; continue }
            emet(prefixe)
            if (suivant < 4096) {
                dico[cle] = suivant++
                if (suivant > (1 shl largeur) && largeur < 12) largeur++
            } else {
                emet(clear); dico.clear(); suivant = eoi + 1; largeur = tailleMin + 1
            }
            prefixe = c
        }
        emet(prefixe)
        emet(eoi)
        if (nbits > 0) sortie.write(acc and 0xFF)
        return sortie.toByteArray()
    }
}
