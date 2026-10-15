/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.SatPass

/**
 * The station's horizon: how high the obstacles stand (degrees) in each
 * azimuth sector. Sector i covers [i·w, (i+1)·w) from north, clockwise,
 * w = 360 / number of sectors (8 or 12).
 *
 * All zero means "no mask": passes stay exactly as computed.
 */
data class MasqueHorizon(val elevationsDeg: List<Double>) {

    val secteurDeg: Double get() = if (elevationsDeg.isEmpty()) 360.0 else 360.0 / elevationsDeg.size

    /** Nothing above the geometric horizon: the mask changes nothing. */
    val vide: Boolean get() = elevationsDeg.none { it > 0.0 }

    /** The obstacle's elevation toward [azDeg]. */
    fun elevationA(azDeg: Double): Double {
        if (elevationsDeg.isEmpty()) return 0.0
        val a = ((azDeg % 360.0) + 360.0) % 360.0
        val i = (a / secteurDeg).toInt().coerceIn(0, elevationsDeg.size - 1)
        return elevationsDeg[i]
    }

    /** Stored form: "0,0,5,12,…". */
    fun texte(): String = elevationsDeg.joinToString(",") { v ->
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }

    /**
     * Where the pass is really heard: from the first instant the satellite
     * clears the mask to the last. [position] gives (azimuth, elevation) at a
     * time. Null when it never clears it.
     */
    fun fenetre(aosMs: Long, losMs: Long, pasMs: Long = 10_000L, position: (Long) -> Pair<Double, Double>): Pair<Long, Long>? {
        if (losMs <= aosMs) return null
        // Within the geometric window: a sector without obstacle is clear as it is.
        fun libre(t: Long): Boolean { val (az, el) = position(t); val m = elevationA(az); return m <= 0.0 || el >= m }
        val instants = ArrayList<Long>()
        var t = aosMs
        while (t < losMs) { instants += t; t += pasMs }
        instants += losMs
        val premier = instants.indexOfFirst { libre(it) }
        if (premier < 0) return null
        val dernier = instants.indexOfLast { libre(it) }
        val debut = if (premier == 0) instants[0] else affine(instants[premier - 1], instants[premier], ::libre)
        val fin = if (dernier == instants.size - 1) instants[dernier] else affine(instants[dernier + 1], instants[dernier], ::libre)
        return debut to fin
    }

    /**
     * [p] seen through the mask: AOS and LOS where the satellite clears the
     * obstacles, the geometric ones kept. Null when it stays hidden all along.
     * An empty mask returns [p] itself.
     */
    fun applique(p: SatPass, position: (Long) -> Pair<Double, Double>): SatPass? {
        if (vide) return p
        val (aos, los) = fenetre(p.geoAosEpochMs, p.geoLosEpochMs, position = position) ?: return null
        if (aos == p.aosEpochMs && los == p.losEpochMs) return p
        return p.copy(aosEpochMs = aos, losEpochMs = los,
            aosAzimuthDeg = if (aos == p.geoAosEpochMs) p.aosAzimuthDeg else position(aos).first,
            losAzimuthDeg = if (los == p.geoLosEpochMs) p.losAzimuthDeg else position(los).first)
    }

    companion object {
        val AUCUN = MasqueHorizon(emptyList())
        /** The sector counts offered: 45° or 30° each. */
        val SECTEURS = listOf(8, 12)

        /** Read back from [texte]; anything unreadable is no mask. */
        fun lis(s: String?): MasqueHorizon {
            if (s.isNullOrBlank()) return AUCUN
            val l = s.split(',').map { it.trim().replace(" ", "").toDoubleOrNull() ?: return AUCUN }
            if (l.size !in SECTEURS) return AUCUN
            return MasqueHorizon(l.map { it.coerceIn(0.0, 90.0) })
        }

        /** Bisect between [dehors] (hidden) and [dedans] (clear) to about a second. */
        private fun affine(dehors: Long, dedans: Long, libre: (Long) -> Boolean): Long {
            var a = dehors; var b = dedans
            repeat(20) {
                if (kotlin.math.abs(b - a) <= 1000L) return b
                val m = (a + b) / 2
                if (libre(m)) b = m else a = m
            }
            return b
        }
    }
}
