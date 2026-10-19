/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.clavier

import fr.f4ioz.satcombo.sdr.Dsp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * BPSK31 from audio: 31.25 symbols a second, a phase reversal for a 0 and
 * none for a 1, the amplitude shaped so a reversal passes through zero.
 *
 *   audio → mixed to 0 Hz on [centreHz] + AFC, sinc² to ~1 kHz
 *         → narrow low-pass → symbol clock (the envelope's dips mark the
 *           symbol edges: sampled half a symbol away)
 *         → differential: z·conj(z₋₁) on the real axis = 1, against = 0
 *         → varicode → text, while the squelch is open
 *
 * AFC: a coarse search (the squared signal has a line at twice the offset;
 * ±[AFC_HZ]) while nothing is locked, then the symbol phase error keeps it.
 * Squelch: the decision quality (how close the differential phase is to 0
 * or π), with the in-band S/N and IMD measured beside it.
 *
 * No Android; allocation only at construction.
 */
class DecodeurPsk31(private val fs: Int, private val sortie: (String) -> Unit, centreHz: Double = 1_000.0) {

    private val facteur = max(1, (fs / 1_000.0).roundToInt())
    /** The baseband rate (Hz). */
    val debit = fs.toDouble() / facteur
    private val parSymbole = debit / BAUD

    private val descente = Descente(fs, facteur)
    private val large = FirComplexe(Dsp.lowPass(63, 90.0, debit))
    private val etroit = FirComplexe(Dsp.lowPass(63, 28.0, debit))

    /** Where the signal is expected, audio Hz (the dial's choice). */
    @Volatile var centreHz: Double = centreHz.coerceIn(MIN_HZ, MAX_HZ)
        set(v) { field = v.coerceIn(MIN_HZ, MAX_HZ); afc = 0.0; nouveauCentre = true }
    @Volatile private var nouveauCentre = true
    private var afc = 0.0

    /** Squelch threshold on the decision quality, 0..1. */
    @Volatile var seuil: Double = 0.55

    /** The tracked frequency, audio Hz. */
    val frequenceHz: Double get() = centreHz + afc
    /** Decision quality 0..1 (1: clean phase). */
    @Volatile var qualite = 0.0; private set
    /** Text passes. */
    @Volatile var ouvert = false; private set
    /** S/N in the signal's own 62.5 Hz, dB (NaN until measured). */
    @Volatile var snrDb = Double.NaN; private set
    /** Third-order IMD measured on the idle (continuous reversals), dB; NaN until heard. */
    @Volatile var imdDb = Double.NaN; private set

    // The acquisition ring (wide-filtered baseband).
    private val ri = DoubleArray(ANNEAU)
    private val rq = DoubleArray(ANNEAU)
    private var rp = 0
    private var rempli = 0
    private var depuisRecherche = 0

    // Symbol clock.
    private val cases = DoubleArray(CASES)
    private var pos = 0.0
    private var depuis = 0.0
    private var visee = parSymbole / 2
    private var prevI = 0.0
    private var prevQ = 0.0
    private var zeros = 0  // recent reversals, for the IMD on the idle
    private var bitsRecents = 0L
    private var niveau = 0.0
    private var faibles = 0

    private val lecteur = Varicode.Lecteur { ch -> if (ouvert) emet(ch) }

    private fun emet(ch: Char) {
        when {
            ch == '\r' -> {}
            ch == '\n' -> sortie("\n")
            ch.code < 32 || ch.code == 127 -> {}
            else -> sortie(ch.toString())
        }
    }

    fun reinitialise() {
        descente.reinitialise(); large.reinitialise(); etroit.reinitialise(); lecteur.reinitialise()
        rp = 0; rempli = 0; depuisRecherche = 0; cases.fill(0.0); pos = 0.0; depuis = 0.0
        prevI = 0.0; prevQ = 0.0; qualite = 0.0; ouvert = false; afc = 0.0
    }

    fun ecoute(pcm: ShortArray, n: Int) {
        for (k in 0 until n) ecoute(pcm[k] / 32_768.0)
    }

    fun ecoute(x: FloatArray, n: Int) {
        for (k in 0 until n) ecoute(x[k].toDouble())
    }

    private fun ecoute(x: Double) {
        if (nouveauCentre) { nouveauCentre = false; rempli = 0; qualite = 0.0; ouvert = false }
        descente.frequenceHz = centreHz + afc
        if (!descente.pousse(x)) return
        val bi = descente.sortieI; val bq = descente.sortieQ
        large.pousse(bi, bq)
        ri[rp] = large.i; rq[rp] = large.q
        rp = (rp + 1) % ANNEAU
        if (rempli < ANNEAU) rempli++
        if (++depuisRecherche >= ANNEAU / 2 && rempli == ANNEAU) { depuisRecherche = 0; mesure() }
        etroit.pousse(bi, bq)
        horloge(etroit.i, etroit.q)
    }

