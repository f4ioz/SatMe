/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A live player's input (ffmpeg's, ffplay's, VLC's stdin) fed on a thread of
 * its own through a short queue: [donne] never waits, the oldest blocks are
 * lost when the player falls behind (live video: better lost than late).
 * A player that stops reading only holds its own thread, and [bloqueeDepuisMs]
 * says since when, for the watchdog.
 */
class EntreeLecteurPc(private val o: OutputStream, nom: String, capacite: Int = 256, private val horloge: () -> Long = System::currentTimeMillis) {
    private val file = ArrayBlockingQueue<ByteArray>(capacite)
    @Volatile private var ecritDepuisMs = 0L
    @Volatile var finie = false; private set
    /** Blocks lost because the player did not keep up. */
    @Volatile var perdus = 0L; private set

    init {
        thread(isDaemon = true, name = "satme-entree-$nom") {
            try {
                while (!finie) {
                    val b = file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    ecritDepuisMs = horloge()
                    o.write(b); o.flush()
                    ecritDepuisMs = 0L
                }
            } catch (_: Exception) {
                // The player gone (broken pipe): nothing more to write.
            } finally { finie = true; ecritDepuisMs = 0L; file.clear() }
        }
    }

    fun donne(b: ByteArray) {
        if (finie) return
        if (!file.offer(b)) { file.poll(); perdus++; file.offer(b) }
    }

    /** Since when a write has been waiting for the player (it reads no more), or null. */
    fun bloqueeDepuisMs(): Long? = ecritDepuisMs.takeIf { it > 0L }

    /** No more blocks; the stream itself is not touched (its process is stopped by [ArretProcessus]). */
    fun ferme() { finie = true; file.clear() }
}
