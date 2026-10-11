/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Antenna pointing, derived from the module attitude.
 *
 * **Why this file exists.** The first version read yaw as azimuth and one
 * tilt as elevation, each on its own. That works in horizontal polarisation
 * and falls apart as soon as the boom rotates on its axis: Euler angles are
 * not three independent measurements but three steps of one rotation, and
 * they mix. At 90° of polarisation pitch and yaw swap roles — hence the
 * inversions seen in the field.
 *
 * **What does not mix** is the direction the boom points. A polarisation
 * rotation happens *around* the boom, so it leaves that direction unchanged.
 * We rebuild the full box rotation, apply it to the boom vector, and read
 * azimuth and elevation off the result. Polarisation has no hold: geometry
 * cancels it, not a setting.
 *
 * **Frame**: X north, Y east, Z down, yaw-pitch-roll. With the boom along
 * box X and horizontal polarisation it gives exactly azimuth = yaw and
 * elevation = pitch (checked by a test).
 */

/** A three-component vector. Nothing more than a named triplet. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    val norme: Float get() = sqrt(x * x + y * y + z * z)

    fun normalise(): Vec3 {
        val n = norme
        // A null vector has no direction. Return it as is rather than divide
        // by zero: the caller has a branch for it.
        return if (n < 1e-6f) this else Vec3(x / n, y / n, z / n)
    }

    fun produitScalaire(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)
}

/** Where the boom points: azimuth 0..360, elevation −90..90. */
data class Pointage(val azimutDeg: Float, val elevationDeg: Float)

object PointageAntenne {

    /**
     * Box rotation matrix, box frame to ground frame, as nine floats in row
     * order. Only applied and composed, so no matrix class.
     */
    fun matrice(a: AttitudeWit, ordre: Ordre = Ordre.ZYX): FloatArray {
        if (ordre == Ordre.ZXY) return matriceZXY(a)
        val r = Math.toRadians(a.roulis.toDouble()).toFloat()
        val p = Math.toRadians(a.tangage.toDouble()).toFloat()
        val l = Math.toRadians(a.lacet.toDouble()).toFloat()
        val cr = cos(r); val sr = sin(r)
        val cp = cos(p); val sp = sin(p)
        val cl = cos(l); val sl = sin(l)
        // R = Rz(lacet) · Ry(tangage) · Rx(roulis)
        return floatArrayOf(
            cl * cp, cl * sp * sr - sl * cr, cl * sp * cr + sl * sr,
            sl * cp, sl * sp * sr + cl * cr, sl * sp * cr - cl * sr,
            -sp,     cp * sr,                cp * cr
        )
    }

    /** Applies a matrix to a vector. */
    fun applique(m: FloatArray, v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z
    )

