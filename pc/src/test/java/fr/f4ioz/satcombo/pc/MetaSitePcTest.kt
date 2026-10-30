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
import fr.f4ioz.satcombo.sstv.ClassementSstv
import fr.f4ioz.satcombo.sstv.EvenementSstv
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.RapportEvenement
import fr.f4ioz.satcombo.sstv.SstvMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The words « Publication web » adds about SSTV: the pass, the auto-pilot, a planche's slides and passes. */
class MetaSitePcTest {

    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()
    private val aos = t("2026-10-03T13:49:30Z")
    private val los = t("2026-10-03T13:59:40Z")
    private val pass = SatPass("ISS", 25544, aos, los, 62.37, 230.0, 60.0, false, false)
    private val autre = SatPass("ISS", 25544, aos + 5_580_000L, los + 5_580_000L, 12.0, 250.0, 80.0, false, false)

    private fun trace(statut: CampagneSstv.Statut = CampagneSstv.Statut.FAIT, images: List<String> = emptyList()) =
        CampagneSstv.Trace("ariss-34", "ARISS série 34", 25544, "ISS", aos + 20_000L, los, 62.0, statut, images = images)

    @Test
    fun `a pass's id is the satellite and its AOS to the minute`() {
        assertEquals("25544-20261003T1349Z", MetaSitePc.idPassage(25544, aos))
        assertEquals(pass, MetaSitePc.passageDe(listOf(autre, pass), aos + 60_000L))
        assertNull(MetaSitePc.passageDe(listOf(pass), los + 10 * 60_000L))
    }

    @Test
    fun `an SSTV picture says its pass and whether the auto-pilot received it`() {
        val s = SstvMeta.SstvShot("SSTV_ISS_20261003_135119_PD120.png", "ISS", t("2026-10-03T13:51:19Z"), mode = "PD120", diapo = 3, diapos = 12)
        val o = MetaSitePc.sstv(JSONObject(), s, 25544, listOf(pass, autre), listOf(trace(images = listOf(s.fileName))))
        assertEquals("25544-20261003T1349Z", o.getString("passage"))
        assertEquals("2026-10-03T13:49:30Z", o.getString("aos"))
        assertEquals("2026-10-03T13:59:40Z", o.getString("los"))
        assertEquals(62.4, o.getDouble("elMax"), 1e-9)
        assertTrue(o.getBoolean("autopilote"))
        assertEquals("ARISS série 34", o.getString("campagne"))
        assertEquals(3, o.getInt("diapo")); assertEquals(12, o.getInt("diapos"))

        // Received by hand: no campaign; a pass set aside by the auto-pilot is not its.
        val main = MetaSitePc.sstv(JSONObject(), s, 25544, listOf(pass), listOf(trace(CampagneSstv.Statut.ECARTE)))
        assertFalse(main.getBoolean("autopilote")); assertFalse(main.has("campagne"))
        // An older picture: the auto-pilot's note says it.
        val note = MetaSitePc.sstv(JSONObject(), s.copy(note = "Campagne : ARISS octobre"), 25544, emptyList(), emptyList())
        assertTrue(note.getBoolean("autopilote")); assertEquals("ARISS octobre", note.getString("campagne")); assertFalse(note.has("passage"))
    }

    @Test
    fun `a retouched SSTV picture says so - couleurs or ia, its model`() {
        // As received: nothing added.
        assertEquals(0, MetaSitePc.retouche(JSONObject(), "").length())
        assertEquals(0, MetaSitePc.retouche(JSONObject(), "autre").length())
        val c = MetaSitePc.retouche(JSONObject(), "couleurs")
        assertEquals("couleurs", c.getString("retouche")); assertFalse(c.has("ia")); assertFalse(c.has("modeleIa"))
        val i = MetaSitePc.retouche(JSONObject(), "ia", "SCUNet real-PSNR")
        assertEquals("ia", i.getString("retouche")); assertTrue(i.getBoolean("ia")); assertEquals("SCUNet real-PSNR", i.getString("modeleIa"))
    }

    @Test
    fun `a journal video says its pass, the auto-pilot and its planches`() {
        val v = MetaSitePc.video("SatMe_ISS_20261003_134930Z_passage.mp4")
        assertNotNull(v); v!!
        assertEquals("ISS", v.satellite); assertEquals(aos, v.debutMs); assertEquals("passage", v.sorte)
        assertNull(MetaSitePc.video("SatMe_ISS_20261003_134930Z_passage.gif"))
        assertEquals("ISS-SSTV", MetaSitePc.video("SatMe_ISS-SSTV_20261003_134930Z_session_Ma-video.mp4")?.satellite)
        val o = MetaSitePc.video(JSONObject(), v, 25544, listOf(pass, autre), listOf(trace()), listOf("planche:serie34"))
        assertEquals("25544-20261003T1349Z", o.getString("passage"))
        assertTrue(o.getBoolean("autopilote")); assertEquals("ARISS série 34", o.getString("campagne"))
        assertEquals("planche:serie34", o.getJSONArray("planches").getString(0))
        assertTrue(MetaSitePc.videoDuPassage(v, "ISS (ZARYA)", aos, los))
        assertFalse(MetaSitePc.videoDuPassage(v, "SO-50", aos, los))
        assertFalse(MetaSitePc.videoDuPassage(v, "ISS", aos + 3_600_000L, los + 3_600_000L))
    }

