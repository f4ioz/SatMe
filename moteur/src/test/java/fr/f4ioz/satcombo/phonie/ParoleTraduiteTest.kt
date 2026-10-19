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
}
