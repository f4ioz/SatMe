/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.adsb.EncodeurAdsb
import fr.f4ioz.satcombo.adsb.ModeS
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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * ADS-B on the PC against an rtl_tcp of a few lines sending squitters built
 * from the standard: the dongle set to 1090 MHz and 2 MS/s, the aircraft in
 * the table, the SDR tab's dongle never taken without the owner's say, the
 * BaseStation lines on this PC only.
 */
class AdsbPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-adsb-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serveur = ServerSocket(0)
    private val commandes = CopyOnWriteArrayList<Pair<Int, Int>>()
    @Volatile private var fini = false

    @After fun fin() { fini = true; runCatching { serveur.close() }; portee.cancel(); dossier.deleteRecursively() }

    /** Every connection served: the header, the commands read, IQ with an aircraft's replies in noise. */
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
            val r = Random(5)
            val mag = FloatArray(8192)
            val bloc = ByteArray(16_384)
            var k = 0
            while (!fini) {
                mag.fill(0f)
                val f = when (k % 3) {
                    0 -> EncodeurAdsb.identification(0x39C4A1, "AFR1234")
                    1 -> EncodeurAdsb.position(0x39C4A1, 49.2, 2.9, 36_000, (k / 3) % 2 == 1)
                    else -> EncodeurAdsb.vitesse(0x39C4A1, 450.0, 90.0, 0)
                }
                EncodeurAdsb.module(f, ModeS.LONG, mag, 1000, 0.5f)
                k++
                for (i in 0 until 8192) {
                    val a = mag[i] * 120f + r.nextFloat() * 4f
                    val ph = r.nextFloat() * 6.283f
                    bloc[2 * i] = (127.5f + a * kotlin.math.cos(ph)).toInt().coerceIn(0, 255).toByte()
                    bloc[2 * i + 1] = (127.5f + a * kotlin.math.sin(ph)).toInt().coerceIn(0, 255).toByte()
                }
                o.write(bloc); Thread.sleep(4)
            }
        }
    }

    private fun attends(ms: Long = 8_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    private fun station(): StationPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
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
        val adsb = AdsbPc(st, sdr, StockageMemoire(), portSbs = 0)
        sdr.adsbTient = { if (adsb.tientCleSdr) SdrPc.RTL else if (adsb.tientPluto) SdrPc.PLUTO else null }
        // The SDR tab receives: ADS-B waits for the owner's choice.
        assertTrue(sdr.demarre())
        val refus = adsb.demarre()
        assertNotNull(refus)
        assertTrue(refus!!.contains("Prendre la clé"))
        assertTrue(sdr.etat.value.actif)
        assertFalse(adsb.actif)
        // Chosen: the SDR tab stops, the dongle goes to 1090 MHz at 2 MS/s.
        commandes.clear()
        assertEquals(null, adsb.demarre(prendre = true))
        assertFalse(sdr.etat.value.actif)
        attends { commandes.any { it.first == 0x01 } }
        assertTrue(commandes.contains(0x02 to 2_000_000))
        assertEquals(1_090_000_000, commandes.first { it.first == 0x01 }.second)
        // The aircraft heard, placed, named.
        attends { adsb.table.liste().firstOrNull()?.let { it.lat != null && it.indicatif == "AFR1234" } == true }
        val a = adsb.table.liste().single()
        assertEquals(49.2, a.lat!!, 1e-3); assertEquals(2.9, a.lon!!, 1e-3); assertEquals(36_000, a.altitudePieds)
        // Meanwhile the SDR tab may not start on it.
        assertFalse(sdr.demarre())
        assertEquals(SdrPc.ADSB_TIENT_CLE, sdr.etat.value.erreur)
        adsb.arrete()
        assertFalse(adsb.actif)
    }

    @Test fun sortie_basestation_et_diffusion() {
        val st = station()
        val sdr = SdrPc(st, SonPc(), StockageMemoire(), portee)
        sdr.lance = false
        sdr.port = serveur.localPort
        fauxRtlTcp()
        val adsb = AdsbPc(st, sdr, StockageMemoire(), portSbs = 0)
        assertFalse(adsb.sbs)
        assertEquals(0, adsb.portSbsEcoute)
        adsb.sert("/api/adsb/reglage", "POST") { if (it == "sbs") "1" else "" }
        val port = adsb.portSbsEcoute
        assertTrue(port > 0)
        val client = Socket("127.0.0.1", port)
        client.soTimeout = 8000
        Thread.sleep(200)
        assertEquals(null, adsb.demarre())
        val ligne = client.getInputStream().bufferedReader().readLine()
        assertTrue(ligne, ligne.startsWith("MSG,") && ligne.contains(",39C4A1,"))
        assertEquals(22, ligne.split(",").size)
        client.close()
        // A scatter target by its locator: the path's geometry, the aircraft in the common volume.
        val o = JSONObject(String(adsb.sert("/api/adsb/reglage", "POST") { when (it) { "cible" -> "JO22"; "bande" -> "23cm"; "rayon" -> "250"; else -> "" } }.corps))
        assertTrue(o.toString(), o.getBoolean("ok"))
        attends { adsb.table.liste().firstOrNull()?.let { it.lat != null && it.vitesseNoeuds != null } == true }
        val j = JSONObject(String(adsb.sert("/api/adsb", "GET") { "" }.corps))
        val d = j.getJSONObject("diffusion")
        assertEquals("JO22", d.getString("loc"))
        assertTrue(d.getJSONObject("geometrie").getBoolean("possible"))
        assertTrue(d.getJSONArray("fenetres").length() >= 1)
        adsb.arrete()
    }
}
