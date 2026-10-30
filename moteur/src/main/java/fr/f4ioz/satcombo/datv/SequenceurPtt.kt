/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import kotlin.concurrent.thread

/**
 * An output that goes active while the RF is on: a relay, a sequencer, a
 * preamplifier's bypass, an amplifier's PTT. What drives it is behind this
 * interface: nothing, a serial line (RTS or DTR), an HTTP request to a box
 * on the network — and, the day the antenna box (ESP32) speaks MQTT, a
 * publication there, without touching [SequenceurPtt].
 *
 * Only an output: nothing here ever starts a transmission.
 */
interface SortiePtt {
    /** What it is, for the page. */
    val nom: String

    /** The output active ([actif]) or back at rest: null once done, else why not. */
    fun pose(actif: Boolean): String?

    /** Lets go of what it holds (a port): at rest first. */
    fun ferme() {}

    /** No output (the default). */
    object Aucune : SortiePtt {
        override val nom = "aucune"
        override fun pose(actif: Boolean): String? = null
    }
}

/**
 * The order around the RF, always the same: **the output active, [avantMs]
 * waited, then the RF**; **the RF off, [apresMs] waited, then the output at
 * rest** — a relay never switches with RF on it.
 *
 * - [avantRf] before the transmitter starts: when the output cannot go
 *   active, it is put back at rest and the start refused (where the RF
 *   would go is not known).
 * - [apresRf] once the transmitter stopped: the rest comes [apresMs] later,
 *   off the caller's thread ([differe]); a new start meanwhile keeps it
 *   active (the pending rest dropped).
 * - [relacheMaintenant]: at rest at once, no delay (a start that failed
 *   before any RF, SatMe-PC closing).
 *
 * With [SortiePtt.Aucune] nothing is waited: the transmission as before.
 */
class SequenceurPtt(
    /** The output as set now (Réglages may change it between two transmissions). */
    private val sortie: () -> SortiePtt,
    private val avantMs: () -> Long,
    private val apresMs: () -> Long,
    private val attente: (Long) -> Unit = { Thread.sleep(it) },
    /** Runs the delayed rest (a thread; at once in the tests). */
    private val differe: (() -> Unit) -> Unit = { f -> thread(isDaemon = true, name = "satme-ptt-repos") { f() } },
) {
    /** The output active now, and the last error (empty: none). */
    @Volatile var actif = false; private set
    @Volatile var erreur = ""; private set
    @Volatile var nomEnCours = ""; private set

    private var enCours: SortiePtt? = null
    private var generation = 0

    /**
     * The output made active and [avantMs] waited: null then (the RF may
     * start), else why not (the output put back at rest).
     */
    fun avantRf(): String? {
        val s = sortie()
        synchronized(this) {
            generation++
            // Another output still waiting for its rest (Réglages changed): at rest first.
            enCours?.takeIf { it !== s }?.let { runCatching { it.pose(false) } }
            enCours = null
            if (s === SortiePtt.Aucune) { actif = false; nomEnCours = ""; return null }
            val err = runCatching { s.pose(true) }.getOrElse { it.message ?: "erreur" }
            if (err != null) {
                runCatching { s.pose(false) }
                actif = false; erreur = err
                return "sortie PTT (" + s.nom + ") : " + err
            }
            enCours = s; actif = true; erreur = ""; nomEnCours = s.nom
        }
        val d = borne(avantMs())
        if (d > 0) attente(d)
        return null
    }

    /** The RF is off: the output at rest [apresMs] later (unless a new start comes first). */
    fun apresRf() {
        val (s, g) = synchronized(this) { (enCours ?: return) to generation }
        differe {
            val d = borne(apresMs())
            if (d > 0) attente(d)
            synchronized(this) {
                if (generation != g || enCours !== s) return@synchronized
                erreur = runCatching { s.pose(false) }.getOrElse { it.message ?: "erreur" }.orEmpty()
                actif = false; enCours = null
            }
        }
    }

    /** At rest now, no delay: no RF was sent, or SatMe-PC closes. */
    @Synchronized
    fun relacheMaintenant() {
        generation++
        val s = enCours ?: return
        erreur = runCatching { s.pose(false) }.getOrElse { it.message ?: "erreur" }.orEmpty()
        actif = false; enCours = null
    }

    companion object {
        /** A delay before or after the RF (ms): 0 to [DELAI_MAX_MS]. */
        const val DELAI_MAX_MS = 5_000L
        fun borne(ms: Long): Long = ms.coerceIn(0L, DELAI_MAX_MS)
    }
}
