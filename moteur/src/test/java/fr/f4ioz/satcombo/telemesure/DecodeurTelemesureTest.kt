/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.telemesure.DecodeurTelemesure.Profil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole chain: made-up audio (coded per the published format, noise, offset, drift) to frames. */
class DecodeurTelemesureTest {

    private fun decode(profil: Profil, audio: ShortArray): DecodeurTelemesure {
        val d = DecodeurTelemesure(profil, 44_100)
        var k = 0
        while (k < audio.size) {
            val n = minOf(2048, audio.size - k)
            d.traite(audio.copyOfRange(k, k + n), n)
            k += n
        }
        return d
    }

    @Test fun baliseQo100DecaleeEtBruitee() {
        val t1 = SignalEssai.texteBalise("N HI de essai QO-100 premier", 256)
        val t2 = SignalEssai.texteBalise("N HI de essai QO-100 second", 256)
        val p3 = SignalEssai.texteBalise("K HI de essai bloc non code", 512)
        val bits = Ao40Fec.code(t1) + BlocP3.code(p3) + IntArray(40) { it and 1 } + Ao40Fec.code(t2)
        // 230 Hz off the expected 1500 Hz, drifting 2 Hz/s (an LNB warming up), Es/N0 6 dB.
        val audio = SignalEssai.audio(bits, 400.0, manchester = true, porteuseHz = 1_730.0, deriveHzS = 2.0, ebN0Db = 6.0, graine = 3)
        val d = decode(Profil.QO100, audio)
        val fec = d.trames.filter { it.format == "fec" }.reversed()
        assertEquals(d.etat.toString(), 2, fec.size)
        assertArrayEquals(t1, fec[0].donnees)
        assertArrayEquals(t2, fec[1].donnees)
        // The uncoded block is found; at this noise it has a few wrong bits (its CRC says so).
        assertEquals(1, d.trames.count { it.format == "p3" })
        assertTrue(d.etat.manchester)
        assertTrue("${d.etat.decalageHz}", kotlin.math.abs(d.etat.decalageHz - 230 - 2 * 32) < 15)
    }

    @Test fun blocNonCodeDeLaBalise() {
        val p3 = SignalEssai.texteBalise("K HI de essai bloc non code", 512)
        val bits = IntArray(200) { it and 1 } + BlocP3.code(p3) + IntArray(40) { it and 1 }
        val audio = SignalEssai.audio(bits, 400.0, manchester = true, porteuseHz = 1_380.0, ebN0Db = 14.0, graine = 11)
        val b = decode(Profil.QO100, audio).trames.filter { it.format == "p3" }
        assertEquals(1, b.size)
        assertTrue(b[0].valide)
        assertArrayEquals(p3, b[0].donnees)
    }

    @Test fun funcube1200DopplerResiduel() {
        // Battery 8 123 mV, battery temperature 21 °C, sequence 4 242, a fitter message.
        val champs = ArrayList<Pair<Int, Long>>()
        champs += listOf(16 to 4100L, 16 to 4200L, 16 to 4300L, 16 to 310L, 16 to 8123L, 16 to 250L, 16 to 7L, 16 to 0L)
        champs += listOf(8 to 20L, 8 to 21L, 8 to 22L, 8 to 21L, 8 to 0L, 8 to 0L, 8 to 3L, 8 to 1L)
        champs += List(10) { 10 to 500L }
        champs += List(6) { 8 to 100L }
        champs += List(4) { 8 to 120L }
        champs += listOf(8 to 1L, 8 to 2L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L, 24 to 4242L, 6 to 0L, 5 to 0L, 1 to 0L)
        champs += List(12) { 1 to 0L }
        assertEquals(440, champs.sumOf { it.first })
        val t1 = SignalEssai.trameFuncube(14, champs, "Bonjour de SatMe".toByteArray())
        val t2 = SignalEssai.trameFuncube(1, champs)
        val bits = Ao40Fec.code(t1) + IntArray(600) { (it / 3) and 1 } + Ao40Fec.code(t2)
        // −420 Hz from the centre, sliding 15 Hz/s (what is left once the tracking corrects), Es/N0 5 dB.
        val audio = SignalEssai.audio(bits, 1200.0, manchester = false, porteuseHz = 1_080.0, deriveHzS = 15.0, ebN0Db = 5.0, graine = 7)
        val d = decode(Profil.FUNCUBE, audio)
        val fec = d.trames.reversed()
        assertEquals(d.etat.toString(), 2, fec.size)
        assertArrayEquals(t1, fec[0].donnees)
        assertArrayEquals(t2, fec[1].donnees)
        val f = Funcube.lis(fec[0].donnees)
        assertEquals("FUNcube-1 (AO-73)", f.satellite)
        assertEquals("FM1", f.genre)
        assertEquals("Bonjour de SatMe", f.message)
        val v = f.mesures.associate { it.nom to it.valeur }
        assertEquals("8123 mV", v["Tension batterie"])
        assertEquals("21 °C", v["Temp. batterie"])
        assertEquals("4242", v["Numéro de séquence"])
        assertEquals("oui", v["Antenne 0 déployée"])
        assertEquals("chien de garde", v["Cause de redémarrage"])
        assertEquals("WO1", Funcube.lis(fec[1].donnees).genre)
        assertTrue(!d.etat.manchester)
    }

    @Test fun rienDansLeBruit() {
        val rnd = java.util.Random(5)
        val audio = ShortArray(44_100 * 30) { (rnd.nextGaussian() * 3000).toInt().toShort() }
        val d = decode(Profil.QO100, audio)
        assertEquals(0, d.trames.count { it.format == "fec" || it.valide })
    }

    @Test fun temperatureDeLAmpli() {
        assertEquals(55.3, Funcube.tempPa(79), 1e-9)
        assertEquals(0.0, Funcube.tempPa(224), 1e-9)
        assertEquals(1.2, Funcube.tempPa(222), 1e-9)
    }
}
