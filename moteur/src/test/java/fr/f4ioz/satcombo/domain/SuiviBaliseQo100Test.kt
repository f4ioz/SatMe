/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiviBaliseQo100Test {

    private fun m(ecart: Double, rapport: Float) = MesureBalise.Mesure(ecart, -20f + rapport, -20f)

    @Test fun `le calage suit la balise, sans croire une mesure faible ou trop loin`() {
        val s = SuiviBaliseQo100()
        assertEquals(1_080L, s.mesure(0, m(100.0, 20f), 1_000, corrige = true))   // 80 % of 100 Hz
        assertNull(s.mesure(1, m(5.0, 20f), 1_000, corrige = true))               // close enough
        assertNull(s.mesure(2, m(300.0, 6f), 1_000, corrige = true))              // too weak
        assertNull(s.mesure(3, m(9_000.0, 25f), 1_000, corrige = true))           // another line
        assertNull(s.mesure(4, m(100.0, 20f), 1_000, corrige = false))            // measured, not written
        assertTrue(s.verrouille(10_000))
    }

    @Test fun `la derive du LNB en Hz par minute`() {
        val s = SuiviBaliseQo100()
        // The LNB warms: its error grows by 30 Hz a minute; the calibration follows it.
        var calage = 0L
        for (k in 0..20) {
            val t = k * 30_000L
            val erreur = 30.0 * t / 60_000.0
            s.mesure(t, m(erreur - calage, 20f), calage, corrige = true)?.let { calage = it }
        }
        val d = s.deriveHzParMin(600_000L)!!
        assertTrue("$d", d in 25.0..35.0)
        assertEquals(1.03, SuiviBaliseQo100.ppm(10_000.0), 0.01)
    }

    @Test fun `les stations du transpondeur, pas les balises`() {
        val bas = Qo100.REGLETTE_BAS_HZ; val haut = Qo100.REGLETTE_HAUT_HZ
        val n = 1000
        val cols = FloatArray(n) { -50f }
        fun col(hz: Long) = ((hz - bas).toDouble() / (haut - bas) * n).toInt()
        // A station in the SSB segment, three columns wide; the middle beacon.
        for (k in -1..1) cols[col(10_489_690_000L) + k] = -30f
        cols[col(Qo100.BALISE_MEDIANE_HZ)] = -20f
        val l = SignauxQo100.trouve(cols, bas, haut)
        assertEquals(1, l.size)
        assertTrue(kotlin.math.abs(l[0].hz - 10_489_690_000L) < 1_500)
        assertEquals(20f, l[0].rapportDb, 0.1f)
    }
}
