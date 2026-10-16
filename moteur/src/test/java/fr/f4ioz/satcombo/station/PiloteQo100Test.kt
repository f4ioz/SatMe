/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.data.SatConfigStore
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.Convertisseur
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QO-100 driven without the phone's screen: the same rules, a rig that only records. */
class PiloteQo100Test {

    private val stockage = StockageMemoire()
    private val settings = SettingsStore(stockage)
    private val satConfig = SatConfigStore(stockage)
    private val ecrits = ArrayList<Pair<Long, Long>>()
    private var armements = 0

    /** A rig on 2 m / 70 cm behind the settings' converters, as a station's MondeCat does. */
    private val materiel = object : PiloteQo100.Materiel {
        fun rx() = Convertisseur(settings.convRxActif, settings.convRxOlHz, settings.convRxInverseur, settings.convRxBasHz, settings.convRxHautHz)
        fun tx() = Convertisseur(settings.convTxActif, settings.convTxOlHz, settings.convTxInverseur, settings.convTxBasHz, settings.convTxHautHz)
        override fun posteRx(satHz: Long) = if (settings.convRxPoste) rx().versPoste(satHz) else satHz
        override fun posteTx(satHz: Long) = tx().versPoste(satHz)
        override fun atteignable(posteHz: Long) = posteHz in 144_000_000L..146_000_000L || posteHz in 430_000_000L..440_000_000L
        override fun ecris(dlSat: Long, ulSat: Long) { ecrits += dlSat to ulSat }
        override fun convertisseursChanges() { armements++ }
    }

    private fun pilote() = PiloteQo100(settings, satConfig, materiel).apply { ouvre() }

    @Test
    fun rien_n_est_ecrit_sans_le_poste_ni_sur_une_balise() {
        val p = pilote()
        assertEquals(Qo100.BALISE_MEDIANE_HZ, p.etat.value.descenteHz)
        assertTrue(p.etat.value.surBalise)
        p.pas(-50_000)
        assertTrue("not asked", ecrits.isEmpty())
        // On the middle beacon: listen only, nothing to the rig.
        p.balise()
        p.auPoste(true)
        assertTrue(ecrits.isEmpty())
        assertTrue(p.etat.value.statut.isNotEmpty())
        // In the voice segment: both legs, the uplink 8 089.5 MHz below.
        p.descente(10_489_700_000L)
        assertEquals(10_489_700_000L to 2_400_200_000L, ecrits.last())
        assertEquals("", p.etat.value.statut)
    }

    @Test
    fun la_frequence_reste_dans_le_transpondeur_sauf_en_balayage() {
        val p = pilote()
        p.descente(10_480_000_000L)
        assertEquals(Qo100.NB.brideDescente(10_480_000_000L), p.etat.value.descenteHz)
        assertTrue(p.etat.value.descenteHz >= 10_489_505_000L)
        p.sansBride(true)
        p.descente(10_489_501_000L)
        assertEquals(10_489_501_000L, p.etat.value.descenteHz)
        assertFalse("a beacon", p.etat.value.emissionPermise)
        // Back in the transponder when sweeping stops; and remembered.
        p.sansBride(false)
        assertTrue(p.etat.value.descenteHz >= 10_489_505_000L)
        assertFalse(settings.qo100SansBride)
    }

    @Test
    fun le_calage_sur_la_balise_deplace_la_descente_seule() {
        val p = pilote()
        p.caleSurLaBalise(10_489_752_300L)
        assertEquals(2_300L, p.etat.value.calageHz)
        assertEquals(2_300L, satConfig.load(Qo100.NORAD).calibShiftHz)
        p.auPoste(true)
        p.descente(10_489_800_000L)
        assertEquals(10_489_802_300L to 2_400_300_000L, ecrits.last())
        // Read again on opening; cleared.
        assertEquals(2_300L, pilote().etat.value.calageHz)
        p.annuleCalage()
        assertEquals(0L, satConfig.load(Qo100.NORAD).calibShiftHz)
    }

