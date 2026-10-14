/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.location.Maidenhead

/**
 * The log's records by satellite: the farthest contact of all, of each
 * satellite, each mode, each continent and each country, and what has been
 * worked (squares, countries, continents). A contact's distance runs from
 * its own square (where it was made from) to the other station's — both
 * needed, else it counts but holds no record.
 */
object RecordsCarnet {

    class Record(val km: Double, val contact: LogEntry)

    class Bilan(
        val total: Record?,
        val parSatellite: Map<String, Record>,
        val parMode: Map<String, Record>,
        val parContinent: Map<String, Record>,
        val parPays: Map<String, Record>,
        val contacts: Int,
        val avecDistance: Int,
        val carres: Set<String>,
        val pays: Set<String>,
        val continents: Set<String>,
        val satellites: Set<String>,
        /** Distances crossed (1 000, 2 000… km): each with the first contact past it. */
        val paliers: List<Pair<Int, LogEntry>>,
    )

    /** The six continents of WAC. */
    val CONTINENTS = listOf("EU", "AF", "AS", "NA", "SA", "OC")
    val PALIERS = listOf(500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 5000)

    /** A mode as worked: LSB and USB are SSB. */
    fun mode(m: String): String = when (val u = m.trim().uppercase()) {
        "LSB", "USB", "SSB" -> "SSB"
        "" -> "?"
        else -> u
    }

    /** Great-circle distance between two squares' centres, km; null when one is unknown. */
    fun distanceKm(de: String, a: String): Double? {
        val p = Maidenhead.centre(de) ?: return null
        val q = Maidenhead.centre(a) ?: return null
        val r = Math.toRadians(1.0)
        val (la1, lo1) = p.first * r to p.second * r
        val (la2, lo2) = q.first * r to q.second * r
        val h = Math.sin((la2 - la1) / 2).let { it * it } + Math.cos(la1) * Math.cos(la2) * Math.sin((lo2 - lo1) / 2).let { it * it }
        return 2 * 6371.0 * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun calcule(log: List<LogEntry>, monCarre: String): Bilan {
        val avecIndicatif = log.filter { it.callsign.isNotBlank() }
        val records = avecIndicatif.mapNotNull { c ->
            val de = c.myLocator.ifBlank { monCarre }
            distanceKm(de, c.theirLocator)?.let { Record(it, c) }
        }
        fun meilleurs(cle: (LogEntry) -> String?): Map<String, Record> = records.mapNotNull { r -> cle(r.contact)?.let { it to r } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.maxBy { it.km } }
        val entite = { c: LogEntry -> Dxcc.entite(c.callsign) }
        val paliers = PALIERS.mapNotNull { km -> records.filter { it.km >= km }.minByOrNull { it.contact.timeMs }?.let { km to it.contact } }
        return Bilan(
            total = records.maxByOrNull { it.km },
            parSatellite = meilleurs { it.satName.ifBlank { null } },
            parMode = meilleurs { mode(it.mode) },
            parContinent = meilleurs { entite(it)?.continent?.ifBlank { null } },
            parPays = meilleurs { entite(it)?.nom },
            contacts = avecIndicatif.size,
            avecDistance = records.size,
            carres = avecIndicatif.mapNotNull { it.theirLocator.trim().uppercase().take(4).takeIf { c -> c.length == 4 } }.toSet(),
            pays = avecIndicatif.mapNotNull { entite(it)?.nom }.toSet(),
            continents = avecIndicatif.mapNotNull { entite(it)?.continent?.ifBlank { null } }.toSet(),
            satellites = avecIndicatif.mapNotNull { it.satName.ifBlank { null } }.toSet(),
            paliers = paliers,
        )
    }
}
