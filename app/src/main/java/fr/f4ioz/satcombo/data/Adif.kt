/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ADIF generation, separate from storage.
 *
 * The one format every logger reads (LoTW, Club Log, QRZ, N1MM, Log4OM). It
 * must be exact: one malformed line and the whole import is refused without
 * saying which. No Android dependency, so it is all tested on the JVM.
 */
object Adif {

    /**
     * ADIF band for a frequency in MHz (lower case, no space). Out of band
     * returns empty rather than a wrong band: a missing field imports, a wrong
     * one gets the contact rejected.
     */
    fun band(freqMhz: Double): String = when {
        freqMhz <= 0.0 -> ""
        freqMhz in 1.8..2.0 -> "160m"
        freqMhz in 3.5..4.0 -> "80m"
        freqMhz in 7.0..7.3 -> "40m"
        freqMhz in 10.1..10.15 -> "30m"
        freqMhz in 14.0..14.35 -> "20m"
        freqMhz in 18.068..18.168 -> "17m"
        freqMhz in 21.0..21.45 -> "15m"
        freqMhz in 24.89..24.99 -> "12m"
        freqMhz in 28.0..29.7 -> "10m"
        freqMhz in 50.0..54.0 -> "6m"
        freqMhz in 144.0..148.0 -> "2m"
        freqMhz in 222.0..225.0 -> "1.25m"
        freqMhz in 420.0..450.0 -> "70cm"
        freqMhz in 902.0..928.0 -> "33cm"
        freqMhz in 1240.0..1300.0 -> "23cm"
        freqMhz in 2300.0..2450.0 -> "13cm"
        // Must reach 3 cm: QO-100 downlinks on 10 489 MHz, and stopping at 13 cm
        // sent every QO-100 contact without BAND_RX.
        freqMhz in 3300.0..3500.0 -> "9cm"
        freqMhz in 5650.0..5925.0 -> "6cm"
        freqMhz in 10000.0..10500.0 -> "3cm"
        freqMhz in 24000.0..24250.0 -> "1.25cm"
        else -> ""
    }

    /**
     * Satellite band letter for SAT_MODE: "U/V" means uplink on 70 cm,
     * downlink on 2 m.
     */
    fun satBandLetter(freqMhz: Double): String = when {
        freqMhz in 21.0..21.45 -> "H"
        freqMhz in 28.0..29.7 -> "A"
        freqMhz in 144.0..148.0 -> "V"
        freqMhz in 420.0..450.0 -> "U"
        freqMhz in 1240.0..1300.0 -> "L"
        freqMhz in 2300.0..2450.0 -> "S"
        freqMhz in 5650.0..5925.0 -> "C"
        freqMhz in 10000.0..10500.0 -> "X"
        else -> ""
    }

    /** "U/V" from uplink and downlink; empty if either is missing. */
    fun satMode(uplinkMhz: Double, downlinkMhz: Double): String {
        val u = satBandLetter(uplinkMhz)
        val d = satBandLetter(downlinkMhz)
        return if (u.isBlank() || d.isBlank()) "" else "$u/$d"
    }

    /**
     * ADIF mode from SatNOGS, which mixes modulations and protocol names. SSB
     * is MODE=SSB with SUBMODE USB or LSB; the rest maps to FM, CW or DATA.
     */
    fun modeOf(raw: String?): Pair<String, String> {
        val s = (raw ?: "").uppercase()
        return when {
            s.contains("USB") -> "SSB" to "USB"
            s.contains("LSB") -> "SSB" to "LSB"
            s.contains("SSB") -> "SSB" to ""
            s.contains("CW") -> "CW" to ""
            s.startsWith("FM") || s.contains("FM") -> "FM" to ""
            s.contains("AM") -> "AM" to ""
            s.contains("SSTV") -> "SSTV" to ""
            s.contains("FT4") -> "MFSK" to "FT4"
            s.contains("FT8") -> "MFSK" to "FT8"
            s.contains("APRS") || s.contains("AFSK") || s.contains("FSK") ||
                s.contains("BPSK") || s.contains("GMSK") || s.contains("DATA") -> "PKT" to ""
            else -> "" to ""
        }
    }

    /**
     * One ADIF field. The length counts UTF-8 bytes: most readers go byte by
     * byte and would slip one character per accent otherwise.
     */
    fun field(tag: String, value: String): String {
        if (value.isBlank()) return ""
        val v = value.replace('\n', ' ').replace('\r', ' ').trim()
        return "<$tag:${v.toByteArray(Charsets.UTF_8).size}>$v"
    }

