/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.domain.ChoixCarnet.Demande
import fr.f4ioz.satcombo.domain.ChoixCarnet.Pourquoi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoixCarnetTest {

    private fun choix(demande: Demande = Demande.AUTO, cockpit: Boolean = false, poste: Boolean = false, recepteur: Boolean = false,
                      cat: Boolean = false, suivi: String? = "RS-44", catnum: Int = 44909) =
        ChoixCarnet.choix(demande, cockpit, poste, recepteur, cat, suivi, catnum)

    @Test
    fun le_poste_en_cat_sur_rs44_l_emporte_sur_le_recepteur_qo100() {
        val c = choix(cat = true, recepteur = true)
        assertFalse(c.qo100); assertEquals("RS-44", c.satellite); assertEquals(44909, c.catnum)
        assertEquals(Pourquoi.POSTE_CAT, c.pourquoi); assertEquals("RS-44 · poste en CAT", c.libelle)
        assertEquals(Pourquoi.SUIVI, choix(recepteur = true).pourquoi)
    }

    @Test
    fun le_poste_sur_qo100_note_qo100() {
        val c = choix(poste = true, cat = false)
        assertTrue(c.qo100); assertEquals("QO-100", c.satellite); assertEquals(Qo100.NORAD, c.catnum)
        assertEquals("QO-100 · poste sur QO-100", c.libelle)
    }

    @Test
    fun le_formulaire_du_cockpit_note_qo100() {
        val c = choix(cockpit = true, cat = true)
        assertTrue(c.qo100); assertEquals("QO-100 · cockpit", c.libelle)
    }

    @Test
    fun le_choix_explicite_l_emporte() {
        assertEquals(Pourquoi.CHOISI, choix(Demande.SUIVI, cockpit = true, poste = true, recepteur = true).pourquoi)
        assertEquals("RS-44 · choisi", choix(Demande.SUIVI, cockpit = true, poste = true).libelle)
        val q = choix(Demande.QO100, cat = true)
        assertTrue(q.qo100); assertEquals("QO-100 · choisi", q.libelle)
        // The followed satellite asked but none followed: back to the rule.
        assertEquals("QO-100 · cockpit", choix(Demande.SUIVI, cockpit = true, suivi = null).libelle)
    }

    @Test
    fun le_recepteur_seul_ne_compte_que_sans_satellite_suivi() {
        assertEquals(Pourquoi.RECEPTEUR, choix(recepteur = true, suivi = null).pourquoi)
        val r = choix(suivi = null)
        assertEquals(Pourquoi.AUCUN, r.pourquoi); assertEquals("", r.libelle)
    }

    @Test
    fun qo100_suivi_comme_satellite_reste_qo100() {
        assertTrue(choix(suivi = "QO-100", catnum = Qo100.NORAD).qo100)
    }

    @Test
    fun la_demande_se_lit_du_formulaire() {
        assertEquals(Demande.QO100, Demande.de("qo100")); assertEquals(Demande.SUIVI, Demande.de("suivi"))
        assertEquals(Demande.AUTO, Demande.de(null)); assertEquals(Demande.AUTO, Demande.de("n'importe"))
    }
}
