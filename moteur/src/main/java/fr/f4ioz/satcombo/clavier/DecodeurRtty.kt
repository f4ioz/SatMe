/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.clavier

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RTTY from audio: two tones [Reglages.shiftHz] apart around [centreHz]
 * (mark the upper one in USB, the lower with [Reglages.inverse]), start-stop
 * characters of five ITA2 bits (one start bit, 1.5 stop bits).
 *
 *   audio → mixed to 0 Hz on the centre, sinc² down to ≥ 1 kHz
 *         → each tone turned to 0 Hz and summed over one bit (its matched
 *           filter) → (|mark| − |space|) / (|mark| + |space|)
 *         → a start edge, then a sample in each bit's middle
 *         → the stop bit checked (framing) → Baudot → text, squelch open
 *
 * AFC: every half second the pair of tones is searched ±[AFC_HZ] around the
 * centre (both must be there: one tone alone moves nothing).
 */
class DecodeurRtty(private val fs: Int, private val sortie: (String) -> Unit, centreHz: Double = 1_500.0,
                   reglages: Reglages = Reglages()) {

    data class Reglages(val baud: Double = 45.45, val shiftHz: Double = 170.0, val inverse: Boolean = false, val usos: Boolean = true) {
        fun borne() = copy(baud = baud.coerceIn(30.0, 110.0), shiftHz = shiftHz.coerceIn(50.0, 1_000.0))
    }

    @Volatile var reglages: Reglages = reglages.borne()
        set(v) { field = v.borne(); change = true }
    @Volatile var centreHz: Double = centreHz.coerceIn(MIN_HZ, MAX_HZ)
        set(v) { field = v.coerceIn(MIN_HZ, MAX_HZ); change = true }
    @Volatile private var change = true

    /** Squelch threshold on the decision quality, 0..1. */
    @Volatile var seuil: Double = 0.6

    @Volatile var qualite = 0.0; private set
    @Volatile var ouvert = false; private set
    /** Tones heard (both of the pair), from the last search. */
    @Volatile var present = false; private set
    @Volatile var snrDb = Double.NaN; private set
    @Volatile private var afc = 0.0
    val frequenceHz: Double get() = centreHz + afc

    private var r = reglages
    private lateinit var descente: Descente
    private var debit = 0.0
    private var parBit = 0.0
    private var long = 1
    private var mI = DoubleArray(1); private var mQ = DoubleArray(1)
    private var sI = DoubleArray(1); private var sQ = DoubleArray(1)
    private var p = 0
    private var smI = 0.0; private var smQ = 0.0; private var ssI = 0.0; private var ssQ = 0.0
    private var phM = 0.0; private var phS = 0.0
    private var ri = DoubleArray(1); private var rq = DoubleArray(1)
    private var rp = 0; private var rempli = 0; private var depuis = 0

    // The character in progress: time (samples) since the start edge's middle, the bits.
    private var etat = ATTENTE
    private var t = 0.0
    private var prochain = 0.0
    private var code = 0
    private var nbits = 0
    private var dernier = 1.0
    private var niveau = 0.0
    private var faibles = 0

    private val lecteur = Baudot.Lecteur(true) { ch ->
        when (ch) { '\r' -> {}; '\n' -> sortie("\n"); else -> sortie(ch.toString()) }
    }

    private fun prepare() {
        change = false
        r = reglages
        lecteur.usos = r.usos
        // At least four times the highest tone (shift/2 + AFC + a bit's width), at least 1 kHz.
        val voulu = max(1_000.0, 4 * (r.shiftHz / 2 + AFC_HZ + r.baud))
        val f = max(1, (fs / voulu).toInt())
        descente = Descente(fs, f)
        debit = fs.toDouble() / f
        parBit = debit / r.baud
        long = max(2, parBit.roundToInt())
        mI = DoubleArray(long); mQ = DoubleArray(long); sI = DoubleArray(long); sQ = DoubleArray(long)
        p = 0; smI = 0.0; smQ = 0.0; ssI = 0.0; ssQ = 0.0
        val n = (debit * 0.5).toInt()
        ri = DoubleArray(n); rq = DoubleArray(n); rp = 0; rempli = 0; depuis = 0
        etat = ATTENTE; qualite = 0.0; ouvert = false; afc = 0.0; present = false
        lecteur.reinitialise()
    }

    fun reinitialise() { change = true }

    fun ecoute(pcm: ShortArray, n: Int) { for (k in 0 until n) ecoute(pcm[k] / 32_768.0) }
    fun ecoute(x: FloatArray, n: Int) { for (k in 0 until n) ecoute(x[k].toDouble()) }

    private fun ecoute(x: Double) {
        if (change) prepare()
        descente.frequenceHz = centreHz
        if (!descente.pousse(x)) return
        val bi = descente.sortieI; val bq = descente.sortieQ
        ri[rp] = bi; rq[rp] = bq
        rp = (rp + 1) % ri.size
        if (rempli < ri.size) rempli++
        if (++depuis >= ri.size) { depuis = 0; cherche() }

        val demi = r.shiftHz / 2 * (if (r.inverse) -1 else 1)
        phM = tourne(phM, afc + demi); phS = tourne(phS, afc - demi)
        val cm = cos(phM); val sm = sin(phM); val cs = cos(phS); val ss = sin(phS)
        // Each tone to 0 Hz, summed over one bit.
        val ai = bi * cm + bq * sm; val aq = bq * cm - bi * sm
        val bi2 = bi * cs + bq * ss; val bq2 = bq * cs - bi * ss
        smI += ai - mI[p]; smQ += aq - mQ[p]; ssI += bi2 - sI[p]; ssQ += bq2 - sQ[p]
        mI[p] = ai; mQ[p] = aq; sI[p] = bi2; sQ[p] = bq2
        if (++p == long) {
            p = 0
            smI = mI.sum(); smQ = mQ.sum(); ssI = sI.sum(); ssQ = sQ.sum()
        }
        val am = sqrt(smI * smI + smQ * smQ); val asp = sqrt(ssI * ssI + ssQ * ssQ)
        val v = (am - asp) / (am + asp + 1e-12)
        bit(v, am + asp)
    }

    private fun tourne(ph: Double, hz: Double): Double {
        var x = ph + 2 * PI * hz / debit
        if (x > PI) x -= 2 * PI else if (x < -PI) x += 2 * PI
        return x
    }

    /** The start-stop machine, one baseband sample at a time ([v] > 0: mark). */
    private fun bit(v: Double, a: Double) {
        when (etat) {
            ATTENTE -> {
                // Mark to space: the edge (as the bit filter sees it, half a bit late).
                if (dernier >= 0 && v < 0) { etat = DEPART; t = 0.0; prochain = parBit / 2 }
            }
            else -> {
                t += 1.0
                if (t >= prochain) {
                    qualite += 0.05 * (abs(v) - qualite)
                    // The tones gone (the end of an over): the squelch shuts at once.
                    if (ouvert && a < 0.25 * niveau) { if (++faibles >= 2) { qualite = 0.0; ouvert = false } } else faibles = 0
                    if (ouvert || qualite > 0.4) niveau += 0.05 * (a - niveau)
                    ouvert = if (ouvert) qualite > seuil - 0.1 else qualite > seuil
                    when (etat) {
                        DEPART -> if (v < 0) { etat = DONNEES; code = 0; nbits = 0; prochain += parBit } else etat = ATTENTE
                        DONNEES -> {
                            if (v > 0) code = code or (1 shl nbits)
                            nbits++
                            prochain += parBit
                            if (nbits == 5) etat = ARRET
                        }
                        ARRET -> {
                            if (v > 0 && ouvert) lecteur.code(code)
                            etat = ATTENTE
                        }
                    }
                }
            }
        }
        dernier = v
    }

    /** Every half second: the pair of tones searched ±[AFC_HZ] around the centre. */
    private fun cherche() {
        if (rempli < ri.size) return
        val demi = r.shiftHz / 2
        val pas = 1.0
        val bord = AFC_HZ + demi + 20
        val n = (2 * bord / pas).toInt() + 1
        val spectre = DoubleArray(n) { k -> puissanceDft(ri, rq, rp, ri.size, -bord + k * pas, debit) }
        fun bande(hz: Double): Double {
            // ±12 Hz around a tone: a keyed tone spreads, its middle is what matters.
            var s = 0.0
            val a = ((hz - 12 + bord) / pas).roundToInt().coerceAtLeast(0)
            val b = ((hz + 12 + bord) / pas).roundToInt().coerceAtMost(n - 1)
            for (k in a..b) s += spectre[k]
            return s
        }
        var meilleur = 0.0; var ou = 0.0
        val g = ArrayList<Double>()
        var o = -AFC_HZ
        while (o <= AFC_HZ) {
            val v = sqrt(bande(o + demi) * bande(o - demi))
            g += v
            if (v > meilleur) { meilleur = v; ou = o }
            o += 1.0
        }
        val median = g.sorted()[g.size / 2]
        present = meilleur > 4 * median
        if (present) afc = ou
        // S/N: the two tones' bands against the spectrum's quiet half.
        val tri = spectre.sorted()
        val bruit = tri.subList(0, tri.size / 2).average()
        val sig = bande(afc + demi) + bande(afc - demi)
        val nb = (2 * 24 / pas + 2)
        snrDb = if (bruit <= 0) Double.NaN else 10 * log10(max(1e-3, sig / (bruit * nb) - 1))
    }

    companion object {
        const val AFC_HZ = 50.0
        const val MIN_HZ = 300.0
        const val MAX_HZ = 3_200.0
        private const val ATTENTE = 0
        private const val DEPART = 1
        private const val DONNEES = 2
        private const val ARRET = 3

        /** The usual speeds and shifts offered. */
        val BAUDS = listOf(45.45, 50.0, 75.0)
        val SHIFTS = listOf(170.0, 425.0, 850.0)
    }
}
