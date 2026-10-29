/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** DATV through LongMynd: its command line, its status lines, the pictures cut out of ffmpeg's stream. */
class DatvPcTest {

    @Test
    fun la_ligne_de_commande_de_longmynd() {
        val r = ReglageDatv(cielKhz = 10_491_500, srKs = 1500, olKhz = 9_750_000)
        assertEquals(741_500L, r.fiKhz)
        assertEquals(listOf("/x/longmynd", "-i", "127.0.0.1", "4003", "-I", "127.0.0.1", "4002", "-r", "5000", "741500", "1500"),
            DatvPc.commande("/x/longmynd", r, 4003, 4002))
        // The bottom input and the LNB supply only when asked; anything else for the supply is ignored.
        assertEquals(listOf("-w", "-p", "v"), DatvPc.commande("l", r.copy(entreeB = true, lnb = "v"), 1, 2).drop(9).take(3))
        assertTrue("-p" !in DatvPc.commande("l", r.copy(lnb = "24"), 1, 2))
        // Scanning: the rates in turn, the chosen one first, the timeout longer for low rates.
        val b = DatvPc.commande("l", r.copy(srKs = 333, balayage = listOf(500, 250, 333, 1000, 125)), 1, 2)
        assertEquals(listOf("-r", "10000"), b.subList(7, 9))
        assertEquals("333,500,250,1000", b.last())
        assertEquals("20000", DatvPc.commande("l", r.copy(balayage = listOf(125)), 1, 2)[8])
        assertEquals(listOf("5000", "1500,1000"), DatvPc.commande("l", r.copy(balayage = listOf(1000)), 1, 2).let { listOf(it[8], it.last()) })
        // The rate locked on, for the quality and the stations heard.
        val s = r.copy(srKs = 333, balayage = listOf(500, 250))
        assertEquals(500, s.debitAccroche(499)); assertEquals(250, s.debitAccroche(251)); assertEquals(333, s.debitAccroche(0))
        assertEquals(1500, r.debitAccroche(499))
    }

    @Test
    fun les_lignes_d_etat() {
        assertEquals(listOf(1 to "4", 12 to "85", 14 to "SATME TEST"), Datv.lignes("\$1,4\r\$12,85\r\$14,SATME TEST\r"))
        assertEquals(listOf(9 to "1500000"), Datv.lignes("\$9,1500000"))
        assertEquals(-56, Datv.signe("200")); assertEquals(-56, Datv.signe("4294967240")); assertEquals(50, Datv.signe("50"))
        assertEquals("QPSK 1/2", Datv.modcod(4, 4)); assertEquals("8PSK 2/3", Datv.modcod(13, 4))
        assertEquals("QPSK 3/4", Datv.modcod(2, 3))
        assertEquals("H.265", Datv.typeFlux(0x24))
    }

