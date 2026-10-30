/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.JournalPassage.Marque
import fr.f4ioz.satcombo.domain.JournalPassage.TypeMarque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/** The journal's globe: the request's words, each look drawn, the satellite where it is, the coast cut at the horizon. */
class GlobeRenduPcTest {

    private val t0 = 1_792_510_800_000L   // 2026-10-20 15:40 UTC, the Sun low over France

    /** A pass over France, NW to SE, 10 minutes, 420 km up. */
    private fun scene(style: String, paysage: Boolean = true, o: GlobeRenduPc.Options = GlobeRenduPc.Options()): RenduPc.Scene {
        val sol = (0..40).map { k -> val f = k / 40.0; JournalPassage.Sol(t0 + k * 15_000L, 62.0 - 30 * f, -14.0 + 34 * f, 420.0) }
        val e = JournalPassage.Entree(25544, "ISS", t0, t0 + 600_000L, "JN18DU",
            points = (0..10).map { JournalPassage.Point(t0 + it * 60_000L, (300.0 + it * 15) % 360, if (it <= 5) 5.0 + it * 12 else 65.0 - (it - 5) * 12) })
        val marques = listOf(
            Marque(TypeMarque.QSO, t0 + 240_000L, t0 + 240_000L, "F5XYZ", lat = 43.6, lon = 1.4),
            Marque(TypeMarque.APRS, t0 + 270_000L, t0 + 270_000L, "EA3ABC", lat = 41.4, lon = 2.2))
        return RenduPc.Scene(e, emptyList(), marques, "F4IOZ", sol = sol, qth = 48.85 to 2.35, etiquetteQth = "F4IOZ · JN18DU",
            paysage = paysage, surGlobe = true, styleGlobe = style, optionsGlobe = o, ouverture = false, recap = false)
    }

    private val echantillons = File(System.getenv("SATME_GLOBE") ?: "build/globe-echantillons").apply { mkdirs() }

    @Test
    fun la_demande_dit_le_globe_et_son_style() {
        assertEquals("cartoon", GlobeRenduPc.styleDemande("globe", ""))
        assertEquals("cartoon", GlobeRenduPc.styleDemande("globe", "inconnu"))
        assertEquals("vintage", GlobeRenduPc.styleDemande("globe", "vintage"))
        assertEquals("bleunuit", GlobeRenduPc.styleDemande("globe", "BleuNuit"))
        assertEquals("realiste", GlobeRenduPc.styleDemande(" globe ", "Realiste"))
        assertNull(GlobeRenduPc.styleDemande("", "cartoon"))
        assertNull(GlobeRenduPc.styleDemande("ciel", "cartoon"))
        for (s in GlobeRenduPc.STYLES) assertEquals(s, GlobeRenduPc.styleDemande("globe", s))
    }

    @Test
    fun chaque_style_est_un_choix_de_la_page() {
        // The page's looks (Orbites, Sked, the journal) and the Suivi tab's list: the same as the videos'.
        val js = fr.f4ioz.satcombo.demo.OrbitesJs.JS
        val suivi = fr.f4ioz.satcombo.demo.PagePc.HTML.substringAfter("id=\"st-globe-styles\"").substringBefore("</select>")
        for (s in GlobeRenduPc.STYLES) {
            assertTrue(s, js.contains("\n    $s: { nom: '"))
            assertTrue(s, suivi.contains("<option value=\"$s\">"))
        }
        assertEquals(GlobeRenduPc.STYLES.size, Regex("<option ").findAll(suivi).count())
    }

    private fun proche(c: Int, cible: Color, tol: Int): Boolean =
        Math.abs(((c shr 16) and 255) - cible.red) <= tol && Math.abs(((c shr 8) and 255) - cible.green) <= tol && Math.abs((c and 255) - cible.blue) <= tol

