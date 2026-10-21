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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Locks the whole I/Q stream on a QO-100 beacon: what the LNB's oscillator
 * does to the beacon it does to every station, so the beacon's quick
 * wanderings, followed here, are taken out of everything.
 *
 * Why: a new LNB (21 October) made every station wander ±25 Hz within a
 * second, around a drift of several hertz a second. FT8's tones are
 * 6.25 Hz apart: nothing decoded. The calibration from the panorama (a
 * measure a second, steps) cannot follow that; this can.
 *
 *  - The beacon's channel: the raw stream turned so the beacon's expected
 *    place ([decalageHz], Hz from the stream's centre) is at zero, summed by
 *    blocks down to about 44 kHz, low-passed (±1.5 kHz), decimated by 10:
 *    about 4.4 kHz.
 *  - Squared: the middle beacon is BPSK (its phase jumps by π); squaring
 *    leaves a plain line at twice its offset.
 *  - Found by a transform of half a second — only a line standing well
 *    clear of the rest ([SNR_ACQUISITION]) — then followed by a
 *    second-order phase loop of [bandeHz]; the beacon's frequency is read
 *    from the loop's integrator (smooth), not from its phase steps. Not
 *    narrow: ±25 Hz within half a second is a phase swing of several
 *    radians (doubled by the squaring), which an 8 Hz loop does not hold;
 *    the beacon is strong enough (≈ 35 dB in 30 Hz once squared) for a
 *    wide one to stay quiet.
 *  - The beacon is held where it was at the last reference ([refHz]): its
 *    every move since — drift and jitter alike — is taken out of the
 *    stream, continuously, within ±[BORNE_HZ], weighed in over a second and
 *    out as the loop's agreement falls. Within an FT8 slot, every station
 *    then stands still. The reference follows the beacon's slow part only
 *    in the last second of each 15 s (between FT8 and FT4 transmissions):
 *    a step there costs nothing, and the panorama and calibration still
 *    see the slow error between steps.
 *  - The slow part (a tracker that keeps up with a ramp, [TAU_LENT_S]) also
 *    measures the quick part taken out ([gigueHz]). Self-check: a
 *    correction that keeps hitting its bound, or a quick part far beyond
 *    what an LNB does ([GIGUE_MAX_HZ]), is a loop on something else; it
 *    lets go and waits ([PAUSE_S]) before searching again.
 *
 * Live, 21 October: a first version (no bound, the quick part measured
 * against a 20 s mean that lagged a drifting LNB by 100–200 Hz) added up to
 * 970 Hz rms while it settled, and FT8 lost two minutes before decoding.
 *
 * Receive only. Off unless [actif].
 */
class VerrouBalise(private val rate: Double, private val bandeHz: Double = 30.0) {

    /** On. */
    @Volatile var actif = false

    /** Where the beacon should be, Hz from the stream's centre (the slow calibration's view). */
    @Volatile var decalageHz = 0.0

    /** UTC ms: the reference moves in the last second of each 15 s. */
    @Volatile var horloge: () -> Long = System::currentTimeMillis
    private var dernierRecalage = Long.MIN_VALUE
    private var refHz = 0.0

    /** Locked on the beacon and taking its quick part out. */
    @Volatile var verrouille = false
        private set

    /** The quick part taken out lately, Hz rms (10 s). */
    @Volatile var gigueHz = 0.0
        private set

    /** The beacon's offset from [decalageHz], slow part, Hz (what the calibration should still take). */
    @Volatile var ecartLentHz = 0.0
        private set

    /** The correction applied now, Hz. */
    @Volatile var correctionHz = 0.0
        private set

    /** Let go by the self-check (the correction made no sense) since the last search. */
    @Volatile var incoherent = false
        private set

    private val d1 = maxOf(1, (rate / 44_100.0).roundToInt())
    private val rate1 = rate / d1
    private val d2 = 10
    private val fs = rate1 / d2
    private val taps = Dsp.lowPass(63, 1_500.0, rate1)
    private val bufI = FloatArray(taps.size)
    private val bufQ = FloatArray(taps.size)
    private var pos = 0
    private var phase2 = 0

    // The beacon channel's mixer.
    private var mixHz = Double.NaN
    private var mr = 1.0; private var mi = 0.0
    private var pr = 1.0; private var pi = 0.0
    private var compteMix = 0

    // Block sum (first decimation).
    private var si = 0.0; private var sq = 0.0; private var dans = 0

    // Acquisition.
    private val nAcq = 2048
    private val acqI = FloatArray(nAcq)
    private val acqQ = FloatArray(nAcq)
    private var nAcqPleins = 0
    private var pause = 0L

    // Phase loop on the squared beacon (rad, rad/sample at fs).
    private var suit = false
    private var theta2 = 0.0
    private var omega2 = 0.0
    private val k1: Double
    private val k2: Double
    private var accord = 0.0
    private var basDepuis = 0

    // The slow part: position and rate (Hz, Hz per sample).
    private var lentHz = 0.0
    private var lentPente = 0.0

    // The quick correction.
    private var poids = 0.0
    private var rapideHz = 0.0
    private var cr = 1.0; private var ci = 0.0
    private var compteCor = 0
    private var gigue2 = 0.0
    private var aLaBorne = 0.0

    init {
        val zeta = 0.707
        val wn = 2.0 * bandeHz / (zeta + 1.0 / (4.0 * zeta))
        val t = 1.0 / fs
        k1 = 2.0 * zeta * wn * t
        k2 = wn * t * wn * t
    }

    fun reset() {
        suit = false; verrouille = false; nAcqPleins = 0; accord = 0.0; basDepuis = 0; pause = 0
        poids = 0.0; rapideHz = 0.0; correctionHz = 0.0; gigue2 = 0.0; gigueHz = 0.0; aLaBorne = 0.0
        cr = 1.0; ci = 0.0; si = 0.0; sq = 0.0; dans = 0; incoherent = false
        bufI.fill(0f); bufQ.fill(0f); pos = 0; phase2 = 0
    }

    /** [n] raw samples, turned in place by the quick correction. */
    fun process(re: FloatArray, im: FloatArray, n: Int) {
        if (!actif) return
        if (mixHz != decalageHz) {
            // The calibration moved: the beacon jumps in its channel; searched again (the correction fades meanwhile).
            if (!mixHz.isNaN()) { suit = false; nAcqPleins = 0 }
            mixHz = decalageHz
            val w = -2.0 * PI * mixHz / rate
            pr = cos(w); pi = sin(w)
        }
        // The reference moved to the slow part between transmissions (once per 15 s).
        val maintenant = horloge()
        val creneau = maintenant / 15_000L
        if (suit && Math.floorMod(maintenant, 15_000L) >= 14_000L && creneau != dernierRecalage) {
            dernierRecalage = creneau; refHz = lentHz
        }
        var wr = cos(-2.0 * PI * correctionHz / rate); var wi = sin(-2.0 * PI * correctionHz / rate)
        for (k in 0 until n) {
            val a = re[k].toDouble(); val b = im[k].toDouble()
            // The beacon's channel, from the raw (uncorrected) sample.
            si += a * mr - b * mi
            sq += a * mi + b * mr
            val nr = mr * pr - mi * pi; mi = mr * pi + mi * pr; mr = nr
            if (++compteMix == 4096) {
                compteMix = 0
                val m = sqrt(mr * mr + mi * mi); mr /= m; mi /= m
            }
            if (++dans == d1) {
                bufI[pos] = (si / d1).toFloat(); bufQ[pos] = (sq / d1).toFloat()
                pos = (pos + 1) % taps.size
                si = 0.0; sq = 0.0; dans = 0
                if (++phase2 == d2) {
                    phase2 = 0
                    var zi = 0.0; var zq = 0.0
                    var p = pos
                    for (t in taps.indices) {
                        zi += taps[t] * bufI[p]; zq += taps[t] * bufQ[p]
                        p = (p + 1) % taps.size
                    }
                    echantillon(zi, zq)
                    correctionHz = poids * rapideHz
                    wr = cos(-2.0 * PI * correctionHz / rate); wi = sin(-2.0 * PI * correctionHz / rate)
                }
            }
            // The stream turned by the quick part.
            re[k] = (a * cr - b * ci).toFloat()
            im[k] = (a * ci + b * cr).toFloat()
            val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
            if (++compteCor == 4096) {
                compteCor = 0
                val m = sqrt(cr * cr + ci * ci); cr /= m; ci /= m
            }
        }
        verrouille = suit && poids > 0.5
        gigueHz = sqrt(gigue2)
    }

    /** One sample of the beacon's channel (at [fs]). */
    private fun echantillon(zi: Double, zq: Double) {
        // Squared: BPSK's jumps by π gone.
        val wi = zi * zi - zq * zq
        val wq = 2 * zi * zq
        if (!suit) {
            // Fading out whatever was taken (a second).
            poids = maxOf(0.0, poids - 1.0 / fs)
            gigue2 += (correctionHz * correctionHz - gigue2) / (10.0 * fs)
            if (pause > 0) { pause--; return }
            acqI[nAcqPleins] = wi.toFloat(); acqQ[nAcqPleins] = wq.toFloat()
            if (++nAcqPleins == nAcq) { nAcqPleins = 0; acquiert() }
            return
        }
        val c = cos(theta2); val s = sin(theta2)
        // Phase error: the squared beacon against the loop's.
        val er = wi * c + wq * s
        val eq = wq * c - wi * s
        val e = atan2(eq, er)
        theta2 += omega2 + k1 * e
        omega2 += k2 * e
        if (theta2 > PI) theta2 -= 2 * PI else if (theta2 < -PI) theta2 += 2 * PI
        // The beacon's frequency (half the squared one's), from the loop's integrator, Hz.
        val hz = omega2 / 2.0 * fs / (2 * PI)
        // The slow part: a tracker that keeps up with a drift.
        // The slow part: critically damped, keeps up with a ramp.
        val ecart = hz - lentHz
        val w = 1.0 / (TAU_LENT_S * fs)
        lentPente += w * w * ecart
        lentHz += lentPente + 2.0 * w * ecart
        ecartLentHz = lentHz
        // Taken out: every move since the reference.
        val brut = hz - refHz
        rapideHz = brut.coerceIn(-BORNE_HZ, BORNE_HZ)
        aLaBorne += ((if (abs(brut) >= BORNE_HZ) 1.0 else 0.0) - aLaBorne) / (5.0 * fs)
        gigue2 += (ecart * ecart - gigue2) / (10.0 * fs)
        // Agreement: cos of the error, smoothed over half a second; weighed in over a second while it holds.
        accord += (cos(e) - accord) / (0.5 * fs)
        poids = if (accord >= ACCORD_MIN) minOf(1.0, poids + 1.0 / fs) else maxOf(0.0, poids - 2.0 / fs)
        if (accord < ACCORD_PERDU) {
            if (++basDepuis > 2 * fs) { suit = false; basDepuis = 0; nAcqPleins = 0 }
        } else basDepuis = 0
        // Self-check: hitting the bound, or swinging beyond what an LNB does.
        if (aLaBorne > 0.2 || (poids >= 1.0 && sqrt(gigue2) > GIGUE_MAX_HZ)) {
            suit = false; incoherent = true; nAcqPleins = 0
            pause = (PAUSE_S * fs).toLong()
        }
    }

    /** The squared beacon's line in half a second of channel: found clear of the rest, the loop starts on it. */
    private fun acquiert() {
        val re = acqI.copyOf(); val im = acqQ.copyOf()
        fr.f4ioz.satcombo.domain.Ft8Signal.fft(re, im)
        val p = DoubleArray(nAcq) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        var meilleur = 0
        for (k in 0 until nAcq) if (p[k] > p[meilleur]) meilleur = k
        val tri = p.sortedArray()
        val mediane = tri[nAcq / 2]
        if (mediane <= 0.0 || p[meilleur] < SNR_ACQUISITION * mediane) return
        val k = if (meilleur > nAcq / 2) meilleur - nAcq else meilleur
        fun pw(i: Int) = p[((i % nAcq) + nAcq) % nAcq]
        val a = pw(meilleur - 1); val b = pw(meilleur); val c = pw(meilleur + 1)
        val den = a - 2 * b + c
        val fin = k + if (den != 0.0) 0.5 * (a - c) / den else 0.0
        omega2 = 2 * PI * fin / nAcq
        theta2 = atan2(acqQ[nAcq - 1].toDouble(), acqI[nAcq - 1].toDouble())
        val hz = omega2 / 2.0 * fs / (2 * PI)
        // The slow part starts where the beacon is: nothing quick to take yet.
        lentHz = hz; lentPente = 0.0; rapideHz = 0.0; refHz = hz
        accord = 0.0; basDepuis = 0; aLaBorne = 0.0; incoherent = false
        suit = true
    }

    /** The beacon channel's rate (for tests). */
    val cadenceBalise: Double get() = fs

    companion object {
        /** The correction's bound, Hz: an LNB's jitter and 15 s of its drift, not a lost loop's swings. */
        const val BORNE_HZ = 250.0
        /** Beyond this (rms over 10 s), the loop is on something else. */
        const val GIGUE_MAX_HZ = 35.0
        /** The squared line over the median of half a second's spectrum (≈ 22 dB) before locking. */
        const val SNR_ACQUISITION = 150.0
        /** The loop's agreement for the correction to weigh in, and below which it is lost (after 2 s). */
        const val ACCORD_MIN = 0.5
        const val ACCORD_PERDU = 0.25
        /** The slow part's tracker, seconds. */
        const val TAU_LENT_S = 8.0
        /** After a self-check, seconds before searching again. */
        const val PAUSE_S = 30.0
    }
}
