/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.hf.Amont
import fr.f4ioz.site.hf.Bandes
import fr.f4ioz.site.hf.CartePropagation
import fr.f4ioz.site.hf.Geo
import fr.f4ioz.site.hf.IndicatifGeo
import fr.f4ioz.site.hf.Kc2g
import fr.f4ioz.site.hf.Memo
import fr.f4ioz.site.hf.PskReporter
import fr.f4ioz.site.hf.Rbn
import fr.f4ioz.site.hf.RbnArchives
import fr.f4ioz.site.hf.Solaire
import fr.f4ioz.site.hf.WsprLive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The HF data sources read from recorded answers: nothing here goes to the network. */
class HfTest {

    private fun fixture(n: String): String = HfTest::class.java.getResourceAsStream("/fixtures/$n")!!.readBytes().toString(Charsets.UTF_8)

    // ── HamQSL ──────────────────────────────────────────────────────────────
    @Test fun `HamQSL's XML gives the indices, the HF conditions and the VHF phenomena`() {
        val o = Solaire.analyse(fixture("hamqsl.xml"))
        assertEquals("89", o.getString("solarflux"))
        assertEquals("2", o.getString("kindex"))
        assertEquals("B3.4", o.getString("xray"))
        assertEquals("Good", o.getJSONObject("hf_conditions").getJSONObject("80m-40m").getString("night"))
        assertEquals("Fair", o.getString("hf_30m-20m_day"))
        assertEquals("Band Closed", o.getJSONObject("vhf_conditions").getString("E-Skip|europe_6m"))
        // A container's children are not flattened into it.
        assertFalse(o.has("calculatedconditions"))
    }

    @Test fun `an XML with a DTD is refused`() {
        try { Solaire.analyse("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><solar><solardata><source>&e;</source></solardata></solar>"); fail() }
        catch (e: Exception) { /* refused */ }
    }

    // ── PSKReporter ─────────────────────────────────────────────────────────
    @Test fun `PSKReporter's reports are read, most recent first, with the pages' field names`() {
        val l = PskReporter.analyse(fixture("pskreporter.xml"))
        assertEquals(31, l.size)
        assertTrue(l.zipWithNext().all { (a, b) -> a.ts >= b.ts })
        val s = l.first { it.tx == "OS8E" }
        assertEquals("80m", s.bande)
        assertEquals("JN29", s.txLoc)
        assertEquals("Belgium", s.txDxcc)
        assertEquals("EU", s.txContinent)
        assertEquals("-21", s.snr)
        val j = s.json()
        for (k in listOf("tx", "tx_loc", "tx_dxcc", "tx_dxcc_code", "tx_continent", "rx", "rx_loc", "rx_dxcc", "rx_dxcc_code", "rx_continent", "freq_hz", "freq_khz", "band", "mode", "snr", "ts", "lotw"))
            assertTrue(k, j.has(k))
        assertEquals(3574.566, j.getDouble("freq_khz"), 1e-9)
        assertEquals("2026-09-29", j.getString("lotw"))
        assertEquals(5, PskReporter.analyse(fixture("pskreporter.xml"), 5).size)
    }

    @Test fun `PSKReporter answering JSON is a refusal, not an empty list`() {
        try { PskReporter.analyse("{\"error\":\"rate limited\"}"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("limite")) }
        try { PskReporter.analyse("Too many requests"); fail() } catch (e: IOException) { /* refused */ }
    }

    @Test fun `one query shape per mode, with the contact, the window and band cut locally`() {
        val p = PskReporter(Amont(true, "essai"), "https://f4ioz.example")
        val u = p.url("FT8")
        assertTrue(u.contains("flowStartSeconds=-1800")); assertTrue(u.contains("rronly=1")); assertTrue(u.contains("mode=FT8"))
        assertTrue(u.contains("appcontact=https%3A%2F%2Ff4ioz.example"))
        assertFalse(p.url("").contains("mode="))
        val l = PskReporter.analyse(fixture("pskreporter.xml"))
        val t = l.maxOf { it.ts }
        assertEquals(l.count { it.bande == "80m" }, PskReporter.filtre(l, t, 600, "80m", 1000).size)
        assertEquals(3, PskReporter.filtre(l, t, 600, "", 3).size)
        assertTrue(PskReporter.filtre(l, t + 10_000, 300, "", 1000).isEmpty())
    }