    @Test
    fun les_images_jpeg_redecoupees() {
        val img1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val img2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 0xFF.toByte(), 0xD9.toByte())
        val flux = img1 + img2
        val vues = ArrayList<ByteArray>()
        val d = DatvPc.Decoupe()
        // In pieces that cut through the markers.
        for (k in flux.indices step 3) { val p = flux.copyOfRange(k, minOf(flux.size, k + 3)); d.ajoute(p, p.size) { vues += it } }
        assertEquals(2, vues.size)
        assertTrue(vues[0].contentEquals(img1) && vues[1].contentEquals(img2))
    }

    @Test
    fun les_causes_d_arret_de_longmynd() {
        val sansCarte = listOf("Flow: FTDI USB init", "ERROR: Unable to open device with VID and PID", "       Is the USB cable plugged in?",
            "ERROR: FTDI init", "Flow: Main loop aborted, waiting for threads.", "Flow: All threads accounted for. Exiting cleanly.")
        assertEquals("MiniTiouner introuvable : est-il branché ?", DatvPc.pourquoi(sansCarte))
        assertTrue(DatvPc.pourquoi(listOf("ERROR: LIBUSB_ERROR_ACCESS")).startsWith("pas d'accès"))
        // Without the udev rule: libusb's refusal, then LongMynd asking whether the cable is plugged in.
        assertTrue(DatvPc.pourquoi(listOf("libusb: error [get_usbfs_fd] libusb couldn't open USB device /dev/bus/usb/001/050, errno=13",
            "libusb: error [get_usbfs_fd] libusb requires write access to USB device nodes", "ERROR: Unable to open device with VID and PID",
            "       Is the USB cable plugged in?")).startsWith("pas d'accès"))
        assertEquals("LongMynd : tuner lock", DatvPc.pourquoi(listOf("Flow: x", "ERROR: tuner lock", "Exiting cleanly.")))
    }

    @Test
    fun les_erreurs_des_decodeurs_comptees_au_fil_du_journal() {
        val f = File.createTempFile("satme-datv", ".log")
        try {
            val j = DatvPc.JournalDecodeur(f)
            f.writeText("[h264 @ 0x1] error while decoding MB 3 4, bytestream -7\n[h264 @ 0x1] concealing 20 DC, 20 AC, 20 MV errors in P frame\n")
            j.lis()
            assertEquals(2L, j.video)
            // A line half written waits for its end; a repeat counts the line before.
            f.appendText("    Last message repeated 3 times\n[aac @ 0x2] Reserved bit set.\n[aac @ 0x2] Number of ba")
            j.lis()
            assertEquals(5L, j.video); assertEquals(1L, j.audio)
            f.appendText("nds (31) exceeds limit (14).\n[mpegts @ 0x3] Packet corrupt\n")
            j.lis()
            assertEquals(2L, j.audio); assertEquals(1L, j.flux)
            // The decoder started again, its log emptied: read from its start.
            f.writeText("[hevc @ 0x4] Could not find ref with POC 7\n")
            j.lis()
            assertEquals(6L, j.video)
            // An earlier reception's lines are skipped.
            j.saute(); j.lis()
            assertEquals(6L, j.video)
            // A decoder joining the stream: its complaints before the first picture are not counted.
            j.enAttente = true
            f.appendText("[hevc @ 0x4] PPS id out of range: 0\n    Last message repeated 60 times\n")
            j.lis()
            assertEquals(6L, j.video)
            j.enAttente = false
            f.appendText("[hevc @ 0x4] Could not find ref with POC 9\n")
            j.lis()
            assertEquals(7L, j.video)
        } finally { f.delete() }
    }

    @Test
    fun hors_de_portee_rien_ne_demarre() {
        val d = DatvPc(StockageMemoire(), File(System.getProperty("java.io.tmpdir")))
        val e = d.demarre(ReglageDatv(cielKhz = 10_491_500, olKhz = 12_000_000))
        assertTrue(e!!.contains("hors de portée"))
        assertTrue(!d.etat.actif)
        assertTrue(d.demarre(ReglageDatv(srKs = 10))!!.contains("symboles"))
    }

    @Test
    fun le_flux_en_udp_vers_un_lecteur_local() {
        val st = StockageMemoire()
        val d = DatvPc(st, File(System.getProperty("java.io.tmpdir")))
        assertEquals(false, d.udpOn)
        assertEquals("127.0.0.1:9080", d.udpDestination)
        // Never the internet; the setting unchanged.
        assertTrue(d.regleUdp(true, "8.8.8.8:9080") != null)
        assertEquals(false, d.udpOn)
        java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { lecteur ->
            lecteur.soTimeout = 3000
            assertEquals(null, d.regleUdp(true, "127.0.0.1:" + lecteur.localPort))
            val paquets = ByteArray(188 * 8) { if (it % 188 == 0) 0x47 else 1 }
            d.versUdp(paquets.copyOf(500)); d.versUdp(paquets.copyOfRange(500, paquets.size))
            val b = ByteArray(2000)
            val p = java.net.DatagramPacket(b, b.size)
            lecteur.receive(p)
            assertEquals(1316, p.length)
            assertEquals(1L, d.udpDatagrammes)
            // Off: nothing more sent.
            d.regleUdp(false, null)
            d.versUdp(paquets); d.versUdp(paquets)
            assertEquals(1L, d.udpDatagrammes)
        }
    }

    @Test
    fun le_node_redemarre_la_reception_reprend() {
        // A node that receives, goes silent (SatMe-Node restarted), then answers again without receiving.
        val demarrages = java.util.concurrent.atomic.AtomicInteger()
        val panne = java.util.concurrent.atomic.AtomicBoolean()
        val actif = java.util.concurrent.atomic.AtomicBoolean()
        val serveur = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        fun repond(x: com.sun.net.httpserver.HttpExchange, code: Int, corps: String) {
            val b = corps.toByteArray(); x.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong()); x.responseBody.use { it.write(b) }
        }
        serveur.createContext("/") { x ->
            val p = x.requestURI.path
            when {
                panne.get() -> repond(x, 503, "")
                p == "/noeud/api" -> repond(x, 200, "{\"noeud\":true,\"nom\":\"essai\"}")
                p == "/noeud/entrer" -> repond(x, 200, "{\"ok\":true,\"cle\":\"k\"}")
                p == "/noeud/datv/demarre" -> { demarrages.incrementAndGet(); actif.set(true); repond(x, 200, "{\"ok\":true}") }
                p == "/noeud/datv" -> repond(x, 200, "{\"actif\":${actif.get()}}")
                else -> repond(x, 404, "")
            }
        }
        serveur.start()
        val d = DatvPc(StockageMemoire(), File(System.getProperty("java.io.tmpdir")))
        try {
            assertEquals(null, d.rejoins("127.0.0.1:" + serveur.address.port, "1234"))
            assertEquals(null, d.demarre(ReglageDatv(cielKhz = 10_491_500, srKs = 1500, olKhz = 9_750_000)))
            assertEquals(1, demarrages.get())
            panne.set(true); actif.set(false)
            Thread.sleep(1500)
            panne.set(false)
            val fin = System.currentTimeMillis() + 8000
            while (demarrages.get() < 2 && System.currentTimeMillis() < fin) Thread.sleep(100)
            assertEquals(2, demarrages.get())
            assertTrue(d.etat.actif)
            assertEquals("", d.etat.erreur)
        } finally { d.arrete(); serveur.stop(0) }
    }
}
