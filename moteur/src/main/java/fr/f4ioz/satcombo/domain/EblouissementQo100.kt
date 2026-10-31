/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Sun outage of QO-100 (« éblouissement solaire »): twice a year, around the
 * equinoxes, the Sun passes behind the geostationary satellite as seen from
 * the station. For a few minutes a day, a few days in a row, the dish looks
 * at the Sun: its X band noise drowns the downlink. Not a fault.
 *
 * **The threshold.** The Sun disturbs reception as soon as part of its disc
 * is in the dish's main lobe: the separation Sun–satellite under half the
 * −3 dB beamwidth ([BruitSolaire.lobeDeg], 70 λ/D) plus the Sun's radius
 * (0.27°). A 80 cm dish at 10.5 GHz: a 2.5° lobe, so 1.52°. A bigger dish
 * has a narrower lobe: fewer minutes, but a deeper fade.
 *
 * The same minutes are a free pointing check: the noise rises and peaks when
 * the Sun is in the beam's axis. A peak a few minutes off the predicted
 * instant means the dish looks a little east or west of the satellite.
 *
 * Reuses [SoleilQo100.prochainsTransits] (the day's minimum, to the minute,
 * the window to the second) with this threshold instead of the 1° of the
 * alignment dates.
 */
object EblouissementQo100 {

    /** The owner's dish when none is typed (Réglages › Mesures › Bruit solaire). */
    const val DIAMETRE_DEFAUT_CM = 80

    /** The downlink the lobe is computed for: the middle beacon. */
    val FREQUENCE_HZ: Double = Qo100.BALISE_MEDIANE_HZ.toDouble()

    /** The Sun's apparent radius, degrees. */
    const val RAYON_SOLEIL_DEG = BruitSolaire.DISQUE_DEG / 2

    /** The dish and what it gives: [parDefaut] when no diameter was typed. */
    data class Reglage(val diametreCm: Int, val parDefaut: Boolean, val lobeDeg: Double, val seuilDeg: Double)

    /** From the diameter typed (0 or less: none, [DIAMETRE_DEFAUT_CM] taken). */
    fun reglage(diametreCm: Int): Reglage {
        val d = if (diametreCm > 0) diametreCm else DIAMETRE_DEFAUT_CM
        val lobe = BruitSolaire.lobeDeg(d / 100.0, FREQUENCE_HZ)
        return Reglage(d, diametreCm <= 0, lobe, lobe / 2 + RAYON_SOLEIL_DEG)
    }

    /** The separation Sun–satellite seen from here at [ms], degrees; null when the satellite is below the horizon. */
    fun ecartDeg(latDeg: Double, lonDeg: Double, ms: Long): Double? {
        val sat = Qo100.pointage(latDeg, lonDeg)
        if (!sat.visible) return null
        val p = SunCalc.azElDeg(latDeg, lonDeg, Date(ms))
        return SoleilQo100.ecartDeg(p[0], p[1], sat.azDeg, sat.elDeg)
    }

    /**
     * The outages to come, one per day, from [depuisMs] over [jours] days (the
     * next two seasons with the default), at most [maximum]. One that is under
     * way at [depuisMs] is the first.
     *
     * The scan starts half an hour early so an outage under way is not cut at
     * its start; two found within twelve hours (a day's edge falling inside
     * one) are the same, the closer kept.
     */
    fun periodes(latDeg: Double, lonDeg: Double, depuisMs: Long, seuilDeg: Double, jours: Int = 400, maximum: Int = 30): List<SoleilQo100.Transit> {
        val l = SoleilQo100.prochainsTransits(latDeg, lonDeg, depuisMs - 30 * 60_000L, jours, seuilDeg, maximum + 2)
        val out = ArrayList<SoleilQo100.Transit>()
        for (t in l.sortedBy { it.instantMs }) {
            val prec = out.lastOrNull()
            if (prec != null && t.instantMs - prec.instantMs < 12 * 3_600_000L) {
                if (t.ecartDeg < prec.ecartDeg) out[out.size - 1] = t
                continue
            }
            out += t
        }
        return out.filter { it.finMs >= depuisMs }.take(maximum)
    }

