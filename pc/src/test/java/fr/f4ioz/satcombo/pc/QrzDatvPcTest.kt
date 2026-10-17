/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.QrzDatv
import fr.f4ioz.satcombo.domain.QrzReponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** DATV's QRZ records on SatMe-PC: lookups at the cache's pace, on disk, the photo proxy (no network). */
class QrzDatvPcTest {

    private fun xml(call: String, image: String = "https://cdn-xml.qrz.com/c/x.jpg") =
        "<QRZDatabase><Callsign><call>$call</call><fname>Ann</fname><name>Lee</name><addr2>York</addr2><country>England</country>" +
            "<grid>IO93lw</grid><image>$image</image></Callsign></QRZDatabase>"

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    @Test
    fun une_requete_par_indicatif_et_gardee_sur_le_disque() {
        val d = Files.createTempDirectory("qrzdatv").toFile()
        var t = 1_000_000L
        val demandes = ArrayList<String>()
        val z = QrzDatvPc(d, { true }, { c -> demandes += c; QrzReponse.lis(xml(c)) }, { null }, { t }, { it.run() })
        repeat(20) { z.suit(listOf("G4ABC/P")); t += 500 }
        assertEquals(listOf("G4ABC"), demandes)
        assertEquals("Ann Lee", z.fiche("G4ABC")!!.nom)
        val j = z.json(listOf("G4ABC/P"), null).getJSONObject("fiches").getJSONObject("G4ABC/P")
        assertEquals("York", j.getString("qth"))
        // Restarted: from the disk, QRZ not asked again.
        val apres = QrzDatvPc(d, { true }, { c -> demandes += c; null }, { null }, { t }, { it.run() })
        apres.suit(listOf("G4ABC"))
        assertEquals(1, demandes.size)
        assertEquals("IO93LW", apres.fiche("G4ABC")!!.carre)
        d.deleteRecursively()
    }

    @Test
    fun sans_compte_rien_n_est_demande() {
        val d = Files.createTempDirectory("qrzdatv").toFile()
        var n = 0
        val z = QrzDatvPc(d, { false }, { n++; null }, { null }, { 0L }, { it.run() })
        z.suit(listOf("G4ABC"))
        assertEquals(0, n)
        assertEquals(false, z.json(listOf("G4ABC"), null).getBoolean("compte"))
        d.deleteRecursively()
    }

    @Test
    fun la_photo_seulement_de_qrz_et_petite() {
        val d = Files.createTempDirectory("qrzdatv").toFile()
        var t = 1_000_000L
        val urls = ArrayList<String>()
        var octets = jpeg
        val z = QrzDatvPc(d, { true }, { c -> QrzReponse.lis(if (c == "M0XYZ") xml(c, "https://exemple.org/x.jpg") else xml(c)) },
            { u -> urls += u; octets }, { t }, { it.run() })
        z.suit(listOf("G4ABC")); t += 6000; z.suit(listOf("M0XYZ"))
        // Another host: never fetched.
        assertNull(z.photo("M0XYZ"))
        // Not looked up: no address to fetch.
        assertNull(z.photo("DL1AAA"))
        assertTrue(urls.isEmpty())
        // Too big: refused, and not retried within 10 minutes.
        octets = ByteArray(QrzDatv.PHOTO_MAX + 1) { 0xFF.toByte() }
        assertNull(z.photo("G4ABC")); assertNull(z.photo("G4ABC"))
        assertEquals(1, urls.size)
        t += QrzDatv.PAUSE_INDICATIF_MS
        octets = jpeg
        val p = z.photo("G4ABC")
        assertNotNull(p); assertEquals("image/jpeg", p!!.second)
        // Kept: not fetched again.
        z.photo("G4ABC"); assertEquals(2, urls.size)
        // Not an image: refused.
        val h = QrzDatvPc(d, { true }, { c -> QrzReponse.lis(xml(c)) }, { "<html>".toByteArray() }, { t }, { it.run() })
        h.suit(listOf("G4ABC")); assertNull(h.photo("G4ABC"))
        d.deleteRecursively()
    }
}
