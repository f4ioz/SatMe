/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

/**
 * DVB-S2 (ETSI EN 302 307-1), what a transmitter of one transport stream
 * needs: the code rates, the constellations, the MODCODs and their frame
 * sizes, the transport stream rate a MODCOD carries at a symbol rate.
 */

/** The LDPC code rates (table 5a/5b), with their constants. */
enum class Taux(val libelle: String, val num: Int, val den: Int) {
    R1_4("1/4", 1, 4), R1_3("1/3", 1, 3), R2_5("2/5", 2, 5), R1_2("1/2", 1, 2), R3_5("3/5", 3, 5), R2_3("2/3", 2, 3),
    R3_4("3/4", 3, 4), R4_5("4/5", 4, 5), R5_6("5/6", 5, 6), R8_9("8/9", 8, 9), R9_10("9/10", 9, 10);

    companion object {
        fun de(s: String): Taux? = entries.firstOrNull { it.libelle == s.trim() }
    }
}

/** QPSK, or 8PSK (with its bit interleaver). */
enum class Constellation(val libelle: String, val bits: Int) {
    QPSK("QPSK", 2), PSK8("8PSK", 3);

    companion object {
        fun de(s: String): Constellation? = entries.firstOrNull { it.libelle.equals(s.trim(), true) || it.name.equals(s.trim(), true) }
    }
}

/** A MODCOD with its frame: [courte] = 16 200-bit FECFRAME, [pilotes] = pilot blocks. */
data class Modcod(
    val constellation: Constellation,
    val taux: Taux,
    val courte: Boolean = false,
    val pilotes: Boolean = false,
) {
    private val i get() = taux.ordinal

    /** Why this MODCOD does not exist, or null. */
    fun refus(): String? = when {
        courte && taux == Taux.R9_10 -> "le taux 9/10 n'existe pas en trames courtes"
        constellation == Constellation.PSK8 && (taux.ordinal < Taux.R3_5.ordinal || taux == Taux.R4_5) ->
            "en 8PSK, taux 3/5, 2/3, 3/4, 5/6, 8/9 et 9/10 seulement"
        else -> null
    }

    /** Table 12's MODCOD field. */
    val code: Int get() = when (constellation) {
        Constellation.QPSK -> 1 + i
        Constellation.PSK8 -> 12 + (i - Taux.R3_5.ordinal) - (if (taux.ordinal > Taux.R3_4.ordinal) 1 else 0)
    }

    /** The PLHEADER's TYPE field: frame size (MSB), pilots (LSB). */
    val type: Int get() = (if (courte) 2 else 0) or (if (pilotes) 1 else 0)

    val nldpc: Int get() = if (courte) 16_200 else 64_800
    /** kldpc = Nbch. */
    val kldpc: Int get() = if (courte) KLDPC_COURTE[i] else KLDPC_NORMALE[i]
    val kbch: Int get() = if (courte) KBCH_COURTE[i] else KBCH_NORMALE[i]
    /** BCH correction power t. */
    val t: Int get() = if (courte) 12 else T_NORMALE[i]
    /** Table 7a/7b's q: (nldpc − kldpc) / 360. */
    val q: Int get() = (nldpc - kldpc) / 360
    /** DATA FIELD length in bits when the frame is full (broadcast slicing). */
    val dfl: Int get() = kbch - 80
    /** Slots of 90 symbols in the XFECFRAME. */
    val slots: Int get() = nldpc / constellation.bits / 90
    /** PLFRAME length in symbols, header and pilots included. */
    val symboles: Int get() = 90 * (slots + 1) + (if (pilotes) 36 * ((slots - 1) / 16) else 0)

    /** The transport stream rate (bit/s, all 188 bytes counted) carried at [rs] symbols/s, frames full. */
    fun debitTs(rs: Double): Double = rs * dfl / symboles

    val libelle: String get() = constellation.libelle + " " + taux.libelle + (if (courte) " courtes" else "") + (if (pilotes) " pilotes" else "")

    companion object {
        val KBCH_NORMALE = intArrayOf(16008, 21408, 25728, 32208, 38688, 43040, 48408, 51648, 53840, 57472, 58192)
        val KLDPC_NORMALE = intArrayOf(16200, 21600, 25920, 32400, 38880, 43200, 48600, 51840, 54000, 57600, 58320)
        val T_NORMALE = intArrayOf(12, 12, 12, 12, 12, 10, 12, 12, 10, 8, 8)
        val KBCH_COURTE = intArrayOf(3072, 5232, 6312, 7032, 9552, 10632, 11712, 12432, 13152, 14232, 0)
        val KLDPC_COURTE = intArrayOf(3240, 5400, 6480, 7200, 9720, 10800, 11880, 12600, 13320, 14400, 0)

        /** Every MODCOD this transmitter makes (QPSK all rates, 8PSK 3/5 to 9/10). */
        fun tous(courte: Boolean, pilotes: Boolean): List<Modcod> =
            Constellation.entries.flatMap { c -> Taux.entries.map { Modcod(c, it, courte, pilotes) } }.filter { it.refus() == null }

        /** "QPSK 1/2" (and the frame), or null. */
        fun de(texte: String, courte: Boolean = false, pilotes: Boolean = false): Modcod? {
            val p = texte.trim().split(Regex("[\\s_]+"))
            if (p.size != 2) return null
            val m = Modcod(Constellation.de(p[0]) ?: return null, Taux.de(p[1]) ?: return null, courte, pilotes)
            return m.takeIf { it.refus() == null }
        }
    }
}

/** The transmit roll-off factors (table 3's RO field). */
enum class Rolloff(val alpha: Double, val ro: Int, val libelle: String) {
    A35(0.35, 0, "0,35"), A25(0.25, 1, "0,25"), A20(0.20, 2, "0,20");

    companion object {
        fun de(a: Double): Rolloff? = entries.firstOrNull { kotlin.math.abs(it.alpha - a) < 1e-6 }
    }
}
