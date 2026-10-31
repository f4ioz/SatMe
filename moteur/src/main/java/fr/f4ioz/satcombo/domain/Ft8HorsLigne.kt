/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * A stretch of a recording read again by SatMe's FT8 / FT4 decoder, after
 * the fact: its sound cut on the UTC slots (the recording's start is its
 * name's second), each slot read as the live decoder does.
 *
 * A recording does not start on a slot, and its start is known to the second
 * only: each slot is taken from [AVANT_S] before its start to [APRES_S] after
 * its end, so a station a second early or late (or a recording's start a
 * second off) still lies whole in it; the time offset given is the slot's
 * (the margin taken out).
 */
object Ft8HorsLigne {

    const val AVANT_S = 1.0
    const val APRES_S = 1.0

    /** A message read: its slot's start (UTC ms), mode, and the decoder's. */
    data class Lu(val trancheMs: Long, val mode: String, val decode: Ft8Decodeur.Decode) {
        val dtS: Double get() = decode.instantS - AVANT_S
    }

    fun signal(mode: String): Ft8Signal.Mode = if (mode.equals("FT4", true)) Ft8Signal.FT4 else Ft8Signal.FT8
    fun dureeMs(mode: String): Long = if (mode.equals("FT4", true)) 7_500L else 15_000L

    /**
     * The slots of [mode] that start within [deMs]..[aMs] (UTC), from the
     * recording [audio] at [cadence] that started at [origineMs]. A slot the
     * recording does not hold most of is skipped.
     */
    fun decode(audio: FloatArray, cadence: Int, origineMs: Long, deMs: Long, aMs: Long, mode: String,
               profondeur: Ft8Decodeur.Profondeur = Ft8Decodeur.Profondeur.PROFONDE,
               progres: (Float) -> Unit = {}): List<Lu> {
        val m = if (mode.equals("FT4", true)) "FT4" else "FT8"
        val sig = signal(m); val d = dureeMs(m)
        val finEnr = origineMs + audio.size * 1000L / cadence
        val premiere = Math.floorDiv(maxOf(deMs, origineMs) - (AVANT_S * 1000).toLong(), d) * d
        val tranches = generateSequence(premiere) { it + d }.takeWhile { it <= minOf(aMs, finEnr) }.toList()
        val sortie = ArrayList<Lu>()
        tranches.forEachIndexed { k, tr ->
            val i0 = ((tr - origineMs - AVANT_S * 1000) * cadence / 1000.0).toLong()
            val i1 = ((tr + d - origineMs + APRES_S * 1000) * cadence / 1000.0).toLong()
            val a = i0.coerceAtLeast(0L).toInt(); val b = i1.coerceAtMost(audio.size.toLong()).toInt()
            // Most of the transmission must be there.
            if (b - a < (sig.dureeS + 0.5) * cadence) { progres((k + 1f) / tranches.size); return@forEachIndexed }
            val morceau = FloatArray((i1 - i0).toInt())
            System.arraycopy(audio, a, morceau, (a - i0).toInt(), b - a)
            runCatching { Ft8Decodeur.decode(morceau, cadence.toDouble(), sig, profondeur = profondeur) }.getOrDefault(emptyList())
                .forEach { sortie += Lu(tr, m, it) }
            progres((k + 1f) / tranches.size)
        }
        // The margins overlap: a message found in two neighbouring slots is the same one, kept in its own.
        return sortie.groupBy { it.decode.message.brut + "|" + it.trancheMs }.values.map { l -> l.maxByOrNull { it.decode.scoreSynchro }!! }
            .sortedWith(compareBy({ it.trancheMs }, { -it.decode.rapportDb }))
    }

    /**
     * Both modes, or the one the zone says: what [decode] finds of each, FT4
     * first when the zone is FT4.
     */
    fun decodeZone(audio: FloatArray, cadence: Int, origineMs: Long, zone: ZonesNumeriques.Zone?, deMs: Long, aMs: Long,
                   progres: (Float) -> Unit = {}): List<Lu> {
        val modes = when (zone?.mode) { ZonesNumeriques.FT8 -> listOf("FT8", "FT4"); else -> listOf("FT4", "FT8") }
        val l = ArrayList<Lu>()
        modes.forEachIndexed { i, m -> l += decode(audio, cadence, origineMs, deMs, aMs, m) { progres((i + it) / modes.size) } }
        return l.sortedWith(compareBy({ it.trancheMs }, { -it.decode.rapportDb }))
    }
}
