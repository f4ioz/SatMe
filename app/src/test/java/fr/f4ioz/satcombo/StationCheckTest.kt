/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.StationCheck
import fr.f4ioz.satcombo.domain.StationReadiness.Niveau
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The station check's judgement of what the equipment answered. */
class StationCheckTest {

    @Test
    fun niveaux_d_un_signal() {
        assertEquals(-120.0, StationCheck.niveaux(ShortArray(1000)).rmsDbfs, 1e-9)
        val sinus = ShortArray(4410) { (16384 * kotlin.math.sin(2 * Math.PI * 1000 * it / 44100.0)).toInt().toShort() }
        val n = StationCheck.niveaux(sinus)
        assertEquals(-6.0, n.creteDbfs, 0.1)     // half scale
        assertEquals(-9.0, n.rmsDbfs, 0.2)       // a sine's RMS is 3 dB under its peak
    }

    @Test
    fun audio_silence_sature_ou_bon() {
        assertEquals(Niveau.WARNING, StationCheck.audio(StationCheck.Niveaux(-70.0, -80.0), null).niveau)
        assertEquals("rd_test_audio_sature", StationCheck.audio(StationCheck.Niveaux(-0.1, -10.0), null).raison)
        assertEquals(Niveau.OK, StationCheck.audio(StationCheck.Niveaux(-12.0, -30.0), null).niveau)
        assertEquals(Niveau.ERROR, StationCheck.audio(null, "rd_test_audio_permission").niveau)
        // A recording running: not tested, not an error.
        assertEquals(Niveau.NOT_REQUIRED, StationCheck.audio(null, "rd_test_audio_en_cours").niveau)
        assertEquals(Niveau.WARNING, StationCheck.audio(null, "rd_test_audio_usb_absente").niveau)
    }

    @Test
    fun poste_seul_ou_paire() {
        assertEquals(Niveau.NOT_REQUIRED, StationCheck.cat(false, false, null, null).niveau)
        assertEquals(Niveau.ERROR, StationCheck.cat(true, false, null, null).niveau)
        StationCheck.cat(true, false, 435_640_000L, null).let { assertEquals(Niveau.OK, it.niveau); assertEquals(listOf("435.6400"), it.args) }
        assertEquals("rd_test_cat_tx_muet", StationCheck.cat(true, true, 145_800_000L, null).raison)
        assertEquals("rd_test_cat_rx_muet", StationCheck.cat(true, true, null, 435_100_000L).raison)
        assertEquals(Niveau.OK, StationCheck.cat(true, true, 145_800_000L, 435_100_000L).niveau)
    }

    @Test
    fun rotor_gps_et_carnet() {
        assertEquals(Niveau.ERROR, StationCheck.rotor(true, null, null).niveau)
        assertEquals(listOf("218", "0"), StationCheck.rotor(true, 218.2, 0.0).args)
        assertEquals(Niveau.NOT_REQUIRED, StationCheck.gps(true, null).niveau)
        assertEquals(Niveau.WARNING, StationCheck.gps(false, 120_000L).niveau)
        assertEquals(Niveau.OK, StationCheck.gps(false, 3_000L).niveau)
        assertEquals(Niveau.OK, StationCheck.carnet(true, "OK").niveau)
        StationCheck.carnet(true, "clé refusée").let { assertEquals(Niveau.ERROR, it.niveau); assertTrue("clé refusée" in it.args) }
        assertEquals(Niveau.NOT_REQUIRED, StationCheck.carnet(false, null).niveau)
    }
}