    /** The symbol clock: the envelope by phase within the symbol, its dip the edge. */
    private fun horloge(i: Double, q: Double) {
        val m = sqrt(i * i + q * q)
        val c = ((pos / parSymbole) * CASES).toInt().coerceIn(0, CASES - 1)
        cases[c] += 0.04 * (m - cases[c])
        val avant = pos
        pos += 1.0
        if (pos >= parSymbole) pos -= parSymbole
        depuis += 1.0
        val passe = if (pos >= avant) visee > avant && visee <= pos else visee > avant || visee <= pos
        if ((passe && depuis > 0.6 * parSymbole) || depuis > 1.4 * parSymbole) {
            depuis = 0.0
            symbole(i, q)
            var bas = 0
            for (j in 1 until CASES) if (cases[j] < cases[bas]) bas = j
            visee = (((bas + CASES / 2) % CASES) + 0.5) / CASES * parSymbole
        }
    }

    private fun symbole(i: Double, q: Double) {
        // z · conj(z₋₁)
        val di = i * prevI + q * prevQ
        val dq = q * prevI - i * prevQ
        prevI = i; prevQ = q
        val p = di * di + dq * dq
        if (p <= 1e-30) return
        // The carrier gone (the end of an over): the squelch shuts at once, not as the quality decays.
        val m = sqrt(i * i + q * q)
        if (ouvert && m < 0.25 * niveau) { if (++faibles >= 3) { qualite = 0.0; ouvert = false } } else faibles = 0
        if (ouvert || qualite > 0.3) niveau += 0.05 * (m - niveau)
        // Squared: the data gone, the phase error doubled.
        val ci = (di * di - dq * dq) / p
        val cq = 2 * di * dq / p
        qualite += 0.1 * (ci - qualite)
        ouvert = if (ouvert) qualite > seuil - 0.1 else qualite > seuil
        if (qualite > 0.3) {
            val erreurHz = 0.5 * atan2(cq, ci) * BAUD / (2 * PI)
            afc = (afc + 0.08 * erreurHz).coerceIn(-AFC_HZ, AFC_HZ)
        }
        val bit = di > 0
        bitsRecents = (bitsRecents shl 1) or (if (bit) 1L else 0L)
        zeros = 64 - java.lang.Long.bitCount(bitsRecents)
        lecteur.bit(bit)
    }

    /** Every half ring: S/N, IMD on the idle, and the coarse search while nothing is locked. */
    private fun mesure() {
        val debut = rp
        // Squared signal: a line at twice the offset, the data gone.
        if (qualite < seuil) {
            val ci = DoubleArray(ANNEAU); val cq = DoubleArray(ANNEAU)
            for (k in 0 until ANNEAU) { val j = (debut + k) % ANNEAU; ci[k] = ri[j] * ri[j] - rq[j] * rq[j]; cq[k] = 2 * ri[j] * rq[j] }
            var meilleur = 0.0; var ou = 0.0; var somme = 0.0; var nb = 0
            var f = -(AFC_HZ + 10)
            while (f <= AFC_HZ + 10) {
                val p = puissanceDft(ci, cq, 0, ANNEAU, 2 * (f - afc), debit)
                somme += p; nb++
                if (p > meilleur) { meilleur = p; ou = f }
                f += 0.5
            }
            if (nb > 0 && meilleur > 12 * somme / nb && abs(ou) <= AFC_HZ) {
                afc = ou.coerceIn(-AFC_HZ, AFC_HZ)
                rempli = 0
            }
        }
        // In-band power against the noise just beside it.
        var sig = 0.0; var bruit = 0.0; var ns = 0; var nb = 0
        var f = -84.0
        while (f <= 84.0) {
            val p = puissanceDft(ri, rq, debut, ANNEAU, f, debit)
            if (abs(f) <= 30.0) { sig += p; ns++ } else if (abs(f) >= 52.0) { bruit += p; nb++ }
            f += 4.0
        }
        if (ns > 0 && nb > 0) {
            val n = bruit / nb
            val s = sig / ns - n
            snrDb = if (s <= 0 || n <= 0) -20.0 else 10 * log10(s / n)
        }
        // Idle (nearly all zeros): the two tones at ±15.6 Hz, the products at ±46.9.
        if (zeros >= 60 && ouvert) {
            val p1 = puissanceDft(ri, rq, debut, ANNEAU, BAUD / 2, debit) + puissanceDft(ri, rq, debut, ANNEAU, -BAUD / 2, debit)
            val p3 = puissanceDft(ri, rq, debut, ANNEAU, 1.5 * BAUD, debit) + puissanceDft(ri, rq, debut, ANNEAU, -1.5 * BAUD, debit)
            if (p1 > 0 && p3 > 0) imdDb = 10 * log10(p3 / p1)
        }
    }

    companion object {
        const val BAUD = 31.25
        /** How far the AFC may pull, Hz either side of the centre. */
        const val AFC_HZ = 50.0
        const val MIN_HZ = 200.0
        const val MAX_HZ = 3_500.0
        private const val ANNEAU = 512
        private const val CASES = 16
    }
}
