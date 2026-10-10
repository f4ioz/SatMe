/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatBench
import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ic9700Sim
import fr.f4ioz.satcombo.cat.ModeleIcom
import fr.f4ioz.satcombo.domain.SuiviMontee
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IC-910H and the IC-9100, beside the IC-9700: same CI-V, three
 * differences — address, speed (19 200 at most) and, on the IC-910, the
 * satellite-mode command (0x1A 0x07, not 0x16 0x5A). Neither knows 0x25/0x26.
 */
class IcomSatelliteTest {

    private fun cat(m: ModeleIcom, sim: Ic9700Sim): CivController = CivController().apply {
        pacingMs = 0L; modele = m; radioAddr = m.adresse; attach(sim)
    }

    @Test
    fun chaque_modele_a_son_adresse_et_sa_vitesse() {
        assertEquals(0xA2, ModeleIcom.de("IC9700").adresse)
        assertEquals(0x60, ModeleIcom.de("IC910").adresse)
        assertEquals(0x7C, ModeleIcom.de("IC9100").adresse)
        assertEquals(115_200, ModeleIcom.IC9700.baud)
        assertEquals(19_200, ModeleIcom.IC910.baud)
        assertEquals(19_200, ModeleIcom.IC9100.baud)
        // Anything else stays the IC-9700, as before.
        assertEquals(ModeleIcom.IC9700, ModeleIcom.de("THD72"))
    }

    @Test
    fun l_ic910_passe_en_mode_satellite_par_sa_propre_commande() = runBlocking {
        val sim = Ic9700Sim(radioAddr = 0x60, modele = ModeleIcom.IC910)
        val c = cat(ModeleIcom.IC910, sim)
        c.setSatelliteMode(true)
        assertTrue(sim.satMode)
        assertEquals(true, c.readSatelliteMode())
        assertEquals(0, sim.refusals)
    }

    @Test
    fun l_ic910_refuse_la_commande_de_l_ic9700() = runBlocking {
        // What SatMe sent before: the IC-910 never entered satellite mode.
        val sim = Ic9700Sim(radioAddr = 0x60, modele = ModeleIcom.IC910)
        val c = cat(ModeleIcom.IC9700, sim).apply { radioAddr = 0x60 }
        c.setSatelliteMode(true)
        assertFalse(sim.satMode)
        assertEquals(1, sim.refusals)
    }

    @Test
    fun un_debut_de_passage_sans_refus_sur_chaque_poste() = runBlocking {
        for (m in ModeleIcom.entries) {
            val sim = Ic9700Sim(radioAddr = m.adresse, modele = m)
            val r = CatBench.runIc9700(sim, modele = m)
            assertEquals("$m : ${r.steps}", 0, r.refusals)
            assertTrue("$m", r.ok)
        }
    }

    @Test
    fun les_postes_qui_suivent_eux_memes_la_montee() {
        // In satellite mode all three move SUB against MAIN themselves (TRACK).
        assertTrue(SuiviMontee.posteSuitSeul("IC9700"))
        assertTrue(SuiviMontee.posteSuitSeul("IC910"))
        assertTrue(SuiviMontee.posteSuitSeul("IC9100"))
    }
}
