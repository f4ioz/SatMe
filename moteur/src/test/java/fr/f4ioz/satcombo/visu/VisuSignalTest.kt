/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.visu

import fr.f4ioz.satcombo.aprs.AfskDemodulateur
import fr.f4ioz.satcombo.aprs.Afsk
import fr.f4ioz.satcombo.aprs.Adresse
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Trame
import fr.f4ioz.satcombo.clavier.DecodeurPsk31
import fr.f4ioz.satcombo.clavier.DecodeurRtty
import fr.f4ioz.satcombo.clavier.SignauxEssai
import fr.f4ioz.satcombo.telemesure.Ao40Fec
import fr.f4ioz.satcombo.telemesure.DecodeurTelemesure
import fr.f4ioz.satcombo.telemesure.G3ruh
import fr.f4ioz.satcombo.telemesure.Genesis
import fr.f4ioz.satcombo.telemesure.RecepteurGenesis
import fr.f4ioz.satcombo.telemesure.RecepteurPaquets
import fr.f4ioz.satcombo.telemesure.SignalEssai
import fr.f4ioz.satcombo.telemesure.SignalFskAudio
import fr.f4ioz.satcombo.telemesure.SignalPaquets
import fr.f4ioz.satcombo.telemesure.AssemblageSsdv
import fr.f4ioz.satcombo.visu.SondeSignal.Vue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The views' taps: rings, quiet without a viewer, and what they show on made-up signals of each mode. */
class VisuSignalTest {

    private var maintenant = 1_000_000L
    private fun sonde(vararg vues: Vue, motifs: List<SondeSignal.Motif> = emptyList()) =
        SondeSignal("essai", "Essai", vues.toSet(), motifs, horloge = { maintenant })

    @Test fun `sans regard, rien n'est ecrit ni alloue`() {
        val s = sonde(Vue.CONSTELLATION, Vue.BITS)
        assertFalse(s.actif)
        repeat(5000) { s.point(1f, 0f); s.bit(1); s.oeil(0.3, 0.5f); s.niveau(1f) }
        val c = s.cliche()
        assertEquals(0, c.points.size); assertEquals(0, c.bits.size); assertEquals(0L, s.ecrits)
    }

    @Test fun `les anneaux gardent les derniers, dans l'ordre`() {
        val s = sonde(Vue.CONSTELLATION, Vue.BITS)
        s.abonne()
        repeat(SondeSignal.N_POINTS + 300) { s.point(it.toFloat(), -it.toFloat()) }
        repeat(SondeSignal.N_BITS + 77) { s.bit(it % 3 / 2) }
        val c = s.cliche()
        assertEquals(2 * SondeSignal.N_POINTS, c.points.size)
        assertEquals(300f, c.points[0]); assertEquals(-300f, c.points[1])
        assertEquals((SondeSignal.N_POINTS + 299).toFloat(), c.points[c.points.size - 2])
        assertEquals(SondeSignal.N_BITS, c.bits.size)
        assertEquals(77L, c.premierBit)
        for (k in c.bits.indices) assertEquals(((77 + k) % 3 / 2).toByte(), c.bits[k])
    }

    @Test fun `le regard parti, la sonde se tait d'elle-meme`() {
        val s = sonde(Vue.BITS)
        s.abonne()
        repeat(10_000) { s.bit(0) }
        assertTrue(s.actif)
        maintenant += SondeSignal.BAIL_MS + 1
        repeat(10_000) { s.bit(0) }
        assertFalse(s.actif)
        val n = s.ecrits
        repeat(10_000) { s.bit(1) }
        assertEquals(n, s.ecrits)
        s.abonne(); assertTrue(s.actif)
    }

    @Test fun `figures pures - MER d'un nuage, deux bosses, debit`() {
        val r = java.util.Random(3)
        val p = FloatArray(2000) { if (it % 2 == 0) (if (r.nextBoolean()) 1f else -1f) + 0.1f * r.nextGaussian().toFloat() else 0.1f * r.nextGaussian().toFloat() }
        val (mer, evm) = AnalyseSignal.constellation(p, 2)
        // σ = 0.1 on each axis: MER = 10 log10(1 / 0.02) ≈ 17 dB.
        assertTrue("$mer", abs(mer - 17.0) < 1.0)
        assertTrue("$evm", abs(evm - 14.1) < 1.5)
        val v = FloatArray(3000) { (if (r.nextBoolean()) 2400f else -2400f) + 300f * r.nextGaussian().toFloat() }
        val t = AnalyseSignal.tons(AnalyseSignal.histogramme(v)!!)!!
        assertTrue("$t", abs(t.ecart - 4800) < 200)
        val o = AnalyseSignal.oeil(v)
        assertTrue("$o", o.first > 0.3 && o.second > 15)
        assertEquals(9600.0 * (1 + 100e-6), AnalyseSignal.debit(DoubleArray(500) { it * 88_200.0 / (9600 * (1 + 100e-6)) }, 88_200.0), 0.01)
        // One hump only: no tones.
        assertEquals(null, AnalyseSignal.tons(AnalyseSignal.histogramme(FloatArray(3000) { 300f * r.nextGaussian().toFloat() })!!))
    }

