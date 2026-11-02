/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

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
import java.net.URL

/**
 * Through the server: the guided tours and the two display options (kept by
 * SatMe-PC), and a connection followed to its end (« essai » / « essaiFini »)
 * so the page can say why it failed. Only simulated or absent hardware.
 */
class GuidePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-guide-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun avecServeur(stockage: StockageMemoire, bloc: (get: (String) -> JSONObject, post: (String) -> JSONObject, st: StationPc) -> Unit) {
        val st = StationPc(stockage, Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        val s = ServeurPc(st, 0)
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            val get = { u: String -> JSONObject((URL(base + u).openConnection() as HttpURLConnection).inputStream.bufferedReader().readText()) }
            val post = { u: String ->
                val c = URL(base + u).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                JSONObject(c.inputStream.bufferedReader().readText())
            }
            bloc(get, post, st)
        } finally { s.arrete() }
    }

    @Test
    fun les_options_sont_gardees_par_satme_pc() {
        val stockage = StockageMemoire()
        avecServeur(stockage) { get, post, _ ->
            // Off by default, both.
            var j = get("/api/interface")
            assertFalse(j.getBoolean("assistant")); assertFalse(j.getBoolean("allegee"))
            j = post("/api/interface?assistant=1")
            assertTrue(j.getBoolean("assistant")); assertFalse(j.getBoolean("allegee"))
            j = post("/api/interface?allegee=1")
            assertTrue(j.getBoolean("assistant")); assertTrue(j.getBoolean("allegee"))
            // The tours and the texts come with them.
            val g = get("/api/guide")
            assertTrue(g.getBoolean("ok")); assertTrue(g.getBoolean("allegee"))
            assertTrue(g.getJSONArray("visites").length() > 20)
            assertTrue(g.getJSONObject("textes").getString("pcg_cx_reglages").isNotBlank())
        }
        // Kept: a new start reads them back.
        assertTrue(stockage.getBoolean("pc_assistant", false))
        assertTrue(stockage.getBoolean("pc_interface_allegee", false))
        avecServeur(stockage) { get, post, _ ->
            assertTrue(get("/api/interface").getBoolean("allegee"))
            assertFalse(post("/api/interface?allegee=0").getBoolean("allegee"))
        }
        assertFalse(stockage.getBoolean("pc_interface_allegee", true))
    }

    @Test
    fun une_connexion_ratee_se_dit_et_le_rotor_ne_bouge_pas() {
        avecServeur(StockageMemoire()) { get, post, st ->
            fun attend(cle: String, n: Int): JSONObject {
                val fin = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < fin) {
                    val e = get("/api/etat").getJSONObject(cle)
                    if (e.optInt("essaiFini") >= n) return e
                    Thread.sleep(100)
                }
                error("$cle : essai $n jamais fini")
            }
            // The rig with no port chosen: refused, said why.
            var n = post("/api/cat/connecter?modele=IC9700&port=-1").getInt("essai")
            var e = attend("cat", n)
            assertFalse(e.getBoolean("connecte"))
            assertTrue(e.getString("statut"), e.getString("statut").startsWith("échec"))
            // The rotor by rotctld on a closed port: refused, said why; no serial port opened.
            n = post("/api/rotor/connecter?lien=ROTCTLD&sim=0&hote=127.0.0.1&tcp=1").getInt("essai")
            e = attend("rotor", n)
            assertFalse(e.getBoolean("connecte"))
            // The simulated mast: connected, and nothing asked of it — no target, it stays where it was.
            n = post("/api/rotor/connecter?lien=GS232&sim=1").getInt("essai")
            e = attend("rotor", n)
            assertTrue(e.getBoolean("connecte"))
            val az = e.get("az")
            Thread.sleep(1500)
            e = get("/api/etat").getJSONObject("rotor")
            assertTrue(e.isNull("azCible"))
            assertEquals(az.toString(), e.get("az").toString())
            post("/api/rotor/deconnecter")
            assertTrue(st.stationRotor.essaiConnexionFini >= n)
        }
    }
}
