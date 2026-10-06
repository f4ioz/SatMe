/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where something was heard in a recording — a voice, a carrier, an SSTV
 * picture — without anyone having logged it. Not the loudness: on an FM
 * downlink with the squelch open the hiss is loudest, and a signal quiets it.
 * What changes is the share of the voice band (300–2800 Hz) against the
 * highs (above 4 kHz): hiss lives in the highs, voices and tones below.
 * The threshold follows each recording (its median, most of a pass being
 * hiss); a closed squelch (silence) is never activity.
 */
object ActiviteSon {
    const val FENETRE_MS = 250L
    /** Above the recording's usual share by this much (dB): something is there. */
    const val SEUIL_DB = 6.0
    /** Shorter than this, a crackle; closer than that, the same activity. */
    const val DUREE_MIN_MS = 1_000L
    const val JOINT_MS = 2_000L
    /** Below this level (dBFS), silence: a closed squelch. */
    const val SILENCE_DBFS = -60.0

    /** One window of the recording: its level and the voice band's share. */
    data class Fenetre(val tMs: Long, val niveauDb: Double, val partVoixDb: Double)

    /** A biquad (RBJ), enough to split the bands. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun f(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y; return y
        }
        companion object {
            private fun fait(fs: Int, f0: Double, haut: Boolean): Biquad {
                val w = 2 * PI * f0 / fs; val al = sin(w) / (2 * 0.7071); val c = cos(w); val a0 = 1 + al
                return if (haut) Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
                else Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }
            fun passeHaut(fs: Int, f0: Double) = fait(fs, f0, true)
            fun passeBas(fs: Int, f0: Double) = fait(fs, f0, false)
        }
    }

    /** Measures, window by window, as the samples come (any rate). */
    class Mesure(private val fs: Int) {
        private val v1 = Biquad.passeHaut(fs, 300.0); private val v2 = Biquad.passeBas(fs, 2800.0)
        private val h1 = Biquad.passeHaut(fs, 4000.0); private val h2 = Biquad.passeHaut(fs, 4000.0)
        private val parFenetre = (fs * FENETRE_MS / 1000).toInt()
        private var n = 0; private var eT = 0.0; private var eV = 0.0; private var eH = 0.0
        private var k = 0L
        val fenetres = ArrayList<Fenetre>()
        fun ajoute(pcm: ShortArray, count: Int = pcm.size) {
            for (i in 0 until count) {
                val x = pcm[i] / 32768.0
                val v = v2.f(v1.f(x)); val h = h2.f(h1.f(x))
                eT += x * x; eV += v * v; eH += h * h
                if (++n == parFenetre) {
                    fenetres += Fenetre(k * FENETRE_MS, 10 * log10(eT / n + 1e-12), 10 * log10((eV + 1e-12) / (eH + 1e-12)))
                    k++; n = 0; eT = 0.0; eV = 0.0; eH = 0.0
                }
            }
        }
    }

    /** The stretches (ms from the file's start) where something was heard. */
    fun plages(f: List<Fenetre>): List<LongRange> {
        val sonores = f.filter { it.niveauDb > SILENCE_DBFS }
        if (sonores.size < 8) return emptyList()
        val seuil = seuil(sonores.map { it.partVoixDb })
        val brutes = ArrayList<LongRange>()
        var debut: Long? = null
        for (w in f) {
            val actif = w.niveauDb > SILENCE_DBFS && w.partVoixDb > seuil
            if (actif && debut == null) debut = w.tMs
            if (!actif && debut != null) { brutes += debut until w.tMs; debut = null }
        }
        debut?.let { brutes += it until (f.last().tMs + FENETRE_MS) }
        // Close ones joined, then crackles left out.
        val jointes = ArrayList<LongRange>()
        for (p in brutes) {
            val d = jointes.lastOrNull()
            if (d != null && p.first - d.last <= JOINT_MS) jointes[jointes.size - 1] = d.first..p.last else jointes += p
        }
        return jointes.filter { it.last - it.first >= DUREE_MIN_MS }
    }

