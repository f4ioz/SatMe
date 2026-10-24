/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.data.SatPass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Which slide an ARISS series sends when: the slides go round in order, one
 * every [Modele.periodeS] (« 36 seconds on with 2 minutes off »: 156 s
 * announced, about 159 s measured in October 2026), so a few pictures whose
 * slide is known (anchors: when it was received, its number) give the
 * slide at any time — and, over the passes to come, which slides one can
 * hope for.
 *
 * The period is fitted by least squares on the anchors; an anchor far off
 * the fit (more than [RUPTURE_S]) is a restart of the loop on board: the
 * phase is taken again from there, the period still fitted on all (each
 * stretch its own phase). An estimate: ARISS pauses, restarts and changes
 * the order without notice.
 */
object HoraireDiapos {

    /** « 36 s on / 2 min off ». */
    const val PERIODE_ANNONCEE_S = 156.0
    /** Robot 36 on air. */
    const val DUREE_S = 36.0
    /** An anchor this far from the fit starts a new stretch (a restart on board). */
    const val RUPTURE_S = 40.0

    /** A picture whose slide is known: when it began, its number (1..nb). */
    data class Repere(val debutMs: Long, val diapo: Int)

    data class Modele(
        /** When slide 1 begins (the last stretch's phase), UTC ms. */
        val origineMs: Long,
        val periodeS: Double,
        val nb: Int = 12,
        /** Root mean square of the anchors off the fit, s; 0 with one anchor. */
        val residuS: Double = 0.0,
        /** Anchors used in all, in the last stretch. */
        val reperes: Int = 1,
        val reperesDernier: Int = 1,
        /** When each restart was seen (the first anchor after it). */
        val ruptures: List<Long> = emptyList(),
        /** The last anchor: the estimate drifts away from it. */
        val dernierMs: Long = origineMs,
        /** The period's own uncertainty (s), 0 when not fitted. */
        val sigmaPeriodeS: Double = 0.0,
        /** The period was fitted (else announced). */
        val ajustee: Boolean = false,
        /** Each stretch between restarts: its first and last anchors, its own slide 1 (the last one is [origineMs]). */
        val troncons: List<Troncon> = emptyList(),
    )

    data class Troncon(val debutMs: Long, val finMs: Long, val origineMs: Long)

    /** [m] as it stood at [t]: the phase of the stretch [t] falls in (the one begun last before it). */
    fun a(m: Modele, t: Long): Modele {
        if (m.troncons.size < 2) return m
        val tr = m.troncons.lastOrNull { it.debutMs - (m.periodeS * 500).toLong() <= t } ?: m.troncons.first()
        return if (tr.origineMs == m.origineMs) m else m.copy(origineMs = tr.origineMs, dernierMs = if (t < tr.debutMs) tr.debutMs else tr.finMs)
    }

    /** « 36 s on / 2 min off », « 36 seconds on with 2 minutes off » → seconds between starts; [PERIODE_ANNONCEE_S] when unreadable. */
    fun periode(horaire: String): Double {
        val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*s\\w*\\s+on\\D+(\\d+(?:[.,]\\d+)?)\\s*(min|s)", RegexOption.IGNORE_CASE).find(horaire)
            ?: return PERIODE_ANNONCEE_S
        val on = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return PERIODE_ANNONCEE_S
        val off = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return PERIODE_ANNONCEE_S
        val s = on + if (m.groupValues[3].lowercase() == "min") off * 60 else off
        return if (s in 40.0..1200.0) s else PERIODE_ANNONCEE_S
    }

    /** Slot of [d] (1..nb) nearest to time [t] counted from [t0], [d0] being there. */
    private fun creneau(t: Long, t0: Long, d: Int, d0: Int, p: Double, nb: Int): Long {
        val k = ((d - d0) % nb + nb) % nb
        val x = (t - t0) / 1000.0 / p
        return Math.round((x - k) / nb) * nb + k
    }

