/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

/**
 * A made-up sound card on a made-up clock, for the tests: it "captures"
 * [signal] at [rate] × (1 + [ppm]) samples a second into a buffer of
 * [tamponMs]; a reader late by more than that overruns it and everything
 * waiting is thrown away (as ALSA does). [stalls]: before read k, the reader
 * is held back that many ms (an overloaded PC). [jitterUs]: how late the
 * clock is read after each read returns (0..that, deterministic).
 */
class LigneVirtuelle(
    private val signal: ShortArray,
    private val rate: Int,
    private val tamponMs: Int = 85,
    private val ppm: Double = 0.0,
    private val stalls: Map<Int, Int> = emptyMap(),
    private val jitterUs: Int = 2_000,
    private val avecAttente: Boolean = true,
) : LigneCapture {
    /** The made-up clock (ns). */
    var tNs = 1_000_000_000L; private set
    /** Index in [signal] of the next sample to read: everything before it was read or thrown. */
    var lu = 0L; private set
    /** Samples thrown in overruns. */
    var jetes = 0L; private set
    private var lectures = 0
    private val t0 = tNs
    private var ferme = false
    private val capacite = (rate * tamponMs / 1000.0).toLong()
    private var graine = 12345L

    private fun produits(t: Long): Long = ((t - t0) / 1e9 * rate * (1 + ppm * 1e-6)).toLong()
    private fun attente(): Long = produits(tNs) - lu

    /** The clock as the reader reads it: right after, a little late. */
    fun nanos(): Long {
        graine = graine * 6364136223846793005L + 1442695040888963407L
        val j = ((graine ushr 33) % (jitterUs + 1).toLong()) * 1000L
        return tNs + j
    }

    override fun lit(b: ByteArray, off: Int, len: Int): Int {
        if (ferme || lu >= signal.size) return -1
        stalls[lectures]?.let { tNs += it * 1_000_000L }
        lectures++
        // Overrun: all that waited is thrown away, the capture starts again.
        if (attente() > capacite) { val a = attente(); jetes += a; lu += a }
        if (lu >= signal.size) return -1
        val k = minOf(len / 2, (signal.size - lu).toInt())
        // Blocks until k samples are there.
        while (attente() < k) tNs += 1_000_000L
        for (i in 0 until k) {
            val v = signal[(lu + i).toInt()].toInt()
            b[off + 2 * i] = (v and 0xFF).toByte(); b[off + 2 * i + 1] = (v shr 8).toByte()
        }
        lu += k
        return 2 * k
    }

    override fun enAttente(): Int = if (!avecAttente) -1 else (attente().coerceAtLeast(0L) * 2).toInt()
    override val tampon: Int get() = (capacite * 2).toInt()
    override fun ferme() { ferme = true }
}