    /**
     * The whole file. [station] goes into STATION_CALLSIGN and OPERATOR, or a
     * LoTW import cannot tell whose contacts they are.
     */
    fun export(entries: List<LogEntry>, station: String = "", programVersion: String = ""): String {
        val sb = StringBuilder()
        sb.append("ADIF export — SatMe\n")
        sb.append(field("ADIF_VER", "3.1.4"))
        sb.append(field("PROGRAMID", "SatMe"))
        if (programVersion.isNotBlank()) sb.append(field("PROGRAMVERSION", programVersion))
        sb.append("\n<EOH>\n")
        val df = SimpleDateFormat("yyyyMMdd", Locale.US)
        val tf = SimpleDateFormat("HHmmss", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        tf.timeZone = TimeZone.getTimeZone("UTC")
        // **No callsign, no record.**
        //
        // The guard lives here, the one place that builds ADIF, not in the
        // callers: one caller once lacked it and anonymous entries were
        // exported. A QSO without CALL is not a contact at all.
        entries.filter { it.callsign.isNotBlank() }.forEach { e ->
            sb.append(enregistrement(e, station))
        }
        return sb.toString()
    }

    /**
     * One contact, without file header, for online uploads (**one record at a
     * time**). Extracted here rather than duplicated: a second ADIF builder
     * would drift — that is how FREQ and FREQ_RX stayed swapped unnoticed.
     *
     * Empty without a callsign: same rule everywhere.
     *
     * [station] is the station callsign as the online log's profile has it
     * ("F4IOZ/M"): Wavelog skips a contact whose STATION_CALLSIGN differs.
     * [operateur] is who operated, the plain callsign.
     */
    fun enregistrement(e: LogEntry, station: String = "", operateur: String = station): String {
        if (e.callsign.isBlank()) return ""
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyyMMdd", Locale.US)
        val tf = SimpleDateFormat("HHmmss", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        tf.timeZone = TimeZone.getTimeZone("UTC")
            val d = Date(e.timeMs)
            sb.append(field("QSO_DATE", df.format(d)))
            sb.append(field("TIME_ON", tf.format(d)))
            sb.append(field("SAT_NAME", e.satName))
            sb.append(field("PROP_MODE", "SAT"))
            if (station.isNotBlank()) {
                sb.append(field("STATION_CALLSIGN", station.uppercase()))
                sb.append(field("OPERATOR", operateur.ifBlank { station }.uppercase()))
            }
            sb.append(field("CALL", e.callsign))
            val (mode, sub) = modeOf(e.mode)
            sb.append(field("MODE", mode))
            sb.append(field("SUBMODE", sub))
            sb.append(field("RST_SENT", e.rstSent))
            sb.append(field("RST_RCVD", e.rstRcvd))
            // **FREQ is the transmit frequency, FREQ_RX the receive one.**
            //
            // ADIF defines them from the logging station's side: FREQ/BAND is
            // what it transmits on, FREQ_RX/BAND_RX what it listens on. They
            // were once swapped (downlink in FREQ): V/U contacts claimed a
            // 435 MHz uplink, and LoTW could not match the other station.
            if (e.uplinkMhz > 0.0) {
                sb.append(field("FREQ", "%.6f".format(Locale.US, e.uplinkMhz)))
                sb.append(field("BAND", band(e.uplinkMhz)))
            }
            if (e.downlinkMhz > 0.0) {
                sb.append(field("FREQ_RX", "%.6f".format(Locale.US, e.downlinkMhz)))
                sb.append(field("BAND_RX", band(e.downlinkMhz)))
            }
            // Satellite azimuth and elevation at contact time. ADIF means them
            // for the station antenna — which, on satellites, points at the bird.
            if (e.elevationDeg >= 0.0) {
                sb.append(field("ANT_AZ", "%.1f".format(Locale.US,
                    ((e.azimuthDeg % 360.0) + 360.0) % 360.0)))
                sb.append(field("ANT_EL", "%.1f".format(Locale.US, e.elevationDeg)))
            }
            sb.append(field("SAT_MODE", satMode(e.uplinkMhz, e.downlinkMhz)))
            if (e.myLocator.isNotBlank()) sb.append(field("MY_GRIDSQUARE", e.myLocator))
            if (e.theirLocator.isNotBlank()) sb.append(field("GRIDSQUARE", e.theirLocator))
            // What the directory told us about the other station, so the remote
            // log does not have to be filled in by hand.
            if (e.nom.isNotBlank()) sb.append(field("NAME", e.nom))
            if (e.qth.isNotBlank()) sb.append(field("QTH", e.qth))
            if (e.courriel.isNotBlank()) sb.append(field("EMAIL", e.courriel))
            // Grid-line operation uses MY_VUCC_GRIDS, but ADIF only accepts two
            // or four adjacent squares. Three (a corner with one square out of
            // reach) would be rejected, so it goes in the comment instead.
            val grids = e.myGrids.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (grids.size == 2 || grids.size == 4) {
                sb.append(field("MY_VUCC_GRIDS", grids.joinToString(",")))
            }
            val note = if (grids.size == 3) (e.note + " " + grids.joinToString("/")).trim() else e.note
            sb.append(field("COMMENT", note))
            sb.append("<EOR>\n")
        return sb.toString()
    }
}