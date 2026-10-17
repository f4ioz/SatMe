/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A calibration bench without radio: a tone corrected by [correction] (as
 * the transmitter's stage does), a transmitter with I/Q impairments
 * ([matrice], [dc]) and a crystal [ppm] off, heard by a receiver tuned
 * [decalageRxHz] below its LO, with noise and the receiver's own DC and
 * image. Read as the receiver ([read16]); a zero timeout finds nothing queued.
 */
class CanalIqSimule(
    private val correction: CorrectionIq,
    private val debit: Double = 2_000_000.0,
    private val tonHz: Double = 200_000.0,
    private val decalageRxHz: Double = 400_000.0,
    private val frequenceHz: Double = 1_245_000_000.0,
    var matrice: Array<DoubleArray> = arrayOf(doubleArrayOf(1.03, 0.04), doubleArrayOf(-0.02, 0.97)),
    var dc: DoubleArray = doubleArrayOf(0.006, -0.004),
    var ppm: Double = 3.2,
    /** The receiver's gain on the transmitter's full scale, and its noise (both of its full scale). */
    private val niveau: Double = 0.2,
    private val bruit: Double = 2e-5,
    graine: Long = 1L,
) : SourceIq {
    private val ton = TonIq(tonHz, debit)
    private val alea = Random(graine)
    private var t = 0L
    var blocs = 0; private set

    override val iq16: Boolean get() = true
    override fun setSampleRate(rate: Int): Double = rate.toDouble()
    override fun setFreqCorrection(ppm: Int) {}
    override fun setCenterFreq(hz: Long): Long = hz
    override fun setGain(tenthDb: Int?) {}
    override fun setAgc(on: Boolean) {}
    override fun read(buf: ByteArray, timeoutMs: Int): Int = throw UnsupportedOperationException()
    override fun close() {}

    override fun read16(buf: ShortArray, timeoutMs: Int): Int {
        if (timeoutMs == 0) return 0
        val n = minOf(8192, buf.size / 2)
        val x = ShortArray(2 * n)
        ton.remplis(x, n)
        EtageIq.corrige(x, n, correction.parametres)
        val decale = decalageRxHz + ppm * 1e-6 * frequenceHz
        for (k in 0 until n) {
            val i = x[2 * k] / 32767.0; val q = x[2 * k + 1] / 32767.0
            val yi = matrice[0][0] * i + matrice[0][1] * q + dc[0]
            val yq = matrice[1][0] * i + matrice[1][1] * q + dc[1]
            val w = 2 * PI * decale * (t + k) / debit
            val c = cos(w); val s = sin(w)
            var ri = niveau * (yi * c - yq * s)
            var rq = niveau * (yi * s + yq * c)
            // The receiver's own DC and a little image: they must not count.
            ri += 0.003 + 0.002 * ri; rq += -0.002 - 0.002 * rq
            ri += bruit * alea.nextGaussian(); rq += bruit * alea.nextGaussian()
            buf[2 * k] = Math.round(ri * 32767).coerceIn(-32767, 32767).toInt().toShort()
            buf[2 * k + 1] = Math.round(rq * 32767).coerceIn(-32767, 32767).toInt().toShort()
        }
        t += n
        blocs++
        return 2 * n
    }
}
