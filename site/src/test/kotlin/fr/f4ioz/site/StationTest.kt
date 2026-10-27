/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.satcombo.sdr.Fft
import fr.f4ioz.site.station.HpsdrDecouverte
import fr.f4ioz.site.station.HpsdrDemod
import fr.f4ioz.site.station.HpsdrProtocole
import fr.f4ioz.site.station.HpsdrSession
import fr.f4ioz.site.station.ServeurWs
import fr.f4ioz.site.station.Ws
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The station's receiver: the frames sent (never a transmit bit, never a payload), the IQ read back,
 * the spectrum's orientation, the demodulators, a whole session against a **fake radio** on the
 * loopback (no real SDR touched), the WebSocket's gate.
 */
class StationTest {

    /** Every word (C0) of an EP2 frame has MOX (bit 0) at 0 and its TX payload is zeros. */
    private fun verifieEp2(b: ByteArray) {
        assertEquals(1032, b.size)
        for (k in 0..1) {
            val o = 8 + k * 512
            assertEquals(0x7F, b[o].toInt()); assertEquals(0x7F, b[o + 1].toInt()); assertEquals(0x7F, b[o + 2].toInt())
            assertEquals("MOX", 0, b[o + 3].toInt() and 1)
            for (i in o + 8 until o + 512) assertEquals("charge TX", 0, b[i].toInt())
        }
    }

