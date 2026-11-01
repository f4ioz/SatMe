/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.visu

import fr.f4ioz.satcombo.sdr.Fft
import fr.f4ioz.satcombo.visu.SondeSignal.Repere
import fr.f4ioz.satcombo.visu.SondeSignal.Vue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a [SondeSignal] held at one reading, with its figures worked out:
 * the constellation's MER and EVM, the eye's opening, the two tones and
 * their shift, the bit rate measured and the clock's error. [json] is what
 * the page draws (compact: the clouds as bytes in base64).
 */
class VisuSignal(
    val cle: String,
    val nom: String,
    val vues: Set<Vue>,
    val debit: Double,
    val fs: Double,
    val unite: String,
    val lsbDabord: Boolean,
    val groupe: Int,
    val ordre: Int,
    val lecture: String,
    /** I, Q pairs, the newest last. */
    val points: FloatArray,
    /** Phase (0..1, the decision at 0.5), level (±1) pairs. */
    val oeil: FloatArray,
    val niveaux: FloatArray,
    val instants: DoubleArray,
    val bits: ByteArray,
    /** The absolute number of [bits]' first. */
    val premierBit: Long,
    val reperes: List<Repere>,
    val trame: Repere?,
    val spectre: Spectre?,
    val ecartAttenduHz: Double,
    /** All writes so far (changes while the demodulator runs) and when they last grew. */
    val seq: Long,
    val vivantMs: Long,
) {
    /** A distribution over a frequency or a level: [valeurs] from [de], [pas] apart (bin centres). */
    class Spectre(val de: Double, val pas: Double, val valeurs: FloatArray, val mesure: Boolean = false)

    /** The two tones (or levels): low, high, in the spectrum's unit. */
    data class Tons(val bas: Double, val haut: Double) { val ecart: Double get() = haut - bas }

    /** MER and EVM of the constellation (dB, %); NaN without enough points. */
    val qualite: Pair<Double, Double> by lazy { AnalyseSignal.constellation(points, ordre) }
    /** The eye at the decisions: opening (0..1) and Q (dB). */
    val ouverture: Pair<Double, Double> by lazy { AnalyseSignal.oeil(niveaux, if (spectre == null) tons?.let { (it.bas + it.haut) / 2 } else null) }
    val distribution: Spectre? by lazy { if (Vue.TONS !in vues) null else spectre ?: AnalyseSignal.histogramme(niveaux) }
    val tons: Tons? by lazy { distribution?.let { AnalyseSignal.tons(it, if (spectre != null && !spectre.mesure) ecartAttenduHz else 0.0) } }
    val debitMesure: Double by lazy { AnalyseSignal.debit(instants, fs) }

    fun json(maintenant: Long = System.currentTimeMillis()): JSONObject {
        val o = JSONObject().put("cle", cle).put("nom", nom).put("vues", JSONArray(vues.map { it.name }))
            .put("debit", arrondi(debit, 2)).put("unite", unite).put("lsb", lsbDabord).put("groupe", groupe).put("ordre", ordre)
            .put("lecture", lecture).put("seq", seq).put("depuis", if (vivantMs > 0) maintenant - vivantMs else -1)
        if (!debitMesure.isNaN()) o.put("debitMesure", arrondi(debitMesure, 2)).put("ppm", if (debit > 0) Math.round((debitMesure / debit - 1) * 1e6) else 0)
        val q = JSONObject()
        if (Vue.CONSTELLATION in vues) {
            o.put("points", octets(points, 64f, false))
            if (!qualite.first.isNaN()) q.put("mer", arrondi(qualite.first, 1)).put("evm", arrondi(qualite.second, 1))
        }
        if (Vue.OEIL in vues) {
            o.put("oeil", octets(oeil, 48f, true))
            if (!ouverture.first.isNaN()) q.put("ouverture", Math.round(ouverture.first * 100)).put("q", arrondi(ouverture.second, 1))
        }
        o.put("qualite", q)
        distribution?.let { d ->
            val mx = d.valeurs.maxOrNull()?.takeIf { it > 0 } ?: 1f
            val t = JSONObject().put("de", arrondi(d.de, 3)).put("pas", arrondi(d.pas, 4))
                .put("v", JSONArray(d.valeurs.map { (it / mx * 1000).roundToInt() })).put("genre", if (spectre == null) "niveaux" else if (spectre.mesure) "mesures" else "spectre")
            tons?.let { t.put("bas", arrondi(it.bas, 3)).put("haut", arrondi(it.haut, 3)).put("ecart", arrondi(it.ecart, 3)) }
            o.put("tons", t)
        }
        if (Vue.BITS in vues) {
            val sb = StringBuilder(bits.size)
            for (b in bits) sb.append(if (b.toInt() == 0) '0' else '1')
            o.put("bits", sb.toString()).put("premier", premierBit)
            o.put("reperes", JSONArray(reperes.map { JSONArray().put(it.debut).put(it.longueur).put(it.nom).put(it.inverse) }))
            trame?.let { o.put("trame", JSONArray().put(it.debut).put(it.longueur).put(it.nom).put(it.ms)) }
        }
        return o
    }

    companion object {
        private fun arrondi(v: Double, n: Int): Double { val p = Math.pow(10.0, n.toDouble()); return Math.round(v * p) / p }

        /**
         * Pairs as bytes, base64: each value × [echelle] in a signed byte;
         * [phase]: the first of each pair is 0..1, an unsigned byte (× 255).
         */
        fun octets(v: FloatArray, echelle: Float, phase: Boolean): String {
            val b = ByteArray(v.size)
            for (k in v.indices) {
                b[k] = if (phase && k % 2 == 0) (v[k] * 255f).roundToInt().coerceIn(0, 255).toByte()
                else (v[k] * echelle).roundToInt().coerceIn(-127, 127).toByte()
            }
            return java.util.Base64.getEncoder().encodeToString(b)
        }
    }
}

