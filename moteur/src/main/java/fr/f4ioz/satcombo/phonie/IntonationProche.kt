/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.phonie

import kotlin.math.abs
import kotlin.math.pow

/**
 * « Traduction parlée », intonation « proche »: the synthetic voice saying
 * the translation brought towards the voice heard, cheaply and gently.
 *
 * Measured on the over heard (16 kHz): its pitch track ([HauteurVoix]),
 * hence its mean pitch and spread; its speed (syllables a second, from the
 * text and the engine's segment times); and, phrase by phrase (one per
 * segment of the engine), the phrase's pitch movement — a curve of degree
 * two or one fitted to its voiced frames over the phrase's time made 0…1,
 * which keeps a rise, a fall, a rise at the end, and drops the detail — and
 * its loudness against the over's.
 *
 * Applied to the synthetic speech, phrase by phrase:
 *  - speed: the synthesiser's length scale, chosen before ([longueur]);
 *  - pitch, frame by frame, in semitones:
 *      target = μo + k·(s − μs − Ts(τ)) + d·To(τ) + (1 − d)·k·Ts(τ)
 *    where s is the synthetic pitch, μ the means, T the phrase curves (o:
 *    heard, s: synthetic), k = spreads' ratio (0.6…1.5) and d = 0.7: the
 *    synthesiser's own detail kept (stress, micro-melody), its mean moved to
 *    the speaker's, its phrase movement mostly replaced by the speaker's.
 *    The shift (target − s) is smoothed over 50 ms, bounded to ±10 semitones
 *    (±8 for the mean), then made by [DecaleHauteur];
 *  - loudness: each phrase ±4 dB at most, 70 % of the heard difference.
 */
object IntonationProche {

    /** A phrase heard: its time in the over (s), its words. */
    data class Phrase(val debutS: Double, val finS: Double, val texte: String)

    /** A phrase's pitch movement: semitones against the speaker's mean, over its time made 0…1. */
    data class Mouvement(val a: Double, val b: Double, val c: Double) {
        fun en(t: Double): Double = (a + b * t + c * t * t).coerceIn(-6.0, 6.0)
        companion object { val PLAT = Mouvement(0.0, 0.0, 0.0) }
    }

    /** What was measured on the over heard. */
    data class Analyse(
        val profil: HauteurVoix.Profil,
        /** Mean pitch in semitones above 100 Hz. */
        val moyenDt: Double,
        val mouvements: List<Mouvement>,
        /** Each phrase's loudness against the over's (dB). */
        val niveauxDb: List<Double>,
        /** Syllables a second; 0 when unknown. */
        val debit: Double,
        /** The pauses between phrases (s). */
        val pauses: List<Double>,
    )

    const val PROFONDEUR = 0.7

    fun analyse(pcm: FloatArray, rate: Int, phrases: List<Phrase>): Analyse {
        val c = HauteurVoix.suit(pcm, rate)
        val p = HauteurVoix.profil(c)
        val mu = if (p.voisee) HauteurVoix.demiTons(p.f0Hz) else 0.0
        val mouvements = phrases.map { ph -> if (p.voisee) mouvement(c, ph.debutS, ph.finS, mu) else Mouvement.PLAT }
        val niveaux = phrases.map { ph -> HauteurVoix.profil(c, ph.debutS, ph.finS).let { if (it.voisee) it.niveauDb else Double.NaN } }
        val ok = niveaux.filter { !it.isNaN() }
        val moyen = if (ok.isEmpty()) 0.0 else ok.average()
        val duree = phrases.sumOf { (it.finS - it.debutS).coerceAtLeast(0.0) }
        val syl = phrases.sumOf { syllabes(it.texte) }
        val pauses = phrases.zipWithNext { a, b -> (b.debutS - a.finS).coerceIn(0.15, 0.6) }
        return Analyse(p, mu, mouvements, niveaux.map { if (it.isNaN()) 0.0 else it - moyen },
            if (duree >= 1.0 && syl >= 3) syl / duree else 0.0, pauses)
    }

