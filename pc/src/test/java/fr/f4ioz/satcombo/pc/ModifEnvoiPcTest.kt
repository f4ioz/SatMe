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
import fr.f4ioz.satcombo.domain.ModifEnvoi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * A contact edited after its upload, against a fake Wavelog: marked, never
 * posted again, changed through APIv2 (found by its old call and time,
 * PATCHed with what changed only), the mark kept across a restart.
 */
class ModifEnvoiPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-modif-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wavelog = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    @After fun fin() { wavelog.stop(0); portee.cancel(); dossier.deleteRecursively() }

    /** What the fake Wavelog got: method, path and query, body, the Authorization header. */
    data class Requete(val methode: String, val chemin: String, val corps: String, val auth: String)
    private val recues = java.util.concurrent.CopyOnWriteArrayList<Requete>()

    /** The online log's contacts, as APIv2 lists them. */
    private val enLigne = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    private var t = 1_791_800_000_000L   // 2026-10-12 10:13 UTC
    private lateinit var st: StationPc

    private fun repond(x: com.sun.net.httpserver.HttpExchange, code: Int, corps: String) {
        val b = corps.toByteArray(); x.sendResponseHeaders(code, b.size.toLong()); x.responseBody.use { it.write(b) }
    }

    private fun carnet(fichier: File = File(dossier, "qso_log.json")): CarnetPc {
        if (!demarre) {
            demarre = true
            wavelog.createContext("/") { x ->
                val chemin = x.requestURI.toString()
                val corps = x.requestBody.readBytes().toString(Charsets.UTF_8)
                recues += Requete(x.requestMethod, chemin, corps, x.requestHeaders.getFirst("Authorization").orEmpty())
                when {
                    chemin.startsWith("/api/station_info/") -> repond(x, 200, JSONArray().put(JSONObject().put("station_id", "1")
                        .put("station_gridsquare", "JN18FS").put("station_callsign", "F4IOZ").put("station_profile_name", "Maison")).toString())
                    chemin == "/api/qso" -> {
                        val call = Regex("<CALL:\\d+>([A-Z0-9/]+)").find(corps)?.groupValues?.get(1).orEmpty()
                        enLigne += JSONObject().put("id", 100 + enLigne.size).put("call", call).put("station_id", 1)
                            .put("qso_date", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(t0)))
                            .put("sat_name", "ISS")
                        repond(x, 201, """{"status":"created"}""")
                    }
                    chemin.startsWith("/index.php/api/v2/qso?") && x.requestMethod == "GET" -> {
                        if (x.requestHeaders.getFirst("Authorization") != "Bearer wl2_jeton") { repond(x, 401, """{"error":{"code":"invalid_token","message":"Invalid token"}}"""); return@createContext }
                        val call = Regex("callsign=([A-Z0-9%]+)").find(chemin)?.groupValues?.get(1).orEmpty()
                        repond(x, 200, JSONObject().put("data", JSONArray(enLigne.filter { it.optString("call") == call })).put("meta", JSONObject()).toString())
                    }
                    chemin.startsWith("/index.php/api/v2/qso/") && x.requestMethod == "PATCH" -> {
                        val id = chemin.substringAfterLast('/').toLong()
                        val q = enLigne.firstOrNull { it.optLong("id") == id }
                        if (q == null) { repond(x, 404, """{"error":{"code":"not_found","message":"QSO not found"}}"""); return@createContext }
                        val p = JSONObject(corps); p.keys().forEach { k -> q.put(k, p.get(k)) }
                        repond(x, 200, JSONObject().put("data", q).put("meta", JSONObject()).toString())
                    }
                    else -> repond(x, 404, "")
                }
            }
            wavelog.start()
        }
        st = st0 ?: StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee).also { st0 = it }
        st.settings.carnetUrl = "http://127.0.0.1:${wavelog.address.port}/"
        st.settings.carnetCle = "cle"; st.settings.carnetProfil = "1"; st.settings.callsign = "F4IOZ"
        return CarnetPc(st, LogStore(fichier)) { t }
    }
    private var st0: StationPc? = null
    private var demarre = false
    private val t0 = 1_791_799_000_000L

    private fun contact(c: String) = LogEntry(t0, "ISS", 25544, 120.0, 20.0, myLocator = "JN18FS", callsign = c, theirLocator = "JN03",
        mode = "FM", rstSent = "59", rstRcvd = "57", downlinkMhz = 437.8, uplinkMhz = 145.99)

    private fun lu(c: CarnetPc) = c.store.load().first { it.timeMs == t0 }

    @Test
    fun envoye_puis_modifie_marque_jamais_renvoye_puis_mis_a_jour_par_l_apiv2() {
        val c = carnet()
        c.store.add(contact("F5AAA"))
        assertTrue(runBlocking { c.depose(t0) }.contains("déposé"))
        assertEquals(1, recues.count { it.chemin == "/api/qso" })
        assertTrue("what left is kept with the contact", lu(c).envoiAdif.contains("F5AAA"))
        assertFalse(ModifEnvoi.modifie(lu(c)))

        // Edited after the upload: marked, with what changed.
        c.modifie(t0, lu(c).copy(callsign = "F5AAB", theirLocator = "JN04"))
        val l = ModifEnvoi.ecarts(lu(c))
        assertEquals(listOf("CALL", "GRIDSQUARE"), l.map { it.champ })
        assertTrue(ModifEnvoi.texte(l).contains("F5AAA → F5AAB"))

        // Never posted again: neither by hand nor by « déposer tout ».
        assertTrue(runBlocking { c.depose(t0) }.contains("déjà dans le carnet en ligne"))
        runBlocking { c.deposeTout() }
        assertEquals(1, recues.count { it.chemin == "/api/qso" })

        // A restart: the mark is still there (it lives in qso_log.json).
        val apres = carnet()
        assertTrue(ModifEnvoi.modifie(lu(apres)))

        // No APIv2 token: said, by hand.
        val main = runBlocking { apres.metAJour(t0) }
        assertTrue(main, main.contains("à la main") && main.contains("F5AAA → F5AAB"))
        assertEquals(0, recues.count { it.chemin.contains("/api/v2/") })

        // With the token: found by its OLD call, only the changes PATCHed.
        st.settings.carnetJetonV2 = "wl2_jeton"
        val dit = runBlocking { apres.metAJour(t0) }
        assertTrue(dit, dit.contains("mis à jour dans Wavelog"))
        val get = recues.first { it.methode == "GET" && it.chemin.contains("/api/v2/qso?") }
        assertTrue(get.chemin, get.chemin.contains("callsign=F5AAA") && get.chemin.contains("qso_since=2026-"))
        assertEquals("Bearer wl2_jeton", get.auth)
        val patch = recues.single { it.methode == "PATCH" }
        assertTrue(patch.chemin.endsWith("/api/v2/qso/100"))
        val corps = JSONObject(patch.corps)
        assertEquals(setOf("call", "gridsquare"), corps.keys().asSequence().toSet())
        assertEquals("F5AAB", corps.getString("call"))
        assertEquals("F5AAB", enLigne.single().getString("call"))
        assertFalse("up to date now", ModifEnvoi.modifie(lu(apres)))
        assertFalse("and after a restart too", ModifEnvoi.modifie(lu(carnet())))
        assertEquals("no new contact, ever", 1, recues.count { it.chemin == "/api/qso" } )
        assertEquals(1, enLigne.size)
    }

    @Test
    fun deux_contacts_qui_ressemblent_rien_n_est_change() {
        val c = carnet()
        c.store.add(contact("F5AAA"))
        runBlocking { c.depose(t0) }
        // A second one, same call and time, in the online log (an earlier ADIF import).
        enLigne += JSONObject(enLigne.single().toString()).put("id", 999)
        c.modifie(t0, lu(c).copy(rstRcvd = "55"))
        st.settings.carnetJetonV2 = "wl2_jeton"
        val dit = runBlocking { c.metAJour(t0) }
        assertTrue(dit, dit.contains("plusieurs"))
        assertEquals(0, recues.count { it.methode == "PATCH" })
        assertTrue(ModifEnvoi.modifie(lu(c)))
    }

    @Test
    fun ce_que_l_apiv2_ne_change_pas_reste_a_faire_a_la_main() {
        val c = carnet()
        c.store.add(contact("F5AAA"))
        runBlocking { c.depose(t0) }
        // A grid line (VUCC_GRIDS: not editable through APIv2) and a report.
        c.modifie(t0, lu(c).copy(theirLocator = "JN03,JN13", rstRcvd = "55"))
        st.settings.carnetJetonV2 = "wl2_jeton"
        val dit = runBlocking { c.metAJour(t0) }
        assertTrue(dit, dit.contains("mis à jour") && dit.contains("à la main"))
        val corps = JSONObject(recues.single { it.methode == "PATCH" }.corps)
        assertEquals("55", corps.getString("rst_rcvd"))
        assertTrue("the grid square emptied: Wavelog's GRIDSQUARE goes", corps.isNull("gridsquare"))
        val reste = ModifEnvoi.ecarts(lu(c))
        assertEquals(listOf("VUCC_GRIDS"), reste.map { it.champ })
        // Fixed by hand in Wavelog, said here: the mark goes.
        assertTrue(c.corrigeALaMain(t0))
        assertFalse(ModifEnvoi.modifie(lu(c)))
    }

    @Test
    fun envoye_avant_que_satme_garde_l_envoi_la_premiere_modification_le_garde() {
        val c = carnet()
        c.store.add(contact("F5AAA").copy(envoyeMs = t0 + 5000))   // an older upload: nothing kept
        assertFalse(ModifEnvoi.modifie(lu(c)))
        c.modifie(t0, lu(c).copy(nom = "Jean"))
        val l = ModifEnvoi.ecarts(lu(c))
        assertEquals(listOf("NAME"), l.map { it.champ })
        // Redated: the mark follows the contact to its new time.
        c.redate(t0, t0 + 60_000)
        val e = c.store.load().single()
        assertEquals(listOf("TIME_ON", "NAME"), ModifEnvoi.ecarts(e).map { it.champ })
    }
}