    // ── The memo: one fetch at a time, the last good value kept ─────────────
    @Test fun `a failed fetch keeps the last good value and waits before asking again`() {
        var t = 1_000_000L
        val amont = Amont(false, "essai")
        val m = Memo<String>(amont, 5 * 60_000L, 10 * 60_000L, 4) { t }
        val appels = AtomicInteger()
        assertEquals("a", m.lis("k", 5_000L) { appels.incrementAndGet(); "a" }.valeur)
        assertEquals("a", m.lis("k", 5_000L) { appels.incrementAndGet(); "b" }.valeur)
        assertEquals(1, appels.get())   // fresh: not asked again
        t += 6 * 60_000L
        m.lis("k") { appels.incrementAndGet(); throw IOException("refus") }
        Thread.sleep(300)
        val l = m.lis("k") { appels.incrementAndGet(); "c" }
        assertEquals("a", l.valeur)       // the last good one, not nothing (the old site's bug)
        assertEquals("refus", l.erreur)
        assertEquals(2, appels.get())     // in its pause: not asked
        t += 11 * 60_000L
        m.lis("k", 5_000L) { appels.incrementAndGet(); "c" }
        assertEquals("c", m.lis("k") { "d" }.valeur)
    }

    @Test fun `the memo keeps a bounded number of keys`() {
        val m = Memo<String>(Amont(true, "essai"), 60_000L, 60_000L, 3)
        for (i in 0 until 10) m.pose("k$i", "v")
        assertEquals(3, m.taille())
    }

    // ── KC2G ────────────────────────────────────────────────────────────────
    @Test fun `KC2G's contours are kept with their value, MultiLineString included`() {
        val o = Kc2g.allege(JSONObject(fixture("kc2g-muf.json")))
        val f = o.getJSONArray("features")
        assertEquals(3, f.length())
        assertEquals(10.1, f.getJSONObject(0).getJSONObject("properties").getDouble("level-value"), 1e-9)
        val multi = JSONObject().put("features", JSONArray().put(JSONObject().put("properties", JSONObject().put("level-value", 14.0))
            .put("geometry", JSONObject().put("type", "MultiLineString").put("coordinates", JSONArray("[[[0,0],[1,1]],[[2,2],[3,3],[4,4]]]")))))
        assertEquals(2, Kc2g.allege(multi).getJSONArray("features").length())
    }

    // ── wspr.live ───────────────────────────────────────────────────────────
    @Test fun `a callsign reaches the SQL stripped`() {
        assertEquals("F4IOZ", WsprLive.indicatifNet("f4ioz' OR 1=1 --"
            .substringBefore('\'')))
        assertEquals("F4IOZOR11", WsprLive.indicatifNet("f4ioz' OR 1=1"))
        assertEquals(12, WsprLive.indicatifNet("ABCDEFGHIJKLMNOP").length)
        assertEquals("EA8/F4IOZ", WsprLive.indicatifNet("ea8/f4ioz;"))
        assertEquals(14, Bandes.wsprCode("20m")); assertNull(Bandes.wsprCode("20m; DROP"))
        assertEquals("630m", Bandes.wsprNom(0)); assertEquals("999 MHz", Bandes.wsprNom(999)); assertEquals("40 MHz", Bandes.wsprNom(40))
    }

