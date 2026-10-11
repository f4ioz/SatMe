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
import fr.f4ioz.satcombo.rotor.Gs232Codec
import fr.f4ioz.satcombo.rotor.Gs232Rotor
import fr.f4ioz.satcombo.rotor.Gs232Simulator
import fr.f4ioz.satcombo.rotor.RotorPos
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GS-232 driver against a simulated controller and a slow mast. No
 * checksum, no ack, no length, and the controller never says "not
 * understood". A missed command and a mast still turning look identical, so
 * the simulator counts refusals to prove a sequence was understood.
 */
class Gs232Test {

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    private fun rotor(sim: SerialLink): Gs232Rotor {
        val r = Gs232Rotor()
        r.pacingMs = 0L          // no real device to spare in tests
        r.attach(sim)
        return r
    }

    /**
     * A serial link returning one character at a time — how a real USB adapter
     * behaves: at 9600 baud `AZ=180 EL=045` takes about 12 ms and rarely
     * arrives at once.
     */
    private class DribbleLink(private val inner: SerialLink) : SerialLink {
        override fun write(bytes: ByteArray, timeoutMs: Int) = inner.write(bytes, timeoutMs)
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            if (buf.isEmpty()) return 0
            val un = ByteArray(1)
            if (inner.read(un, timeoutMs) <= 0) return 0
            buf[0] = un[0]
            return 1
        }
        override fun close() { inner.close() }
    }

    @Test
    fun la_consigne_tient_toujours_en_trois_chiffres() {
        // The controller reads fixed columns. `W180 45` is not "almost right":
        // it is a 450° elevation read from a shifted field.
        assertEquals("W180 045\r", Gs232Codec.moveCommand(180.0, 45.0))
        assertEquals("W005 000\r", Gs232Codec.moveCommand(5.4, 0.0))
        assertEquals("W450 180\r", Gs232Codec.moveCommand(450.0, 179.6))
        assertEquals("C2\r", Gs232Codec.QUERY)
        assertEquals("S\r", Gs232Codec.STOP)
    }

    @Test
    fun une_consigne_qui_ne_tient_pas_dans_le_format_ne_part_pas() {
        assertNull(Gs232Codec.moveCommand(-1.0, 0.0))
        assertNull(Gs232Codec.moveCommand(1000.0, 0.0))
        assertNull(Gs232Codec.moveCommand(0.0, -0.6))
        assertNull(Gs232Codec.moveCommand(Double.NaN, 0.0))
    }

    @Test
    fun les_deux_dialectes_de_position_se_relisent() {
        // GS-232B answers `AZ=180 EL=045`, GS-232A `+0180+0045`, and the same box
        // switches between them via an internal jumper nobody remembers moving.
        assertEquals(RotorPos(180.0, 45.0), Gs232Codec.parsePosition("AZ=180 EL=045\r"))
        assertEquals(RotorPos(180.0, 45.0), Gs232Codec.parsePosition("+0180+0045"))
        assertEquals(RotorPos(12.0, 3.0), Gs232Codec.parsePosition("az=012 el=003"))
        assertEquals(RotorPos(359.0, 0.0), Gs232Codec.parsePosition("AZ=359  EL=000"))
    }

    @Test
    fun une_reponse_incomprehensible_rend_null_plutot_que_zero() {
        // Returning "azimuth zero" for an unparsed reply would send the mast
        // north on the first glitch on the cable.
        assertNull(Gs232Codec.parsePosition("?>"))
        assertNull(Gs232Codec.parsePosition("AZ=1"))
        assertNull(Gs232Codec.parsePosition(""))
        assertNull(Gs232Codec.parsePosition("W180 045"))
    }

    @Test
    fun le_mat_simule_ne_tourne_pas_plus_vite_que_sa_mecanique() = runBlocking {
        // 6°/s is a G-5500's speed: half a turn takes a minute while the
        // satellite moves on. That delay is what the overlap logic is for.
        val sim = Gs232Simulator()
        val r = rotor(sim)
        assertTrue(r.moveTo(180.0, 0.0))
        sim.advance(1000)
        assertEquals(6.0, sim.azDeg, 1e-9)
        sim.advance(10_000)
        assertEquals(66.0, sim.azDeg, 1e-9)
        assertTrue(sim.isMoving)
        sim.advance(60_000)
        assertEquals(180.0, sim.azDeg, 1e-9)
        assertTrue(!sim.isMoving)
        // The travel counter only counts what the mast actually covered.
        assertEquals(180.0, sim.azTravelDeg, 1e-9)
        assertEquals(0.0, sim.elTravelDeg, 1e-9)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun le_pilote_lit_la_position_du_mat_simule() = runBlocking {
        val sim = Gs232Simulator()
        val r = rotor(sim)
        assertTrue(r.moveTo(180.0, 45.0))
        sim.advance(60_000)
        assertEquals(RotorPos(180.0, 45.0), r.readPosition())
        assertEquals(0, sim.refusals)
    }

    @Test
    fun la_reponse_qui_arrive_caractere_par_caractere_est_rassemblee() = runBlocking {
        // The quietest serial-driver bug: a single read. On a real cable the
        // reply arrives in pieces, we catch `AZ=1`, and conclude "no answer"
        // half the time.
        val sim = Gs232Simulator()
        val r = Gs232Rotor()
        r.pacingMs = 0L
        r.attach(DribbleLink(sim))
        assertTrue(r.moveTo(90.0, 30.0))
        sim.advance(60_000)
        assertEquals(RotorPos(90.0, 30.0), r.readPosition())
    }

    @Test
    fun une_consigne_hors_course_est_refusee_et_comptee() = runBlocking {
        // 500° fits the format but not the mechanics. The real controller
        // silently ignores it; the simulator counts it, so a test can assert it.
        val sim = Gs232Simulator(azMaxDeg = 450.0, elMaxDeg = 180.0)
        val r = rotor(sim)
        assertTrue(r.moveTo(500.0, 0.0))      // the well-formed frame is sent
        sim.advance(60_000)
        assertEquals("le mât a bougé sur une consigne refusée", 0.0, sim.azDeg, 1e-9)
        assertEquals(1, sim.refusals)
        // Non-GS-232 input is refused too.
        sim.write("Z\r".toByteArray(), 500)
        assertEquals(2, sim.refusals)
        // The limit itself passes.
        assertTrue(r.moveTo(450.0, 180.0))
        sim.advance(120_000)
        assertEquals(450.0, sim.azDeg, 1e-9)
        assertEquals(2, sim.refusals)
    }

    @Test
    fun l_arret_immediat_fige_le_mat_ou_il_est() = runBlocking {
        val sim = Gs232Simulator()
        val r = rotor(sim)
        r.moveTo(180.0, 0.0)
        sim.advance(5_000)
        assertEquals(30.0, sim.azDeg, 1e-9)
        assertTrue(r.stop())
        sim.advance(60_000)
        assertEquals("le mât a continué après l'arrêt", 30.0, sim.azDeg, 1e-9)
        assertTrue(!sim.isMoving)
    }

    @Test
    fun un_pilote_non_branche_rend_null_plutot_que_d_exploser() = runBlocking {
        val r = Gs232Rotor()
        r.pacingMs = 0L
        assertTrue(!r.isOpen)
        assertNull(r.readPosition())
        assertTrue(!r.moveTo(180.0, 45.0))
        assertTrue(!r.stop())
        assertTrue(r.listDevices().isEmpty())
        // A closed controller behaves the same.
        val sim = Gs232Simulator()
        val ouvert = rotor(sim)
        ouvert.close()
        assertTrue(!ouvert.isOpen)
        assertTrue(sim.isClosed)
        assertNull(ouvert.readPosition())
    }

    @Test
    fun le_journal_garde_les_trames_du_rotor_en_clair() = runBlocking {
        // Same log as CAT, same reason: when the mast does not move, the only
        // useful question is "did the frame go out, and what came back?"
        CatJournal.clear()
        CatJournal.enabled = true
        val sim = Gs232Simulator()
        val r = rotor(sim)
        r.moveTo(180.0, 45.0)
        sim.advance(60_000)
        r.readPosition()
        val e = CatJournal.entries.value
        assertTrue("journal vide", e.size >= 3)
        assertTrue(e.any { it.out && it.text == "consigne → azimut 180°, élévation 45°" })
        assertTrue(e.any { it.out && it.text == "demande de position" })
        assertTrue(e.any { !it.out && it.text.contains("position : azimut 180°") })
        assertTrue(e.first().hex.startsWith("57"))     // 'W'
    }
}
