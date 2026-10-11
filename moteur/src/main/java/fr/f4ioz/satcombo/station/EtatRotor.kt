/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

/**
 * The mast as the station knows it: settings, connection, where it points,
 * where it goes, why it waits. Kept by [StationRotor]; the phone shows it as
 * it is (same field names as before the move).
 */
data class EtatRotor(
    val rotorEnabled: Boolean = false,
    val rotorConnected: Boolean = false,
    val rotorStatus: String = "",
    val rotorType: String = "GS232",
    val rotorBaud: Int = 9600,
    val rotorUsbIndex: Int = 0,
    val rotorPort: Int = 4533,
    val rotorMaxAz: Int = 450,
    val rotorMaxEl: Int = 90,
    /** Where the mast's end stop is: "NORTH" or "SOUTH". */
    val rotorAzStop: String = "NORTH",
    /** Does the controller count from its end stop rather than true north? */
    val rotorAzFromStop: Boolean = false,
    /** Pointing error tolerated before the satellite is considered lost. */
    val rotorMaxError: Int = 15,
    val rotorMinEl: Int = 0,
    val rotorFlip: Boolean = false,
    val rotorAzOnly: Boolean = false,
    /** Minutes before AOS at which the mast goes to wait at the rise point. */
    val rotorPreAos: Int = 3,
    /** True while the mast waits for the satellite at its rise point. */
    val rotorPrePositioning: Boolean = false,
    val rotorPark: Boolean = true,
    val rotorParkAz: Int = 0,
    val rotorParkEl: Int = 0,
    val rotorSimulated: Boolean = false,
    val rotorPosAz: Double? = null,
    val rotorPosEl: Double? = null,
    val rotorFlipped: Boolean = false,
    val rotorMoves: Int = 0,
    val rotorDevices: List<String> = emptyList(),
    /** Share of the current pass actually reachable, 0 to 1. */
    val rotorCoverage: Double? = null,
    val rotorLink: String = "GS232",
    val rotorHost: String = "192.168.1.10",
    val rotorDeadband: Int = 4,
    val rotorSim: Boolean = false,
    val rotorTargetAz: Double? = null,
    val rotorTargetEl: Double? = null,
    val rotorActualAz: Double? = null,
    val rotorActualEl: Double? = null,
    /**
     * Where the antenna points, as the compass must show it: azimuth wrapped
     * into one turn, elevation flip undone. Null when the mast says nothing —
     * the compass falls back to the phone. [rotorAimEl] stays null on an
     * azimuth-only rotor.
     */
    val rotorAimAz: Double? = null,
    val rotorAimEl: Double? = null,
    val rotorOutOfRange: Boolean = false,

    /**
     * Log of the last connection attempt, line by line: which port was tried,
     * which refused permission, which opened and never answered.
     */
    val rotorDiag: List<String> = emptyList(),

    /**
     * Try the other ports when the chosen one does not answer: as for the rig,
     * the port index depends on plug-in order, which nobody controls.
     */
    val rotorAutoPort: Boolean = true,

    /** Manual azimuth, for testing without a satellite. */
    val rotorManualAz: Int = 0,

    /** Manual elevation, for testing without a satellite. */
    val rotorManualEl: Int = 0,

    /** Last frame received from the controller, verbatim — empty if silent. */
    val rotorLastReply: String = "",

    /** Last frame sent to the controller, verbatim. */
    val rotorLastSent: String = "",
    /** Gap between the target and the reachable point, degrees. */
    val rotorErrorDeg: Double = 0.0,
    /** Going round the end stop: what is happening or waited for ("" when nothing). */
    val rotorDeroule: String = "",
    /** Held by a transmission: shown as a warning. */
    val rotorDerouleAlerte: Boolean = false,
    /** When this pass will cross the stop beyond the dead zone (a turn), null when it will not. */
    val rotorDerouleAt: Long? = null,
    /** The last command, unwrapped (420 = 60 on the overlap): where the cable is, for the dial. */
    val rotorCmdAz: Double? = null,
    /**
     * The antennas' planned path (az 0..360, el 0..90 as seen on the sky) when the pass
     * crosses the stop; empty otherwise (they follow the satellite).
     */
    val rotorChemin: List<Pair<Double, Double>> = emptyList(),
    /** The mast's path over the pass, as sky points: the planned path, or the plain follow. */
    val rotorTrajet: List<Pair<Double, Double>> = emptyList(),
    /** The largest miss on that path, and when; null without a path. */
    val rotorEcartMax: Double? = null,
    val rotorEcartMaxAt: Long? = null,
    /** How long a full turn takes at the measured speed, seconds. */
    val rotorTourS: Int = 0
)
