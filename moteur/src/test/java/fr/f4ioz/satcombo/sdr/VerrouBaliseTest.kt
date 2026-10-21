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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A transponder whose every signal wanders together (an LNB's oscillator,
 * as on 21 October: a few hertz a second of drift, ±10–25 Hz within a
 * second), the BPSK beacon with them, at the rate the receiver runs
 * (1 058 400 S/s) and the beacon as strong as heard (17 dB in 2.4 kHz).
 */
class VerrouBaliseTest {

    private val rate = Dsp.RTL_RATE.toDouble()
    private val baliseHz = 100_000.0          // the beacon, from the stream's centre
    private val canalHz = -10_000.0           // the FT8 dial, from the centre
    private val audios = listOf(600.0, 1_000.0, 1_400.0, 1_800.0, 2_200.0)
    private val appelants = listOf("F4IOZ", "G0ABC", "DL1XYZ", "EA4AB", "ON4KST")
    private val avanceS = 4.0                 // stream before the slot, for the lock to settle

    /** The LNB's quick wandering, Hz, 100 points a second: smoothed noise, [rms] Hz. */
    private fun gigue(rms: Double, secondes: Double, graine: Long): DoubleArray {
        val r = java.util.Random(graine)
        val n = (secondes * 100).toInt() + 200
        val brut = DoubleArray(n + 60) { r.nextGaussian() }
        val lisse = DoubleArray(n) { i -> (0 until 60).sumOf { k -> brut[i + k] * (1 - cos(2 * PI * k / 59)) } }
        val m = lisse.average(); val s = sqrt(lisse.sumOf { (it - m) * (it - m) } / n)
        return DoubleArray(n) { if (rms == 0.0) 0.0 else (lisse[it] - m) / s * rms }
    }

    class Ecoute(val audio: FloatArray, val chaine: RxChain)

