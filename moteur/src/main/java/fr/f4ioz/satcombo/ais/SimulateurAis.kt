/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ais

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * GMSK as AIS sends it (BT 0.4, modulation index 0.5: ±2.4 kHz at
 * 9600 bit/s), written into an I/Q buffer: for the tests and the trial
 * vessels. Never sent to a transmitter.
 */
object ModulateurAis {
    const val BT = 0.4
    const val DEVIATION_HZ = 2400.0

    /** The Gaussian frequency pulse for [spb] samples a bit, ±2 bits long, summing to one. */
    fun impulsion(spb: Int): DoubleArray {
        val sigma = sqrt(ln(2.0)) / (2 * PI * BT) * spb
        val n = 4 * spb + 1
        val h = DoubleArray(n) { val t = (it - n / 2).toDouble(); exp(-t * t / (2 * sigma * sigma)) }
        val s = h.sum()
        return DoubleArray(n) { h[it] / s }
    }

    /**
     * Adds [niveaux] (the NRZI levels on the air, from [HdlcAis.trame])
     * into [out] (interleaved I/Q at [debit]) from pair [debut], shifted
     * by [decalageHz], at amplitude [amp]. The pairs written, or 0 when
     * they do not fit.
     */
    fun ajoute(niveaux: BooleanArray, debit: Int, decalageHz: Double, amp: Double, out: FloatArray, debut: Int, phase0: Double = 0.0): Int {
        val spb = debit / 9600
        val h = impulsion(spb)
        val n = niveaux.size * spb + h.size
        if (debut < 0 || (debut + n) * 2 > out.size) return 0
        var ph = phase0
        val demi = h.size / 2
        val total = niveaux.size * spb
        for (k in 0 until n) {
            // The NRZ symbol stream through the pulse: ±1 in steady state.
            var f = 0.0
            for (j in h.indices) { val s = k - j; if (s in 0 until total) f += if (niveaux[s / spb]) h[j] else -h[j] }
            ph += 2 * PI * (decalageHz + DEVIATION_HZ * f) / debit
            val e = if (k < demi || k >= n - demi) 0.5 else 1.0
            out[2 * (debut + k)] += (amp * e * cos(ph)).toFloat()
            out[2 * (debut + k) + 1] += (amp * e * sin(ph)).toFloat()
        }
        return n
    }

    /** Gaussian noise of standard deviation [sigma] on each of I and Q, into [n] pairs of [out]. */
    fun bruit(out: FloatArray, n: Int, sigma: Double, r: Random) {
        for (i in 0 until 2 * n) {
            val u1 = r.nextDouble().coerceAtLeast(1e-12); val u2 = r.nextDouble()
            out[i] += (sigma * sqrt(-2 * ln(u1)) * cos(2 * PI * u2)).toFloat()
        }
    }
}

/**
 * Trial vessels around a position, for the AIS tab's « essai » and the
 * tests: class A ships (positions every few seconds, their static data
 * now and then), a class B yacht, a buoy (aid to navigation); every message
 * framed, GMSK modulated on channel A or B in noise, at 288 kS/s.
 */
class SimulateurAis(private val lat0: Double, private val lon0: Double, graine: Int = 1, val debit: Int = DemodAis.DEBIT) {
    private val r = Random(graine)

    private class Navire(val mmsi: Int, val nom: String, val indicatif: String, val type: Int, var lat: Double, var lon: Double,
                         var vitesse: Double, var route: Double, val classe: Char, val destination: String, val dims: IntArray, var prochain: Long, var compte: Int = 0)

    private val navires = listOf(
        Navire(227006760, "ILE DE BREHAT", "FNXC", 60, lat0 + 0.12, lon0 - 0.20, 14.5, 300.0, 'A', "ROSCOFF", intArrayOf(120, 30, 12, 12), 0),
        Navire(636019825, "NORDIC STAR", "D5RT7", 70, lat0 - 0.18, lon0 + 0.25, 11.8, 75.0, 'A', "ROTTERDAM", intArrayOf(150, 40, 16, 16), 400),
        Navire(538007114, "OCEAN PEARL", "V7KL3", 80, lat0 + 0.30, lon0 + 0.10, 9.6, 210.0, 'A', "LE HAVRE", intArrayOf(200, 50, 22, 22), 900),
        Navire(228137800, "MARIE-ANNE", "FGH2245", 30, lat0 - 0.05, lon0 - 0.35, 4.2, 140.0, 'A', "", intArrayOf(14, 6, 3, 3), 1500),
        Navire(227154200, "ALBATROS", "FAB1234", 36, lat0 + 0.02, lon0 + 0.06, 5.5, 20.0, 'B', "", intArrayOf(8, 4, 2, 2), 2100),
        Navire(992271001, "BOUEE CARDINALE N", "", 0, lat0 + 0.08, lon0 - 0.05, 0.0, 0.0, 'N', "", intArrayOf(1, 1, 1, 1), 2600),
    )
    private var t = 0L
    private var canal = false
    var envoyes = 0; private set
    /** The noise on I and Q (signal amplitude 0.3). */
    var bruit = 0.02

    /** The messages a vessel sends next: its position, and its static data one time in six. */
    private fun messages(n: Navire): List<BooleanArray> {
        val l = ArrayList<BooleanArray>()
        when (n.classe) {
            'A' -> {
                l += EncodeurAis.position(n.mmsi, n.lat, n.lon, n.vitesse, n.route, Math.round(n.route).toInt() % 360, if (n.type == 30) 7 else 0, seconde = ((t / 1000) % 60).toInt())
                if (n.compte % 6 == 0) l += EncodeurAis.statique(n.mmsi, n.nom, n.indicatif, n.type, n.dims, n.destination, tirant = n.dims[0] / 25.0)
            }
            'B' -> {
                l += EncodeurAis.classeB(n.mmsi, n.lat, n.lon, n.vitesse, n.route, null, seconde = ((t / 1000) % 60).toInt())
                if (n.compte % 6 == 0) { l += EncodeurAis.statiqueA(n.mmsi, n.nom); l += EncodeurAis.statiqueB(n.mmsi, n.type, n.indicatif, n.dims) }
            }
            else -> l += EncodeurAis.aide(n.mmsi, 20, n.nom, n.lat, n.lon, n.dims)
        }
        n.compte++
        return l
    }

    /**
     * [ms] of signal into [out] (interleaved I/Q floats): the vessels moved,
     * those due heard. The pairs written.
     */
    fun bloc(ms: Int, out: FloatArray): Int {
        val n = (debit.toLong() * ms / 1000).toInt().coerceAtMost(out.size / 2)
        java.util.Arrays.fill(out, 0, 2 * n, 0f)
        ModulateurAis.bruit(out, n, bruit, r)
        var place = 0
        for (v in navires) {
            // Dead reckoning: knots to degrees.
            val d = v.vitesse * ms / 3_600_000.0 / 60.0
            v.lat += d * cos(Math.toRadians(v.route)); v.lon += d * sin(Math.toRadians(v.route)) / cos(Math.toRadians(v.lat))
            if (t < v.prochain) continue
            v.prochain = t + when (v.classe) { 'A' -> if (v.vitesse > 14) 2000L else 4000L; 'B' -> 15_000L; else -> 30_000L }
            for (m in messages(v)) {
                val niveaux = HdlcAis.trame(m)
                canal = !canal
                val w = ModulateurAis.ajoute(niveaux, debit, if (canal) -25_000.0 else 25_000.0, 0.3, out, place, r.nextDouble() * 2 * PI)
                if (w == 0) break
                place += w + 200
                envoyes++
            }
        }
        t += ms
        return n
    }
}