    @Test
    fun chaque_style_dessine_le_globe_et_le_satellite_ou_il_est() {
        val w = 1280; val h = 720
        val instant = t0 + 300_000L
        val vus = HashMap<String, IntArray>()
        for (style in GlobeRenduPc.STYLES) {
            val sc = scene(style)
            val img = RenduPc.image(w, h, sc, instant)
            ImageIO.write(img, "png", File(echantillons, "globe-$style.png"))
            val px = img.getRGB(0, 0, w, h, null, 0, w)
            assertTrue("$style: not blank", px.toSet().size > 400)
            vus[style] = px
            // The view above the caption, as RenduPc lays it out.
            val haut = RenduPc.hautVue(w, h, sc).toFloat()
            val cam = GlobeRenduPc.Camera(GlobeRenduPc.centre(sc, instant), w / 2f, haut / 2, 0.425f * haut)
            // The satellite's colour where it is (at its height).
            val (s, alt) = GlobeRenduPc.sousSatellite(sc.sol, instant)!!
            val k = 1 + alt / 6371.0
            val p = doubleArrayOf(s[0] * k, s[1] * k, s[2] * k)
            val sx = cam.x(p).toInt(); val sy = cam.y(p).toInt()
            // « Bleu nuit »'s satellite is a cyan light, the others' the ISS's yellow.
            val sa = if (style == "bleunuit") Color(0x3fe0c8) else RenduPc.ISS
            var trouve = false
            for (y in sy - 6..sy + 6) for (x in sx - 6..sx + 6) if (proche(img.getRGB(x, y), sa, 40)) trouve = true
            assertTrue("$style: the satellite at ($sx, $sy)", trouve)
            if (style == "cartoon") {
                // The open Atlantic (lit): the cartoon's bright blue.
                val (ax, ay, z) = cam.ecran(44.0, -20.0)
                assertTrue(z > 0.3)
                val c = img.getRGB(ax.toInt(), ay.toInt())
                assertTrue("ocean %06x".format(c and 0xffffff), proche(c, Color(0x35bdf5), 60))
                // Space around the globe is not the ocean.
                assertTrue(!proche(img.getRGB(4, 4), Color(0x35bdf5), 60))
                // Land under France: not the ocean's blue.
                val (fx, fy, _) = cam.ecran(46.5, 2.5)
                val cf = img.getRGB(fx.toInt(), fy.toInt())
                assertTrue("France %06x".format(cf and 0xffffff), (cf and 255) < 150)
            }
        }
        // Seven looks, seven pictures.
        val styles = vus.keys.toList()
        for (i in styles.indices) for (j in i + 1 until styles.size) {
            val a = vus.getValue(styles[i]); val b = vus.getValue(styles[j])
            assertTrue("${styles[i]} vs ${styles[j]}", a.indices.count { a[it] != b[it] } > a.size / 10)
        }
        // The phone's portrait too.
        val portrait = RenduPc.image(720, 960, scene("cartoon", paysage = false), instant)
        ImageIO.write(portrait, "png", File(echantillons, "globe-cartoon-portrait.png"))
        // The screen's HD, as a picture (PNG) is made.
        val hd = RenduPc.image(1920, 1080, scene("cartoon"), instant)
        ImageIO.write(hd, "png", File(echantillons, "globe-cartoon-hd.png"))
        assertTrue(hd.getRGB(0, 0, 1920, 1080, null, 0, 1920).toSet().size > 400)
    }

    @Test
    fun le_globe_suit_sans_saut() {
        val sc = scene("cartoon")
        var avant = GlobeRenduPc.centre(sc, t0)
        var t = t0
        while (t < t0 + 600_000L) {
            t += 2_000L
            val c = GlobeRenduPc.centre(sc, t)
            val ang = Math.toDegrees(Math.acos((avant[0] * c[0] + avant[1] * c[1] + avant[2] * c[2]).coerceIn(-1.0, 1.0)))
            assertTrue("$ang° in 2 s", ang < 0.3)
            avant = c
        }
    }

