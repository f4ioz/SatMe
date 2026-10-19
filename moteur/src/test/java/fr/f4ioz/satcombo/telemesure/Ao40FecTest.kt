/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.meteor.Derandomise
import fr.f4ioz.satcombo.meteor.ReedSolomon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ao40FecTest {

    /** The sync vector as printed in the coded AO-40 format's description. */
    @Test fun vecteurDeSynchroPublie() {
        assertEquals("11111110000111011110010110010010000001000100110001011101011011000", Ao40Fec.SYNC.joinToString(""))
    }

    /** The CCSDS PN sequence starts FF 48 0E C0 9A (its standard's table). */
    @Test fun sequencePseudoAleatoire() {
        assertEquals(listOf(0xFF, 0x48, 0x0E, 0xC0, 0x9A), (0 until 5).map { Derandomise.SEQUENCE[it].toInt() and 0xFF })
    }

    /**
     * The generator polynomial's coefficients as powers of α, published in
     * the format's description (G1 = G31 = 249, G2 = 59 …, G16 = 24): the
     * parity of a single 1 in the last data byte is x³² mod g(x), i.e. g's
     * lower coefficients, highest first.
     */
    @Test fun polynomeGenerateurPublie() {
        val log = IntArray(256)
        var x = 1
        for (i in 0 until 255) { log[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x187 }
        val d = IntArray(223); d[222] = 1
        val p = ReedSolomon.code(d)
        val publie = intArrayOf(0, 249, 59, 66, 4, 43, 126, 251, 97, 30, 3, 213, 50, 66, 170, 5, 24)
        // p[0] is G31, … p[31] is G0.
        for (j in 0..31) {
            val g = if (j <= 16) publie[j] else publie[32 - j]
            assertEquals("G$j", g, log[p[31 - j]])
        }
    }

    @Test fun allerRetourSansBruit() {
        val d = ByteArray(256) { (it * 7 + 3).toByte() }
        val bits = Ao40Fec.code(d)
        assertEquals(5200, bits.size)
        for (i in 0 until 65) assertEquals(Ao40Fec.SYNC[i], bits[i * 80])
        val soft = FloatArray(5200) { if (bits[it] == 1) 1f else -1f }
        val r = Ao40Fec.decode(soft)!!
        assertArrayEquals(d, r.donnees)
        assertEquals(0, r.octetsCorriges)
        assertEquals(0, r.symbolesFaux)
    }

    @Test fun corrigeUneRafaleEtDuBruit() {
        val d = SignalEssai.texteBalise("N HI de essai SatMe", 256)
        val bits = Ao40Fec.code(d)
        val rnd = java.util.Random(4)
        val soft = FloatArray(5200) { (if (bits[it] == 1) 1f else -1f) + 0.7f * rnd.nextGaussian().toFloat() }
        // A fade: 600 consecutive symbols lost (spread over many rows by the block).
        for (k in 2000 until 2600) soft[k] = 0.05f * rnd.nextGaussian().toFloat()
        val r = Ao40Fec.decode(soft)!!
        assertArrayEquals(d, r.donnees)
        assertTrue(r.symbolesFaux > 100)
    }

    @Test fun refuseLeBruit() {
        val rnd = java.util.Random(9)
        assertNull(Ao40Fec.decode(FloatArray(5200) { rnd.nextGaussian().toFloat() }))
    }

    /**
     * A coded frame of the QO-100 middle beacon as received on 18/10/2026
     * (hard decisions, in the order sent, 650 bytes), 300 bits then flipped.
     */
    @Test fun trameReelleDeLaBaliseQo100() {
        val hex = "DAD5B3B5AEBA56FC9CC68BBF2056E4EDFADD2A9EF010379BD90BB5B6E503C5DC5053FEF32EFAE566ABC1C653877082931537" +
            "F4A4174933A75768F85BF7029DB7E45E0708163D762EE90505AF779B9B73116DBB7DA5CA00E94DF30C6EFAC2436ED987B422" +
            "305615ED997AEBF515458FA12E0F46CEF20A18E9B2E4F6EAE8AE8F9FCE86DF6F14C5671E18AA0CF6732CA319C86B580B25C7" +
            "CD6F99CDCBB86CB215618A7D37BC966D3EB4A10AE07E64EC2AA75BE2DD99F526F46CD43395665D6629A6F8E3C999488F4743" +
            "62174273F7EBDF550A26C559D0B3A3E0295118113C2782FFC56A114520D29F313D4E8787C9344A88F06EF62BF09F1FAFC2B7" +
            "574B63E3249F673F30674E69A9AE469E51A651C9F43B35A31C24E71475B060E1751293A5AA6206DC7A19B02B06686A70BF6C" +
            "BA80569D58320DA07D33168CF47267D45693790254E3ADDAB54B65E9F9392E59EDF6D35CDFC47A6073082393A83B9A04FF8E" +
            "45A897FC9C634642457D27FC091D15A096E6BF21A05317919F71CF59A3F84E729801B3E70477B6FD2DF56DB9EA93ECE2F9FF" +
            "5CBABF4C98D6A44D170EBDC7B66D7664DD1BDB8F51F7A12E89D6F975236E0780A4357B65FE6BA9478F8102DB1659828B6168" +
            "D41EC637412318D56A2C71F3B63D82625BA77278746E9497036580E7AE594D45E4DCFAC477981EE0A863C2649009C8A2CE07" +
            "30B4FD8ABC0F4DDE17C88617C681EC86DF4DA373D946B428C4B70D94D787F03FA36BD1CAE9D7CA2219DB146BAFE6B2BBCEDF" +
            "94E0D8A67D5FA62D54173D080FAC407AD8A96F1FC303DCCCFA143458BE77F6C74CEF87E94D3BAD870C295A0CB687F0BBA181" +
            "91446B38A165194FE7B3C49AA07ED6AA36223FC829CD8ABF082DDC4171C451A0BDBE9464D148AFA00444F92FE0574D7BCEDE"
        assertEquals(1300, hex.length)
        val bits = IntArray(5200) { (hex[it / 4].digitToInt(16) shr (3 - it % 4)) and 1 }
        // The sync is there, untouched.
        for (i in 0 until 65) assertEquals(Ao40Fec.SYNC[i], bits[i * 80])
        val rnd = java.util.Random(1)
        val soft = FloatArray(5200) { if (bits[it] == 1) 1f else -1f }
        val faux = HashSet<Int>()
        while (faux.size < 300) faux += rnd.nextInt(5200)
        for (k in faux) soft[k] = -soft[k]
        val r = Ao40Fec.decode(soft)!!
        val texte = String(r.donnees, Charsets.US_ASCII)
        assertTrue(texte, texte.startsWith("N HI de Qatar-OSCAR 100 (DK0SB)"))
        assertTrue(texte, texte.contains("international emergency frequency on QO-100"))
        assertTrue("${r.symbolesFaux}", r.symbolesFaux in 290..300)
    }

    @Test fun blocP3CrcEtRecherche() {
        val b = SignalEssai.texteBalise("K HI de essai", 512)
        val bits = BlocP3.code(b)
        val recus = ArrayList<Pair<ByteArray, Boolean>>()
        val c = BlocP3.Chercheur { o, ok -> recus += o to ok }
        val rnd = java.util.Random(2)
        repeat(100) { c.pousse(rnd.nextInt(2)) }
        // Inverted polarity and one wrong sync bit: still found.
        bits.forEachIndexed { i, v -> c.pousse((v xor 1) xor (if (i == 5) 1 else 0)) }
        assertEquals(1, recus.size)
        assertTrue(recus[0].second)
        assertArrayEquals(b, recus[0].first)
        assertEquals("K HI de essai", BlocP3.texte(recus[0].first).first())
    }
}
