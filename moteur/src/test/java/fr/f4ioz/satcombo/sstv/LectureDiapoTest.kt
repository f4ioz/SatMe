/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.demo.ApiPlancheEvenement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Random
import javax.imageio.ImageIO

/**
 * The footer of the ARISS slides read ([LectureDiapo]) on pictures made
 * here, 320×240 as Robot 36 gives them: a dark slide with its footer in
 * a series' own way (yellow and thin as series 33, white and bold as
 * series 34), cut by the picture's bottom edge or moved up, with noise;
 * « Relancer l'analyse » on the event's pictures only.
 */
class LectureDiapoTest {

    private fun hasard(graine: Long) = Random(graine)

    /** A dark picture with speckle (sigma [bruit]). */
    private fun fond(r: Random, bruit: Double): IntArray = IntArray(320 * 240) {
        val v = (14 + r.nextGaussian() * bruit).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or (v / 2)
    }

    /** [motif] ([w] wide) painted at ([x0], [y0]) in [coul] (rows below the picture lost), with noise. */
    private fun peint(px: IntArray, motif: FloatArray, w: Int, x0: Int, y0: Int, coul: Triple<Float, Float, Float>, r: Random, bruit: Double) {
        for (i in motif.indices) {
            val x = x0 + i % w; val y = y0 + i / w
            if (x !in 0..319 || y !in 0..239) continue
            fun c(k: Float) = (motif[i] * k + r.nextGaussian() * bruit).toInt().coerceIn(0, 255)
            px[y * 320 + x] = (0xFF shl 24) or (c(coul.first) shl 16) or (c(coul.second) shl 8) or c(coul.third)
        }
    }

    private val JAUNE = Triple(1f, 0.9f, 0.3f)
    private val BLANC = Triple(1f, 1f, 1f)

    /** A slide of [style]: its footer « n/12 » with the « /12 » at ([x], [y]), its series' number above. */
    private fun diapo(style: LectureDiapo.Style, n: Int, x: Int, y: Int, coul: Triple<Float, Float, Float>, graine: Long, bruit: Double = 10.0): IntArray {
        val r = hasard(graine)
        val px = fond(r, bruit)
        peint(px, style.nombres.getValue(n), style.zl, x - style.zl, y, coul, r, bruit)
        peint(px, style.barre, style.bl, x, y, coul, r, bruit)
        style.motifSerie?.let { peint(px, it, style.sl, x + style.sx, y + style.sy, coul, r, bruit) }
        return px
    }

    @Test fun la_serie_33_en_jaune_coupee_par_le_bas() {
        val s = LectureDiapo.SERIE_33
        // As received: the second line cut two rows short, then four (a picture begun late is moved down by its start).
        for ((n, y) in listOf(7 to 230, 3 to 230, 10 to 230, 6 to 232, 8 to 222)) {
            val l = LectureDiapo.litTout(diapo(s, n, 173, y, JAUNE, n * 31L + y), 320, 240)
            assertEquals("$n à y=$y", n, l.diapo)
            assertEquals(33, l.serie)
        }
    }

    @Test fun le_bas_de_la_diapo_remonte_au_milieu_de_l_image() {
        // A picture begun late: the slide's bottom in the middle of it, noise under it.
        val s = LectureDiapo.SERIE_33
        val px = diapo(s, 9, 173, 158, JAUNE, 5L)
        val r = hasard(6)
        for (y in 170 until 240) for (x in 0 until 320) if (r.nextInt(3) == 0) px[y * 320 + x] = (0xFF shl 24) or (r.nextInt(256) shl 8)
        assertEquals(LectureDiapo.Lecture(9, 12, 33), LectureDiapo.litTout(px, 320, 240))
    }

    @Test fun les_numeros_jamais_recus_de_la_serie_33_se_lisent_aussi() {
        // 1, 11 and 12 put together from the others' figures.
        for (n in listOf(1, 11, 12)) assertEquals(n, LectureDiapo.lit(diapo(LectureDiapo.SERIE_33, n, 173, 230, JAUNE, n.toLong()), 320, 240))
    }

    @Test fun la_serie_34_en_blanc_et_son_numero() {
        val s = LectureDiapo.SERIE_34
        for (n in listOf(2, 9, 11)) {
            val l = LectureDiapo.litTout(diapo(s, n, 173, 219, BLANC, 100L + n), 320, 240)
            assertEquals(n, l.diapo); assertEquals(34, l.serie)
        }
    }

