/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.rotor.RotctldCodec
import fr.f4ioz.satcombo.rotor.RotctldRotor
import fr.f4ioz.satcombo.rotor.RotorPos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.Locale

/**
 * The `rotctld` client against a tiny fake Hamlib (on a system-chosen port).
 * Three hidden traps: the decimal point, `RPRT -1` instead of two position
 * lines, and the extended-mode echo. The last two leave one extra line in the
 * pipe, shifting every later reply by one, forever and silently.
 */
class RotctldTest {

    /**
     * A fake `rotctld`.
     *
     * [extended] replays extended mode (`rotctld -vv`, named replies);
     * [failing] a silent controller returning `RPRT -1` instead of a position.
     */
    private class FauxHamlib(
        private val extended: Boolean = false,
        private val failing: Boolean = false
    ) {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        val received: MutableList<String> = Collections.synchronizedList(ArrayList())
        @Volatile var az: Double = 180.0
        @Volatile var el: Double = 45.0

        private val fil = Thread { servir() }

        fun start() { fil.isDaemon = true; fil.start() }
        fun stop() { runCatching { server.close() } }

        private fun servir() {
            runCatching {
                val s = server.accept()
                val r = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                val w = s.getOutputStream().writer(Charsets.US_ASCII)
                while (true) {
                    val ligne = r.readLine() ?: break
                    received += ligne
                    val t = ligne.trim()
                    when {
                        t.startsWith("P ") -> {
                            // Hamlib parses numbers in the C locale. A decimal
                            // comma is not "almost right": it is a flat refusal.
                            val p = t.split(Regex("\\s+"))
                            val a = p.getOrNull(1)?.toDoubleOrNull()
                            val e = p.getOrNull(2)?.toDoubleOrNull()
                            if (t.contains(',') || a == null || e == null) w.write("RPRT -1\n")
                            else {
                                az = a; el = e
                                if (extended) w.write("set_pos: ${p[1]} ${p[2]}\n")
                                w.write("RPRT 0\n")
                            }
                        }
                        t == "p" -> when {
                            failing -> w.write("RPRT -1\n")
                            extended -> {
                                w.write("get_pos:\n")
                                w.write(String.format(Locale.US, "Azimuth: %.6f\n", az))
                                w.write(String.format(Locale.US, "Elevation: %.6f\n", el))
                                w.write("RPRT 0\n")
                            }
                            else -> {
                                w.write(String.format(Locale.US, "%.6f\n", az))
                                w.write(String.format(Locale.US, "%.6f\n", el))
                            }
                        }
                        t == "S" -> w.write("RPRT 0\n")
                        else -> w.write("RPRT -1\n")
                    }
                    w.flush()
                }
            }
        }
    }

    private fun <T> avecServeur(h: FauxHamlib, corps: suspend (RotctldRotor) -> T): T {
        h.start()
        val r = RotctldRotor()
        try {
            return runBlocking {
                assertTrue("la prise ne s'ouvre pas", r.open("127.0.0.1", h.port))
                corps(r)
            }
        } finally { r.close(); h.stop() }
    }

    @Test
    fun la_consigne_part_avec_un_point_decimal_meme_en_francais() {
        // The dumbest, costliest trap: a phone set to French writes "180,00"
        // and Hamlib answers `RPRT -1` with no explanation. The mast does not
        // move, the app shows nothing wrong, and a whole pass is lost.
        val defaut = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("P 180.00 45.50\n", RotctldCodec.moveCommand(180.0, 45.5))
            val h = FauxHamlib()
            avecServeur(h) { r ->
                assertTrue("la consigne a été refusée", r.moveTo(180.0, 45.5))
            }
            assertTrue("consigne absente : ${h.received}",
                h.received.any { it.trim() == "P 180.00 45.50" })
        } finally { Locale.setDefault(defaut) }
    }

    @Test
    fun la_position_se_lit_sur_deux_lignes() {
        val h = FauxHamlib()
        avecServeur(h) { r ->
            assertEquals(RotorPos(180.0, 45.0), r.readPosition())
            // Round trip: what we write, we read back.
            assertTrue(r.moveTo(12.0, 3.5))
            assertEquals(RotorPos(12.0, 3.5), r.readPosition())
        }
        assertTrue(h.received.any { it.trim() == "p" })
    }

    @Test
    fun un_rprt_negatif_rend_null_au_lieu_de_decaler_tout_le_reste() {
        // When the controller does not answer, `p` returns a one-line error
        // code, not two numbers. A reader blindly expecting two lines eats the
        // next command's reply, and everything is shifted from then on.
        val h = FauxHamlib(failing = true)
        avecServeur(h) { r ->
            assertNull(r.readPosition())
            // Proof nothing shifted: the next command gets its own reply.
            assertTrue(r.moveTo(90.0, 10.0))
            assertNull(r.readPosition())
            assertTrue(r.stop())
        }
    }

    @Test
    fun l_echo_du_mode_etendu_ne_se_fait_plus_prendre_pour_une_position() {
        // Extended mode names its fields and ends with `RPRT 0`, which simple
        // mode lacks. Ignoring it leaves one extra line and the same shift —
        // hence the second read, which is the real point of the test.
        val h = FauxHamlib(extended = true)
        avecServeur(h) { r ->
            assertEquals(RotorPos(180.0, 45.0), r.readPosition())
            assertEquals(RotorPos(180.0, 45.0), r.readPosition())
            assertTrue(r.moveTo(300.0, 12.0))
            assertEquals(RotorPos(300.0, 12.0), r.readPosition())
        }
    }

    @Test
    fun l_arret_passe_par_une_ligne_a_lui_seul() {
        val h = FauxHamlib()
        avecServeur(h) { r -> assertTrue(r.stop()) }
        assertTrue("l'arrêt n'est pas parti : ${h.received}",
            h.received.any { it.trim() == "S" })
        assertEquals("S\n", RotctldCodec.STOP)
        assertEquals("p\n", RotctldCodec.QUERY)
    }

    @Test
    fun un_client_non_connecte_rend_null_plutot_que_d_exploser() = runBlocking {
        val r = RotctldRotor()
        assertTrue(!r.isOpen)
        assertNull(r.readPosition())
        assertTrue(!r.moveTo(180.0, 45.0))
        assertTrue(!r.stop())
        // A closed port does not open, and does not crash the app.
        val libre = ServerSocket(0)
        val port = libre.localPort
        libre.close()
        assertTrue(!r.open("127.0.0.1", port, timeoutMs = 300))
        r.close()
        assertTrue(!r.isOpen)
    }

    @Test
    fun une_consigne_impossible_a_formater_ne_part_pas() {
        assertNull(RotctldCodec.moveCommand(Double.NaN, 0.0))
        assertNull(RotctldCodec.moveCommand(0.0, Double.POSITIVE_INFINITY))
        val h = FauxHamlib()
        avecServeur(h) { r ->
            assertTrue(!r.moveTo(Double.NaN, 0.0))
            assertTrue(r.moveTo(45.0, 5.0))
        }
        assertEquals("une trame est partie quand même", 1, h.received.size)
    }
}
