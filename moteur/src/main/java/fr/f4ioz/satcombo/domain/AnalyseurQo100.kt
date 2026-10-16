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
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What each signal on QO-100's narrow transponder is, from the receiver's
 * raw IQ stream.
 *
 * One FFT over the whole stream, frames back to back (no overlap, no gap):
 * at the receiver's 1 058 400 S/s, 65 536 points give 16.15 Hz bins and
 * 62 ms frames — fine enough to see a CW element, a syllable, an FT8 symbol
 * (160 ms). Finer than a bin: the phase of the strongest bin from one frame
 * to the next gives a steady tone's frequency within a hertz (the frames
 * touch, so the phase turns by exactly the tone's offset from the bin).
 *
 * Then: the noise floor per kilohertz, the lines standing above it on a
 * smoothed spectrum, followed frame after frame as « pistes » with a stable
 * number, each one's history kept a minute (level, on or off, the strongest
 * tone, how spread) and judged by [ClassifieurQo100].
 *
 * The frequency scale is the stream's (bin 0 at −rate/2); the caller gives
 * the sky frequency of the stream's centre when asking for the list. An
 * inverting converter's stream is mirrored before ([pousse8], [pousse16]),
 * so a voice's sideband keeps its side.
 *
 * Nothing Android: the PC feeds it now, the phone can later.
 */
class AnalyseurQo100(val cadence: Double, val taille: Int = 65_536) {

    init { require(taille >= 256 && taille and (taille - 1) == 0) { "taille non puissance de deux : $taille" } }

    val hzParCase = cadence / taille
    val dureeTrameMs = taille * 1000.0 / cadence

    // ------------------------------------------------------------------ the FFT

    private val fft = FftQo100(taille)
    private val fenetre = DoubleArray(taille) { 0.5 - 0.5 * cos(2.0 * PI * it / taille) }
    private val bufI = DoubleArray(taille)
    private val bufQ = DoubleArray(taille)
    private var plein = 0
    private val re = DoubleArray(taille)
    private val im = DoubleArray(taille)

    /** Bins analysed: [binBas] until [binHaut], 0 being −rate/2 (the transponder, not the whole stream). */
    var binBas = 0; private set
    var binHaut = taille; private set
    private var m = taille

    private var p = DoubleArray(m)
    private var curRe = DoubleArray(m)
    private var curIm = DoubleArray(m)
    private var prevRe = DoubleArray(m)
    private var prevIm = DoubleArray(m)
    private var prevValide = false
    private var bruit = DoubleArray(m)
    private var moyenne = DoubleArray(m)
    private var tenue = DoubleArray(m)

    private val blocs get() = (m + BLOC - 1) / BLOC
    private var plancherBloc = DoubleArray(blocs)
    private var plancherLisse = DoubleArray(blocs)
    private var plancherPret = false
    private val tri = DoubleArray(BLOC)

    /** Frames analysed since the start. */
    var trames = 0L; private set
    /** Time spent in the frames (ns), to tell what it costs. */
    var nanos = 0L; private set

    /**
     * Analyse only bins [bas] until [haut] (0 is −rate/2): the transponder
     * and a margin. Changing it forgets everything.
     */
    fun bande(bas: Int, haut: Int) {
        val b = bas.coerceIn(0, taille - 16)
        val h = haut.coerceIn(b + 16, taille)
        if (b == binBas && h == binHaut) return
        binBas = b; binHaut = h; m = h - b
        p = DoubleArray(m); curRe = DoubleArray(m); curIm = DoubleArray(m)
        prevRe = DoubleArray(m); prevIm = DoubleArray(m)
        bruit = DoubleArray(m); moyenne = DoubleArray(m); tenue = DoubleArray(m)
        plancherBloc = DoubleArray(blocs); plancherLisse = DoubleArray(blocs)
        reinitialise()
    }

    /** The receiver moved (another PLL): every piste forgotten, the frame started again. */
    fun reinitialise() {
        plein = 0; prevValide = false; plancherPret = false
        moyenne.fill(0.0); tenue.fill(0.0)
        pistes.clear(); candidates.clear()
    }

    /** Samples were lost: the next frame starts afresh, the phase from frame to frame not trusted once. */
    fun discontinuite() { plein = 0; prevValide = false }

    // ------------------------------------------------------------------ feeding

