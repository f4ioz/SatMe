/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * QO-100 from the cockpit, against an rtl_tcp of a few lines: one gesture
 * receives, the log goes on QO-100, the session survives a restart only as
 * an offer unless chosen.
 */
class TraficQo100PcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-trafic-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serveur = ServerSocket(0)
    @Volatile private var fini = false

    @After fun fin() { fini = true; runCatching { serveur.close() }; portee.cancel(); dossier.deleteRecursively() }

    /** An rtl_tcp answering as many connections as asked, noise only. */
    private fun fauxRtlTcp() = thread(isDaemon = true) {
        while (!fini) runCatching {
            val c = serveur.accept()
            thread(isDaemon = true) {
                c.use {
                    val o = c.getOutputStream()
                    o.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); o.flush()
                    thread(isDaemon = true) { runCatching { val e = DataInputStream(c.getInputStream()); while (true) { e.readUnsignedByte(); e.readInt() } } }
                    val bloc = ByteArray(16_384) { (127 + (it % 7) - 3).toByte() }
                    runCatching { while (!fini && !c.isClosed) { o.write(bloc); Thread.sleep(7) } }
                }
            }
        }
    }

    private fun station(stockage: StockageMemoire): StationPc {
        val st = StationPc(stockage, Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        st.settings.sdrAudio = false   // no speaker in a test
        st.demarre()
        return st
    }

    private fun sdr(st: StationPc, stockage: StockageMemoire, port: Int): SdrPc =
        SdrPc(st, SonPc(), stockage, portee).apply { lance = false; this.port = port; recepteur = SdrPc.RTL }

    @Test
    fun un_geste_recoit_qo100_et_le_carnet_le_suit() {
        fauxRtlTcp()
        val stockage = StockageMemoire()
        val st = station(stockage)
        val sdr = sdr(st, stockage, serveur.localPort)
        sdr.modeChoisi = "NFM"
        val t = TraficQo100Pc(st, sdr)
        val carnet = CarnetPc(st, fr.f4ioz.satcombo.data.LogStore(File(dossier, "qso.json")))
        carnet.choix = { d, cockpit -> t.choixCarnet(d, cockpit) }; carnet.qo100 = { t.qo100Carnet() }
        // Nothing followed, nothing received: no contact.
        assertNull(carnet.ajoute("F5XYZ", "JN03", "59", "59"))

        assertNull(t.demarre())
        assertTrue(t.recoit)
        assertTrue(st.qo100.etat.value.aLaCle)
        assertEquals("AUTO", sdr.modeChoisi)
        assertEquals("USB", sdr.modeEffectif())
        assertEquals("QO-100", sdr.etat.value.satellite)
        assertTrue(st.settings.qo100EnCours)

        // A CW signal clicked: its dial, its log mode.
        val a = t.accorde(10_489_520_700L, "cw")!!
        assertEquals(10_489_520_000L, a.cadranHz)
        assertEquals(10_489_520_000L, st.qo100.etat.value.descenteHz)
        t.tic()
        assertEquals(10_489_520_000L, st.settings.qo100DernierHz)

        val c = carnet.ajoute("f5xyz", "jn03", "599", "599")!!
        assertEquals("QO-100", c.satName); assertEquals(Qo100.NORAD, c.catnum)
        assertEquals("CW", c.mode)
        assertEquals(10_489.52, c.downlinkMhz, 1e-6)
        assertEquals((10_489_520_000L - Qo100.DECALAGE_HZ) / 1e6, c.uplinkMhz, 1e-6)
        assertTrue(c.elevationDeg > 20)

        val j = t.json()
        assertTrue(j.getBoolean("recoit")); assertFalse(j.getBoolean("reprendre"))
        assertEquals("QO-100 · 10 489,520 CW", j.getJSONObject("contexte").getString("libelle"))

        // SatMe-PC stopped (the shutdown stops the receiver, not the session) and started again.
        sdr.arrete()
        st.qo100.aLaCle(false)
        val st2 = station(stockage)
        val sdr2 = sdr(st2, stockage, serveur.localPort)
        val t2 = TraficQo100Pc(st2, sdr2)
        assertFalse(t2.repriseAuDemarrage())          // not chosen: only offered
        assertTrue(t2.json().getBoolean("reprendre"))
        assertFalse(sdr2.etat.value.actif)
        st2.settings.qo100RepriseAuto = true
        assertTrue(t2.repriseAuDemarrage())
        assertTrue(t2.recoit)
        assertEquals(10_489_520_000L, st2.qo100.etat.value.descenteHz)
        assertFalse(t2.json().getBoolean("reprendre"))

        // Stopped by the operator: nothing to take back.
        t2.arrete()
        assertFalse(st2.settings.qo100EnCours)
        assertFalse(t2.recoit)
        assertFalse(t2.json().getBoolean("reprendre"))
        sdr2.arrete()
    }

    /**
     * The rig worked on RS-44 by CAT while the receiver hears QO-100 in the
     * background: the log goes on RS-44; QO-100 from the cockpit's form, when
     * the rig is on it, or when chosen; the satellite chosen wins.
     */
    @Test
    fun le_carnet_suit_le_poste_pas_le_recepteur() {
        fauxRtlTcp()
        File(dossier, "emetteurs").mkdirs()
        // A fake RS-44 (elements of another satellite: only its name and number matter here).
        File(dossier, "amsat-tle.txt").writeText("""RS-44
1 44909U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996
2 44909  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734
""")
        File(dossier, "emetteurs/44909.json").writeText("""[
          {"description":"Linear transponder","mode":"SSB","uplink_low":145935000,"uplink_high":145995000,"downlink_low":435610000,"downlink_high":435670000,"alive":true,"invert":true,"type":"Transponder"}]""")
        val stockage = StockageMemoire()
        val st = station(stockage)
        val sdr = sdr(st, stockage, serveur.localPort)
        val t = TraficQo100Pc(st, sdr)
        val carnet = CarnetPc(st, fr.f4ioz.satcombo.data.LogStore(File(dossier, "qso.json")))
        carnet.choix = { d, cockpit -> t.choixCarnet(d, cockpit) }; carnet.qo100 = { t.qo100Carnet() }
        val fin = System.currentTimeMillis() + 8_000
        while (!st.suis(44909)) { check(System.currentTimeMillis() < fin); Thread.sleep(50) }
        st.connecteCat("SIM", 0, 0)
        while (!st.etat.value.catConnecte) { check(System.currentTimeMillis() < fin); Thread.sleep(50) }
        assertNull(t.demarre())
        assertTrue(t.recoit)

        val auto = t.json().getJSONObject("carnet")
        assertEquals("RS-44 · poste en CAT", auto.getJSONObject("onglet").getString("libelle"))
        assertEquals("QO-100 · cockpit", auto.getJSONObject("cockpit").getString("libelle"))

        // The receiver on QO-100 does not move the log: RS-44, its transponder's frequencies.
        val rs = carnet.ajoute("F5ABC", "JN03", "59", "59")!!
        assertEquals("RS-44", rs.satName); assertEquals(44909, rs.catnum)
        assertTrue(rs.downlinkMhz in 435.6..435.7)
        Thread.sleep(5)   // each contact its own time (its key)
        // The cockpit's own form: QO-100, its dial.
        val qo = carnet.ajoute("F5DEF", "JN03", "59", "59", depuisCockpit = true)!!
        assertEquals("QO-100", qo.satName); assertEquals(Qo100.NORAD, qo.catnum)
        assertTrue(qo.downlinkMhz > 10_489.0)
        // Chosen by hand: it wins, both ways.
        assertEquals("RS-44", carnet.ajoute("F5GHI", "", "59", "59", demande = fr.f4ioz.satcombo.domain.ChoixCarnet.Demande.SUIVI,
            depuisCockpit = true)!!.satName)
        assertEquals("QO-100", carnet.ajoute("F5JKL", "", "59", "59", demande = fr.f4ioz.satcombo.domain.ChoixCarnet.Demande.QO100)!!.satName)
        // The rig on QO-100 (« Appliquer au poste »): QO-100 from the Carnet tab too.
        st.qo100AuPoste(true)
        assertEquals("QO-100", carnet.ajoute("F5MNO", "", "59", "59")!!.satName)
        assertEquals("QO-100 · poste sur QO-100", t.json().getJSONObject("carnet").getJSONObject("onglet").getString("libelle"))
        st.qo100AuPoste(false)
        assertEquals("RS-44", carnet.ajoute("F5PQR", "", "59", "59")!!.satName)

        // Edited afterwards: RS-44 → QO-100 → RS-44, the ADIF following (its transmitter's middle again).
        val q2 = carnet.changeSatellite(rs.timeMs, "QO-100", 0)!!
        assertEquals("QO-100", q2.satName); assertTrue(q2.downlinkMhz > 10_489.0)
        assertTrue(carnet.adif(setOf(rs.timeMs)).contains("<SAT_NAME:6>QO-100"))
        val r2 = carnet.changeSatellite(rs.timeMs, "RS-44", 44909)!!
        assertEquals(44909, r2.catnum); assertEquals(435.64, r2.downlinkMhz, 1e-6)
        assertTrue(carnet.adif(setOf(rs.timeMs)).contains("<SAT_NAME:5>RS-44"))
        assertEquals("F5ABC", carnet.contacts().first { it.timeMs == rs.timeMs }.callsign)
        st.deconnecteCat()
        sdr.arrete()
    }

    @Test
    fun sans_recepteur_le_cockpit_dit_pourquoi() {
        val stockage = StockageMemoire()
        val st = station(stockage)
        val ferme = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val t = TraficQo100Pc(st, sdr(st, stockage, ferme))
        val e = t.demarre()
        assertNotNull(e)
        assertTrue(e!!, e.contains("rtl_tcp"))
        assertFalse(t.recoit)
        assertFalse(st.settings.qo100EnCours)
        val j = t.json()
        assertFalse(j.getJSONObject("recepteur").getBoolean("actif"))
        assertFalse(j.getJSONObject("contexte").getBoolean("qo100"))
    }
}
