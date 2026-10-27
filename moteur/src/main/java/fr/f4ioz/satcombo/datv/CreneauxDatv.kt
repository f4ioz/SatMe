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
        val demi = PlanQo100.occupeKhz(srKs, alpha) / 2
        val ind = indicatif.trim().uppercase()
        // A wide channel shares its centre with a narrow one: once, with the higher rate.
        return PlanQo100.CANAUX.filter { it.srMaxKs >= srKs }.sortedWith(compareBy({ it.descenteKhz }, { -it.srMaxKs }))
            .distinctBy { it.descenteKhz }.map { c ->
            val interdit = PlanQo100.refus(c.monteeKhz, srKs, alpha)
            val nous = notreDescenteKhz != null && Math.abs(notreDescenteKhz - c.descenteKhz) < demi
            when {
                interdit != null -> Creneau(c.descenteKhz, c.monteeKhz, c.srMaxKs, Etat.INTERDIT, raison = interdit)
                nous -> Creneau(c.descenteKhz, c.monteeKhz, c.srMaxKs, Etat.NOUS, occupant = ind)
                signaux == null -> Creneau(c.descenteKhz, c.monteeKhz, c.srMaxKs, Etat.INCONNU)
                else -> {
                    val bas = c.descenteKhz - demi
                    val haut = c.descenteKhz + demi
                    val la = signaux.filter { !it.balise && it.debutKhz - GARDE_KHZ < haut && it.finKhz + GARDE_KHZ > bas }
                        .maxByOrNull { minOf(haut, it.finKhz.toDouble()) - maxOf(bas, it.debutKhz.toDouble()) }
                    when {
                        la == null -> Creneau(c.descenteKhz, c.monteeKhz, c.srMaxKs, Etat.LIBRE)
                        ind.isNotEmpty() && nom(la).trim().uppercase() == ind -> Creneau(c.descenteKhz, c.monteeKhz, c.srMaxKs, Etat.NOUS, occupant = ind)
                        else -> Creneau(c.descenteKhz, c.monteeKhz, c.srMaxKs, Etat.OCCUPE,
                            occupant = nom(la).trim().ifEmpty { "signal " + la.srKs + " kS/s" })
                    }
                }
            }
        }
    }

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
