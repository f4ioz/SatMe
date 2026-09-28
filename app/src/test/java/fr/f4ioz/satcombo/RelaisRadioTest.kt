/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.RelaisRadio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SatMe as a radio in Wavelog: what is sent, and when. Robolectric for a real org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelaisRadioTest {

    private fun so50(up: Long = 145_850_000, down: Long = 436_795_000) =
        RelaisRadio.Etat("SO-50", up, down, "FM", "FM")

    @Test
    fun envoye_au_debut_puis_seulement_sur_changement() {
        val r = RelaisRadio()
        assertTrue(r.aEnvoyer(so50(), 0))
        r.envoye(so50(), 0)
        // Doppler moves a few hundred hertz: not worth a message.
        assertFalse(r.aEnvoyer(so50(down = 436_795_400), 10_000))
        // Another satellite: at once, even a second later.
        assertTrue(r.aEnvoyer(RelaisRadio.Etat("AO-91", 435_250_000, 145_960_000, "FM", "FM"), 1_000))
    }

    @Test
    fun une_frequence_qui_a_bouge_part_au_plus_toutes_les_5_s() {
        val r = RelaisRadio()
        r.envoye(so50(), 0)
        val loin = so50(down = 436_797_000)
        assertFalse("trop tôt", r.aEnvoyer(loin, 4_000))
        assertTrue(r.aEnvoyer(loin, 5_000))
    }

    @Test
    fun rien_ne_bouge_un_rappel_toutes_les_2_minutes() {
        val r = RelaisRadio()
        r.envoye(so50(), 0)
        assertFalse(r.aEnvoyer(so50(), 119_000))
        assertTrue(r.aEnvoyer(so50(), 120_000))
    }

    @Test
    fun les_bandes_font_le_mode_satellite() {
        assertEquals("V/U", so50().modeSat)
        assertEquals("U/V", RelaisRadio.Etat("AO-91", 435_250_000, 145_960_000, "FM", "FM").modeSat)
        assertEquals("L/U", RelaisRadio.Etat("AO-92", 1_267_350_000, 435_350_000, "FM", "FM").modeSat)
        assertEquals("S/X", RelaisRadio.Etat("QO-100", 2_400_250_000, 10_489_750_000, "USB", "USB").modeSat)
    }

    @Test
    fun le_message_suit_l_api_radio() {
        val j = JSONObject(RelaisRadio.json("cle", "SatMe",
            RelaisRadio.Etat("RS-44", 145_966_000, 435_644_000, "LSB", "USB")))
        assertEquals("cle", j.getString("key"))
        assertEquals("SatMe", j.getString("radio"))
        assertEquals("SAT", j.getString("prop_mode"))
        assertEquals("RS-44", j.getString("sat_name"))
        assertEquals("V/U", j.getString("sat_mode"))
        // "frequency"/"mode" are the transmitting side.
        assertEquals(145_966_000L, j.getLong("frequency"))
        assertEquals("LSB", j.getString("mode"))
        assertEquals(435_644_000L, j.getLong("frequency_rx"))
        assertEquals(145_966_000L, j.getLong("uplink_freq"))
        assertEquals(435_644_000L, j.getLong("downlink_freq"))
        assertEquals("LSB", j.getString("uplink_mode"))
        assertEquals("USB", j.getString("downlink_mode"))
    }
}
