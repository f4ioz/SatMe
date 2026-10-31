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
import fr.f4ioz.satcombo.domain.EblouissementQo100
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SoleilQo100
import org.json.JSONObject
import java.time.ZoneId

/**
 * QO-100's Sun outages from this station ([EblouissementQo100]): the next
 * two seasons computed once per position and dish (a year of Sun to the
 * minute, well under a second, off the caller's thread), again after a day
 * or once the last one is past. [observateur] where the station is,
 * [diametreCm] the dish typed (0: none, 80 cm taken). Read only.
 */
class EblouissementPc(
    private val observateur: () -> Observer?,
    private val diametreCm: () -> Int,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** False: computed on the caller's thread (the tests). */
    private val enFond: Boolean = true,
) {
    private class Cache(val cle: String, val faitMs: Long, val periodes: List<SoleilQo100.Transit>)

    @Volatile private var cache: Cache? = null
    @Volatile private var enCalcul = false

    fun reglage(): EblouissementQo100.Reglage = EblouissementQo100.reglage(diametreCm())

    private fun cle(o: Observer, r: EblouissementQo100.Reglage) =
        String.format(java.util.Locale.US, "%.3f/%.3f/%.4f", o.latDeg, o.lonDeg, r.seuilDeg)

    /** The outages to come; null while being computed or with no position. */
    fun periodes(): List<SoleilQo100.Transit>? {
        val o = observateur() ?: return null
        val r = reglage()
        val k = cle(o, r)
        val t = horloge()
        val c = cache
        if (c != null && c.cle == k && t - c.faitMs < 86_400_000L && (c.periodes.isEmpty() || c.periodes.last().finMs > t)) return c.periodes
        if (!enFond) { calcule(o, r, k, t); return cache?.periodes }
        if (!enCalcul) {
            enCalcul = true
            Thread { try { calcule(o, r, k, t) } finally { enCalcul = false } }.apply { isDaemon = true; name = "qo100-soleil" }.start()
        }
        // The old list while the new one is computed, when it was for the same place.
        return c?.takeIf { it.cle == k }?.periodes
    }

    private fun calcule(o: Observer, r: EblouissementQo100.Reglage, k: String, t: Long) {
        val l = if (!Qo100.pointage(o.latDeg, o.lonDeg).visible) emptyList()
            else runCatching { EblouissementQo100.periodes(o.latDeg, o.lonDeg, t, r.seuilDeg) }.getOrDefault(emptyList())
        cache = Cache(k, t, l)
    }

    /** The end of the outage under way now, or null (none, or not computed yet). */
    fun finEnCours(t: Long = horloge()): Long? = periodes()?.let { EblouissementQo100.enCours(it, t)?.finMs }

    /** `GET /api/qo100/soleil`. */
    fun json(): JSONObject {
        val o = observateur()
        val visible = o?.let { Qo100.pointage(it.latDeg, it.lonDeg).visible } ?: false
        return EblouissementQo100.json(reglage(), periodes(), horloge(), zone(), o != null, visible)
    }

    /** What the web publishing adds to `balises` during an outage (null otherwise): its end and its words. */
    fun siteJson(t: Long = horloge()): JSONObject? {
        val p = periodes()?.let { EblouissementQo100.enCours(it, t) } ?: return null
        return JSONObject().put("enCours", true).put("fin", p.finMs).put("texte", EblouissementQo100.texteEnCours(p.finMs, zone()).take(200))
    }
}
