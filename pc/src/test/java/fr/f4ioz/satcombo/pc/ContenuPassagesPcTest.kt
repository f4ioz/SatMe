/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.JournalFt
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.ZonesNumeriques
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The journal list's badges: each pass's kinds counted from the files kept beside it (no recording read), and
 * counted again when one of those files changes.
 */
class ContenuPassagesPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-journal-contenu-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    /** 27/10/2026 16:29:31 UTC. */
    private val t0 = 1_793_118_571_000L

    private fun ecrit(chemin: String, texte: String) = File(dossier, chemin).apply { parentFile.mkdirs(); writeText(texte) }

    @Test
    fun la_liste_dit_ce_que_chaque_passage_contient() {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        val log = LogStore(File(dossier, "qso_log.json"))
        log.add(LogEntry(t0 + 60_000L, "RS-44", 44909, 0.0, 0.0, callsign = "F1ABC", mode = "USB"))
        log.add(LogEntry(t0 + 120_000L, "RS-44", 44909, 0.0, 0.0, callsign = "F2ABC", mode = "CW"))
        val carnet = CarnetPc(st, log)
        val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), File(dossier, "sstv"))
        val j = JournalPc(st, carnet, son, File(dossier, "journal"))
        val web = JournalWebPc(j, st, dossier)

        val nom = "SatMe_RS-44_20261027_162931Z.wav"
        val e = JournalPassage.Entree(44909, "RS-44", t0 - 300_000L, t0 + 600_000L, "JN18FS", enregistrements = listOf(nom))
        // Another pass, a day later, with nothing.
        val vide = JournalPassage.Entree(44909, "RS-44", t0 + 86_400_000L, t0 + 86_400_000L + 600_000L, "JN18FS")
        j.rangement.enregistre(e); j.rangement.enregistre(vide)

        // FT4 messages kept with the pass, and its recording's zones (an FT4 one, read; a narrow one, not).
        j.ft.garde(e, (0 until 3).map { JournalFt.Decode(t0 + it * 7_500L, "FT4", -8, 0.2, 1500 + it, "CQ F${it}AAA JN18", "F${it}AAA", "", "JN18", false, 44909) })
        ecrit("enregistrements/SatMe_RS-44_20261027_162931Z.numerique", ZonesNumeriques.ecrit(listOf(
            ZonesNumeriques.Zone(t0, t0 + 30_000L, ZonesNumeriques.FT4, 4, 1400.0, 1600.0),
            ZonesNumeriques.Zone(t0 + 100_000L, t0 + 140_000L, ZonesNumeriques.NUM, 3, 900.0, 1000.0)), t0))
        // APRS: two frames heard, one sent (left out), one long after.
        ecrit("aprs/2026-10-27.txt", "${t0 + 10_000}\tRS-44\tAAAA\n${t0 + 20_000}\tRS-44\tBBBB\n${t0 + 30_000}\t${AprsPc.EMIS}\tCCCC\n${t0 + 9_000_000}\t\tDDDD\n")
        // Telemetry: two frames of the satellite (one with an SSDV piece), a test frame, another satellite's.
        ecrit("telemesure/paquets/20261027-1625-RS-44.jsonl", listOf(
            JSONObject().put("t", t0 + 1_000).put("sat", "RS-44").put("norad", 44909),
            JSONObject().put("t", t0 + 2_000).put("sat", "RS-44").put("norad", 44909).put("ssdv", org.json.JSONArray().put(JSONObject().put("cle", "RS44-1").put("n", 0))),
            JSONObject().put("t", t0 + 3_000).put("sat", "RS-44").put("norad", 44909).put("essai", true),
            JSONObject().put("t", t0 + 4_000).put("sat", "SO-50").put("norad", 27607)).joinToString("\n") + "\n")
        // An over written down, and a DATV recording made during the pass.
        ecrit("phonie/2026-10-27.jsonl", JSONObject().put("t", t0 + 50_000).put("satellite", "RS-44").put("texte", "bonjour").toString() + "\n")
        ecrit("enregistrements/SatMe_DATV_20261027_163000Z_10-491.ts", "x")

        fun liste(): Map<String, JSONObject> {
            val l = JSONObject(String(web.sert("/liste") { "" }.corps)).getJSONArray("passages")
            return (0 until l.length()).associate { l.getJSONObject(it).getString("id") to l.getJSONObject(it).getJSONObject("c") }
        }
        val c = liste().getValue(e.id)
        assertEquals(c.toString(), 3, c.getInt("FT4"))
        assertEquals(1, c.getInt("NUM"))
        assertEquals(2, c.getInt("APRS"))
        assertEquals(2, c.getInt("TLM"))
        assertEquals(1, c.getInt("SSDV"))
        // One voice contact, one over written down.
        assertEquals(2, c.getInt("PHONIE"))
        assertEquals(1, c.getInt("CW"))
        assertEquals(1, c.getInt("DATV"))
        assertFalse(c.has("SSTV")); assertFalse(c.has("FT8"))
        // The empty pass: no badge at all.
        assertEquals(0, liste().getValue(vide.id).length())

        // A file changes (a re-reading found FT8): counted again, the rest kept.
        j.ft.garde(e, listOf(JournalFt.Decode(t0 + 15_000L, "FT8", -10, 0.1, 1200, "CQ F9ZZZ JN18", "F9ZZZ", "", "JN18", false, 44909)))
        val c2 = liste().getValue(e.id)
        assertEquals(1, c2.getInt("FT8")); assertEquals(3, c2.getInt("FT4")); assertEquals(2, c2.getInt("APRS"))
        // The recording's zones worked out again: none left.
        File(dossier, "enregistrements/SatMe_RS-44_20261027_162931Z.numerique").apply { writeText(ZonesNumeriques.ecrit(emptyList(), t0)); setLastModified(lastModified() + 2_000) }
        assertFalse(liste().getValue(e.id).has("NUM"))
    }

    @Test
    fun la_page_a_ses_pastilles_et_son_filtre() {
        val h = fr.f4ioz.satcombo.demo.PageJournal.html()
        assertTrue(h.contains("id=\"filtres\""))
        assertTrue(h.contains("satme-journal-types"))
        assertTrue(h.contains("function retenu(p)"))
        // Every kind the station counts has its badge on the page.
        fr.f4ioz.satcombo.domain.ContenuPassage.ORDRE.forEach { assertTrue(it, h.contains("  $it: [") || h.contains(" $it: [")) }
    }
}
