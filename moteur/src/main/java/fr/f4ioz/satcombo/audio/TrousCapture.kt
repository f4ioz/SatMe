/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import kotlin.math.roundToLong

/**
 * Samples a sound capture lost: told, after each read, how many samples came
 * ([bloc]'s n), when (the PC's monotonic clock) and how many wait in the
 * card's buffer, it says how many went missing just before them.
 *
 * The samples captured so far (read, waiting, and those found missing) are
 * compared with what the time elapsed at the card's rate makes: the
 * difference ("the lag") stays nearly flat while nothing is lost. The card's
 * crystal is not the PC's — a few hundred ppm, tens of milliseconds over a
 * pass — so the lag's normal level is followed slowly (its level and its
 * slope); only a jump over [seuilMs] above it is a gap. The samples waiting
 * in the buffer count as captured: a capture thread held back a moment finds
 * them there and that is no loss — a buffer overrun throws them away, and the
 * lag jumps by what was thrown.
 *
 * A gap of up to [maxMs] is to be filled (with silence) before the block, so
 * that what comes after keeps its time; a longer one is a cut (« coupure »):
 * said, not filled, the count set right again.
 *
 * Without the buffer's level ([bloc]'s enAttente < 0) nothing is judged: a
 * thread late by half a second would look like a gap there.
 */
class TrousCapture(
    val rate: Int,
    val seuilMs: Double = SEUIL_MS,
    val maxMs: Double = MAX_MS,
    /** How slowly the lag's normal level follows (s). */
    private val tauS: Double = 4.0,
    /** The first moments only learn the level (ms). */
    private val chauffeMs: Double = 300.0,
) {
    /** One block's verdict: [manquants] samples to put before it, or a [coupureMs] too long to fill (0 / 0: nothing lost). */
    data class Verdict(val manquants: Int, val coupureMs: Long) {
        val rien: Boolean get() = manquants == 0 && coupureMs == 0L
    }

    private var t0 = Long.MIN_VALUE
    private var tDernier = 0L
    /** Samples read, plus those found missing. */
    private var compte = 0L
    /** The lag's normal level (samples) at [tBase], and its slope (samples a second). */
    private var base = 0.0
    private var tBase = 0L
    private var pente = 0.0
    /** The level once a second, for the slope: (ns, samples). */
    private val historique = ArrayDeque<Pair<Long, Double>>()

    /** Gaps found, samples found missing (filled or not), cuts. */
    @Volatile var trous = 0; private set
    @Volatile var manquantsTotal = 0L; private set
    @Volatile var coupures = 0; private set

    /** The card's clock against the PC's, ppm (negative: the card gives fewer samples than its rate says); 0 until measured over 10 s. */
    val ppm: Double get() = if (historique.size < 10) 0.0 else -pente / rate * 1e6

    fun bloc(n: Int, tNs: Long, enAttente: Int): Verdict {
        if (t0 == Long.MIN_VALUE) {
            t0 = tNs; tDernier = tNs; tBase = tNs
            compte = n.toLong()
            base = -(compte + enAttente.coerceAtLeast(0)).toDouble()
            return RIEN
        }
        compte += n
        val dtBloc = (tNs - tDernier).coerceAtLeast(0L) / 1e9
        tDernier = tNs
        if (enAttente < 0) return RIEN
        val ecouleS = (tNs - t0) / 1e9
        val retard = ecouleS * rate - (compte + enAttente)
        val prevu = base + pente * ((tNs - tBase) / 1e9)
        val e = retard - prevu
        val seuil = seuilMs * rate / 1000.0
        val chauffe = ecouleS * 1000.0 < chauffeMs
        if (!chauffe && e > seuil) {
            val k = e.roundToLong()
            compte += k
            base = prevu; tBase = tNs
            trous++
            manquantsTotal += k
            return if (k <= maxMs * rate / 1000.0) Verdict(k.toInt(), 0L)
            else { coupures++; Verdict(0, k * 1000L / rate) }
        }
        // The normal level followed: a late reading (or one early) moves it only a little.
        val alpha = if (chauffe) 0.5 else (dtBloc / tauS).coerceAtMost(1.0)
        base = prevu + alpha * (if (chauffe) e else e.coerceIn(-seuil / 2, seuil / 2))
        tBase = tNs
        if (!chauffe && (historique.isEmpty() || tNs - historique.last().first >= 1_000_000_000L)) {
            historique.addLast(tNs to base)
            while (historique.size > 61) historique.removeFirst()
            if (historique.size >= 10) {
                val (ta, ba) = historique.first()
                pente = (base - ba) / ((tNs - ta) / 1e9)
            }
        }
        return RIEN
    }

    companion object {
        /** A jump of the lag over this is a gap (ms): well above a USB codec's few milliseconds of granularity. */
        const val SEUIL_MS = 20.0
        /** Gaps up to this are filled; longer ones are cuts (ms). */
        const val MAX_MS = 2_000.0
        private val RIEN = Verdict(0, 0L)
    }
}
