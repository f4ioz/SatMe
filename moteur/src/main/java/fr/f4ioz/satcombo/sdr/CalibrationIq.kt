/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A single tone at [decalageHz] from the LO, [amplitude] of full scale: what a calibration transmits. */
class TonIq(private val decalageHz: Double, private val debit: Double, private val amplitude: Double = 0.5) {
    private var c = 1.0
    private var s = 0.0

    fun remplis(b: ShortArray, n: Int = b.size / 2) {
        val w = 2 * PI * decalageHz / debit
        val cw = cos(w); val sw = sin(w)
        val a = amplitude * EtageIq.PLEINE_ECHELLE
        for (k in 0 until n) {
            b[2 * k] = Math.round(a * c).toInt().toShort()
            b[2 * k + 1] = Math.round(a * s).toInt().toShort()
            val nc = c * cw - s * sw
            s = c * sw + s * cw
            c = nc
        }
        val m = hypot(c, s)
        c /= m; s /= m
    }
}

/**
 * What a receiver hears of a calibration tone: the tone, the LO leakage
 * and the image (the tone mirrored around the LO), each as the power in a
 * few bins of an averaged, windowed spectrum (4-term Blackman–Harris: its
 * sidelobes under −90 dB), the latter two relative to the tone.
 */
class AnalyseTonIq(private val debit: Double, val n: Int = 16_384) {
    data class Resultat(
        /** The tone's peak level in the samples (dBFS): the receiver's gain set from it. */
        val creteDbfs: Double,
        val tonDb: Double,
        /** Linear, relative to the tone. */
        val fuite: Double,
        val image: Double,
        /** The noise in as many bins, relative to the tone: the floor of the two above. */
        val bruit: Double,
        /** Where the tone was found less where it was expected (Hz). */
        val ecartHz: Double,
    ) {
        val fuiteDbc: Double get() = db(fuite)
        val imageDbc: Double get() = db(image)
        val bruitDbc: Double get() = db(bruit)
    }

    private val fenetre = DoubleArray(n) { k ->
        val x = 2 * PI * k / n
        0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)
    }

    /** The bin's frequency (Hz), negative ones in the upper half. */
    private fun frequence(k: Int): Double = (if (k < n / 2) k else k - n) * debit / n
    private fun bin(f: Double): Int = Math.floorMod(Math.round(f * n / debit).toInt(), n)

    /**
     * [paires] I/Q pairs of [iq] analysed for a tone expected at [tonHz],
     * the LO at [loHz] and the image at [imageHz] (from the receiver's
     * centre); the tone searched within ±[rechercheHz]. Null when too short
     * or nothing stands out.
     */
    fun analyse(iq: ShortArray, paires: Int, tonHz: Double, loHz: Double, imageHz: Double, rechercheHz: Double = 60_000.0): Resultat? {
        val blocs = paires / n
        if (blocs < 1) return null
        val p = DoubleArray(n)
        val re = DoubleArray(n); val im = DoubleArray(n)
        var crete = 0
        for (b in 0 until blocs) {
            val o = 2 * b * n
            for (k in 0 until n) {
                val i = iq[o + 2 * k].toInt(); val q = iq[o + 2 * k + 1].toInt()
                crete = maxOf(crete, abs(i), abs(q))
                re[k] = i * fenetre[k]; im[k] = q * fenetre[k]
            }
            Fft.transform(re, im)
            for (k in 0 until n) p[k] += re[k] * re[k] + im[k] * im[k]
        }
        // The tone: the strongest bin around where it should be, its centre interpolated (log-parabola).
        val l = (rechercheHz * n / debit).toInt()
        val k0 = bin(tonHz)
        var kt = k0
        for (d in -l..l) { val k = Math.floorMod(k0 + d, n); if (p[k] > p[kt]) kt = k }
        val y0 = ln(p[Math.floorMod(kt - 1, n)] + 1e-30); val y1 = ln(p[kt] + 1e-30); val y2 = ln(p[Math.floorMod(kt + 1, n)] + 1e-30)
        val den = y0 - 2 * y1 + y2
        val frac = if (den < 0) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
        var ftrouve = frequence(kt) + frac * debit / n
        // Folded back near the expected one (the search may wrap around).
        while (ftrouve - tonHz > debit / 2) ftrouve -= debit
        while (tonHz - ftrouve > debit / 2) ftrouve += debit
        val ecart = ftrouve - tonHz
        val ton = somme(p, kt)
        val tri = p.copyOf().also { it.sort() }
        val bruit = tri[n / 2] * (2 * LARGEUR + 1) / MEDIANE_SUR_MOYENNE
        if (ton <= 4 * bruit) return null
        val fuite = somme(p, bin(loHz + ecart))
        val image = somme(p, bin(imageHz + ecart))
        return Resultat(20 * log10(max(crete, 1) / 32768.0), 10 * log10(ton), fuite / ton, image / ton, bruit / ton, ecart)
    }

    private fun somme(p: DoubleArray, k: Int): Double {
        var s = 0.0
        for (d in -LARGEUR..LARGEUR) s += p[Math.floorMod(k + d, n)]
        return s
    }

    companion object {
        /** Bins each side summed: the window's main lobe and a little drift. */
        const val LARGEUR = 5
        /** A noise bin's power (exponential): its median is ln 2 of its mean. */
        const val MEDIANE_SUR_MOYENNE = 0.6931
        fun db(v: Double): Double = 10 * log10(max(v, 1e-15))
    }
}

