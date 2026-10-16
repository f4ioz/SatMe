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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What a signal on the narrow transponder looks like it is. */
enum class ClasseQo100(val cle: String, val libelle: String) {
    BLU("blu", "BLU (phonie)"),
    CW("cw", "CW"),
    FT8("ft8", "FT8"),
    FT4("ft4", "FT4"),
    PSK31("psk31", "PSK31"),
    RTTY("rtty", "RTTY"),
    SSTV("sstv", "SSTV"),
    PORTEUSE("porteuse", "porteuse"),
    BALISE("balise", "balise"),
    NUMERIQUE_LARGE("numerique_large", "numérique large"),
    INCONNU("inconnu", "inconnu"),
}

/**
 * A piste's history summed up: what the judge looks at. Every figure is
 * measured, frame by frame, on the receiver's spectrum ([AnalyseurQo100]).
 */
data class MesuresSignal(
    /** History length (s). */
    val dureeS: Double,
    val trames: Int,
    /** Centre of the occupied band, in the analyser's bins (fractional). */
    val centreBin: Double,
    /** Band holding 90 % of the power (Hz). */
    val largeurHz: Double,
    /** Over the noise in 2.5 kHz, typical frame when on. */
    val snrDb: Double,
    /** Share of the frames it was on. */
    val presence: Double,
    /** On/off changes per second. */
    val bascules: Double,
    /** Spread of the level when on (dB). */
    val enveloppeDb: Double,
    /** Spread of the strongest tone's frequency (Hz), the drift taken out. */
    val tonEcartHz: Double,
    /** Frames whose strongest tone was measured. */
    val nTons: Int,
    /** Tones on a 6.25 Hz grid (0 to 1). */
    val grilleFt8: Double,
    /** Distinct tones seen, and the span between the outer ones (Hz). */
    val tonsDistincts: Int,
    val etendueTonsHz: Double,
    /** A frame's own spread (Hz), typical. */
    val etalHz: Double,
    /** Share of a frame's power in its strongest 3 bins. */
    val concentration: Double,
    /** Flatness of the mean spectrum over its band (1: flat). */
    val platitude: Double,
    /** How far the mean spectrum stands over the noise across its band (dB): a mere step in the floor hardly does. */
    val reliefDb: Double,
    /** Separate lines in the mean spectrum, and the gap between the two strongest (Hz). */
    val raies: Int,
    val ecartRaiesHz: Double,
    /** Transmissions starting on 15 s / 7.5 s UTC slots and lasting an FT8 / FT4 frame (0 to 1). */
    val creneaux15: Double,
    val creneaux75: Double,
    /** Transmissions seen whole, and their typical length (s). */
    val emissions: Int,
    val dureeEmissionS: Double,
    /** Typical CW element (ms), 0 when none. */
    val elementMs: Double,
) {
    companion object {
        /** From a piste's history [h] and mean spectrum [spec] (from bin [specBas]). */
        fun depuis(h: Historique, spec: DoubleArray, specBas: Int, specTrames: Int, hzParCase: Double, trameMs: Double): MesuresSignal {
            val n = h.taille
            val i0 = h.idx(0); val i1 = h.idx(n - 1)
            val duree = (h.t[i1] - h.t[i0]) / 1000.0 + trameMs / 1000.0

            // On / off.
            var on = 0; var bascules = 0
            for (i in 0 until n) {
                val k = h.idx(i)
                if (h.on[k]) on++
                if (i > 0 && h.on[k] != h.on[h.idx(i - 1)]) bascules++
            }
            val niveaux = ArrayList<Double>()
            val snrs = ArrayList<Double>()
            var etal = 0.0; var conc = 0.0; var nEtal = 0
            for (i in 0 until n) {
                val k = h.idx(i)
                if (!h.on[k]) continue
                val s = h.snr[k].toDouble()
                niveaux += 10 * log10(max(s, 1e-6))
                snrs += s * h.cases[k] * hzParCase * 1.5 / 2500.0
                if (h.etal[k] > 0 || h.conc[k] > 0) { etal += h.etal[k]; conc += h.conc[k]; nEtal++ }
            }
            val enveloppe = ecartType(niveaux)
            val snrDb = if (snrs.isEmpty()) 0.0 else 10 * log10(max(1e-6, mediane(snrs)))

            // The mean spectrum: the band holding 90 % of the power, its flatness, its lines.
            var total = 0.0
            val pos = DoubleArray(spec.size) { max(0.0, spec[it]) }
            for (v in pos) total += v
            var c5 = 0; var c95 = spec.size - 1
            if (total > 0) {
                var cum = 0.0; var a = -1; var b = -1
                for (k in pos.indices) {
                    cum += pos[k]
                    if (a < 0 && cum >= 0.05 * total) a = k
                    if (b < 0 && cum >= 0.95 * total) b = k
                }
                c5 = max(0, a); c95 = max(c5, b)
            }
            val largeur = (c95 - c5 + 1) * hzParCase
            val centre = specBas + (c5 + c95) / 2.0
            var lg = 0.0; var ar = 0.0; var nb = 0
            val pic = pos.maxOrNull() ?: 0.0
            for (k in c5..c95) { val v = max(pos[k], pic * 1e-3); lg += kotlin.math.ln(v); ar += v; nb++ }
            val platitude = if (nb > 0 && ar > 0) kotlin.math.exp(lg / nb) / (ar / nb) else 0.0
            val relief = if (nb > 0 && specTrames > 0) 10 * log10(max(1e-3, ar / nb / specTrames)) else -30.0
            val (raies, ecartRaies) = raies(pos, hzParCase)

            // The strongest tone's frequency, run by run, its drift taken out.
            val runs = emissions(h, trameMs)
            val residus = ArrayList<Double>()
            var rs = 0.0; var rc = 0.0; var poids = 0.0
            var tonsMax = 0; var etendueMax = 0.0
            for (r in runs) {
                val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
                for (i in r.first..r.second) {
                    val k = h.idx(i)
                    if (!h.on[k] || h.fin[k].isNaN() || h.conc[k] < 0.5f) continue
                    xs += (h.t[k] - h.t[i0]) / 1000.0; ys += h.fin[k] * hzParCase
                }
                if (xs.size < 8) continue
                val res = sansDerive(xs, ys)
                residus += res
                var s = 0.0; var c = 0.0
                for (v in res) { val a = 2 * PI * v / 6.25; s += sin(a); c += cos(a) }
                rs += s; rc += c
                // Per run, the grid's length (an FT8 frame starts on its own base tone).
                poids += res.size
                val (t, e) = tons(res)
                if (t > tonsMax) { tonsMax = t; etendueMax = e }
            }
            val grille = if (poids > 0) sqrt(rs * rs + rc * rc) / poids else 0.0
            val tonEcart = if (residus.size >= 8) ecartRobuste(residus) else 0.0

            val (c15, c75, complets, dureeType) = creneaux(h, runs, trameMs)
            return MesuresSignal(
                dureeS = duree, trames = n, centreBin = centre, largeurHz = largeur, snrDb = snrDb,
                presence = on.toDouble() / n, bascules = bascules / duree, enveloppeDb = enveloppe,
                tonEcartHz = tonEcart, nTons = residus.size, grilleFt8 = grille,
                tonsDistincts = tonsMax, etendueTonsHz = etendueMax,
                etalHz = if (nEtal > 0) etal / nEtal * hzParCase else 0.0,
                concentration = if (nEtal > 0) conc / nEtal else 0.0,
                platitude = platitude, reliefDb = relief, raies = raies, ecartRaiesHz = ecartRaies,
                creneaux15 = c15, creneaux75 = c75, emissions = complets, dureeEmissionS = dureeType,
                elementMs = element(h, trameMs),
            )
        }

        /** On-runs (indices into the history, oldest first): gaps up to 0.37 s bridged, blips under 0.3 s dropped. */
        fun emissions(h: Historique, trameMs: Double): List<Pair<Int, Int>> {
            val pont = (370 / trameMs).roundToInt().coerceAtLeast(1)
            val min = (300 / trameMs).roundToInt().coerceAtLeast(1)
            val out = ArrayList<Pair<Int, Int>>()
            var debut = -1; var dernier = -1
            for (i in 0 until h.taille) {
                if (!h.on[h.idx(i)]) continue
                if (debut >= 0 && i - dernier > pont) { if (dernier - debut + 1 >= min) out += debut to dernier; debut = -1 }
                if (debut < 0) debut = i
                dernier = i
            }
            if (debut >= 0 && dernier - debut + 1 >= min) out += debut to dernier
            return out
        }

        private data class Creneaux(val c15: Double, val c75: Double, val complets: Int, val duree: Double)

        /**
         * FT8 sends 12.6 s from 0.5 s into a 15 s UTC slot; FT4 5 s from
         * 0.5 s into a 7.5 s one (the satellite adds a quarter second, the
         * clocks their own error). Every transmission seen whole is tried
         * against both.
         */
        private fun creneaux(h: Historique, runs: List<Pair<Int, Int>>, trameMs: Double): Creneaux {
            var ok15 = 0.0; var ok75 = 0.0; var n = 0.0; var complets = 0
            val durees = ArrayList<Double>()
            for ((a, b) in runs) {
                val ta = h.t[h.idx(a)] - trameMs.toLong()
                val tb = h.t[h.idx(b)]
                val dur = (tb - ta) / 1000.0
                val entier = a > 0 && b < h.taille - 1 - (400 / trameMs).toInt()
                val m15 = Math.floorMod(ta - 0L, 15_000L)
                val m75 = Math.floorMod(ta - 0L, 7_500L)
                val debut15 = m15 in 0L..3_000L || m15 > 14_600L
                val debut75 = m75 in 0L..2_000L || m75 > 7_100L
                if (entier) {
                    complets++; durees += dur; n += 1.0
                    if (debut15 && dur in 11.4..13.9) ok15 += 1.0
                    if (debut75 && dur in 4.2..6.0) ok75 += 1.0
                } else if (a > 0) {
                    // Still going: a good start counts half.
                    n += 0.5
                    if (debut15 && dur < 14.0) ok15 += 0.5
                    if (debut75 && dur < 6.0) ok75 += 0.5
                }
            }
            if (n == 0.0) return Creneaux(0.0, 0.0, 0, 0.0)
            // One transmission alone could fall on a slot by chance: two make it.
            val f15 = ok15 / n * when { ok15 >= 2 -> 1.0; ok15 >= 1 -> 0.75; else -> 0.4 }
            val f75 = ok75 / n * when { ok75 >= 2 -> 1.0; ok75 >= 1 -> 0.75; else -> 0.4 }
            return Creneaux(f15, f75, complets, if (durees.isEmpty()) 0.0 else mediane(durees))
        }

        /** Shortest usual on-run (ms): a CW dot, roughly. */
        private fun element(h: Historique, trameMs: Double): Double {
            val l = ArrayList<Double>()
            var r = 0
            for (i in 0 until h.taille) {
                if (h.on[h.idx(i)]) r++ else { if (r > 0 && i - r > 0) l += r * trameMs; r = 0 }
            }
            if (l.size < 6) return 0.0
            l.sort()
            return l[l.size / 5]
        }

        /** Distinct tones among [v] (Hz, drift removed), and the span between the outer ones. */
        private fun tons(v: List<Double>): Pair<Int, Double> {
            if (v.size < 8) return 0 to 0.0
            val mn = v.min(); val mx = v.max()
            val nb = ((mx - mn) / 1.0).toInt() + 3
            if (nb > 600) return 0 to (mx - mn)
            val hst = DoubleArray(nb)
            for (x in v) hst[((x - mn) / 1.0).toInt() + 1] += 1.0
            val lisse = DoubleArray(nb) { i -> (if (i > 0) hst[i - 1] else 0.0) + hst[i] + (if (i < nb - 1) hst[i + 1] else 0.0) }
            val seuil = 0.04 * v.size
            val pics = ArrayList<Int>()
            for (i in 0 until nb) {
                if (lisse[i] < seuil) continue
                if (i > 0 && lisse[i - 1] > lisse[i]) continue
                if (i < nb - 1 && lisse[i + 1] >= lisse[i]) continue
                if (pics.isNotEmpty() && i - pics.last() < 3) { if (lisse[i] > lisse[pics.last()]) pics[pics.size - 1] = i; continue }
                pics += i
            }
            val s = v.sorted()
            return pics.size to (s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)] - s[(s.size * 0.05).toInt()])
        }

        /** Lines in a mean spectrum: maxima above a quarter of the strongest, three bins apart at least. */
        private fun raies(pos: DoubleArray, hzParCase: Double): Pair<Int, Double> {
            val pic = pos.maxOrNull() ?: return 0 to 0.0
            if (pic <= 0) return 0 to 0.0
            val l = ArrayList<Int>()
            for (k in 1 until pos.size - 1) {
                if (pos[k] < 0.25 * pic || pos[k] < pos[k - 1] || pos[k] < pos[k + 1]) continue
                if (l.isNotEmpty() && k - l.last() < 3) { if (pos[k] > pos[l.last()]) l[l.size - 1] = k; continue }
                l += k
            }
            if (l.size < 2) return l.size to 0.0
            // Between two lines there must be a dip, or it is one hump.
            val vraies = ArrayList<Int>()
            for (k in l) {
                if (vraies.isEmpty()) { vraies += k; continue }
                val a = vraies.last()
                var creux = Double.MAX_VALUE
                for (j in a..k) creux = min(creux, pos[j])
                if (creux < 0.5 * min(pos[a], pos[k])) vraies += k else if (pos[k] > pos[a]) vraies[vraies.size - 1] = k
            }
            if (vraies.size < 2) return vraies.size to 0.0
            val deux = vraies.sortedByDescending { pos[it] }.take(2)
            return vraies.size to abs(deux[0] - deux[1]) * hzParCase
        }

        private fun sansDerive(xs: List<Double>, ys: List<Double>): List<Double> {
            val mx = xs.average(); val my = ys.average()
            var num = 0.0; var den = 0.0
            for (i in xs.indices) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
            val pente = if (den > 0) num / den else 0.0
            return xs.indices.map { ys[it] - my - pente * (xs[it] - mx) }
        }

        fun mediane(l: List<Double>): Double { val s = l.sorted(); return s[s.size / 2] }

        private fun ecartType(l: List<Double>): Double {
            if (l.size < 2) return 0.0
            val m = l.average()
            return sqrt(l.sumOf { (it - m) * (it - m) } / (l.size - 1))
        }

        /** Median absolute deviation, as a standard deviation. */
        private fun ecartRobuste(l: List<Double>): Double {
            val m = mediane(l)
            return 1.4826 * mediane(l.map { abs(it - m) })
        }
    }
}

