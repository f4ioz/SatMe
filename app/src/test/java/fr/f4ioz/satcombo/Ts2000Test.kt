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
import fr.f4ioz.satcombo.cat.Ts2000
import fr.f4ioz.satcombo.cat.Ts2000Lien
import fr.f4ioz.satcombo.cat.Ts2000Sim
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TS-2000 in SAT mode, against what the author's rig answered (see [Ts2000]). */
class Ts2000Test {

    @Test
    fun les_reponses_du_vrai_poste_se_lisent() {
        assertEquals(435_659_600L, Ts2000.frequence("FA00435659600;", "A"))
        assertEquals(145_950_640L, Ts2000.frequence("FB00145950640;", "B"))
        assertNull(Ts2000.frequence("?;", "A"))
        assertEquals("FA00435660000;", Ts2000.ecrit("A", 435_660_000L))
        assertTrue(Ts2000.enSatellite("SA1010110RS44;")); assertFalse(Ts2000.enSatellite("SA0010110RS44;"))
        assertEquals("RS44", Ts2000.nomSatellite("SA1010110RS44;"))
        // IF in SAT mode, receiving in USB; and on 40 m in LSB.
        assertEquals(false, Ts2000.emission("IF00435659600    -0001000000022000010;"))
        assertEquals(false, Ts2000.emission("IF00007074190    -0001000000010000080;"))
        assertEquals(true, Ts2000.emission("IF00435659600    -0001000000122000010;"))
        assertNull(Ts2000.emission("?;"))
        assertEquals(0, Ts2000.smetre("SM00000;")); assertEquals(15, Ts2000.smetre("SM00015;"))
    }

    @Test
    fun le_smetre_sur_l_echelle_icom() {
        assertEquals(0, Ts2000.versIcom(0)); assertEquals(120, Ts2000.versIcom(15)); assertEquals(241, Ts2000.versIcom(30))
        assertEquals("S9", fr.f4ioz.satcombo.domain.JournalPassage.libelleS(Ts2000.versIcom(15)))
        assertEquals("S9+60", fr.f4ioz.satcombo.domain.JournalPassage.libelleS(Ts2000.versIcom(30)))
    }

    @Test
    fun les_tons_kenwood_numerotes_depuis_un() {
        assertEquals(1, Ts2000.numeroTon(670)); assertEquals(13, Ts2000.numeroTon(1000)); assertEquals(42, Ts2000.numeroTon(2541))
    }

    @Test
    fun jamais_une_commande_d_emission() {
        val l = Ts2000Lien(); l.attach(Ts2000Sim()); l.pacingMs = 0
        for (c in listOf("TX;", "TX0;", "KY TEST;")) {
            val refuse = runCatching { runBlocking { l.commande(c) } }.isFailure
            assertTrue(c, refuse)
        }
    }

    @Test
    fun la_paire_suit_le_doppler_sur_fa_et_fb() = runBlocking {
        val sim = Ts2000Sim()
        val p = Ft817Pair(); p.configureTs2000(); p.rx.attach(sim); p.pacingMs = 0
        assertTrue(p.lienTs2000!!.estUnTs2000())
        assertTrue(Ts2000.enSatellite(p.lienTs2000!!.satellite()))
        p.setPair(435_645_120L, 145_964_880L)
        assertEquals(435_645_120L, sim.fa); assertEquals(145_964_880L, sim.fb)
        // Linear: the operator turns the RX knob, the pair reads it back.
        sim.fa = 435_650_000L
        assertEquals(435_650_000L, p.readDownlink())
        p.setUplink(145_960_000L); assertEquals(145_960_000L, sim.fb)
        // Each side its mode: the uplink's with control toggled onto it, then given back.
        p.setModes("USB", "LSB"); assertEquals(2, sim.mode); assertEquals(1, sim.modeMontee)
        assertFalse(sim.controleMontee)
        // Not written while transmitting (the operator's PTT).
        sim.emet = true
        p.setUplink(145_950_000L); assertEquals(145_960_000L, sim.fb)
        sim.emet = false
        assertEquals(0, sim.tentativesEmission); assertEquals(0, sim.refus)
        // The S-meter, on Icom's scale.
        sim.smetre = 15; assertEquals(120, p.lienTs2000!!.smetre())
    }

    @Test
    fun le_banc_d_essai_passe() = runBlocking {
        val r = CatBench.runTs2000()
        assertTrue(r.summary, r.ok); assertEquals(0, r.refusals)
    }

    @Test
    fun en_fm_la_montee_passe_en_fm_avec_son_ton() = runBlocking {
        // SO-50 on 07/10: the uplink had stayed in LSB, the satellite heard nothing.
        val sim = Ts2000Sim().apply { mode = 4; modeMontee = 1 }
        val p = Ft817Pair(); p.configureTs2000(); p.rx.attach(sim); p.pacingMs = 0
        p.setModes("FM", "FM")
        assertEquals(4, sim.mode); assertEquals(4, sim.modeMontee)
        p.setCtcss(670)
        assertTrue(sim.tonOn); assertEquals(1, sim.ton)
        // Control always back on the downlink (the operator's knob), nothing refused, nothing keyed.
        assertFalse(sim.controleMontee); assertEquals(0, sim.refus); assertEquals(0, sim.tentativesEmission)
        assertEquals(false, Ts2000.controleMontee("SA1010110RS44;")); assertEquals(true, Ts2000.controleMontee("SA1011110RS44;"))
    }
}