    @Test
    fun la_chaine_decide_des_convertisseurs_et_le_poste_affiche_la_moyenne_frequence() {
        val p = pilote()
        assertFalse(p.etat.value.posteAtteignable)
        // A 10 GHz → 2 m downconverter and a 432 → 2.4 GHz transverter.
        p.poseOl(true, 10_344_973_000L)
        p.poseOl(false, 1_968_000_000L)
        assertTrue(settings.convRxActif && settings.convRxPoste && settings.convTxActif)
        assertEquals(10_344_973_000L, settings.convRxOlHz)
        assertTrue(armements >= 2)
        assertEquals(144_777_000L, p.etat.value.posteRxHz)
        assertEquals(432_250_000L, p.etat.value.posteTxHz)
        assertTrue(p.etat.value.posteAtteignable)
        // "Direct" removes the converter, it does not leave the old one on.
        p.poseOl(true, 0L)
        assertFalse(settings.convRxActif)
        assertEquals(Qo100.BALISE_MEDIANE_HZ, p.etat.value.posteRxHz)
        // Each chain its own oscillators.
        p.ajouteChaine("Portable")
        assertEquals("Portable", settings.qo100Chaine)
        assertFalse(settings.convTxActif)
        p.chaine("Fixe")
        assertEquals(1_968_000_000L, settings.convTxOlHz)
    }

    @Test
    fun un_oscillateur_se_mesure_et_l_absurde_est_refuse() {
        val p = pilote()
        val (ok, _) = p.mesure(true, 10_489_750_000L, 144_776_400L)
        assertTrue(ok)
        assertEquals(10_344_973_600L, p.etat.value.chaineActive.descenteOlHz)
        // The two fields swapped: no oscillator, nothing changed.
        assertFalse(p.mesure(true, 144_776_400L, 10_489_750_000L).first)
        assertFalse(p.mesure(false, 0L, 432_000_000L).first)
        assertEquals(10_344_973_600L, p.etat.value.chaineActive.descenteOlHz)
    }

    @Test
    fun les_memoires_se_gardent() {
        val p = pilote()
        p.descente(10_489_688_000L)
        p.poseMemoire("")
        p.descente(10_489_800_000L)
        p.poseMemoire("Sked")
        assertEquals(listOf(10_489_688_000L, 10_489_800_000L), pilote().etat.value.memoires.map { it.hz })
        p.retireMemoire(10_489_688_000L)
        assertEquals(listOf("Sked"), pilote().etat.value.memoires.map { it.nom })
    }

    @Test
    fun l_urgence_proposee_n_arrive_pas_au_poste_l_urgence_a_la_main_si() {
        val p = pilote()
        p.auPoste(true)
        ecrits.clear()
        // A memory, a signal clicked: heard, the uplink held back, said.
        p.descente(10_489_860_000L, fr.f4ioz.satcombo.domain.GardeQo100.Origine.PROPOSEE)
        assertTrue(ecrits.isEmpty())
        assertTrue(p.etat.value.urgence)
        assertTrue(p.etat.value.monteeRetenue)
        assertEquals(fr.f4ioz.satcombo.i18n.t("qo100_urgence_retenue"), p.etat.value.statut)
        // Tuned by hand: written, with the warning.
        p.pas(100)
        assertEquals(10_489_860_100L to 2_400_360_100L, ecrits.last())
        assertFalse(p.etat.value.monteeRetenue)
        assertEquals(fr.f4ioz.satcombo.i18n.t("qo100_urgence"), p.etat.value.statut)
        // Proposed elsewhere: written as before, the warning gone.
        p.descente(10_489_700_000L, fr.f4ioz.satcombo.domain.GardeQo100.Origine.PROPOSEE)
        assertEquals(10_489_700_000L to 2_400_200_000L, ecrits.last())
        assertEquals("", p.etat.value.statut)
    }
}
