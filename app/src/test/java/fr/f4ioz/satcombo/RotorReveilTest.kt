/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.CatJournal
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.rotor.Gs232Rotor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Cannot connect; SatMe is not in the open-with list." Two causes:
 *
 * - The USB filter lacked the Arduino vendor id: the app is silently absent
 *   from the list, and so is the access grant that comes with it.
 * - On Arduino boards DTR resets the board, so the first query lands during
 *   boot and the screen wrongly says "open, but the controller does not
 *   answer". Hence retries.
 */
class RotorReveilTest {

    @After
    fun apres() { CatJournal.enabled = false; CatJournal.clear() }

    /** A rebooting board: swallows the first queries, then answers normally. */
    private class ArduinoQuiRedemarre(private val avalees: Int) : SerialLink {
        var recues = 0; private set
        private val sortie = ArrayDeque<Byte>()

        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
            val s = String(bytes, Charsets.US_ASCII).trim()
            if (!s.uppercase().startsWith("C")) return true
            recues++
            if (recues > avalees) {
                "AZ=123 EL=045\r".toByteArray(Charsets.US_ASCII).forEach { sortie.addLast(it) }
            }
            return true
        }

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            var n = 0
            while (n < buf.size && sortie.isNotEmpty()) buf[n++] = sortie.removeFirst()
            return n
        }

        override fun close() {}
    }

    private fun rotor(l: SerialLink): Gs232Rotor {
        val r = Gs232Rotor()
        r.pacingMs = 0L
        r.settleMs = 0L
        r.attach(l)
        return r
    }

    // ------------------------------------------------------------------
    // Retries
    // ------------------------------------------------------------------

    @Test
    fun une_seule_question_ne_suffit_pas_a_declarer_un_controleur_muet() = runBlocking {
        val carte = ArduinoQuiRedemarre(avalees = 2)
        val r = rotor(carte)

        // The old behaviour: one query, silence, verdict.
        assertNull("la carte n'a pas encore fini de redémarrer", r.readPosition())

        // Now: ask again.
        val pos = r.probePosition(tries = 3, gapMs = 0L)
        assertNotNull("le contrôleur répond dès qu'il est réveillé", pos)
        assertEquals(123.0, pos!!.azDeg, 1e-9)
        assertEquals(45.0, pos.elDeg, 1e-9)
    }

    @Test
    fun un_controleur_vraiment_muet_reste_muet_et_le_dit() = runBlocking {
        val muet = object : SerialLink {
            var ecrit = 0
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { ecrit++; return true }
            override fun read(buf: ByteArray, timeoutMs: Int): Int = 0
            override fun close() {}
        }
        val r = rotor(muet)
        assertNull(r.probePosition(tries = 3, gapMs = 0L))
        // Three queries, no more: retries must not keep the operator waiting a
        // minute in front of an unplugged cable.
        assertEquals(3, muet.ecrit)
        assertEquals(3, r.lastTries)
        assertTrue("aucune trame reçue", r.lastReply.isBlank())
    }

    @Test
    fun la_derniere_trame_illisible_est_conservee_pour_l_ecran() = runBlocking {
        // A device that talks a different dialect: not silence, and fixed
        // differently.
        val bavard = object : SerialLink {
            private val sortie = ArrayDeque<Byte>()
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
                "?;\r".toByteArray(Charsets.US_ASCII).forEach { sortie.addLast(it) }
                return true
            }
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                var n = 0
                while (n < buf.size && sortie.isNotEmpty()) buf[n++] = sortie.removeFirst()
                return n
            }
            override fun close() {}
        }
        val r = rotor(bavard)
        assertNull(r.probePosition(tries = 2, gapMs = 0L))
        assertEquals("?;", r.lastReply)
    }

    @Test
    fun un_controleur_qui_repond_du_premier_coup_n_est_pas_relance() = runBlocking {
        val carte = ArduinoQuiRedemarre(avalees = 0)
        val r = rotor(carte)
        assertNotNull(r.probePosition(tries = 3, gapMs = 0L))
        assertEquals("une seule question a suffi", 1, carte.recues)
        assertEquals(1, r.lastTries)
    }

    // ------------------------------------------------------------------
    // USB filter
    // ------------------------------------------------------------------

    private fun filtre(): File? = listOf(
        "src/main/res/xml/usb_device_filter.xml",
        "app/src/main/res/xml/usb_device_filter.xml",
        "../app/src/main/res/xml/usb_device_filter.xml")
        .map { File(it) }.firstOrNull { it.isFile }

    /**
     * Without this declaration SatMe is missing from "open with" on plug-in —
     * and nothing anywhere says so.
     */
    @Test
    fun le_filtre_usb_declare_les_cartes_a_microcontroleur() {
        val f = filtre()
        assertTrue("usb_device_filter.xml introuvable depuis " + File(".").absolutePath,
            f != null)
        val texte = f!!.readText()
        // Values are decimal: the platform does not accept 0x2341.
        val attendus = mapOf(
            "Arduino SA (0x2341)" to 9025,
            "Arduino ancien (0x2A03)" to 10755,
            "Atmel (0x03EB)" to 1003,
            "SparkFun (0x1B4F)" to 6991,
            "Adafruit (0x239A)" to 9114,
            "Teensy (0x16C0)" to 5824,
            "STMicroelectronics (0x0483)" to 1155,
            "CH34x (0x1A86)" to 6790,
            "FTDI (0x0403)" to 1027,
            "CP210x (0x10C4)" to 4292)
        val manquants = attendus.filterValues {
            !texte.contains("vendor-id=\"" + it + "\"")
        }.keys
        assertTrue("fabricants absents du filtre USB : " + manquants.joinToString(", "),
            manquants.isEmpty())
    }

    @Test
    fun le_filtre_usb_prend_le_cp210x_en_entier() {
        val f = filtre()
        assertTrue("usb_device_filter.xml introuvable", f != null)
        val texte = f!!.readText()
        // The original entry only matched product 0xEA60 (IC-9700); a CP2105 or
        // CP2108 has another product id and was left out.
        assertTrue("le CP210x doit être déclaré sans numéro de produit",
            Regex("""<usb-device\s+vendor-id="4292"\s*/>""").containsMatchIn(texte))
        // And the CDC class as a safety net for home-built controllers.
        assertTrue("la classe CDC doit être déclarée",
            Regex("""<usb-device\s+class="2"\s*/>""").containsMatchIn(texte))
    }
}