    /**
     * Where hiss ends and activity begins. Two groups with a real valley
     * between them (an SSTV pass: pictures most of the time, hiss between
     * them) — the threshold in the valley, whichever is the larger. One group
     * with a tail (a linear transponder: hiss, voices now and then) —
     * [SEUIL_DB] above the median.
     */
    fun seuil(v: List<Double>): Double {
        // Without the extremes (the very start, a click): 2 % each side.
        val tous = v.sorted()
        val tri = tous.subList(tous.size * 2 / 100, tous.size - tous.size * 2 / 100).ifEmpty { tous }
        val mediane = tri[tri.size / 2]
        val ecart = tri.map { abs(it - mediane) }.sorted()[tri.size / 2]
        val parDefaut = mediane + maxOf(SEUIL_DB, 3 * ecart)
        // Histogram by half dB, smoothed over three boxes.
        val bas = tri.first(); val pas = 0.5
        val n = ((tri.last() - bas) / pas).toInt() + 1
        if (n < 6) return parDefaut
        val h = DoubleArray(n); tri.forEach { h[((it - bas) / pas).toInt().coerceIn(0, n - 1)] += 1.0 }
        val l = DoubleArray(n) { i -> (h[maxOf(0, i - 1)] + h[i] + h[minOf(n - 1, i + 1)]) / 3 }
        // Otsu: the cut that best separates two groups.
        var meilleur = -1.0; var coupe = -1
        val total = tri.size.toDouble(); val somme = tri.sum()
        var nB = 0.0; var sB = 0.0
        for (i in 0 until n - 1) {
            nB += h[i]; sB += h[i] * (bas + (i + 0.5) * pas)
            val nH = total - nB; if (nB < 1 || nH < 1) continue
            val mB = sB / nB; val mH = (somme - sB) / nH
            val inter = nB * nH * (mB - mH) * (mB - mH)
            if (inter > meilleur) { meilleur = inter; coupe = i }
        }
        if (coupe < 0) return parDefaut
        val coupeDb = bas + (coupe + 1) * pas
        val bass = tri.filter { it < coupeDb }; val hauts = tri.filter { it >= coupeDb }
        if (bass.size < tri.size * 0.08 || hauts.size < tri.size * 0.08) return parDefaut
        if (hauts.average() - bass.average() < 2.0) return parDefaut
        // A real valley: lower than half the smaller peak on either side.
        val piedBas = (0..coupe).maxOf { l[it] }; val piedHaut = (coupe + 1 until n).maxOf { l[it] }
        val iBas = (0..coupe).maxByOrNull { l[it] }!!; val iHaut = (coupe + 1 until n).maxByOrNull { l[it] }!!
        val creux = (iBas..iHaut).minOf { l[it] }
        return if (creux <= 0.5 * minOf(piedBas, piedHaut)) minOf(coupeDb, parDefaut) else parDefaut
    }

    /** Kept next to the recording ("<name>.activite"): one stretch per line, ms. */
    fun ecrit(p: List<LongRange>): String = p.joinToString("") { "${it.first}\t${it.last}\n" }
    fun lit(t: String): List<LongRange> = t.lines().mapNotNull { l ->
        val c = l.split('\t'); if (c.size < 2) null else runCatching { c[0].toLong()..c[1].toLong() }.getOrNull()
    }

    /** The level's RMS of a window, for tests: a sine of [hz] at [ampl] (0..1) mixed with white noise. */
    fun signalEssai(fs: Int, ms: Long, hz: Double, ampl: Double, bruit: Double, graine: Int = 1): ShortArray {
        val r = java.util.Random(graine.toLong())
        return ShortArray((fs * ms / 1000).toInt()) { i ->
            val s = ampl * sin(2 * PI * hz * i / fs) + bruit * (r.nextGaussian() / 3)
            (s.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    @Suppress("unused") private fun rms(a: ShortArray) = sqrt(a.sumOf { (it / 32768.0) * (it / 32768.0) } / a.size)
}
