/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.audio

import java.util.Locale

/**
 * The sound a capture lost, kept to be told: each loss (when, how long,
 * where — the card's buffer overrun, or the decoders too slow behind the
 * capture — and whether it was filled with silence), the last hours of them.
 * « Son perdu : 19,7 s pendant ce passage — PC surchargé (charge 34) ».
 */
class SonPerdu(private val garderMs: Long = 6 * 3_600_000L) {

    enum class Cause(val cle: String) {
        /** The card's buffer ran over: the capture thread was not given the processor in time. */
        CARTE("carte"),
        /** The capture went on but the decoders fell too far behind: blocks dropped between them. */
        FILE("file"),
    }

    /** One loss: when it was seen (UTC ms), how long (ms), why, filled with silence or not (a cut). */
    data class Perte(val t: Long, val ms: Long, val cause: Cause, val comble: Boolean)

    /** What is told: losses, their total, the last one (UTC ms, 0: none). */
    data class Bilan(val n: Int, val ms: Long, val derniereMs: Long, val coupures: Int = 0) {
        val rien: Boolean get() = n == 0
    }

    private val pertes = ArrayDeque<Perte>()
    private var total = Bilan(0, 0L, 0L)

    @Synchronized fun note(p: Perte) {
        pertes.addLast(p)
        total = Bilan(total.n + 1, total.ms + p.ms, p.t, total.coupures + if (p.comble) 0 else 1)
        while (pertes.isNotEmpty() && pertes.first().t < p.t - garderMs) pertes.removeFirst()
    }

    /** Since the capture started. */
    @Synchronized fun total(): Bilan = total

    /** Those seen in [de]..[a] (UTC ms). */
    @Synchronized fun entre(de: Long, a: Long = Long.MAX_VALUE): Bilan {
        val l = pertes.filter { it.t in de..a }
        return Bilan(l.size, l.sumOf { it.ms }, l.maxOfOrNull { it.t } ?: 0L, l.count { !it.comble })
    }

    @Synchronized fun liste(): List<Perte> = pertes.toList()

    @Synchronized fun remet() { pertes.clear(); total = Bilan(0, 0L, 0L) }

    companion object {
        private val FR = Locale.FRANCE

        /** « 19,7 s », « 850 ms », « 2 min 05 s ». */
        fun duree(ms: Long): String = when {
            ms < 1_000L -> "$ms ms"
            ms < 60_000L -> String.format(FR, "%.1f s", ms / 1000.0)
            else -> String.format(FR, "%d min %02d s", ms / 60_000L, (ms / 1000L) % 60L)
        }

        /** The 1-minute load from `/proc/loadavg` ("24.75 25.92 …"), null when unreadable. */
        fun charge(loadavg: String?): Double? = loadavg?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toDoubleOrNull()

        /** « PC surchargé (charge 34) » when the load is over the processors, else « (charge 3,1) »; "" unknown. */
        fun cause(charge: Double?, coeurs: Int): String = when {
            charge == null -> ""
            charge > coeurs -> "PC surchargé (charge " + chiffre(charge) + " pour $coeurs cœurs)"
            else -> "charge " + chiffre(charge)
        }

        private fun chiffre(x: Double) = if (x >= 10) Math.round(x).toString() else String.format(FR, "%.1f", x)

        /**
         * « son perdu : 19,7 s pendant ce passage (12 coupures) — PC surchargé (charge 34 pour 12 cœurs) »;
         * [ou]: « pendant ce passage », « en 15 min ».
         */
        fun texte(b: Bilan, ou: String, charge: Double?, coeurs: Int): String {
            val k = if (b.n > 1) " (${b.n} trous" + (if (b.coupures > 0) ", dont ${b.coupures} trop longs pour être comblés" else "") + ")" else
                if (b.coupures > 0) " (trop long pour être comblé)" else ""
            val c = cause(charge, coeurs)
            return "son perdu : " + duree(b.ms) + " " + ou + k + (if (c.isNotEmpty()) " — $c" else "")
        }

        /** The SSTV picture card's words for its sidecar's sonPerdu (ms): « son perdu pendant l'image : 1,2 s »; "" none. */
        fun pendantImage(ms: Long): String = if (ms <= 0L) "" else "son perdu pendant l'image : " + duree(ms)
    }
}
