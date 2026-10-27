/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.domain.PointSked
import fr.f4ioz.satcombo.domain.TraceSked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The sked's video: the request's words, the file's name, ffmpeg's command, the map's and the globe's frames, both stations and the track where they are. */
class VideoSkedTest {

    private val t0 = 1_792_510_800_000L   // 2026-10-20 15:40 UTC

    /** Paris and IN80 (Spain), a pass over the Bay of Biscay, NW to SE, 14 minutes, 500 km up. */
    private val moi = Observer(48.85, 2.35, 0.0, "QTH")
    private val autre = Observer(40.5, -7.0, 0.0, "IN80")

    private fun vec(lat: Double, lon: Double): DoubleArray {
        val a = Math.toRadians(lat); val b = Math.toRadians(lon)
        return doubleArrayOf(cos(a) * cos(b), cos(a) * sin(b), sin(a))
    }

    /** Azimuth and elevation of the satellite over (lat, lon) at [alt] km, seen from [o]: a sphere's geometry. */
    private fun azEl(o: Observer, lat: Double, lon: Double, alt: Double): Pair<Double, Double> {
        val k = 1 + alt / 6371.0
        val s = vec(lat, lon).map { it * k }
        val up = vec(o.latDeg, o.lonDeg)
        val d = DoubleArray(3) { s[it] - up[it] }
        val n = sqrt(d.sumOf { it * it })
        val el = Math.toDegrees(asin((d[0] * up[0] + d[1] * up[1] + d[2] * up[2]) / n))
        val lo = Math.toRadians(o.lonDeg); val la = Math.toRadians(o.latDeg)
        val est = doubleArrayOf(-sin(lo), cos(lo), 0.0)
        val nord = doubleArrayOf(-sin(la) * cos(lo), -sin(la) * sin(lo), cos(la))
        val az = Math.toDegrees(atan2(d.indices.sumOf { d[it] * est[it] }, d.indices.sumOf { d[it] * nord[it] }))
        return (az + 360) % 360 to el
    }

    private val trace: TraceSked = run {
        val pts = (0..84).map { k ->
            val f = k / 84.0
            val lat = 62.0 - 34 * f; val lon = -22.0 + 36 * f
            val (azA, elA) = azEl(moi, lat, lon, 500.0); val (azB, elB) = azEl(autre, lat, lon, 500.0)
            PointSked(t0 + k * 10_000L, azA, elA, azB, elB, lat, lon, 500.0)
        }
        TraceSked(moi, autre, pts)
    }

    /** The common window: where both see it. */
    private val fenetre: LongRange = trace.points.filter { it.elMoi >= 0 && it.elAutre >= 0 }.let { it.first().t..it.last().t }

    private fun sked() = VideoSked.Sked(trace, fenetre.first, fenetre.last, "ISS", "F4IOZ", "IN80", locMoi = "jn18du")

    private val echantillons = File(System.getenv("SATME_GLOBE") ?: "build/sked-echantillons").apply { mkdirs() }

    private fun proche(c: Int, cible: Color, tol: Int): Boolean =
        Math.abs(((c shr 16) and 255) - cible.red) <= tol && Math.abs(((c shr 8) and 255) - cible.green) <= tol && Math.abs((c and 255) - cible.blue) <= tol

    private fun autour(img: BufferedImage, x: Int, y: Int, r: Int, cible: Color, tol: Int): Boolean {
        for (j in y - r..y + r) for (i in x - r..x + r)
            if (i in 0 until img.width && j in 0 until img.height && proche(img.getRGB(i, j), cible, tol)) return true
        return false
    }

