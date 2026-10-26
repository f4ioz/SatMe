/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParoleTraduiteTest {

    @Test fun `jamais plus de deux en attente, la plus vieille part`() {
        var t = 0L
        val f = ParoleTraduite.File<String>(2, 30_000L) { t }
        f.ajoute("a", 0); f.ajoute("b", 0); f.ajoute("c", 0)
        assertEquals(2, f.taille()); assertEquals(1L, f.sautees)
        assertEquals("b", f.prochaine()); assertEquals("c", f.prochaine()); assertNull(f.prochaine())
    }

    @Test fun `une traduction de plus de 30 s n est plus dite`() {
        var t = 100_000L
        val f = ParoleTraduite.File<String>(2, 30_000L) { t }
        f.ajoute("vieille", 60_000L)   // heard 40 s ago
        f.ajoute("fraiche", 95_000L)
        assertEquals("fraiche", f.prochaine()); assertEquals(1L, f.perimees)
        f.ajoute("x", 95_000L)
        t = 126_000L
        assertNull(f.prochaine()); assertEquals(2L, f.perimees)
        assertTrue(f.frais(100_000L)); assertTrue(!f.frais(90_000L))
    }

    @Test fun `seulement les QSO en langue etrangere`() {
        assertEquals("bonjour", ParoleTraduite.aDire("pl", "fr", "bonjour", "dzień dobry", true))
        assertNull(ParoleTraduite.aDire("fr", "fr", "", "bonjour", true))
        assertEquals("bonjour", ParoleTraduite.aDire("fr", "fr", "", "bonjour", false))
        // A translation missing (pair not installed): nothing, rather than the foreign words in a French voice.
        assertNull(ParoleTraduite.aDire("pl", "fr", "", "dzień dobry", false))
        assertNull(ParoleTraduite.aDire("", "fr", "", "", false))
    }

    @Test fun `voix auto la plus proche de la hauteur entendue`() {
        val l = listOf("fr_FR-siwis-medium", "fr_FR-gilles-low")
        assertEquals("fr_FR-gilles-low", ParoleTraduite.choisit("auto", "fr", l, 118.0)?.id)
        assertEquals("fr_FR-siwis-medium", ParoleTraduite.choisit("auto", "fr", l, 230.0)?.id)
        assertEquals("fr_FR-siwis-medium", ParoleTraduite.choisit("fr_FR-siwis-medium", "fr", l, 100.0)?.id)
        // The choice for another language, or a model absent: the auto choice.
        assertEquals("fr_FR-gilles-low", ParoleTraduite.choisit("de_DE-thorsten-medium", "fr", l, 100.0)?.id)
        assertNull(ParoleTraduite.choisit("auto", "it", l, 100.0))
        // Two speakers in one model.
        assertEquals("fr_FR-upmc-medium:jessica", ParoleTraduite.choisit("auto", "fr", listOf("fr_FR-upmc-medium"), 220.0)?.id)
    }

    @Test fun `wav lisible`() {
        val w = ParoleTraduite.wav(ShortArray(10) { it.toShort() }, 22_050)
        assertEquals(64, w.size)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4))
        val s = ParoleTraduite.enShorts(floatArrayOf(0f, 0.5f, -1f))
        assertEquals((-0.85f * 32767).toInt().toShort(), s[2])
    }

    @Test fun `la DATV ne garde que la plus recente, avec sa propre limite d age`() {
        var t = 100_000L
        val f = ParoleTraduite.File<String>(2, 30_000L) { t }
        f.ajoute("phonie", t)
        f.ajoute("datv 1", t, seule = true, perime = 20_000L, genre = "datv")
        f.ajoute("datv 2", t, seule = true, perime = 20_000L, genre = "datv")
        assertEquals(2, f.taille()); assertEquals(1L, f.sautees)
        t += 25_000
        // The phonie's still fresh at 25 s, the DATV's not.
        assertEquals("phonie", f.prochaine())
        assertNull(f.prochaine())
        assertEquals(1L, f.perimees)
        assertTrue(f.frais(t - 25_000)); assertFalse(f.frais(t - 25_000, 20_000L))
    }

    @Test fun `le son d origine glisse vers le niveau demande, sans saut`() {
        val b = ParoleTraduite.Baisse(rampeMs = 100.0)
        val rate = 1000
        // 100 ms at 1000 Hz: 100 frames from 1 to 0.25; stereo, both channels alike.
        val x = ShortArray(400) { 10_000 }
        b.applique(x, x.size, 2, rate, 0.25)
        assertEquals(0.25, b.gain, 1e-9)
        val g = (0 until 200).map { x[2 * it] / 10_000.0 }
        assertTrue(g.zipWithNext().all { (a, c) -> a >= c && a - c <= 0.0101 })
        assertEquals(0.99, g[0], 0.01); assertEquals(0.25, g[150], 0.001)
        assertTrue((0 until 200).all { x[2 * it] == x[2 * it + 1] })
        // Back up the same way; at 1 it leaves the sound untouched.
        val y = ShortArray(400) { 10_000 }
        b.applique(y, y.size, 2, rate, 1.0)
        assertEquals(1.0, b.gain, 1e-9)
        val z = ShortArray(4) { 1234 }
        b.applique(z, 4, 2, rate, 1.0)
        assertTrue(z.all { it == 1234.toShort() })
        assertEquals(0.3, ParoleTraduite.gainBaisse(70), 1e-9); assertEquals(1.0, ParoleTraduite.gainBaisse(-5), 1e-9); assertEquals(0.0, ParoleTraduite.gainBaisse(100), 1e-9)
    }
}
