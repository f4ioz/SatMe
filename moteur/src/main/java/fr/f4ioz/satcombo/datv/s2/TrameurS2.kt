/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

import fr.f4ioz.satcombo.sdr.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** One PLFRAME's soft values for the FEC decoders, in the FECFRAME's order (positive: bit 0). */
class TrameS2(val numero: Long, val modcod: Modcod, val llr: FloatArray, val rupture: Boolean)

/**
 * From symbols to PLFRAMEs (5.5): the PLHEADER found, the frames followed,
 * the carrier recovered, the data descrambled and soft-demapped.
 *
 * - **Search.** Over a frame's length of symbols, the SOF and the PLS
 *   code's pairs correlated differentially (each symbol times the previous
 *   one's conjugate: no carrier needed, any offset within the filter); the
 *   best places decoded (the 128 PLS codewords against the header, the
 *   phase from the SOF) and kept only when the next header is where their
 *   length says.
 * - **Locked.** The next header checked where expected (±1 symbol); lost
 *   after [PERTES_MAX] misses in a row.
 * - **Carrier.** At lock, the frequency from the whole frame (the M-th
 *   power of the symbols — 4 for QPSK, 8 for 8PSK — then its spectrum's
 *   peak), the phase from the header. Then a second-order loop symbol by
 *   symbol: driven by the known symbols (header, pilots, dummy frames) and
 *   by decisions on the data.
 */
class TrameurS2(private val frontal: FrontalS2?, private val sortie: (TrameS2) -> Unit) {

    // The symbols waiting, I and Q apart.
    private var sr = FloatArray(1 shl 17)
    private var si = FloatArray(1 shl 17)
    private var fin = 0

    /** Where the receiver is: 0 searching, 1 headers seen (not confirmed), 2 locked. */
    @Volatile var etape = 0; private set
    private var epoque = -1
    private var essais = 0

    // Locked: the header's place, its PLS, misses in a row.
    private var h = 0
    private var code = 0
    private var type = 0
    private var pertes = 0
    private var nouveau = true
    private var rupture = true
    private var numero = 0L

    // The carrier loop: phase at symbol h, rad per symbol.
    private var theta = 0.0
    private var omega = 0.0
    private var amplitude = 1.0

    // What the status shows.
    @Volatile var merDb: Double? = null; private set
    @Volatile var snrConnusDb: Double? = null; private set
    @Volatile var modcodCode = -1; private set
    @Volatile var typeTrame = 0; private set
    @Volatile var tramesDonnees = 0L; private set
    @Volatile var tramesVides = 0L; private set
    @Volatile var tramesAutres = 0L; private set
    @Volatile var enTetesManques = 0L; private set
    @Volatile var verrouillages = 0L; private set
    /** The carrier's offset left to the loop (rad/symbol). */
    val omegaBoucle: Double get() = omega
    /** The last data symbols after the carrier (I, Q), for the constellation. */
    val points = FloatArray(2 * POINTS)
    @Volatile var nPoints = 0; private set

    private var merS = 0.0
    private var merB = 0.0

    /** Each frame's carrier figures, for the tests. */
    var trace: ((String) -> Unit)? = null

    var largeurConnus = 0.01
    var largeurDonnees = 0.002

    // ------------------------------------------------------------ in

    fun symbole(i: Float, q: Float) {
        val ep = frontal?.epoque ?: 0
        if (ep != epoque) { epoque = ep; repart(); }
        if (frontal?.enAcquisition == true) return
        if (fin == sr.size) {
            sr = sr.copyOf(sr.size * 2); si = si.copyOf(si.size * 2)
        }
        sr[fin] = i; si[fin] = q; fin++
        if (etape == 2) { if (fin >= h + longueurMax(code, type) + 92) avance() }
        else if (fin >= FENETRE + LMAX + 92) cherche()
    }

    /** The lock given up (the carrier searched again). */
    fun lache() { etape = 0; rupture = true; fin = 0 }

    private fun repart() {
        fin = 0; etape = 0; essais = 0; nouveau = true; rupture = true
    }

    /** Drops the [n] oldest symbols. */
    private fun jette(n: Int) {
        if (n <= 0) return
        System.arraycopy(sr, n, sr, 0, fin - n); System.arraycopy(si, n, si, 0, fin - n)
        fin -= n; h -= n
    }

