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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The Pluto's transmit side, against a fake IIO daemon only (never a real Pluto). */
class PlutoEmissionTest {

    /**
     * An IIO daemon taking TX buffers at [debit] pairs per second; it may
     * stop answering them ([muetApres]) or cut the connection ([coupeApres]).
     */
    private class FauxIiod(val debit: Double = 2_000_000.0, val muetApres: Int = Int.MAX_VALUE, val coupeApres: Int = Int.MAX_VALUE, val xml: String = XML) {
        val serveur = ServerSocket(0)
        val commandes = CopyOnWriteArrayList<String>()
        val connexions = AtomicInteger()
        val attributs = ConcurrentHashMap<String, String>(mapOf("ad9361-phy OUTPUT altvoltage1 powerdown" to "0"))
        @Volatile var writebufs = 0
        @Volatile var octetsRecus = 0L
        @Volatile var crete = 0

        init {
            thread(isDaemon = true) {
                runCatching { while (true) { val c = serveur.accept(); connexions.incrementAndGet(); thread(isDaemon = true) { sert(c) } } }
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

        private fun lis(e: InputStream, n: Int): ByteArray = ByteArray(n).also { var k = 0; while (k < n) { val r = e.read(it, k, n - k); if (r < 0) error("fin"); k += r } }

        private fun sert(c: Socket) = runCatching {
            var ouvert = 0
            c.use {
                val e = BufferedInputStream(c.getInputStream())
                val o = c.getOutputStream()
                while (true) {
                    val l = ligne(e) ?: break
                    commandes += l
                    val m = l.split(' ')
                    when (m[0]) {
                        "PRINT" -> { val x = xml.toByteArray(); o.write("${x.size}\n".toByteArray()); o.write(x); o.write('\n'.code) }
                        "READ" -> {
                            val v = attributs[m.drop(1).joinToString(" ")]
                            if (v == null) o.write("-2\n".toByteArray())
                            else { val b = v.toByteArray() + 0; o.write("${b.size}\n".toByteArray()); o.write(b); o.write('\n'.code) }
                        }
                        "WRITE" -> {
                            // Logged with its value: "WRITE dev OUTPUT chan attr = value".
                            val n = m.last().toInt()
                            val cle = m.drop(1).dropLast(1).joinToString(" ")
                            val v = String(lis(e, n))
                            attributs[cle] = v
                            commandes[commandes.size - 1] = "WRITE $cle = $v"
                            o.write("$n\n".toByteArray())
                        }
                        "TIMEOUT" -> o.write("0\n".toByteArray())
                        "OPEN" -> { ouvert = m[2].toInt() * 4; o.write("0\n".toByteArray()) }
                        "CLOSE" -> { ouvert = 0; o.write("0\n".toByteArray()) }
                        "WRITEBUF" -> {
                            if (writebufs >= coupeApres) return@runCatching
                            val n = m[2].toInt()
                            // As the real daemon: no buffer open, refused; else 0 (ready), the bytes, then the count pushed.
                            if (ouvert == 0) { o.write("-9\n".toByteArray()); o.flush(); continue }
                            if (n != ouvert) { o.write("-22\n".toByteArray()); o.flush(); continue }
                            o.write("0\n".toByteArray()); o.flush()
                            val d = lis(e, n)
                            octetsRecus += n
                            for (k in 0 until n / 2) { val v = ((d[2 * k].toInt() and 0xFF) or (d[2 * k + 1].toInt() shl 8)).toShort().toInt(); if (kotlin.math.abs(v) > crete) crete = kotlin.math.abs(v) }
                            writebufs++
                            if (writebufs > muetApres) { Thread.sleep(60_000); return@runCatching }
                            // The DAC's pace.
                            Thread.sleep((n / 4 / debit * 1000).toLong())
                            o.write("$n\n".toByteArray())
                        }
                        else -> o.write("-22\n".toByteArray())
                    }
                    o.flush()
                }
            }
        }

        fun close() = runCatching { serveur.close() }
        val loEteint get() = attributs["ad9361-phy OUTPUT altvoltage1 powerdown"] == "1"
    }

    private val serveurs = ArrayList<FauxIiod>()
    private fun faux(f: FauxIiod) = f.also { serveurs += it }
    @After fun fin() { serveurs.forEach { it.close() } }

    private fun attends(ms: Long, condition: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (condition()) return true; Thread.sleep(10) }
        return condition()
    }

