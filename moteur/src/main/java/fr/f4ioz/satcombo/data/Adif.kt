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

    /** The "satellite" of a terrestrial DATV contact: no satellite fields for it. */
    const val DATV_TERRESTRE = "DATV terrestre"

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
     *
     * Checked against the ADIF 3.1.4 Mode enumeration: **FT8 is a mode of its
     * own** (no submode) while FT4, JS8, Q65 and FST4 are submodes of MFSK;
     * PSK31 a submode of PSK; FreeDV, D-STAR, C4FM and DMR submodes of
     * DIGITALVOICE. FT8 once went out as MFSK/FT8, which no reader expects.
     * The digital names are tested before FM and AM: "C4FM" holds "FM".
     */
    fun modeOf(raw: String?): Pair<String, String> {
        val s = (raw ?: "").uppercase()
        return when {
            s.contains("DATV") || s.contains("ATV") -> "ATV" to ""
            s.contains("FT8") -> "FT8" to ""
            s.contains("FT4") -> "MFSK" to "FT4"
            s.contains("JS8") -> "MFSK" to "JS8"
            s.contains("Q65") -> "MFSK" to "Q65"
            s.contains("FST4") -> "MFSK" to "FST4"
            s.contains("MSK144") -> "MSK144" to ""
            s.contains("JT65") -> "JT65" to ""
            s.contains("RTTY") -> "RTTY" to ""
            s == "MFSK" -> "MFSK" to ""
            s.contains("PSK31") -> "PSK" to "PSK31"
            s.contains("FREEDV") -> "DIGITALVOICE" to "FREEDV"
            s.contains("DSTAR") || s.contains("D-STAR") -> "DIGITALVOICE" to "DSTAR"
            s.contains("C4FM") -> "DIGITALVOICE" to "C4FM"
            s == "DMR" -> "DIGITALVOICE" to "DMR"
            s.contains("SSTV") -> "SSTV" to ""
            s.contains("USB") -> "SSB" to "USB"
            s.contains("LSB") -> "SSB" to "LSB"
            s.contains("SSB") -> "SSB" to ""
            s.contains("CW") -> "CW" to ""
            s.startsWith("FM") || s.contains("FM") -> "FM" to ""
            s.contains("AM") -> "AM" to ""
            s.contains("APRS") || s.contains("AFSK") || s.contains("FSK") ||
                s.contains("BPSK") || s.contains("GMSK") || s.contains("DATA") || s == "PKT" -> "PKT" to ""
            else -> "" to ""
        }
    }

    /**
     * A Maidenhead locator of 2, 4, 6 or 8 characters (field, square,
     * subsquare, extended square). Anything else would get the contact
     * refused, so it is left out of the export instead.
     */
    private val LOCATOR = Regex("^[A-R]{2}([0-9]{2}([A-X]{2}([0-9]{2})?)?)?$")

    fun locatorValide(l: String): Boolean = LOCATOR.matches(l.trim().uppercase())

    /**
     * The big squares of a grid line written as a list ("JN06,JN16"), each
     * reduced to 4 characters, valid ones only, without repeats. ADIF's
     * VUCC_GRIDS and MY_VUCC_GRIDS take exactly two or four of them.
     */
    fun grilles(s: String): List<String> = s.split(',', ';', '/', ' ')
        .map { it.trim().uppercase() }.filter { it.length >= 4 && locatorValide(it) }
        .map { it.take(4) }.distinct()

    /** True for a locator field that actually holds a grid line ("JN06,JN16"). */
    fun estUneGrille(l: String): Boolean = l.contains(',')

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
            // A satellite contact only: a terrestrial DATV one (23 cm) has neither SAT_NAME nor PROP_MODE.
            val satellite = e.satName.isNotBlank() && e.satName != DATV_TERRESTRE
            if (satellite) {
                sb.append(field("SAT_NAME", e.satName))
                sb.append(field("PROP_MODE", "SAT"))
            }
            if (station.isNotBlank()) {
                sb.append(field("STATION_CALLSIGN", station.uppercase()))
                sb.append(field("OPERATOR", operateur.ifBlank { station }.uppercase()))
            }
            sb.append(field("CALL", e.callsign.trim().uppercase()))
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
            if (satellite && e.elevationDeg >= 0.0) {
                sb.append(field("ANT_AZ", "%.1f".format(Locale.US,
                    ((e.azimuthDeg % 360.0) + 360.0) % 360.0)))
                sb.append(field("ANT_EL", "%.1f".format(Locale.US, e.elevationDeg)))
            }
            if (satellite) sb.append(field("SAT_MODE", satMode(e.uplinkMhz, e.downlinkMhz)))
            // **A grid line typed (or imported) into a locator field** —
            // "JN06,JN16" — is not a GRIDSQUARE: it goes to the VUCC_GRIDS
            // fields, and the GRIDSQUARE field gets nothing. A malformed
            // locator is left out rather than sent: one bad field and the
            // whole contact is refused.
            val maGrille = estUneGrille(e.myLocator)
            if (!maGrille && locatorValide(e.myLocator)) sb.append(field("MY_GRIDSQUARE", e.myLocator.trim().uppercase()))
            if (estUneGrille(e.theirLocator)) {
                val g = grilles(e.theirLocator)
                if (g.size == 2 || g.size == 4) sb.append(field("VUCC_GRIDS", g.joinToString(",")))
            } else if (locatorValide(e.theirLocator)) sb.append(field("GRIDSQUARE", e.theirLocator.trim().uppercase()))
            // What the directory told us about the other station, so the remote
            // log does not have to be filled in by hand.
            if (e.nom.isNotBlank()) sb.append(field("NAME", e.nom))
            if (e.qth.isNotBlank()) sb.append(field("QTH", e.qth))
            if (e.courriel.isNotBlank()) sb.append(field("EMAIL", e.courriel))
            // Grid-line operation uses MY_VUCC_GRIDS, but ADIF only accepts two
            // or four adjacent squares. Three (a corner with one square out of
            // reach) would be rejected, so it goes in the comment instead.
            val grids = e.myGrids.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { if (maGrille) grilles(e.myLocator) else emptyList() }
            if (grids.size == 2 || grids.size == 4) {
                sb.append(field("MY_VUCC_GRIDS", grids.joinToString(",")))
            }
            val note = if (grids.size == 3) (e.note + " " + grids.joinToString("/")).trim() else e.note
            sb.append(field("COMMENT", note))
            sb.append("<EOR>\n")
        return sb.toString()
    }
}