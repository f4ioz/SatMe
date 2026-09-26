/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.DispositionClavier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc des dispositions de clavier.
 *
 * Il vise un défaut qui ne se verrait qu'au pire moment : une lettre absente
 * de la grille ne se remarque que le jour où un indicatif la réclame, en plein
 * passage, et l'on croirait à une panne du clavier.
 */
class DispositionClavierTest {

    @Test
    fun chaque_disposition_porte_les_trente_six_touches() {
        DispositionClavier.toutes.forEach { nom ->
            val rangees = DispositionClavier.rangees(nom)
            assertTrue("disposition $nom incomplète",
                DispositionClavier.complete(rangees))
        }
    }

    @Test
    fun aucune_touche_n_est_en_double() {
        DispositionClavier.toutes.forEach { nom ->
            val touches = DispositionClavier.rangees(nom).flatten()
            assertEquals("doublon dans $nom", touches.size, touches.toSet().size)
        }
    }

    @Test
    fun l_azerty_commence_bien_par_azerty() {
        val rangees = DispositionClavier.rangees(DispositionClavier.AZERTY)
        assertEquals("AZERTYUIOP", rangees[1].joinToString(""))
        assertEquals("QSDFGHJKLM", rangees[2].joinToString(""))
        assertEquals("WXCVBN", rangees[3].joinToString(""))
    }

    @Test
    fun le_qwerty_commence_bien_par_qwerty() {
        val rangees = DispositionClavier.rangees(DispositionClavier.QWERTY)
        assertEquals("QWERTYUIOP", rangees[1].joinToString(""))
        assertEquals("ASDFGHJKL", rangees[2].joinToString(""))
        assertEquals("ZXCVBNM", rangees[3].joinToString(""))
    }

    /** Sur un clavier physique on a « 1234567890 » sous les doigts. */
    @Test
    fun les_chiffres_suivent_le_clavier_physique() {
        listOf(DispositionClavier.AZERTY, DispositionClavier.QWERTY).forEach { nom ->
            assertEquals("1234567890",
                DispositionClavier.rangees(nom).first().joinToString(""))
        }
    }

    @Test
    fun l_alphabetique_reste_sur_six_colonnes() {
        val rangees = DispositionClavier.rangees(DispositionClavier.ALPHABETIQUE)
        assertEquals(6, rangees.size)
        rangees.forEach { assertEquals(6, it.size) }
    }

    /**
     * Un réglage venu d'une version ultérieure, ou abîmé, ne doit pas laisser
     * l'opérateur sans clavier au milieu d'un passage.
     */
    @Test
    fun un_nom_inconnu_rend_l_alphabetique() {
        assertEquals(DispositionClavier.rangees(DispositionClavier.ALPHABETIQUE),
                     DispositionClavier.rangees("dvorak-2031"))
        assertEquals(DispositionClavier.rangees(DispositionClavier.ALPHABETIQUE),
                     DispositionClavier.rangees(""))
    }

    @Test
    fun une_disposition_amputee_est_refusee() {
        assertTrue(!DispositionClavier.complete(listOf("ABC".toList())))
    }
}
