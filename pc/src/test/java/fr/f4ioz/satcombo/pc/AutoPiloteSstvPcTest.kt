/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.PiloteSstv
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class AutoPiloteSstvPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-autopilote-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val m = 60_000L
    private val t0 = 1_793_000_000_000L
    private var heure = t0
    private val passe = SatPass("ISS", 25544, t0 + 60 * m, t0 + 70 * m, 50.0, 0.0, 0.0, false, false)
    private val basse = SatPass("ISS", 25544, t0 + 160 * m, t0 + 168 * m, 4.0, 0.0, 0.0, false, false)

    private class Fausse : PiloteSstv.Station {
        val gestes = ArrayList<String>()
        override fun emission(): String? = null
        override fun conflit(c: CampagneSstv.Campagne): String? = null
        override fun capture() = mapOf("suivi" to "7530")
        override fun prepare(c: CampagneSstv.Campagne, p: SatPass) = PiloteSstv.Preparation(listOf("ISS suivi")).also { gestes += "prepare" }
        override fun enregistre(c: CampagneSstv.Campagne): String? { gestes += "enregistre"; return null }
        override fun arreteEnregistrement(): String { gestes += "arrete"; return "a.wav" }
        override fun images(deMs: Long, aMs: Long) = listOf("SatMe_SSTV_ISS.png")
        override fun parque(): String? = null
        override fun restaure(avant: Map<String, String>): List<String> { gestes += "restaure " + avant["suivi"]; return emptyList() }
        override fun annonce(texte: String) {}
    }

    private fun monte(f: Fausse = Fausse(), ocr: (BufferedImage) -> String? = { null }): AutoPiloteSstvPc {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee, { heure })
        return AutoPiloteSstvPc(st, dossier, { heure }, { _, _, _ -> listOf(passe, basse) }, ocr, { error("pas de réseau dans les essais") }, f)
    }

    private fun post(a: AutoPiloteSstvPc, route: String, p: Map<String, String> = emptyMap(), corps: ByteArray? = null): JSONObject =
        JSONObject(String(a.sert("/api/autopilote$route", "POST", { p[it].orEmpty() }) { corps }.corps, Charsets.UTF_8))
    private fun get(a: AutoPiloteSstvPc): JSONObject = JSONObject(String(a.sert("/api/autopilote", "GET", { "" }) { null }.corps, Charsets.UTF_8))

    private val formulaire = mapOf("nom" to "ARISS SSTV Series 34", "catnum" to "25544", "sat" to "ISS", "freq" to "437,550", "modulation" to "FM",
        "mode" to "Robot 36", "de" to (t0 - m).toString(), "a" to (t0 + 3 * 86_400_000L).toString(), "aconfirmer" to "1", "el" to "10",
        "horaire" to "36 s on / 2 min off", "lien" to "https://ariss-usa.org/ARISS_SSTV/", "source" to "poste")

    @Test fun `une campagne creee, ses passages hauts seulement, gardee sur disque`() {
        val a = monte()
        val r = post(a, "/campagne", formulaire)
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("ariss-sstv-series-34", r.getJSONObject("campagne").getString("id"))
        val j = get(a)
        assertFalse(j.getBoolean("maitre"))
        val l = j.getJSONArray("prochains")
        assertEquals("la passe à 4° est laissée", 1, l.length())
        assertEquals("PREVU", l.getJSONObject(0).getString("statut"))
        assertEquals(437_550_000L, j.getJSONArray("campagnes").getJSONObject(0).getLong("freqHz"))
        // Refused: no satellite.
        assertFalse(post(a, "/campagne", formulaire + ("catnum" to "")).getBoolean("ok"))
        // Read back by another instance (a restart).
        val b = monte()
        assertEquals("ARISS SSTV Series 34", b.campagnes.single().nom)
        assertEquals(setOf(25544), b.catnums())
        post(b, "/retire", mapOf("id" to "ariss-sstv-series-34"))
        assertTrue(monte().campagnes.isEmpty())
    }

    @Test fun `l'auto-pilote ne fait rien tant que l'interrupteur general est coupe, puis tout le passage`() {
        val f = Fausse()
        val a = monte(f)
        post(a, "/campagne", formulaire)
        heure = passe.aosEpochMs - 2 * m
        a.tic()
        assertTrue(f.gestes.isEmpty())
        post(a, "/maitre", mapOf("on" to "1"))
        a.tic()
        assertEquals(listOf("prepare"), f.gestes)
        assertEquals("PRET", get(a).getJSONObject("enCours").getString("phase"))
        heure = passe.aosEpochMs + 1_000L
        a.tic()
        assertEquals("EN_COURS", get(a).getJSONArray("prochains").getJSONObject(0).getString("statut"))
        heure = passe.losEpochMs + 31_000L
        a.tic()
        assertEquals(listOf("prepare", "enregistre", "arrete", "restaure 7530"), f.gestes)
        val h = get(a).getJSONArray("historique").getJSONObject(0)
        assertEquals("FAIT", h.getString("statut")); assertEquals("a.wav", h.getString("enregistrement"))
    }

    @Test fun `apres un redemarrage en plein passage, il est repris avec l'etat d'avant, STOP l'arrete`() {
        val a = monte()
        post(a, "/campagne", formulaire)
        post(a, "/maitre", mapOf("on" to "1"))
        heure = passe.aosEpochMs + 2 * m
        a.tic()
        assertTrue(File(dossier, "sstv-campagnes.json").readText().contains("\"enCours\""))
        // SatMe-PC stopped and started again.
        val f = Fausse()
        val b = monte(f)
        heure += 5_000L
        b.tic()
        assertEquals(listOf("prepare", "enregistre"), f.gestes)
        assertTrue(get(b).getJSONObject("enCours").getJSONArray("faits").toString().contains("redémarrage"))
        assertTrue(post(b, "/stop").getBoolean("ok"))
        assertEquals("ARRETE", get(b).getJSONArray("historique").getJSONObject(0).getString("statut"))
        assertTrue(f.gestes.contains("restaure 7530"))
        assertFalse(post(b, "/stop").getBoolean("ok"))
    }

    @Test fun `une affiche lue propose le formulaire, une adresse non http est refusee`() {
        val a = monte(ocr = { "SSTV Series 34\nStart: Thursday, October 22\nFrequency: 437.550 MHz\nSSTV Mode: Robot 36\nEnd: Sunday, October 25\nhttps://ariss-usa.org/ARISS_SSTV/" })
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val r = post(a, "/affiche", corps = png)
        assertTrue(r.toString(), r.getBoolean("ok"))
        val p = r.getJSONObject("proposition")
        assertEquals(437_550_000L, p.getLong("freqHz")); assertEquals("Robot 36", p.getString("mode")); assertEquals(25544, p.getInt("catnum"))
        assertFalse(post(a, "/affiche", mapOf("url" to "file:///etc/passwd")).getBoolean("ok"))
        assertFalse(post(a, "/affiche", mapOf("url" to "https://celestrak.org/x.png")).getBoolean("ok"))
        assertFalse(post(a, "/affiche", corps = "pas une image".toByteArray()).getBoolean("ok"))
    }

    /** A flyer drawn here (light text on a dark blue ground, as the ARISS ones), read by the real tesseract. */
    @Test fun `le vrai tesseract sur une affiche dessinee`() {
        assumeTrue(OutilsPc.trouve("tesseract") != null)
        val img = BufferedImage(768, 768, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = Color(10, 30, 70); fillRect(0, 0, 768, 768)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 30)
            val lignes = listOf("SSTV Series 34" to Color(255, 190, 60), "Start: Thursday, October 22" to Color.WHITE,
                "Frequency: 437.550 MHz" to Color.WHITE, "SSTV Mode: Robot 36" to Color.WHITE, "Schedule: 36 sec on / 2 min off." to Color.WHITE,
                "End: Sunday, October 25" to Color.WHITE)
            lignes.forEachIndexed { i, (t, c) -> color = c; drawString(t, 40, 80 + i * 90) }
            dispose()
        }
        val t = AutoPiloteSstvPc.lisImage(img)
        assertNotNull(t)
        println("tesseract (affiche dessinée) : $t")
        val p = fr.f4ioz.satcombo.domain.AfficheSstv.lis(t!!, t0)
        assertEquals(437_550_000L, p.freqHz)
        assertEquals("Robot 36", p.modeSstv)
        assertTrue(p.debutMs > 0 && p.finMs > p.debutMs)
    }

    /** The real ARISS Series 34 flyer, when it is on this PC (not kept in the sources). */
    @Test fun `le vrai tesseract sur l'affiche ARISS`() {
        val f = File(System.getenv("SATME_AFFICHE_ARISS") ?: "/tmp/claude-1000/-home-olivier-SatMe/b5067be1-191c-495c-bb90-943d07604e92/scratchpad/ariss-sstv.jpeg")
        assumeTrue(f.isFile && OutilsPc.trouve("tesseract") != null)
        val a = monte(ocr = AutoPiloteSstvPc::lisImage)
        val r = a.affiche(f.readBytes())
        println("tesseract (affiche ARISS) : " + r.optString("texte"))
        val p = r.getJSONObject("proposition")
        assertEquals(437_550_000L, p.getLong("freqHz")); assertEquals("Robot 36", p.getString("mode")); assertEquals(25544, p.getInt("catnum"))
        assertTrue(p.getBoolean("aConfirmer"))
        assertEquals("https://ariss-usa.org/ARISS_SSTV/", p.getString("lien"))
    }
}
