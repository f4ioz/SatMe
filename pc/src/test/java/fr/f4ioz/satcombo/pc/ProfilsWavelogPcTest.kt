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
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/** Each contact to the Wavelog profile of where it was made from, on the PC as on the phone; the missing ones created. */
class ProfilsWavelogPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-profils-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wavelog = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val profils = mutableListOf(
        JSONObject().put("station_id", "1").put("station_gridsquare", "JN18FS").put("station_callsign", "F4IOZ").put("station_profile_name", "F4IOZ @ JN18"),
        JSONObject().put("station_id", "2").put("station_gridsquare", "JN19").put("station_callsign", "F4IOZ").put("station_profile_name", "Portable"),
        // Another station callsign: never F4IOZ's contacts (the phone's rule; Wavelog would refuse them).
        JSONObject().put("station_id", "9").put("station_gridsquare", "JN07").put("station_callsign", "F4IOZ/P").put("station_profile_name", "Portable /P"))
    private lateinit var st: StationPc
    private val depots = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    private val creations = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    @After fun fin() { wavelog.stop(0); portee.cancel(); dossier.deleteRecursively() }

    private fun carnet(): CarnetPc {
        wavelog.createContext("/") { x ->
            val corps = x.requestBody.readBytes().toString(Charsets.UTF_8)
            val rep = when {
                x.requestURI.path.startsWith("/api/station_info/") -> JSONArray(profils).toString()
                x.requestURI.path.startsWith("/api/create_station/") -> {
                    val o = JSONArray(corps).getJSONObject(0); creations += o
                    profils += JSONObject().put("station_id", "${profils.size + 1}").put("station_gridsquare", o.getString("station_gridsquare"))
                        .put("station_callsign", o.getString("station_callsign")).put("station_profile_name", o.getString("station_profile_name"))
                    """{"status":"success"}"""
                }
                x.requestURI.path == "/api/qso" -> { depots += JSONObject(corps); """{"status":"created"}""" }
                else -> "{}"
            }.toByteArray()
            x.sendResponseHeaders(200, rep.size.toLong()); x.responseBody.use { it.write(rep) }
        }
        wavelog.start()
        st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        st.settings.carnetUrl = "http://127.0.0.1:${wavelog.address.port}/"; st.settings.carnetCle = "cle"; st.settings.callsign = "F4IOZ"
        st.poseLocator("JN18FS")
        return CarnetPc(st, LogStore(File(dossier, "qso_log.json")))
    }

    private fun contact(c: String, t: Long, ici: String) = LogEntry(t, "ISS", 25544, 100.0, 20.0, myLocator = ici, callsign = c, theirLocator = "JN03", mode = "FM")

    @Test
    fun chaque_lieu_son_profil_le_manquant_cree() {
        val c = carnet()
        c.store.add(contact("F5AAA", 1_000L, "JN18FS")); c.store.add(contact("F5BBB", 2_000L, "JN19AB")); c.store.add(contact("F5CCC", 3_000L, "JN07XX"))
        assertNull(runBlocking { c.releveProfils() })
        assertEquals(setOf("JN18"), c.ici())
        assertEquals(listOf("JN07" to null, "JN18" to "1", "JN19" to "2"), c.lieuxDuCarnet().map { (l, _, p) -> l.single() to p })

        // Made in JN19: its profile, under its station callsign.
        assertTrue(runBlocking { c.depose(2_000L) }.endsWith("déposé au carnet en ligne"))
        assertEquals("2", depots.last().getString("station_profile_id"))
        assertTrue(depots.last().getString("string").contains("<STATION_CALLSIGN:5>F4IOZ"))
        // Made in JN07: no profile there, said rather than filed under another square.
        assertTrue(runBlocking { c.depose(3_000L) }.startsWith("aucun profil Wavelog pour JN07"))
        assertEquals(1, depots.size)

        // Created, then used.
        assertTrue(runBlocking { c.creeProfils(listOf(setOf("JN07")), "227", "FRANCE", "14", "27") }.startsWith("1 profil(s) créé(s)"))
        assertEquals("F4IOZ @ JN07", creations.single().getString("station_profile_name"))
        assertEquals("27", creations.single().getString("station_itu"))
        assertTrue(runBlocking { c.depose(3_000L) }.endsWith("déposé au carnet en ligne"))
        assertEquals("4", depots.last().getString("station_profile_id"))
        // Without the ITU zone, nothing is created (Wavelog would then refuse every contact).
        assertTrue(runBlocking { c.creeProfils(listOf(setOf("JN08")), "227", "FRANCE", "14", "") }.startsWith("zone ITU obligatoire"))
    }

    @Test
    fun ici_le_profil_choisi_quand_aucun_ne_couvre() {
        val c = carnet()
        profils.removeAt(0)                              // no JN18 profile
        runBlocking { c.releveProfils() }
        assertNull(c.profilPourLieu(setOf("JN18")))
        st.settings.carnetProfil = "2"            // chosen by hand for here
        assertEquals("2", c.profilPourLieu(setOf("JN18")))
        assertNull("elsewhere, never the one chosen for here", c.profilPourLieu(setOf("JN07")))
    }
}
