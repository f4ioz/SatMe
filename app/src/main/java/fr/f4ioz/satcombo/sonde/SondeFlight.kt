/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

/**
 * A radiosonde flight, from first point heard to last.
 *
 * A sonde climbs for two hours, bursts around 30 km, falls in half an hour and
 * goes quiet on the ground (or keeps transmitting for hours if the battery
 * lasts). Finding it comes down to three things: the last trusted fix, the
 * burst point, and the extrapolated landing when the signal is lost before
 * the ground.
 *
 * No Android on purpose: fully testable, and an error here costs an afternoon
 * of walking.
 */
class SondeFlight(val serial: String, val type: String) {

    private val points = ArrayList<SondeFrame>(4096)

    /** All retained points, oldest first. */
    val track: List<SondeFrame> get() = points

    /** Last point, trusted or not. */
    var last: SondeFrame? = null
        private set

    /** Last trusted point: the one we walk towards. */
    var lastTrusted: SondeFrame? = null
        private set

    /** Highest trusted point, i.e. the burst. */
    var burst: SondeFrame? = null
        private set

    val count: Int get() = points.size

    /** Frequency of the last reception, Hz. */
    var freqHz: Long = 0L
        private set

    /**
     * Adds a frame; true if retained.
     *
     * The continuity filter guards against bad frames from CRC-less decoders:
     * a point jumping 100 km is a misread byte, not a balloon.
     */
    fun add(f: SondeFrame): Boolean {
        if (!f.plausible) return false
        val prev = last
        if (prev != null && f.trusted && prev.trusted) {
            val jump = Geo.distanceKm(prev.lat, prev.lon, f.lat, f.lon)
            val dt = elapsedSec(prev, f)
            // 100 m/s is already generous (jet-stream maximum). The extra 20 km
            // covers a reception gap of a few minutes.
            if (jump > 0.1 * dt + 20.0) return false
        }
        points += f
        last = f
        if (f.freqHz > 0) freqHz = f.freqHz
        if (f.trusted) {
            lastTrusted = f
            val b = burst
            if (b == null || f.altM > b.altM) burst = f
        }
        return true
    }

    /**
     * Seconds between two frames.
     *
     * Sonde GPS time first, phone clock second. They agree live, but not when
     * replaying a recording: an hour of audio decodes in two minutes, the phone
     * clock runs 30x slow relative to the flight, and the continuity check
     * would reject good points. The sonde's GPS clock is always right about
     * the flight.
     */
    private fun elapsedSec(prev: SondeFrame, f: SondeFrame): Double {
        if (f.timeUtcMs > 0L && prev.timeUtcMs > 0L && f.timeUtcMs > prev.timeUtcMs) {
            return (f.timeUtcMs - prev.timeUtcMs) / 1000.0
        }
        if (f.heardAtMs > prev.heardAtMs) return (f.heardAtMs - prev.heardAtMs) / 1000.0
        return 1.0
    }

    /** Has it burst? Known once it is back below its peak. */
    val hasBurst: Boolean
        get() {
            val b = burst ?: return false
            val l = lastTrusted ?: return false
            return b.altM > 12_000.0 && l.altM < b.altM - 500.0
        }

    /** Burst altitude, metres, or 0. */
    val burstAltM: Double get() = burst?.altM ?: 0.0

    /** Mean descent rate over recent frames, m/s, positive. Used for the landing estimate. */
    fun descentRate(samples: Int = 10): Double {
        val recent = points.filter { it.trusted }.takeLast(samples)
        if (recent.size < 2) return 0.0
        val v = recent.map { -it.climbMps }.filter { it > 0.5 }
        if (v.isEmpty()) return 0.0
        return v.average()
    }

    /**
     * Estimated landing point, or null if still climbing or not enough data.
     *
     * Deliberately simple: extend the last horizontal vector over the
     * remaining fall time. Not a wind model (the app lacks the data for one),
     * but over the last kilometre drift is small and it beats nothing.
     */
    fun estimatedLanding(): Pair<Double, Double>? {
        val l = lastTrusted ?: return null
        if (l.altM <= 0.0) return null
        val rate = descentRate()
        if (rate < 1.0) return null
        val seconds = l.altM / rate
        if (seconds > 3600.0) return null
        val distM = l.speedMps * seconds
        val br = Math.toRadians(l.headingDeg)
        val dLat = distM * Math.cos(br) / 111_320.0
        val dLon = distM * Math.sin(br) /
            (111_320.0 * Math.cos(Math.toRadians(l.lat)).coerceAtLeast(0.05))
        return Pair(l.lat + dLat, l.lon + dLon)
    }

    /** Distance from the QTH to the last trusted point, km. */
    fun distanceFromKm(lat: Double, lon: Double): Double {
        val l = lastTrusted ?: return 0.0
        return Geo.distanceKm(lat, lon, l.lat, l.lon)
    }

