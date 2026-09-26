/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

/**
 * Gatekeeper for the MP3 encoder.
 *
 * ### Why this file exists
 *
 * The bundled LAME Java wrapper (`com.naman14.androidlame.AndroidLame`)
 * declares **all** its native methods `static`: `initialize`, `lameEncode`,
 * `lameFlush`, `lameClose`. Behind them sits one global in the native library
 * (symbol `glf` in the `.bss` of `libandroidlame.so`). However many
 * `AndroidLame` objects exist, there is **only one encoder per process**.
 *
 * With two users, the fault is silent until fatal: the second `build()` resets
 * the first one's encoder, and the first `close()` frees the second's. The next
 * `encode()` works on freed memory and the process dies in
 * `lame_encode_mp3_frame` → `format_bitstream`, with no Kotlin frame on top
 * (seen in Play Console on a Galaxy A35). The crash handler cannot catch it: a
 * SIGSEGV never goes through `Thread.setDefaultUncaughtExceptionHandler`.
 *
 * ### What it does and does not do
 *
 * It does not make the library reentrant (that would need a rebuild with
 * per-instance state). It ensures only one caller holds it at a time.
 *
 * It **refuses** the second caller rather than making it wait, on purpose: a
 * pass recording or an SDR session holds the encoder for ten minutes, and an
 * export dialog blocked that long is just a slower failure. An immediate
 * refusal can be stated in one sentence on screen.
 *
 * No Android, no LAME here: it only hands out a token, so it runs in plain unit
 * tests where the native library does not load.
 */
object EncodeurMp3 {

    /** The possible holders, named once. */
    const val ENREGISTREUR = "enregistrement"
    const val SDR = "SDR"
    const val MIRE_SSTV = "mire SSTV"
    const val MIRE_SONDE = "mire sonde"

    private val verrou = Any()
    private var occupant: String? = null

    /** Who holds the encoder, or `null` when free. */
    val occupePar: String? get() = synchronized(verrou) { occupant }

    /** True when nobody holds it. Informational only: see [prend]. */
    val libre: Boolean get() = occupePar == null

    /**
     * Takes the encoder for [qui], or returns `false` if already taken.
     *
     * Test and take share one synchronized block, so two simultaneous callers
     * cannot both see it free. That is why [libre] must never be used to
     * decide — only to inform.
     */
    fun prend(qui: String): Boolean = synchronized(verrou) {
        if (occupant != null) false else { occupant = qui; true }
    }

    fun rend(qui: String) = synchronized(verrou) {
        if (occupant == qui) occupant = null
    }

    /**
     * Runs [bloc] with the encoder, or returns `null` doing nothing if taken.
     *
     * For short uses that start and end in one place (test-pattern exports).
     * The pass recorder and SDR take and release in different places, so they
     * call [prend] and [rend] directly.
     *
     * Released in `finally`: an exception mid-export must not lock the encoder
     * until the next app start.
     */
    fun <T> avec(qui: String, bloc: () -> T): T? {
        if (!prend(qui)) return null
        return try { bloc() } finally { rend(qui) }
    }

    /**
     * Frees the encoder whoever holds it.
     *
     * Tests only: in production, a holder that never releases is a bug to fix,
     * not to work around.
     */
    internal fun forceLibere() = synchronized(verrou) { occupant = null }
}
