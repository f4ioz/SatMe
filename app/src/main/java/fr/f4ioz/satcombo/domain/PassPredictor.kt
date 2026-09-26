/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import com.github.amsacode.predict4java.GroundStationPosition
import com.github.amsacode.predict4java.SatPos
import com.github.amsacode.predict4java.SatelliteFactory
import com.github.amsacode.predict4java.TLE
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.TleEntry
import java.util.Date
import kotlin.math.acos

/**
 * SGP4 propagation via predict4java (com.github.amsacode). Live look-angles for
 * the polar plot/compass, and pass lists with naked-eye visibility (ISS-Detector
 * style). Eclipse is computed geometrically since this fork exposes no flag.
 */
class PassPredictor {

    private val earthRadiusKm = 6371.0

    private fun gs(o: Observer) = GroundStationPosition(o.latDeg, o.lonDeg, o.altMeters)
    private fun tle(e: TleEntry) = TLE(arrayOf(e.name, lisible(e.line1), lisible(e.line2)))

    companion object {
        /**
         * Are these elements usable by predict4java?
         *
         * **Why the check exists.** The library parses its numeric fields with
         * `Integer.parseInt` and throws on anything else. A truncated download,
         * a stray character in a bulletin, and the exception surfaces deep
         * inside a coroutine computing passes — taking every satellite down
         * with it, not only the faulty one. Seen in production on 20.47.
         *
         * The cheapest honest test is to build the object and see: reproducing
         * the library's own field-by-field parsing here would be a second
         * implementation to keep in step with the first.
         */
        fun elementsUtilisables(e: TleEntry): Boolean {
            if (e.line1.length < 69 || e.line2.length < 69) return false
            if (!e.line1.startsWith("1 ") || !e.line2.startsWith("2 ")) return false
            return runCatching {
                TLE(arrayOf(e.name, lisible(e.line1), lisible(e.line2)))
            }.isSuccess
        }

        /**
         * Rewrites an Alpha-5 catalog field into digits, for the library alone.
         *
         * **Why it is needed.** Catalog numbers above 99999 are written
         * "A0057" — a letter for the leading digits, the convention CelesTrak
         * and 18 SPCS use. predict4java reads that field with
         * `Integer.parseInt` and throws. In production on 20.47 this took down
         * the whole pass computation for anyone tracking SOYUZ-MS 29 or
         * PROGRESS-MS 35.
         *
         * **Why rewrite rather than drop.** Every new satellite now gets a
         * six-digit number, amateur ones included: dropping them would quietly
         * hide more and more birds each year.
         *
         * The number is only an identifier to the library — the orbital maths
         * never touch it. The true catalog number stays in [TleEntry], which is
         * what favourites and AMSAT status match on. Only the copy handed to
         * predict4java is altered, so nothing is written back to the cache.
         */
        internal fun lisible(ligne: String): String {
            if (ligne.length < 7) return ligne
            val champ = ligne.substring(2, 7)
            if (champ.all { it.isDigit() }) return ligne
            val vrai = fr.f4ioz.satcombo.data.OmmParser.decodeAlpha5(champ)
            val chiffres = "%05d".format(vrai % 100000)
            val refait = ligne.take(2) + chiffres + ligne.drop(7)
            // The checksum covers the columns we just changed, and some readers
            // verify it.
            val corps = refait.take(68)
            return corps + fr.f4ioz.satcombo.data.OmmParser.checksum(corps)
        }
    }

    /** Instantaneous position/look-angles at [whenMs]. */
    fun positionAt(e: TleEntry, o: Observer, whenMs: Long): SatPosition {
        val sat = SatelliteFactory.createSatellite(tle(e))
        val date = Date(whenMs)
        val p: SatPos = sat.getPosition(gs(o), date)
        val latDeg = Math.toDegrees(p.latitude)
        val lonDeg = normLon(Math.toDegrees(p.longitude))
        return SatPosition(
            azimuthDeg = Math.toDegrees(p.azimuth),
            elevationDeg = Math.toDegrees(p.elevation),
            rangeKm = p.range,
            rangeRateKmS = p.rangeRate,
            altKm = p.altitude,
            latDeg = latDeg,
            lonDeg = lonDeg,
            sunlit = isSunlit(latDeg, lonDeg, p.altitude, date)
        )
    }

