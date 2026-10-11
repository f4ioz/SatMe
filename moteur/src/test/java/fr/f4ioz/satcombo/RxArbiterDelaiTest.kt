/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.RxArbiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resume delay.
 *
 * **The setting must be noticeable.** It once had no effect on an IC-9700,
 * though not for the obvious reason: the arbiters reverted to their 2 s
 * default on every launch, because the stored setting was only applied from
 * the selector.
 */
class RxArbiterDelaiTest {

    /** Turns the dial, waits, and reports when control comes back. */
    private fun msAvantReprise(delaiMs: Long, pasMs: Long = 100L): Long {
        val a = RxArbiter()
        a.regle(delaiMs)
        a.observe(435_000_000L, 0L)
        a.observe(435_001_000L, pasMs)      // one dial step
        assertFalse("la main doit passer à l'opérateur", a.driven)
        var t = pasMs
        repeat(60) {
            t += pasMs
            a.observe(435_001_000L, t)
            if (a.driven) return t - pasMs
        }
        return -1L
    }

    @Test
    fun un_delai_court_reprend_vraiment_plus_vite() {
        val court = msAvantReprise(250L)
        val long = msAvantReprise(2_000L)
        assertTrue("reprise à $court ms pour un délai de 250 ms", court in 1..800)
        assertTrue("reprise à $long ms pour un délai de 2 s", long >= 1_900)
        // The point of the test: the two settings must feel different.
        assertTrue("le réglage ne se sent pas : $court contre $long", long > court * 2)
    }

    @Test
    fun le_delai_commande_aussi_le_nombre_dechantillons() {
        // Both must move together: a short delay that still waits for eight
        // samples stays long, and the setting seems to do nothing.
        val a = RxArbiter()
        a.regle(250L)
        a.observe(435_000_000L, 0L)
        a.observe(435_001_000L, 50L)
        var t = 50L
        var repris = false
        repeat(12) { t += 50L; a.observe(435_001_000L, t); if (a.driven) repris = true }
        assertTrue("pas de reprise après ${t} ms avec un délai de 250 ms", repris)
    }

    @Test
    fun un_delai_hors_bornes_ne_casse_rien() {
        // Neither zero (would grab control mid-gesture) nor infinity (would
        // never give it back).
        val a = RxArbiter()
        a.regle(0L)
        a.observe(435_000_000L, 0L)
        a.observe(435_001_000L, 10L)
        assertFalse(a.driven)
        a.regle(99_000L)
    }

    @Test
    fun tant_que_la_molette_bouge_la_main_reste_a_loperateur() {
        val a = RxArbiter()
        a.regle(250L)
        var hz = 435_000_000L
        var t = 0L
        a.observe(hz, t)
        repeat(20) {
            t += 100L; hz -= 1_000L      // operator keeps tuning down
            a.observe(hz, t)
        }
        assertFalse("la main ne doit pas être reprise pendant le geste", a.driven)
    }
}