    @Test
    fun la_demande_dit_la_carte_ou_le_globe_et_ses_choix() {
        fun lit(vararg kv: Pair<String, String>) = VideoSked.rendu { k -> kv.toMap()[k].orEmpty() }
        assertEquals(VideoSked.Rendu(), lit())
        assertFalse(lit().globe); assertFalse(lit().vertical)
        val r = lit("vue" to "globe", "format" to "telephone", "style" to "vintage", "camera" to "poursuite", "traces" to "80",
            "villes" to "aucune", "etiquettes" to "drapeau", "detail" to "2", "monuments" to "aucun", "montaille" to "1.6")
        assertTrue(r.globe); assertTrue(r.vertical)
        assertEquals("vintage", r.style); assertEquals("poursuite", r.camera); assertEquals(80, r.traces)
        assertEquals("aucune", r.options.villes); assertEquals("drapeau", r.options.etiquettes); assertEquals(2, r.options.detail)
        assertEquals("aucun", r.options.monuments); assertEquals(1.6f, r.options.tailleMonuments, 1e-6f)
        assertEquals(720, r.largeur); assertEquals(1280, r.hauteur)
        // Wrong words: the defaults (the page's « vue du satellite » or « depuis ma station » are taken as they come).
        val f = lit("vue" to "globe", "style" to "inconnu", "camera" to "fusée", "traces" to "500", "format" to "carré", "montaille" to "NaN")
        assertEquals("cartoon", f.style); assertEquals("espace", f.camera); assertEquals(100, f.traces); assertFalse(f.vertical)
        assertEquals(1f, f.options.tailleMonuments, 1e-6f)
        assertEquals("sol", lit("camera" to "sol").camera); assertEquals("satellite", lit("camera" to " Satellite ").camera)
        assertEquals(1280, f.largeur); assertEquals(720, f.hauteur)
        for (s in GlobeRenduPc.STYLES) assertEquals(s, lit("vue" to "globe", "style" to s).style)
    }

    @Test
    fun le_nom_et_la_commande_de_ffmpeg() {
        val s = sked()
        assertTrue(s.etiquetteMoi, s.etiquetteMoi == "F4IOZ · JN18DU")
        val n = VideoSked.nomFichier(s, VideoSked.Rendu(globe = true, vertical = true))
        assertTrue(n, n.startsWith("SatMe_Sked_ISS_IN80_20261020_") && n.endsWith("_globe_9x16.mp4"))
        assertTrue(VideoSked.nomFichier(s, VideoSked.Rendu()).matches(Regex("SatMe_Sked_ISS_IN80_20261020_\\d{4}Z\\.mp4")))
        val sortie = File(echantillons, n)
        val c = RenduPc.commandeEncode("ffmpeg", 720, 1280, VideoSked.FPS, (VideoSked.DUREE_S + VideoSked.FIN_S) * VideoSked.FPS, null, sortie, crf = 24, faststart = true)
        fun apres(k: String) = c[c.indexOf(k) + 1]
        assertEquals("720x1280", apres("-s")); assertEquals("10", apres("-r")); assertEquals("27.00", apres("-t"))
        assertEquals("24", apres("-crf")); assertEquals("+faststart", apres("-movflags")); assertEquals("libx264", apres("-c:v"))
        assertEquals(sortie.path, c.last())
        assertFalse(c.contains("-c:a"))
        // The journal's keep theirs: no faststart.
        assertFalse(RenduPc.commandeEncode("ffmpeg", 1280, 720, 25, 100, null, sortie).contains("-movflags"))
        // From the first instant to the last.
        assertEquals(trace.points.first().t, VideoSked.instant(s, 0, 240)); assertEquals(trace.points.last().t, VideoSked.instant(s, 239, 240))
        val (eMoi, eLui) = s.elMax
        assertTrue("$eMoi $eLui", eMoi > 5 && eLui > 5)
    }

