/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.sdr.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * LEILA heard in the QO-100 sound: the siren the ground station lays over a
 * station sending stronger than the beacons.
 *
 * What is looked for, in the last 3 s: one note standing far above the rest
 * of the passband almost all the time, at a steady level, going up and down
 * over a few hundred hertz (not a carrier, not CW, not FT8 or RTTY), as a
 * continuous line or two tones in turn, and the same way again and again
 * (not a voice, whose level the AGC holds steady too). Tried on the
 * recordings of real passes (SSB, CW, FM, SSTV): none taken for LEILA.
 *
 * Nothing Android here: the phone and SatMe-PC feed it the same way.
 */
class DetecteurLeila(val rate: Int) {

    /** When LEILA was last heard (ms), 0 never. */
    @Volatile var dernierMs = 0L
        private set
    /** Where the siren sits in the sound (Hz), when last heard. */
    @Volatile var tonHz = 0
        private set

    /** Heard within the last [dureeMs]. */
    fun entendue(maintenant: Long, dureeMs: Long = 10_000L) = dernierMs > 0 && maintenant - dernierMs < dureeMs

    private val n = 1024
    private val pas = n / 2
    private val fenetreHann = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
    private val tampon = DoubleArray(n)
    private var plein = 0
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val hzParCase = rate.toDouble() / n
    private val caseBas = (BAS_HZ / hzParCase).toInt()
    private val caseHaut = (HAUT_HZ / hzParCase).toInt()

    /** The last 3 s, a trame per [pas] samples: the note (Hz, NaN when none stands out) and its level (dB). */
    private val nTrames = (FENETRE_S * rate / pas).toInt()
    private val tons = DoubleArray(nTrames) { Double.NaN }
    private val niveaux = DoubleArray(nTrames)
    private var pos = 0
    private var trames = 0L

    fun ecoute(pcm: ShortArray, nEch: Int, maintenant: Long = System.currentTimeMillis()) {
        for (i in 0 until nEch) {
            tampon[plein++] = pcm[i] / 32768.0
            if (plein == n) {
                trame()
                System.arraycopy(tampon, pas, tampon, 0, n - pas)
                plein = n - pas
                if (trames % 8 == 0L && trames >= nTrames && juge()) dernierMs = maintenant
            }
        }
    }

    private fun trame() {
        for (i in 0 until n) { re[i] = tampon[i] * fenetreHann[i]; im[i] = 0.0 }
        Fft.transform(re, im)
        val p = DoubleArray(caseHaut - caseBas + 1) { val k = caseBas + it; re[k] * re[k] + im[k] * im[k] }
        var iMax = 0
        for (i in p.indices) if (p[i] > p[iMax]) iMax = i
        val mediane = p.sortedArray()[p.size / 2]
        val ecart = 10 * log10((p[iMax] + 1e-20) / (mediane + 1e-20))
        // The peak's true place, between two bins (parabola on the logs).
        var hz = Double.NaN
        if (ecart >= ECART_DB && iMax > 0 && iMax < p.size - 1) {
            val a = log10(p[iMax - 1] + 1e-20); val b = log10(p[iMax] + 1e-20); val c = log10(p[iMax + 1] + 1e-20)
            val den = a - 2 * b + c
            val d = if (den < 0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
            hz = (caseBas + iMax + d) * hzParCase
        }
        tons[pos] = hz
        niveaux[pos] = 10 * log10(p[iMax] + 1e-20)
        pos = (pos + 1) % nTrames
        trames++
    }

    /** The last 3 s: a siren? */
    private fun juge(): Boolean {
        val f = ArrayList<Double>(nTrames); val l = ArrayList<Double>(nTrames)
        for (k in 0 until nTrames) {
            val i = (pos + k) % nTrames
            if (!tons[i].isNaN()) { f += tons[i]; l += niveaux[i] }
        }
        // There almost all the time: a voice or CW leaves gaps.
        if (f.size < nTrames * PRESENCE) return false
        // At a steady level: a siren is not keyed nor spoken.
        val moy = l.average()
        if (sqrt(l.sumOf { (it - moy) * (it - moy) } / l.size) > NIVEAU_STABLE_DB) return false
        // Over a few hundred hertz: not a carrier, not FT8, not RTTY.
        val tries = f.sorted()
        val etendue = tries[(tries.size * 0.9).toInt().coerceAtMost(tries.size - 1)] - tries[(tries.size * 0.1).toInt()]
        if (etendue < ETENDUE_MIN_HZ || etendue > ETENDUE_MAX_HZ) return false
        // By small steps or two tones in turn: most steps from one trame to the next are small.
        val sauts = (1 until f.size).map { abs(f[it] - f[it - 1]) }.sorted()
        if (sauts[sauts.size / 2] > etendue * 0.15) return false
        // Up and down again and again: the turns counted, each one going back by a third of the span at least.
        val seuil = etendue / 3
        var tours = 0; var sens = 0; var extreme = f[0]
        for (x in f) {
            when (sens) {
                0 -> if (abs(x - extreme) >= seuil) { sens = if (x > extreme) 1 else -1; extreme = x }
                1 -> if (x > extreme) extreme = x else if (extreme - x >= seuil) { sens = -1; extreme = x; tours++ }
                else -> if (x < extreme) extreme = x else if (x - extreme >= seuil) { sens = 1; extreme = x; tours++ }
            }
        }
        if (tours < TOURS_MIN) return false
        // A continuous line, not a voice's jumps (its level held steady by the AGC): most steps between two trames heard within 60 Hz.
        var proches = 0; var paires = 0
        for (k in 1 until nTrames) {
            val a = tons[(pos + k - 1) % nTrames]; val b = tons[(pos + k) % nTrames]
            if (!a.isNaN() && !b.isNaN()) { paires++; if (abs(b - a) <= 60.0) proches++ }
        }
        if (paires == 0 || proches < paires * CONTINUITE) return false
        // And again and again the same way: the line's autocorrelation, between 0.2 and 1.5 s, strong.
        val g = DoubleArray(nTrames)
        var prec = f.average()
        for (k in 0 until nTrames) { val v = tons[(pos + k) % nTrames]; if (!v.isNaN()) prec = v; g[k] = prec }
        val m = g.average()
        for (k in g.indices) g[k] -= m
        val energie = g.sumOf { it * it } + 1e-9
        val sPas = pas.toDouble() / rate
        var meilleure = 0.0
        for (decal in (0.2 / sPas).toInt()..minOf((1.5 / sPas).toInt(), nTrames - 1)) {
            var s = 0.0
            for (k in 0 until nTrames - decal) s += g[k] * g[k + decal]
            meilleure = maxOf(meilleure, s / energie)
        }
        if (meilleure < PERIODIQUE) return false
        tonHz = tries[tries.size / 2].toInt()
        return true
    }

    companion object {
        const val BAS_HZ = 250.0
        const val HAUT_HZ = 3000.0
        const val FENETRE_S = 3.0
        /** How far above the passband's median the note stands (dB). */
        const val ECART_DB = 18.0
        const val PRESENCE = 0.9
        const val CONTINUITE = 0.85
        const val PERIODIQUE = 0.5
        const val NIVEAU_STABLE_DB = 4.0
        const val ETENDUE_MIN_HZ = 250.0
        const val ETENDUE_MAX_HZ = 2700.0
        const val TOURS_MIN = 3
    }
}
