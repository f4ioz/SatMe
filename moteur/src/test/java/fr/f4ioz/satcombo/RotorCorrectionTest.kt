/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.BornesControleur
import fr.f4ioz.satcombo.rotor.CorrectionAxe
import fr.f4ioz.satcombo.rotor.CorrectionRotor
import fr.f4ioz.satcombo.rotor.RotorCorrige
import fr.f4ioz.satcombo.rotor.RotorDriver
import fr.f4ioz.satcombo.rotor.RotorPos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The software correction: controller reading ↔ real angle, both ways. A sign
 * error here is a mast pointing 40° off with every screen agreeing it is
 * right, so each map is checked forward, backward and round trip.
 */
class RotorCorrectionTest {

    @Test
    fun par_defaut_rien_ne_change() {
        val c = CorrectionAxe.lit("")
        assertSame(CorrectionAxe.IDENTITE, c)
        assertTrue(c.estIdentite)
        for (x in listOf(-10.0, 0.0, 45.3, 180.0, 540.0)) {
            assertEquals(x, c.reel(x), 0.0)
            assertEquals(x, c.lu(x), 0.0)
        }
        assertEquals("", c.ecrit())
        assertTrue(CorrectionRotor.IDENTITE.estIdentite)
    }

    @Test
    fun le_cas_d_olivier_180_lu_pour_160_reel() {
        // The controller reads 180 when the boom is really at 160.
        val c = CorrectionAxe.lit("0:0;180:160")
        assertEquals(160.0, c.reel(180.0), 1e-9)
        assertEquals(80.0, c.reel(90.0), 1e-9)
        // To be really at 90, tell the controller 101.25.
        assertEquals(101.25, c.lu(90.0), 1e-9)
        for (x in listOf(0.0, 33.0, 90.0, 150.0, 160.0)) assertEquals(x, c.reel(c.lu(x)), 1e-9)
    }

    @Test
    fun un_seul_point_est_un_decalage() {
        val c = CorrectionAxe.lit("90:93.5")
        assertEquals(3.5, c.reel(0.0), 1e-9)
        assertEquals(183.5, c.reel(180.0), 1e-9)
        assertEquals(176.5, c.lu(180.0), 1e-9)
    }

    @Test
    fun par_points_lineaire_par_morceaux_et_prolonge_aux_bouts() {
        val c = CorrectionAxe.lit("0:0;90:85;180:178")
        assertEquals(42.5, c.reel(45.0), 1e-9)
        assertEquals(85.0, c.reel(90.0), 1e-9)
        assertEquals(131.5, c.reel(135.0), 1e-9)
        // Beyond the last point the last segment carries on.
        assertEquals(178.0 + 93.0 / 90.0 * 10.0, c.reel(190.0), 1e-9)
        for (x in listOf(-5.0, 10.0, 85.0, 120.0, 178.0, 185.0)) assertEquals(x, c.reel(c.lu(x)), 1e-9)
    }

    @Test
    fun ce_qui_ne_peut_pas_etre_une_correction_est_refuse() {
        // Folding back: no inverse.
        assertNull(CorrectionAxe.depuis(listOf(0.0 to 0.0, 90.0 to 100.0, 180.0 to 95.0)))
        // A typo: 1600 for 160.
        assertNull(CorrectionAxe.litOuNull("0:0;180:1600"))
        // Not numbers.
        assertNull(CorrectionAxe.litOuNull("0:0;abc"))
        // Read as identity (never half a map) when stored broken.
        assertTrue(CorrectionAxe.lit("0:0;180:1600").estIdentite)
        // A decimal comma is accepted, as typed on a French keyboard.
        assertEquals(160.5, CorrectionAxe.lit("0:0;180:160,5").reel(180.0), 1e-9)
    }

    @Test
    fun ecrit_puis_relu_donne_la_meme() {
        val c = CorrectionAxe.depuis(listOf(180.0 to 160.0, 0.0 to 0.5))!!
        assertEquals("0:0.5;180:160", c.ecrit())
        assertEquals(c, CorrectionAxe.lit(c.ecrit()))
    }

