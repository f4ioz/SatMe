/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.espace.MeteoSpatiale
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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Space weather on the PC with a fetcher of its own: nothing asked until
 * opted in, only NOAA SWPC asked then, each product kept on disk and shown
 * again after a restart without the network, a failure keeping the last
 * copy, each alert said once.
 */
class MeteoSpatialePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-espace-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun echantillon(n: String) = MeteoSpatialePcTest::class.java.getResourceAsStream("/espace-pc/$n.json")?.readBytes()?.toString(Charsets.UTF_8)

    private val demandes = CopyOnWriteArrayList<String>()
    @Volatile private var panne = false
    private val faux: (String) -> String = { url ->
        demandes += url
        if (panne) throw IOException("hors ligne")
        val n = MeteoSpatiale.PRODUITS.entries.first { url.endsWith(it.value) }.key
        echantillon(n) ?: "[]"
    }

    private fun station(): StationPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "IN88GS"
        st.demarre()
        val fin = System.currentTimeMillis() + 8000
        while (st.etat.value.observateur == null) { check(System.currentTimeMillis() < fin); Thread.sleep(50) }
        return st
    }

    @Test fun rien_sans_accord_puis_cache() {
        val st = station()
        var now = MeteoSpatiale.temps("2026-10-17T13:00:00")!!
        val stockage = StockageMemoire()
        val m = MeteoSpatialePc(st, stockage, File(dossier, "espace"), { now }, faux)
        // Off by default: the loop asks nothing.
        assertFalse(m.actif)
        m.tic()
        assertTrue(demandes.isEmpty())
        assertFalse(JSONObject(String(m.sert("/api/espace/maintenant", "POST") { "" }.corps)).getBoolean("ok"))
        assertTrue(demandes.isEmpty())
        val alertes = CopyOnWriteArrayList<String>()
        m.surAlerte = { alertes += it.titre }
        // Opted in: every product, from services.swpc.noaa.gov only.
        stockage.edit().putBoolean(MeteoSpatialePc.CLE_ACTIF, true).apply()
        m.tic()
        assertEquals(MeteoSpatiale.PRODUITS.size, demandes.size)
        assertTrue(demandes.all { it.startsWith("https://services.swpc.noaa.gov/") && !it.contains("celestrak") })
        val j = m.json()
        assertEquals(5.33, j.getJSONObject("kp").getDouble("kp"), 1e-9)
        assertEquals(1, j.getJSONArray("alertes").length())
        assertEquals(1, alertes.size)
        // Not again before the period; the storm's alert said once.
        m.tic()
        assertEquals(MeteoSpatiale.PRODUITS.size, demandes.size)
        // After it: asked again; offline, the copies stay, the failure said quietly.
        now += 16 * 60_000L
        panne = true
        m.tic()
        assertTrue(demandes.size > MeteoSpatiale.PRODUITS.size)
        val k = m.json()
        assertEquals(5.33, k.getJSONObject("kp").getDouble("kp"), 1e-9)
        assertTrue(k.getJSONObject("erreurs").length() > 0)
        assertEquals(1, alertes.size)
        // Restarted: the copies on disk, no network, the alert not said again.
        demandes.clear()
        val m2 = MeteoSpatialePc(st, StockageMemoire(), File(dossier, "espace"), { now }, faux)
        val alertes2 = CopyOnWriteArrayList<String>()
        m2.surAlerte = { alertes2 += it.titre }
        m2.tic()
        assertTrue(demandes.isEmpty())
        assertEquals(5.33, m2.json().getJSONObject("kp").getDouble("kp"), 1e-9)
        assertTrue(m2.json().getJSONObject("recus").has("kp"))
        assertTrue(alertes2.isEmpty())
    }
}
