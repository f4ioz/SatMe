/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.ActiviteSon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ActiviteSonTest {
    private val fs = 22050

    /** Hiss, then a voice-band tone 10 s, hiss again: one stretch, where the tone was. */
    @Test
    fun une_tonalite_dans_le_souffle_est_entendue() {
        val m = ActiviteSon.Mesure(fs)
        // Hiss: white noise (as much in the highs as in the voice band).
        m.ajoute(ActiviteSon.signalEssai(fs, 30_000, 1000.0, 0.0, 0.6, 1))
        m.ajoute(ActiviteSon.signalEssai(fs, 10_000, 1200.0, 0.5, 0.15, 2))
        m.ajoute(ActiviteSon.signalEssai(fs, 30_000, 1000.0, 0.0, 0.6, 3))
        val p = ActiviteSon.plages(m.fenetres)
        assertEquals(1, p.size)
        assertTrue("$p", p[0].first in 29_000L..31_000L && p[0].last in 39_000L..41_000L)
        assertEquals(p, ActiviteSon.lit(ActiviteSon.ecrit(p)))
    }

    /** Activity most of the time (an SSTV pass): pictures with hiss between them, each picture heard. */
    @Test
    fun une_activite_majoritaire_est_entendue_aussi() {
        val m = ActiviteSon.Mesure(fs)
        for (k in 0 until 4) {
            m.ajoute(ActiviteSon.signalEssai(fs, 8_000, 1000.0, 0.0, 0.6, 10 + k))
            m.ajoute(ActiviteSon.signalEssai(fs, 30_000, 1700.0, 0.5, 0.15, 20 + k))
        }
        val p = ActiviteSon.plages(m.fenetres)
        assertEquals("$p", 4, p.size)
        p.forEachIndexed { k, r -> assertTrue("$r", r.first in (k * 38_000L + 7_000)..(k * 38_000L + 9_000)) }
    }

    /** A closed squelch (silence) and steady hiss: nothing heard. */
    @Test
    fun le_silence_et_le_souffle_ne_sont_rien() {
        val m = ActiviteSon.Mesure(fs)
        m.ajoute(ShortArray(fs * 20))
        m.ajoute(ActiviteSon.signalEssai(fs, 40_000, 1000.0, 0.0, 0.6, 4))
        assertTrue(ActiviteSon.plages(m.fenetres).isEmpty())
    }

    /** Real recordings, when present on this PC: the timeline printed, for the eye. */
    @Test
    fun enregistrements_reels() {
        val dossier = File(System.getProperty("user.home"), "SatMe-atelier/essais-activite")
        val l = dossier.listFiles { f -> f.name.endsWith(".raw") }?.sortedBy { it.name } ?: emptyList()
        assumeTrue("pas d'enregistrement réel", l.isNotEmpty())
        for (f in l) {
            val b = f.readBytes()
            val pcm = ShortArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort() }
            val m = ActiviteSon.Mesure(fs); m.ajoute(pcm)
            val p = ActiviteSon.plages(m.fenetres)
            val tri = m.fenetres.map { it.partVoixDb }.sorted()
            println("== ${f.name} : ${pcm.size / fs} s, médiane ${"%.1f".format(tri[tri.size / 2])} dB, ${p.size} plages, " +
                "${p.sumOf { it.last - it.first } / 1000} s actives")
            println("   déciles " + (0..10).joinToString(" ") { "%.1f".format(tri[(it * (tri.size - 1)) / 10]) })
            val h = IntArray(60); tri.forEach { v -> val k = (v - 10).toInt().coerceIn(0, 59); h[k]++ }
            println("   histogramme (10 à 70 dB, 1 dB par case) : " + h.joinToString(","))
            p.forEach { println("   %6.1f → %6.1f s  (%4.1f s)".format(it.first / 1000.0, it.last / 1000.0, (it.last - it.first) / 1000.0)) }
            // A compact line per 5 s: level and the voice share.
            m.fenetres.chunked(20).forEachIndexed { k, c ->
                println("   %4d s  niv %6.1f  voix %5.1f".format(k * 5, c.map { it.niveauDb }.average(), c.map { it.partVoixDb }.average()))
            }
        }
    }
}