    /** The movement of the voiced frames between [debutS] and [finS], against [mu] (semitones). */
    fun mouvement(c: HauteurVoix.Courbe, debutS: Double, finS: Double, mu: Double): Mouvement {
        val i0 = (debutS / c.pasS).toInt().coerceAtLeast(0)
        val i1 = minOf(c.n, (finS / c.pasS).toInt())
        val idx = (i0 until i1).filter { c.f0[it] > 0f }
        if (idx.size < 4) return Mouvement.PLAT
        // Over the voiced part's own span: the silences at either end do not stretch it.
        val premier = idx.first(); val dernier = idx.last()
        val span = (dernier - premier).coerceAtLeast(1).toDouble()
        val t = idx.map { (it - premier) / span }
        val y = idx.map { HauteurVoix.demiTons(c.f0[it].toDouble()) - mu }
        return ajuste(t, y)
    }

    /** Least squares of degree 2 (1 with few points or a short span; a mean with fewer). */
    fun ajuste(t: List<Double>, y: List<Double>): Mouvement {
        val n = t.size
        if (n == 0) return Mouvement.PLAT
        val moyen = y.average()
        if (n < 4) return Mouvement(moyen, 0.0, 0.0)
        val degre = if (n >= 12) 2 else 1
        val m = degre + 1
        val a = Array(m) { DoubleArray(m) }
        val b = DoubleArray(m)
        for (i in 0 until n) {
            val p = DoubleArray(m) { k -> t[i].pow(k) }
            for (r in 0 until m) { b[r] += p[r] * y[i]; for (s in 0 until m) a[r][s] += p[r] * p[s] }
        }
        // A little ridge on the curvature: a few frames do not make a parabola.
        if (degre == 2) a[2][2] += 0.01 * n
        val x = resous(a, b) ?: return Mouvement(moyen, 0.0, 0.0)
        return Mouvement(x[0], x[1], if (degre == 2) x[2] else 0.0)
    }

