/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.MaterielRx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-device frequency offsets.
 *
 * **Nothing moves until something has been measured.** That was the condition
 * (no side effect on LEO satellites), and it is checked here rather than in
 * the field.
 */
class MaterielRxTest {

    @Test
    fun sans_mesure_rien_ne_bouge() {
        MaterielRx.parDefaut().forEach { m ->
            assertEquals(0.0, m.ppm, 0.0)
            assertEquals(145_950_000L, MaterielRx.corrige(145_950_000L, m.ppm))
        }
    }

    @Test
    fun le_premier_poste_est_la_reference() {
        val ref = MaterielRx.parDefaut().filter { it.reference }
        assertEquals(1, ref.size)
        assertEquals("FT-817 A", ref.first().nom)
    }

    /**
     * The error is proportional: three times the frequency, three times the
     * offset. That is why we store ppm, not hertz.
     */
    @Test
    fun l_ecart_suit_la_frequence() {
        val a = MaterielRx.corrige(144_000_000L, 2.0) - 144_000_000L
        val b = MaterielRx.corrige(432_000_000L, 2.0) - 432_000_000L
        assertEquals(288L, a)
        assertEquals(864L, b)
        assertEquals(3.0, b.toDouble() / a, 0.01)
    }

    @Test
    fun corriger_puis_redresser_revient_au_point_de_depart() {
        listOf(-40.0, -2.5, 0.0, 1.7, 25.0).forEach { ppm ->
            val f = 144_790_000L
            val revenu = MaterielRx.redresse(MaterielRx.corrige(f, ppm), ppm)
            assertTrue("ppm=$ppm : $revenu", kotlin.math.abs(revenu - f) <= 1L)
        }
    }

    @Test
    fun une_mesure_donne_l_ecart() {
        // 144.790 MHz expected, 144.793 read: about +20.7 ppm.
        val ppm = MaterielRx.ppmDepuisMesure(144_790_000L, 144_793_000L)
        assertTrue(ppm != null && kotlin.math.abs(ppm - 20.72) < 0.05)
    }

    /**
     * 100 ppm is 14 kHz at 144 MHz: beyond that it is a typo, not a crystal,
     * and storing it would shift everything.
     */
    @Test
    fun une_mesure_absurde_est_refusee() {
        assertNull(MaterielRx.ppmDepuisMesure(144_790_000L, 154_790_000L))
        assertNull(MaterielRx.ppmDepuisMesure(0L, 144_790_000L))
        assertNull(MaterielRx.ppmDepuisMesure(144_790_000L, 0L))
    }

    @Test
    fun un_ecart_incroyable_ne_corrige_rien() {
        assertEquals(144_000_000L, MaterielRx.corrige(144_000_000L, 5_000.0))
        assertEquals(144_000_000L, MaterielRx.corrige(144_000_000L, Double.NaN))
    }

    /** The reference stays at zero: it is the fixed point. */
    @Test
    fun la_reference_ne_se_regle_pas() {
        val apres = MaterielRx.range(MaterielRx.parDefaut(), "FT-817 A", 12.0)
        assertEquals(0.0, MaterielRx.choisi(apres, "FT-817 A").ppm, 0.0)
    }

    @Test
    fun un_autre_appareil_se_regle() {
        val apres = MaterielRx.range(MaterielRx.parDefaut(), "Clé SDR 1", 25.0)
        assertEquals(25.0, MaterielRx.choisi(apres, "Clé SDR 1").ppm, 0.0)
        // The others have not moved.
        assertEquals(0.0, MaterielRx.choisi(apres, "Clé SDR 2").ppm, 0.0)
    }

    @Test
    fun un_nom_inconnu_retombe_sur_la_reference() {
        assertTrue(MaterielRx.choisi(MaterielRx.parDefaut(), "inexistant").reference)
    }

    @Test
    fun la_liste_se_range_et_se_relit() {
        val liste = MaterielRx.range(MaterielRx.parDefaut(), "Clé SDR 1", -18.5)
        val relue = MaterielRx.lit(MaterielRx.ecrit(liste))
        assertEquals(liste, relue)
    }

    /** No list gives the default list, never an empty one. */
    @Test
    fun une_liste_illisible_rend_celle_par_defaut() {
        assertEquals(MaterielRx.parDefaut(), MaterielRx.lit("n'importe quoi"))
        assertEquals(MaterielRx.parDefaut(), MaterielRx.lit("[]"))
    }

    /** A corrupted stored value must not shift frequencies. */
    @Test
    fun une_valeur_rangee_absurde_est_ramenee_a_zero() {
        val relue = MaterielRx.lit("Clé SDR 1|99999.0|false")
        assertEquals(0.0, relue.first().ppm, 0.0)
    }
}