/**
 * The closed loop: from the levels measured for a correction, the
 * correction that minimises the LO leakage (the DC pair) and then the
 * image (the gain and phase pair), two rounds of each.
 *
 * Both are, near their best, a bowl in their pair (the leakage |a + b·d|²
 * in the complex offset d; the image |a' + b'·(g + jφ)/2|²): five
 * measurements around a point (it, and ± a step on each axis) give the
 * bowl's bottom on each axis by a parabola. The step then shrinks to where
 * the bowl rises as high as what is left, so the measurements keep standing
 * above the noise.
 */
class CalibrationIq(
    /** The levels with this correction applied, or null (no tone heard, stopped). */
    private val mesure: (ParametresIq) -> AnalyseTonIq.Resultat?,
    private val journal: (String) -> Unit = {},
    private val arret: () -> Boolean = { false },
) {
    data class Resultat(
        val avant: AnalyseTonIq.Resultat,
        val apres: AnalyseTonIq.Resultat,
        val parametres: ParametresIq,
        val mesures: Int,
    )

    var mesures = 0; private set

    private fun m(p: ParametresIq): AnalyseTonIq.Resultat? {
        if (arret()) return null
        mesures++
        return mesure(p)
    }

    private class Axe(val nom: String, val pas0: Double, val pasMin: Double, val lit: (ParametresIq) -> Double, val ecrit: (ParametresIq, Double) -> ParametresIq)

    private val dcI = Axe("DC I", 0.004, 2e-5, { it.dcI }, { p, v -> p.copy(dcI = v) })
    private val dcQ = Axe("DC Q", 0.004, 2e-5, { it.dcQ }, { p, v -> p.copy(dcQ = v) })
    private val gain = Axe("gain", 0.02, 1e-4, { it.gain }, { p, v -> p.copy(gain = v) })
    private val phase = Axe("phase", 1.0, 0.005, { it.phaseDeg }, { p, v -> p.copy(phaseDeg = v) })

    /** From [depart] (its ppm kept as it is): the correction found, with the levels before (neutral) and after. Null if stopped or nothing heard. */
    fun lance(depart: ParametresIq, tours: Int = 2, iterations: Int = 2): Resultat? {
        mesures = 0
        val avant = m(ParametresIq(ppm = depart.ppm)) ?: return null
        journal("sans correction : fuite %.1f dBc, image %.1f dBc".format(avant.fuiteDbc, avant.imageDbc))
        var p = depart.borne()
        val pas = doubleArrayOf(dcI.pas0, gain.pas0)
        for (t in 0 until tours) {
            for (k in 0 until iterations) {
                val r = descend(p, dcI, dcQ, pas[0], { it.fuite }) ?: return null
                p = r.first; pas[0] = r.second
            }
            for (k in 0 until iterations) {
                val r = descend(p, gain, phase, pas[1], { it.image }) ?: return null
                p = r.first; pas[1] = r.second
            }
        }
        val apres = m(p) ?: return null
        journal("corrigé : fuite %.1f dBc, image %.1f dBc".format(apres.fuiteDbc, apres.imageDbc))
        return Resultat(avant, apres, p, mesures)
    }

    /** One step on a pair of axes, [pas] in the first axis' units (the second scaled as their initial steps); the new point and step. */
    private fun descend(p0: ParametresIq, x: Axe, y: Axe, pas: Double, f: (AnalyseTonIq.Resultat) -> Double): Pair<ParametresIq, Double>? {
        val ech = y.pas0 / x.pas0
        val dx = pas; val dy = pas * ech
        val r0 = m(p0) ?: return null
        val f0 = f(r0)
        val fxp = f(m(x.ecrit(p0, x.lit(p0) + dx)) ?: return null)
        val fxm = f(m(x.ecrit(p0, x.lit(p0) - dx)) ?: return null)
        val fyp = f(m(y.ecrit(p0, y.lit(p0) + dy)) ?: return null)
        val fym = f(m(y.ecrit(p0, y.lit(p0) - dy)) ?: return null)
        fun bas(fp: Double, fm: Double, d: Double): Pair<Double, Double> {
            val courbe = fp + fm - 2 * f0
            // No bowl seen (noise): a step towards the lower side.
            if (courbe <= 1e-3 * (fp + fm)) return (if (fp < fm) d else if (fm < fp) -d else 0.0) to 0.0
            return (-d * (fp - fm) / (2 * courbe)).coerceIn(-4 * d, 4 * d) to courbe / (2 * d * d)
        }
        val (ex, cx) = bas(fxp, fxm, dx)
        val (ey, cy) = bas(fyp, fym, dy)
        val p1 = y.ecrit(x.ecrit(p0, x.lit(p0) + ex), y.lit(p0) + ey).borne()
        val r1 = m(p1) ?: return null
        val f1 = f(r1)
        // The step for next time: where the bowl rises by what is left (and the noise), from the first axis' curvature.
        val reste = max(f1, r1.bruit)
        val nouveau = if (cx > 0) sqrt(reste / cx) else pas
        val pasSuivant = nouveau.coerceIn(x.pasMin, x.pas0 * 2)
        journal("%s/%s : %.1f → %.1f dB (pas %.2g)".format(x.nom, y.nom, AnalyseTonIq.db(f0), AnalyseTonIq.db(f1), pasSuivant))
        return if (f1 < f0) p1 to pasSuivant else p0 to max(pas / 2, x.pasMin)
    }
}

