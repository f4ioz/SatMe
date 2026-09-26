/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.ChaineQo100
import fr.f4ioz.satcombo.domain.ChaineQo100.Chaine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc des chaînes de conversion QO-100.
 *
 * Les nombres de référence sont ceux mesurés par F4IOZ le 3 septembre 2026 :
 * WebSDR IS0GRB à 10 489,805 60 MHz, FT-817 à 144,832 65 MHz sur le même
 * signal. Ce sont des mesures réelles, pas des exemples inventés — si un jour
 * ce banc rougit, c'est que la règle a changé, pas les faits.
 */
class ChaineQo100Test {

    private companion object {
        const val CIEL = 10_489_805_600L
        const val POSTE = 144_832_650L
        const val OL = 10_344_972_950L
    }

    // ------------------------------------------------- la mesure à deux nombres

    /**
     * La mesure que fait tout le monde et que personne n'automatise : deux
     * fréquences observées, l'écart est l'oscillateur.
     */
    @Test
    fun l_oscillateur_se_deduit_de_deux_frequences_observees() {
        assertEquals(OL, ChaineQo100.olMesure(CIEL, POSTE))
    }

    @Test
    fun la_mesure_retrouve_la_frequence_du_poste() {
        val c = Chaine(nom = "Fixe", descenteOlHz = OL)
        assertEquals(POSTE, c.posteRx(CIEL))
    }

    /** Le second relevé, deux heures plus tard, extinction complète entre. */
    @Test
    fun le_second_releve_donne_la_meme_chose_a_dix_hertz() {
        val ol2 = ChaineQo100.olMesure(10_489_805_590L, 144_832_650L)
        assertTrue("écart ${OL - ol2} Hz", kotlin.math.abs(OL - ol2) <= 20L)
    }

    /** La balise CW basse doit tomber à 144,527 05 sur cette chaîne. */
    @Test
    fun la_balise_basse_tombe_ou_elle_doit() {
        val c = Chaine(descenteOlHz = OL)
        assertEquals(144_527_050L, c.posteRx(10_489_500_000L))
    }

    // ------------------------------------------------------------- la montée

    @Test
    fun la_montee_se_mesure_de_la_meme_facon() {
        // Porteuse émise, vue à 2 400,200 sur le WebSDR, poste affichant 432,200.
        assertEquals(1_968_000_000L,
            ChaineQo100.olMesure(2_400_200_000L, 432_200_000L))
    }

    @Test
    fun sans_convertisseur_le_poste_voit_le_ciel() {
        val directe = Chaine(nom = "SDR direct")
        assertEquals(CIEL, directe.posteRx(CIEL))
        assertFalse(directe.descenteActive)
    }

    // ------------------------------------------------------- la plausibilité

    /**
     * On ne refuse pas un chiffre parce qu'il s'écarte du nominal — c'est
     * précisément ce qu'on mesure. On refuse ce qui ne peut pas être un
     * oscillateur du tout.
     */
    @Test
    fun un_ecart_important_au_nominal_reste_credible() {
        assertTrue(ChaineQo100.descenteCredible(OL))
        assertTrue(ChaineQo100.descenteCredible(9_750_000_000L))
        assertTrue(ChaineQo100.descenteCredible(10_057_500_000L))
    }

    @Test
    fun les_deux_champs_inverses_donnent_un_chiffre_refuse() {
        assertFalse(ChaineQo100.descenteCredible(
            ChaineQo100.olMesure(POSTE, CIEL)))
    }

    @Test
    fun une_virgule_deplacee_est_refusee() {
        assertFalse(ChaineQo100.descenteCredible(1_034_497_295L))
        assertFalse(ChaineQo100.descenteCredible(103_449_729_500L))
    }

    @Test
    fun la_montee_a_ses_propres_bornes() {
        assertTrue(ChaineQo100.monteeCredible(1_968_000_000L))
        assertFalse(ChaineQo100.monteeCredible(OL))
    }

    // ----------------------------------------------------- la liste nommée

    /**
     * Fixe et portable n'ont ni les mêmes convertisseurs ni les mêmes erreurs.
     * Retaper la valeur à chaque changement de site, c'est se tromper un jour.
     */
    @Test
    fun deux_chaines_cohabitent_sans_se_melanger() {
        var liste = ChaineQo100.PAR_DEFAUT
        liste = ChaineQo100.range(liste, Chaine("Fixe", OL, 1_968_000_000L))
        liste = ChaineQo100.range(liste, Chaine("Portable", 9_750_123_000L))
        assertEquals(2, liste.size)
        assertEquals(OL, ChaineQo100.choisie(liste, "Fixe").descenteOlHz)
        assertEquals(9_750_123_000L, ChaineQo100.choisie(liste, "Portable").descenteOlHz)
    }

    @Test
    fun ranger_deux_fois_le_meme_nom_remplace_au_lieu_d_ajouter() {
        var liste = ChaineQo100.PAR_DEFAUT
        liste = ChaineQo100.range(liste, Chaine("Fixe", OL))
        liste = ChaineQo100.range(liste, Chaine("Fixe", 9_750_000_000L))
        assertEquals(2, liste.size)
        assertEquals(9_750_000_000L, ChaineQo100.choisie(liste, "Fixe").descenteOlHz)
    }

    @Test
    fun une_chaine_neuve_s_ajoute() {
        val liste = ChaineQo100.range(ChaineQo100.PAR_DEFAUT, Chaine("Voiture", OL))
        assertEquals(3, liste.size)
    }

    /** Un nom inconnu ne doit pas laisser l'écran sans chaîne. */
    @Test
    fun un_nom_inconnu_retombe_sur_la_premiere() {
        assertEquals("Fixe", ChaineQo100.choisie(ChaineQo100.PAR_DEFAUT, "Bateau").nom)
        assertEquals("", ChaineQo100.choisie(emptyList(), "Fixe").nom)
    }

    /**
     * Les chaînes par défaut sont des **noms**, pas des valeurs : proposer un
     * oscillateur nominal ferait croire qu'il convient, alors que chaque
     * exemplaire a son erreur propre.
     */
    @Test
    fun les_chaines_par_defaut_sont_vides() {
        ChaineQo100.PAR_DEFAUT.forEach {
            assertFalse(it.nom, it.descenteActive)
            assertFalse(it.nom, it.monteeActive)
        }
    }
}
