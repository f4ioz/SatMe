/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The pass's sound decoded into a file, block by block, must give the very
 * samples the in-memory version gave (copied here as the reference, as it
 * was in JournalRendu until 11/10).
 */
class SonPassageTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "sonpassage-" + System.nanoTime())

    /** Recordings: a file name stands for its samples and rate. */
    private val sons = HashMap<String, Pair<ShortArray, Int>>()
    private fun enregistrement(nom: String, secondes: Double, rate: Int, graine: Int): File {
        val r = Random(graine)
        val n = (secondes * rate).toInt()
        // Some silent stretches, so that "non-silent wins" is exercised.
        val pcm = ShortArray(n) { i -> if ((i / 5000) % 4 == 3) 0 else (r.nextInt(-20000, 20000)).toShort() }
        sons[nom] = pcm to rate
        return File(dossier, nom)
    }

    /** Hands a recording out in blocks of uneven sizes, as a real decoder does. */
    private val decodeur = SonPassage.Decodeur { f, sortie ->
        val (pcm, rate) = sons.getValue(f.name)
        val r = Random(f.name.hashCode())
        var pos = 0
        while (pos < pcm.size) {
            val n = minOf(r.nextInt(300, 5000), pcm.size - pos)
            val bloc = pcm.copyOfRange(pos, pos + n)
            pos += n
            if (!sortie(bloc, n, rate)) break
        }
    }

    // ------------------------------------------------ the in-memory reference

    private fun extraitSonBrut(f: File, deMs: Long, aMs: Long): Pair<ShortArray, Int>? {
        var out: ShortArray? = null; var n = 0; var rateSortie = 22050
        var lus = 0L; var facteur = 1; var debut = 0L; var fin = 0L
        var somme = 0; var dansSomme = 0
        decodeur.decode(f) { pcm, count, rate ->
            if (out == null) {
                facteur = (rate / 22050.0).let { kotlin.math.round(it).toInt() }.coerceAtLeast(1)
                rateSortie = rate / facteur
                debut = deMs.coerceAtLeast(0) * rate / 1000; fin = aMs * rate / 1000
                out = ShortArray(((fin - debut) / facteur + 1).toInt().coerceIn(1, 40_000_000))
            }
            val o = out!!
            for (i in 0 until count) {
                val k = lus + i
                if (k < debut || k >= fin) continue
                somme += pcm[i]; dansSomme++
                if (dansSomme == facteur) { if (n < o.size) o[n++] = (somme / facteur).toShort(); somme = 0; dansSomme = 0 }
            }
            lus += count
            lus < fin
        }
        val o = out ?: return null
        return if (n < rateSortie) null else o.copyOf(n) to rateSortie
    }

    private fun extraitSon(f: File, de0: Long, aMs: Long, annonceMs: Long): Pair<ShortArray, Int>? {
        val deMs = maxOf(de0, annonceMs)
        val silenceMs = deMs - de0
        val s = extraitSonBrut(f, deMs, aMs) ?: return null
        if (silenceMs <= 0) return s
        return (ShortArray((silenceMs * s.second / 1000).toInt()) + s.first) to s.second
    }

    private fun sonMorceaux(morceaux: List<SonPassage.Morceau>, de: Long, a: Long): Pair<ShortArray, Int>? {
        var rate = 0
        var out: ShortArray? = null
        for (m in morceaux.sortedBy { it.origine }) {
            if (m.origine + m.annonce >= a || m.origine + m.dureeMs <= de) continue
            val (p, r) = extraitSon(m.f, de - m.origine, minOf(a, m.origine + m.dureeMs) - m.origine, m.annonce) ?: continue
            if (out == null) { rate = r; out = ShortArray(((a - de) * r / 1000).toInt()) }
            val o = out!!
            for (i in o.indices) {
                val k = (i.toLong() * r / rate).toInt()
                if (k >= p.size) break
                if (p[k].toInt() != 0) o[i] = p[k]
            }
        }
        return out?.let { it to rate }
    }

    private fun sonAccelere(pcm: ShortArray, rate: Int, debutMs: Long, segs: List<JournalPassage.Segment>): ShortArray {
        val out = ShortArray((JournalPassage.dureeLecture(segs) * rate / 1000).toInt() + rate)
        var n = 0
        for (s in segs) {
            val longueur = ((s.aMs - s.deMs) / s.vitesse * rate / 1000).toInt()
            if (s.vitesse == 1) {
                val de = ((s.deMs - debutMs) * rate / 1000).toInt()
                for (i in 0 until longueur) { val k = de + i; if (k in pcm.indices && n + i < out.size) out[n + i] = pcm[k] }
            }
            n += longueur
        }
        return out.copyOf(n.coerceAtMost(out.size))
    }

    // ------------------------------------------------ comparison

    private fun tout(s: SourcePcm): ShortArray {
        val l = ArrayList<Short>()
        val buf = ShortArray(3000)
        while (true) { val n = s.lis(buf); if (n == 0) break; for (i in 0 until n) l += buf[i] }
        return l.toShortArray()
    }

    private fun compare(morceaux: List<SonPassage.Morceau>, de: Long, a: Long,
                        segs: List<JournalPassage.Segment>? = null, titre: Int = 0) {
        val ref = sonMorceaux(morceaux, de, a)!!
        val attendu = (if (segs != null) sonAccelere(ref.first, ref.second, de, segs) else ref.first)
            .let { if (titre > 0) ShortArray(titre) + it else it }
        SonPassage.fabrique(morceaux, de, a, dossier, decodeur)!!.use { son ->
            assertEquals(ref.second, son.rate)
            assertEquals(ref.first.size.toLong(), son.echantillons)
            assertArrayEquals(attendu, tout(son.source(de, segs, titre)))
        }
    }

    @Test
    fun un_enregistrement_avec_son_annonce() {
        // 44.1 kHz, averaged down by 2; a 3 s spoken header left as silence.
        val f = enregistrement("a.mp3", 40.0, 44_100, 1)
        val m = listOf(SonPassage.Morceau(f, 1_000_000L, 3_000L, 40_000L))
        compare(m, 1_000_000L, 1_040_000L)
        // A pass starting after the recording did.
        compare(m, 1_005_000L, 1_030_000L)
    }

    @Test
    fun deux_enregistrements_a_deux_cadences_qui_se_chevauchent() {
        val a = enregistrement("a.mp3", 30.0, 44_100, 2)
        val b = enregistrement("b.mp3", 30.0, 48_000, 3)
        val m = listOf(SonPassage.Morceau(b, 2_020_000L, 2_000L, 30_000L), SonPassage.Morceau(a, 2_000_000L, 1_000L, 30_000L))
        compare(m, 2_000_000L, 2_050_000L)
    }

    @Test
    fun un_morceau_de_moins_d_une_seconde_ne_compte_pas() {
        val court = enregistrement("court.mp3", 0.6, 44_100, 4)
        val long = enregistrement("long.mp3", 20.0, 44_100, 5)
        val m = listOf(SonPassage.Morceau(court, 3_000_000L, 0L, 600L), SonPassage.Morceau(long, 3_001_000L, 0L, 20_000L))
        compare(m, 3_000_000L, 3_021_000L)
        // Alone, it gives no sound at all.
        assertNull(SonPassage.fabrique(listOf(SonPassage.Morceau(court, 0L, 0L, 600L)), 0L, 600L, dossier, decodeur))
    }

    @Test
    fun la_lecture_acceleree_et_la_page_d_ouverture() {
        val f = enregistrement("a.mp3", 60.0, 44_100, 6)
        val m = listOf(SonPassage.Morceau(f, 4_000_000L, 2_000L, 60_000L))
        val segs = listOf(JournalPassage.Segment(3_990_000L, 4_010_000L, 10),   // starts before the pass
            JournalPassage.Segment(4_010_000L, 4_025_000L, 1),
            JournalPassage.Segment(4_025_000L, 4_050_000L, 10),
            JournalPassage.Segment(4_050_000L, 4_062_000L, 1))                // ends past it
        compare(m, 4_000_000L, 4_060_000L, segs)
        compare(m, 4_000_000L, 4_060_000L, segs, titre = 3 * 22_050)
        compare(m, 4_000_000L, 4_060_000L, null, titre = 3 * 22_050)
    }

    @Test
    fun le_fichier_temporaire_est_efface() {
        val f = enregistrement("a.mp3", 5.0, 44_100, 7)
        val son = SonPassage.fabrique(listOf(SonPassage.Morceau(f, 0L, 0L, 5_000L)), 0L, 5_000L, dossier, decodeur)!!
        son.close()
        assertEquals(0, dossier.listFiles()?.count { it.name.endsWith(".pcm") } ?: 0)
    }
}
