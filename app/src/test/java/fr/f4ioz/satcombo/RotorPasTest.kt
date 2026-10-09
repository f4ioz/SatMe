/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.RotorMath
import fr.f4ioz.satcombo.rotor.RotorPas
import fr.f4ioz.satcombo.rotor.RotorPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fewer clicks of the controller's relays: ahead, one axis at a time, not too often. */
class RotorPasTest {

    private val lim = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, azStopDeg = 180.0)

    @Test
    fun seul_l_axe_qui_doit_bouger_recoit_une_nouvelle_valeur() {
        val c = RotorPas.commande(RotorPos(205.0, 30.4), RotorPos(200.0, 30.0), 0.5, 0.05, 2.0, lim, 100_000, 0)!!
        assertEquals(30.0, c.elDeg, 1e-9)          // elevation given back its own position
        assertTrue(c.azDeg > 205.0)                  // azimuth ahead of the satellite
    }

    @Test
    fun le_mat_part_en_avance_d_une_marge() {
        val c = RotorPas.commande(RotorPos(205.0, 30.0), RotorPos(200.0, 30.0), 0.5, 0.0, 2.0, lim, 100_000, 0)!!
        assertEquals(205.0 + 1.8, c.azDeg, 1e-9)
        // Target standing still (parked): no lead.
        assertEquals(205.0, RotorPas.commande(RotorPos(205.0, 30.0), RotorPos(200.0, 30.0), 0.0, 0.0, 2.0, lim, 100_000, 0)!!.azDeg, 1e-9)
        // Moving the other way than the mast must go: no lead (it would run past and come back).
        assertEquals(205.0, RotorPas.commande(RotorPos(205.0, 30.0), RotorPos(200.0, 30.0), -0.5, 0.0, 2.0, lim, 100_000, 0)!!.azDeg, 1e-9)
    }

    @Test
    fun pas_plus_d_une_consigne_toutes_les_six_secondes_sauf_grand_ecart() {
        assertNull(RotorPas.commande(RotorPos(203.0, 30.0), RotorPos(200.0, 30.0), 0.5, 0.0, 2.0, lim, 100_000, 97_000))
        // Beyond twice the margin: at once.
        assertTrue(RotorPas.commande(RotorPos(210.0, 30.0), RotorPos(200.0, 30.0), 0.5, 0.0, 2.0, lim, 100_000, 97_000) != null)
        // Within the margin: nothing at all.
        assertNull(RotorPas.commande(RotorPos(201.0, 30.5), RotorPos(200.0, 30.0), 0.5, 0.0, 2.0, lim, 100_000, 0))
    }

    /** A pass followed second by second: how many commands. */
    @Test
    fun un_passage_demande_deux_fois_moins_de_consignes() {
        fun compte(avecAvance: Boolean): Int {
            var mat = RotorPos(200.0, 10.0)
            var dernier = -100_000L
            var n = 0
            for (t in 0..600) {
                val vise = RotorPos(200.0 + 0.3 * t, 10.0 + 0.05 * t)
                val c = RotorPas.commande(vise, mat, if (avecAvance) 0.3 else 0.0, if (avecAvance) 0.05 else 0.0,
                    2.0, lim, t * 1000L, dernier) ?: continue
                n++; dernier = t * 1000L; mat = c
            }
            return n
        }
        val sans = compte(false); val avec = compte(true)
        println("consignes sans avance $sans, avec $avec")
        assertTrue("sans $sans, avec $avec", avec * 1.6 <= sans)
    }
}
