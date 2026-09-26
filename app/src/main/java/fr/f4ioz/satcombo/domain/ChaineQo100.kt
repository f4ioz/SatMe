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
 * Converter chains of the QO-100 station: a downconverter, an upconverter and
 * a name. Kept here rather than in general settings because they only make
 * sense for QO-100 (a 10 345 MHz LNB means nothing on RS-44).
 *
 * **Why several.** Home and portable stations have different converters, each
 * with its own measured oscillator error. Retyping values on every site change
 * means getting it wrong one day — and 300 kHz off on QO-100 means hearing
 * nothing at all.
 */
object ChaineQo100 {

    /**
     * A complete chain, as wired.
     *
     * Oscillators are in Hz and **measured, not nominal**: that is the point of
     * storing them. Zero means no converter on that side (e.g. an SDR dongle
     * fed straight from the LNB output).
     */
    data class Chaine(
        val nom: String = "",
        val descenteOlHz: Long = 0L,
        val monteeOlHz: Long = 0L,
    ) {
        val descenteActive: Boolean get() = descenteOlHz > 0L
        val monteeActive: Boolean get() = monteeOlHz > 0L

        /** Frequency the radio will show on receive. */
        fun posteRx(cielHz: Long): Long =
            if (descenteActive) cielHz - descenteOlHz else cielHz

        /** Frequency the radio will show on transmit. */
        fun posteTx(monteeHz: Long): Long =
            if (monteeActive) monteeHz - monteeOlHz else monteeHz
    }

    /**
     * Local oscillator derived from **two observed frequencies**: the same
     * signal heard on a reference WebSDR and on your own radio. The difference
     * is the chain's LO — no need to know the nominal LNB value or trust the
     * datasheet. The operator copies two numbers; the subtraction never gets
     * the sign wrong.
     */
    fun olMesure(cielHz: Long, posteHz: Long): Long = cielHz - posteHz

    /** Plausible downlink LO range: 9 to 11 GHz. */
    private val DESCENTE = 9_000_000_000L..11_000_000_000L

    /** Plausible uplink LO range: 1.5 to 2.4 GHz. */
    private val MONTEE = 1_500_000_000L..2_400_000_000L

    /**
     * Is this measurement credible?
     *
     * Do not reject a value for being far from nominal — that offset is exactly
     * what we measure, and it can reach several hundred kHz. Reject only what
     * cannot be an oscillator: swapped fields, a misplaced decimal, MHz typed
     * for kHz.
     *
     * A wrong but plausible LO is not caught here; it shows up by ear when
     * looking for the beacon. That is why the screen must show the resulting
     * frequency, not just "OK".
     */
    fun descenteCredible(olHz: Long): Boolean = olHz in DESCENTE

    fun monteeCredible(olHz: Long): Boolean = olHz in MONTEE

    /**
     * Default chains, to be filled by measurement.
     *
     * **Names only, no values:** nominal oscillators would look correct while
     * every unit has its own error. An empty field asking for a measurement
     * beats a plausible number never checked.
     */
    val PAR_DEFAUT: List<Chaine> = listOf(
        Chaine(nom = "Fixe"),
        Chaine(nom = "Portable"),
    )

    /** Stores a chain by name; appends it if new. */
    fun range(liste: List<Chaine>, chaine: Chaine): List<Chaine> {
        val i = liste.indexOfFirst { it.nom.equals(chaine.nom, ignoreCase = true) }
        return if (i < 0) liste + chaine
        else liste.toMutableList().also { it[i] = chaine }
    }

    /** The chain with this name, else the first, else an empty chain. */
    fun choisie(liste: List<Chaine>, nom: String): Chaine =
        liste.firstOrNull { it.nom.equals(nom, ignoreCase = true) }
            ?: liste.firstOrNull()
            ?: Chaine()
}
