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

/**
 * The band plans drawn as a thin strip over the receiver's spectrum: the
 * IARU Region 1 plans for 2 m, 70 cm, 23 cm, 13 cm and 3 cm, simplified to
 * what matters around satellites (satellite sub-bands, beacons, CW, SSB,
 * FM, digital), and QO-100's narrowband plan ([Qo100.SEGMENTS], reused).
 *
 * Display only. Segments nest: a wide one (« Satellites ») may hold finer
 * ones (QO-100's uplink); [segment] gives the finest. The owner may replace
 * any band with a JSON file (see [avecSurcharge]).
 */
object PlanBandes {

    enum class Usage { SATELLITE, BALISE, CW, ETROITE, BLU, FM, RELAIS, NUMERIQUE, TOUS_MODES, ATV, DIFFUSION, URGENCE }

    data class Segment(val bande: String, val basHz: Long, val hautHz: Long, val usage: Usage, val nom: String) {
        operator fun contains(hz: Long): Boolean = hz >= basHz && hz < hautHz
        val largeurHz: Long get() = hautHz - basHz
    }

    const val FORMAT = "satme-plan-bandes"

    private fun s(b: String, bas: Double, haut: Double, u: Usage, nom: String) =
        Segment(b, Math.round(bas * 1e6), Math.round(haut * 1e6), u, nom)

    private fun usageQo100(u: Qo100.Usage): Usage = when (u) {
        Qo100.Usage.BALISE -> Usage.BALISE
        Qo100.Usage.CW -> Usage.CW
        Qo100.Usage.NUMERIQUE -> Usage.NUMERIQUE
        Qo100.Usage.PHONIE -> Usage.BLU
        Qo100.Usage.DIFFUSION -> Usage.DIFFUSION
        Qo100.Usage.URGENCE -> Usage.URGENCE
        Qo100.Usage.MIXTE -> Usage.TOUS_MODES
    }

    /** QO-100's narrowband segments, as the cockpit draws them (names from [nomQo100]). */
    fun qo100(nomQo100: (String) -> String): List<Segment> = Qo100.SEGMENTS.map {
        Segment("qo100", it.basHz, it.hautHz, usageQo100(it.usage), nomQo100(it.cle))
    }

    /** IARU Region 1, simplified (MHz). */
    private val IARU_R1: List<Segment> = listOf(
        // 2 m
        s("2m", 144.000, 144.025, Usage.SATELLITE, "Satellites (descente)"),
        s("2m", 144.025, 144.100, Usage.CW, "CW"),
        s("2m", 144.100, 144.150, Usage.ETROITE, "CW, numérique"),
        s("2m", 144.150, 144.400, Usage.BLU, "BLU, CW, numérique"),
        s("2m", 144.400, 144.491, Usage.BALISE, "Balises"),
        s("2m", 144.500, 144.794, Usage.TOUS_MODES, "Tous modes"),
        s("2m", 144.794, 144.990, Usage.NUMERIQUE, "Numérique (APRS 144,800)"),
        s("2m", 144.990, 145.194, Usage.RELAIS, "Entrées relais FM"),
        s("2m", 145.194, 145.594, Usage.FM, "FM simplex"),
        s("2m", 145.594, 145.794, Usage.RELAIS, "Sorties relais FM"),
        s("2m", 145.794, 145.806, Usage.SATELLITE, "Espace (ISS)"),
        s("2m", 145.806, 146.000, Usage.SATELLITE, "Satellites"),
        // 70 cm
        s("70cm", 430.000, 432.000, Usage.TOUS_MODES, "Tous modes (selon pays)"),
        s("70cm", 432.000, 432.100, Usage.CW, "CW (EME)"),
        s("70cm", 432.100, 432.400, Usage.BLU, "BLU, CW, numérique"),
        s("70cm", 432.400, 432.490, Usage.BALISE, "Balises"),
        s("70cm", 432.500, 432.994, Usage.TOUS_MODES, "Tous modes"),
        s("70cm", 432.994, 433.381, Usage.RELAIS, "Entrées relais FM"),
        s("70cm", 433.394, 433.581, Usage.FM, "FM simplex"),
        s("70cm", 433.600, 434.000, Usage.NUMERIQUE, "Tous modes, numérique"),
        s("70cm", 434.000, 434.594, Usage.ATV, "Tous modes, ATV"),
        s("70cm", 434.594, 434.981, Usage.RELAIS, "Sorties relais FM"),
        s("70cm", 435.000, 438.000, Usage.SATELLITE, "Satellites"),
        s("70cm", 438.000, 440.000, Usage.TOUS_MODES, "ATV, numérique, relais"),
        // 23 cm
        s("23cm", 1240.000, 1243.250, Usage.TOUS_MODES, "Tous modes"),
        s("23cm", 1243.250, 1260.000, Usage.ATV, "ATV"),
        s("23cm", 1260.000, 1270.000, Usage.SATELLITE, "Satellites (montée)"),
        s("23cm", 1270.000, 1272.000, Usage.NUMERIQUE, "Numérique"),
        s("23cm", 1272.000, 1291.494, Usage.ATV, "ATV, relais"),
        s("23cm", 1296.000, 1296.150, Usage.CW, "CW (EME)"),
        s("23cm", 1296.150, 1296.800, Usage.BLU, "BLU, CW, numérique"),
        s("23cm", 1296.800, 1296.994, Usage.BALISE, "Balises"),
        s("23cm", 1296.994, 1297.481, Usage.RELAIS, "Sorties relais FM"),
        s("23cm", 1297.494, 1297.981, Usage.FM, "FM simplex"),
        s("23cm", 1298.000, 1300.000, Usage.NUMERIQUE, "Tous modes, numérique"),
        // 13 cm
        s("13cm", 2300.000, 2320.000, Usage.TOUS_MODES, "Tous modes (selon pays)"),
        s("13cm", 2320.000, 2320.150, Usage.CW, "CW"),
        s("13cm", 2320.150, 2320.800, Usage.BLU, "BLU, CW, numérique"),
        s("13cm", 2320.800, 2321.000, Usage.BALISE, "Balises"),
        s("13cm", 2321.000, 2400.000, Usage.TOUS_MODES, "Tous modes, ATV"),
        s("13cm", 2400.000, 2450.000, Usage.SATELLITE, "Satellites"),
        s("13cm", 2400.050, 2400.300, Usage.BLU, "QO-100 montée bande étroite"),
        s("13cm", 2401.500, 2409.500, Usage.ATV, "QO-100 montée bande large"),
        // 3 cm
        s("3cm", 10368.000, 10368.150, Usage.CW, "CW (EME)"),
        s("3cm", 10368.150, 10368.800, Usage.BLU, "BLU, CW, numérique"),
        s("3cm", 10368.800, 10368.990, Usage.BALISE, "Balises"),
        s("3cm", 10450.000, 10500.000, Usage.SATELLITE, "Satellites"),
        s("3cm", 10491.000, 10499.000, Usage.ATV, "QO-100 bande large (DATV)"),
    )

