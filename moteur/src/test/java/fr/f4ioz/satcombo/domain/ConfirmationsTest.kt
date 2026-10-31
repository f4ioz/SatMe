/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.data.StatutsQsl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Confirmations kept by source: LoTW and paper for the VUCC, eQSL counted apart, never as LoTW. */
class ConfirmationsTest {

    private fun e(t: Long, c: String, l: String, sat: String = "ISS") = LogEntry(t, sat, 25544, 0.0, 10.0, callsign = c, theirLocator = l)
    private fun s(e: LogEntry, lotw: String = "", eqsl: String = "", papier: String = "") =
        StatutsQsl.Statut(e.callsign, e.timeMs, e.satName, lotwRecu = lotw, eqslRecu = eqsl, papierRecu = papier)

    @Test fun `eQSL seul ne compte jamais pour le VUCC`() {
        val a = e(1, "F5AAA", "JN03AA"); val b = e(2, "EA4BBB", "IN80"); val c = e(3, "G4CCC", "IO91"); val d = e(4, "ON4DDD", "JO20")
        val conf = mapOf(1L to s(a, lotw = "Y"), 2L to s(b, eqsl = "Y"), 3L to s(c, papier = "Y", eqsl = "Y"))
        val k = Confirmations.bilan(listOf(a, b, c, d), conf).carres
        assertEquals(4, k.travailles)
        assertEquals(1, k.lotw); assertEquals(1, k.papier); assertEquals(2, k.eqsl)
        assertEquals("LoTW + paper", 2, k.arrl)
        assertEquals("IN80 by eQSL only", 1, k.eqslSeul)
        assertFalse(Confirmations.valideArrl(setOf(Confirmations.Source.EQSL)))
        assertTrue(Confirmations.valideArrl(setOf(Confirmations.Source.PAPIER)))
        val r = Confirmations.remarqueVucc(listOf(a, b, c, d), conf)
        assertTrue(r, r.startsWith("2 locators confirmés LoTW ou papier (LoTW 1, papier 1)") && r.contains("1 par eQSL seulement, non compté pour le VUCC"))
        assertTrue(Confirmations.REGLE.contains("pas eQSL"))
    }

    @Test fun `un locator confirme une fois meme par deux contacts, une ligne donne ses deux carres`() {
        val a = e(1, "F5AAA", "JN03"); val b = e(2, "F5BBB", "JN03"); val c = e(3, "F5CCC", "JN06,JN16")
        val conf = mapOf(1L to s(a, lotw = "Y"), 2L to s(b, papier = "Y"), 3L to s(c, lotw = "V"))
        val bl = Confirmations.bilan(listOf(a, b, c), conf)
        assertEquals(3, bl.carres.travailles); assertEquals(3, bl.carres.arrl)
        assertEquals(setOf(Confirmations.Source.LOTW, Confirmations.Source.PAPIER), bl.parCarre["JN03"])
        assertEquals(setOf(Confirmations.Source.LOTW), bl.parCarre["JN16"])
        assertEquals(listOf("lotw", "papier"), Confirmations.json(bl.parCarre.getValue("JN03")).let { j -> (0 until j.length()).map { j.getString(it) } })
    }

    @Test fun `satellites et contacts, en attente n'est pas confirme`() {
        val a = e(1, "F5AAA", "JN03", "SO-50"); val b = e(2, "F5BBB", "JN04", "SO-50"); val c = e(3, "F5CCC", "JN05", "AO-91")
        val conf = mapOf(1L to s(a, eqsl = "Y"), 2L to StatutsQsl.Statut("F5BBB", 2, "SO-50", lotwEnvoye = "Y"), 3L to s(c, papier = "Y"))
        val bl = Confirmations.bilan(listOf(a, b, c, LogEntry(9, "", 0, 0.0, 0.0, callsign = "F1TER", theirLocator = "JN18")), conf)
        assertEquals(2, bl.satellites.travailles)
        assertEquals("AO-91 by paper", 1, bl.satellites.arrl)
        assertEquals("SO-50 by eQSL only", 1, bl.satellites.eqslSeul)
        assertEquals("terrestrial contact out", 3, bl.contacts.travailles)
        assertEquals(1, bl.contacts.arrl)
        val j = Confirmations.json(bl)
        assertEquals(1, j.getJSONObject("carres").getInt("eqslSeul"))
        assertEquals(1, j.getJSONObject("carres").getInt("arrl"))
    }

    @Test fun `ce qui est parti reste avec le contact dans qso_log`() {
        val f = File.createTempFile("qso", ".json")
        try {
            val st = LogStore(f)
            val c = e(1_791_800_000_000L, "F5AAA", "JN03").copy(envoyeMs = 5)
            st.add(c)
            st.fixeEnvoi(c.timeMs, ModifEnvoi.ecrit(ModifEnvoi.empreinte(c)))
            val lu = LogStore(f).load().single()
            assertEquals(ModifEnvoi.empreinte(c), ModifEnvoi.lit(lu.envoiAdif))
            assertFalse(ModifEnvoi.modifie(lu))
            st.marqueEnvoye(c.timeMs, 7)
            assertEquals("a mark without its fields keeps the old ones", lu.envoiAdif, LogStore(f).load().single().envoiAdif)
        } finally { f.delete() }
    }
}
