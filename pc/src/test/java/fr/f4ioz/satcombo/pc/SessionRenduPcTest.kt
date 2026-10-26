/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.CadreSstv
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
    private fun scenes(vignettes: Boolean = true, police: Float = 1f, tz: java.util.TimeZone = LegendePc.UTC, avecSon: Boolean = false): List<RenduPc.Scene> = (0..2).map { k ->
        val de = t0 + k * 95 * 60_000L
        val sol = (0..24).map { s -> val f = s / 24.0; JournalPassage.Sol(de + s * 15_000L, 60.0 - 28 * f, -18.0 + 8 * k + 32 * f, 420.0) }
        val e = JournalPassage.Entree(25544, "ISS", de, de + 360_000L, "JN18DU",
            enregistrements = if (avecSon && k == 1) listOf("qso.wav") else emptyList(),
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

            fun fait(r: org.json.JSONObject): File {
                assertTrue(r.toString(), r.getBoolean("ok"))
                var x: org.json.JSONObject
                do { Thread.sleep(200); x = lit("/travail", mapOf("n" to r.getString("travail"))) } while (!x.getBoolean("fini"))
                assertTrue(x.toString(), x.getBoolean("ok"))
                return web.sert("/fichier") { mapOf("n" to r.getString("travail"))[it].orEmpty() }.fichier!!
            }
            // The margins (ms): one pass of 4 minutes at ×100 without a card, then 10 minutes more before its AOS (+6 s).
            val base = mapOf("ids" to es[0].id, "mode" to "passages", "vPassage" to "100", "res" to "XS", "ouverture" to "0", "recap" to "0")
            val court = duree(RenduPc.ffmpeg()!!, fait(lit("/fabrique-session", base + ("avant" to "0") + ("apres" to "0"))))
            val long = duree(RenduPc.ffmpeg()!!, fait(lit("/fabrique-session", base + ("avant" to "600000") + ("apres" to "0"))))
            assertEquals("$court → $long", 6.0, long - court, 1.0)
            // One pass whole on the globe, asked the session's way: made as a session of one, its margins too.
            val seul = lit("/fabrique", mapOf("id" to es[0].id, "quoi" to "video", "vue" to "globe", "moteur" to "session", "vPassage" to "100", "res" to "XS",
                "ouverture" to "0", "recap" to "0", "margeAvant" to "600000", "margeApres" to "0", "avant" to "10", "apres" to "30"))
            assertEquals(1, seul.getInt("passages"))
            val f1 = fait(seul)
            assertTrue(f1.name, f1.name.endsWith("_passage.mp4"))
            assertEquals(long, duree(RenduPc.ffmpeg()!!, f1), 0.3)
            // Every other way of asking (the phone's words, the sky, a stretch, the accelerated video): the same engine, one pass.
            for (autre in listOf(mapOf("vue" to "globe"), mapOf("vue" to "ciel", "moteur" to "session"), mapOf("vue" to "globe", "moteur" to "session", "accelere" to "1"),
                mapOf("vue" to "globe", "moteur" to "session", "de" to "${es[0].debutMs + 10_000L}", "a" to "${es[0].debutMs + 40_000L}"), mapOf("carte" to "1")))
                assertEquals(autre.toString(), 1, lit("/fabrique", mapOf("id" to es[0].id, "quoi" to "image", "t" to "${es[0].debutMs + 60_000L}") + autre).getInt("passages"))
            // The editor's words for one pass or several, a stretch: only the passes it reaches.
            val bout = lit("/fabrique-session", mapOf("ids" to es.joinToString(",") { it.id }, "quoi" to "gif", "margeAvant" to "0", "margeApres" to "0",
                "de" to "${es[1].debutMs + 10_000L}", "a" to "${es[1].debutMs + 40_000L}", "vue" to "ciel"))
            assertEquals(1, bout.getInt("passages"))
            fait(bout)
            // Several passes' pass files: one each, together in a ZIP.
            val zip = fait(lit("/fabrique-session", mapOf("ids" to es.joinToString(",") { it.id }, "quoi" to "paquet")))
            val noms = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
            assertEquals(noms.toString(), 2, noms.size)
            // A phone's 9:16, on the sky, the SSTV picture where the page put it: the picture upright.
            val tel = ImageIO.read(fait(lit("/fabrique-session", mapOf("ids" to es[0].id, "quoi" to "image", "t" to "${es[0].debutMs + 60_000L}",
                "format" to "telephone", "vue" to "ciel"))))
            assertEquals(1080, tel.width); assertEquals(1920, tel.height)
        } finally { portee.coroutineContext[kotlinx.coroutines.Job]?.cancel(); dossier.deleteRecursively() }
    }

    @Test
    fun le_format_telephone_et_les_incrustations_placees() {
        // 9:16: the phone's whole screen upright, at each resolution; a picture, a GIF.
        assertEquals(720 to 1280, RenduPc.tailleVideo(false, "M", vertical = true))
        assertEquals(480 to 854, RenduPc.tailleVideo(true, "XS", vertical = true))
        assertEquals(1080 to 1920, RenduPc.tailleVideo(false, "HD", vertical = true))
        // The older two unchanged: 16:9 and the phone app's 3:4.
        assertEquals(1280 to 720, RenduPc.tailleVideo(true, "M")); assertEquals(720 to 960, RenduPc.tailleVideo(false, "M"))
        fun scene(paysage: Boolean, vertical: Boolean, cadre: CadreSstv?) = scenes().first().let { sc ->
            RenduPc.Scene(sc.e, emptyList(), sc.marques, "F4IOZ", sol = sc.sol, qth = sc.qth, paysage = paysage, vertical = vertical, surGlobe = true,
                resolution = "XS", cadreSstv = cadre, ouverture = false, recap = false)
        }
        val v = scene(false, true, null)
        assertEquals(1080 to 1920, RenduPc.tailleImage(v)); assertEquals(360 to 640, RenduPc.tailleGif(v))
        assertEquals(1920 to 1080, RenduPc.tailleImage(scene(true, false, null))); assertEquals(1080 to 1440, RenduPc.tailleImage(scene(false, false, null)))
        // Upright, the view over the caption is taller than wide (not the 3:4's square).
        val haut = RenduPc.hautVue(1080, 1920, v)
        assertTrue("$haut", haut in 1300..1800)
        assertTrue(RenduPc.hautVue(1080, 1440, scene(false, false, null)) <= 1080)
        for ((w, h) in listOf(1280 to 720, 720 to 1280, 1920 to 1080, 1080 to 1920)) println("vue $w×$h : ${RenduPc.hautVue(w, h, scene(w > h, h > w, null))}")
        // The SSTV picture placed on the page, in shares of the view: the same place in each format.
        val m = scenes().first().marques.first()
        for ((paysage, vertical) in listOf(true to false, false to true, false to false)) {
            val sc = scene(paysage, vertical, CadreSstv(0.5f, 0.25f, 0.4f))
            val s = SessionRenduPc.Session(listOf(sc), "passages", avant = 0L, apres = 0L, ouverture = false, recap = false)
            val f = File(echantillons, "format-$paysage-$vertical.png")
            SessionRenduPc.png(s, m.finMs, f)
            val b = ImageIO.read(f)
            assertEquals(RenduPc.tailleImage(sc), b.width to b.height)
            val hv = RenduPc.hautVue(b.width, b.height, sc)
            // Its centre: 70 % across, 25 % + 15 % × width × 3/4 / height down the view.
            val l = 0.4f * b.width; val x = (0.5f * b.width + l / 2).toInt(); val y = (0.25f * hv + l * 3 / 8).toInt()
            val c = Color(b.getRGB(x, y)); val n = Color(b.getRGB((0.1f * b.width).toInt(), (0.15f * hv).toInt()))
            assertTrue("$paysage $vertical $c", maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) > 100)
            assertTrue("$paysage $vertical outside $n", n != c)
        }
    }

    @Test
    fun un_morceau_en_temps_reel_et_l_accelere() {
        val sc = scenes()
        val e = sc[1].e
        // A stretch: the windows cut to it, in real time with its sound.
        val m = (e.debutMs + 100_000L)..(e.debutMs + 160_000L)
        assertTrue(SessionRenduPc.touche(e, 0L, 0L, m)); assertTrue(!SessionRenduPc.touche(sc[0].e, 0L, 0L, m))
        val s = SessionRenduPc.Session(listOf(sc[1]), "passages", SessionRenduPc.Vitesses(passage = 50.0), son = true, morceau = m, avant = 0L, apres = 0L)
        assertEquals(m, s.fenetres.single())
        assertEquals(listOf(m), s.ecoute)
        val plan = SessionRenduPc.plan(s)
        assertEquals(60.0, plan.size.toDouble() / SessionRenduPc.FPS, 1.0)
        // Accelerated: real time on its normal stretches, its own pace on the fast ones, the margins at the pass's pace.
        val segs = listOf(JournalPassage.Segment(e.debutMs, e.debutMs + 140_000L, 10), JournalPassage.Segment(e.debutMs + 140_000L, e.debutMs + 170_000L, 1),
            JournalPassage.Segment(e.debutMs + 170_000L, e.finMs, 10))
        val a = SessionRenduPc.Session(listOf(sc[1]), "passages", SessionRenduPc.Vitesses(passage = 50.0), son = true, accelere = segs, avant = 60_000L, apres = 0L)
        val al = SessionRenduPc.allure(a)
        assertEquals(1.0, al.a(e.debutMs + 155_000L), 1e-9)
        assertEquals(10.0, al.a(e.debutMs + 60_000L), 1e-9)
        assertEquals(50.0, al.a(e.debutMs - 50_000L), 1e-9)
        assertEquals(listOf((e.debutMs + 140_000L)..(e.debutMs + 170_000L)), a.ecoute)
        // The sounds one after the other: a second of silence between, each at the first's rate.
        val un = fr.f4ioz.satcombo.domain.SourcePcm.de(ShortArray(8000) { 100 })
        val deux = SessionRenduPc.auTaux(fr.f4ioz.satcombo.domain.SourcePcm.de(ShortArray(16000) { 200 }), 16000, 8000)
        val tout = SessionRenduPc.suite(SessionRenduPc.suite(un, fr.f4ioz.satcombo.domain.SourcePcm.de(ShortArray(8000))), deux)
        val l = ArrayList<Short>(); val buf = ShortArray(3000)
        while (true) { val k = tout.lis(buf); if (k <= 0) break; for (i in 0 until k) l += buf[i] }
        assertEquals(24000, l.size); assertEquals(100.toShort(), l[0]); assertEquals(0.toShort(), l[12000]); assertEquals(200.toShort(), l[23999])
    }

    @Test
    fun les_marges_avant_l_aos_et_apres_le_los() {
        fun lit(vararg kv: Pair<String, String>) = SessionRenduPc.parametres { k -> kv.toMap()[k].orEmpty() }
        // 30 s by default; in ms, « margeAvant » before « avant »; 0 to 30 min.
        assertEquals(30_000L, lit().avant); assertEquals(30_000L, lit().apres)
        assertEquals(180_000L, lit("avant" to "180000").avant); assertEquals(60_000L, lit("apres" to "60000").apres)
        assertEquals(240_000L, lit("avant" to "180000", "margeAvant" to "240000").avant)
        assertEquals(0L, lit("margeApres" to "0").apres)
        assertEquals(SessionRenduPc.MARGE_MAX_MS, lit("avant" to "1e12").avant); assertEquals(0L, lit("avant" to "-5").avant)
        assertEquals(30_000L, lit("avant" to "abc", "apres" to "NaN").avant)

        // One pass, 3 minutes before: it starts 3 minutes before the AOS, the satellite coming.
        val un = SessionRenduPc.Session(scenes().take(1), "passages", SessionRenduPc.Vitesses(passage = 20.0), avant = 180_000L, apres = 60_000L)
        val e = un.scenes[0].e
        assertTrue(un.seul)
        assertEquals((e.debutMs - 180_000L)..(e.finMs + 60_000L), un.fenetres.single())
        val plan = SessionRenduPc.plan(un)
        assertEquals(e.debutMs - 180_000L, plan.first().t); assertEquals(e.finMs + 60_000L, plan.last().t)
        assertTrue(plan.none { it is SessionRenduPc.Trame.Tourne })
        // At ×20: 3 minutes in about 9 s of video.
        val avant = plan.filter { it.t < e.debutMs }
        assertEquals(9.0, avant.size.toDouble() / SessionRenduPc.FPS, 1.0)
        // Two minutes before the AOS: the satellite drawn where its elements put it (not without them).
        val tr = avant.first { it.t >= e.debutMs - 120_000L }
        val vu = SessionRenduPc.image(un, tr, 1280, 720)
        ImageIO.write(vu, "png", File(echantillons, "marge-avant-aos.png"))
        val sansElements = SessionRenduPc.Session(scenes().take(1).onEach { sc -> sc.horsTrace = { null } }, "passages", SessionRenduPc.Vitesses(passage = 20.0), avant = 180_000L)
        val cache = SessionRenduPc.image(sansElements, tr, 1280, 720)
        var diff = 0
        for (y in 0 until 720 step 2) for (x in 0 until 1280 step 2) if (vu.getRGB(x, y) != cache.getRGB(x, y)) diff++
        assertTrue("$diff", diff > 30)
        // A pass alone: its own title and caption (not « Passage 1 / 1 »), its own summary after it.
        assertNull(un.scenes[0].legendeSession)
        assertEquals(3 * SessionRenduPc.FPS, SessionRenduPc.debut(un, 640, 360).sumOf { it.second })
        assertTrue(SessionRenduPc.fin(un, 640, 360).isNotEmpty())
        assertEquals(SessionRenduPc.debut(un, 1, 1).sumOf { it.second } + plan.size + SessionRenduPc.fin(un, 1, 1).sumOf { it.second }, SessionRenduPc.trames(un))

        // Several passes: each its windows, in both modes.
        val n = SessionRenduPc.Session(scenes(), "passages", SessionRenduPc.Vitesses(), avant = 120_000L, apres = 0L)
        assertTrue(!n.seul)
        n.scenes.forEachIndexed { i, sc -> assertEquals((sc.e.debutMs - 120_000L)..sc.e.finMs, n.fenetres[i]) }
        val pn = SessionRenduPc.plan(n)
        for (i in 0..2) {
            val l = pn.filter { it is SessionRenduPc.Trame.Passe && it.i == i }
            assertEquals(n.scenes[i].e.debutMs - 120_000L, l.first().t); assertEquals(n.scenes[i].e.finMs, l.last().t)
        }
        val c = SessionRenduPc.plan(SessionRenduPc.Session(scenes(), "continu", SessionRenduPc.Vitesses(), avant = 120_000L, apres = 0L))
        assertEquals(n.scenes[0].e.debutMs - 120_000L, c.first().t); assertEquals(n.scenes[2].e.finMs, c.last().t)
    }

    @Test
    fun avec_une_longue_marge_on_voit_venir_le_satellite_de_loin() {
        // One pass, 5 minutes before: the margin starts from afar (« zoom hors passage »), the AOS near.
        val un = SessionRenduPc.Session(scenes().take(1), "passages", SessionRenduPc.Vitesses(passage = 20.0), avant = 300_000L, zHors = 0.8, zPassage = 2.0)
        val e = un.scenes[0].e
        val plan = SessionRenduPc.plan(un)
        val z = plan.map { SessionRenduPc.zoom(un, it) }
        assertEquals(0.8, z.first(), 1e-6)
        assertTrue(plan.take(SessionRenduPc.FPS * 3).all { Math.abs(SessionRenduPc.zoom(un, it) - 0.8) < 1e-6 })
        assertEquals(2.0, z[plan.indexOfFirst { it.t >= e.debutMs }], 1e-6)
        assertTrue(plan.filter { it.t in e.debutMs..e.finMs }.all { Math.abs(SessionRenduPc.zoom(un, it) - 2.0) < 1e-6 })
        // Coming nearer before the AOS, step by step.
        val l = Math.log(2.0 / 0.8)
        for (k in 1 until z.size) assertTrue("$k", Math.abs(Math.log(z[k] / z[k - 1])) <= l / 6)
        ImageIO.write(SessionRenduPc.image(un, plan.first(), 1280, 720), "png", File(echantillons, "marge-5min-loin.png"))
        ImageIO.write(SessionRenduPc.image(un, plan.first { it.t >= e.debutMs }, 1280, 720), "png", File(echantillons, "marge-5min-aos.png"))
    }

    @Test
    fun un_passage_seul_en_video_et_en_gif() {
        val un = SessionRenduPc.Session(scenes().take(1), "passages", SessionRenduPc.Vitesses(passage = 60.0, image = 20.0), avant = 180_000L, apres = 60_000L, titre = null)
        val gif = SessionRenduPc.gif(un, File(echantillons, "passage-seul.gif")) {}!!
        assertTrue(gif.length() > 20_000)
        val ff = RenduPc.ffmpeg() ?: return
        val mp4 = SessionRenduPc.video(un, File(echantillons, "passage-seul.mp4")) {}!!
        assertEquals(SessionRenduPc.trames(un).toDouble() / SessionRenduPc.FPS, duree(ff, mp4), 0.5)
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

    @Test
    fun deux_zooms_et_le_passage_de_l_un_a_l_autre() {
        val s = SessionRenduPc.Session(scenes(), "continu", SessionRenduPc.Vitesses(5000.0, 20.0, 3.0), zHors = 0.8, zPassage = 2.0)
        val f = s.passages
        // In a pass (AOS to LOS): the pass's zoom; in its margin, on the way; far between two: the other.
        assertEquals(1.0, SessionRenduPc.partPassage(f, f[1].first + 1000, 20.0), 1e-9)
        assertTrue(SessionRenduPc.partPassage(f, s.fenetres[1].first, 20.0) in 0.3..0.8)
        assertEquals(0.0, SessionRenduPc.partPassage(f, (f[0].last + f[1].first) / 2, 20.0), 1e-9)
        val plan = SessionRenduPc.plan(s)
        val z = plan.map { SessionRenduPc.zoom(s, it) }
        assertTrue(z.all { it in 0.8 - 1e-9..2.0 + 1e-9 })
        assertEquals(0.8, z.min(), 1e-6); assertEquals(2.0, z.max(), 1e-6)
        // Progressive: never more than a sixth of the way from one frame to the next (over 1.5 s, 12 frames, eased).
        val l = Math.log(2.0 / 0.8)
        for (k in 1 until z.size) assertTrue("$k ${z[k - 1]} → ${z[k]}", Math.abs(Math.log(z[k] / z[k - 1])) <= l / 6)
        val avant = plan.indexOfFirst { it is SessionRenduPc.Trame.Passe && it.i == 1 && it.t >= s.scenes[1].e.debutMs }
        assertTrue("${z[avant - SessionRenduPc.FPS]}", z[avant] > 1.99 && z[avant - SessionRenduPc.FPS] in 0.85..1.99)
        // Passes in a row: out half way through the turn, back at its ends to the windows' zoom (one turn step from it).
        val p = SessionRenduPc.Session(scenes(), "passages", SessionRenduPc.Vitesses(), zHors = 0.8, zPassage = 2.0, transition = 2.0)
        val pp = SessionRenduPc.plan(p)
        val tours = pp.filterIsInstance<SessionRenduPc.Trame.Tourne>().filter { it.i == 1 }
        assertTrue(SessionRenduPc.zoom(p, tours[tours.size / 2]) < 0.85)
        val finPrec = SessionRenduPc.zoom(p, pp[pp.indexOf(tours.first()) - 1]); val debutSuiv = SessionRenduPc.zoom(p, pp[pp.indexOf(tours.last()) + 1])
        assertTrue("$finPrec ${SessionRenduPc.zoom(p, tours.first())}", Math.abs(Math.log(SessionRenduPc.zoom(p, tours.first()) / finPrec)) < 0.2)
        assertTrue("$debutSuiv ${SessionRenduPc.zoom(p, tours.last())}", Math.abs(Math.log(SessionRenduPc.zoom(p, tours.last()) / debutSuiv)) < 0.2)
        // The frame shows it: the globe larger in the pass than between two (less of the sky round it).
        fun bleu(b: BufferedImage): Int { var n = 0; for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) { val c = Color(b.getRGB(x, y)); if (c.red < 45 && c.green < 70 && c.blue in 40..140) n++ }; return n }
        val dedans = SessionRenduPc.image(s, plan[avant + 10], 640, 360)
        val dehors = SessionRenduPc.image(s, plan.first { it is SessionRenduPc.Trame.Entre && Math.abs(SessionRenduPc.zoom(s, it) - 0.8) < 1e-6 }, 640, 360)
        ImageIO.write(dedans, "png", File(echantillons, "zoom-passage.png")); ImageIO.write(dehors, "png", File(echantillons, "zoom-hors.png"))
        assertTrue("${bleu(dedans)} / ${bleu(dehors)}", bleu(dehors) > bleu(dedans) * 1.3)
    }

    @Test
    fun d_autres_satellites_tournent_aussi() {
        val violet = Color(0xFF00FF)
        // Over Paris, at 800 km.
        val autre = GlobeRenduPc.Autre("AO-91", violet) { t -> JournalPassage.Sol(t, 47.0, 4.0, 800.0) }
        fun violets(b: BufferedImage): Int { var n = 0; for (y in 0 until b.height) for (x in 0 until b.width) { val c = Color(b.getRGB(x, y)); if (c.red > 200 && c.blue > 200 && c.green < 80) n++ }; return n }
        val sans = SessionRenduPc.Session(scenes(), "passages")
        val avec = SessionRenduPc.Session(scenes(), "passages", autres = listOf(autre))
        val taches = SessionRenduPc.Session(scenes(), "passages", autres = listOf(autre), tachesAutres = true)
        val tr = SessionRenduPc.plan(sans).first { it is SessionRenduPc.Trame.Passe && it.i == 1 }
        val a = SessionRenduPc.image(sans, tr, 1280, 720); val b = SessionRenduPc.image(avec, tr, 1280, 720); val c = SessionRenduPc.image(taches, tr, 1280, 720)
        ImageIO.write(c, "png", File(echantillons, "compagnon.png"))
        assertEquals(0, violets(a))
        assertTrue("${violets(b)}", violets(b) > 20)
        // Its footprint too.
        var diff = 0
        for (y in 0 until c.height step 2) for (x in 0 until c.width step 2) if (b.getRGB(x, y) != c.getRGB(x, y)) diff++
        assertTrue("$diff", diff > 2000)
        // Not kept on the pass's scene afterwards.
        assertTrue(sans.scenes.all { it.autres.isEmpty() && it.gzImpose == null })
    }

    @Test
    fun avec_le_son_un_contact_passe_en_temps_reel() {
        val s = SessionRenduPc.Session(scenes(avecSon = true), "passages", SessionRenduPc.Vitesses(passage = 50.0, image = 3.0), son = true)
        val q = s.scenes[1].marques.single()
        val plan = SessionRenduPc.plan(s)
        val pendant = plan.filter { it is SessionRenduPc.Trame.Passe && it.t in q.debutMs - 9_000..q.debutMs + 9_000 }
        // 18 s at ×1, 8 frames a second.
        assertTrue("${pendant.size}", pendant.size >= 18 * SessionRenduPc.FPS - 2)
        for (k in 1 until pendant.size) assertEquals(1000L / SessionRenduPc.FPS, pendant[k].t - pendant[k - 1].t)
        // Without the sound: as a picture, ×3.
        val sansSon = SessionRenduPc.plan(SessionRenduPc.Session(scenes(avecSon = true), "passages", SessionRenduPc.Vitesses(passage = 50.0, image = 3.0)))
        assertTrue(sansSon.count { it is SessionRenduPc.Trame.Passe && it.t in q.debutMs - 9_000..q.debutMs + 9_000 } < pendant.size / 2)

        // A recording from 10 s before the contact, 30 s long: heard where the contact goes in real time, silent elsewhere.
        val rate = 22_050
        val wav = File(echantillons, "qso.wav")
        val pcm = ShortArray(30 * rate) { i -> (8000 * Math.sin(2 * Math.PI * 440 * i / rate)).toInt().toShort() }
        RenduPc.ecritWav(fr.f4ioz.satcombo.domain.SourcePcm.de(pcm), rate, wav)
        val de = s.fenetres[1].first
        val sp = fr.f4ioz.satcombo.domain.SonPassage.fabrique(listOf(fr.f4ioz.satcombo.domain.SonPassage.Morceau(wav, q.debutMs - 10_000, 0L, 30_000L)),
            de, s.fenetres[1].last, echantillons, JournalPc.decodeur)!!
        sp.use {
            val src = SessionRenduPc.source(s, plan, SessionRenduPc.Son(mapOf(1 to sp), mapOf(1 to de), rate))
            val buf = ShortArray(4096); var total = 0L; var sonore = 0L
            while (true) { val n = src.lis(buf); if (n <= 0) break; total += n; for (i in 0 until n) if (buf[i].toInt() != 0) sonore++ }
            val attendu = (3 * SessionRenduPc.FPS + plan.size).toLong() * rate / SessionRenduPc.FPS
            assertTrue("$total / $attendu", Math.abs(total - attendu) <= rate / SessionRenduPc.FPS + 1)
            // The contact's 20 s, in the recording: about 20 s heard.
            assertTrue("${sonore / rate.toDouble()} s", sonore > 18L * rate && sonore < 21L * rate)
        }
    }

    @Test
    fun la_session_en_gif_et_en_image() {
        val s = SessionRenduPc.Session(scenes(), "continu", SessionRenduPc.Vitesses(10_000.0, 300.0, 30.0), titre = "Essai")
        val gif = SessionRenduPc.gif(s, File(echantillons, "session.gif")) {}!!
        assertTrue(gif.length() > 50_000)
        val lecteur = ImageIO.getImageReadersByFormatName("gif").next()
        lecteur.input = ImageIO.createImageInputStream(gif)
        val n = lecteur.getNumImages(true)
        assertTrue("$n", n > 20 && n <= SessionRenduPc.GIF_MAX + 2)
        val png = SessionRenduPc.png(s, s.scenes[2].marques.first().finMs + 1000, File(echantillons, "session-moment.png"))
        assertEquals(1920, ImageIO.read(png).width)
        // Between two passes: a moment of the turn, not a pass's end.
        assertTrue(SessionRenduPc.trameA(s, (s.fenetres[0].last + s.fenetres[1].first) / 2) is SessionRenduPc.Trame.Entre)
    }
}
