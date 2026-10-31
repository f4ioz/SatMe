/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.domain.PlanBandes
import fr.f4ioz.satcombo.domain.PlanBandes.Segment
import fr.f4ioz.satcombo.domain.PlanBandes.Usage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The HF bands (160 m to 6 m) as TellusMe colours them: the IARU Region 1
 * HF band plan, simplified to its CW, narrow-band/digital, beacon and phone
 * (all modes) segments. A static table, display only: the national rules
 * decide, not this. 2 m and 70 cm come from the engine's own plan
 * ([PlanBandes]), the one SatMe draws.
 */
object PlanHf {

    /** A band button: its ADIF name, its edges, where the button tunes, and the mode used there. */
    data class Bande(val nom: String, val basHz: Long, val hautHz: Long, val accordHz: Long, val mode: String)

    val BANDES = listOf(
        Bande("160m", 1_810_000, 2_000_000, 1_840_000, "LSB"),
        Bande("80m", 3_500_000, 3_800_000, 3_700_000, "LSB"),
        Bande("60m", 5_351_500, 5_366_500, 5_357_000, "USB"),
        Bande("40m", 7_000_000, 7_200_000, 7_074_000, "USB"),
        Bande("30m", 10_100_000, 10_150_000, 10_116_000, "CW"),
        Bande("20m", 14_000_000, 14_350_000, 14_200_000, "USB"),
        Bande("17m", 18_068_000, 18_168_000, 18_130_000, "USB"),
        Bande("15m", 21_000_000, 21_450_000, 21_250_000, "USB"),
        Bande("12m", 24_890_000, 24_990_000, 24_950_000, "USB"),
        Bande("10m", 28_000_000, 29_700_000, 28_500_000, "USB"),
        Bande("6m", 50_000_000, 52_000_000, 50_150_000, "USB"),
        Bande("2m", 144_000_000, 146_000_000, 144_300_000, "USB"),
        Bande("70cm", 430_000_000, 440_000_000, 432_200_000, "USB"),
    )

    private fun s(b: String, bas: Double, haut: Double, u: Usage, nom: String) =
        Segment(b, Math.round(bas * 1e3), Math.round(haut * 1e3), u, nom)

    /** IARU Region 1 HF band plan, simplified (kHz). */
    val SEGMENTS_HF: List<Segment> = listOf(
        s("160m", 1810.0, 1838.0, Usage.CW, "CW"),
        s("160m", 1838.0, 1843.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("160m", 1843.0, 2000.0, Usage.BLU, "Tous modes (phonie)"),
        s("80m", 3500.0, 3570.0, Usage.CW, "CW"),
        s("80m", 3570.0, 3600.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("80m", 3600.0, 3800.0, Usage.BLU, "Tous modes (phonie)"),
        s("60m", 5351.5, 5354.0, Usage.CW, "CW, modes étroits"),
        s("60m", 5354.0, 5366.0, Usage.BLU, "Tous modes (BLU USB)"),
        s("60m", 5366.0, 5366.5, Usage.NUMERIQUE, "Signaux faibles"),
        s("40m", 7000.0, 7040.0, Usage.CW, "CW"),
        s("40m", 7040.0, 7060.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("40m", 7060.0, 7200.0, Usage.BLU, "Tous modes (phonie)"),
        s("30m", 10100.0, 10130.0, Usage.CW, "CW"),
        s("30m", 10130.0, 10150.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("20m", 14000.0, 14070.0, Usage.CW, "CW"),
        s("20m", 14070.0, 14099.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("20m", 14099.0, 14101.0, Usage.BALISE, "Balises (IBP)"),
        s("20m", 14101.0, 14350.0, Usage.BLU, "Tous modes (phonie)"),
        s("17m", 18068.0, 18095.0, Usage.CW, "CW"),
        s("17m", 18095.0, 18109.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("17m", 18109.0, 18111.0, Usage.BALISE, "Balises (IBP)"),
        s("17m", 18111.0, 18168.0, Usage.BLU, "Tous modes (phonie)"),
        s("15m", 21000.0, 21070.0, Usage.CW, "CW"),
        s("15m", 21070.0, 21149.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("15m", 21149.0, 21151.0, Usage.BALISE, "Balises (IBP)"),
        s("15m", 21151.0, 21450.0, Usage.BLU, "Tous modes (phonie)"),
        s("12m", 24890.0, 24915.0, Usage.CW, "CW"),
        s("12m", 24915.0, 24929.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("12m", 24929.0, 24931.0, Usage.BALISE, "Balises (IBP)"),
        s("12m", 24931.0, 24990.0, Usage.BLU, "Tous modes (phonie)"),
        s("10m", 28000.0, 28070.0, Usage.CW, "CW"),
        s("10m", 28070.0, 28190.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("10m", 28190.0, 28225.0, Usage.BALISE, "Balises"),
        s("10m", 28225.0, 29200.0, Usage.BLU, "Tous modes (phonie)"),
        s("10m", 29200.0, 29300.0, Usage.NUMERIQUE, "Numérique automatique"),
        s("10m", 29300.0, 29510.0, Usage.SATELLITE, "Satellites (descente)"),
        s("10m", 29520.0, 29700.0, Usage.FM, "FM, relais"),
        s("6m", 50000.0, 50100.0, Usage.CW, "CW (balises 50,000–50,030)"),
        s("6m", 50100.0, 50300.0, Usage.BLU, "BLU, CW"),
        s("6m", 50300.0, 50400.0, Usage.NUMERIQUE, "Modes étroits, numérique"),
        s("6m", 50400.0, 50500.0, Usage.BALISE, "Balises"),
        s("6m", 50500.0, 52000.0, Usage.TOUS_MODES, "Tous modes, FM"),
    )

    /** HF, then 2 m and 70 cm from the engine's plan. */
    val SEGMENTS: List<Segment> = SEGMENTS_HF + PlanBandes.defaut().filter { it.bande == "2m" || it.bande == "70cm" }

    fun bande(hz: Long): Bande? = BANDES.firstOrNull { hz >= it.basHz && hz <= it.hautHz }

    fun segment(hz: Long): Segment? = PlanBandes.segment(SEGMENTS, hz)

    /** Phone, CW, digital: the three colours the page uses (the others keep their own). */
    fun famille(u: Usage): String = when (u) {
        Usage.CW, Usage.ETROITE -> "cw"
        Usage.NUMERIQUE -> "num"
        Usage.BLU, Usage.TOUS_MODES, Usage.FM, Usage.RELAIS -> "phonie"
        Usage.BALISE -> "balise"
        else -> "autre"
    }

    /** For the page: the bands [bandes] (a rig's), their buttons and their segments. */
    fun json(bandes: List<String>): JSONObject = JSONObject()
        .put("bandes", JSONArray(BANDES.filter { it.nom in bandes }.map {
            JSONObject().put("nom", it.nom).put("bas", it.basHz).put("haut", it.hautHz).put("accord", it.accordHz).put("mode", it.mode)
        }))
        .put("segments", JSONArray(SEGMENTS.filter { it.bande in bandes }.map {
            JSONObject().put("b", it.bande).put("bas", it.basHz).put("haut", it.hautHz).put("f", famille(it.usage)).put("nom", it.nom)
        }))
}
