/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.domain.SstvIss
import org.junit.Assert.assertEquals
import org.junit.Test

/** ISS SSTV on its own: 10 s before AOS to 5 s after LOS, up to the chosen pass. */
class SstvIssTest {

    private val h = 3_600_000L
    private val passes = listOf(0L * h to 0L * h + 600_000, 2 * h to 2 * h + 600_000, 4 * h to 4 * h + 600_000)

    @Test
    fun fenetres_jusqu_au_passage_choisi() {
        val f = SstvIss.fenetres(passes, -h, dernierAos = 2 * h)
        assertEquals(listOf(-10_000L to 605_000L, 2 * h - 10_000 to 2 * h + 605_000), f)
    }

    @Test
    fun un_passage_en_cours_compte_un_passage_fini_non() {
        // During the first pass: still recorded.
        assertEquals(3, SstvIss.fenetres(passes, 300_000L, 4 * h).size)
        // After its LOS + 5 s: gone.
        assertEquals(2, SstvIss.fenetres(passes, 606_000L, 4 * h).size)
    }

    @Test
    fun le_passage_choisi_compte_meme_recalcule_quelques_secondes_plus_tard() {
        // Chosen at its AOS 0; computed again, the same pass rises 3 s later.
        val recalcules = passes.map { (it.first + 3_000L) to (it.second + 3_000L) }
        assertEquals(1, SstvIss.fenetres(recalcules, -h, dernierAos = 0L).size)
        assertEquals(2, SstvIss.fenetres(recalcules, -h, dernierAos = 2 * h).size)
    }

    private fun tx(desc: String, mode: String?, dl: Long?) = Transmitter(desc, mode, null, null, dl, dl, false, true, "Transmitter")

    @Test
    fun le_transpondeur_sstv_de_l_iss() {
        val liste = listOf(tx("Mode V APRS", "AFSK", 145_825_000L), tx("FM Voice Repeater", "FM", 437_800_000L),
            tx("SSTV", "FM", 145_800_000L))
        assertEquals(2, SstvIss.indexSstv(liste))
        // Without the word SSTV: the 145.800 MHz downlink.
        assertEquals(1, SstvIss.indexSstv(listOf(liste[0], tx("Downlink", "FM", 145_800_000L))))
        assertEquals(-1, SstvIss.indexSstv(liste.take(2)))
    }
}
