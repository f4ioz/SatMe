/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Transmitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraficSatelliteTest {

    private val so50 = Transmitter("Mode V/U FM - Voice Repeater CTCSS 67.0", "FM", 145_850_000L, null, 436_795_000L, null, false, true, "Transceiver")
    private val rs44 = Transmitter("Linear transponder", "USB", 145_935_000L, 145_995_000L, 435_610_000L, 435_670_000L, true, true, "Transponder")
    private val iss = Transmitter("APRS digipeater", "AFSK", 145_825_000L, null, 145_825_000L, null, false, true, "Transceiver", 1200.0)

    @Test fun preset_relu_tel_qu_ecrit_et_valeurs_folles_ecartees() {
        val p = TraficSatellite.Preset(ctcss = 670, elevationMin = 15, puissancePct = 25)
        assertEquals(p, TraficSatellite.lit(p.ecrit()))
        assertEquals(TraficSatellite.Preset(), TraficSatellite.lit(null))
        assertEquals(TraficSatellite.Preset(), TraficSatellite.lit("n'importe quoi"))
        // A tone not in the table, an elevation and a power out of range.
        val f = TraficSatellite.lit("ctcss=671;el=99;p=500")
        assertEquals(TraficSatellite.AUTO, f.ctcss)
        assertEquals(60, f.elevationMin)
        assertEquals(100, f.puissancePct)
        assertEquals(TraficSatellite.AUCUN, TraficSatellite.lit("ctcss=0").ctcss)
        assertEquals(50, TraficSatellite.TONS.size)
    }

    @Test fun armement_seulement_so50() {
        assertEquals(744, TraficSatellite.tonArmement(27607, "SO-50"))
        assertEquals(744, TraficSatellite.tonArmement(null, "SaudiSat-1C (SO-50)"))
        assertNull(TraficSatellite.tonArmement(43017, "AO-91"))
    }

    @Test fun garde_d_elevation() {
        assertNull(TraficSatellite.gardeElevation("SO-50", 5.0, 0))
        assertNull(TraficSatellite.gardeElevation("SO-50", 20.0, 15))
        assertTrue(TraficSatellite.gardeElevation("SO-50", 9.6, 15)!!.contains("15°"))
        assertNotNull(TraficSatellite.gardeElevation("SO-50", null, 15))
    }

    @Test fun garde_de_montee_avec_le_doppler() {
        // FM: the channel and its Doppler (≈3.7 kHz at 2 m) are fine.
        assertNull(TraficSatellite.gardeMontee(so50, 145_853_000L))
        assertNull(TraficSatellite.gardeMontee(so50, 145_846_500L))
        assertNotNull(TraficSatellite.gardeMontee(so50, 145_900_000L))
        // Linear: anywhere in the passband.
        assertNull(TraficSatellite.gardeMontee(rs44, 145_960_000L))
        assertNotNull(TraficSatellite.gardeMontee(rs44, 144_300_000L))
        // Nothing known: nothing to say.
        assertNull(TraficSatellite.gardeMontee(null, 145_000_000L))
        assertNull(TraficSatellite.gardeMontee(so50, null))
    }

    @Test fun macros() {
        assertEquals("EA4XX DE F4IOZ JN18 K", TraficSatellite.macro("{LUI} de {MOI} {LOC} k", "f4ioz", "jn18fs", "ea4xx", "RS-44").uppercase())
        assertEquals("CQ F4IOZ JN18FS", TraficSatellite.macro("CQ {LUI} {MOI} {LOC6}", "F4IOZ", "JN18FS", "", ""))
        assertEquals("CQ SAT DE F4IOZ", TraficSatellite.texteCw("cq sat de f4ioz !!"))
        assertEquals(TraficSatellite.CW_MAX, TraficSatellite.texteCw("E".repeat(500)).length)
    }

    @Test fun aprs_par_digipeater_seulement_sur_145825() {
        assertTrue(TraficSatellite.aprsPossible(iss))
        assertFalse(TraficSatellite.aprsPossible(so50))
        assertFalse(TraficSatellite.aprsPossible(rs44))
        assertEquals(TraficSatellite.Genre.APRS, TraficSatellite.genre(iss))
        assertEquals(TraficSatellite.Genre.FM, TraficSatellite.genre(so50))
        assertEquals(TraficSatellite.Genre.LINEAIRE, TraficSatellite.genre(rs44))
        assertTrue(TraficSatellite.conseils(TraficSatellite.Genre.APRS).any { it.contains("ARISS") })
    }

    @Test fun balise_d_un_seul_passage() {
        val b = TraficSatellite.Balise()
        val aos = 1_000_000L; val los = aos + 600_000L
        // Too early: refused.
        assertNotNull(b.arme(25544, aos, los, aos - 40 * 60_000L))
        assertNull(b.arme(25544, aos, los, aos - 60_000L))
        assertTrue(b.armee)
        b.intervalleMs = 10_000L
        assertEquals(TraficSatellite.Balise.INTERVALLE_MIN_MS, b.intervalleMs)
        b.max = 2
        // Before AOS, or low: not due.
        assertFalse(b.due(aos - 1, 30.0, 0))
        assertFalse(b.due(aos + 1_000, 5.0, 0))
        assertTrue(b.due(aos + 1_000, 12.0, 0))
        // The satellite's own lowest elevation wins when higher.
        assertFalse(b.due(aos + 1_000, 12.0, 20))
        b.emise(aos + 1_000)
        assertFalse(b.due(aos + 30_000, 30.0, 0))
        assertTrue(b.due(aos + 61_000, 30.0, 0))
        b.emise(aos + 61_000)
        // The count reached: disarmed.
        assertFalse(b.armee)
        // LOS, or another satellite: disarmed.
        b.arme(25544, aos, los, aos)
        assertFalse(b.expire(aos + 10, 25544))
        assertTrue(b.expire(aos + 10, 27607))
        b.arme(25544, aos, los, aos)
        assertTrue(b.expire(los, 25544))
        assertFalse(b.armee)
    }
}
