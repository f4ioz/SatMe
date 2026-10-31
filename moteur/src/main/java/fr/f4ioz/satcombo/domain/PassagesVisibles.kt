/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.TleEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * « L'ISS visible à l'œil nu »: the part of each pass where the station can
 * be seen — the sky dark enough here (the Sun under −6°, civil twilight
 * over), the station still in sunlight, at least 10° up — with where it
 * appears, culminates and vanishes (it often vanishes mid-sky, entering the
 * Earth's shadow), and its brightness.
 *
 * **The magnitude** is an estimate: the ISS's standard magnitude (−1.8 at
 * 1000 km, half lit) corrected for the distance and the phase, the station
 * taken as a diffusing sphere. Good to about half a magnitude, which is what
 * tells « bright » from « dazzling ». Its large panels can flare brighter.
 */
object PassagesVisibles {

    const val CATNUM_ISS = 25544
    const val EL_MIN_DEG = 10.0
    const val SOLEIL_MAX_DEG = -6.0
    /** Heavens-Above's convention: magnitude at 1000 km, phase angle 90°. */
    const val MAGNITUDE_STANDARD_ISS = -1.8

    data class Point(val t: Long, val azDeg: Double, val elDeg: Double)

    /** The visible part of a pass: [debut] appears, [max] highest, [fin] vanishes ([ombre]: into the Earth's shadow), its [magnitude] at its brightest. */
    data class Visible(val debut: Point, val max: Point, val fin: Point, val magnitude: Double, val ombre: Boolean, val aosMs: Long, val losMs: Long) {
        val dureeS: Long get() = (fin.t - debut.t) / 1000L
    }

    /** One instant of a pass: where the satellite is, whether lit, how high the Sun is here and where. */
    data class Echantillon(val t: Long, val pos: SatPosition, val soleilAz: Double, val soleilEl: Double)

