/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.location

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup position. A fresh fix that never comes (indoors, GPS only) used to
 * hold the whole startup, behind "Downloading orbital elements…".
 */
class AttenteFixTest {

    @Test
    fun un_point_frais_qui_ne_vient_pas_cede_au_dernier_connu() = runBlocking {
        val t0 = System.nanoTime()
        val r = LocationProvider.attendreFix({ awaitCancellation() }, { "dernier" }, "défaut",
            freshMs = 200, lastMs = 200)
        assertEquals("dernier", r)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test
    fun sans_aucun_point_on_prend_le_qth_par_defaut() = runBlocking {
        val r = LocationProvider.attendreFix<String>({ null }, { awaitCancellation() }, "défaut",
            freshMs = 200, lastMs = 200)
        assertEquals("défaut", r)
    }

    @Test
    fun un_point_frais_rapide_est_pris_tel_quel() = runBlocking {
        val r = LocationProvider.attendreFix({ delay(10); "frais" }, { "dernier" }, "défaut")
        assertEquals("frais", r)
    }
}
