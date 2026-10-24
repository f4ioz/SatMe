/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.rotor

import fr.f4ioz.satcombo.i18n.tf
import java.util.Locale
import kotlin.math.abs

/**
 * One axis of the software correction: what the controller reads ↔ where the
 * antenna really is.
 *
 * The cure is in the controller (its own calibration, `O`/`F`, see
 * [Gs232Codec.Etalonnage]); this is the fallback when that cannot be done —
 * rotctld, a firmware without it, a mast whose real top is not the
 * firmware's maximum — and the fine tuning after it.
 *
 * Points are (read, real), in the controller's own numbers on the wire (for
 * azimuth, after [RotorMath.commandAz]; for elevation, 0 to 180 with the flip
 * side above 90). None: identity, what every existing station has. One: an
 * offset. Two: offset and scale. More: piecewise-linear, the end segments
 * carried on beyond the ends.
 *
 * **Monotonic, or refused.** A map that folds back has no inverse: two
 * commands for one angle, and a mast sent the wrong way. Every segment must
 * climb on both sides, at a slope a pot could have (½ to 2): a typo (`1600`
 * for `160`) must not silently become a calibration.
 */
class CorrectionAxe private constructor(
    /** (read, real), sorted by read, strictly increasing on both sides. */
    val points: List<Pair<Double, Double>>
) {

    /** True when nothing changes: no point, or every point on the diagonal. */
    val estIdentite: Boolean get() = points.all { abs(it.first - it.second) < 1e-9 }

    /** The real angle for a controller reading. */
    fun reel(lu: Double): Double = applique(lu, points)

    /** The controller value for a real angle: the inverse of [reel]. */
    fun lu(reel: Double): Double = applique(reel, points.map { it.second to it.first })

    /** As stored: `read:real;read:real`, "" for identity. */
    fun ecrit(): String = if (estIdentite) "" else
        points.joinToString(";") { String.format(Locale.US, "%s:%s", n(it.first), n(it.second)) }

    /** One line for the screens: the offset at 0 and the scale, or the points. */
    fun resume(): String = when {
        estIdentite -> "—"
        points.size == 1 -> String.format(Locale.US, "%+.1f°", points[0].second - points[0].first)
        points.size == 2 -> {
            val (a, b) = droite(points[0], points[1])
            tf("rotor_corr_droite", String.format(Locale.US, "%.4f", a), String.format(Locale.US, "%+.2f", b))
        }
        else -> points.joinToString("  ") { n(it.first) + "→" + n(it.second) }
    }

    override fun equals(other: Any?) = other is CorrectionAxe && other.points == points
    override fun hashCode() = points.hashCode()
    override fun toString() = "CorrectionAxe(" + ecrit() + ")"

    companion object {
        val IDENTITE = CorrectionAxe(emptyList())

        /** At most this many points: a calibration, not a lookup table. */
        const val POINTS_MAX = 8

        /** The slope a real pot and a real controller can be off by, both ways. */
        const val PENTE_MIN = 0.5
        const val PENTE_MAX = 2.0

        /** Farther than this between read and real is a typo, not a calibration. */
        const val ECART_MAX_DEG = 90.0

        /**
         * The axis from its points, or null when they cannot be one: not
         * numbers, folding back, too steep, too far. Points with the same
         * reading are averaged (the same position measured twice).
         */
        fun depuis(brut: List<Pair<Double, Double>>): CorrectionAxe? {
            if (brut.any { it.first.isNaN() || it.second.isNaN() || it.first.isInfinite() || it.second.isInfinite() }) return null
            val pts = brut.groupBy { Math.round(it.first * 100.0) }
                .map { (_, l) -> l.map { it.first }.average() to l.map { it.second }.average() }
                .sortedBy { it.first }
            if (pts.size > POINTS_MAX) return null
            if (pts.any { abs(it.first - it.second) > ECART_MAX_DEG }) return null
            for (i in 1 until pts.size) {
                val dl = pts[i].first - pts[i - 1].first
                val dr = pts[i].second - pts[i - 1].second
                if (dl <= 1e-9 || dr <= 1e-9) return null
                val pente = dr / dl
                if (pente < PENTE_MIN || pente > PENTE_MAX) return null
            }
            return CorrectionAxe(pts)
        }

        /** Reads what [ecrit] wrote; identity when empty or unreadable (never half a map). */
        fun lit(s: String?): CorrectionAxe = litOuNull(s) ?: IDENTITE

        /** Same, but null when unreadable — for what the operator types, to tell him. */
        fun litOuNull(s: String?): CorrectionAxe? {
            val t = s?.trim().orEmpty()
            if (t.isEmpty()) return IDENTITE
            val pts = t.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { p ->
                val ab = p.split(':')
                if (ab.size != 2) return null
                val a = ab[0].trim().replace(',', '.').toDoubleOrNull() ?: return null
                val b = ab[1].trim().replace(',', '.').toDoubleOrNull() ?: return null
                a to b
            }
            return depuis(pts)
        }

        /**
         * From the verification table: (controller reading, measured) pairs.
         *
         * [parPoints] keeps them as they are (piecewise-linear through each);
         * otherwise a least-squares straight line, kept as its two ends, the
         * honest choice when the readings wander by a degree or two — a curve
         * through every point would fit the noise. One measure: an offset.
         */
        fun ajuste(releves: List<Pair<Double, Double>>, parPoints: Boolean): CorrectionAxe? {
            if (releves.isEmpty()) return null
            if (releves.size == 1 || parPoints) return depuis(releves)
            val n = releves.size.toDouble()
            val mx = releves.sumOf { it.first } / n
            val my = releves.sumOf { it.second } / n
            val sxx = releves.sumOf { (it.first - mx) * (it.first - mx) }
            // All measured at one reading: an offset is all it can say.
            if (sxx < 1e-9) return depuis(listOf(mx to my))
            val a = releves.sumOf { (it.first - mx) * (it.second - my) } / sxx
            val b = my - a * mx
            val lo = releves.minOf { it.first }
            val hi = releves.maxOf { it.first }
            return depuis(listOf(lo to a * lo + b, hi to a * hi + b))
        }

        private fun droite(p: Pair<Double, Double>, q: Pair<Double, Double>): Pair<Double, Double> {
            val a = (q.second - p.second) / (q.first - p.first)
            return a to (p.second - a * p.first)
        }

        private fun applique(x: Double, pts: List<Pair<Double, Double>>): Double {
            if (pts.isEmpty()) return x
            if (pts.size == 1) return x + (pts[0].second - pts[0].first)
            // The segment holding x; outside, the nearest end segment carried on.
            var i = 1
            while (i < pts.size - 1 && x > pts[i].first) i++
            val (a, b) = droite(pts[i - 1], pts[i])
            return a * x + b
        }

        private fun n(d: Double): String {
            val r = Math.round(d * 100.0) / 100.0
            return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.US, "%s", r)
        }
    }
}

