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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * QO-100: the satellite that does not move.
 *
 * The rest of SatMe assumes satellites pass. Es'hail-2 is geostationary at
 * 25.9° E and from Europe simply sits at the same azimuth and elevation, day
 * and night. That removes three things:
 *
 * - **No Doppler.** Range rate is zero by construction. It is zeroed through
 *   the range rate rather than a switch, so the existing chain stays intact
 *   with no special branch.
 * - **No pass.** No AOS, LOS, countdown or ground track. SGP4 on a
 *   geostationary gives nothing usable; do not ask it.
 * - **Nothing for the mast to track.** The dish is pointed once.
 *
 * What is new: downlink at 10 489 MHz and uplink at 2 400 MHz, out of reach
 * of amateur radios, hence converters (see [Convertisseur]).
 *
 * Pure domain: no Android, no radio, no settings, no converters. Everything
 * is in sky frequencies; translation to radio frequencies happens elsewhere,
 * at the last moment.
 *
 * Source: AMSAT-DL narrowband transponder band plan.
 */
object Qo100 {

    /** Catalogue number, used to find the per-satellite calibration. */
    const val NORAD = 43700

    /** Header text. Both names are in common use. */
    const val NOM = "QO-100 / Es'hail-2"

    /** Position on the geostationary arc, degrees east. */
    const val LONGITUDE_DEG = 25.9

    /**
     * Fixed uplink/downlink offset: `downlink = uplink + 8 089.5 MHz`, always,
     * no spectrum inversion. Unlike an inverting transponder, one kHz up on
     * the uplink is one kHz up on the downlink.
     */
    const val DECALAGE_HZ = 8_089_500_000L

    /** The three narrowband beacons, sky frequencies. */
    const val BALISE_BASSE_HZ = 10_489_500_000L

    /**
     * Middle beacon, BPSK 400 bit/s: the calibration reference. It is
     * clock-disciplined on the ground, so any offset measured on it is
     * downconverter LO drift, stored in the satellite's calibration offset. A
     * TV LNB drifts tens of kHz at power-up and settles in half an hour.
     */
    const val BALISE_MEDIANE_HZ = 10_489_750_000L

    const val BALISE_HAUTE_HZ = 10_490_000_000L

    /**
     * A QO-100 transponder. Bounds are sky frequencies; uplink bounds are
     * spelled out rather than computed so the band plan reads at a glance.
     *
     * **The 7 kHz at the top edge.** AMSAT-DL gives uplink 2 400.005–2 400.490
     * and downlink 10 489.505–10 489.997. The bottom edges match the
     * 8 089.5 MHz offset; the top ones do not (the uplink edge gives
     * 10 489.990). Those 7 kHz are **the multimedia beacon** (8APSK
     * 7 200 bit/s, see [SEGMENTS]): received, but nobody transmits there. The
     * uplink edge is the last frequency you *transmit* on, the downlink edge
     * the last you *receive* anything on.
     *
     * So never derive an uplink from the downlink top edge: always use
     * [monteeDepuisDescente] and check [emissionAutorisee] before sending it to
     * the radio. 7 kHz is enough to put a carrier on a beacon.
     */
    data class Transpondeur(
        /** Translation key suffix: `qo100_tp_nb`, `qo100_tp_wb`. */
        val cle: String,
        val monteeBasHz: Long,
        val monteeHautHz: Long,
        val descenteBasHz: Long,
        val descenteHautHz: Long,
    ) {
        /** Middle of the passband, a reasonable starting point. */
        val centreDescenteHz: Long get() = (descenteBasHz + descenteHautHz) / 2

        /** Is this downlink inside the transponder? */
        fun contientDescente(hz: Long): Boolean = hz in descenteBasHz..descenteHautHz

        /** Clamps a downlink into the transponder. */
        fun brideDescente(hz: Long): Long = hz.coerceIn(descenteBasHz, descenteHautHz)

        /** Usable width, for display. */
        val largeurHz: Long get() = descenteHautHz - descenteBasHz
    }

    /**
     * Narrowband transponder: SSB, CW, slow digital modes. Uplink RHCP,
     * downlink linear vertical. The one an ordinary amateur station uses.
     */
    val NB = Transpondeur(
        cle = "nb",
        monteeBasHz = 2_400_005_000L, monteeHautHz = 2_400_490_000L,
        descenteBasHz = 10_489_505_000L, descenteHautHz = 10_489_997_000L)

    /**
     * Wideband transponder: amateur DVB-S2 TV. Needs a DVB-S2 modulator SatMe
     * does not drive; listed for display and completeness only.
     */
    val WB = Transpondeur(
        cle = "wb",
        monteeBasHz = 2_401_500_000L, monteeHautHz = 2_409_500_000L,
        descenteBasHz = 10_491_000_000L, descenteHautHz = 10_499_000_000L)

    val TRANSPONDEURS: List<Transpondeur> = listOf(NB, WB)

    // --- Narrowband transponder band plan --------------------------------

    /**
     * What a segment allows. Used only for the segment colour on the scale and
     * the warning tone when the cursor lands there; the details (max width,
     * marker) live in [Segment].
     */
    enum class Usage {
        /** A beacon. Listen, never transmit on it. */
        BALISE,

