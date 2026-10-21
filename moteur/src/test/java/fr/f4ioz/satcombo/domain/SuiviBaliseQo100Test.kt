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
        // Half a minute of measures before anything is written; then 80 % of the 100 Hz they agree on.
        for (k in 0 until 5) assertNull(s.mesure(k.toLong(), m(100.0, 20f), 1_000, corrige = true))
        assertEquals(1_080L, s.mesure(5, m(100.0, 20f), 1_000, corrige = true))
        assertNull(s.mesure(6, m(15.0, 20f), 1_100, corrige = true))              // close enough
        assertNull(s.mesure(2, m(300.0, 6f), 1_100, corrige = true))              // too weak
        assertNull(s.mesure(3, m(5.0, 20f), 1_100, corrige = false))              // measured, not written
        // Another line 20 kHz away (a strong station): thrown away, not even recorded.
        assertNull(s.mesure(4, m(19_700.0, 25f), 1_100, corrige = true))
        assertEquals(1, s.ecartees)
        assertTrue(s.verrouille(10_000))
    }

    @Test fun `une balise qui saute entre deux lobes ne fait pas sauter l'accord`() {
        // The night of 14 to 15 October: measures -5 500 and -6 200 Hz in turn, the true error between.
        val s = SuiviBaliseQo100()
        var calage = -5_850L
        val ecrits = ArrayList<Long>()
        for (k in 0 until 60) {
            val erreur = if (k % 2 == 0) -5_500.0 else -6_200.0
            s.mesure(k * 5_000L, m(erreur - calage, 20f), calage, corrige = true)?.let { calage = it; ecrits += it }
        }
        // The mean sits between the lobes: a few small touches at first, never a 700 Hz swing.
        assertTrue("$ecrits", ecrits.size <= 6 && ecrits.all { kotlin.math.abs(it + 5_850) <= 60 })
    }

    @Test fun `le centre de la balise PSK, ni l'un ni l'autre lobe`() {
        // A spectrum 1 Hz a bin, two lobes at -350 and +350 Hz of 10 489,750, the left one a little higher.
        val n = 20_000
        val centre = Qo100.BALISE_MEDIANE_HZ.toDouble()
        val mag = FloatArray(n) { -60f }
        for (k in -150..150) { mag[n / 2 - 350 + k] = maxOf(mag[n / 2 - 350 + k], -20f - kotlin.math.abs(k) / 30f + 1f) }
        for (k in -150..150) { mag[n / 2 + 350 + k] = maxOf(mag[n / 2 + 350 + k], -20f - kotlin.math.abs(k) / 30f) }
        val pic = MesureBalise.mesurer(mag, centre, n.toDouble(), fenetreHz = 3_000.0)!!
        val c = MesureBalise.mesurerCentre(mag, centre, n.toDouble(), fenetreHz = 3_000.0)!!
        assertTrue("pic ${pic.ecartHz}", kotlin.math.abs(pic.ecartHz + 350) < 20)
        // A lobe 1 dB stronger pulls the centre a little (tens of hertz), never to the lobe (350 Hz).
        assertTrue("centre ${c.ecartHz}", kotlin.math.abs(c.ecartHz) < 100)
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

    @Test fun `la journée garde aussi le niveau de la balise, minute par minute`() {
        val s = SuiviBaliseQo100()
        for (k in 0..6) s.mesure(k * 20_000L, m(100.0, 20f + k), 1_000, corrige = false)
        // Three minutes started: the first two finished, each its median level beside its error.
        val n = s.journeeNiveau()
        assertEquals(listOf(0L, 60_000L), n.map { it.first })
        assertEquals(21.0, n[0].second, 1e-6); assertEquals(24.0, n[1].second, 1e-6)
        assertEquals(s.journee().map { it.first }, n.map { it.first })
    }

    @Test fun `une tete qui derive de 4 Hz par seconde est suivie de pres`() {
        // 21 October: a new LNB walking 240 Hz a minute; a measure a second, ±40 Hz of scatter.
        val s = SuiviBaliseQo100()
        val r = java.util.Random(7)
        var calage = 0L
        val ecarts = ArrayList<Double>()
        for (k in 0 until 120) {
            val t = k * 1_000L
            val vraie = -600.0 - 4.0 * k
            s.mesure(t, m(vraie - calage + r.nextGaussian() * 40, 20f), calage, corrige = true)?.let { calage = it }
            if (k >= 40) ecarts += Math.abs(vraie - calage)
        }
        // Behind by little once the drift is known (a two-minute mean lagged ~240 Hz).
        assertTrue("retard moyen ${ecarts.average()}", ecarts.average() < 40.0)
    }
}
