/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.Montage.Chaine
import fr.f4ioz.satcombo.station.Montage.Genre
import fr.f4ioz.satcombo.station.Montage.Sens
import fr.f4ioz.satcombo.station.Montage.Signal
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Ce qui manque » : what the drawing of the station says is missing or
 * dangerous — a reference input left free, a GPSDO without its antenna, a
 * supply not drawn, an amplifier's output in the air, an LNB nobody feeds,
 * and above all the LNB's supply (13 / 18 V on the coaxial) reaching an SDR
 * board's input without a DC block: that kills the board.
 *
 * **Read only**: the chain is looked at, nothing is changed or switched.
 */
object VerificationMontage {

    /** How serious: [DANGER] can destroy hardware, [ATTENTION] stops something working, [INFO] is worth a look. */
    enum class Niveau { DANGER, ATTENTION, INFO }

    /** One remark: its words, the pieces and cables it is about (lit on the drawing). */
    data class Remarque(val niveau: Niveau, val texte: String, val elements: List<String> = emptyList(), val liens: List<String> = emptyList()) {
        fun json(): JSONObject = JSONObject().put("niveau", niveau.name.lowercase()).put("texte", texte)
            .put("elements", JSONArray(elements)).put("liens", JSONArray(liens))
    }

    /** The ports that put a DC voltage on their coaxial (an LNB's supply): the MiniTiouner's inputs, a bias-T's or an injector's LNB side. */
    private val SOURCES_DC = mapOf("minitiouner" to setOf("lnb_a", "lnb_b"), "bias-t" to setOf("lnb"), "injecteur" to setOf("lnb"))

    /** What the DC goes through unchanged: a splitter, an attenuator, a piece mounted directly. */
    private fun laissePasser(m: Montage.Modele) = m.genre == Genre.COUPLEUR || m.id == "attenuateur"

    /** The inputs a DC voltage damages: an SDR board's, a transceiver's antenna. */
    private fun sensible(m: Montage.Modele, p: Montage.Port) =
        p.signal == Signal.RF && p.sens != Sens.SORTIE && (m.genre == Genre.SDR || m.genre == Genre.POSTE)

    private fun nom(c: Chaine, id: String) = c.element(id)?.let { Montage.nomCourt(it) } ?: id
    private fun relie(c: Chaine, element: String, port: String) =
        c.liens.any { (it.de.element == element && it.de.port == port) || (it.vers.element == element && it.vers.port == port) }

    /** Everything worth saying of [c], the dangers first. */
    fun verifie(c: Chaine): List<Remarque> {
        val r = ArrayList<Remarque>()
        r += courantContinu(c)
        for (e in c.elements) {
            val m = Montage.modele(e) ?: continue
            val ports = Montage.ports(e)
            // An amplifier or an up-converter fed, its output free: a transmission into nothing.
            if (m.genre == Genre.AMPLI || m.genre == Genre.CONVERTISSEUR) {
                val entree = ports.firstOrNull { it.signal == Signal.RF && it.sens == Sens.ENTREE && it.voie.emet }
                val sortie = ports.firstOrNull { it.signal == Signal.RF && it.sens == Sens.SORTIE && it.voie.emet }
                if (entree != null && sortie != null && relie(c, e.id, entree.id) && !relie(c, e.id, sortie.id))
                    r += Remarque(Niveau.ATTENTION, "Sortie de « ${Montage.nomCourt(e)} » en l'air : jamais d'émission sans antenne ni charge fictive", listOf(e.id))
            }
            // A reference input fitted but free.
            ports.filter { it.signal == Signal.REF && it.sens == Sens.ENTREE && !relie(c, e.id, it.id) }.forEach { p ->
                r += Remarque(Niveau.INFO, "« ${p.nom} » de « ${Montage.nomCourt(e)} » non reliée : relie-la à un GPSDO, ou décoche-la dans sa fiche", listOf(e.id))
            }
            // A GPSDO without its antenna.
            ports.filter { it.signal == Signal.GPS && it.sens == Sens.ENTREE && !relie(c, e.id, it.id) }.forEach {
                r += Remarque(Niveau.INFO, "« ${Montage.nomCourt(e)} » sans antenne GPS : sa référence ne se cale pas", listOf(e.id))
            }
            // A supply input not drawn (a piece cabled to the rest of the station only).
            if (ports.any { it.signal == Signal.DC && it.sens == Sens.ENTREE } && c.liens.any { it.de.element == e.id || it.vers.element == e.id })
                ports.filter { it.signal == Signal.DC && it.sens == Sens.ENTREE && !relie(c, e.id, it.id) }.forEach {
                    r += Remarque(Niveau.INFO, "Alimentation de « ${Montage.nomCourt(e)} » non dessinée", listOf(e.id))
                }
        }
        r += lnbSansAlimentation(c)
        r += sansOrdinateur(c)
        return r.sortedBy { it.niveau.ordinal }
    }