    @Test fun `les mots de synchro et la trame marques dans les bits`() {
        val s = sonde(Vue.BITS, motifs = listOf(SondeSignal.DRAPEAU_HDLC, SondeSignal.Motif("BF35", Genesis.SYNC.toLong(), 16, 1, true)))
        s.abonne()
        fun octet(o: Int) { for (k in 7 downTo 0) s.bit((o ushr k) and 1) }
        repeat(3) { octet(0x00) }
        octet(0x7E); octet(0x12); octet(0x34); octet(0x7E)
        s.trameEntreReperes("trame")
        octet(0xBF xor 0xFF); octet(0x35 xor 0xFF)
        val c = s.cliche()
        val drapeaux = c.reperes.filter { it.nom == "7E" }
        assertEquals(listOf(24L, 48L), drapeaux.map { it.debut })
        assertEquals(SondeSignal.Repere(32, 16, "trame", ms = maintenant), c.trame)
        val g = c.reperes.single { it.nom == "BF35" }
        assertEquals(56L, g.debut); assertTrue(g.inverse)
        val j = c.json()
        assertEquals(72, j.getString("bits").length)
        assertEquals(3, j.getJSONArray("reperes").length())
        assertEquals(32, j.getJSONArray("trame").getInt(0))
    }

    @Test fun `BPSK FUNcube - la constellation en deux nuages a plus et moins un`() {
        val d = DecodeurTelemesure(DecodeurTelemesure.Profil.FUNCUBE, 44_100)
        d.sonde.abonne()
        val t = SignalEssai.trameFuncube(1, List(40) { 8 to 7L })
        val audio = SignalEssai.audio(Ao40Fec.code(t) + Ao40Fec.code(t), 1200.0, manchester = false, porteuseHz = 1_300.0, ebN0Db = 14.0, apresS = 0.0)
        var c: VisuSignal? = null
        var k = 0
        while (k < audio.size) {
            val n = minOf(4096, audio.size - k); d.traite(audio.copyOfRange(k, k + n), n); k += n
            if (c == null && k > audio.size * 3 / 4) c = d.sonde.cliche()
        }
        val v = c!!
        assertTrue(v.points.size >= 1000)
        var proches = 0
        for (i in 0 until v.points.size / 2) if (abs(abs(v.points[2 * i]) - 1f) < 0.4f && abs(v.points[2 * i + 1]) < 0.4f) proches++
        assertTrue("$proches / ${v.points.size / 2}", proches > 0.9 * v.points.size / 2)
        assertTrue("MER ${v.qualite}", v.qualite.first > 10)
        assertTrue("débit ${v.debitMesure}", abs(v.debitMesure - 1200) < 2)
        assertTrue(d.etat.trames >= 1)
        assertEquals("trame codée", d.sonde.cliche().trame?.nom)
        val j = v.json()
        assertTrue(j.has("points") && j.getJSONObject("qualite").has("mer"))
        assertFalse(j.has("oeil"))
    }

    @Test fun `FSK 9600 - l'oeil, l'histogramme trouve l'ecart, les drapeaux, le debit`() {
        val bits = G3ruh.bits((1..6).map { SignalPaquets.trameTelemesure(it, "trame d'essai numero") })
        val fs = RecepteurPaquets.FS_CIBLE
        val (re, im) = SignalPaquets.baseband(bits, fs, 9600, 1_500.0, 18.0, 2L)
        val r = RecepteurPaquets(null)
        r.contexte = { RecepteurPaquets.Contexte(essai = true) }
        r.sonde.abonne()
        var c: VisuSignal? = null
        var k = 0
        while (k < re.size) {
            val n = minOf(4096, re.size - k); r.traiteBaseband(re.copyOfRange(k, k + n), im.copyOfRange(k, k + n), n, fs); k += n
            if (c == null && r.trames.size >= 3) c = r.sonde.cliche()
        }
        val v = c!!
        assertEquals("Hz", v.unite)
        val t = v.tons
        assertNotNull(t)
        // The levels at ± the deviation (3 kHz), a little less after the filters.
        assertTrue("$t", t!!.ecart in 3_600.0..6_600.0)
        assertTrue("${v.ouverture}", v.ouverture.second > 6)
        assertTrue("${v.debitMesure}", abs(v.debitMesure - 9600) < 10)
        assertTrue(v.reperes.any { it.nom == "7E" })
        assertEquals("trame G3RUH", v.trame?.nom)
        assertTrue(v.lecture.startsWith("G3RUH"))
        // The flags are where the bits read 01111110.
        for (m in v.reperes.filter { it.nom == "7E" }) {
            val i = (m.debut - v.premierBit).toInt()
            assertEquals("01111110", (i until i + 8).joinToString("") { v.bits[it].toString() })
        }
        assertTrue(v.oeil.size > 1000)
    }

