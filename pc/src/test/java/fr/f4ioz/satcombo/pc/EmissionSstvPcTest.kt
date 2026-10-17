/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.Emetteur
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.Bladerf
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.FauxBladerf
import fr.f4ioz.satcombo.sdr.ModulationAudio
import fr.f4ioz.satcombo.sdr.RxChain
import fr.f4ioz.satcombo.sdr.RxMode
import fr.f4ioz.satcombo.sstv.IndicatifSstv
import fr.f4ioz.satcombo.sstv.SstvDecoder
import fr.f4ioz.satcombo.sstv.SstvMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * SSTV on the air from the page's side to the transmitters' samples, all
 * offline: a fake IIO daemon (the Pluto) demodulates what it is sent with
 * SatMe's own receiver and decodes the picture; a fake bladeRF-cli keeps
 * its samples. No radio is ever touched.
 */
class EmissionSstvPcTest {

    /** An IIO daemon handing every TX buffer to [recoit], at [vitesse] times the DAC's pace. */
    private class FauxPluto(val vitesse: Double = 3.0, val recoit: (ShortArray) -> Unit = {}) {
        val serveur = ServerSocket(0)
        val connexions = AtomicInteger()
        val commandes = CopyOnWriteArrayList<String>()
        val attributs = ConcurrentHashMap<String, String>()
        @Volatile var octets = 0L
        val port get() = serveur.localPort

        init {
            thread(isDaemon = true) { runCatching { while (true) { val c = serveur.accept(); connexions.incrementAndGet(); thread(isDaemon = true) { sert(c) } } } }
        }

        private fun ligne(e: InputStream): String? {
            val b = ByteArrayOutputStream()
            while (true) { val c = e.read(); if (c < 0) return null; if (c == '\n'.code) return b.toString("US-ASCII").trimEnd('\r'); b.write(c) }
        }

        private fun lis(e: InputStream, n: Int) = ByteArray(n).also { var k = 0; while (k < n) { val r = e.read(it, k, n - k); if (r < 0) error("fin"); k += r } }

        private fun sert(c: Socket) = runCatching {
            c.use {
                val e = BufferedInputStream(c.getInputStream()); val o = c.getOutputStream()
                while (true) {
                    val l = ligne(e) ?: break
                    val m = l.split(' ')
                    commandes += l
                    when (m[0]) {
                        "PRINT" -> { val x = EmissionDatvPcTest.XML.toByteArray(); o.write("${x.size}\n".toByteArray()); o.write(x); o.write('\n'.code) }
                        "WRITE" -> { val n = m.last().toInt(); attributs[m.drop(1).dropLast(1).joinToString(" ")] = String(lis(e, n)); o.write("$n\n".toByteArray()) }
                        "TIMEOUT", "OPEN", "CLOSE" -> o.write("0\n".toByteArray())
                        "WRITEBUF" -> {
                            val n = m[2].toInt()
                            // As the real daemon: 0 (ready), the bytes, then the count pushed.
                            o.write("0\n".toByteArray()); o.flush()
                            val d = lis(e, n)
                            recoit(ShortArray(n / 2) { ((d[2 * it].toInt() and 0xFF) or (d[2 * it + 1].toInt() shl 8)).toShort() })
                            octets += n
                            Thread.sleep((n / 4 / (2_116_800.0 * vitesse) * 1000).toLong())
                            o.write("$n\n".toByteArray())
                        }
                        else -> o.write("-22\n".toByteArray())
                    }
                    o.flush()
                }
            }
        }

        val loEteint get() = attributs["ad9361-phy OUTPUT altvoltage1 powerdown"] == "1"
        fun close() = runCatching { serveur.close() }
    }

