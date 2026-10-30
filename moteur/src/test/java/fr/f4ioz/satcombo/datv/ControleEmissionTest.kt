/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page's heartbeat with several tabs: only the tab that started the
 * transmission keeps it; another tab (or the phone) watches, may STOP, and
 * neither its heartbeat nor its going away counts.
 */
class ControleEmissionTest {

    @Test
    fun seul_l_onglet_qui_a_demarre_fait_vivre_l_emission() {
        var t = 0L
        val c = ControleEmission({ t })
        val a = ControleEmission.Client("onglet-a", false)
        val b = ControleEmission.Client("onglet-b", false)
        c.prend(a, "DATV")
        // The second tab: its heartbeat refused, its start refused, STOP stays (not decided here).
        assertFalse(c.vie(b))
        assertNotNull(c.refus(b, emet = true))
        assertTrue(c.json(b).let { it.getBoolean("tenue") && !it.getBoolean("moi") })
        assertTrue(c.json(a).getBoolean("moi"))
        t = 5_000
        assertTrue(c.vie(a))
        assertEquals(0.0, c.json(a).getDouble("vieS"), 0.0)
        // Its heartbeat refused, the second tab never moved the controller's time.
        t = 9_000; assertFalse(c.vie(b)); assertEquals(4.0, c.json(a).getDouble("vieS"), 0.0)
    }

    @Test
    fun le_telephone_ou_un_autre_appareil_silencieux_coupe() {
        var t = 0L
        val c = ControleEmission({ t }, chienDistantMs = 2_000L)
        val tel = ControleEmission.Client("tel", true, "192.0.2.7")
        c.confirme(tel)
        assertNull(c.refus(tel, emet = false))
        c.prend(tel, "DATV")
        t = 1_500; assertNull(c.perdu())
        t = 2_500; assertTrue(c.perdu()!!.contains("liaison perdue"))
        // A local page keeps the transmitters' own, longer guard unless asked.
        val c2 = ControleEmission({ t })
        c2.prend(ControleEmission.Client("pc", false), "DATV")
        t += 60_000
        assertNull(c2.perdu())
        c2.chienLocal = true
        assertNotNull(c2.perdu())
    }
}
