/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

import kotlin.random.Random

/**
 * Trial aircraft, for trying the page without a receiver: a few made-up
 * flights around a point, their squitters built from the standard's
 * layout, turned into the magnitudes a dongle at 2 MS/s would give (with
 * noise), and fed to the real demodulator. Nothing is transmitted.
 */
class SimulateurAdsb(centreLat: Double, centreLon: Double, graine: Int = 1) {

    class Vol(val icao: Int, val indicatif: String, var lat: Double, var lon: Double, val altPieds: Int,
              val noeuds: Double, val routeDeg: Double, val amplitude: Float)

    private val alea = Random(graine)

    val vols: List<Vol> = listOf(
        Vol(0x39C4A1, "AFR1234", centreLat + 1.2, centreLon - 1.6, 36_000, 450.0, 115.0, 0.30f),
        Vol(0x3C6DD2, "DLH4AB", centreLat - 0.4, centreLon + 1.9, 38_000, 470.0, 280.0, 0.18f),
        Vol(0x4CA7F3, "RYR82KD", centreLat + 0.6, centreLon + 0.5, 24_500, 380.0, 200.0, 0.45f),
        Vol(0x406B11, "EZY51QP", centreLat - 1.5, centreLon - 0.8, 33_000, 440.0, 20.0, 0.12f),
        Vol(0x3944F0, "FHBXA", centreLat + 0.1, centreLon - 0.3, 5_500, 160.0, 75.0, 0.6f),
        Vol(0x4B1805, "SWR7TN", centreLat + 2.2, centreLon + 1.0, 39_000, 480.0, 250.0, 0.09f),
    )

    private var t = 0L

    /**
     * [ms] milliseconds of signal (2000 samples each) into [out]: each
     * flight moved, its replies placed at random times — positions twice a
     * second (even and odd), velocity every second, identification every
     * five. Returns the samples written.
     */
    fun bloc(ms: Int, out: FloatArray, bruit: Float = 0.02f): Int {
        val n = ms * 2000
        for (k in 0 until n) out[k] = kotlin.math.abs(bruit * (alea.nextFloat() + alea.nextFloat() - 1f) * 1.7f)
        val s = ms / 1000.0
        for (v in vols) {
            val (la, lo) = DiffusionAvions.avance(v.lat, v.lon, v.routeDeg, v.noeuds * 1.852 / 3600.0 * s)
            v.lat = la; v.lon = lo
            val trames = ArrayList<ByteArray>()
            val dans = t / 500 != (t + ms) / 500
            if (dans) {
                trames += EncodeurAdsb.position(v.icao, v.lat, v.lon, v.altPieds, false)
                trames += EncodeurAdsb.position(v.icao, v.lat, v.lon, v.altPieds, true)
            }
            if (t / 1000 != (t + ms) / 1000) trames += EncodeurAdsb.vitesse(v.icao, v.noeuds, v.routeDeg, 0)
            if (t / 5000 != (t + ms) / 5000 || t == 0L) trames += EncodeurAdsb.identification(v.icao, v.indicatif)
            for (tr in trames) {
                val debut = alea.nextInt(0, maxOf(1, n - DemodAdsb.FENETRE - 4))
                EncodeurAdsb.module(tr, ModeS.LONG, out, debut, v.amplitude, alea.nextFloat() * 0.3f)
            }
        }
        t += ms
        return n
    }
}
