/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.satcombo.data.LectureElements
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.site.carnet.Qso
import fr.f4ioz.site.carnet.StatsCarnet
import fr.f4ioz.site.ciel.CielStation
import fr.f4ioz.site.pages.Accueil
import fr.f4ioz.site.pages.MurSstv
import fr.f4ioz.site.publication.Publication
import fr.f4ioz.site.publication.TypePublication
import fr.f4ioz.site.reglages.Reglages
import fr.f4ioz.site.reglages.SectionsAccueil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** « Au-dessus de JN18FS », the logbook's figures and map, the SSTV wall's passes, the home page's places with the sky. */
class CielTest {

    private val elements = LectureElements.lis(CielTest::class.java.getResourceAsStream("/fixtures/elements-essai.tle")!!.bufferedReader().readText())
    /** JN18FS's centre. */
    private val obs = Observer(48.77, 2.46, 0.0, "JN18FS")
    /** The elements' epoch (25 October 2026), noon UTC the next day. */
    private val t0 = 1_793_016_000_000L

    private fun passe(nom: String, aos: Long, los: Long, max: Double = 40.0) =
        SatPass(nom, 1, aos, los, max, 200.0, 20.0, false, false, listOf(200.0 to 0.0, 120.0 to max, 20.0 to 0.0))

    @Test fun `the sky at an instant, only what is up, the passes still to come, the Sun where it is`() {
        assertEquals(4, elements.size)
        val p = PassPredictor()
        val passages = elements.flatMap { p.upcomingPasses(it, obs, t0 - 1_800_000L, 24, 5.0) }.sortedBy { it.aosEpochMs }
        assertTrue("passes in a day", passages.size > 5)
        // At the middle of the first pass, its satellite is up and comes first; the passes over are gone.
        val premier = passages.first { it.aosEpochMs > t0 }
        val t = (premier.aosEpochMs + premier.losEpochMs) / 2
        val i = CielStation.instantane(p, elements, obs, passages, t)
        assertTrue(i.vus.any { it.catnum == premier.catalogNumber })
        assertTrue(i.vus.all { it.el > 0 })
        assertEquals(i.vus.sortedByDescending { it.el }, i.vus)
        assertTrue(i.passages.all { it.losEpochMs > t })
        assertEquals(premier.catalogNumber, i.enCours?.catalogNumber ?: -1)
        assertTrue(i.prochain!!.aosEpochMs > t)
        // Noon UTC in late October over Paris: the Sun up, to the south.
        val midi = CielStation.instantane(p, elements, obs, passages, t0)
        assertTrue(midi.soleilEl in 20.0..35.0)
        assertTrue(midi.soleilAz in 160.0..220.0)
    }

    @Test fun `the pass under way is the highest, the next is the first still to begin`() {
        val t = 1_000_000_000L
        val i = CielStation.Instantane(t, obs, emptyList(),
            listOf(passe("A", t - 60_000, t + 60_000, 20.0), passe("B", t - 30_000, t + 300_000, 70.0), passe("C", t + 600_000, t + 900_000)), 0.0, -10.0)
        assertEquals("B", i.enCours!!.satName)
        assertEquals("C", i.prochain!!.satName)
        assertNull(CielStation.Instantane(t, obs, emptyList(), listOf(passe("C", t + 1, t + 2)), 0.0, 0.0).enCours)
    }

    @Test fun `the dial, zenith in the middle, north up, east on the right, the horizon on the rim`() {
        val (x0, y0) = CielStation.polaire(123.0, 90.0, 100.0)
        assertEquals(0.0, x0, 1e-9); assertEquals(0.0, y0, 1e-9)
        val (xn, yn) = CielStation.polaire(0.0, 0.0, 100.0)
        assertEquals(0.0, xn, 1e-9); assertEquals(-100.0, yn, 1e-9)
        val (xe, ye) = CielStation.polaire(90.0, 0.0, 100.0)
        assertEquals(100.0, xe, 1e-9); assertEquals(0.0, ye, 1e-9)
        val (x30, y30) = CielStation.polaire(180.0, 30.0, 90.0)
        assertEquals(0.0, x30, 1e-9); assertEquals(60.0, y30, 1e-9)
        // Below the horizon stays on the rim.
        assertEquals(100.0, abs(CielStation.polaire(270.0, -5.0, 100.0).first), 1e-9)
    }

    @Test fun `the countdown's words`() {
        assertEquals("00:45", CielStation.compte(45_000))
        assertEquals("07:30", CielStation.compte(450_000))
        assertEquals("59:59", CielStation.compte(3_599_000))
        assertEquals("2 h 05", CielStation.compte(2 * 3_600_000L + 5 * 60_000L + 59_000L))
        assertEquals("00:00", CielStation.compte(-5_000))
    }

    @Test fun `the dial's SVG names the satellites up (escaped) and draws the pass shown`() {
        val t = 1_000_000_000L
        val i = CielStation.Instantane(t, obs, listOf(CielStation.Vu("<b>X</b>", 9, 45.0, 30.0, 50.0, 3.0, 500.0, 900.0)),
            listOf(passe("C", t + 600_000, t + 900_000)), 150.0, 20.0)
        val svg = CielStation.svg(i, "Le ciel")
        assertTrue(svg.contains("&lt;b&gt;X&lt;/b&gt;")); assertFalse(svg.contains("<b>X"))
        assertTrue(svg.contains("class=\"cc-trace\""))
        assertTrue(svg.contains("cc-soleil"))
        assertFalse(CielStation.svg(i.copy(soleilEl = -3.0), "x").contains("cc-soleil"))
        val j = CielStation.json(i, "JN18FS", elements)
        assertEquals("JN18FS", j.getString("locator"))
        assertEquals(4, j.getJSONArray("sats").length())
        val p = j.getJSONArray("passages").getJSONObject(0)
        assertEquals("C", p.getString("nom")); assertEquals(3, p.getJSONArray("trace").length())
    }

