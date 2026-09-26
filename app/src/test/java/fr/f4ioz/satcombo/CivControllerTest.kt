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
import fr.f4ioz.satcombo.cat.CatJournal
import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ic9700Sim
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The CI-V driver, against a simulated IC-9700.
 *
 * Several tests cover bugs that really cost passes: a tone encoded backwards,
 * a reply searched for in our own echo, and a single read that truncated
 * everything after an ack. None shows on the radio's front panel — that is
 * the problem.
 */
class CivControllerTest {

    private fun bench(sim: Ic9700Sim = Ic9700Sim()): CivController {
        val cat = CivController()
        cat.pacingMs = 0L        // no real device to spare in tests
        cat.attach(sim)
        return cat
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun une_sequence_saine_ne_fait_rien_refuser_au_poste() = runBlocking {
        // The claim we could never make before: the radio understood
        // everything. Not "nothing crashed" — understood.
        val sim = Ic9700Sim()
        val r = CatBench.runIc9700(sim)
        assertEquals("refus du poste simulé : ${r.steps}", 0, r.refusals)
        assertTrue(r.ok)
        assertTrue(sim.satMode)
    }

    @Test
    fun le_couple_satellite_atterrit_sur_les_bonnes_bandes() = runBlocking {
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)
        cat.setSatellitePair(435_500_000L, 145_900_000L)
        assertEquals(435_500_000L, sim.mainHz)
        assertEquals(145_900_000L, sim.subHz)
        // End on MAIN, so the operator's dial stays on receive.
        assertTrue("le poste est resté sur la bande secondaire", !sim.onSub)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun l_echo_du_bus_ne_se_fait_plus_prendre_pour_une_reponse() = runBlocking {
        // With "CI-V USB Echo Back" on, the radio echoes the query before the
        // reply. The old code searched the raw buffer for 0x03, found its own
        // query and read five bytes of nothing after it.
        val sim = Ic9700Sim()
        sim.echo = true
        val cat = bench(sim)
        cat.setFrequency(435_123_450L)
        assertEquals(435_123_450L, cat.readFrequency())
    }

    @Test
    fun une_reponse_precedee_d_un_accuse_n_est_plus_tronquee() = runBlocking {
        // Some radios ack then reply. A single read returned the ack and lost
        // the reply.
        val sim = Ic9700Sim()
        sim.ackBeforeReply = true
        sim.echo = true          // both at once
        // The simulator enforces the real rule: never both VFOs on one band.
        // MAIN starts on 435, SUB on 145; writing 145 to MAIN would be refused.
        // This test is about frame splitting, not bands, so write where the
        // radio already is.
        val cat = bench(sim)
        cat.setFrequency(435_875_000L)
        assertEquals(435_875_000L, cat.readFrequency())
    }

    @Test
    fun le_ton_de_so_50_est_enfin_accepte() = runBlocking {
        // 67.0 Hz for SO-50, 74.4 Hz to arm it: both pass.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        assertTrue(cat.setToneFreq(670))
        assertEquals(670, sim.toneTenthHz)
        assertTrue(cat.setToneFreq(744))
        assertEquals(744, sim.toneTenthHz)
        assertEquals(744, cat.readToneFreq())
        assertEquals(0, sim.refusals)
    }

    @Test
    fun l_ancien_encodage_du_ton_est_refuse_par_le_poste() = runBlocking {
        // Byte-level proof: replay what the old code sent for 88.5 Hz —
        // 1B 00 00 88 50 — and the radio refuses it, like the real one. Back
        // then nothing said so: an ack still arrived, and the app showed "tone
        // set" while the repeater stayed silent.
        val sim = Ic9700Sim()
        sim.write(b(0xFE, 0xFE, 0xA2, 0xE0, 0x1B, 0x00, 0x00, 0x88, 0x50, 0xFD), 500)
        assertEquals(1, sim.refusals)
        assertEquals(0, sim.toneTenthHz)

        // The correct encoding passes.
        sim.write(b(0xFE, 0xFE, 0xA2, 0xE0, 0x1B, 0x00, 0x00, 0x08, 0x85, 0xFD), 500)
        assertEquals(1, sim.refusals)
        assertEquals(885, sim.toneTenthHz)
    }

    @Test
    fun un_ton_hors_plage_ne_part_meme_pas() = runBlocking {
        // Second guard, before the radio: the driver refuses to send what no
        // radio would accept.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        val avant = sim.received.size
        assertTrue(!cat.setToneFreq(8850))
        assertTrue(!cat.setToneFreq(0))
        assertEquals("une trame est partie quand même", avant, sim.received.size)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun la_commande_25_est_refusee_sur_la_bande_secondaire_en_mode_satellite() = runBlocking {
        // This is why the pair goes via 0x07 D0/D1 then 0x05, not 0x25. A
        // refusal is far better than applying the command to the wrong band.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)
        assertTrue("le poste aurait dû refuser", !cat.setVfoFreq(145_900_000L, unselected = true))
        assertEquals(1, sim.refusals)
        assertEquals(145_000_000L, sim.subHz)
        // Outside satellite mode the same command is legitimate.
        cat.setSatelliteMode(false)
        assertTrue(cat.setVfoFreq(145_900_000L, unselected = true))
        assertEquals(145_900_000L, sim.subHz)
        assertEquals(1, sim.refusals)
    }

    @Test
    fun sans_fil_serie_le_pilote_rend_null_plutot_que_d_exploser() = runBlocking {
        val cat = CivController()
        cat.pacingMs = 0L
        assertTrue(!cat.isOpen)
        assertNull(cat.readFrequency())
        assertNull(cat.readVfoFreq(unselected = true))
        assertNull(cat.sendAndRead(0x03))
        assertTrue(!cat.setFrequency(435_000_000L))
        // A closed radio behaves the same.
        val sim = Ic9700Sim()
        val ouvert = bench(sim)
        ouvert.close()
        assertTrue(!ouvert.isOpen)
        assertTrue(sim.isClosed)
    }

    @Test
    fun le_changement_de_v_sur_u_a_u_sur_v_ne_fait_rien_refuser() = runBlocking {
        // The reported bug in full: RS-44 (down 435, up 145) then AO-91 (down
        // 145, up 435). The simulator refuses what the real radio refuses (both
        // VFOs on one band), so a single refusal fails this test. There used to
        // be one, leaving both VFOs on 435.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)

        cat.setSatellitePair(435_660_000L, 145_940_000L)
        assertEquals(435_660_000L, sim.mainHz)
        assertEquals(145_940_000L, sim.subHz)

        cat.setSatellitePair(145_960_000L, 435_250_000L)
        assertEquals("la descente n'a pas changé de bande", 145_960_000L, sim.mainHz)
        assertEquals("la montée n'a pas changé de bande", 435_250_000L, sim.subHz)

        // And back again, since one pass follows another.
        cat.setSatellitePair(435_660_000L, 145_940_000L)
        assertEquals(435_660_000L, sim.mainHz)
        assertEquals(145_940_000L, sim.subHz)

        assertEquals("le poste a refusé quelque chose", 0, sim.refusals)
        assertTrue("le poste est resté sur la bande secondaire", !sim.onSub)
    }

    @Test
    fun le_doppler_ne_relit_le_poste_qu_une_fois_par_passage() = runBlocking {
        // Reading both bands costs four frames; at ten cycles a second it would
        // flood the bus. Only on the first pair, then never while tracking the
        // same satellite.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)
        cat.setSatellitePair(435_660_000L, 145_940_000L)
        val apresLePremier = sim.received.size
        repeat(20) { i ->
            cat.setSatellitePair(435_660_000L - i * 100L, 145_940_000L + i * 30L)
        }
        val parTour = (sim.received.size - apresLePremier) / 20
        assertTrue("trop de trames par tour de Doppler : $parTour", parTour <= 4)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun changer_de_satellite_fait_oublier_ce_qu_on_croyait_savoir() = runBlocking {
        // Between passes the operator touches the radio. What we thought we
        // knew is worthless, and forgetBands() admits it.
        val sim = Ic9700Sim()
        val cat = bench(sim)
        cat.setSatelliteMode(true)
        cat.setSatellitePair(435_660_000L, 145_940_000L)
        val avant = sim.received.size
        cat.forgetBands()
        cat.setSatellitePair(435_660_000L, 145_940_000L)
        assertTrue("le poste n'a pas été relu après l'oubli",
            sim.received.size - avant > 4)
        assertEquals(0, sim.refusals)
    }

    @Test
    fun le_journal_enregistre_les_deux_sens_en_clair() = runBlocking {
        CatJournal.clear()
        CatJournal.enabled = true
        try {
            val sim = Ic9700Sim()
            val cat = bench(sim)
            cat.selectMainSub(true)
            cat.setFrequency(145_900_000L)
            cat.readFrequency()
            val e = CatJournal.entries.value
            assertTrue("journal vide", e.size >= 4)
            assertTrue(e.any { it.out && it.text.contains("secondaire") })
            assertTrue(e.any { it.out && it.text.contains("145.90000 MHz") })
            assertTrue(e.any { !it.out && it.text.contains("accusé") })
            assertTrue(e.any { !it.out && it.text.contains("fréquence : 145.90000 MHz") })
            // Hex stays available for byte-by-byte checking.
            assertTrue(e.first().hex.startsWith("FE FE A2 E0"))
            // The log does not grow without bound.
            assertTrue(e.size <= CatJournal.DEPTH)
        } finally {
            CatJournal.enabled = false
            CatJournal.clear()
        }
    }

    @Test
    fun le_journal_ecrit_les_frequences_avec_un_point_meme_en_francais() = runBlocking {
        // A French phone used to log "145,90000 MHz" while the rest of the app
        // shows "145.90000": the same frequency read two ways on one screen.
        val defaut = Locale.getDefault()
        CatJournal.clear()
        CatJournal.enabled = true
        try {
            Locale.setDefault(Locale.FRANCE)
            val cat = bench()
            cat.selectMainSub(true)
            cat.setFrequency(145_900_000L)
            cat.readFrequency()
            val e = CatJournal.entries.value
            assertTrue("envoi absent : ${e.map { it.text }}",
                e.any { it.out && it.text.contains("145.90000 MHz") })
            assertTrue("relecture absente : ${e.map { it.text }}",
                e.any { !it.out && it.text.contains("fréquence : 145.90000 MHz") })
        } finally {
            Locale.setDefault(defaut)
            CatJournal.enabled = false
            CatJournal.clear()
        }
    }
}
