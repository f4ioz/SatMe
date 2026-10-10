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

    /**
     * A real controller: whole degrees in the command (GS-232), a mast that
     * stops a few degrees off it (pot calibration, overrun) and a reading
     * that wanders by a degree. Commands sent while [vise] moves for [s] s.
     */
    private fun compteReel(s: Int, vise: (Int) -> RotorPos, avecDerniere: Boolean): Int {
        val alea = java.util.Random(7)
        var cible = RotorPos(200.0, 10.0)
        var derniere: RotorPos? = null
        var dernier = -100_000L
        var n = 0
        for (t in 0..s) {
            val lu = RotorPos(cible.azDeg + 6.0 + alea.nextInt(3) - 1, cible.elDeg - 3.0 + alea.nextInt(3) - 1)
            val v = vise(t)
            val c = RotorPas.commande(v, lu, 0.0, 0.0, 4.0, lim, t * 1000L, dernier,
                derniere = if (avecDerniere) derniere else null) ?: continue
            n++; dernier = t * 1000L
            cible = RotorPos(Math.round(c.azDeg).toDouble(), Math.round(c.elDeg).toDouble())
            derniere = c
        }
        return n
    }

    @Test
    fun au_garage_un_mat_qui_s_arrete_a_cote_ne_fait_plus_claquer_les_relais() {
        // Parked for ten minutes: the mast settles 6° beside its command.
        val avant = compteReel(600, { RotorPos(180.0, 0.0) }, avecDerniere = false)
        val apres = compteReel(600, { RotorPos(180.0, 0.0) }, avecDerniere = true)
        println("garage : $avant consignes avant, $apres après")
        assertTrue("avant $avant", avant > 50)     // the bug: a command every 6 s
        assertEquals(1, apres)                      // one, to go there
    }

    @Test
    fun pendant_un_passage_un_mat_decale_ne_recoit_que_les_consignes_utiles() {
        val passe = { t: Int -> RotorPos(150.0 + 0.3 * t, 10.0 + 0.1 * t) }
        val avant = compteReel(600, passe, avecDerniere = false)
        val apres = compteReel(600, passe, avecDerniere = true)
        println("passage : $avant consignes avant, $apres après")
        // 180° and 60° at 4° a step: about 45 + 15 moves, not one every 6 s.
        assertTrue("après $apres", apres <= 60)
        assertTrue("avant $avant, après $apres", apres * 1.5 < avant)
    }

    @Test
    fun l_axe_immobile_recoit_sa_derniere_consigne_pas_la_lecture() {
        val c = RotorPas.commande(RotorPos(210.0, 30.0), RotorPos(203.0, 27.0), 0.0, 0.0, 4.0, lim, 100_000, 0,
            derniere = RotorPos(200.0, 30.0))!!
        assertEquals(30.0, c.elDeg, 1e-9)
    }

    @Test
    fun un_mat_perdu_recoit_sa_consigne_au_plus_une_fois_par_minute() {
        // The controller ignored the command (switched off, cable): the mast reads 90° away.
        val d = RotorPos(200.0, 30.0)
        val lu = RotorPos(110.0, 30.0)
        assertNull(RotorPas.commande(d, lu, 0.0, 0.0, 4.0, lim, 30_000, 0, derniere = d))
        assertEquals(d, RotorPas.commande(d, lu, 0.0, 0.0, 4.0, lim, 61_000, 0, derniere = d))
        // A few degrees off is a settled mast, never resent.
        assertNull(RotorPas.commande(d, RotorPos(207.0, 26.0), 0.0, 0.0, 4.0, lim, 600_000, 0, derniere = d))
    }
}