    @Test fun `wspr's band activity is read, numbers as strings too`() {
        val a = WsprLive.activite(JSONObject(fixture("wspr-bandes.json")))
        assertTrue(a.length() > 5)
        assertEquals("2190m", a.getJSONObject(0).getString("band"))
        assertTrue((0 until a.length() - 1).all { a.getJSONObject(it).getInt("band_code") <= a.getJSONObject(it + 1).getInt("band_code") })
        val s = WsprLive.activite(JSONObject("{\"data\":[{\"band\":\"14\",\"spots\":\"12\",\"reporters\":\"3\",\"dist_med\":\"800\",\"dist_max\":\"9000\",\"snr_avg\":\"-14.25\"}]}"))
        assertEquals("20m", s.getJSONObject(0).getString("band")); assertEquals(12, s.getJSONObject(0).getInt("spots"))
    }

    @Test fun `a footprint drops the rows without a position`() {
        val j = JSONObject("{\"data\":[{\"band\":7,\"call\":\"DL1ABC\",\"loc\":\"JO62\",\"lat\":52.5,\"lon\":13.0,\"home_lat\":48.7,\"home_lon\":2.5,\"dist\":880,\"az\":60,\"snr\":-12,\"ts\":1793137050}," +
            "{\"band\":7,\"call\":\"X\",\"loc\":\"\",\"lat\":null,\"lon\":null,\"dist\":1,\"az\":1,\"snr\":1,\"ts\":1}]}")
        val o = WsprLive.empreinteDe(j, "F4IOZ", "tx", 6)
        assertEquals(1, o.getInt("count"))
        assertEquals("DL1ABC", o.getJSONObject("best_dx").getString("call"))
        assertEquals(48.7, o.getJSONObject("home").getDouble("lat"), 1e-9)
        assertEquals(0, WsprLive(Amont(true, "essai"), "https://db1.wspr.live").empreinte("", 6, "tx").getInt("count"))
    }

    // ── RBN live ────────────────────────────────────────────────────────────
    @Test fun `RBN lines are read, RTTY's « BPS » ones too, noise refused`() {
        val s = Rbn.analyse("DX de EA5WU-#:     7020.5  F4IOZ        CW    13 dB  22 WPM  CQ      2137Z", 1000L)!!
        assertEquals("EA5WU", s.skimmer); assertEquals(7020.5, s.khz, 1e-9); assertEquals("40m", s.bande); assertEquals("F4IOZ", s.dx)
        assertEquals(13, s.snr); assertEquals(22, s.wpm); assertEquals("CQ", s.type); assertNotNull(s.rxLat)
        val r = Rbn.analyse("DX de W6YX-#:    10143.9  KD7BCC       RTTY   23 dB  45 BPS  CQ      0305Z", 1000L)!!
        assertEquals("RTTY", r.mode); assertEquals("30m", r.bande)
        assertEquals(-3, Rbn.analyse("DX de DK9IP-#:   14025.0  JA1ABC       CW    -3 dB  25 WPM  CQ      1200Z", 0L)!!.snr)
        assertEquals("NCDXF B", Rbn.analyse("DX de OH6BG-#:   14100.0  4X6TU        CW    12 dB  22 WPM  NCDXF B 1201Z", 0L)!!.type)
        assertNull(Rbn.analyse("Please enter your call:", 0L))
        assertNull(Rbn.analyse("F4IOZ de RELAY 27-Oct-2026 2137Z >", 0L))
    }