    @Test
    fun une_terre_coupee_par_l_horizon_ne_peint_pas_l_autre_face() {
        // A band of land from 60° E round the back to 60° W (lon 60 → 300), the view at 0° 0°:
        // seen only at both limbs; the middle of the disc stays sea.
        val ll = ArrayList<Double>()
        for (lo in 60..300 step 2) { ll += lo.toDouble(); ll += -40.0 }
        for (lo in 300 downTo 60 step 2) { ll += lo.toDouble(); ll += 40.0 }
        val a = GlobeRenduPc.anneau(ll.toDoubleArray())!!
        val cam = GlobeRenduPc.Camera(doubleArrayOf(1.0, 0.0, 0.0), 200f, 200f, 180f)
        val p = Path2D.Float(Path2D.WIND_NON_ZERO); val cote = Path2D.Float()
        GlobeRenduPc.decoupe(a, cam, p, cote)
        val b = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics(); g.color = Color.WHITE; g.fill(p); g.dispose()
        fun terre(lat: Double, lon: Double): Boolean { val (x, y, _) = cam.ecran(lat, lon); return b.getRGB(x.toInt(), y.toInt()) and 0xffffff != 0 }
        assertTrue(terre(0.0, 75.0)); assertTrue(terre(0.0, -75.0)); assertTrue(terre(30.0, 70.0))
        for (lo in listOf(-45.0, -20.0, 0.0, 20.0, 45.0)) assertTrue("lon $lo", !terre(0.0, lo))
        assertTrue(!terre(60.0, 75.0)); assertTrue(!terre(-60.0, -75.0))
        // Outside the disc: nothing.
        assertEquals(0, b.getRGB(2, 200) and 0xffffff)
        // A ring wholly behind: nothing at all.
        val derriere = ArrayList<Double>()
        for (k in 0 until 36) { derriere += 180.0 + 20 * cos(k * Math.PI / 18); derriere += 20 * sin(k * Math.PI / 18) }
        val p2 = Path2D.Float()
        GlobeRenduPc.decoupe(GlobeRenduPc.anneau(derriere.toDoubleArray())!!, cam, p2, null)
        assertTrue(p2.bounds2D.isEmpty)
        // A ring wholly in front: itself.
        val devant = ArrayList<Double>()
        for (k in 0 until 36) { devant += 20 * cos(k * Math.PI / 18); devant += 20 * sin(k * Math.PI / 18) }
        val p3 = Path2D.Float()
        GlobeRenduPc.decoupe(GlobeRenduPc.anneau(devant.toDoubleArray())!!, cam, p3, null)
        assertTrue(p3.contains(200.0, 200.0))
    }

    @Test
    fun une_coupure_de_l_antarctique_ne_trace_pas_de_cote() {
        // The ±180° cut and the pole are no coast: not stroked.
        val ll = doubleArrayOf(170.0, -70.0, 180.0, -70.0, 180.0, -90.0, -180.0, -90.0, -180.0, -70.0, -170.0, -70.0, -170.0, -60.0, 170.0, -60.0)
        val a = GlobeRenduPc.anneau(ll)!!
        val cam = GlobeRenduPc.Camera(doubleArrayOf(0.0, 0.0, -1.0), 200f, 200f, 180f)
        val p = Path2D.Float(); val cote = Path2D.Float()
        GlobeRenduPc.decoupe(a, cam, p, cote)
        val b = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics(); g.color = Color.WHITE; g.stroke = java.awt.BasicStroke(2f); g.draw(cote); g.dispose()
        // The pole, at the centre: no line reaches it.
        for (y in 195..205) for (x in 195..205) assertEquals(0, b.getRGB(x, y) and 0xffffff)
    }

    @Test
    fun un_gif_et_une_video_du_globe() {
        val sc = scene("cartoon")
        val gif = RenduPc.gif(sc, File(echantillons, "globe.gif")) {}
        assertTrue(gif.readBytes().copyOf(6).toString(Charsets.US_ASCII) == "GIF89a")
        val ff = RenduPc.ffmpeg() ?: return
        val sc2 = RenduPc.Scene(sc.e, emptyList(), sc.marques, "F4IOZ", sol = sc.sol, qth = sc.qth, paysage = true, surGlobe = true, styleGlobe = "realiste",
            resolution = "XS", ouverture = false, recap = false, segments = listOf(JournalPassage.Segment(t0 + 200_000L, t0 + 220_000L, 1)))
        val mp4 = RenduPc.video(sc2, File(echantillons, "globe.mp4"), null, 22_050, echantillons) {}
        assertTrue("ffmpeg $ff", mp4 != null && mp4.length() > 10_000)
    }