    @Test
    fun le_globe_montre_les_deux_stations_et_la_fenetre_verte() {
        for (vertical in listOf(false, true)) {
            val r = VideoSked.Rendu(globe = true, vertical = vertical, style = "cartoon")
            val d = VideoSked.Dessin(sked(), r) { _, _, _ -> null }
            val t = trace.points.first().t
            val img = d.image(t)
            assertEquals(r.largeur, img.width); assertEquals(r.hauteur, img.height)
            ImageIO.write(img, "png", File(echantillons, "sked-globe-${if (vertical) "9x16" else "16x9"}.png"))
            val cam = d.camera(t)
            for ((o, c) in listOf(moi to VideoSked.AMBRE, autre to VideoSked.ROSE)) {
                val (x, y, z) = cam.ecran(o.latDeg, o.lonDeg)
                assertTrue(z > 0.3)
                // In the view, under the band.
                assertTrue("$x", x > 0 && x < d.vw); assertTrue("$y", y > VideoSked.BANDE && y < d.vh)
                // The pin in the station's colour just above the place.
                assertTrue("pin ${o.name} at ($x, $y)", autour(img, x.toInt(), (y - 6 * d.dp).toInt(), 2, c, 20))
            }
            // The common window green under the satellite's path, at its middle.
            val m = trace.instant((fenetre.first + fenetre.last) / 2)
            val (mx, my, _) = cam.ecran(m.lat, m.lon)
            assertTrue("window at ($mx, $my)", autour(img, mx.toInt(), my.toInt(), 3, Color(46, 158, 91), 30))
            // The skies and the clock: the panel's dark.
            val px = if (vertical) 10 to 1270 else 1270 to 710
            assertTrue(proche(img.getRGB(px.first, px.second), Color(11, 16, 22), 2))
        }
    }

    @Test
    fun chaque_rendu_et_chaque_camera_dessinent_sans_erreur() {
        val s = sked()
        val t = (fenetre.first + fenetre.last) / 2
        for (style in GlobeRenduPc.STYLES) {
            val img = VideoSked.Dessin(s, VideoSked.Rendu(globe = true, style = style)) { _, _, _ -> null }.image(t)
            assertTrue(style, img.getRGB(0, 0, 880, 720, null, 0, 880).toSet().size > 300)
            ImageIO.write(img, "png", File(echantillons, "sked-$style.png"))
        }
        for (cam in GlobeRenduPc.CAMERAS_SKED) {
            val d = VideoSked.Dessin(s, VideoSked.Rendu(globe = true, camera = cam, vertical = true)) { _, _, _ -> null }
            // Two instants: a moving view draws its Earth again, a still one reuses it.
            d.image(t); val img = d.image(t + 20_000L)
            ImageIO.write(img, "png", File(echantillons, "sked-camera-$cam.png"))
            val c = d.camera(t)
            assertTrue(cam, c.r > 0 && c.ox > 0 && c.oy > VideoSked.BANDE)
        }
        // The still view frames both stations whatever the time; following the satellite moves it.
        val d = VideoSked.Dessin(s, VideoSked.Rendu(globe = true)) { _, _, _ -> null }
        assertTrue(d.camera(trace.points.first().t).c.contentEquals(d.camera(trace.points.last().t).c))
        val p = VideoSked.Dessin(s, VideoSked.Rendu(globe = true, camera = "poursuite")) { _, _, _ -> null }
        assertFalse(p.camera(trace.points.first().t).c.contentEquals(p.camera(trace.points.last().t).c))
        // The map too, both formats, without tiles.
        for (v in listOf(false, true)) {
            val img = VideoSked.Dessin(s, VideoSked.Rendu(vertical = v)) { _, _, _ -> null }.image(t)
            ImageIO.write(img, "png", File(echantillons, "sked-carte-${if (v) "9x16" else "16x9"}.png"))
            assertEquals(if (v) 1280 else 720, img.height)
        }
    }

    @Test
    fun la_video_du_globe_se_fait() {
        RenduPc.ffmpeg() ?: return
        val dossier = File(echantillons, "videos").apply { mkdirs() }
        val v = VideoSked(dossier) { _, _, _ -> null }
        val r = VideoSked.Rendu(globe = true, vertical = true, options = GlobeRenduPc.Options(villes = "aucune", detail = 0))
        assertTrue(v.lance(sked(), r))
        assertFalse("one at a time", v.lance(sked(), r))
        val fin = System.currentTimeMillis() + 300_000L
        while (v.etat.enCours && System.currentTimeMillis() < fin) Thread.sleep(200)
        assertEquals("", v.etat.erreur)
        val f = v.fichier(v.etat.nom)
        assertTrue(v.etat.nom, f != null && f.length() > 20_000 && f.name.endsWith("_globe_9x16.mp4"))
    }
}