    @Test fun `RBN keeps 35 minutes, aggregates per band and a call's footprint`() {
        var t = 10_000_000L
        val rbn = Rbn("F4IOZ", false) { t }
        rbn.ajoute(Rbn.analyse("DX de DK9IP-#: 7020.0 F4IOZ CW 10 dB 22 WPM CQ 1200Z", t)!!)
        t += 36 * 60_000L
        rbn.ajoute(Rbn.analyse("DX de DK9IP-#: 7021.0 F4IOZ CW 12 dB 22 WPM CQ 1236Z", t)!!)
        rbn.ajoute(Rbn.analyse("DX de DK9IP-#: 7021.0 F4IOZ CW 20 dB 22 WPM CQ 1236Z", t)!!)
        rbn.ajoute(Rbn.analyse("DX de W3LPL-#: 14021.0 F4IOZ CW 8 dB 22 WPM CQ 1236Z", t)!!)
        rbn.ajoute(Rbn.analyse("DX de W3LPL-#: 14022.0 G4ABC CW 9 dB 22 WPM CQ 1236Z", t)!!)
        val l = rbn.recents(30 * 60_000L)
        assertEquals(4, l.size)   // the first, 36 min old, is gone
        val b = Rbn.parBande(l)
        assertEquals("40m", b.getJSONObject(0).getString("band"))
        assertEquals(20, b.getJSONObject(0).getInt("snr_max"))
        assertEquals(2, b.getJSONObject(1).getInt("dx"))
        val e = Rbn.empreinte("F4IOZ", l, Geo.locator("JN18FS"))
        assertEquals(2, e.getInt("count"))   // best SNR per (skimmer, band)
        assertEquals("W3LPL", e.getJSONObject("best_dx").getString("call"))
        val c = Rbn.carte(l)
        assertEquals(4, c.getInt("total"))
        assertEquals("rbn", c.getString("source"))
    }

    // ── Where a callsign is ─────────────────────────────────────────────────
    @Test fun `operating suffixes are not countries`() {
        val dl = IndicatifGeo.position("DL1ABC")!!
        assertEquals(dl, IndicatifGeo.position("DL1ABC/M"))   // not England
        assertEquals(dl, IndicatifGeo.position("DL1ABC/P"))
        assertEquals(dl, IndicatifGeo.position("DL1ABC/A"))   // not the USA
        assertNull(IndicatifGeo.position("DL1ABC/MM"))         // at sea: nowhere (not Scotland)
        val f = IndicatifGeo.position("F4IOZ")!!
        assertEquals(f, IndicatifGeo.position("F/DL1ABC"))
        assertTrue(IndicatifGeo.position("JA1ABC")!!.second > 100)
        assertTrue(IndicatifGeo.position("W1AW")!!.second < -60)
        assertTrue(IndicatifGeo.position("EA8AB")!!.first < 30)
        assertTrue(IndicatifGeo.position("TF3AB")!!.first > 60)
        assertNull(IndicatifGeo.position("QZ9ZZZ"))
        assertEquals("EU", IndicatifGeo.continent("F4IOZ"))
    }

    @Test fun `locators of 8 and 10 characters are read to 6`() {
        assertEquals(Geo.locator("JN18FS"), Geo.locator("JN18fs23"))
        assertEquals(Geo.locator("JN18FS"), Geo.locator("JN18FS23AB"))
        assertNull(Geo.locator("ZZ99")); assertNull(Geo.locator("JN1"))
        assertEquals(344.0, Geo.distanceKm(48.85, 2.35, 51.5, -0.12), 25.0)
    }

    @Test fun `the PSK map's paths say who hears whom`() {
        fun spot(tx: String, txLoc: String, rx: String, rxLoc: String) = PskReporter.Spot(tx, txLoc, "", "", "", rx, rxLoc, "", "", "", 14_074_000, "20m", "FT8", "-5", 1L, "")
        val o = CartePropagation.psk(listOf(spot("F4IOZ", "JN18FS", "W1AW", "FN31"), spot("JA1ABC", "PM95", "F5XYZ", "JN18")), "JN18FS")
        val p = o.getJSONArray("qth_paths")
        assertEquals("heard", p.getJSONObject(0).getString("dir")); assertEquals("W1AW", p.getJSONObject(0).getString("call"))
        assertEquals("hearing", p.getJSONObject(1).getString("dir")); assertEquals("JA1ABC", p.getJSONObject(1).getString("call"))
        assertEquals(2, o.getJSONArray("blobs").length())
    }

