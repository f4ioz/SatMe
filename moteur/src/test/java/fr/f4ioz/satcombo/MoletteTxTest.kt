/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.MoletteTx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transmit dial used as an offset knob.
 *
 * This computation shipped wrong twice. It lived inside the CAT loop, mixed
 * with serial reads and writes, out of reach of tests — the operator had to
 * catch it at the radio.
 */
class MoletteTxTest {

    private val consigne = 435_100_000L

    @Test
    fun un_vrai_geste_est_absorbe_une_fois() {
        val d = MoletteTx.decide(
            shiftHz = 100L, referenceHz = consigne, lueHz = consigne + 300L,
            gesteVu = true, moletteTranquille = true)
        assertTrue(d.absorbe)
        assertEquals(400L, d.shiftHz)
        assertTrue(d.gesteConsomme)
    }

    @Test
    fun un_geste_vers_le_bas_soustrait() {
        val d = MoletteTx.decide(50L, consigne, consigne - 250L, true, true)
        assertEquals(-200L, d.shiftHz)
    }

    /**
     * **The regression.**
     *
     * Doppler drifts, the loop runs, nobody touches the dial. The radio
     * reading departs from the freshly computed uplink — but not from the last
     * written setpoint, the only honest reference. The offset must not move
     * by one hertz.
     *
     * The old code compared against the fresh uplink and absorbed the drift:
     * the offset oscillated forever between two values.
     */
    @Test
    fun la_derive_doppler_sans_geste_ne_bouge_pas_le_decalage() {
        var shift = 480L
        var reference = consigne
        // Two minutes of 1 Hz loop with Doppler running: the radio always
        // returns what was written.
        repeat(120) {
            val d = MoletteTx.decide(
                shiftHz = shift, referenceHz = reference, lueHz = reference,
                gesteVu = false, moletteTranquille = true)
            assertFalse(d.absorbe)
            shift = d.shiftHz
            reference = d.referenceHz
        }
        assertEquals(480L, shift)
    }

    /**
     * Same with radio rounding: the FT-817 quantises, so its reply never
     * exactly matches the request. Without a gesture, nothing may trigger.
     */
    @Test
    fun l_arrondi_du_poste_sans_geste_ne_declenche_rien() {
        var shift = 0L
        repeat(50) { i ->
            val d = MoletteTx.decide(
                shiftHz = shift, referenceHz = consigne,
                lueHz = consigne + (if (i % 2 == 0) 10L else -10L),
                gesteVu = false, moletteTranquille = true)
            shift = d.shiftHz
        }
        assertEquals(0L, shift)
    }

    /**
     * What stops the runaway: after absorption the reference follows the
     * radio. Otherwise the same offset would be re-absorbed next cycle and the
     * shift would double each time.
     */
    @Test
    fun la_reference_suit_le_poste_apres_absorption() {
        val premier = MoletteTx.decide(0L, consigne, consigne + 300L, true, true)
        assertEquals(300L, premier.shiftHz)
        assertEquals(consigne + 300L, premier.referenceHz)

        // Next cycle: no gesture, same reading. Nothing moves.
        val second = MoletteTx.decide(
            premier.shiftHz, premier.referenceHz, consigne + 300L, false, true)
        assertFalse(second.absorbe)
        assertEquals(300L, second.shiftHz)
    }

    /** Dial still moving: a position in passing is not an intention. */
    @Test
    fun rien_ne_s_absorbe_tant_que_la_molette_tourne() {
        val d = MoletteTx.decide(0L, consigne, consigne + 5_000L,
            gesteVu = true, moletteTranquille = false)
        assertFalse(d.absorbe)
        assertFalse(d.gesteConsomme)
    }

    /**
     * A too-small gesture is consumed without being absorbed. Leaving it
     * pending would absorb it later, when the offset may have changed meaning.
     */
    @Test
    fun un_geste_sous_le_seuil_est_consomme_sans_etre_absorbe() {
        val d = MoletteTx.decide(0L, consigne, consigne + 10L, true, true)
        assertFalse(d.absorbe)
        assertTrue(d.gesteConsomme)
        assertEquals(0L, d.shiftHz)
    }

    /** Nothing written yet: no reference, so no measurement. */
    @Test
    fun sans_consigne_ecrite_on_ne_mesure_rien() {
        val d = MoletteTx.decide(0L, 0L, consigne, true, true)
        assertFalse(d.absorbe)
        assertTrue(d.gesteConsomme)
    }

    /** Two successive gestures add up, like two button presses. */
    @Test
    fun deux_gestes_successifs_s_additionnent() {
        val a = MoletteTx.decide(0L, consigne, consigne + 200L, true, true)
        val b = MoletteTx.decide(a.shiftHz, a.referenceHz, a.referenceHz + 150L, true, true)
        assertEquals(350L, b.shiftHz)
    }

    /**
     * The absorption threshold equals the write threshold, on purpose: an
     * offset not worth writing is not worth absorbing.
     */
    @Test
    fun le_seuil_est_celui_de_l_ecriture() {
        assertEquals(20L, MoletteTx.SEUIL_HZ)
    }
}
