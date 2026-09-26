/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.ui.CoinsAim
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The compass's numeric corners. With a rotator connected they follow the
 * rotator, even with the satellite out of view. The trap is the label: "155°"
 * under `AZ` and under `AZ MÂT` mean different things, so tests check both.
 */
class CoinsAimTest {

    @Test
    fun sous_l_horizon_le_mat_remplace_les_tirets() {
        // Real case: ISS below the horizon, mast connected at 155°.
        val c = CoinsAim.coin(mat = 155.0, sat = null, satVisible = false,
            libelleMat = "AZ MÂT", libelleSat = "AZ")
        assertEquals("AZ MÂT", c.libelle)
        assertEquals("155°", c.valeur)
    }

    @Test
    fun sans_rotor_ni_satellite_il_reste_le_tiret() {
        val c = CoinsAim.coin(null, null, false, "AZ MÂT", "AZ")
        assertEquals("AZ", c.libelle)
        assertEquals(CoinsAim.RIEN, c.valeur)
    }

    @Test
    fun sans_rotor_le_satellite_visible_est_affiche_comme_avant() {
        val c = CoinsAim.coin(null, 212.7, true, "ÉL MÂT", "AZ")
        assertEquals("AZ", c.libelle)
        // The satellite keeps its original truncation: existing display
        // is not changed in passing.
        assertEquals("212°", c.valeur)
    }

    @Test
    fun la_position_lue_passe_devant_le_satellite_visible() {
        // Both exist: the mast describes the real world.
        val c = CoinsAim.coin(mat = 155.0, sat = 212.7, satVisible = true,
            libelleMat = "AZ MÂT", libelleSat = "AZ")
        assertEquals("AZ MÂT", c.libelle)
        assertEquals("155°", c.valeur)
    }

    @Test
    fun un_mat_muet_rend_la_main_au_satellite_et_le_libelle_le_dit() {
        // readPosition() silent: rotorAimAz goes back to null. The label
        // reverting to "AZ" is the only visible sign the source changed —
        // without it the mast would look stuck.
        val c = CoinsAim.coin(null, 212.0, true, "AZ MÂT", "AZ")
        assertEquals("AZ", c.libelle)
        assertEquals("212°", c.valeur)
    }

    @Test
    fun le_mat_est_arrondi_et_non_tronque() {
        // 155.6° shown as "155°" would suggest a pointing error that does not exist.
        assertEquals("156°", CoinsAim.coin(155.6, null, false, "M", "S").valeur)
        assertEquals("16°", CoinsAim.coin(15.5, null, false, "M", "S").valeur)
        assertEquals("0°", CoinsAim.coin(0.0, null, false, "M", "S").valeur)
    }

    @Test
    fun une_valeur_impossible_ne_s_affiche_pas() {
        // A NaN from a failed computation shows as a dash, not "NaN°".
        val c = CoinsAim.coin(Double.NaN, null, false, "AZ MÂT", "AZ")
        assertEquals("AZ", c.libelle)
        assertEquals(CoinsAim.RIEN, c.valeur)
    }

    @Test
    fun un_rotor_d_azimut_seul_laisse_l_elevation_au_satellite() {
        // rotorAimEl is null on an azimuth-only mast: the elevation corner
        // must not invent a zero.
        val c = CoinsAim.coin(null, 45.0, true, "ÉL MÂT", "ÉL")
        assertEquals("ÉL", c.libelle)
        assertEquals("45°", c.valeur)
    }
}
