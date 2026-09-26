/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Le banc du démarrage, sur un vrai Android en mémoire.
 *
 * Il existe à cause des régressions livrées d'affilée que rien ne pouvait
 * attraper : le carnet du clavier vidé en 19.11, et avant lui des écrans dont
 * l'état se reconstruisait de travers. Les 768 essais de domaine vérifient des
 * règles pures ; **aucun ne construisait l'application**. Or c'est là que les
 * choses se branchent les unes aux autres, et donc là qu'elles se débranchent.
 *
 * Robolectric fournit préférences, ressources et manifeste sur la JVM. Un essai
 * ici coûte quelques secondes au lieu d'un appareil et d'un passage de
 * satellite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemarrageViewModelTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun prefs() = app()
        .getSharedPreferences("satcombo_settings", Context.MODE_PRIVATE)

    /**
     * Le ViewModel programme le rafraîchissement des TLE dès sa construction.
     * Sur l'appareil, WorkManager s'initialise depuis le manifeste ; ici il
     * faut le poser à la main, sinon la construction lève.
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

    // ------------------------------------------------ la mémoire du clavier

    /**
     * **La régression du 25 août.**
     *
     * La mémoire du clavier était construite par `rafraichitFile()`, fonction
     * de la file d'attente sur laquelle la construction du carnet avait été
     * greffée. La file supprimée, la mémoire est partie avec — et le clavier
     * s'ouvrait sur un carnet vide après chaque mise à jour, jusqu'à ce qu'on
     * aille éteindre puis rallumer la base interne dans les réglages.
     *
     * Un carnet plein doit donner une mémoire pleine, **au démarrage**, sans
     * qu'aucun écran n'ait été ouvert.
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

    /** Le carré connu remonte avec l'indicatif : c'est tout l'intérêt. */
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

    // ------------------------------------------------ la reprise du geste

    /**
     * Une installation existante réglée sur trois appuis passe à deux.
     *
     * Changer un défaut ne touche que les installations neuves : la valeur
     * déjà écrite est relue telle quelle. Il faut donc la réécrire une fois.
     */
    @Test
    fun une_installation_existante_passe_au_double_appui() {
        prefs().edit().putInt("log_taps", 3).remove("reprises").commit()

        assertEquals(2, MainViewModel(app()).ui.value.logTaps)
    }

    /**
     * **Et une seule fois.** Rejouée à chaque démarrage, la reprise écraserait
     * le choix que l'opérateur vient de faire, et le réglage deviendrait
     * impossible à changer — un défaut pire que celui qu'on corrige.
     */
    @Test
    fun la_reprise_ne_rejoue_pas_sur_le_choix_de_l_operateur() {
        prefs().edit().putInt("log_taps", 3).remove("reprises").commit()
        MainViewModel(app())                            // la reprise passe : 2

        prefs().edit().putInt("log_taps", 3).commit()   // l'opérateur reprend 3

        assertEquals(3, MainViewModel(app()).ui.value.logTaps)
    }

    /** Une installation neuve démarre à deux appuis. */
    @Test
    fun une_installation_neuve_demarre_au_double_appui() {
        prefs().edit().clear().commit()

        assertEquals(2, MainViewModel(app()).ui.value.logTaps)
    }

    // ------------------------------------------- le geste sur la boussole

    /**
     * **Le geste n'écrit plus rien.**
     *
     * Il posait au carnet une entrée complète — heure, satellite, azimut,
     * élévation — et vide de nom. Quatre le 25 août, parties jusque dans
     * l'export ADIF. Un contact sans indicatif n'est pas un contact à moitié
     * fait : c'est un indicatif qu'on connaissait à l'instant même et qu'on a
     * perdu.
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

    // ------------------------------------------ les journaux d'avant la 19.11

    /**
     * Les tampons déjà en mémoire ne sont pas perdus.
     *
     * Le drapeau `aNommer` a disparu de `LogEntry` en 19.11 ; les journaux
     * écrits avant portent encore sa clé `"an"`. Un appareil qui met à jour
     * doit relire son carnet entier — l'appareil d'Olivier en comptait douze
     * marqués, plus treize contacts. Les entrées reviennent simplement sans
     * nom, et l'éditeur du journal sait les corriger.
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
        // Et le tampon d'hier ne part pas à l'ADIF : il n'a pas d'indicatif.
        assertEquals(1, LogStore(app()).toAdif().split("<EOR>").size - 1)
    }

    // ------------------------------------------------ l'export ADIF

    /**
     * Un relevé sans indicatif n'est pas un contact et ne sort pas du carnet.
     * Quatre d'entre eux étaient partis dans l'export du 25 août.
     */
    @Test
    fun l_export_adif_laisse_les_entrees_sans_indicatif() {
        val journal = LogStore(app())
        journal.add(LogEntry(1L, "RS-44", 44909, 30.0, 5.0))          // anonyme
        journal.add(LogEntry(2L, "RS-44", 44909, 30.0, 5.0, callsign = "F1FPL"))

        val adif = journal.toAdif("F4IOZ")

        assertEquals(1, adif.split("<EOR>").size - 1)
        assertTrue(adif.contains("F1FPL"))
    }
}
