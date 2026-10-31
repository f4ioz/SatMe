/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Before and after, in real time with the processors kept busy: a made-up
 * card captured as before this version (an 85 ms buffer, the decoders run
 * on the capture thread) and as now ([CaptureSon]: a second of buffer, the
 * decoders on their own thread behind a queue), the same slow decoders (a
 * 300 ms burst of work every second, as an OCR or a picture's denoising).
 */
class CaptureSonChargeTest {

    /** A card in real time: samples made by the clock, a buffer of [tamponMs] thrown away whole when it overruns. */
    private class LigneTempsReel(private val rate: Int, tamponMs: Int, private val dureeS: Int) : LigneCapture {
        private val t0 = System.nanoTime()
        private var lu = 0L
        var jetes = 0L; private set
        private val capacite = rate.toLong() * tamponMs / 1000
        @Volatile private var ferme = false
        private fun produits() = (System.nanoTime() - t0) * rate / 1_000_000_000L
        override fun lit(b: ByteArray, off: Int, len: Int): Int {
            if (ferme || lu >= rate.toLong() * dureeS) return -1
            if (produits() - lu > capacite) { val a = produits() - lu; jetes += a; lu += a }
            val k = len / 2
            while (produits() - lu < k && !ferme) LockSupport.parkNanos(1_000_000L)
            for (i in 0 until k) { b[off + 2 * i] = 1; b[off + 2 * i + 1] = 0 }
            lu += k
            return 2 * k
        }
        override fun enAttente(): Int = ((produits() - lu).coerceAtLeast(0L) * 2).toInt()
        override val tampon: Int get() = (capacite * 2).toInt()
        override fun ferme() { ferme = true }
    }

    /** The slow decoders: a little work per block, a 300 ms burst every second of sound. */
    private class Decodeurs(private val rate: Int) {
        private var recus = 0L
        var x = 0.0
        fun bloc(n: Int) {
            for (i in 0 until n) x += Math.sin(i.toDouble())
            val avant = recus / rate
            recus += n
            if (recus / rate != avant) { val fin = System.nanoTime() + 300_000_000L; while (System.nanoTime() < fin) x += Math.sqrt(x + 1) }
        }
    }

    private fun avant(rate: Int, s: Int): Long {
        val l = LigneTempsReel(rate, 85, s)
        val d = Decodeurs(rate)
        val o = ByteArray(CaptureSon.BLOC_OCTETS)
        // As before: read, then every decoder, on the same thread.
        while (true) { val n = l.lit(o, 0, o.size); if (n < 0) break; d.bloc(n / 2) }
        return l.jetes
    }

    private fun apres(rate: Int, s: Int): Pair<Long, Int> {
        val l = LigneTempsReel(rate, CaptureSon.TAMPON_MS, s)
        val d = Decodeurs(rate)
        val c = CaptureSon(l, rate, { _, n, _, _ -> d.bloc(n) }).demarre("satme-son-essai")
        while (c.marche) Thread.sleep(20)
        c.arrete()
        return l.jetes to c.trous.trous
    }

    @Test
    fun sous_charge_la_capture_ne_perd_plus_rien() {
        val rate = 48_000
        val s = 4
        val n = Runtime.getRuntime().availableProcessors()
        val charge = Executors.newFixedThreadPool(n)
        val fini = AtomicBoolean(false)
        repeat(n) { charge.submit { var x = 1.0; while (!fini.get()) x = Math.sqrt(x + 1.0); x } }
        try {
            val a = avant(rate, s)
            val (b, trous) = apres(rate, s)
            println("Sous charge ($n fils occupés) : avant ${a * 1000 / rate} ms perdus sur ${s * 1000} ms, " +
                "maintenant ${b * 1000 / rate} ms ($trous trou(s) vu(s))")
            assertTrue("avant : $a", a > rate / 2)
            assertTrue("maintenant : $b", b < a / 4)
        } finally {
            fini.set(true); charge.shutdownNow(); charge.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
