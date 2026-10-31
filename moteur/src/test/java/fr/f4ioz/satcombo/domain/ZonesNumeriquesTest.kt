/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.ft.EnregistrementFt
import fr.f4ioz.satcombo.ft.EnregistrementFt.Emission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The digital zones of a recording: FT4 and FT8 told apart by their bursts' length and cadence, voices and hiss left out. */
class ZonesNumeriquesTest {

    /** 27/10/2026 16:29:31.400 UTC: a recording that does not start on a slot. */
    private val origine = 1_793_118_571_400L
    private fun tranche(n: Int, d: Long) = (origine / d + 1 + n) * d

    @Test
    fun des_salves_ft4_font_une_zone_ft4() {
        val e = (0 until 8).flatMap { k ->
            listOf(Emission("CQ F5LMG IN88", "FT4", tranche(2 * k, 7_500L), 1744.0, -6.0, 0.3),
                Emission("YO2CMI DH5YBE R-13", "FT4", tranche(2 * k + 1, 7_500L), 2080.0, -8.0, 0.8))
        }
        val a = EnregistrementFt.fabrique(origine, 130.0, 12_000, e, decalage = EnregistrementFt.dents(-20.0, 4.0))
        val z = ZonesNumeriques.cherche(a, 12_000, origine)
        assertEquals(z.toString(), 1, z.size)
        assertEquals("FT4", z[0].mode); assertEquals("FT4", z[0].libelle)
        assertTrue(z[0].salves >= 12)
        assertTrue(z[0].debutMs in tranche(0, 7_500L) - 1_000..tranche(0, 7_500L) + 1_500)
        assertTrue(z[0].basHz <= 1744.0 && z[0].hautHz >= 2080.0 + 60)
    }

    @Test
    fun des_salves_ft8_font_une_zone_ft8() {
        val e = (0 until 5).map { k -> Emission("CQ PD1BHZ JO22", "FT8", tranche(k, 15_000L), 1200.0, -8.0, 0.5, penteHzS = 1.0) }
        val z = ZonesNumeriques.cherche(EnregistrementFt.fabrique(origine, 100.0, 12_000, e), 12_000, origine)
        assertEquals(z.toString(), 1, z.size)
        assertEquals("FT8", z[0].mode)
    }

    @Test
    fun le_souffle_et_une_voix_ne_sont_pas_du_numerique() {
        val n = 12_000 * 90
        val r = java.util.Random(3)
        val a = FloatArray(n) { (r.nextGaussian() * 0.05).toFloat() }
        // A "voice": wideband, a few seconds on, at no cadence (2 to 6 s, gaps of 1 to 5 s).
        var t = 15.0
        while (t < 80.0) {
            val d = 2.0 + r.nextDouble() * 4.0
            for (i in (t * 12_000).toInt() until minOf(n, ((t + d) * 12_000).toInt())) a[i] += (r.nextGaussian() * 0.08).toFloat()
            t += d + 1.0 + r.nextDouble() * 4.0
        }
        assertEquals(emptyList<ZonesNumeriques.Zone>(), ZonesNumeriques.cherche(a, 12_000, origine))
    }

    @Test
    fun la_longueur_et_le_depart_disent_le_mode() {
        val t = tranche(0, 15_000L)
        assertEquals("FT4", ZonesNumeriques.genre(t + 400, t + 5_400))
        assertEquals("FT4", ZonesNumeriques.genre(t + 7_500 - 300, t + 7_500 + 4_800))   // a second early
        assertEquals("FT8", ZonesNumeriques.genre(t + 600, t + 13_200))
        assertEquals("NUM", ZonesNumeriques.genre(t + 3_700, t + 8_700))                 // off the slots
        assertEquals("NUM", ZonesNumeriques.genre(t + 500, t + 2_800))                   // too short
    }

    @Test
    fun les_zones_sont_gardees_a_cote_de_l_enregistrement() {
        val l = listOf(ZonesNumeriques.Zone(origine + 1_000, origine + 60_000, "FT4", 14, 700.0, 2350.0),
            ZonesNumeriques.Zone(origine + 90_000, origine + 120_000, "NUM", 3, 1500.0, 1600.0))
        assertEquals(l, ZonesNumeriques.lit(ZonesNumeriques.ecrit(l, origine), origine))
        assertEquals("numérique", l[1].libelle)
    }
}