    // ------------------------------------------------------------ the differential correlation

    /** |C_SOF| + |C_PLS| at [p], and the phasor of their sum (the carrier's rotation per symbol). */
    private fun metrique(p: Int, f: DoubleArray?): Double {
        var ar = 0.0; var ai = 0.0
        for (k in 1 until 26) {
            val dr = sr[p + k] * sr[p + k - 1] + si[p + k] * si[p + k - 1]
            val di = si[p + k] * sr[p + k - 1] - sr[p + k] * si[p + k - 1]
            val w = DIFF_SOF[k]
            // d · conj(w), w = ±j.
            ar += w * di; ai -= w * dr
        }
        var br = 0.0; var bi = 0.0
        for (i in 0 until 32) {
            val k = 27 + 2 * i
            val dr = sr[p + k] * sr[p + k - 1] + si[p + k] * si[p + k - 1]
            val di = si[p + k] * sr[p + k - 1] - sr[p + k] * si[p + k - 1]
            val w = DIFF_PLS[i]
            br += w * di; bi -= w * dr
        }
        val a = hypot(ar, ai); val b = hypot(br, bi)
        if (f != null) {
            // The PLS pairs' sign (the pilots' bit) unknown: the one that agrees with the SOF.
            val s = if (ar * br + ai * bi >= 0) 1.0 else -1.0
            f[0] = ar + s * br; f[1] = ai + s * bi
        }
        return a + b
    }

    // ------------------------------------------------------------ search

    private fun cherche() {
        val n = FENETRE
        val m = DoubleArray(n)
        var s = 0.0; var s2 = 0.0
        for (p in 0 until n) { val v = metrique(p, null); m[p] = v; s += v; s2 += v * v }
        val moy = s / n
        val et = sqrt((s2 / n - moy * moy).coerceAtLeast(1e-30))
        // The best places, one per 90 symbols.
        val ordre = (0 until n).sortedByDescending { m[it] }
        val cand = ArrayList<Int>()
        for (p in ordre) {
            if (cand.size >= CANDIDATS) break
            if (cand.none { abs(it - p) < 90 }) cand += p
        }
        var meilleur = -1; var score = 0.0; var mCode = 0; var mType = 0
        val f = DoubleArray(2)
        for (p in cand) {
            val z1 = (m[p] - moy) / et
            if (z1 < Z_PREMIER) break
            metrique(p, f)
            val w = atan2(f[1], f[0])
            val (c, ty) = decodePls(p, w, null) ?: continue
            val l = longueur(c, ty)
            if (l <= 0) continue
            var bm = -1.0; var bd = 0
            for (d in -1..1) { val v = metrique(p + l + d, null); if (v > bm) { bm = v; bd = d } }
            val z2 = (bm - moy) / et
            if (z2 <= Z_SUIVANT || z1 + z2 <= score) continue
            val pl = confirme(p, c, ty, z1 + z2) ?: continue
            score = z1 + z2; meilleur = p; mCode = pl.first; mType = pl.second
        }
        if (meilleur < 0) {
            etape = 0
            jette(n)
            // Nothing in several windows: the carrier searched again over the spectrum.
            if (++essais % 4 == 0) frontal?.acquiers()
            return
        }
        etape = 2
        verrouillages++
        jette(meilleur)
        h = 0
        code = mCode; type = mType; pertes = 0; nouveau = true; rupture = true
        metrique(0, f)
        decodePls(0, atan2(f[1], f[0]), null)
        omega = omegaPls
        if (fin >= h + longueurMax(code, type) + 92) avance()
    }

