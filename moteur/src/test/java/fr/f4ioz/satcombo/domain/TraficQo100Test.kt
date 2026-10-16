/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraficQo100Test {

    @Test fun `the receiver on QO-100 and running makes QO-100 the context`() {
        val c = TraficQo100.contexte(true, true, false, 10_489_744_000L, "SSB", "ISS", 25544)
        assertTrue(c.qo100)
        assertEquals("QO-100", c.satellite)
        assertEquals(Qo100.NORAD, c.catnum)
        assertEquals(TraficQo100.Par.CLE, c.par)
        assertEquals(10_489_744_000L - Qo100.DECALAGE_HZ, c.monteeHz)
        assertEquals("QO-100 · 10 489,744 USB", c.libelle)
    }

    @Test fun `the receiver set on QO-100 but stopped does not count`() {
        val c = TraficQo100.contexte(true, false, false, 10_489_744_000L, "SSB", "ISS", 25544)
        assertFalse(c.qo100)
        assertEquals("ISS", c.satellite)
        assertEquals(TraficQo100.Par.SUIVI, c.par)
    }

    @Test fun `the rig on QO-100 is enough, and nothing followed gives nothing`() {
        val p = TraficQo100.contexte(false, false, true, 10_489_510_000L, "CW", null, 0)
        assertTrue(p.qo100); assertEquals(TraficQo100.Par.POSTE, p.par); assertEquals("CW", p.modeCarnet)
        assertEquals("QO-100 · 10 489,510 CW", p.libelle)
        val r = TraficQo100.contexte(false, true, false, 10_489_510_000L, "SSB", null, 0)
        assertEquals(TraficQo100.Par.AUCUN, r.par); assertEquals("", r.libelle)
    }

    @Test fun `a blank log mode is SSB`() {
        assertEquals("SSB", TraficQo100.contexte(true, true, false, 10_489_744_000L, "", null, 0).modeCarnet)
    }

    @Test fun `a voice is tuned with its carrier below it, in USB`() {
        val a = TraficQo100.accord("blu", 10_489_761_500L)
        assertEquals("USB", a.demod); assertEquals("SSB", a.modeCarnet); assertEquals("phonie", a.decodeur)
        assertEquals(10_489_760_000L, a.cadranHz)
    }

    @Test fun `CW is heard as a 700 Hz note`() {
        val a = TraficQo100.accord("cw", 10_489_520_700L)
        assertEquals(10_489_520_000L, a.cadranHz); assertEquals("CW", a.modeCarnet); assertEquals("cw", a.decodeur)
    }

    @Test fun `FT8 in its window keeps the usual dial, elsewhere one that puts it in the audio`() {
        val a = TraficQo100.accord("ft8", 10_489_541_200L)
        assertEquals(TraficQo100.CADRAN_FT8_HZ, a.cadranHz); assertEquals("FT8", a.ft8Mode); assertEquals("ft8", a.decodeur)
        val b = TraficQo100.accord("ft4", 10_489_601_300L)
        assertEquals(10_489_600_000L, b.cadranHz); assertEquals("FT4", b.modeCarnet)
        assertTrue(10_489_601_300L - b.cadranHz in 200L..2_900L)
    }

    @Test fun `SSTV centred at 1 900 Hz, and the panel for each class`() {
        assertEquals(10_489_800_000L, TraficQo100.accord("sstv", 10_489_801_900L).cadranHz)
        assertEquals("sstv", TraficQo100.decodeurDe("sstv"))
        assertEquals("", TraficQo100.decodeurDe("balise"))
        assertEquals("", TraficQo100.decodeurDe("inconnu"))
        assertEquals("ft8", TraficQo100.decodeurDe("ft4"))
    }

    @Test fun `resuming is offered only when it was cut, and done alone only when chosen`() {
        assertTrue(TraficQo100.aReprendre(enCours = true, recoitMaintenant = false))
        assertFalse(TraficQo100.aReprendre(enCours = true, recoitMaintenant = true))
        assertFalse(TraficQo100.aReprendre(enCours = false, recoitMaintenant = false))
        assertFalse(TraficQo100.repriseAuto(choisie = false, enCours = true))
        assertTrue(TraficQo100.repriseAuto(choisie = true, enCours = true))
        assertFalse(TraficQo100.repriseAuto(choisie = true, enCours = false))
    }

    @Test fun `a large offset without automatic calibration is pointed out`() {
        assertTrue(TraficQo100.conseilCalage(7_400L, auto = false))
        assertTrue(TraficQo100.conseilCalage(-900L, auto = false))
        assertFalse(TraficQo100.conseilCalage(7_400L, auto = true))
        assertFalse(TraficQo100.conseilCalage(120L, auto = false))
        assertFalse(TraficQo100.conseilCalage(null, auto = false))
    }

    @Test fun `the session is kept in the settings, the automatic resume off by default`() {
        val s = SettingsStore(StockageMemoire())
        assertFalse(s.qo100EnCours); assertFalse(s.qo100RepriseAuto); assertEquals("SSB", s.qo100ModeCarnet)
        s.qo100EnCours = true; s.qo100DernierHz = 10_489_744_000L; s.qo100ModeCarnet = "FT8"
        assertTrue(s.qo100EnCours); assertEquals(10_489_744_000L, s.qo100DernierHz); assertEquals("FT8", s.qo100ModeCarnet)
    }

    @Test fun `kHz written the French way`() {
        assertEquals("10 489,750", TraficQo100.khz(10_489_750_000L))
        assertEquals("145,800", TraficQo100.khz(145_800_400L))
    }
}