/**
 * A calibration bench: a tone transmitted with a live [correction], heard
 * by another receiver ([rx]), tuned [decalageRxHz] below the transmitter's
 * LO so that the tone, the LO and the image all fall on one side of the
 * receiver's own DC and image. [mesure] sets the correction, waits for it to
 * reach the air ([attenteMs]: the transmitter's buffers), throws away what
 * the receiver had queued, then analyses what comes.
 */
class BancIq(
    private val correction: CorrectionIq,
    private val rx: SourceIq,
    private val debitRx: Double,
    /** The tone's offset from the transmitter's LO (Hz). */
    val tonHz: Double,
    /** The transmitter's LO less the receiver's centre (Hz). */
    val decalageRxHz: Double,
    private val attenteMs: Long,
    private val paires: Int = 65_536,
    private val analyse: AnalyseTonIq = AnalyseTonIq(debitRx),
    private val dort: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val tampon = ShortArray(2 * paires)

    fun mesure(p: ParametresIq): AnalyseTonIq.Resultat? {
        correction.parametres = p
        return ecoute()
    }

    /** The levels with the correction as it is (the page's sliders moving it). */
    fun ecoute(): AnalyseTonIq.Resultat? {
        dort(attenteMs)
        if (!lis()) return null
        return analyse.analyse(tampon, paires, decalageRxHz + tonHz, decalageRxHz, decalageRxHz - tonHz)
    }

    /** What the receiver had queued dropped, then [paires] fresh pairs; false when it is gone. */
    private fun lis(): Boolean {
        // Larger than any receiver's block: none cut short.
        val b = ShortArray(262_144)
        while (true) { val n = rx.read16(b, 0); if (n < 0) return false; if (n == 0) break }
        var pos = 0
        var vides = 0
        while (pos < tampon.size) {
            val n = rx.read16(b, 500)
            if (n < 0) return false
            if (n == 0) { if (++vides > 6) return false; continue }
            val c = minOf(n, tampon.size - pos)
            System.arraycopy(b, 0, tampon, pos, c)
            pos += c
        }
        return true
    }
}
