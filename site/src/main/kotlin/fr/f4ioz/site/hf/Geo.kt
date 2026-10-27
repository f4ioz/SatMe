/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.hf

import fr.f4ioz.satcombo.domain.Dxcc
import fr.f4ioz.satcombo.domain.IndicatifCompose
import fr.f4ioz.satcombo.location.Maidenhead
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The HF pages' one band table: the label of a frequency, the order the
 * pages show them in, wspr.live's band codes. Labels on the wire are lower
 * case (« 20m »), as the old site's pages expected.
 */
object Bandes {

    /** Label, lowest and highest kHz (lowest included, highest excluded). */
    class Bande(val nom: String, val bas: Double, val haut: Double)

    val TOUTES = listOf(
        Bande("160m", 1800.0, 2000.0), Bande("80m", 3500.0, 4000.0), Bande("60m", 5300.0, 5500.0),
        Bande("40m", 7000.0, 7300.0), Bande("30m", 10100.0, 10150.0), Bande("20m", 14000.0, 14350.0),
        Bande("17m", 18068.0, 18168.0), Bande("15m", 21000.0, 21450.0), Bande("12m", 24890.0, 24990.0),
        Bande("10m", 28000.0, 29700.0), Bande("6m", 50000.0, 54000.0), Bande("4m", 70000.0, 70500.0),
        Bande("2m", 144000.0, 148000.0), Bande("70cm", 430000.0, 440000.0),
    )

    /** The HF bands (and 6 m) in the order the pages list them. */
    val ORDRE_HF = listOf("160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m")

    val NOMS: Set<String> = TOUTES.map { it.nom }.toSet()

    /** The band of [khz], "" outside them. */
    fun deKhz(khz: Double): String = TOUTES.firstOrNull { khz >= it.bas && khz < it.haut }?.nom.orEmpty()

    /** wspr.live's integer band codes (MHz, rounded down, -1 for 2190 m). */
    val WSPR: Map<Int, String> = linkedMapOf(-1 to "2190m", 0 to "630m", 1 to "160m", 3 to "80m", 5 to "60m", 7 to "40m",
        10 to "30m", 14 to "20m", 18 to "17m", 21 to "15m", 24 to "12m", 28 to "10m", 50 to "6m", 70 to "4m",
        144 to "2m", 432 to "70cm", 1296 to "23cm")

    private val CODE_WSPR: Map<String, Int> = WSPR.entries.associate { it.value to it.key }

    /** A code wspr.live has but no band of ours (13, 40, 430…: MHz) is said in MHz — « 40m » would be 40 MHz taken for the 40 m band. */
    fun wsprNom(code: Int): String = WSPR[code] ?: "$code MHz"

    /** The code of a label, null when wspr.live has none (never interpolate anything else into SQL). */
    fun wsprCode(nom: String): Int? = CODE_WSPR[nom]

    /** A band label from a visitor, kept only when it is one of ours. */
    fun nette(s: String?): String = s?.trim()?.lowercase()?.takeIf { it in NOMS }.orEmpty()
}

/** Locators, distances and bearings on a spherical Earth. */
object Geo {

    const val R_KM = 6371.0

    private val LOC = Regex("^[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2}([A-X]{2})?)?)?$")

    /** A 4/6/8/10-character locator's centre, read to 6 characters (SatMe's Maidenhead); null when not one. */
    fun locator(s: String?): Pair<Double, Double>? {
        val u = s?.trim()?.uppercase() ?: return null
        if (u.length < 4 || !LOC.matches(u)) return null
        return Maidenhead.toLatLon(if (u.length >= 6) u.take(6) else u.take(4))
    }

    /** A locator the site keeps as typed: upper case, 4 or 6 characters, else null. */
    fun locatorNet(s: String?): String? = s?.trim()?.uppercase()?.takeIf { Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$").matches(it) }

    fun distanceKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = Math.PI / 180
        val a = sin((la2 - la1) * r / 2).let { it * it } + cos(la1 * r) * cos(la2 * r) * sin((lo2 - lo1) * r / 2).let { it * it }
        return 2 * R_KM * asin(min(1.0, sqrt(a)))
    }

