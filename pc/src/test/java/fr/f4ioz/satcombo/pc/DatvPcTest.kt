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
}
