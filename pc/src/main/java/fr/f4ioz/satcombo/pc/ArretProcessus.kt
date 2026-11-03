/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Stopping a helper process (ffmpeg, ffplay, VLC…) that a thread may be
 * feeding through its stdin, without ever waiting on that stream.
 *
 * A write to a process that no longer reads blocks for ever and holds the
 * stream's lock: closing the stream then — or [Process.destroy], which closes
 * it — waits for ever too. Here the process is only signalled first
 * ([ProcessHandle.destroy], then [ProcessHandle.destroyForcibly] after
 * [delaiMs]); once it is gone the blocked write fails (broken pipe) and lets
 * the stream go; only then are its streams closed. All but the first signal
 * happen on a thread of its own: the caller (often under a lock) never waits.
 */
object ArretProcessus {
    /** The process signalled now, killed after [delaiMs] if still there, its streams closed after; the thread doing it. */
    fun arrete(p: Process, delaiMs: Long = 1_000L): Thread {
        val h = runCatching { p.toHandle() }.getOrNull()
        runCatching { h?.destroy() }
        return thread(isDaemon = true, name = "satme-arret-processus") { finit(p, h, delaiMs) }
    }

    /**
     * For a helper that ends cleanly at the end of its input (a recording, a
     * question-and-answer helper): its stdin closed on a thread of its own,
     * [attenteMs] given to finish, then [arrete]. The caller waits at most
     * [attenteMs] — never on the stream.
     */
    fun fermePuisArrete(p: Process, attenteMs: Long = 1_500L, delaiMs: Long = 1_000L) {
        thread(isDaemon = true, name = "satme-arret-entree") { runCatching { p.outputStream.close() } }
        if (runCatching { p.waitFor(attenteMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)) { arrete(p, 0L); return }
        arrete(p, delaiMs)
    }

    private fun finit(p: Process, h: ProcessHandle?, delaiMs: Long) {
        val parti = runCatching { p.waitFor(delaiMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!parti) {
            runCatching { h?.destroyForcibly() ?: p.destroyForcibly() }
            runCatching { p.waitFor(5, TimeUnit.SECONDS) }
        }
        // Gone: a write still blocked in its stdin has failed by now; the streams are free.
        runCatching { p.outputStream.close() }
        runCatching { p.inputStream.close() }
        runCatching { p.errorStream.close() }
    }
}

/**
 * Stopping each part of a program in turn, none waited for longer than
 * [delaiMs], all of them together no longer than [totalMs]: a part stuck
 * (a lock never let go) is left behind and the next one stops all the same.
 */
class ArretBorne(private val delaiMs: Long = 5_000L, totalMs: Long = 30_000L, private val dit: (String) -> Unit = { System.err.println(it) }) {
    private val finMs = System.currentTimeMillis() + totalMs

    /** Runs [bloc] on a thread of its own; true when it ended in time. */
    fun fais(nom: String, bloc: () -> Unit): Boolean {
        val t = thread(isDaemon = true, name = "satme-arret-$nom") { runCatching { bloc() }.onFailure { dit("arrêt de $nom : " + (it.message ?: it.toString())) } }
        val attente = minOf(delaiMs, maxOf(200L, finMs - System.currentTimeMillis()))
        runCatching { t.join(attente) }
        if (t.isAlive) { dit("arrêt de $nom : pas fini en $attente ms, on continue"); return false }
        return true
    }
}