    fun azimut(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = Math.PI / 180
        val y = sin((lo2 - lo1) * r) * cos(la2 * r)
        val x = cos(la1 * r) * sin(la2 * r) - sin(la1 * r) * cos(la2 * r) * cos((lo2 - lo1) * r)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Longitude difference folded into -180..180. */
    fun ecartLon(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0
}

/**
 * Where a callsign is, roughly (the country's centre), and its continent:
 * SatMe's DXCC table first (the location prefix wins, /P /M /MM /AM /QRP /A /B
 * are operating suffixes, not countries — the old site put a DL1ABC/M in
 * England and a /A in the USA), then the old site's hand-made prefix table
 * for the entities SatMe has no centre for.
 */
object IndicatifGeo {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, DoubleArray>()
    private val AUCUN = DoubleArray(0)

    /** The rough position of [indicatif] (RBN's « -# » skimmer mark ignored), null when unknown or at sea/in the air. */
    fun position(indicatif: String): Pair<Double, Double>? {
        val brut = indicatif.trim().uppercase().substringBefore('-')
        if (brut.isEmpty() || brut.length > 20) return null
        val v = cache[brut] ?: calcule(brut).also { if (cache.size < 50_000) cache[brut] = it }
        return if (v.isEmpty()) null else v[0] to v[1]
    }

    private fun calcule(brut: String): DoubleArray {
        val d = IndicatifCompose.decoupe(brut)
        if (d.maritime || d.aeronautique) return AUCUN
        Dxcc.entite(brut)?.let { e -> val la = e.lat; val lo = e.lon; if (la != null && lo != null) return doubleArrayOf(la, lo) }
        val lieu = d.lieu.ifEmpty { d.base }
        return prefixe(lieu) ?: AUCUN
    }

    /** The continent (EU, NA, SA, AS, AF, OC), "" when unknown. */
    fun continent(indicatif: String): String = runCatching { Dxcc.entite(indicatif.substringBefore('-'))?.continent }.getOrNull().orEmpty()

    private fun prefixe(p: String): DoubleArray? {
        val cc = p.filter { it.isLetterOrDigit() }
        for (n in minOf(4, cc.length) downTo 1) PREFIXES[cc.take(n)]?.let { return it }
        return null
    }

    // The old site's prefix → centre table (dxcc_geo.py), for what Dxcc has no centre for.
    private val PREFIXES: Map<String, DoubleArray> = buildMap {
        fun p(lat: Double, lon: Double, vararg k: String) { for (x in k) put(x, doubleArrayOf(lat, lon)) }
        p(39.0, -98.0, "K", "W", "N", "A"); p(20.8, -156.3, "KH6"); p(13.4, 144.8, "KH2"); p(64.0, -150.0, "KL"); p(18.2, -66.5, "KP4")
        p(17.7, -64.8, "KP2"); p(56.0, -106.0, "VE", "VA"); p(48.0, -56.0, "VO"); p(64.0, -110.0, "VY"); p(54.0, -125.0, "VE7"); p(23.0, -102.0, "XE", "XF")
        p(51.0, 10.0, "DL", "DA", "DB", "DC", "DD", "DF", "DG", "DH", "DJ", "DK", "DM", "DO", "DP"); p(47.0, 2.0, "F", "TM", "HW")
        p(53.0, -1.5, "G", "M", "2E"); p(56.5, -4.0, "GM", "MM"); p(52.0, -3.5, "GW", "MW"); p(54.6, -6.5, "GI", "MI"); p(54.2, -4.5, "GD")
        p(49.45, -2.58, "GU"); p(49.2, -2.13, "GJ"); p(53.4, -8.0, "EI", "EJ"); p(42.0, 12.5, "I", "IZ", "IK", "IW", "IU"); p(40.0, 9.0, "IS")
        p(40.0, -3.7, "EA", "EB", "EC", "ED", "EE"); p(39.6, 2.9, "EA6"); p(28.3, -16.5, "EA8"); p(35.9, -5.3, "EA9"); p(39.5, -8.0, "CT", "CR")
        p(32.7, -16.9, "CT3"); p(38.5, -28.0, "CU"); p(52.1, 5.3, "PA", "PB", "PC", "PD", "PE", "PH"); p(50.6, 4.6, "ON", "OO", "OR", "OT"); p(47.6, 14.1, "OE")
        p(46.8, 8.2, "HB", "HB9"); p(47.15, 9.55, "HB0"); p(49.8, 15.5, "OK", "OL"); p(48.7, 19.5, "OM"); p(52.0, 19.0, "SP", "SQ", "SN", "SO", "SR")
        p(62.0, 26.0, "OH", "OG", "OF"); p(60.2, 20.0, "OH0"); p(61.0, 9.0, "LA", "LB", "LG", "LN"); p(62.0, 15.0, "SM", "SA", "SB", "SK", "7S", "8S")
        p(56.0, 9.5, "OZ", "OU", "OV", "5P", "5Q"); p(42.7, 25.5, "LZ"); p(45.9, 25.0, "YO", "YP", "YQ", "YR"); p(44.0, 20.8, "YU", "YT"); p(46.1, 14.8, "S5")
        p(45.1, 15.2, "9A"); p(44.0, 18.0, "E7"); p(41.6, 21.7, "Z3"); p(41.0, 20.0, "ZA"); p(47.2, 19.5, "HA", "HG"); p(39.0, 22.0, "SV", "SW", "SX", "SY", "SZ", "J4")
        p(55.2, 23.9, "LY"); p(49.8, 6.1, "LX"); p(58.6, 25.0, "ES"); p(56.9, 24.6, "YL"); p(64.9, -19.0, "TF"); p(35.9, 14.4, "9H"); p(43.9, 12.4, "T7")
        p(43.7, 7.4, "3A"); p(42.5, 1.5, "C3"); p(36.1, -5.3, "ZB"); p(46.2, 6.1, "4U"); p(47.0, 28.8, "ER"); p(42.5, 19.3, "4O"); p(42.0, 9.0, "TK"); p(41.9, 12.45, "1A")
        p(48.4, 31.2, "UR", "US", "UT", "UX", "UY", "EM", "EN", "EO"); p(53.7, 28.0, "EU", "EV", "EW")
        p(56.0, 38.0, "UA", "R", "RA", "RK", "RN", "RU", "RV", "RW", "RX", "RZ", "RT", "RM"); p(57.0, 65.0, "UA9", "RA9", "R9"); p(62.0, 105.0, "UA0", "RA0", "R0")
        p(36.0, 138.0, "JA", "JE", "JF", "JG", "JH", "JI", "JJ", "JK", "JN", "JO", "JP", "JR", "7K", "7L", "7M", "7N", "8J")
        p(35.0, 105.0, "BA", "BD", "BG", "BH", "BY"); p(23.7, 121.0, "BV"); p(36.5, 127.8, "HL", "DS", "6K"); p(22.3, 114.2, "VR"); p(-2.0, 118.0, "YB", "YC", "YD", "YE")
        p(21.0, 79.0, "VU"); p(1.35, 103.8, "9V"); p(15.0, 101.0, "HS", "E2"); p(16.0, 107.0, "XV"); p(4.0, 102.0, "9M"); p(46.8, 103.0, "JT"); p(31.5, 34.8, "4X", "4Z")
        p(21.0, 57.0, "A4"); p(24.0, 54.0, "A6"); p(25.3, 51.2, "A7"); p(26.0, 50.5, "A9"); p(29.3, 47.9, "9K"); p(24.0, 45.0, "HZ", "7Z"); p(35.0, 38.0, "YK")
        p(33.9, 35.5, "OD"); p(31.0, 36.0, "JY"); p(32.0, 53.0, "EP"); p(40.1, 45.0, "EK"); p(40.4, 47.9, "4J", "4K"); p(41.4, 64.6, "UK"); p(41.2, 74.8, "EX")
        p(-25.0, 134.0, "VK", "AX"); p(-29.0, 167.0, "VK9"); p(-41.0, 174.0, "ZL", "ZM"); p(-21.3, 165.5, "FK"); p(-17.7, -149.4, "FO"); p(-17.8, 178.0, "3D2")
        p(-15.4, 166.9, "YJ"); p(-14.3, -170.7, "KH8"); p(-29.0, 24.0, "ZS", "ZR"); p(32.0, -6.0, "CN", "CN2"); p(27.0, 30.0, "SU"); p(28.0, 2.6, "7X"); p(34.0, 9.0, "3V")
        p(27.0, 17.0, "5A"); p(-0.8, 11.6, "TR"); p(7.5, -5.5, "TU"); p(9.5, 7.0, "5N"); p(7.9, -1.0, "9G"); p(-13.5, 28.0, "9J"); p(0.2, 37.9, "5Z"); p(-6.0, 35.0, "5H")
        p(8.0, 38.0, "ET"); p(-22.0, 17.0, "V5"); p(-19.0, 29.8, "Z2"); p(-20.2, 57.5, "3B8"); p(-21.1, 55.5, "FR"); p(-19.0, 46.7, "5R"); p(16.0, -24.0, "D4"); p(14.5, -14.4, "6W")
        p(-10.0, -55.0, "PY", "PP", "PT", "PU", "PR", "PV", "PW", "ZV", "ZW", "ZX", "ZY", "ZZ"); p(-38.0, -65.0, "LU", "LW", "AY", "AZ", "L2")
        p(-33.0, -71.0, "CE", "CA", "CB", "CC", "XQ", "3G"); p(-33.0, -56.0, "CX"); p(4.6, -74.0, "HK", "HJ", "5J", "5K"); p(8.0, -66.0, "YV", "YY", "4M")
        p(-12.0, -77.0, "OA"); p(-1.8, -78.0, "HC", "HD"); p(-16.5, -68.1, "CP"); p(-25.3, -57.6, "ZP"); p(4.0, -56.0, "PZ"); p(6.8, -58.2, "8R"); p(4.0, -53.0, "FY")
        p(12.2, -69.0, "PJ"); p(12.5, -70.0, "P4"); p(19.0, -70.7, "HI"); p(19.0, -72.3, "HH"); p(22.0, -80.0, "CO", "CM"); p(14.6, -61.0, "FM"); p(16.2, -61.6, "FG")
        p(13.1, -59.6, "8P"); p(10.7, -61.5, "9Y"); p(19.3, -81.2, "ZF"); p(24.7, -77.9, "C6"); p(18.1, -77.3, "6Y"); p(10.0, -84.0, "TI", "TE"); p(15.5, -90.3, "TG")
        p(8.5, -80.0, "HP"); p(15.0, -86.5, "HR"); p(12.9, -85.2, "YN"); p(13.8, -88.9, "YS"); p(17.2, -88.5, "V3")
    }
}
