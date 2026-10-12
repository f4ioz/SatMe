/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FT8 through the PC's sound card: a real message, in the card's blocks and rate, on UTC slots. */
class Ft8PcTest {

    /** A CQ from F4IOZ in IN77 at 1000 Hz, 0.5 s into its slot (as the engine's decoder test builds it). */
    private fun audio(): FloatArray {
        val message = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(message, 0, 28, 2L)
        Ft8.ecritEntier(message, 29, 28, Ft8.indicatifVers28("F4IOZ")!!)
        Ft8.ecritEntier(message, 59, 15, (('I' - 'A') * 1800 + ('N' - 'A') * 100 + 7 * 10 + 7).toLong())
        Ft8.ecritEntier(message, 74, 3, 1L)
        val bits = BooleanArray(Ft8.BITS)
        System.arraycopy(Ft8.avecControle(message), 0, bits, 0, Ft8.BITS_UTILES)
        val r = java.util.Random(1)
        for (i in Ft8.BITS_UTILES until Ft8.BITS) bits[i] = r.nextBoolean()
        return Ft8Signal.synthetise(Ft8.bitsVersSymboles(bits), Ft8Signal.FT8, 1000.0, 0.5)
    }

    @Test
    fun un_cq_entendu_sur_la_carte_son_est_decode_a_la_fin_de_son_creneau() {
        // The clock follows the samples fed: the last second of a slot, then on.
        val t0 = 1_791_800_000_000L / 15_000L * 15_000L - 1_000L
        var verses = 0L
        val f = Ft8Pc { t0 + verses * 1000L / 48_000 }
        f.demarre("FT8")
        val rate = 48_000
        val a = Ft8Signal.reechantillonne(audio(), Ft8Signal.FT8.cadenceHz, rate.toDouble())
        val bloc = ShortArray(2048)
        fun verse(n: Int, echantillon: (Int) -> Float) {
            var i = 0
            while (i < n) {
                val k = minOf(bloc.size, n - i)
                for (j in 0 until k) bloc[j] = (echantillon(i + j) * 0.4f * 32767).toInt().toShort()
                verses += k
                f.ecoute(bloc, k, rate)
                i += k
            }
        }
        // The end of a slot (dropped: started mid-transmission), then the CQ's whole slot, then the next one begins.
        verse(rate) { 0f }
        verse(15 * rate) { i -> if (i < a.size) a[i] else 0f }
        verse(2048) { 0f }
        val fin = System.currentTimeMillis() + 20_000
        while (f.entendus.isEmpty() && System.currentTimeMillis() < fin) Thread.sleep(50)
        val e = f.entendus.single()
        assertEquals("CQ F4IOZ IN77", e.texte)
        assertEquals("F4IOZ", e.appelant); assertEquals("IN77", e.locator)
        assertEquals(1000.0, e.frequenceHz.toDouble(), 10.0)
        assertEquals(1, f.tranches)
        assertTrue(f.cascade!!.lignes.isNotEmpty())
    }
}
