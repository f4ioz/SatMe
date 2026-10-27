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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The band plan's channels free or busy from the ground station's spectrum. */
class CreneauxDatvTest {

    private fun signal(centre: Long, sr: Int, balise: Boolean = false): SignalLarge {
        // The width BandeLarge measures (at 75 % of the height): about the symbol rate.
        val demi = sr / 2L
        return SignalLarge(centre - demi, centre + demi, centre, 2 * demi, sr, 30_000, -3.0, balise, false)
    }

    /** A flat noise with raised-cosine signals, as [BandeLargeTest] makes them. */
    private fun spectre(vararg s: Triple<Long, Int, Int>): IntArray {
        val n = 922
        val r = java.util.Random(3)
        val v = IntArray(n) { BandeLarge.BRUIT + r.nextInt(600) - 300 }
        for ((c, sr, h) in s) for (i in 0 until n) {
            val x = Math.abs(BandeLarge.khz(i.toDouble(), n) - c) / sr
            val a = when { x <= 0.325 -> 1.0; x >= 0.675 -> 0.0; else -> 0.5 * (1 + Math.cos(Math.PI / 0.35 * (x - 0.325))) }
            if (a > 0) v[i] = maxOf(v[i], (BandeLarge.BRUIT + h + 10 * Math.log10(a) * BandeLarge.UNITES_PAR_DB).toInt())
        }
        return v
    }

    @Test
    fun les_canaux_etroits_a_333_ks() {
        val l = CreneauxDatv.calcule(333, 0.25, emptyList())
        // The 14 narrow channels, 500 kHz apart, all free on an empty band.
        assertEquals(14, l.size)
        assertEquals(10_492_750L, l.first().descenteKhz)
        assertEquals(10_499_250L, l.last().descenteKhz)
        assertTrue(l.all { it.etat == CreneauxDatv.Etat.LIBRE })
        assertEquals(2_409_750L, l.last().monteeKhz)
    }

    @Test
    fun a_1500_ks_seulement_les_canaux_larges_permis() {
        val l = CreneauxDatv.calcule(1500, 0.20, emptyList())
        assertEquals(listOf(10_494_750L, 10_496_250L), l.map { it.descenteKhz })
        // 1000 kS/s: the lowest wide channel too.
        assertEquals(10_493_250L, CreneauxDatv.calcule(1000, 0.20, emptyList()).first().descenteKhz)
        // 2000 kS/s: no channel of the plan.
        assertTrue(CreneauxDatv.calcule(2000, 0.20, emptyList()).isEmpty())
    }

    @Test
    fun trop_pres_de_la_balise_interdit() {
        // The plan's lowest channel fits at 333 kS/s with the usual roll-offs (0.35 included)…
        assertEquals(CreneauxDatv.Etat.LIBRE, CreneauxDatv.calcule(333, 0.35, emptyList()).first().etat)
        // …but [PlanQo100.refus] decides: an occupied band wider than the room left above the beacon is refused, the top one too.
        val l = CreneauxDatv.calcule(333, 0.6, emptyList())
        val bas = l.first()
        assertEquals(CreneauxDatv.Etat.INTERDIT, l.last().etat)
        assertEquals(CreneauxDatv.Etat.INTERDIT, bas.etat)
        assertTrue(bas.raison.contains("balise"))
        assertNotNull(CreneauxDatv.refusChoix(bas))
        assertNull(CreneauxDatv.refusChoix(l[1]))
    }

    @Test
    fun occupe_par_un_signal_avec_son_nom() {
        val l = CreneauxDatv.calcule(333, 0.25, listOf(signal(10_497_750L, 333)), { "F1ABC" })
        val c = l.first { it.descenteKhz == 10_497_750L }
        assertEquals(CreneauxDatv.Etat.OCCUPE, c.etat)
        assertEquals("F1ABC", c.occupant)
        assertTrue(CreneauxDatv.refusChoix(c)!!.contains("F1ABC"))
        // The neighbours 500 kHz away stay free.
        assertEquals(CreneauxDatv.Etat.LIBRE, l.first { it.descenteKhz == 10_497_250L }.etat)
        assertEquals(CreneauxDatv.Etat.LIBRE, l.first { it.descenteKhz == 10_498_250L }.etat)
        // A wide signal covers several narrow channels; a nameless one is named by its rate.
        val w = CreneauxDatv.calcule(333, 0.25, listOf(signal(10_494_750L, 1500)))
        assertEquals(listOf(10_494_250L, 10_494_750L, 10_495_250L), w.filter { it.etat == CreneauxDatv.Etat.OCCUPE }.map { it.descenteKhz })
        assertEquals("signal 1500 kS/s", w.first { it.etat == CreneauxDatv.Etat.OCCUPE }.occupant)
    }

    @Test
    fun la_balise_ne_compte_pas_et_sans_spectre_inconnu() {
        val l = CreneauxDatv.calcule(333, 0.25, listOf(signal(Datv.BALISE_KHZ, 1500, balise = true)))
        assertTrue(l.none { it.etat == CreneauxDatv.Etat.OCCUPE })
        val inconnus = CreneauxDatv.calcule(333, 0.25, null)
        assertTrue(inconnus.all { it.etat == CreneauxDatv.Etat.INCONNU })
        assertNull(CreneauxDatv.refusChoix(inconnus[3]))
    }

    @Test
    fun notre_signal() {
        // Transmitting there: ours, with the callsign; our callsign named on a signal: ours too.
        val l = CreneauxDatv.calcule(333, 0.25, listOf(signal(10_498_750L, 333)), notreDescenteKhz = 10_498_750L, indicatif = "f4ioz")
        val c = l.first { it.descenteKhz == 10_498_750L }
        assertEquals(CreneauxDatv.Etat.NOUS, c.etat)
        assertEquals("F4IOZ", c.occupant)
        val n = CreneauxDatv.calcule(333, 0.25, listOf(signal(10_496_750L, 333)), { "F4IOZ" }, null, "F4IOZ")
        assertEquals(CreneauxDatv.Etat.NOUS, n.first { it.descenteKhz == 10_496_750L }.etat)
        assertEquals(c, CreneauxDatv.de(l, 2_409_250L))
    }

    @Test
    fun depuis_un_spectre_mesure() {
        // The beacon, a 333 kS/s station on 10495.75 and a 125 kS/s one on 10498.25.
        val v = spectre(Triple(Datv.BALISE_KHZ, 1500, 27_000), Triple(10_495_750L, 333, 20_000), Triple(10_498_250L, 125, 15_000))
        val sg = BandeLarge.detecte(v)
        val l = CreneauxDatv.calcule(333, 0.25, sg)
        val occupes = l.filter { it.etat == CreneauxDatv.Etat.OCCUPE }.map { it.descenteKhz }
        assertEquals(listOf(10_495_750L, 10_498_250L), occupes)
        assertEquals(l.size - 2, l.count { it.etat == CreneauxDatv.Etat.LIBRE })
        val j = CreneauxDatv.json(l)
        assertEquals("occupe", j.getJSONObject(l.indexOfFirst { it.descenteKhz == 10_495_750L }).getString("etat"))
    }
}
