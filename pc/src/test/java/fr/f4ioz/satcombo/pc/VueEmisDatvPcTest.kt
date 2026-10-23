/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** The picture being transmitted, as the page sees it: a copy of the stream that never waits. */
class VueEmisDatvPcTest {

    /** A decoder that keeps what it is given; its pictures written by the test. */
    private class FauxDecodeur(val bloque: Boolean = false) : VueEmisDatvPc.Decodeur {
        val recu = ByteArrayOutputStream()
        val images = PipedOutputStream()
        override val sortie: InputStream = PipedInputStream(images, 1 shl 16)
        @Volatile var arrete = false
        override val vivant: Boolean get() = !arrete
        override val entree: OutputStream = object : OutputStream() {
            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                // A decoder that does not keep up: it never takes anything.
                if (bloque) Thread.sleep(60_000)
                synchronized(recu) { recu.write(b, off, len) }
            }
        }
        override fun arrete() { arrete = true; runCatching { images.close() } }
        fun octets() = synchronized(recu) { recu.size() }
    }

    private fun paquet(pid: Int, n: Int = 0) = ByteArray(188).also { it[0] = 0x47; it[1] = (pid shr 8).toByte(); it[2] = pid.toByte(); it[4] = n.toByte() }

    private fun attends(ms: Long = 3000, c: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (c()) return true; Thread.sleep(10) }
        return c()
    }

    @Test
    fun rienSansEmissionNiDemande() {
        val crees = AtomicInteger()
        val v = VueEmisDatvPc({ crees.incrementAndGet(); FauxDecodeur() })
        // Not transmitting: the page's request starts nothing.
        assertNull(v.demande())
        assertEquals(0, crees.get())
        v.emission(true)
        // Transmitting, nobody looking: the packets ignored, no decoder.
        repeat(10) { v.paquet(paquet(256)) }
        assertEquals(0, crees.get())
        assertFalse(v.actif)
    }

    @Test
    fun copieLesPaquetsVusSansLesNuls() {
        val d = FauxDecodeur()
        val v = VueEmisDatvPc({ d })
        v.emission(true)
        v.demande()
        assertTrue(v.actif)
        repeat(5) { v.paquet(paquet(256, it)) }
        repeat(5) { v.paquet(paquet(0x1FFF)) }
        v.paquet(ByteArray(100))
        assertTrue(attends { d.octets() == 5 * 188 })
        Thread.sleep(100)
        assertEquals(5 * 188, d.octets())
    }

    @Test
    fun lesImagesDuDecodeurVontALaPage() {
        val d = FauxDecodeur()
        val v = VueEmisDatvPc({ d })
        v.emission(true)
        v.demande()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        d.images.write(byteArrayOf(9, 9) + jpeg); d.images.flush()
        assertTrue(attends { v.images == 1L })
        assertArrayEquals(jpeg, v.demande())
    }

    @Test
    fun neBloqueJamaisLEmission() {
        val d = FauxDecodeur(bloque = true)
        val v = VueEmisDatvPc({ d }, capacite = 16)
        v.emission(true)
        v.demande()
        val t0 = System.nanoTime()
        repeat(5000) { v.paquet(paquet(256)) }
        // A decoder stuck: the packets left out of the copy, at once.
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000)
        assertTrue(v.ecartes > 4000)
    }

    @Test
    fun arreteSansRegardNiEmission() {
        var t = 0L
        val d = FauxDecodeur()
        val v = VueEmisDatvPc({ d }, horloge = { t }, repos = 10_000L)
        v.emission(true)
        v.demande()
        t = 5_000; v.tic()
        assertTrue(v.actif)
        // Nobody asked for 10 s: stopped.
        t = 16_000; v.tic()
        assertFalse(v.actif)
        assertTrue(attends { d.arrete })
        // Asked again: a new decoder.
        val d2 = FauxDecodeur()
        val v2 = VueEmisDatvPc({ d2 })
        v2.emission(true); v2.demande()
        assertTrue(v2.actif)
        // The transmission's end stops it and forgets the picture.
        v2.emission(false)
        assertFalse(v2.actif)
        assertNull(v2.image)
        assertNull(v2.demande())
        assertFalse(v2.actif)
    }

    @Test
    fun decodeurAbsentReessayeToutesLesCinqSecondes() {
        var t = 1L
        val crees = AtomicInteger()
        val v = VueEmisDatvPc({ crees.incrementAndGet(); null }, horloge = { t })
        v.emission(true)
        v.demande(); v.demande()
        assertEquals(1, crees.get())
        t = 6_001; v.demande()
        assertEquals(2, crees.get())
    }

    @Test
    fun commandeBassePriorite() {
        val c = VueEmisDatvPc.DecodeurFfmpeg.commande("ffmpeg", "/usr/bin/nice")
        assertEquals(listOf("/usr/bin/nice", "-n", "10", "ffmpeg"), c.take(4))
        assertTrue(c.containsAll(listOf("-threads", "1", "pipe:0", "pipe:1", "image2pipe")))
        assertTrue(c.any { it.startsWith("fps=5,scale=480") })
        assertEquals("ffmpeg", VueEmisDatvPc.DecodeurFfmpeg.commande("ffmpeg").first())
    }
}