    /**
     * The fit of [reperes] (any order); null when none. [periodeS]: the
     * period to start from (and kept when the anchors cannot tell).
     */
    fun ajuste(reperes: List<Repere>, nb: Int = 12, periodeS: Double = PERIODE_ANNONCEE_S): Modele? {
        val l = reperes.filter { it.diapo in 1..nb && it.debutMs > 0 }.sortedBy { it.debutMs }
        if (l.isEmpty()) return null
        // Stretches: anchors on one line (one phase); each anchor's slot counted from the one before.
        val ts = ArrayList<ArrayList<Pair<Repere, Long>>>()
        val ecartes = ArrayList<Repere>()
        fun ecart(t: List<Pair<Repere, Long>>, r: Repere, p: Double): Pair<Long, Double> {
            val (rl, jl) = t.last()
            val j = jl + creneau(r.debutMs, rl.debutMs, r.diapo, rl.diapo, p, nb)
            val a = t.sumOf { (x, jx) -> x.debutMs / 1000.0 - p * jx } / t.size
            return j to (r.debutMs / 1000.0 - (a + p * j))
        }
        for (r in l) {
            val cur = ts.lastOrNull()
            if (cur == null) { ts += arrayListOf(r to 0L); continue }
            val p = periodeCommune(ts) ?: periodeS
            val (j, e) = ecart(cur, r, p)
            if (abs(e) <= RUPTURE_S) { cur += r to j; continue }
            // A lone anchor off the line before it, this one back on it: that one was wrong, not the loop.
            val avant = ts.getOrNull(ts.size - 2)
            if (cur.size == 1 && avant != null) {
                val (j2, e2) = ecart(avant, r, p)
                if (abs(e2) <= RUPTURE_S) { ecartes += cur.first().first; ts.removeAt(ts.size - 1); avant += r to j2; continue }
            }
            ts += arrayListOf(r to 0L)
        }
        val pc = periodeCommune(ts)
        val p = pc ?: periodeS
        var s2 = 0.0; var n = 0
        val phases = ts.map { t -> t.sumOf { (x, j) -> x.debutMs / 1000.0 - p * j } / t.size }
        for ((t, a) in ts.zip(phases)) for ((r, j) in t) { val e = r.debutMs / 1000.0 - (a + p * j); s2 += e * e; n++ }
        val libres = n - ts.size - (if (pc != null) 1 else 0)
        val residu = if (libres > 0) sqrt(s2 / libres) else 0.0
        val den = ts.filter { it.size >= 2 }.sumOf { t -> val jm = t.sumOf { it.second.toDouble() } / t.size; t.sumOf { (it.second - jm) * (it.second - jm) } }
        val sigmaP = if (pc != null && den > 0) residu.coerceAtLeast(2.0) / sqrt(den) else 0.0
        val dernier = ts.last()
        // Slide 1's start in each stretch: an anchor's slot brought back to a slide 1.
        val troncons = ts.zip(phases).map { (t, a) ->
            val (r0, j0) = t.first()
            Troncon(r0.debutMs, t.last().first.debutMs, ((a + p * (j0 - (r0.diapo - 1))) * 1000).roundToLong())
        }
        return Modele(troncons.last().origineMs, p, nb, residu, l.size - ecartes.size, dernier.size, ts.drop(1).map { it.first().first.debutMs },
            dernier.last().first.debutMs, sigmaP, pc != null, troncons)
    }

    /** One period for all stretches (each its own phase), by least squares; null when no stretch spans two slots. */
    private fun periodeCommune(ts: List<List<Pair<Repere, Long>>>): Double? {
        var num = 0.0; var den = 0.0
        for (t in ts) {
            if (t.size < 2) continue
            val jm = t.sumOf { it.second.toDouble() } / t.size
            val tm = t.sumOf { it.first.debutMs / 1000.0 } / t.size
            for ((r, j) in t) { num += (j - jm) * (r.debutMs / 1000.0 - tm); den += (j - jm) * (j - jm) }
        }
        return if (den > 0 && num / den in 40.0..1200.0) num / den else null
    }

    /** Slot index (slide 1 of the origin = 0) beginning at or before [t]. */
    fun creneauA(m: Modele, t: Long): Long = floor((t - m.origineMs) / 1000.0 / m.periodeS).toLong()

    fun debut(m: Modele, j: Long): Long = m.origineMs + (j * m.periodeS * 1000).roundToLong()
    fun diapo(m: Modele, j: Long): Int = ((j % m.nb + m.nb) % m.nb).toInt() + 1

    /**
     * The slide beginning near [t] (within [toleranceS]); 0 when [t] falls
     * between two (or the estimate there is too loose).
     */
    fun diapoA(m0: Modele, t: Long, toleranceS: Double = 40.0): Int {
        val m = a(m0, t)
        val x = (t - m.origineMs) / 1000.0 / m.periodeS
        val j = Math.round(x)
        if (abs(x - j) * m.periodeS > toleranceS + incertitudeS(m, t)) return 0
        if (incertitudeS(m, t) > m.periodeS / 3) return 0
        return diapo(m, j)
    }