/** Both axes; [IDENTITE] for every station that never calibrated. */
data class CorrectionRotor(val az: CorrectionAxe = CorrectionAxe.IDENTITE, val el: CorrectionAxe = CorrectionAxe.IDENTITE) {

    val estIdentite: Boolean get() = az.estIdentite && el.estIdentite

    /** What the controller reads → where the antenna is. */
    fun versReel(lu: RotorPos): RotorPos = RotorPos(az.reel(lu.azDeg), el.reel(lu.elDeg))

    /** Where the antenna must go → what to send the controller. */
    fun versControleur(reel: RotorPos): RotorPos = RotorPos(az.lu(reel.azDeg), el.lu(reel.elDeg))

    companion object {
        val IDENTITE = CorrectionRotor()
    }
}

/**
 * The controller's own range, in its own numbers on the wire: what it can be
 * sent, whatever the antenna does with it.
 */
data class BornesControleur(val azMin: Double, val azMax: Double, val elMin: Double, val elMax: Double) {

    /** Inside, within a rounding. */
    fun contient(p: RotorPos): Boolean =
        p.azDeg >= azMin - 0.5 && p.azDeg <= azMax + 0.5 && p.elDeg >= elMin - 0.5 && p.elDeg <= elMax + 0.5

    fun ramene(p: RotorPos): RotorPos = RotorPos(p.azDeg.coerceIn(azMin, azMax), p.elDeg.coerceIn(elMin, elMax))

