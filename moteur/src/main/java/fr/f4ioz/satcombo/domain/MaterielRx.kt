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
 * Per-receiver frequency error, in ppm.
 *
 * Seen in the field: the same measurement (reference WebSDR minus displayed
 * frequency) gives a different LO on the FT-817 and on the SDR dongle, with a
 * single LNB.
 *
 * **The difference is in the receiver, not the converter.** Each receiver has
 * its own reference, and it is off: a few ppm for an FT-817, tens of ppm for a
 * cheap SDR dongle. Folding that into the station chain mixes two independent
 * errors, and correcting one shifts the other.
 *
 * **Why ppm, not Hz.** Oscillator error is a proportion: 2 ppm is 288 Hz at
 * 144 MHz and 864 Hz at 432 MHz. A value in Hz would only be right at the
 * frequency where it was measured, including after changing downconverter.
 *
 * **No effect on LEO satellites by default.** The error defaults to zero and
 * FT-817 A is the reference by convention, so behaviour is unchanged until a
 * measurement exists. Once measured, it applies wherever the receiver is used —
 * a crystal is not wrong only on QO-100.
 */
object MaterielRx {

    /**
     * A receiver and its error, measured **for the receiver** against a
     * reference. Positive when the receiver displays less than the truth, i.e.
     * when it must be asked a higher frequency to land in the right place.
     */
    data class Materiel(
        val nom: String,
        val ppm: Double = 0.0,
        /** True for the one used as the standard; its error stays zero. */
        val reference: Boolean = false,
    )

    /**
     * First-launch defaults. The first FT-817 is the reference (the most
     * stable, and a fixed point is needed): without a declared reference, a
     * first measurement cannot separate LNB error from receiver error.
     */
    fun parDefaut(): List<Materiel> = listOf(
        Materiel("FT-817 A", 0.0, reference = true),
        Materiel("FT-817 B"),
        Materiel("Clé SDR 1"),
        Materiel("Clé SDR 2"),
    )

    /**
     * Beyond this it is a typo, not a crystal: 100 ppm is 14 kHz at 144 MHz,
     * no commercial receiver would work, and accepting it would shift
     * frequencies for no visible reason.
     */
    const val PPM_MAX = 100.0

    fun credible(ppm: Double): Boolean = ppm.isFinite() && kotlin.math.abs(ppm) <= PPM_MAX

    /**
     * What the receiver must be set to in order to really be on [freqHz].
     *
     * Applies to the frequency the receiver tunes — the IF behind a converter,
     * not the sky frequency — since that is where its crystal works.
     */
    fun corrige(freqHz: Long, ppm: Double): Long {
        if (!credible(ppm) || ppm == 0.0 || freqHz <= 0L) return freqHz
        return freqHz + Math.round(freqHz * ppm / 1_000_000.0)
    }

    /** The inverse: what the receiver displays, brought back to the truth. */
    fun redresse(afficheHz: Long, ppm: Double): Long {
        if (!credible(ppm) || ppm == 0.0 || afficheHz <= 0L) return afficheHz
        return Math.round(afficheHz / (1.0 + ppm / 1_000_000.0))
    }

    /**
     * Error from a measurement: [attenduHz] is what the receiver should have
     * displayed (the reference, brought to IF), [luHz] what it displays.
     * Returns `null` for an absurd measurement: storing nothing beats storing a
     * number that shifts everything.
     */
    fun ppmDepuisMesure(attenduHz: Long, luHz: Long): Double? {
        if (attenduHz <= 0L || luHz <= 0L) return null
        val ppm = (luHz - attenduHz) * 1_000_000.0 / attenduHz
        return if (credible(ppm)) ppm else null
    }

    /** Stores the value under this name, never touching the reference. */
    fun range(liste: List<Materiel>, nom: String, ppm: Double): List<Materiel> =
        liste.map {
            if (it.nom == nom && !it.reference) it.copy(ppm = ppm) else it
        }

    fun choisi(liste: List<Materiel>, nom: String): Materiel =
        liste.firstOrNull { it.nom == nom }
            ?: liste.firstOrNull { it.reference }
            ?: parDefaut().first()

    // ------------------------------------------------------------ persistence
    //
    // Plain text, not JSON: the domain must not depend on Android, and
    // `org.json` does. On the JVM test bench `org.json` is an empty stub, so
    // persistence failed there silently.
    //
    // One line per receiver, three fields separated by '|'. The separator is
    // stripped from names, or the list would be unreadable on next start.

    private const val SEP = '|'

    fun ecrit(liste: List<Materiel>): String =
        liste.joinToString("\n") { m ->
            "${m.nom.replace(SEP, ' ').replace('\n', ' ')}$SEP${m.ppm}$SEP${m.reference}"
        }

    /** An empty or unreadable list gives the defaults, never nothing. */
    fun lit(texte: String): List<Materiel> {
        val out = texte.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ligne ->
                val p = ligne.split(SEP)
                if (p.size < 3) return@mapNotNull null
                val nom = p[0].trim().ifBlank { return@mapNotNull null }
                val ppm = p[1].trim().toDoubleOrNull() ?: 0.0
                Materiel(
                    nom = nom,
                    // A damaged value must not shift frequencies: zero if in doubt.
                    ppm = if (credible(ppm)) ppm else 0.0,
                    reference = p[2].trim().equals("true", ignoreCase = true))
            }
            .toList()
        return out.ifEmpty { parDefaut() }
    }
}