    /** SatMe's receiver on the Pluto's 2 116 800 S/s: halved, then the SDR chain and the SSTV decoder. */
    private class Recepteur(mode: RxMode) {
        val chaine = RxChain(Dsp.RTL_RATE.toDouble()).apply { this.mode = mode }
        @Volatile var image: Pair<SstvMode, IntArray>? = null
        @Volatile var lignes = 0
        val dec = SstvDecoder(Dsp.AUDIO_RATE, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                if (image == null) { image = mode to pixels.copyOf(); lignes = linesDone }
            }
        })
        private var son = ShortArray(0)
        fun recoit(b: ShortArray) {
            val n = b.size / 4
            val d = ShortArray(2 * n) { k -> ((b[4 * (k / 2) + (k % 2)] + b[4 * (k / 2) + 2 + (k % 2)]) / 2).toShort() }
            if (son.size < chaine.maxAudio(4 * n)) son = ShortArray(chaine.maxAudio(4 * n))
            val a = chaine.process16(d, d.size, son)
            dec.feed(son, a)
        }
    }

    private val tmp = File(System.getProperty("java.io.tmpdir"), "satme-test-sstv-tx")
    private val faux = ArrayList<FauxPluto>()
    private val fauxBladerf = ArrayList<FauxBladerf>()
    @After fun fin() { faux.forEach { it.close() }; Bladerf.arreteTout(); fauxBladerf.forEach { it.close() } }

    private fun emission(p: FauxPluto?, indicatif: String = "F4IOZ", autre: String? = null, plutoOccupe: String? = null,
                         vieMs: Long = 15_000L, blade: FauxBladerf? = null): EmissionSstvPc {
        p?.let { faux += it }
        blade?.let { fauxBladerf += it }
        return EmissionSstvPc(StockageMemoire(), { indicatif }, { "JN03" }, plutoHote = { "127.0.0.1" }, plutoPort = { p?.port ?: 9 },
            plutoOccupe = { plutoOccupe }, bladerfOccupe = { null }, bladerfProgramme = { blade?.cli?.path },
            autreEmission = { autre }, gainMaxDb = { -10.0 }, vieMs = vieMs)
    }

    private fun attends(ms: Long, ok: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (ok()) return true; Thread.sleep(50) }
        return ok()
    }

    @Test
    fun rien_n_est_emis_sans_confirmation_indicatif_ni_bonne_frequence() {
        val p = FauxPluto()
        val e = emission(p)
        e.emetteur = Emetteur.PLUTO
        assertEquals("émission non confirmée", e.demarre(false, interieur = true))
        e.frequenceKhz = 145_800L
        assertTrue(e.demarre(true)!!.contains("ISS"))
        assertTrue(e.demarre(true, satellite = true)!!.contains("ISS"))
        e.frequenceKhz = 145_900L
        assertTrue(e.demarre(true)!!.contains("vers un satellite"))
        e.frequenceKhz = 150_000L
        assertTrue(e.demarre(true)!!.contains("hors des bandes"))
        e.frequenceInterieurKhz = 435_000L
        assertTrue(e.demarre(true, interieur = true)!!.contains("essai en intérieur"))
        assertTrue(emission(p, indicatif = "").demarre(true, interieur = true)!!.contains("indicatif"))
        assertEquals("une émission DATV est en cours", emission(p, autre = "une émission DATV est en cours").demarre(true, interieur = true))
        assertEquals("le Pluto reçoit", emission(p, plutoOccupe = "le Pluto reçoit").also { it.emetteur = Emetteur.PLUTO }.demarre(true, interieur = true))
        // PD 290 twice: more than the indoor test's 5 minutes.
        e.frequenceInterieurKhz = 1_245_000L
        e.mode = SstvMode.byName("PD 290")!!; e.repetitions = 2
        assertTrue(e.demarre(true, interieur = true)!!.contains("300 s au plus"))
        // Nothing reached the Pluto, nothing at startup either.
        assertEquals(0, p.connexions.get())
        assertFalse(e.etat.emet)
        assertEquals(0, p.connexions.get())
    }

    @Test
    fun pluto_essai_en_interieur_l_image_decodee_puis_arret_automatique() {
        val rx = Recepteur(RxMode.NFM)
        val p = FauxPluto { rx.recoit(it) }
        val e = emission(p)
        e.emetteur = Emetteur.PLUTO
        e.mode = SstvMode.byName("Robot 36")!!
        e.modulation = ModulationAudio.NBFM
        e.cwId = true
        e.regleGain(0.0)
        assertNull(e.demarre(true, interieur = true))
        assertTrue(e.etat.emet && e.etat.interieur)
        // Indoors: 1245 MHz, −40 dB at most, the rate a multiple of the audio's.
        assertEquals("1245000000", p.attributs["ad9361-phy OUTPUT altvoltage1 frequency"])
        assertEquals("-40.00", p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        assertEquals("2116800", p.attributs["ad9361-phy OUTPUT voltage0 sampling_frequency"])
        // While sending: the parameters stay, the gain stays under −40 dB.
        assertEquals(-40.0, e.regleGain(-20.0)!!, 0.0)
        assertTrue(attends(180_000) { e.vie(); e.tic(); !e.etat.emet })
        assertEquals("image envoyée", e.etat.raison)
        assertEquals("", e.etat.erreur)
        assertTrue(p.loEteint)
        // What SatMe's receiver made of it: the picture with the callsign band.
        val (m, px) = rx.image!!
        assertEquals("Robot 36", m.name)
        assertEquals(240, rx.lignes)
        val src = e.image(m).getOrThrow()
        var s = 0L
        for (y in 2 until m.height - 2) for (x in 0 until m.width) for (sh in intArrayOf(16, 8, 0))
            s += kotlin.math.abs(((src[y * m.width + x] shr sh) and 255) - ((px[y * m.width + x] shr sh) and 255))
        val ecart = s.toDouble() / ((m.height - 4) * m.width * 3)
        println("Pluto → récepteur SatMe : Robot 36 décodé, écart moyen %.1f / 255".format(ecart))
        assertTrue("écart $ecart", ecart < 12)
        // The band is there: its background colour across the top.
        val bande = IndicatifSstv.hauteurBande(m.height)
        val fond = (0 until m.width).count { x -> kotlin.math.abs(((px[(bande / 2) * m.width + x] shr 8) and 255) - ((IndicatifSstv.FOND shr 8) and 255)) < 30 }
        assertTrue("$fond", fond > m.width / 2)
    }

    @Test
    fun la_page_muette_arrete_l_emission() {
        val p = FauxPluto()
        val e = emission(p, vieMs = 1_500L)
        e.emetteur = Emetteur.PLUTO
        e.modulation = ModulationAudio.USB
        assertNull(e.demarre(true, interieur = true))
        assertTrue(attends(10_000) { e.tic(); !e.etat.emet })
        assertEquals("page SatMe fermée ou injoignable", e.etat.raison)
        assertTrue(p.loEteint)
    }

    @Test
    fun bladerf_essai_en_interieur_gain_bride_et_arret() {
        assumeTrue("sh ou mkfifo absent", File("/bin/sh").canExecute() && File("/usr/bin/mkfifo").canExecute())
        val f = FauxBladerf(limite = 4L * 1_058_400 * 2)
        val e = emission(null, blade = f)
        e.emetteur = Emetteur.BLADERF
        e.regleGain(-20.0)
        e.mode = SstvMode.byName("Martin M2")!!
        assertNull(e.demarre(true, interieur = true))
        assertTrue(attends(20_000) { e.vie(); f.compte >= 4L * 1_058_400 * 2 })
        assertTrue(e.json().getBoolean("emet"))
        e.arrete()
        assertFalse(e.etat.emet)
        assertEquals("off", f.txActif)
        assertEquals(0, Bladerf.enCours())
        val c = f.commandes
        assertEquals(listOf("set gain tx1 26", "set frequency tx1 1245000000", "set samplerate tx1 1058400"), c.take(3))
        assertTrue(c.filter { it.startsWith("set gain") }.all { it == "set gain tx1 26" })
        assertTrue(c.containsAll(listOf("tx start", "tx stop", "quit")))
        // The first two seconds, SC16 Q11: the header heard by SatMe's receiver.
        val b = f.iq
        val iq = ShortArray(b.size / 2) { (((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)) * 16).toShort() }
        val chaine = RxChain(Dsp.RTL_RATE.toDouble())
        var vu: SstvMode? = null
        val dec = SstvDecoder(Dsp.AUDIO_RATE, object : SstvDecoder.Listener { override fun onVis(mode: SstvMode) { vu = mode } })
        val son = ShortArray(chaine.maxAudio(2 * iq.size))
        dec.feed(son, chaine.process16(iq, iq.size, son))
        assertEquals("Martin M2", vu?.name)
    }
}
