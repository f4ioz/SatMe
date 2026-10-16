/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

/**
 * The SSTV tuning aid: the sound's spectrum from [BASSE_HZ] to [HAUTE_HZ]
 * (where the 1200 Hz sync, the 1500–2300 Hz picture and the 1900 Hz
 * header live), the level, and where the sync pulses really sit. On SSB a
 * mistuned receiver slides every tone; past 250 Hz the header is no longer
 * recognised. Seeing the sync away from 1200 Hz tells which way to turn.
 *
 * The sync is measured on the instantaneous frequency, not the spectrum:
 * a 5–20 ms pulse a line is lost under the picture's energy in a spectrum,
 * but stands out as a short steady tone below the picture's black.
 */
class SstvAccord(private val rate: Int, private val periodeMs: Int = 250) {

    private val fenetre = (rate * FENETRE_S).toInt().coerceAtLeast(256)
    private val tampon = ShortArray(fenetre)
    private var ecrits = 0L
    private var depuisCalcul = 0
    private val parCalcul = rate * periodeMs / 1000

    /** dB per bin, [BASSE_HZ] to [HAUTE_HZ] by [PAS_HZ] (relative: 0 = the strongest). */
    @Volatile var spectre: FloatArray = FloatArray(0); private set
    /** Peak level of the last period, 0..1. */
    @Volatile var niveau: Float = 0f; private set
    private var crete = 0
    /** The sync pulses seen away from 1200 Hz (Hz), when they are regular enough to tell; null otherwise. */
    @Volatile var ecartSyncHz: Int? = null; private set

    private val nBins = ((HAUTE_HZ - BASSE_HZ) / PAS_HZ) + 1
    private val coefs = DoubleArray(nBins) { 2 * cos(2 * PI * (BASSE_HZ + it * PAS_HZ) / rate) }
    private val fen = DoubleArray(fenetre) { 0.5 - 0.5 * cos(2 * PI * it / (fenetre - 1)) }

    // The instantaneous frequency, and the short steady tones found in it.
    private val demod = Demodulator(rate)
    private var large = FloatArray(4096)
    private var etroit = FloatArray(4096)
    private var runN = 0
    private var runSomme = 0.0
    private var runMin = 0f
    private var runMax = 0f
    private val impT = LongArray(IMPULSIONS)
    private val impF = FloatArray(IMPULSIONS)
    private var impN = 0
    private val minRun = (rate * 0.004).toInt()
    private val maxRun = (rate * 0.025).toInt()

    fun ajoute(pcm: ShortArray, n: Int) {
        if (large.size < n) { large = FloatArray(n); etroit = FloatArray(n) }
        demod.process(pcm, n, large, etroit)
        for (i in 0 until n) {
            val v = pcm[i]
            tampon[(ecrits % fenetre).toInt()] = v
            ecrits++
            val a = if (v < 0) -v.toInt() else v.toInt()
            if (a > crete) crete = a
            impulsion(etroit[i])
        }
        depuisCalcul += n
        if (depuisCalcul >= parCalcul && ecrits >= fenetre) { depuisCalcul = 0; calcule() }
    }

    /** One sample of frequency: a run steady within 60 Hz, in the sync's range, 4 to 25 ms long, is a pulse. */
    private fun impulsion(f: Float) {
        val dedans = f in 950f..1480f && (runN == 0 || (max(runMax, f) - minOf(runMin, f)) < 60f)
        if (dedans) {
            if (runN == 0) { runMin = f; runMax = f }
            runN++; runSomme += f
            if (f < runMin) runMin = f
            if (f > runMax) runMax = f
            return
        }
        if (runN in minRun..maxRun) {
            val k = impN % IMPULSIONS
            impT[k] = ecrits; impF[k] = (runSomme / runN).toFloat(); impN++
        }
        runN = 0; runSomme = 0.0
        if (f in 950f..1480f) { runN = 1; runSomme = f.toDouble(); runMin = f; runMax = f }
    }

    private fun calcule() {
        niveau = crete / 32768f
        crete = 0
        val debut = ecrits - fenetre
        val x = DoubleArray(fenetre) { tampon[((debut + it) % fenetre).toInt()] * fen[it] }
        var max = 1e-12
        val p = DoubleArray(nBins)
        for (b in 0 until nBins) {
            val c = coefs[b]
            var s1 = 0.0; var s2 = 0.0
            for (v in x) { val s0 = v + c * s1 - s2; s2 = s1; s1 = s0 }
            p[b] = max(0.0, s1 * s1 + s2 * s2 - c * s1 * s2)
            if (p[b] > max) max = p[b]
        }
        spectre = FloatArray(nBins) { (10 * log10(max(p[it], max * 1e-6) / max)).toFloat() }
        val recentes = (0 until minOf(impN, IMPULSIONS)).filter { ecrits - impT[it] < rate * 10L }.map { impF[it] }
        ecartSyncHz = ecart(recentes)
    }

    companion object {
        const val BASSE_HZ = 900
        const val HAUTE_HZ = 2600
        const val PAS_HZ = 20
        const val FENETRE_S = 0.1
        private const val IMPULSIONS = 24

        /**
         * The tuning error from the pulses of the last 10 s: at least 4 of
         * them agreeing (median absolute deviation under 30 Hz); their median
         * less 1200 Hz. Null otherwise — voice and noise rarely agree.
         */
        fun ecart(frequences: List<Float>): Int? {
            if (frequences.size < 4) return null
            val s = frequences.sorted()
            val med = s[s.size / 2]
            val mad = frequences.map { abs(it - med) }.sorted()[frequences.size / 2]
            if (mad > 30f) return null
            return Math.round(med - 1200f)
        }
    }
}