/**
 * The judge: from a piste's measures, its likeliest class. Each class is a
 * product of soft conditions (width, on/off, steadiness, tones, slots); the
 * band plan's segment only nudges (×1.15), a beacon is recognised at its
 * place only when it looks like one.
 */
object ClassifieurQo100 {

    /** Under this, « inconnu ». */
    const val SEUIL = 0.3

    /** 1 between [b] and [c], down to 0 at [a] and [d]. */
    private fun entre(x: Double, a: Double, b: Double, c: Double, d: Double): Double =
        min(((x - a) / (b - a)).coerceIn(0.0, 1.0), ((d - x) / (d - c)).coerceIn(0.0, 1.0))

    /** 1 under [b], 0 over [c]. */
    private fun sous(x: Double, b: Double, c: Double): Double = ((c - x) / (c - b)).coerceIn(0.0, 1.0)

    /** 1 over [b], 0 under [a]. */
    private fun sur(x: Double, a: Double, b: Double): Double = ((x - a) / (b - a)).coerceIn(0.0, 1.0)

    /** Scores per class, before the band plan (tests print them). */
    fun scores(m: MesuresSignal): Map<ClasseQo100, Double> {
        val bw = m.largeurHz
        val s = HashMap<ClasseQo100, Double>()
        val creneau = max(m.creneaux15, m.creneaux75)
        s[ClasseQo100.PORTEUSE] = sous(bw, 50.0, 100.0) * sur(m.presence, 0.85, 0.97) * sous(m.enveloppeDb, 1.5, 3.0) *
            sous(m.tonEcartHz, 1.0, 3.0) * sous(creneau, 0.3, 0.6)
        s[ClasseQo100.CW] = sous(bw, 90.0, 160.0) * entre(m.presence, 0.08, 0.2, 0.85, 0.95) * sur(m.bascules, 0.3, 1.2) *
            sous(m.tonEcartHz, 3.0, 8.0) * sous(creneau, 0.3, 0.6)
        val tonsFt8 = sur(m.grilleFt8, 0.45, 0.7) * entre(m.tonsDistincts.toDouble(), 2.5, 4.0, 9.5, 12.0) * entre(m.etendueTonsHz, 15.0, 25.0, 50.0, 60.0)
        // Several FT8 stations side by side make one block: wider, but on the slots all the same.
        s[ClasseQo100.FT8] = max(entre(bw, 25.0, 40.0, 100.0, 140.0) * max(sur(m.creneaux15, 0.3, 0.7), 0.85 * tonsFt8),
            entre(bw, 100.0, 140.0, 3000.0, 3500.0) * sur(m.creneaux15, 0.5, 0.8)) * sous(m.enveloppeDb, 3.0, 5.0)
        s[ClasseQo100.FT4] = max(entre(bw, 45.0, 65.0, 140.0, 180.0) * sur(m.creneaux75, 0.3, 0.7),
            entre(bw, 140.0, 180.0, 3000.0, 3500.0) * sur(m.creneaux75, 0.5, 0.8)) * sous(m.enveloppeDb, 3.0, 5.0) *
            sous(m.creneaux15, 0.4, 0.7)
        s[ClasseQo100.PSK31] = entre(bw, 20.0, 30.0, 90.0, 120.0) * sur(m.presence, 0.8, 0.93) * sous(creneau, 0.25, 0.5) *
            sous(tonsFt8, 0.3, 0.6) * sur(m.tonEcartHz, 2.0, 5.0) * sur(m.dureeS, 6.0, 12.0) * sous(m.enveloppeDb, 3.5, 6.0)
        s[ClasseQo100.RTTY] = entre(bw, 120.0, 170.0, 350.0, 450.0) * (if (m.raies >= 2) entre(m.ecartRaiesHz, 120.0, 150.0, 200.0, 240.0) else 0.0) *
            sur(m.presence, 0.6, 0.85)
        s[ClasseQo100.SSTV] = entre(bw, 400.0, 600.0, 1300.0, 1700.0) * sous(m.enveloppeDb, 1.5, 3.0) * sur(m.presence, 0.8, 0.93) *
            sous(m.etalHz / bw, 0.18, 0.28) * sur(m.dureeS, 5.0, 10.0) * sur(m.reliefDb, 2.0, 5.0)
        s[ClasseQo100.NUMERIQUE_LARGE] = entre(bw, 350.0, 600.0, 2800.0, 3400.0) * sous(m.enveloppeDb, 1.5, 3.0) * sur(m.presence, 0.8, 0.93) *
            sur(m.etalHz / bw, 0.18, 0.26) * sur(m.platitude, 0.4, 0.6) * sur(m.reliefDb, 2.0, 5.0)
        s[ClasseQo100.BLU] = entre(bw, 700.0, 1400.0, 3300.0, 4200.0) * max(sur(m.enveloppeDb, 2.5, 4.5), sous(m.presence, 0.75, 0.9)) *
            sous(m.platitude, 0.85, 0.95)
        return s
    }