    private fun resous(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() + b[i] }
        for (c in 0 until n) {
            val p = (c until n).maxByOrNull { abs(m[it][c]) } ?: return null
            if (abs(m[p][c]) < 1e-12) return null
            val tmp = m[c]; m[c] = m[p]; m[p] = tmp
            for (r in 0 until n) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /**
     * The synthesiser's length scale (> 1 slower): the speaker's speed against
     * the usual one of their language, carried over to the target language,
     * against what the voice does at 1 ([debitVoix], learnt). Gently: 0.8…1.25.
     */
    fun longueur(a: Analyse, langue: String, cible: String, debitVoix: Double): Double {
        if (a.debit <= 0 || debitVoix <= 0) return 1.0
        val relatif = (a.debit / debitUsuel(langue)).pow(0.7)
        val voulu = debitUsuel(cible) * relatif
        return (debitVoix / voulu).coerceIn(0.8, 1.25)
    }

    /** Syllables a second of ordinary speech (orders of magnitude from published measures). */
    fun debitUsuel(langue: String): Double = when (langue) {
        "es", "ja" -> 7.5; "fr", "it" -> 7.0; "pt" -> 6.8; "pl", "ru", "uk", "cs" -> 6.3
        "en", "nl" -> 6.2; "de" -> 5.9; "zh" -> 5.2; else -> 6.4
    }

    /** About how many syllables: groups of vowels; a callsign or a number spelt out, one per sign. */
    fun syllabes(texte: String): Int = texte.split(Regex("[\\s,.;:!?…«»\"()]+")).filter { it.isNotBlank() }.sumOf { m ->
        if (m.any { it.isDigit() }) m.count { it.isLetterOrDigit() }
        else maxOf(1, Regex("[aeiouyàâäéèêëîïôöùûüœæąęóåøаеёиоуыэюяіїєαεηιουω]+", RegexOption.IGNORE_CASE).findAll(m).count())
    }

    /**
     * The synthetic phrases ([synth], at [rate]) brought towards the over
     * ([a]) and joined with the heard pauses; [d]: how much of the phrase
     * movement is the speaker's (0: none, the mean pitch only).
     */
    fun applique(synth: List<FloatArray>, rate: Int, a: Analyse, d: Double = PROFONDEUR): FloatArray {
        val courbes = synth.map { HauteurVoix.suit(it, rate) }
        // The synthetic voice's mean and spread over the whole over.
        val tous = courbes.flatMap { c -> c.voisees().map { HauteurVoix.demiTons(it.toDouble()) } }.sorted()
        val morceaux = synth.mapIndexed { i, x ->
            if (!a.profil.voisee || tous.size < 10) return@mapIndexed x
            val mus = tous.average()
            val etendueS = HauteurVoix.quantile(tous, 0.9) - HauteurVoix.quantile(tous, 0.1)
            val k = if (etendueS > 0.5 && a.profil.etendueDt > 0.5) (a.profil.etendueDt / etendueS).coerceIn(0.6, 1.5) else 1.0
            val decalage = decalages(courbes[i], mus, k, a.moyenDt, a.mouvements.getOrElse(i) { Mouvement.PLAT }, d)
            val y = DecaleHauteur.applique(x, rate, courbes[i]) { t -> 2.0.pow(decalage[(t / courbes[i].pasS).toInt().coerceIn(0, decalage.size - 1)] / 12.0) }
            val g = 10.0.pow(((a.niveauxDb.getOrElse(i) { 0.0 }) * 0.7).coerceIn(-4.0, 4.0) / 20.0).toFloat()
            for (j in y.indices) y[j] *= g
            y
        }
        return joint(morceaux, rate, a.pauses)
    }

    /** The shift (semitones) of each frame of [c], smoothed over 50 ms. */
    fun decalages(c: HauteurVoix.Courbe, mus: Double, k: Double, muo: Double, to: Mouvement, d: Double): DoubleArray {
        val n = c.n
        val r = DoubleArray(n) { Double.NaN }
        val idx = (0 until n).filter { c.f0[it] > 0f }
        if (idx.isEmpty()) return DoubleArray(maxOf(1, n))
        val premier = idx.first(); val span = (idx.last() - premier).coerceAtLeast(1).toDouble()
        val ts = mouvement(c, 0.0, n * c.pasS, mus)
        val moyen = (muo - mus).coerceIn(-8.0, 8.0)
        for (i in idx) {
            val tau = ((i - premier) / span).coerceIn(0.0, 1.0)
            val s = HauteurVoix.demiTons(c.f0[i].toDouble())
            val propre = ts.en(tau)
            val cible = mus + moyen + k * (s - mus - propre) + d * to.en(tau) + (1 - d) * k * propre
            r[i] = (cible - s).coerceIn(-10.0, 10.0)
        }
        // The unvoiced frames: the nearest voiced ones' shift (the grains there are left alone anyway).
        var dernier = Double.NaN
        for (i in 0 until n) if (!r[i].isNaN()) dernier = r[i] else if (!dernier.isNaN()) r[i] = dernier
        dernier = r.firstOrNull { !it.isNaN() } ?: 0.0
        for (i in 0 until n) if (r[i].isNaN()) r[i] = dernier
        return DoubleArray(n) { i -> var s = 0.0; var m = 0; for (j in i - 2..i + 2) if (j in 0 until n) { s += r[j]; m++ }; s / m }
    }

    /** The phrases one after the other, [pauses] between them (0.25 s when unknown). */
    fun joint(morceaux: List<FloatArray>, rate: Int, pauses: List<Double> = emptyList()): FloatArray {
        val blancs = morceaux.indices.map { i -> if (i == morceaux.size - 1) 0 else Math.round(rate * pauses.getOrElse(i) { 0.25 }).toInt() }
        val sortie = FloatArray(morceaux.sumOf { it.size } + blancs.sum())
        var p = 0
        morceaux.forEachIndexed { i, x -> x.copyInto(sortie, p); p += x.size + blancs[i] }
        return sortie
    }
}
