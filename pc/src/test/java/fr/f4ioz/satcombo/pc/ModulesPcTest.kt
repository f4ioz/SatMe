/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.FonctionsNonSat
import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.demo.PagePc
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/** Réglages › Modules through the server: api/capacites, api/modules, a start refused, another site's page refused. */
class ModulesPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-modules-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun liste(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test
    fun les_modules_se_choisissent_et_le_menu_suit() {
        val stockage = StockageMemoire()
        val st = StationPc(stockage, Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        val s = ServeurPc(st, 0)
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            fun get(u: String) = JSONObject((URL(base + u).openConnection() as HttpURLConnection).inputStream.bufferedReader().readText())
            fun post(u: String): JSONObject {
                val c = URL(base + u).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                return JSONObject(c.inputStream.bufferedReader().readText())
            }
            // A new station: everything but the functions not about satellites, as before.
            var j = get("/api/capacites")
            var o = liste(j.getJSONArray("onglets"))
            assertTrue("qo100" in o && "sstv" in o && "planche" in o)
            assertFalse("avions" in o || "ais" in o || "sondes" in o)
            assertEquals(ModulesStation.MODULES.size, j.getJSONArray("modules").length())
            assertEquals("Avions", j.getJSONObject("eteints").getString("avions"))

            // A profile: SSTV ARISS.
            j = post("/api/modules?profil=sstv")
            assertTrue(j.getBoolean("ok"))
            assertEquals("sstv", j.getString("profil"))
            o = liste(j.getJSONArray("onglets"))
            assertTrue("sstv" in o && "planche" in o && "sdr" in o && "station" in o && "reglages" in o)
            assertFalse("qo100" in o || "datv" in o || "ft8" in o)
            assertTrue("#sb-datv" in liste(j.getJSONArray("masques")))
            assertFalse(j.getJSONObject("nonSat").getBoolean("ais"))

            // One module back: no profile matches any more.
            j = post("/api/modules?m=qo100&on=1")
            assertTrue("qo100" in liste(j.getJSONArray("onglets")))
            assertTrue(j.isNull("profil"))
            assertFalse(post("/api/modules?profil=rien").getBoolean("ok"))
            assertFalse(post("/api/modules?m=station&on=0").getBoolean("ok"))

            // A start of a module switched off: refused, said where to switch it on.
            val r = post("/api/meteor/demarrer")
            assertFalse(r.getBoolean("ok"))
            assertEquals("meteor", r.getString("module"))
            assertTrue(r.getString("erreur").contains("Réglages › Modules"))

            // The former route still works, and keeps both keys in step.
            j = post("/api/nonsat?o=ais&on=1")
            assertTrue("ais" in liste(j.getJSONArray("onglets")))
            assertTrue(ModulesStation.actif(stockage, "ais"))
            assertEquals(setOf("ais"), FonctionsNonSat.choisis(stockage))

            // « Tout »: everything but « Mon site web », which only the owner switches on.
            j = post("/api/modules?profil=tout")
            assertEquals(PagePc.TOUS.size, j.getJSONArray("onglets").length())
            assertEquals(listOf("#site-carte", ".site-b"), liste(j.getJSONArray("masques")))
            assertEquals("tout", j.getString("profil"))
            // Its actions refused while it is off.
            val refus = post("/api/site/essai")
            assertFalse(refus.getBoolean("ok")); assertEquals("site", refus.getString("module"))
            // Switched on by hand: a profile leaves it on, and still matches.
            j = post("/api/modules?m=site&on=1")
            assertEquals(0, j.getJSONArray("masques").length())
            j = post("/api/modules?profil=sstv")
            assertEquals("sstv", j.getString("profil"))
            assertTrue(ModulesStation.actif(stockage, "site"))
            j = post("/api/modules?profil=tout")
            assertEquals(0, j.getJSONArray("masques").length())

            // From another site's page open in the same browser: refused, nothing changed.
            Socket("127.0.0.1", s.portReel).use { k ->
                k.getOutputStream().write(("POST /api/modules?profil=sstv HTTP/1.1\r\nHost: 127.0.0.1:${s.portReel}\r\nOrigin: http://ailleurs.example\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                assertTrue(k.getInputStream().bufferedReader().readLine().contains("403"))
            }
            assertEquals(emptySet<String>(), ModulesStation.coupes(stockage))
        } finally { s.arrete() }
    }

}