    /**
     * A candidate header at [p] read as ([c], [ty]): the next one found by its
     * SOF (±1 symbol), both PLS decoded and their correlations added (a
     * constant stream: the surer decision at low Es/N0). Kept when each
     * the headers agree, or the sum keeps the length that found the second
     * one, or the two peaks stand very high ([z]); the (MODCOD, TYPE), or null.
     */
    private fun confirme(p: Int, c: Int, ty: Int, z: Double): Pair<Int, Int>? {
        val f = DoubleArray(2)
        metrique(p, f)
        val w0 = atan2(f[1], f[0])
        var second = p + longueur(c, ty)
        var bv = -1.0; var bd = 0
        for (d in -1..1) { val v = sofModule(second + d, w0); if (v > bv) { bv = v; bd = d } }
        second += bd
        val un = decodePls(p, w0, null) ?: return null
        val premiers = scoresPls.copyOf()
        metrique(second, f)
        val deux = decodePls(second, atan2(f[1], f[0]), null) ?: return null
        var bc = 0
        for (k in 0 until 128) if (premiers[k] + scoresPls[k] > premiers[bc] + scoresPls[bc]) bc = k
        val somme = (bc shr 2) to (bc and 3)
        if (longueur(somme.first, somme.second) <= 0) return null
        // The two headers alike: one constant MODCOD. Unlike (a dummy frame then data, or noise): the sum
        // only when it keeps the length that put the second header where it was found.
        return when {
            un == deux -> un
            (somme == un || somme == deux) && longueur(somme.first, somme.second) == longueur(c, ty) -> somme
            un == (c to ty) && z > Z_SUR -> un
            else -> null
        }
    }

    /**
     * The PLS at [p] decoded coherently: the phase from the SOF (with [w] rad/symbol removed),
     * the 64 symbols against the 128 codewords. (MODCOD, TYPE), or null when no codeword stands out.
     */
    private fun decodePls(p: Int, w: Double, phaseConnue: Double?): Pair<Int, Int>? {
        if (phaseConnue != null) {
            val (c, v) = plsA(p, w, phaseConnue)
            return if (v <= 0f) null else (c shr 2) to (c and 3)
        }
        // No carrier yet: the differential estimate is too rough over 90 symbols, so a grid of
        // frequencies around it, each with its SOF phase; the best header (SOF + PLS) wins.
        var best = -1; var bv = Double.NEGATIVE_INFINITY; var bw = w
        for (d in -GRILLE..GRILLE) {
            val wd = w + d * PAS_GRILLE
            var ar = 0.0; var ai = 0.0
            for (k in 0 until 26) {
                val c = cos(-wd * k); val s = sin(-wd * k)
                val zr = sr[p + k] * c - si[p + k] * s; val zi = sr[p + k] * s + si[p + k] * c
                ar += zr * REF_SOF_R[k] + zi * REF_SOF_I[k]; ai += zi * REF_SOF_R[k] - zr * REF_SOF_I[k]
            }
            val (c, v) = plsA(p, wd, atan2(ai, ar))
            val total = v + hypot(ar, ai)
            if (total > bv) { bv = total; best = c; bw = wd; System.arraycopy(scoresTmp, 0, scoresPls, 0, 128) }
        }
        omegaPls = bw
        return if (best < 0) null else (best shr 2) to (best and 3)
    }

    /** The frequency the last grid search found best (rad/symbol), and its 128 codewords' correlations. */
    private var omegaPls = 0.0
    private val scoresPls = FloatArray(128)
    private val scoresTmp = FloatArray(128)

    /** The best PLS codeword at [p] with phase [phi] at p and [w] rad/symbol, and its correlation. */
    private fun plsA(p: Int, w: Double, phi: Double): Pair<Int, Float> {
        val soft = FloatArray(64)
        for (j in 0 until 64) {
            val k = 26 + j
            val a = -(phi + w * k)
            val c = cos(a); val s = sin(a)
            val zr = sr[p + k] * c - si[p + k] * s; val zi = sr[p + k] * s + si[p + k] * c
            // Projected on the (j^k)·e^{jπ/4} axis: +1 for bit 0.
            soft[j] = (zr * BASE_R[k] + zi * BASE_I[k]).toFloat()
        }
        var best = -1; var bv = Float.NEGATIVE_INFINITY
        for (c in 0 until 128) {
            val cw = MOTS_PLS[c]
            var v = 0f
            for (j in 0 until 64) v += cw[j] * soft[j]
            scoresTmp[c] = v
            if (v > bv) { bv = v; best = c }
        }
        return best to bv
    }

    // ------------------------------------------------------------ locked

