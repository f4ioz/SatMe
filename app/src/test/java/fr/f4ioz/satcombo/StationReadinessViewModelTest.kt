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
import fr.f4ioz.satcombo.domain.StationReadiness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The readiness check wired to the real view model, and its promise never to act. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StationReadinessViewModelTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun poseWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app(), Configuration.Builder().build())
    }

    @Test
    fun le_controle_tourne_sur_une_installation_neuve() = runBlocking {
        val vm = MainViewModel(app())
        val l = vm.stationReadiness()
        assertEquals(StationReadiness.Domaine.entries.size, l.size)
        // Nothing chosen yet: no satellite is an error, the log has no callsign.
        assertEquals(StationReadiness.Niveau.ERROR, l.single { it.domaine == StationReadiness.Domaine.SAT }.niveau)
    }

    /** 04/10: with automatic recording under CAT on, SatMe no longer started (a collector ran before its fields). */
    @Test
    fun demarre_avec_l_enregistrement_auto_en_cat_actif() {
        fr.f4ioz.satcombo.data.SettingsStore(app()).enregAutoCat = true
        try {
            val vm = MainViewModel(app())
            org.junit.Assert.assertTrue(vm.enregAutoCat.actif())
            vm.enregAutoCat.verifie(force = true)
        } finally {
            fr.f4ioz.satcombo.data.SettingsStore(app()).enregAutoCat = false
        }
    }

    @Test
    fun le_profil_est_garde() {
        val vm = MainViewModel(app())
        val p = StationReadiness.Profil(catRequis = false, pointage = StationReadiness.Pointage.ROTOR,
            enregistrementRequis = false, synchroRequise = true)
        vm.setProfilStation(p)
        assertEquals(p, MainViewModel(app()).profilStation())
    }

    /**
     * The snapshot only reads. Its source must not name anything that keys a
     * transmitter, moves a mast or writes to a rig (the A0 audit's list).
     */
    @Test
    fun le_controle_n_appelle_rien_qui_agisse() {
        val src = File("src/main/java/fr/f4ioz/satcombo/MainViewModel.kt").readText()
        // The snapshot and the station check (Alpha 3), down to the automatic SSTV block.
        val debut = src.indexOf("private suspend fun instantaneReadiness(")
        val fin = src.indexOf("// ------------------------------------------------------- automatic SSTV", debut)
        assertTrue(debut > 0 && fin > debut)
        val corps = src.substring(debut, fin)
        assertTrue(corps.contains("suspend fun testeStation("))
        val interdits = listOf("setTransmit", "aprsEmet", "emet(", "aprsBaliseTic", "SortieAudio", "rotorGotoManual",
            "rotorJog", "rotorParkNow", "rotorStopNow", "setRotorEnabled", "connectRotor", "connectCat", "setCatEnabled",
            "catSendTestFreq", "armSo50", "thd72Frequence", "thd72Pas", "thd72Puissance", "setThd72BandeTx",
            "setFt817Role", "setIc705Baud", "aprsEcouteDemarre", "runCatBench", "toggleDopplerHold",
            "setFrequency", "setMode(", "deposeAuCarnet",
            // Since 20.79, out of the view model under these names.
            "thd72.frequence", "thd72.pas", "thd72.puissance", "thd72.setBandeTx", "aprs.emetKiss",
            "aprs.kissConnecte", "aprs.kissPasseEnKiss", "aprs.setTravail", "enregAutoCat.setActif")
        interdits.forEach { assertFalse("instantaneReadiness calls $it", corps.contains(it)) }
        // The view model's own recording start (not the 2-second AudioRecord of the audio check).
        assertFalse("the check starts a pass recording", Regex("""(?<![.\w])startRecording\(""").containsMatchIn(corps))
        // The domain is pure Kotlin: no Android, no view model.
        val domaine = File("src/main/java/fr/f4ioz/satcombo/domain/StationReadiness.kt").readText()
        assertFalse(domaine.contains("import android"))
        assertFalse(domaine.contains("MainViewModel"))
    }
}