    /** A producer of a constant tone (the modulator's place). */
    private val ton: (ShortArray) -> Unit = { b -> for (k in b.indices) b[k] = (if (k % 2 == 0) 1000 else -1000).toShort() }

    @Test
    fun rien_n_est_envoye_avant_un_demarrage_explicite() {
        val f = faux(FauxIiod())
        val p = PlutoEmission("127.0.0.1", f.port, gainMaxDb = -10.0)
        assertFalse(p.etat.emet)
        assertEquals(-30.0, p.gainApplique(-30.0), 0.0)
        p.arrete()
        Thread.sleep(100)
        assertEquals(0, f.connexions.get())
        assertTrue(f.commandes.isEmpty())
    }

    @Test
    fun sequence_exacte_au_demarrage_et_a_l_arret() {
        val f = faux(FauxIiod())
        val p = PlutoEmission("127.0.0.1", f.port, gainMaxDb = -10.0)
        val r = PlutoEmission.Reglage(frequenceHz = 2_405_250_000L, debit = 2_331_000L, gainDb = -30.0, dureeMs = 60_000L, paires = 8192)
        assertNull(p.demarre(r, ton))
        assertTrue(attends(3000) { f.writebufs >= 6 })
        assertTrue(p.etat.emet)
        assertFalse(f.loEteint)
        p.arrete()
        assertFalse(p.etat.emet)
        assertEquals("arrêt demandé", p.etat.raison)
        assertTrue(attends(2000) { f.commandes.any { it.startsWith("CLOSE") } })
        val tout = f.commandes.toList()
        val attendu = listOf(
            "PRINT",
            "WRITE ad9361-phy OUTPUT altvoltage1 powerdown = 1",
            "WRITE ad9361-phy OUTPUT voltage0 hardwaregain = -30.00",
            "WRITE ad9361-phy OUTPUT voltage0 sampling_frequency = 2331000",
            "WRITE ad9361-phy OUTPUT voltage0 rf_bandwidth = 2331000",
            "WRITE ad9361-phy OUTPUT altvoltage1 frequency = 2405250000",
            "WRITE cf-ad9361-dds-core-lpc OUTPUT altvoltage0 raw = 0",
            "WRITE cf-ad9361-dds-core-lpc OUTPUT altvoltage0 scale = 0",
            "WRITE cf-ad9361-dds-core-lpc OUTPUT altvoltage1 raw = 0",
            "WRITE cf-ad9361-dds-core-lpc OUTPUT altvoltage1 scale = 0",
            "WRITE cf-ad9361-dds-core-lpc OUTPUT altvoltage2 raw = 0",
            "WRITE cf-ad9361-dds-core-lpc OUTPUT altvoltage2 scale = 0",
            "WRITE cf-ad9361-dds-core-lpc OUTPUT altvoltage3 raw = 0",
            "WRITE cf-ad9361-dds-core-lpc OUTPUT altvoltage3 scale = 0",
            "TIMEOUT 3000",
            "OPEN cf-ad9361-dds-core-lpc 8192 00000003",
            "WRITEBUF cf-ad9361-dds-core-lpc 32768",
        )
        assertEquals(attendu, tout.take(attendu.size))
        // The LO on only after the first buffers, off at the stop, then the buffer closed.
        val loOn = tout.indexOf("WRITE ad9361-phy OUTPUT altvoltage1 powerdown = 0")
        val loOff = tout.lastIndexOf("WRITE ad9361-phy OUTPUT altvoltage1 powerdown = 1")
        assertEquals(1, tout.count { it == "WRITE ad9361-phy OUTPUT altvoltage1 powerdown = 0" })
        assertTrue(tout.subList(0, loOn).count { it.startsWith("WRITEBUF") } >= PlutoEmission.AMORCE)
        assertTrue(loOff > loOn)
        assertTrue(tout.indexOf("CLOSE cf-ad9361-dds-core-lpc") > loOff)
        assertTrue(f.loEteint)
        // Every write one of the TX class's own; the samples as given.
        for (l in tout.filter { it.startsWith("WRITE ") }) {
            val m = l.split(' ')
            assertTrue(l, PlutoEmission.ECRITURES.any { (d, canal, a) -> d == m[1] && "$canal" == "${m[2]} ${m[3]}" && a == m[4] })
        }
        assertEquals(1000, f.crete)
    }

