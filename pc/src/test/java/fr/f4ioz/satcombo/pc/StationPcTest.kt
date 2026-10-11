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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The PC station on a fixed clock, in the middle of an ISS pass seen from
 * JN18FS, with a simulated IC-9700 and a simulated mast: nothing downloaded,
 * no port opened.
 */
class StationPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-pc-" + System.nanoTime()).apply {
        File(this, "emetteurs").mkdirs()
        File(this, "amsat-tle.txt").writeText("""ISS
1 25544U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996
2 25544  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734
""")
        File(this, "emetteurs/25544.json").writeText("""[
          {"description":"FM voice repeater (V/U)","mode":"FM","uplink_low":145990000,"downlink_low":437800000,"alive":true,"invert":false,"type":"Transceiver"}]""")
    }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The middle of the first ISS pass over JN18FS after the elements' epoch. */
    private val milieu: Long by lazy {
        val iss = Catalogue.lisTle(File(dossier, "amsat-tle.txt").readText()).first()
        val obs = Observer(48.77, 2.46)
        val p = PassPredictor().upcomingPasses(iss, obs, iss.epochMs!!, 48, 10.0).first()
        (p.aosEpochMs + p.losEpochMs) / 2
    }

    private fun station(): StationPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee, horloge = { milieu })
        st.settings.manualLocator = "JN18FS"
        st.demarre()
        return st
    }

    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun attends(ms: Long = 8_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    @Test
    fun le_poste_simule_suit_le_doppler_de_l_iss() {
        val st = station()
        assertTrue(st.suis(25544))
        attends { st.etat.value.position != null && st.etat.value.emetteurs.isNotEmpty() }
        assertTrue("above the horizon", st.etat.value.position!!.elevationDeg > 10)
        st.connecteCat("SIM", 0, 0)
        attends { st.stationCat.etat.value.descenteHz.let { it != null && it != 435_000_000L } }
        val (dl, ul) = st.frequencesDuCiel()
        // The rig follows the sky to within the loop's step.
        assertEquals(dl!!.toDouble(), st.stationCat.etat.value.descenteHz!!.toDouble(), 500.0)
        assertTrue(dl != 437_800_000L)
        assertNotNull(ul)
        st.deconnecteCat()
        attends { !st.etat.value.catConnecte }
    }

    @Test
    fun le_ts2000_simule_suit_aussi_le_doppler() {
        val st = station()
        assertTrue(st.suis(25544))
        attends { st.etat.value.position != null && st.etat.value.emetteurs.isNotEmpty() }
        st.connecteCat("TS2000SIM", 0, 0)
        attends { st.stationCat.etat.value.descenteHz.let { it != null && it != 435_659_600L } }
        val (dl, _) = st.frequencesDuCiel()
        assertEquals(dl!!.toDouble(), st.stationCat.etat.value.descenteHz!!.toDouble(), 500.0)
        assertEquals("TS2000", st.etat.value.catModele)
    }

    @Test
    fun avec_un_convertisseur_le_poste_recoit_la_frequence_intermediaire() {
        val st = station()
        assertTrue(st.suis(25544))
        attends { st.etat.value.position != null && st.etat.value.emetteurs.isNotEmpty() }
        // A 437 MHz downlink through a 400 MHz converter (as a 10 GHz LNB on QO-100): 37 MHz on the rig.
        st.poseConvertisseurs(400_000_000L, false, null, null)
        // Its band: by default 10.4–10.8 GHz (an LNB), never a 437 MHz satellite by mistake.
        assertEquals(437_800_000L, st.convRx().versPoste(437_800_000L))
        st.settings.convRxBasHz = 430_000_000L; st.settings.convRxHautHz = 440_000_000L
        st.connecteCat("SIM", 0, 0)
        attends { st.stationCat.etat.value.descenteHz.let { it != null && it != 435_000_000L } }
        val (dl, _) = st.frequencesDuCiel()
        // What is shown is the sky; what the rig got was the IF, read back into the sky.
        assertEquals(dl!!.toDouble(), st.stationCat.etat.value.descenteHz!!.toDouble(), 500.0)
        assertEquals(dl - 400_000_000L, st.convRx().versPoste(dl))
    }

    @Test
    fun le_mat_simule_vise_le_satellite() {
        val st = station()
        st.suis(25544)
        attends { st.etat.value.position != null }
        st.stationRotor.setRotorSim(true)
        st.stationRotor.setRotorEnabled(true)
        st.stationRotor.connectRotor()
        attends(15_000) { st.stationRotor.etat.value.rotorTargetAz != null }
        val cible = st.stationRotor.etat.value.rotorTargetAz!!
        val az = st.etat.value.position!!.azimuthDeg
        val ecart = Math.abs(((cible - az) % 360 + 540) % 360 - 180)
        assertTrue("target $cible, satellite $az", ecart < 10)
    }

    @Test
    fun la_page_et_l_etat_se_lisent_et_un_get_ne_change_rien() {
        val st = station()
        val s = ServeurPc(st, 0)
        s.demarre()
        try {
            val base = "http://127.0.0.1:${s.portReel}"
            fun get(u: String): Pair<Int, String> {
                val c = URL(base + u).openConnection() as HttpURLConnection
                return c.responseCode to runCatching { c.inputStream.bufferedReader().readText() }.getOrDefault("")
            }
            val (code, page) = get("/")
            assertEquals(200, code)
            assertTrue(page.contains("SatMe"))
            assertEquals("JN18FS", JSONObject(get("/api/etat").second).getString("locator"))
            // An action asked by GET (a browser fetching ahead): refused, nothing connected.
            assertEquals(404, get("/api/cat/connecter?modele=SIM").first)
            Thread.sleep(300)
            assertFalse(st.etat.value.catConnecte)
            // By POST: done.
            val c = URL("$base/api/cat/connecter?modele=SIM").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            assertEquals(200, c.responseCode)
            attends { st.etat.value.catConnecte }
        } finally { s.arrete() }
    }
}