        /** CW only. */
        CW,

        /** Digital modes. Allowed width is in [Segment.largeurMaxHz]. */
        NUMERIQUE,

        /** Voice only, in practice SSB. Most of the activity. */
        PHONIE,

        /** Broadcast frequency, reserved for club transmissions. */
        DIFFUSION,

        /** Emergency frequency. Keep it clear. */
        URGENCE,

        /** Mixed modes and special uses; anything up to 2.7 kHz. */
        MIXTE,
        ;

        /** May one transmit in a segment of this kind? */
        val emissionPermise: Boolean get() = this != BALISE

        /**
         * Warning key suffix: the screen builds `"qo100_warn_" + cleAvertissement`.
         *
         * Derived from the name rather than copied, so a new usage cannot ship
         * without its text: the strings test walks [entries] and asks for each key.
         */
        val cleAvertissement: String get() = name.lowercase()
    }

    /**
     * A slice of the narrowband transponder, as published by AMSAT-DL.
     *
     * Bounds are downlink sky frequencies, closed below and open above
     * (`basHz <= f < hautHz`), so the twelve segments join end to end with no
     * gap or overlap; the coverage test checks it.
     */
    data class Segment(
        /**
         * Translation key suffix: the screen builds `"qo100_seg_" + cle`.
         * Renaming here empties a label, and the strings test says so.
         */
        val cle: String,
        val basHz: Long,
        val hautHz: Long,
        val usage: Usage,
        /** Max transmit width in Hz, or zero when the plan sets none (CW). */
        val largeurMaxHz: Int = 0,
        /**
         * The frequency that gives the segment its meaning, if any (beacon
         * centre, broadcast, emergency). Null for plain ranges.
         */
        val repereHz: Long? = null,
    ) {
        val largeurHz: Long get() = hautHz - basHz

        /** Does this downlink fall in this segment? */
        operator fun contains(hz: Long): Boolean = hz >= basHz && hz < hautHz

        /** Shortcut: may one transmit here? */
        val emissionPermise: Boolean get() = usage.emissionPermise
    }

    /**
     * The twelve narrowband segments, beacon to beacon.
     *
     * Source: AMSAT-DL detailed band plan. Covers 10 489.500 to 10 490.000
     * (500 kHz), a bit more than the 492 kHz of [NB], because it includes the
     * two CW beacons framing the passband: they are the visible end stops on
     * the scale.
     *
     * Worth noting when re-reading this table:
     *
     * - **There are four beacons, not three.** The multimedia beacon at
     *   10 489.990–10 489.997 (8APSK 7 200 bit/s) explains the 7 kHz at the
     *   top edge (see [Transpondeur]). It has no constant: it is a guard, not
     *   a marker.
     * - **The last transmit frequency is 10 489.990**, not 10 489.997. That is
     *   [DERNIERE_DESCENTE_EMISSIBLE_HZ], the one value here that prevents real
     *   interference.
     * - **Broadcast and emergency are 7.5 kHz each**, so their shared edge is
     *   at 10 489.8575. Not a typo: the published table does give two 7.5 kHz
     *   slices around 10 489.855 and 10 489.860.
     */
    val SEGMENTS: List<Segment> = listOf(
        Segment("balise_basse", 10_489_500_000L, 10_489_505_000L, Usage.BALISE,
            repereHz = BALISE_BASSE_HZ),
        Segment("cw", 10_489_505_000L, 10_489_540_000L, Usage.CW),
        Segment("num_etroit", 10_489_540_000L, 10_489_580_000L, Usage.NUMERIQUE,
            largeurMaxHz = 500),
        Segment("num_large", 10_489_580_000L, 10_489_650_000L, Usage.NUMERIQUE,
            largeurMaxHz = 2_700),
        Segment("ssb_bas", 10_489_650_000L, 10_489_745_000L, Usage.PHONIE,
            largeurMaxHz = 2_700),
        Segment("balise_mediane", 10_489_745_000L, 10_489_755_000L, Usage.BALISE,
            repereHz = BALISE_MEDIANE_HZ),
        Segment("ssb_haut", 10_489_755_000L, 10_489_850_000L, Usage.PHONIE,
            largeurMaxHz = 2_700),
        Segment("diffusion", 10_489_850_000L, 10_489_857_500L, Usage.DIFFUSION,
            largeurMaxHz = 2_700, repereHz = 10_489_855_000L),
        Segment("urgence", 10_489_857_500L, 10_489_865_000L, Usage.URGENCE,
            largeurMaxHz = 2_700, repereHz = 10_489_860_000L),
        Segment("mixte", 10_489_865_000L, 10_489_990_000L, Usage.MIXTE,
            largeurMaxHz = 2_700),
        Segment("balise_multimedia", 10_489_990_000L, 10_489_997_000L, Usage.BALISE,
            repereHz = 10_489_993_500L),
        Segment("balise_haute", 10_489_997_000L, 10_490_000_000L, Usage.BALISE,
            repereHz = BALISE_HAUTE_HZ),
    )

