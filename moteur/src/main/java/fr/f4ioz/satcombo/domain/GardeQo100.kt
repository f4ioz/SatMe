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
 * The band plan's guards, wherever SatMe chooses a QO-100 frequency for the
 * operator: the uplink written to the rig, a signal clicked, a memory, the
 * FT8 free spot, a DATV talkback frequency.
 *
 * - **Emergency.** SatMe never lands there on its own: a frequency it
 *   proposes that falls in the emergency segment is heard, but its uplink is
 *   not written to the rig. Tuned by hand, it is written, with a red banner.
 * - **Digital modes stay in the digital segments**: the FT8 free spot is
 *   searched only there.
 * - **A mode out of its segment** (SSB in the CW segment…) gets a hint.
 */
object GardeQo100 {

    /** Who chose the frequency. */
    enum class Origine {
        /** The operator: knob, ruler, typed frequency. */
        MAIN,

        /** SatMe: a signal clicked, a memory, a free spot, a talkback frequency. */
        PROPOSEE,
    }

    /** Is this downlink in the emergency segment? */
    fun urgence(descenteHz: Long): Boolean = Qo100.segment(descenteHz)?.usage == Qo100.Usage.URGENCE

    /**
     * May the uplink for this downlink be written to the rig? Never where the
     * plan forbids transmitting; in the emergency segment only when the
     * operator tuned there himself.
     */
    fun monteeEnvoyable(descenteHz: Long, origine: Origine): Boolean =
        Qo100.emissionAutorisee(descenteHz) && (origine == Origine.MAIN || !urgence(descenteHz))

    /** May SatMe propose this downlink for transmitting? Not on a beacon, not in the emergency segment. */
    fun proposable(descenteHz: Long): Boolean = Qo100.emissionAutorisee(descenteHz) && !urgence(descenteHz)

    /** The digital segments, contiguous ones merged (sky Hz, end excluded). */
    val ZONES_NUMERIQUES: List<LongRange> = Qo100.SEGMENTS.filter { it.usage == Qo100.Usage.NUMERIQUE }
        .fold(emptyList()) { l, s ->
            val d = l.lastOrNull()
            if (d != null && d.last + 1 == s.basHz) l.dropLast(1) + listOf(d.first until s.hautHz)
            else l + listOf(s.basHz until s.hautHz)
        }

    /** Is this downlink in a digital segment? */
    fun numerique(descenteHz: Long): Boolean = ZONES_NUMERIQUES.any { descenteHz in it }

    /**
     * The audio offsets (USB, above [cadranHz]) where a signal [largeurHz]
     * wide stays whole in one digital segment, within
     * [basAudioHz]..[hautAudioHz]: where an FT8 free spot may be proposed.
     * Null: nowhere (the dial is not near the digital segments).
     */
    fun plageAudioNumerique(cadranHz: Long, basAudioHz: Int = 500, hautAudioHz: Int = 2_500, largeurHz: Int = 60): IntRange? {
        val demi = largeurHz / 2
        return ZONES_NUMERIQUES.mapNotNull { z ->
            val a = maxOf(basAudioHz.toLong(), z.first + demi - cadranHz)
            val b = minOf(hautAudioHz.toLong(), z.last - demi - cadranHz)
            if (a <= b) a.toInt()..b.toInt() else null
        }.maxByOrNull { it.last - it.first }
    }

    /** What a log mode is, for the band plan. */
    enum class Famille { PHONIE, CW, NUMERIQUE, IMAGE }

    private val PHONIES = setOf("SSB", "USB", "LSB", "BLU", "AM", "FM", "FREEDV", "DV")
    private val NUMERIQUES = setOf("FT8", "FT4", "JT65", "JT9", "Q65", "FST4", "MSK144", "WSPR", "JS8", "PSK", "PSK31", "PSK63",
        "BPSK31", "QPSK31", "RTTY", "OLIVIA", "CONTESTIA", "MFSK", "THOR", "DOMINO", "HELL", "VARA", "PACKET", "DATA", "DIGI")
    /** Modes wider than the 500 Hz digital segment allows. */
    private val LARGES = setOf("VARA", "PACKET", "SSTV")

    fun famille(mode: String): Famille? = when (val m = mode.trim().uppercase()) {
        "CW" -> Famille.CW
        "SSTV" -> Famille.IMAGE
        in PHONIES -> Famille.PHONIE
        in NUMERIQUES -> Famille.NUMERIQUE
        else -> null
    }

    /** A hint: its translation key and the segment it is about. */
    data class Conseil(val cle: String, val segment: Qo100.Segment)

    /**
     * A hint when [mode] does not fit the segment of [descenteHz] (SSB in the
     * CW segment, FT8 among the SSB…); null when it fits, when the mode is
     * unknown, or where another warning already speaks (beacons, emergency,
     * broadcast).
     */
    fun conseilMode(descenteHz: Long, mode: String): Conseil? {
        val s = Qo100.segment(descenteHz) ?: return null
        val f = famille(mode) ?: return null
        val va = when (s.usage) {
            Qo100.Usage.CW -> f == Famille.CW
            Qo100.Usage.NUMERIQUE -> f == Famille.NUMERIQUE || (f == Famille.IMAGE && s.largeurMaxHz >= 2_700)
            Qo100.Usage.PHONIE -> f == Famille.PHONIE || f == Famille.IMAGE
            else -> true
        }
        if (!va) return Conseil("qo100_conseil_mode", s)
        if (s.largeurMaxHz in 1 until 2_700 && mode.trim().uppercase() in LARGES) return Conseil("qo100_conseil_large", s)
        return null
    }
}
