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
 * Where the radiosondes audible from France are launched, and on what
 * frequency.
 *
 * Coordinates are launch pads, not weather stations: sometimes 2 km apart,
 * which matters when confirming where a balloon came from. Frequencies change
 * overnight, so they only point the dongle; the transmitted serial identifies
 * the sonde.
 *
 * Launch times are the detail everyone misses: Météo-France launches at 23:11
 * and 11:11 UTC, 49 minutes *before* the synoptic hour, so the balloon is at
 * 30 km on the hour. Listening at midnight or noon is too late.
 */
object SondeSites {

    data class Site(
        /** WMO id, or a free string for occasional sites. */
        val wmo: String,
        val name: String,
        val country: String,
        val lat: Double,
        val lon: Double,
        /** Site altitude, metres. */
        val altM: Int,
        /** Usual sonde type: "RS41", "M20", "M10", "DFM". */
        val sonde: String,
        /** Observed frequencies, kHz. */
        val freqKhz: List<Int>,
        /** Launch times, decimal UTC hours (23.183 = 23:11). */
        val launchesUtc: List<Double>,
        /** Occasional site: campaigns, research, not daily. */
        val occasional: Boolean = false
    ) {
        /** Main frequency, Hz. */
        val mainHz: Long get() = (freqKhz.firstOrNull() ?: 0) * 1000L
    }

    /** Météo-France night launch, 23:11 UTC. */
    const val MF_NIGHT = 23.0 + 11.0 / 60.0

    /** Météo-France day launch, 11:11 UTC. */
    const val MF_DAY = 11.0 + 11.0 / 60.0

    private val MF = listOf(MF_NIGHT, MF_DAY)
    private val SYNOPTIC2 = listOf(0.0, 12.0)
    private val SYNOPTIC4 = listOf(0.0, 6.0, 12.0, 18.0)

    /** Operational French stations. */
    val FRANCE = listOf(
        Site("07110", "Brest-Guipavas", "FR", 48.44425, -4.41238, 95, "M20",
            listOf(404_000), MF),
        Site("07145", "Trappes", "FR", 48.773097, 2.009377, 168, "M10",
            listOf(401_000), MF),
        Site("07510", "Bordeaux-Mérignac", "FR", 44.831398, -0.691992, 49, "M20",
            listOf(402_800, 402_400), MF),
        Site("07645", "Nîmes-Courbessac", "FR", 43.856576, 4.405732, 60, "M20",
            listOf(405_000, 403_800), MF),
        Site("07761", "Ajaccio", "FR", 41.918003, 8.792084, 6, "M20",
            listOf(403_000), MF))

    /** Occasional French sites: campaigns, research, manufacturer tests. */
    val FRANCE_OCCASIONAL = listOf(
        Site("LAN", "CMS Lannion", "FR", 48.7506, -3.4729, 80, "M20",
            listOf(402_000), emptyList(), occasional = true),
        Site("QUI", "Quimper", "FR", 47.99975, -4.17785, 90, "M20",
            listOf(403_000), emptyList(), occasional = true),
        Site("OHP", "OHP Saint-Michel", "FR", 43.9314, 5.7125, 650, "M10",
            listOf(403_500), emptyList(), occasional = true),
        Site("URY", "Ury (Meteomodem)", "FR", 48.3183, 2.5936, 120, "M20",
            listOf(403_000), emptyList(), occasional = true),
        Site("TRA", "Trainou", "FR", 47.9647, 2.1125, 130, "M10",
            listOf(403_000), emptyList(), occasional = true),
        Site("SIR", "SIRTA Palaiseau", "FR", 48.7130, 2.2080, 156, "M10",
            listOf(403_000), emptyList(), occasional = true),
        Site("BOU", "Bourges", "FR", 47.0592, 2.3697, 161, "DFM",
            listOf(403_000), emptyList(), occasional = true))

    /** Neighbours whose sondes regularly reach France. */
    val NEIGHBOURS = listOf(
        Site("03808", "Camborne", "GB", 50.218698, -5.326914, 88, "RS41",
            listOf(405_700), SYNOPTIC2),
        Site("03953", "Valentia", "IE", 51.9381, -10.2433, 14, "RS41",
            listOf(400_500), SYNOPTIC2),
        Site("03743", "Larkhill", "GB", 51.2000, -1.8000, 132, "RS41",
            listOf(403_700), SYNOPTIC4),
        Site("06447", "Uccle", "BE", 50.7973, 4.3581, 100, "RS41",
            listOf(403_000), listOf(12.0)),
        Site("06458", "Beauvechain", "BE", 50.7586, 4.7683, 110, "DFM",
            listOf(403_000), SYNOPTIC2),
        Site("06260", "De Bilt", "NL", 52.1017, 5.1783, 4, "RS41",
            listOf(403_900), SYNOPTIC2),
        Site("06610", "Payerne", "CH", 46.8123, 6.9422, 491, "RS41",
            listOf(403_500), SYNOPTIC2),
        Site("10410", "Essen", "DE", 51.4053, 6.9672, 153, "RS41",
            listOf(405_300), SYNOPTIC2),
        Site("10618", "Idar-Oberstein", "DE", 49.6928, 7.3300, 376, "RS41",
            listOf(402_700), SYNOPTIC4),
        Site("10739", "Stuttgart-Schnarrenberg", "DE", 48.8281, 9.2000, 315, "RS41",
            listOf(404_500), SYNOPTIC2),
        Site("16045", "Cuneo-Levaldigi", "IT", 44.5478, 7.6122, 384, "RS41",
            listOf(403_700), SYNOPTIC2),
        Site("16044", "Udine-Campoformido", "IT", 46.0333, 13.1833, 94, "RS41",
            listOf(404_000), SYNOPTIC2),
        Site("08190", "Barcelone", "ES", 41.3833, 2.1167, 98, "M20",
            listOf(403_000), SYNOPTIC2),
        Site("08430", "Murcie", "ES", 38.0000, -1.1667, 62, "M10",
            listOf(403_400), SYNOPTIC2))

