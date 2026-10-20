/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.DecodeursMultiples
import fr.f4ioz.satcombo.telemesure.DecodeurTelemesure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** BPSK telemetry on SatMe-PC: off by default, the beacon channel in one click, the test mode, frames kept locally. */
class TelemesurePcTest {

    private fun stockage() = StockageFichier(File.createTempFile("tlm", ".properties").also { it.delete(); it.deleteOnExit() })

    @Test fun `coupe par defaut, rien ne decode`() {
        val dir = Files.createTempDirectory("tlm").toFile()
        val t = TelemesurePc(stockage(), dir, CanauxPc(stockage()))
        assertFalse(t.leoActif)
        t.ecouteSdr(ShortArray(44_100) { 1000 }, 44_100)
        val j = t.json()
        assertFalse(j.getJSONObject("leo").has("etat"))
        assertFalse(j.getJSONObject("balise").getBoolean("canal"))
        assertEquals(DecodeursMultiples.CADRAN_BALISE_HZ, j.getJSONObject("balise").getLong("cadran"))
    }

    @Test fun `la balise en un clic, une seule`() {
        val k = CanauxPc(stockage())
        val t = TelemesurePc(stockage(), Files.createTempDirectory("tlm").toFile(), k)
        assertTrue(t.action("/api/telemesure/balise", mapOf("on" to "1")).getBoolean("ok"))
        assertTrue(t.action("/api/telemesure/balise", mapOf("on" to "1")).getBoolean("ok"))
        assertTrue(k.actif)
        assertEquals(listOf(DecodeursMultiples.Decodeur.BALISE), k.liste.map { it.decodeur })
        assertEquals(10_489_748_500L, k.liste[0].cadranHz)
        assertTrue(t.json().getJSONObject("balise").getBoolean("canal"))
        t.action("/api/telemesure/balise", mapOf("on" to "0"))
        assertTrue(k.liste.isEmpty())
    }

    @Test fun `l'essai passe par le meme decodeur et n'est pas garde`() {
        val dir = Files.createTempDirectory("tlm").toFile()
        val t = TelemesurePc(stockage(), dir, CanauxPc(stockage()))
        assertTrue(t.lanceEssai(DecodeurTelemesure.Profil.FUNCUBE))
        val fin = System.currentTimeMillis() + 60_000
        while (t.json().getJSONObject("essai").getBoolean("enCours") && System.currentTimeMillis() < fin) Thread.sleep(100)
        val e = t.json().getJSONObject("essai")
        val l = e.getJSONArray("trames")
        assertEquals(2, l.length())
        val f = l.getJSONObject(1)
        assertTrue(f.getBoolean("essai"))
        assertEquals("FM1", f.getString("genre"))
        assertTrue(f.getString("message").startsWith("Essai SatMe"))
        assertFalse(File(dir, "telemesure").exists())
    }

    /** Made-up 9600 bit/s frames through the packet decoder, as the dongle's samples would bring them. */
    private fun recoit(t: TelemesurePc, n: Int) {
        val trames = (1..n).map { fr.f4ioz.satcombo.telemesure.SignalPaquets.trameTelemesure(it) }
        val fs = fr.f4ioz.satcombo.telemesure.RecepteurPaquets.FS_CIBLE
        val (re, im) = fr.f4ioz.satcombo.telemesure.SignalPaquets.baseband(fr.f4ioz.satcombo.telemesure.G3ruh.bits(trames), fs, 9_600, 900.0, 18.0, 3)
        t.paquets.traiteBaseband(re, im, re.size, fs)
    }

    @Test fun `paquets coupes par defaut, partage SiDS seulement coche et jamais pour un essai`() {
        val recus = java.util.concurrent.CopyOnWriteArrayList<String>()
        val s = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/telemetry/") { x -> recus += x.requestBody.readBytes().toString(Charsets.UTF_8); x.sendResponseHeaders(201, -1); x.close() }
        s.start()
        try {
            val dir = Files.createTempDirectory("tlm").toFile()
            val t = TelemesurePc(stockage(), dir, null, urlSids = "http://127.0.0.1:" + s.address.port + "/api/telemetry/")
            assertFalse(t.paquetsActif); assertFalse(t.sidsActif); assertFalse(t.kiss)
            var essai = true
            t.contextePaquets = { fr.f4ioz.satcombo.telemesure.RecepteurPaquets.Contexte("TESTSAT", 99_999, 435_000_000L, 435_001_000L, essai) }
            // No callsign: sharing refused.
            assertFalse(t.action("/api/telemesure/paquets/sids", mapOf("on" to "1")).getBoolean("ok"))
            t.station = { Triple("F4IOZ", 48.85, 2.35) }
            assertTrue(t.action("/api/telemesure/paquets/sids", mapOf("on" to "1")).getBoolean("ok"))
            t.action("/api/telemesure/paquets/kiss", mapOf("on" to "1"))
            // The generator's frames: shown, neither written nor shared.
            recoit(t, 2)
            assertEquals(2, t.json().getJSONObject("paquets").getJSONArray("trames").length())
            assertEquals(0, t.sids.enAttente)
            assertFalse(File(dir, "telemesure").exists())
            // Real ones: written for the pass (JSON lines, KISS) and queued for SatNOGS DB.
            essai = false
            t.action("/api/telemesure/paquets/vide", emptyMap())
            recoit(t, 3)
            assertEquals(3, t.sids.enAttente)
            assertEquals(3, t.sids.vide())
            assertEquals(3, recus.size)
            assertTrue(recus[0], recus[0].contains("noradID=99999") && recus[0].contains("source=F4IOZ") && recus[0].contains("latitude=48.9N"))
            val fichiers = File(dir, "telemesure/paquets").listFiles()!!.map { it.name }.sorted()
            assertEquals(2, fichiers.size)
            assertTrue(fichiers[0], fichiers[0].endsWith("-TESTSAT.jsonl"))
            assertEquals(3, File(dir, "telemesure/paquets/" + fichiers[0]).readLines().size)
            assertTrue(fichiers[1].endsWith(".kiss"))
            // The rate: the transmitter's, or the operator's.
            t.emetteurPaquets = { Triple(4_800, "UHF", "GMSK") }
            assertEquals(4_800, t.debitVoulu())
            t.action("/api/telemesure/paquets/debit", mapOf("v" to "1200"))
            assertEquals(1_200, t.debitVoulu())
        } finally { s.stop(0) }
    }

    @Test fun `une trame de la balise est ecrite dans le dossier du jour`() {
        val dir = Files.createTempDirectory("tlm").toFile()
        val k = CanauxPc(stockage())
        TelemesurePc(stockage(), dir, k)
        val d = ByteArray(256) { 'A'.code.toByte() }
        k.auTrame(DecodeurTelemesure.Trame(1_792_000_000_000L, "fec", d, true, 3, 40, 9.5))
        val f = File(dir, "telemesure").listFiles()!!.single()
        assertTrue(f.name, f.name.startsWith("qo100-"))
        val o = org.json.JSONObject(f.readLines().single())
        assertEquals("QO-100", o.getString("satellite"))
        assertEquals("41".repeat(256), o.getString("hex"))
    }
}
