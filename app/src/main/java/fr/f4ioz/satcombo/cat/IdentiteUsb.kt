/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import kotlin.math.abs

/**
 * Identifying a serial adapter that has no serial number.
 *
 * Pitfall: matching adapters only by USB serial number fails on a PL2303TA,
 * which has none (only the newer HXD/GC/GS carry `iSerialNumber`; FTDI always
 * does). The cable was recognised by the driver yet unusable.
 *
 * So: an identity that always exists, and — when two identical cables are
 * plugged in for duplex — a method that ignores labels entirely: ask each rig
 * its frequency, and the answer tells which is which.
 */
object IdentiteUsb {

    /**
     * Key under which an adapter is remembered.
     *
     * The serial number when present (existing FTDI settings keep working).
     * Otherwise vendor/product plus position in the USB tree.
     *
     * The second form is stable only while nothing is unplugged, and two
     * identical cables can swap between plug-ins. It finds an adapter; it never
     * guarantees it is the right rig.
     */
    fun cle(serie: String?, vid: Int, pid: Int, position: String): String {
        val s = serie?.trim().orEmpty()
        return if (s.isNotEmpty()) s
        else "%04X:%04X@%s".format(vid, pid, position)
    }

    /** Does this key denote an adapter without a serial number? */
    fun sansNumeroDeSerie(cle: String): Boolean = cle.contains('@') && cle.contains(':')

    /**
     * Finds an adapter among candidates.
     *
     * Exact key first. Then, **if there is only one candidate, take it**:
     * refusing the only cable because its USB position changed would be the
     * very bug being fixed.
     */
    fun resout(cleMemorisee: String?, candidats: List<String>): String? {
        if (candidats.isEmpty()) return null
        if (cleMemorisee != null && candidats.contains(cleMemorisee)) return cleMemorisee
        if (candidats.size == 1) return candidats.first()
        return null
    }

    // ------------------------------------------- which is RX, which is TX

    /**
     * What an adapter answered when asked its frequency. Null [freqHz] means
     * nothing readable: no rig on the line, or wrong baud rate.
     */
    class Sonde(val cle: String, val freqHz: Long?)

    /** The proposed assignment. */
    class Attribution(val rx: String?, val tx: String?, val certaine: Boolean)

    /**
     * Assigns adapters from what the rigs answered.
     *
     * In duplex the two rigs are on different bands: RX on the downlink, TX on
     * the uplink, both known from the transponder. The frequency read gives the
     * role unambiguously; a USB tree position says nothing.
     *
     * [certaine] is false when both answer in the same band or only one answers:
     * we propose, the operator decides by seeing the frequencies — something he
     * understands, unlike "USB serial (1)" and "USB serial (2)".
     */
    fun attribue(
        sondes: List<Sonde>,
        descenteHz: Long?,
        monteeHz: Long?,
        toleranceHz: Long = 2_000_000L,
    ): Attribution {
        val repondeurs = sondes.filter { it.freqHz != null }
        if (repondeurs.isEmpty()) return Attribution(null, null, false)

        if (descenteHz == null || monteeHz == null) {
            return Attribution(repondeurs.getOrNull(0)?.cle, repondeurs.getOrNull(1)?.cle, false)
        }

        fun pres(f: Long, cible: Long) = abs(f - cible) <= toleranceHz

        val versRx = repondeurs.filter { pres(it.freqHz!!, descenteHz) }
        val versTx = repondeurs.filter { pres(it.freqHz!!, monteeHz) }

        // Clear case: one rig in each band, and they differ.
        if (versRx.size == 1 && versTx.size == 1 && versRx[0].cle != versTx[0].cle) {
            return Attribution(versRx[0].cle, versTx[0].cle, true)
        }

        // Otherwise propose without claiming: the operator will see the frequencies.
        return Attribution(
            rx = versRx.firstOrNull()?.cle ?: repondeurs.getOrNull(0)?.cle,
            tx = versTx.firstOrNull()?.cle
                ?: repondeurs.firstOrNull { it.cle != versRx.firstOrNull()?.cle }?.cle,
            certaine = false)
    }

    /**
     * Is a frequency read plausible for a portable amateur rig?
     *
     * Tells a real answer from noise bytes parsed as a frequency. The FT-817
     * covers 100 kHz to 470 MHz; outside that, the rig didn't speak.
     */
    fun freqPlausible(hz: Long): Boolean = hz in 100_000L..470_000_000L
}
