/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/** The IIOD client and the Pluto receiver against an IIO daemon of a few lines. */
class PlutoIiodTest {

    /**
     * A Pluto as its daemon answers: the context, the attributes, the RX
     * buffer (a tone at [toneHz], 12-bit, in two pieces per READBUF).
     */
    private class FauxIiod(
        val xml: String = XML,
        val toneHz: Double = 100_000.0,
        val amplitude: Double = 1000.0,
        /** READBUFs answered before the connection is cut (unplugged), or silence ([muet]). */
        val coupeApres: Int = Int.MAX_VALUE,
        val muet: Boolean = false,
    ) {
        val serveur = ServerSocket(0)
        val commandes = CopyOnWriteArrayList<String>()
        val attributs = ConcurrentHashMap(mapOf(
            "ad9361-phy OUTPUT altvoltage0 frequency" to "2400000000",
            "ad9361-phy OUTPUT altvoltage0 frequency_available" to "[70000000 1 6000000000]",
            "ad9361-phy INPUT voltage0 sampling_frequency" to "30720000",
            "ad9361-phy INPUT voltage0 rf_bandwidth" to "18000000",
            "ad9361-phy INPUT voltage0 gain_control_mode" to "slow_attack",
            "ad9361-phy INPUT voltage0 hardwaregain" to "71.000000 dB",
        ))
        /** Writes refused with this errno. */
        val refus = ConcurrentHashMap<String, Int>()
        @Volatile var readbufs = 0
        private var phase = 0.0

        init {
            thread(isDaemon = true) {
                runCatching { while (true) { val c = serveur.accept(); thread(isDaemon = true) { sert(c) } } }
            }
        }

        val port get() = serveur.localPort

        private fun ligne(e: InputStream): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = e.read()
                if (c < 0) return null
                if (c == '\n'.code) return b.toString("US-ASCII").trimEnd('\r')
                b.write(c)
            }
        }

        private fun sert(c: Socket) = runCatching {
            c.use {
                val e = BufferedInputStream(c.getInputStream())
                val o = c.getOutputStream()
                while (true) {
                    val l = ligne(e) ?: break
                    commandes += l
                    val m = l.split(' ')
                    when (m[0]) {
                        "VERSION" -> o.write("0.24.faux     \n".toByteArray())
                        "PRINT" -> { val x = xml.toByteArray(); o.write("${x.size}\n".toByteArray()); o.write(x); o.write('\n'.code) }
                        "READ" -> {
                            val v = attributs[m.drop(1).joinToString(" ")]
                            if (v == null) o.write("-2\n".toByteArray())
                            else { val b = v.toByteArray() + 0; o.write("${b.size}\n".toByteArray()); o.write(b); o.write('\n'.code) }
                        }
                        "WRITE" -> {
                            val n = m.last().toInt()
                            val v = String(ByteArray(n).also { var k = 0; while (k < n) k += e.read(it, k, n - k) })
                            val cle = m.drop(1).dropLast(1).joinToString(" ")
                            val r = refus[cle]
                            if (r != null) o.write("$r\n".toByteArray())
                            else { attributs[cle] = v; o.write("$n\n".toByteArray()) }
                        }
                        "TIMEOUT", "OPEN", "CLOSE" -> o.write("0\n".toByteArray())
                        "READBUF" -> {
                            if (readbufs >= coupeApres) return@runCatching
                            if (muet) { Thread.sleep(60_000); return@runCatching }
                            readbufs++
                            val n = m[2].toInt()
                            val fs = 2_116_800.0
                            val d = ByteArray(n)
                            for (k in 0 until n / 4) {
                                val i = Math.round(amplitude * cos(phase)).toInt(); val q = Math.round(amplitude * sin(phase)).toInt()
                                phase += 2 * PI * toneHz / fs
                                d[4 * k] = i.toByte(); d[4 * k + 1] = (i shr 8).toByte(); d[4 * k + 2] = q.toByte(); d[4 * k + 3] = (q shr 8).toByte()
                            }
                            // Two pieces, the mask after the first length only.
                            val a = n / 2
                            o.write("$a\n00000003\n".toByteArray()); o.write(d, 0, a)
                            o.write("${n - a}\n".toByteArray()); o.write(d, a, n - a)
                            Thread.sleep(15)
                        }
                        else -> o.write("-22\n".toByteArray())
                    }
                    o.flush()
                }
            }
        }

        fun close() = runCatching { serveur.close() }
    }

    private val serveurs = ArrayList<FauxIiod>()
    private fun faux(f: FauxIiod) = f.also { serveurs += it }
    @After fun fin() { serveurs.forEach { it.close() } }

    @Test
    fun le_contexte_dit_la_carte_son_micrologiciel_et_le_masque() {
        val f = faux(FauxIiod())
        val p = PlutoSdr.connecte("127.0.0.1", f.port)
        assertEquals("PlutoSDR Rev.C (Z7010-AD9364)", p.modele)
        assertEquals("v0.37", p.micrologiciel)
        assertEquals("PlutoSDR Rev.C (Z7010-AD9364) · micrologiciel v0.37", p.description)
        assertEquals(70_000_000L, p.frequenceMinHz)
        assertEquals(6_000_000_000L, p.frequenceMaxHz)
        p.close()
        // Connecting writes nothing.
        assertTrue(f.commandes.none { it.startsWith("WRITE") || it.startsWith("OPEN") })
    }

    @Test
    fun lecture_et_ecriture_d_attributs_et_codes_d_erreur() {
        val f = faux(FauxIiod())
        val c = Iiod.connecte("127.0.0.1", f.port)
        assertTrue(c.version().startsWith("0.24"))
        assertEquals("slow_attack", c.lit("ad9361-phy", Iiod.Canal(false, "voltage0"), "gain_control_mode"))
        c.ecrit("ad9361-phy", Iiod.Canal(true, "altvoltage0"), "frequency", "435000000")
        assertEquals("435000000", c.lit("ad9361-phy", Iiod.Canal(true, "altvoltage0"), "frequency"))
        try { c.lit("ad9361-phy", null, "inconnu"); fail() } catch (x: Iiod.Refus) { assertEquals(-2, x.code) }
        f.refus["ad9361-phy INPUT voltage0 hardwaregain"] = -22
        try { c.ecrit("ad9361-phy", Iiod.Canal(false, "voltage0"), "hardwaregain", "99"); fail() }
        catch (x: Iiod.Refus) { assertEquals(-22, x.code); assertTrue(x.message!!.contains("valeur refusée")) }
        // The connection still in step after the refusals.
        assertEquals("435000000", c.lit("ad9361-phy", Iiod.Canal(true, "altvoltage0"), "frequency"))
        c.close()
    }

    @Test
    fun reglages_du_cote_reception_seulement() {
        val f = faux(FauxIiod())
        val p = PlutoSdr.connecte("127.0.0.1", f.port)
        assertEquals(Dsp.RTL_RATE.toDouble(), p.setSampleRate(Dsp.RTL_RATE), 0.5)
        assertEquals("2116800", f.attributs["ad9361-phy INPUT voltage0 sampling_frequency"])
        assertEquals("1058400", f.attributs["ad9361-phy INPUT voltage0 rf_bandwidth"])
        p.setCenterFreq(435_000_000L)
        assertEquals("435000000", f.attributs["ad9361-phy OUTPUT altvoltage0 frequency"])
        // +10 ppm: the LO asked 10 ppm lower, so the crystal's error brings it back.
        p.setFreqCorrection(10)
        assertEquals("434995650", f.attributs["ad9361-phy OUTPUT altvoltage0 frequency"])
        p.setGain(305)
        assertEquals("manual", f.attributs["ad9361-phy INPUT voltage0 gain_control_mode"])
        assertEquals("30", f.attributs["ad9361-phy INPUT voltage0 hardwaregain"])
        p.setGain(900)
        assertEquals("71", f.attributs["ad9361-phy INPUT voltage0 hardwaregain"])
        p.setGain(null)
        assertEquals("slow_attack", f.attributs["ad9361-phy INPUT voltage0 gain_control_mode"])
        // Out of the Pluto's range: nothing sent, a warning, the reception goes on.
        val avant = f.commandes.size
        p.setCenterFreq(50_000_000L)
        assertEquals(avant, f.commandes.size)
        assertTrue(p.avertissement.contains("70 MHz"))
        assertTrue(p.ouvert)
        // A refused value: a warning, still open.
        f.refus["ad9361-phy OUTPUT altvoltage0 frequency"] = -22
        p.setCenterFreq(5_999_000_000L)
        assertTrue(p.avertissement.contains("valeur refusée"))
        assertTrue(p.ouvert)
        p.close()
        sansEmission(f)
    }

    @Test
    fun le_tampon_decime_garde_le_ton_et_les_seize_bits() {
        val f = faux(FauxIiod(toneHz = 100_000.0, amplitude = 1000.0))
        val p = PlutoSdr.connecte("127.0.0.1", f.port)
        p.setSampleRate(Dsp.RTL_RATE)
        assertTrue(p.iq16)
        val b = ShortArray(PlutoSdr.BLOC)
        var n = 0
        repeat(4) { n = p.read16(b, 3000) }   // past the filter's start
        assertEquals(PlutoSdr.BLOC, n)
        // Amplitude 1000 on 12 bits: ×16 on 16 bits.
        var somme = 0.0; var dphi = 0.0
        for (k in 1 until n / 2) {
            somme += hypot(b[2 * k].toDouble(), b[2 * k + 1].toDouble())
            val a = atan2(b[2 * k + 1].toDouble(), b[2 * k].toDouble()) - atan2(b[2 * k - 1].toDouble(), b[2 * k - 2].toDouble())
            dphi += Math.IEEEremainder(a, 2 * PI)
        }
        assertEquals(16_000.0, somme / (n / 2 - 1), 200.0)
        val hz = dphi / (n / 2 - 1) / (2 * PI) * Dsp.RTL_RATE
        assertEquals(100_000.0, hz, 5.0)
        // The bytes for a reception that only takes those: the same tone, ±63.
        val o = ByteArray(1000)
        assertEquals(1000, p.read(o, 3000))
        val i0 = (o[0].toInt() and 0xff) - 128; val q0 = (o[1].toInt() and 0xff) - 128
        assertEquals(62.5, hypot(i0.toDouble(), q0.toDouble()), 2.0)
        p.close()
        assertTrue(f.commandes.any { it == "OPEN cf-ad9361-lpc ${PlutoSdr.ECHANTILLONS} 00000003" })
        Thread.sleep(200)
        assertTrue(f.commandes.any { it == "CLOSE cf-ad9361-lpc" })
        sansEmission(f)
    }

    @Test
    fun debranche_en_pleine_reception() {
        val f = faux(FauxIiod(coupeApres = 3))
        val p = PlutoSdr.connecte("127.0.0.1", f.port)
        p.setSampleRate(Dsp.RTL_RATE)
        val b = ShortArray(PlutoSdr.BLOC)
        var n: Int
        var lus = 0
        val fin = System.currentTimeMillis() + 5000
        do { n = p.read16(b, 200); if (n > 0) lus++ } while (n >= 0 && System.currentTimeMillis() < fin)
        assertEquals(-1, n)
        assertTrue(lus > 0)
        assertFalse(p.ouvert)
        assertEquals("l'ADALM-PLUTO a coupé la liaison", p.erreur)
        p.close()
    }

    @Test
    fun muet_en_pleine_reception() {
        val f = faux(FauxIiod(muet = true))
        val p = PlutoSdr.connecte("127.0.0.1", f.port, attenteMs = 800)
        p.setSampleRate(Dsp.RTL_RATE)
        val b = ShortArray(PlutoSdr.BLOC)
        var n: Int
        val fin = System.currentTimeMillis() + 5000
        do { n = p.read16(b, 200) } while (n >= 0 && System.currentTimeMillis() < fin)
        assertEquals(-1, n)
        assertEquals("l'ADALM-PLUTO ne répond plus (débranché ?)", p.erreur)
        p.close()
    }

    @Test
    fun pas_un_pluto_ou_personne() {
        val f = faux(FauxIiod(xml = "<?xml version=\"1.0\"?><context name=\"x\"><device id=\"iio:device0\" name=\"xadc\"></device></context>"))
        try { PlutoSdr.connecte("127.0.0.1", f.port); fail() } catch (x: IOException) { assertTrue(x.message!!, x.message!!.contains("pas un ADALM-PLUTO")) }
        val libre = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        try { PlutoSdr.connecte("127.0.0.1", libre, 500); fail() } catch (x: IOException) { assertTrue(x.message!!.contains("injoignable")) }
    }

    @Test
    fun le_decimateur_rejette_ce_qui_replierait() {
        fun niveau(hz: Double): Double {
            val d = DecimateurIq(2, 1.0)
            val n = 20_000
            val brut = ByteArray(4 * n)
            for (k in 0 until n) {
                val i = Math.round(10_000 * cos(2 * PI * hz * k / 2_116_800)).toInt(); val q = Math.round(10_000 * sin(2 * PI * hz * k / 2_116_800)).toInt()
                brut[4 * k] = i.toByte(); brut[4 * k + 1] = (i shr 8).toByte(); brut[4 * k + 2] = q.toByte(); brut[4 * k + 3] = (q shr 8).toByte()
            }
            val s = ShortArray(n + 4)
            val m = d.traite(brut, brut.size, s)
            var p = 0.0
            for (k in 100 until m / 2) p += hypot(s[2 * k].toDouble(), s[2 * k + 1].toDouble())
            return 20 * log10(p / (m / 2 - 100) / 10_000)
        }
        assertEquals(0.0, niveau(0.0), 0.05)
        assertEquals(0.0, niveau(300_000.0), 0.1)
        assertTrue(niveau(750_000.0) < -60)
    }

    @Test
    fun la_chaine_en_seize_bits_entend_comme_en_octets() {
        val n = 32_768
        val octets = ByteArray(2 * n); val courts = ShortArray(2 * n)
        for (k in 0 until n) {
            val i = 127.5 + 60 * cos(2 * PI * 3_000.0 * k / Dsp.RTL_RATE); val q = 127.5 + 60 * sin(2 * PI * 3_000.0 * k / Dsp.RTL_RATE)
            octets[2 * k] = Math.round(i).toInt().toByte(); octets[2 * k + 1] = Math.round(q).toInt().toByte()
            courts[2 * k] = Math.round((Math.round(i) - 127.5) / 127.5 * 32768).toInt().toShort()
            courts[2 * k + 1] = Math.round((Math.round(q) - 127.5) / 127.5 * 32768).toInt().toShort()
        }
        val a = RxChain(); val b = RxChain()
        a.mode = RxMode.USB; b.mode = RxMode.USB
        val sa = ShortArray(a.maxAudio(2 * n)); val sb = ShortArray(b.maxAudio(2 * n))
        val na = a.process(octets, 2 * n, sa); val nb = b.process16(courts, 2 * n, sb)
        assertEquals(na, nb)
        assertEquals(a.levelDb, b.levelDb, 0.01f)
        for (k in 0 until na) assertEquals(sa[k].toDouble(), sb[k].toDouble(), 2.0)
        assertEquals(0f, b.clipRatio)
    }

    /** Nothing sent to the transmit side, whatever happened. */
    private fun sansEmission(f: FauxIiod) {
        for (c in f.commandes) {
            assertFalse(c, c.contains("dds") || c.contains("altvoltage1") || c.contains("OUTPUT voltage") || c.contains("ensm_mode") ||
                c.contains("calib") || c.contains("filter_fir"))
            if (c.startsWith("WRITE")) assertTrue(c, PlutoSdr.ECRITURES.any { (canal, attr) -> c.startsWith("WRITE ad9361-phy $canal $attr ") })
            if (c.startsWith("OPEN") || c.startsWith("READBUF") || c.startsWith("CLOSE")) assertTrue(c, c.split(' ')[1] == "cf-ad9361-lpc")
        }
    }

    companion object {
        val XML = """<?xml version="1.0" encoding="utf-8"?><context name="local" version-major="0" version-minor="24" version-git="v0.24" description="Linux">""" +
            """<context-attribute name="hw_model" value="Analog Devices PlutoSDR Rev.C (Z7010-AD9364)" />""" +
            """<context-attribute name="fw_version" value="v0.37" />""" +
            """<device id="iio:device0" name="ad9361-phy" ><channel id="voltage0" type="input" ><attribute name="hardwaregain" filename="in_voltage0_hardwaregain" /></channel></device>""" +
            """<device id="iio:device2" name="cf-ad9361-dds-core-lpc" ></device>""" +
            """<device id="iio:device3" name="cf-ad9361-lpc" ><channel id="voltage0" type="input" ><scan-element index="0" format="le:S12/16&gt;&gt;0" /></channel>""" +
            """<channel id="voltage1" type="input" ><scan-element index="1" format="le:S12/16&gt;&gt;0" /></channel></device></context>"""
    }
}
