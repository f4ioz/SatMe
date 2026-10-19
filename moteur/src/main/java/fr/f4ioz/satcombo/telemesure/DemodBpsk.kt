/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.sdr.ComplexDecimator
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * BPSK telemetry from a receiver's audio (a USB channel, the carrier
 * somewhere in [centreHz] ± [rechercheHz]), at [debit] bit/s, plain or
 * Manchester coded (each bit's second half inverted), told apart by itself.
 *
 *   audio → complex mix by −[centreHz] → low-pass and decimate
 *   → carrier found by the squared signal's spectrum (BPSK squared is a
 *     pure tone at twice the offset), then held by a Costas loop
 *   → half-bit matched filter, timing by Gardner's detector at two
 *     half-bits per bit (the edges of plain and Manchester bits both fall
 *     on half-bit boundaries)
 *   → the half-bits paired (which pairing, and whether the halves agree or
 *     oppose, chosen by their mean energy)
 *   → per bit two soft values: coherent (sign up to the 180° ambiguity) and
 *     differential (positive: the phase changed), both near ±1.
 *
 * [sortie] gets (differential, coherent) for each bit. No allocation per
 * block once warmed up; one thread.
 */
class DemodBpsk(
    private val fs: Int,
    val debit: Double,
    private val centreHz: Double = 1_500.0,
    private val rechercheHz: Double = 1_200.0,
    private val sortie: (Float, Float) -> Unit,
) {
    private val decim = maxOf(1, (fs / maxOf(16.0 * debit, 2.4 * (rechercheHz + debit))).toInt())
    private val fs2 = fs.toDouble() / decim
    private val coupure = minOf(rechercheHz + 2.0 * debit, 0.45 * fs2)
    private val dec = ComplexDecimator(Dsp.lowPass(maxOf(31, 10 * decim + 1), coupure, fs.toDouble()), decim)
    /** Samples per half-bit. */
    private val sps = fs2 / (2.0 * debit)
    private val lmf = maxOf(1, sps.roundToInt())

    // Mixer.
    private var mc = 1.0; private var ms = 0.0
    private val wMel = 2 * PI * centreHz / fs
    private val mcPas = cos(wMel); private val msPas = sin(wMel)
    private var bI = FloatArray(0); private var bQ = FloatArray(0)
    private var dI = FloatArray(0); private var dQ = FloatArray(0)

    // Acquisition: the squared signal's spectrum.
    private val nAcq = Integer.highestOneBit((fs2 * 1.1).toInt().coerceIn(1024, 16384))
    private val acqI = DoubleArray(nAcq); private val acqQ = DoubleArray(nAcq)
    private var nAcqPris = 0
    @Volatile var enAcquisition = true; private set

    // Carrier NCO and Costas loop (per half-bit), ζ = 0.707.
    private var phi = 0.0
    private var w = 0.0
    private val bl = 0.012
    private val ka: Double; private val kb: Double
    init {
        val z = 0.707
        val th = bl / (z + 1 / (4 * z))
        val d = 1 + 2 * z * th + th * th
        ka = 4 * z * th / d; kb = 4 * th * th / d
    }

    // Matched filter (boxcar over a half-bit).
    private val mfI = FloatArray(lmf); private val mfQ = FloatArray(lmf)
    private var mfPos = 0
    private var mfSI = 0.0; private var mfSQ = 0.0

    // Timing: a ring of filtered samples, the next strobe time.
    private val anneau = 64
    private val rI = DoubleArray(anneau); private val rQ = DoubleArray(anneau)
    private var n = 0L
    private var prochain = 0.0
    private var prI = 0.0; private var prQ = 0.0
    private var puissance = 1e-6

    // Half-bit pairing: [plain p0, plain p1, Manchester p0, Manchester p1].
    private val energie = DoubleArray(4)
    private var choix = 2
    private var kDemi = 0L
    private var hI = 0.0; private var hQ = 0.0
    private var bitI = 0.0; private var bitQ = 0.0
    private var echelleD = 1e-6; private var echelleC = 1e-6

    // Lock indicator: E[I² − Q²] / E[I² + Q²] on the half-bits.
    private var verrou = 0.0
    private var bitsSortis = 0L

    /** Carrier offset from [centreHz], Hz (the NCO). */
    val decalageHz: Double get() = w * fs2 / (2 * PI)
    /** 0..1: how well the Costas loop holds the carrier. */
    val verrouillage: Double get() = verrou.coerceIn(0.0, 1.0)
    /** True when the bits are Manchester coded (as heard). */
    val manchester: Boolean get() = choix >= 2
    /** The carrier's prominence when it was found, dB above the squared spectrum's median. */
    @Volatile var presenceDb: Double = 0.0; private set
    val bits: Long get() = bitsSortis

    /** Looks for the carrier again (a new pass, a lost signal). */
    fun reacquiert() { enAcquisition = true; nAcqPris = 0 }

    fun reinitialise() {
        dec.reset(); mc = 1.0; ms = 0.0; phi = 0.0; w = 0.0
        mfI.fill(0f); mfQ.fill(0f); mfPos = 0; mfSI = 0.0; mfSQ = 0.0
        rI.fill(0.0); rQ.fill(0.0); n = 0; prochain = 0.0; energie.fill(0.0); kDemi = 0
        verrou = 0.0; reacquiert()
    }

    /** [nb] audio samples. */
    fun traite(pcm: ShortArray, nb: Int) {
        if (bI.size < nb) { bI = FloatArray(nb); bQ = FloatArray(nb) }
        var c = mc; var s = ms
        for (k in 0 until nb) {
            val x = pcm[k].toFloat()
            bI[k] = (x * c).toFloat(); bQ[k] = (-x * s).toFloat()
            val nc = c * mcPas - s * msPas
            s = c * msPas + s * mcPas; c = nc
        }
        val m = 1.0 / sqrt(c * c + s * s); mc = c * m; ms = s * m
        val mo = dec.maxOut(nb)
        if (dI.size < mo) { dI = FloatArray(mo); dQ = FloatArray(mo) }
        val no = dec.process(bI, bQ, nb, dI, dQ)
        for (k in 0 until no) echantillon(dI[k].toDouble(), dQ[k].toDouble())
    }

    private fun echantillon(i0: Double, q0: Double) {
        if (enAcquisition) {
            acqI[nAcqPris] = i0; acqQ[nAcqPris] = q0
            if (++nAcqPris == nAcq) acquiert()
        }
        // NCO.
        val c = cos(phi); val s = sin(phi)
        val i = i0 * c + q0 * s
        val q = q0 * c - i0 * s
        phi += w
        if (phi > PI) phi -= 2 * PI else if (phi < -PI) phi += 2 * PI
        // Matched filter.
        mfSI += i - mfI[mfPos]; mfSQ += q - mfQ[mfPos]
        mfI[mfPos] = i.toFloat(); mfQ[mfPos] = q.toFloat()
        mfPos = if (mfPos + 1 == lmf) 0 else mfPos + 1
        val yI = mfSI / lmf; val yQ = mfSQ / lmf
        val idx = (n % anneau).toInt()
        rI[idx] = yI; rQ[idx] = yQ
        n++
        // Strobes due by now (the ring holds the samples up to n − 1).
        while (prochain <= (n - 1).toDouble()) {
            if (prochain - sps < maxOf(0L, n - anneau + 2).toDouble()) { prochain = (n - 1).toDouble() + sps; continue }
            interpole(prochain); val sI = tI; val sQ = tQ
            interpole(prochain - sps / 2); val mI = tI; val mQ = tQ
            // Gardner: (y[k] − y[k−1]) · y[k−½], phase-blind.
            val e = ((sI - prI) * mI + (sQ - prQ) * mQ) / (puissance + 1e-12)
            prI = sI; prQ = sQ
            prochain += sps - 0.04 * sps * e.coerceIn(-1.0, 1.0)
            demiBit(sI, sQ)
        }
    }

    private var tI = 0.0; private var tQ = 0.0

    /** The filtered signal at time [t] (linear between samples), into [tI], [tQ]. */
    private fun interpole(t: Double) {
        val t0 = Math.floor(t).toLong()
        val f = t - t0
        val a = (t0 % anneau).toInt()
        val b = if (t0 + 1 > n - 1) a else ((t0 + 1) % anneau).toInt()
        tI = rI[a] + f * (rI[b] - rI[a]); tQ = rQ[a] + f * (rQ[b] - rQ[a])
    }

    /** One half-bit (on time): Costas, pairing, and a bit every second one. */
    private fun demiBit(i: Double, q: Double) {
        val p = i * i + q * q
        puissance += 0.01 * (p - puissance)
        if (!enAcquisition) {
            val e = (i * q / (puissance + 1e-12)).coerceIn(-1.0, 1.0)
            phi += ka * e
            w += kb * e / sps
            verrou += 0.005 * ((i * i - q * q) / (puissance + 1e-12) - verrou)
        }
        val k = kDemi++
        val par = (k and 1L).toInt()
        val plein = hypot(hI + i, hQ + q)
        val oppose = hypot(hI - i, hQ - q)
        energie[par] += 0.01 * (plein - energie[par])
        energie[2 + par] += 0.01 * (oppose - energie[2 + par])
        var mx = choix
        for (j in 0..3) if (energie[j] > energie[mx] * 1.15) mx = j
        choix = mx
        if (par == choix % 2) {
            val cI = if (choix >= 2) (hI - i) / 2 else (hI + i) / 2
            val cQ = if (choix >= 2) (hQ - q) / 2 else (hQ + q) / 2
            // Differential: −Re(b · b̄prev), positive when the phase turned.
            val d = -(cI * bitI + cQ * bitQ)
            bitI = cI; bitQ = cQ
            echelleD += 0.01 * (abs(d) - echelleD)
            echelleC += 0.01 * (abs(cI) - echelleC)
            bitsSortis++
            if (!enAcquisition) sortie((d / (echelleD + 1e-12)).toFloat(), (cI / (echelleC + 1e-12)).toFloat())
        }
        hI = i; hQ = q
    }

    /** The carrier from the squared signal's spectrum. */
    private fun acquiert() {
        val re = DoubleArray(nAcq); val im = DoubleArray(nAcq)
        for (k in 0 until nAcq) {
            val wv = 0.5 - 0.5 * cos(2 * PI * k / (nAcq - 1))
            re[k] = (acqI[k] * acqI[k] - acqQ[k] * acqQ[k]) * wv
            im[k] = 2 * acqI[k] * acqQ[k] * wv
        }
        Fft.transform(re, im)
        val mag = DoubleArray(nAcq) { re[it] * re[it] + im[it] * im[it] }
        val pasHz = fs2 / nAcq
        val lim = minOf((2 * rechercheHz / pasHz).toInt(), nAcq / 2 - 2)
        var best = 0; var bv = -1.0
        for (b in -lim..lim) {
            val v = mag[Math.floorMod(b, nAcq)]
            if (v > bv) { bv = v; best = b }
        }
        val ym = mag[Math.floorMod(best - 1, nAcq)]; val yp = mag[Math.floorMod(best + 1, nAcq)]
        val den = ym - 2 * bv + yp
        val delta = if (den != 0.0) (0.5 * (ym - yp) / den).coerceIn(-0.5, 0.5) else 0.0
        val f2 = (best + delta) * pasHz
        val tri = DoubleArray(2 * lim + 1) { mag[Math.floorMod(it - lim, nAcq)] }.sorted()
        val med = tri[tri.size / 2]
        presenceDb = if (med > 0) 10 * log10(bv / med) else 0.0
        w = 2 * PI * (f2 / 2) / fs2
        phi = 0.0; verrou = 0.0
        enAcquisition = false
        nAcqPris = 0
    }

    companion object {
        /** Squared-spectrum prominence below which nothing is taken for a carrier. */
        const val PRESENCE_MIN_DB = 12.0
    }
}
