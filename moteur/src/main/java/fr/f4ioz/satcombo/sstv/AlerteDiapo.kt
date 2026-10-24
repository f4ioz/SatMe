/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.i18n.tf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * « Diapo manquante attendue »: before a pass the timetable
 * ([HoraireDiapos]) says sends whole a slide still missing — or only
 * received with a mark under the owner's threshold — a notice, once per
 * pass: « Diapo 5 attendue à 04:32 UTC (≈ 06:32 heure locale), 49° —
 * passage ISS ». Here what to say and when; the phone (a notification on
 * the activities' channel) and SatMe-PC (the activities' notices) say it.
 *
 * Not when the estimate is too loose to name a slide (more than a third of
 * a period off), never for a pass begun.
 */
object AlerteDiapo {

    data class Reglages(
        val actif: Boolean = true,
        /** How long before the slide (its start) the notice comes, minutes. */
        val avantMin: Int = 15,
        /** A slide whose best copy is marked under this is still wanted (0: only those missing). */
        val noteMin: Int = 60,
    )

    /** One slide hoped for on a pass. */
    data class Attendue(val diapo: Int, val debutMs: Long,
                        /** The satellite's elevation then (track), its peak when unknown. */
                        val elDeg: Double,
                        /** Its best mark so far, -1 never received. */
                        val meilleure: Int)

    data class Alerte(val passage: SatPass, val satNom: String, val diapos: List<Attendue>, val quandMs: Long, val incertitudeS: Double) {
        /** Once per pass: its satellite and AOS minute. */
        val cle: String get() = passage.catalogNumber.toString() + ":" + passage.aosEpochMs / 60_000L
    }

    /** The best mark of each slide numbered in [p] (the hand's, read, estimated alike). */
    fun meilleures(p: SerieSstv.Prevision): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        for (g in p.groupes) {
            if (g.diapo <= 0) continue
            val n = g.images.maxOfOrNull { g.notes[it.fileName] ?: -1 } ?: -1
            m[g.diapo] = maxOf(m[g.diapo] ?: -1, n)
        }
        return m
    }

    /** The slides still wanted: missing, or best under [noteMin] (a mark not measured yet counts as good). */
    fun voulues(p: SerieSstv.Prevision, noteMin: Int): Set<Int> {
        val nb = p.modele?.nb ?: p.serie.nb
        val mb = meilleures(p)
        return (1..nb).filter { d -> d !in p.recues || (noteMin > 0 && (mb[d] ?: -1).let { it in 0 until noteMin }) }.toSet()
    }

    /** The elevation at [t] on [p]'s track (samples spread AOS → LOS), its peak when it has none. */
    fun elevation(p: SatPass, t: Long): Double {
        val tr = p.track
        if (tr.size < 2 || p.losEpochMs <= p.aosEpochMs) return p.maxElevationDeg
        val x = ((t - p.aosEpochMs).toDouble() / (p.losEpochMs - p.aosEpochMs) * tr.size - 0.5).coerceIn(0.0, tr.size - 1.0)
        val i = x.toInt().coerceAtMost(tr.size - 2)
        val f = x - i
        return tr[i].second * (1 - f) + tr[i + 1].second * f
    }

    /**
     * The notices for the passes to come of [p]: each pass that sends whole a
     * slide still wanted, the notice [Reglages.avantMin] before the first of
     * them; passes begun or too uncertain left out. Soonest first.
     */
    fun alertes(p: SerieSstv.Prevision, r: Reglages, maintenant: Long): List<Alerte> {
        if (!r.actif) return emptyList()
        val m = p.modele ?: return emptyList()
        val tab = p.tableau ?: return emptyList()
        val voulues = voulues(p, r.noteMin)
        if (voulues.isEmpty()) return emptyList()
        val mb = meilleures(p)
        return tab.passages.filter { it.passage.aosEpochMs > maintenant && it.incertitudeS <= m.periodeS / 3 }.mapNotNull { ps ->
            val l = ps.prevues.filter { it.entiere && it.diapo in voulues }.distinctBy { it.diapo }
                .map { Attendue(it.diapo, it.debutMs, elevation(ps.passage, it.debutMs + (HoraireDiapos.DUREE_S * 500).toLong()), mb[it.diapo] ?: -1) }
            if (l.isEmpty()) null
            else Alerte(ps.passage, p.serie.satNom, l, l.minOf { it.debutMs } - r.avantMin * 60_000L, ps.incertitudeS)
        }.sortedBy { it.quandMs }
    }

    /** Those due at [maintenant] and not said yet ([dites]: their keys); a pass already begun is never said. */
    fun dues(l: List<Alerte>, dites: Set<String>, maintenant: Long): List<Alerte> =
        l.filter { maintenant >= it.quandMs && maintenant < it.passage.aosEpochMs + 60_000L && it.cle !in dites }.distinctBy { it.cle }

    // ------------------------------------------------------------------ the words

    private fun hm(t: Long, zone: TimeZone) = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }.format(Date(t))

    /**
     * « Diapo 5 attendue à 04:32 UTC (≈ 06:32 heure locale), 49° — passage
     * ISS » (several: each slide, its time and elevation). [zone]: the local
     * time's.
     */
    fun texte(a: Alerte, zone: TimeZone = TimeZone.getDefault()): String {
        val utc = TimeZone.getTimeZone("UTC")
        val parties = a.diapos.map { d ->
            tf("dm_alerte_diapo", d.diapo, hm(d.debutMs, utc), hm(d.debutMs, zone), Math.round(d.elDeg)) +
                (if (d.meilleure >= 0) " " + tf("dm_alerte_note", d.meilleure) else "")
        }
        val tete = if (a.diapos.size == 1) parties.first() else tf("dm_alerte_diapos", parties.joinToString(" ; "))
        return tete + " — " + tf("dm_alerte_passage", a.satNom.ifBlank { a.passage.satName }, Math.round(a.passage.maxElevationDeg))
    }

    /** The notice's title (phone). */
    fun titre(a: Alerte): String =
        if (a.diapos.size == 1) tf("dm_titre_une", a.diapos.first().diapo) else tf("dm_titre_plusieurs", a.diapos.joinToString(", ") { it.diapo.toString() })

    // ------------------------------------------------------------------ what was said

    /** The keys said (kept in a small file), the 300 last. */
    fun litDites(texte: String?): Set<String> = runCatching {
        val a = JSONObject(texte ?: "").optJSONArray("dites") ?: JSONArray()
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    fun ecritDites(s: Set<String>): String = JSONObject().put("v", 1).put("dites", JSONArray(s.toList().takeLast(300))).toString()
}
