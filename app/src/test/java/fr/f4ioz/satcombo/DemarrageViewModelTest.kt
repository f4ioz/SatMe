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
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Startup, on an in-memory Android (Robolectric). Domain tests check pure
 * rules but **none built the app** — where things get wired together, and
 * so where they come apart. Several regressions shipped that way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemarrageViewModelTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun prefs() = app()
        .getSharedPreferences("satcombo_settings", Context.MODE_PRIVATE)

    /**
     * The ViewModel schedules TLE refresh on construction. On device
     * WorkManager initialises from the manifest; here it must be set up by
     * hand or construction throws.
     */
    @Before
    fun poseWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app(), Configuration.Builder().build())
    }

    @Test
    fun le_viewmodel_demarre() {
        assertNotNull(MainViewModel(app()).ui.value)
    }

    // ------------------------------------------------ keypad memory

    /**
     * **Keypad memory regression.** It was built by `rafraichitFile()`, a queue
     * function; removing the queue emptied the keypad. A full log must give a
     * full memory **at startup**, before any screen is opened.
     */
    @Test
    fun le_carnet_local_peuple_la_memoire_des_le_demarrage() {
        LogStore(app()).add(LogEntry(
            timeMs = 1_700_000_000_000L, satName = "RS-44", catnum = 44909,
            azimuthDeg = 120.0, elevationDeg = 30.0,
            callsign = "F1FPL", theirLocator = "JN09LE"))

        val memoire = MainViewModel(app()).ui.value.express.memoire

        assertTrue("le clavier ouvre un carnet vide", memoire.isNotEmpty())
        assertTrue(memoire.any { it.indicatif == "F1FPL" })
    }

    /** The known grid square comes with the callsign: that is the point. */
    @Test
    fun la_memoire_porte_le_carre_du_correspondant() {
        LogStore(app()).add(LogEntry(
            timeMs = 1_700_000_000_000L, satName = "RS-44", catnum = 44909,
            azimuthDeg = 0.0, elevationDeg = 10.0,
            callsign = "F5RRO", theirLocator = "IN77US"))

        val connu = MainViewModel(app()).ui.value.express.memoire
            .first { it.indicatif == "F5RRO" }

        assertEquals("IN77US", connu.locatorPrincipal)
    }

    // ------------------------------------------------ gesture migration

    /**
     * An existing install set to three taps moves to two: changing a default
     * does not touch stored values, so it must be rewritten once.
     */
    @Test
    fun une_installation_existante_passe_au_double_appui() {
        prefs().edit().putInt("log_taps", 3).remove("reprises").commit()

        assertEquals(2, MainViewModel(app()).ui.value.logTaps)
    }

    /**
     * **Only once.** Replayed on every start, the migration would overwrite the
     * operator's choice and make the setting impossible to change — worse
     * than the bug being fixed.
     */
    @Test
    fun la_reprise_ne_rejoue_pas_sur_le_choix_de_l_operateur() {
        prefs().edit().putInt("log_taps", 3).remove("reprises").commit()
        MainViewModel(app())                            // migration runs: 2

        prefs().edit().putInt("log_taps", 3).commit()   // operator picks 3 again

        assertEquals(3, MainViewModel(app()).ui.value.logTaps)
    }

    /** A new install starts at two taps. */
    @Test
    fun une_installation_neuve_demarre_au_double_appui() {
        prefs().edit().clear().commit()

        assertEquals(2, MainViewModel(app()).ui.value.logTaps)
    }

    // ------------------------------------------- compass gesture

    /**
     * **The gesture no longer writes anything.** It used to log entries with no
     * callsign, which reached the ADIF export. A contact without a callsign is
     * not half a contact: it is a callsign known a moment ago and lost.
     */
    @Test
    fun le_geste_ouvre_le_clavier_sans_rien_inscrire() {
        val vm = MainViewModel(app())
        val avant = vm.ui.value.log.size

        vm.ouvreSaisie()

        assertEquals(Screen.NOMMAGE, vm.ui.value.screen)
        assertEquals("le geste a écrit au carnet", avant, vm.ui.value.log.size)
        assertEquals(0, LogStore(app()).load().size)
    }

    // ------------------------------------------ logs from older versions

    /**
     * Older logs still carry the removed `aNommer` flag (`"an"`). The whole log
     * must read back; those entries return without a callsign, fixable in the
     * log editor.
     */
    @Test
    fun un_journal_d_avant_la_suppression_se_relit_entier() {
        app().filesDir.resolve("qso_log.json").writeText(
            """[{"t":2,"s":"RS-44","c":44909,"az":30.0,"el":5.0,"cs":"F1FPL","an":false},
                {"t":1,"s":"RS-44","c":44909,"az":31.0,"el":4.0,"cs":"","an":true}]""")

        val journal = LogStore(app()).load()

        assertEquals(2, journal.size)
        assertEquals("F1FPL", journal.first { it.timeMs == 2L }.callsign)
        assertEquals("", journal.first { it.timeMs == 1L }.callsign)
        // The old placeholder does not go to ADIF: it has no callsign.
        assertEquals(1, LogStore(app()).toAdif().split("<EOR>").size - 1)
    }

    // ------------------------------------------------ ADIF export

    /** An entry without a callsign is not a contact and is not exported. */
    @Test
    fun l_export_adif_laisse_les_entrees_sans_indicatif() {
        val journal = LogStore(app())
        journal.add(LogEntry(1L, "RS-44", 44909, 30.0, 5.0))          // anonymous
        journal.add(LogEntry(2L, "RS-44", 44909, 30.0, 5.0, callsign = "F1FPL"))

        val adif = journal.toAdif("F4IOZ")

        assertEquals(1, adif.split("<EOR>").size - 1)
        assertTrue(adif.contains("F1FPL"))
    }
}
