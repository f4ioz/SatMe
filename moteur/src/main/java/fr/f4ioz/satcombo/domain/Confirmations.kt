/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.StatutsQsl
import org.json.JSONArray
import org.json.JSONObject

/**
 * What is confirmed, **and by what**: LoTW, a paper card, eQSL — kept apart,
 * never merged into one « confirmé ».
 *
 * The rule the counters follow: the ARRL's awards (VUCC, DXCC, WAS) take
 * LoTW and paper cards, **not eQSL** (ARRL VUCC rules, March 2023; « eQSL
 * confirmations not accepted » on AMSAT's awards page as well). So a grid
 * confirmed by eQSL only is counted as such, separately, and never in the
 * « LoTW + papier » figure. Other awards (eQSL's own, some clubs') accept
 * eQSL: the figure is there for them.
 *
 * The flags come from the online log ([StatutsQsl]), matched to the local
 * contacts ([StatutsQsl.associe]).
 */
object Confirmations {

    enum class Source(val cle: String, val libelle: String) {
        LOTW("lotw", "LoTW"), PAPIER("papier", "carte papier"), EQSL("eqsl", "eQSL")
    }

    /** The services that confirmed this contact (none when [s] is null). */
    fun sources(s: StatutsQsl.Statut?): Set<Source> {
        if (s == null) return emptySet()
        val l = HashSet<Source>()
        if (StatutsQsl.lotw(s) == StatutsQsl.Etat.CONFIRME) l += Source.LOTW
        if (StatutsQsl.papier(s) == StatutsQsl.Etat.CONFIRME) l += Source.PAPIER
        if (StatutsQsl.eqsl(s) == StatutsQsl.Etat.CONFIRME) l += Source.EQSL
        return l
    }

    /** Counts for the ARRL's awards (VUCC, DXCC, WAS): LoTW or a paper card. eQSL never does. */
    fun valideArrl(s: Set<Source>): Boolean = Source.LOTW in s || Source.PAPIER in s

    /** The grids a contact gives (4 characters): its locator, or each of a grid line (VUCC credits all). */
    fun carres(e: LogEntry): List<String> {
        val l = e.theirLocator.trim().uppercase()
        val g = if (Adif.estUneGrille(l)) Adif.grilles(l) else listOf(l)
        return g.mapNotNull { GRILLE.find(it)?.value }.distinct()
    }

    private val GRILLE = Regex("^[A-R]{2}[0-9]{2}")

    /**
     * One counter: how many worked, and confirmed by each service. [arrl] is
     * LoTW or paper (an element counted once); [eqslSeul] confirmed by eQSL
     * and nothing else — **not** in [arrl].
     */
    data class Compte(val travailles: Int, val lotw: Int, val papier: Int, val eqsl: Int, val arrl: Int, val eqslSeul: Int)

    data class Bilan(
        val carres: Compte, val satellites: Compte, val contacts: Compte,
        /** Each grid worked: the services that confirmed it (empty: worked only). */
        val parCarre: Map<String, Set<Source>>,
    )

    private fun compte(par: Map<String, Set<Source>>) = Compte(
        par.size, par.values.count { Source.LOTW in it }, par.values.count { Source.PAPIER in it },
        par.values.count { Source.EQSL in it }, par.values.count(::valideArrl),
        par.values.count { it == setOf(Source.EQSL) })

    /** Grids, satellites and contacts by satellite, worked and confirmed per service. */
    fun bilan(log: List<LogEntry>, associes: Map<Long, StatutsQsl.Statut>): Bilan {
        val sat = log.filter { it.callsign.isNotBlank() && Diplomes.parSatellite(it) }
        val parCarre = HashMap<String, MutableSet<Source>>()
        val parSat = HashMap<String, MutableSet<Source>>()
        val parContact = HashMap<String, Set<Source>>()
        sat.forEach { e ->
            val src = sources(associes[e.timeMs])
            carres(e).forEach { c -> parCarre.getOrPut(c) { HashSet() }.addAll(src) }
            val nom = NomSatellite.propre(e.satName).ifBlank { e.satName }.trim().uppercase()
            if (nom.isNotEmpty()) parSat.getOrPut(nom) { HashSet() }.addAll(src)
            parContact[e.timeMs.toString()] = src
        }
        return Bilan(compte(parCarre), compte(parSat), compte(parContact), parCarre.toSortedMap())
    }

    /**
     * For a grids award: « 37 locators confirmés LoTW ou papier (LoTW 35, papier 4) ; 5 par eQSL seulement,
     * non comptés pour le VUCC ».
     */
    fun remarqueVucc(log: List<LogEntry>, associes: Map<Long, StatutsQsl.Statut>): String {
        val c = bilan(log, associes).carres
        val s = { n: Int -> if (n > 1) "s" else "" }
        return "${c.arrl} locator${s(c.arrl)} confirmé${s(c.arrl)} LoTW ou papier (LoTW ${c.lotw}, papier ${c.papier})" +
            (if (c.eqslSeul > 0) " ; ${c.eqslSeul} par eQSL seulement, non compté${s(c.eqslSeul)} pour le VUCC" else "")
    }

    /** The rule, said once on the page (and in the docs). */
    const val REGLE = "VUCC, DXCC et WAS (ARRL) comptent les confirmations LoTW et les cartes papier, pas eQSL : " +
        "un locator confirmé seulement par eQSL est compté à part."

    fun json(c: Compte): JSONObject = JSONObject().put("travailles", c.travailles).put("lotw", c.lotw).put("papier", c.papier)
        .put("eqsl", c.eqsl).put("arrl", c.arrl).put("eqslSeul", c.eqslSeul)

    fun json(b: Bilan): JSONObject = JSONObject().put("carres", json(b.carres)).put("satellites", json(b.satellites))
        .put("contacts", json(b.contacts)).put("regle", REGLE)

    /** The sources of a grid, for the map: ["lotw", "papier"], [] worked only. */
    fun json(s: Set<Source>): JSONArray = JSONArray(Source.values().filter { it in s }.map { it.cle })
}
