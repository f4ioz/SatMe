/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.rotor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** A mast position in degrees, azimuth from true north. */
data class RotorPos(val azDeg: Double, val elDeg: Double)

/**
 * A rotator as the app sees it: give it a heading, ask where it is, stop it.
 *
 * Serial ASCII or a Hamlib network socket — the app must not care. It sends
 * azimuth and elevation; the rest is dialect.
 */
interface RotorDriver {
    val isOpen: Boolean

    /** Sends a command. False if it did not go out. */
    suspend fun moveTo(azDeg: Double, elDeg: Double): Boolean

    /** Reads the actual mast position, or null if the controller said nothing. */
    suspend fun readPosition(): RotorPos?

    /** Immediate stop, no questions asked. */
    suspend fun stop(): Boolean

    fun close()
}

/**
 * Everything in rotator control that can break something.
 *
 * No Android, serial or network here: this is bench-tested in milliseconds,
 * on purpose. It is the only part of SatMe that physically moves a
 * three-metre antenna; a sign error gives a torn cable, not an odd display.
 *
 * **Overlap.** A 450° rotator can reach 10° as 10 or 370, same point in the
 * sky. The choice shows at the foot of the mast: a pass crossing north costs
 * forty degrees of rotation — or three hundred and fifty.
 *
 * **Flip.** Elevation rotators going to 180° can track through the zenith
 * without swinging azimuth: az 180 / el 80 equals az 0 / el 100. With 90° of
 * hysteresis, so the mast does not spend the pass flipping back and forth.
 *
 * **Refusal**, the most important. An out-of-range command returns `null` and
 * the caller does nothing. Silently clamping would turn the mast to where the
 * satellite is not — worse than doing nothing, because nothing would say so.
 */
object RotorMath {

    /**
     * What a flip must save to be allowed. Without it a near-zenith pass makes
     * the mast swing round every second, right at the best part of the pass.
     */
    const val FLIP_HYSTERESIS_DEG = 90.0

    /**
     * Pointing error vs travel, ten to one: aim right rather than move less.
     * The mast wears; a pass cannot be replayed.
     */
    const val ERROR_WEIGHT = 10.0

    /**
     * Time to go and wait for the satellite?
     *
     * A rotator takes a good minute to cross the sky. Started at AOS, it
     * arrives late and chases the satellite for the whole pass. Pre-positioning
     * costs nothing and saves the start of the pass, when the satellite is
     * farthest and the signal weakest.
     *
     * True only in the window before AOS; after it, normal tracking rules.
     *
     * @param aosMs start of the tracked pass, or null if none.
     * @param avanceMin minutes of lead; 0 disables.
     */
    fun prePositionDue(nowMs: Long, aosMs: Long?, avanceMin: Int): Boolean {
        if (avanceMin <= 0 || aosMs == null) return false
        val reste = aosMs - nowMs
        return reste > 0L && reste <= avanceMin * 60_000L
    }

    /** Mechanical range, end stop, and the operator's deadband. */
    data class Limits(
        /** Azimuth range: 360, 450 or 540 degrees depending on the controller. */
        val azMaxDeg: Double = 450.0,
        /** Elevation range: 90 for most, 180 for those that flip. */
        val elMaxDeg: Double = 90.0,
        /** Below this offset, leave the mast alone. */
        val deadbandDeg: Double = 2.0,
        /**
         * Mechanical end stop, true azimuth: 0 = north stop, 180 = south stop.
         * The mast covers unwrapped [azStopDeg] .. `azStopDeg + azMaxDeg` only.
         */
        val azStopDeg: Double = 0.0
    ) {
        /** First reachable unwrapped azimuth. */
        val azMinReach: Double get() = azStopDeg

        /** Last one. */
        val azMaxReach: Double get() = azStopDeg + azMaxDeg
    }

    /**
     * A command ready to send, its branch, and what had to be given up.
     *
     * [errorDeg] is zero wherever we refuse instead of clamping ([aim],
     * [park]). Only [follow] fills it, because it never refuses: when the
     * satellite goes behind the stop, the mast stays put and the offset is
     * reported in degrees rather than hidden.
     */
    data class Aim(
        val azDeg: Double,
        val elDeg: Double,
        val flipped: Boolean,
        val errorDeg: Double = 0.0
    )

