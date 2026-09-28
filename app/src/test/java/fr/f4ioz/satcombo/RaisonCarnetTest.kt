/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.CarnetEnLigne
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Why Wavelog refused a contact. Its 400 answer echoes the whole ADIF before
 * the reason; showing the raw start of it (or a bare "HTTP 400") hid why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaisonCarnetTest {

    // Shape of Wavelog's answer (Api.php, qso()): status, type, string, counts, messages.
    private fun refus(message: String) = "HTTP 400 " +
        """{"status":"abort","type":"adif","string":"<CALL:5>F4XYZ<SAT_NAME:5>AO-73<EOR>",""" +
        """"adif_count":1,"adif_errors":1,"messages":["","$message"]}"""

    @Test
    fun la_raison_de_wavelog_et_non_l_adif_renvoye() {
        val r = refus("Wrong station callsign <b>F4IOZ</b> while importing QSO with F4XYZ")
        assertEquals(CarnetEnLigne.Issue.REFUS, CarnetEnLigne.issue(r))
        val p = CarnetEnLigne.raison(r)
        assertTrue(p, p.startsWith("HTTP 400 : Wrong station callsign"))
        assertFalse(p, p.contains("<CALL"))
    }

    @Test
    fun un_doublon_est_deja_dans_wavelog() {
        assertTrue(CarnetEnLigne.doublon(refus("Duplicate for F4XYZ on 2026-09-28 17:32")))
        assertFalse(CarnetEnLigne.doublon(refus("Wrong station callsign F4IOZ")))
    }

    @Test
    fun une_page_html_se_lit_sans_ses_balises() {
        assertEquals("HTTP 500 : Internal error",
            CarnetEnLigne.raison("HTTP 500 <html><body><h1>Internal error</h1></body></html>"))
    }
}
