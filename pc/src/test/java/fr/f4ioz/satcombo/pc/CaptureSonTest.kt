/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.audio.SonPerdu
import fr.f4ioz.satcombo.sstv.SstvDecoder
import fr.f4ioz.satcombo.sstv.SstvEncoder
import fr.f4ioz.satcombo.sstv.SstvMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The capture with a made-up card ([LigneVirtuelle]) on a made-up clock: the
 * holes an overloaded PC makes are found, filled with as much silence, the
 * time after them kept; the cards' clock drift is never taken for one.
 */
class CaptureSonTest {

    /** What the capture handed out: every sample in order, and which were silence put in. */
    private class Sortie {
        val pcm = ArrayList<Short>()
        val comble = ArrayList<Boolean>()
        val trous = ArrayList<Pair<Int, Boolean>>()
        val pertes = ArrayList<SonPerdu.Perte>()
    }

    private fun capture(l: LigneVirtuelle, rate: Int, garde: Boolean = true, fileMs: Int = CaptureSon.FILE_MS): Pair<CaptureSon, Sortie> {
        val s = Sortie()
        val c = CaptureSon(l, rate, { pcm, n, _, comble -> if (garde) for (i in 0 until n) { s.pcm += pcm[i]; s.comble += comble } },
            trou = { n, comble -> s.trous += n to comble }, perte = { s.pertes += it }, nanos = l::nanos, fileMs = fileMs)
        return c to s
    }

    /** Read and handed out to the end, in one thread. */
    private fun tourne(c: CaptureSon) { while (c.pasLecture()) c.pasDistribution(0) ; while (c.enFile > 0) c.pasDistribution(0) }

    /** A signal whose every sample says where it is (never 0): i → i mod 20000 + 1. */
    private fun rampe(n: Int) = ShortArray(n) { (it % 20_000 + 1).toShort() }

    @Test
    fun la_derive_d_horloge_de_la_carte_n_est_pas_un_trou() {
        // Ten minutes at ±500 ppm: 300 ms of drift by the end, never a gap.
        for (ppm in listOf(-500.0, 300.0, 800.0)) {
            val rate = 8_000
            val l = LigneVirtuelle(ShortArray(rate * 600), rate, tamponMs = 1_000, ppm = ppm, jitterUs = 3_000)
            val (c, s) = capture(l, rate, garde = false)
            tourne(c)
            assertEquals("ppm $ppm", 0, c.trous.trous)
            assertTrue(s.pertes.isEmpty())
            assertTrue("ppm mesuré ${c.trous.ppm} pour $ppm", abs(c.trous.ppm - ppm) < 60)
        }
    }

    @Test
    fun un_lecteur_retenu_moins_que_le_tampon_ne_perd_rien() {
        val rate = 48_000
        val l = LigneVirtuelle(rampe(rate * 30), rate, tamponMs = CaptureSon.TAMPON_MS, stalls = mapOf(200 to 700, 400 to 900))
        val (c, s) = capture(l, rate)
        tourne(c)
        assertEquals(0L, l.jetes)
        assertEquals(0, c.trous.trous)
        assertEquals(rate * 30, s.pcm.size)
        assertTrue(s.comble.none { it })
    }

