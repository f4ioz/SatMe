/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.cw.AnalyseCw
import fr.f4ioz.satcombo.domain.Dxcc
import fr.f4ioz.satcombo.domain.Qo100

/**
 * Callsigns written in a received DATV picture, as text recognition reads
 * them: the words that make a plausible callsign (a country's prefix, a
 * letter before the area digit, not a grid square nor a frequency), kept
 * only when the same one comes back in consecutive reads.
 */
object IndicatifsImage {

    private val CARRE = Regex("^[A-R]{2}[0-9]{2}(?:[A-X]{2})?$")

    /** The plausible callsigns of a recognised [texte], each once, in order. */
    fun candidats(texte: String): List<String> {
        val vus = LinkedHashSet<String>()
        for (mot in texte.uppercase().split(Regex("[^A-Z0-9/]+"))) {
            val m = mot.trim('/')
            if (m.length < 4 || m.length > 14) continue
            plausible(m)?.let { vus += it }
        }
        return vus.toList()
    }

    /** [mot] as a callsign (slashes kept), or null when it is not one. */
    fun plausible(mot: String): String? {
        val m = mot.uppercase()
        val base = AnalyseCw.base(m) ?: return null
        if (base.length < 4 || base.length > 7 || CARRE.matches(base)) return null
        // A real prefix has a letter before the area digit: 10GHZ, 2400MHZ are not callsigns.
        val chiffre = base.indexOfLast { it.isDigit() }
        if (chiffre <= 0 || base.substring(0, chiffre).none { it.isLetter() }) return null
        if (Dxcc.entite(m) == null) return null
        return m
    }

    /** True when the station's service name (LongMynd's, often its callsign) holds [indicatif]. */
    fun accord(indicatif: String, service: String): Boolean {
        val b = AnalyseCw.base(indicatif) ?: return false
        return service.uppercase().split(Regex("[^A-Z0-9/]+")).any { AnalyseCw.base(it) == b }
    }

    /** True when [cielKhz] is in QO-100's wide band transponder (its downlink). */
    fun qo100(cielKhz: Long): Boolean = cielKhz in 10_490_500L..10_500_000L

    /** QO-100's uplink for a downlink [cielKhz]. */
    fun monteeKhz(cielKhz: Long): Long = cielKhz - Qo100.DECALAGE_HZ / 1000L
}

/**
 * The reads' vote: a candidate is shown once seen in [requis] consecutive
 * reads (a read without it starts its count again).
 */
class VoteIndicatifs(private val requis: Int = 2, private val garde: Int = 5) {

    /** A callsign confirmed: how many reads in a row, when first and last confirmed. */
    data class Lu(val indicatif: String, val lectures: Int, val premierMs: Long, val dernierMs: Long)

    private val suite = HashMap<String, Int>()
    private val confirmes = ArrayList<Lu>()

    /** One read's candidates at [t]; the callsigns confirmed by this read. */
    @Synchronized
    fun lecture(candidats: Collection<String>, t: Long): List<String> {
        val c = candidats.toSet()
        suite.keys.retainAll(c)
        val ok = ArrayList<String>()
        for (x in c) {
            val n = (suite[x] ?: 0) + 1
            suite[x] = n
            if (n < requis) continue
            ok += x
            val i = confirmes.indexOfFirst { it.indicatif == x }
            val avant = if (i >= 0) confirmes.removeAt(i) else null
            confirmes.add(0, Lu(x, n, avant?.premierMs ?: t, t))
        }
        while (confirmes.size > garde) confirmes.removeAt(confirmes.size - 1)
        return ok
    }

    /** The confirmed callsigns, last confirmed first. */
    @Synchronized fun confirmes(): List<Lu> = confirmes.toList()

    @Synchronized fun oublie() { suite.clear(); confirmes.clear() }
}