    /** The outage under way at [t] among [periodes], or null. */
    fun enCours(periodes: List<SoleilQo100.Transit>, t: Long): SoleilQo100.Transit? =
        periodes.firstOrNull { t in it.debutMs..it.finMs }

    /** The next outage starting after [t], or null. */
    fun prochaine(periodes: List<SoleilQo100.Transit>, t: Long): SoleilQo100.Transit? =
        periodes.firstOrNull { it.debutMs > t }

    private val HEURE = DateTimeFormatter.ofPattern("HH:mm")
    private val JOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val ISO = DateTimeFormatter.ISO_INSTANT

    fun heure(ms: Long, zone: ZoneId): String = HEURE.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** « jusqu'à 12:47 (10:47 UTC) », the UTC left out when the zone is UTC. */
    fun jusqua(finMs: Long, zone: ZoneId): String {
        val l = heure(finMs, zone)
        val u = heure(finMs, ZoneId.of("UTC"))
        return "jusqu'à $l" + if (l == u) " UTC" else " ($u UTC)"
    }

    /** The « Pourquoi rien ? » line during an outage. */
    fun texteEnCours(finMs: Long, zone: ZoneId): String =
        "Éblouissement solaire en cours ${jusqua(finMs, zone)} — normal, pas une panne : le Soleil passe derrière QO-100 vu d'ici."

    /** Minutes of an outage, rounded, one at least. */
    fun minutes(t: SoleilQo100.Transit): Int = Math.max(1, Math.round(t.dureeS / 60.0).toInt())

    /**
     * The API's answer (`GET /api/qo100/soleil`): the dish and its threshold,
     * the outage under way, the next ones (UTC and local times as text too,
     * for a web page that has no zone of its own). [periodes] null: being
     * computed; [observateurConnu] false: no position.
     */
    fun json(r: Reglage, periodes: List<SoleilQo100.Transit>?, t: Long, zone: ZoneId, observateurConnu: Boolean, visible: Boolean = true): JSONObject {
        val o = JSONObject().put("t", t).put("zone", zone.id)
            .put("parabole", JSONObject().put("diametreCm", r.diametreCm).put("parDefaut", r.parDefaut)
                .put("lobeDeg", Math.round(r.lobeDeg * 100) / 100.0).put("seuilDeg", Math.round(r.seuilDeg * 100) / 100.0)
                .put("frequenceHz", FREQUENCE_HZ.toLong()))
            .put("conseil", "Le bruit monte puis redescend : son maximum tombe à l'heure prévue quand la parabole vise juste. " +
                "Un maximum en avance ou en retard de quelques minutes : la parabole regarde un peu à l'est ou à l'ouest du satellite.")
        if (!observateurConnu) return o.put("inconnu", true)
        if (!visible) return o.put("invisible", true)
        if (periodes == null) return o.put("calcul", true)
        fun p(x: SoleilQo100.Transit) = JSONObject().put("debut", x.debutMs).put("fin", x.finMs).put("max", x.instantMs)
            .put("ecartDeg", Math.round(x.ecartDeg * 100) / 100.0).put("minutes", minutes(x)).put("dureeS", x.dureeS)
            .put("az", Math.round(x.azSoleilDeg * 10) / 10.0).put("el", Math.round(x.elSoleilDeg * 10) / 10.0)
            .put("jour", JOUR.format(Instant.ofEpochMilli(x.instantMs).atZone(zone)))
            .put("debutUtc", ISO.format(Instant.ofEpochMilli(x.debutMs / 1000 * 1000))).put("finUtc", ISO.format(Instant.ofEpochMilli(x.finMs / 1000 * 1000)))
            .put("maxUtc", ISO.format(Instant.ofEpochMilli(x.instantMs / 1000 * 1000)))
            .put("debutLocal", heure(x.debutMs, zone)).put("finLocal", heure(x.finMs, zone)).put("maxLocal", heure(x.instantMs, zone))
        val ec = enCours(periodes, t)
        o.put("enCours", ec?.let { p(it).put("texte", texteEnCours(it.finMs, zone)) } ?: JSONObject.NULL)
        o.put("prochaine", prochaine(periodes, t)?.let { p(it) } ?: JSONObject.NULL)
        o.put("periodes", JSONArray(periodes.map { p(it) }))
        return o
    }
}
