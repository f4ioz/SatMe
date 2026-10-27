/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.telemesure.AssemblageSsdv.Resultat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** SSDV pictures put together over several passes: merging, duplicates, image numbers coming round, missing packets, the files. */
class AssemblageSsdvTest {

    private val t0 = 1_790_000_000_000L
    private val orbite = 95 * 60_000L
    private val mire = SignalPaquets.paquetsMire(5).map { Ssdv.lis(it)!! }
    private fun dossier(): File = Files.createTempDirectory("ssdv").toFile()

    @Test fun deuxPassagesUneImage() {
        val d = dossier()
        val a = AssemblageSsdv(d) { t0 }
        assertTrue(mire.size > 10)
        // First pass: the first two thirds, a few lost; the next orbit, the rest and again some of the first.
        val premier = mire.indices.filter { it < mire.size * 2 / 3 && it % 5 != 2 }
        premier.forEachIndexed { k, i -> assertEquals(Resultat.NOUVEAU, a.ajoute(mire[i], t0 + k * 2_000L, "SATDEMO").resultat) }
        val ph = a.photos().single()
        assertFalse(ph.complete)
        assertNull(ph.fichier)
        assertTrue(ph.totalEstime().second)
        var fin: AssemblageSsdv.Ajout? = null
        mire.indices.filter { it !in premier }.forEachIndexed { k, i -> fin = a.ajoute(mire[i], t0 + orbite + k * 2_000L, "SATDEMO") }
        assertSame(ph, fin!!.photo)
        assertTrue(fin!!.finie)
        assertTrue(ph.complete)
        assertEquals(mire.size, ph.total)
        assertEquals(100, ph.pourcent())
        assertEquals(2, ph.passages.size)
        assertEquals(premier.size, ph.passages[0].paquets)
        assertEquals(mire.size - premier.size, ph.passages[1].paquets)
        // The strip: every packet with its pass, none missing.
        val bande = ph.bande()
        assertEquals(mire.size, bande.sumOf { it.second })
        assertTrue(bande.none { it.third < 0 })
        assertEquals(setOf(0, 1), bande.map { it.third }.toSet())
        // The finished picture with the others.
        assertEquals(ph.id + ".jpg", ph.fichier)
        assertTrue(File(d, "telemesure/ssdv/" + ph.fichier).length() > 1000)
    }

    @Test fun doublonsGardesUneFois() {
        val a = AssemblageSsdv(null) { t0 }
        assertEquals(Resultat.NOUVEAU, a.ajoute(mire[0], t0).resultat)
        assertEquals(Resultat.DOUBLON, a.ajoute(mire[0], t0 + 1_000).resultat)
        // The same packet repaired by Reed-Solomon (another station, a worse signal): still a duplicate.
        val abime = SignalPaquets.paquetsMire(5)[0].copyOf()
        for (k in listOf(20, 60, 120)) abime[k] = (abime[k].toInt() xor 0x41).toByte()
        val repare = Ssdv.lis(abime)!!
        assertEquals(3, repare.corriges)
        assertEquals(Resultat.DOUBLON, a.ajoute(repare, t0 + orbite).resultat)
        // A packet whose CRC stays wrong never comes this far.
        for (k in 1 until 80 step 2) abime[k] = (abime[k].toInt() xor 0x33).toByte()
        assertNull(Ssdv.lis(abime))
        assertEquals(1, a.photos().single().recus)
        assertEquals(1, a.photos().single().passages.size)
    }

    @Test fun numeroQuiRevientAutreImage() {
        val a = AssemblageSsdv(null) { t0 }
        val p1 = a.ajoute(mire[0], t0).photo!!
        // Another picture under the same number (the byte came round): packet 0 with other bytes.
        val autre = Ssdv.codeImage(IntArray(320 * 240) { 0x20C040 }, 320, 240, "Q0SAT", 5).map { Ssdv.lis(it)!! }
        val p2 = a.ajoute(autre[0], t0 + orbite).photo!!
        assertNotEquals(p1.id, p2.id)
        // Its next packets join it, not the first picture.
        assertSame(p2, a.ajoute(autre[1], t0 + orbite + 2_000).photo)
        assertEquals(1, p1.recus)
        // Too long after: a new picture even with the same bytes.
        val p3 = a.ajoute(mire[1], t0 + orbite + AssemblageSsdv.FENETRE_MS + 60_000).photo!!
        assertTrue(p3 !== p1 && p3 !== p2)
        assertEquals(3, a.photos().size)
        // Another callsign, same number: its own picture.
        val f = Ssdv.codeImage(SignalPaquets.mire(), 320, 240, "F4IOZ", 5).map { Ssdv.lis(it)!! }
        assertEquals("F4IOZ", a.ajoute(f[0], t0 + 10_000).photo!!.indicatif)
        assertEquals(4, a.photos().size)
    }

    @Test fun apresLaFinUneAutreImage() {
        val a = AssemblageSsdv(null) { t0 }
        mire.forEachIndexed { k, p -> a.ajoute(p, t0 + k * 1_000L) }
        val ph = a.photos().single()
        assertTrue(ph.complete)
        // A longer picture under the same number: its packet past the end starts another one.
        val longue = Ssdv.codeImage(IntArray(320 * 240) { i -> if (i % 7 == 0) 0xFFFFFF else (i * 2654435761L).toInt() and 0xFFFFFF }, 320, 240, "Q0SAT", 5)
            .map { Ssdv.lis(it)!! }
        assertTrue(longue.size > mire.size)
        assertNotEquals(ph.id, a.ajoute(longue[mire.size], t0 + orbite).photo!!.id)
    }

