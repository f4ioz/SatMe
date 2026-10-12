/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The rtl_tcp client against a server of a few lines that sends its samples in odd pieces. */
class RtlTcpTest {

    @Test
    fun l_entete_les_commandes_et_des_paires_iq_jamais_coupees() {
        val serveur = ServerSocket(0)
        val recues = ArrayList<Pair<Int, Int>>()
        val fin = thread {
            serveur.accept().use { c ->
                val o = c.getOutputStream()
                o.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); o.flush()
                // I = 1, Q = 2 throughout, in pieces of odd sizes.
                val iq = ByteArray(20_001) { if (it % 2 == 0) 1 else 2 }
                var p = 0
                for (t in listOf(1, 7, 333, 4_096, 5, 15_559)) { o.write(iq, p, t); o.flush(); p += t; Thread.sleep(5) }
                val e = DataInputStream(c.getInputStream())
                repeat(4) { recues += e.readUnsignedByte() to e.readInt() }
            }
        }
        val r = RtlTcp.connecte("127.0.0.1", serveur.localPort)
        assertEquals(RtlTcp.TUNER_R820T, r.tuner)
        assertEquals(29, r.nbGains)
        val buf = ByteArray(RtlTcp.BLOC)
        var total = 0
        while (total < 20_000) {
            val n = r.read(buf, 2000)
            assertTrue("data", n > 0)
            assertEquals("even", 0, n % 2)
            for (i in 0 until n) assertEquals(if (i % 2 == 0) 1.toByte() else 2.toByte(), buf[i])
            total += n
        }
        assertEquals(20_000, total)
        r.setCenterFreq(145_800_000L); r.setSampleRate(1_058_400); r.setGain(297)
        fin.join(3000)
        assertEquals(listOf(0x01 to 145_800_000, 0x02 to 1_058_400, 0x03 to 1, 0x04 to 297), recues)
        // The server gone: the end is said, not waited for.
        Thread.sleep(100)
        assertEquals(-1, r.read(buf, 500))
        r.close(); serveur.close()
    }

    @Test(expected = IOException::class)
    fun un_serveur_qui_n_est_pas_rtl_tcp_est_refuse() {
        val serveur = ServerSocket(0)
        thread { serveur.accept().use { it.getOutputStream().write("HTTP/1.1 200".toByteArray()) } }
        try { RtlTcp.connecte("127.0.0.1", serveur.localPort) } finally { serveur.close() }
    }
}