    /** How far the estimate may be off at [t], s: the fit's spread and the period's error carried. */
    fun incertitudeS(m: Modele, t: Long): Double {
        val k = abs(t - m.dernierMs) / 1000.0 / m.periodeS
        val sp = if (m.ajustee) m.sigmaPeriodeS else 3.0  // announced period: a few seconds off, as seen in 2026
        return m.residuS + k * sp
    }

    // ------------------------------------------------------------ passes

    /** A slide expected on a pass. */
    data class Prevue(val diapo: Int, val debutMs: Long, val finMs: Long,
                      /** Sent whole while the satellite is above the minimum elevation. */
                      val entiere: Boolean)

    data class Passage(val passage: SatPass, val debutHautMs: Long, val finHautMs: Long,
                       val prevues: List<Prevue>, val incertitudeS: Double) {
        val entieres: List<Int> get() = prevues.filter { it.entiere }.map { it.diapo }
    }

    /**
     * When [p] is above [elMin]: its track (samples spread from AOS to LOS),
     * else a sine of its peak; AOS–LOS when the minimum is 0 or none rises.
     */
    fun auDessus(p: SatPass, elMin: Double): Pair<Long, Long>? {
        if (elMin <= 0.0) return p.aosEpochMs to p.losEpochMs
        if (p.maxElevationDeg < elMin) return null
        val d = (p.losEpochMs - p.aosEpochMs).toDouble()
        val tr = p.track
        if (tr.size >= 3) {
            fun t(i: Double) = p.aosEpochMs + (d * (i + 0.5) / tr.size).toLong()
            var a = -1.0; var b = -1.0
            for (i in tr.indices) {
                val e = tr[i].second
                if (e >= elMin && a < 0) a = if (i == 0) 0.0 - 0.5 else (i - 1) + (elMin - tr[i - 1].second) / (e - tr[i - 1].second).coerceAtLeast(1e-6)
                if (e >= elMin) b = if (i == tr.size - 1) tr.size - 0.5 else i + (tr[i].second - elMin) / (tr[i].second - tr[i + 1].second).coerceAtLeast(1e-6)
            }
            if (a >= -0.5 && b >= 0) return t(a).coerceAtLeast(p.aosEpochMs) to t(b).coerceAtMost(p.losEpochMs)
        }
        // A pass seen as sin(π·u) of its peak.
        val u = asin((elMin / p.maxElevationDeg).coerceIn(0.0, 1.0)) / PI
        return (p.aosEpochMs + (d * u).toLong()) to (p.losEpochMs - (d * u).toLong())
    }

    /** The slides on air during [p] (any part from AOS to LOS); whole ones sent while it is above [elMin]. */
    fun passage(m: Modele, p: SatPass, elMin: Double, dureeS: Double = DUREE_S): Passage {
        val haut = auDessus(p, elMin)
        val out = ArrayList<Prevue>()
        var j = creneauA(m, p.aosEpochMs - (dureeS * 1000).toLong())
        while (true) {
            val s = debut(m, j); val e = s + (dureeS * 1000).toLong()
            if (s > p.losEpochMs) break
            if (e > p.aosEpochMs) out += Prevue(diapo(m, j), s, e, haut != null && s >= haut.first && e <= haut.second)
            j++
        }
        return Passage(p, haut?.first ?: 0L, haut?.second ?: 0L, out, incertitudeS(m, (p.aosEpochMs + p.losEpochMs) / 2))
    }

    data class Tableau(
        val passages: List<Passage>,
        /** Slides not yet in the collection. */
        val manquantes: List<Int>,
        /** For each missing slide, the passes that send it whole (the next first). */
        val prochaines: Map<Int, List<Passage>>,
    )

    /** The passes to come ([passes], from [maintenant]) with their slides; [recues]: the slides one has. */
    fun tableau(m: Modele, passes: List<SatPass>, elMin: Double, recues: Set<Int>, maintenant: Long,
                dureeS: Double = DUREE_S): Tableau {
        val ps = passes.filter { it.losEpochMs > maintenant }.sortedBy { it.aosEpochMs }.map { passage(m, it, elMin, dureeS) }
        val manquent = (1..m.nb).filter { it !in recues }
        return Tableau(ps, manquent, manquent.associateWith { d -> ps.filter { p -> p.prevues.any { it.diapo == d && it.entiere } } })
    }
}
