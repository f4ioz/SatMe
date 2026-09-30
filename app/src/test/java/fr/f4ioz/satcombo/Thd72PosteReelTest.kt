/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.cat.Thd72
import fr.f4ioz.satcombo.cat.Thd72Bande
import fr.f4ioz.satcombo.cat.Thd72Lien
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * On demand, a real TH-D72 on the PC: `-Dsatme.thd72=/dev/ttyUSB0` (port set
 * with `stty -F … 9600 raw -echo`). Follows a short Doppler run on band B,
 * sets and clears a tone there, then puts band B back exactly — never keys.
 */
class Thd72PosteReelTest {
    @Test
    fun doppler_sur_la_bande_b_puis_remise_en_etat() = runBlocking {
        val dev = System.getProperty("satme.thd72") ?: ""
        assumeTrue(dev.isNotBlank() && File(dev).exists())
        val f = RandomAccessFile(dev, "rw")
        fun dispo() = runCatching { java.io.FileInputStream(f.fd).available() }.getOrDefault(0)
        val lien = Thd72Lien().also {
            it.attach(object : SerialLink {
                override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { f.write(bytes); return true }
                override fun read(buf: ByteArray, timeoutMs: Int): Int {
                    val fin = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < fin) {
                        val d = dispo(); if (d > 0) return f.read(buf, 0, minOf(d, buf.size)); Thread.sleep(10)
                    }
                    return 0
                }
                override fun close() = f.close()
            })
        }
        try {
            lien.commande("")  // a lone CR: clears whatever was pending
            assertTrue("TH-D72 ?", lien.estUnThd72())
            val origine = lien.commande("FO 1")!!
            println("bande B au départ : $origine ; bande A : ${lien.commande("FO 0")} ; BC : ${lien.bandeCourante()}")
            val b = Thd72Bande(lien, 1)
            for ((demande, attendu) in listOf(436_805_300L to 436_805_000L, 436_800_400L to 436_800_000L,
                    436_795_100L to 436_795_000L)) {
                val t0 = System.nanoTime()
                assertTrue(b.setFrequency(demande))
                val ms = (System.nanoTime() - t0) / 1_000_000
                val lue = b.readFrequency()
                println("demandé $demande → poste $lue ($ms ms)")
                assertEquals(attendu, lue)
            }
            assertTrue(b.setCtcss(670))
            println("avec ton 67,0 Hz : ${lien.commande("FO 1")}")
            assertTrue(b.setCtcss(0))
            val c = Thd72.champs(origine, 1)!!
            println("remise : ${lien.commande(Thd72.commande(c))}")
            assertEquals(origine, lien.commande("FO 1"))
        } finally {
            lien.close()
        }
    }
}
