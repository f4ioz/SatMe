/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pointing aid, the history, the level from the AGCs, the oscillator calibrated on the beacon, the local spectrum. */
class PointageDatvTest {

    @Test fun `niveau tire des AGC selon les points mesures par le BATC`() {
        // The points themselves (shown figure = input − 30).
        assertEquals(-97.0, NiveauAgc.indicationDb(0, 3200)!!, 0.05)
        assertEquals(-80.0, NiveauAgc.indicationDb(0, 500)!!, 0.05)
        assertEquals(-70.0, NiveauAgc.indicationDb(1, 148)!!, 0.05)
        assertEquals(-60.0, NiveauAgc.indicationDb(31_000, 148)!!, 0.05)
        assertEquals(-35.0, NiveauAgc.indicationDb(37_600, 148)!!, 0.05)
        // In between: a straight line, AGC2 on a log scale.
        assertEquals(-55.0, NiveauAgc.indicationDb(32_600, 148)!!, 0.05)
        val m = NiveauAgc.indicationDb(0, 1000)!!
        assertTrue(m < -80 && m > -97)
        // Monotonic: more AGC2 (more gain needed) a lower level; more AGC1 a higher one.
        assertTrue(NiveauAgc.indicationDb(0, 2000)!! < NiveauAgc.indicationDb(0, 400)!!)
        assertTrue(NiveauAgc.indicationDb(36_000, 148)!! > NiveauAgc.indicationDb(25_000, 148)!!)
        // Beyond: the last slope, then held; nothing said yet: null.
        assertTrue(NiveauAgc.indicationDb(0, 20_000)!! < -97)
        assertEquals(-34.0, NiveauAgc.indicationDb(50_000, 148)!!, 0.05)
        assertNull(NiveauAgc.indicationDb(0, 0))
        assertEquals(-1, NiveauAgc.zone(-80.0)); assertEquals(0, NiveauAgc.zone(-60.0)); assertEquals(1, NiveauAgc.zone(-34.0))
    }

    @Test fun `tendance, maximum et decrochage`() {
        val s = SuiviPointage()
        fun p(t: Long, mer: Double?, v: Boolean = true) = s.ajoute(SuiviPointage.Point(t, mer, mer?.let { it - 4.7 }, -62.0, v))
        // 8.0 dB for 7 s, then 8.5: better than 5 s ago.
        for (t in 0L..7_000L step 250L) p(t, 8.0)
        assertEquals(0, s.tendance(7_000)!!.sens)
        for (t in 7_250L..9_000L step 250L) p(t, 8.5)
        val m = s.tendance(9_000)!!
        assertEquals(1, m.sens); assertEquals(0.5, m.deltaDb, 0.01)
        assertEquals(8.5, s.maxMer!!, 0.0); assertEquals(3.8, s.maxMarge!!, 0.01)
        // Worse again: down, the best kept.
        for (t in 9_250L..16_000L step 250L) p(t, 7.9)
        assertEquals(-1, s.tendance(13_000)!!.sens)
        assertEquals(8.5, s.maxMer!!, 0.0)
        // The lock lost: said since when; no trend without MER.
        p(16_250, null, false); p(20_000, null, false)
        assertEquals(16_250L, s.decrocheMs)
        assertNull(s.tendance(25_000))
        p(21_000, 7.0)
        assertNull(s.decrocheMs)
        // Reset: the best starts again from now.
        s.raz(); assertNull(s.maxMer); p(21_250, 7.1); assertEquals(7.1, s.maxMer!!, 0.0)
        // Two minutes kept.
        p(200_000, 7.2)
        assertTrue(s.points().all { it.t >= 80_000 })
        val j = s.json(200_000)
        assertEquals(7.2, j.getDouble("max"), 0.0)
    }

    @Test fun `historique par cases de 10 s, trous et marques`() {
        val h = HistoriqueDatv(pasMs = 10_000L, dureeMs = 3_600_000L)
        // 10 s at 8.2/8.4 locked, then 10 s half lost.
        for (t in 0L until 10_000L step 500L) h.echantillon(t, true, if (t % 1000 == 0L) 8.2 else 8.4, 3.5, -62.0)
        for (t in 10_000L until 20_000L step 500L) h.echantillon(t, t < 15_000, 6.0, 1.3, -63.0)
        h.marque(12_000, "azimut +", "retouche")
        // A gap (stopped), then again.
        h.echantillon(60_000, true, 8.0, 3.3, -62.0)
        h.echantillon(70_000, true, 8.0, 3.3, -62.0)
        val l = h.tous()
        assertEquals(3, l.size)
        assertEquals(8.3, l[0].mer!!, 0.001); assertEquals(100, l[0].verrouPct)
        assertEquals(50, l[1].verrouPct); assertEquals(20_000L - 10_000L, l[1].t)
        assertEquals(60_000L, l[2].t)
        val j = h.json(70_000, 3_600_000L, max = 2)
        assertEquals(2, j.getJSONArray("points").length())
        assertEquals(20_000L, j.getLong("pas"))
        assertEquals("azimut +", j.getJSONArray("marques").getJSONObject(0).getString("texte"))
        // An hour later, the first points gone.
        h.echantillon(3_700_000, true, 8.0, 3.3, -62.0); h.echantillon(3_710_000, true, 8.0, 3.3, -62.0)
        assertTrue(h.tous().all { it.t >= 100_000 })
        assertTrue(h.marques().isEmpty())
    }