/** The figures of the views, pure functions (the tests call them directly). */
object AnalyseSignal {

    /**
     * MER (dB) and EVM (%) of an M-PSK cloud ([p]: I, Q pairs): each point
     * against the nearest ideal one on the circle of the mean amplitude
     * (BPSK on the I axis, QPSK and up offset by π/M).
     */
    fun constellation(p: FloatArray, ordre: Int): Pair<Double, Double> {
        val n = p.size / 2
        if (n < 16) return Double.NaN to Double.NaN
        var a = 0.0
        for (k in 0 until n) a += hypot(p[2 * k].toDouble(), p[2 * k + 1].toDouble())
        a /= n
        if (a <= 1e-12) return Double.NaN to Double.NaN
        val m = ordre.coerceAtLeast(2)
        val pas = 2 * PI / m
        val decale = if (m == 2) 0.0 else PI / m
        var e = 0.0
        for (k in 0 until n) {
            val i = p[2 * k].toDouble(); val q = p[2 * k + 1].toDouble()
            val th = Math.round((atan2(q, i) - decale) / pas) * pas + decale
            val di = i - a * cos(th); val dq = q - a * sin(th)
            e += di * di + dq * dq
        }
        e /= n
        if (e <= 1e-18) return 60.0 to 0.0
        return 10 * log10(a * a / e) to 100 * sqrt(e) / a
    }

    /**
     * The eye at the decisions ([v]: the levels there), split at [seuil] (by
     * default the middle of the extremes' median): the opening, 1 − 3σ either
     * side over the levels' distance (0..1), and Q, the distance over the
     * two σ (dB).
     */
    fun oeil(v: FloatArray, seuil: Double? = null): Pair<Double, Double> {
        if (v.size < 40) return Double.NaN to Double.NaN
        val s = seuil ?: run { val t = v.sorted(); (t[t.size / 20] + t[t.size - 1 - t.size / 20]) / 2.0 }
        var n1 = 0; var m1 = 0.0; var q1 = 0.0; var n0 = 0; var m0 = 0.0; var q0 = 0.0
        for (x in v) { val d = x.toDouble(); if (d > s) { n1++; m1 += d; q1 += d * d } else { n0++; m0 += d; q0 += d * d } }
        if (n1 < 10 || n0 < 10) return Double.NaN to Double.NaN
        m1 /= n1; m0 /= n0
        val s1 = sqrt(maxOf(0.0, q1 / n1 - m1 * m1)); val s0 = sqrt(maxOf(0.0, q0 / n0 - m0 * m0))
        val d = m1 - m0
        if (d <= 0) return 0.0 to Double.NaN
        val o = ((d - 3 * (s1 + s0)) / d).coerceIn(0.0, 1.0)
        val q = if (s1 + s0 <= 1e-12) 60.0 else 20 * log10(d / (s1 + s0))
        return o to q
    }

    /** The levels' histogram over their range (0.5 % cut either end, a margin added), [n] bins. */
    fun histogramme(v: FloatArray, n: Int = 96): VisuSignal.Spectre? {
        if (v.size < 40) return null
        val t = v.sorted()
        var lo = t[t.size / 200].toDouble(); var hi = t[t.size - 1 - t.size / 200].toDouble()
        val marge = maxOf(1e-9, (hi - lo) * 0.15)
        lo -= marge; hi += marge
        val pas = (hi - lo) / n
        val h = FloatArray(n)
        for (x in v) { val i = ((x - lo) / pas).toInt(); if (i in 0 until n) h[i]++ }
        return VisuSignal.Spectre(lo + pas / 2, pas, h)
    }