    /** [n] complex samples, received at [tMs] (the end of the block). */
    fun pousse(i: FloatArray, q: FloatArray, n: Int, tMs: Long) {
        for (k in 0 until n) {
            bufI[plein] = i[k].toDouble(); bufQ[plein] = q[k].toDouble()
            if (++plein == taille) { trame(tMs - ((n - 1 - k) * 1000.0 / cadence).toLong()); plein = 0 }
        }
    }

    /** The dongle's bytes ([len] of them, I then Q, offset 127.5); [miroir]: Q negated (inverting converter). */
    fun pousse8(iq: ByteArray, len: Int, tMs: Long, miroir: Boolean = false) {
        val n = len / 2
        val s = if (miroir) -1.0 else 1.0
        for (k in 0 until n) {
            bufI[plein] = ((iq[2 * k].toInt() and 0xff) - 127.5) / 127.5
            bufQ[plein] = s * ((iq[2 * k + 1].toInt() and 0xff) - 127.5) / 127.5
            if (++plein == taille) { trame(tMs - ((n - 1 - k) * 1000.0 / cadence).toLong()); plein = 0 }
        }
    }

    /** As [pousse8], from signed 16-bit I, Q pairs ([len] shorts). */
    fun pousse16(iq: ShortArray, len: Int, tMs: Long, miroir: Boolean = false) {
        val n = len / 2
        val s = if (miroir) -1.0 / 32768.0 else 1.0 / 32768.0
        for (k in 0 until n) {
            bufI[plein] = iq[2 * k] / 32768.0
            bufQ[plein] = iq[2 * k + 1] * s
            if (++plein == taille) { trame(tMs - ((n - 1 - k) * 1000.0 / cadence).toLong()); plein = 0 }
        }
    }

    // ------------------------------------------------------------------ one frame

    private fun trame(tMs: Long) {
        val debut = System.nanoTime()
        for (k in 0 until taille) { re[k] = bufI[k] * fenetre[k]; im[k] = bufQ[k] * fenetre[k] }
        fft.transforme(re, im)
        val moitie = taille / 2
        for (j in 0 until m) {
            val src = (binBas + j + moitie) and (taille - 1)
            val a = re[src]; val b = im[src]
            curRe[j] = a; curIm[j] = b
            p[j] = a * a + b * b
        }
        plancher()
        // Smoothed (~0.4 s) and held (falls 5 dB a second): a CW gap or a breath does not lose the line.
        for (j in 0 until m) {
            val r = p[j] / bruit[j]
            moyenne[j] += ALPHA * (r - moyenne[j])
            tenue[j] = max(moyenne[j], tenue[j] * CHUTE)
        }
        suit(tMs)
        val t = prevRe; prevRe = curRe; curRe = t
        val u = prevIm; prevIm = curIm; curIm = u
        prevValide = true
        trames++
        nanos += System.nanoTime() - debut
    }

    /**
     * The noise per bin: per kilohertz, the 30th percentile (a block half
     * filled by a station still shows its noise there), then the third
     * lowest of the eleven around (two voices side by side fill a few
     * blocks, never eight), followed slowly, between block centres
     * interpolated.
     */
    private fun plancher() {
        val nb = blocs
        for (b in 0 until nb) {
            val a = b * BLOC
            val z = min(m, a + BLOC)
            val c = z - a
            System.arraycopy(p, a, tri, 0, c)
            java.util.Arrays.sort(tri, 0, c)
            plancherBloc[b] = tri[(c * 3) / 10] / P30
        }
        val v = DoubleArray(2 * VOISINS + 1)
        for (b in 0 until nb) {
            var c = 0
            for (d in -VOISINS..VOISINS) { val x = b + d; if (x in 0 until nb) v[c++] = plancherBloc[x] }
            java.util.Arrays.sort(v, 0, c)
            val x = if (c >= 7) v[2] else v[c / 2]
            plancherLisse[b] = if (plancherPret) plancherLisse[b] + 0.15 * (x - plancherLisse[b]) else x
        }
        plancherPret = true
        for (j in 0 until m) {
            val f = (j + 0.5) / BLOC - 0.5
            val b0 = floor(f).toInt().coerceIn(0, nb - 1)
            val b1 = min(nb - 1, b0 + 1)
            val w = (f - b0).coerceIn(0.0, 1.0)
            bruit[j] = max(1e-30, plancherLisse[b0] * (1 - w) + plancherLisse[b1] * w)
        }
    }

    // ------------------------------------------------------------------ the pistes

    private var prochainId = 1
    private val pistes = ArrayList<Piste>()
    private val candidates = ArrayList<Piste>()

