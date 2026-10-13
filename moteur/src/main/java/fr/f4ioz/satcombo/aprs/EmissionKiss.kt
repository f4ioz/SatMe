/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf

/**
 * What the station sends through a KISS radio, and when it may — the same
 * on the phone and on SatMe-PC: who sends (the callsign and its SSID), by
 * which path, from where, and every check before the radio keys.
 */
object EmissionKiss {

    /**
     * Why not hand a frame to the KISS radio now, as a message key, or null.
     * Its frequency must be an APRS one: read by SatMe before KISS, or
     * vouched for by the operator ([frequenceConfirmee]) when it could not
     * be read; then the rules of any transmission ([AprsEmission.refus]).
     */
    fun refus(e: LienKiss.Etat, frequenceConfirmee: Boolean, indicatif: String, derniereMs: Long, maintenant: Long): String? {
        if (!e.connecte) return "aprs_kiss_non_connecte"
        val lue = e.frequenceHz
        // Set by SatMe itself on an APRS frequency: no need for the operator to vouch for it.
        val connue = lue != null && AprsEmission.FENETRES.any { lue in it }
        if (!frequenceConfirmee && !connue) return "aprs_kiss_confirmer"
        return AprsEmission.refus(indicatif, maintenant, derniereMs, lue ?: 145_825_000L, 0x05, false, false)?.let { "aprs_tx_refus_$it" }
    }

    /** The message of a refusal, in the operator's language. */
    fun texte(cle: String, e: LienKiss.Etat): String =
        if (cle == "aprs_tx_refus_frequence") tf(cle, "%.4f".format(java.util.Locale.US, (e.frequenceHz ?: 0L) / 1e6)) else t(cle)

    /** The sender: the callsign from the settings, with the chosen SSID. */
    fun source(s: SettingsStore): String = s.callsign.trim().uppercase().let {
        if (s.aprsSsid > 0 && it.isNotEmpty()) "$it-${s.aprsSsid}" else it
    }

    /** Next message number (1..999), so an ack can be matched. */
    fun numeroSuivant(s: SettingsStore): String {
        val n = s.aprsNumero % 999 + 1
        s.aprsNumero = n
        return n.toString()
    }

    /** The path for what is sent now: the ISS digipeater, or the terrestrial network. */
    fun chemin(travail: String, issEnVue: Boolean): List<String> = when (travail) {
        "ISS" -> listOf("ARISS")
        "TERRE" -> listOf("WIDE1-1", "WIDE2-1")
        else -> if (issEnVue) listOf("ARISS") else listOf("WIDE1-1", "WIDE2-1")
    }

    /**
     * Where a position is sent from: the QTH, or — with the approximate
     * position option — the QTH shifted by a fixed random offset under 500 m
     * (drawn once, kept in the settings).
     */
    fun position(s: SettingsStore, lat: Double, lon: Double): Pair<Double, Double> {
        if (!s.aprsPositionFloue) return lat to lon
        var n = s.aprsFlouNordM; var e = s.aprsFlouEstM
        if (n.isNaN() || e.isNaN()) {
            // Uniform over the disc: radius from the square root, so the centre is not favoured.
            val r = 500.0 * kotlin.math.sqrt(kotlin.random.Random.nextDouble())
            val a = kotlin.random.Random.nextDouble(0.0, 2 * Math.PI)
            n = (r * kotlin.math.cos(a)).toFloat(); e = (r * kotlin.math.sin(a)).toFloat()
            s.aprsFlouNordM = n; s.aprsFlouEstM = e
        }
        return (lat + n / 111_320.0) to (lon + e / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)))
    }

    /** The ISS beacon may go now through the KISS radio: connected, on 145.825 as read by SatMe. */
    fun balisePrete(e: LienKiss.Etat): Boolean = e.connecte && e.frequenceHz != null && e.frequenceHz in AprsEmission.FENETRES[0]
}
