/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.CreneauxDatv.Etat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A click on the spectrum: the uplink picked, and the free-slot hint (indicative only). */
class CreneauxIndiceDatvTest {

    private fun signal(centre: Long, sr: Int, balise: Boolean = false): SignalLarge {
        val demi = sr / 2L
        return SignalLarge(centre - demi, centre + demi, centre, 2 * demi, sr, 30_000, -3.0, balise, false)
    }

    @Test
    fun un_clic_pres_d_un_canal_s_y_accroche_sinon_a_10_khz() {
        // 10 499,30 MHz at 333 kS/s: inside the 10 499,25 channel's band.
        assertEquals(2_409_750L, CreneauxDatv.accroche(10_499_300L, 333, 0.25))
        assertEquals(2_409_250L, CreneauxDatv.accroche(10_498_900L, 333, 0.25))
        // Between two narrow channels at 125 kS/s (±78 kHz): the click itself, rounded.
        assertEquals(2_408_000L, CreneauxDatv.accroche(10_497_503L, 125, 0.25))
        assertEquals(2_408_010L, CreneauxDatv.accroche(10_497_507L, 125, 0.25))
        // A wide channel only takes the rates it allows: 1500 kS/s goes to 10 494,75.
        assertEquals(10_494_750L - PlanQo100.DECALAGE_KHZ, CreneauxDatv.accroche(10_494_600L, 1500, 0.20))
    }

    @Test
    fun indice_libre_occupe_inconnu_interdit_nous() {
        val montee = 2_409_750L
        val libre = CreneauxDatv.indice(montee, 333, 0.25, emptyList())
        assertEquals(Etat.LIBRE, libre.etat)
        assertTrue(CreneauxDatv.texteIndice(libre).startsWith("créneau qui semble libre"))
        // Said as a guess, never as a permission.
        assertTrue(CreneauxDatv.texteIndice(libre).contains("indicatif seulement"))
        val occ = CreneauxDatv.indice(montee, 333, 0.25, listOf(signal(10_499_230L, 333)), { "F1ABC" })
        assertEquals(Etat.OCCUPE, occ.etat)
        assertTrue(CreneauxDatv.texteIndice(occ).startsWith("créneau qui semble occupé (F1ABC)"))
        // Off the band plan too: a signal 160 kHz away at 125 kS/s does not overlap (its skirts and the guard included).
        assertEquals(Etat.LIBRE, CreneauxDatv.indice(2_408_000L, 125, 0.25, listOf(signal(10_497_660L, 125))).etat)
        assertEquals(Etat.OCCUPE, CreneauxDatv.indice(2_408_000L, 125, 0.25, listOf(signal(10_497_560L, 125))).etat)
        // The beacon never counts as a station.
        assertEquals(Etat.INCONNU, CreneauxDatv.indice(montee, 333, 0.25, null).etat)
        assertEquals(Etat.INTERDIT, CreneauxDatv.indice(2_402_500L, 333, 0.25, emptyList()).etat)
        assertEquals(Etat.NOUS, CreneauxDatv.indice(montee, 333, 0.25, listOf(signal(10_499_250L, 333)), { "f4ioz" }, indicatif = "F4IOZ").etat)
        assertEquals(Etat.NOUS, CreneauxDatv.indice(montee, 333, 0.25, emptyList(), notreDescenteKhz = 10_499_250L).etat)
    }

    @Test
    fun la_balise_n_occupe_pas_un_creneau() {
        val c = CreneauxDatv.indice(2_403_500L, 333, 0.25, listOf(signal(10_491_500L, 1500, balise = true)))
        assertEquals(Etat.LIBRE, c.etat)
        assertEquals(2_403_500L + PlanQo100.DECALAGE_KHZ, CreneauxDatv.json(c).getLong("descente"))
    }
}
