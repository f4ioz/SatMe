/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.Ft8Banc
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A transponder whose every signal wanders together (an LNB's oscillator:
 * ±30 Hz within a second, as on 21 October), the BPSK beacon with them:
 * FT8 decodes nothing as heard, everything once the stream is locked on
 * the beacon.
 */
class VerrouBaliseTest {

    private val rate = 24.0 * 12_000          // the chain's audio comes out at 12 kHz
    private val baliseHz = 100_000.0          // the beacon, from the stream's centre
    private val canalHz = -10_000.0           // the FT8 dial, from the centre (within the first stage)
    private val audios = listOf(600.0, 1_000.0, 1_400.0, 1_800.0, 2_200.0)
    private val appelants = listOf("F4IOZ", "G0ABC", "DL1XYZ", "EA4AB", "ON4KST")
    private val avanceS = 3.0                 // stream before the slot, for the lock to settle

    /** The LNB's quick wandering, Hz, at 100 points a second: smoothed noise, [rms] Hz. */
    private fun gigue(rms: Double, secondes: Double, graine: Long): DoubleArray {
        val r = java.util.Random(graine)
        val n = (secondes * 100).toInt() + 200
        val brut = DoubleArray(n + 60) { r.nextGaussian() }
        val lisse = DoubleArray(n) { i -> (0 until 60).sumOf { k -> brut[i + k] * (1 - cos(2 * PI * k / 59)) } }
        val m = lisse.average(); val s = sqrt(lisse.sumOf { (it - m) * (it - m) } / n)
        return DoubleArray(n) { (lisse[it] - m) / s * rms }
    }

    /** Runs the chain over the synthetic stream; returns the slot's audio (12 kHz). */
    private fun ecoute(gigueRms: Double, verrou: Boolean, erreurBaliseHz: Double = 120.0): FloatArray {
        val total = avanceS + 15.0
        val j = gigue(gigueRms, total, 5)
        val alea = java.util.Random(11)
        val trajets = appelants.mapIndexed { i, a ->
            Ft8Signal.trajectoire(Ft8Banc.tons(Ft8Banc.message("CQ", a, "JN18")), Ft8Signal.FT8, audios[i])
        }
        val sigma = 0.02
        val aFt8 = sigma * sqrt(10.0 * 2 * 2_500 / rate)          // +10 dB... in 2500 Hz
        val aBalise = sigma * sqrt(3_000.0 * 2 * 2_500 / rate)
        val bitsBalise = IntArray(((total + 1) * 400).toInt()) { if (alea.nextBoolean()) 1 else -1 }
        val chaine = RxChain(rate)
        chaine.mode = RxMode.USB
        chaine.offsetHz = canalHz
        chaine.verrou.actif = verrou
        chaine.verrou.decalageHz = baliseHz - erreurBaliseHz
        val bloc = 14_400
        val iq = ShortArray(2 * bloc)
        val out = ShortArray(chaine.maxAudio(4 * bloc))
        val audio = ArrayList<Float>()
        val phases = DoubleArray(audios.size)
        var phaseJ = 0.0; var phaseB = 0.0
        val nTotal = (total * rate).toInt()
        var i = 0
        while (i < nTotal) {
            val n = minOf(bloc, nTotal - i)
            for (k in 0 until n) {
                val t = (i + k) / rate
                val fj = j[(t * 100).toInt()]
                phaseJ += 2 * PI * fj / rate
                var re = 0.0; var im = 0.0
                // The BPSK beacon.
                phaseB += 2 * PI * baliseHz / rate
                val bit = bitsBalise[(t * 400).toInt()]
                re += aBalise * bit * cos(phaseB); im += aBalise * bit * sin(phaseB)
                // The stations, from 0.5 s into the slot.
                val ts = t - avanceS - 0.5
                for (s in trajets.indices) {
                    val idx = (ts * Ft8Signal.FT8.cadenceHz).toInt()
                    if (idx < 0 || idx >= trajets[s].size) continue
                    phases[s] += 2 * PI * (canalHz + trajets[s][idx]) / rate
                    re += aFt8 * cos(phases[s]); im += aFt8 * sin(phases[s])
                }
                // Everything wanders together.
                val c = cos(phaseJ); val sn = sin(phaseJ)
                val rr = re * c - im * sn + sigma * alea.nextGaussian()
                val ii = re * sn + im * c + sigma * alea.nextGaussian()
                iq[2 * k] = (rr * 32_768).toInt().coerceIn(-32_768, 32_767).toShort()
                iq[2 * k + 1] = (ii * 32_768).toInt().coerceIn(-32_768, 32_767).toShort()
            }
            val p = chaine.process16(iq, 2 * n, out)
            if (i / rate >= avanceS) for (k in 0 until p) audio.add(out[k] / 32_768f)
            i += n
        }
        if (verrou) {
            assertTrue("verrouillé", chaine.verrou.verrouille)
            val g = chaine.verrou.gigueHz
            assertTrue("gigue ôtée $g", if (gigueRms > 0) g > gigueRms / 2 && g < gigueRms * 1.5 else g < 3.0)
            assertTrue("écart lent ${chaine.verrou.ecartLentHz}", kotlin.math.abs(chaine.verrou.ecartLentHz - erreurBaliseHz) < 20)
        }
        return audio.toFloatArray()
    }

    private fun lus(audio: FloatArray) = Ft8Decodeur.decode(audio, 12_000.0, profondeur = Ft8Decodeur.Profondeur.PROFONDE)
        .map { it.message.brut }.toSet()

    @Test
    fun une_gigue_commune_empeche_ft8_le_verrou_la_rend() {
        val attendus = appelants.map { "CQ $it JN18" }.toSet()
        val sans = lus(ecoute(15.0, verrou = false))
        assertTrue("sans verrou : $sans", sans.size <= 1)
        val avec = lus(ecoute(15.0, verrou = true))
        println("Gigue commune 15 Hz rms : sans verrou ${sans.size}/5, avec verrou ${avec.size}/5")
        assertTrue("avec verrou : $avec", avec.size >= 4 && attendus.containsAll(avec))
    }

    @Test
    fun sans_gigue_le_verrou_ne_gate_rien() {
        val attendus = appelants.map { "CQ $it JN18" }.toSet()
        assertTrue(lus(ecoute(0.0, verrou = false)) == attendus)
        assertTrue(lus(ecoute(0.0, verrou = true)) == attendus)
    }
}