    /**
     * Geometric eclipse test: the satellite is lit if the Sun elevation at the
     * sub-satellite point exceeds the negative shadow-horizon angle for its
     * altitude (arccos(Re/(Re+h))).
     */
    private fun isSunlit(subLat: Double, subLon: Double, altKm: Double, date: Date): Boolean {
        val h = if (altKm > 0) altKm else 500.0
        val shadowDeg = Math.toDegrees(acos((earthRadiusKm / (earthRadiusKm + h)).coerceIn(0.0, 1.0)))
        val sunEl = SunCalc.elevationDeg(subLat, subLon, date)
        return sunEl > -shadowDeg
    }

    /** Scan forward [hours] and collect passes above [minElDeg]. */
    fun upcomingPasses(
        e: TleEntry,
        o: Observer,
        fromMs: Long = System.currentTimeMillis(),
        hours: Int = 72,
        minElDeg: Double = 5.0
    ): List<SatPass> {
        val sat = SatelliteFactory.createSatellite(tle(e))
        val station = gs(o)
        val endMs = fromMs + hours * 3600_000L
        val stepMs = 30_000L
        val passes = ArrayList<SatPass>()

        var t = fromMs
        var inPass = false
        var aos = 0L; var aosAz = 0.0
        var maxEl = -90.0
        var sunlitDuringPass = false
        var track = ArrayList<Pair<Double, Double>>()

        while (t <= endMs) {
            val d = Date(t)
            val p = sat.getPosition(station, d)
            val elDeg = Math.toDegrees(p.elevation)
            if (elDeg >= 0.0 && !inPass) {
                inPass = true
                aos = if (t > fromMs) refineCrossing(sat, station, t - stepMs, t) else t
                aosAz = Math.toDegrees(sat.getPosition(station, Date(aos)).azimuth)
                maxEl = elDeg; sunlitDuringPass = false
                track = ArrayList()
            }
            if (inPass) {
                if (elDeg > maxEl) maxEl = elDeg
                if (elDeg >= 0) track.add(Math.toDegrees(p.azimuth) to elDeg)
                if (isSunlit(Math.toDegrees(p.latitude), normLon(Math.toDegrees(p.longitude)), p.altitude, d))
                    sunlitDuringPass = true
                if (elDeg < 0.0) {
                    val los = refineCrossing(sat, station, t - stepMs, t)
                    val losAzRefined = Math.toDegrees(sat.getPosition(station, Date(los)).azimuth)
                    if (maxEl >= minElDeg) {
                        passes += SatPass(
                            satName = e.name, catalogNumber = e.catalogNumber,
                            aosEpochMs = aos, losEpochMs = los,
                            maxElevationDeg = maxEl,
                            aosAzimuthDeg = aosAz, losAzimuthDeg = losAzRefined,
                            sunlit = sunlitDuringPass,
                            nightAtObserver = SunCalc.elevationDeg(o.latDeg, o.lonDeg, Date((aos + los) / 2)) < -6.0,
                            track = track
                        )
                    }
                    inPass = false; maxEl = -90.0
                }
            }
            t += stepMs
        }
        return passes
    }

    /** Sample the az/el trajectory of a pass between [aosMs] and [losMs]. */
    fun passTrack(e: TleEntry, o: Observer, aosMs: Long, losMs: Long, steps: Int = 60): List<Pair<Double, Double>> {
        if (losMs <= aosMs) return emptyList()
        val sat = SatelliteFactory.createSatellite(tle(e))
        val station = gs(o)
        val out = ArrayList<Pair<Double, Double>>(steps + 1)
        val span = losMs - aosMs
        for (i in 0..steps) {
            val t = aosMs + span * i / steps
            val p = sat.getPosition(station, Date(t))
            val el = Math.toDegrees(p.elevation)
            if (el >= 0) out += Math.toDegrees(p.azimuth) to el
        }
        return out
    }



    /**
     * Timed look-angle samples between [fromMs] and [toMs] (inclusive), one
     * every 1/[steps] of the span. Each triple is (timeMs, azDeg, elDeg) with
     * elevation kept even when below the horizon so the caller can decide how to
     * draw the rise/set. Used by the sked page animation and export.
     */
    /**
     * Positions complètes à une liste d'instants, satellite construit une
     * seule fois.
     *
     * [positionAt] refabrique le satellite à chaque appel : c'est sans
     * conséquence pour la boussole, qui n'en demande qu'une par seconde, mais
     * la table de Doppler d'un passage en demande une cinquantaine d'un coup.
     * D'où ce passage groupé.
     */
    fun samplePositions(e: TleEntry, o: Observer, timesMs: List<Long>): List<SatPosition> {
        if (timesMs.isEmpty()) return emptyList()
        val sat = SatelliteFactory.createSatellite(tle(e))
        val station = gs(o)
        return timesMs.map { t ->
            val d = Date(t)
            val p = sat.getPosition(station, d)
            val latDeg = Math.toDegrees(p.latitude)
            val lonDeg = normLon(Math.toDegrees(p.longitude))
            SatPosition(
                azimuthDeg = Math.toDegrees(p.azimuth),
                elevationDeg = Math.toDegrees(p.elevation),
                rangeKm = p.range,
                rangeRateKmS = p.rangeRate,
                altKm = p.altitude,
                latDeg = latDeg,
                lonDeg = lonDeg,
                sunlit = isSunlit(latDeg, lonDeg, p.altitude, d)
            )
        }
    }