    /**
     * What was decided before the pass, not to be reopened.
     *
     * @property shiftDeg full turns added to the track, in degrees: 0, ±360
     *   or ±720.
     * @property startAzDeg unwrapped azimuth of the first point, shift
     *   included — not yet clamped, that is [clampAz]'s job.
     * @property coverage fraction of the pass actually covered, 0 to 1,
     *   weighted by sin(elevation).
     * @property worstErrorDeg largest expected pointing error.
     */
    data class Plan(
        val shiftDeg: Double,
        val startAzDeg: Double,
        val coverage: Double,
        val worstErrorDeg: Double
    )

    /** Brings an azimuth into [0, 360). */
    fun norm360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * Among az, az+360, az+720…, the one inside the range closest to [near];
     * null if none fits. On a 450° mast a pass crossing north keeps going.
     */
    fun unwrapNear(azDeg: Double, near: Double, azMaxDeg: Double, azStopDeg: Double = 0.0): Double? {
        val base = azStopDeg + norm360(azDeg - azStopDeg)
        var best: Double? = null
        var k = 0
        while (base + k * 360.0 <= azStopDeg + azMaxDeg + 1e-9) {
            val cand = base + k * 360.0
            val b = best
            if (b == null || abs(cand - near) < abs(b - near)) best = cand
            k++
        }
        return best
    }

    /**
     * Same, ignoring the range: the representation of [azDeg] closest to
     * [near], even if the mast cannot go there.
     *
     * Deliberate, and the core of 18.10. A range-bounded unwrap can never
     * point out of reach, so it would always report zero error and the stop
     * would be invisible. Unwrap freely, then clamp; the difference is exactly
     * what we want to know.
     */
    fun unwrapFree(azDeg: Double, near: Double): Double {
        val base = norm360(azDeg)
        val k = Math.round((near - base) / 360.0).toDouble()
        return base + k * 360.0
    }

    /**
     * Clamps an unwrapped azimuth into the range, never wrapping it: a
     * satellite behind the stop leaves the mast at the stop, and the error is
     * a subtraction.
     */
    fun clampAz(azDeg: Double, limits: Limits): Double =
        azDeg.coerceIn(limits.azMinReach, limits.azMaxReach)

    /**
     * The command to aim at [azTrueDeg] / [elTrueDeg], or null when that sky
     * point is out of reach.
     *
     * [wasFlipped] is the state from the previous call; it carries the
     * hysteresis, which is why the result returns it.
     */
    fun aim(
        azTrueDeg: Double,
        elTrueDeg: Double,
        current: RotorPos,
        limits: Limits,
        wasFlipped: Boolean = false
    ): Aim? {
        val direct = candidate(azTrueDeg, elTrueDeg, current, limits, flipped = false)
        val flipped = candidate(azTrueDeg + 180.0, 180.0 - elTrueDeg, current, limits, flipped = true)
        val held = if (wasFlipped) flipped else direct
        val other = if (wasFlipped) direct else flipped
        if (held == null) return other
        if (other == null) return held
        return if (cost(other, current) + FLIP_HYSTERESIS_DEG < cost(held, current)) other else held
    }

    private fun candidate(
        azDeg: Double, elDeg: Double, current: RotorPos,
        limits: Limits, flipped: Boolean
    ): Aim? {
        // Flipping needs an elevation rotator reaching 180°.
        if (flipped && limits.elMaxDeg < 180.0 - 1e-9) return null
        if (elDeg < -1e-9 || elDeg > limits.elMaxDeg + 1e-9) return null
        val az = unwrapNear(azDeg, current.azDeg, limits.azMaxDeg, limits.azStopDeg) ?: return null
        return Aim(az, elDeg, flipped)
    }

    /** Mechanical cost of a command: degrees the mast will travel. */
    fun cost(aim: Aim, current: RotorPos): Double =
        abs(aim.azDeg - current.azDeg) + abs(aim.elDeg - current.elDeg)

    /**
     * True if the mast is worth disturbing. Each start wears a relay; below
     * the deadband the beam (tens of degrees wide) already covers the target.
     */
    fun needsMove(aim: Aim, current: RotorPos, deadbandDeg: Double): Boolean =
        abs(aim.azDeg - current.azDeg) >= deadbandDeg ||
            abs(aim.elDeg - current.elDeg) >= deadbandDeg

