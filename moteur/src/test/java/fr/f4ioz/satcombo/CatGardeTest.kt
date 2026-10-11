/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.CatGarde
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatGardeTest {
    private val t0 = 1_800_000_000_000L

    @Test
    fun un_poste_qui_n_entend_plus_est_rouvert_une_fois_par_minute() {
        // Just opened: left alone, even silent.
        assertFalse(CatGarde.relancer(t0 + 5_000, t0, t0, 0L))
        // Answering: left alone.
        assertFalse(CatGarde.relancer(t0 + 60_000, t0, t0 + 59_000, 0L))
        // Silent 15 s, opened long ago: reopened.
        assertTrue(CatGarde.relancer(t0 + 60_000, t0, t0 + 45_000, 0L))
        // Reopened less than a minute ago: not again yet.
        assertFalse(CatGarde.relancer(t0 + 90_000, t0, t0 + 45_000, t0 + 60_000))
        // Never opened: nothing.
        assertFalse(CatGarde.relancer(t0, 0L, 0L, 0L))
    }
}