    /**
     * An azimuth reading put back on the ring. K3NG answers `C2` modulo 360
     * (`convert_raw_azimuth_to_real_azimuth`): on a south-start ring 180→540
     * the far half comes back as 0..179. A correction with a scale must see
     * the ring's 370, not 10, or it would bend the wrong half.
     */
    fun surAnneau(az: Double): Double {
        var a = az
        while (a < azMin - 0.5) a += 360.0
        return a
    }

    companion object {
        /** The controller's range for the mast's settings: the same numbers SatMe already sent it. */
        fun de(limits: RotorMath.Limits, fromStop: Boolean): BornesControleur = BornesControleur(
            RotorMath.commandAz(limits.azMinReach, limits, fromStop),
            RotorMath.commandAz(limits.azMaxReach, limits, fromStop),
            0.0, limits.elMaxDeg)
    }
}

/**
 * A driver seen through the software correction: real angles in, real angles
 * out, the controller's numbers only on the wire.
 *
 * Limits stay in **real** degrees everywhere above (the plan, the flip, the
 * stops): this layer only translates. What it sends never leaves the
 * controller's range ([bornes]): a real angle the controller cannot be told
 * (a mast whose real top is 160 asked for 175) goes out at the controller's
 * limit, and [ecretageDeg] says by how much. The station refuses such an
 * angle before sending when it is typed by hand ([atteignable]); tracking
 * gets the nearest it can, as it does at the end stops.
 *
 * Identity passes everything through untouched: not even the clamp, so a
 * station that never calibrated sends exactly what it sent before.
 */
class RotorCorrige(
    val brut: RotorDriver,
    private val correction: () -> CorrectionRotor,
    private val bornes: () -> BornesControleur
) : RotorDriver {

    override val isOpen: Boolean get() = brut.isOpen

    /** The last reading as the controller gave it, before correction; null until one. */
    @Volatile var dernierBrut: RotorPos? = null
        private set

    /** How far the last command had to be brought back into the controller's range, degrees. */
    @Volatile var ecretageDeg: Double = 0.0
        private set

    /** Can [reel] be sent as it is, without leaving the controller's range? */
    fun atteignable(reel: RotorPos): Boolean {
        val c = correction()
        return c.estIdentite || bornes().contient(c.versControleur(reel))
    }

    override suspend fun moveTo(azDeg: Double, elDeg: Double): Boolean {
        val c = correction()
        if (c.estIdentite) { ecretageDeg = 0.0; return brut.moveTo(azDeg, elDeg) }
        val voulu = c.versControleur(RotorPos(azDeg, elDeg))
        val envoye = bornes().ramene(voulu)
        ecretageDeg = abs(voulu.azDeg - envoye.azDeg) + abs(voulu.elDeg - envoye.elDeg)
        return brut.moveTo(envoye.azDeg, envoye.elDeg)
    }

    override suspend fun readPosition(): RotorPos? {
        val p = brut.readPosition() ?: return null
        dernierBrut = p
        val c = correction()
        if (c.estIdentite) return p
        return c.versReel(RotorPos(bornes().surAnneau(p.azDeg), p.elDeg))
    }

    override suspend fun stop(): Boolean = brut.stop()

    override fun close() = brut.close()
}
