/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.telemesure.ArchiveGenesis
import fr.f4ioz.satcombo.telemesure.AssemblageVoix
import fr.f4ioz.satcombo.telemesure.Genesis
import fr.f4ioz.satcombo.telemesure.SignalFskAudio
import fr.f4ioz.satcombo.telemesure.SignalPaquets
import fr.f4ioz.satcombo.telemesure.Ssdv
import fr.f4ioz.satcombo.telemesure.VoixGenesis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A GENESIS pass in the journal, on a **synthetic** HADES-SA recording at 200 bit/s (a temperatures frame, three SSDV
 * packets, three voice packets): read again, its telemetry made readable, its partial picture, its voice — and read
 * again a second time, nothing twice.
 */
class GenesisJournalPcTest {

    private fun stockage() = StockageFichier(File.createTempFile("tlm", ".properties").also { it.delete(); it.deleteOnExit() })

    private val nom = "SatMe_HADES-SA--SpinnyONE_20261103_022111Z.wav"
    private val debut = 1_793_672_471_000L // 2026-11-03 02:21:11Z

    private fun wav(): ByteArray {
        val fs = 24_000
        val temperatures = AssemblageSsdvHex("668B1F01FF747978FF6F7879FF7D")
        val tlm = Genesis.code(2, Genesis.ADRESSE_HADES_SA, temperatures)
        val paquets = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 144).take(3).map { Ssdv.recachete(it, Genesis.INDICATIF_SSDV) }
        val o = paquets.flatMap { Genesis.codeSsdv(it).toList() }.toByteArray()
        val voix = (0 until 3).flatMap { n ->
            Genesis.codeVoix(3, n, VoixGenesis.envoye(ByteArray(40) { i -> if (i % 4 == 3) 0 else (n + i).toByte() })).toList()
        }.toByteArray()
        val pcm = SignalFskAudio.audio(Genesis.bits(o) + Genesis.bits(tlm, preambule = 24) + Genesis.bits(voix) + IntArray(64) { it and 1 },
            fs, 200.0, 1_125.0, 900.0, 14.0, 1.0)
        val f = File.createTempFile("hades", ".wav").also { it.deleteOnExit() }
        fr.f4ioz.satcombo.sstv.SstvSon.ecritWav(f, pcm, fs)
        return f.readBytes()
    }

    private fun AssemblageSsdvHex(s: String) = fr.f4ioz.satcombo.telemesure.AssemblageSsdv.deHex(s)!!

    @Test fun `un passage GENESIS relu - telemesure lisible, image partielle, voix, sans doublon`() {
        val dir = Files.createTempDirectory("genesis").toFile()
        val t = TelemesurePc(stockage(), dir, null, horloge = { debut + 3_600_000L })
        val w = wav()
        val sat = "HADES-SA (SpinnyONE)" to 68446
        val r = t.relitGenesis(w, nom, sat)
        assertTrue(r.toString(), r.getBoolean("ok"))
        // Read again a second time (the startup's read-again over a pass already read): nothing twice.
        assertTrue(t.relitGenesis(w, nom, sat).getBoolean("ok"))
        val archive = ArchiveGenesis(dir).entre(debut - 60_000, debut + 600_000, "HADES-SA")
        assertEquals(listOf(10, 10, 10, 2), archive.map { it.type })
        assertEquals(1, AssemblageVoix.tous(dir).size)

        // The journal's pass: its telemetry made readable at the time it was heard, its picture as it stands.
        val e = JournalPassage.Entree(68446, "HADES-SA (SpinnyONE)", debut, debut + 8 * 60_000L)
        val j = t.journalGenesis.json(e, false)
        val tlm = j.getJSONArray("tlm")
        assertEquals(1, tlm.length())
        val f = tlm.getJSONObject(0)
        assertEquals("Températures", f.getString("nom"))
        assertTrue(f.getLong("t") in debut..debut + 60_000L)
        assertTrue(f.getString("resume"), f.getString("resume").startsWith("CPU 22.5 °C"))
        val cpu = (0 until f.getJSONArray("champs").length()).map { f.getJSONArray("champs").getJSONObject(it) }.first { it.getString("k") == "cpu" }
        assertEquals(22.5, cpu.getDouble("v"), 0.0)
        assertEquals("°C", cpu.getString("u"))
        val im = j.getJSONArray("ssdv").getJSONObject(0)
        assertEquals(3, im.getInt("recus"))
        assertEquals(3, im.getJSONArray("ici").length())
        assertFalse(im.getBoolean("complete"))
        assertEquals(1, im.getJSONArray("passages").length())
        // In English when asked.
        assertEquals("Temperatures", t.journalGenesis.json(e, true).getJSONArray("tlm").getJSONObject(0).getString("nom"))
        // Another pass of the same satellite a day later: none of it.
        assertEquals(0, t.journalGenesis.json(e.copy(debutMs = debut + 86_400_000L, finMs = debut + 86_700_000L), false).getJSONArray("tlm").length())

        // The picture served with its missing blocks hatched (not the mid-grey of the raw one).
        val cle = im.getString("cle")
        val hachee = javax.imageio.ImageIO.read(t.jpegSsdv(mapOf("cle" to cle))!!.inputStream())
        val brute = javax.imageio.ImageIO.read(t.jpegSsdv(mapOf("cle" to cle, "brut" to "1"))!!.inputStream())
        assertEquals(320, hachee.width)
        val bas = (0 until 320 step 4).count { x -> hachee.getRGB(x, 230) != brute.getRGB(x, 230) }
        assertTrue("$bas", bas > 20)

        // The auto-pilot's history: the pass's packets and its picture, partial and said so.
        assertEquals(3 to 1, t.bilanSsdv(debut - 60_000, debut + 600_000))
        val d = t.detailSsdv(debut - 60_000, debut + 600_000).single()
        assertTrue(d, d.startsWith("image n° 144 partielle : 3/"))
    }
}
