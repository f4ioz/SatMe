/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.Ouverture
import fr.f4ioz.satcombo.plateforme.PortSerie
import fr.f4ioz.satcombo.plateforme.PortsSerie
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The page and its API, with an IC-7000 in memory: what the browser does, end to end. */
class ServeurTellusTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sim = SimulateurIc7000(frequence = 7_100_000L)
    private var lignes: Lignes? = null
    private lateinit var station: StationTellus
    private lateinit var serveur: ServeurTellus

    private val ports = object : PortsSerie {
        val p = PortSerie(0, 0, 0, 1, "IC-7000 de test", "test:ic7000", 0, "test")
        override fun liste() = listOf(p)
        override fun autorise(p: PortSerie) = true
        override fun demande(p: PortSerie) {}
        override suspend fun demandeEtAttends(p: PortSerie) = true
        override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes, deuxArrets: Boolean): Ouverture {
            this@ServeurTellusTest.lignes = lignes
            return Ouverture(object : SerialLink by sim { override fun close() {} }, "")
        }
    }

    @Before fun debut() {
        val r = ReglagesTellus(StockageMemoire()).apply { portCat = "test:ic7000"; indicatif = "F0ABC"; locator = "JN18EU" }
        station = StationTellus(r, ports, decouvre = { _, _, _ -> error("pas de découverte dans ce test") }, pacingMs = 0L)
        serveur = ServeurTellus(station, CarnetTellus(tmp.root), Propagation(File(tmp.root, "p.xml"), telecharge = { Simulation.HAMQSL }),
            ClusterDx(), 0)
        serveur.demarre()
    }

    @After fun fin() { station.arrete(); serveur.arrete() }

    private fun requete(m: String, chemin: String, corps: String? = null, origine: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${serveur.portReel}$chemin").openConnection() as HttpURLConnection
        c.requestMethod = m
        origine?.let { c.setRequestProperty("Origin", it) }
        if (corps != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(corps.toByteArray()) }
        }
        val code = c.responseCode
        val t = (if (code < 400) c.inputStream else c.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
        return code to t
    }

    @Test fun page_et_poste_ic7000_de_bout_en_bout() {
        val (code, page) = requete("GET", "/")
        assertEquals(200, code)
        assertTrue(page.contains("TellusMe")); assertTrue(page.contains("réception seule"))
        assertTrue(page.contains("--rx:#3FE0C8"))
        assertTrue(!page.contains("/*JETONS*/"))

        var e = JSONObject(requete("POST", "/api/poste", "id=IC7000").second)
        assertEquals("IC7000", e.getString("poste"))
        assertEquals(Lignes.BASSES, lignes)
        Thread.sleep(800)
        e = JSONObject(requete("GET", "/api/etat").second)
        assertTrue(e.getBoolean("connecte"))
        assertEquals(7_100_000L, e.getLong("hz"))
        assertEquals("40m", e.getString("bande"))

        e = JSONObject(requete("POST", "/api/accorde", "hz=14074000").second)
        assertTrue(e.getBoolean("ok"))
        assertEquals(14_074_000L, sim.frequence)
        e = JSONObject(requete("POST", "/api/mode", "m=CW").second)
        assertTrue(e.getBoolean("ok")); assertEquals(0x03, sim.mode)

        val plan = JSONObject(requete("GET", "/api/plan").second)
        assertEquals(13, plan.getJSONArray("bandes").length()) // HF + 6 m, 2 m, 70 cm

        val q = JSONObject(requete("POST", "/api/carnet", "indicatif=K1ABC&rs=599&rr=579").second)
        assertTrue(q.getBoolean("ok"))
        val adi = requete("GET", "/api/carnet.adi").second
        assertTrue(adi.contains("<CALL:5>K1ABC")); assertTrue(adi.contains("<MODE:2>CW")); assertTrue(adi.contains("<STATION_CALLSIGN:5>F0ABC"))
        assertTrue(adi.contains("<FREQ:9>14.074000"))
        assertEquals(0, sim.recues.count { (it[4].toInt() and 0xFF) == 0x1C })
    }

    @Test fun un_autre_site_ne_peut_rien_changer() {
        val avant = station.frequence
        // Raw: HttpURLConnection does not let a test set Origin.
        java.net.Socket("127.0.0.1", serveur.portReel).use { s ->
            s.getOutputStream().write(("POST /api/accorde HTTP/1.1\r\nHost: 127.0.0.1:${serveur.portReel}\r\nOrigin: http://mechant.example\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 10\r\n\r\nhz=7000000").toByteArray())
            val r = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(r, r.startsWith("HTTP/1.1 403"))
        }
        assertEquals(avant, station.frequence)
        assertEquals(404, requete("GET", "/api/inconnu").first)
        // A GET never changes anything.
        assertEquals(404, requete("GET", "/api/accorde?hz=7000000").first)
    }

    @Test fun propagation_et_cluster_par_l_api() {
        var p = JSONObject(requete("GET", "/api/propagation").second)
        val fin = System.currentTimeMillis() + 3000
        while (!p.getBoolean("ok") && System.currentTimeMillis() < fin) { Thread.sleep(50); p = JSONObject(requete("GET", "/api/propagation").second) }
        assertEquals(142, p.getInt("flux"))
        val c = JSONObject(requete("GET", "/api/cluster?bande=20m").second)
        assertEquals(false, c.getBoolean("actif"))
    }
}