    /**
     * The LNB's supply followed down the coaxial from each port that puts it
     * there, through what lets it pass (a splitter, an attenuator): a danger
     * for each sensitive input it reaches.
     */
    private fun courantContinu(c: Chaine): List<Remarque> {
        val r = ArrayList<Remarque>()
        val vus = HashSet<String>()
        for (e in c.elements) {
            val sorties = SOURCES_DC[e.modele] ?: continue
            for (p in sorties) {
                // Breadth first over (element, port by which the DC arrives), with the cables taken.
                data class Pas(val element: String, val arrivee: String, val liens: List<String>, val par: List<String>)
                val file = ArrayDeque(listOf(Pas(e.id, p, emptyList(), emptyList())))
                val faits = HashSet<String>()
                while (file.isNotEmpty()) {
                    val pas = file.removeFirst()
                    if (!faits.add(pas.element + "." + pas.arrivee)) continue
                    val el = c.element(pas.element) ?: continue
                    // Leaving the source by its own port; through a piece that lets DC pass by any other RF port.
                    val departs = if (pas.liens.isEmpty()) listOf(pas.arrivee)
                        else Montage.ports(el).filter { it.signal == Signal.RF && it.id != pas.arrivee }.map { it.id }
                    for (d in departs) for (l in c.liens) {
                        val loin = when {
                            l.de.element == pas.element && l.de.port == d -> l.vers
                            l.vers.element == pas.element && l.vers.port == d -> l.de
                            else -> continue
                        }
                        if (Montage.port(c, l.de)?.signal != Signal.RF || l.cable.famille !in setOf(Montage.Famille.COAX, Montage.Famille.DIRECT)) continue
                        val autre = c.element(loin.element) ?: continue
                        val m = Montage.modele(autre) ?: continue
                        val port = Montage.port(c, loin) ?: continue
                        val chemin = pas.liens + l.id
                        if (sensible(m, port)) {
                            if (vus.add(autre.id + "." + port.id))
                                r += Remarque(Niveau.DANGER, "Tension continue du LNB (13 / 18 V) sur « ${port.nom} » de « ${Montage.nomCourt(autre)} » : " +
                                    "elle vient de « ${Montage.nomCourt(e)} »" + (if (pas.par.isNotEmpty()) " par " + pas.par.joinToString(", ") { "« " + nom(c, it) + " »" } else "") +
                                    ". Mets un DC block (ou un T de polarisation) devant : sans lui, l'entrée peut griller.",
                                    listOf(e.id) + pas.par + autre.id, chemin)
                        } else if (laissePasser(m)) file.add(Pas(autre.id, port.id, chemin, pas.par + autre.id))
                    }
                }
            }
        }
        return r
    }

    /** An LNB whose outputs reach no supply (the MiniTiouner, a bias-T, an injector), through splitters and attenuators. */
    private fun lnbSansAlimentation(c: Chaine): List<Remarque> {
        val r = ArrayList<Remarque>()
        for (e in c.elements) {
            if (Montage.modele(e)?.genre != Genre.LNB) continue
            val sorties = Montage.ports(e).filter { it.signal == Signal.RF && it.sens == Sens.SORTIE }
            if (sorties.none { relie(c, e.id, it.id) }) continue
            val file = ArrayDeque(sorties.map { e.id to it.id }); val vus = HashSet<String>(); var alimente = false
            while (file.isNotEmpty() && !alimente) {
                val (id, port) = file.removeFirst()
                if (!vus.add("$id.$port")) continue
                for (l in c.liens) {
                    val loin = when { l.de.element == id && l.de.port == port -> l.vers; l.vers.element == id && l.vers.port == port -> l.de; else -> continue }
                    val autre = c.element(loin.element) ?: continue
                    val m = Montage.modele(autre) ?: continue
                    if (loin.port in SOURCES_DC[m.id].orEmpty()) { alimente = true; break }
                    if (laissePasser(m)) Montage.ports(autre).filter { it.signal == Signal.RF && it.id != loin.port }.forEach { file.add(autre.id to it.id) }
                }
            }
            if (!alimente) r += Remarque(Niveau.ATTENTION, "« ${Montage.nomCourt(e)} » non alimenté : ni MiniTiouner, ni T de polarisation, ni injecteur sur ses sorties", listOf(e.id))
        }
        return r
    }

    /** A radio, a board or a receiver that SatMe drives, cabled to nothing that reaches the computer. */
    private fun sansOrdinateur(c: Chaine): List<Remarque> {
        if (c.elements.none { Montage.modele(it)?.genre == Genre.ORDINATEUR }) return emptyList()
        val pilotes = setOf(Genre.POSTE, Genre.SDR, Genre.RECEPTEUR_DATV)
        return c.elements.filter { Montage.modele(it)?.genre in pilotes }.filter { e -> !versOrdinateur(c, e.id) }
            .map { Remarque(Niveau.INFO, "« ${Montage.nomCourt(it)} » n'est relié à aucun ordinateur (USB, réseau)", listOf(it.id)) }
    }

    private fun versOrdinateur(c: Chaine, depart: String): Boolean {
        val vus = hashSetOf(depart); val file = ArrayDeque(listOf(depart))
        while (file.isNotEmpty()) {
            val id = file.removeFirst()
            for (l in c.liens) {
                val (ici, la) = when (id) { l.de.element -> l.de to l.vers; l.vers.element -> l.vers to l.de; else -> continue }
                if (Montage.port(c, ici)?.signal?.versPc != true) continue
                val g = c.element(la.element)?.let { Montage.modele(it) }?.genre
                if (g == Genre.ORDINATEUR) return true
                if (g in setOf(Genre.NOEUD, Genre.INTERFACE, Genre.CARTE_SON) && vus.add(la.element)) file.add(la.element)
            }
        }
        return false
    }

    fun json(l: List<Remarque>): JSONArray = JSONArray(l.map { it.json() })
}