    @Test
    fun les_choix_du_globe_se_lisent_de_la_demande() {
        fun lit(vararg kv: Pair<String, String>) = GlobeRenduPc.options { k -> kv.toMap()[k].orEmpty() }
        assertEquals(GlobeRenduPc.Options(), lit())
        assertEquals(GlobeRenduPc.Options("toutes", "nom", 1, 0f, 0f, 1f), lit("villes" to "n'importe", "etiquettes" to "x", "detail" to "abc", "gz" to "NaN"))
        val o = lit("villes" to "capitales", "etiquettes" to "nomdrapeau", "detail" to "2", "gdx" to "0.25", "gdy" to "-0.1", "gz" to "1.5")
        assertEquals(GlobeRenduPc.Options("capitales", "nomdrapeau", 2, 0.25f, -0.1f, 1.5f), o)
        // Out of their bounds: held to them.
        val b = lit("villes" to "aucune", "etiquettes" to "drapeau", "detail" to "7", "gdx" to "-3", "gdy" to "2", "gz" to "9")
        assertEquals(GlobeRenduPc.Options("aucune", "drapeau", 2, -0.5f, 0.5f, 3f), b)
        assertEquals(0.5f, lit("gz" to "0.1").gz)
        assertEquals(40, GlobeRenduPc.nombreCapitales(0)); assertEquals(100, GlobeRenduPc.nombreCapitales(1))
        assertEquals(fr.f4ioz.satcombo.demo.GlobeDonnees.CAPITALES.size, GlobeRenduPc.nombreCapitales(2))
    }

    private fun pixels(img: BufferedImage) = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    private fun differents(a: IntArray, b: IntArray) = a.indices.count { a[it] != b[it] }

    @Test
    fun chaque_choix_du_globe_change_l_image() {
        val w = 1280; val h = 720; val instant = t0 + 300_000L
        fun rend(o: GlobeRenduPc.Options, nom: String? = null): BufferedImage {
            val img = RenduPc.image(w, h, scene("cartoon", o = o), instant)
            nom?.let { ImageIO.write(img, "png", File(echantillons, it)) }
            return img
        }
        val base = pixels(rend(GlobeRenduPc.Options()))
        val aucune = pixels(rend(GlobeRenduPc.Options(villes = "aucune")))
        val capitales = pixels(rend(GlobeRenduPc.Options(villes = "capitales", etiquettes = "nomdrapeau", detail = 2), "globe-capitales-drapeaux.png"))
        val drapeaux = pixels(rend(GlobeRenduPc.Options(villes = "capitales", etiquettes = "drapeau"), "globe-capitales-codes.png"))
        val noms = pixels(rend(GlobeRenduPc.Options(villes = "capitales", etiquettes = "nom")))
        val sans = pixels(rend(GlobeRenduPc.Options(detail = 0)))
        val tout = pixels(rend(GlobeRenduPc.Options(detail = 2)))
        for ((n, p) in listOf("aucune" to aucune, "capitales" to capitales, "detail 0" to sans, "detail 2" to tout))
            assertTrue(n, differents(base, p) > 500)
        assertTrue(differents(noms, drapeaux) > 500); assertTrue(differents(noms, capitales) > 500)

        // Moved and larger: the globe's middle where asked, the caption below unchanged.
        val o = GlobeRenduPc.Options(villes = "capitales", etiquettes = "nomdrapeau", gdx = 0.2f, gdy = 0.15f, gz = 1.8f)
        val sc = scene("cartoon", o = o)
        val img = rend(o, "globe-decale-zoom.png")
        val haut = RenduPc.hautVue(w, h, sc).toFloat()
        val cam = GlobeRenduPc.camera(sc, instant, w.toFloat(), haut)
        assertEquals(w / 2f + 0.2f * w, cam.ox, 0.01f); assertEquals(haut / 2 + 0.15f * haut, cam.oy, 0.01f)
        assertEquals(0.425f * haut * 1.8f, cam.r, 0.01f)
        // The top left corner, far from the moved globe: space, not the Earth.
        assertTrue(Color(img.getRGB(10, 10)).let { it.blue < 140 && it.green < 100 })
        // The caption is the same as the plain frame's.
        val bas = haut.toInt() + 2
        val a = rend(GlobeRenduPc.Options()).getRGB(0, bas, w, h - bas, null, 0, w); val b = img.getRGB(0, bas, w, h - bas, null, 0, w)
        assertEquals(0, differents(a, b))
        // The satellite still where the camera puts it.
        val (s, alt) = GlobeRenduPc.sousSatellite(sc.sol, instant)!!
        val k = 1 + alt / 6371.0; val p = doubleArrayOf(s[0] * k, s[1] * k, s[2] * k)
        val sx = cam.x(p).toInt(); val sy = cam.y(p).toInt()
        var trouve = false
        for (y in sy - 6..sy + 6) for (x in sx - 6..sx + 6) if (x in 0 until w && y in 0 until h && proche(img.getRGB(x, y), RenduPc.ISS, 40)) trouve = true
        assertTrue("satellite at ($sx, $sy)", trouve)
        // Small and to a side too.
        rend(GlobeRenduPc.Options(gdx = -0.3f, gz = 0.5f), "globe-petit.png")
    }

