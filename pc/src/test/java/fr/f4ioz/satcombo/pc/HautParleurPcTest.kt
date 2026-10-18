/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class HautParleurPcTest {

    private val rate = 44_100
    private val hp = HautParleurPc(rate) { null }   // no card: the test reads the blocks itself

    @After fun ferme() = hp.ferme()

    /** Bursty blocks a little slower than the card (the receiver's clock): no gap, no dip, the cushion held. */
    @Test fun rafales_plus_lentes_que_la_carte_sans_creux() {
        val produitParS = 44_016
        val sortie = ShortArray(rate / 100)
        var produit = 0L
        var phase = 0.0
        var silences = 0
        val niveaux = ArrayList<Double>()
        // 60 s: the card asks 10 ms every 10 ms; the receiver gives 50 ms at once every 50 ms.
        for (ms in 0 until 60_000 step 10) {
            if (ms % 50 == 0) {
                val du = (ms + 50L) * produitParS / 1000 - produit
                val b = ShortArray(du.toInt()) { (8000 * sin(phase).also { phase += 2 * PI * 700 / rate }).toInt().toShort() }
                hp.pousse(b, b.size); produit += du
            }
            hp.prochain(sortie)
            if (ms > 1_000) {
                if (sortie.all { it == 0.toShort() }) silences++
                niveaux += kotlin.math.sqrt(sortie.sumOf { it.toDouble() * it } / sortie.size)
            }
        }
        assertEquals(0, silences)
        // A steady tone: every 10 ms at the same level (a dropped block would show as a dip).
        val moyen = niveaux.average()
        assertTrue(niveaux.all { abs(it - moyen) / moyen < 0.05 })
    }

    @Test fun a_sec_il_attend_le_coussin_puis_reprend() {
        val sortie = ShortArray(rate / 100)
        hp.prochain(sortie)
        assertTrue(sortie.all { it == 0.toShort() })
        val b = ShortArray((rate * HautParleurPc.CIBLE_S).toInt() + 10) { 1000 }
        hp.pousse(b, b.size)
        hp.prochain(sortie)
        assertTrue(sortie.all { it == 1000.toShort() })
    }

    @Test fun loin_derriere_il_saute_au_coussin() {
        val b = ShortArray(rate) { 500 }
        hp.pousse(b, b.size)   // 1 s at once (a stall then a burst)
        val sortie = ShortArray(rate / 100)
        var lus = 0
        while (true) { hp.prochain(sortie); if (sortie.any { it == 0.toShort() }) break; lus += sortie.size; if (lus > rate) break }
        assertTrue("joué $lus", lus < rate * (HautParleurPc.CIBLE_S + 0.05))
    }
}
