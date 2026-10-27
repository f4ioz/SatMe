/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gain remembered per symbol rate, and the gain ramps. */
class PuissanceDatvTest {

    @Test
    fun calibration_apres_dix_secondes_au_bon_niveau() {
        val c = CalibrationGain()
        assertFalse(c.mesure(333, -22.0, -2.5, false, 0))
        assertFalse(c.mesure(333, -22.0, -2.4, false, 5_000))
        assertTrue(c.mesure(333, -22.0, -2.6, false, 10_000))
        assertEquals(-22.0, c.de(333)!!.gainDb, 0.0)
        assertNull(c.de(250))
        // The same again: nothing new.
        assertFalse(c.mesure(333, -22.0, -2.6, false, 21_000))
    }

    @Test
    fun pas_de_calibration_trop_fort_trop_faible_ou_gain_qui_bouge() {
        val c = CalibrationGain()
        for (t in 0..20) c.mesure(333, -10.0, 0.5, true, t * 1000L)
        assertNull(c.de(333))
        for (t in 0..20) c.mesure(333, -40.0, -9.0, false, t * 1000L)
        assertNull(c.de(333))
        // The gain changing each second never settles.
        for (t in 0..20) c.mesure(333, -30.0 + t, -3.0, false, t * 1000L)
        assertNull(c.de(333))
        for (t in 0..20) c.mesure(333, -30.0, null, false, t * 1000L)
        assertNull(c.de(333))
    }

    @Test
    fun calibration_gardee_et_relue() {
        val c = CalibrationGain()
        c.mesure(1500, -12.5, -1.5, false, 0); c.mesure(1500, -12.5, -1.5, false, 10_000)
        val d = CalibrationGain()
        d.reprends(c.texte())
        assertEquals(-12.5, d.de(1500)!!.gainDb, 0.0)
        // A rate not offered or a positive gain read back: left out.
        d.reprends("""[{"sr":777,"g":-10,"e":-2,"t":1},{"sr":333,"g":3,"e":-2,"t":1},{"sr":250,"g":-20,"e":-2,"t":1}]""")
        assertEquals(listOf(250), d.tous().map { it.srKs })
        d.oublie(250)
        assertTrue(d.tous().isEmpty())
    }

    @Test
    fun rampe_vers_le_haut_pas_a_pas_et_plafonnee() {
        val r = RampeGain()
        r.demarre(RampeGain.Sens.HAUT, -5.0, -10.0)
        // The ceiling wins over the target.
        assertEquals(-10.0, r.etat.cibleDb, 0.0)
        var g = -14.0
        var t = 0L
        val pas = ArrayList<Double>()
        while (r.active && t < 60_000) { r.tic(g, false, t)?.let { g = it; pas += it }; t += 1000 }
        assertEquals(listOf(-13.0, -12.0, -11.0, -10.0), pas)
        // One step every 2 s.
        assertFalse(r.active)
        assertTrue(r.etat.message.contains("atteint"))
    }

    @Test
    fun rampe_vers_le_haut_arretee_si_trop_fort() {
        val r = RampeGain()
        r.demarre(RampeGain.Sens.HAUT, -5.0, 0.0)
        assertEquals(-19.0, r.tic(-20.0, false, 0)!!, 0.0)
        assertNull(r.tic(-19.0, true, 5_000))
        assertFalse(r.active)
        assertTrue(r.etat.message.contains("trop fort"))
    }

    @Test
    fun rampe_vers_le_bas_rapide_meme_trop_fort() {
        val r = RampeGain()
        r.demarre(RampeGain.Sens.BAS, -60.0, -10.0)
        assertEquals(-23.0, r.tic(-20.0, true, 0)!!, 0.0)
        assertNull(r.tic(-23.0, true, 500))
        assertEquals(-26.0, r.tic(-23.0, true, 1000)!!, 0.0)
        // The last step stops at the target.
        assertEquals(-60.0, r.tic(-58.0, false, 2000)!!, 0.0)
        assertNull(r.tic(-60.0, false, 3000))
        assertFalse(r.active)
        r.demarre(RampeGain.Sens.BAS, -60.0, -10.0)
        r.arrete()
        assertNull(r.tic(-20.0, false, 10_000))
    }

    @Test
    fun seuils_de_mer_par_modcod() {
        // What a receiving station needs, as the summary says it (EN 302 307-1 table 13).
        assertEquals(1.0, QualiteDatv.seuilDb("QPSK 1/2", 4)!!, 1e-9)
        assertEquals(3.1, QualiteDatv.seuilDb("QPSK 2/3", 4)!!, 1e-9)
        assertEquals(1.3, QualiteDatv.seuilDb("QPSK 1/2", 4, true)!!, 1e-9)
        assertEquals(6.62, QualiteDatv.seuilDb("8PSK 2/3", 4)!!, 1e-9)
        assertEquals(6.9, QualiteDatv.seuilDb("QPSK 3/4", 3)!!, 1e-9)
    }
}
