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
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** A session's video: its words, its frames' plan in both modes, its frames, its MP4. */
class SessionRenduPcTest {

    private val t0 = 1_792_510_800_000L   // 2026-10-20 15:40 UTC
    private val echantillons = File(System.getenv("SATME_SESSION") ?: "build/session-echantillons").apply { mkdirs() }

    /** A picture received: bands of colour, numbered by its shade. */
    private fun photo(n: Int): String {
        val b = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics()
        for (k in 0 until 8) { g.color = Color.getHSBColor((n * 0.21f + k * 0.1f) % 1f, 0.7f, 0.95f); g.fillRect(0, k * 30, 320, 30) }
        g.color = Color.WHITE; g.font = g.font.deriveFont(60f); g.drawString("SSTV $n", 40, 140); g.dispose()
        val f = File(echantillons, "photo-$n.png"); ImageIO.write(b, "png", f); return f.path
    }

    /** Three passes of 6 minutes, 95 minutes apart, over France; pictures in the first and the last, a contact in the second. */
    private fun scenes(vignettes: Boolean = true, police: Float = 1f, tz: java.util.TimeZone = LegendePc.UTC): List<RenduPc.Scene> = (0..2).map { k ->
        val de = t0 + k * 95 * 60_000L
        val sol = (0..24).map { s -> val f = s / 24.0; JournalPassage.Sol(de + s * 15_000L, 60.0 - 28 * f, -18.0 + 8 * k + 32 * f, 420.0) }
        val e = JournalPassage.Entree(25544, "ISS", de, de + 360_000L, "JN18DU",
            points = (0..6).map { JournalPassage.Point(de + it * 60_000L, (300.0 + it * 20) % 360, if (it <= 3) 5.0 + it * 15 else 50.0 - (it - 3) * 15) })
        val marques = when (k) {
            0 -> listOf(Marque(TypeMarque.SSTV, de + 60_000L, de + 96_000L, "Robot 36", fichier = photo(1)))
            1 -> listOf(Marque(TypeMarque.QSO, de + 150_000L, de + 150_000L, "F5XYZ", lat = 43.6, lon = 1.4))
            else -> listOf(Marque(TypeMarque.SSTV, de + 90_000L, de + 126_000L, "Robot 36", fichier = photo(2)),
                Marque(TypeMarque.SSTV, de + 200_000L, de + 236_000L, "Robot 36", fichier = photo(3)))
        }
        RenduPc.Scene(e, emptyList(), marques, "F4IOZ", sol = sol, qth = 48.85 to 2.35, etiquetteQth = "F4IOZ · JN18DU",
            paysage = true, surGlobe = true, styleGlobe = "cartoon", resolution = "XS", police = police, fuseau = tz).also { sc ->
            // Between the passes: an orbit of 92 minutes, inclined 51.6°, the Earth turning under it.
            sc.horsTrace = { t ->
                val a = 2 * Math.PI * (t - t0) / (92 * 60_000.0)
                val la = Math.toDegrees(Math.asin(Math.sin(Math.toRadians(51.6)) * Math.sin(a)))
                val lo = Math.toDegrees(Math.atan2(Math.cos(Math.toRadians(51.6)) * Math.sin(a), Math.cos(a))) - (t - t0) / 240_000.0 - 20
                JournalPassage.Sol(t, la, ((lo + 540) % 360) - 180, 420.0)
            }
        }
    }