    @Test fun `the light coastline keeps the shapes, drops the crumbs`() {
        val grand = JSONArray((0..400).flatMap { k -> val a = k / 400.0 * 2 * Math.PI; listOf(10 * Math.cos(a), 45 + 8 * Math.sin(a)) })
        val miette = JSONArray(listOf(0.0, 0.0, 0.1, 0.0, 0.1, 0.1, 0.0, 0.1, 0.0, 0.0))
        val l = CielStation.traitsLegers(JSONArray(listOf(grand, miette)))
        assertEquals(1, l.length())
        val a = l.getJSONArray(0)
        assertTrue(a.length() in 40 until grand.length())
        assertEquals(0, a.length() % 2)
    }

    @Test fun `the home page with the sky, the sky on the left, the others two by two`() {
        val p = Accueil.places(listOf("ciel", "passages", "qso", "image", "article", "outils"))
        assertEquals("--gc:1/7;--gr:1/4", p["ciel"])
        assertEquals("--gc:7/10;--gr:1/3", p["passages"])
        assertEquals("--gc:10/13;--gr:3/4", p["article"])
        assertEquals("--gc:1/13;--gr:4/5", p["outils"])
        assertEquals("--gc:7/13;--gr:3/4", Accueil.places(listOf("ciel", "passages", "qso", "article"))["article"])
        assertEquals("--gc:8/13;--gr:3/4", Accueil.places(listOf("ciel", "passages", "qso"))["qso"])
        // With the live block, the sky is one of the column beside it; five there.
        val d = Accueil.places(listOf("direct", "ciel", "passages", "qso", "image", "article"))
        assertEquals("--gc:1/8;--gr:1/4", d["direct"])
        assertEquals("--gc:8/13;--gr:1/2", d["ciel"])
        assertEquals("--gc:10/13;--gr:3/4", d["article"])
        assertEquals(6, d.size)
        // Without the sky, as before.
        assertEquals("--gc:10/13;--gr:3/4", Accueil.places(listOf("direct", "passages", "qso", "image", "article"))["article"])
    }

    @Test fun `the sky card is a setting of its own, on by default`() {
        assertTrue(Reglages().accueil.ciel)
        val r = Reglages(accueil = SectionsAccueil(ciel = false))
        assertFalse(Reglages.depuis(r.json()).accueil.ciel)
        assertTrue(Reglages.depuis(JSONObject().put("accueil", JSONObject().put("qso", false))).accueil.ciel)
    }

    private fun qso(c: String, carre: String, sat: String = "", pays: String = "France", t: Long = 0L, bande: String = "2m") = Qso(c, t, bande, "FM", sat, carre, pays, "wavelog")

    @Test fun `the logbook in figures`() {
        val l = listOf(qso("F1AAA", "JN18", "RS-44", t = 3), qso("F1AAA", "JN18", "SO-50", t = 2), qso("EA4BB", "IN80", "RS-44", "Spain", 1),
            qso("VK2CC", "QF56", "", "Australia", 0, "20m"), qso("G4DD", "", "ISS", "England"), qso("ON4EE", "XX99", "", "Belgium"))
        val s = StatsCarnet.stats(l, 48.77, 2.46)
        assertEquals(6, s.qso); assertEquals(5, s.indicatifs); assertEquals(5, s.pays)
        assertEquals(3, s.carres); assertEquals(3, s.satellites); assertEquals(2, s.bandes)
        assertEquals("VK2CC", s.odx!!.indicatif)
        assertTrue(s.odxKm in 16_000..17_500)
        assertEquals("RS-44" to 2, s.parSatellite.first())
        val c = StatsCarnet.carres(l, 48.77, 2.46)
        assertEquals(listOf("JN18", "IN80", "QF56"), c.map { it.carre })
        assertEquals(2, c[0].n); assertEquals(2, c[0].sat); assertEquals("F1AAA", c[0].dernier)
        assertTrue(c[0].km < 100)
        assertEquals("JN18", StatsCarnet.json(c).getJSONObject(0).getString("g"))
        assertNull(StatsCarnet.centre("ZZ99")); assertNotNull(StatsCarnet.centre("jn18fs"))
        assertEquals(0, StatsCarnet.stats(emptyList(), null, null).odxKm)
    }

    private fun image(id: Char, sat: String, t: Long, passage: String = "") = Publication(id.toString().repeat(16), TypePublication.SSTV, "", "f.png", "image/png", 1, t,
        JSONObject().put("satellite", sat).put("utc", t).also { if (passage.isNotEmpty()) it.put("passage", passage) })

    @Test fun `the wall's passes, the same satellite within 25 minutes, or the same pass named`() {
        val m = 60_000L
        val l = listOf(image('a', "ISS", 0), image('b', "ISS", 2 * m), image('c', "ISS", 4 * m), image('d', "ISS", 95 * m),
            image('e', "RS-44", 96 * m), image('f', "RS-44", 160 * m, "P1"), image('1', "RS-44", 200 * m, "P1"))
        val p = MurSstv.parPassage(l.shuffled())
        assertEquals(listOf("RS-44", "RS-44", "ISS", "ISS"), p.map { it.satellite })
        assertEquals(listOf(2, 1, 1, 3), p.map { it.images.size })
        // The newest first, inside a pass too.
        assertEquals("1111111111111111", p[0].images.first().id)
        assertEquals(0L, p[3].debutMs); assertEquals(4 * m, p[3].finMs)
        assertTrue(MurSstv.parPassage(emptyList()).isEmpty())
    }
}