    @Test fun paquetsManquantsEnGris() {
        val a = AssemblageSsdv(null) { t0 }
        // The last packet missing, and two in the middle.
        val pris = mire.indices.filter { it != 3 && it != 7 && it != mire.size - 1 }
        pris.forEach { a.ajoute(mire[it], t0 + it * 1_000L) }
        val ph = a.photos().single()
        assertFalse(ph.complete)
        assertNull(ph.total)
        assertEquals(listOf(3, 7), ph.manquants)
        val (estime, devine) = ph.totalEstime()
        assertTrue(devine)
        assertTrue("$estime / ${mire.size}", estime in mire.size - 2..mire.size + 2)
        assertEquals(ph.recus * 100 / estime, ph.pourcent())
        assertTrue(ph.pourcent() in 50..99)
        // Missing in the strip, and the picture still made (those blocks grey).
        val bande = ph.bande()
        assertTrue(bande.any { it.first == 3 && it.second == 1 && it.third == -1 })
        val j = ph.jpeg()!!
        val img = javax.imageio.ImageIO.read(j.inputStream())
        assertEquals(320, img.width)
        // The last packet now: the count is known.
        a.ajoute(mire.last(), t0 + orbite)
        assertEquals(mire.size, ph.total)
        assertEquals(mire.size - 2, ph.recus)
        assertEquals(2, ph.passages.size)
    }

    @Test fun gardeSurDisqueEtRelu() {
        val d = dossier()
        val a = AssemblageSsdv(d) { t0 }
        mire.indices.filter { it % 2 == 0 }.forEach { a.ajoute(mire[it], t0 + it * 1_000L, "SATDEMO") }
        mire.indices.filter { it % 4 == 1 }.forEach { a.ajoute(mire[it], t0 + orbite + it * 1_000L, "SATDEMO") }
        val avant = a.photos().single()
        val f = File(d, "telemesure/ssdv/paquets/" + avant.id + ".jsonl")
        assertTrue(f.isFile)
        // A damaged line (a byte changed in the hex): left out when read again.
        f.appendText("{\"t\":1,\"src\":\"radio\",\"hex\":\"" + AssemblageSsdv.hex(SignalPaquets.paquetsMire(5)[3].also { it[50] = (it[50] + 1).toByte(); it[51] = (it[51] + 1).toByte()
            for (k in 2..200 step 3) it[k] = 0 }) + "\"}\nnot json\n")
        val b = AssemblageSsdv(d) { t0 + 2 * orbite }
        val apres = b.photo(avant.id)!!
        assertEquals(avant.recus, apres.recus)
        assertEquals(2, apres.passages.size)
        assertEquals("SATDEMO", apres.satellite)
        assertEquals(avant.bande(), apres.bande())
        // The rest after the restart joins the same picture; a duplicate still refused.
        assertEquals(Resultat.DOUBLON, b.ajoute(mire[0], t0 + 2 * orbite).resultat)
        mire.indices.filter { it !in apres.image.listeNumeros() }.forEach { b.ajoute(mire[it], t0 + 2 * orbite + it, "SATDEMO") }
        assertTrue(apres.complete)
        assertEquals(3, apres.passages.size)
        assertTrue(File(d, "telemesure/ssdv/" + apres.id + ".jpg").isFile)
        // Dropped: out of the list and off the disk.
        assertTrue(b.abandonne(apres.id))
        assertFalse(f.exists())
        assertTrue(AssemblageSsdv(d).photos().isEmpty())
    }

    @Test fun essaisJamaisEcrits() {
        val d = dossier()
        val a = AssemblageSsdv(d) { t0 }
        mire.forEach { a.ajoute(it, t0, essai = true) }
        assertTrue(a.photos().single().complete)
        assertFalse(File(d, "telemesure").exists())
        a.videEssais()
        assertTrue(a.photos().isEmpty())
    }

    @Test fun importDeFichiers() {
        val a = AssemblageSsdv(null) { t0 }
        // Raw packets, one after the other (the sync left out of one).
        val brut = SignalPaquets.paquetsMire(5)
        val fichier = brut.take(4).fold(ByteArray(0)) { x, p -> x + p } + brut[4].copyOfRange(1, 256)
        assertEquals(5 to 0, a.importe(fichier, "/tmp/passage.bin"))
        val ph = a.photos().single()
        assertEquals("import passage.bin", ph.passages.single().source)
        // A KISS file of the frames: the same five again, and three more.
        val kiss = brut.take(8).fold(ByteArray(0)) { x, p -> x + fr.f4ioz.satcombo.aprs.Kiss.trame(SignalPaquets.trameSsdv(p)) }
        assertEquals(3 to 5, a.importe(kiss, "autre.kiss"))
        // SatMe's frame log (JSON lines), with their time.
        val jl = brut.drop(8).joinToString("\n") { p ->
            org.json.JSONObject().put("t", t0 + orbite).put("sat", "SATDEMO").put("hex", AssemblageSsdv.hex(SignalPaquets.trameSsdv(p))).toString()
        }
        val (n, dbl) = a.importe(jl.toByteArray(), "20260101-1200-SATDEMO.jsonl")
        assertEquals(brut.size - 8, n); assertEquals(0, dbl)
        assertTrue(ph.complete)
        assertEquals(3, ph.passages.size)
        assertEquals("SATDEMO", ph.satellite)
        assertNotNull(a.json().getJSONObject(0).getJSONArray("bande"))
    }
}
