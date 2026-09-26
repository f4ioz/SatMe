/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.FusionCarnet
import fr.f4ioz.satcombo.domain.FusionCarnet.Fiche
import org.junit.Assert.assertEquals
import org.junit.Test

/** Le banc de la fusion des carnets, entre deux téléphones. */
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

    /** Les millisecondes ne font pas deux contacts : l'identité est à la seconde. */
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

    // ------------------------------------------------------- compléter

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

    // ------------------------------------------------------- ne pas écraser

    /**
     * La règle qui tient tout le reste : une fusion n'écrase jamais.
     *
     * Rien dans une entrée ne dit laquelle des deux valeurs a été corrigée en
     * dernier. Départager au hasard, c'est perdre une correction sans le dire.
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

    // ------------------------------------------------------- l'ensemble

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
