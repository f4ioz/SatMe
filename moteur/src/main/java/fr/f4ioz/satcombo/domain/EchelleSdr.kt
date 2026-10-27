/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Transmitter
import org.json.JSONArray
import org.json.JSONObject

/**
 * The SDR waterfall's annotated scale: every known satellite downlink that
 * falls in the receiver's view, where this station hears it now (Doppler and
 * the satellite's own « correction manuelle » applied), coloured by whether
 * it is up, rising soon or down; a linear transponder as a band whose edges
 * follow the Doppler. And what a click on the scale means: follow a
 * transmitter, tune inside a transponder (the place kept relative to the
 * band as the Doppler moves it), or a plain terrestrial frequency.
 *
 * Pure: positions and passes come from the caller.
 */
object EchelleSdr {

    /** Rising « soon »: AOS within this. */
    const val BIENTOT_MS = 5 * 60_000L

    /** Up (green), rising within [BIENTOT_MS] (white), down (grey). */
    enum class Etat(val cle: String) { VISIBLE("visible"), BIENTOT("bientot"), SOUS("sous") }

    /**
     * One satellite as seen from here at [maintenant]: its elevation and
     * azimuth, its range rate now and a second later (the Doppler's drift),
     * its pass under way or next (AOS, LOS; null when none is known), its
     * correction (Hz, added to every downlink as the receiver does).
     */
    data class Sat(
        val catnum: Int,
        val nom: String,
        val elevationDeg: Double,
        val azimutDeg: Double,
        val rangeRateKmS: Double,
        val rangeRate1sKmS: Double = rangeRateKmS,
        val aosMs: Long? = null,
        val losMs: Long? = null,
        val correctionHz: Long = 0L,
        val favori: Boolean = false,
    )

    /**
     * One downlink on the scale: sky Hz now ([basHz] == [hautHz] for a single
     * frequency), at rest, its drift (Hz/s), its Doppler at the middle.
     */
    data class Marque(
        val sat: Sat,
        val emetteur: String,
        val mode: String,
        val basHz: Long,
        val hautHz: Long,
        val reposBasHz: Long,
        val reposHautHz: Long,
        val derive: Double,
        val dopplerHz: Long,
        val etat: Etat,
        val bande: Boolean,
        /** The receiver follows this very transmitter (this satellite, this downlink). */
        val suivi: Boolean = false,
        /** The receiver follows this satellite (maybe another of its transmitters). */
        val satSuivi: Boolean = false,
    ) {
        val milieuHz: Long get() = (basHz + hautHz) / 2
    }

    /** Where a satellite stands: up, rising within 5 min, or down. */
    fun etat(elevationDeg: Double, aosMs: Long?, maintenant: Long): Etat = when {
        elevationDeg >= 0.0 -> Etat.VISIBLE
        aosMs != null && aosMs >= maintenant && aosMs - maintenant <= BIENTOT_MS -> Etat.BIENTOT
        else -> Etat.SOUS
    }

    /** [restHz] as heard here with range rate [rr] and the satellite's correction. */
    fun entendu(restHz: Long, rr: Double, correctionHz: Long): Long = Doppler.downlink(restHz, rr) + correctionHz

    /** The widest Doppler a low orbit gives at [hz] (≈ 8 km/s), for picking what may fall in a window. */
    fun margeDoppler(hz: Long): Long = Math.round(hz * 8.0 / Doppler.C_KM_S) + 1