    @Test
    fun `a planche with its event says its slides, its passes, its videos and its report`() {
        val ev = EvenementSstv.Evenement("c:ariss-34", EvenementSstv.Genre.CAMPAGNE, "ARISS série 34", "ISS", 25544,
            t("2026-10-02T00:00:00Z"), t("2026-10-06T23:59:00Z"), nb = 4)
        val a = SstvMeta.SstvShot("a.png", "ISS", aos + 60_000L, mode = "PD120")
        val b = SstvMeta.SstvShot("b.png", "ISS", aos + 200_000L, mode = "PD120")
        val c = SstvMeta.SstvShot("c.png", "ISS", aos + 5_700_000L, mode = "Robot36")
        val shots = listOf(a, b, c).associateBy { it.fileName }
        fun im(s: SstvMeta.SstvShot, d: Int, p: Long) = RapportEvenement.Image(s.fileName, s.timeMs, s.timeMs - 110_000L, d, 4, ClassementSstv.Origine.LU, 80, true, passageMs = p)
        val ia = im(a, 1, aos); val ib = im(b, 2, aos); val ic = im(c, 4, autre.aosEpochMs)
        fun pa(p: SatPass, l: List<RapportEvenement.Image>) = RapportEvenement.Passage(p.aosEpochMs, p.losEpochMs, p.maxElevationDeg, 0.0, 0.0, emptyList(),
            emptyList(), emptyList(), emptyList(), l, RapportEvenement.Statut.RECU)
        val vide = RapportEvenement.Passage(aos + 11_160_000L, los + 11_160_000L, 30.0, 0.0, 0.0, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), RapportEvenement.Statut.ATTENDU_RIEN)
        val diapos = listOf(
            RapportEvenement.Diapo(1, ClassementSstv.Origine.LU, "a.png", 80, 1, 1),
            RapportEvenement.Diapo(2, ClassementSstv.Origine.ESTIME, "b.png", 70, 1, 1),
            RapportEvenement.Diapo(3, ClassementSstv.Origine.AUCUNE, null, -1, 0, 0),
            RapportEvenement.Diapo(4, ClassementSstv.Origine.LU, "c.png", 90, 2, 1))
        val r = RapportEvenement.Rapport(ev, RapportEvenement.Station("F4XYZ", "JN18FS"), 0L, 10.0, diapos, listOf(pa(pass, listOf(ia, ib)), pa(autre, listOf(ic)), vide),
            listOf(ia, ib, ic), RapportEvenement.Stats(3, 3, 2, 1, 0, 3, 3, 0, 3, 1, 80, 90, a.timeMs, c.timeMs), null, RapportEvenement.Geometrie(false))
        val m = Planche.Modele("serie34", "Série 34", cases = List(4) { Planche.Zone(0f, 0f, .2f, .2f) }, images = mapOf(0 to "a.png", 1 to "b.png", 3 to "c.png"), evenement = ev)
        val camp = CampagneSstv.Campagne("ariss-34", "ARISS série 34", 25544, "ISS", debutMs = ev.debutMs, finMs = ev.finMs)
        val video = MetaSitePc.video("SatMe_ISS_20261003_134930Z_passage.mp4")!!
        val o = MetaSitePc.planche(JSONObject(), m, r, shots, camp, listOf(trace()), listOf(video))

        assertEquals("ISS", o.getString("satellite")); assertEquals(25544, o.getInt("catnum"))
        assertEquals("c:ariss-34", o.getString("evenementId")); assertEquals("campagne", o.getString("evenementGenre"))
        assertEquals("ARISS série 34", o.getString("campagne")); assertEquals("ariss-34", o.getString("campagneId"))
        assertEquals("PD120", o.getString("mode"))
        assertEquals(Instant.ofEpochMilli(a.timeMs).toString(), o.getString("premiere"))
        assertEquals(Instant.ofEpochMilli(c.timeMs).toString(), o.getString("derniere"))
        assertEquals(4, o.getInt("diapos")); assertEquals(3, o.getInt("diaposRecues"))
        assertEquals(listOf(3), o.getJSONArray("diaposManquantes").toList())
        val best = o.getJSONArray("meilleures")
        assertEquals(3, best.length())
        assertEquals("sstv:a.png", best.getJSONObject(0).getString("image")); assertEquals(80, best.getJSONObject(0).getInt("note"))
        assertTrue(best.getJSONObject(1).getBoolean("estimee"))
        val ps = o.getJSONArray("passages")
        assertEquals("only the passes that brought pictures", 2, ps.length())
        val p0 = ps.getJSONObject(0)
        assertEquals("25544-20261003T1349Z", p0.getString("id")); assertEquals(2, p0.getInt("images"))
        assertTrue(p0.getBoolean("autopilote")); assertEquals("ARISS série 34", p0.getString("campagne"))
        assertEquals("journal:" + video.fichier, p0.getString("video"))
        assertFalse(ps.getJSONObject(1).getBoolean("autopilote")); assertFalse(ps.getJSONObject(1).has("video"))
        assertTrue(o.getBoolean("autopilote"))
        assertEquals("journal:" + video.fichier, o.getJSONArray("videos").getString(0))
        assertEquals("rapport:serie34", o.getString("rapport"))

