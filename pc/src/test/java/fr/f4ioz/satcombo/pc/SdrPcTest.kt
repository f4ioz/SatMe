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
import fr.f4ioz.satcombo.sdr.Dsp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The PC's dongle against an rtl_tcp of a few lines, in the middle of an ISS
 * pass: the frequency it is sent, the sound handed to the decoders.
 */
class SdrPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-sdr-" + System.nanoTime()).apply {
        File(this, "emetteurs").mkdirs()
        File(this, "amsat-tle.txt").writeText("""ISS
1 25544U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996
2 25544  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734
""")
        File(this, "emetteurs/25544.json").writeText("""[
          {"description":"FM voice repeater (V/U)","mode":"FM","uplink_low":145990000,"downlink_low":437800000,"alive":true,"invert":false,"type":"Transceiver"}]""")
    }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serveur = ServerSocket(0)
    /** What the dongle was asked: (command, parameter). */
    private val commandes = CopyOnWriteArrayList<Pair<Int, Int>>()
    @Volatile private var fini = false

    @After fun fin() { fini = true; runCatching { serveur.close() }; portee.cancel(); dossier.deleteRecursively() }

    private fun fauxRtlTcp() = thread(isDaemon = true) {
        runCatching {
            serveur.accept().use { c ->
                val o = c.getOutputStream()
                o.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); o.flush()
                thread(isDaemon = true) {
                    val e = DataInputStream(c.getInputStream())
                    runCatching { while (true) commandes += e.readUnsignedByte() to e.readInt() }
                }
                // Noise around 127.5, at about the dongle's rate.
                val r = Random(1)
                val bloc = ByteArray(16_384) { (127 + r.nextInt(-20, 21)).toByte() }
                while (!fini) { o.write(bloc); Thread.sleep(7) }
            }
        }
    }

    private fun attends(ms: Long = 8_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    @Test
    fun la_cle_suit_l_iss_et_son_son_va_aux_decodeurs() {
        val iss = Catalogue.lisTle(File(dossier, "amsat-tle.txt").readText()).first()
        val p = PassPredictor().upcomingPasses(iss, Observer(48.77, 2.46), iss.epochMs!!, 48, 10.0).first()
        val milieu = (p.aosEpochMs + p.losEpochMs) / 2
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee, horloge = { milieu })
        st.settings.manualLocator = "JN18FS"
        st.demarre()
        assertTrue(st.suis(25544))
        attends { st.etat.value.position != null && st.etat.value.emetteurs.isNotEmpty() }
        val son = SonPc()
        val recu = java.util.concurrent.atomic.AtomicInteger()
        son.auditeurs += { _, n, rate -> assertEquals(Dsp.AUDIO_RATE, rate); recu.addAndGet(n) }
        val sdr = SdrPc(st, son, StockageMemoire(), portee, horloge = { milieu })
        st.settings.sdrAudio = false   // no speaker in a test
        sdr.lance = false
        sdr.port = serveur.localPort
        fauxRtlTcp()
        assertTrue(sdr.demarre())
        // The decoders take the dongle's sound, as a card's.
        assertEquals(SdrPc.NOM_SOURCE, son.etat.value.appareil)
        attends { recu.get() > Dsp.AUDIO_RATE / 4 }
        // Its rate, then the ISS's downlink with the Doppler of the middle of the pass (a few kHz at most there).
        attends { commandes.any { it.first == 0x01 } }
        assertEquals(0x02 to Dsp.RTL_RATE, commandes.first())
        val f = commandes.first { it.first == 0x01 }.second.toLong()
        assertEquals(437_800_000.0, f.toDouble(), 12_000.0)
        assertTrue(sdr.etat.value.actif)
        // Stopped: nothing feeds the decoders any more.
        sdr.arrete()
        assertFalse(son.etat.value.actif)
        assertFalse(sdr.etat.value.actif)
    }

    /** The PLL the fake dongle was last tuned to, and the beacon it shows (absolute IF). */
    @Volatile private var pllFaux = 0L
    private val baliseFausseHz = 739_750_300L   // 10 489.750 through a 9 750 MHz LNB, 300 Hz off

    private fun fausseParabole() = thread(isDaemon = true) {
        runCatching {
            serveur.accept().use { c ->
                val o = c.getOutputStream()
                o.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); o.flush()
                thread(isDaemon = true) {
                    val e = DataInputStream(c.getInputStream())
                    runCatching { while (true) { val k = e.readUnsignedByte(); val v = e.readInt(); commandes += k to v; if (k == 0x01) pllFaux = v.toLong() and 0xFFFFFFFFL } }
                }
                val r = Random(2)
                var phase = 0.0
                val bloc = ByteArray(16_384)
                while (!fini) {
                    val f = (baliseFausseHz - pllFaux).toDouble()
                    val pas = 2 * Math.PI * f / Dsp.RTL_RATE
                    for (i in 0 until bloc.size / 2) {
                        phase += pas
                        bloc[2 * i] = (127.5 + 25 * Math.cos(phase) + r.nextDouble(-6.0, 6.0)).toInt().toByte()
                        bloc[2 * i + 1] = (127.5 + 25 * Math.sin(phase) + r.nextDouble(-6.0, 6.0)).toInt().toByte()
                    }
                    o.write(bloc); Thread.sleep(7)
                }
            }
        }
    }

    @Test
    fun la_cle_voit_la_balise_qo100_et_le_calage_converge_en_un_coup() {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        st.demarre()
        st.settings.sdrAudio = false
        // A 9 750 MHz LNB on the dongle; listening in the voice segment, 50 kHz below the beacon.
        st.qo100.poseOl(true, 9_750_000_000L)
        st.qo100.descente(10_489_700_000L)
        st.qo100.aLaCle(true)
        val sdr = SdrPc(st, SonPc(), StockageMemoire(), portee)
        sdr.lance = false; sdr.port = serveur.localPort
        fausseParabole()
        assertTrue(sdr.demarre())
        attends { pllFaux == 739_700_000L }
        sdr.demandePanorama()
        attends { sdr.mesureBalise() != null }
        val m = sdr.mesureBalise()!!
        assertEquals(300.0, m.ecartHz, 60.0)
        assertTrue("well above the noise: " + m.rapportDb, m.rapportDb > 15f)
        // The calibration written: 300 Hz is the software mixer's (the PLL does not move, no click),
        // and the panorama's axis takes it in — the beacon is then where it should be.
        st.qo100.calage(st.qo100.etat.value.calageHz + m.ecartArrondiHz)
        attends { sdr.etat.value.cielHz == 10_489_700_000L + m.ecartArrondiHz }
        assertEquals(739_700_000L, pllFaux)
        val avant = sdr.panoramaTrames
        attends { sdr.demandePanorama(); sdr.panoramaTrames > avant + 2 }
        assertEquals(0.0, sdr.mesureBalise()!!.ecartHz, 60.0)
        sdr.arrete()
    }

    /** An rtl_tcp whose dongle hears an RS41 (the test pattern) in FM, 5 kHz off the tuning. */
    private fun fausseSonde() = thread(isDaemon = true) {
        runCatching {
            serveur.accept().use { c ->
                val o = c.getOutputStream()
                o.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); o.flush()
                thread(isDaemon = true) {
                    val e = DataInputStream(c.getInputStream())
                    runCatching { while (true) commandes += e.readUnsignedByte() to e.readInt() }
                }
                val src = fr.f4ioz.satcombo.sonde.SondeMire.Source("RS41", 48.2, -4.5, 30, sampleRate = Dsp.AUDIO_RATE)
                val audio = ShortArray(Dsp.AUDIO_RATE / 20)
                val facteur = Dsp.RTL_RATE / Dsp.AUDIO_RATE
                var phase = 0.0
                val decalage = 2 * Math.PI * 5_000.0 / Dsp.RTL_RATE
                while (!fini) {
                    val n = src.read(audio); if (n <= 0) break
                    val iq = ByteArray(n * facteur * 2)
                    var k = 0
                    for (i in 0 until n) {
                        // ±5 kHz for full scale, as the chain's discriminator reads it.
                        val pas = decalage + 2 * Math.PI * (audio[i] / 32768.0 * 5_000.0) / Dsp.RTL_RATE
                        repeat(facteur) {
                            phase += pas
                            iq[k++] = (127.5 + 60 * Math.cos(phase)).toInt().toByte()
                            iq[k++] = (127.5 + 60 * Math.sin(phase)).toInt().toByte()
                        }
                    }
                    o.write(iq); Thread.sleep(25)
                }
            }
        }
    }

    @Test
    fun une_sonde_rs41_se_decode_par_la_cle_et_l_accord_se_recentre() {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "IN78"
        st.demarre()
        st.settings.sondeFreqHz = 403_000_000L
        st.settings.sondeModel = "RS41"
        val son = SonPc()
        val sdr = SdrPc(st, son, StockageMemoire(), portee, dossierSondes = File(dossier, "sondes"))
        sdr.lance = false; sdr.port = serveur.localPort
        fausseSonde()
        assertTrue(sdr.demarreSondes())
        // The decoders of sound are not given a sonde's bits.
        assertFalse(son.etat.value.actif)
        attends { commandes.any { it.first == 0x01 && it.second == 403_000_000 } }
        // Re-centred on the signal, 5 kHz off: then the frames come.
        attends(20_000) { kotlin.math.abs(sdr.etat.value.decalageHz - 5_000) < 1_500 }
        attends(25_000) { sdr.sondes.etat.trames > 0 }
        assertEquals("RS41", sdr.sondes.etat.type)
        assertEquals(403_000_000L, sdr.sondes.etat.derniere!!.freqHz)
        assertTrue(sdr.sondes.journaux().any { it.name.endsWith(".csv") })
        sdr.arrete()
        assertFalse(sdr.sondes.etat.actif)
    }

    @Test
    fun le_mode_d_un_emetteur() {
        assertEquals(fr.f4ioz.satcombo.sdr.RxMode.NFM, SdrPc.modeDe("FM"))
        assertEquals(fr.f4ioz.satcombo.sdr.RxMode.USB, SdrPc.modeDe("USB"))
        assertEquals(fr.f4ioz.satcombo.sdr.RxMode.LSB, SdrPc.modeDe("LSB"))
        assertEquals(fr.f4ioz.satcombo.sdr.RxMode.USB, SdrPc.modeDe("CW"))
        assertEquals(fr.f4ioz.satcombo.sdr.RxMode.NFM, SdrPc.modeDe("AFSK"))
    }

    @Test
    fun la_cle_quitte_meteor_quand_on_la_met_sur_qo100_et_le_son_revient() {
        // A METEOR (the ISS's elements under METEOR-M2 4's number): the dongle starts for its pictures, silent.
        File(dossier, "amsat-tle.txt").appendText("""METEOR-M2 4
1 59051U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996
2 59051  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734
""")
        val t = Catalogue.lisTle(File(dossier, "amsat-tle.txt").readText()).first().epochMs!!
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee, horloge = { t })
        st.settings.manualLocator = "JN18FS"
        st.demarre()
        assertTrue(st.suis(59051))
        val son = SonPc()
        val recu = java.util.concurrent.atomic.AtomicInteger()
        son.auditeurs += { _, n, _ -> recu.addAndGet(n) }
        val sdr = SdrPc(st, son, StockageMemoire(), portee, horloge = { t }, meteor = MeteorPc(st, File(dossier, "meteor")))
        st.settings.sdrAudio = false
        sdr.lance = false
        sdr.port = serveur.localPort
        fauxRtlTcp()
        assertTrue(sdr.demarre())
        assertTrue(sdr.pourMeteor)
        assertFalse(son.etat.value.actif)
        // QO-100 set on the dongle (the morning of 15 October): METEOR left, the sound back.
        st.qo100.aLaCle(true)
        attends { !sdr.pourMeteor && son.etat.value.appareil == SdrPc.NOM_SOURCE }
        attends { recu.get() > Dsp.AUDIO_RATE / 4 }
        sdr.arrete()
    }
}