    /** A line followed from frame to frame: its extent (bins), its history. */
    inner class Piste(var lo: Double, var hi: Double, val depuisMs: Long) {
        var id = 0
        var vueMs = depuisMs
        var detections = 0
        val hist = Historique(HISTORIQUE)
        /** Excess power summed per bin over the frames it was on, from bin [specBas]. */
        var spec = DoubleArray(0); var specBas = 0
        var specTrames = 0
        var refSnr = 0.0
        var classe = ClasseQo100.INCONNU
        var confiance = 0.0
        var details = ""
        val bornes = ArrayDeque<Triple<Long, Double, Double>>()
    }

    private data class Detection(val a: Int, val b: Int, val pic: Double)

    private fun detections(): List<Detection> {
        val out = ArrayList<Detection>()
        var j = 0
        while (j < m) {
            if (tenue[j] < SEUIL) { j++; continue }
            var fin = j; var k = j; var pic = 0.0; var au = 0
            while (k < m && k - fin <= ECART_FUSION) {
                if (tenue[k] >= SEUIL) { fin = k; au++; pic = max(pic, tenue[k]) }
                k++
            }
            if (au >= 2) out += Detection(j, fin, pic)
            j = fin + 1
        }
        return out
    }

    private fun suit(tMs: Long) {
        val dets = detections()
        val vues = HashSet<Piste>()
        for (d in dets) {
            var lo = (binBas + d.a).toDouble(); var hi = (binBas + d.b).toDouble()
            val touchees = (pistes + candidates).filter { it.hi + 1 >= lo && it.lo - 1 <= hi }
            // Narrow stations followed a while (FT8 side by side) are never melted into one by a frame bridging them.
            val etablies = touchees.filter { etablie(it, tMs) }
            val pst = if (touchees.isEmpty()) {
                Piste(lo, hi, tMs).also { candidates += it }
            } else if (etablies.size >= 2) {
                val g = etablies.maxBy { min(it.hi, hi) - max(it.lo, lo) }
                lo = max(lo, g.lo); hi = min(hi, g.hi)
                g
            } else {
                // Several touched: the oldest takes the others in.
                val g = touchees.minWith(compareBy<Piste>({ if (it.id == 0) 1 else 0 }, { it.depuisMs }))
                for (o in touchees) if (o !== g) { g.lo = min(g.lo, o.lo); g.hi = max(g.hi, o.hi); pistes.remove(o); candidates.remove(o); vues.remove(o) }
                g
            }
            pst.lo = min(pst.lo, lo); pst.hi = max(pst.hi, hi)
            pst.bornes.addLast(Triple(tMs, lo, hi))
            pst.vueMs = tMs
            pst.detections++
            vues += pst
        }
        val tout = pistes + candidates
        for (pst in tout) {
            // The extent: what was detected these last 20 s (it widens at once, narrows slowly).
            while (pst.bornes.isNotEmpty() && tMs - pst.bornes.first().first > 20_000L) pst.bornes.removeFirst()
            if (pst.bornes.isNotEmpty() && trames % 16 == 0L) {
                pst.lo = pst.bornes.minOf { it.second }; pst.hi = pst.bornes.maxOf { it.third }
            }
            mesure(pst, tMs, pst in vues)
        }
        // Candidates confirmed once seen most of the last second and a half.
        val it = candidates.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (tMs - c.vueMs > 1_000L) { it.remove(); continue }
            if (c.detections >= CONFIRMATION && tMs - c.depuisMs >= CONFIRMATION * dureeTrameMs * 0.9) {
                c.id = prochainId++; pistes += c; it.remove()
            }
        }
        pistes.removeAll { tMs - it.vueMs > OUBLI_MS }
    }

    private fun etablie(p: Piste, tMs: Long) = p.id != 0 && tMs - p.depuisMs > 5_000L && (p.hi - p.lo + 1) * hzParCase < ETROITE_HZ

    /** The frame as seen by a piste:its level, on or off, its strongest tone, how spread. */
    private fun mesure(pst: Piste, tMs: Long, vue: Boolean) {
        val a = max(0, floor(pst.lo).toInt() - 1 - binBas)
        val b = min(m - 1, ceil(pst.hi).toInt() + 1 - binBas)
        if (b < a) return
        var d = 0.0; var nb = 0.0; var ex = 0.0; var kp = a; var pmax = -1.0; var cg = 0.0
        for (j in a..b) {
            val e = p[j] - bruit[j]
            d += e; nb += bruit[j]
            // Only the bins well above the noise for the shape: the noise's own peaks would widen everything.
            if (p[j] > NET * bruit[j]) { ex += e; cg += e * j }
            if (p[j] > pmax) { pmax = p[j]; kp = j }
        }
        val n = b - a + 1
        val snr = d / nb
        val centre = if (ex > 0) cg / ex else (a + b) / 2.0
        var s2 = 0.0; var conc = 0.0
        for (j in a..b) {
            if (p[j] <= NET * bruit[j]) continue
            val e = p[j] - bruit[j]
            s2 += e * (j - centre) * (j - centre); if (abs(j - kp) <= 1) conc += e
        }
        val etal = if (ex > 0) sqrt(s2 / ex) else 0.0
        conc = if (ex > 0) conc / ex else 0.0
        var fin = Double.NaN
        if (prevValide) {
            val x = curRe[kp] * prevRe[kp] + curIm[kp] * prevIm[kp]
            val y = curIm[kp] * prevRe[kp] - curRe[kp] * prevIm[kp]
            fin = binBas + kp + atan2(y, x) / (2 * PI)
        }
        // On: above the noise's spread in the band, and not a tenth of the piste's usual level.
        val sigma = sqrt(1.5 / n)
        // The usual level in bin-noise units (whatever the extent, which changes), followed over ~15 s.
        val niveau = d / (nb / n)
        pst.refSnr = max(niveau, pst.refSnr * 0.996)
        val on = snr > 4 * sigma && niveau > 0.1 * pst.refSnr
        pst.hist.ajoute(tMs, snr.toFloat(), on, fin.toFloat(), conc.toFloat(), etal.toFloat(), (binBas + centre).toFloat(),
            (nb / n).toFloat(), n)
        if (on) {
            val lo = binBas + a; val hi = binBas + b
            if (pst.spec.isEmpty()) { pst.specBas = lo - 8; pst.spec = DoubleArray(hi - lo + 17) }
            if (lo < pst.specBas || hi >= pst.specBas + pst.spec.size) {
                val nBas = min(lo - 8, pst.specBas); val nHaut = max(hi + 8, pst.specBas + pst.spec.size - 1)
                val ns = DoubleArray(nHaut - nBas + 1)
                System.arraycopy(pst.spec, 0, ns, pst.specBas - nBas, pst.spec.size)
                pst.spec = ns; pst.specBas = nBas
            }
            // Over a minute, the old frames fade (a station drifting is not smeared forever).
            if (pst.specTrames > 1000) for (k in pst.spec.indices) pst.spec[k] *= 0.999
            // Signed: the noise averages out to nothing over the frames.
            for (j in a..b) pst.spec[binBas + j - pst.specBas] += (p[j] - bruit[j]) / bruit[j]
            pst.specTrames++
        }
    }

    // ------------------------------------------------------------------ the list

    /**
     * The signals now, each judged: [cielCentreHz] is the sky frequency of
     * the stream's centre (bin taille/2), already corrected for the LNB.
     * Only those seen within [recentMs].
     */
    fun signaux(tMs: Long, cielCentreHz: Double, recentMs: Long = 20_000L): List<SignalQo100> {
        val out = ArrayList<SignalQo100>()
        val paires = paires(tMs)
        for ((a, b) in paires) {
            val ma = mesures(a) ?: continue; val mb = mesures(b) ?: continue
            val bas = min(ma.centreBin - ma.largeurHz / 2 / hzParCase, mb.centreBin - mb.largeurHz / 2 / hzParCase)
            val haut = max(ma.centreBin + ma.largeurHz / 2 / hzParCase, mb.centreBin + mb.largeurHz / 2 / hzParCase)
            val ciel = cielCentreHz + ((bas + haut) / 2 - taille / 2) * hzParCase
            val ecart = abs(ma.centreBin - mb.centreBin) * hzParCase
            out += SignalQo100(min(a.id, b.id), Math.round(ciel), Math.round((haut - bas) * hzParCase).toInt(), ClasseQo100.RTTY, 0.8,
                min(a.depuisMs, b.depuisMs), tMs - max(a.vueMs, b.vueMs), Math.round(max(ma.snrDb, mb.snrDb) * 10) / 10.0,
                "2 raies à ${Math.round(ecart)} Hz, en alternance")
        }
        val appariees = paires.flatMap { listOf(it.first, it.second) }.toSet()
        for (pst in pistes) {
            if (tMs - pst.vueMs > recentMs || pst in appariees) continue
            val mes = mesures(pst) ?: continue
            val ciel = cielCentreHz + (mes.centreBin - taille / 2) * hzParCase
            val (c, conf, det) = ClassifieurQo100.juge(mes, ciel.toLong())
            // The receiver's own line at its centre (its oscillator leaking through): not a station.
            if (c == ClasseQo100.PORTEUSE && abs(mes.centreBin - taille / 2) < 1.5) continue
            // Unknown and hardly over the floor: a bump of the noise, not worth a label.
            if (c == ClasseQo100.INCONNU && pst.classe == ClasseQo100.INCONNU && mes.reliefDb < 3.0 && mes.snrDb < 6.0) continue
            // A verdict changes only when the new one is clearly better: no flicker from second to second.
            if (c != pst.classe) {
                if (pst.classe == ClasseQo100.INCONNU || conf > pst.confiance + 0.1 || conf >= 0.5) {
                    pst.classe = c; pst.confiance = conf
                } else pst.confiance = max(0.0, pst.confiance - 0.05)
            } else pst.confiance += 0.3 * (conf - pst.confiance)
            pst.details = if (pst.classe == c) det else pst.details
            out += SignalQo100(pst.id, Math.round(ciel), Math.round(mes.largeurHz).toInt(), pst.classe,
                Math.round(pst.confiance * 100) / 100.0, pst.depuisMs, tMs - pst.vueMs, Math.round(mes.snrDb * 10) / 10.0, pst.details)
        }
        return rattache(out).sortedBy { it.hz }
    }

    /**
     * RTTY seen as two pistes: two narrow lines 140–220 Hz apart, both there
     * nearly all the time, one up when the other is down (frame by frame
     * their levels go opposite ways; two stations side by side do not).
     */
    private fun paires(tMs: Long): List<Pair<Piste, Piste>> {
        val etroites = pistes.filter { tMs - it.vueMs < 3_000L && (it.hi - it.lo + 1) * hzParCase < 140 && it.hist.taille >= 64 }
            .sortedBy { it.lo }
        val out = ArrayList<Pair<Piste, Piste>>()
        val prises = HashSet<Piste>()
        for (i in etroites.indices) for (j in i + 1 until etroites.size) {
            val a = etroites[i]; val b = etroites[j]
            if (a in prises || b in prises) continue
            val ecart = ((b.lo + b.hi) - (a.lo + a.hi)) / 2 * hzParCase
            if (ecart !in 140.0..220.0) continue
            if (correlation(a.hist, b.hist) < -0.3) { out += a to b; prises += a; prises += b }
        }
        return out
    }

    /** Correlation of two pistes' levels (dB) over their last common frames. */
    private fun correlation(a: Historique, b: Historique): Double {
        val n = min(300, min(a.taille, b.taille))
        if (n < 64) return 0.0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var k = 0
        for (i in 0 until n) {
            val ia = a.idx(a.taille - n + i); val ib = b.idx(b.taille - n + i)
            if (a.t[ia] != b.t[ib]) continue
            if (!a.on[ia] && !b.on[ib]) continue
            val x = 10 * kotlin.math.log10(max(a.snr[ia].toDouble(), 1e-3)); val y = 10 * kotlin.math.log10(max(b.snr[ib].toDouble(), 1e-3))
            sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; k++
        }
        if (k < 48) return 0.0
        val cov = sxy / k - sx / k * sy / k
        val vx = sxx / k - sx / k * sx / k; val vy = syy / k - sy / k * sy / k
        return if (vx <= 0 || vy <= 0) 0.0 else cov / sqrt(vx * vy)
    }

    /**
     * A wide signal's own pieces seen apart — SSTV's 1200 Hz sync below its
     * picture, a BPSK beacon's side lobes — are not stations: an unknown
     * weaker line within [PROCHE_HZ] of such a signal's edge is taken into it.
     */
    private fun rattache(l: List<SignalQo100>): List<SignalQo100> {
        val larges = setOf(ClasseQo100.SSTV, ClasseQo100.BALISE, ClasseQo100.NUMERIQUE_LARGE)
        val out = l.toMutableList()
        for (s in l) {
            if (s.classe != ClasseQo100.INCONNU) continue
            val hote = out.filter { it.classe in larges && it.snrDb > s.snrDb }
                .minByOrNull { abs(it.hz - s.hz) - (it.largeurHz + s.largeurHz) / 2.0 } ?: continue
            if (abs(hote.hz - s.hz) - (hote.largeurHz + s.largeurHz) / 2.0 > PROCHE_HZ) continue
            out.remove(s)
            val bas = min(hote.hz - hote.largeurHz / 2, s.hz - s.largeurHz / 2)
            val haut = max(hote.hz + hote.largeurHz / 2, s.hz + s.largeurHz / 2)
            out[out.indexOf(hote)] = hote.copy(hz = (bas + haut) / 2, largeurHz = (haut - bas).toInt())
        }
        return out
    }

    /** The measures of a piste over its history, for the judge; null when too young. */
    fun mesures(pst: Piste): MesuresSignal? {
        val h = pst.hist
        if (h.taille < 16) return null
        return MesuresSignal.depuis(h, pst.spec, pst.specBas, pst.specTrames, hzParCase, dureeTrameMs)
    }

    /** The pistes now (tests). */
    fun pistes(): List<Piste> = pistes.toList()

    companion object {
        /** About a kilohertz per noise block at 16 Hz bins. */
        const val BLOC = 64
        const val VOISINS = 5
        /** The 30th percentile of exponential noise is 0.357 times its mean. */
        val P30 = -ln(0.7)
        const val ALPHA = 0.15
        const val CHUTE = 0.93
        /** Smoothed spectrum over noise, to stand as a line (4.8 dB). */
        const val SEUIL = 3.0
        /** Bins below the threshold bridged inside one line (~16 Hz). */
        const val ECART_FUSION = 1
        /** Under this width a piste followed 5 s keeps to itself. */
        const val ETROITE_HZ = 160.0
        const val CONFIRMATION = 16
        /** A bin counts in a frame's shape above four times the noise. */
        const val NET = 4.0
        /** A piece this close to a wide signal's edge is part of it. */
        const val PROCHE_HZ = 450.0
        const val OUBLI_MS = 40_000L
        /** Frames kept per piste: a minute at 62 ms. */
        const val HISTORIQUE = 1024
    }
}