    @Test
    fun un_tampon_deborde_est_comble_de_silence_et_la_suite_garde_son_heure() {
        val rate = 48_000
        // The old 85 ms buffer, the reader held back 500 ms then 1.3 s: about 0.42 s and 1.2 s thrown.
        val l = LigneVirtuelle(rampe(rate * 40), rate, tamponMs = 85, stalls = mapOf(300 to 500, 600 to 1_300))
        val (c, s) = capture(l, rate)
        tourne(c)
        assertTrue(l.jetes > rate)
        assertEquals(2, c.trous.trous)
        assertEquals(2, s.trous.size)
        assertTrue(s.trous.all { it.second })
        // As much silence as was thrown, to within the threshold.
        val combles = s.comble.count { it }
        assertTrue("comblés $combles pour ${l.jetes} jetés", abs(combles - l.jetes) < rate * 0.02)
        // As long as the signal: the time it lasted.
        assertTrue(abs(s.pcm.size - rate * 40) < rate * 0.02)
        // Every real sample where it was on the air (within 20 ms), never one twice.
        var pire = 0L
        var precedent = -1L
        for (j in s.pcm.indices) {
            if (s.comble[j]) continue
            val v = s.pcm[j].toInt() - 1
            // The signal index nearest j with that value modulo 20000.
            val i = j - Math.floorMod(j - v, 20_000).let { if (it > 10_000) it - 20_000 else it }
            pire = maxOf(pire, abs(i - j).toLong())
            assertTrue("échantillon $i rendu deux fois", i > precedent)
            precedent = i.toLong()
        }
        assertTrue("décalé de $pire échantillons", pire < rate * 0.02)
        assertEquals(2, s.pertes.size)
        assertTrue(s.pertes.all { it.cause == SonPerdu.Cause.CARTE && it.comble })
    }

    @Test
    fun une_coupure_trop_longue_n_est_pas_comblee_pour_les_decodeurs() {
        val rate = 48_000
        val l = LigneVirtuelle(rampe(rate * 20), rate, tamponMs = 85, stalls = mapOf(100 to 3_500))
        val (c, s) = capture(l, rate)
        tourne(c)
        assertEquals(1, c.trous.coupures)
        assertTrue(s.comble.none { it })
        assertEquals(1, s.trous.size)
        assertTrue(!s.trous[0].second)
        assertTrue(abs(s.trous[0].first - l.jetes) < rate * 0.02)
        assertTrue(!s.pertes.single().comble)
    }

    @Test
    fun sans_le_niveau_du_tampon_rien_n_est_juge() {
        // A line that does not say what waits: a late reader would look like a hole, so none is guessed.
        val rate = 48_000
        val l = LigneVirtuelle(rampe(rate * 10), rate, tamponMs = 1_000, stalls = mapOf(50 to 600), avecAttente = false)
        val (c, s) = capture(l, rate)
        tourne(c)
        assertEquals(0, c.trous.trous)
        assertEquals(rate * 10, s.pcm.size)
    }

    @Test
    fun des_decodeurs_trop_lents_perdent_des_blocs_comptes_et_combles() {
        val rate = 48_000
        val l = LigneVirtuelle(rampe(rate * 6), rate, tamponMs = 1_000)
        // A queue of 0.5 s, nothing handed out for 2 s of reading: 1.5 s dropped behind the decoders.
        val (c, s) = capture(l, rate, fileMs = 500)
        var k = 0
        while (c.pasLecture()) { if (++k > (rate * 2) / 2048) c.pasDistribution(0) }
        while (c.enFile > 0) c.pasDistribution(0)
        assertTrue(c.blocsJetes > 0)
        // Exactly the samples dropped, as silence: the output as long as the input.
        assertEquals(rate * 6, s.pcm.size)
        val p = s.pertes.single()
        assertEquals(SonPerdu.Cause.FILE, p.cause)
        assertTrue(p.comble)
        assertTrue(abs(p.ms - c.blocsJetes * 2048 * 1000 / rate) <= 1)
    }

    // ------------------------------------------------------------------ SSTV

