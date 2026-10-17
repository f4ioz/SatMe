/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import org.junit.Ignore
import org.junit.Test

/**
 * The software receiver's QEF thresholds against table 13: per MODCOD,
 * Es/N0 raised by 0.25 dB from just below the standard's ideal value until
 * [TRAMES] frames in a row decode (normal frames, pilots, 1 MS/s, 5 kHz
 * off). A bench (twenty minutes or so), run by hand: remove @Ignore.
 */
class EssaiSeuilsTest {
    private val norme = mapOf("QPSK 1/4" to -2.35, "QPSK 1/3" to -1.24, "QPSK 2/5" to -0.30, "QPSK 1/2" to 1.00, "QPSK 3/5" to 2.23,
        "QPSK 2/3" to 3.10, "QPSK 3/4" to 4.03, "QPSK 4/5" to 4.68, "QPSK 5/6" to 5.18, "QPSK 8/9" to 6.20, "QPSK 9/10" to 6.42,
        "8PSK 3/5" to 5.50, "8PSK 2/3" to 6.62, "8PSK 3/4" to 7.91, "8PSK 5/6" to 9.35, "8PSK 8/9" to 10.69, "8PSK 9/10" to 10.98)

    @Ignore("banc d'une vingtaine de minutes, lancé à la main")
    @Test
    fun seuils() {
        for ((nom, ideal) in norme) {
            val m = Modcod.de(nom, pilotes = true)!!
            var db = ideal - 0.25
            while (db < ideal + 3) {
                val c = CanalS2(m, rs = 1e6, fs = 2.5e6, esN0Db = db, decalageHz = 5e3, graine = (db * 100).toLong())
                val x = c.signal((TRAMES + 3) * m.symboles / 1e6)
                val recu = Recu(c)
                val rx = RecepteurS2(c.fs, c.rs, recu.sortie, 4)
                c.recois(x, rx)
                val e = rx.etat()
                rx.ferme()
                println("SEUIL $nom Es/N0 ${"%.2f".format(db)} dB (norme $ideal) : ${e.tramesDonnees} trames, ${e.ldpcEchecs} échecs, " +
                    "MER ${e.merDb?.let { "%.1f".format(it) }}, itérations ${"%.1f".format(e.iterationsMoyennes)}")
                if (e.tramesDonnees >= TRAMES - 2 && e.ldpcEchecs == 0L) break
                db += 0.25
            }
        }
    }

    /** Real time: per rate and MODCOD (Es/N0 1 dB above the threshold), the wall time and CPU time per second of signal. */
    @Ignore("banc, lancé à la main")
    @Test
    fun temps_reel() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        fun cpu(): Long = bean.allThreadIds.sumOf { bean.getThreadCpuTime(it).coerceAtLeast(0) }
        for (rs in listOf(125e3, 333e3, 1000e3, 1500e3, 2000e3)) for (nom in listOf("QPSK 1/4", "QPSK 1/2", "QPSK 3/4", "QPSK 9/10", "8PSK 2/3")) {
            val m = Modcod.de(nom, pilotes = true)!!
            val fs = RecepteurS2.debitPour(rs.toInt()).toDouble()
            val secondes = maxOf(1.0, 12.0 * m.symboles / rs)
            val c = CanalS2(m, rs = rs, fs = fs, esN0Db = norme[nom]!! + 1, decalageHz = 5e3)
            val x = c.signal(secondes)
            val recu = Recu(c)
            val rx = RecepteurS2(c.fs, c.rs, recu.sortie, 4)
            val c0 = cpu(); val t0 = System.nanoTime()
            c.recois(x, rx)
            val mur = (System.nanoTime() - t0) / 1e9; val proc = (cpu() - c0) / 1e9
            val e = rx.etat()
            rx.ferme()
            println("TEMPS " + (rs / 1000).toInt() + " kS/s " + nom + " (fs " + (fs / 1e6) + " MS/s) : " + e.tramesDonnees + " trames, " + e.ldpcEchecs + " échecs, " +
                "mur %.2f s et CPU %.2f s par seconde de signal, itérations %.1f".format(mur / secondes, proc / secondes, e.iterationsMoyennes))
        }
    }

    companion object { const val TRAMES = 20 }
}
