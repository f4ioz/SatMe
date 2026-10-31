/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.domain.PassagesVisibles
import org.json.JSONObject

/**
 * « L'ISS visible à l'œil nu » for the Suivi tab and a web site
 * (`GET /api/iss/visible?jours=3`): [PassagesVisibles] from the elements the
 * station has, kept ten minutes per position, elements and span. Read only.
 */
class IssVisiblePc(
    private val observateur: () -> Observer?,
    private val satellites: () -> List<TleEntry>,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    private val pp = PassPredictor()
    private class Cache(val cle: String, val faitMs: Long, val l: List<PassagesVisibles.Visible>)
    @Volatile private var cache: Cache? = null

    fun json(jours: Int = 3): JSONObject {
        val j = jours.coerceIn(1, 10)
        val t = horloge()
        val o = observateur()
        val e = satellites().firstOrNull { it.catalogNumber == PassagesVisibles.CATNUM_ISS }
        val nom = e?.name ?: "ISS"
        if (o == null || e == null) return PassagesVisibles.json(emptyList(), j, nom, PassagesVisibles.CATNUM_ISS, t, o != null, e != null)
        val cle = "${o.latDeg}/${o.lonDeg}/${e.line1.hashCode()}/$j"
        val c = cache?.takeIf { it.cle == cle && t - it.faitMs in 0 until 600_000L }
        val l = c?.l ?: synchronized(this) {
            runCatching { PassagesVisibles.visibles(pp, e, o, t, j * 24) }.getOrDefault(emptyList()).also { cache = Cache(cle, t, it) }
        }
        // A pass over since the list was made is dropped.
        return PassagesVisibles.json(l.filter { it.fin.t > t }, j, nom, PassagesVisibles.CATNUM_ISS, t)
    }
}