    /** Park position after the pass. Null if out of range: better not park than park elsewhere. */
    fun park(azDeg: Double, elDeg: Double, limits: Limits): Aim? {
        if (azDeg < limits.azMinReach - 1e-9 || azDeg > limits.azMaxReach + 1e-9) return null
        if (elDeg < -1e-9 || elDeg > limits.elMaxDeg + 1e-9) return null
        return Aim(azDeg, elDeg, false)
    }

    /**
     * A hand-typed angle, brought into the mast's range.
     *
     * Unlike [park] — a setting chosen once, knowing the mechanics, so an
     * out-of-range value is reported and ignored — a typed angle names a sky
     * point. A south-stop mast covering 180 to 630 reaches north fine: it just
     * calls it 360.
     *
     * Try the number as is, then ±one turn; the first that fits wins. No turn
     * is added when the number already fits: on a north-stop mast 90 stays 90.
     * Elevation has no turns: refused when out of range.
     */
    fun manual(azDeg: Double, elDeg: Double, limits: Limits): Aim? {
        for (tour in listOf(0.0, 360.0, -360.0)) {
            val a = park(azDeg + tour, elDeg, limits)
            if (a != null) return a
        }
        return null
    }

    /** Mast degrees between two successive azimuth commands. */
    fun travel(fromDeg: Double, toDeg: Double): Double = abs(toDeg - fromDeg)

    // ------------------------------------------------------------------
    // End stops: decide before the pass rather than suffer during it.
    // ------------------------------------------------------------------

    /**
     * Unrolls a track continuously: the predictor's 359 → 1 jump becomes 359 →
     * 361, the path the mast would really travel — even outside the range,
     * which is what we want to find out.
     */
    fun unroll(track: List<Pair<Double, Double>>, startNearDeg: Double): List<Double> {
        if (track.isEmpty()) return emptyList()
        val out = ArrayList<Double>(track.size)
        var cur = unwrapFree(track[0].first, startNearDeg)
        out += cur
        for (i in 1 until track.size) {
            cur = unwrapFree(track[i].first, cur)
            out += cur
        }
        return out
    }

    /**
     * Chooses, before the pass, which side of the stop to track it on.
     *
     * A mast has a dead point (north or south depending on the make) where the
     * cable reaches its end. A pass crossing it forces a full unwind — half a
     * minute of mast turning in the void, right when the satellite is high and
     * the signal best.
     *
     * Five shifts are tried, −2 to +2 turns, and the best coverage wins.
     *
     * **Coverage is weighted by sin(elevation).** Thirty seconds lost at 3°
     * behind the trees are not worth thirty seconds at the zenith. Counting
     * samples equally would favour protecting the rise over the middle of the
     * pass.
     *
     * **At equal coverage, the smaller error wins** — only the part beyond the
     * tolerance. Missing the first point by 20° vs 100° matters; 3° vs 0° does
     * not, and buying that with a full mast turn would be backwards.
     *
     * **Error within [toleranceDeg] counts as covered.** Missing the first
     * point by 3° behind the stop is not worth a full turn: the beam forgives
     * a few degrees, the half-minute unwind is lost for good. Beyond the
     * tolerance a missed point stays missed, and [worstErrorDeg] always
     * reports the real worst error — it feeds the banner.
     *
     * [startNearDeg] (mast position) only picks how the first point is
     * written. Null on an empty track, which differs from covering nothing.
     */
    fun plan(
        track: List<Pair<Double, Double>>,
        limits: Limits,
        startNearDeg: Double = limits.azStopDeg,
        toleranceDeg: Double = 0.0
    ): Plan? {
        if (track.isEmpty()) return null
        val unrolled = unroll(track, startNearDeg)
        // sin(elevation), never negative: below the horizon a point weighs
        // nothing, and must never weigh backwards.
        val weights = track.map { max(0.0, sin(Math.toRadians(it.second))) }
        val total = weights.sum()

        var best: Plan? = null
        // Smallest shift first: on a perfect tie, prefer adding no turns.
        for (shift in listOf(0.0, -360.0, 360.0, -720.0, 720.0)) {
            var covered = 0.0
            var count = 0
            var worst = 0.0
            for (i in unrolled.indices) {
                val want = unrolled[i] + shift
                val err = abs(want - clampAz(want, limits))
                if (err > worst) worst = err
                if (err <= toleranceDeg + 1e-9) { covered += weights[i]; count++ }
            }
            // A pass entirely below the horizon weighs nothing: fall back to a
            // raw count rather than divide by zero.
            val cov = if (total > 1e-12) covered / total else count.toDouble() / unrolled.size
            // Only the excess over tolerance breaks ties: below it, two plans
            // are equal, and splitting them would buy a full mast turn for
            // three degrees the beam cannot even see.
            val exces = max(0.0, worst - toleranceDeg)
            val b = best
            val excesB = if (b == null) 0.0 else max(0.0, b.worstErrorDeg - toleranceDeg)
            val meilleur = b == null ||
                cov > b.coverage + 1e-9 ||
                (cov > b.coverage - 1e-9 && exces < excesB - 1e-9)
            if (meilleur) best = Plan(shift, unrolled[0] + shift, cov, worst)
        }
        return best
    }

