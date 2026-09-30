/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.cat.SerialLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** KISS framing, and a fake TH-D72 on a fake serial line. */
class KissTest {

    private val trame = AprsEmission.trame("F4IOZ-7", listOf("ARISS"),
        AprsEmission.message("ANSRVR", "CQ HOTG 73", "5"))

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun echappement_des_octets_speciaux() {
        assertArrayEquals(b(0xC0, 0x00, 0x01, 0xDB, 0xDC, 0x02, 0xDB, 0xDD, 0xC0),
            Kiss.trame(b(0x01, 0xC0, 0x02, 0xDB)))
        val recues = ArrayList<ByteArray>()
        KissDecodeur { recues += it }.octets(Kiss.trame(b(0x01, 0xC0, 0x02, 0xDB)))
        assertArrayEquals(b(0x01, 0xC0, 0x02, 0xDB), recues.single())
    }

    @Test
    fun ax25_sans_fcs_aller_retour() {
        assertEquals(trame, Ax25.decodeSansFcs(Ax25.encodeSansFcs(trame)))
    }

    @Test
    fun decodage_robuste() {
        val recues = ArrayList<ByteArray>()
        val d = KissDecodeur { recues += it }
        val un = Kiss.trame(Ax25.encodeSansFcs(trame))
        // The TNC's text prompt before KISS, then a frame cut in three reads,
        // then a settings command (not data), then two frames sharing a FEND.
        d.octets("cmd:KISS ON\r\ncmd:".toByteArray())
        d.octets(un.copyOfRange(0, 5)); d.octets(un.copyOfRange(5, 20)); d.octets(un.copyOfRange(20, un.size))
        d.octets(b(0xC0, 0x01, 0x28, 0xC0))  // TXDELAY: ignored
        d.octets(un.copyOf(un.size - 1) + un)
        assertEquals(3, recues.size)
        recues.forEach { assertEquals(trame, Ax25.decodeSansFcs(it)) }
    }

