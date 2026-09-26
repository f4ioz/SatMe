/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.FusionCarnet
import fr.f4ioz.satcombo.domain.FusionCarnet.Fiche
import org.junit.Assert.assertEquals
import org.junit.Test

/** Merging logs between two phones. */
class FusionCarnetTest {

    private val t = 1_800_000_000_000L

    private fun f(quand: Long, ind: String, vararg champs: Pair<String, String>,
                  cat: Int = 24278) =
        Fiche(quand, ind, cat, champs.toMap())

    @Test
    fun un_contact_absent_s_ajoute() {
        val b = FusionCarnet.fusionne(listOf(f(t, "F1FPL")), listOf(f(t + 60_000, "F5RRO")))
        assertEquals(2, b.fondu.size)
        assertEquals(1, b.ajoutes)
    }

    @Test
    fun un_contact_deja_present_ne_se_duplique_pas() {
        val b = FusionCarnet.fusionne(listOf(f(t, "F1FPL")), listOf(f(t, "F1FPL")))
        assertEquals(1, b.fondu.size)
        assertEquals(0, b.ajoutes)
        assertEquals(1, b.identiques)
    }

    /** Milliseconds do not make two contacts: identity is to the second. */
    @Test
    fun la_seconde_suffit_a_reconnaitre_un_contact() {
        val b = FusionCarnet.fusionne(listOf(f(t, "F1FPL")), listOf(f(t + 400, "F1FPL")))
        assertEquals(1, b.fondu.size)
    }

    @Test
    fun deux_stations_a_la_meme_seconde_restent_deux_contacts() {
        val b = FusionCarnet.fusionne(listOf(f(t, "F1FPL")), listOf(f(t, "F5RRO")))
        assertEquals(2, b.fondu.size)
    }

    @Test
    fun le_meme_indicatif_sur_deux_satellites_reste_deux_contacts() {
        val b = FusionCarnet.fusionne(
            listOf(f(t, "F1FPL", cat = 24278)),
            listOf(f(t, "F1FPL", cat = 44909)))
        assertEquals(2, b.fondu.size)
    }

    @Test
    fun l_indicatif_se_reconnait_quelle_que_soit_la_casse() {
        val b = FusionCarnet.fusionne(listOf(f(t, "F1FPL")), listOf(f(t, "f1fpl")))
        assertEquals(1, b.fondu.size)
    }

    // ------------------------------------------------------- filling in

    @Test
    fun un_champ_vide_localement_se_remplit() {
        val b = FusionCarnet.fusionne(
            listOf(f(t, "F1FPL", "loc" to "")),
            listOf(f(t, "F1FPL", "loc" to "JN09LE")))
        assertEquals("JN09LE", b.fondu.first().champs["loc"])
        assertEquals(1, b.completes)
    }

    @Test
    fun un_champ_inconnu_localement_s_ajoute() {
        val b = FusionCarnet.fusionne(
            listOf(f(t, "F1FPL")),
            listOf(f(t, "F1FPL", "nom" to "Erwin")))
        assertEquals("Erwin", b.fondu.first().champs["nom"])
    }

    // ------------------------------------------------------- never overwrite

    /**
     * The rule everything else rests on: a merge never overwrites.
     *
     * Nothing in an entry says which value was corrected last. Picking one at
     * random silently loses a correction.
     */
    @Test
    fun une_valeur_differente_ne_remplace_pas_la_locale() {
        val b = FusionCarnet.fusionne(
            listOf(f(t, "F1FPL", "loc" to "JN09LE")),
            listOf(f(t, "F1FPL", "loc" to "JN18FS")))
        assertEquals("JN09LE", b.fondu.first().champs["loc"])
        assertEquals(1, b.desaccords)
    }

    @Test
    fun un_champ_vide_en_face_n_efface_rien() {
        val b = FusionCarnet.fusionne(
            listOf(f(t, "F1FPL", "loc" to "JN09LE")),
            listOf(f(t, "F1FPL", "loc" to "")))
        assertEquals("JN09LE", b.fondu.first().champs["loc"])
        assertEquals(0, b.desaccords)
    }

    @Test
    fun un_desaccord_se_compte_meme_si_autre_chose_se_complete() {
        val b = FusionCarnet.fusionne(
            listOf(f(t, "F1FPL", "loc" to "JN09LE", "nom" to "")),
            listOf(f(t, "F1FPL", "loc" to "JN18FS", "nom" to "Erwin")))
        assertEquals("JN09LE", b.fondu.first().champs["loc"])
        assertEquals("Erwin", b.fondu.first().champs["nom"])
        assertEquals(1, b.desaccords)
    }

    // ------------------------------------------------------- the whole log

    @Test
    fun le_carnet_fondu_reste_du_plus_recent_au_plus_ancien() {
        val b = FusionCarnet.fusionne(
            listOf(f(t, "F1FPL")),
            listOf(f(t + 120_000, "F5RRO"), f(t - 120_000, "F5OHH")))
        assertEquals(listOf("F5RRO", "F1FPL", "F5OHH"), b.fondu.map { it.indicatif })
    }

    @Test
    fun fusionner_deux_fois_le_meme_lot_ne_change_rien() {
        val local = listOf(f(t, "F1FPL"))
        val lot = listOf(f(t + 60_000, "F5RRO"))
        val une = FusionCarnet.fusionne(local, lot)
        val deux = FusionCarnet.fusionne(une.fondu, lot)
        assertEquals(2, deux.fondu.size)
        assertEquals(0, deux.ajoutes)
    }

    @Test
    fun un_lot_vide_laisse_le_carnet_intact() {
        val b = FusionCarnet.fusionne(listOf(f(t, "F1FPL")), emptyList())
        assertEquals(1, b.fondu.size)
        assertEquals(0, b.ajoutes)
    }
}