    @Test
    fun le_tableau_ajuste_une_droite_ou_passe_par_les_points() {
        // Controller readings and inclinometer, with a degree of wander.
        val releves = listOf(45.0 to 40.5, 90.0 to 79.0, 135.0 to 120.5, 180.0 to 160.0)
        val droite = CorrectionAxe.ajuste(releves, parPoints = false)!!
        assertEquals(2, droite.points.size)
        for ((lu, mesure) in releves) assertEquals(mesure, droite.reel(lu), 1.0)
        val points = CorrectionAxe.ajuste(releves, parPoints = true)!!
        for ((lu, mesure) in releves) assertEquals(mesure, points.reel(lu), 1e-9)
        // One measure: an offset.
        assertEquals(1, CorrectionAxe.ajuste(listOf(90.0 to 88.0), false)!!.points.size)
        assertNull(CorrectionAxe.ajuste(emptyList(), false))
    }

    /** A driver that only remembers. */
    private class Memoire(var lu: RotorPos = RotorPos(0.0, 0.0)) : RotorDriver {
        val envoyes = ArrayList<RotorPos>()
        override val isOpen = true
        override suspend fun moveTo(azDeg: Double, elDeg: Double): Boolean { envoyes += RotorPos(azDeg, elDeg); return true }
        override suspend fun readPosition() = lu
        override suspend fun stop() = true
        override fun close() {}
    }

    @Test
    fun le_pilote_corrige_traduit_dans_les_deux_sens() = runBlocking {
        val m = Memoire(RotorPos(200.0, 180.0))
        val corr = CorrectionRotor(CorrectionAxe.lit("0:2;360:362"), CorrectionAxe.lit("0:0;180:160"))
        val d = RotorCorrige(m, { corr }, { BornesControleur(0.0, 360.0, 0.0, 180.0) })
        // Read: the controller's 180 is a real 160; its 200 a real 202.
        val p = d.readPosition()!!
        assertEquals(202.0, p.azDeg, 1e-9); assertEquals(160.0, p.elDeg, 1e-9)
        assertEquals(RotorPos(200.0, 180.0), d.dernierBrut)
        // Send: a real 90 / 80 goes out as 88 / 90.
        assertTrue(d.moveTo(90.0, 80.0))
        assertEquals(88.0, m.envoyes.last().azDeg, 1e-9); assertEquals(90.0, m.envoyes.last().elDeg, 1e-9)
        assertEquals(0.0, d.ecretageDeg, 1e-9)
    }

    @Test
    fun jamais_au_dela_de_la_course_du_controleur() = runBlocking {
        val m = Memoire()
        val corr = CorrectionRotor(el = CorrectionAxe.lit("0:0;180:160"))
        val d = RotorCorrige(m, { corr }, { BornesControleur(0.0, 360.0, 0.0, 180.0) })
        // A real 175 would be 196.9 for the controller: it gets 180, the gap is said.
        assertFalse(d.atteignable(RotorPos(10.0, 175.0)))
        assertTrue(d.atteignable(RotorPos(10.0, 160.0)))
        d.moveTo(10.0, 175.0)
        assertEquals(180.0, m.envoyes.last().elDeg, 1e-9)
        assertTrue(d.ecretageDeg > 16.0)
    }

    @Test
    fun sans_correction_rien_n_est_touche_pas_meme_ramene() = runBlocking {
        val m = Memoire(RotorPos(123.4, 56.7))
        val d = RotorCorrige(m, { CorrectionRotor.IDENTITE }, { BornesControleur(180.0, 540.0, 0.0, 90.0) })
        // Out of these bounds on purpose: identity must pass it through as before.
        d.moveTo(100.0, 120.0)
        assertEquals(RotorPos(100.0, 120.0), m.envoyes.last())
        assertEquals(RotorPos(123.4, 56.7), d.readPosition())
        assertNotNull(d.dernierBrut)
    }
}
