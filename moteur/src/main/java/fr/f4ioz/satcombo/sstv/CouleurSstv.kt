/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One picture's colour measured and, when asked, set right: global
 * corrections only, each pixel moved by the same rule, nothing invented.
 *
 * An SSTV picture is sent as tones, 1500 Hz black to 2300 Hz white, the
 * colour modes (Robot, PD) as Y, R-Y and B-Y in the same band. What goes
 * wrong with its colour, and what is measured here:
 *  - **a cast** — a tuning error moves every tone: Y, Cb and Cr all go up
 *    (or down) by the same amount, 0.32 level a hertz, and the picture turns
 *    magenta (green when low); a sender's own mistuned levels do the same to
 *    one chroma. The decoders already re-centre on the sync pulses; what
 *    is left is read on the picture: the colour the most nearly neutral
 *    pixels share (a mode sought from neutral grey outwards over the
 *    Cb/Cr plane, its median kept, not the mean of everything — a blue
 *    slide stays blue). When it looks like a tuning error (Cb and Cr off
 *    alike), the offset in hertz is said;
 *  - **the colour beside its picture** — the chroma read a little early or
 *    late against the luma (an encoder's timing, a porch of another
 *    length): coloured fringes on one side of every edge. The edges of Cb
 *    and Cr are matched against those of Y (their gradients correlated,
 *    ±6 pixels, refined to a tenth by a parabola), across and down;
 *  - **levels** — black and white where 0.5 % and 99.5 % of the pixels
 *    are, stretched to the studio range (16–235) when they fall short,
 *    never by more than ×1.6;
 *  - **saturation** — the chroma radius of the 90th percentile; off by
 *    default, raised only when washed out (a weak signal's tone scale
 *    shrinks towards the middle);
 *  - **noise** — luma and chroma, from the median of a Laplacian (robust to
 *    the slide's own edges), in levels.
 * A picture that is mostly noise is measured and left alone; a picture
 * already right comes out unchanged (each step needs a clear measure).
 * The picture as received is never touched: this gives new pixels.
 *
 * Pure Kotlin, no Android, so the phone may use it too.
 */
object CouleurSstv {

    /** Levels a hertz: 1500–2300 Hz span 0–255. */
    const val NIVEAUX_PAR_HZ = 255.0 / 800.0

    /** The furthest a chroma shift is looked for, px. */
    const val DECALAGE_MAX = 6

    class Options(
        val dominante: Boolean = true,
        val chroma: Boolean = true,
        val niveaux: Boolean = true,
        val saturation: Boolean = false,
    ) {
        fun code(): String = (if (dominante) "d" else "") + (if (chroma) "c" else "") + (if (niveaux) "n" else "") + (if (saturation) "s" else "")
        val rien: Boolean get() = !dominante && !chroma && !niveaux && !saturation
    }

    /** What was measured on a picture (levels on the studio scale, 16 black, 235 white, 128 no colour). */
    data class Analyse(
        /** Rows measured from the top. */
        val lignes: Int,
        /** The cast: the colour of the neutral pixels, Cb and Cr less 128. */
        val dominanteCb: Double,
        val dominanteCr: Double,
        /** Share of the pixels that agree on it. */
        val appuiNeutre: Double,
        /** The cast is measured well enough to be taken off. */
        val dominanteSure: Boolean,
        /** Cb and Cr off alike: the tuning error it amounts to, Hz (tones too high: +); null when it does not look like one. */
        val accordHz: Double?,
        /** Chroma shift against the luma, px (+: the colour lies right of / below its edges). */
        val cbX: Double, val cbY: Double, val crX: Double, val crY: Double,
        /** Strength of the edges' match (0..1); a shift is only taken from 0.15 on. */
        val correlationChroma: Double,
        /** Black and white (0.5 % and 99.5 % of the luma). */
        val noir: Double,
        val blanc: Double,
        /** Chroma radius of the 90th percentile, cast taken off. */
        val saturation: Double,
        /** Noise, levels (standard deviation): luma, chroma. */
        val bruitY: Double,
        val bruitC: Double,
        /** Mostly noise: nothing is applied. */
        val bruitDominant: Boolean,
    ) {
        /** The cast's size, levels. */
        val dominante: Double get() = hypot(dominanteCb, dominanteCr)
    }

    /** What would be done (or was): each step's own figures, zero when skipped. */
    data class Plan(
        /** Taken off Cb, Cr and (a tuning error's, which moves the luma alike) Y. */
        val dCb: Double = 0.0, val dCr: Double = 0.0, val dY: Double = 0.0,
        val cbX: Double = 0.0, val cbY: Double = 0.0, val crX: Double = 0.0, val crY: Double = 0.0,
        val noir: Double = 16.0, val gain: Double = 1.0,
        val saturation: Double = 1.0,
    ) {
        val dominante: Boolean get() = dCb != 0.0 || dCr != 0.0 || dY != 0.0
        val chroma: Boolean get() = cbX != 0.0 || cbY != 0.0 || crX != 0.0 || crY != 0.0
        val niveaux: Boolean get() = gain != 1.0 || noir != 16.0
        val vide: Boolean get() = !dominante && !chroma && !niveaux && saturation == 1.0
    }

    class Resultat(val px: IntArray, val analyse: Analyse, val plan: Plan)

    // ------------------------------------------------------------ colour space (studio range, as SstvMode)

    private fun yDe(r: Int, g: Int, b: Int) = 16.0 + (65.738 * r + 129.057 * g + 25.064 * b) / 256.0
    private fun cbDe(r: Int, g: Int, b: Int) = 128.0 + (-37.945 * r - 74.494 * g + 112.439 * b) / 256.0
    private fun crDe(r: Int, g: Int, b: Int) = 128.0 + (112.439 * r - 94.154 * g - 18.285 * b) / 256.0

    private fun argb(y: Double, cb: Double, cr: Double): Int {
        val yy = 298.082 * (y - 16)
        val v = cr - 128.0; val u = cb - 128.0
        val r = ((yy + 408.583 * v) / 256.0).roundToInt().coerceIn(0, 255)
        val g = ((yy - 100.291 * u - 208.120 * v) / 256.0).roundToInt().coerceIn(0, 255)
        val b = ((yy + 516.412 * u) / 256.0).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private class Plans(val y: FloatArray, val cb: FloatArray, val cr: FloatArray, val ecrete: BooleanArray)

    private fun plans(px: IntArray, n: Int): Plans {
        val y = FloatArray(n); val cb = FloatArray(n); val cr = FloatArray(n); val e = BooleanArray(n)
        for (i in 0 until n) {
            val c = px[i]; val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            y[i] = yDe(r, g, b).toFloat(); cb[i] = cbDe(r, g, b).toFloat(); cr[i] = crDe(r, g, b).toFloat()
            // A channel at an end: its colour was cut off when the picture was drawn, not measured.
            e[i] = r == 0 || r == 255 || g == 0 || g == 255 || b == 0 || b == 255
        }
        return Plans(y, cb, cr, e)
    }

    // ------------------------------------------------------------ measuring

    /** [px] ARGB, [w]×[h], its first [lignes] rows received (the rest not measured). */
    fun analyse(px: IntArray, w: Int, h: Int, lignes: Int = h): Analyse {
        val n = lignes.coerceIn(0, h)
        if (w < 8 || n < 8) return Analyse(n, 0.0, 0.0, 0.0, false, null, 0.0, 0.0, 0.0, 0.0, 0.0, 16.0, 235.0, 0.0, 0.0, 0.0, false)
        return analyse(plans(px, w * n), w, n)
    }

    private fun analyse(p: Plans, w: Int, n: Int): Analyse {
        val bruitY = bruit(p.y, w, n)
        val bruitC = (bruit(p.cb, w, n) + bruit(p.cr, w, n)) / 2
        val (noir, blanc) = percentiles(p.y, 0.005, 0.995)
        // Noise beyond about a fifth of the scale: the picture is mostly snow, its statistics say nothing.
        val bruitDominant = bruitY > 28.0 || bruitC > 34.0
        val dom = dominante(p, w * n)
        val dCb = dom.cb; val dCr = dom.cr; val appui = dom.appui
        val tailleD = hypot(dCb, dCr)
        val sure = !bruitDominant && dom.sure && appui >= 0.04 && tailleD <= 40.0
        // Cb and Cr off alike and by the same sign: as a tuning error would do.
        val accord = if (tailleD >= 2.0 && dCb * dCr > 0 && abs(dCb - dCr) <= 0.35 * maxOf(abs(dCb), abs(dCr)) + 1.5)
            (dCb + dCr) / 2 / NIVEAUX_PAR_HZ else null
        val (cbX, crX, corrX) = decalages(p, w, n, horizontal = true)
        val (cbY, crY, corrY) = decalages(p, w, n, horizontal = false)
        val sat = saturation(p, w * n, dCb, dCr)
        return Analyse(n, dCb, dCr, appui, sure, accord, cbX, cbY, crX, crY, maxOf(corrX, corrY).coerceAtLeast(0.0),
            noir, blanc, sat, bruitY, bruitC, bruitDominant)
    }

    /** Noise of a plane: median of the absolute 3×3 Laplacian (Immerkær's kernel, norm 6) over 0.6745·6. */
    private fun bruit(c: FloatArray, w: Int, n: Int): Double {
        val hist = IntArray(2048)
        var tot = 0
        for (y in 1 until n - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = c[i - w - 1] - 2 * c[i - w] + c[i - w + 1] - 2 * c[i - 1] + 4 * c[i] - 2 * c[i + 1] + c[i + w - 1] - 2 * c[i + w] + c[i + w + 1]
            hist[(abs(l) * 2).toInt().coerceAtMost(2047)]++; tot++
        }
        if (tot == 0) return 0.0
        var s = 0; var k = 0
        while (k < 2047 && s + hist[k] < tot / 2) { s += hist[k]; k++ }
        return (k + 0.5) / 2 / (0.6745 * 6)
    }

    private fun percentiles(c: FloatArray, a: Double, b: Double): Pair<Double, Double> {
        val hist = IntArray(1024)
        for (v in c) hist[(v * 4).toInt().coerceIn(0, 1023)]++
        fun p(q: Double): Double {
            val cible = (c.size * q).toLong(); var s = 0L
            for (k in 0 until 1024) { s += hist[k]; if (s > cible) return k / 4.0 + 0.125 }
            return 255.0
        }
        return p(a) to p(b)
    }

    /** The cast measured: Cb, Cr off, the share of pixels that agree, and whether dark and light pixels agree on it. */
    private class Dominante(val cb: Double, val cr: Double, val appui: Double, val sure: Boolean)

    /**
     * The cast: the colour of the nearly neutral pixels, sought apart among
     * the darker half and the lighter half of the picture. A tuning
     * error moves every pixel alike, so both must find it, within a few
     * levels; a slide's own colour (a blue sky, a sea) is seldom both its
     * dark and its light part. Each: from neutral grey (128, 128),
     * the median of the pixels within a radius, again until it stays.
     */
    private fun dominante(p: Plans, n: Int): Dominante {
        val hist = IntArray(256); var libres = 0
        for (i in 0 until n) if (!p.ecrete[i]) { hist[p.y[i].toInt().coerceIn(0, 255)]++; libres++ }
        if (libres < 64) return Dominante(0.0, 0.0, 0.0, false)
        fun quantile(q: Double): Int { var s = 0; for (k in 0 until 256) { s += hist[k]; if (s > libres * q) return k }; return 255 }
        val t = quantile(0.5)
        val tous = mode(p, n) { true }
        val sombre = mode(p, n) { it < t }
        val clair = mode(p, n) { it >= t }
        if (tous == null) return Dominante(0.0, 0.0, 0.0, false)
        val accord = sombre != null && clair != null && hypot(sombre.first - clair.first, sombre.second - clair.second) <= 5.0 &&
            sombre.third >= 0.01 && clair.third >= 0.01
        return if (accord) Dominante((sombre!!.first + clair!!.first) / 2 - 128.0, (sombre.second + clair.second) / 2 - 128.0, tous.third, true)
        else Dominante(tous.first - 128.0, tous.second - 128.0, tous.third, false)
    }

    /** The mode near neutral of the pixels whose luma [garde] takes: its Cb, Cr and the share of them close to it. */
    private fun mode(p: Plans, n: Int, garde: (Int) -> Boolean): Triple<Double, Double, Double>? {
        val idx = IntArray(n); var m = 0
        for (i in 0 until n) if (!p.ecrete[i] && garde(p.y[i].toInt())) idx[m++] = i
        if (m < 32) return null
        var cb = 128.0; var cr = 128.0
        val r = 16.0
        val tb = FloatArray(m); val tr = FloatArray(m)
        for (iter in 0 until 20) {
            var k = 0
            for (j in 0 until m) { val i = idx[j]; val db = p.cb[i] - cb; val dr = p.cr[i] - cr
                if (db * db + dr * dr <= r * r) { tb[k] = p.cb[i]; tr[k] = p.cr[i]; k++ } }
            if (k < 16) return null
            val nb = mediane(tb, k); val nr = mediane(tr, k)
            val bouge = hypot(nb - cb, nr - cr)
            cb = nb; cr = nr
            if (bouge < 0.05) break
        }
        var proches = 0
        for (j in 0 until m) { val i = idx[j]; val db = p.cb[i] - cb; val dr = p.cr[i] - cr; if (db * db + dr * dr <= 64.0) proches++ }
        return Triple(cb, cr, proches.toDouble() / m)
    }

    private fun mediane(t: FloatArray, k: Int): Double { val c = t.copyOf(k); c.sort(); return if (k % 2 == 1) c[k / 2].toDouble() else (c[k / 2 - 1] + c[k / 2]) / 2.0 }

    private fun saturation(p: Plans, n: Int, dCb: Double, dCr: Double): Double {
        val hist = IntArray(512)
        var tot = 0
        for (i in 0 until n) { if (p.ecrete[i]) continue; hist[(hypot(p.cb[i] - 128 - dCb, p.cr[i] - 128 - dCr) * 2).toInt().coerceAtMost(511)]++; tot++ }
        if (tot == 0) return 0.0
        val cible = (tot * 0.9).toInt(); var s = 0
        for (k in 0 until 512) { s += hist[k]; if (s > cible) return k / 2.0 + 0.25 }
        return 255.0
    }

    /**
     * How far Cb and Cr lie from Y along [horizontal] rows (else columns):
     * the gradient magnitudes of each chroma correlated with the luma's over
     * ±[DECALAGE_MAX], the peak refined by a parabola. Both chromas
     * measured together first (their edges are mostly the same); each then
     * on its own around it. Zero when the edges do not match clearly.
     */
    private fun decalages(p: Plans, w: Int, n: Int, horizontal: Boolean): Triple<Double, Double, Double> {
        val gy = gradient(p.y, w, n, horizontal)
        val gb = gradient(p.cb, w, n, horizontal)
        val gr = gradient(p.cr, w, n, horizontal)
        val gc = FloatArray(gb.size) { gb[it] + gr[it] }
        val (s, corr) = pic(gy, gc, w, n, horizontal)
        if (corr < SEUIL_CORRELATION) return Triple(0.0, 0.0, corr)
        // Each chroma on its own; kept only when it agrees with the pair within a pixel and a half (else the pair's).
        val (sb, cb) = pic(gy, gb, w, n, horizontal)
        val (sr, cr) = pic(gy, gr, w, n, horizontal)
        val b = if (cb >= SEUIL_CORRELATION && abs(sb - s) <= 1.5) sb else s
        val r = if (cr >= SEUIL_CORRELATION && abs(sr - s) <= 1.5) sr else s
        fun net(v: Double) = if (abs(v) < 0.25) 0.0 else (v * 10).roundToInt() / 10.0
        return Triple(net(b), net(r), corr)
    }

    private const val SEUIL_CORRELATION = 0.15

    /** How far short of black (16) and white (235) both ends must be for the levels to be stretched. */
    private const val ECART_NIVEAU = 10.0

    private fun gradient(c: FloatArray, w: Int, n: Int, horizontal: Boolean): FloatArray {
        val g = FloatArray(w * n)
        if (horizontal) { for (y in 0 until n) for (x in 1 until w - 1) g[y * w + x] = abs(c[y * w + x + 1] - c[y * w + x - 1]) }
        else for (y in 1 until n - 1) for (x in 0 until w) g[y * w + x] = abs(c[(y + 1) * w + x] - c[(y - 1) * w + x])
        return g
    }

    /** The shift (sub-pixel) where [b]'s gradients best follow [a]'s, and the correlation there (Pearson, 0..1). */
    private fun pic(a: FloatArray, b: FloatArray, w: Int, n: Int, horizontal: Boolean): Pair<Double, Double> {
        val m = DECALAGE_MAX
        val (x0, x1, y0, y1) = if (horizontal) listOf(m + 1, w - m - 1, 0, n) else listOf(0, w, m + 1, n - m - 1)
        if (x1 <= x0 || y1 <= y0) return 0.0 to 0.0
        var ma = 0.0; var cnt = 0
        for (y in y0 until y1) for (x in x0 until x1) { ma += a[y * w + x]; cnt++ }
        ma /= cnt
        var va = 0.0
        for (y in y0 until y1) for (x in x0 until x1) { val d = a[y * w + x] - ma; va += d * d }
        val c = DoubleArray(2 * m + 1)
        for (k in -m..m) {
            val off = if (horizontal) k else k * w
            var mb = 0.0
            for (y in y0 until y1) for (x in x0 until x1) mb += b[y * w + x + off]
            mb /= cnt
            var cov = 0.0; var vb = 0.0
            for (y in y0 until y1) for (x in x0 until x1) { val i = y * w + x; val db = b[i + off] - mb; cov += (a[i] - ma) * db; vb += db * db }
            c[k + m] = if (va > 0 && vb > 0) cov / sqrt(va * vb) else 0.0
        }
        var best = 0
        for (k in c.indices) if (c[k] > c[best]) best = k
        var s = (best - m).toDouble()
        if (best in 1 until c.size - 1) {
            val l = c[best - 1]; val cc = c[best]; val r = c[best + 1]
            val den = l - 2 * cc + r
            if (den < 0) s += (0.5 * (l - r) / den).coerceIn(-0.5, 0.5)
        }
        return s to c[best]
    }

    // ------------------------------------------------------------ correcting

    /** What [o] does to a picture measured as [a]: each step only when its measure is clear. */
    fun plan(a: Analyse, o: Options): Plan {
        if (a.bruitDominant || o.rien) return Plan()
        var p = Plan()
        if (o.dominante && a.dominanteSure && a.dominante >= 2.0) p = p.copy(dCb = a.dominanteCb, dCr = a.dominanteCr,
            dY = a.accordHz?.let { it * NIVEAUX_PAR_HZ } ?: 0.0)
        if (o.chroma && a.correlationChroma >= SEUIL_CORRELATION) p = p.copy(cbX = a.cbX, cbY = a.cbY, crX = a.crX, crY = a.crY)
        var gain = 1.0
        if (o.niveaux) {
            // Both ends short by about as much, as a tone scale shrunk towards its middle does (a weak
            // signal, a level set low): a slide without black, or without white, is left as it is.
            val bas = a.noir - 16.0; val haut = 235.0 - a.blanc
            if (bas >= ECART_NIVEAU && haut >= ECART_NIVEAU && abs(bas - haut) <= 0.5 * maxOf(bas, haut) + 6.0 && a.blanc - a.noir >= 24.0) {
                gain = (219.0 / (a.blanc - a.noir)).coerceIn(1.0, 1.6)
                p = p.copy(noir = a.noir, gain = (gain * 100).roundToInt() / 100.0)
            }
        }
        if (o.saturation && a.saturation in 4.0..30.0) {
            // Washed out: as far as a slide's colours usually go, never beyond ×1.5.
            val f = (36.0 / a.saturation).coerceIn(1.0, 1.5)
            if (f > 1.03) p = p.copy(saturation = (f * 100).roundToInt() / 100.0)
        }
        return p
    }

    /** [px] measured and corrected by [o]; the first [lignes] rows only (the rest as they are). */
    fun corrige(px: IntArray, w: Int, h: Int, lignes: Int = h, o: Options = Options()): Resultat {
        val n = lignes.coerceIn(0, h)
        if (w < 8 || n < 8) return Resultat(px.copyOf(), analyse(px, w, h, n), Plan())
        val p = plans(px, w * n)
        val a = analyse(p, w, n)
        val plan = plan(a, o)
        return Resultat(applique(px, w, h, n, p, plan), a, plan)
    }

    /** [plan] applied to [px] (the first [lignes] rows). */
    fun applique(px: IntArray, w: Int, h: Int, lignes: Int, plan: Plan): IntArray {
        val n = lignes.coerceIn(0, h)
        if (w < 8 || n < 8 || plan.vide) return px.copyOf()
        return applique(px, w, h, n, plans(px, w * n), plan)
    }

    private fun applique(px: IntArray, w: Int, h: Int, n: Int, p0: Plans, plan: Plan): IntArray {
        if (plan.vide) return px.copyOf()
        val p = if (plan.dominante) sansDominante(px, w * n, plan) else p0
        val cb = if (plan.cbX != 0.0 || plan.cbY != 0.0) decale(p.cb, w, n, plan.cbX, plan.cbY) else p.cb
        val cr = if (plan.crX != 0.0 || plan.crY != 0.0) decale(p.cr, w, n, plan.crX, plan.crY) else p.cr
        val out = px.copyOf()
        for (i in 0 until w * n) {
            var y = p.y[i].toDouble()
            var u = cb[i] - 128.0
            var v = cr[i] - 128.0
            if (plan.niveaux) y = 16.0 + (y - plan.noir) * plan.gain
            if (plan.saturation != 1.0) { u *= plan.saturation; v *= plan.saturation }
            out[i] = argb(y, u + 128.0, v + 128.0)
        }
        return out
    }

    /**
     * The cast taken off, in RGB: each channel less what the cast adds to it.
     * A channel the picture had cut off (at 0 or 255) is only known to be
     * beyond: of the values it may have had, the one nearest the pixel's
     * other channels (the least colour, nothing invented) — a black spoilt
     * by a tuning error comes back black, not tinted the other way.
     */
    private fun sansDominante(px: IntArray, n: Int, plan: Plan): Plans {
        val d = doubleArrayOf(1.164 * plan.dY + 1.596 * plan.dCr, 1.164 * plan.dY - 0.392 * plan.dCb - 0.813 * plan.dCr, 1.164 * plan.dY + 2.017 * plan.dCb)
        val y = FloatArray(n); val cb = FloatArray(n); val cr = FloatArray(n)
        val e = IntArray(3); val o = DoubleArray(3)
        for (i in 0 until n) {
            val c = px[i]
            e[0] = (c shr 16) and 0xFF; e[1] = (c shr 8) and 0xFF; e[2] = c and 0xFF
            var somme = 0.0; var libres = 0
            for (k in 0..2) if (e[k] in 1..254) { o[k] = e[k] - d[k]; somme += o[k]; libres++ }
            for (k in 0..2) if (e[k] == 0 || e[k] == 255) {
                val (bas, haut) = if (e[k] == 0) 0.0 to maxOf(0.0, -d[k]) else minOf(255.0, 255.0 - d[k]) to 255.0
                o[k] = if (libres > 0) (somme / libres).coerceIn(bas, haut) else if (e[k] == 0) bas else haut
            }
            val r = o[0]; val g = o[1]; val b = o[2]
            y[i] = (16.0 + (65.738 * r + 129.057 * g + 25.064 * b) / 256.0).toFloat()
            cb[i] = (128.0 + (-37.945 * r - 74.494 * g + 112.439 * b) / 256.0).toFloat()
            cr[i] = (128.0 + (112.439 * r - 94.154 * g - 18.285 * b) / 256.0).toFloat()
        }
        return Plans(y, cb, cr, BooleanArray(0))
    }

    /** Plane [c] read [dx], [dy] further on (bilinear; edges held). */
    private fun decale(c: FloatArray, w: Int, n: Int, dx: Double, dy: Double): FloatArray {
        val o = FloatArray(c.size)
        for (y in 0 until n) {
            val sy = (y + dy).coerceIn(0.0, (n - 1).toDouble())
            val y0 = floor(sy).toInt().coerceAtMost(n - 1); val y1 = (y0 + 1).coerceAtMost(n - 1); val fy = (sy - y0).toFloat()
            for (x in 0 until w) {
                val sx = (x + dx).coerceIn(0.0, (w - 1).toDouble())
                val x0 = floor(sx).toInt().coerceAtMost(w - 1); val x1 = (x0 + 1).coerceAtMost(w - 1); val fx = (sx - x0).toFloat()
                val a = c[y0 * w + x0] * (1 - fx) + c[y0 * w + x1] * fx
                val b = c[y1 * w + x0] * (1 - fx) + c[y1 * w + x1] * fx
                o[y * w + x] = a * (1 - fy) + b * fy
            }
        }
        return o
    }

    // ------------------------------------------------------------ for tests and simulations

    /**
     * A picture as a tuning error of [hz] would decode it (YCbCr modes): Y,
     * Cb and Cr all moved by [hz]·0.32 level. With [cbX], [crX]… its chroma
     * read that many pixels late (lying right of its edges).
     */
    fun simule(px: IntArray, w: Int, h: Int, hz: Double = 0.0, dCb: Double = 0.0, dCr: Double = 0.0,
               cbX: Double = 0.0, crX: Double = 0.0, cbY: Double = 0.0, crY: Double = 0.0): IntArray {
        val p = plans(px, w * h)
        val cb = decale(p.cb, w, h, -cbX, -cbY); val cr = decale(p.cr, w, h, -crX, -crY)
        val k = hz * NIVEAUX_PAR_HZ
        return IntArray(w * h) { i -> argb(p.y[i] + k, cb[i] + k + dCb, cr[i] + k + dCr) }
    }
}