    /**
     * The two humps of a distribution: with [ecart] known, the pair of bins
     * that far apart with the most between them; otherwise the highest
     * hump and the highest other one clearly apart (and at least a sixth as
     * high). Each refined by its centroid. Null when there is only one.
     */
    fun tons(s: VisuSignal.Spectre, ecart: Double = 0.0): VisuSignal.Tons? {
        val n = s.valeurs.size
        if (n < 8) return null
        val l = FloatArray(n) { i -> var a = 0f; var c = 0; for (j in i - 1..i + 1) if (j in 0 until n) { a += s.valeurs[j]; c++ }; a / c }
        val fond = l.sorted()[n / 5].toDouble()
        var i1: Int; var i2: Int
        if (ecart > 0) {
            val d = (ecart / s.pas).roundToInt()
            if (d < 2 || d >= n) return null
            var best = -1.0; var b = -1
            for (i in 0 until n - d) { val v = l[i] + l[i + d].toDouble(); if (v > best) { best = v; b = i } }
            if (b < 0) return null
            i1 = b; i2 = b + d
        } else {
            i1 = 0
            for (i in 1 until n) if (l[i] > l[i1]) i1 = i
            val loin = maxOf(3, n / 10)
            i2 = -1
            for (i in 0 until n) if (abs(i - i1) >= loin && (i2 < 0 || l[i] > l[i2])) i2 = i
            if (i2 < 0 || l[i2] - fond < (l[i1] - fond) / 6) return null
            // A real second hump: a dip between the two.
            val a = minOf(i1, i2); val b = maxOf(i1, i2)
            var creux = Float.MAX_VALUE
            for (i in a..b) if (l[i] < creux) creux = l[i]
            if (creux - fond > 0.8 * (l[i2] - fond)) return null
        }
        if (ecart > 0) {
            // A spectrum: each tone's centroid close around where the pair put it (a keyed tone's
            // spectrum is not a line: a wide window would pull the two towards each other).
            val r = maxOf(1, abs(i2 - i1) / 16)
            fun pres(i: Int): Double {
                var p = 0.0; var m = 0.0
                for (j in i - r..i + r) if (j in 0 until n) { val w = maxOf(0.0, s.valeurs[j] - fond); p += w; m += w * j }
                return s.de + s.pas * (if (p > 0) m / p else i.toDouble())
            }
            val f1 = pres(i1); val f2 = pres(i2)
            return VisuSignal.Tons(minOf(f1, f2), maxOf(f1, f2))
        }
        val demi = maxOf(1, abs(i2 - i1) / 3)
        fun centre(i: Int): Double {
            var p = 0.0; var m = 0.0
            for (j in i - demi..i + demi) if (j in 0 until n) { val w = maxOf(0.0, s.valeurs[j] - fond); p += w; m += w * j }
            return s.de + s.pas * (if (p > 0) m / p else i.toDouble())
        }
        val f1 = centre(i1); val f2 = centre(i2)
        return VisuSignal.Tons(minOf(f1, f2), maxOf(f1, f2))
    }

    /** The rate of the decisions [t] (samples at [fs]): over their last increasing run; NaN when too few. */
    fun debit(t: DoubleArray, fs: Double): Double {
        if (fs <= 0 || t.size < 32) return Double.NaN
        var d = t.size - 1
        while (d > 0 && t[d - 1] < t[d]) d--
        val n = t.size - d
        if (n < 32) return Double.NaN
        val duree = t[t.size - 1] - t[d]
        return if (duree > 0) (n - 1) * fs / duree else Double.NaN
    }

    /** The power spectrum of [x] at [fs] from [de] to [a] Hz (Hann windows half overlapping, averaged), at most 400 bins. */
    fun spectre(x: FloatArray, fs: Double, de: Double, a: Double): VisuSignal.Spectre? {
        if (fs <= 0 || x.size < 256) return null
        val m = Integer.highestOneBit(minOf(x.size / 2, 8192).coerceAtLeast(256))
        val w = DoubleArray(m) { 0.5 - 0.5 * cos(2 * PI * it / (m - 1)) }
        val p = DoubleArray(m / 2)
        val re = DoubleArray(m); val im = DoubleArray(m)
        var nb = 0
        var k = 0
        while (k + m <= x.size) {
            for (i in 0 until m) { re[i] = x[k + i] * w[i]; im[i] = 0.0 }
            Fft.transform(re, im)
            for (i in 0 until m / 2) p[i] += re[i] * re[i] + im[i] * im[i]
            nb++; k += m / 2
        }
        if (nb == 0) return null
        val pasHz = fs / m
        val i0 = (de / pasHz).toInt().coerceIn(0, m / 2 - 1); val i1 = (a / pasHz).toInt().coerceIn(i0 + 1, m / 2)
        val n = i1 - i0
        val r = maxOf(1, (n + 399) / 400)
        val v = FloatArray(n / r) { i -> var s = 0.0; for (j in 0 until r) s += p[i0 + i * r + j]; (s / r / nb).toFloat() }
        return VisuSignal.Spectre(i0 * pasHz + (r - 1) * pasHz / 2, pasHz * r, v)
    }
}