    private fun avance() {
        while (etape == 2) {
            val l = longueur(code, type)
            if (l <= 0 || fin < h + l + 92) return
            traiteTrame(h, l)
            // The next header where the length says, its SOF against the loop's phase; ±1 symbol if the clock slipped.
            var bd = 0
            val c0 = sofCoherent(h + l, theta, omega)
            var present = c0 > SEUIL_SOF
            if (!present) {
                for (d in intArrayOf(-1, 1)) {
                    val c = sofCoherent(h + l + d, theta + d * omega, omega)
                    if (c > SEUIL_SOF_DECALE && c > c0) { bd = d; present = true }
                }
            }
            if (!present) { pertes++; enTetesManques++ } else pertes = 0
            val nh = h + l + bd
            if (pertes > PERTES_MAX) { etape = 0; rupture = true; jette(nh); return }
            // The next PLS, the carrier carried over.
            val pl = decodePls(nh, omega, theta + bd * omega)
            if (pl != null && longueur(pl.first, pl.second) > 0) { code = pl.first; type = pl.second }
            h = nh
            jette(h)
        }
    }

    /** |Σ z·conj(SOF)| over the 26 SOF symbols at [p], [w] rad/symbol removed: no phase needed. */
    private fun sofModule(p: Int, w: Double): Double {
        var ar = 0.0; var ai = 0.0
        for (k in 0 until 26) {
            val an = -w * k; val c = cos(an); val s = sin(an)
            val zr = sr[p + k] * c - si[p + k] * s; val zi = sr[p + k] * s + si[p + k] * c
            ar += zr * REF_SOF_R[k] + zi * REF_SOF_I[k]; ai += zi * REF_SOF_R[k] - zr * REF_SOF_I[k]
        }
        return hypot(ar, ai)
    }

    /** Re Σ z·conj(SOF) over the 26 SOF symbols at [p], phase [ph] at p and [w] rad/symbol removed, per symbol and level. */
    private fun sofCoherent(p: Int, ph: Double, w: Double): Double {
        var a = 0.0
        for (k in 0 until 26) {
            val an = -(ph + w * k); val c = cos(an); val s = sin(an)
            val zr = sr[p + k] * c - si[p + k] * s; val zi = sr[p + k] * s + si[p + k] * c
            a += zr * REF_SOF_R[k] + zi * REF_SOF_I[k]
        }
        return a / 26 / amplitude.coerceAtLeast(1e-9)
    }

