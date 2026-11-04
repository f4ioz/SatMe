/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * « Ne rien suivre » and « Entre deux passages programmés : ne rien suivre »:
 * the PC station in the middle of an ISS pass over JN18FS, a simulated rig,
 * nothing downloaded, no port opened.
 */
class RienSuiviPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-rien-" + System.nanoTime()).apply {
        File(this, "emetteurs").mkdirs()
        File(this, "amsat-tle.txt").writeText("""ISS
1 25544U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996
2 25544  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734
""")
        File(this, "emetteurs/25544.json").writeText("""[
          {"description":"FM voice repeater (V/U)","mode":"FM","uplink_low":145990000,"downlink_low":437800000,"alive":true,"invert":false,"type":"Transceiver"}]""")
    }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val milieu: Long by lazy {
        val iss = Catalogue.lisTle(File(dossier, "amsat-tle.txt").readText()).first()
        val p = PassPredictor().upcomingPasses(iss, Observer(48.77, 2.46), iss.epochMs!!, 48, 10.0).first()
        (p.aosEpochMs + p.losEpochMs) / 2
    }
    @Volatile private var heure = 0L

    private fun station(stockage: StockageMemoire = StockageMemoire()): StationPc {
        if (heure == 0L) heure = milieu
        val st = StationPc(stockage, Catalogue(dossier, enLigne = false), PortsSeriePc(), portee, horloge = { heure })
        st.settings.manualLocator = "JN18FS"
        st.demarre()
        return st
    }

    private fun attends(ms: Long = 8_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    private fun suitIss(st: StationPc) {
        assertTrue(st.suis(25544))
        attends { st.etat.value.position != null && st.etat.value.emetteurs.isNotEmpty() }
    }

    @Test
    fun ne_rien_suivre_coupe_le_doppler_et_tient_apres_un_redemarrage() {
        val stockage = StockageMemoire()
        val st = station(stockage)
        suitIss(st)
        st.connecteCat("SIM", 0, 0)
        attends { st.pilotageDoppler() }
        st.neSuisRien()
        val e = st.etat.value
        assertNull(e.suivi); assertNull(e.passage); assertTrue(e.emetteurs.isEmpty()); assertTrue(e.arc.isEmpty())
        // A few seconds of the loop: nothing computed again for a satellite, the rig not driven.
        Thread.sleep(2_500)
        assertNull(st.etat.value.suivi); assertNull(st.etat.value.position); assertNull(st.etat.value.passage)
        assertFalse(st.pilotageDoppler())
        assertEquals(null to null, st.frequencesDuCiel())
        st.deconnecteCat()
        // The next start: still nothing followed.
        val st2 = station(stockage)
        Thread.sleep(1_500)
        assertNull(st2.etat.value.suivi)
        // Choosing a satellite again follows it as before, and is kept.
        suitIss(st2)
        val st3 = station(stockage)
        attends { st3.etat.value.suivi?.catalogNumber == 25544 }
    }

    @Test
    fun apres_une_campagne_le_satellite_d_avant_sans_le_reglage_aucun_avec() {
        val stockage = StockageMemoire()
        val st = station(stockage)
        assertFalse("off by default", st.etat.value.entreRien)
        // Off: the satellite followed before is followed again (the campaign had taken another, here none).
        st.neSuisRien()
        assertEquals("ISS suivi de nouveau", st.reprendApresProgramme(25544, "FM voice repeater (V/U)"))
        attends { st.etat.value.suivi?.catalogNumber == 25544 && st.etat.value.emetteurs.isNotEmpty() }
        // Off, the same satellite: it stays, its own transmitter back.
        assertNull(st.reprendApresProgramme(25544, ""))
        assertEquals(25544, st.etat.value.suivi?.catalogNumber)
        // Nothing followed before the campaign: nothing after.
        assertEquals("plus aucun satellite suivi", st.reprendApresProgramme(null, ""))
        assertNull(st.etat.value.suivi)
        assertNull("already none: nothing to do", st.reprendApresProgramme(null, ""))
        // On: back to none, even when a satellite was followed before.
        st.entreRien(true)
        suitIss(st)
        assertEquals("plus aucun satellite suivi", st.reprendApresProgramme(25544, ""))
        assertNull(st.etat.value.suivi)
        // The setting is kept between two starts.
        assertTrue(station(stockage).etat.value.entreRien)
    }

    @Test
    fun un_rendez_vous_prend_la_main_puis_rend_aucun_avec_le_reglage() {
        val st = station()
        st.neSuisRien()
        st.entreRien(true)
        assertTrue(st.gardeAgenda(0, "SSTV ISS", milieu - 10 * 60_000L, milieu + 60_000L, "ISS", "SSTV", "", 0, 0).isSuccess)
        // The appointment takes over by itself.
        attends { st.etat.value.suivi?.catalogNumber == 25544 }
        // Its window over (and its 5 min after): nothing followed any more.
        heure = milieu + 7 * 60_000L
        attends { st.etat.value.suivi == null }
    }

    @Test
    fun un_rendez_vous_sans_le_reglage_garde_son_satellite() {
        val st = station()
        st.neSuisRien()
        assertTrue(st.gardeAgenda(0, "SSTV ISS", milieu - 10 * 60_000L, milieu + 60_000L, "ISS", "SSTV", "", 0, 0).isSuccess)
        attends { st.etat.value.suivi?.catalogNumber == 25544 }
        heure = milieu + 7 * 60_000L
        Thread.sleep(2_500)
        assertEquals("as before: the appointment's satellite stays", 25544, st.etat.value.suivi?.catalogNumber)
    }

    @Test
    fun la_main_qui_choisit_pendant_le_rendez_vous_l_emporte() {
        val st = station()
        st.entreRien(true)
        st.neSuisRien()
        assertTrue(st.gardeAgenda(0, "SSTV ISS", milieu - 10 * 60_000L, milieu + 60_000L, "ISS", "SSTV", "", 0, 0).isSuccess)
        attends { st.etat.value.suivi?.catalogNumber == 25544 }
        Thread.sleep(1_500)
        // Chosen again by hand during the appointment: no longer the appointment's to give back.
        suitIss(st)
        heure = milieu + 7 * 60_000L
        Thread.sleep(2_500)
        assertEquals(25544, st.etat.value.suivi?.catalogNumber)
    }

    @Test
    fun la_page_et_l_api_sans_satellite_suivi() {
        val st = station()
        suitIss(st)
        val s = ServeurPc(st, 0)
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            fun post(u: String): Int { val c = URL(base + u).openConnection() as HttpURLConnection; c.requestMethod = "POST"; return c.responseCode }
            fun get(u: String): Pair<Int, String> {
                val c = URL(base + u).openConnection() as HttpURLConnection
                return c.responseCode to runCatching { c.inputStream.bufferedReader().readText() }.getOrDefault("")
            }
            assertEquals(200, post("/api/suivre/rien"))
            assertNull(st.etat.value.suivi)
            val (code, etat) = get("/api/etat")
            assertEquals(200, code)
            val j = JSONObject(etat)
            assertFalse(j.has("suivi"))
            assertFalse(j.getBoolean("entreRien"))
            assertEquals(200, post("/api/agenda/entre-rien?on=1"))
            assertTrue(st.etat.value.entreRien)
            assertTrue(JSONObject(get("/api/etat").second).getBoolean("entreRien"))
            // The page and the routes that read the satellite followed answer without one.
            val (p, page) = get("/")
            assertEquals(200, p)
            assertTrue(page.contains("Ne rien suivre") && page.contains("g-entre-rien") && page.contains("aucun satellite suivi"))
            for (u in listOf("/api/passages", "/api/frise", "/api/orbites", "/api/doppler", "/api/sdr", "/api/sdr/echelle?de=430000000&a=440000000",
                    "/api/datv", "/api/pourquoi", "/api/rotor/plan", "/api/carnet", "/api/frequences", "/api/qo100", "/api/commandes", "/api/satfiche")) {
                val c = get(u).first
                assertTrue("$u : $c", c in 200..499 && c != 500)
            }
        } finally { s.arrete() }
    }
}
