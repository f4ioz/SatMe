/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The METEOR receiver without the phone: a minute of a real pass, from a WAV recording, to pictures. */
class RecepteurMeteorTest {

    private val source = File(System.getProperty("user.home"), "SatMe-atelier/meteor/meteor-m2-4-20260305-cf32.wav")

    /** Seconds [de]..[de]+[duree] of the recording, as a WAV of its own (cf32, its rate). */
    private fun extrait(de: Double, duree: Double): File {
        val w = RandomAccessFile(source, "r").use { RecepteurMeteor.Wav.lis(it.channel)!! }
        val octets = 8
        val premier = (de * w.fs).toLong()
        val n = (duree * w.fs).toLong()
        val f = File.createTempFile("meteor-", "-20260305-cf32.wav")
        RandomAccessFile(source, "r").use { src ->
            f.outputStream().buffered().use { o ->
                val data = n * octets
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(2).putInt(w.fs.toInt())
                    .putInt(w.fs.toInt() * octets).putShort(octets.toShort()).putShort(32)
                h.put("data".toByteArray()).putInt(data.toInt())
                o.write(h.array())
                src.seek(w.debutDonnees + premier * octets)
                val buf = ByteArray(1 shl 20)
                var reste = data
                while (reste > 0) {
                    val k = src.read(buf, 0, minOf(buf.size.toLong(), reste).toInt())
                    if (k <= 0) break
                    o.write(buf, 0, k); reste -= k
                }
            }
        }
        return f
    }

    @Test
    fun une_minute_d_un_vrai_passage_donne_les_images() {
        assumeTrue("enregistrement d'essai absent", source.isFile)
        val f = extrait(380.0, 60.0)
        try {
            val r = RecepteurMeteor()
            val images = RandomAccessFile(f, "r").use { r.decodeFichier(it.channel, "METEOR-M2-4 " + f.name, 0L) }
            assertTrue("trames : " + r.etat.trames, r.etat.trames > 100)
            assertTrue("lignes : " + r.etat.lignes, r.etat.lignes >= 80)
            assertTrue(images.isNotEmpty())
            val i = images.first()
            assertEquals(1568, i.image.largeur)
            assertTrue(i.image.hauteur >= 80)
            assertEquals(-1f, r.etat.progression)
            assertNotNull(r.apercu)
        } finally { f.delete() }
    }

    @Test
    fun les_voies_et_les_satellites() {
        assertTrue(RecepteurMeteor.emetLrpt(59051, "METEOR-M2 4"))
        assertTrue(RecepteurMeteor.emetLrpt(0, "METEOR-M2 5"))
        assertFalse(RecepteurMeteor.emetLrpt(40069, "METEOR-M 2"))
        assertEquals(1, RecepteurMeteor.choisitVoie(listOf("LRPT 80k" to 137_912_500L, "LRPT 72k" to 137_900_000L)))
        assertEquals(1772670060000L, RecepteurMeteor.dateDuNom("meteor_20260305_0021.wav"))
    }
}
