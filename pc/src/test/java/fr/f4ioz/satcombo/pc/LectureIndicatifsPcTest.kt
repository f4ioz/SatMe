/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cw.AnalyseCw
import fr.f4ioz.satcombo.datv.IndicatifsImage
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * The real tesseract on test cards as the receiver shows them (the DATV
 * card, then other callsigns, fonts and colours; scaled and JPEG-compressed
 * as the reception's pictures are): how many callsigns come out right.
 */
class LectureIndicatifsPcTest {

    private val prog = OutilsPc.trouve("tesseract")
    private val ff = RenduPc.ffmpeg()
    private val dossier = System.getenv("SATME_BANC_OCR")?.let { File(it).apply { mkdirs() } }

    private val indicatifs = listOf("F4IOZ", "EA4ABC", "G4XYZ", "SP5XYZ", "DL1ABC/P", "OH2XYZ", "9A1AA", "VK2ABC", "F/G4ABC")

    /** [img] as the receiver shows it: scaled to [l]×[h], JPEG at ffmpeg's quality [q] (ImageIO's when no ffmpeg). */
    private fun recue(img: BufferedImage, l: Int, h: Int, q: Int): BufferedImage {
        val png = File.createTempFile("satme-banc-", ".png"); val jpg = File.createTempFile("satme-banc-", ".jpg")
        try {
            ImageIO.write(img, "png", png)
            if (ff != null) {
                val p = ProcessBuilder(ff, "-loglevel", "error", "-y", "-i", png.path, "-vf", "scale=$l:$h", "-q:v", q.toString(), jpg.path)
                    .redirectErrorStream(true).start()
                p.inputStream.readBytes(); p.waitFor()
                return ImageIO.read(jpg)
            }
            val r = BufferedImage(l, h, BufferedImage.TYPE_INT_RGB)
            r.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); drawImage(img, 0, 0, l, h, null); dispose() }
            val o = ByteArrayOutputStream()
            val w = ImageIO.getImageWritersByFormatName("jpg").next()
            val p = w.defaultWriteParam.apply { compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT; compressionQuality = (1f - q / 31f).coerceIn(0.1f, 1f) }
            w.output = ImageIO.createImageOutputStream(o); w.write(null, javax.imageio.IIOImage(r, null, null), p); w.dispose()
            return ImageIO.read(ByteArrayInputStream(o.toByteArray()))
        } finally { png.delete(); jpg.delete() }
    }

    /** A plain card: bars above, the callsign and a square on a band, in [police]. */
    private fun carte(indicatif: String, police: String, clair: Boolean): BufferedImage {
        val l = 1280; val h = 720
        val img = BufferedImage(l, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val barres = listOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE)
            barres.forEachIndexed { i, c -> color = c; fillRect(i * l / barres.size, 0, l / barres.size + 1, h / 2) }
            color = if (clair) Color(0x0B1020) else Color(0xF2F2E8); fillRect(0, h / 2, l, h / 2)
            color = if (clair) Color(0x9FE8FF) else Color(0x10204A)
            font = Font(police, Font.PLAIN, 64)
            drawString("$indicatif  JN18FS", 120, h / 2 + 130)
            color = Color(0xFFC65C); font = Font(police, Font.BOLD, 56)
            drawString("DATV", 120, h / 2 + 260)
            dispose()
        }
        return img
    }

    private data class Cas(val nom: String, val image: BufferedImage, val attendu: String)

    private fun cas(): List<Cas> {
        val c = ArrayList<Cas>()
        javaClass.getResourceAsStream("/datv/mire-recue-640x360.jpg")?.use { c += Cas("reçue", ImageIO.read(it), "F4IOZ") }
        for (i in indicatifs) {
            val mire = MirePc.carte(1280, 720, i, "JN18FS", "DATV")
            for ((l, h, q) in listOf(Triple(640, 360, 5), Triple(1280, 720, 5), Triple(640, 360, 12), Triple(960, 540, 8))) {
                c += Cas("mire $i ${l}x$h q$q", recue(mire, l, h, q), i)
            }
        }
        val polices = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        for (p in listOf("DejaVu Sans", "Liberation Sans", "Noto Sans").filter { it in polices }) {
            for (i in indicatifs) for (clair in listOf(true, false)) {
                c += Cas("$p ${if (clair) "clair/sombre" else "sombre/clair"} $i", recue(carte(i, p, clair), 640, 360, 5), i)
            }
        }
        return c
    }

    private fun base(i: String) = AnalyseCw.base(i) ?: i

    @Test fun `la mire recue donne F4IOZ jamais FAIOZ`() {
        assumeTrue(prog != null)
        val jpeg = javaClass.getResourceAsStream("/datv/mire-recue-640x360.jpg")!!.use { it.readBytes() }
        var service = "F4IOZ"
        val src = TextesDatvPc.Source({ true }, { true }, { jpeg }, { service }, { 1_255_000L }, {})
        val t = TextesDatvPc(fr.f4ioz.satcombo.plateforme.StockageMemoire(), src, null, { 0L })
        // The service name says F4IOZ: shown from the first read.
        t.lisImage(prog!!, jpeg)
        assertTrue(t.confirmes().map { it.indicatif }.toString(), t.confirmes().map { it.indicatif } == listOf("F4IOZ"))
        val j = t.json()
        println("lu : « ${j.getString("texte")} » · ${j.getJSONArray("lus")}")
        assertTrue(j.getString("texte"), !j.getString("texte").contains("FAIOZ") && j.getString("texte").contains("F4IOZ"))
        // Nothing vouching: the vote's two reads.
        service = ""
        val u = TextesDatvPc(fr.f4ioz.satcombo.plateforme.StockageMemoire(), src, null, { 0L })
        u.lisImage(prog, jpeg)
        assertTrue(u.confirmes().isEmpty())
        u.lisImage(prog, jpeg)
        assertTrue(u.confirmes().map { it.indicatif }.toString(), u.confirmes().map { it.indicatif } == listOf("F4IOZ"))
    }

    @Test fun `le taux de lecture des indicatifs sur les mires`() {
        assumeTrue(prog != null)
        val liste = cas()
        val pool = Executors.newFixedThreadPool(maxOf(1, minOf(6, Runtime.getRuntime().availableProcessors() / 2)))
        val resultats = try {
            pool.invokeAll(liste.map { c -> Callable {
                dossier?.let { d -> ImageIO.write(c.image, "png", File(d, c.nom.replace(Regex("[^A-Za-z0-9]+"), "_") + ".png")) }
                c to TextesDatvPc.lis(prog!!, c.image).orEmpty()
            } }).map { it.get() }
        } finally { pool.shutdown() }
        var seul = 0; var garanti = 0; var faux = 0; var brut = 0
        for ((c, texte) in resultats) {
            val lus = IndicatifsImage.lis(texte)
            val avecService = IndicatifsImage.lis(texte, IndicatifsImage.Connus(service = c.attendu))
            val ok = lus.any { base(it.indicatif) == base(c.attendu) }
            val okService = avecService.any { base(it.indicatif) == base(c.attendu) && it.garant != null }
            val autres = lus.filter { base(it.indicatif) != base(c.attendu) }
            // As read, no sign swapped.
            if (lus.any { base(it.indicatif) == base(c.attendu) && it.corrections == 0 }) brut++
            if (ok) seul++
            if (okService) garanti++
            faux += autres.size
            println("${if (ok) "OK " else "-- "}${if (okService) "S " else "  "}${c.nom} : « ${texte.lines().filter { it.isNotBlank() }.joinToString(" · ").take(160)} »" +
                (if (autres.isEmpty()) "" else " · autres : " + autres.joinToString { it.indicatif }))
        }
        val n = liste.size
        println("LECTURE $n cartes · tel quel : $brut (${100 * brut / n} %) · seul : $seul (${100 * seul / n} %) · avec le service : $garanti (${100 * garanti / n} %) · autres indicatifs : $faux")
        // Floors well under what tesseract 5.3 reads (100 %, 100 %, none other): another version may read a little less.
        assertTrue("$seul / $n", seul * 100 >= n * 85)
        assertTrue("$garanti / $n", garanti * 100 >= n * 90)
        assertTrue("$faux", faux <= n / 20)
    }
}
