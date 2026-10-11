/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import fr.f4ioz.satcombo.sdr.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Audio (baseband) spectrum analyser.
 *
 * Not the SDR panadapter, which looks at a radio band: this looks at the sound
 * going to the MP3 file. It answers "is anything really coming in?" during a
 * pass, and shows at a glance whether the modulation is centred, noise is
 * swamping the signal, or SSTV tones are present.
 *
 * Pure Kotlin, no Android import, so unit-testable. Reuses the SDR chain's
 * [Fft].
 *
 * The signal is real, so only the first half of the spectrum carries
 * information; bins above Nyquist mirror the first ones and are ignored.
 *
 * @param rate capture sample rate (44 100 Hz, or 16 000 Hz over Bluetooth).
 * @param taille analysis window length, power of two.
 * @param nbBandes number of bars shown.
 * @param freqMaxHz top of the scale. 3 500 Hz covers voice, SSB and the three
 *        SSTV tones (1 200, 1 500, 2 300 Hz); going higher only squashes what
 *        we want to read.
 * @param plancherDb level of an empty bar.
 */
class AnalyseurSpectre(
    val rate: Int = 44_100,
    val taille: Int = 1024,
    val nbBandes: Int = 32,
    val freqMaxHz: Double = 3_500.0,
    val plancherDb: Double = -78.0
) {

    init {
        require(taille > 1 && taille and (taille - 1) == 0) { "taille non puissance de deux : $taille" }
        require(nbBandes in 1..(taille / 2 - 1)) { "nombre de bandes hors limites : $nbBandes" }
        require(plancherDb < 0.0) { "plancher positif : $plancherDb" }
    }

    private val re = DoubleArray(taille)
    private val im = DoubleArray(taille)

    /** Hann window: without it a carrier between two bins leaks over the whole
     *  spectrum and the display becomes a flat wall. */
    private val fen = DoubleArray(taille) { 0.5 - 0.5 * cos(2.0 * PI * it / (taille - 1)) }

    /**
     * Band edges, in bin numbers. Bin zero is skipped: it is DC, which every
     * input stage carries a little, and would make the first bar always full.
     */
    private val bornes = IntArray(nbBandes + 1).also { b ->
        val haut = ((freqMaxHz * taille / rate).toInt()).coerceIn(nbBandes + 1, taille / 2)
        for (i in 0..nbBandes) b[i] = 1 + ((haut - 1).toLong() * i / nbBandes).toInt()
    }

    private var rempli = 0
    private var creteBrute = 0.0

    /** Level of each band, 0 (floor) to 1 (full scale). */
    val bandes = FloatArray(nbBandes)

    /** Signal peak over the last window, 0 to 1. Used as a VU meter. */
    var crete: Float = 0f
        private set

    /** Windows computed since the last [reset]. */
    var trames: Long = 0
        private set

    /** Centre frequency of band [i], Hz. */
    fun centreHz(i: Int): Double = (bornes[i] + bornes[i + 1]) * 0.5 * rate / taille

    /** Index of the strongest band, or −1 when the spectrum is empty. */
    fun bandeLaPlusForte(): Int {
        var meilleur = -1
        var val0 = 0f
        for (i in bandes.indices) if (bandes[i] > val0) { val0 = bandes[i]; meilleur = i }
        return meilleur
    }

    fun reset() {
        rempli = 0; creteBrute = 0.0; crete = 0f; trames = 0
        bandes.fill(0f)
    }

    /**
     * Pushes [n] signed samples. True when at least one window was just
     * computed, in which case [bandes] and [crete] are up to date.
     */
    fun pousser(pcm: ShortArray, n: Int): Boolean {
        var fait = false
        var k = 0
        val fin = minOf(n, pcm.size)
        while (k < fin) {
            val prend = minOf(taille - rempli, fin - k)
            for (t in 0 until prend) {
                val e = pcm[k + t] / 32768.0
                re[rempli + t] = e
                val a = abs(e)
                if (a > creteBrute) creteBrute = a
            }
            rempli += prend
            k += prend
            if (rempli == taille) { calculer(); rempli = 0; fait = true }
        }
        return fait
    }

    private fun calculer() {
        for (i in 0 until taille) { re[i] *= fen[i]; im[i] = 0.0 }
        Fft.transform(re, im)
        // Two factors: half a real signal's energy is in the negative
        // frequencies not shown, and the Hann window has a mean gain of 0.5.
        // A full-scale sine thus reads 0 dB.
        val norm = 2.0 / (taille * 0.5)
        for (b in 0 until nbBandes) {
            var max = 0.0
            for (i in bornes[b] until bornes[b + 1]) {
                val m = sqrt(re[i] * re[i] + im[i] * im[i]) * norm
                if (m > max) max = m
            }
            val db = if (max <= 1e-9) -140.0 else 20.0 * log10(max)
            bandes[b] = ((db - plancherDb) / -plancherDb).toFloat().coerceIn(0f, 1f)
        }
        crete = creteBrute.toFloat().coerceIn(0f, 1f)
        creteBrute = 0.0
        trames++
    }
}
