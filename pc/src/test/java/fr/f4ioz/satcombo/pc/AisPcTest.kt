/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.ais.Aivdm
import fr.f4ioz.satcombo.ais.DemodAis
import fr.f4ioz.satcombo.ais.EncodeurAis
import fr.f4ioz.satcombo.ais.HdlcAis
import fr.f4ioz.satcombo.ais.MessageAis
import fr.f4ioz.satcombo.ais.ModulateurAis
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
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * AIS on the PC against an rtl_tcp of a few lines sending GMSK bursts built
 * from the standard: the dongle set to 162.000 MHz and 288 kS/s, the ships
 * in the table, the SDR tab's dongle never taken without the owner's say
 * nor from ADS-B, the NMEA sentences on this PC only.
 */
class AisPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-ais-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serveur = ServerSocket(0)
    private val commandes = CopyOnWriteArrayList<Pair<Int, Int>>()
    @Volatile private var fini = false

    @After fun fin() { fini = true; runCatching { serveur.close() }; portee.cancel(); dossier.deleteRecursively() }

    /** A second of signal: a ship's position on A, its static data on B, in noise, as 8-bit I/Q. */
    private val signal: ByteArray by lazy {
        val iq = FloatArray(2 * DemodAis.DEBIT / 4)
        val r = Random(9)
        val p = ModulateurAis.ajoute(HdlcAis.trame(EncodeurAis.position(227006760, 48.71, -3.97, 14.5, 300.0, 300)), DemodAis.DEBIT, -25_000.0, 0.4, iq, 1000)
        ModulateurAis.ajoute(HdlcAis.trame(EncodeurAis.statique(227006760, "ILE DE BREHAT", "FNXC", 60, intArrayOf(120, 30, 12, 12), "ROSCOFF")), DemodAis.DEBIT, 25_000.0, 0.4, iq, 1000 + p + 2000)
        ModulateurAis.bruit(iq, iq.size / 2, 0.03, r)
        ByteArray(iq.size) { ((iq[it] * 127.5f + 127.5f).toInt().coerceIn(0, 255)).toByte() }
    }

    private fun fauxRtlTcp() = thread(isDaemon = true) {
        while (!fini) {
            val c = runCatching { serveur.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { sert(c) }
        }
    }

    private fun sert(c: Socket) = runCatching {
        c.use {
            val o = c.getOutputStream()
            o.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); o.flush()
            thread(isDaemon = true) {
                val e = DataInputStream(c.getInputStream())
                runCatching { while (true) commandes += e.readUnsignedByte() to e.readInt() }
            }
            while (!fini) { o.write(signal); Thread.sleep(50) }
        }
    }

    private fun attends(ms: Long = 10_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    private fun station(): StationPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "IN88GS"
        st.demarre()
        attends { st.etat.value.observateur != null }
        return st
    }

    @Test fun la_cle_sdr_n_est_prise_que_sur_demande() {
        val st = station()
        st.settings.sdrAudio = false
        val sdr = SdrPc(st, SonPc(), StockageMemoire(), portee)
        sdr.lance = false
        sdr.port = serveur.localPort
        fauxRtlTcp()
        val ais = AisPc(st, sdr, StockageMemoire(), portNmea = 0)
        sdr.aisTient = { ais.tientCleSdr }
        assertFalse(ais.actif); assertFalse(ais.nmea)
        // The SDR tab receives: AIS waits for the owner's choice.
        assertTrue(sdr.demarre())
        val refus = ais.demarre()
        assertNotNull(refus); assertTrue(refus!!.contains("Prendre la clé"))
        assertTrue(sdr.etat.value.actif); assertFalse(ais.actif)
        // Chosen: the SDR tab stops, the dongle goes to 162 MHz at 288 kS/s.
        commandes.clear()
        assertEquals(null, ais.demarre(prendre = true))
        assertFalse(sdr.etat.value.actif)
        attends { commandes.any { it.first == 0x01 } }
        assertTrue(commandes.contains(0x02 to 288_000))
        assertEquals(162_000_000, commandes.first { it.first == 0x01 }.second)
        // The ship heard on both channels, named.
        attends { ais.table.liste().firstOrNull()?.let { it.lat != null && it.nom == "ILE DE BREHAT" } == true }
        val n = ais.table.liste().single()
        assertEquals(48.71, n.lat!!, 1e-4); assertEquals("AB", n.canaux)
        val j = JSONObject(String(ais.sert("/api/ais", "GET") { "" }.corps))
        val v = j.getJSONArray("navires").getJSONObject(0)
        assertEquals("passagers", v.getString("typeTexte")); assertEquals(150, v.getInt("longueur")); assertTrue(v.has("km"))
        // Meanwhile the SDR tab may not start on it.
        assertFalse(sdr.demarre())
        assertEquals(SdrPc.AIS_TIENT_CLE, sdr.etat.value.erreur)
        ais.arrete(); assertFalse(ais.actif)
    }

    @Test fun jamais_prise_a_l_adsb() {
        val st = station()
        val sdr = SdrPc(st, SonPc(), StockageMemoire(), portee)
        sdr.lance = false
        sdr.port = serveur.localPort
        val ais = AisPc(st, sdr, StockageMemoire(), portNmea = 0)
        ais.adsbTientCle = { true }
        val e = ais.demarre(prendre = true)
        assertNotNull(e); assertTrue(e!!.contains("ADS-B"))
        assertFalse(ais.actif)
        // And ADS-B does not take it from AIS either.
        val adsb = AdsbPc(st, sdr, StockageMemoire(), portSbs = 0)
        adsb.aisTientCle = { true }
        assertNotNull(adsb.demarre(prendre = true)); assertFalse(adsb.actif)
    }

    @Test fun phrases_nmea_sur_ce_pc() {
        val st = station()
        val sdr = SdrPc(st, SonPc(), StockageMemoire(), portee)
        sdr.lance = false
        sdr.port = serveur.localPort
        fauxRtlTcp()
        val udp = DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())
        udp.soTimeout = 10_000
        val ais = AisPc(st, sdr, StockageMemoire(), portNmea = udp.localPort)
        ais.sert("/api/ais/reglage", "POST") { if (it == "nmea") "1" else "" }
        assertTrue(ais.nmea)
        assertEquals(null, ais.demarre())
        val a = Aivdm.Assembleur()
        var m: MessageAis? = null
        val fin = System.currentTimeMillis() + 10_000
        while (m?.type != 5 && System.currentTimeMillis() < fin) {
            val p = DatagramPacket(ByteArray(200), 200)
            udp.receive(p)
            val l = String(p.data, 0, p.length, Charsets.US_ASCII)
            assertTrue(l, l.startsWith("!AIVDM,") && l.endsWith("\r\n"))
            a.lit(l)?.let { m = MessageAis.lis(it.first) }
        }
        assertEquals("ILE DE BREHAT", m!!.nom)
        ais.arrete(); udp.close()
    }

    @Test fun essai_sans_recepteur() {
        val st = station()
        val sdr = SdrPc(st, SonPc(), StockageMemoire(), portee)
        sdr.port = 1   // nothing there: the trial touches no receiver
        val ais = AisPc(st, sdr, StockageMemoire(), portNmea = 0)
        assertEquals(null, ais.essai())
        attends(15_000) { ais.table.liste().count { it.nom.isNotEmpty() } >= 3 }
        assertEquals("essai", ais.tient)
        ais.arrete()
    }
}
