/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.PileBandes
import fr.f4ioz.satcombo.domain.PileBandes.Entree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The band stack: noted after 5 s on a frequency, given back press after press. */
class PileBandesTest {

    private fun reste(r: PileBandes.Registre, t0: Long, e: Entree, ms: Long): Long {
        var t = t0
        while (t <= t0 + ms) { r.observe(t, e); t += 500 }
        return t
    }

    @Test
    fun les_bandes() {
        assertEquals("2m", PileBandes.bandeDe(145_800_000L))
        assertEquals("70cm", PileBandes.bandeDe(436_795_000L))
        assertEquals("3cm", PileBandes.bandeDe(10_489_750_000L))
        assertEquals(PileBandes.AUTRE, PileBandes.bandeDe(137_100_000L))
    }

    @Test
    fun note_apres_cinq_secondes_seulement() {
        val r = PileBandes.Registre()
        val iss = Entree(145_800_000L, "NFM", 0)
        reste(r, 0, iss, 4_000)
        assertTrue(r.pile("2m").isEmpty())
        // A few Hz of wobble is the same frequency.
        r.observe(4_600, iss.copy(hz = 145_800_040L))
        r.observe(5_100, iss)
        assertEquals(listOf(iss), r.pile("2m"))
        // Leaving and coming back within 5 s: nothing new.
        r.observe(6_000, Entree(145_825_000L, "NFM", 0))
        r.observe(7_000, iss)
        assertEquals(1, r.pile("2m").size)
    }

    @Test
    fun les_trois_dernieres_rappelees_tour_a_tour() {
        val r = PileBandes.Registre()
        var t = 0L
        for (hz in listOf(145_500_000L, 145_600_000L, 145_700_000L, 145_800_000L)) t = reste(r, t, Entree(hz, "NFM", 0), 6_000)
        assertEquals(listOf(145_800_000L, 145_700_000L, 145_600_000L), r.pile("2m").map { it.hz })
        // From 70 cm: the last one; then each press the one before, round.
        assertEquals(145_800_000L, r.rappel("2m", 435_000_000L)!!.hz)
        assertEquals(145_700_000L, r.rappel("2m", 145_800_000L)!!.hz)
        assertEquals(145_600_000L, r.rappel("2m", 145_700_000L)!!.hz)
        assertEquals(145_800_000L, r.rappel("2m", 145_600_000L)!!.hz)
        // Taken back and stayed on: not doubled, the order kept.
        t = reste(r, t, Entree(145_800_000L, "NFM", 0), 6_000)
        assertEquals(listOf(145_800_000L, 145_700_000L, 145_600_000L), r.pile("2m").map { it.hz })
        // The same frequency in another mode: updated in place.
        reste(r, t, Entree(145_700_000L, "USB", 2_400), 6_000)
        assertEquals(Entree(145_700_000L, "USB", 2_400), r.pile("2m")[1])
    }

    @Test
    fun une_pile_vide_donne_la_frequence_habituelle_et_se_relit() {
        val r = PileBandes.Registre()
        assertEquals(10_489_750_000L, r.rappel("3cm", 0)!!.hz)
        assertNull(r.rappel(PileBandes.AUTRE, 0))
        reste(r, 0, Entree(137_100_000L, "NFM", 40_000), 6_000)
        val relu = PileBandes.lit(r.json(137_100_000L).toString())
        assertEquals(r.pile(PileBandes.AUTRE).map { it.hz }, relu.pile(PileBandes.AUTRE).map { it.hz })
        assertTrue(r.json(137_100_000L).getJSONArray("bandes").getJSONObject(5).getBoolean("ici"))
        assertFalse(PileBandes.lit("n'importe quoi").pile("2m").isNotEmpty())
    }
}