    /** Which class the band plan expects in a segment (a nudge, never the only evidence). */
    private fun attendues(seg: String?): Set<ClasseQo100> = when (seg) {
        "cw" -> setOf(ClasseQo100.CW, ClasseQo100.PORTEUSE)
        "num_etroit" -> setOf(ClasseQo100.FT8, ClasseQo100.FT4, ClasseQo100.PSK31, ClasseQo100.RTTY)
        "num_large" -> setOf(ClasseQo100.FT8, ClasseQo100.FT4, ClasseQo100.PSK31, ClasseQo100.RTTY, ClasseQo100.SSTV, ClasseQo100.NUMERIQUE_LARGE)
        "ssb_bas", "ssb_haut", "diffusion", "urgence" -> setOf(ClasseQo100.BLU)
        "mixte" -> setOf(ClasseQo100.BLU, ClasseQo100.SSTV, ClasseQo100.NUMERIQUE_LARGE)
        else -> emptySet()
    }

    /** The class, its confidence (0 to 1), a few words of why — for a signal centred on [cielHz]. */
    fun juge(m: MesuresSignal, cielHz: Long): Triple<ClasseQo100, Double, String> {
        balise(m, cielHz)?.let { return Triple(ClasseQo100.BALISE, 0.95, it) }
        if (m.dureeS < 3.0) return Triple(ClasseQo100.INCONNU, 0.0, "en observation")
        val seg = Qo100.segment(cielHz)?.cle
        val att = attendues(seg)
        val s = scores(m).mapValues { (c, v) -> min(1.0, if (c in att) v * 1.15 else v) }
        val tri = s.entries.sortedByDescending { it.value }
        val (c, v) = tri[0]
        val second = tri.getOrNull(1)?.value ?: 0.0
        if (v < SEUIL) return Triple(ClasseQo100.INCONNU, 0.0, "≈ ${hz(m.largeurHz)}")
        // Close second: less sure.
        val conf = (v * (1 - 0.5 * second / v)).coerceIn(0.0, 1.0)
        return Triple(c, conf, details(c, m))
    }

