/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.RotorPos
import fr.f4ioz.satcombo.rotor.RotorTenue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Position hold: no flicker, but no lying either.
 *
 * A single missed reply once wiped the mast position; the compass flipped to
 * the satellite and back to the mast a second later. These tests hold both
 * ends: the held position must survive a gap, and must expire before it
 * becomes wrong.
 */
class RotorTenueTest {

    private val pos = RotorPos(155.0, 16.0)
    private val autre = RotorPos(200.0, 40.0)

    @Test
    fun une_lecture_fraiche_passe_toujours_devant_la_precedente() {
        // Normal case: the controller answered, show the answer. Nothing held
        // may override it, even by a millisecond.
        val vu = RotorTenue.montrer(autre, pos, dateMs = 1_000L, maintenant = 1_001L)
        assertEquals(autre, vu)
    }

    @Test
    fun un_trou_court_ne_fait_pas_disparaitre_le_mat() {
        // One skipped reply, 300 ms later: exactly the gap seen on an Arduino
        // emulator busy driving two motors. The screen must not show it.
        val vu = RotorTenue.montrer(null, pos, dateMs = 10_000L, maintenant = 10_300L)
        assertEquals(pos, vu)
    }

    @Test
    fun la_tenue_finit_et_le_satellite_reprend_la_main() {
        // After the delay the satellite takes over, label included. An
        // unplugged controller must be visible: holding a silent mast's last
        // position forever shows a number long after it stopped being true.
        val juste = RotorTenue.montrer(null, pos, 10_000L, 10_000L + RotorTenue.DEFAUT_MS)
        assertEquals("la tenue doit couvrir toute sa durée", pos, juste)
        val apres = RotorTenue.montrer(null, pos, 10_000L, 10_001L + RotorTenue.DEFAUT_MS)
        assertNull("le mât muet est resté affiché au-delà de la tenue", apres)
    }

    @Test
    fun une_horloge_qui_recule_ne_prolonge_pas_la_tenue() {
        // DST change, reboot, NTP step: a negative age must not count as "very
        // fresh" and freeze the position on screen forever.
        assertNull(RotorTenue.montrer(null, pos, dateMs = 50_000L, maintenant = 10_000L))
    }

    @Test
    fun sans_lecture_precedente_il_n_y_a_rien_a_tenir() {
        // First cycle, or right after an unplug cleared memory: never show an
        // invented position.
        assertNull(RotorTenue.montrer(null, null, dateMs = 0L, maintenant = 1_000L))
    }

    @Test
    fun une_tenue_nulle_rend_la_main_immediatement() {
        // The duration is a parameter: zero gives the old no-hold behaviour.
        // Pinned by a test so that disabling the hold stays possible.
        assertNull(RotorTenue.montrer(null, pos, 10_000L, 10_001L, tenueMs = 0L))
    }
}