    /** The frame at [p] ([l] symbols): carrier, then its data to the decoders. */
    private fun traiteTrame(p: Int, l: Int) {
        val cst = constellationDe(code)
        val mc = modcodDe(code, type)
        modcodCode = code; typeTrame = type
        val connus = connus(code, type, l)
        if (nouveau) { estimeFrequence(p, l, if (cst == 3) 8 else 4); nouveau = false }
        // The header's phase and level, the frequency carried.
        run {
            var ar = 0.0; var ai = 0.0
            val ref = enTete(code, type)
            for (k in 0 until 90) {
                val a = -(omega * k); val c = cos(a); val s = sin(a)
                val zr = sr[p + k] * c - si[p + k] * s; val zi = sr[p + k] * s + si[p + k] * c
                ar += zr * ref[2 * k] + zi * ref[2 * k + 1]; ai += zi * ref[2 * k] - zr * ref[2 * k + 1]
            }
            val ph = atan2(ai, ar)
            amplitude = hypot(ar, ai) / 90
            // Right after a lock the header sets the phase; afterwards the loop only corrects it.
            if (rupture) theta = ph
            else theta += 0.5 * remet(ph - theta)
        }
        val g1c: Double; val g2c: Double; val g1d: Double; val g2d: Double
        run {
            fun g(bn: Double): Pair<Double, Double> {
                val z = 0.707; val th = bn / (z + 0.25 / z); val d = 1 + 2 * z * th + th * th
                return (4 * z * th / d) to (4 * th * th / d)
            }
            // Known symbols come in bursts: they set the phase fast, the frequency no faster than the data do.
            val (a, _) = g(largeurConnus); g1c = a; g2c = g(largeurDonnees).second
            val (c, d) = g(if (cst == 0) 0.0 else largeurDonnees); g1d = c; g2d = d
        }
        val nData = if (mc != null) mc.nldpc / mc.constellation.bits else 0
        val donnees = if (mc != null) FloatArray(2 * nData) else null
        var o = 0
        val seq = CouchePhysique.SEQUENCE_PL
        val inv = 1.0 / amplitude.coerceAtLeast(1e-9)
        var sigS = 0.0; var sigB = 0.0; var cS = 0.0; var cB = 0.0
        var th = theta; var w = omega
        val blocs = StringBuilder(); var blocC = 0.0; var blocN = 0
        // With pilots (or a dummy frame): the phase from each block of known symbols, interpolated between them.
        val phases = if ((type and 1 != 0 && mc != null) || code == 0) phasesParBlocs(p, l, connus) else null
        val es = esN0()
        val kTanh = sqrt(2.0) * es
        for (k in 0 until l) {
            if (phases != null) th = phases[k]
            val c = cos(-th); val s = sin(-th)
            val zr = ((sr[p + k] * c - si[p + k] * s) * inv)
            val zi = ((sr[p + k] * s + si[p + k] * c) * inv)
            val e: Double
            val rr = connus[2 * k]; val ri = connus[2 * k + 1]
            if (rr != 0f || ri != 0f) {
                e = zi * rr - zr * ri
                if (trace != null) { blocC += zr * rr + zi * ri; blocN++; if (blocN == 36 || k == 89) { blocs.append(" %.1f/%.2f".format(blocC / blocN, th)); blocC = 0.0; blocN = 0 } }
                cS += 1.0; cB += (zr - rr) * (zr - rr) + (zi - ri) * (zi - ri)
                if (phases == null) { th += w + g1c * e; w += g2c * e }
            } else {
                // Data: descrambled for the decoders; the decision for the loop (the scrambling keeps the constellation).
                if (donnees != null) {
                    val r = seq[k - 90].toInt()
                    var dr = zr; var di = zi
                    when (r) { 1 -> { dr = zi; di = -zr }; 2 -> { dr = -zr; di = -zi }; 3 -> { dr = -zi; di = zr } }
                    donnees[o++] = dr.toFloat(); donnees[o++] = di.toFloat()
                }
                if (cst == 2 || cst == 3) {
                    val er: Double; val ei: Double
                    if (cst == 3) {
                        val a = atan2(zi, zr)
                        val q = Math.round(a / (PI / 4)) * (PI / 4)
                        er = cos(q); ei = sin(q)
                    } else { er = if (zr >= 0) R2 else -R2; ei = if (zi >= 0) R2 else -R2 }
                    sigS += 1.0; sigB += (zr - er) * (zr - er) + (zi - ei) * (zi - ei)
                    if (phases == null) {
                        // QPSK: soft decisions (the expected symbol given the noise), which keep the loop sane at low Es/N0.
                        e = if (cst == 2) zi * R2 * tanh(kTanh * zr) - zr * R2 * tanh(kTanh * zi) else zi * er - zr * ei
                        th += w + g1d * e; w += g2d * e
                    }
                } else if (phases == null) th += w
            }
            if (th > PI) th -= 2 * PI else if (th < -PI) th += 2 * PI
        }
        trace?.invoke("trame code $code type $type l $l : θ ${"%.3f".format(theta)}→${"%.3f".format(th)} ω ${"%.5f".format(omega)}→${"%.5f".format(w)} A ${"%.3f".format(amplitude)} connus ${"%.1f".format(10 * log10(cS / cB.coerceAtLeast(1e-12)))} dB DD ${"%.1f".format(10 * log10(sigS / sigB.coerceAtLeast(1e-12)))} dB" + blocs)
        if (phases != null) { th = finTheta; w = finOmega }
        theta = remet(th); omega = w
        if (cS > 0) {
            val v = 10 * log10(cS / cB.coerceAtLeast(1e-12))
            // Enough known symbols (pilots, a dummy frame): this frame's own figure; a header alone, smoothed.
            snrConnusDb = snrConnusDb?.let { if (rupture || cS >= 400) v else it + 0.3 * (v - it) } ?: v
        }
        if (sigS > 0) {
            merS += sigS; merB += sigB
            if (merS > 20_000) { val v = 10 * log10(merS / merB.coerceAtLeast(1e-12)); merDb = merDb?.let { it + 0.5 * (v - it) } ?: v; merS = 0.0; merB = 0.0 }
        }
        if (code == 0) { tramesVides++; return }
        if (mc == null || donnees == null) { tramesAutres++; rupture = true; return }
        // The constellation's last points.
        synchronized(points) {
            val n = minOf(POINTS, nData)
            System.arraycopy(donnees, 2 * (nData - n), points, 0, 2 * n)
            nPoints = n
        }
        tramesDonnees++
        sortie(TrameS2(numero++, mc, demappe(mc, donnees, esN0()), rupture))
        rupture = false
    }

