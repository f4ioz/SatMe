/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.cat.Thd72
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

/**
 * SatMe tunes the TH-D72 for APRS before KISS, and moves it between 144.800
 * and 145.825 MHz. Against a fake radio that answers like the real one did
 * on the bench: "?" in normal mode, "cmd:" from the TNC, silence in KISS.
 */
class KissFrequenceTest {

    /** A TH-D72 on its PC port: normal mode, TNC prompt, or KISS. */
    private class FauxThd72(var fo: String = "FO 0,0145500000,0,1,0,1,0,0,0,08,08,000,0,00600000,1") : SerialLink {
        val sortie = LinkedBlockingQueue<Byte>()
        var etat = "NORMAL"
        val commandes = ArrayList<String>()
        private val ligne = StringBuilder()
        private fun dit(s: String) = s.forEach { sortie.put(it.code.toByte()) }

        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
            if (etat == "KISS") {
                // Only the exit frame (C0 FF C0) is understood.
                if (bytes.size >= 3 && bytes[1] == 0xFF.toByte()) etat = "CMD"
                return true
            }
            for (b in bytes) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c != '\r') { ligne.append(c); continue }
                val l = ligne.toString().trim(); ligne.setLength(0)
                commandes += l
                when (etat) {
                    "NORMAL" -> when {
                        l.isEmpty() -> dit("?\r")
                        l == "BC" -> dit("BC 0\r")
                        l == "FO 0" -> dit(fo + "\r")
                        l.startsWith("FO 0,") -> { fo = l; dit(fo + "\r") }
                        l.startsWith("TN 2,") -> { etat = "CMD"; dit("cmd:") }
                        l.startsWith("TN 0,") -> dit("TN 0,0\r")
                        else -> dit("N\r")
                    }
                    "CMD" -> when {
                        l == "KISS ON" -> dit("KISS ON\rcmd:")
                        l == "RESTART" -> { etat = "KISS"; dit("RESTART\r") }
                        l == "TC 1" -> { etat = "NORMAL"; dit("TTS 1\r") }
                        else -> dit("cmd:")
                    }
                }
            }
            return true
        }

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val premier = sortie.poll(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: return 0
            buf[0] = premier
            var n = 1
            while (n < buf.size) { val b = sortie.poll() ?: break; buf[n++] = b }
            return n
        }

        override fun close() {}
    }

    @After fun ferme() { TncKiss.deconnecte(sortirDuKiss = false) }

    @Test
    fun la_bande_est_preparee_pour_l_aprs() {
        val c = Thd72.champs("FO 0,0145500000,8,1,1,1,1,0,0,08,08,000,0,00600000,1", 0)!!
        val a = Thd72.pourAprs(c, 144_800_000L)
        assertEquals("0144800000", a[1])
        assertEquals("0", a[2])          // 30 kHz does not land on 144.800: 5 kHz
        assertEquals(listOf("0", "0", "0", "0", "0"), a.subList(3, 8))  // simplex, no tone
        assertEquals("0", a[14])         // FM
        // A step that divides the frequency is kept.
        assertEquals("7", Thd72.pourAprs(Thd72.champs("FO 0,0145500000,7,0,0,0,0,0,0,08,08,000,0,00600000,0", 0)!!, 145_825_000L)[2])
    }

    @Test
    fun en_kiss_sur_144800_puis_bascule_sur_145825() = runBlocking {
        val poste = FauxThd72()
        TncKiss.attache(null, poste, "TH-D72", 9600)
        assertTrue(TncKiss.passeEnKiss(144_800_000L))
        assertEquals("KISS", poste.etat)
        assertEquals(144_800_000L, TncKiss.etat.value.frequenceHz)
        assertTrue(poste.fo.startsWith("FO 0,0144800000,0,0,0,0,0,0,"))
        assertTrue(poste.fo.endsWith(",0"))
        // During an ISS pass: out of KISS, retuned, back in.
        assertTrue(TncKiss.regleFrequence(145_825_000L))
        assertEquals("KISS", poste.etat)
        assertEquals(145_825_000L, TncKiss.etat.value.frequenceHz)
        assertTrue(poste.commandes.contains("TC 1"))
        // Already there: nothing sent.
        val avant = poste.commandes.size
        assertTrue(TncKiss.regleFrequence(145_825_000L))
        assertEquals(avant, poste.commandes.size)
    }

    @Test
    fun sans_frequence_voulue_le_poste_garde_la_sienne() = runBlocking {
        val poste = FauxThd72()
        TncKiss.attache(null, poste, "TH-D72", 9600)
        assertTrue(TncKiss.passeEnKiss(null))
        assertEquals(145_500_000L, TncKiss.etat.value.frequenceHz)
        assertFalse(poste.commandes.any { it.startsWith("FO 0,") })
    }
}
