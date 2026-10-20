/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.plugins.jpeg.JPEGHuffmanTable
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.plugins.jpeg.JPEGQTable

/** SSDV from the published format: packets, Reed-Solomon, the JPEG rebuilt with packets missing. */
class SsdvTest {

    @Test fun tablesDeLAnnexeK() {
        fun egal(h: Jpeg.Huffman, t: JPEGHuffmanTable) {
            assertArrayEquals(t.lengths.map { it.toInt() }.toIntArray(), h.longueurs)
            assertArrayEquals(t.values.map { it.toInt() }.toIntArray(), h.symboles)
        }
        egal(Jpeg.DC_LUMINANCE, JPEGHuffmanTable.StdDCLuminance)
        egal(Jpeg.DC_CHROMINANCE, JPEGHuffmanTable.StdDCChrominance)
        egal(Jpeg.AC_LUMINANCE, JPEGHuffmanTable.StdACLuminance)
        egal(Jpeg.AC_CHROMINANCE, JPEGHuffmanTable.StdACChrominance)
        assertArrayEquals(JPEGQTable.K1Luminance.table, Jpeg.Q_LUMINANCE)
        assertArrayEquals(JPEGQTable.K2Chrominance.table, Jpeg.Q_CHROMINANCE)
    }

    @Test fun indicatifEnBase40() {
        for (c in listOf("Q0SAT", "F4IOZ", "A", "ZZ9ZZZ")) assertEquals(c, Ssdv.indicatif(Ssdv.codeIndicatif(c)))
    }

    @Test fun reedSolomonRepareLePaquet() {
        val p = SignalPaquets.paquetsMire(1)[2]
        val abime = p.copyOf()
        for (k in listOf(3, 20, 40, 77, 100, 150, 199, 210, 230, 250)) abime[k] = (abime[k].toInt() xor 0x5A).toByte()
        val l = Ssdv.lis(abime)!!
        assertEquals(10, l.corriges)
        assertEquals(2, l.numero)
        assertEquals("Q0SAT", l.indicatif)
        // Too many: dropped, never half-shown.
        for (k in 1 until 60 step 3) abime[k] = (abime[k].toInt() xor 0x33).toByte()
        assertNull(Ssdv.lis(abime))
        // Without its sync byte (some satellites leave it out), inside a longer frame.
        val dedans = byteArrayOf(1, 2, 3) + p.copyOfRange(1, 256) + byteArrayOf(9)
        assertEquals(1, Ssdv.cherche(dedans).size)
    }