    private var finTheta = 0.0
    private var finOmega = 0.0

    /**
     * Each symbol's phase from the blocks of known symbols (header, pilot
     * blocks, a dummy frame's body cut in 36s): each block's phase against
     * the line θ + ω·k, unwrapped from block to block, joined by straight
     * lines; the residual slope corrects ω. Sets the next frame's θ and ω.
     */
    private fun phasesParBlocs(p: Int, l: Int, connus: FloatArray): DoubleArray {
        val debuts = ArrayList<Int>(); val longueurs = ArrayList<Int>()
        var k = 0
        while (k < l) {
            if (connus[2 * k] == 0f && connus[2 * k + 1] == 0f) { k++; continue }
            var f = k
            while (f < l && (connus[2 * f] != 0f || connus[2 * f + 1] != 0f) && (k < 90 || f - k < 36)) { f++; if (k < 90 && f == 90) break }
            debuts += k; longueurs += f - k
            k = f
        }
        val nb = debuts.size
        val centres = DoubleArray(nb); val r = DoubleArray(nb); val poids = DoubleArray(nb)
        val th = theta; val w = omega
        for (b in 0 until nb) {
            var ar = 0.0; var ai = 0.0
            for (j in debuts[b] until debuts[b] + longueurs[b]) {
                val a = -(th + w * j); val c = cos(a); val s = sin(a)
                val zr = sr[p + j] * c - si[p + j] * s; val zi = sr[p + j] * s + si[p + j] * c
                val rr = connus[2 * j]; val ri = connus[2 * j + 1]
                ar += zr * rr + zi * ri; ai += zi * rr - zr * ri
            }
            centres[b] = debuts[b] + (longueurs[b] - 1) / 2.0
            r[b] = atan2(ai, ar)
            poids[b] = longueurs[b].toDouble()
        }
        // Unwrapped block after block, each from the trend so far (a drifting carrier followed): the slope is the residual frequency.
        var pente = 0.0
        for (b in 1 until nb) {
            val dc = centres[b] - centres[b - 1]
            val prevu = r[b - 1] + pente * dc
            r[b] = prevu + remet(r[b] - prevu)
            pente += SUIVI_PENTE * (r[b] - prevu) / dc
        }
        val ph = DoubleArray(l)
        var b = 0
        for (j in 0 until l) {
            while (b + 1 < nb && centres[b + 1] <= j) b++
            val res = when {
                j <= centres[0] -> r[0] + pente * (j - centres[0])
                b + 1 < nb -> r[b] + (r[b + 1] - r[b]) * (j - centres[b]) / (centres[b + 1] - centres[b])
                else -> r[nb - 1] + pente * (j - centres[nb - 1])
            }
            ph[j] = th + w * j + res
        }
        finTheta = th + w * l + r[nb - 1] + pente * (l - centres[nb - 1])
        finOmega = w + pente
        return ph
    }

    /** Wraps into ±π. */
    private fun remet(a: Double): Double { var x = a; while (x > PI) x -= 2 * PI; while (x < -PI) x += 2 * PI; return x }

    /**
     * The carrier's offset over the frame: z^M (the modulation gone, the
     * offset multiplied by M), its spectrum's peak — after removing what is
     * already known ([omega]).
     */
    private fun estimeFrequence(p: Int, l: Int, puissance: Int) {
        val n = Integer.highestOneBit(minOf(l, 1 shl 15) - 1) shl 1
        val re = DoubleArray(n); val im = DoubleArray(n)
        val cnt = minOf(l, n)
        for (k in 0 until cnt) {
            val a = -omega * k
            val c = cos(a); val s = sin(a)
            val zr = sr[p + k] * c - si[p + k] * s; val zi = sr[p + k] * s + si[p + k] * c
            val r = hypot(zr, zi).coerceAtLeast(1e-12)
            var ur = zr / r; var ui = zi / r
            // u^M by squarings (M = 4 or 8).
            var m = 1
            while (m < puissance) { val t = ur * ur - ui * ui; ui = 2 * ur * ui; ur = t; m *= 2 }
            re[k] = ur; im[k] = ui
        }
        Fft.transform(re, im)
        var best = 0; var bv = -1.0
        val pw = DoubleArray(n) { re[it] * re[it] + im[it] * im[it] }
        for (k in 0 until n) if (pw[k] > bv) { bv = pw[k]; best = k }
        val y0 = pw[(best - 1 + n) % n]; val y1 = pw[best]; val y2 = pw[(best + 1) % n]
        val den = y0 - 2 * y1 + y2
        val fr = if (den < 0) 0.5 * (y0 - y2) / den else 0.0
        val bin = (if (best >= n / 2) best - n else best) + fr
        omega += 2 * PI * bin / n / puissance
    }

