/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

/**
 * The unit system for distances, altitudes and speeds: metric, imperial
 * (miles, feet) or nautical (nautical miles, knots). Nobody converts in their
 * head during a three-minute pass.
 *
 * Pure Kotlin, no Android import: conversion factors and switch-over
 * thresholds (metres below 1 km, feet below 1 mile) are the kind of detail
 * that breaks silently, so they are tested on the JVM.
 */
object Units {

    /** Metres, kilometres, km/h. */
    const val METRIC = "metric"

    /** Feet, miles, mph. */
    const val IMPERIAL = "imperial"

    /** Nautical miles, knots, feet — marine and aviation. */
    const val NAUTICAL = "nautical"

    /** The three systems, in picker order. */
    val ALL: List<String> = listOf(METRIC, IMPERIAL, NAUTICAL)

    // ---- exact factors -----------------------------------------------------
    /** One metre in international feet (exactly 1 / 0.3048). */
    const val FEET_PER_METER = 3.2808398950131235

    /** One kilometre in statute miles. */
    const val MILES_PER_KM = 0.621371192237334

    /** One kilometre in nautical miles (exactly 1852 m each). */
    const val NM_PER_KM = 1000.0 / 1852.0

    /** One metre per second in knots. */
    const val KNOTS_PER_MPS = 3600.0 / 1852.0

    /**
     * Maps the stored setting to a known system. Missing, empty or unknown
     * falls back to metric rather than making distances vanish.
     */
    fun normalize(v: String?): String {
        val k = v?.trim()?.lowercase().orEmpty()
        return if (k in ALL) k else METRIC
    }

    /** Short distance unit name, for a column header. */
    fun distanceUnit(sys: String): String = when (normalize(sys)) {
        IMPERIAL -> "mi"
        NAUTICAL -> "NM"
        else -> "km"
    }

    /**
     * A distance given in km, to one decimal, switching to the short unit when
     * more readable: 900 m beats 0.9 km, 1200 ft beats 0.23 mi.
     */
    fun distance(km: Double, sys: String): String = when (normalize(sys)) {
        IMPERIAL -> {
            val mi = km * MILES_PER_KM
            if (mi < 1.0) "%.0f ft".format(km * 1000.0 * FEET_PER_METER)
            else "%.1f mi".format(mi)
        }
        NAUTICAL -> {
            val nm = km * NM_PER_KM
            // At sea, below half a nautical mile, metres — as on a ship's bridge.
            if (nm < 0.5) "%.0f m".format(km * 1000.0) else "%.1f NM".format(nm)
        }
        else -> if (km < 1.0) "%.0f m".format(km * 1000.0) else "%.1f km".format(km)
    }

    /**
     * The same distance without decimals, for large numbers in pass tables.
     */
    fun distanceRound(km: Double, sys: String): String = when (normalize(sys)) {
        IMPERIAL -> "%.0f mi".format(km * MILES_PER_KM)
        NAUTICAL -> "%.0f NM".format(km * NM_PER_KM)
        else -> "%.0f km".format(km)
    }

    /** The number alone, for callers that write the unit themselves. */
    fun distanceValue(km: Double, sys: String): Double = when (normalize(sys)) {
        IMPERIAL -> km * MILES_PER_KM
        NAUTICAL -> km * NM_PER_KM
        else -> km
    }

    /**
     * A height given in metres. Nautical uses feet, as aviation charts and
     * bulletins do.
     */
    fun altitude(m: Double, sys: String): String = when (normalize(sys)) {
        METRIC -> "%.0f m".format(m)
        else -> "%.0f ft".format(m * FEET_PER_METER)
    }

    /** A short length in metres (radius, margin, GPS accuracy). */
    fun shortDistance(m: Double, sys: String): String = when (normalize(sys)) {
        METRIC -> "%.0f m".format(m)
        else -> "%.0f ft".format(m * FEET_PER_METER)
    }

    /**
     * A ground speed given in m/s: km/h, mph or knots depending on the system.
     */
    fun speed(mps: Double, sys: String): String = when (normalize(sys)) {
        IMPERIAL -> "%.0f mph".format(mps * 3.6 * MILES_PER_KM)
        NAUTICAL -> "%.0f kt".format(mps * KNOTS_PER_MPS)
        else -> "%.0f km/h".format(mps * 3.6)
    }

    /**
     * A vertical speed given in m/s. Keeps its sign: that is what says a balloon
     * has just burst.
     */
    fun vertical(mps: Double, sys: String): String = when (normalize(sys)) {
        METRIC -> "%+.1f m/s".format(mps)
        else -> "%+.1f ft/s".format(mps * FEET_PER_METER)
    }
}
