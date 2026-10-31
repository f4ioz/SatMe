/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The SSDV and Geoscan pictures in one list for the common « Images » gallery: live, saved, the tests', newest first, nothing twice. */
class GalerieTelemesureTest {

    private val t0 = 1_790_000_000_000L
    private val mire = SignalPaquets.paquetsMire(7).map { Ssdv.lis(it)!! }
    private fun dossier(): File = Files.createTempDirectory("galerie").toFile()
    private fun images(o: JSONObject) = o.getJSONArray("images").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun ssdvEnCoursEtFinieEtSauvee() {
        val d = dossier()
        var maintenant = t0
        val a = AssemblageSsdv(d) { maintenant }
        // A finished picture (written to telemesure/ssdv), then another one under way, a few packets missing.
        mire.forEachIndexed { k, p -> a.ajoute(p, t0 + k * 1_000L, "SATDEMO-1") }
        val autre = SignalPaquets.paquetsMire(8).map { Ssdv.lis(it)!! }
        maintenant = t0 + 3_600_000L
        autre.filterIndexed { k, _ -> k % 4 != 1 && k < autre.size - 2 }.forEachIndexed { k, p -> a.ajoute(p, maintenant + k * 1_000L, "SATDEMO-2") }
        // An older picture only on disk (out of the memory's list), made-up callsign.
        val vieille = File(d, "telemesure/ssdv/20260102-030405-FAKE1-3.jpg").apply { writeBytes(a.photos().last().jpeg()!!) }
        vieille.setLastModified(t0 - 86_400_000L)
        val j = GalerieTelemesure.json(listOf(a), emptyList(), d)
        val l = images(j)
        assertEquals(3, l.size)
        assertEquals(3, j.getJSONObject("n").getInt("ssdv"))
        assertEquals(1, j.getJSONObject("n").getInt("enCours"))
        // Newest first: the one under way, the finished one (once, though its JPEG is on disk too), the saved one.
        val (c, f, s) = l
        assertEquals("ssdv", c.getString("type"))
        assertTrue(c.getBoolean("enCours")); assertFalse(c.getBoolean("complete"))
        assertEquals("SATDEMO-2", c.getString("satellite"))
        assertTrue(c.getInt("manquants") > 0)
        assertTrue(c.getInt("recus") < c.getInt("total"))
        // The address changes as packets come: the page fetches the growing picture again.
        assertTrue(c.getString("src"), c.getString("src").startsWith("api/telemesure/ssdv.jpg?cle=") && c.getString("src").endsWith("&v=" + c.getInt("recus")))
        assertTrue(c.getJSONObject("detail").has("bande"))
        assertTrue(f.getBoolean("complete"))
        assertEquals(mire.size, f.getInt("recus"))
        assertEquals(0, f.getInt("manquants"))
        assertTrue(f.getString("titre").endsWith("n° 7"))
        assertEquals("ssdv-f:" + vieille.name, s.getString("cle"))
        assertEquals("FAKE1 n° 3", s.getString("titre"))
        assertTrue(s.getBoolean("complete"))
        assertEquals("api/telemesure/ssdv.jpg?f=" + vieille.name, s.getString("src"))
        assertEquals(1_767_323_045_000L, s.getLong("debut"))
        assertEquals(l.map { it.getString("cle") }.toSet().size, l.size)
    }

    @Test fun geoscanEtEssais() {
        val d = dossier()
        val g = Geoscan.Images(d) { t0 }
        val jpeg = Geoscan.jpegEssai()
        // A Geoscan picture with a chunk missing; another whose start (FF D8) has not come yet.
        Geoscan.morceaux(0x09, 1, jpeg).filterIndexed { k, _ -> k != 4 }.forEach { g.ajoute(Geoscan.morceau(it)!!, t0 + 5_000L) }
        Geoscan.morceaux(0x09, 2, jpeg).drop(1).forEach { g.ajoute(Geoscan.morceau(it)!!, t0 + 9_000L) }
        // A finished one on disk only (after a restart).
        File(d, "telemesure/geoscan").mkdirs()
        val f = File(d, "telemesure/geoscan/geoscan-20260103-101010-9-4.jpg").apply { writeBytes(jpeg); setLastModified(t0 - 60_000L) }
        // The test's SSDV picture, kept apart, marked.
        val essai = AssemblageSsdv(null) { t0 }
        mire.take(5).forEach { essai.ajoute(it, t0 + 1_000L, essai = true) }
        val j = GalerieTelemesure.json(listOf(essai), listOf(g to false), d)
        val l = images(j)
        assertEquals(listOf("geoscan", "geoscan", "ssdv", "geoscan"), l.map { it.getString("type") })
        assertEquals(3, j.getJSONObject("n").getInt("geoscan"))
        assertEquals(1, j.getJSONObject("n").getInt("ssdv"))
        val sansDebut = l[0]
        assertTrue(sansDebut.isNull("src"))
        assertEquals("239Alferov · fichier 2", sansDebut.getString("titre"))
        val trou = l[1]
        assertEquals(54, trou.getInt("manquants"))
        assertTrue(trou.getString("src").startsWith("api/telemesure/ssdv.jpg?geo="))
        assertTrue(l[2].getBoolean("essai"))
        assertEquals("geoscan-f:" + f.name, l[3].getString("cle"))
        assertEquals("239Alferov · fichier 4", l[3].getString("titre"))
        assertEquals("api/telemesure/ssdv.jpg?f=" + f.name, l[3].getString("src"))
    }

    @Test fun limiteEtDossierVide() {
        val d = dossier()
        val r = File(d, "telemesure/ssdv").apply { mkdirs() }
        for (k in 0 until 12) File(r, "20260101-0000%02d-FAKE2-%d.jpg".format(k, k)).apply { writeBytes(byteArrayOf(1)); setLastModified(t0 + k * 1000L) }
        // Not a picture's name: left out.
        File(r, "notes.txt").writeText("x")
        val j = GalerieTelemesure.json(emptyList(), emptyList(), d, max = 5)
        val l = images(j)
        assertEquals(5, l.size)
        assertEquals(12, j.getJSONObject("n").getInt("ssdv"))
        assertEquals("FAKE2 n° 11", l[0].getString("titre"))
        assertEquals(0, images(GalerieTelemesure.json(emptyList(), emptyList(), dossier())).size)
        assertEquals(0, images(GalerieTelemesure.json(emptyList(), emptyList(), null)).size)
    }
}