    @Test fun trop_de_bruit_ou_rien_a_lire_ne_donne_aucun_numero() {
        val r = hasard(9)
        // Snow over the whole picture (a lost signal).
        val neige = IntArray(320 * 240) { (0xFF shl 24) or (r.nextInt(256) shl 16) or (r.nextInt(256) shl 8) or r.nextInt(256) }
        assertEquals(LectureDiapo.Lecture(0), LectureDiapo.litTout(neige, 320, 240))
        // A dark picture: nothing.
        assertEquals(0, LectureDiapo.lit(fond(r, 10.0), 320, 240))
        // The footer drowned in noise: not read rather than read wrong.
        for (g in 1L..6L) {
            val l = LectureDiapo.litTout(diapo(LectureDiapo.SERIE_33, 7, 173, 230, JAUNE, g, bruit = 70.0), 320, 240)
            assertTrue("bruit $g : ${l.diapo}", l.diapo == 0 || l.diapo == 7)
        }
    }

    /** « СЕРИЯ 33 / SLIDE n/12 » written with another font than ARISS's: never a wrong number. */
    @Test fun une_autre_police_ne_fait_pas_lire_un_faux_numero() {
        for (n in listOf(3, 5, 7, 12)) for (taille in listOf(9, 10, 11)) {
            val b = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
            val g = b.createGraphics()
            g.color = Color(12, 12, 20); g.fillRect(0, 0, 320, 240)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, taille)
            g.color = Color(200, 180, 60)
            g.drawString("СЕРИЯ 33", 140, 222); g.drawString("SLIDE $n/12", 140, 238)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 26); g.drawString("NA1SS", 10, 236); g.drawString("RS0ISS", 222, 236)
            g.dispose()
            val r = hasard(n * 7L + taille)
            for (y in 0 until 240) for (x in 0 until 320) {
                val c = b.getRGB(x, y)
                fun k(v: Int) = (v + r.nextGaussian() * 8).toInt().coerceIn(0, 255)
                b.setRGB(x, y, (k((c shr 16) and 0xFF) shl 16) or (k((c shr 8) and 0xFF) shl 8) or k(c and 0xFF))
            }
            val lu = LectureDiapo.lit(b.getRGB(0, 0, 320, 240, null, 0, 320), 320, 240)
            assertTrue("SLIDE $n/12 en $taille px lu $lu", lu == 0 || lu == n)
        }
    }

    // ------------------------------------------------------------ the series said by the event's name

    private fun ev(nom: String) = EvenementSstv.Evenement("c:x", EvenementSstv.Genre.CAMPAGNE, nom, "ISS", 25544, T0, T0 + 4 * 86_400_000L, 158.97)

    @Test fun le_numero_de_serie_du_nom_et_les_images_d_une_autre_serie() {
        assertEquals(34, EvenementSstv.numeroSerie(ev("ARISS SSTV Series 34")))
        assertEquals(33, EvenementSstv.numeroSerie(ev("ARISS série 33 — Student Education")))
        assertEquals(35, EvenementSstv.numeroSerie(ev("SSTV serie n° 35")))
        assertEquals(0, EvenementSstv.numeroSerie(ev("SSTV event requested by the organizers")))
        val e = ev("ARISS SSTV Series 34")
        val s = SstvMeta.SstvShot(fileName = "a.png", satName = "ISS", timeMs = T0 + 3_600_000L)
        assertTrue(EvenementSstv.dans(e, s, 0)); assertTrue(EvenementSstv.dans(e, s, 34))
        // Its footer says series 33: not a picture of series 34, though received during it.
        assertFalse(EvenementSstv.dans(e, s, 33))
        // An event whose name says no series takes it.
        assertTrue(EvenementSstv.dans(ev("ISS · SSTV 03/10"), s, 33))
    }

    @Test fun le_groupe_dit_la_serie_que_lisent_ses_images() {
        val px = diapo(LectureDiapo.SERIE_33, 4, 173, 230, JAUNE, 3L)
        val a = ClassementSstv.avecLu(ClassementSstv.analyse(px, 320, 240, 240, true), LectureDiapo.litTout(px, 320, 240))
        assertEquals(4, a.lu); assertEquals(33, a.serie)
        val g = ClassementSstv.groupe(listOf(SstvMeta.SstvShot(fileName = "a.png", satName = "ISS", timeMs = T0)), mapOf("a.png" to a)).single()
        assertEquals(4, g.diapo); assertEquals(33, g.serie); assertEquals(ClassementSstv.Origine.LU, g.origine)
    }

    // ------------------------------------------------------------ « Relancer l'analyse » on the event only

    @Test fun relancer_l_analyse_seulement_sur_les_images_de_l_evenement() {
        val dir = Files.createTempDirectory("relance-ev").toFile()
        try {
            val noms = listOf("dans1.png", "dans2.png", "hors.png")
            val temps = listOf(T0 + 3_600_000L, T0 + 3_800_000L, T0 + 20 * 86_400_000L)
            for ((i, n) in noms.withIndex()) {
                val b = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
                b.setRGB(0, 0, 320, 240, diapo(LectureDiapo.SERIE_33, 2 + i, 173, 230, JAUNE, i.toLong()), 0, 320)
                ImageIO.write(b, "png", File(dir, n))
                SstvMeta.change(File(dir, n)) { it.copy(satName = "ISS", timeMs = temps[i], mode = "Robot36") }
            }
            fun shots() = noms.map { n -> SstvMeta.decode(n, File(dir, SstvMeta.sidecarName(n)).readText()) }
            val lus = ArrayList<String>()
            val galerie = ClassementGalerie { f -> synchronized(lus) { lus += f.name }; ImageIO.read(f).let { ClassementGalerie.Pixels(it.getRGB(0, 0, it.width, it.height, null, 0, it.width), it.width, it.height) } }
            val src = object : ApiPlancheEvenement.Source {
                override val dossier = dir
                override val classement = galerie
                override fun images() = shots()
                override fun evenements() = listOf(ev("ARISS série 33"))
                override fun catnumDe(sat: String) = 25544
                override fun passes(catnum: Int, deMs: Long, aMs: Long): List<SatPass> = emptyList()
                override fun positions(catnum: Int, ts: List<Long>): List<Pair<Double, Double>>? = null
                override fun epoqueElements(catnum: Int): Long? = null
                override fun elMin() = 10.0
                override fun station() = RapportEvenement.Station("F4IOZ", "JN18FS")
                override fun chaine(plan: String): Pair<String, List<List<RapportEvenement.Etape>>>? = null
                override fun planche(m: Planche.Modele): ByteArray? = null
                override fun vignette(nom: String): ByteArray? = null
                override fun maintenant() = T0 + 2 * 86_400_000L
            }
            val rangement = Planche.Rangement(File(dir, "planches"))
            val m = Planche.Modele("p1", "Série 33", evenement = ev("ARISS série 33"), evenementMain = true)
            rangement.enregistre(m)
            fun route(r: String, vararg p: Pair<String, String>): JSONObject {
                val q = p.toMap()
                val rep = ApiPlancheEvenement.sert(r, { q[it].orEmpty() }, src, rangement, Any()) { id -> rangement.modeles().firstOrNull { it.id == id } }!!
                return JSONObject(String(rep.corps, Charsets.UTF_8))
            }
            galerie.etat(dir, shots(), attendre = true)
            assertEquals(3, lus.size)
            // The event's pictures only: two measured again, the third kept.
            val j = route("/relance", "id" to "p1", "ev" to "1")
            assertEquals(2, j.getInt("n")); assertTrue(j.getBoolean("evenement"))
            var a: JSONObject
            var tours = 0
            do { Thread.sleep(50); a = route("/analyse", "id" to "p1", "ev" to "1") } while (a.getInt("restent") > 0 && ++tours < 200)
            assertEquals(0, a.getInt("restent")); assertEquals(2, a.getInt("total")); assertEquals(2, a.getInt("lues"))
            assertEquals(listOf("dans1.png", "dans2.png"), lus.drop(3).sorted())
            assertEquals(1, galerie.mesurees(dir, shots().filter { it.fileName == "hors.png" }))
            // All of them.
            assertEquals(3, route("/relance", "id" to "p1", "oublie" to "0").getInt("n"))
        } finally { dir.deleteRecursively() }
    }

    private companion object {
        /** 3/10/2026 12:00 UTC. */
        const val T0 = 1_791_028_800_000L
    }
}