    @Test
    fun la_demande_dit_les_passages_le_mode_et_les_allures() {
        fun lit(vararg kv: Pair<String, String>) = SessionRenduPc.parametres { k -> kv.toMap()[k].orEmpty() }
        val d = lit()
        assertEquals(emptyList<String>(), d.ids); assertEquals("passages", d.mode)
        assertEquals(SessionRenduPc.Vitesses(1000.0, 20.0, 3.0), d.v)
        assertNull(d.titre); assertTrue(!d.vignettes)
        val c = lit("ids" to " 25544_1, 25544_2,,25544_1 ", "mode" to "continu", "vHors" to "5000", "vPassage" to "50", "vImage" to "1.5",
            "titre" to "  Ma semaine ", "vignettes" to "1")
        assertEquals(listOf("25544_1", "25544_2"), c.ids); assertEquals("continu", c.mode)
        assertEquals(SessionRenduPc.Vitesses(5000.0, 50.0, 1.5), c.v)
        assertEquals("Ma semaine", c.titre); assertTrue(c.vignettes)
        // Held to their bounds.
        assertEquals(SessionRenduPc.Vitesses(20_000.0, 500.0, 100.0), lit("vHors" to "1e9", "vPassage" to "9999", "vImage" to "400").v)
        assertEquals(SessionRenduPc.Vitesses(1.0, 1.0, 0.5), lit("vHors" to "0.1", "vPassage" to "0.2", "vImage" to "0.1").v)
        assertEquals(SessionRenduPc.Vitesses(1000.0, 20.0, 3.0), lit("vHors" to "abc", "vPassage" to "-4", "vImage" to "NaN").v)
        // The old words when the new ones are missing: vPassage = vitesse, vHors = vitesse × entre (10 by default).
        assertEquals(SessionRenduPc.Vitesses(300.0, 30.0, 3.0), lit("vitesse" to "30").v)
        assertEquals(SessionRenduPc.Vitesses(1500.0, 30.0, 3.0), lit("vitesse" to "30", "entre" to "50").v)
        assertEquals(SessionRenduPc.Vitesses(700.0, 30.0, 3.0), lit("vitesse" to "30", "entre" to "50", "vHors" to "700").v)
        assertEquals(SessionRenduPc.Vitesses(20_000.0, 500.0, 3.0), lit("vitesse" to "6000").v)
        assertEquals("passages", lit("mode" to "autre").mode)
    }

    /** Real time per video time between each frame and the next, by frame. */
    private fun allures(plan: List<SessionRenduPc.Trame>) = plan.zipWithNext().map { (a, b) -> (b.t - a.t) * SessionRenduPc.FPS / 1000.0 }

    @Test
    fun le_plan_des_images_dans_les_deux_modes() {
        val sc = scenes()
        val f = sc.map { (it.e.debutMs - 30_000L)..(it.e.finMs + 30_000L) }
        val actifs = SessionRenduPc.actifs(sc)
        // The three pictures (the contact has no sound recorded): 3 s around each.
        assertEquals(3, actifs.size); assertEquals(sc[0].e.debutMs + 57_000L, actifs[0].first)
        // « passages »: a turn between two, the passes' frames in order.
        val p = SessionRenduPc.plan(f, actifs, "passages", SessionRenduPc.Vitesses())
        assertEquals(2 * SessionRenduPc.tourne(1.5), p.count { it is SessionRenduPc.Trame.Tourne })
        assertTrue(p.zipWithNext().all { (a, b) -> b.t >= a.t })
        // « continu »: the time runs on, from the first pass's start to the last one's end.
        val c = SessionRenduPc.plan(f, actifs, "continu", SessionRenduPc.Vitesses())
        assertTrue(c.zipWithNext().all { (a, b) -> b.t > a.t })
        assertEquals(f.first().first, c.first().t); assertEquals(f.last().last, c.last().t)
        for (i in f.indices) assertTrue(c.any { it is SessionRenduPc.Trame.Passe && it.i == i })
        // Frames per real minute: many more while a picture comes than in the rest of the pass, and in the pass than outside.
        fun parMinute(dans: (Long) -> Boolean): Double {
            val n = c.count { dans(it.t) }
            var reel = 0L; var t = f.first().first
            while (t < f.last().last) { if (dans(t)) reel += 1000; t += 1000 }
            return n / (reel / 60_000.0)
        }
        val image = parMinute { t -> actifs.any { t in it } }
        val passe = parMinute { t -> f.any { t in it } && actifs.none { t in it } }
        val dehors = parMinute { t -> f.none { t in it } }
        assertTrue("image $image, passe $passe", image > passe * 3)
        assertTrue("passe $passe, dehors $dehors", passe > dehors * 10)
        // Far from the passes, the pace is vHors: ×1000, 125 s a frame.
        assertTrue(allures(c).count { Math.abs(it - 1000.0) < 1 } > 10)
    }

