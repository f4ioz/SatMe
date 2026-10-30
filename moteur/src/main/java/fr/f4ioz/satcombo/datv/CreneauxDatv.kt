/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The band plan's channels for a symbol rate, each one free or busy as the
 * ground station's spectrum ([BandeLarge]) shows it now: where a
 * transmitter may go. Never a permission by itself: [PlanQo100.refus] and
 * [ReglesEmission] still decide at the start.
 */
object CreneauxDatv {
    enum class Etat(val cle: String) {
        /** Nothing seen there. */
        LIBRE("libre"),
        /** Another station's signal overlaps it. */
        OCCUPE("occupe"),
        /** Too near the beacon or outside the transponder ([PlanQo100.refus]). */
        INTERDIT("interdit"),
        /** Our own signal (transmitting there, or our callsign named there). */
        NOUS("nous"),
        /** No spectrum: nothing known. */
        INCONNU("inconnu"),
    }

    data class Creneau(
        val descenteKhz: Long,
        val monteeKhz: Long,
        val srMaxKs: Int,
        val etat: Etat,
        /** Who is there (the name the spectrum knows), or empty. */
        val occupant: String = "",
        /** Why it may not be chosen (an [Etat.INTERDIT] one), or empty. */
        val raison: String = "",
    )

    /**
     * Two signals closer than this (kHz) count as overlapping: the edges
     * [BandeLarge] measures are at 75 % of the height, the skirts go further.
     */
    const val GARDE_KHZ = 15.0

    /**
     * The channels [srKs] may use (roll-off [alpha]), low to high, each with
     * its state. [signaux] null: no fresh spectrum ([Etat.INCONNU]).
     * [nom] names a signal (the spectrum's names); [notreDescenteKhz]: where
     * we transmit now (downlink), or null; [indicatif]: our callsign.
     */
    fun calcule(
        srKs: Int,
        alpha: Double,
        signaux: List<SignalLarge>?,
        nom: (SignalLarge) -> String = { "" },
        notreDescenteKhz: Long? = null,
        indicatif: String = "",
    ): List<Creneau> {
        // A wide channel shares its centre with a narrow one: once, with the higher rate.
        return PlanQo100.CANAUX.filter { it.srMaxKs >= srKs }.sortedWith(compareBy({ it.descenteKhz }, { -it.srMaxKs }))
            .distinctBy { it.descenteKhz }.map { c -> etat(c.descenteKhz, c.srMaxKs, srKs, alpha, signaux, nom, notreDescenteKhz, indicatif) }
    }

    /** One frequency ([descenteKhz], downlink) at [srKs]: forbidden, ours, unknown, free or busy. */
    private fun etat(descenteKhz: Long, srMaxKs: Int, srKs: Int, alpha: Double, signaux: List<SignalLarge>?, nom: (SignalLarge) -> String,
                     notreDescenteKhz: Long?, indicatif: String): Creneau {
        val montee = descenteKhz - PlanQo100.DECALAGE_KHZ
        val demi = PlanQo100.occupeKhz(srKs, alpha) / 2
        val ind = indicatif.trim().uppercase()
        val interdit = PlanQo100.refus(montee, srKs, alpha)
        val nous = notreDescenteKhz != null && Math.abs(notreDescenteKhz - descenteKhz) < demi
        return when {
            interdit != null -> Creneau(descenteKhz, montee, srMaxKs, Etat.INTERDIT, raison = interdit)
            nous -> Creneau(descenteKhz, montee, srMaxKs, Etat.NOUS, occupant = ind)
            signaux == null -> Creneau(descenteKhz, montee, srMaxKs, Etat.INCONNU)
            else -> {
                val bas = descenteKhz - demi
                val haut = descenteKhz + demi
                val la = signaux.filter { !it.balise && it.debutKhz - GARDE_KHZ < haut && it.finKhz + GARDE_KHZ > bas }
                    .maxByOrNull { minOf(haut, it.finKhz.toDouble()) - maxOf(bas, it.debutKhz.toDouble()) }
                when {
                    la == null -> Creneau(descenteKhz, montee, srMaxKs, Etat.LIBRE)
                    ind.isNotEmpty() && nom(la).trim().uppercase() == ind -> Creneau(descenteKhz, montee, srMaxKs, Etat.NOUS, occupant = ind)
                    else -> Creneau(descenteKhz, montee, srMaxKs, Etat.OCCUPE,
                        occupant = nom(la).trim().ifEmpty { "signal " + la.srKs + " kS/s" })
                }
            }
        }
    }

