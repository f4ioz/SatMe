/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.demo.ApiRadio
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ft8VeilleTest {
    private val T0 = 1_760_000_000_000L - 1_760_000_000_000L % 15_000L

    @Test fun `l'horloge, la mediane des DT moins le demi-seconde`() {
        // Every station 0.8 s late: our clock is 0.8 s ahead. One station far off changes nothing.
        val l = (0 until 6).map { T0 + it * 15_000L to 1.3 } + (T0 to 3.0)
        val h = Ft8Veille.horloge(l, T0 + 6 * 15_000L)!!
        assertEquals(0.8, h.decalageS, 1e-9)
        assertEquals(0.0, h.dispersionS, 1e-9)
        assertEquals(7, h.decodes)
        assertEquals(6, h.tranches)
    }

    @Test fun `trop peu de decodages, ou trop vieux, pas d'horloge`() {
        assertNull(Ft8Veille.horloge(listOf(T0 to 0.5, T0 to 0.6), T0))
        val vieux = (0 until 10).map { T0 to 0.5 }
        assertNull(Ft8Veille.horloge(vieux, T0 + 10 * 60_000L))
        assertEquals(0.0, Ft8Veille.horloge(vieux, T0 + 60_000L)!!.decalageS, 1e-9)
    }

    @Test fun `l'horloge ne lit que les dernieres tranches`() {
        // Twenty slots: the oldest twelve said +2 s, the last eight say 0.
        val l = (0 until 20).map { T0 + it * 15_000L to if (it < 12) 2.5 else 0.5 }
        val h = Ft8Veille.horloge(l, T0 + 20 * 15_000L)!!
        assertEquals(0.0, h.decalageS, 1e-9)
        assertEquals(Ft8Veille.TRANCHES_HORLOGE, h.tranches)
    }

    @Test fun `le diagnostic, le pire d'abord`() {
        assertNull(Ft8Veille.diagnostic(false, false, null, 0f, 99))
        assertEquals("son", Ft8Veille.diagnostic(true, false, null, 0.5f, 0)!!.code)
        assertEquals("son", Ft8Veille.diagnostic(true, true, 5_000L, 0.5f, 0)!!.code)
        assertEquals("son", Ft8Veille.diagnostic(true, true, null, 0.5f, 0, panne = "micro refusé")!!.code)
        assertEquals("silence", Ft8Veille.diagnostic(true, true, 100L, 0f, 50)!!.code)
        val r = Ft8Veille.diagnostic(true, true, 100L, 0.2f, 40)!!
        assertEquals("rien", r.code)
        assertTrue(r.titre.contains("40 tranches"))
        assertNull(Ft8Veille.diagnostic(true, true, 100L, 0.2f, 39))
        assertNull(Ft8Veille.diagnostic(true, true, null, 0.2f, 0))
    }

    @Test fun `tranches sans decodage, d'apres le dernier`() {
        assertEquals(12, Ft8Veille.sansDecodage(12, null, T0, 15_000L))
        // Decoded in the slot that just ended: none since.
        assertEquals(0, Ft8Veille.sansDecodage(50, T0, T0 + 20_000L, 15_000L))
        assertEquals(41, Ft8Veille.sansDecodage(50, T0, T0 + 42 * 15_000L + 1_000L, 15_000L))
        assertEquals(5, Ft8Veille.sansDecodage(5, T0, T0 + 42 * 15_000L, 15_000L))
    }

    @Test fun `m'appelle`() {
        assertTrue(Ft8Veille.appelleMoi("F4IOZ F4ABC JN18", "F4ABC", "F4IOZ", "F4IOZ"))
        assertTrue(Ft8Veille.appelleMoi("<F4IOZ/P> F4ABC -10", "F4ABC", "F4IOZ/P", "f4ioz"))
        assertTrue(Ft8Veille.appelleMoi("F4IOZ F4ABC R-10", "F4ABC", null, "F4IOZ"))
        assertFalse(Ft8Veille.appelleMoi("CQ F4IOZ JN18", "F4IOZ", null, "F4IOZ"))
        assertFalse(Ft8Veille.appelleMoi("F4ABC K1ABC FN42", "K1ABC", "F4ABC", "F4IOZ"))
        assertFalse(Ft8Veille.appelleMoi("F4IOZ F4ABC JN18", "F4ABC", "F4IOZ", ""))
    }

    @Test fun `les stations guettees, tapees, gardees`() {
        assertEquals(listOf("F4ABC", "K1ABC", "EA8/F4XYZ"), Ft8Veille.guettees(" f4abc, K1ABC;; ea8/f4xyz f4abc x <b>"))
        assertTrue(Ft8Veille.guettee("F4XYZ", listOf("EA8/F4XYZ")))
        assertTrue(Ft8Veille.guettee("K1ABC/P", listOf("K1ABC")))
        assertFalse(Ft8Veille.guettee("K1ABD", listOf("K1ABC")))
        assertFalse(Ft8Veille.guettee(null, listOf("K1ABC")))
        val s = StockageMemoire()
        assertEquals(emptyList<String>(), Ft8Veille.lit(s))
        Ft8Veille.ecrit(s, "k1abc f4abc")
        assertEquals(listOf("K1ABC", "F4ABC"), Ft8Veille.lit(s))
        Ft8Veille.ecrit(s, "")
        assertEquals(emptyList<String>(), Ft8Veille.lit(s))
    }

    @Test fun `la page recoit l'horloge, le diagnostic et les marques`() {
        val l = (0 until 6).map { ApiRadio.Ligne(T0 + it * 15_000L, "F4IOZ K1ABC FN42", "K1ABC", "F4IOZ", "FN42", 1200, -10, 1.0, "FT8") }
        val o = ApiRadio.ft8(true, "FT8", 0f, 0.3f, 6, true, 200, 3000, emptyList(), FloatArray(0), l, "F4IOZ", null, emptySet(),
            guettees = listOf("K1ABC"), sonDepuisMs = 10_000L, maintenant = T0 + 6 * 15_000L)
        assertEquals(0.5, o.getJSONObject("horloge").getDouble("decalage"), 1e-9)
        assertEquals("son", o.getJSONObject("diag").getString("code"))
        val e = o.getJSONArray("entendus").getJSONObject(0)
        assertTrue(e.getBoolean("moi"))
        assertTrue(e.getBoolean("guettee"))
        assertEquals("K1ABC", o.getJSONArray("guettees").getString(0))
        // Without the new arguments, as before: no clock yet, no trouble.
        val vide = ApiRadio.ft8(false, "FT8", 0f, 0f, 0, false, 200, 3000, emptyList(), FloatArray(0), emptyList(), "", null, emptySet())
        assertTrue(vide.isNull("horloge")); assertTrue(vide.isNull("diag"))
    }
}
