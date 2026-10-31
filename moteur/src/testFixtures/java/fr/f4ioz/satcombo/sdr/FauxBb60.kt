/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A fake BB60 API: the calls it gets written down, a tone at [toneHz] from
 * the centre in its I/Q (at the rate the decimation gives), errors and an
 * unplugged device on demand. No library, no USB.
 */
class FauxBb60(
    var presents: Int = 1,
    var toneHz: Double = 100_000.0,
    var amplitude: Int = 8_000,
) : Bb60Natif {
    /** Every call, as "nom(arguments)". */
    val appels = CopyOnWriteArrayList<String>()
    /** Unplugged: every call on a handle answers bbDeviceConnectionErr, opening finds nothing. */
    @Volatile var debranche = false
    /** The next read answers this status once (0: none). */
    @Volatile var prochaineLecture = 0
    /** Every read says the API dropped samples. */
    @Volatile var perte = false
    @Volatile var temperature = 41.5f
    @Volatile var tension = 5.05f
    @Volatile var ouverts = 0
    @Volatile var lectures = 0
    private var prochain = 1
    private var decimation = 1
    private var phase = 0.0
    private val lies = HashSet<Int>()

    private fun note(s: String) { appels += s }
    private fun sur(h: Int): Int = if (debranche || h !in lies) Bb60.CONNEXION else 0

    override fun versionApi() = "5.0.12-faux"
    override fun texteStatut(statut: Int) = "statut $statut"
    override fun appareils(): List<Pair<Int, Int>> = if (debranche) emptyList() else (0 until presents).map { 1_234_567 + it to Bb60.BB60D }

    @Synchronized override fun ouvre(serie: Int): Int {
        note("ouvre($serie)")
        if (debranche || presents == 0) throw Bb60Erreur(Bb60.NON_OUVERT, "Device not open")
        val h = prochain++
        lies += h; ouverts++
        return h
    }
    @Synchronized override fun ferme(h: Int): Int { note("ferme($h)"); lies -= h; return 0 }
    override fun typeAppareil(h: Int) = Bb60.BB60D
    override fun serie(h: Int) = 1_234_567L
    override fun firmware(h: Int) = 8
    override fun diagnostics(h: Int): FloatArray {
        if (sur(h) != 0) throw Bb60Erreur(Bb60.CONNEXION, "connexion")
        return floatArrayOf(temperature, tension, 0.9f)
    }
    override fun niveauReference(h: Int, dBm: Double): Int { note("reference($dBm)"); return sur(h) }
    override fun gainAttenuation(h: Int, gain: Int, attenuation: Int): Int { note("gainAtten($gain,$attenuation)"); return sur(h) }
    override fun centreIq(h: Int, hz: Double): Int { note("centre(${hz.toLong()})"); return sur(h) }
    override fun configureIq(h: Int, decimation: Int, bandeHz: Double): Int {
        note("iq($decimation,${bandeHz.toLong()})"); this.decimation = decimation; return sur(h)
    }
    override fun iq16(h: Int): Int { note("iq16"); return sur(h) }
    override fun demarreIq(h: Int): Int { note("initie"); return sur(h) }
    override fun arrete(h: Int): Int { note("abort"); return 0 }
    override fun parametresIq(h: Int) = doubleArrayOf(Bb60.DEBIT_MAX / decimation, Bb60.BANDE_MAX.getValue(decimation))

    override fun lisIq(h: Int, dest: ShortArray, paires: Int, lecture: Bb60Natif.Lecture): Int {
        lectures++
        sur(h).let { if (it != 0) return it }
        val dite = prochaineLecture
        if (dite != 0) { prochaineLecture = 0; if (dite < 0) return dite }
        val fs = Bb60.DEBIT_MAX / decimation
        val w = 2 * PI * toneHz / fs
        for (k in 0 until paires) {
            dest[2 * k] = (amplitude * cos(phase)).roundToInt().toShort()
            dest[2 * k + 1] = (amplitude * sin(phase)).roundToInt().toShort()
            phase += w
        }
        phase %= 2 * PI
        lecture.perte = perte; lecture.restants = 0
        val t = System.currentTimeMillis()
        lecture.secondes = (t / 1000).toInt(); lecture.nanos = ((t % 1000) * 1_000_000).toInt()
        // About the device's pace, not faster: the tests read in real time.
        Thread.sleep(2)
        return maxOf(dite, 0)
    }
}