    /** Vertical bars, swapped every 20 rows: a time slip shows as rows out of place. */
    private fun mire(m: SstvMode): IntArray {
        val bars = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000)
        return IntArray(m.width * m.height) { i ->
            val x = i % m.width; val y = i / m.width
            val k = x * 8 / m.width
            0xFF000000.toInt() or bars[if ((y / 20) % 2 == 0) k else 7 - k]
        }
    }

    private fun luma(p: Int) = ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10

    /** Each row's mean error against the picture sent (0..255). */
    private fun erreurs(px: IntArray, ref: IntArray, m: SstvMode, lignes: Int): IntArray = IntArray(lignes) { y ->
        var e = 0L
        for (x in 0 until m.width) e += abs(luma(px[y * m.width + x]) - luma(ref[y * m.width + x]))
        (e / m.width).toInt()
    }

    private fun decode(pcm: ShortArray, rate: Int): Pair<IntArray, Int>? {
        var r: Pair<IntArray, Int>? = null
        val d = SstvDecoder(rate, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                if (r == null || linesDone > r!!.second) r = pixels.copyOf() to linesDone
            }
        })
        d.continuous = true
        var i = 0
        while (i < pcm.size) { val n = minOf(2048, pcm.size - i); d.feed(pcm.copyOfRange(i, i + n), n); i += n }
        d.finish()
        return r
    }

    @Test
    fun une_image_sstv_avec_un_trou_garde_ses_lignes_a_leur_place() {
        val rate = 48_000
        val m = SstvMode.byName("Robot 36")!!
        val ref = mire(m)
        val signal = SstvEncoder.encode(m, ref, rate, leadMs = 500.0, trailMs = 500.0) + ShortArray(rate)
        // Held back 700 ms around row 100 with the old 85 ms buffer: ~0.6 s (4 rows) thrown.
        val bloc = 2048
        val lecture = ((0.5 + 0.9 + 100 * m.blockMs / 1000.0) * rate / bloc).toInt()
        val l = LigneVirtuelle(signal, rate, tamponMs = 85, stalls = mapOf(lecture to 700))
        val (c, s) = capture(l, rate)
        // What the capture read, without the silence: as before this version.
        val brut = ArrayList<Short>(signal.size)
        val c2 = CaptureSon(LigneVirtuelle(signal, rate, tamponMs = 85, stalls = mapOf(lecture to 700)), rate,
            { pcm, n, _, comble -> if (!comble) for (i in 0 until n) brut += pcm[i] })
        tourne(c)
        tourne(c2)
        assertTrue(l.jetes > rate / 2)

        val (avec, nA) = decode(s.pcm.toShortArray(), rate)!!
        val (sans, nS) = decode(brut.toShortArray(), rate)!!
        val eA = erreurs(avec, ref, m, minOf(nA, m.height))
        val eS = erreurs(sans, ref, m, minOf(nS, m.height))
        val trouLignes = (l.jetes * 1000.0 / rate / m.blockMs).toInt() + 2
        // After the hole: the rows in their place with the silence put in…
        val apresA = (100 + trouLignes + 4 until minOf(nA, m.height - 4)).map { eA[it] }
        val apresS = (100 + trouLignes + 4 until minOf(nS, m.height - 4)).map { eS[it] }
        // The band of the hole: plain (no picture torn across it), only where the sound was lost.
        val bande = (0 until minOf(nA, m.height)).filter { eA[it] > 10 }
        fun ecartType(y: Int): Double { val v = (0 until m.width).map { luma(avec[y * m.width + it]).toDouble() }; val moy = v.average(); return Math.sqrt(v.sumOf { (it - moy) * (it - moy) } / v.size) }
        val ecart = bande.drop(1).dropLast(1).maxOfOrNull { ecartType(it) } ?: 0.0
        println("SSTV : trou ${l.jetes * 1000 / rate} ms ; lignes touchées $bande (écart-type ${"%.1f".format(ecart)}) ; " +
            "erreur après le trou : comblé ${apresA.average()}, sans ${apresS.average()}")
        assertEquals(m.height, nA)
        assertTrue(bande.isNotEmpty() && bande.size <= trouLignes + 2 && bande.first() >= 96 && bande.last() <= 100 + trouLignes + 2)
        assertTrue("bande pas unie : $ecart", ecart < 25)
        assertTrue(apresA.average() < 2.0)
        // …and without it, every row after the hole is shifted (the « déchirure »).
        assertTrue(apresS.average() > 20.0)
    }
}