    @Test
    fun l_allure_change_sans_a_coup() {
        val sc = scenes()
        val f = sc.map { (it.e.debutMs - 30_000L)..(it.e.finMs + 30_000L) }
        for (mode in listOf("continu", "passages")) for (v in listOf(SessionRenduPc.Vitesses(), SessionRenduPc.Vitesses(20_000.0, 500.0, 0.5), SessionRenduPc.Vitesses(50.0, 100.0, 100.0))) {
            val plan = SessionRenduPc.plan(f, SessionRenduPc.actifs(sc), mode, v)
            val allure = SessionRenduPc.Allure(f, SessionRenduPc.actifs(sc), mode == "continu", v)
            // From one frame to the next, the pace changes by about e^(±EASE / FPS) at most (×0.75 … ×1.28), never a jump.
            val k = Math.exp(SessionRenduPc.EASE / SessionRenduPc.FPS) * 1.02; val kBas = 1 - SessionRenduPc.EASE / SessionRenduPc.FPS - 0.01
            plan.filter { it !is SessionRenduPc.Trame.Tourne }.zipWithNext().filter { (a, b) -> mode == "continu" || (a as? SessionRenduPc.Trame.Passe)?.i == (b as? SessionRenduPc.Trame.Passe)?.i }
                .forEach { (a, b) ->
                    val r = allure.a(b.t) / allure.a(a.t)
                    assertTrue("$mode $v ${a.t - t0} → ${b.t - t0}: ×$r", r <= k && r >= kBas)
                }
            // Each pace is reached.
            assertTrue(plan.any { Math.abs(allure.a(it.t) - v.image) < 1e-6 })
        }
    }

    private fun proche(c: Int, cible: Color, tol: Int) =
        Math.abs(((c shr 16) and 255) - cible.red) <= tol && Math.abs(((c shr 8) and 255) - cible.green) <= tol && Math.abs((c and 255) - cible.blue) <= tol

