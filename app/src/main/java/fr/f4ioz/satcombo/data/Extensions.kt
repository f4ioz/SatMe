/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

/**
 * Keyring for optional features.
 *
 * Features written and bench-tested but not yet proven on a real pass used to
 * appear only for whoever typed the matching word in the Settings
 * "Extensions" field. Almost everything is now in [OPEN]; the mechanism stays
 * whole (field, keywords, master words) so the next unproven feature can go
 * in [ALL] without [OPEN], with no UI to rewrite.
 *
 * The author's callsign no longer unlocks anything: it made him the only
 * operator who never saw the app as others do.
 *
 * A switch, not protection: the words are in clear in the APK. The point is
 * that nobody stumbles on an unfinished feature and thinks it broken.
 *
 * No Android import: the logic is JVM-tested, since a mistake here would
 * silently lock features out.
 */
object Extensions {

    /** SSTV decoding (live and from file). */
    const val SSTV = "sstv"

    /** RTL-SDR dongle over USB. */
    const val SDR = "sdr"

    /**
     * APT decoding: NOAA weather images on 137 MHz.
     *
     * **Closed again in 20.47, by the author's decision.** The keyword is "noaa",
     * the name an operator looks for, not the format's; the constant keeps its
     * code name because screens test it by name.
     *
     * Careful: the image folder is still "apt" (`AptHub`). That is a disk path,
     * not a keyword, and must not change or received images become unreachable.
     */
    const val APT = "noaa"

    /** Small flag before the callsign on the QRV photo. */
    const val FLAG = "drapeau"

    /**
     * The two Breton flags in the QRV photo lists. A matter of relevance, not
     * maturity: they are not national flags. In [OPEN] by the author's choice,
     * so shown to everyone; removing it from [OPEN] would restore the key.
     */
    const val BZH = "bzh"

    /**
     * Weather radiosonde decoding. Tested on synthetic signals, not yet on a
     * real balloon flight.
     */
    const val SONDE = "sonde"

    /**
     * Azimuth/elevation rotor control.
     *
     * The first SatMe feature that moves something heavy: a wrong display is
     * fixed next release, a mast turning the wrong way pulls on cables for a
     * full minute. Bench-tested against a simulated controller only, never a
     * real G-5500 on a tower.
     */
    const val ROTOR = "rotor"

    // The "adif" key guarded the bundled callsign database, removed in 20.42.
    // A key guarding nothing is a dormant trap, so it went too.

    /**
     * The QO-100 screen. Relies on a chain not yet proven end to end: two
     * converters, a crossed frequency pair, fixed pointing and beacon lock.
     * Each piece is bench-tested, but only a real downconverter whose LO
     * drifts at power-up can validate the whole.
     */
    const val QO100 = "qo100"

    /**
     * APRS reception: AFSK 1200 frames (ISS digipeater, 145.825 MHz) decoded
     * while recording. Checked on the WA8LMF reference recordings, not yet
     * on a real ISS pass: its page says so.
     */
    const val APRS = "aprs"

    /**
     * METEOR-M pictures (LRPT) through the SDR dongle. Checked on a recording
     * of a real METEOR-M2-4 pass, not yet live: its page says so.
     */
    const val METEOR = "meteor"

    /** Every known extension, in display order. */
    val ALL: List<String> = listOf(SSTV, SDR, APT, METEOR, FLAG, BZH, SONDE, ROTOR, QO100, APRS)

    /**
     * Open to everyone, no key needed.
     *
     * **Everything, since 18.48.** Once published, a feature hidden behind an
     * undocumented word is not cautious, it is unfindable. Warning banners on
     * each screen now say what has and has not been proven — read at the
     * moment of use, which is more honest than a key.
     *
     * Entering [OPEN] is final: do not take away a feature operators rely on.
     *
     * **One exception: NOAA images**, closed again in 20.47 by deliberate
     * choice. Type "noaa" in Extensions to get them.
     */
    val OPEN: Set<String> = (ALL - APT).toSet()

    /** Master words that unlock everything, French and English. */
    private val MASTER = setOf("all", "tout", "toutes", "beta", "bêta", "*")

    /**
     * Extensions unlocked for this operator: always [OPEN], plus whatever the
     * field unlocks.
     *
     * @param callsign callsign from settings (no longer used)
     * @param code content of the "Extensions" field (may be empty)
     */
    fun unlocked(callsign: String, code: String): Set<String> {
        val words = tokens(code)
        // Author's choice (18.75): master words unlock EVERYTHING, NOAA
        // included.
        if (words.any { it in MASTER }) return ALL.toSet()
        return OPEN + ALL.filter { it in words }
    }

    /** Shortcut for screens that test a single feature. */
    fun isUnlocked(name: String, callsign: String, code: String): Boolean =
        name in unlocked(callsign, code)

    /**
     * Splits the field into words, accepting any reasonable separator
     * (space, comma, semicolon, plus, slash): bad punctuation must not hide a
     * feature.
     */
    private fun tokens(code: String): Set<String> =
        code.lowercase()
            .split(' ', ',', ';', '+', '/', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
