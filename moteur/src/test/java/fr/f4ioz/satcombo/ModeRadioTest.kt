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

    @Test
    fun la_fsk_lente_et_la_ssdv_en_usb_donnees() {
        // SO-127 (HADES-SA): « SSDV » 800 bd, « GENESIS FSK » 800 and 200 bd, « FSK » 800 bd.
        for ((m, b) in listOf("SSDV" to 800.0, "GENESIS FSK" to 800.0, "GENESIS FSK" to 200.0, "FSK" to 800.0, "GFSK" to 300.0)) {
            assertFalse("$m $b", ModeRadio.surFm(m, b))
            assertTrue("$m $b", ModeRadio.donnees(m, b))
            assertEquals("USB-D", ModeRadio.libelle(m, b))
            assertEquals("USB", normalizeMode(m, isUplink = false, invert = false, isTransponder = false, baud = b))
        }
        // FSK at 1200 bd and more, or of unknown rate: FM as before.
        for ((m, b) in listOf("FSK" to 9600.0, "GMSK" to 1200.0, "FSK" to null, "AFSK" to 300.0)) {
            assertTrue("$m $b", ModeRadio.surFm(m, b))
            assertFalse("$m $b", ModeRadio.donnees(m, b))
        }
    }

    @Test
    fun un_mode_tape_avec_donnees() {
        assertTrue(ModeRadio.donnees("USB-D"))
        assertFalse(ModeRadio.surFm("USB-D"))
        assertTrue(ModeRadio.surFm("FM-D"))
        assertEquals("FM-D", ModeRadio.libelle("FM-D"))
        assertEquals("LSB", ModeRadio.libelle("LSB"))
        assertEquals("CW", ModeRadio.libelle("CW"))
    }
}
