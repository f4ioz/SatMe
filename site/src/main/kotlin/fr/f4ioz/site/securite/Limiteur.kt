/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.securite

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * At most [limite] events per key (a visitor's address) in [fenetreMs]: a
 * sliding window, in memory (forgotten at a restart, which suits a brake).
 * Keys idle for a whole window are dropped as others come.
 */
class Limiteur(val limite: Int, val fenetreMs: Long, private val horloge: () -> Long = System::currentTimeMillis) {

    private val fenetres = ConcurrentHashMap<String, ArrayDeque<Long>>()
    @Volatile private var nettoye = 0L

    /** Counts one event for [cle]; false when it is one too many (the event is not counted then). */
    fun accepte(cle: String): Boolean {
        val t = horloge()
        menage(t)
        val q = fenetres.computeIfAbsent(cle) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && q.peekFirst() <= t - fenetreMs) q.pollFirst()
            if (q.size >= limite) return false
            q.addLast(t)
            return true
        }
    }

    /** How many events [cle] has in the window now (without counting one). */
    fun nombre(cle: String): Int {
        val t = horloge()
        val q = fenetres[cle] ?: return 0
        synchronized(q) {
            while (q.isNotEmpty() && q.peekFirst() <= t - fenetreMs) q.pollFirst()
            return q.size
        }
    }

    fun oublie(cle: String) { fenetres.remove(cle) }

    private fun menage(t: Long) {
        if (t - nettoye < 60_000L || fenetres.size < 2000) return
        nettoye = t
        fenetres.entries.removeIf { (_, q) -> synchronized(q) { q.isEmpty() || q.peekLast() <= t - fenetreMs } }
    }
}