    /**
     * The downlinks of [sats] (each with its transmitters) falling, Doppler
     * applied, in [deHz]..[aHz]; [suivi]: the satellite and the downlink the
     * receiver follows (catnum, description), when it follows one.
     * Up first, then rising, then down; the followed ones last (drawn on top).
     */
    fun marques(sats: List<Pair<Sat, List<Transmitter>>>, deHz: Long, aHz: Long, maintenant: Long,
                suivi: Pair<Int, String>? = null): List<Marque> {
        val l = ArrayList<Marque>()
        for ((s, tx) in sats) {
            val et = etat(s.elevationDeg, s.aosMs, maintenant)
            for (t in tx) {
                if (!t.alive) continue
                val lo = t.downlinkLowHz ?: continue
                val hi = t.downlinkHighHz?.takeIf { it > 0 } ?: lo
                val rb = minOf(lo, hi); val rh = maxOf(lo, hi)
                val b = entendu(rb, s.rangeRateKmS, s.correctionHz); val h = entendu(rh, s.rangeRateKmS, s.correctionHz)
                if (h < deHz || b > aHz) continue
                val mil = (rb + rh) / 2
                val derive = (Doppler.downlink(mil, s.rangeRate1sKmS) - Doppler.downlink(mil, s.rangeRateKmS)).toDouble()
                val estSuivi = suivi != null && suivi.first == s.catnum
                l += Marque(s, t.description, t.mode.orEmpty(), b, h, rb, rh, derive, Doppler.downlink(mil, s.rangeRateKmS) - mil, et,
                    rh > rb, estSuivi && suivi?.second == t.description, estSuivi)
            }
        }
        return l.sortedWith(compareBy<Marque>({ it.satSuivi }, { -it.etat.ordinal }, { it.basHz }))
    }

    /** What a click on the scale asks. */
    sealed class Accord {
        /** Follow this transmitter (its downlink, its Doppler). */
        data class Emetteur(val catnum: Int, val emetteur: String) : Accord()
        /** Follow this transponder with [reposHz] (its rest frame) heard: the place kept in the band as the Doppler moves it. */
        data class Bande(val catnum: Int, val emetteur: String, val reposHz: Long) : Accord()
        /** A plain frequency (sky Hz), no Doppler. */
        data class Terrestre(val hz: Long) : Accord()
    }

    /**
     * The click at [hz] (sky): within [tolHz] of a single-frequency downlink,
     * that transmitter (the nearest; up ones before the others); inside a
     * transponder's band (the followed one first, then up ones), there with
     * its rest frequency; elsewhere the frequency itself.
     */
    fun clic(hz: Long, marques: List<Marque>, tolHz: Long): Accord {
        val rang = { m: Marque -> (if (m.satSuivi) 0 else 10) + m.etat.ordinal }
        marques.filter { !it.bande && Math.abs(it.basHz - hz) <= tolHz }
            .minWithOrNull(compareBy<Marque>({ rang(it) }, { Math.abs(it.basHz - hz) }))
            ?.let { return Accord.Emetteur(it.sat.catnum, it.emetteur) }
        marques.filter { it.bande && hz in it.basHz..it.hautHz }.minByOrNull { rang(it) }
            ?.let { return Accord.Bande(it.sat.catnum, it.emetteur, reposDans(it, hz)) }
        return Accord.Terrestre(hz)
    }

    /** The rest frequency in [m]'s band heard at sky [hz] (Doppler and correction taken back), within the band. */
    fun reposDans(m: Marque, hz: Long): Long {
        val r = Doppler.restFromDownlink(hz - m.sat.correctionHz, m.sat.rangeRateKmS)
        return r.coerceIn(m.reposBasHz, m.reposHautHz)
    }

    /** The scale for the page. */
    fun json(marques: List<Marque>, maintenant: Long): JSONObject = JSONObject().put("ok", true).put("t", maintenant)
        .put("marques", JSONArray(marques.map { m ->
            val s = m.sat
            JSONObject().put("n", s.catnum).put("nom", s.nom).put("tx", m.emetteur).put("mode", m.mode)
                .put("bas", m.basHz).put("haut", m.hautHz).put("rbas", m.reposBasHz).put("rhaut", m.reposHautHz)
                .put("v", Math.round(m.derive * 10) / 10.0).put("dop", m.dopplerHz).put("etat", m.etat.cle).put("bande", m.bande)
                .put("suivi", m.suivi).put("satSuivi", m.satSuivi).put("fav", s.favori)
                .put("el", Math.round(s.elevationDeg * 10) / 10.0).put("az", Math.round(s.azimutDeg))
                .put("aos", s.aosMs ?: JSONObject.NULL).put("los", s.losMs ?: JSONObject.NULL).put("corr", s.correctionHz)
        }))
}
