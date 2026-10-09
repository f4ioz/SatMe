/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.meteor.Convolutif
import fr.f4ioz.satcombo.meteor.Derandomise
import fr.f4ioz.satcombo.meteor.ReedSolomon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** The CCSDS codes METEOR uses: each one against itself, and the known sequence. */
class MeteorCodesTest {

    @Test
    fun la_sequence_de_derandomisation_est_celle_du_ccsds() {
        assertEquals("ff480ec09a0d70bc8e2c93ada7b746ce",
            Derandomise.SEQUENCE.take(16).joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        assertEquals(255, Derandomise.SEQUENCE.size)
    }

    @Test
    fun reed_solomon_corrige_jusqu_a_seize_octets() {
        val r = Random(7)
        repeat(50) { essai ->
            val d = IntArray(ReedSolomon.K) { r.nextInt(256) }
            val c = d + ReedSolomon.code(d)
            assertEquals(0, ReedSolomon.corrige(c.copyOf()))
            val abime = c.copyOf()
            val n = essai % 17
            val pos = (0 until ReedSolomon.N).shuffled(r).take(n)
            for (p in pos) abime[p] = abime[p] xor (1 + r.nextInt(255))
            assertEquals(n, ReedSolomon.corrige(abime))
            assertArrayEquals(c, abime)
        }
        // Seventeen: refused, not "corrected" wrong.
        val d = IntArray(ReedSolomon.K) { it }
        val c = d + ReedSolomon.code(d)
        for (p in 0 until 17) c[p * 13] = c[p * 13] xor 0x55
        assertEquals(-1, ReedSolomon.corrige(c))
    }

    @Test
    fun viterbi_retrouve_les_bits_malgre_le_bruit() {
        val r = Random(3)
        val g = java.util.Random(3)
        val bits = IntArray(20_000) { r.nextInt(2) }
        val codes = IntArray(bits.size * 2)
        Convolutif.code(bits, codes)
        val v = Convolutif.Decodeur()
        val sortie = IntArray(bits.size)
        var n = 0
        var erreursCanal = 0
        for (i in bits.indices) {
            fun bruite(b: Int): Int {
                val s = (if (b == 1) 60 else -60) + (g.nextGaussian() * 35).toInt()
                if ((s > 0) != (b == 1)) erreursCanal++
                return s.coerceIn(-127, 127)
            }
            v.pas(bruite(codes[2 * i]), bruite(codes[2 * i + 1]))
            if (i % 1000 == 999) n += v.lis(sortie, n)
        }
        n += v.lis(sortie, n, final = true)
        assertEquals(bits.size, n)
        val erreurs = bits.indices.count { bits[it] != sortie[it] }
        // About 4 % of the symbols arrive wrong (Eb/N0 ≈ 4.7 dB); nearly every bit comes out right.
        assertEquals(true, erreursCanal > 1_200)
        assertEquals("erreurs $erreurs aux positions ${bits.indices.filter { bits[it] != sortie[it] }.take(30)}", true, erreurs < 10)
    }

    @Test
    fun les_tables_de_l_image_sont_celles_du_jpeg() {
        assertEquals(162, fr.f4ioz.satcombo.meteor.MsuMr.AC_VAL.size)
        // Quality 50 is the table as published; 100 is all ones.
        assertEquals(16, fr.f4ioz.satcombo.meteor.MsuMr.tableQuantif(50)[0])
        assertEquals(1, fr.f4ioz.satcombo.meteor.MsuMr.tableQuantif(100).maxOrNull())
    }

    @Test
    fun seuls_les_meteor_qui_emettent_sont_retenus() {
        val h = fr.f4ioz.satcombo.meteor.MeteorHub
        org.junit.Assert.assertTrue(h.emetLrpt(57166, "METEOR-M2 3"))
        org.junit.Assert.assertTrue(h.emetLrpt(59051, "METEOR-M2 4"))
        org.junit.Assert.assertTrue(h.emetLrpt(99999, "METEOR-M2 5"))
        org.junit.Assert.assertFalse(h.emetLrpt(40069, "METEOR M2"))
        org.junit.Assert.assertFalse(h.emetLrpt(44387, "METEOR-M2 2"))
        org.junit.Assert.assertFalse(h.emetLrpt(25544, "ISS (ZARYA)"))
    }

    @Test
    fun la_date_se_lit_dans_le_nom_du_fichier() {
        val h = fr.f4ioz.satcombo.meteor.MeteorHub
        assertEquals(1772670060000L, h.dateDuNom("meteor-m2-4-202603050021-BP51fn-baseband-cf32.wav"))
        assertEquals(1772668800000L, h.dateDuNom("meteor-m2-4-20260305-cf32.wav"))
        assertEquals(null, h.dateDuNom("passage.wav"))
    }
}
