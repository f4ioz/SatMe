/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.EnregistrementAuto
import fr.f4ioz.satcombo.domain.EnregistrementAuto.Audio
import org.junit.Assert.assertEquals
import org.junit.Test

class EnregistrementAutoTest {

    private val t0 = 1_759_500_000_000L

    @Test
    fun cinq_secondes_avant_et_apres_chaque_passage_a_venir() {
        val p = listOf(t0 + 600_000L to t0 + 1_200_000L, t0 - 900_000L to t0 - 300_000L, t0 + 6_000_000L to t0 + 6_500_000L)
        val f = EnregistrementAuto.fenetres(p, t0)
        assertEquals(listOf((t0 + 595_000L) to (t0 + 1_205_000L), (t0 + 5_995_000L) to (t0 + 6_505_000L)), f)
        // The pass in progress is kept, from where it is.
        assertEquals(1, EnregistrementAuto.fenetres(listOf(t0 - 60_000L to t0 + 60_000L), t0).size)
        // Overlapping windows become one; never more than MAX.
        assertEquals(1, EnregistrementAuto.fenetres(listOf(t0 to t0 + 600_000L, t0 + 598_000L to t0 + 900_000L), t0 - 1).size)
        val beaucoup = (0 until 40).map { (t0 + it * 6_000_000L) to (t0 + it * 6_000_000L + 600_000L) }
        assertEquals(EnregistrementAuto.MAX, EnregistrementAuto.fenetres(beaucoup, t0).size)
    }

    @Test
    fun l_audio_du_poste_est_juge() {
        assertEquals(Audio.OK, EnregistrementAuto.juge(-32.0, -12.0))
        assertEquals(Audio.SILENCE, EnregistrementAuto.juge(-75.0, -60.0))
        assertEquals(Audio.SATURE, EnregistrementAuto.juge(-8.0, -0.2))
        assertEquals(Audio.ABSENTE, EnregistrementAuto.juge(null, null))
    }
}
