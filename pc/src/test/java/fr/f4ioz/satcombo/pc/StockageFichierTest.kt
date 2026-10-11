/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StockageFichierTest {

    private val f = File(System.getProperty("java.io.tmpdir"), "satme-reglages-" + System.nanoTime() + ".properties")

    @Test
    fun chaque_valeur_se_relit_avec_son_type_apres_redemarrage() {
        StockageFichier(f).edit().putString("s", "JN18FS").putInt("i", 12).putLong("l", 1L shl 40)
            .putFloat("f", 1.5f).putBoolean("b", true).putStringSet("e", setOf("a", "b c")).apply()
        val relu = StockageFichier(f)
        assertEquals("JN18FS", relu.getString("s", null))
        assertEquals(12, relu.getInt("i", 0))
        assertEquals(1L shl 40, relu.getLong("l", 0))
        assertEquals(1.5f, relu.getFloat("f", 0f))
        assertTrue(relu.getBoolean("b", false))
        assertEquals(setOf("a", "b c"), relu.getStringSet("e", null))
        // Another type than the one written: the default, never a wrong value.
        assertEquals(7, relu.getInt("s", 7))
        relu.edit().remove("s").apply()
        assertNull(StockageFichier(f).getString("s", null))
        f.delete()
    }

    @Test
    fun les_reglages_du_telephone_s_y_lisent_et_s_y_ecrivent() {
        val s = SettingsStore(StockageFichier(f))
        s.manualLocator = "jn03ab"
        s.callsign = "F4IOZ"
        val relu = SettingsStore(StockageFichier(f))
        assertEquals("JN03AB", relu.manualLocator)
        assertEquals("F4IOZ", relu.callsign)
        f.delete()
    }
}