    private fun balise(m: MesuresSignal, ciel: Long): String? = when {
        abs(ciel - Qo100.BALISE_BASSE_HZ) <= 700 && m.largeurHz <= 600 -> "balise CW basse"
        abs(ciel - Qo100.BALISE_HAUTE_HZ) <= 700 && m.largeurHz <= 600 -> "balise CW haute"
        abs(ciel - Qo100.BALISE_MEDIANE_HZ) <= 1_500 && m.largeurHz in 150.0..2_000.0 -> "balise PSK 400 bit/s"
        ciel in 10_489_988_000L..10_489_998_000L && m.largeurHz >= 800 -> "balise multimédia"
        else -> null
    }

    private fun hz(x: Double): String = if (x >= 1000) "%.1f kHz".format(java.util.Locale.FRANCE, x / 1000) else "${x.roundToInt()} Hz"

    private fun details(c: ClasseQo100, m: MesuresSignal): String = when (c) {
        ClasseQo100.BLU -> "≈ ${hz(m.largeurHz)}, modulation syllabique"
        ClasseQo100.CW -> if (m.elementMs in 30.0..250.0) "manipulation ≈ ${(1200 / m.elementMs).roundToInt()} mots/min" else "raie manipulée"
        ClasseQo100.FT8 -> listOfNotNull(if (m.largeurHz > 140) "plusieurs stations" else null, if (m.grilleFt8 >= 0.5 && m.tonsDistincts >= 4) "${m.tonsDistincts} tons 6,25 Hz" else null,
            if (m.creneaux15 >= 0.5) "créneaux 15 s" else null).joinToString(", ").ifEmpty { "≈ ${hz(m.largeurHz)}" }
        ClasseQo100.FT4 -> (if (m.largeurHz > 180) "plusieurs stations, " else "") + "≈ ${hz(m.largeurHz)}, créneaux 7,5 s"
        ClasseQo100.PSK31 -> "≈ ${hz(m.largeurHz)}, continu, phase inversée"
        ClasseQo100.RTTY -> "2 raies à ${m.ecartRaiesHz.roundToInt()} Hz"
        ClasseQo100.SSTV -> "≈ ${hz(m.largeurHz)}, tonalité FM, enveloppe constante"
        ClasseQo100.NUMERIQUE_LARGE -> "≈ ${hz(m.largeurHz)} plat, enveloppe constante (OFDM ?)"
        ClasseQo100.PORTEUSE -> "raie stable (±${"%.1f".format(java.util.Locale.FRANCE, m.tonEcartHz)} Hz)"
        else -> "≈ ${hz(m.largeurHz)}"
    }
}
