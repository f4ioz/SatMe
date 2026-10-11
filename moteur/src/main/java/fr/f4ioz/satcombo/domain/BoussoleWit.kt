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
 * The WitMotion attitude module protocol, reduced to what an antenna compass
 * needs.
 *
 * The module (WT9011DCL-BT50) fuses gyro, accelerometer and magnetometer on
 * chip and returns a tilt-compensated attitude. That is the point: a bare
 * magnetometer is only right when flat, and an antenna boom never is.
 *
 * No Bluetooth, no Android here: bytes in, angles out, testable on the bench.
 */

/** GATT identifiers of the module, taken from the datasheet. */
object GattWit {
    /**
     * Mind the base: WitMotion uses `...-00805f9a34fb`, with **9a** where the
     * standard Bluetooth base has **9b**. Not a typo in their docs — it is
     * what the module advertises, and the standard base would find nothing.
     */
    const val SERVICE = "0000ffe5-0000-1000-8000-00805f9a34fb"
    const val NOTIFICATION = "0000ffe4-0000-1000-8000-00805f9a34fb"
    const val ECRITURE = "0000ffe9-0000-1000-8000-00805f9a34fb"

    /** Subscription descriptor, this one fully standard. */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** Names advertised by the family: WT901BLE, WT9011DCL… */
    fun nomPlausible(nom: String?): Boolean {
        val n = nom?.uppercase() ?: return false
        return n.startsWith("WT") || n.contains("WITMOTION") || n.contains("HWT")
    }
}

/** An attitude, in degrees. Only yaw is used. */
data class AttitudeWit(
    val roulis: Float,
    val tangage: Float,
    val lacet: Float
)

/** What a received frame can be. */
sealed class TrameWit {
    data class Attitude(val valeur: AttitudeWit) : TrameWit()
    /** A known frame of no use here (magnetic field, quaternion…). */
    data class Ignoree(val drapeau: Int) : TrameWit()
}

object BoussoleWit {

    const val ENTETE = 0x55
    const val DRAPEAU_ATTITUDE = 0x61
    const val LONGUEUR = 20

    /**
     * Other flags the module may send. Same length; recognising them avoids
     * taking them for noise and resyncing in the middle of a valid frame.
     */
    private val DRAPEAUX_CONNUS = setOf(0x51, 0x52, 0x53, 0x59, 0x61, 0x71)

    private fun entier16(bas: Byte, haut: Byte): Int {
        val v = (haut.toInt() and 0xFF shl 8) or (bas.toInt() and 0xFF)
        return if (v >= 32768) v - 65536 else v
    }

    /** `angle = raw / 32768 × 180°`, per the datasheet. */
    private fun angle(bas: Byte, haut: Byte): Float =
        entier16(bas, haut) / 32768f * 180f

    /**
     * Reads **one** attitude frame at index [debut]; `null` on a bad header or
     * missing bytes.
     *
     * Frame 0x61 has **no checksum** (unlike 0x53), so header and length are
     * the only guard. That is why [Accumulateur] resyncs rather than insists.
     */
    fun litAttitude(octets: ByteArray, debut: Int = 0): AttitudeWit? {
        if (debut + LONGUEUR > octets.size) return null
        if ((octets[debut].toInt() and 0xFF) != ENTETE) return null
        if ((octets[debut + 1].toInt() and 0xFF) != DRAPEAU_ATTITUDE) return null
        // 2..7 acceleration, 8..13 angular rate, 14..19 angles.
        return AttitudeWit(
            roulis = angle(octets[debut + 14], octets[debut + 15]),
            tangage = angle(octets[debut + 16], octets[debut + 17]),
            lacet = angle(octets[debut + 18], octets[debut + 19])
        )
    }

