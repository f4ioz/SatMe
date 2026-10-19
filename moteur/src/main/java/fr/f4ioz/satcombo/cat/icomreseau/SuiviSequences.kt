/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

/**
 * The radio's numbered packets on one channel (control or CI-V): which
 * arrived, which are missing (asked again), which came twice (dropped — a
 * CI-V reply delivered twice would be taken as the answer to the next
 * question). Idles count: the radio numbers them with its frames.
 */
class SuiviRecus {
    private var attendu = -1
    /** Missing sequences and when first missed. */
    private val trous = LinkedHashMap<Int, Long>()
    private val vus = ArrayDeque<Int>()

    @Volatile var manques = 0L; private set
    @Volatile var doubles = 0L; private set

    private fun ecart(a: Int, b: Int): Int { val d = (b - a) and 0xFFFF; return if (d >= 0x8000) d - 0x10000 else d }

    /** True when the packet is new (to be used), false when already seen. */
    @Synchronized fun recois(seq: Int, maintenant: Long = System.currentTimeMillis()): Boolean {
        if (attendu < 0) { attendu = (seq + 1) and 0xFFFF; retiens(seq); return true }
        val d = ecart(attendu, seq)
        when {
            d == 0 -> attendu = (seq + 1) and 0xFFFF
            d in 1..64 -> {
                var s = attendu
                while (s != seq) { trous[s] = maintenant; manques++; s = (s + 1) and 0xFFFF }
                attendu = (seq + 1) and 0xFFFF
            }
            d > 64 || d < -256 -> { trous.clear(); attendu = (seq + 1) and 0xFFFF }   // restarted count
            else -> {
                // Behind: a hole filled, or a repeat.
                if (trous.remove(seq) == null && seq in vus) { doubles++; return false }
            }
        }
        retiens(seq)
        return true
    }

    private fun retiens(seq: Int) { vus.addLast(seq); if (vus.size > 128) vus.removeFirst() }

    /** The holes worth asking for again (younger than [ageMaxMs]); older ones are given up. */
    @Synchronized fun aRedemander(maintenant: Long = System.currentTimeMillis(), ageMaxMs: Long = 1_000L): List<Int> {
        trous.entries.removeAll { maintenant - it.value > ageMaxMs }
        return trous.keys.take(16)
    }

    @Synchronized fun remetAZero() { attendu = -1; trous.clear(); vus.clear() }
}

/** Our own numbered packets, kept a little while so the radio can ask for one again. */
class ReserveEnvoyes(private val profondeur: Int = 64) {
    private val paquets = LinkedHashMap<Int, ByteArray>()
    @Volatile var renvoyes = 0L; private set

    @Synchronized fun garde(seq: Int, b: ByteArray) {
        paquets[seq and 0xFFFF] = b
        while (paquets.size > profondeur) paquets.remove(paquets.keys.first())
    }

    @Synchronized fun cherche(seq: Int): ByteArray? = paquets[seq and 0xFFFF]?.also { renvoyes++ }
}
