/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.normalizeMode
import fr.f4ioz.satcombo.domain.ModeRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ISS APRS transponder ("AFSK") put the IC-9700 in USB: digital modes heard in FM. */
class ModeRadioTest {

    @Test
    fun les_modes_entendus_en_fm() {
        for (m in listOf("FM", "NFM", "AFSK", "AFSK S-Net", "APRS", "FSK", "GFSK", "GMSK", "MSK", "SSTV", "DSTAR"))
            assertTrue(m, ModeRadio.surFm(m))
    }

    @Test
    fun les_modes_qui_restent_en_bande_laterale() {
        for (m in listOf("USB", "LSB", "CW", "BPSK", "PSK", "MFSK", "FT8", "FT4", "", null))
            assertFalse(m ?: "null", ModeRadio.surFm(m))
    }

    @Test
    fun le_transpondeur_aprs_de_l_iss_en_fm() {
        assertEquals("FM", normalizeMode("AFSK", isUplink = false, invert = false, isTransponder = false))
        assertEquals("USB", normalizeMode("BPSK", isUplink = false, invert = false, isTransponder = false))
    }
}