    /**
     * A TH-D72 on the other end of the USB cable, answering as the real one did
     * (replies copied from a session with the author's radio): PC commands in
     * normal mode, the TNC's banner and "cmd:" prompt in packet mode, silence
     * and frames in KISS.
     */
    private class FauxTh72 : SerialLink {
        val ecrit = java.io.ByteArrayOutputStream()
        val aEnvoyer = LinkedBlockingQueue<ByteArray>()
        var etat = "NORMAL"
        private val ligne = StringBuilder()
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
            synchronized(ecrit) { ecrit.write(bytes) }
            if (etat == "KISS") {
                if (bytes.contentEquals(Kiss.SORTIE)) { etat = "PAQUET"; aEnvoyer.put("\r\nKenwood Radio Modem\r\ncmd:".toByteArray()) }
                return true
            }
            for (b in bytes) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c != '\r') { ligne.append(c); continue }
                val cmd = ligne.toString(); ligne.setLength(0)
                repondre(cmd)
            }
            return true
        }
        private fun repondre(cmd: String) {
            fun dit(s: String) = aEnvoyer.put(s.toByteArray(Charsets.ISO_8859_1))
            if (etat == "NORMAL") when (cmd) {
                "BC" -> dit("BC 0\r")
                "FO 0" -> dit("FO 0,0144800000,0,0,0,1,0,0,0,00,08,000,0,00600000,0\r")
                "TN 0,0" -> dit("TN 0,0\r")
                "TN 2,0" -> { etat = "PAQUET"; dit("TN 2,0\r\r\n\r\nbbRAM loaded with defaults\u0007\r\n\r\nKenwood Radio Modem\r\n" +
                    "AX.25 Level 2 Version 2.0\r\ncmd:MY NOCALL\r\nMYCALL   was NOCALL\r\ncmd:") }
                else -> dit("?\r")
            } else when (cmd) {  // TNC in packet mode
                "TC 1" -> { etat = "NORMAL"; dit("TTS 1\r") }
                "KISS ON" -> dit("\r\ncmd:KISS ON\r\nKISS     was OFF\r\n")
                "RESTART" -> { dit("cmd:RESTART\r\n"); etat = "KISS" }
                else -> dit("\r\ncmd:")
            }
        }
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val o = aEnvoyer.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            o.copyInto(buf); return o.size
        }
        override fun close() {}
        fun octets(): ByteArray = synchronized(ecrit) { ecrit.toByteArray() }
    }

    @Test
    fun frequence_lue_dans_la_reponse_fo() {
        assertEquals(144_800_000L, Kiss.frequenceFo("FO 0,0144800000,0,0,0,1,0,0,0,00,08,000,0,00600000,0\r"))
        assertEquals(435_850_000L, Kiss.frequenceFo("FO 1,0435850000,0,0,0,0,0,0,0,09,04,000,0,01600000,0"))
        assertEquals(null, Kiss.frequenceFo("?\r"))
    }

    @Test
    fun avec_un_th_d72_en_mode_normal() = runBlocking {
        val poste = FauxTh72()
        TncKiss.attache(null, poste, "TH-D72", 9600)
        assertTrue(TncKiss.passeEnKiss())
        assertEquals("KISS", poste.etat)
        assertEquals(144_800_000L, TncKiss.etat.value.frequenceHz)
        assertEquals(0, TncKiss.etat.value.bande)
        assertTrue(String(poste.octets(), Charsets.ISO_8859_1).contains("TN 2,0\r"))
        val avant = poste.octets().size
        assertTrue(TncKiss.envoie(trame))
        assertArrayEquals(Kiss.trame(Ax25.encodeSansFcs(trame)), poste.octets().copyOfRange(avant, poste.octets().size))
        // The radio heard a frame via the ISS and passes it on.
        val entendue = AprsEmission.trame("F5RRO", listOf("RS0ISS*"), ">QRV")
        poste.aEnvoyer.put(Kiss.trame(Ax25.encodeSansFcs(entendue)))
        val fin = System.currentTimeMillis() + 3000
        while (TncKiss.etat.value.recues == 0 && System.currentTimeMillis() < fin) Thread.sleep(20)
        assertEquals(1, TncKiss.etat.value.recues)
        assertEquals(1, TncKiss.etat.value.envoyees)
        val n = poste.octets().size
        TncKiss.deconnecte()
        // Back as it was: KISS exit, control to the radio, TNC off.
        assertArrayEquals(Kiss.SORTIE + "\rTC 1\rTN 0,0\r".toByteArray(), poste.octets().copyOfRange(n, poste.octets().size))
        assertEquals("NORMAL", poste.etat)
        assertEquals(false, TncKiss.etat.value.connecte)
    }

    @Test
    fun deja_en_kiss_rien_de_plus() = runBlocking {
        val poste = FauxTh72().apply { etat = "KISS" }
        TncKiss.attache(null, poste, "TH-D72", 9600)
        assertTrue(TncKiss.passeEnKiss())
        assertEquals("\r", String(poste.octets(), Charsets.ISO_8859_1))  // one CR, silence: nothing else sent
        TncKiss.deconnecte()
    }

    /**
     * Bytes a real TH-D72 sent over USB in KISS, antenna on, on 144.800 MHz
     * (30/09/2026): two frames from the APRS network, one of them Mic-E.
     */
    @Test
    fun capture_d_un_vrai_th_d72() {
        val hex = 
        "c00082a09a926068608c62a0a4b2407c8c6c9684a640e68c6a96a8a440e6ae92" +
        "888a66406303f0403330313835397a343932312e38374e2f30303135382e3530" +
        "45232041505253204f4953452020553d31322e3556c0c000a872a0b0ae6e608c" +
        "7096828a40668c62a0a4b240fcae92888a6240e08c6a96a8a440e703f027772f" +
        "6e6c201c232f5d3d0dc0"
        val octets = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val lues = ArrayList<Paquet>()
        KissDecodeur { o -> Ax25.decodeSansFcs(o)?.let { lues += Aprs.lit(it, 0) } }.octets(octets)
        assertEquals(2, lues.size)
        assertEquals("F1PRY-14>APMI04,F6KBS-3*,F5KTR-3*,WIDE3-1:@301859z4921.87N/00158.50E# APRS OISE  U=12.5V",
            lues[0].trame.tnc2())
        assertEquals(49.3645, lues[0].lat!!, 1e-3); assertEquals(1.975, lues[0].lon!!, 1e-3)
        assertEquals("F8KAE-3", lues[1].source)
        assertEquals(TypeAprs.POSITION, lues[1].type)
        assertEquals(49.1462, lues[1].lat!!, 1e-3); assertEquals(1.3303, lues[1].lon!!, 1e-3)
    }
}
