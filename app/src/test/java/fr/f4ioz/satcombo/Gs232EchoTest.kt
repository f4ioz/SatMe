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
import fr.f4ioz.satcombo.rotor.RotorPos
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is lying on the wire is not the answer to the question just asked.
 * Echoed `W` commands and leftovers from the previous cycle passed for the
 * mast position, making tracking drop out one second in five.
 *
 * Rule: **a line is an answer only if it parses**. The rest is discarded but
 * kept for the log: "something, but not that" and "nothing" need different
 * fixes.
 */
class Gs232EchoTest {

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    /**
     * A chatty emulator: it echoes the command before answering, and can have
     * bytes pending before we even ask.
     */
    private class LienBavard(
        private val avantLaQuestion: String = "",
        private val echo: Boolean = true,
        private val reponse: String = "AZ=155EL=016\r"
    ) : SerialLink {
        private val sortie = ArrayDeque<Byte>()
        var questions = 0; private set
        private var derniereConsigne = ""

        init { pousser(avantLaQuestion) }

        private fun pousser(s: String) =
            s.toByteArray(Charsets.US_ASCII).forEach { sortie.addLast(it) }

        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
            val s = String(bytes, Charsets.US_ASCII).trim()
            if (s.uppercase().startsWith("C")) {
                questions++
                if (echo && derniereConsigne.isNotEmpty()) pousser("$derniereConsigne\r")
                pousser(reponse)
            } else {
                derniereConsigne = s
            }
            return true
        }

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            var n = 0
            while (n < buf.size && sortie.isNotEmpty()) buf[n++] = sortie.removeFirst()
            return n
        }

        override fun close() {}
    }

    private fun rotor(l: SerialLink): Gs232Rotor {
        val r = Gs232Rotor()
        r.pacingMs = 0L          // no real device to spare in tests
        r.attach(l)
        return r
    }

    @Test
    fun l_echo_de_la_consigne_n_est_pas_pris_pour_la_position() = runBlocking {
        // The field bug: the previous cycle sent `W155 016`, the emulator
        // echoes it, CR-terminated, **before** the position. We returned
        // "W155 016" as the `C2` answer, the parser found nothing, and the mast
        // position blinked off screen.
        val l = LienBavard()
        val r = rotor(l)
        assertTrue(r.moveTo(155.0, 16.0))
        assertEquals(RotorPos(155.0, 16.0), r.readPosition())
        assertEquals(1, l.questions)
    }

    @Test
    fun le_reliquat_du_tour_precedent_est_jete_avant_de_questionner() = runBlocking {
        // A late reply, a boot banner, an ack: whatever sat in the buffer
        // describes the past. Reading it as the current answer shows a stale
        // position — or worse, an unparseable one that wipes everything.
        val l = LienBavard(avantLaQuestion = "AZ=010EL=002\r", echo = false)
        val r = rotor(l)
        assertEquals("le vieux tampon a été pris pour la réponse",
            RotorPos(155.0, 16.0), r.readPosition())
    }

    @Test
    fun le_bruit_d_amorcage_non_termine_ne_masque_pas_la_reponse() = runBlocking {
        // A freshly reset Arduino spits a half line with no CR. It is flushed
        // and the real answer gets through.
        val l = LienBavard(avantLaQuestion = "Arduino GS-232 v1.2", echo = false)
        assertEquals(RotorPos(155.0, 16.0), rotor(l).readPosition())
    }

    @Test
    fun une_reponse_qui_ne_se_relit_pas_reste_dans_le_journal() = runBlocking {
        // Silence and garbage have different fixes (check the cable vs. the
        // baud rate). The rejected frame must survive, or the screen says "no
        // answer" to a controller that is talking.
        val l = LienBavard(echo = false, reponse = "?>\r")
        val r = rotor(l)
        assertNull("une trame illisible a été prise pour une position", r.readPosition())
        assertEquals("?>", r.lastReply)
    }

    @Test
    fun le_charabia_qui_precede_la_position_ne_la_perd_pas() = runBlocking {
        // Several complete lines before the good one: keep reading until one
        // parses instead of stopping at the first.
        val l = LienBavard(echo = false, reponse = "?>\rERR\rAZ=155EL=016\r")
        val r = rotor(l)
        val p = r.readPosition()
        assertNotNull(p)
        assertEquals(155.0, p!!.azDeg, 1e-9)
        assertEquals(16.0, p.elDeg, 1e-9)
    }
}