    /**
     * Applies the plan, second by second.
     *
     * Nothing is reopened: unwrap the true azimuth nearest the previous
     * command (which carries the chosen branch), clamp into range, report what
     * was given up. The mast never goes back for another way to write the same
     * point: that would be the very full turn we avoid.
     *
     * Elevation flip stays possible on 180° rotators, with the same
     * [FLIP_HYSTERESIS_DEG] as in 18.9 — and here the cost also weighs errors,
     * ten times heavier than travel: flipping to aim right is worth it,
     * flipping to move a bit less is not.
     *
     * Never null: there is always somewhere the mast can go.
     */
    fun follow(
        azTrueDeg: Double,
        elTrueDeg: Double,
        current: RotorPos,
        limits: Limits,
        wasFlipped: Boolean = false
    ): Aim {
        val direct = reach(azTrueDeg, elTrueDeg, current.azDeg, limits, flipped = false)
        val flipped =
            if (limits.elMaxDeg >= 180.0 - 1e-9)
                reach(azTrueDeg + 180.0, 180.0 - elTrueDeg, current.azDeg, limits, flipped = true)
            else null
        if (flipped == null) return direct
        val held = if (wasFlipped) flipped else direct
        val other = if (wasFlipped) direct else flipped
        return if (followCost(other, current) + FLIP_HYSTERESIS_DEG < followCost(held, current))
            other else held
    }

    /** Cost of a command: mast degrees, plus error at a high price. */
    fun followCost(aim: Aim, current: RotorPos): Double =
        cost(aim, current) + ERROR_WEIGHT * aim.errorDeg

    private fun reach(
        azDeg: Double, elDeg: Double, lastAzDeg: Double, limits: Limits, flipped: Boolean
    ): Aim {
        val want = unwrapFree(azDeg, lastAzDeg)
        val az = clampAz(want, limits)
        val el = elDeg.coerceIn(0.0, limits.elMaxDeg)
        return Aim(az, el, flipped, abs(want - az) + abs(elDeg - el))
    }

    /**
     * Where the antenna really points, from what the mast reports.
     *
     * An overlap mast reports 380° where the sky has 20: the compass must show
     * the sky point, not the ring turn. A flipped elevation rotator at 100°
     * actually points 80° the opposite way; showing the raw reading would put
     * the needle exactly opposite the antenna — the one display error an
     * operator does not forgive.
     */
    fun antennaAim(pos: RotorPos): RotorPos =
        if (pos.elDeg > 90.0 + 1e-9)
            RotorPos(norm360(pos.azDeg + 180.0), 180.0 - pos.elDeg)
        else RotorPos(norm360(pos.azDeg), pos.elDeg)

    /**
     * Converts a true azimuth to the controller's origin, at the last moment.
     *
     * Everywhere in the app azimuth is from true north. Some controllers count
     * from their stop: on a south-stop mast their "zero" is our 180. The
     * conversion happens only when writing the frame, and [trueAz] undoes it
     * as soon as the position comes back. One setting, nothing else needs to
     * know.
     */
    fun commandAz(azTrueDeg: Double, limits: Limits, fromStop: Boolean): Double =
        if (fromStop) azTrueDeg - limits.azStopDeg else azTrueDeg

    /** Inverse of [commandAz]: the controller's reading, in true north. */
    fun trueAz(azCmdDeg: Double, limits: Limits, fromStop: Boolean): Double =
        if (fromStop) azCmdDeg + limits.azStopDeg else azCmdDeg
}
