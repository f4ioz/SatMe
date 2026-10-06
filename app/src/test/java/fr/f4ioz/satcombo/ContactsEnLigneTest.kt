/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.AdifImport
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsEnLigneTest {
    @Test
    fun les_contacts_satellite_d_un_carnet_en_ligne_se_lisent_en_entier() {
        val adif = """Wavelog export<EOH>
<CALL:6>EA4XYZ<QSO_DATE:8>20261005<TIME_ON:6>112528<SAT_NAME:5>SO-50<PROP_MODE:3>SAT<GRIDSQUARE:4>IN80<MY_GRIDSQUARE:6>JN18FT<MODE:2>FM<FREQ:7>145.850<FREQ_RX:7>436.795<RST_SENT:2>59<RST_RCVD:2>57<NAME:4>Juan<EOR>
<CALL:5>F5ABC<QSO_DATE:8>20261005<TIME_ON:4>1130<MODE:3>SSB<BAND:3>20m<EOR>
<CALL:5>F4AAA<QSO_DATE:8>20261006<TIME_ON:6>080000<PROP_MODE:3>SAT<SAT_NAME:5>RS-44<MODE:3>SSB<SUBMODE:3>USB<EOR>
"""
        val l = AdifImport.contactsEnLigne(adif)
        assertEquals(2, l.size)
        val a = l[0]
        assertEquals("EA4XYZ", a.indicatif); assertEquals("SO-50", a.satellite); assertEquals("IN80", a.locator)
        assertEquals("JN18FT", a.monLocator); assertEquals("FM", a.mode)
        assertEquals(145.850, a.freqMhz, 1e-6); assertEquals(436.795, a.freqRxMhz, 1e-6)
        assertEquals("59", a.rstEnvoye); assertEquals("57", a.rstRecu); assertEquals("Juan", a.nom)
        assertEquals(1791199528000L, a.quandMs)
        assertEquals("USB", l[1].mode)
    }
}
