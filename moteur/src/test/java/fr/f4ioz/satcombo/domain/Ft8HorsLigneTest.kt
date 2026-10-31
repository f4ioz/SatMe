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
 * A recording read again after the fact: cut on the UTC slots from a start that is not one, a station a little
 * early or late, a LEO pass's drift — as on RS-44 on 27 October 2026, whose FT4 the live listening (in FT8) never read.
 */
class Ft8HorsLigneTest {

    /** 16:29:31 UTC, the second of the recording's name; its sound really starts 0.6 s later. */
    private val nom = 1_793_118_571_000L
    private val vrai = nom + 600L
    private fun tranche(n: Int, d: Long) = (vrai / d + 1 + n) * d

    private val ft4 = listOf(
        Emission("CQ F5LMG IN88", "FT4", tranche(0, 7_500L), 1744.0, -8.0, 0.2),
        Emission("F5LMG OH5MWL +06", "FT4", tranche(1, 7_500L), 949.0, -9.0, 0.05, penteHzS = 1.5),
        Emission("YO2CMI DH5YBE R-13", "FT4", tranche(1, 7_500L), 2080.0, -10.0, 0.8),
        Emission("CQ PD1BHZ JO22", "FT4", tranche(2, 7_500L), 1817.0, -7.0, 0.4, penteHzS = -2.0),
    )

    private fun audio(e: List<Emission>, dureeS: Double = 32.0) =
        // The CAT following the downlink by 4 Hz steps at 20 Hz/s (the fine steps SatMe now writes while FT8/FT4 listens).
        EnregistrementFt.fabrique(vrai, dureeS, 12_000, e, graine = 7, decalage = EnregistrementFt.dents(-20.0, 4.0))

    @Test
    fun le_ft4_d_un_enregistrement_est_relu_sur_ses_tranches_utc() {
        val a = audio(ft4)
        // Read with the name's start: a second's margin each side absorbs the 0.6 s.
        val l = Ft8HorsLigne.decode(a, 12_000, nom, nom, nom + 32_000, "FT4")
        for (e in ft4) {
            val d = l.firstOrNull { it.decode.message.brut == e.texte }
            assertTrue("${e.texte} : $l", d != null)
            assertEquals(e.texte, e.trancheMs, d!!.trancheMs)
            assertEquals("FT4", d.mode)
            // The offset as the name's clock sees it: 0.6 s later than sent.
            assertEquals(e.texte, e.dtS - 0.6, d.dtS, 0.12)
            assertEquals(e.texte, e.hz, d.decode.frequenceHz, 4.0)
        }
        assertEquals("rien en FT8", 0, Ft8HorsLigne.decode(a, 12_000, nom, nom, nom + 32_000, "FT8").size)
    }

    @Test
    fun une_zone_est_lue_dans_les_deux_modes() {
        val e = ft4.take(2) + Emission("CQ M0NKC IO91", "FT8", tranche(1, 15_000L), 1088.0, -8.0, 0.5)
        val zone = ZonesNumeriques.Zone(nom, nom + 50_000, "FT4", 3, 900.0, 1800.0)
        val l = Ft8HorsLigne.decodeZone(audio(e, 50.0), 12_000, nom, zone, nom, nom + 50_000)
        assertEquals(setOf("CQ F5LMG IN88" to "FT4", "F5LMG OH5MWL +06" to "FT4", "CQ M0NKC IO91" to "FT8"),
            l.map { it.decode.message.brut to it.mode }.toSet())
        assertEquals("par tranche", l.sortedBy { it.trancheMs }.map { it.trancheMs }, l.map { it.trancheMs })
    }

    @Test
    fun une_tranche_que_l_enregistrement_ne_couvre_pas_est_laissee() {
        // Two seconds of sound: no slot is whole in it.
        val a = FloatArray(24_000)
        assertEquals(0, Ft8HorsLigne.decode(a, 12_000, nom, nom - 60_000, nom + 60_000, "FT4").size)
    }
}
