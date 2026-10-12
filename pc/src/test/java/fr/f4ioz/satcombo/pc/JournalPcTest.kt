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
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sstv.SstvMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The journal page's answers on the PC, from a pass, a contact, a picture and a recording. */
class JournalPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-journal-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val t0 = 1_791_800_000_000L   // the pass starts here, 10 minutes long

    private fun stamp(t: Long) = SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    @Test
    fun la_page_du_journal_trouve_le_passage_son_contact_son_image_et_son_son() {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        st.settings.journalDecalageQsoS = 0
        val carnet = CarnetPc(st, LogStore(File(dossier, "qso_log.json")))
        val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), File(dossier, "sstv"))
        val j = JournalPc(st, carnet, son, File(dossier, "journal"))
        val web = JournalWebPc(j, st, dossier)

        // A recording that starts with the pass: 60 s of a tone, at 24 kHz.
        val nomSon = "SatMe_SO-50_${stamp(t0)}.wav"
        File(dossier, "enregistrements").mkdirs()
        val w = EnregistreurWav(File(dossier, "enregistrements/$nomSon"), 48_000)
        val ton = ShortArray(60 * 48_000) { i -> (6000 * Math.sin(2 * Math.PI * 800 * i / 48_000)).toInt().toShort() }
        w.ajoute(ton, ton.size); w.ferme()
        // The pass, as the collector keeps it.
        val e = JournalPassage.Entree(27607, "SO-50", t0, t0 + 600_000L, "JN18FS",
            enregistrements = listOf(nomSon),
            points = (0..10).map { JournalPassage.Point(t0 + it * 60_000L, 100.0 + it * 10, 5.0 + it) })
        j.rangement.enregistre(e)
        // A contact at 2 min, a picture at 4 min.
        carnet.store.add(LogEntry(t0 + 120_000L, "SO-50", 27607, 120.0, 7.0, callsign = "F5XYZ", theirLocator = "JN03", mode = "FM", nom = "Jean"))
        val image = SstvMeta.fileName("SO-50", t0 + 240_000L, "Robot 36", true)
        File(dossier, "sstv").mkdirs(); File(dossier, "sstv/$image").writeBytes(ByteArray(10))
        File(dossier, "sstv/" + SstvMeta.sidecarName(image)).writeText(
            SstvMeta.encode(SstvMeta.SstvShot(image, "SO-50", t0 + 240_000L, mode = "Robot 36", source = "live")))

        fun lit(route: String, p: Map<String, String> = emptyMap()) = String(web.sert(route) { p[it].orEmpty() }.corps)

        val liste = JSONObject(lit("/liste")).getJSONArray("passages")
        assertEquals(1, liste.length())
        val l0 = liste.getJSONObject(0)
        assertEquals(1, l0.getInt("q")); assertEquals(1, l0.getInt("i")); assertTrue(l0.getBoolean("son"))

        val p = JSONObject(lit("/passage", mapOf("id" to e.id)))
        val marques = p.getJSONArray("marques")
        assertEquals(listOf("QSO", "SSTV"), (0 until marques.length()).map { marques.getJSONObject(it).getString("k") }.sorted())
        val sons = p.getJSONArray("sons")
        assertEquals(t0, sons.getJSONObject(0).getLong("o"))
        assertEquals(60_000L, sons.getJSONObject(0).getLong("du"))
        assertEquals("Jean", JSONObject(lit("/fiches", mapOf("id" to e.id))).getJSONObject("F5XYZ").getString("nom"))

        // Accelerated: normal speed around the contact.
        val seg = JSONArray(lit("/segments", mapOf("id" to e.id, "avant" to "10", "apres" to "30", "surActivite" to "0", "sstv" to "0")))
        val normal = (0 until seg.length()).map { seg.getJSONArray(it) }.first { it.getInt(2) == 1 }
        assertEquals(t0 + 110_000L, normal.getLong(0)); assertEquals(t0 + 150_000L, normal.getLong(1))

        // A moment's sound, made as a WAV: 20 s from 10 s in.
        val f = JSONObject(lit("/fabrique", mapOf("id" to e.id, "quoi" to "son", "de" to "${t0 + 10_000L}", "a" to "${t0 + 30_000L}")))
        assertTrue(f.getBoolean("ok"))
        var tr: JSONObject
        do { Thread.sleep(50); tr = JSONObject(lit("/travail", mapOf("n" to f.getString("travail")))) } while (!tr.getBoolean("fini"))
        assertTrue(tr.getBoolean("ok"))
        val wav = web.sert("/fichier") { mapOf("n" to f.getString("travail"))[it].orEmpty() }.fichier!!
        assertEquals(20_000L, JournalPc.dureeWav(wav).toDouble().let { Math.round(it / 100) * 100 })
        // The drawn exports stay the phone's.
        assertTrue(lit("/fabrique", mapOf("id" to e.id, "quoi" to "video")).contains("\"ok\":false"))
    }
}