    /**
     * Module yaw as a compass azimuth: 0–360°, north up.
     *
     * **Offset** [offsetDeg]: the module's zero is not the antenna's north.
     * It depends on how the box is fixed on the boom and changes whenever it
     * is remounted (the datasheet requires a calibration on every mount).
     *
     * **Direction** [inverse]: the module counts in ENU, positive
     * counter-clockwise, while azimuth runs clockwise. Depending on firmware
     * and box orientation, yaw may follow azimuth or oppose it. **Do not
     * guess**: the operator turns the antenna a quarter turn right, and if the
     * number drops, he ticks the box.
     *
     * Declination is not applied here: SatMe applies it downstream, doing it
     * twice would double it.
     */
    fun azimutDepuisLacet(lacetDeg: Float, offsetDeg: Float = 0f, inverse: Boolean = false): Float {
        val signe = if (inverse) -lacetDeg else lacetDeg
        var a = (signe + offsetDeg) % 360f
        if (a < 0f) a += 360f
        return a
    }

    /**
     * Offset from a reading: the antenna points at known azimuth
     * [azimutVrai], the module says [lacetDeg]; this is what to add.
     *
     * Returned in −180..180 so a small error reads as a small number: "−3°"
     * is checked at a glance, "357°" gets argued with.
     */
    fun calageDepuisReleve(lacetDeg: Float, azimutVrai: Float, inverse: Boolean = false): Float {
        val signe = if (inverse) -lacetDeg else lacetDeg
        var d = (azimutVrai - signe) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    // ---- Old per-angle reading: removed ----
    //
    // It took yaw as azimuth and one of the other two angles as elevation.
    // Right in horizontal polarisation only: as soon as the boom rotates on
    // its axis, elevation migrates from one angle to the other. `PointageAntenne`
    // answers the same question from the boresight vector, immune to
    // polarisation. Do not bring back a second, diverging answer.

    /**
     * Buffer that reassembles frames.
     *
     * A BLE notification does not carry exactly one frame: depending on the
     * negotiated MTU it may split one or deliver two. Accumulate, then cut.
     *
     * With no checksum, a stray 0x55 in noise can look like a header. So we
     * advance by **one byte** when a header leads nowhere, not twenty: a false
     * start costs one frame, not the sync.
     */
    class Accumulateur(private val plafond: Int = 256) {
        private val tampon = ArrayList<Byte>(plafond)

        /** What the buffer holds, for tests. */
        val enAttente: Int get() = tampon.size

        fun vide() { tampon.clear() }

        /** Pours [morceau] in and returns the complete attitudes it yielded. */
        fun verse(morceau: ByteArray): List<AttitudeWit> {
            for (b in morceau) tampon.add(b)
            // A growing buffer is one that fails to resync: cap it.
            while (tampon.size > plafond) tampon.removeAt(0)

            val sorties = ArrayList<AttitudeWit>()
            var i = 0
            while (i + LONGUEUR <= tampon.size) {
                if ((tampon[i].toInt() and 0xFF) != ENTETE) { i++; continue }
                val drapeau = tampon[i + 1].toInt() and 0xFF
                if (drapeau !in DRAPEAUX_CONNUS) { i++; continue }
                if (drapeau == DRAPEAU_ATTITUDE) {
                    val octets = ByteArray(LONGUEUR) { k -> tampon[i + k] }
                    val a = litAttitude(octets)
                    if (a == null) { i++; continue }
                    sorties.add(a)
                }
                i += LONGUEUR
            }
            // What remains is an incomplete frame or crumbs.
            repeat(i) { tampon.removeAt(0) }
            return sorties
        }
    }

    /**
     * Heading smoothing.
     *
     * The module claims 0.2° and holds it, but a hand-held antenna shakes.
     * Same first-order filter as the phone sensors, along the shortest arc so
     * 359° → 1° does not spin the needle a full turn.
     */
    fun lisse(precedent: Float, nouveau: Float, k: Float = 0.25f): Float {
        if (precedent.isNaN()) return nouveau
        var d = ((nouveau - precedent + 540f) % 360f) - 180f
        var r = (precedent + k * d) % 360f
        if (r < 0f) r += 360f
        return r
    }
}