    private fun ecart(a: BufferedImage, b: BufferedImage, x0: Int, y0: Int, w: Int, h: Int): Double {
        var s = 0.0
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            for (sh in intArrayOf(0, 8, 16)) { val d = ((p ushr sh) and 0xFF) - ((q ushr sh) and 0xFF); s += d * d }
        }
        return Math.sqrt(s / (3.0 * w * h))
    }

    @Test fun mireCodeeEtRelue() {
        val px = SignalPaquets.mire()
        val paquets = SignalPaquets.paquetsMire(7)
        assertTrue(paquets.size in 5..200)
        assertTrue(Ssdv.lis(paquets.last())!!.dernier)
        val img = Ssdv.Image("Q0SAT", 7)
        paquets.forEach { img.ajoute(Ssdv.lis(it)!!) }
        val j = ImageIO.read(img.jpeg()!!.inputStream())
        assertEquals(320, j.width); assertEquals(240, j.height)
        assertEquals(img.mcusTotal, img.mcusLues)
        val orig = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 320, 240, px, 0, 320) }
        assertTrue(ecart(orig, j, 0, 0, 320, 240) < 14.0)
    }

    // ------------------------------------------------------------ an encoder from a real JPEG

    /** A baseline JPEG's scan as quantised blocks (DC absolute), with its sampling: the test's own reader. */
    private class Lu(val largeur: Int, val hauteur: Int, val hs: Int, val vs: Int, val mcus: List<Array<IntArray>>)

    private fun lisJpeg(f: ByteArray): Lu {
        var i = 2
        var w = 0; var h = 0; var hs = 1; var vs = 1
        // The writer leaves out the tables it was given (an « abbreviated » file): those by default.
        val dc = hashMapOf(0 to Jpeg.DC_LUMINANCE, 1 to Jpeg.DC_CHROMINANCE); val ac = hashMapOf(0 to Jpeg.AC_LUMINANCE, 1 to Jpeg.AC_CHROMINANCE)
        fun u(k: Int) = f[k].toInt() and 0xFF
        while (true) {
            check(u(i) == 0xFF)
            val m = u(i + 1); val l = (u(i + 2) shl 8) or u(i + 3)
            when (m) {
                0xC0 -> { h = (u(i + 5) shl 8) or u(i + 6); w = (u(i + 7) shl 8) or u(i + 8); hs = u(i + 11) ushr 4; vs = u(i + 11) and 15 }
                0xC4 -> {
                    var k = i + 4
                    while (k < i + 2 + l) {
                        val cl = u(k); val lens = IntArray(16) { u(k + 1 + it) }; val n = lens.sum()
                        val t = Jpeg.Huffman(lens, IntArray(n) { u(k + 17 + it) })
                        if (cl ushr 4 == 0) dc[cl and 15] = t else ac[cl and 15] = t
                        k += 17 + n
                    }
                }
                0xDA -> { i += 2 + l; break }
            }
            i += 2 + l
        }
        // The scan, its stuffing taken out.
        val o = java.io.ByteArrayOutputStream()
        while (!(u(i) == 0xFF && u(i + 1) != 0)) { o.write(u(i)); i += if (u(i) == 0xFF) 2 else 1 }
        val scan = o.toByteArray()
        var bit = 0
        val b = object : Jpeg.Bits { override fun bit(): Int { val v = (scan[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1; bit++; return v } }
        val nm = (w / (8 * hs)) * (h / (8 * vs))
        val pred = IntArray(3)
        val mcus = List(nm) {
            Array(hs * vs + 2) { k ->
                val c = if (k < hs * vs) 0 else k - hs * vs + 1
                Jpeg.lisBloc(b, dc[if (c == 0) 0 else 1]!!, ac[if (c == 0) 0 else 1]!!, pred[c]).also { pred[c] = it[0] }
            }
        }
        return Lu(w, h, hs, vs, mcus)
    }

    private fun jpegDEssai(w: Int, h: Int, qualite: Int): Pair<ByteArray, BufferedImage> {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, Color(x * 255 / w, y * 255 / h, 128).rgb)
        g.color = Color.WHITE; g.fillOval(w / 4, h / 4, w / 2, h / 2)
        g.color = Color.RED; g.drawString("SatMe SSDV", 20, 30)
        g.dispose()
        val (qy, qc) = Ssdv.tables(qualite)
        val wr = ImageIO.getImageWritersByFormatName("jpeg").next()
        val p = wr.defaultWriteParam as JPEGImageWriteParam
        p.setEncodeTables(arrayOf(JPEGQTable(qy), JPEGQTable(qc)),
            arrayOf(JPEGHuffmanTable.StdDCLuminance, JPEGHuffmanTable.StdDCChrominance),
            arrayOf(JPEGHuffmanTable.StdACLuminance, JPEGHuffmanTable.StdACChrominance))
        val out = java.io.ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { s -> wr.output = s; wr.write(null, IIOImage(img, null, null), p) }
        wr.dispose()
        return out.toByteArray() to img
    }

    /** Every pixel of the block mid-grey. */
    private fun gris(b: BufferedImage, x0: Int, y0: Int, w: Int, h: Int): Boolean {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
            val p = b.getRGB(x, y)
            for (sh in intArrayOf(0, 8, 16)) if (kotlin.math.abs(((p ushr sh) and 0xFF) - 128) > 3) return false
        }
        return true
    }

    @Test fun jpegEnPaquetsDeuxPerdus() {
        val (f, source) = jpegDEssai(256, 192, 4)
        val lu = lisJpeg(f)
        val mode = Ssdv.ECHANTILLONNAGE.indexOfFirst { it == lu.hs to lu.vs }
        assertTrue(mode >= 0)
        val (qy, qc) = Ssdv.tables(4)
        // The file's blocks as they were, in a whole JPEG: the reference (and close to the picture drawn).
        val original = ImageIO.read(Jpeg.fichier(lu.largeur, lu.hauteur, lu.hs, lu.vs, qy, qc, lu.mcus).inputStream())
        assertTrue(ecart(source, original, 0, 0, 256, 192) < 12.0)
        val paquets = Ssdv.paquets(lu.mcus, lu.largeur, lu.hauteur, "F4IOZ", 42, qualite = 4, mode = mode)
        assertTrue(paquets.size > 8)
        val perdus = setOf(3, 6)
        val img = Ssdv.Image("F4IOZ", 42)
        paquets.forEachIndexed { k, p -> if (k !in perdus) assertTrue(img.ajoute(Ssdv.lis(p)!!)) }
        assertEquals(listOf(3, 6), img.manquants())
        val rebati = ImageIO.read(img.jpeg()!!.inputStream())
        assertTrue("${img.mcusLues}/${img.mcusTotal}", img.mcusLues in img.mcusTotal / 2 until img.mcusTotal)
        val mw = 8 * lu.hs; val mh = 8 * lu.vs; val parLigne = lu.largeur / mw
        // The MCUs of the first packets come out as they were (away from the grey ones: chroma is smoothed across blocks).
        val premiere = Ssdv.lis(paquets[3])!!.premiereMcu!!
        for (m in 0 until premiere - parLigne - 1) {
            val x = (m % parLigne) * mw; val y = (m / parLigne) * mh
            assertTrue("mcu $m", ecart(original, rebati, x, y, mw, mh) < 1.0)
        }
        // The lost ones mid-grey, as many as were not read.
        var grises = 0
        for (m in 0 until img.mcusTotal) if (gris(rebati, (m % parLigne) * mw + mw / 4, (m / parLigne) * mh + mh / 4, mw / 2, mh / 2)) grises++
        assertEquals(img.mcusTotal - img.mcusLues, grises)
        // The missing packets come after all: the picture completes.
        perdus.forEach { img.ajoute(Ssdv.lis(paquets[it])!!) }
        assertNotNull(img.jpeg()); assertEquals(img.mcusTotal, img.mcusLues)
        assertTrue(ecart(original, ImageIO.read(img.jpeg()!!.inputStream()), 0, 0, lu.largeur, lu.hauteur) < 0.5)
    }
}
