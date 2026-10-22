/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

/**
 * A SatMe-Node's boards and their three roles — DATV receiver, SDR lent to
 * SatMe-PC, transmitter — read the same way by SatMe-PC and the phone: the
 * boards each role may take, and what two roles on one board mean (the node
 * never lets one board receive and transmit at once, nor serve two receptions).
 */
object RolesNoeud {
    const val AUCUN = "aucun"

    /** The boards each role may take, in the order offered. */
    val DATV = Datv.RECEPTEURS
    val SDR = listOf(AUCUN, Datv.BLADERF, Datv.PLUTO)
    val TX = listOf(AUCUN, Datv.PLUTO, Datv.BLADERF)

    fun nom(a: String): String = when (a) {
        Datv.MINITIOUNER -> "MiniTiouner"; Datv.PLUTO -> "ADALM-PLUTO"; Datv.BLADERF -> "bladeRF 2.0"; else -> "aucune"
    }

    /**
     * What sharing a board between roles costs, in words for the page (empty:
     * each board one role). Not refused here: the node refuses the second use
     * while the first holds the board.
     */
    fun conflits(datv: String, sdr: String, tx: String): List<String> {
        val l = ArrayList<String>()
        fun pris(a: String) = a.isNotBlank() && a != AUCUN
        if (pris(sdr) && sdr == datv) l += nom(sdr) + " en réception DATV et en SDR : un seul des deux à la fois (le second est refusé tant que le premier tient la carte)"
        if (pris(tx) && tx == sdr) l += nom(tx) + " en SDR et en émission : l'émission est refusée tant que le SDR est en service"
        if (pris(tx) && tx == datv) l += nom(tx) + " en réception DATV et en émission : l'émission est refusée pendant la réception DATV"
        return l
    }

    /**
     * [a] against [b], two release versions (« 2026.10.24 », a tag's date):
     * negative when [a] is older, 0 the same, positive newer; null when either
     * is not a release's (« dev », empty: built by hand).
     */
    fun compareVersions(a: String, b: String): Int? {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').takeIf { p -> p.size >= 2 && p.all { it.isNotEmpty() && it.all(Char::isDigit) } }
            ?.map { it.toLong() }
        val x = parts(a) ?: return null
        val y = parts(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0L }).compareTo(y.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return 0
    }
}