    /** Es/N0 (linear) from the known symbols, bounded: what scales the LLRs. */
    private fun esN0(): Float = Math.pow(10.0, ((snrConnusDb ?: 3.0).coerceIn(-6.0, 20.0)) / 10).toFloat()

    /**
     * LLRs from the frame's data symbols (descrambled, carrier removed, unit
     * level) at [es] = Es/N0: QPSK 2√2·y·Es/N0 per axis; 8PSK max-log,
     * 2·Es/N0·(best correlation with a 0 − with a 1).
     */
    private fun demappe(m: Modcod, x: FloatArray, es: Float): FloatArray {
        val n = m.nldpc
        val l = FloatArray(n)
        when (m.constellation) {
            Constellation.QPSK -> { val g = (2 * sqrt(2.0)).toFloat() * es; for (k in 0 until n) l[k] = x[k] * g }
            Constellation.PSK8 -> {
                val lu = FloatArray(n)
                val ci = CouchePhysique.PSK8_I; val cq = CouchePhysique.PSK8_Q
                val d = FloatArray(8)
                for (sy in 0 until n / 3) {
                    val i = x[2 * sy]; val q = x[2 * sy + 1]
                    for (w in 0 until 8) d[w] = i * ci[w] + q * cq[w]
                    for (bit in 0 until 3) {
                        var m0 = Float.NEGATIVE_INFINITY; var m1 = Float.NEGATIVE_INFINITY
                        for (w in 0 until 8) if ((w shr (2 - bit)) and 1 == 0) { if (d[w] > m0) m0 = d[w] } else if (d[w] > m1) m1 = d[w]
                        lu[3 * sy + bit] = (m0 - m1) * 2 * es
                    }
                }
                // The interleaver undone: symbol r's bits came from row r of the columns.
                val lignes = n / 3
                val ordre = if (m.taux == Taux.R3_5) intArrayOf(2, 1, 0) else intArrayOf(0, 1, 2)
                for (r in 0 until lignes) for (j in 0 until 3) l[ordre[j] * lignes + r] = lu[3 * r + j]
            }
        }
        return l
    }

