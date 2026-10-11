/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TX status polling: **which radio to ask, and what to say when we can't.**
 * Falling back to the RX radio always answered "receive" (it never
 * transmits), so the screen asserted something false instead of admitting
 * ignorance. (The wire race also involved is fixed in the drivers.)
 */
class SondeEmissionTest {

    /** What polling should conclude from what it has. */
    private fun diagnostic(txOuvert: Boolean, reponse: Boolean?, erreur: Boolean = false): String =
        when {
            !txOuvert -> "poste d'émission non ouvert"
            erreur -> "erreur"
            reponse == null -> "pas de réponse du poste"
            reponse -> "émission"
            else -> "réception"
        }

    /** Is the border lit? Never without a reliable reading. */
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
     * The bug: with no TX radio open, conclude nothing. The old code queried
     * the RX radio and reported its answer as the TX one.
     */
    @Test
    fun sans_poste_d_emission_on_ne_conclut_pas() {
        assertEquals("poste d'émission non ouvert",
            diagnostic(txOuvert = false, reponse = null))
        assertEquals(false, liseret(txOuvert = false, reponse = null))
    }

    /** Even if the other radio answers "receive", that is no reason to show it. */
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

    // ------------------------------------------- two-reading guard

    /**
     * The lighting rule as polling applies it: two consecutive "transmit"
     * readings to light, one "receive" to clear, a missed reading changes
     * nothing.
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
     * Bug: while turning the dial, a leftover ack on the wire was read as a
     * status byte — `00`, i.e. "transmit". A single reading must not light
     * anything.
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

    /** A missed reading decides nothing: keep the previous state. */
    @Test
    fun une_lecture_manquee_laisse_l_etat_en_place() {
        assertEquals(listOf(false, true, true),
            suite(listOf(true, true, null)))
    }

    /**
     * Follow-up bug: the border never lit. The radio answers worst while
     * transmitting; with silence resetting the counter, two consecutive
     * confirmations were never reached.
     */
    @Test
    fun un_silence_entre_deux_confirmations_n_empeche_pas_l_allumage() {
        assertEquals(listOf(false, false, true),
            suite(listOf(true, null, true)))
    }

    @Test
    fun un_silence_ne_vaut_pas_reception() {
        // Lit, then silence: stay lit, and the next confirmation need not
        // start over.
        assertEquals(listOf(false, true, true, true),
            suite(listOf(true, true, null, true)))
    }

    @Test
    fun une_erreur_se_dit_et_n_allume_rien() {
        assertEquals("erreur", diagnostic(txOuvert = true, reponse = null, erreur = true))
        assertEquals(false, liseret(txOuvert = true, reponse = null))
    }
}
