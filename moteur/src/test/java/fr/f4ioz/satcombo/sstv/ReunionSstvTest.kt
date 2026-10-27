/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** « Réunir »: two partial pictures that are one picture cut in two, put back together. */
class ReunionSstvTest {

    private val fs = 12_000
    private val r36 = SstvMode.byName("Robot 36")!!
    private val bande = 10

    private fun gris(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun valeur(y: Int) = 30 + ((y / bande) * 67) % 200
    private fun mire(): IntArray = IntArray(r36.width * r36.height) { gris(valeur(it / r36.width)) }

    private fun shot(nom: String, t: Long, complete: Boolean = false, sat: String = "UmKA 1 (RS40S)", mode: String = "Robot 36") =
        SstvMeta.SstvShot(fileName = nom, satName = sat, timeMs = t, mode = mode, complete = complete, source = "live")

    @Test
    fun deux_partielles_de_suite_sont_une_paire_et_pas_le_reste() {
        val t = 1_793_136_103_325L
        val a = shot("a.png", t); val b = shot("b.png", t + 21_170)
        assertTrue(ReunionSstv.peutReunir(a, b))
        // A copy decoded again from a's sound (another source) between them does not break the pair.
        val r2 = a.copy(fileName = "a_r2.png", timeMs = t + 500, source = "file")
        assertEquals(listOf("a.png" to "b.png"), ReunionSstv.paires(listOf(b, r2, a)).map { it.first.fileName to it.second.fileName })
        assertEquals("b.png", ReunionSstv.partenaire(listOf(a, b), "a.png")?.fileName)
        // A complete picture, another mode, another satellite, too far apart, already put together: not.
        assertTrue(!ReunionSstv.peutReunir(a, shot("c.png", t + 20_000, complete = true)))
        assertTrue(!ReunionSstv.peutReunir(a, shot("c.png", t + 20_000, mode = "PD 120")))
        assertTrue(!ReunionSstv.peutReunir(a, shot("c.png", t + 20_000, sat = "ISS")))
        assertTrue(!ReunionSstv.peutReunir(a, shot("c.png", t + ReunionSstv.ecartMaxMs(r36) + 1)))
        assertTrue(!ReunionSstv.peutReunir(a, b.copy(reunieDans = "x.png")))
        // Three partial ones in a row: one pair, the earliest.
        assertEquals(1, ReunionSstv.paires(listOf(a, b, shot("c.png", t + 40_000))).size)
    }

    @Test
    fun deux_morceaux_sans_son_sont_places_par_leur_heure() {
        val ref = mire()
        val w = r36.width
        val pa = IntArray(w * r36.height) { ReunionSstv.GRIS }.also { System.arraycopy(ref, 0, it, 0, w * 100) }
        // The second one began at row 150: its row 0 is the picture's 150.
        val pb = IntArray(w * r36.height) { ReunionSstv.GRIS }.also { System.arraycopy(ref, w * 150, it, 0, w * 70) }
        val a = shot("a.png", 1000L).copy(ligne0Ms = 1_000_000L)
        val b = shot("b.png", 2000L).copy(ligne0Ms = 1_000_000L + (150 * r36.blockMs).toLong() + 20)
        val d = ReunionSstv.decalageTemps(r36, a, b)
        assertEquals(150, d)
        val r = ReunionSstv.reunit(r36, ReunionSstv.Morceau(pa, 100), ReunionSstv.Morceau(pb, 70), null, d)
        assertNotNull(r); r!!
        assertEquals(220, r.lignes)
        assertEquals(listOf(100..149), r.trous)
        for (y in listOf(0, 50, 99, 150, 180, 219)) assertEquals("rang $y", ref[y * w + 5], r.px[y * w + 5])
        assertEquals(ReunionSstv.GRIS, r.px[120 * w + 5])
    }

    private fun bruit(n: Int, seed: Long, niveau: Double): ShortArray {
        val r = java.util.Random(seed)
        return ShortArray(n) { (r.nextGaussian() * niveau).toInt().coerceIn(-32767, 32767).toShort() }
    }

    /** The live decoder's pictures in [x] (continuous decoding). */
    private fun direct(x: ShortArray): List<Pair<IntArray, Int>> {
        val out = ArrayList<Pair<IntArray, Int>>()
        val d = SstvDecoder(fs, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) { out += pixels.copyOf() to linesDone }
        }).also { it.continuous = true }
        var i = 0
        while (i < x.size) { val k = minOf(2048, x.size - i); d.feed(x.copyOfRange(i, i + k), k); i += k }
        d.finish()
        return out
    }

    @Test
    fun deux_partielles_avec_leur_son_redeviennent_une_image() {
        // A picture whose second half came out a picture of its own: the top's sound and the bottom's, overlapping
        // by a second as two pictures' own sounds do; the bottom decoded from its first pulses (no header).
        val son = bruit(fs / 2, 1, 300.0) + SstvEncoder.encode(r36, mire(), fs) + bruit(fs, 2, 300.0)
        val debut0 = fs / 2 + ((120.0 + SstvDecoder.VIS_MS) * fs / 1000).toInt()
        val coupe = debut0 + (110 * r36.blockMs * fs / 1000).toInt()
        val sa = son.copyOfRange(0, coupe + fs / 2)
        val sb = son.copyOfRange(coupe - fs / 2, son.size)
        val a = direct(sa).single(); val b = direct(sb).single()
        assertTrue("haut ${a.second}", a.second in 100..115)
        assertTrue("bas ${b.second}", b.second >= 110)
        val t0 = 1_793_136_000_000L
        val sonA = ReunionSstv.Son(sa, fs, t0)
        // Its time is only known roughly: a second and a half off.
        val sonB = ReunionSstv.Son(sb, fs, t0 + (coupe - fs / 2) * 1000L / fs + 1500)
        val tout = ReunionSstv.colle(sonA, sonB)
        assertEquals("le son recollé à l'échantillon près", son.size, tout.pcm.size)
        val r = ReunionSstv.reunit(r36, ReunionSstv.Morceau(a.first, a.second), ReunionSstv.Morceau(b.first, b.second), tout, null)
        assertNotNull(r); r!!
        assertEquals(r36.height, r.lignes)
        assertTrue(r.complete)
        // Its row 0: the line after the four pulses it was recognised on.
        assertTrue("le bas placé à ${r.placeB}", r.placeB in 108..120)
        val w = r36.width
        val mal = (2 until r36.height - 2).filter { y ->
            var s = 0; for (x in w / 4 until 3 * w / 4) s += (r.px[y * w + x] shr 8) and 0xFF
            val g = s / (w / 2)
            (-1..1).none { dd -> abs(g - valeur((y + dd).coerceIn(0, r36.height - 1))) < 18 }
        }
        assertTrue("lignes mal placées : $mal", mal.size <= 3)
    }
}