    @Test fun `Metis asks for the IQ stream only`() {
        val s = HpsdrProtocole.metis(true)
        assertEquals(64, s.size)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xFE.toByte(), 0x04, 0x01), s.copyOf(4))
        assertTrue(s.drop(4).all { it.toInt() == 0 })
        assertEquals(0x00, HpsdrProtocole.metis(false)[3].toInt())
    }

    @Test fun `EP2 frames never transmit, whatever the frequency and sequence`() {
        for (f in listOf(100_000L, 1_840_000L, 7_100_000L, 14_074_000L, 28_200_000L, 50_313_000L, 60_000_000L, 0xFFFFFFFFL))
            for (seq in listOf(0L, 1L, 255L, 0x7FFFFFFFL, 0xFFFFFFFFL))
                for (r in 0..3) verifieEp2(HpsdrProtocole.ep2(seq, r, f))
        val b = HpsdrProtocole.ep2(0x01020304L, 2, 14_074_000L)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xFE.toByte(), 0x01, 0x02, 1, 2, 3, 4), b.copyOf(8))
        assertEquals(0x00, b[11].toInt()); assertEquals(2, b[12].toInt())
        // The frequency in the C0=0x02 word (the NCO RX1 follows on this radio), big-endian.
        assertEquals(0x02, b[8 + 512 + 3].toInt())
        val f = ((b[524].toLong() and 0xFF) shl 24) or ((b[525].toLong() and 0xFF) shl 16) or ((b[526].toLong() and 0xFF) shl 8) or (b[527].toLong() and 0xFF)
        assertEquals(14_074_000L, f)
        assertEquals(mapOf(48_000 to 0, 96_000 to 1, 192_000 to 2, 384_000 to 3), HpsdrProtocole.CODES_DEBIT)
    }

    /** An EP6 frame carrying [iq] (126 samples, I and Q as 24-bit integers). */
    private fun ep6(seq: Int, iq: IntArray): ByteArray {
        val b = ByteArray(1032)
        b[0] = 0xEF.toByte(); b[1] = 0xFE.toByte(); b[2] = 0x01; b[3] = 0x06
        b[7] = seq.toByte()
        var k = 0
        for (f in 0..1) {
            val o = 8 + f * 512
            b[o] = 0x7F; b[o + 1] = 0x7F; b[o + 2] = 0x7F
            for (s in 0 until 63) {
                val p = o + 8 + s * 8
                for ((j, v) in listOf(iq[k++], iq[k++]).withIndex()) { b[p + 3 * j] = (v shr 16).toByte(); b[p + 3 * j + 1] = (v shr 8).toByte(); b[p + 3 * j + 2] = v.toByte() }
            }
        }
        return b
    }

    @Test fun `EP6 frames are read with their 24-bit sign`() {
        val iq = IntArray(252) { if (it % 2 == 0) 0x7FFFFF else -0x800000 }
        val l = HpsdrProtocole.ep6(ep6(1, iq))
        assertEquals(252, l.size)
        assertEquals(8_388_607f, l[0]); assertEquals(-8_388_608f, l[1])
        assertEquals(0, HpsdrProtocole.ep6(ByteArray(1031)).size)
        assertEquals(0, HpsdrProtocole.ep6(ep6(1, iq).also { it[3] = 0x04 }).size)
    }

    /** A tone at [hz] from the centre, [n] complex samples at [debit], interleaved, full scale × [a]. */
    private fun ton(hz: Double, n: Int, debit: Int, a: Double = 0.3, depart: Int = 0): FloatArray =
        FloatArray(2 * n) { i -> val k = (i / 2 + depart).toDouble(); val ph = 2 * PI * hz * k / debit
            ((if (i % 2 == 0) cos(ph) else sin(ph)) * a * HpsdrDemod.PLEINE_ECHELLE).toFloat() }

    private fun picHz(re: DoubleArray, im: DoubleArray, debit: Double): Double {
        Fft.transform(re, im)
        val n = re.size
        var best = 0; var m = -1.0
        for (k in 0 until n) { val v = re[k] * re[k] + im[k] * im[k]; if (v > m) { m = v; best = k } }
        return (if (best >= n / 2) best - n else best) * debit / n
    }

    @Test fun `the conjugation turns this radio's inverted spectrum right`() {
        // On this radio a carrier 5 kHz above the tuned frequency arrives as −5 kHz in the raw IQ.
        val iq = ton(-5000.0, 2048, 48_000)
        HpsdrSession.conjugue(iq)
        val re = DoubleArray(2048) { iq[2 * it].toDouble() }; val im = DoubleArray(2048) { iq[2 * it + 1].toDouble() }
        assertEquals(5000.0, picHz(re, im, 48_000.0), 48_000.0 / 2048)
    }

    private fun audioPic(pcm: ByteArray): Pair<Double, Double> {
        val n = pcm.size / 2
        val s = DoubleArray(n) { (((pcm[2 * it + 1].toInt()) shl 8) or (pcm[2 * it].toInt() and 0xFF)).toDouble() }
        val m = Integer.highestOneBit(n)
        val re = DoubleArray(m) { s[n - m + it] }; val im = DoubleArray(m)
        val rms = sqrt(re.sumOf { it * it } / m)
        return picHz(re, im, 12_000.0).let { kotlin.math.abs(it) } to rms
    }

    private fun demodule(mode: String, hz: Double, debit: Int = 48_000): Pair<Double, Double> {
        val d = HpsdrDemod(debit); d.regle(mode = mode, volume = 1f)
        val o = java.io.ByteArrayOutputStream()
        for (b in 0 until 60) o.write(d.traite(ton(hz, 1008, debit, depart = b * 1008), 1008))
        return audioPic(o.toByteArray())
    }

    @Test fun `USB and LSB give the tone, the other sideband rejected`() {
        val (fu, ru) = demodule("USB", 1000.0)
        assertTrue("USB $fu", fu in 880.0..1120.0)
        val (fl, _) = demodule("LSB", -1000.0)
        assertTrue("LSB $fl", fl in 880.0..1120.0)
        val (_, rRejet) = demodule("USB", -1000.0)
        assertTrue("rejet $rRejet / $ru", rRejet < ru * 0.5)
        val (fh, _) = demodule("USB", 1000.0, 384_000)
        assertTrue("USB 384 k $fh", fh in 880.0..1120.0)
        val (fc, _) = demodule("CW", 700.0)
        assertTrue("CW $fc", fc in 600.0..800.0)
    }

    @Test fun `a discovery answer is read`() {
        val b = ByteArray(60); b[0] = 0xEF.toByte(); b[1] = 0xFE.toByte(); b[2] = 0x02
        for (i in 3..8) b[i] = (0x10 + i).toByte(); b[9] = 32; b[10] = 0x01
        val r = HpsdrDecouverte.reponse(b, 60, "192.0.2.7")!!
        assertTrue(r.enLigne); assertFalse(r.occupee); assertEquals("13:14:15:16:17:18", r.mac); assertEquals("3.2", r.firmware); assertEquals("Hermes (ANAN-10/10E)", r.carte)
        b[2] = 0x03; assertTrue(HpsdrDecouverte.reponse(b, 60, "x")!!.occupee)
        b[2] = 0x04; assertEquals(null, HpsdrDecouverte.reponse(b, 60, "x"))
    }

    /** A fake radio on the loopback: answers discovery, streams a tone after Metis start, keeps every frame it gets. */
    private class FausseRadio : AutoCloseable {
        val prise = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val recus: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
        @Volatile var client: InetSocketAddress? = null
        @Volatile var fin = false
        val port get() = prise.localPort
        init {
            prise.soTimeout = 200
            Thread {
                val b = ByteArray(2048)
                while (!fin) {
                    val p = DatagramPacket(b, b.size)
                    try { prise.receive(p) } catch (e: Exception) { continue }
                    val d = p.data.copyOf(p.length); recus += d
                    if (d.size == 63 && d[2].toInt() == 2) { val r = ByteArray(60); r[0] = 0xEF.toByte(); r[1] = 0xFE.toByte(); r[2] = 2; r[9] = 32; r[10] = 1
                        prise.send(DatagramPacket(r, r.size, p.socketAddress)) }
                    if (d.size == 64 && d[2].toInt() == 4) client = if (d[3].toInt() == 1) p.socketAddress as InetSocketAddress else null
                }
            }.apply { isDaemon = true; start() }
            Thread {
                var seq = 0; var k = 0
                while (!fin) {
                    val c = client
                    if (c != null) {
                        // A carrier 3 kHz above the tuned frequency: −3 kHz in this radio's raw IQ.
                        val t = IntArray(252) { i -> val n = (k + i / 2).toDouble(); val ph = -2 * PI * 3000.0 * n / 48_000
                            ((if (i % 2 == 0) cos(ph) else sin(ph)) * 2_000_000).roundToInt() }
                        k += 126
                        runCatching { prise.send(DatagramPacket(ep6(seq++, t), 1032, c)) }
                    }
                    Thread.sleep(2)
                }
            }.apply { isDaemon = true; start() }
        }
        override fun close() { fin = true; prise.close() }
        private fun ep6(seq: Int, iq: IntArray) = StationTest().ep6Public(seq, iq)
    }

    fun ep6Public(seq: Int, iq: IntArray) = ep6(seq, iq)

    @Test fun `a session against a fake radio has its spectrum the right way up, transmits nothing, stops at the end`() {
        FausseRadio().use { radio ->
            assertTrue(HpsdrDecouverte.cherche("127.0.0.1", radio.port, false, 1000).enLigne)
            val s = HpsdrSession("127.0.0.1", radio.port, clientMs = 1500L)
            s.regle(hz = 7_100_000L)
            assertTrue(s.assure())
            var o: JSONObject? = null
            val fin = System.currentTimeMillis() + 5000
            while (o == null && System.currentTimeMillis() < fin) { s.touche(); o = s.instantane(); Thread.sleep(50) }
            assertNotNull(o)
            assertEquals(512, o!!.getJSONArray("bins").length())
            assertEquals(7_100_000L, o.getLong("center"))
            // The carrier 3 kHz above: right of the centre.
            val bins = o.getJSONArray("bins"); var best = 0
            for (i in 0 until 512) if (bins.getInt(i) > bins.getInt(best)) best = i
            assertEquals(256 + 3000.0 / (48_000.0 / 512), best.toDouble(), 2.0)
            s.regleAudio(on = true, mode = "USB")
            Thread.sleep(400); s.touche()
            assertTrue(s.audioRecu().isNotEmpty())
            // No client any more: the session ends and tells the radio to stop.
            val t = System.currentTimeMillis() + 6000
            while (s.enMarche && System.currentTimeMillis() < t) Thread.sleep(100)
            assertFalse(s.enMarche)
            Thread.sleep(300)
            val l = synchronized(radio.recus) { radio.recus.toList() }
            val ep2 = l.filter { it.size == 1032 }
            assertTrue(ep2.size > 8)
            ep2.forEach { verifieEp2(it) }
            assertTrue(l.any { it.size == 64 && it[3].toInt() == 1 })
            assertEquals(0, l.last { it.size == 64 }[3].toInt())
            assertTrue(l.filter { it.size == 64 }.all { it[2].toInt() == 4 && (it[3].toInt() == 0 || it[3].toInt() == 1) })
        }
    }

    private fun poignee(port: Int, origine: String?, cookie: String?): Pair<String, BufferedInputStream> {
        val c = Socket("127.0.0.1", port); c.soTimeout = 5000
        val o = c.getOutputStream()
        o.write(("GET /ws/sdr/spectrum HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Version: 13\r\n" + (origine?.let { "Origin: $it\r\n" } ?: "") + (cookie?.let { "Cookie: $it\r\n" } ?: "") + "\r\n").toByteArray())
        val e = BufferedInputStream(c.getInputStream())
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) { val b = e.read(); if (b < 0) break; sb.append(b.toChar()) }
        return sb.toString() to e
    }

    private fun trame(e: BufferedInputStream): Pair<Int, ByteArray> {
        val b0 = e.read(); var n = e.read() and 0x7F
        if (n == 126) n = (e.read() shl 8) or e.read()
        val d = ByteArray(n); var l = 0; while (l < n) l += e.read(d, l, n - l)
        return (b0 and 0x0F) to d
    }

    @Test fun `the WebSocket wants the site's origin and an admin session`() {
        FausseRadio().use { radio ->
            val s = HpsdrSession("127.0.0.1", radio.port, clientMs = 1500L)
            val ws = ServeurWs("127.0.0.1", 0, s, { it?.contains("f4ioz_admin=" + "x".repeat(40)) == true }, setOf("https://f4ioz.example")) {}
            ws.demarre()
            try {
                assertTrue(poignee(ws.portReel, "https://evil.example", "f4ioz_admin=" + "x".repeat(40)).first.startsWith("HTTP/1.1 403"))
                assertTrue(poignee(ws.portReel, null, "f4ioz_admin=" + "x".repeat(40)).first.startsWith("HTTP/1.1 403"))
                val (t, e) = poignee(ws.portReel, "https://f4ioz.example", "autre=1")
                assertTrue(t.startsWith("HTTP/1.1 101"))
                assertTrue(t.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
                val (op, d) = trame(e)
                assertEquals(Ws.FERMETURE, op); assertEquals(1008, ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF))
                assertFalse(s.enMarche)
                // The admin: the spectrum comes; the frequency it sends is applied.
                val (t2, e2) = poignee(ws.portReel, "https://f4ioz.example", "f4ioz_admin=" + "x".repeat(40))
                assertTrue(t2.startsWith("HTTP/1.1 101"))
                var json: JSONObject? = null
                val fin = System.currentTimeMillis() + 5000
                while (json == null && System.currentTimeMillis() < fin) { val (o, b) = trame(e2); if (o == Ws.TEXTE) json = JSONObject(String(b)).takeIf { it.has("bins") } }
                assertNotNull(json)
                assertTrue(s.enMarche)
                s.regle(hz = 14_100_000L)
                assertEquals(14_100_000L, s.freq)
            } finally { ws.arrete(); s.arrete() }
        }
    }
}