    fun sampleTrack(
        e: TleEntry, o: Observer, fromMs: Long, toMs: Long, steps: Int = 96
    ): List<Triple<Long, Double, Double>> {
        if (toMs <= fromMs || steps <= 0) return emptyList()
        val sat = SatelliteFactory.createSatellite(tle(e))
        val station = gs(o)
        val out = ArrayList<Triple<Long, Double, Double>>(steps + 1)
        val span = toMs - fromMs
        for (i in 0..steps) {
            val t = fromMs + span * i / steps
            val p = sat.getPosition(station, Date(t))
            out += Triple(t, Math.toDegrees(p.azimuth), Math.toDegrees(p.elevation))
        }
        return out
    }

    /**
     * Échantillonne un passage entre [fromMs] et [toMs] au pas [stepMs], en
     * gardant l'élévation et la vitesse radiale.
     *
     * Un échantillonneur par lots, et non un appel par point : le satellite
     * n'est construit qu'une fois. Cela compte, parce que le tableau du Doppler
     * demande deux balayages — la demi-minute sur tout le passage, puis les
     * deux secondes autour du sommet — soit une centaine de points pour un
     * seul affichage.
     *
     * La borne de fin est toujours rendue, même quand le pas ne tombe pas
     * juste : sans elle, la ligne LOS manquerait au tableau.
     */
    fun samplePositions(
        e: TleEntry, o: Observer, fromMs: Long, toMs: Long, stepMs: Long
    ): List<DopplerPass.Sample> {
        if (toMs < fromMs || stepMs <= 0L) return emptyList()
        val sat = SatelliteFactory.createSatellite(tle(e))
        val station = gs(o)
        val out = ArrayList<DopplerPass.Sample>()
        fun at(t: Long): DopplerPass.Sample {
            val p = sat.getPosition(station, Date(t))
            return DopplerPass.Sample(t, Math.toDegrees(p.elevation), p.rangeRate)
        }
        var t = fromMs
        while (t <= toMs) {
            out += at(t)
            t += stepMs
        }
        if (out.isEmpty() || out.last().timeMs != toMs) out += at(toMs)
        return out
    }

    /** Sub-satellite ground track over roughly one orbital period (lat, lon). */
    fun groundTrack(e: TleEntry, fromMs: Long, steps: Int = 120): List<Pair<Double, Double>> {
        val mm = e.line2.substring(52, 63).trim().toDoubleOrNull() ?: return emptyList()
        if (mm < 6.0) return emptyList() // non-LEO: SGP4 track misleading, skip
        val periodMs = (1440.0 / mm * 60_000).toLong()
        val sat = SatelliteFactory.createSatellite(tle(e))
        val out = ArrayList<Pair<Double, Double>>(steps + 1)
        val start = fromMs - periodMs / 8
        for (i in 0..steps) {
            val t = start + periodMs * i / steps
            val p = sat.getPosition(GroundStationPosition(0.0, 0.0, 0.0), Date(t))
            out += Math.toDegrees(p.latitude) to normLon(Math.toDegrees(p.longitude))
        }
        return out
    }

