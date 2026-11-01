/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.BandPlan

/**
 * A receive tuning of the IC-9700 asked by another program on this PC
 * (`POST /api/cat/accord`) between passes: a frequency, a mode — the data
 * variants (USB-D, LSB-D, FM-D) as SatMe sets them, 06 then 1A 06 — on
 * MAIN or SUB. Receive only: nothing here keys the rig, nor touches its
 * transmit side (no PTT, no power, no tone, no split, no satellite mode); a
 * parameter that would is refused by name rather than ignored.
 */
object AccordPostePc {

    /** [civ]: the CI-V mode byte; [donnees]: its data variant. */
    data class Mode(val nom: String, val civ: Int, val donnees: Boolean)

    data class Demande(val hz: Long?, val mode: Mode?, val sub: Boolean)

    /** The modes taken, by name (Hamlib's PKT… names too); « -D » the data variant. */
    val MODES: Map<String, Mode> = linkedMapOf(
        "LSB" to Mode("LSB", 0x00, false), "USB" to Mode("USB", 0x01, false), "AM" to Mode("AM", 0x02, false),
        "CW" to Mode("CW", 0x03, false), "CW-R" to Mode("CW-R", 0x07, false), "FM" to Mode("FM", 0x05, false),
        "LSB-D" to Mode("LSB-D", 0x00, true), "USB-D" to Mode("USB-D", 0x01, true), "FM-D" to Mode("FM-D", 0x05, true),
    ).also { m -> m["PKTLSB"] = m["LSB-D"]!!; m["PKTUSB"] = m["USB-D"]!!; m["PKTFM"] = m["FM-D"]!!; m["CWR"] = m["CW-R"]!! }

    /** What a receive tuning never takes: each would touch the transmit side. */
    val INTERDITS = setOf("ptt", "tx", "emission", "montee", "uplink", "puissance", "power", "ton", "tone", "ctcss",
        "split", "satellite", "sat", "duplex", "decalage")

    /** The request read, or why not. */
    fun lit(q: Map<String, String>): Result<Demande> {
        q.keys.firstOrNull { it.lowercase() in INTERDITS }?.let {
            return Result.failure(IllegalArgumentException("réception seule : « $it » refusé (rien ici ne touche à l'émission)"))
        }
        val hz = q["hz"]?.let { v ->
            v.trim().toLongOrNull() ?: return Result.failure(IllegalArgumentException("fréquence illisible : $v"))
        }
        if (hz != null && BandPlan.band(hz) == BandPlan.Band.AUTRE)
            return Result.failure(IllegalArgumentException("fréquence hors des bandes de l'IC-9700 (144, 430, 1200 MHz) : $hz Hz"))
        val mode = q["mode"]?.takeIf { it.isNotBlank() }?.let { v ->
            MODES[v.trim().uppercase()] ?: return Result.failure(IllegalArgumentException("mode inconnu : $v (LSB, USB, AM, CW, CW-R, FM, LSB-D, USB-D, FM-D)"))
        }
        val sub = when (q["bande"]?.trim()?.lowercase()) {
            null, "", "main" -> false
            "sub" -> true
            else -> return Result.failure(IllegalArgumentException("bande : main ou sub"))
        }
        if (hz == null && mode == null) return Result.failure(IllegalArgumentException("rien à accorder : hz et/ou mode"))
        return Result.success(Demande(hz, mode, sub))
    }
}