    /** Everything, occasional sites included. */
    val ALL: List<Site> = FRANCE + FRANCE_OCCASIONAL + NEIGHBOURS

    /** Sites sorted by distance from a QTH. */
    fun nearest(lat: Double, lon: Double, max: Int = 6,
                includeOccasional: Boolean = false): List<Pair<Site, Double>> =
        (if (includeOccasional) ALL else ALL.filter { !it.occasional })
            .map { it to Geo.distanceKm(lat, lon, it.lat, it.lon) }
            .sortedBy { it.second }
            .take(max)

    /**
     * Most likely launch site for a sonde heard at this position. Only a guess:
     * a balloon drifts 50 to 120 km, so beyond 200 km we don't say.
     */
    fun likelyOrigin(lat: Double, lon: Double, type: String = ""): Site? {
        val cands = ALL.filter { type.isBlank() || it.sonde == type }
            .ifEmpty { ALL }
        val best = cands.minByOrNull { Geo.distanceKm(lat, lon, it.lat, it.lon) } ?: return null
        return if (Geo.distanceKm(lat, lon, best.lat, best.lon) <= 200.0) best else null
    }

    // ---------------------------------------------------------- scan plan

    /** First scan frequency, Hz. */
    const val SCAN_FROM_HZ = 400_150_000L

    /**
     * Last scan frequency, Hz.
     *
     * The met band officially goes to 406 MHz, but we stop at 405.9: above
     * start the COSPAS-SARSAT distress beacons, and there is no reason to sweep
     * a receiver over them. Hard-coded on purpose, not a setting.
     */
    const val SCAN_TO_HZ = 405_900_000L

    /** Scan step, Hz: sondes sit on 10 kHz multiples. */
    const val SCAN_STEP_HZ = 10_000L

    /** Is the frequency in the allowed listening band? */
    fun inBand(hz: Long): Boolean = hz in SCAN_FROM_HZ..SCAN_TO_HZ

    /**
     * Frequencies to try, in order: nearby stations first, then the full
     * sweep. Looking where something is known to be saves minutes per launch.
     */
    fun scanPlan(lat: Double, lon: Double): List<Long> {
        val out = LinkedHashSet<Long>()
        for ((site, _) in nearest(lat, lon, max = 8, includeOccasional = true)) {
            for (k in site.freqKhz) {
                val hz = k * 1000L
                if (inBand(hz)) out += hz
            }
        }
        var hz = SCAN_FROM_HZ
        while (hz <= SCAN_TO_HZ) { out += hz; hz += SCAN_STEP_HZ }
        return out.toList()
    }

    /** Recommended filter width for a sonde type, Hz. */
    fun bandwidthFor(sonde: String): Int = when (sonde) {
        "M10", "M20" -> Meteomodem.BANDWIDTH_HZ
        else -> Rs41.BANDWIDTH_HZ
    }

    /** Bit rate for a sonde type, baud. */
    fun baudFor(sonde: String): Double = when (sonde) {
        "M10" -> Meteomodem.M10_BAUD
        "M20" -> Meteomodem.M20_BAUD
        else -> Rs41.BAUD
    }

    /**
     * Minutes to the site's next launch, or -1 if it has no schedule.
     * [nowUtcMinutes] is minutes since UTC midnight.
     */
    fun minutesToNextLaunch(site: Site, nowUtcMinutes: Int): Int {
        if (site.launchesUtc.isEmpty()) return -1
        var best = Int.MAX_VALUE
        for (h in site.launchesUtc) {
            val m = Math.round(h * 60.0).toInt()
            var d = m - nowUtcMinutes
            if (d < 0) d += 24 * 60
            if (d < best) best = d
        }
        return best
    }

    /**
     * Inside a useful listening window for this site? From 10 minutes before
     * launch to 3 hours after: a full flight, ascent, burst and descent.
     */
    fun listeningNow(site: Site, nowUtcMinutes: Int): Boolean {
        if (site.launchesUtc.isEmpty()) return false
        for (h in site.launchesUtc) {
            val m = Math.round(h * 60.0).toInt()
            var since = nowUtcMinutes - m
            if (since < 0) since += 24 * 60
            if (since <= 180 || since >= 24 * 60 - 10) return true
        }
        return false
    }
}
