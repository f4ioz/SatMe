/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * What a Mode S reply says, read from its bits: the extended squitters
 * (DF17 from a transponder, DF18 from other equipment) by their type code,
 * and the short replies that carry an altitude or an identity.
 *
 * - TC 1–4 identification (callsign, category)
 * - TC 5–8 surface position (movement, track, CPR on 90°)
 * - TC 9–18 airborne position, barometric altitude
 * - TC 19 airborne velocity (ground speed or airspeed and heading, vertical rate)
 * - TC 20–22 airborne position, GNSS height
 */
sealed class MessageAdsb {
    abstract val icao: Int

    data class Identification(override val icao: Int, val indicatif: String, val categorie: String) : MessageAdsb()

    data class Position(override val icao: Int, val surface: Boolean, val impair: Boolean, val latCpr: Int, val lonCpr: Int,
                        /** Feet; barometric unless [gnss]. Null on the surface or when not sent. */
                        val altitudePieds: Int?, val gnss: Boolean,
                        /** Surface only: knots and degrees, when sent. */
                        val vitesseNoeuds: Double? = null, val routeDeg: Double? = null) : MessageAdsb()

    data class Vitesse(override val icao: Int, val vitesseNoeuds: Double?, val routeDeg: Double?,
                       /** Airspeed rather than ground speed (subtypes 3 and 4); then [routeDeg] is the heading. */
                       val air: Boolean, val vertical: Int?) : MessageAdsb()

    /** A short or long reply giving the altitude (DF 0, 4, 16, 20). */
    data class Altitude(override val icao: Int, val altitudePieds: Int?) : MessageAdsb()

    /** A reply giving the identity code (DF 5, 21). */
    data class Identite(override val icao: Int, val squawk: String) : MessageAdsb()

    /** Seen but nothing more to read (DF11 all-call, other type codes). */
    data class Autre(override val icao: Int, val df: Int, val tc: Int) : MessageAdsb()

    companion object {

        /**
         * Reads [b] ([nBits] long, its parity already checked or recovered):
         * [icaoParite] is the address taken out of the parity for the
         * formats that overlay it (null for DF11/17/18, which carry it).
         */
        fun lis(b: ByteArray, nBits: Int, icaoParite: Int? = null): MessageAdsb? {
            val df = ModeS.bits(b, 0, 5).toInt()
            return when (df) {
                17, 18 -> {
                    if (nBits != ModeS.LONG) return null
                    // DF18 with CF ≠ 0 or 6 carries an anonymous or non-ICAO address: still a 24-bit key.
                    val aa = ModeS.bits(b, 8, 24).toInt()
                    squitter(b, aa)
                }
                11 -> Autre(ModeS.bits(b, 8, 24).toInt(), 11, 0)
                0, 4, 16, 20 -> icaoParite?.let { Altitude(it, ModeS.altitude13(ModeS.bits(b, 19, 13).toInt())) }
                5, 21 -> icaoParite?.let { Identite(it, ModeS.squawk(ModeS.bits(b, 19, 13).toInt())) }
                else -> null
            }
        }

        private fun me(b: ByteArray, de: Int, n: Int): Int = ModeS.bits(b, 32 + de, n).toInt()

        private fun squitter(b: ByteArray, aa: Int): MessageAdsb {
            val tc = me(b, 0, 5)
            return when (tc) {
                in 1..4 -> {
                    val cat = me(b, 5, 3)
                    val s = StringBuilder()
                    for (k in 0 until 8) s.append(ModeS.caractere(me(b, 8 + 6 * k, 6)))
                    Identification(aa, s.toString().replace("#", "").trim(), categorie(tc, cat))
                }
                in 5..8 -> {
                    val mov = me(b, 5, 7)
                    val s = me(b, 12, 1)
                    val trk = me(b, 13, 7)
                    Position(aa, true, me(b, 21, 1) == 1, me(b, 22, 17), me(b, 39, 17), null, false,
                        mouvement(mov), if (s == 1) trk * 360.0 / 128.0 else null)
                }
                in 9..18, in 20..22 -> {
                    val alt = me(b, 8, 12)
                    val gnss = tc >= 20
                    Position(aa, false, me(b, 21, 1) == 1, me(b, 22, 17), me(b, 39, 17),
                        // GNSS height: the same 12-bit coding as the barometric one, in feet.
                        ModeS.altitude12(alt), gnss)
                }
                19 -> vitesse(b, aa)
                else -> Autre(aa, ModeS.bits(b, 0, 5).toInt(), tc)
            }
        }

        private fun vitesse(b: ByteArray, aa: Int): MessageAdsb {
            val st = me(b, 5, 3)
            val vr = me(b, 37, 9)
            val vertical = if (vr == 0) null else (vr - 1) * 64 * (if (me(b, 36, 1) == 1) -1 else 1)
            return when (st) {
                1, 2 -> {
                    val f = if (st == 2) 4 else 1
                    val ew = me(b, 14, 10); val ns = me(b, 25, 10)
                    if (ew == 0 || ns == 0) return Vitesse(aa, null, null, false, vertical)
                    val vew = (ew - 1) * f * (if (me(b, 13, 1) == 1) -1 else 1)
                    val vns = (ns - 1) * f * (if (me(b, 24, 1) == 1) -1 else 1)
                    val v = hypot(vew.toDouble(), vns.toDouble())
                    var trk = Math.toDegrees(atan2(vew.toDouble(), vns.toDouble()))
                    if (trk < 0) trk += 360.0
                    Vitesse(aa, v, trk, false, vertical)
                }
                3, 4 -> {
                    val cap = if (me(b, 13, 1) == 1) me(b, 14, 10) * 360.0 / 1024.0 else null
                    val as10 = me(b, 25, 10)
                    val v = if (as10 == 0) null else (as10 - 1) * (if (st == 4) 4.0 else 1.0)
                    Vitesse(aa, v, cap, true, vertical)
                }
                else -> Autre(aa, 17, 19)
            }
        }

        /** The surface movement field in knots (null: no information). */
        fun mouvement(m: Int): Double? = when (m) {
            0 -> null
            1 -> 0.0
            in 2..8 -> 0.125 + (m - 2) * 0.875 / 6.0
            in 9..12 -> 1.0 + (m - 9) * 0.25
            in 13..38 -> 2.0 + (m - 13) * 0.5
            in 39..93 -> 15.0 + (m - 39) * 1.0
            in 94..108 -> 70.0 + (m - 94) * 2.0
            in 109..123 -> 100.0 + (m - 109) * 5.0
            124 -> 175.0
            else -> null
        }

        /** The emitter category, in words (TC 1–4 × CA 0–7). */
        fun categorie(tc: Int, ca: Int): String = if (ca == 0) "" else when (tc) {
            4 -> listOf("", "léger", "petit", "moyen", "gros porteur (tourbillons)", "lourd", "très manœuvrant", "hélicoptère")[ca]
            3 -> listOf("", "planeur", "aérostat", "parachutiste", "ULM", "", "drone", "spatial")[ca]
            2 -> listOf("", "véhicule d'urgence", "véhicule de service", "obstacle ponctuel", "obstacles groupés", "obstacle linéaire", "", "")[ca]
            else -> ""
        }
    }
}
