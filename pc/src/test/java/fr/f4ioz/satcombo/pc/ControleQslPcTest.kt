/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import com.sun.net.httpserver.HttpServer
import fr.f4ioz.satcombo.data.AdifImport
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.data.StatutsQsl
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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/**
 * The log's ADIF check and its QSL statuses through SatMe-PC's API, against
 * a fake Wavelog serving fictitious contacts.
 */
class ControleQslPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-qsl-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wavelog = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val demandes = java.util.concurrent.CopyOnWriteArrayList<String>()
    @After fun fin() { wavelog.stop(0); portee.cancel(); dossier.deleteRecursively() }

    private val t = AdifImport.instant("20261020", "101500")!!
    @Volatile private var lotwF5 = "N"

    private fun adif() = "Wavelog<EOH>\n" +
        "<CALL:5>F5ZZZ<QSO_DATE:8>20261020<TIME_ON:6>101600<SAT_NAME:5>SO-50<PROP_MODE:3>SAT" +
        "<LOTW_QSL_SENT:1>Y<LOTW_QSL_RCVD:1>$lotwF5<EQSL_QSL_SENT:1>Y<EQSL_QSL_RCVD:1>Y<EOR>\n"

    private fun reponds(x: com.sun.net.httpserver.HttpExchange, corps: String) {
        val b = corps.toByteArray(); x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) }
    }

    private fun station(): Pair<StationPc, CarnetPc> {
        wavelog.createContext("/api/station_info/") { x ->
            reponds(x, """[{"station_id":"7","station_gridsquare":"JN06,JN16","station_callsign":"F4XXX","station_profile_name":"Portable"}]""")
        }
        wavelog.createContext("/api/get_contacts_adif") { x ->
            demandes += x.requestBody.readBytes().toString(Charsets.UTF_8)
            reponds(x, JSONObject().put("adif", adif()).put("exported_qsos", 1).put("lastfetchedid", 99).toString())
        }
        wavelog.start()
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        st.settings.carnetUrl = "http://127.0.0.1:${wavelog.address.port}/"
        st.settings.carnetCle = "cle-lecture"; st.settings.callsign = "F4XXX"
        val c = CarnetPc(st, LogStore(File(dossier, "qso_log.json")))
        c.qsl = QslPc(st, c, StatutsQsl.Store(File(dossier, "qsl_statuts.json")))
        c.store.save(listOf(
            LogEntry(t, "SO-50", 27607, 10.0, 20.0, myLocator = "JN06,JN16", callsign = "F5ZZZ", theirLocator = "JN03",
                mode = "FM", uplinkMhz = 145.850, downlinkMhz = 436.795),
            LogEntry(t + 3_600_000L, "SO-50", 27607, 10.0, 20.0, myLocator = "JN18FS", callsign = "F6ZZZ", theirLocator = "JN03",
                mode = "", uplinkMhz = 145.850, downlinkMhz = 436.795)))
        return st to c
    }

    @Test
    fun le_controle_et_les_qsl_passent_par_l_api() {
        val (st, c) = station()
        val s = ServeurPc(st, 0, carnet = c)
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            fun get(u: String) = JSONObject((URL(base + u).openConnection() as HttpURLConnection).inputStream.bufferedReader().readText())
            fun post(u: String): JSONObject {
                val co = URL(base + u).openConnection() as HttpURLConnection
                co.requestMethod = "POST"
                return JSONObject(co.inputStream.bufferedReader().readText())
            }

            val ctl = get("/api/carnet/controle")
            assertEquals(2, ctl.getInt("total"))
            assertEquals(1, ctl.getInt("bloquants"))
            val codes = (0 until ctl.getJSONArray("anomalies").length()).map { ctl.getJSONArray("anomalies").getJSONObject(it).getString("code") }
            assertTrue(codes.toString(), "mode-absent" in codes && "grille-dans-locator" in codes)

            // Nothing read yet.
            assertEquals(0L, get("/api/carnet/qsl").getLong("releveMs"))
            // A read asked by GET is refused (an action is a POST).
            assertEquals(404, (URL("$base/api/carnet/qsl/releve").openConnection() as HttpURLConnection).responseCode)

            val dit = post("/api/carnet/qsl/releve").getString("dit")
            assertTrue(dit, dit.contains("relecture complète"))
            assertTrue(demandes.last(), JSONObject(demandes.last()).getLong("fetchfromid") == 0L)
            val q = get("/api/carnet/qsl")
            assertEquals(1, q.getInt("releves"))
            assertEquals(1, q.getInt("sansStatut"))
            assertEquals(1, q.getJSONObject("lotw").getInt("attente"))
            assertEquals(1, q.getJSONObject("eqsl").getInt("ok"))
            val f5 = (0 until get("/api/carnet").getJSONArray("contacts").length()).map { get("/api/carnet").getJSONArray("contacts").getJSONObject(it) }
                .first { it.getString("c") == "F5ZZZ" }
            assertEquals("attente", f5.getJSONObject("qsl").getString("lotw"))
            assertEquals("ok", f5.getJSONObject("qsl").getString("eqsl"))

            // Next read: incremental, from the cursor kept.
            post("/api/carnet/qsl/releve")
            assertEquals(99L, JSONObject(demandes.last()).getLong("fetchfromid"))
            // LoTW confirmed meanwhile: only a full read sees it on this old contact.
            lotwF5 = "Y"
            assertTrue(post("/api/carnet/qsl/releve?tout=1").getString("dit").contains("relecture complète"))
            assertEquals(0L, JSONObject(demandes.last()).getLong("fetchfromid"))
            assertEquals(1, get("/api/carnet/qsl").getJSONObject("lotw").getInt("ok"))
            assertFalse(File(dossier, "qsl_statuts.json").readText().contains("cle-lecture"))
        } finally { s.arrete() }
    }
}
