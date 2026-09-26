/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le banc du sondage d'émission.
 *
 * Le liseré ne s'allumait pas « selon le port ». Il ne dépendait pas du port :
 * il dépendait de deux choses que le port ne faisait que révéler.
 *
 * La première est une course sur le fil, corrigée dans les pilotes eux-mêmes
 * par un verrou de transaction — elle ne se teste pas ici.
 *
 * La seconde est cette règle-ci : **quel poste interroger, et que dire quand
 * on ne peut pas.** Le repli vers le poste de réception rendait « réception »
 * avec constance, puisqu'un poste de réception n'émet jamais. L'écran
 * affirmait donc quelque chose de faux au lieu d'avouer son ignorance, et
 * l'opérateur se fiait à l'absence de liseré.
 */
class SondeEmissionTest {

    /** Ce que le sondage doit conclure, à partir de ce qu'il a sous la main. */
    private fun diagnostic(txOuvert: Boolean, reponse: Boolean?, erreur: Boolean = false): String =
        when {
            !txOuvert -> "poste d'émission non ouvert"
            erreur -> "erreur"
            reponse == null -> "pas de réponse du poste"
            reponse -> "émission"
            else -> "réception"
        }

    /** Le liseré s'allume-t-il ? Sans lecture sûre, jamais. */
    private fun liseret(txOuvert: Boolean, reponse: Boolean?): Boolean =
        txOuvert && reponse == true

    @Test
    fun le_poste_qui_emet_repond_emission() {
        assertEquals("émission", diagnostic(txOuvert = true, reponse = true))
        assertEquals(true, liseret(txOuvert = true, reponse = true))
    }

    @Test
    fun le_poste_qui_recoit_repond_reception() {
        assertEquals("réception", diagnostic(txOuvert = true, reponse = false))
        assertEquals(false, liseret(txOuvert = true, reponse = false))
    }

    /**
     * Le défaut : sans poste d'émission ouvert, on ne conclut rien.
     *
     * L'ancienne écriture interrogeait alors le poste de réception et
     * rapportait sa réponse comme si c'était celle de l'émission.
     */
    @Test
    fun sans_poste_d_emission_on_ne_conclut_pas() {
        assertEquals("poste d'émission non ouvert",
            diagnostic(txOuvert = false, reponse = null))
        assertEquals(false, liseret(txOuvert = false, reponse = null))
    }

    /**
     * Et surtout : même si l'autre poste, lui, répondait « réception », ce
     * n'est pas une raison pour l'afficher.
     */
    @Test
    fun la_reponse_de_l_autre_poste_ne_vaut_pas_reponse() {
        assertEquals("poste d'émission non ouvert",
            diagnostic(txOuvert = false, reponse = false))
    }

    @Test
    fun sans_reponse_le_liseret_s_eteint() {
        assertEquals("pas de réponse du poste", diagnostic(txOuvert = true, reponse = null))
        assertEquals(false, liseret(txOuvert = true, reponse = null))
    }

    // ------------------------------------------- le garde à deux relevés

    /**
     * La règle d'allumage, telle que le sondage l'applique : deux relevés
     * « émission » consécutifs pour allumer, un seul « réception » pour
     * éteindre, et une lecture manquée ne change rien.
     */
    private fun suite(releves: List<Boolean?>): List<Boolean> {
        var n = 0
        var allume = false
        return releves.map { tx ->
            when (tx) {
                true -> n++
                false -> n = 0
                null -> Unit
            }
            allume = if (tx == null) allume else n >= 2
            allume
        }
    }

    /**
     * Le défaut du 26 août : en tournant la molette, un acquittement laissé
     * sur le fil était lu comme un octet d'état — `00`, donc « émission ».
     * Un relevé isolé ne doit plus rien allumer.
     */
    @Test
    fun un_releve_isole_n_allume_pas() {
        assertEquals(listOf(false, false, false),
            suite(listOf(false, true, false)))
    }

    @Test
    fun deux_releves_consecutifs_allument() {
        assertEquals(listOf(false, false, true, true),
            suite(listOf(false, true, true, true)))
    }

    @Test
    fun un_seul_releve_de_reception_eteint() {
        assertEquals(listOf(false, true, false),
            suite(listOf(true, true, false)))
    }

    /** Une lecture manquée ne décide de rien : on garde l'état précédent. */
    @Test
    fun une_lecture_manquee_laisse_l_etat_en_place() {
        assertEquals(listOf(false, true, true),
            suite(listOf(true, true, null)))
    }

    /**
     * Le défaut du 26 août au soir : le liseré ne s'allumait plus du tout.
     *
     * C'est pendant l'émission que le poste répond le moins bien. Une réponse
     * sur deux se perdait, et comme le silence remettait le compteur à zéro,
     * deux confirmations consécutives n'étaient jamais atteintes.
     */
    @Test
    fun un_silence_entre_deux_confirmations_n_empeche_pas_l_allumage() {
        assertEquals(listOf(false, false, true),
            suite(listOf(true, null, true)))
    }

    @Test
    fun un_silence_ne_vaut_pas_reception() {
        // Allumé, puis un silence : on reste allumé, et la confirmation
        // suivante n'a pas à tout recommencer.
        assertEquals(listOf(false, true, true, true),
            suite(listOf(true, true, null, true)))
    }

    @Test
    fun une_erreur_se_dit_et_n_allume_rien() {
        assertEquals("erreur", diagnostic(txOuvert = true, reponse = null, erreur = true))
        assertEquals(false, liseret(txOuvert = true, reponse = null))
    }
}