    @Test
    fun les_images_de_la_session() {
        val s = SessionRenduPc.Session(scenes(), "passages", SessionRenduPc.Vitesses(passage = 30.0), titre = null, vignettes = true)
        assertEquals(3, s.images.size)
        val w = 1280; val h = 720
        val plan = SessionRenduPc.plan(s)
        // The last pass, its second picture arriving: the strip holds the two before it.
        val arrive = plan.first { it is SessionRenduPc.Trame.Passe && it.i == 2 && it.t >= s.scenes[2].e.debutMs + 215_000L }
        val img = SessionRenduPc.image(s, arrive, w, h)
        ImageIO.write(img, "png", File(echantillons, "session-image-arrive.png"))
        // The strip, bottom right of the view: the picture's colours, framed in orange.
        val dp = RenduPc.echelle(w, h); val haut = RenduPc.hautVue(w, h, s.scenes[2]).toFloat()
        var orange = 0
        for (y in (haut - 50 * dp).toInt() until haut.toInt()) for (x in (w - 200 * dp).toInt() until w) if (proche(img.getRGB(x, y), RenduPc.SSTV, 30)) orange++
        assertTrue("strip $orange", orange > 50)
        // The turn to the second pass, its card in the middle.
        val tourne = plan.filterIsInstance<SessionRenduPc.Trame.Tourne>().first { it.i == 1 && it.k >= 0.45 }
        val ti = SessionRenduPc.image(s, tourne, w, h)
        ImageIO.write(ti, "png", File(echantillons, "session-transition.png"))
        // Inside the card, above its words: dark.
        val dans = Color(ti.getRGB(w / 2, (haut / 2 - 30 * dp).toInt()))
        assertTrue("$dans", dans.red < 70 && dans.green < 70)
        // Turning: the camera half way, different from both ends.
        val a = SessionRenduPc.image(s, plan.filterIsInstance<SessionRenduPc.Trame.Tourne>().first { it.i == 1 }, w, h)
        val b = SessionRenduPc.image(s, plan.filterIsInstance<SessionRenduPc.Trame.Tourne>().last { it.i == 1 }, w, h)
        assertTrue(a.getRGB(0, 0, w, h, null, 0, w).contentEquals(b.getRGB(0, 0, w, h, null, 0, w)).not())
        // « continu », between two passes: the satellite where its elements put it.
        val c = SessionRenduPc.Session(scenes(), "continu", SessionRenduPc.Vitesses(hors = 6000.0, passage = 600.0), vignettes = true)
        val entre = SessionRenduPc.plan(c).filterIsInstance<SessionRenduPc.Trame.Entre>().let { it[it.size / 2] }
        ImageIO.write(SessionRenduPc.image(c, entre, w, h), "png", File(echantillons, "session-entre.png"))
        ImageIO.write(SessionRenduPc.titre(s, w, h), "png", File(echantillons, "session-titre.png"))
        // Larger texts, the PC's own time: the turn's card and the caption.
        val s2 = SessionRenduPc.Session(scenes(police = 2f, tz = java.util.TimeZone.getTimeZone("Europe/Paris")), "passages", SessionRenduPc.Vitesses(passage = 30.0), vignettes = true, transition = 3.0)
        val p2 = SessionRenduPc.plan(s2)
        assertEquals(2 * 24, p2.count { it is SessionRenduPc.Trame.Tourne })
        ImageIO.write(SessionRenduPc.image(s2, p2.filterIsInstance<SessionRenduPc.Trame.Tourne>().first { it.i == 2 && it.k >= 0.45 }, w, h), "png",
            File(echantillons, "session-transition-police2-locale.png"))
        ImageIO.write(SessionRenduPc.titre(s2, w, h), "png", File(echantillons, "session-titre-police2.png"))
        val m = SessionRenduPc.mosaique(s, w, h)
        ImageIO.write(m, "png", File(echantillons, "session-mosaique.png"))
        var couleurs = 0
        for (y in 0 until h step 4) for (x in 0 until w step 4) if (Color(m.getRGB(x, y)).let { maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue) > 120 }) couleurs++
        assertTrue("mosaic $couleurs", couleurs > 2000)
    }

    private fun duree(ff: String, f: File): Double = ProcessBuilder(ff.replace("ffmpeg", "ffprobe"), "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f.path)
        .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().toDouble()

    @Test
    fun la_session_en_video_dans_les_deux_modes() {
        val ff = RenduPc.ffmpeg() ?: return
        for ((mode, v) in listOf("passages" to SessionRenduPc.Vitesses(passage = 100.0, image = 20.0), "continu" to SessionRenduPc.Vitesses(10_000.0, 500.0, 50.0))) {
            val s = SessionRenduPc.Session(scenes(), mode, v, titre = "Essai", vignettes = true)
            val n = SessionRenduPc.trames(s)
            val mp4 = SessionRenduPc.video(s, File(echantillons, "session-$mode.mp4")) {}
            assertTrue(mode, mp4 != null && mp4.length() > 10_000)
            val d = duree(ff, mp4!!)
            assertEquals(mode, n.toDouble() / SessionRenduPc.FPS, d, 0.5)
            // The card 3 s and the mosaic 6 s around the passes.
            assertTrue("$mode $d s", d > 9.5 && d < 60)
        }
    }

    @Test
    fun la_route_fabrique_la_session() {
        val dossier = File(System.getProperty("java.io.tmpdir"), "satme-session-" + System.nanoTime()).apply { mkdirs() }
        val portee = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val st = StationPc(fr.f4ioz.satcombo.plateforme.StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
            val carnet = CarnetPc(st, fr.f4ioz.satcombo.data.LogStore(File(dossier, "qso_log.json")))
            val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), File(dossier, "sstv"))
            val j = JournalPc(st, carnet, son, File(dossier, "journal"))
            val web = JournalWebPc(j, st, dossier)
            val es = (0..1).map { k ->
                val de = t0 + k * 95 * 60_000L
                JournalPassage.Entree(27607, "SO-50", de, de + 240_000L, "JN18FS",
                    points = (0..4).map { JournalPassage.Point(de + it * 60_000L, 100.0 + it * 10, 5.0 + it * 8) }).also { j.rangement.enregistre(it) }
            }
            fun lit(route: String, p: Map<String, String>) = org.json.JSONObject(String(web.sert(route) { p[it].orEmpty() }.corps))
            // Nothing known: said so.
            assertEquals("vide", lit("/fabrique-session", mapOf("ids" to "inconnu_1")).optString("raison").let { if (RenduPc.ffmpeg() == null) "vide" else it })
            if (RenduPc.ffmpeg() == null) return
            val r = lit("/fabrique-session", mapOf("ids" to es.joinToString(",") { it.id } + ",inconnu_2", "mode" to "passages", "vitesse" to "100",
                "res" to "XS", "format" to "paysage", "style" to "vintage", "villes" to "capitales", "vignettes" to "1", "ouverture" to "1", "recap" to "0"))
            assertTrue(r.toString(), r.getBoolean("ok")); assertEquals(2, r.getInt("passages"))
            var tr: org.json.JSONObject
            do { Thread.sleep(200); tr = lit("/travail", mapOf("n" to r.getString("travail"))) } while (!tr.getBoolean("fini"))
            assertTrue(tr.toString(), tr.getBoolean("ok"))
            val mp4 = web.sert("/fichier") { mapOf("n" to r.getString("travail"))[it].orEmpty() }.fichier!!
            assertTrue(mp4.name, mp4.name.endsWith("_session.mp4") && mp4.length() > 10_000)
            // The card 3 s, two passes of 5 minutes at ×100 (3 s each), one turn.
            val d = duree(RenduPc.ffmpeg()!!, mp4)
            assertTrue("$d s", d > 8 && d < 12)
        } finally { portee.coroutineContext[kotlinx.coroutines.Job]?.cancel(); dossier.deleteRecursively() }
    }

    @Test
    fun une_image_arrive_lentement() {
        val dossier = File(System.getenv("SATME_SESSION3") ?: "build/session3-echantillons").apply { mkdirs() }
        val s = SessionRenduPc.Session(scenes(), "passages", SessionRenduPc.Vitesses(), vignettes = true)
        val plan = SessionRenduPc.plan(s)
        // The last pass's second picture, 36 s received: at ×3 about 12 s of video (with its easing).
        val m = s.scenes[2].marques.last()
        val pendant = plan.filter { it is SessionRenduPc.Trame.Passe && it.t in m.debutMs..m.finMs }
        assertTrue("${pendant.size} frames", pendant.size >= 11 * SessionRenduPc.FPS)
        // A frame each second of video while it comes, from 1 s before.
        val de = plan.indexOf(pendant.first()) - SessionRenduPc.FPS
        var n = 0
        for (k in de until plan.indexOf(pendant.last()) + SessionRenduPc.FPS step SessionRenduPc.FPS) {
            ImageIO.write(SessionRenduPc.image(s, plan[k], 1280, 720), "png", File(dossier, "image-lente-%02d.png".format(n++)))
        }
        assertTrue(n >= 12)
        // Not long before it, the pass's pace (×20): it slowed down on the way.
        val avant = plan.filter { it is SessionRenduPc.Trame.Passe && it.t in (m.debutMs - 60_000L)..(m.debutMs - 40_000L) }
        assertTrue(avant.size in 3..12)
    }
}
