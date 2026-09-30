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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * On demand, with a real radio on the PC: `-Dsatme.kiss=/dev/ttyUSB0`
 * (port set beforehand: `stty -F /dev/ttyUSB0 9600 raw -echo`). Switches
 * it to KISS and back — never transmits anything.
 */
class KissPosteReelTest {
    @Test
    fun passer_en_kiss_et_revenir() = runBlocking {
        val dev = System.getProperty("satme.kiss") ?: ""
        assumeTrue(dev.isNotBlank() && File(dev).exists())
        val f = RandomAccessFile(dev, "rw")
        val lien = object : SerialLink {
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { f.write(bytes); return true }
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                val fin = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < fin) {
                    val dispo = runCatching { java.io.FileInputStream(f.fd).available() }.getOrDefault(0)
                    if (dispo > 0) return f.read(buf, 0, minOf(dispo, buf.size))
                    Thread.sleep(20)
                }
                return 0
            }
            override fun close() = f.close()
        }
        TncKiss.attache(null, lien, dev, 9600)
        assertTrue("passage en KISS", TncKiss.passeEnKiss())
        val e = TncKiss.etat.value
        println("KISS ${dev} : fréquence lue ${e.frequenceHz} Hz, bande ${e.bande}, erreur ${e.erreur}")
        assertTrue(e.initialise)
        TncKiss.deconnecte()
        // Back in normal mode: the radio answers its PC commands again.
        val g = RandomAccessFile(dev, "rw")
        g.write("FO 0\r".toByteArray()); Thread.sleep(800)
        val r = ByteArray(200); val n = java.io.FileInputStream(g.fd).available().let { if (it > 0) g.read(r, 0, minOf(it, 200)) else 0 }
        val rep = String(r, 0, n)
        println("après déconnexion, FO 0 → $rep")
        g.close()
        assertTrue(rep.startsWith("FO 0,"))
        assertEquals(e.frequenceHz, Kiss.frequenceFo(rep))
    }

    /** On demand: `-Dsatme.kissbrut=capture.bin` — raw bytes a real radio sent in KISS, through SatMe's chain. */
    @Test
    fun capture_brute_d_un_vrai_poste() {
        val chemin = System.getProperty("satme.kissbrut") ?: ""
        assumeTrue(chemin.isNotBlank() && File(chemin).exists())
        val paquets = ArrayList<Paquet>()
        var brutes = 0
        KissDecodeur { o -> brutes++; Ax25.decodeSansFcs(o)?.let { paquets += Aprs.lit(it, 0) } }
            .octets(File(chemin).readBytes())
        println("KISS brut : $brutes trame(s), ${paquets.size} lue(s)")
        paquets.forEach { p ->
            println("  ${p.trame.tnc2().map { if (it.code < 32) '·' else it }.joinToString("")}")
            println("     → ${p.type}" + (p.lat?.let { " %.4f %.4f".format(java.util.Locale.US, it, p.lon) } ?: "") +
                (p.message?.let { " « $it »" } ?: ""))
        }
        assertEquals(brutes, paquets.size)
    }
}
