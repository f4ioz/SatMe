/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

/**
 * What the decoders' sound input gives, measured as it comes: when the last
 * block arrived, and its mean and peak level (dBFS) over the last
 * [fenetreMs]. Tells a dead input (no samples at all) from a live but quiet
 * one (a squelch shut, the AF gain down): the first is a fault, the second
 * is not.
 */
class MesureEntree(val fenetreMs: Long = 10_000L) {

    /** One second's sum: squares, samples, peak. */
    private class Seconde(val s: Long, var carres: Double = 0.0, var n: Long = 0, var crete: Int = 0)

    private val secondes = ArrayDeque<Seconde>()

    /** When the last block came (ms), 0: none since started. */
    @Volatile var dernierBlocMs: Long = 0L; private set
    /** Since when the input is measured (ms). */
    @Volatile var depuisMs: Long = 0L; private set

    /** What the window says now. [fenetreMs]: the span actually measured (shorter right after a start). */
    data class Instantane(val dernierBlocMs: Long, val rmsDbfs: Double?, val creteDbfs: Double?, val fenetreMs: Long)

    @Synchronized fun bloc(pcm: ShortArray, n: Int, t: Long) {
        if (depuisMs == 0L) depuisMs = t
        dernierBlocMs = t
        val s = t / 1000
        val c = secondes.lastOrNull()?.takeIf { it.s == s } ?: Seconde(s).also { secondes.addLast(it) }
        for (i in 0 until n.coerceAtMost(pcm.size)) {
            val v = pcm[i].toInt()
            c.carres += v.toDouble() * v
            val a = if (v < 0) -v else v
            if (a > c.crete) c.crete = a
        }
        c.n += n
        oublie(t)
    }

    private fun oublie(t: Long) {
        val premier = (t - fenetreMs) / 1000
        while (secondes.isNotEmpty() && secondes.first().s < premier) secondes.removeFirst()
    }

    @Synchronized fun instantane(t: Long): Instantane {
        oublie(t)
        val l = secondes.filter { it.n > 0 }
        val n = l.sumOf { it.n }
        if (n == 0L) return Instantane(dernierBlocMs, null, null, 0L)
        val rms = Math.sqrt(l.sumOf { it.carres } / n) / 32768.0
        val crete = l.maxOf { it.crete } / 32768.0
        val duree = ((t - maxOf(depuisMs, t - fenetreMs)).coerceIn(0L, fenetreMs))
        return Instantane(dernierBlocMs, dbfs(rms), dbfs(crete), duree)
    }

    @Synchronized fun remet() { secondes.clear(); dernierBlocMs = 0L; depuisMs = 0L }

    companion object {
        /** 0..1 as dBFS, floored at −120. */
        fun dbfs(x: Double): Double = if (x <= 1e-6) -120.0 else 20 * Math.log10(x)
    }
}