    /** The plans SatMe knows: IARU Region 1, then QO-100's narrowband (named with [nomQo100]). */
    fun defaut(nomQo100: (String) -> String = { it }): List<Segment> = IARU_R1 + qo100(nomQo100)

    /** The bands that segment list covers ("2m", "qo100"…), in order. */
    fun bandes(l: List<Segment>): List<String> = l.map { it.bande }.distinct()

    /**
     * [defaut] with the bands named in [surcharge] replaced by its segments
     * (a band left out keeps the default's; a band with no segment in the
     * file cannot be emptied, a typo should not erase a plan).
     */
    fun avecSurcharge(defaut: List<Segment>, surcharge: List<Segment>): List<Segment> {
        if (surcharge.isEmpty()) return defaut
        val remplacees = surcharge.map { it.bande }.toSet()
        return defaut.filter { it.bande !in remplacees } + surcharge
    }

    /**
     * The segments touching [basHz]..[hautHz], the widest first, so the
     * finer ones drawn after stay visible.
     */
    fun dans(l: List<Segment>, basHz: Long, hautHz: Long): List<Segment> =
        l.filter { it.hautHz > basHz && it.basHz < hautHz }.sortedWith(compareByDescending<Segment> { it.largeurHz }.thenBy { it.basHz })

    /** The finest segment holding [hz], or null outside every plan. */
    fun segment(l: List<Segment>, hz: Long): Segment? = l.filter { hz in it }.minByOrNull { it.largeurHz }

    fun json(l: List<Segment>): JSONObject = JSONObject().put("format", FORMAT).put("segments", JSONArray(l.map {
        JSONObject().put("bande", it.bande).put("bas", it.basHz).put("haut", it.hautHz).put("usage", it.usage.name).put("nom", it.nom)
    }))

    /**
     * Segments read from a file: {"segments":[{"bande","bas","haut","usage","nom"}]}
     * (Hz; or "basMhz"/"hautMhz"). An unknown usage reads as « tous modes »;
     * a segment without a band, or upside down, is left out.
     */
    fun lit(texte: String): List<Segment> {
        val t = texte.trim()
        val a = runCatching { if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("segments") }.getOrNull() ?: return emptyList()
        val r = ArrayList<Segment>()
        for (i in 0 until minOf(a.length(), 2000)) {
            val o = a.optJSONObject(i) ?: continue
            val bande = o.optString("bande").trim().take(20)
            fun f(k: String) = if (o.has(k)) o.optLong(k, 0L) else Math.round(o.optDouble(k + "Mhz", 0.0) * 1e6)
            val bas = f("bas"); val haut = f("haut")
            if (bande.isEmpty() || bas <= 0L || haut <= bas) continue
            val u = runCatching { Usage.valueOf(o.optString("usage").uppercase()) }.getOrDefault(Usage.TOUS_MODES)
            r += Segment(bande, bas, haut, u, o.optString("nom").trim().take(60))
        }
        return r
    }
}
