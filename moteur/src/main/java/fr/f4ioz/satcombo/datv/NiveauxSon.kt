/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A level meter for 16-bit PCM, one or two channels interleaved: each
 * block's peak and RMS in dBFS, the peak held [tenueMs] then falling
 * [chuteDbParS] dB a second, and whether both channels carry the same
 * sound (mono sent as stereo).
 */
class NiveauxSon(private val tenueMs: Long = 1_500L, private val chuteDbParS: Double = 20.0) {

    class Canal {
        @Volatile var crete = PLANCHER; internal set
        @Volatile var rms = PLANCHER; internal set
        @Volatile var tenue = PLANCHER; internal set
        internal var tenueMs = 0L
        internal var tenueVal = PLANCHER
    }

    val gauche = Canal()
    val droite = Canal()
    @Volatile var mono = true; private set
    @Volatile var dernierMs = 0L; private set

    /** A block of [n] frames of [canaux] channels (1 or 2) at time [t]. */
    @Synchronized
    fun mesure(pcm: ShortArray, n: Int, canaux: Int, t: Long) {
        if (n <= 0) return
        val c = canaux.coerceIn(1, 2)
        var pg = 0; var pd = 0; var sg = 0.0; var sd = 0.0; var pareils = true
        for (i in 0 until n) {
            val g = pcm[i * c].toInt()
            val d = if (c == 2) pcm[i * c + 1].toInt() else g
            if (g != d) pareils = false
            val ag = Math.abs(g); val ad = Math.abs(d)
            if (ag > pg) pg = ag
            if (ad > pd) pd = ad
            sg += g.toDouble() * g; sd += d.toDouble() * d
        }
        mono = pareils
        regle(gauche, pg, sg / n, t)
        regle(droite, pd, sd / n, t)
        dernierMs = t
    }

    private fun regle(k: Canal, crete: Int, carre: Double, t: Long) {
        k.crete = db(crete.toDouble())
        k.rms = db(sqrt(carre))
        k.tenue = tenue(k, t)
        if (k.crete >= k.tenue) { k.tenueVal = k.crete; k.tenueMs = t; k.tenue = k.crete }
    }

    private fun tenue(k: Canal, t: Long): Double {
        val ecart = t - k.tenueMs - tenueMs
        return if (ecart <= 0) k.tenueVal else maxOf(PLANCHER, k.tenueVal - chuteDbParS * ecart / 1000.0)
    }

    /** The levels for the page; silent when nothing came for a second. */
    fun json(t: Long): JSONObject {
        val vieux = t - dernierMs > 1_000L
        fun c(k: Canal) = JSONObject().put("crete", if (vieux) PLANCHER else arrondi(k.crete)).put("rms", if (vieux) PLANCHER else arrondi(k.rms))
            .put("tenue", arrondi(if (vieux) PLANCHER else tenue(k, t)))
        return JSONObject().put("g", c(gauche)).put("d", c(droite)).put("mono", mono).put("frais", !vieux)
    }

    companion object {
        const val PLANCHER = -90.0

        fun db(v: Double): Double = if (v <= 0.0) PLANCHER else maxOf(PLANCHER, 20 * log10(v / 32768.0))

        private fun arrondi(v: Double) = Math.round(v * 10) / 10.0

        /** The first [n] samples scaled by [gain] (1 = as is), clipped to 16 bits. */
        fun applique(pcm: ShortArray, n: Int, gain: Double) {
            if (gain == 1.0) return
            for (i in 0 until n) pcm[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }

        /** Stereo frames to mono (the two channels averaged). */
        fun mono(pcm: ShortArray, trames: Int): ShortArray = ShortArray(trames) { ((pcm[2 * it] + pcm[2 * it + 1]) / 2).toShort() }
    }
}