    @Test
    fun bleu_nuit_un_ocean_profond_des_terres_vertes_et_la_nuit() {
        // 15:45 UTC in October: the Sun low over France, set over the Volga.
        val w = 1280; val h = 720; val instant = t0 + 300_000L
        val o = GlobeRenduPc.Options(villes = "aucune", detail = 0)
        val sc = scene("bleunuit", o = o)
        val img = RenduPc.image(w, h, sc, instant)
        ImageIO.write(img, "png", File(echantillons, "globe-bleunuit-seul.png"))
        val haut = RenduPc.hautVue(w, h, sc).toFloat()
        val cam = GlobeRenduPc.camera(sc, instant, w.toFloat(), haut)
        fun c(lat: Double, lon: Double) = cam.ecran(lat, lon).let { assertTrue(it.third > 0.2); Color(img.getRGB(it.first.toInt(), it.second.toInt())) }
        // The open Atlantic, lit: a deep blue.
        val mer = c(44.0, -20.0)
        assertTrue("mer $mer", mer.blue > mer.red + 40 && mer.blue > mer.green)
        // France in the low Sun: green land, greener than the sea.
        val terre = c(46.5, 2.5)
        assertTrue("terre $terre", terre.green > terre.blue && terre.green > terre.red + 20)
        // The steppe past the twilight: night, nearly black.
        val nuit = c(50.5, 46.0)
        assertTrue("nuit $nuit", nuit.red + nuit.green + nuit.blue < 90)
        // Space round it: the night blue, not the ocean's.
        assertTrue(proche(img.getRGB(4, 4), Color(0x050a12), 30))
    }

    @Test
    fun le_relief_du_dessin_anime_se_voit() {
        // The Alps, near the middle of the view at the satellite's moment: browner than the plain around them.
        val w = 1280; val h = 720; val instant = t0 + 300_000L
        val o = GlobeRenduPc.Options(villes = "aucune", detail = 0, gz = 3f)
        val sc = scene("cartoon", o = o)
        val img = RenduPc.image(w, h, sc, instant)
        ImageIO.write(img, "png", File(echantillons, "globe-relief.png"))
        val haut = RenduPc.hautVue(w, h, sc).toFloat()
        val cam = GlobeRenduPc.camera(sc, instant, w.toFloat(), haut)
        fun c(lat: Double, lon: Double) = cam.ecran(lat, lon).let { Color(img.getRGB(it.first.toInt(), it.second.toInt())) }
        val alpes = c(46.0, 7.9); val beauce = c(48.3, 1.6)
        assertTrue("Alpes $alpes vs plaine $beauce", alpes.red - alpes.green > beauce.red - beauce.green + 15)
    }
}