    /** The product `aᵀ · b`, used to compare two attitudes. */
    fun transposeePuis(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[k * 3 + i] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    /**
     * The world frame the module counts in.
     *
     * **The one thing an offset cannot fix.** East and west swapped with
     * north right is a **mirror** (frame handedness), not an offset: a
     * sighting fixes only the pose it was taken at. Modules disagree (NED
     * with clockwise yaw, or ENU counter-clockwise) and datasheets do not
     * always say, so it is **measured** with a second sighting.
     */
    enum class Repere { NED, ENU }

    /** The order in which the three rotations compose. */
    enum class Ordre { ZYX, ZXY }

    /**
     * Candidate conventions, tried and **decided by measurement**.
     *
     * Reading in the other frame swaps the first two axes, which also flips
     * pitch and roll: it does **not** describe a module whose yaw alone runs
     * backwards (disproved on the bench). So all candidates are offered and
     * the sightings decide.
     */
    enum class Convention(
        val repere: Repere,
        val lacetOppose: Boolean,
        val ordre: Ordre = Ordre.ZYX
    ) {
        DIRECTE(Repere.NED, false),
        LACET_OPPOSE(Repere.NED, true),
        AXES_ECHANGES(Repere.ENU, false),
        AXES_ET_LACET(Repere.ENU, true),
        // **Composition order is the missing dimension.**
        //
        // The first four differ only in yaw handling, and level sightings
        // constrain only yaw. What separates them is how pitch and roll
        // compose, off the level — hence these four, and the third, raised
        // sighting without which they are indistinguishable.
        DIRECTE_ZXY(Repere.NED, false, Ordre.ZXY),
        LACET_OPPOSE_ZXY(Repere.NED, true, Ordre.ZXY),
        AXES_ECHANGES_ZXY(Repere.ENU, false, Ordre.ZXY),
        AXES_ET_LACET_ZXY(Repere.ENU, true, Ordre.ZXY);

        companion object {
            fun depuisTexte(t: String): Convention =
                entries.firstOrNull { it.name == t } ?: DIRECTE
        }
    }

    /** The attitude as the convention reads it. */
    fun selon(a: AttitudeWit, c: Convention): AttitudeWit =
        if (c.lacetOppose) AttitudeWit(a.roulis, a.tangage, -a.lacet) else a

    /**
     * Azimuth and elevation of a ground-frame direction. In NED, Z points
     * down, so the upward component is `−z`.
     */
    fun lis(direction: Vec3, repere: Repere = Repere.NED): Pointage {
        val v = direction.normalise()
        // NED: azimuth turns from x to y, elevation rises as z goes down.
        // ENU: first two axes swapped, third points up. Nothing else differs,
        // and that is enough to swap east and west.
        var az = if (repere == Repere.NED)
            Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()
        else
            Math.toDegrees(atan2(v.x.toDouble(), v.y.toDouble())).toFloat()
        if (az < 0f) az += 360f
        val haut = if (repere == Repere.NED) -v.z else v.z
        val el = Math.toDegrees(asin(haut.coerceIn(-1f, 1f).toDouble())).toFloat()
        return Pointage(az, el)
    }

    /**
     * The same attitude, composed z-x-y: `R = Rz(lacet) · Rx(roulis) · Ry(tangage)`.
     *
     * Some firmwares publish their angles in this order, and the difference
     * **never** shows while level: with zero roll and pitch both formulas give
     * the same matrix. That is why every flat test missed it.
     */
    private fun matriceZXY(a: AttitudeWit): FloatArray {
        val r = Math.toRadians(a.roulis.toDouble()).toFloat()
        val p = Math.toRadians(a.tangage.toDouble()).toFloat()
        val l = Math.toRadians(a.lacet.toDouble()).toFloat()
        val cr = cos(r); val sr = sin(r)
        val cp = cos(p); val sp = sin(p)
        val cl = cos(l); val sl = sin(l)
        return floatArrayOf(
            cl * cp - sl * sr * sp, -sl * cr, cl * sp + sl * sr * cp,
            sl * cp + cl * sr * sp,  cl * cr, sl * sp - cl * sr * cp,
            -cr * sp,                sr,      cr * cp
        )
    }

    /** World vector of an azimuth and elevation, in the given frame. */
    fun direction(azimutDeg: Float, elevationDeg: Float, repere: Repere = Repere.NED): Vec3 {
        val a = Math.toRadians(azimutDeg.toDouble())
        val e = Math.toRadians(elevationDeg.toDouble())
        val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
        val ce = kotlin.math.cos(e); val se = kotlin.math.sin(e)
        return if (repere == Repere.NED)
            Vec3((ce * ca).toFloat(), (ce * sa).toFloat(), (-se).toFloat())
        else
            Vec3((ce * sa).toFloat(), (ce * ca).toFloat(), se.toFloat())
    }

    /**
     * Full pointing, invariant to polarisation.
     *
     * [fleche] is the boom direction **in the box frame**, what calibration
     * learns. [calageAzimut] re-aligns north: a rotation, safe for invariance.
     */
    fun pointage(
        a: AttitudeWit,
        fleche: Vec3,
        calageAzimut: Float = 0f,
        /**
         * The module frame, **measured, not guessed**.
         *
         * Replaces the old "reverse" flag (yaw negated before the matrix),
         * which is not equivalent: switching frames also changes the rotation
         * order. The frame only affects how the output vector is read — an
         * axis swap, which keeps polarisation invariance.
         */
        convention: Convention = Convention.DIRECTE
    ): Pointage {
        // **No inversion here, on purpose.**
        //
        // With the boom vector, heading is **fully determined** by matrix and
        // vector. The old output flip was a reflection, not a rotation, and
        // polarisation invariance holds only for rotations: cardinal points
        // came out reversed and the offset shifted in vertical polarisation.
        // If heading is reversed, redo a cardinal calibration.
        val brut = lis(
            applique(matrice(selon(a, convention), convention.ordre), fleche),
            convention.repere)
        var az = (brut.azimutDeg + calageAzimut) % 360f
        if (az < 0f) az += 360f
        return Pointage(az, brut.elevationDeg)
    }

    /** What the sightings determine: the boom, and the module frame. */
    data class Calibration(val fleche: Vec3, val convention: Convention, val ecartDeg: Float)

    /**
     * Full calibration: **three sightings**, one with the antenna raised.
     *
     * **Why three.** Level sightings constrain only yaw: successive
     * calibrations picked different conventions, all right when flat and
     * wrong once tilted (absurd elevation, drift in vertical polarisation).
     * Only the raised sighting tests how pitch and roll compose.
     *
     * [elevationC] need not be precise, only clearly raised. What is judged is
     * that the **azimuth holds** despite it, which the wrong convention cannot
     * do.
     */
    fun calibreDepuisTroisVisees(
        poseA: AttitudeWit, azimutA: Float,
        poseB: AttitudeWit, azimutB: Float,
        poseC: AttitudeWit, azimutC: Float, elevationC: Float
    ): Calibration? {
        var separation = kotlin.math.abs(azimutB - azimutA) % 360f
        if (separation > 180f) separation = 360f - separation
        if (separation < ECART_VISEES) return null

        var meilleure: Calibration? = null
        for (convention in Convention.entries) {
            val f = apprendFlecheDepuisAzimut(poseA, azimutA, convention = convention)
                ?: continue
            fun ecartA(pose: AttitudeWit, vise: Float): Float {
                val relu = pointage(pose, f, convention = convention).azimutDeg
                var e = kotlin.math.abs(relu - vise) % 360f
                if (e > 180f) e = 360f - e
                return e
            }
            // Worst of the two checks, not the mean: a convention that passes
            // level and fails raised must be rejected, not rescued by its good
            // half.
            val ecart = maxOf(ecartA(poseB, azimutB), ecartA(poseC, azimutC))
            if (meilleure == null || ecart < meilleure!!.ecartDeg) {
                meilleure = Calibration(f, convention, ecart)
            }
        }
        val c = meilleure ?: return null
        return if (c.ecartDeg > TOLERANCE_VISEES) null else c
    }

    /**
     * Calibration from **two known sightings**.
     *
     * **Why two.** One sighting fixes the boom but not the frame: the learned
     * boom absorbs the difference *at that pose*, so north was right and east
     * came out west. The boom is learned on the first sighting under each
     * convention; the one that reads the **second** back wins.
     *
     * The sightings must be at least [ECART_VISEES] degrees apart, otherwise
     * both hypotheses stay plausible: returns `null`. A random offset is worse
     * than none.
     */
    fun calibreDepuisDeuxVisees(
        poseA: AttitudeWit, azimutA: Float,
        poseB: AttitudeWit, azimutB: Float
    ): Calibration? {
        var separation = kotlin.math.abs(azimutB - azimutA) % 360f
        if (separation > 180f) separation = 360f - separation
        if (separation < ECART_VISEES) return null

        var meilleure: Calibration? = null
        for (convention in Convention.entries) {
            val f = apprendFlecheDepuisAzimut(poseA, azimutA, convention = convention)
                ?: continue
            val relu = pointage(poseB, f, convention = convention).azimutDeg
            var ecart = kotlin.math.abs(relu - azimutB) % 360f
            if (ecart > 180f) ecart = 360f - ecart
            if (meilleure == null || ecart < meilleure!!.ecartDeg) {
                meilleure = Calibration(f, convention, ecart)
            }
        }
        // Off by more than a quarter turn on the second sighting is no
        // hypothesis: the readings themselves are inconsistent. Say so rather
        // than calibrate on noise.
        val c = meilleure ?: return null
        return if (c.ecartDeg > TOLERANCE_VISEES) null else c
    }

    /** Below this, the two sightings cannot measure the direction. */
    const val ECART_VISEES = 45f

    /** Above this, even the best hypothesis does not fit: refuse. */
    const val TOLERANCE_VISEES = 25f

    // ---- heading offset: two known directions ----

    /** What two readings determine: how much to offset, and which way. */
    data class CalageCap(val calageDeg: Float, val inverse: Boolean)

    /**
     * Tolerance between the two sightings and what is expected. 45°: an
     * antenna is aimed by hand, not with a theodolite, and a rough quarter
     * turn stays unambiguous. Beyond, both hypotheses become equally
     * plausible.
     */
    const val TOLERANCE_CAP = 45f

    /**
     * Heading offset from two known sightings: **north**, then **west**.
     *
     * **Why two.** One reading cannot tell a 180° offset from a reversed
     * direction of rotation: either way, aiming north shows south. West is
     * the handiest second direction, found without instruments once north is
     * known.
     *
     * From north to west, compass azimuth **increases by 270°**. If the two
     * raw readings differ by 270° the module counts the right way; by 90°, it
     * counts backwards. The offset is then read on the north reading alone.
     *
     * Returns `null` when the difference looks like neither (antenna badly
     * aimed, or module still moving). **Better write nothing than a random
     * offset**: a wrong dial believed calibrated is worse than one known to be
     * wrong.
     */
    fun calageCapDepuisNordOuest(brutNordDeg: Float, brutOuestDeg: Float): CalageCap? {
        var ecart = (brutOuestDeg - brutNordDeg) % 360f
        if (ecart < 0f) ecart += 360f

        // **The distance must be circular.** A bare subtraction ignores
        // wrap-around: for two identical sightings the difference is 0, and
        // `|0 - 90|` made the reversed hypothesis look only 90° off. The guard
        // then let an offset be written from nothing.
        fun distance(a: Float, b: Float): Float {
            var d = kotlin.math.abs(a - b) % 360f
            if (d > 180f) d = 360f - d
            return d
        }
        val versDirect = distance(ecart, 270f)
        val versInverse = distance(ecart, 90f)

        // The two hypotheses are 180° apart, so they cannot both be close.
        // But they can **both be far** (identical or opposite sightings): then
        // refuse rather than keep the less bad one.
        if (minOf(versDirect, versInverse) > TOLERANCE_CAP) return null
        val inverse = versInverse < versDirect
        val signe = if (inverse) -brutNordDeg else brutNordDeg
        var calage = (-signe) % 360f
        if (calage > 180f) calage -= 360f
        if (calage < -180f) calage += 360f
        return CalageCap(calage, inverse)
    }

    // ---- learning the boom axis ----

    /**
     * Below this angle between the two polarisations, the axis cannot be read.
     *
     * Not a comfort margin: a rotation axis comes from the antisymmetric part
     * of its matrix, whose magnitude is `2 sin θ`. Near zero it drowns in
     * noise and the axis becomes any direction.
     */
    const val ROTATION_MINIMALE = 25f

    /** Above this, `sin θ` drops again and the axis is ill-conditioned. */
    const val ROTATION_MAXIMALE = 155f

    /**
     * Boom direction from two readings at **the same pointing** but two
     * polarisations.
     *
     * The only thing that did not move between the two readings is the boom,
     * so it is the axis of the rotation between the two attitudes.
     *
     * Returns `null` when the rotation is too small or near a half turn: the
     * axis is ill-determined, and **saying so beats a direction drawn from
     * noise** that looks neat.
     *
     * The sign stays undetermined (a boom and its opposite share an axis);
     * [resoutSens] decides.
     */
    fun apprendFleche(pol1: AttitudeWit, pol2: AttitudeWit): Vec3? {
        val m = transposeePuis(matrice(pol2), matrice(pol1))
        // Axis from the antisymmetric part; its norm is 2 sin θ.
        val axe = Vec3(m[7] - m[5], m[2] - m[6], m[3] - m[1])
        val deuxSinus = axe.norme
        val minimum = 2f * sin(Math.toRadians(ROTATION_MINIMALE.toDouble())).toFloat()
        if (deuxSinus < minimum) return null
        // Trace gives the angle: 1 + 2 cos θ. Past the upper bound the same
        // `2 sin θ` means a near-full rotation, ill-posed.
        val cosinus = ((m[0] + m[4] + m[8]) - 1f) / 2f
        val angle = Math.toDegrees(
            atan2(deuxSinus.toDouble() / 2.0, cosinus.toDouble())).toFloat()
        if (abs(angle) > ROTATION_MAXIMALE) return null
        return axe.normalise()
    }

    /**
     * Learns the boom from **a single** reading at a known azimuth.
     *
     * **Better than the two polarisation poses.** Rotating around the boom
     * gives the rotation axis, i.e. a *line*, and a line has two ends. The end
     * was then guessed from a raised pose, which can be wrong: the antenna
     * then points exactly opposite, north and south swapped (seen in the
     * field).
     *
     * A known azimuth removes the ambiguity: world direction known, box
     * rotation known, so the box-frame boom follows exactly, `b = Rᵀ · p`.
     *
     * The default reading is **level**, the pose one can hold without
     * instruments. The current polarisation does not matter.
     *
     * The expected azimuth is **magnetic**, like everything the module
     * returns; SatMe applies declination downstream.
     */
    fun apprendFlecheDepuisAzimut(
        pose: AttitudeWit,
        azimutDeg: Float,
        elevationDeg: Float = 0f,
        /** The offset is removed from the sighting: the vector must not carry it. */
        calageAzimut: Float = 0f,
        /** Same frame as when reading, otherwise nothing reads back. */
        convention: Convention = Convention.DIRECTE
    ): Vec3? {
        val m = matrice(selon(pose, convention), convention.ordre)
        // The sighting must be built **in the convention's frame**, otherwise
        // learning compensates one world and reading describes another — this
        // is exactly what made east come out west.
        val p = direction(azimutDeg - calageAzimut, elevationDeg, convention.repere)
        // A rotation's transpose is its inverse: bring the sighting from world
        // to box.
        val b = Vec3(
            m[0] * p.x + m[3] * p.y + m[6] * p.z,
            m[1] * p.x + m[4] * p.y + m[7] * p.z,
            m[2] * p.x + m[5] * p.y + m[8] * p.z
        )
        val n = kotlin.math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
        if (n < 1e-6f) return null
        return Vec3(b.x / n, b.y / n, b.z / n)
    }

    /**
     * Flips the boom end for end: the one-gesture fix when calibration picked
     * the wrong end and everything is 180° off.
     */
    fun retourne(fleche: Vec3): Vec3 = Vec3(-fleche.x, -fleche.y, -fleche.z)

    /**
     * Resolves the sign ambiguity with the antenna raised: if the learned boom
     * gives a negative elevation while the antenna points skyward, it
     * describes the back of the box, so flip it.
     *
     * Returns `null` if the antenna was not raised enough: a level antenna
     * says nothing about its own direction.
     */
    fun resoutSens(fleche: Vec3, leveeFranche: AttitudeWit, seuilDeg: Float = 10f): Vec3? {
        val el = lis(applique(matrice(leveeFranche), fleche)).elevationDeg
        if (abs(el) < seuilDeg) return null
        return if (el > 0f) fleche else -fleche
    }

    /**
     * Snaps the boom to the nearest canonical axis when very close.
     *
     * A box is screwed flat onto a tube, so the boom nearly always falls on
     * ±X, ±Y or ±Z. Snapping within eight degrees removes the noise of a
     * single reading without forcing a genuinely skewed mount.
     */
    fun rangeSurAxe(fleche: Vec3, toleranceDeg: Float = 8f): Vec3 {
        val candidats = listOf(
            Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f),
            Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f),
            Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f)
        )
        val v = fleche.normalise()
        val seuil = cos(Math.toRadians(toleranceDeg.toDouble())).toFloat()
        return candidats.firstOrNull { v.produitScalaire(it) >= seuil } ?: v
    }

    /** Writes a boom vector for storage in preferences. */
    fun enTexte(v: Vec3): String = "%.6f,%.6f,%.6f".format(java.util.Locale.US, v.x, v.y, v.z)

    /**
     * Reads a stored boom back. `null` on anything unreadable, including the
     * empty string, which means "not learned yet".
     */
    fun depuisTexte(s: String?): Vec3? {
        val p = s?.split(",") ?: return null
        if (p.size != 3) return null
        val x = p[0].toFloatOrNull() ?: return null
        val y = p[1].toFloatOrNull() ?: return null
        val z = p[2].toFloatOrNull() ?: return null
        val v = Vec3(x, y, z)
        return if (v.norme < 1e-3f) null else v.normalise()
    }

    // ---- the convention, measured instead of assumed ----

    /**
     * Any convention of the Euler-angle family.
     *
     * **Why so general.** Seven versions guessed how this module composes its
     * angles (output mirror, negated yaw, swapped frame, z-y-x vs z-x-y…);
     * each held when level and failed elsewhere. So the **whole family** is
     * described — orders, assignments, signs, frames — and the reading picks.
     *
     * [ordre]: the three axes in composition order, e.g. "xyz".
     * [assign]: which angle each axis carries — 0 roll, 1 pitch, 2 yaw.
     * [signes]: +1 or −1 for each.
     * [enu]: true for east-north-up, false for north-east-down.
     */
    data class ConventionLibre(
        val ordre: String,
        val assign: IntArray,
        val signes: IntArray,
        val enu: Boolean
    ) {
        /** Text form: order, roles, signs, frame. */
        fun encode(): String =
            "$ordre|${assign.joinToString("")}|" +
                signes.joinToString("") { if (it > 0) "+" else "-" } +
                "|" + (if (enu) "ENU" else "NED")

        override fun equals(other: Any?) = other is ConventionLibre &&
            encode() == other.encode()
        override fun hashCode() = encode().hashCode()

        companion object {
            /**
             * The WT901BLE convention, measured on a full reading with an
             * east pose: Rz(yaw)·Ry(pitch)·Rx(roll), east-north-up. Default,
             * since most of these modules share the same firmware.
             *
             * It carries the vector computation whenever the boom was chosen
             * by hand without a full analysis, so it must be one of
             * [toutes]. The former `xyz|012|+-+|ENU` put roll first: right at
             * north, 0° of elevation east and west.
             */
            val PAR_DEFAUT = ConventionLibre("zyx", intArrayOf(2, 1, 0),
                intArrayOf(1, 1, 1), true)

            fun decode(t: String): ConventionLibre {
                val p = t.split('|')
                if (p.size != 4 || p[0].length != 3 || p[1].length != 3 ||
                    p[2].length != 3) return PAR_DEFAUT
                return runCatching {
                    ConventionLibre(
                        p[0],
                        IntArray(3) { p[1][it] - '0' },
                        IntArray(3) { if (p[2][it] == '+') 1 else -1 },
                        p[3] == "ENU")
                }.getOrDefault(PAR_DEFAUT)
            }

            /**
             * The candidates — **yaw composed first**, always.
             *
             * Not one more guess: a property of any attitude sensor. Heading
             * applies on the outside, tilt is taken in the already-turned
             * frame. A roll-first convention tilts around a **fixed world
             * axis**: right at north by coincidence, and elevation vanishes as
             * soon as you aim elsewhere.
             *
             * It happened: two outer-roll conventions passed every check —
             * all done facing north — and gave 1° of elevation instead of 56°
             * when aiming east. The constraint removes them and divides the
             * field by three.
             */
            fun toutes(): List<ConventionLibre> {
                val axes = listOf("xyz", "xzy", "yxz", "yzx", "zxy", "zyx")
                val roles = listOf(
                    intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
                    intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
                val out = ArrayList<ConventionLibre>(576)
                // `r[0] == 2`: yaw first. See above.
                for (a in axes) for (r in roles.filter { it[0] == 2 })
                    for (s0 in intArrayOf(1, -1)) for (s1 in intArrayOf(1, -1))
                        for (s2 in intArrayOf(1, -1)) for (enu in listOf(true, false))
                            out.add(ConventionLibre(a, r,
                                intArrayOf(s0, s1, s2), enu))
                return out
            }
        }
    }

    private fun elementaire(axe: Char, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble()).toFloat()
        val c = cos(a); val s = sin(a)
        return when (axe) {
            'x' -> floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
            'y' -> floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
            else -> floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        }
    }

    private fun produit(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var v = 0f
            for (k in 0..2) v += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = v
        }
        return r
    }

    /** Body → world matrix under the given convention. */
    fun matriceLibre(a: AttitudeWit, c: ConventionLibre): FloatArray {
        val angles = floatArrayOf(a.roulis, a.tangage, a.lacet)
        var m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (k in 0..2) {
            m = produit(m, elementaire(c.ordre[k], c.signes[k] * angles[c.assign[k]]))
        }
        return m
    }

    private fun appliqueM(m: FloatArray, v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z)

    private fun appliqueTM(m: FloatArray, v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z)

    /** Pointing under a measured convention. */
    fun pointageLibre(a: AttitudeWit, fleche: Vec3, c: ConventionLibre): Pointage =
        lis(appliqueM(matriceLibre(a, c), fleche),
            if (c.enu) Repere.ENU else Repere.NED)

    /** The boom implied by a known sighting, under a convention. */
    fun flecheLibre(pose: AttitudeWit, azimutDeg: Float, elevationDeg: Float,
                    c: ConventionLibre): Vec3? {
        val p = direction(azimutDeg, elevationDeg,
            if (c.enu) Repere.ENU else Repere.NED)
        val b = appliqueTM(matriceLibre(pose, c), p)
        return if (b.norme < 1e-6f) null else b.normalise()
    }
}
