/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatBench
import fr.f4ioz.satcombo.cat.Ft817Pair
import fr.f4ioz.satcombo.cat.Ft817Sim
import fr.f4ioz.satcombo.cat.Ic705Cat
import fr.f4ioz.satcombo.cat.Ic705Sim
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FT-817 + IC-705: two protocols in one station. Checked against simulators
 * until the real IC-705 is on the bench.
 */
class PaireIc705Test {

    private fun ic705(sim: Ic705Sim) = Ic705Cat().also { it.attach(sim); it.pacingMs = 0 }

    @Test
    fun l_ic705_seul_frequence_mode_ton_et_etat() = runBlocking {
        val sim = Ic705Sim(); val r = ic705(sim)
        assertTrue(r.setFrequency(145_850_000L))
        assertEquals(145_850_000L, r.readFrequency())
        assertTrue(r.setMode("FM")); assertEquals(0x05, sim.mode)
        assertTrue(r.setCtcss(670)); assertTrue(sim.toneOn); assertEquals(670, sim.toneTenthHz)
        assertTrue(r.setCtcss(0)); assertFalse(sim.toneOn)
        assertEquals(false, r.isTransmitting())
        sim.transmitting = true
        assertEquals(true, r.isTransmitting())
        assertEquals(0, sim.refusals)
    }

    @Test
    fun l_echo_usb_de_l_ic705_ne_trompe_pas_la_lecture() = runBlocking {
        val sim = Ic705Sim().also { it.echo = true }; val r = ic705(sim)
        r.setFrequency(435_300_000L)
        assertEquals(435_300_000L, r.readFrequency())
    }

    @Test
    fun le_banc_ic705_en_reception_ft817_en_emission() = runBlocking {
        val rx = Ic705Sim(); val tx = Ft817Sim()
        val rep = CatBench.runFt817Ic705(rx, tx, downlinkHz = 435_300_000L, uplinkHz = 145_850_000L)
        assertEquals(0x05, rx.mode)
        assertTrue(rep.steps.joinToString(" / "), rep.ok)
        // Nothing only an IC-9700 understands (satellite mode, MAIN/SUB).
        assertEquals(0, rep.refusals)
        assertEquals(435_300_000L, rx.hz)
        assertEquals(145_850_000L, tx.hz)
    }

    @Test
    fun on_n_ecrit_pas_sur_le_ft817_qui_emet() = runBlocking {
        val rx = Ic705Sim(); val tx = Ft817Sim()
        val p = Ft817Pair().apply { configure(rxIc705 = true, txIc705 = false); attach(rx, tx); pacingMs = 0 }
        tx.transmitting = true
        p.setPair(435_300_000L, 145_850_000L)
        assertEquals(435_300_000L, rx.hz)
        assertTrue("montée écrite pendant l'émission", tx.hz != 145_850_000L)
    }

    @Test
    fun ic705_en_emission_le_ton_part_sur_lui() = runBlocking {
        val rx = Ft817Sim(); val tx = Ic705Sim()
        val p = Ft817Pair().apply { configure(rxIc705 = false, txIc705 = true); attach(rx, tx); pacingMs = 0 }
        p.setPair(145_800_000L, 437_800_000L)
        p.setCtcss(670)
        assertEquals(437_800_000L, tx.hz)
        assertEquals(670, tx.toneTenthHz)
        tx.transmitting = true
        p.setUplink(437_810_000L)
        assertEquals("montée écrite pendant l'émission", 437_800_000L, tx.hz)
        assertEquals(0, tx.refusals + rx.refusals)
    }

    @Test
    fun le_banc_ic705_en_emission() = runBlocking {
        val icom = Ic705Sim(); val yaesu = Ft817Sim()
        val rep = CatBench.runFt817Ic705(icom, yaesu, ic705Emet = true,
            downlinkHz = 145_800_000L, uplinkHz = 437_800_000L)
        assertTrue(rep.steps.joinToString(" / "), rep.ok)
        assertEquals(437_800_000L, icom.hz)
        assertEquals(670, icom.toneTenthHz)
        assertEquals(145_800_000L, yaesu.hz)
    }
}
