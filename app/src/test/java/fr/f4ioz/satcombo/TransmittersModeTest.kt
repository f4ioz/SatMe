/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Transmitter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SatNOGS mode only means something for non-transponders: a linear
 * transponder has no mode, and the field holds whatever was typed (QO-100 SSB
 * segments say "FM") — a wrong instruction if shown.
 */
class TransmittersModeTest {

    private fun emetteur(
        bas: Long?, haut: Long?, mode: String?,
    ) = Transmitter(
        description = "x", mode = mode,
        uplinkLowHz = null, uplinkHighHz = null,
        downlinkLowHz = bas, downlinkHighHz = haut,
        invert = false, alive = true, type = "Transponder")

    @Test
    fun une_balise_garde_son_mode() {
        val b = emetteur(10_489_745_000L, 10_489_745_000L, "BPSK")
        assertFalse(b.isTransponder)
        assertTrue(b.modeSignifiant)
    }

    @Test
    fun un_transpondeur_ne_montre_pas_le_sien() {
        val t = emetteur(10_489_650_000L, 10_489_750_000L, "FM")
        assertTrue(t.isTransponder)
        assertFalse(t.modeSignifiant)
    }

    /** Without a downlink range we cannot tell: keep what we have. */
    @Test
    fun sans_plage_le_mode_reste_affiche() {
        assertTrue(emetteur(null, null, "CW").modeSignifiant)
        assertTrue(emetteur(145_950_000L, null, "CW").modeSignifiant)
    }
}