    companion object {
        /** The longest PLFRAME (QPSK, normal, pilots) and the search window. */
        const val LMAX = 33_282
        const val FENETRE = LMAX + 90
        const val CANDIDATS = 12
        /** The PLS search's frequency grid: ±GRILLE steps of PAS_GRILLE rad/symbol. */
        const val GRILLE = 20
        /** How much of each block's surprise goes into the frequency (an α-β tracker over the blocks). */
        const val SUIVI_PENTE = 0.25
        const val PAS_GRILLE = 0.004
        const val Z_PREMIER = 2.5
        const val Z_SUIVANT = 4.0
        /** Both peaks this high: the first header's PLS taken alone when the two disagree. */
        const val Z_SUR = 16.0
        /** The next SOF taken as there above this share of its full value (0.6 when one symbol off). */
        const val SEUIL_SOF = 0.4
        const val SEUIL_SOF_DECALE = 0.6
        const val PERTES_MAX = 3
        const val POINTS = 400
        private val R2 = 1 / sqrt(2.0)

        /** Bits per symbol of PLS MODCOD [c] (0: dummy, −1: none). */
        fun bitsDe(c: Int): Int = when (c) { 0 -> 0; in 1..11 -> 2; in 12..17 -> 3; in 18..23 -> 4; in 24..28 -> 5; else -> -1 }

        fun constellationDe(c: Int): Int = bitsDe(c)

        /** The PLFRAME's length in symbols, header included; −1 for a reserved MODCOD. */
        fun longueur(c: Int, type: Int): Int {
            val b = bitsDe(c)
            if (b < 0) return -1
            if (b == 0) return CouchePhysique.SYMBOLES_VIDE
            val slots = (if (type and 2 != 0) 16_200 else 64_800) / b / 90
            return 90 * (slots + 1) + (if (type and 1 != 0) 36 * ((slots - 1) / 16) else 0)
        }

        fun longueurMax(c: Int, type: Int): Int = maxOf(longueur(c, type), 0)

        /** The MODCOD this receiver decodes (QPSK, 8PSK), else null. */
        fun modcodDe(c: Int, type: Int): Modcod? {
            if (c !in 1..17) return null
            return Modcod.tous((type and 2) != 0, (type and 1) != 0).firstOrNull { it.code == c }
        }

        /** Header symbol k's axis: j^(k mod 2)·e^{jπ/4}. */
        private val BASE_R = DoubleArray(90) { k -> if (k % 2 == 0) R2 else -R2 }
        private val BASE_I = DoubleArray(90) { R2 }
        private val SOF_BITS = IntArray(26) { (CouchePhysique.SOF shr (25 - it)) and 1 }
        private val REF_SOF_R = DoubleArray(26) { BASE_R[it] * (1 - 2 * SOF_BITS[it]) }
        private val REF_SOF_I = DoubleArray(26) { BASE_I[it] * (1 - 2 * SOF_BITS[it]) }
        /** The SOF's differentials: s_k·conj(s_{k−1}) = b_k·b_{k−1}·(±j); stored as the factor of j. */
        private val DIFF_SOF = DoubleArray(26) { k -> if (k == 0) 0.0 else (1 - 2 * SOF_BITS[k]) * (1 - 2 * SOF_BITS[k - 1]) * (if (k % 2 == 1) 1.0 else -1.0) }
        /** The PLS pairs' differentials (k = 27 + 2i, odd: +j), up to the pilots' bit. */
        private val DIFF_PLS = DoubleArray(32) { i ->
            val a = CouchePhysique.BROUILLAGE_PLS[2 * i] - '0'; val b = CouchePhysique.BROUILLAGE_PLS[2 * i + 1] - '0'
            ((1 - 2 * a) * (1 - 2 * b)).toDouble()
        }
        /** The 128 PLS codewords as scrambled, ±1 (bit 0: +1). */
        private val MOTS_PLS = Array(128) { c ->
            val e = CouchePhysique.enteteBits(c shr 2, c and 3)
            FloatArray(64) { j -> (1 - 2 * e[26 + j]).toFloat() }
        }
        private val ENTETES = arrayOfNulls<FloatArray>(128)

        /** The 90 header symbols of (MODCOD, TYPE), I and Q interleaved. */
        fun enTete(c: Int, type: Int): FloatArray {
            val i = (c and 31) * 4 + (type and 3)
            ENTETES[i]?.let { return it }
            val s = FloatArray(180)
            CouchePhysique.entete(c and 31, type and 3, s, 0)
            ENTETES[i] = s
            return s
        }

        private val CONNUS = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()

        /**
         * Frame (MODCOD, TYPE)'s known symbols, scrambled as sent (0: data):
         * the header, the pilot blocks, a dummy frame's whole body.
         */
        fun connus(c: Int, type: Int, l: Int): FloatArray = CONNUS.computeIfAbsent((c and 31) * 4 + (type and 3)) {
            val s = FloatArray(2 * l)
            System.arraycopy(enTete(c, type), 0, s, 0, 180)
            val seq = CouchePhysique.SEQUENCE_PL
            fun pilote(k: Int) {
                // (1+j)/√2 times j^R.
                var a = R2.toFloat(); var b = R2.toFloat()
                when (seq[k - 90].toInt()) { 1 -> { val t = a; a = -b; b = t }; 2 -> { a = -a; b = -b }; 3 -> { val t = a; a = b; b = -t } }
                s[2 * k] = a; s[2 * k + 1] = b
            }
            if (c == 0) for (k in 90 until l) pilote(k)
            else if (type and 1 != 0) {
                val slots = (if (type and 2 != 0) 16_200 else 64_800) / bitsDe(c) / 90
                var k = 90
                for (sl in 0 until slots) {
                    k += 90
                    if ((sl + 1) % 16 == 0 && sl + 1 < slots) { for (j in 0 until 36) pilote(k + j); k += 36 }
                }
            }
            s
        }
    }
}