    @Test fun `GENESIS en BLU - le spectre montre les deux tons a 1600 Hz`() {
        val r = RecepteurGenesis(null, AssemblageSsdv(null))
        r.contexte = { RecepteurGenesis.Contexte("Essai", essai = true) }
        r.sonde.abonne()
        val bits = Genesis.bits(Genesis.code(2, 3, ByteArray(15))) + Genesis.bits(Genesis.code(2, 3, ByteArray(15) { it.toByte() })) + Genesis.bits(Genesis.code(2, 3, ByteArray(15))) + IntArray(64) { it and 1 }
        val audio = SignalFskAudio.audio(bits, 44_100, 800.0, 1_600.0, 1_700.0, 20.0, silenceS = 0.4)
        var c: VisuSignal? = null
        var k = 0
        while (k < audio.size) {
            val n = minOf(4096, audio.size - k); r.traite(audio.copyOfRange(k, k + n), n, 44_100); k += n
            if (c == null && k > audio.size * 0.6) c = r.sonde.cliche()
        }
        val v = c!!
        val t = v.tons!!
        assertTrue("$t", abs(t.ecart - 1600) < 60)
        assertTrue("$t", abs((t.bas + t.haut) / 2 - 1700) < 60)
        assertTrue("${v.debitMesure}", abs(v.debitMesure - 800) < 2)
        assertTrue(r.sonde.cliche().reperes.map { it.nom }.toString(), v.reperes.any { it.nom == "synchro BF35" })
    }

    @Test fun `AFSK 1200 - les tons a 1200 et 2200 Hz, les drapeaux, la trame AX25`() {
        val a = AfskDemodulateur(48_000) {}
        a.sonde.abonne()
        val tr = Ax25.encode(Trame(Adresse("APRS"), Adresse("Q0SAT", 1), emptyList(), "!4850.00N/00220.00E-essai de la vue".toByteArray()))
        val pcm = Afsk.module(listOf(tr), 48_000)
        a.traite(pcm, pcm.size)
        val v = a.sonde.cliche()
        val t = v.tons!!
        assertTrue("$t", abs(t.bas - 1200) < 60 && abs(t.haut - 2200) < 60)
        // Tones off theirs (a rig's offset): measured where they are, not where they should be.
        val b = AfskDemodulateur(48_000) {}
        b.sonde.abonne()
        val pcm2 = Afsk.module(listOf(tr), 48_000, marque = 1_260.0, espace = 2_260.0)
        b.traite(pcm2, pcm2.size)
        val t2 = b.sonde.cliche().tons!!
        assertTrue("$t2", abs(t2.bas - 1260) < 60 && abs(t2.haut - 2260) < 60)
        assertTrue(v.reperes.any { it.nom == "7E" })
        assertEquals("trame AX.25", v.trame?.nom)
        assertTrue("${v.debitMesure}", abs(v.debitMesure - 1200) < 5)
    }

    @Test fun `PSK31 et RTTY ont leur sonde`() {
        val p = DecodeurPsk31(8_000, {}, 1_000.0)
        p.sonde.abonne()
        val audio = SignauxEssai.audioPsk31(SignauxEssai.Psk31("CQ CQ de essai essai", 0.2), 8_000, 1_000.0, 6.0)
        p.ecoute(audio, audio.size)
        val v = p.sonde.cliche()
        assertTrue(v.points.size > 200)
        assertTrue("MER ${v.qualite}", v.qualite.first > 8)
        val rt = DecodeurRtty(8_000, {}, 1_500.0)
        rt.sonde.abonne()
        val ar = SignauxEssai.audioRtty(SignauxEssai.Rtty("RYRYRY CQ CQ"), 8_000, 1_500.0, 3.0)
        rt.ecoute(ar, ar.size)
        val w = rt.sonde.cliche()
        val t = w.tons!!
        assertTrue("$t", abs(t.ecart - 170) < 25)
        assertTrue(w.oeil.size > 100)
        assertEquals(5, w.groupe)
    }
}