    @Test
    fun le_gain_ne_depasse_jamais_le_plafond() {
        val f = faux(FauxIiod())
        val p = PlutoEmission("127.0.0.1", f.port, gainMaxDb = -20.0)
        assertEquals(-20.0, p.gainApplique(0.0), 0.0)
        assertEquals(-40.0, p.gainApplique(-10.0, plafondDb = -40.0), 0.0)
        assertEquals(-89.75, p.gainApplique(-200.0), 0.0)
        assertEquals(-30.25, p.gainApplique(-30.1), 0.0)
        assertNull(p.demarre(PlutoEmission.Reglage(2_405_250_000L, 2_331_000L, gainDb = 5.0, dureeMs = 60_000L, paires = 4096), ton))
        assertEquals("-20.00", f.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        assertEquals(-20.0, p.regleGain(3.0)!!, 0.0)
        assertEquals(-25.0, p.regleGain(-25.0)!!, 0.0)
        assertEquals("-25.00", f.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        p.arrete()
        assertNull(p.regleGain(-30.0))
        assertTrue(f.loEteint)
    }

    @Test
    fun arret_a_la_fin_de_la_duree() {
        val f = faux(FauxIiod())
        val p = PlutoEmission("127.0.0.1", f.port)
        assertNull(p.demarre(PlutoEmission.Reglage(2_405_250_000L, 2_331_000L, -30.0, dureeMs = 600L, paires = 4096), ton))
        assertTrue(attends(3000) { !p.etat.emet })
        assertEquals("durée maximale atteinte", p.etat.raison)
        assertTrue(f.loEteint)
    }

    @Test
    fun la_garde_arrete_l_emission() {
        val f = faux(FauxIiod())
        val p = PlutoEmission("127.0.0.1", f.port)
        val page = java.util.concurrent.atomic.AtomicBoolean(true)
        assertNull(p.demarre(PlutoEmission.Reglage(2_405_250_000L, 2_331_000L, -30.0, 60_000L, paires = 4096,
            garde = { if (page.get()) null else "page fermée" }), ton))
        assertTrue(attends(2000) { f.writebufs > 3 })
        page.set(false)
        assertTrue(attends(2000) { !p.etat.emet })
        assertEquals("page fermée", p.etat.raison)
        assertTrue(f.loEteint)
    }

    @Test
    fun chien_de_garde_quand_le_pluto_ne_prend_plus_rien() {
        val f = faux(FauxIiod(muetApres = 5))
        val p = PlutoEmission("127.0.0.1", f.port, chienMs = 800L, attenteMs = 10_000)
        assertNull(p.demarre(PlutoEmission.Reglage(2_405_250_000L, 2_331_000L, -30.0, 60_000L, paires = 4096), ton))
        assertTrue(attends(4000) { !p.etat.emet })
        assertTrue(p.etat.erreur, p.etat.erreur.contains("ne prend plus"))
        // The control connection still there: the LO powered down.
        assertTrue(f.loEteint)
    }

    @Test
    fun liaison_coupee_arret_et_oscillateur_eteint() {
        val f = faux(FauxIiod(coupeApres = 4))
        val p = PlutoEmission("127.0.0.1", f.port)
        assertNull(p.demarre(PlutoEmission.Reglage(2_405_250_000L, 2_331_000L, -30.0, 60_000L, paires = 4096), ton))
        assertTrue(attends(4000) { !p.etat.emet })
        assertTrue(p.etat.erreur, p.etat.erreur.contains("coupé") || p.etat.erreur.contains("liaison"))
        assertTrue(f.loEteint)
    }

    @Test
    fun manques_comptes_puis_arret_quand_ils_persistent() {
        val f = faux(FauxIiod(debit = 4_000_000.0))
        val p = PlutoEmission("127.0.0.1", f.port, manquesMax = 5, fenetreManquesMs = 5_000L)
        val n = AtomicInteger()
        // Fast at first, then far too slow for the Pluto.
        val lent: (ShortArray) -> Unit = { b -> if (n.incrementAndGet() > 6) Thread.sleep(40); ton(b) }
        assertNull(p.demarre(PlutoEmission.Reglage(2_405_250_000L, 2_331_000L, -30.0, 60_000L, paires = 4096), lent))
        assertTrue(attends(5000) { !p.etat.emet })
        assertTrue(p.etat.manques > 5)
        assertTrue(p.etat.erreur, p.etat.erreur.contains("retard"))
        assertTrue(f.loEteint)
    }

    @Test
    fun pas_un_pluto_rien_n_est_ecrit() {
        val f = faux(FauxIiod())
        val autre = f.commandes
        val p = PlutoEmission("127.0.0.1", f.port)
        // A context without the TX device.
        val serveur = FauxSansTx()
        try {
            val q = PlutoEmission("127.0.0.1", serveur.port)
            val e = q.demarre(PlutoEmission.Reglage(2_405_250_000L, 2_331_000L, -30.0, 60_000L, paires = 4096), ton)
            assertNotNull(e)
            assertTrue(serveur.commandes.none { it.startsWith("WRITE ") && !it.contains("powerdown") })
        } finally { serveur.close() }
        assertTrue(autre.isEmpty())
        assertFalse(p.etat.emet)
    }

    /** Answers PRINT with a context that has no transmitter. */
    private class FauxSansTx {
        val s = ServerSocket(0)
        val commandes = CopyOnWriteArrayList<String>()
        val port get() = s.localPort
        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val c = s.accept()
                        thread(isDaemon = true) {
                            runCatching {
                                c.use {
                                    val e = c.getInputStream().bufferedReader(Charsets.US_ASCII)
                                    val o = c.getOutputStream()
                                    while (true) {
                                        val l = e.readLine() ?: break
                                        commandes += l
                                        if (l == "PRINT") { val x = "<context><device id=\"a\" name=\"ad9361-phy\" ></device></context>".toByteArray(); o.write("${x.size}\n".toByteArray()); o.write(x); o.write('\n'.code) }
                                        else o.write("-22\n".toByteArray())
                                        o.flush()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        fun close() = runCatching { s.close() }
    }

    @Test
    fun option_couper_l_oscillateur_une_seule_ecriture() {
        val f = faux(FauxIiod())
        assertTrue(PlutoEmission.coupeOscillateur("127.0.0.1", f.port))
        assertEquals(listOf("WRITE ad9361-phy OUTPUT altvoltage1 powerdown = 1"), f.commandes.toList())
        assertTrue(f.loEteint)
        // Nobody there: false, no exception.
        val libre = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        assertFalse(PlutoEmission.coupeOscillateur("127.0.0.1", libre, 500))
    }

    @Test
    fun la_reception_ne_peut_toujours_rien_ecrire_cote_emission() {
        // The receiver's writes and the transmitter's: nothing in common, nothing TX on the receiver's side.
        val rx = PlutoSdr.ECRITURES.map { (c, a) -> Triple(PlutoSdr.PHY, c, a) }.toSet()
        assertTrue(rx.intersect(PlutoEmission.ECRITURES).isEmpty())
        assertTrue(PlutoSdr.ECRITURES.none { (c, _) -> c == PlutoEmission.LO_TX || c == PlutoEmission.TX })
    }

    @Test
    fun masque_tx_du_contexte() {
        assertEquals("00000003", PlutoEmission.masqueTx(XML))
        assertNull(PlutoEmission.masqueTx(XML.replace("le:S16/16&gt;&gt;0", "le:S16/32&gt;&gt;0")))
    }

    @Test
    fun masque_tx_du_contexte_reel() {
        // A Rev.C, firmware v0.37: I/Q at scan indices 0 and 1, the four tone channels without any.
        assertTrue(REEL.contains("name=\"cf-ad9361-dds-core-lpc\"") && REEL.contains("altvoltage3"))
        assertEquals("00000003", PlutoEmission.masqueTx(REEL))
        val d = PlutoSdr.Contexte.lis(REEL)
        assertTrue(PlutoEmission.PHY in d.appareils && PlutoEmission.TX_DEV in d.appareils)
        assertEquals("00000003" to 12, d.masqueRx)
        // Indices swapped: I would come second, refused.
        assertNull(PlutoEmission.masqueTx(REEL.replace("<scan-element index=\"0\" format=\"le:S16", "<scan-element index=\"7\" format=\"le:S16")))
    }

    @Test
    fun le_vrai_contexte_est_servi_et_l_emission_part() {
        val f = faux(FauxIiod(xml = REEL))
        val p = PlutoEmission("127.0.0.1", f.port, gainMaxDb = -10.0)
        assertNull(p.demarre(PlutoEmission.Reglage(frequenceHz = 1_245_000_000L, debit = 2_116_800L, gainDb = -40.0, dureeMs = 60_000L, paires = 106_496), ton))
        assertTrue(attends(3000) { f.writebufs >= 3 })
        assertTrue(p.etat.emet)
        p.arrete()
        assertEquals("", p.etat.erreur)
        assertTrue(f.commandes.contains("OPEN cf-ad9361-dds-core-lpc 106496 00000003"))
        assertTrue(f.commandes.contains("WRITEBUF cf-ad9361-dds-core-lpc 425984"))
        assertTrue(f.loEteint)
    }

    companion object {
        /** The context a real ADALM-PLUTO (Rev.C, firmware v0.37) prints. */
        val REEL: String = PlutoEmissionTest::class.java.getResourceAsStream("/sdr/pluto-contexte.xml")!!.readBytes().toString(Charsets.UTF_8)

        val XML = """<?xml version="1.0" encoding="utf-8"?><context name="local" version-major="0" version-minor="24" version-git="v0.24" description="Linux">""" +
            """<context-attribute name="hw_model" value="Analog Devices PlutoSDR Rev.C (Z7010-AD9364)" />""" +
            """<device id="iio:device0" name="ad9361-phy" ><channel id="voltage0" type="output" ><attribute name="hardwaregain" filename="out_voltage0_hardwaregain" /></channel></device>""" +
            """<device id="iio:device2" name="cf-ad9361-dds-core-lpc" ><channel id="voltage0" type="output" ><scan-element index="0" format="le:S16/16&gt;&gt;0" /></channel>""" +
            """<channel id="voltage1" type="output" ><scan-element index="1" format="le:S16/16&gt;&gt;0" /></channel>""" +
            """<channel id="altvoltage0" name="TX1_I_F1" type="output" ><attribute name="raw" filename="out_altvoltage0_TX1_I_F1_raw" /></channel></device>""" +
            """<device id="iio:device3" name="cf-ad9361-lpc" ><channel id="voltage0" type="input" ><scan-element index="0" format="le:S12/16&gt;&gt;0" /></channel>""" +
            """<channel id="voltage1" type="input" ><scan-element index="1" format="le:S12/16&gt;&gt;0" /></channel></device></context>"""
    }
}