    /** Runs the chain over the synthetic stream; returns the slot's audio (44.1 kHz). */
    private fun ecoute(gigueRms: Double, verrou: Boolean, balise: Boolean = true, deriveHzS: Double = 5.0,
                       sauteS: Double = -1.0): Ecoute {
        val total = avanceS + 15.0
        val j = gigue(gigueRms, total, 5)
        val alea = java.util.Random(11)
        val trajets = appelants.mapIndexed { i, a ->
            Ft8Signal.trajectoire(Ft8Banc.tons(Ft8Banc.message("CQ", a, "JN18")), Ft8Signal.FT8, audios[i])
        }
        val sigma = 0.02
        val bruit2400 = 2 * sigma * sigma * 2_400 / rate
        val aFt8 = sqrt(bruit2400 * 2_500 / 2_400 * 10.0.pow(0.5))       // +5 dB in 2500 Hz
        val aBalise = if (balise) sqrt(bruit2400 * 10.0.pow(1.7)) else 0.0  // 17 dB in 2.4 kHz
        val bits = IntArray(((total + 1) * 400).toInt()) { if (alea.nextBoolean()) 1 else -1 }
        val chaine = RxChain(rate)
        chaine.mode = RxMode.USB
        chaine.offsetHz = canalHz
        chaine.verrou.actif = verrou
        chaine.verrou.decalageHz = baliseHz - 150.0
        val bloc = 52_920
        val iq = ShortArray(2 * bloc)
        val out = ShortArray(chaine.maxAudio(4 * bloc))
        val audio = ArrayList<Float>()
        val phases = DoubleArray(audios.size)
        var phaseJ = 0.0; var phaseB = 0.0
        val nTotal = (total * rate).toInt()
        val pasFt8 = Ft8Signal.FT8.cadenceHz / rate
        var i = 0
        // The stream's clock: the slot starts at avanceS on a 15 s boundary.
        val debutTranche = 1_792_603_530_000L
        chaine.verrou.horloge = { debutTranche + ((i / rate - avanceS) * 1000).toLong() }
        while (i < nTotal) {
            val n = minOf(bloc, nTotal - i)
            // The calibration moved once (the dongle retuned by 300 Hz, the expected place with it).
            val decale = if (sauteS >= 0 && i / rate >= sauteS) 300.0 else 0.0
            chaine.verrou.decalageHz = baliseHz - 150.0 + decale
            for (k in 0 until n) {
                val t = (i + k) / rate
                val fj = j[(t * 100).toInt()] + deriveHzS * t - decale
                phaseJ += 2 * PI * fj / rate
                var re = 0.0; var im = 0.0
                // The BPSK beacon, its transitions smoothed over a symbol.
                phaseB += 2 * PI * baliseHz / rate
                val u = t * 400; val s = u.toInt(); val f = u - s
                val amp = bits[maxOf(0, s - 1)] + (bits[s] - bits[maxOf(0, s - 1)]) * (1 - cos(PI * f)) / 2
                re += aBalise * amp * cos(phaseB); im += aBalise * amp * sin(phaseB)
                val ts = t - avanceS - 0.5
                if (ts >= 0) for (st in trajets.indices) {
                    val idx = (ts * Ft8Signal.FT8.cadenceHz).toInt()
                    if (idx >= trajets[st].size) continue
                    phases[st] += 2 * PI * (canalHz + trajets[st][idx]) / rate
                    re += aFt8 * cos(phases[st]); im += aFt8 * sin(phases[st])
                }
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
        assertTrue(pasFt8 > 0)
        return Ecoute(audio.toFloatArray(), chaine)
    }

    private fun Double.pow(x: Double) = Math.pow(this, x)

    private fun lus(e: Ecoute) = Ft8Decodeur.decode(e.audio, rate / 24, profondeur = Ft8Decodeur.Profondeur.PROFONDE)
        .map { it.message.brut }.toSet()

    @Test
    fun une_gigue_commune_empeche_ft8_le_verrou_la_rend() {
        val attendus = appelants.map { "CQ $it JN18" }.toSet()
        val sans = lus(ecoute(12.0, verrou = false))
        val e = ecoute(12.0, verrou = true)
        val avec = lus(e)
        val v = e.chaine.verrou
        println("Gigue commune 12 Hz rms, dérive 5 Hz/s : sans verrou ${sans.size}/5, avec ${avec.size}/5, ôté ${"%.1f".format(v.gigueHz)} Hz rms")
        assertTrue("sans verrou : $sans", sans.size <= 1)
        assertTrue("avec verrou : $avec", avec.size >= 4 && attendus.containsAll(avec))
        assertTrue(v.verrouille)
        // What is taken out is the jitter, not more (live, a loop gone wrong took out 970 Hz rms).
        assertTrue("ôté ${v.gigueHz}", v.gigueHz in 6.0..20.0)
        assertTrue("borne", kotlin.math.abs(v.correctionHz) <= VerrouBalise.BORNE_HZ)
    }

    @Test
    fun sans_gigue_le_verrou_ne_gate_rien() {
        val attendus = appelants.map { "CQ $it JN18" }.toSet()
        val e = ecoute(0.0, verrou = true, deriveHzS = 0.0)
        assertEquals(attendus, lus(e))
        assertTrue("ôté ${e.chaine.verrou.gigueHz}", e.chaine.verrou.gigueHz < 2.0)
    }

    @Test
    fun sans_balise_rien_nest_touche() {
        val e = ecoute(0.0, verrou = true, balise = false, deriveHzS = 0.0)
        assertTrue(!e.chaine.verrou.verrouille)
        assertEquals(0.0, e.chaine.verrou.correctionHz, 0.0)
        assertEquals(5, lus(e).size)
    }

    @Test
    fun un_calage_qui_bouge_est_repris_sans_a_coup() {
        // The calibration moves 1 s into the stream: the lock lets go, finds the beacon again, holds.
        val e = ecoute(12.0, verrou = true, sauteS = 1.0)
        assertTrue(e.chaine.verrou.verrouille)
        assertTrue(lus(e).size >= 4)
    }
}