    /** Bottom of the scale: start of the low beacon. */
    val REGLETTE_BAS_HZ: Long get() = SEGMENTS.first().basHz

    /** Top of the scale: the high beacon, included. */
    val REGLETTE_HAUT_HZ: Long get() = SEGMENTS.last().hautHz

    /**
     * Last downlink one may transmit on. Above it: multimedia beacon, then the
     * high CW beacon. The published downlink edge (10 489.997) is *beyond*
     * this value — that is the trap, and why this constant is not derived
     * from [NB].
     */
    const val DERNIERE_DESCENTE_EMISSIBLE_HZ = 10_489_990_000L

    /**
     * Segment containing this downlink, or `null` outside the scale.
     *
     * Intervals are open above, so the very last frequency is caught by hand;
     * otherwise the high beacon edge would be the one point belonging to nothing.
     */
    fun segment(descenteHz: Long): Segment? =
        SEGMENTS.firstOrNull { descenteHz in it }
            ?: SEGMENTS.last().takeIf { descenteHz == it.hautHz }

    /**
     * May one transmit on this downlink? False outside the scale and on all
     * four beacons. The screen asks this before sending a TX frequency to the
     * radio; never derive it from [NB] bounds or the published table edge.
     */
    fun emissionAutorisee(descenteHz: Long): Boolean =
        segment(descenteHz)?.emissionPermise == true

    /** Downlink for an uplink. No inversion: an addition. */
    fun descenteDepuisMontee(monteeHz: Long): Long = monteeHz + DECALAGE_HZ

    /** Uplink for a downlink. */
    fun monteeDepuisDescente(descenteHz: Long): Long = descenteHz - DECALAGE_HZ

    /**
     * Where to point the dish, and how far to rotate the feed.
     *
     * [azDeg] is true azimuth, clockwise from geographic north — not magnetic
     * north: declination is added at display time, as everywhere in the app.
     */
    data class Pointage(
        val azDeg: Double,
        val elDeg: Double,
        /**
         * Feed rotation in degrees, positive clockwise seen from behind the dish.
         *
         * The narrowband downlink is linearly polarised, so the feed must be
         * rotated to match. From France it is about twenty degrees; ignoring it
         * costs a few dB and looks like an antenna problem.
         */
        val skewDeg: Double,
    ) {
        /** Is the satellite above the horizon from here? */
        val visible: Boolean get() = elDeg > 0.0
    }

    /**
     * Pointing from a point on Earth, no orbit propagation.
     *
     * Observer and satellite are placed in an Earth-fixed frame and the vector
     * between them is projected onto local east/north/up. Longer than the
     * textbook closed form, but correct in both hemispheres and at extreme
     * longitudes, where the closed form silently switches branch.
     *
     * Spherical Earth: about a tenth of a degree off the ellipsoid, far below
     * what a phone compass and a spirit level can achieve.
     */
    fun pointage(latDeg: Double, lonDeg: Double): Pointage {
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg)
        val lamSat = Math.toRadians(LONGITUDE_DEG)

        // Observer and satellite, in Earth radii.
        val ox = cos(phi) * cos(lam)
        val oy = cos(phi) * sin(lam)
        val oz = sin(phi)
        val sx = RAPPORT_ORBITE * cos(lamSat)
        val sy = RAPPORT_ORBITE * sin(lamSat)

        // Vector from antenna to satellite.
        val vx = sx - ox
        val vy = sy - oy
        val vz = -oz

        // Local frame. "Up" is the local vertical, i.e. the observer's
        // direction from the Earth's centre.
        val est = -sin(lam) * vx + cos(lam) * vy
        val nord = -sin(phi) * cos(lam) * vx - sin(phi) * sin(lam) * vy + cos(phi) * vz
        val haut = ox * vx + oy * vy + oz * vz

        val az = (Math.toDegrees(atan2(est, nord)) + 360.0) % 360.0
        val el = Math.toDegrees(atan2(haut, hypot(est, nord)))

        return Pointage(azDeg = az, elDeg = el, skewDeg = skew(latDeg, lonDeg))
    }

    /**
     * Feed rotation, wrapped to ±90°: linear polarisation repeats every 180°,
     * so 170° is the same as −10°.
     */
    private fun skew(latDeg: Double, lonDeg: Double): Double {
        val phi = Math.toRadians(latDeg)
        val delta = Math.toRadians(LONGITUDE_DEG - lonDeg)
        // Exactly on the equator the angle is undefined: the feed is aligned.
        if (abs(latDeg) < 1e-9) return 0.0
        var s = Math.toDegrees(atan2(sin(delta), tan(phi)))
        while (s > 90.0) s -= 180.0
        while (s <= -90.0) s += 180.0
        return s
    }

    /**
     * Geostationary orbit radius over Earth radius: 42 164 / 6 378.137 =
     * 6.6107. The only distance constant needed, since everything is in Earth
     * radii.
     *
     * Mind the direction: textbooks often give the inverse, 0.15127, because
     * it appears in the closed-form elevation formula. Here the satellite is
     * actually placed in the frame, so we need its distance, not the inverse.
     */
    private const val RAPPORT_ORBITE = 42_164.0 / 6_378.137
}
