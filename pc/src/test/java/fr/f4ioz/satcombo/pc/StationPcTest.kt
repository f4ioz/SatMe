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
    fun un_contact_se_note_avec_le_satellite_sa_position_et_les_frequences_au_repos() {
        val st = station()
        val carnet = CarnetPc(st, fr.f4ioz.satcombo.data.LogStore(File(dossier, "qso_log.json")), horloge = { milieu })
        // No satellite followed: nothing logged.
        assertEquals(null, carnet.ajoute("F5XYZ", "JN03", "59", "57"))
        assertTrue(st.suis(25544))
        attends { st.etat.value.position != null && st.etat.value.emetteurs.isNotEmpty() }
        val e = carnet.ajoute("f5xyz", "jn03", "59", "57", "essai")!!
        assertEquals("F5XYZ", e.callsign); assertEquals("JN03", e.theirLocator)
        assertEquals("ISS", e.satName); assertEquals(milieu, e.timeMs)
        assertEquals(st.etat.value.position!!.elevationDeg, e.elevationDeg, 0.5)
        assertEquals("FM", e.mode)
        assertEquals(437.8, e.downlinkMhz, 1e-6); assertEquals(145.99, e.uplinkMhz, 1e-6)
        assertEquals("JN18FS", e.myLocator)
        // Changed, then gone; the ADIF says it.
        carnet.modifie(e.timeMs, e.copy(callsign = "F5XYZ/P", nom = "Jean"))
        assertEquals("Jean", carnet.contacts().single().nom)
        assertTrue(carnet.adif().contains("<CALL:7>F5XYZ/P"))
        carnet.supprime(e.timeMs)
        assertTrue(carnet.contacts().isEmpty())
    }

    @Test
    fun chaque_satellite_garde_son_calage_et_sa_reference() {
        val sat = StockageMemoire()
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee, horloge = { milieu }, stockageSat = sat)
        st.settings.manualLocator = "JN18FS"
        st.demarre()
        assertTrue(st.suis(25544))
        attends { st.etat.value.position != null && st.etat.value.emetteurs.isNotEmpty() }
        val (dl0, _) = st.frequencesDuCiel()
        st.reglageSat(calibHz = 50L, txHz = -100L)
        assertEquals(dl0!! + 50L, st.frequencesDuCiel().first)
        st.reglageSat(memorise = true)
        st.reglageSat(calibHz = 300L)
        st.reglageSat(retour = true)
        assertEquals(50L, st.etat.value.calibHz); assertEquals(-100L, st.etat.value.txShiftHz)
        // Kept for this satellite, as the phone keeps it ("shift_25544").
        assertEquals(50L, sat.getLong("shift_25544", 0L))
        assertEquals(50L, st.stationCat.let { st.etat.value.calibHz })
    }

    @Test
    fun un_rendez_vous_fait_suivre_son_satellite_tout_seul() {
        val agenda = StockageMemoire()
        fr.f4ioz.satcombo.data.AgendaStore.put(agenda, fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent(
            id = 1L, title = "SSTV ISS", timeMs = milieu - 60_000L, satName = "ISS", endMs = milieu + 60_000L))
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee, horloge = { milieu }, stockageAgenda = agenda)
        st.settings.manualLocator = "JN18FS"
        st.demarre()
        // Nothing chosen by hand: the appointment brings the ISS.
        attends { st.etat.value.suivi?.catalogNumber == 25544 }
        assertEquals(1, st.etat.value.agenda.size)
        // Its passes are marked; taken out of the agenda, they are not any more.
        attends { st.etat.value.passagesSuivi.isNotEmpty() }
        val p = st.etat.value.passagesSuivi.first { it.aosEpochMs <= milieu }
        assertTrue(st.auAgenda(p))
        st.retireAgenda(1L)
        assertFalse(st.auAgenda(p))
    }

    @Test
    fun un_adif_entre_au_carnet_et_reconstitue_son_passage() {
        val st = station()
        attends { st.etat.value.satellites.isNotEmpty() }
        val carnet = CarnetPc(st, fr.f4ioz.satcombo.data.LogStore(File(dossier, "qso_log.json")), horloge = { milieu })
        val son = SonStation(SonPc(), st, File(dossier, "enr"), File(dossier, "sstv"))
        val journal = JournalPc(st, carnet, son, File(dossier, "journal"))
        val imp = ImportPc(st, carnet, journal) { milieu }
        val f = java.text.SimpleDateFormat("yyyyMMdd HHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun qso(call: String, sat: String, t: Long, loc: String): String {
            val (d, h) = f.format(java.util.Date(t)).split(' ')
            return "<CALL:${call.length}>$call<QSO_DATE:8>$d<TIME_ON:6>$h<SAT_NAME:${sat.length}>$sat<PROP_MODE:3>SAT<GRIDSQUARE:${loc.length}>$loc<MY_GRIDSQUARE:6>JN18FS<MODE:2>FM<EOR>\n"
        }
        val adif = "Export\n<EOH>\n" + qso("F5XYZ", "ISS", milieu - 60_000L, "JN03") + qso("EA4ABC", "ISS", milieu + 30_000L, "IN80") +
            qso("G0AAA", "LUNE-1", milieu, "IO91")
        val b = imp.depuisAdif(adif, dejaEnLigne = true)
        assertEquals(3, b.lus); assertEquals(3, b.ajoutes); assertEquals(1, b.passages); assertEquals(1, b.inconnus)
        val l = carnet.contacts()
        assertTrue(l.filter { it.satName == "ISS" }.let { iss -> iss.size == 2 && iss.all { it.envoyeMs > 0 && it.elevationDeg > 0 } })
        // On a satellite the bulletin does not know: kept in the log all the same, without its position.
        val lune = l.single { it.callsign == "G0AAA" }
        assertEquals("LUNE-1", lune.satName); assertEquals(0, lune.catnum)
        assertEquals(1, journal.passages().size)
        // Again: nothing new, no second pass.
        val c = imp.depuisAdif(adif, dejaEnLigne = true)
        assertEquals(0, c.ajoutes); assertEquals(0, c.passages)
    }

    @Test
    fun un_sked_se_trouve_puis_se_rejoue_des_deux_cotes() = kotlinx.coroutines.runBlocking {
        val st = station()
        attends { st.etat.value.satellites.isNotEmpty() && st.etat.value.observateur != null }
        // Madrid (IN80): close enough to share most ISS passes with Paris.
        val f = st.sked(25544, "IN80", 0.0)!!
        assertTrue(f.isNotEmpty())
        // (The first may be the pass under way, the clock being in the middle of one.)
        val w = f.first { it.debutTrace > milieu }
        assertTrue(w.debutTrace <= w.deMs && w.aMs <= w.finTrace)
        val tr = st.traceSked(25544, "IN80", w.debutTrace, w.finTrace)!!
        assertEquals(40.0, tr.autre.latDeg, 1.0)
        assertTrue(tr.points.size in 100..401)
        // Inside the common window both see it; at its two ends, one at least is at the horizon.
        val centre = tr.points.minByOrNull { Math.abs(it.t - (w.deMs + w.aMs) / 2) }!!
        assertTrue(centre.elMoi > 0 && centre.elAutre > 0)
        assertTrue(tr.points.first().let { it.elMoi < 0.5 || it.elAutre < 0.5 })
        // The satellite is near both stations then: within the ISS's footprint (~2,300 km).
        assertTrue(Math.abs(centre.lat - 44.0) < 25)
        // An unreadable square, a window upside down: nothing.
        assertEquals(null, st.sked(25544, "ZZ", 0.0))
        assertEquals(null, st.traceSked(25544, "IN80", w.finTrace, w.debutTrace))
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
            // An AMSAT report needs a satellite and a callsign: refused before anything leaves.
            val rapport = URL("$base/api/amsat/rapport?entendu=1").openConnection() as HttpURLConnection
            rapport.requestMethod = "POST"
            val r = JSONObject(rapport.inputStream.bufferedReader().readText())
            assertFalse(r.getBoolean("ok"))
            assertTrue(r.getString("erreur").contains("satellite"))
        } finally { s.arrete() }
    }
}
