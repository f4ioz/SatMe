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

    /**
     * A pass's data for « Données du passage » and its picture growing packet by packet: three packets held from an
     * earlier pass (one heard again now), three new ones heard 10 s apart; frames of three kinds, a BBS message.
     */
    @Test fun `donnees du passage - image construite paquet par paquet, trames par sorte, messagerie`() {
        val dir = Files.createTempDirectory("genesis-d").toFile()
        val a = fr.f4ioz.satcombo.telemesure.AssemblageSsdv(dir) { debut }
        val archive = ArchiveGenesis(dir)
        val sat = "HADES-SA (SpinnyONE)"
        val paquets = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "Q0SAT", 144).map { Ssdv.recachete(it, Genesis.INDICATIF_SSDV) }
            .mapNotNull { Ssdv.lis(it) }
        assertTrue(paquets.size > 8)
        val avant = debut - 2 * 3_600_000L
        (0..2).forEach { n -> a.ajoute(paquets[n], avant + n * 10_000L, sat) }
        val t1 = debut + 60_000L
        (2..5).forEachIndexed { k, n ->
            val r = a.ajoute(paquets[n], t1 + k * 10_000L, sat)
            archive.ssdv(t1 + k * 10_000L, sat, 68446, r.photo!!.id, n, "radio", "200")
        }
        val temperatures = AssemblageSsdvHex("668B1F01FF747978FF6F7879FF7D")
        archive.trame(t1 - 20_000L, sat, 68446, 2, temperatures, "radio", "200")
        archive.trame(t1 + 100_000L, sat, 68446, 2, temperatures.copyOf().also { it[13] = 0x70 }, "radio", "200")
        archive.trame(t1 + 5_000L, sat, 68446, 1, ByteArray(28) { (it * 7).toByte() }, "radio", "200")
        val bbs = ByteArray(70) { ' '.code.toByte() }
        "EA4XYZ".toByteArray().copyInto(bbs, 0); "HOLA 73".toByteArray().copyInto(bbs, 30); bbs[65] = 3
        for (i in 66 until 70) bbs[i] = 0
        archive.trame(t1 + 50_000L, sat, 68446, 15, bbs, "radio", "200")

        val g = GenesisJournalPc({ archive }, { a })
        val e = JournalPassage.Entree(68446, sat, debut, debut + 10 * 60_000L)
        val x = g.etapes(e).single()
        // Held before the pass: 0, 1 and 2 (2 heard again now); this pass's packets at the time they came.
        assertEquals(setOf(0, 1, 2), x.avant)
        assertEquals(listOf(t1 to 2, t1 + 10_000L to 3, t1 + 20_000L to 4, t1 + 30_000L to 5), x.recus)
        assertEquals(setOf(0, 1, 2), x.visibles(t1 - 1))
        assertEquals(setOf(0, 1, 2, 3), x.visibles(t1 + 15_000L))
        assertEquals((0..5).toSet(), x.visibles(t1 + 60_000L))
        // The picture grows: before packet 3, after it, at the end; the earlier passes' blocks darker than this one's.
        val cle = x.cle
        val im0 = g.imageA(e, cle, t1 - 1)!!; val im1 = g.imageA(e, cle, t1 + 15_000L)!!; val im2 = g.imageA(e, cle, t1 + 60_000L)!!
        assertEquals(320, im0.width)
        fun differents(p: java.awt.image.BufferedImage, q: java.awt.image.BufferedImage) =
            (0 until 240 step 3).sumOf { y -> (0 until 320 step 3).count { xx -> p.getRGB(xx, y) != q.getRGB(xx, y) } }
        assertTrue(differents(im0, im1) > 50)
        assertTrue(differents(im1, im2) > 50)
        // The same state twice: the same picture (kept).
        assertTrue(im1 === g.imageA(e, cle, t1 + 19_000L))
        val dans = g.image(a.photo(cle)!!, emptySet(), setOf(0, 1, 2))
        val sombre = g.image(a.photo(cle)!!, setOf(0, 1, 2), emptySet())
        val lum = { im: java.awt.image.BufferedImage -> (0 until 16).sumOf { yy -> (0 until 64).sumOf { xx -> im.getRGB(xx, yy) and 0xFF } } }
        assertTrue(lum(sombre) < lum(dans))

        // « Données du passage »: the counts, the frames by kind with every value, the BBS message once.
        val j = g.json(e, false)
        val c = j.getJSONObject("compteurs")
        assertEquals(4, c.getInt("trames")); assertEquals(3, c.getInt("types")); assertEquals(4, c.getInt("paquets"))
        assertEquals(3, c.getInt("nouveaux")); assertEquals("200", c.getString("debit"))
        val gr = (0 until j.getJSONArray("groupes").length()).map { j.getJSONArray("groupes").getJSONObject(it) }
        assertEquals(listOf(2, 1), gr.map { it.getInt("type") })
        val temp = gr[0]
        assertEquals("Températures", temp.getString("nom")); assertEquals(2, temp.getInt("n"))
        assertEquals(listOf(t1 - 20_000L, t1 + 100_000L), (0 until 2).map { temp.getJSONArray("t").getLong(it) })
        val cpu = (0 until temp.getJSONArray("champs").length()).map { temp.getJSONArray("champs").getJSONObject(it) }.first { it.getString("k") == "cpu" }
        assertEquals("°C", cpu.getString("u"))
        assertEquals(22.5, cpu.getJSONArray("v").getDouble(0), 0.0)
        assertEquals(16.0, cpu.getJSONArray("v").getDouble(1), 0.0)
        val m = j.getJSONArray("bbs").getJSONObject(0)
        assertEquals("EA4XYZ", m.getString("de")); assertEquals("HOLA 73", m.getString("m")); assertEquals(1, j.getJSONArray("bbs").length())
        val s = j.getJSONArray("ssdv").getJSONObject(0)
        assertEquals(4, s.getJSONArray("recu").length()); assertEquals(3, s.getJSONArray("avant").length())
        assertEquals(true to "200", g.resume(e))

        // The video: the picture from the pass's first packet on, its count each packet once, the pace slowed meanwhile.
        val v = RenduPc.SsdvPassage(x.titre, x.recus, x.avant, 26) { tt -> g.image(a.photo(cle)!!, x.avant, x.ici(tt)) }
        assertEquals(null, RenduPc.ssdvA(listOf(v), t1 - 1))
        assertTrue(RenduPc.ssdvA(listOf(v), t1 + 5_000L) === v)
        assertEquals(3, v.nombreA(t1 - 1)); assertEquals(4, v.nombreA(t1 + 15_000L)); assertEquals(6, v.nombreA(t1 + 60_000L))
        assertTrue(v.image(t1 + 15_000L) === im1)
    }
}