    /** Bearing from the QTH to the last trusted point, degrees. */
    fun bearingFrom(lat: Double, lon: Double): Double {
        val l = lastTrusted ?: return 0.0
        return Geo.bearingDeg(lat, lon, l.lat, l.lon)
    }

    /** First trusted point: approximate launch time and place. */
    val first: SondeFrame? get() = points.firstOrNull { it.trusted }

    /** Listening duration, seconds. */
    val durationSec: Long
        get() {
            val a = points.firstOrNull()?.heardAtMs ?: return 0L
            val b = points.lastOrNull()?.heardAtMs ?: return 0L
            return ((b - a) / 1000L).coerceAtLeast(0L)
        }

    /** Clears the flight, keeping its identity. */
    fun clear() {
        points.clear()
        last = null
        lastTrusted = null
        burst = null
    }

    /** Keeps one trusted point in [step], to avoid a 10 MB GPX. */
    fun thinned(step: Int = 1): List<SondeFrame> {
        if (step <= 1) return points.filter { it.trusted }
        val t = points.filter { it.trusted }
        return t.filterIndexed { i, _ -> i % step == 0 || i == t.size - 1 }
    }
}

/**
 * Track export as GPX (OsmAnd, hiking GPS) and KML (Google Earth). Both carry
 * the same waypoints: last trusted fix, burst, estimated landing.
 */
object SondeExport {

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun iso(ms: Long): String {
        if (ms <= 0L) return ""
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }

    fun gpx(flight: SondeFlight, step: Int = 1): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"SatMe\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata><name>").append(esc(flight.serial)).append("</name></metadata>\n")
        flight.lastTrusted?.let {
            append(wpt(it.lat, it.lon, it.altM, "Dernier point sûr", iso(it.timeUtcMs)))
        }
        flight.burst?.takeIf { flight.hasBurst }?.let {
            append(wpt(it.lat, it.lon, it.altM, "Éclatement", iso(it.timeUtcMs)))
        }
        flight.estimatedLanding()?.let { (la, lo) ->
            append(wpt(la, lo, 0.0, "Impact estimé", ""))
        }
        append("  <trk><name>").append(esc(flight.serial)).append(" ")
            .append(esc(flight.type)).append("</name><trkseg>\n")
        for (p in flight.thinned(step)) {
            append("      <trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele>"
                .format(java.util.Locale.US, p.lat, p.lon, p.altM))
            if (p.timeUtcMs > 0) append("<time>").append(iso(p.timeUtcMs)).append("</time>")
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    private fun wpt(lat: Double, lon: Double, alt: Double, name: String, time: String) =
        buildString {
            append("  <wpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele>"
                .format(java.util.Locale.US, lat, lon, alt))
            if (time.isNotEmpty()) append("<time>").append(time).append("</time>")
            append("<name>").append(esc(name)).append("</name></wpt>\n")
        }

    fun kml(flight: SondeFlight, step: Int = 1): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        append("  <name>").append(esc(flight.serial)).append("</name>\n")
        flight.lastTrusted?.let { pin("Dernier point sûr", it.lat, it.lon, it.altM) }
        flight.burst?.takeIf { flight.hasBurst }?.let { pin("Éclatement", it.lat, it.lon, it.altM) }
        flight.estimatedLanding()?.let { (la, lo) -> pin("Impact estimé", la, lo, 0.0) }
        append("  <Placemark><name>").append(esc(flight.type))
            .append("</name><LineString><altitudeMode>absolute</altitudeMode>\n")
        append("    <coordinates>")
        for (p in flight.thinned(step)) {
            append("%.6f,%.6f,%.1f ".format(java.util.Locale.US, p.lon, p.lat, p.altM))
        }
        append("</coordinates></LineString></Placemark>\n")
        append("</Document></kml>\n")
    }

    private fun StringBuilder.pin(name: String, lat: Double, lon: Double, alt: Double) {
        append("  <Placemark><name>").append(esc(name)).append("</name>")
        append("<Point><coordinates>%.6f,%.6f,%.1f</coordinates></Point></Placemark>\n"
            .format(java.util.Locale.US, lon, lat, alt))
    }

    /**
     * Log line, human- and machine-readable. Deliberately flat: a spreadsheet
     * will still open it when the app is long gone.
     */
    fun csvHeader() = "utc;serial;type;lat;lon;alt_m;speed_mps;heading_deg;climb_mps;sats;batt_v;freq_hz\n"

    fun csvLine(f: SondeFrame): String = "%s;%s;%s;%.6f;%.6f;%.1f;%.1f;%.0f;%.1f;%d;%.1f;%d\n"
        .format(java.util.Locale.US, iso(f.timeUtcMs), f.serial, f.type,
            f.lat, f.lon, f.altM, f.speedMps, f.headingDeg, f.climbMps,
            f.sats, f.batteryV, f.freqHz)
}
