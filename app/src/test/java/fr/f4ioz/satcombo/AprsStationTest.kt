/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import fr.f4ioz.satcombo.data.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The APRS station (settings, sender, numbers, path, position sent): what it gives, before and after its move. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AprsStationTest {
    private fun app(): Application = ApplicationProvider.getApplicationContext()
    private val s by lazy { SettingsStore(app()) }

    @Before
    fun pose() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app(), Configuration.Builder().build())
    }

    @After
    fun range() { s.aprsSsid = 0; s.aprsTravail = "AUTO"; s.aprsPositionFloue = false; s.callsign = "" }

    @Test
    fun l_indicatif_emis_porte_le_ssid_choisi() {
        s.callsign = " f4ioz "
        val vm = MainViewModel(app())
        assertEquals("F4IOZ", vm.aprs.source())
        vm.aprs.setSsid(9)
        assertEquals(9, vm.aprs.ssid())
        assertEquals("F4IOZ-9", vm.aprs.source())
    }

    @Test
    fun les_numeros_de_message_vont_de_1_a_999() {
        val vm = MainViewModel(app())
        s.aprsNumero = 998
        assertEquals("999", vm.aprs.numeroSuivant())
        assertEquals("1", vm.aprs.numeroSuivant())
        assertEquals("2", vm.aprs.numeroSuivant())
    }

    @Test
    fun le_chemin_suit_le_mode_de_travail() {
        val vm = MainViewModel(app())
        vm.aprs.setTravail("ISS")
        assertEquals(listOf("ARISS"), vm.aprs.cheminParDefaut())
        vm.aprs.setTravail("TERRE")
        assertEquals(listOf("WIDE1-1", "WIDE2-1"), vm.aprs.cheminParDefaut())
        assertEquals("TERRE", vm.aprs.travail())
    }

    @Test
    fun la_position_approchee_reste_a_moins_de_500_m_et_ne_bouge_pas() {
        val vm = MainViewModel(app())
        vm.pickLocator("JN18FT", 48.8, 2.45)
        val exacte = vm.aprs.positionEmise()
        assertNotNull(exacte)
        vm.aprs.setPositionFloue(true)
        assertTrue(vm.aprs.positionFloue())
        val a = vm.aprs.positionEmise()!!
        assertEquals(a, vm.aprs.positionEmise())
        // Metres north and east, flat at this scale.
        val n = (a.first - exacte!!.first) * 111_320.0
        val e = (a.second - exacte.second) * 111_320.0 * Math.cos(Math.toRadians(exacte.first))
        val d = Math.hypot(n, e)
        assertTrue("à $d m", d < 500.0 && d > 0.0)
        vm.aprs.nouveauFlou()
        assertNotEquals(a, vm.aprs.positionEmise())
        vm.aprs.setPositionFloue(false)
        assertEquals(exacte, vm.aprs.positionEmise())
    }
}
