/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatJournal
import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ft817Cat
import fr.f4ioz.satcombo.cat.Ft817Sim
import fr.f4ioz.satcombo.cat.Ic9700Sim
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The CAT frame log.
 *
 * Without it, a bad pass left nothing to look at but a polite message. A log
 * that drops lines, or keeps too many, is worse than none: it sends you
 * looking in the wrong place.
 */
class CatJournalTest {

    @Before
    fun avant() { CatJournal.clear(); CatJournal.enabled = false }

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    @Test
    fun le_journal_est_muet_tant_qu_on_ne_le_demande_pas() {
        // It runs inside the Doppler loop several times a second for a whole
        // pass. When off, it must cost nothing and keep nothing.
        CatJournal.log(true, byteArrayOf(0xFE.toByte(), 0xFE.toByte()), "quelque chose")
        assertTrue(CatJournal.entries.value.isEmpty())
        CatJournal.enabled = true
        CatJournal.log(true, byteArrayOf(0xFE.toByte(), 0xFE.toByte()), "quelque chose")
        assertEquals(1, CatJournal.entries.value.size)
    }

    @Test
    fun le_journal_ne_grossit_pas_indefiniment() {
        // Bounded depth: enough to cover the start of a pass, small enough to
        // ignore on a phone.
        CatJournal.enabled = true
        repeat(CatJournal.DEPTH + 25) { i ->
            CatJournal.log(i % 2 == 0, byteArrayOf(i.toByte()), "trame $i")
        }
        val e = CatJournal.entries.value
        assertEquals(CatJournal.DEPTH, e.size)
        // The oldest lines go, not the newest.
        assertEquals("trame 25", e.first().text)
        assertEquals("trame ${CatJournal.DEPTH + 24}", e.last().text)
    }

    @Test
    fun l_ordre_d_arrivee_est_conserve_et_l_horodatage_avec() {
        // An unordered log is useless: what you want is what preceded the refusal.
        CatJournal.enabled = true
        CatJournal.log(true, byteArrayOf(0x01), "question", tMs = 1_000L)
        CatJournal.log(false, byteArrayOf(0x02), "réponse", tMs = 1_040L)
        CatJournal.log(true, byteArrayOf(0x03), "suite", tMs = 1_080L)
        val e = CatJournal.entries.value
        assertEquals(listOf("question", "réponse", "suite"), e.map { it.text })
        assertEquals(listOf(true, false, true), e.map { it.out })
        assertEquals(listOf(1_000L, 1_040L, 1_080L), e.map { it.tMs })
        assertEquals("01", e.first().hex)
    }

    @Test
    fun on_peut_vider_le_journal_sans_l_eteindre() {
        CatJournal.enabled = true
        CatJournal.log(true, byteArrayOf(0x01), "avant")
        assertEquals(1, CatJournal.entries.value.size)
        CatJournal.clear()
        assertTrue(CatJournal.entries.value.isEmpty())
        // Still on: clearing is not disabling.
        CatJournal.log(true, byteArrayOf(0x02), "après")
        assertEquals(1, CatJournal.entries.value.size)
    }

    @Test
    fun les_deux_dialectes_tiennent_dans_le_meme_journal() = runBlocking {
        // The point: an operator with two radios of different brands reads one
        // list in plain language, no manual needed. The hex stays underneath
        // for byte counting.
        CatJournal.enabled = true
        val icom = Ic9700Sim()
        val civ = CivController()
        civ.pacingMs = 0L
        civ.attach(icom)
        civ.setSatelliteMode(true)

        val yaesu = Ft817Sim()
        val ft = Ft817Cat()
        ft.pacingMs = 0L
        ft.attach(yaesu)
        ft.setMode("FM")

        val e = CatJournal.entries.value
        assertTrue("le CI-V n'a rien laissé", e.any { it.text.contains("mode satellite activé") })
        assertTrue("l'accusé n'est pas journalisé", e.any { !it.out && it.text.contains("accusé") })
        assertTrue("le Yaesu n'a rien laissé", e.any { it.text.contains("mode ← FM") })
        assertTrue(e.any { it.hex.startsWith("FE FE A2 E0 16") })
        assertTrue(e.any { it.hex == "08 00 00 00 07" })
    }
}