    /**
     * « Créneau qui semble libre / occupé » for any uplink [monteeKhz] (a
     * click on the spectrum, a frequency typed), on and off the band plan:
     * the same reading as [calcule], for the page's hint only — **indicative**:
     * the ground station's spectrum is a few seconds old, a weak station
     * may be under its noise, and nothing here allows or forbids a start.
     */
    fun indice(
        monteeKhz: Long,
        srKs: Int,
        alpha: Double,
        signaux: List<SignalLarge>?,
        nom: (SignalLarge) -> String = { "" },
        notreDescenteKhz: Long? = null,
        indicatif: String = "",
    ): Creneau = etat(monteeKhz + PlanQo100.DECALAGE_KHZ, srKs, srKs, alpha, signaux, nom, notreDescenteKhz, indicatif)

    /** The hint's sentence for the page, said as a guess. */
    fun texteIndice(c: Creneau): String = when (c.etat) {
        Etat.LIBRE -> "créneau qui semble libre"
        Etat.OCCUPE -> "créneau qui semble occupé (" + c.occupant + ")"
        Etat.NOUS -> "notre propre signal"
        Etat.INTERDIT -> c.raison
        Etat.INCONNU -> "occupation inconnue (spectre de la station sol coupé)"
    } + if (c.etat == Etat.LIBRE || c.etat == Etat.OCCUPE) " — indicatif seulement, d’après le spectre de la station sol : écouter avant d’émettre" else ""

    /**
     * The uplink a click at [descenteKhz] (downlink) picks for [srKs]: the
     * band plan's channel when the click falls within its occupied band,
     * else the frequency clicked rounded to [PAS_CLIC_KHZ].
     */
    fun accroche(descenteKhz: Long, srKs: Int, alpha: Double): Long {
        val demi = PlanQo100.occupeKhz(srKs, alpha) / 2
        val c = PlanQo100.CANAUX.filter { it.srMaxKs >= srKs }.minByOrNull { Math.abs(it.descenteKhz - descenteKhz) }
        if (c != null && Math.abs(c.descenteKhz - descenteKhz) <= demi) return c.monteeKhz
        return Math.round((descenteKhz - PlanQo100.DECALAGE_KHZ) / PAS_CLIC_KHZ.toDouble()) * PAS_CLIC_KHZ
    }

    /** Off the band plan, a click is rounded to this (kHz). */
    const val PAS_CLIC_KHZ = 10L

    fun json(c: Creneau): JSONObject = JSONObject().put("descente", c.descenteKhz).put("montee", c.monteeKhz).put("etat", c.etat.cle)
        .put("occupant", c.occupant).put("raison", c.raison).put("texte", texteIndice(c))

    /** Why [c] may not be picked for transmitting, or null. */
    fun refusChoix(c: Creneau): String? = when (c.etat) {
        Etat.INTERDIT -> c.raison
        Etat.OCCUPE -> "créneau %s MHz occupé (%s) : en choisir un libre".format(Locale.FRANCE, PlanQo100.mhz(c.descenteKhz), c.occupant)
        else -> null
    }

    /** The state of the channel holding [monteeKhz] (uplink), or null when it is none of [l]. */
    fun de(l: List<Creneau>, monteeKhz: Long): Creneau? = l.firstOrNull { it.monteeKhz == monteeKhz }

    fun json(l: List<Creneau>): JSONArray = JSONArray(l.map { c ->
        JSONObject().put("descente", c.descenteKhz).put("montee", c.monteeKhz).put("srMax", c.srMaxKs).put("etat", c.etat.cle)
            .put("occupant", c.occupant).put("raison", c.raison)
    })
}