    /** Bisect a horizon crossing between [tLow] (below/above) and [tHigh] to ~1 s. */
    /**
     * Le passage en cours : la période continue autour de [nowMs] pendant
     * laquelle le satellite est au-dessus de l'horizon. Nul s'il est couché.
     *
     * La liste des passages ne suffit pas à répondre. Elle est balayée à partir
     * de « maintenant moins vingt minutes » : quand on ouvre un satellite déjà
     * levé, son premier passage commence donc à l'instant du balayage et non à
     * l'acquisition réelle. Sur une orbite basse cela ne se voit pas — le
     * passage dure dix minutes. Sur RS-44, dont un passage dure des heures, la
     * fenêtre serait tronquée et les contacts du début du passage sortiraient
     * de leur propre passage. C'est le défaut de la règle « moins d'une
     * heure », revenu par la porte d'à côté.
     *
     * On remonte donc le temps depuis maintenant, pas d'une minute, jusqu'à
     * repasser sous l'horizon, puis on affine le franchissement à la seconde.
     * [maxHeures] borne la recherche : au-delà, ce n'est plus un passage, c'est
     * un satellite géostationnaire, et la question de « ce passage » ne se pose
     * plus dans les mêmes termes.
     */
    fun currentPass(
        e: TleEntry, o: Observer, nowMs: Long, maxHeures: Int = 12,
    ): Pair<Long, Long>? {
        val sat = SatelliteFactory.createSatellite(tle(e))
        val station = gs(o)
        fun el(t: Long) = Math.toDegrees(sat.getPosition(station, Date(t)).elevation)
        if (el(nowMs) < 0.0) return null
        val pas = 60_000L
        val borne = maxHeures * 3600_000L

        var t = nowMs
        while (nowMs - t < borne && el(t - pas) >= 0.0) t -= pas
        val aos = if (nowMs - t >= borne) t else refineCrossing(sat, station, t - pas, t)

        var u = nowMs
        while (u - nowMs < borne && el(u + pas) >= 0.0) u += pas
        val los = if (u - nowMs >= borne) u else refineCrossing(sat, station, u, u + pas)
        return aos to los
    }

    private fun refineCrossing(
        sat: com.github.amsacode.predict4java.Satellite,
        station: GroundStationPosition,
        tLow: Long, tHigh: Long
    ): Long {
        var lo = tLow; var hi = tHigh
        val loAbove = Math.toDegrees(sat.getPosition(station, Date(lo)).elevation) >= 0.0
        repeat(18) {
            if (hi - lo <= 1000) return@repeat
            val mid = (lo + hi) / 2
            val above = Math.toDegrees(sat.getPosition(station, Date(mid)).elevation) >= 0.0
            if (above == loAbove) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private fun normLon(lon: Double): Double {
        var l = lon
        while (l > 180) l -= 360
        while (l < -180) l += 360
        return l
    }
}

/** Compact solar position (NOAA approximation) — good enough for twilight gating. */
object SunCalc {
    fun elevationDeg(latDeg: Double, lonDeg: Double, date: Date): Double =
        azElDeg(latDeg, lonDeg, date)[1]

    /**
     * Azimut et élévation du Soleil, en degrés, dans cet ordre.
     *
     * L'azimut est vrai, compté depuis le nord dans le sens des aiguilles
     * d'une montre — la même convention que partout ailleurs dans
     * l'application, et notamment que [fr.f4ioz.satcombo.domain.Qo100.pointage],
     * sans quoi comparer les deux directions n'aurait aucun sens.
     *
     * Le modèle est celui de l'élévation, inchangé : une approximation NOAA à
     * quelques centièmes de degré près. C'est très en dessous du demi-degré
     * que fait le disque solaire, donc largement assez pour dire à quelle
     * minute le Soleil passe devant le satellite — et beaucoup trop grossier
     * pour prétendre à autre chose.
     */
    fun azElDeg(latDeg: Double, lonDeg: Double, date: Date): DoubleArray {
        val jd = date.time / 86400000.0 + 2440587.5
        val n = jd - 2451545.0
        val l = (280.460 + 0.9856474 * n) % 360
        val g = Math.toRadians((357.528 + 0.9856003 * n) % 360)
        val lambda = Math.toRadians(l + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val decl = Math.asin(Math.sin(eps) * Math.sin(lambda))
        val gmst = (18.697374558 + 24.06570982441908 * n) % 24
        val lst = Math.toRadians((gmst * 15 + lonDeg) % 360)
        val ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda))
        val ha = lst - ra
        val latR = Math.toRadians(latDeg)

        // Le trièdre local, comme pour le pointage : est, nord, haut.
        val est = -Math.cos(decl) * Math.sin(ha)
        val nord = Math.cos(latR) * Math.sin(decl) -
                Math.sin(latR) * Math.cos(decl) * Math.cos(ha)
        val haut = Math.sin(latR) * Math.sin(decl) +
                Math.cos(latR) * Math.cos(decl) * Math.cos(ha)

        val az = (Math.toDegrees(Math.atan2(est, nord)) + 360.0) % 360.0
        val el = Math.toDegrees(Math.asin(haut.coerceIn(-1.0, 1.0)))
        return doubleArrayOf(az, el)
    }
}
