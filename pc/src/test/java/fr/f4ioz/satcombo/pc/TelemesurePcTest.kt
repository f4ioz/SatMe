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

    /** A WAV of made-up SO-127 audio (USB, GENESIS FSK 800 bit/s): [n] packets of a test picture with HADES-SA's callsign bytes. */
    private fun wavSo127(n: Int, fs: Int = 48_000): ByteArray {
        val paquets = fr.f4ioz.satcombo.telemesure.SignalPaquets.paquetsMire(21).take(n)
            .map { fr.f4ioz.satcombo.telemesure.Ssdv.recachete(it, fr.f4ioz.satcombo.telemesure.Genesis.INDICATIF_SSDV) }
        val o = paquets.flatMap { fr.f4ioz.satcombo.telemesure.Genesis.codeSsdv(it).toList() }.toByteArray()
        val pcm = fr.f4ioz.satcombo.telemesure.SignalFskAudio.audio(fr.f4ioz.satcombo.telemesure.Genesis.bits(o), fs, 800.0, 1_600.0, 1_500.0, 13.0, 2.0)
        val f = File.createTempFile("so127", ".wav").also { it.deleteOnExit() }
        fr.f4ioz.satcombo.sstv.SstvSon.ecritWav(f, pcm, fs)
        return f.readBytes()
    }

    @Test fun `SSDV en BLU - coupe par defaut, un enregistrement relu donne l'image`() {
        val dir = Files.createTempDirectory("tlm").toFile()
        val t = TelemesurePc(stockage(), dir, null)
        t.suiviGenesis = { "HADES-SA" to 68446 }
        assertFalse(t.genesisActif)
        val wav = wavSo127(5)
        // Off: the card's sound is not decoded.
        val (pcm, rate) = fr.f4ioz.satcombo.telemesure.LectureWav.lis(wav)!!
        t.ecouteCarte(pcm, pcm.size, rate)
        assertTrue(t.paquets.assemblage.photos().isEmpty())
        // A recording read again: its packets into the pictures, the pass named after the file.
        val r = t.relitGenesis(wav, "SatMe_HADES-SA_20261030_091200Z.wav")
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(5, r.getInt("nouveaux"))
        val ph = t.paquets.assemblage.photos().single()
        assertEquals("HADES-SA", ph.satellite)
        assertEquals(5, ph.recus)
        assertTrue(ph.passages.single().source.startsWith("enregistrement SatMe_HADES-SA"))
        // The recording's own time (its name), not now.
        assertTrue(ph.debut in 1_793_351_520_000L..1_793_351_600_000L)
        // Switched on: the card's sound decoded live (the duplicates kept once).
        t.action("/api/telemesure/genesis", mapOf("on" to "1"))
        t.ecouteCarte(pcm, pcm.size, rate)
        assertEquals(5, t.paquets.assemblage.photos().single().recus)
        assertTrue(t.json().getJSONObject("genesis").getInt("ssdv") >= 5)
    }

    @Test fun `SSDV en BLU - l'essai ne garde rien`() {
        val dir = Files.createTempDirectory("tlm").toFile()
        val t = TelemesurePc(stockage(), dir, null)
        t.genesisProfilChoisi = "800"
        assertTrue(t.lanceEssaiGenesis())
        val fin = System.currentTimeMillis() + 120_000
        while (t.json().getJSONObject("genesis").getBoolean("essaiEnCours") && System.currentTimeMillis() < fin) Thread.sleep(100)
        val im = t.json().getJSONObject("paquets").getJSONArray("images")
        assertEquals(1, im.length())
        assertTrue(im.getJSONObject(0).getBoolean("essai"))
        assertTrue(im.getJSONObject(0).getBoolean("complete"))
        assertFalse(File(dir, "telemesure/ssdv").exists() && File(dir, "telemesure/ssdv").listFiles()!!.isNotEmpty())
    }

    @Test fun `SSDV par un repeteur FM en AFSK 1200 - nu ou dans une trame AX25`() {
        val dir = Files.createTempDirectory("tlm").toFile()
        val t = TelemesurePc(stockage(), dir, null)
        t.suiviGenesis = { "LAPAN-A2" to 40931 }
        val paquets = fr.f4ioz.satcombo.telemesure.Ssdv.codeImage(fr.f4ioz.satcombo.telemesure.SignalPaquets.mire(), 320, 240, "YB0ABC", 4).take(4)
        // Two bare (ssdv2sat's default), two in a UI frame (« --ax25 »-like): the FCS after each.
        fun fcs(b: ByteArray): ByteArray { val c = fr.f4ioz.satcombo.aprs.Ax25.crc(b); return b + byteArrayOf(c.toByte(), (c ushr 8).toByte()) }
        val trames = paquets.take(2).map { fcs(it) } + paquets.drop(2).map { fr.f4ioz.satcombo.telemesure.SignalPaquets.trameSsdv(it) }
        val pcm = fr.f4ioz.satcombo.aprs.Afsk.module(trames, 48_000)
        val d = fr.f4ioz.satcombo.aprs.AfskDemodulateur(48_000) { }
        d.surOctets = { o -> t.trameAfsk(o) }
        d.traite(pcm, pcm.size)
        val ph = t.paquets.assemblage.photos().single()
        assertEquals(4, ph.recus)
        assertEquals("YB0ABC", ph.indicatif)
        assertEquals("LAPAN-A2", ph.satellite)
        assertEquals("AFSK 1200", ph.passages.single().source)
    }
}
