/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import fr.f4ioz.satcombo.cat.CatDecode

/**
 * What the network link refuses to carry to the rig: anything that keys
 * the transmitter. Receive only for now: reading, tuning and modes (the
 * Doppler loop's writes, as over USB) pass; PTT does not.
 *
 * Refused, by CI-V command:
 * - `1C 00` with a value other than 00 — transmit on (reading the TX state, no value, passes);
 * - `1C 01` with a value — the antenna tuner, whose tuning transmits;
 * - `17` — send a CW message;
 * - `28` — play a TX voice memory;
 * - the same behind the `29` band prefix.
 */
object GardeEmission {

    /** Why [trame] (one CI-V frame, FE FE … FD) is refused, null when it may go. */
    fun refus(trame: ByteArray): String? {
        if (trame.size < 6) return null
        val fin = if ((trame.last().toInt() and 0xFF) == CatDecode.END) trame.size - 1 else trame.size
        var i = 4
        var cmd = trame[i].toInt() and 0xFF
        // 29 <band> <cmd> …: the command to one band, judged as the command itself.
        if (cmd == 0x29 && fin > i + 2) { i += 2; cmd = trame[i].toInt() and 0xFF }
        val donnees = trame.copyOfRange(minOf(i + 1, fin), fin)
        return when (cmd) {
            0x1C -> when {
                donnees.isEmpty() -> null
                (donnees[0].toInt() and 0xFF) == 0x00 && donnees.size >= 2 && (donnees[1].toInt() and 0xFF) != 0x00 ->
                    "émission (CI-V 1C 00) refusée par la liaison réseau : réception seule"
                (donnees[0].toInt() and 0xFF) == 0x01 && donnees.size >= 2 ->
                    "boîte d'accord (CI-V 1C 01) refusée par la liaison réseau : elle émet pour s'accorder"
                else -> null
            }
            0x17 -> "message CW (CI-V 17) refusé par la liaison réseau : réception seule"
            0x28 -> "mémoire vocale TX (CI-V 28) refusée par la liaison réseau : réception seule"
            else -> null
        }
    }

    /** The first refusal among the frames [octets] carries, null when all may go. */
    fun refusDans(octets: ByteArray): String? =
        CatDecode.splitCiv(octets).firstNotNullOfOrNull { refus(it) }
}