/** A frame per entry, the last [capacite] kept. */
class Historique(val capacite: Int) {
    val t = LongArray(capacite)
    val snr = FloatArray(capacite)
    val on = BooleanArray(capacite)
    val fin = FloatArray(capacite)
    val conc = FloatArray(capacite)
    val etal = FloatArray(capacite)
    val centre = FloatArray(capacite)
    val bruitCase = FloatArray(capacite)
    val cases = IntArray(capacite)
    private var pos = 0
    var taille = 0; private set

    fun ajoute(tMs: Long, s: Float, o: Boolean, f: Float, c: Float, e: Float, ce: Float, br: Float, n: Int) {
        t[pos] = tMs; snr[pos] = s; on[pos] = o; fin[pos] = f; conc[pos] = c; etal[pos] = e; centre[pos] = ce
        bruitCase[pos] = br; cases[pos] = n
        pos = (pos + 1) % capacite
        if (taille < capacite) taille++
    }

    /** Index in the arrays of the [i]-th entry kept, oldest first. */
    fun idx(i: Int): Int = (pos - taille + i + capacite) % capacite
}

/** A signal heard on the transponder, judged. */
data class SignalQo100(
    /** Stays the same while the signal is followed. */
    val id: Int,
    /** Sky frequency of its centre. */
    val hz: Long,
    val largeurHz: Int,
    val classe: ClasseQo100,
    /** 0 to 1. */
    val confiance: Double,
    /** Followed since (ms, epoch). */
    val depuisMs: Long,
    /** Last seen this long ago (ms): an FT8 station is silent every other slot. */
    val vuIlYaMs: Long,
    /** Over the noise in 2.5 kHz. */
    val snrDb: Double,
    val details: String,
)

/** Radix-2 FFT with its tables computed once: 65 536 points, sixteen times a second. */
internal class FftQo100(private val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(-2.0 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(-2.0 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        var bits = 0; while (1 shl bits < n) bits++
        for (i in 0 until n) { var x = i; var y = 0; repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }; r[i] = y }
    }

    fun transforme(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val pas = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val l = j + half
                    val vr = re[l] * wr - im[l] * wi
                    val vi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - vr; im[l] = im[j] - vi
                    re[j] += vr; im[j] += vi
                    k += pas
                }
                i += len
            }
            len = len shl 1
        }
    }
}
