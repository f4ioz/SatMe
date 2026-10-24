/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.QslSstv
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class QslRenduPcTest {

    private val infos = QslSstv.Infos("F4IOZ", "JN18FS", 1_792_830_976_062L, "ISS", 437_550_000L, "FM", "Robot 36", 17.0, 17.2,
        "ARISS SSTV Series 34 — un nom de série très long qui ne tient pas sur la carte", 5, 12)

    @Test fun la_qsl_se_dessine_sans_toucher_l_originale() {
        val d = Files.createTempDirectory("qsl").toFile()
        try {
            val png = File(d, "SatMe_SSTV_ISS_20261024_083616Z_Robot36.png")
            val b = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 240) for (x in 0 until 320) b.setRGB(x, y, (x shl 16) or (y shl 8) or 0x40)
            ImageIO.write(b, "png", png)
            val avant = png.readBytes(); val quand = png.lastModified()
            for (dispo in QslSstv.Disposition.entries) for (jpeg in listOf(false, true)) {
                val q = QslRenduPc.rend(png, infos, dispo, jpeg)!!
                val im = ImageIO.read(q.inputStream())
                assertEquals(640, im.width)
                assertTrue(im.height >= 480)
                // The picture itself, doubled, at the top left.
                val c = im.getRGB(200, 100)
                assertEquals(100.0, ((c shr 16) and 0xFF).toDouble(), 6.0)
            }
            assertArrayEquals(avant, png.readBytes())
            assertEquals(quand, png.lastModified())
            assertEquals(listOf(png.name), d.list()!!.toList())
        } finally { d.deleteRecursively() }
    }
}
