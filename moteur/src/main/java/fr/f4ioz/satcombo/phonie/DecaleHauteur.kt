/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import kotlin.math.PI
import kotlin.math.cos

/**
 * A voice's pitch moved, its length kept: pitch-synchronous overlap-add in
 * the time domain.
 *
 * One mark per period on the voiced parts (on the waveform's peak, period
 * after period, from [HauteurVoix]'s track), every 10 ms elsewhere. Around
 * each mark, two periods under a Hann window. The output's marks are laid
 * every period ÷ factor; each takes the input's grain whose mark is nearest
 * in time, so the length and the timing of the syllables stay as they were
 * while the periods come closer (higher) or farther apart (lower). The
 * spectral envelope travels inside each grain: the formants stay put, only
 * the pitch moves. The sum is divided by the windows' sum, so the level
 * does not follow the overlap.
 */
object DecaleHauteur {

    private class Marque(val pos: Int, val periode: Int, val voisee: Boolean)

    /**
     * [pcm] at [rate] with its pitch times [facteur] (of the time in seconds;
     * 0.5…2, 1 = untouched). [courbe]: the input's track, found when absent.
     */
    fun applique(pcm: FloatArray, rate: Int, courbe: HauteurVoix.Courbe? = null, facteur: (Double) -> Double): FloatArray {
        if (pcm.size < rate / 20) return pcm.copyOf()
        val c = courbe ?: HauteurVoix.suit(pcm, rate)
        val marques = marques(pcm, rate, c)
        if (marques.size < 2) return pcm.copyOf()
        val sortie = DoubleArray(pcm.size)
        val poids = DoubleArray(pcm.size)
        var k = 0
        var ts = marques[0].pos.toDouble()
        while (ts < pcm.size) {
            val t = ts.toInt()
            while (k + 1 < marques.size && abs(marques[k + 1].pos - t) <= abs(marques[k].pos - t)) k++
            val m = marques[k]
            val beta = if (m.voisee) facteur(t.toDouble() / rate).coerceIn(0.5, 2.0) else 1.0
            val l = m.periode
            for (j in -l until l) {
                val src = m.pos + j
                val dst = t + j
                if (src < 0 || src >= pcm.size || dst < 0 || dst >= pcm.size) continue
                val w = 0.5 * (1 + cos(PI * j / l))
                sortie[dst] += w * pcm[src]
                poids[dst] += w
            }
            ts += maxOf(1.0, l / beta)
        }
        return FloatArray(pcm.size) { i -> if (poids[i] > 1e-6) (sortie[i] / maxOf(poids[i], PLANCHER)).toFloat() else 0f }
    }

    /** A constant factor. */
    fun applique(pcm: FloatArray, rate: Int, facteur: Double): FloatArray = applique(pcm, rate, null) { facteur }

    /** Windows overlapping less than this are not raised further (no noise blown up between grains). */
    private const val PLANCHER = 0.5

    private fun abs(x: Int) = if (x < 0) -x else x

    private fun marques(x: FloatArray, rate: Int, c: HauteurVoix.Courbe): List<Marque> {
        val l = ArrayList<Marque>()
        val pasNonVoise = rate / 100
        var p = 0
        var precedenteVoisee = false
        while (p < x.size) {
            val f = c.a(p.toDouble() / rate)
            if (f <= 0f) {
                l += Marque(p, pasNonVoise, false)
                p += pasNonVoise
                precedenteVoisee = false
                continue
            }
            val periode = (rate / f).toInt().coerceAtLeast(8)
            // On the peak: in the whole period after the last mark, else ±¼ period around the expected place.
            val (de, a) = if (!precedenteVoisee) p to p + periode else (p - periode / 4) to (p + periode / 4)
            var m = de.coerceIn(0, x.size - 1)
            for (i in maxOf(0, de)..minOf(x.size - 1, a)) if (x[i] > x[m]) m = i
            if (l.isNotEmpty() && m <= l.last().pos) m = l.last().pos + maxOf(1, periode / 2)
            if (m >= x.size) break
            l += Marque(m, periode, true)
            precedenteVoisee = true
            p = m + periode
        }
        return l
    }
}
