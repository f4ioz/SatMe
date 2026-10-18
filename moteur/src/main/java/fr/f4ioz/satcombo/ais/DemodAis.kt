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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Both AIS channels from one I/Q stream centred on 162.000 MHz at [debit]
 * (288 kS/s by default): each channel (A at −25 kHz, B at +25 kHz) mixed
 * to zero, low-pass filtered and decimated to 48 kS/s (five samples a bit
 * at 9600 bit/s), its frequency read (the GMSK deviation is ±2.4 kHz) and
 * smoothed over about half a bit.
 *
 * **The clock**: an AIS burst is short (at most about 1 100 bits) and its
 * transmitter's clock good to a few ppm, so the right sampling instant does
 * not move within a frame. Each of the five sample phases has its own
 * slicer and HDLC deframer; the one nearest the eye's centre decodes the
 * frame, its neighbours decode it too (kept once) or fail their check.
 *
 * **The tuning offset** (the dongle's error shifts the frequency read) is
 * measured on the training sequence, whose alternation averages to it:
 * while a deframer hunts, its slicer follows the mean of the last 16 bits;
 * when it finds the start flag, it freezes the mean of the 16 bits before
 * the flag for the rest of the frame.
 *
 * [recu] gets each good message: its bits, the channel ('A' or 'B'), its
 * level in dB (relative to full scale).
 */
class DemodAis(val debit: Int = DEBIT, private val recu: (BooleanArray, Char, Double) -> Unit) {

    private val decim = debit / DEBIT_CANAL
    init { require(decim * DEBIT_CANAL == debit && decim >= 1) { "débit multiple de 48 kS/s attendu" } }

    private val taps = passeBas(decim * 24 + 1, COUPURE_HZ / debit)

    inner class Canal(val nom: Char, decalageHz: Double) {
        // The mixer's phasor, one turn every debit / gcd samples: a table.
        private val periode = run { var a = debit; var b = Math.abs(decalageHz.toInt()); while (b != 0) { val t = a % b; a = b; b = t }; debit / a }
        private val cosT = FloatArray(periode) { cos(-2 * PI * decalageHz * it / debit).toFloat() }
        private val sinT = FloatArray(periode) { sin(-2 * PI * decalageHz * it / debit).toFloat() }
        private var k = 0
        private val histI = FloatArray(taps.size * 2)
        private val histQ = FloatArray(taps.size * 2)
        private var h = 0
        private var compte = 0
        private var iAv = 0f; private var qAv = 0f
        private var f1 = 0f; private var f2 = 0f
        // The smoothed frequency's last samples, for the offset's means.
        private val ys = FloatArray(HISTOIRE)
        private var n = 0L
        private var somme = 0.0
        private var puissance = 0.0
        private var dernier = 0
        private var dernierN = -1_000_000L
        var trames = 0; private set

        private inner class Phase {
            var seuil = 0f
            val deframeur = HdlcAis.Deframeur { b -> livre(b) }
        }
        private val phases = Array(SPB) { Phase() }

        private fun livre(b: BooleanArray) {
            // The same frame from neighbouring phases: once.
            val cle = b.contentHashCode()
            if (cle == dernier && n - dernierN < 4 * HISTOIRE) return
            dernier = cle; dernierN = n
            trames++
            recu(b, nom, if (puissance > 0) 10 * log10(puissance) else -99.0)
        }

        fun echantillon(i: Float, q: Float) {
            val c = cosT[k]; val s = sinT[k]
            if (++k == periode) k = 0
            val mi = i * c - q * s
            val mq = i * s + q * c
            histI[h] = mi; histI[h + taps.size] = mi
            histQ[h] = mq; histQ[h + taps.size] = mq
            if (++h == taps.size) h = 0
            if (++compte < decim) return
            compte = 0
            var yi = 0f; var yq = 0f
            for (t in taps.indices) { val x = h + t; yi += taps[t] * histI[x]; yq += taps[t] * histQ[x] }
            canal48(yi, yq)
        }

        /** The mean of the samples from [de] back to [a] back (1: the last one). */
        private fun moyenne(de: Int, a: Int): Float {
            var s = 0f
            for (j in a + 1..de) s += ys[((n - j) % HISTOIRE).toInt()]
            return s / (de - a)
        }

        private fun canal48(i: Float, q: Float) {
            puissance = puissance * 0.98 + 0.02 * (i * i + q * q)
            // Frequency: the angle turned since the last sample, ±π/10 at the deviation.
            val f = atan2(q * iAv - i * qAv, i * iAv + q * qAv).toFloat()
            iAv = i; qAv = q
            val y = (f + f1 + f2) / 3f
            f2 = f1; f1 = f
            if (n >= COURT) somme -= ys[((n - COURT) % HISTOIRE).toInt()]
            somme += y
            ys[(n % HISTOIRE).toInt()] = y
            n++
            val ph = phases[(n % SPB).toInt()]
            val d = ph.deframeur
            val avant = d.enTrame
            if (!avant) ph.seuil = (somme / COURT).toFloat()
            d.niveau(y >= ph.seuil)
            // The start flag: the offset as the training sequence before it gave it.
            if (!avant && d.enTrame && n > HISTOIRE) ph.seuil = moyenne(COURT + 8 * SPB, 8 * SPB)
        }

        /** Good frames over the phases (each frame counts once per phase that decoded it). */
        val bonnes: Int get() = phases.sumOf { it.deframeur.bonnes }
    }

    val a = Canal('A', -25_000.0)
    val b = Canal('B', 25_000.0)

    /** [n] I/Q pairs of 8-bit unsigned samples (as rtl_tcp sends them). */
    fun traite8(iq: ByteArray, n: Int) {
        for (p in 0 until n) {
            val i = ((iq[2 * p].toInt() and 0xFF) - 127.5f) / 127.5f
            val q = ((iq[2 * p + 1].toInt() and 0xFF) - 127.5f) / 127.5f
            a.echantillon(i, q); b.echantillon(i, q)
        }
    }

    /** [n] I/Q pairs of floats, interleaved. */
    fun traite(iq: FloatArray, n: Int) {
        for (p in 0 until n) { val i = iq[2 * p]; val q = iq[2 * p + 1]; a.echantillon(i, q); b.echantillon(i, q) }
    }

    val trames: Int get() = a.trames + b.trames

    companion object {
        const val DEBIT = 288_000
        const val DEBIT_CANAL = 48_000
        const val BAUDS = 9600
        const val SPB = DEBIT_CANAL / BAUDS
        /** The offset's mean: over 16 bits. */
        const val COURT = 16 * SPB
        const val HISTOIRE = 32 * SPB
        const val COUPURE_HZ = 8_000.0
        const val CENTRE_HZ = 162_000_000L
        const val CANAL_A_HZ = 161_975_000L
        const val CANAL_B_HZ = 162_025_000L

        /** A windowed-sinc low pass of [n] taps, cut at [fc] (fraction of the sampling rate), unity gain. */
        fun passeBas(n: Int, fc: Double): FloatArray {
            val m = (n - 1) / 2.0
            val t = DoubleArray(n) { i ->
                val x = i - m
                val s = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                s * (0.54 - 0.46 * cos(2 * PI * i / (n - 1)))
            }
            val somme = t.sum()
            return FloatArray(n) { (t[it] / somme).toFloat() }
        }
    }
}