    /**
     * The angle at the satellite between the Sun and the observer (degrees),
     * from the satellite's direction seen here and the Sun's: the Sun being
     * far, the satellite sees it along the same direction as we do.
     */
    fun anglePhaseDeg(satAz: Double, satEl: Double, solAz: Double, solEl: Double): Double {
        fun v(az: Double, el: Double) = doubleArrayOf(cos(Math.toRadians(el)) * sin(Math.toRadians(az)), cos(Math.toRadians(el)) * cos(Math.toRadians(az)), sin(Math.toRadians(el)))
        val s = v(satAz, satEl); val u = v(solAz, solEl)
        val c = -(s[0] * u[0] + s[1] * u[1] + s[2] * u[2])
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    /** The estimated magnitude at [distanceKm] and phase angle [phaseDeg] (0: fully lit, as seen from behind the Sun). */
    fun magnitude(distanceKm: Double, phaseDeg: Double, standard: Double = MAGNITUDE_STANDARD_ISS): Double {
        val b = Math.toRadians(phaseDeg.coerceIn(0.0, 179.0))
        // A diffusing sphere's phase law, relative to its value at 90° (1/π).
        val rapport = (sin(b) + (PI - b) * cos(b)).coerceAtLeast(1e-3)
        return standard + 5 * log10(distanceKm / 1000.0) - 2.5 * log10(rapport)
    }

    /** « brillante », « très brillante »… for a magnitude. */
    fun eclat(m: Double): String = when {
        m <= -3.0 -> "éblouissante"
        m <= -1.5 -> "très brillante"
        m <= 0.0 -> "brillante"
        m <= 2.0 -> "visible"
        else -> "faible"
    }

    private val ROSE = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
    fun rose(az: Double): String = ROSE[(Math.round(((az % 360 + 360) % 360) / 22.5).toInt()) % 16]

    private fun visibleIci(e: Echantillon, elMin: Double, soleilMax: Double) = e.pos.elevationDeg >= elMin && e.pos.sunlit && e.soleilEl < soleilMax

    /**
     * The visible part of one pass from its samples (in time order), or null
     * when none: the longest run of samples meeting the three conditions.
     */
    fun partieVisible(l: List<Echantillon>, aosMs: Long, losMs: Long, elMin: Double = EL_MIN_DEG, soleilMax: Double = SOLEIL_MAX_DEG): Visible? {
        var best: IntRange? = null
        var i = 0
        while (i < l.size) {
            if (!visibleIci(l[i], elMin, soleilMax)) { i++; continue }
            var j = i
            while (j + 1 < l.size && visibleIci(l[j + 1], elMin, soleilMax)) j++
            if (best == null || j - i > best.last - best.first) best = i..j
            i = j + 1
        }
        val r = best ?: return null
        val run = l.subList(r.first, r.last + 1)
        fun p(e: Echantillon) = Point(e.t, e.pos.azimuthDeg, e.pos.elevationDeg)
        val haut = run.maxBy { it.pos.elevationDeg }
        val mag = run.minOf { magnitude(it.pos.rangeKm, anglePhaseDeg(it.pos.azimuthDeg, it.pos.elevationDeg, it.soleilAz, it.soleilEl)) }
        // It ends in the shadow when the next sample is up and dark-sky but unlit.
        val apres = l.getOrNull(r.last + 1)
        val ombre = apres != null && !apres.pos.sunlit && apres.pos.elevationDeg >= elMin
        return Visible(p(run.first()), p(haut), p(run.last()), mag, ombre, aosMs, losMs)
    }

    /**
     * The visible passes of [e] from [o] over the next [heures] hours from
     * [depuisMs]: the passes found by [pp], each sampled every [pasMs].
     */
    fun visibles(pp: PassPredictor, e: TleEntry, o: Observer, depuisMs: Long, heures: Int = 72, pasMs: Long = 10_000L,
                 elMin: Double = EL_MIN_DEG, soleilMax: Double = SOLEIL_MAX_DEG): List<Visible> {
        val passes = pp.upcomingPasses(e, o, depuisMs, heures, elMin)
        return passes.mapNotNull { p ->
            // A pass with the Sun over −6° here at its start, middle and end has no dark part: not sampled.
            if (listOf(p.aosEpochMs, (p.aosEpochMs + p.losEpochMs) / 2, p.losEpochMs).all { SunCalc.elevationDeg(o.latDeg, o.lonDeg, Date(it)) >= soleilMax })
                return@mapNotNull null
            val ts = (p.aosEpochMs..p.losEpochMs step pasMs).toList()
            val pos = pp.samplePositions(e, o, ts)
            val ech = ts.indices.map { i ->
                val s = SunCalc.azElDeg(o.latDeg, o.lonDeg, Date(ts[i]))
                Echantillon(ts[i], pos[i], s[0], s[1])
            }
            partieVisible(ech, p.aosEpochMs, p.losEpochMs, elMin, soleilMax)
        }
    }

    private val ISO = DateTimeFormatter.ISO_INSTANT

    /** The API's answer (`GET /api/iss/visible?jours=3`). */
    fun json(l: List<Visible>, jours: Int, nom: String, catnum: Int, t: Long, observateurConnu: Boolean = true, elementsConnus: Boolean = true): JSONObject {
        val o = JSONObject().put("t", t).put("satellite", nom).put("catnum", catnum).put("jours", jours)
            .put("conditions", JSONObject().put("elMinDeg", EL_MIN_DEG).put("soleilMaxDeg", SOLEIL_MAX_DEG))
        if (!observateurConnu) return o.put("inconnu", true)
        if (!elementsConnus) return o.put("sansElements", true)
        fun p(x: Point) = JSONObject().put("t", x.t).put("utc", ISO.format(Instant.ofEpochMilli(x.t / 1000 * 1000)))
            .put("az", Math.round(x.azDeg)).put("el", Math.round(x.elDeg)).put("dir", rose(x.azDeg))
        return o.put("passages", JSONArray(l.map { v ->
            JSONObject().put("debut", p(v.debut)).put("max", p(v.max)).put("fin", p(v.fin))
                .put("magnitude", Math.round(v.magnitude * 10) / 10.0).put("eclat", eclat(v.magnitude))
                .put("ombre", v.ombre).put("dureeS", v.dureeS).put("aos", v.aosMs).put("los", v.losMs)
        }))
    }
}
