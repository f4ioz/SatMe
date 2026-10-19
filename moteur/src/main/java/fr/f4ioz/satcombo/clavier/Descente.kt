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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio to complex baseband: mixed by −[frequenceHz] (a rotating phasor),
 * then two boxcars of [facteur] samples (a sinc² response: the mixing image
 * and the neighbours far off fall in its nulls) and one sample kept in
 * [facteur]. The output rate is fs / facteur.
 */
internal class Descente(private val fs: Int, val facteur: Int) {
    var frequenceHz = 0.0
    private var c = 1.0
    private var s = 0.0
    private var fPas = 0.0
    private var pc = 1.0
    private var ps = 0.0
    private val anneauI = DoubleArray(facteur)
    private val anneauQ = DoubleArray(facteur)
    private var k = 0
    private var sommeI = 0.0
    private var sommeQ = 0.0
    private var accI = 0.0
    private var accQ = 0.0
    private var n = 0

    var sortieI = 0.0; private set
    var sortieQ = 0.0; private set

    /** One audio sample; true when an output ([sortieI], [sortieQ]) is ready. */
    fun pousse(x: Double): Boolean {
        val i = x * c
        val q = -x * s
        if (frequenceHz != fPas) { fPas = frequenceHz; val w = 2.0 * PI * frequenceHz / fs; pc = cos(w); ps = sin(w) }
        val nc = c * pc - s * ps
        s = c * ps + s * pc
        c = nc
        sommeI += i - anneauI[k]; sommeQ += q - anneauQ[k]
        anneauI[k] = i; anneauQ[k] = q
        if (++k == facteur) {
            k = 0
            // Drift of the running sums wiped out once per turn.
            var a = 0.0; var b = 0.0
            for (j in 0 until facteur) { a += anneauI[j]; b += anneauQ[j] }
            sommeI = a; sommeQ = b
            val m = 1.0 / sqrt(c * c + s * s)
            c *= m; s *= m
        }
        accI += sommeI; accQ += sommeQ
        if (++n < facteur) return false
        n = 0
        val g = 1.0 / (facteur.toDouble() * facteur)
        sortieI = accI * g; sortieQ = accQ * g
        accI = 0.0; accQ = 0.0
        return true
    }

    fun reinitialise() {
        anneauI.fill(0.0); anneauQ.fill(0.0); k = 0; sommeI = 0.0; sommeQ = 0.0; accI = 0.0; accQ = 0.0; n = 0
    }
}

/** A complex FIR with real taps, one sample at a time. */
internal class FirComplexe(private val h: FloatArray) {
    private val bi = DoubleArray(h.size)
    private val bq = DoubleArray(h.size)
    private var p = 0
    var i = 0.0; private set
    var q = 0.0; private set

    fun pousse(xi: Double, xq: Double) {
        bi[p] = xi; bq[p] = xq
        var a = 0.0; var b = 0.0
        var j = p
        for (t in h.indices) {
            a += h[t] * bi[j]; b += h[t] * bq[j]
            j = if (j == 0) h.size - 1 else j - 1
        }
        p = if (p == h.size - 1) 0 else p + 1
        i = a; q = b
    }

    fun reinitialise() { bi.fill(0.0); bq.fill(0.0); p = 0 }
}

/** Power of [n] complex samples (oldest at [debut] in a ring) at [hz], Hann-windowed DFT. */
internal fun puissanceDft(ri: DoubleArray, rq: DoubleArray, debut: Int, n: Int, hz: Double, debit: Double): Double {
    val w = -2.0 * PI * hz / debit
    val pc = cos(w); val ps = sin(w)
    var c = 1.0; var s = 0.0
    var a = 0.0; var b = 0.0
    val taille = ri.size
    for (k in 0 until n) {
        val j = (debut + k) % taille
        val f = 0.5 - 0.5 * cos(2.0 * PI * k / n)
        val x = ri[j] * f; val y = rq[j] * f
        a += x * c - y * s; b += x * s + y * c
        val nc = c * pc - s * ps; s = c * ps + s * pc; c = nc
    }
    return (a * a + b * b) / (n.toDouble() * n)
}
