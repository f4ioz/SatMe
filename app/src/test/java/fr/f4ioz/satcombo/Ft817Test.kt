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
import fr.f4ioz.satcombo.cat.CatDecode
import fr.f4ioz.satcombo.cat.Ft817Cat
import fr.f4ioz.satcombo.cat.Ft817Pair
import fr.f4ioz.satcombo.cat.Ft817Sim
import fr.f4ioz.satcombo.cat.SerialLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FT-817 pair against simulated radios. The Yaesu dialect has no address
 * or delimiter: replies can only be counted, and one missing byte silently
 * shifts everything. Note the status bit is set on **receive**, not transmit.
 */
class Ft817Test {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun rig(sim: Ft817Sim): Ft817Cat {
        val cat = Ft817Cat()
        cat.pacingMs = 0L        // no real device to spare in tests
        cat.attach(sim)
        return cat
    }

    private fun pair(rxSim: Ft817Sim, txSim: Ft817Sim): Ft817Pair {
        val p = Ft817Pair()
        p.attach(rxSim, txSim)
        p.pacingMs = 0L
        return p
    }

    /**
     * One byte at a time, as a real 4800-baud USB adapter delivers; a single
     * read once caught one or two bytes and concluded "no answer".
     */
    private class DribbleLink(private val inner: SerialLink) : SerialLink {
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean = inner.write(bytes, timeoutMs)
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            if (buf.isEmpty()) return 0
            val un = ByteArray(1)
            if (inner.read(un, timeoutMs) <= 0) return 0
            buf[0] = un[0]
            return 1
        }
        override fun close() { inner.close() }
    }

    @Test
    fun une_sequence_saine_ne_fait_rien_refuser_au_couple() = runBlocking {
        val rxSim = Ft817Sim()
        val txSim = Ft817Sim()
        val r = CatBench.runFt817Pair(rxSim, txSim)
        assertEquals("refus des postes simulés : ${r.steps}", 0, r.refusals)
        assertTrue(r.ok)
        assertEquals(145_800_000L, rxSim.hz)
        assertEquals(437_800_000L, txSim.hz)
    }

    @Test
    fun la_frequence_fait_l_aller_retour() = runBlocking {
        val sim = Ft817Sim()
        val cat = rig(sim)
        assertTrue(cat.setFrequency(435_120_000L))
        assertEquals(435_120_000L, sim.hz)
        assertEquals(435_120_000L, cat.readFrequency())
        assertEquals(0, sim.refusals)
    }

    @Test
    fun les_unites_de_hertz_disparaissent_comme_le_veut_le_poste() = runBlocking {
        // The FT-817 tunes in 10 Hz steps. Not a bug, a limit to know, or
        // Doppler correction thinks it wrote a value the radio never shows.
        val sim = Ft817Sim()
        val cat = rig(sim)
        cat.setFrequency(145_800_007L)
        assertEquals(145_800_000L, sim.hz)
        cat.setFrequency(145_800_019L)
        assertEquals(145_800_010L, sim.hz)
    }

    @Test
    fun la_lecture_rend_la_frequence_et_le_mode_ensemble() = runBlocking {
        // One query, five reply bytes: four frequency, one mode. Splitting
        // them would cost an extra round trip every second.
        val sim = Ft817Sim()
        val cat = rig(sim)
        cat.setFrequency(437_800_000L)
        assertTrue(cat.setMode("FM"))
        assertEquals(0x08, sim.mode)
        val (hz, mode) = cat.readFrequencyAndMode()!!
        assertEquals(437_800_000L, hz)
        assertEquals(0x08, mode)
    }

    @Test
    fun le_bit_d_etat_est_mis_quand_le_poste_recoit() = runBlocking {
        // Per the manual: bit 7 SET on receive. Getting it backwards means
        // writing the VFO of a radio mid-transmission.
        val sim = Ft817Sim()
        sim.transmitting = false
        val cat = rig(sim)
        assertEquals(false, cat.isTransmitting())
    }

    @Test
    fun le_bit_d_etat_s_efface_quand_le_poste_emet() = runBlocking {
        val sim = Ft817Sim()
        sim.transmitting = true
        val cat = rig(sim)
        assertEquals(true, cat.isTransmitting())
    }

    @Test
    fun on_n_ecrit_pas_sur_un_poste_qui_emet() = runBlocking {
        // Half-duplex safety, as in SatPC32: while the operator talks the
        // uplink stays put. The downlink keeps being corrected — the whole
        // point of two radios.
        val rxSim = Ft817Sim()
        val txSim = Ft817Sim()
        txSim.transmitting = true
        val p = pair(rxSim, txSim)
        val avant = txSim.hz
        p.setPair(145_805_000L, 437_805_000L)
        assertEquals("la montée a bougé pendant l'émission", avant, txSim.hz)
        assertEquals(145_805_000L, rxSim.hz)
        // As soon as PTT drops, the uplink resumes.
        txSim.transmitting = false
        p.setUplink(437_805_000L)
        assertEquals(437_805_000L, txSim.hz)
    }

    @Test
    fun le_ton_d_acces_part_en_gros_boutien() = runBlocking {
        // This encoding was already right, which makes the comparison useful:
        // same tone, two dialects, only the CI-V one was wrong.
        val sim = Ft817Sim()
        val cat = rig(sim)
        assertTrue(cat.setCtcss(885))
        assertEquals(885, sim.toneTenthHz)
        assertEquals(0x4A, sim.toneMode)
        // Frame 0x0B, byte by byte: 08 85, not 88 50.
        val ton = sim.received.last()
        assertEquals(0x0B, ton[4].toInt() and 0xFF)
        assertEquals(0x08, ton[0].toInt() and 0xFF)
        assertEquals(0x85, ton[1].toInt() and 0xFF)
        // Zero turns the tone off, nothing more sent.
        assertTrue(cat.setCtcss(0))
        assertEquals(0x8A, sim.toneMode)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun un_ton_hors_plage_ne_part_meme_pas() = runBlocking {
        val sim = Ft817Sim()
        val cat = rig(sim)
        val avant = sim.received.size
        assertTrue(!cat.setCtcss(8850))
        assertTrue(!cat.setCtcss(CatDecode.TONE_MAX_TENTH + 1))
        assertEquals("une trame est partie quand même", avant, sim.received.size)
        assertEquals(0, sim.refusals)
        // The limit itself passes.
        assertTrue(cat.setCtcss(CatDecode.TONE_MAX_TENTH))
        assertEquals(CatDecode.TONE_MAX_TENTH, sim.toneTenthHz)
    }

    @Test
    fun la_reponse_qui_arrive_octet_par_octet_est_rassemblee() = runBlocking {
        // The quietest bug in both drivers: a single read. On a real cable the
        // reply arrives in pieces, and the driver said "no answer" half the time.
        val sim = Ft817Sim()
        val cat = Ft817Cat()
        cat.pacingMs = 0L
        cat.attach(DribbleLink(sim))
        cat.setFrequency(435_500_000L)
        assertEquals(435_500_000L, cat.readFrequency())
        assertEquals(false, cat.isTransmitting())
    }

    @Test
    fun le_dialecte_yaesu_se_relit_octet_par_octet() {
        // No radio or simulator: hand-written bytes against the manual.
        assertArrayEqualsInt(intArrayOf(0x43, 0x78, 0x00, 0x00), CatDecode.yaesuFreq(437_800_000L))
        assertEquals(437_800_000L, CatDecode.yaesuFreqOf(b(0x43, 0x78, 0x00, 0x00)))
        // A status reply, both directions.
        assertTrue(CatDecode.describeYaesu(b(0x00), fromRig = true, lastOp = 0xF7).contains("émet"))
        assertTrue(CatDecode.describeYaesu(b(0x08, 0x00, 0x00, 0x00, 0x07), fromRig = false)
            .contains("FM"))
        assertNotNull(CatDecode.yaesuFreqOf(CatDecode.yaesuFreq(29_600_000L)))
    }

    @Test
    fun un_couple_non_branche_rend_null_plutot_que_d_exploser() = runBlocking {
        val p = Ft817Pair()
        assertTrue(!p.isOpen)
        assertTrue(!p.bothOpen)
        assertNull(p.readDownlink())
        p.setPair(145_800_000L, 437_800_000L)   // must do nothing, and above all not crash
        p.setCtcss(670)
        val cat = Ft817Cat()
        cat.pacingMs = 0L
        assertTrue(!cat.isOpen)
        assertNull(cat.readFrequency())
        assertNull(cat.readFrequencyAndMode())
        assertNull(cat.isTransmitting())
        assertTrue(!cat.setFrequency(435_000_000L))
        // A closed radio behaves the same.
        val sim = Ft817Sim()
        val ouvert = rig(sim)
        ouvert.close()
        assertTrue(!ouvert.isOpen)
        assertTrue(sim.isClosed)
    }

    private fun assertArrayEqualsInt(expected: IntArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("octet $i", expected[i], actual[i].toInt() and 0xFF)
    }
}
