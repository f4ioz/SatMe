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
 * A level (dB) followed over time, in a few fixed-size rings: a point a
 * second for ten minutes, one every ten seconds for an hour, one a minute
 * for a day. Each point keeps the mean, the lowest and the highest of the
 * measures it gathers, so a short fade or a burst still shows once
 * decimated. Memory stays bounded whatever runs for how long.
 */
class HistoriqueNiveau(val paliers: List<Palier> = PALIERS) {

    /** A ring: buckets [pasMs] wide, [n] of them. */
    class Palier(val pasMs: Long, val n: Int) { val dureeMs: Long get() = pasMs * n }

    /** A bucket: its start, and what fell in it. */
    class Point(val t: Long, val moy: Double, val min: Double, val max: Double, val n: Int)

    class Stats(val min: Double, val max: Double, val moy: Double, val n: Int)

    private class Seau(val t: Long) {
        var somme = 0.0; var min = Double.POSITIVE_INFINITY; var max = Double.NEGATIVE_INFINITY; var n = 0
        fun ajoute(v: Double) { somme += v; n++; if (v < min) min = v; if (v > max) max = v }
        fun point() = Point(t, somme / n, min, max, n)
    }

    private class Anneau(val p: Palier) {
        val faits = ArrayDeque<Point>()
        var courant: Seau? = null
        fun ajoute(t: Long, v: Double) {
            val debut = Math.floorDiv(t, p.pasMs) * p.pasMs
            val c = courant
            if (c == null || c.t != debut) {
                // A measure older than the bucket under way (a clock moved back) is dropped.
                if (c != null && debut < c.t) return
                if (c != null && c.n > 0) pousse(c.point())
                courant = Seau(debut).also { it.ajoute(v) }
            } else c.ajoute(v)
        }
        fun pousse(pt: Point) { faits.addLast(pt); while (faits.size > p.n) faits.removeFirst() }
        fun points(depuis: Long): List<Point> {
            val l = faits.filter { it.t >= depuis }.toMutableList()
            courant?.takeIf { it.n > 0 && it.t >= depuis }?.let { l += it.point() }
            return l
        }
    }

    private val anneaux = paliers.sortedBy { it.pasMs }.map { Anneau(it) }

    /** A measure [db] at [t] (ms); NaN or infinite ones are ignored. */
    @Synchronized fun ajoute(t: Long, db: Double) {
        if (!db.isFinite()) return
        anneaux.forEach { it.ajoute(t, db) }
    }

    /** The finest ring that covers [fenetreMs]. */
    fun palierPour(fenetreMs: Long): Palier = anneaux.firstOrNull { it.p.dureeMs >= fenetreMs }?.p ?: anneaux.last().p

    /** The points of the last [fenetreMs] before [maintenant], oldest first, from the finest ring covering it. */
    @Synchronized fun points(fenetreMs: Long, maintenant: Long): List<Point> {
        val a = anneaux.firstOrNull { it.p.dureeMs >= fenetreMs } ?: anneaux.last()
        return a.points(maintenant - fenetreMs)
    }

    @Synchronized fun vide() { anneaux.forEach { it.faits.clear(); it.courant = null } }

    @Synchronized fun estVide(): Boolean = anneaux.all { it.faits.isEmpty() && it.courant == null }

    companion object {
        /** 10 min at 1 s, 1 h at 10 s, 24 h at 1 min. */
        val PALIERS = listOf(Palier(1_000L, 600), Palier(10_000L, 360), Palier(60_000L, 1_440))

        /** The lowest, the highest and the mean over [points] (each bucket weighed by its measures); null when empty. */
        fun stats(points: List<Point>): Stats? {
            val n = points.sumOf { it.n }
            if (n == 0) return null
            return Stats(points.minOf { it.min }, points.maxOf { it.max }, points.sumOf { it.moy * it.n } / n, n)
        }

        /**
         * Several series side by side as CSV (UTC ISO time, then each one's
         * mean, dB, one decimal; empty where a series has nothing then).
         */
        fun csv(series: List<Pair<String, List<Point>>>): String {
            val temps = series.flatMap { s -> s.second.map { it.t } }.toSortedSet()
            val index = series.map { s -> s.second.associateBy { it.t } }
            val sb = StringBuilder("temps_utc")
            series.forEach { sb.append(',').append(it.first).append("_moy_db,").append(it.first).append("_min_db,").append(it.first).append("_max_db") }
            sb.append('\n')
            val f = java.time.format.DateTimeFormatter.ISO_INSTANT
            fun d(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
            for (t in temps) {
                sb.append(f.format(java.time.Instant.ofEpochMilli(t)))
                index.forEach { m -> val p = m[t]; if (p == null) sb.append(",,,") else sb.append(',').append(d(p.moy)).append(',').append(d(p.min)).append(',').append(d(p.max)) }
                sb.append('\n')
            }
            return sb.toString()
        }
    }
}
