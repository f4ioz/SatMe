/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatJournal
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.rotor.Gs232Rotor
import fr.f4ioz.satcombo.rotor.Gs232Simulator
import fr.f4ioz.satcombo.rotor.RotorMath
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual mast control, for checking a freshly plugged cable without waiting
 * for a pass. A typed angle is mapped into the mast's travel rather than
 * rejected over notation, and sent and received frames are kept — even empty.
 */
class RotorManualTest {

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    private fun rotor(l: SerialLink): Gs232Rotor {
        val r = Gs232Rotor()
        r.pacingMs = 0L
        r.attach(l)
        return r
    }

    /** A connected controller that never answers: the most common case. */
    private class MuetLink : SerialLink {
        var ecrit = 0; private set
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { ecrit++; return true }
        override fun read(buf: ByteArray, timeoutMs: Int): Int = 0
        override fun close() {}
    }

    // ------------------------------------------------------------------
    // Typed angle
    // ------------------------------------------------------------------

    @Test
    fun sur_un_mat_a_butee_nord_l_angle_tape_ne_bouge_pas() {
        val l = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, azStopDeg = 0.0)
        val a = RotorMath.manual(90.0, 45.0, l)
        assertNotNull(a)
        assertEquals(90.0, a!!.azDeg, 1e-9)
        assertEquals(45.0, a.elDeg, 1e-9)
        // North too, already within travel: no extra turn added.
        assertEquals(0.0, RotorMath.manual(0.0, 0.0, l)!!.azDeg, 1e-9)
    }

    @Test
    fun sur_un_mat_a_butee_sud_le_nord_se_trouve_a_360() {
        // A south-stop mast covers unwrapped azimuths 180 to 630. Typing "0"
        // is not an error: it is north, reachable as 360 there. `park`
        // rejects it (parking anywhere but the requested spot is wrong); here
        // the target is the same point in the sky, written differently.
        val l = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, azStopDeg = 180.0)
        assertNull("park n'a pas à deviner un tour", RotorMath.park(0.0, 30.0, l))
        val a = RotorMath.manual(0.0, 30.0, l)
        assertNotNull(a)
        assertEquals(360.0, a!!.azDeg, 1e-9)
        // What already fits passes unchanged.
        assertEquals(200.0, RotorMath.manual(200.0, 30.0, l)!!.azDeg, 1e-9)
        // True 90 maps to 450 or 90, but 90 is below the stop: 450 is the only
        // one that exists.
        assertEquals(450.0, RotorMath.manual(90.0, 0.0, l)!!.azDeg, 1e-9)
    }

    @Test
    fun un_angle_qui_n_existe_nulle_part_reste_refuse() {
        // Half travel, and an azimuth reachable on no turn.
        val court = RotorMath.Limits(azMaxDeg = 180.0, elMaxDeg = 90.0, azStopDeg = 0.0)
        assertNull(RotorMath.manual(270.0, 0.0, court))
        // Elevation has no turns: reject it when out of range.
        val l = RotorMath.Limits(azMaxDeg = 450.0, elMaxDeg = 90.0, azStopDeg = 0.0)
        assertNull(RotorMath.manual(90.0, 120.0, l))
        assertNull(RotorMath.manual(90.0, -5.0, l))
    }

    // ------------------------------------------------------------------
    // What goes out, what comes back
    // ------------------------------------------------------------------

    @Test
    fun la_trame_envoyee_et_la_trame_recue_sont_gardees_en_clair() = runBlocking {
        val sim = Gs232Simulator()
        val r = rotor(sim)
        assertTrue(r.moveTo(90.0, 45.0))
        assertEquals("W090 045", r.lastSent)
        sim.advance(60_000)
        assertNotNull(r.readPosition())
        assertEquals("C2", r.lastSent)
        assertEquals("AZ=090 EL=045", r.lastReply.trim())
        assertTrue(r.stop())
        assertEquals("S", r.lastSent)
    }

    @Test
    fun un_controleur_muet_laisse_la_trame_recue_vide() = runBlocking {
        // Silence is information, and the only one that matters when nothing
        // works: an open port with nobody behind it looks exactly like a mast
        // still turning. Only this empty string, shown as is, tells them apart.
        val muet = MuetLink()
        val r = rotor(muet)
        assertTrue(r.moveTo(90.0, 45.0))
        assertEquals("W090 045", r.lastSent)
        assertNull(r.readPosition())
        assertEquals("", r.lastReply)
        assertTrue("la consigne n'est même pas partie", muet.ecrit >= 2)
    }

    @Test
    fun un_pilote_non_branche_ne_pretend_pas_avoir_parle() = runBlocking {
        val r = Gs232Rotor()
        r.pacingMs = 0L
        assertTrue(!r.moveTo(90.0, 45.0))
        assertEquals("", r.lastSent)
        assertNull(r.readPosition())
        assertEquals("", r.lastReply)
        assertEquals("", r.lastError)
    }
}
