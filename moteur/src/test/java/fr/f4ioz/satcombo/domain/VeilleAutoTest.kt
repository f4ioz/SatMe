/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.SatPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeilleAutoTest {

    private val m = 60_000L
    private val t0 = 1_000 * m

    private fun p(n: Int, nom: String, aos: Long, min: Int, el: Double = 30.0) =
        SatPass(nom, n, aos, aos + min * m, el, 0.0, 0.0, false, false)

    private val rs44 = p(44909, "RS-44", t0 + 60 * m, 15)
    private val iss = p(25544, "ISS", t0 + 70 * m, 10)
    private val so50 = p(27607, "SO-50", t0 + 120 * m, 12, el = 5.0)
    private val ao7 = p(7530, "AO-07", t0 + 30 * m, 10)
    private val tous = listOf(iss, so50, rs44, ao7)

    @Test fun `seuls les satellites choisis, au-dessus de l'elevation, le plus tot d'abord`() {
        val l = VeilleAuto.plan(tous, setOf(44909, 25544, 27607), 10.0, emptySet(), t0)
        assertEquals(listOf("RS-44", "ISS"), l.map { it.passage.satName })
    }

    @Test fun `deux passages en meme temps, le premier continue, l'autre est perdu`() {
        val l = VeilleAuto.plan(tous, setOf(44909, 25544), 0.0, emptySet(), t0)
        assertTrue(l[0].garde)
        assertFalse(l[1].garde)
        assertEquals("RS-44", l[1].perdu)
    }

    @Test fun `le premier ecarte, l'autre est garde, meme apres de nouveaux elements`() {
        // The AOS moved by 40 s with new elements: still the pass set aside.
        val ecartes = setOf("44909:" + (rs44.aosEpochMs - 40_000L))
        val l = VeilleAuto.plan(tous, setOf(44909, 25544), 0.0, ecartes, t0)
        assertTrue(l[0].ecarte)
        assertTrue(l[1].garde)
    }

    @Test fun `suivi 5 min avant l'AOS, enregistre 5 s avant, jusqu'a 30 s apres le LOS`() {
        val l = VeilleAuto.plan(tous, setOf(44909), 0.0, emptySet(), t0)
        assertNull(VeilleAuto.aSuivre(l, rs44.aosEpochMs - 6 * m))
        assertEquals(rs44, VeilleAuto.aSuivre(l, rs44.aosEpochMs - 4 * m))
        assertNull(VeilleAuto.aEnregistrer(l, rs44.aosEpochMs - 4 * m))
        assertEquals(rs44, VeilleAuto.aEnregistrer(l, rs44.aosEpochMs - 4_000L))
        assertEquals(rs44, VeilleAuto.aEnregistrer(l, rs44.losEpochMs + 20_000L))
        assertNull(VeilleAuto.aEnregistrer(l, rs44.losEpochMs + 40_000L))
    }

    @Test fun `un passage fini sort du plan, les cles anciennes aussi`() {
        assertTrue(VeilleAuto.plan(tous, setOf(7530), 0.0, emptySet(), ao7.losEpochMs + m).isEmpty())
        val k = VeilleAuto.purge(setOf("1:" + t0, "2:" + (t0 + 30 * 3_600_000L)), t0 + 25 * 3_600_000L)
        assertEquals(setOf("2:" + (t0 + 30 * 3_600_000L)), k)
    }
}
