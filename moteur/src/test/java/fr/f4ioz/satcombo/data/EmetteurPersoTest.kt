/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Autre fréquence… »: what is typed, kept per satellite, and its place in the list. */
class EmetteurPersoTest {

    @Test fun ce_qui_est_tape() {
        assertEquals(436_875_000L, EmetteurPerso.hz("436,875"))
        assertEquals(436_875_000L, EmetteurPerso.hz(" 436.875 "))
        assertEquals(145_875_000L, EmetteurPerso.hz("145875000"))
        assertNull(EmetteurPerso.hz("abc")); assertNull(EmetteurPerso.hz("5"))
        assertEquals("USB-D", EmetteurPerso.mode("usb-d")); assertEquals("USB-D", EmetteurPerso.mode("USB DATA")); assertNull(EmetteurPerso.mode("PSK"))
        val (r, err) = EmetteurPerso.lis("436,875", "", "USB-D")
        assertNull(err)
        assertEquals(EmetteurPerso.Reglage(436_875_000L, null, "USB-D"), r)
        assertNotNull(EmetteurPerso.lis("436,875", "x", "USB").second)
        assertNotNull(EmetteurPerso.lis("", "", "USB").second)
    }

    @Test fun gardee_par_satellite_et_au_bout_de_la_liste() {
        val st = SatConfigStore(StockageMemoire())
        val r = EmetteurPerso.Reglage(436_875_000L, 145_875_000L, "USB-D")
        st.savePerso(68446, r)
        assertEquals(r, st.load(68446).perso)
        assertNull(st.load(25544).perso)
        val sat = listOf(Transmitter("SSDV", "SSDV", null, null, 436_875_000L, 436_875_000L, false, true, "Transmitter", 800.0))
        val l = EmetteurPerso.ajoute(sat, st.load(68446).perso)
        assertEquals(2, l.size)
        assertTrue(EmetteurPerso.estPerso(l[1]))
        assertEquals(145_875_000L, l[1].uplinkLowHz)
        // Typed again: replaced, not added.
        assertEquals(2, EmetteurPerso.ajoute(l, r.copy(mode = "FM")).size)
        st.savePerso(68446, null)
        assertNull(st.load(68446).perso)
        assertEquals(1, EmetteurPerso.ajoute(l, null).size)
    }
}