    private fun etat(fi: Long, sr: Int = 1500, st: Int = 4) = EtatDatv(actif = true, etat = st, fiTrouveeKhz = fi, srTrouveKs = sr, merDb = 8.3)

    @Test fun `calage de l'OL sur la balise`() {
        val c = CalageBalise()
        val r = ReglageDatv(cielKhz = Datv.BALISE_KHZ, srKs = 1500, olKhz = 9_750_000L)
        // The beacon found at IF 741 497: the real oscillator is 9 750 003 (3 kHz above the one set).
        assertFalse(c.mesure(1_000, etat(741_497), r, verrouDepuisMs = 0))   // held less than 5 s
        for (t in 5_000L..40_000L step 1000L) assertTrue(c.mesure(t, etat(741_497 + (t / 1000 % 2)), r, 0))
        assertEquals(9_750_003L, c.olReel())
        assertEquals(9_750_003L, c.correction(9_750_000L))
        assertNull(c.correction(9_750_002L))   // within 3 kHz: nothing
        assertEquals(-3L, c.json(9_750_000L, 40_000).getLong("ecart"))
        // Not the beacon: another rate, DVB-S, tuned elsewhere, or too far.
        assertFalse(c.mesure(41_000, etat(741_497, sr = 333), r, 0))
        assertFalse(c.mesure(41_000, etat(741_497, st = 3), r, 0))
        assertFalse(c.mesure(41_000, etat(741_497), r.copy(cielKhz = 10_494_750L), 0))
        assertFalse(c.mesure(41_000, etat(743_250), r, 0))
        assertEquals(1, c.refusees)
    }

    @Test fun `derive du LNB en kHz par heure`() {
        val c = CalageBalise()
        val r = ReglageDatv(cielKhz = Datv.BALISE_KHZ, olKhz = 9_750_000L)
        // 1 kHz more every 10 minutes: +6 kHz an hour (the IF goes down as the oscillator goes up).
        for (s in 0L..1_800L step 5L) c.mesure(10_000 + s * 1000, etat(741_500 - s / 600), r, 0)
        assertEquals(6.0, c.deriveKhzH()!!, 1.5)
        assertNull(CalageBalise().deriveKhzH())
    }

    @Test fun `balayage SDR assemble sur l'echelle de la station sol`() {
        // RTL-SDR at 2.4 MS/s, LNB at 9750: steps overlapping across 740.5 to 749.5 MHz.
        val pas = SpectreLocal.pas(9_750_000L, 2_400_000.0)
        assertEquals(5, pas.size)
        assertTrue(pas.first() - 960_000 <= 740_500_000L && pas.last() + 960_000 >= 749_500_000L)
        assertTrue(pas.zipWithNext().all { (a, b) -> b - a < 1_920_000 })
        // A Pluto at 12 MS/s: at once.
        assertEquals(1, SpectreLocal.pas(9_750_000L, 12_000_000.0).size)
        // The test band through the whole chain: the beacon, the stations found, at their rates.
        val src = GenerateurBandeLarge(9_750_000L)
        val a = SpectreLocal.balaie(src, 2_400_000.0, 9_750_000L, { true }, vidageMs = 0L)!!
        assertEquals(1.0, a.couverture(), 0.0)
        val v = a.valeurs()
        assertEquals(SpectreLocal.POINTS, v.size)
        val sg = BandeLarge.detecte(v)
        val b = sg.single { it.balise }
        assertEquals(Datv.BALISE_KHZ.toDouble(), b.centreKhz.toDouble(), 40.0)
        assertEquals(1500, b.srKs)
        assertEquals(8.3, (b.niveau - BandeLarge.BRUIT) / BandeLarge.UNITES_PAR_DB, 1.5)
        val autres = sg.filter { !it.balise }.associateBy { it.srKs }
        assertEquals(10_494_750.0, autres.getValue(1000).centreKhz.toDouble(), 40.0)
        assertEquals(10_497_250.0, autres.getValue(333).centreKhz.toDouble(), 30.0)
        assertEquals(10_498_750.0, autres.getValue(125).centreKhz.toDouble(), 30.0)
        assertNull(SpectreLocal.avertissement(sg, 0L))
        assertNull(SpectreLocal.avertissement(sg, 10_491_520L))
        assertNotNull(SpectreLocal.avertissement(sg, 10_492_000L))
        assertNotNull(SpectreLocal.avertissement(sg.filter { !it.balise }, 0L))
        // Another LNB oscillator for the SDR's sweep: everything moves (the warning's case).
        val decale = SpectreLocal.balaie(GenerateurBandeLarge(9_749_700L), 2_400_000.0, 9_750_000L, { true }, vidageMs = 0L)!!
        val b2 = BandeLarge.detecte(decale.valeurs()).first { it.srKs == 1500 }
        assertEquals(Datv.BALISE_KHZ + 300.0, b2.centreKhz.toDouble(), 40.0)
        // Stopped half way: nothing.
        var n = 0
        assertNull(SpectreLocal.balaie(GenerateurBandeLarge(9_750_000L), 2_400_000.0, 9_750_000L, { ++n < 3 }, vidageMs = 0L))
    }
}
