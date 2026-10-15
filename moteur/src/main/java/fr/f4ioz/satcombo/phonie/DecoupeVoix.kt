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
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cuts the voice out of a 16 kHz stream: what is said between two silences,
 * one piece per over, for the speech recogniser.
 *
 * Per 20 ms frame: the energy against a noise floor followed from below
 * (falls at once, climbs 0.5 dB/s), and the speech band (300–3400 Hz)
 * against the hiss above 4 kHz. Voice opens a piece (with 300 ms before
 * it), 800 ms without voice closes it. A piece shorter than [minS], or
 * that is only noise (too few voiced frames, no syllables: a flat level),
 * is dropped; one longer than [maxS] is cut at its quietest frame of the
 * last 4 s. A closed squelch is silence, and does not move the floor.
 */
class DecoupeVoix(
    val rate: Int = 16_000,
    private val minS: Double = 1.0,
    private val maxS: Double = 25.0,
    /** dB above the floor to open; to stay open. */
    private val seuilOuvreDb: Double = 7.0,
    private val seuilTientDb: Double = 4.0,
) {
    /** A piece of voice: its first sample (counted from the stream's start), its samples, how it sounded. */
    class Segment(val debut: Long, val pcm: FloatArray, val voixDb: Double, val fractionVoisee: Double) {
        val dureeS: Double get() = pcm.size / 16_000.0
    }

    private val trame = rate / 50
    private val avant = 15          // frames kept before the voice (300 ms)
    private val tenue = 40          // frames of silence that close (800 ms)

    private var bruitDb = Double.NaN
    private var lu = 0L             // samples taken in so far
    private val cours = FloatArray(trame)
    private var nCours = 0

    // The piece being cut.
    private var ouvert = false
    private var pieceDebut = 0L
    private val trames = ArrayList<FloatArray>()
    private val energies = ArrayList<Double>()
    private val voisees = ArrayList<Boolean>()
    private var silence = 0
    private val passe = ArrayDeque<FloatArray>()

    // Speech band and hiss band, second-order sections.
    private val passeHaut300 = Biquad.passeHaut(300.0, rate)
    private val passeBas3400 = Biquad.passeBas(3400.0, rate)
    private val passeHaut4000 = Biquad.passeHaut(4000.0, rate)

    /** The noise floor now (dBFS), NaN before the first frame. */
    val bruit: Double get() = bruitDb

    /** True while a piece is open. */
    val enVoix: Boolean get() = ouvert

    /**
     * Feeds [x] (16 kHz, −1…1). [squelch]: the receiver's gate when known
     * (false = closed: silence). Returns the pieces closed meanwhile.
     */
    fun pousse(x: FloatArray, n: Int = x.size, squelch: Boolean? = null): List<Segment> {
        var sortis: MutableList<Segment>? = null
        var i = 0
        while (i < n) {
            val k = minOf(trame - nCours, n - i)
            System.arraycopy(x, i, cours, nCours, k)
            nCours += k; i += k
            if (nCours == trame) {
                nCours = 0
                trameEntiere(cours.copyOf(), squelch)?.let { (sortis ?: ArrayList<Segment>().also { l -> sortis = l }).add(it) }
            }
        }
        return sortis ?: emptyList()
    }

    /** Closes the piece in progress (the source stopping). */
    fun vide(): Segment? = if (ouvert) ferme(trames.size) else null

    /** Forgets everything (a new source). */
    fun reinitialise() {
        bruitDb = Double.NaN; ouvert = false; trames.clear(); energies.clear(); voisees.clear(); passe.clear(); silence = 0; nCours = 0
        passeHaut300.reset(); passeBas3400.reset(); passeHaut4000.reset()
    }

    private fun trameEntiere(t: FloatArray, squelch: Boolean?): Segment? {
        val debut = lu
        lu += trame
        var e = 0.0; var eVoix = 0.0; var eHaut = 0.0
        for (s in t) {
            e += s * s
            val v = passeBas3400.f(passeHaut300.f(s.toDouble()))
            eVoix += v * v
            val h = passeHaut4000.f(s.toDouble())
            eHaut += h * h
        }
        val db = 10 * log10(e / trame + 1e-12)
        val ferme = squelch == false
        // Digital silence or a closed squelch says nothing of the noise.
        if (!ferme && db > -100) {
            bruitDb = when {
                bruitDb.isNaN() -> db
                db < bruitDb -> bruitDb + (db - bruitDb) * 0.3
                else -> bruitDb + 0.01
            }
        }
        val dessus = if (ferme || bruitDb.isNaN()) -99.0 else db - bruitDb
        // Hiss louder than the speech band is not a voice (FM noise, a squelch tail).
        val bandeOk = 10 * log10(eVoix + 1e-15) > 10 * log10(eHaut + 1e-15) + 3
        val voix = bandeOk && dessus > (if (ouvert) seuilTientDb else seuilOuvreDb)
        if (!ouvert) {
            passe.addLast(t)
            if (passe.size > avant) passe.removeFirst()
            if (!voix) return null
            ouvert = true; silence = 0
            pieceDebut = debut - (passe.size - 1) * trame
            for (p in passe) { trames += p; energies += -99.0; voisees += false }
            energies[energies.size - 1] = dessus; voisees[voisees.size - 1] = true
            passe.clear()
            return null
        }
        trames += t; energies += dessus; voisees += voix
        silence = if (voix) 0 else silence + 1
        if (silence >= tenue) return ferme(trames.size - (tenue - avant).coerceAtLeast(0))
        if (trames.size * trame >= maxS * rate) {
            // At the quietest frame of the last 4 s, the rest kept for the next piece.
            val de = (trames.size - 200).coerceAtLeast(trames.size / 2)
            var coupe = trames.size
            var min = Double.MAX_VALUE
            for (j in de until trames.size) if (energies[j] < min) { min = energies[j]; coupe = j + 1 }
            return ferme(coupe, garde = true)
        }
        return null
    }

    /** Closes the piece after its first [nb] frames; [garde]: the rest begins the next one. */
    private fun ferme(nb: Int, garde: Boolean = false): Segment? {
        val nbT = nb.coerceIn(0, trames.size)
        val pcm = FloatArray(nbT * trame)
        for (j in 0 until nbT) System.arraycopy(trames[j], 0, pcm, j * trame, trame)
        val nVoix = (0 until nbT).count { voisees[it] }
        val niveaux = (0 until nbT).filter { voisees[it] }.map { energies[it] }
        val debut = pieceDebut
        if (garde && nbT < trames.size) {
            val reste = trames.subList(nbT, trames.size).toList()
            val re = energies.subList(nbT, energies.size).toList()
            val rv = voisees.subList(nbT, voisees.size).toList()
            trames.clear(); energies.clear(); voisees.clear()
            trames += reste; energies += re; voisees += rv
            pieceDebut = debut + nbT.toLong() * trame
            silence = 0
        } else {
            trames.clear(); energies.clear(); voisees.clear(); ouvert = false; silence = 0
        }
        val duree = pcm.size.toDouble() / rate
        if (duree < minS || nVoix * trame < 0.5 * rate) return null
        val moyenne = niveaux.average()
        val ecart = sqrt(niveaux.sumOf { (it - moyenne) * (it - moyenne) } / niveaux.size)
        // A flat level (a carrier, steady hiss) has no syllables.
        if (ecart < 2.0) return null
        val haut = niveaux.sorted()[(niveaux.size * 9) / 10]
        if (haut < 10.0) return null
        return Segment(debut, pcm, haut, nVoix.toDouble() / nbT)
    }

    /** One RBJ second-order section. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun f(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

        companion object {
            private const val Q = 0.7071
            fun passeBas(f: Double, rate: Int): Biquad {
                val w = 2 * PI * f / rate; val a = sin(w) / (2 * Q); val c = cos(w); val a0 = 1 + a
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - a) / a0)
            }
            fun passeHaut(f: Double, rate: Int): Biquad {
                val w = 2 * PI * f / rate; val a = sin(w) / (2 * Q); val c = cos(w); val a0 = 1 + a
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - a) / a0)
            }
        }
    }

    companion object {
        /** Brings a piece to about −20 dBFS RMS (at most +30 dB, never clipping). */
        fun normalise(x: FloatArray): FloatArray {
            if (x.isEmpty()) return x
            var e = 0.0; var crete = 0f
            for (s in x) { e += s * s; crete = maxOf(crete, Math.abs(s)) }
            val rms = sqrt(e / x.size)
            if (rms < 1e-9 || crete < 1e-9) return x
            val g = minOf(0.1 / rms, 31.6, 0.99 / crete)
            return FloatArray(x.size) { (x[it] * g).toFloat() }
        }
    }
}