    // ── RBN archives (Ouvertures) ──────────────────────────────────────────
    private fun zip(csv: String): ByteArray {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("20261025.csv")); z.write(csv.toByteArray(Charsets.ISO_8859_1)); z.closeEntry() }
        return o.toByteArray()
    }

    @Test fun `an archive day is summed for the stations near the locator, hours in UTC`() {
        val j = RbnArchives.resumeArchive(ByteArrayInputStream(zip(fixture("rbn-20261025.csv"))), "20261025", Geo.locator("JN18FS")!!)
        // The French (and Belgian, within 450 km of Paris) stations of the extract.
        assertTrue(j.spots in 25..40)
        assertTrue(j.parHeure.keys.all { it in Bandes.ORDRE_HF })
        assertTrue(j.parHeure.values.flatMap { it.keys }.all { it in 0..23 })
        val rond = RbnArchives.deJson(RbnArchives.enJson(j))
        assertEquals(j.spots, rond.spots)
        assertEquals(j.parHeure, rond.parHeure)
        val minuit = LocalDate.of(2026, 10, 25).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        assertEquals(1792886400L, minuit)
        assertEquals(minuit, RbnArchives.instant("2026-10-25 00:00:00"))
        assertEquals(minuit + 13 * 3600 + 5 * 60, RbnArchives.instant("2026-10-25 13:05:00"))
    }

    @Test fun `heat cells round half away from zero, as SQLite did`() {
        assertEquals(48, RbnArchives.latCellule(RbnArchives.cellule(48.86, 2.35)))
        assertEquals(4, RbnArchives.lonCellule(RbnArchives.cellule(48.86, 2.35)))
        assertEquals(0, RbnArchives.lonCellule(RbnArchives.cellule(48.86, 1.99)))
        assertEquals(-36, RbnArchives.latCellule(RbnArchives.cellule(-34.0, -58.4)))
        assertEquals(-60, RbnArchives.lonCellule(RbnArchives.cellule(-34.0, -58.4)))
        assertEquals(4.0, RbnArchives.arrondi(0.5) * 4, 1e-9); assertEquals(-4.0, RbnArchives.arrondi(-0.5) * 4, 1e-9)
    }

    @Test fun `only the station's locators are read, for the days asked, kept 32 days`() {
        val d = Files.createTempDirectory("rbn").toFile()
        try {
            val jour = LocalDate.of(2026, 10, 26)
            val t = jour.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000 + 3_600_000L
            val demandes = ArrayList<String>()
            val csv = fixture("rbn-20261025.csv")
            val a = RbnArchives(d, listOf("JN18FS"), 3, Amont(true, "essai"), { t }) { j, lecteur ->
                synchronized(demandes) { demandes += j }
                val iso = j.substring(0, 4) + "-" + j.substring(4, 6) + "-" + j.substring(6)
                lecteur(ByteArrayInputStream(zip(csv.replace("2026-10-25", iso))))
            }
            // Another place: no download at all.
            val autre = a.ouvertures("IO91WM", 7)
            assertTrue(autre.getString("error").contains("JN18FS"))
            assertTrue(a.chaleur("AA00", "", -1, 7).has("error"))
            assertTrue(demandes.isEmpty())
            // An old summary on disk goes.
            java.io.File(d, "JN18FS").mkdirs(); java.io.File(d, "JN18FS/20260901.json").writeText("{}")
            a.ouvertures("JN18FS", 3)
            val fin = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < fin && !(a.ouvertures("JN18FS", 3).let { !it.getBoolean("ingesting") && it.getInt("days") == 3 })) Thread.sleep(50)
            assertEquals(listOf("20261025", "20261024", "20261023"), demandes)
            assertFalse(java.io.File(d, "JN18FS/20260901.json").exists())
            val o3 = a.ouvertures("JN18FS", 3); val o1 = a.ouvertures("JN18FS", 1)
            assertEquals(3, o3.getInt("days")); assertEquals(1, o1.getInt("days"))
            // The window asked is the window summed (the old page summed every day it had).
            assertEquals(o1.getLong("total") * 3, o3.getLong("total"))
            assertTrue(a.chaleur("JN18FS", "", -1, 3).getJSONArray("cells").length() > 0)
            // Read again: nothing downloaded twice.
            a.ouvertures("JN18FS", 3); Thread.sleep(200)
            assertEquals(3, demandes.size)
        } finally { d.deleteRecursively() }
    }
}
