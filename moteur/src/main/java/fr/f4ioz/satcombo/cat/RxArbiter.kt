/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import kotlin.math.abs

/**
 * Who holds the RX VFO: the operator or the software.
 *
 * On a linear transponder both are right in turn. While the operator tunes,
 * nothing must fight the dial. Once he stops, his frequency becomes a
 * *satellite* frequency and the software must hold it, moving the VFO with
 * Doppler — otherwise the station drifts out of the filter within a minute.
 *
 * Pitfall (fixed in 18.17): RX was read constantly but never written, so the
 * rest frequency, recomputed from a still VFO, drifted by the full Doppler and
 * the uplink moved the wrong way instead.
 *
 * The core difficulty: the rig doesn't say *who* moved the VFO. Reading back
 * our own write looks exactly like an operator gesture. Hence the memory of
 * recent commands: a read matching one of them is ours.
 *
 * No Android here on purpose: this is the only part of the CAT chain that can
 * be tested without a radio.
 */
class RxArbiter(
    /** Below this, a difference is rig rounding, not a gesture. */
    private val moveHz: Long = 20L,
    /** Quiet time required after the last gesture before taking over. */
    private var holdMs: Long = 2_000L,
    /** Quiet reads required on top of the quiet time. */
    private var stableSamples: Int = 8,
    /** How many recent commands stay recognisable. */
    private val memory: Int = 8
) {

    /** True when the software sets the RX frequency. */
    var driven: Boolean = true
        private set

    private val commands = ArrayList<Long>()
    private var lastObserved = 0L
    private var lastMoveMs = 0L
    private var stable = 0

    /** Consecutive quiet reads so far (for display). */
    val stableCount: Int get() = stable

    /**
     * Changes the takeover delay at runtime.
     *
     * Both values move **together**: the quiet time is always paired with a
     * count of quiet reads. Setting 0.5 s while still waiting for eight reads
     * would change nothing — at the rig's poll rate those reads often last
     * longer. A setting with no effect is worse than no setting.
     */
    fun regle(nouveauHoldMs: Long) {
        holdMs = nouveauHoldMs.coerceIn(200L, 5_000L)
        stableSamples = echantillonsPour(holdMs)
    }

    companion object {
        /**
         * Quiet reads required for a delay: one per 250 ms, at least two — a
         * single quiet read may just be the gap between two dial clicks.
         */
        fun echantillonsPour(holdMs: Long): Int =
            (holdMs / 250L).toInt().coerceAtLeast(2)
    }

    /** Reset: new pass, new satellite, or link reopened. */
    fun reset() {
        // True, not false. After a satellite/transponder change or reconnect,
        // **we** know where to be. Starting operator-driven adopted wherever the
        // rig happened to sit instead of the computed passband centre.
        driven = true
        commands.clear()
        lastObserved = 0L
        lastMoveMs = 0L
        stable = 0
    }

    /** Remember what we just wrote, so reading it back doesn't look like a gesture. */
    fun commanded(hz: Long) {
        commands += hz
        while (commands.size > memory) commands.removeAt(0)
    }

    /**
     * A timestamped read from the rig.
     *
     * @return true if it came from the operator, i.e. no command of ours explains it.
     */
    fun observe(hz: Long, nowMs: Long): Boolean {
        // The very first read is not a gesture. It used to hand control to the
        // operator, and the app then adopted wherever the rig had been left as
        // the channel, ignoring the selected satellite's passband centre.
        if (lastObserved == 0L) {
            lastObserved = hz
            lastMoveMs = nowMs
            stable = 0
            driven = true
            return false
        }
        val notre = commands.any { abs(hz - it) < moveHz }
        val bouge = !notre && abs(hz - lastObserved) >= moveHz
        if (bouge) {
            // Operator took the dial: drop old commands, or slow tuning passing
            // through one of them would be mistaken for ours.
            driven = false
            stable = 0
            lastMoveMs = nowMs
            commands.clear()
        } else {
            stable++
        }
        lastObserved = hz
        if (!driven && stable >= stableSamples && nowMs - lastMoveMs >= holdMs) driven = true
        return bouge
    }
}
