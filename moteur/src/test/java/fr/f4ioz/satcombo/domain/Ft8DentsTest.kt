/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.ft.EnregistrementFt
import fr.f4ioz.satcombo.ft.EnregistrementFt.Emission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CAT's Doppler steps as the decoder hears them. On a LEO pass the downlink moves about 20 Hz/s; written
 * by 20 Hz steps, every signal slides between two writes and jumps back at each: a sawtooth across the whole
 * transmission that no single drift explains. FT8 (6.25 Hz tones) is then not read at all, FT4 (20.8 Hz)
 * loses several dB; by 4 Hz steps (`StationCat.pasDopplerHz` while FT8/FT4 listens) both are read.
 */
class Ft8DentsTest {

    private fun lus(mode: String, snr: Double, pasHz: Double, graines: IntRange): Int {
        val d = if (mode == "FT4") 7_500L else 15_000L
        val texte = "CQ F5LMG IN88"
        return graines.count { g ->
            val a = EnregistrementFt.fabrique(0L, d / 1000.0, 12_000, listOf(Emission(texte, mode, 0L, 1500.0, snr, 0.5)),
                graine = g.toLong(), decalage = EnregistrementFt.dents(20.0, pasHz, g * 0.137))
            Ft8Decodeur.decode(a, 12_000.0, if (mode == "FT4") Ft8Signal.FT4 else Ft8Signal.FT8, profondeur = Ft8Decodeur.Profondeur.PROFONDE)
                .any { it.message.brut == texte }
        }
    }

    @Test
    fun par_pas_de_20_hz_le_ft8_n_est_pas_lu_par_pas_de_4_hz_il_l_est() {
        assertEquals("20 Hz", 0, lus("FT8", -8.0, 20.0, 1..3))
        assertEquals("4 Hz", 3, lus("FT8", -12.0, 4.0, 1..3))
    }

    @Test
    fun par_pas_de_4_hz_le_ft4_faible_est_lu() {
        assertTrue(lus("FT4", -12.0, 4.0, 1..4) >= 4)
    }
}