        val rap = MetaSitePc.rapport(JSONObject(), m, r, camp)
        assertEquals("planche:serie34", rap.getString("planche")); assertEquals("ISS", rap.getString("satellite"))
        assertEquals(Instant.ofEpochMilli(a.timeMs).toString(), rap.getString("premiere"))
    }

    @Test
    fun `a planche without an event says what its boxes hold`() {
        val a = SstvMeta.SstvShot("a.png", "SO-50", aos, mode = "Robot36")
        val m = Planche.Modele("p1", "Planche", cases = List(3) { Planche.Zone(0f, 0f, .2f, .2f) }, images = mapOf(0 to "a.png", 2 to "absente.png"))
        val o = MetaSitePc.planche(JSONObject(), m, null, mapOf("a.png" to a), null, emptyList(), emptyList())
        assertEquals("SO-50", o.getString("satellite")); assertEquals("Robot36", o.getString("mode"))
        assertEquals(3, o.getInt("diapos")); assertEquals(1, o.getInt("diaposRecues"))
        assertEquals(listOf(2, 3), o.getJSONArray("diaposManquantes").toList())
        assertEquals("sstv:a.png", o.getJSONArray("meilleures").getJSONObject(0).getString("image"))
        assertFalse(o.has("passages")); assertFalse(o.has("evenementId"))
        assertEquals("rapport:p1", o.getString("rapport"))
    }

    @Test fun `an SSDV picture - its packets, its passes, the auto-pilot`() {
        val so = SatPass("HADES-SA", 68446, aos, los, 48.2, 200.0, 20.0, false, false)
        val suivant = SatPass("HADES-SA", 68446, aos + 5_700_000L, los + 5_700_000L, 22.0, 250.0, 80.0, false, false)
        val tr = CampagneSstv.Trace("c1", "SO-127 octobre", 68446, "HADES-SA", so.aosEpochMs, so.losEpochMs, 48.2, CampagneSstv.Statut.FAIT)
        val i = MetaSitePc.ImageSsdv("20261030-091204-Q0SAT-17", "HADES-SA", "Q0SAT", 17, aos + 60_000L, suivant.aosEpochMs + 120_000L,
            38, 40, false, 2, false, 320, 240, listOf(
                MetaSitePc.PartSsdv(aos + 60_000L, aos + 300_000L, 21, "radio"),
                MetaSitePc.PartSsdv(suivant.aosEpochMs + 30_000L, suivant.aosEpochMs + 120_000L, 17, "radio")))
        val o = MetaSitePc.ssdv(JSONObject(), i, 68446, listOf(so, suivant), listOf(tr))
        assertEquals("HADES-SA", o.getString("satellite")); assertEquals(68446, o.getInt("catnum"))
        assertEquals(Instant.ofEpochMilli(aos + 60_000L).toString(), o.getString("utc"))
        assertEquals(o.getString("utc"), o.getString("premier"))
        assertEquals(17, o.getInt("image")); assertEquals(38, o.getInt("paquetsRecus")); assertEquals(40, o.getInt("paquetsAttendus"))
        assertEquals(2, o.getInt("manquants")); assertFalse(o.getBoolean("complete")); assertFalse(o.has("attendusEstime"))
        val ps = o.getJSONArray("passages")
        assertEquals(2, ps.length())
        assertEquals(MetaSitePc.idPassage(68446, so.aosEpochMs), ps.getJSONObject(0).getString("id"))
        assertEquals(Instant.ofEpochMilli(so.losEpochMs).toString(), ps.getJSONObject(0).getString("los"))
        assertEquals(21, ps.getJSONObject(0).getInt("paquets"))
        assertTrue(ps.getJSONObject(0).getBoolean("autopilote")); assertEquals("SO-127 octobre", ps.getJSONObject(0).getString("campagne"))
        assertFalse(ps.getJSONObject(1).has("autopilote"))
        assertTrue(o.getBoolean("autopilote")); assertEquals("SO-127 octobre", o.getString("campagne"))
    }
}
