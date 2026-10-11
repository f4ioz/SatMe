/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

/**
 * Common interface for satellite-capable rig control over USB serial.
 *
 * The IC-9700 (CivController) is the first implementation. The design below is
 * shaped so the Yaesu rigs can slot in later with minimal change to the VM:
 *
 *  FT-847  — binary CAT, hex values, NULL-MODEM (crossed) cable. MUST send a
 *            "CAT ON" enable command (00 00 00 00 00) before any command, and
 *            "CAT OFF" (00 00 00 00 80) when done. Native satellite mode with
 *            12 sat VFOs and Normal/Inverted tracking. PITFALL: the CAT *offset*
 *            command is limited to 10 kHz resolution — do NOT use it for fine
 *            Doppler; instead write the main RX VFO directly (10 Hz resolution)
 *            and write the TX VFO directly too (same approach as the IC-9700:
 *            absolute frequencies, not offsets). Full-duplex crossband.
 *
 *  FT-817/818 — CAT always on (no enable needed). 5-byte frames: 4 parameter
 *            bytes + 1 command byte. HALF-DUPLEX, so for satellite work you use
 *            a PAIR: one rig fixed on RX (downlink), one on TX (uplink), each
 *            driven on its own serial port. TX is calibrated by ear (whistle
 *            until you hear yourself), like SatPC32.
 *
 *  IC-9700 — CI-V, full duplex, MAIN(selected)=downlink, SUB(unselected)=uplink.
 *            In satellite mode the rig refuses 0x25/0x26 on SUB: select the
 *            band (0x07 D0/D1), then write with 0x05 (see setSatellitePair).
 */
interface RigDriver {
    val name: String

    /** Open the serial link at the given baud. Returns success. */
    suspend fun open(baud: Int): Boolean
    fun close()
    val isOpen: Boolean

    /** Put the rig into satellite/cross-band mode (no-op if not applicable). */
    suspend fun enterSatelliteMode() {}

    /** Set the operating modes for downlink and uplink (e.g. "FM","USB","LSB","CW"). */
    suspend fun setModes(downlink: String, uplink: String) {}

    /** Read the downlink (RX) frequency the operator is tuned to, or null. */
    suspend fun readDownlink(): Long?

    /** Write downlink + uplink as a pair (FM birds: both Doppler-corrected). */
    suspend fun setPair(downlinkHz: Long, uplinkHz: Long)

    /** Write only the uplink (linear: operator keeps the RX dial). */
    suspend fun setUplink(uplinkHz: Long)

    /** CTCSS tone for FM uplink (tenths of Hz, 0 = off). */
    suspend fun setCtcss(tenthHz: Int) {}
}

/** Maps a transmitter mode string to a normalized mode label. */
fun normalizeMode(mode: String?, isUplink: Boolean, invert: Boolean, isTransponder: Boolean): String {
    val m = mode?.uppercase() ?: ""
    return when {
        fr.f4ioz.satcombo.domain.ModeRadio.surFm(m) -> "FM"
        m.contains("CW") -> "CW"
        isTransponder && invert -> if (isUplink) "LSB" else "USB"
        m.contains("LSB") -> "LSB"
        else -> "USB"
    }
}
