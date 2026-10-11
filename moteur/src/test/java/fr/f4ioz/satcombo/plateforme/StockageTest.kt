/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.plateforme

import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.SourcesStore
import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.i18n.Lang
import fr.f4ioz.satcombo.i18n.t
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings without Android: what a PC station will start from. */
class StockageTest {

    @Test
    fun le_stockage_en_memoire_rend_ce_qu_on_y_met() {
        val s = StockageMemoire()
        assertEquals("x", s.getString("a", "x"))
        s.edit().putString("a", "b").putInt("n", 3).putBoolean("v", true).putFloat("f", 1.5f)
            .putLong("l", 7L).putStringSet("e", setOf("1", "2")).apply()
        assertEquals("b", s.getString("a", null))
        assertEquals(3, s.getInt("n", 0))
        assertTrue(s.getBoolean("v", false))
        assertEquals(1.5f, s.getFloat("f", 0f), 0f)
        assertEquals(7L, s.getLong("l", 0L))
        assertEquals(setOf("1", "2"), s.getStringSet("e", null))
        s.edit().remove("a").apply()
        assertNull(s.getString("a", null))
    }

    @Test
    fun rien_n_est_ecrit_avant_apply() {
        val s = StockageMemoire()
        val e = s.edit().putInt("n", 3)
        assertEquals(0, s.getInt("n", 0))
        e.apply()
        assertEquals(3, s.getInt("n", 0))
    }

    @Test
    fun les_reglages_tournent_sans_android() {
        val r = SettingsStore(StockageMemoire())
        // Defaults as on a new phone.
        assertEquals(115_200, r.civBaud)
        assertEquals(4, r.rotorDeadband)
        r.callsign = "F4IOZ"; r.civBaud = 19_200; r.notifiedPassKeys = setOf("25544@1")
        assertEquals("F4IOZ", r.callsign)
        assertEquals(19_200, r.civBaud)
        assertEquals(setOf("25544@1"), r.notifiedPassKeys)
        // Read back by another instance on the same storage, as after a restart.
        val s = StockageMemoire()
        SettingsStore(s).callsign = "F4IOZ"
        assertEquals("F4IOZ", SettingsStore(s).callsign)
    }

    @Test
    fun les_sources_choisies_aussi() {
        val st = SourcesStore(StockageMemoire())
        assertEquals(Sources.DEFAULT_IDS, st.load())
        st.save(setOf("amsat_gp"))
        assertEquals(setOf("amsat_gp"), st.load())
    }

    @Test
    fun la_langue_previent_a_chaque_lecture_et_a_chaque_changement() {
        var lectures = 0; var changements = 0
        val avantL = I18n.surLecture; val avantC = I18n.surChangement
        try {
            I18n.surLecture = { lectures++ }; I18n.surChangement = { changements++ }
            I18n.apply("en", "fr")
            assertEquals(Lang.EN, I18n.current())
            t("rig")
            assertEquals(1, changements)
            assertTrue("lectures $lectures", lectures >= 2)
        } finally {
            I18n.surLecture = avantL; I18n.surChangement = avantC
            I18n.apply("fr", "fr")
        }
    }
}
