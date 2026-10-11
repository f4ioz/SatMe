/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import com.fazecast.jSerialComm.SerialPort
import fr.f4ioz.satcombo.cat.Ts2000
import fr.f4ioz.satcombo.cat.Ts2000Sim
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * The PC's serial layer on a real (virtual) serial line: two pseudo-terminals
 * joined by socat, jSerialComm on one end, a simulated TS-2000 on the other.
 * Linux only, skipped where socat is missing.
 */
class PortSerieVirtuelTest {

    @Test
    fun un_ts2000_repond_a_travers_un_vrai_port_serie() = runBlocking {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Linux") && File("/usr/bin/socat").canExecute())
        val dossier = File(System.getProperty("java.io.tmpdir"), "satme-pty-" + System.nanoTime()).apply { mkdirs() }
        val a = File(dossier, "a"); val b = File(dossier, "b")
        val socat = ProcessBuilder("socat", "pty,raw,echo=0,link=${a.path}", "pty,raw,echo=0,link=${b.path}")
            .redirectErrorStream(true).start()
        try {
            val fin = System.currentTimeMillis() + 5_000
            while (!(a.exists() && b.exists()) && System.currentTimeMillis() < fin) Thread.sleep(20)
            // The rig's end: what comes in goes to the simulator, its answers go back.
            val sim = Ts2000Sim()
            val cote = RandomAccessFile(b.canonicalPath, "rw")
            val pont = thread(isDaemon = true) {
                val buf = ByteArray(256)
                runCatching {
                    while (true) {
                        val n = cote.channel.read(java.nio.ByteBuffer.wrap(buf))
                        if (n <= 0) break
                        sim.write(buf.copyOf(n), 100)
                        val r = ByteArray(256)
                        val m = sim.read(r, 50)
                        if (m > 0) cote.write(r, 0, m)
                    }
                }
            }
            val port = SerialPort.getCommPort(a.canonicalPath)
            port.clearDTR(); port.clearRTS()
            port.setComPortParameters(57_600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
            assumeTrue("pty opened", port.openPort())
            val lien = LienTs2000Pc(null).apply { pacingMs = 0; attach(LienPc(port)) }
            assertEquals(Ts2000.ID, lien.commande("ID;", 1_000))
            lien.commande(Ts2000.ecrit("A", 436_795_000L))
            assertEquals(436_795_000L, Ts2000.frequence(lien.commande("FA;", 1_000), "A"))
            assertEquals(0, sim.tentativesEmission)
            lien.close()
            cote.close()
            pont.join(500)
        } finally {
            socat.destroy()
            dossier.deleteRecursively()
        }
    }
}
