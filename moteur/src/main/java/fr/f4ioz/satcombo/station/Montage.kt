/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import org.json.JSONArray
import org.json.JSONObject

/**
 * The station's hardware as it is put together: the dish and its LNB, the
 * converters, the transceivers and SDR boards, the computer, and the cables
 * between them — what the « Montage » page draws.
 *
 * **A drawing, nothing else.** A chain changes no setting, opens no port,
 * starts nothing: it says what is plugged where, so that the page can light
 * the path a reception or a transmission takes.
 */
object Montage {

    /** What a piece of hardware is; [colonne] its place from the antennas (0) to the computer, left to right. */
    enum class Genre(val nom: String, val colonne: Int) {
        ANTENNE("Antennes", 0),
        LNB("Têtes de réception (LNB)", 1),
        AMPLI("Amplificateurs", 1),
        CONVERTISSEUR("Convertisseurs", 2),
        ALIMENTATION("Alimentations, T de polarisation", 2),
        FILTRE("Filtres", 2),
        CHARGE("Atténuateurs, charges", 2),
        COUPLEUR("Répartiteurs", 2),
        ROTOR("Rotors", 1),
        POSTE("Postes émetteurs-récepteurs", 3),
        SDR("Récepteurs et émetteurs SDR", 3),
        RECEPTEUR_DATV("Récepteurs DATV", 3),
        NOEUD("Boîtiers déportés", 4),
        CARTE_SON("Cartes son", 4),
        INTERFACE("Interfaces de station", 4),
        ORDINATEUR("Ordinateurs", 5),
        TELEPHONE("Téléphones", 6),
    }

    /** Which way a signal goes through a port. */
    enum class Sens { ENTREE, SORTIE, BIDIR }

    /** What a port carries: radio (RF), data, sound, commands (CAT, PTT, rotor), a mounting. */
    enum class Signal { RF, DONNEES, AUDIO, COMMANDE, MECA }

    /** The path a radio port belongs to: reception, transmission, both; [AUCUNE] for the rest. */
    enum class Voie { RX, TX, RXTX, AUCUNE;
        val recoit get() = this == RX || this == RXTX
        val emet get() = this == TX || this == RXTX
    }

    /** Cables by family: a link is possible when both ports accept its family. */
    enum class Famille { COAX, DIRECT, USB, RESEAU, WIFI, AUDIO, SERIE, ROTOR }

    /** The cables the page draws, each in its own way (a coaxial thick, a network cable dashed…). */
    enum class Cable(val nom: String, val famille: Famille) {
        COAX_N("Coaxial N", Famille.COAX),
        COAX_SMA("Coaxial SMA", Famille.COAX),
        COAX_F("Coaxial F (TV)", Famille.COAX),
        COAX_BNC("Coaxial BNC", Famille.COAX),
        COAX_PL("Coaxial PL (UHF)", Famille.COAX),
        DIRECT("Monté directement", Famille.DIRECT),
        USB("USB", Famille.USB),
        ETHERNET("Câble réseau", Famille.RESEAU),
        WIFI("Wi-Fi", Famille.WIFI),
        AUDIO("Audio", Famille.AUDIO),
        CAT("CAT (série)", Famille.SERIE),
        PTT("PTT", Famille.SERIE),
        ROTOR("Commande du rotor", Famille.ROTOR);

        companion object {
            fun de(nom: String?): Cable? = entries.firstOrNull { it.name == nom }
            /** The coaxial cable for a connector (N, SMA, F, BNC, PL), N when unknown. */
            fun coax(connecteur: String): Cable = when (connecteur.uppercase()) {
                "SMA" -> COAX_SMA; "F" -> COAX_F; "BNC" -> COAX_BNC; "PL", "SO-239", "UHF" -> COAX_PL; else -> COAX_N
            }
        }
    }

    /**
     * One port of a piece of hardware: [mhz] the band it carries (radio
     * ports), [connecteur] as printed beside it, [multiple] when several
     * cables may leave it (a computer's USB, a network).
     */
    data class Port(
        val id: String, val nom: String, val sens: Sens, val signal: Signal, val voie: Voie = Voie.AUCUNE,
        val mhz: Int? = null, val connecteur: String = "", val multiple: Boolean = false,
    ) {
        /** The cable families this port takes. */
        val familles: Set<Famille> get() = when (signal) {
            Signal.RF -> if (connecteur == "guide") setOf(Famille.DIRECT) else setOf(Famille.COAX, Famille.DIRECT)
            Signal.DONNEES -> when (connecteur) {
                "USB" -> setOf(Famille.USB); "RJ45" -> setOf(Famille.RESEAU); "Wi-Fi" -> setOf(Famille.WIFI)
                else -> setOf(Famille.USB, Famille.RESEAU, Famille.WIFI)
            }
            Signal.AUDIO -> setOf(Famille.AUDIO)
            Signal.COMMANDE -> if (connecteur == "rotor") setOf(Famille.ROTOR) else setOf(Famille.SERIE, Famille.USB)
            Signal.MECA -> setOf(Famille.ROTOR)
        }
    }

    /**
     * A piece of hardware the catalogue knows: [dessin] the drawing the page
     * makes of it, [l]×[h] its size there, [roles] what ties it to SatMe's
     * live state ("sdr:pluto", "tx:bladerf", "datv:minitiouner", "cat"…).
     */
    data class Modele(
        val id: String, val nom: String, val genre: Genre, val dessin: String, val ports: List<Port>,
        val l: Int, val h: Int, val roles: Set<String> = emptySet(), val note: String = "",
    ) {
        fun port(id: String): Port? = ports.firstOrNull { it.id == id }
    }

    /** One piece put in the station: its [modele] in the catalogue, the [nom] the owner gives it. */
    data class Element(val id: String, val modele: String, val nom: String = "")

    /** One end of a cable: an element's port. */
    data class Bout(val element: String, val port: String) {
        override fun toString() = "$element.$port"
        companion object {
            fun lit(s: String?): Bout? {
                val t = s?.split('.') ?: return null
                return if (t.size == 2 && ID.matches(t[0]) && ID.matches(t[1])) Bout(t[0], t[1]) else null
            }
        }
    }

    /** A cable between two ports. */
    data class Lien(val id: String, val de: Bout, val vers: Bout, val cable: Cable, val note: String = "")

    /** The station as it is put together. */
    data class Chaine(val elements: List<Element> = emptyList(), val liens: List<Lien> = emptyList(), val nom: String = "") {
        fun element(id: String) = elements.firstOrNull { it.id == id }
        val vide get() = elements.isEmpty()
    }

    /** An element's or a port's id: short, plain (it goes into file names and the page's addresses). */
    val ID = Regex("[a-z0-9_-]{1,32}")

    const val ELEMENTS_MAX = 40
    const val LIENS_MAX = 80
    const val VERSION = 1

    /** The model of [e], from the catalogue. */
    fun modele(e: Element): Modele? = CatalogueMateriel.modele(e.modele)

    /** The name shown for [e]: the owner's, else the catalogue's. */
    fun nom(e: Element): String = e.nom.ifBlank { modele(e)?.nom ?: e.modele }

    /** The port at [b] in chain [c], or null when the element or the port is not there. */
    fun port(c: Chaine, b: Bout): Port? = c.element(b.element)?.let { modele(it) }?.port(b.port)

    /** The cable families both ports take, or empty when they cannot be linked. */
    fun familles(a: Port, b: Port): Set<Famille> = a.familles intersect b.familles

    /** Whether [cable] fits between the ports [a] and [b]. */
    fun compatible(a: Port, b: Port, cable: Cable): Boolean = cable.famille in familles(a, b)

    /** The cable a link between [a] and [b] gets when none is chosen. */
    fun cableParDefaut(a: Port, b: Port): Cable? {
        val f = familles(a, b)
        return when {
            Famille.COAX in f -> Cable.coax(listOf(a.connecteur, b.connecteur).firstOrNull { it in setOf("N", "SMA", "F", "BNC", "PL") } ?: "N")
            Famille.DIRECT in f -> Cable.DIRECT
            Famille.USB in f -> Cable.USB
            Famille.RESEAU in f -> Cable.ETHERNET
            Famille.WIFI in f -> Cable.WIFI
            Famille.AUDIO in f -> Cable.AUDIO
            Famille.SERIE in f -> if (a.id.contains("ptt") || b.id.contains("ptt")) Cable.PTT else Cable.CAT
            Famille.ROTOR in f -> Cable.ROTOR
            else -> null
        }
    }

    /** Why a cable from [de] to [vers] cannot be put in [c], or null when it can. */
    fun refus(c: Chaine, de: Bout, vers: Bout, cable: Cable, sauf: String? = null): String? {
        if (de.element == vers.element) return "un appareil relié à lui-même"
        val a = port(c, de) ?: return "port inconnu : $de"
        val b = port(c, vers) ?: return "port inconnu : $vers"
        if (!compatible(a, b, cable)) return "${cable.nom} ne va pas de « ${a.nom} » à « ${b.nom} »"
        val autres = c.liens.filter { it.id != sauf }
        for ((bout, p) in listOf(de to a, vers to b))
            if (!p.multiple && autres.any { it.de == bout || it.vers == bout }) return "« ${p.nom} » a déjà son câble"
        return null
    }

    /**
     * The chain cleaned: unknown hardware and broken cables dropped, ids
     * made unique, the sizes bounded. What is read from a file or a page
     * always goes through here.
     */
    fun nettoie(c: Chaine): Chaine {
        val vus = HashSet<String>()
        val elements = c.elements.asSequence()
            .filter { ID.matches(it.id) && CatalogueMateriel.modele(it.modele) != null && vus.add(it.id) }
            .map { it.copy(nom = propre(it.nom, 60)) }
            .take(ELEMENTS_MAX).toList()
        var sortie = Chaine(elements, emptyList(), propre(c.nom, 80))
        val ids = HashSet<String>()
        for (l in c.liens) {
            if (sortie.liens.size >= LIENS_MAX) break
            if (!ID.matches(l.id) || !ids.add(l.id)) continue
            if (refus(sortie, l.de, l.vers, l.cable) != null) continue
            sortie = sortie.copy(liens = sortie.liens + l.copy(note = propre(l.note, 80)))
        }
        return sortie
    }

    private fun propre(s: String, max: Int) = s.filter { it >= ' ' && it != '<' && it != '>' }.trim().take(max)

    // ------------------------------------------------------------------ JSON

    fun ecrit(c: Chaine): JSONObject = JSONObject().put("v", VERSION).put("nom", c.nom)
        .put("elements", JSONArray(c.elements.map { e -> JSONObject().put("id", e.id).put("m", e.modele).put("nom", e.nom) }))
        .put("liens", JSONArray(c.liens.map { l ->
            JSONObject().put("id", l.id).put("de", l.de.toString()).put("vers", l.vers.toString()).put("c", l.cable.name).put("note", l.note)
        }))

    /** A chain read back ([ecrit]'s JSON, a page's), cleaned; an empty chain when unreadable. */
    fun lit(texte: String?): Chaine = runCatching { lit(JSONObject(texte ?: "")) }.getOrDefault(Chaine())

    fun lit(o: JSONObject): Chaine {
        val el = o.optJSONArray("elements") ?: JSONArray()
        val li = o.optJSONArray("liens") ?: JSONArray()
        val elements = (0 until minOf(el.length(), ELEMENTS_MAX * 2)).mapNotNull { i ->
            el.optJSONObject(i)?.let { Element(it.optString("id"), it.optString("m"), it.optString("nom")) }
        }
        val liens = (0 until minOf(li.length(), LIENS_MAX * 2)).mapNotNull { i ->
            val x = li.optJSONObject(i) ?: return@mapNotNull null
            val de = Bout.lit(x.optString("de")) ?: return@mapNotNull null
            val vers = Bout.lit(x.optString("vers")) ?: return@mapNotNull null
            val cable = Cable.de(x.optString("c")) ?: return@mapNotNull null
            Lien(x.optString("id"), de, vers, cable, x.optString("note"))
        }
        return nettoie(Chaine(elements, liens, o.optString("nom")))
    }

    /** The catalogue, the cables and the ready-made chains, for the page. */
    fun catalogueJson(): JSONObject = JSONObject()
        .put("genres", JSONArray(Genre.entries.map { JSONObject().put("id", it.name).put("nom", it.nom).put("colonne", it.colonne) }))
        .put("modeles", JSONArray(CatalogueMateriel.MODELES.map { m ->
            JSONObject().put("id", m.id).put("nom", m.nom).put("court", CatalogueMateriel.court(m)).put("genre", m.genre.name).put("dessin", m.dessin).put("l", m.l).put("h", m.h)
                .put("note", m.note).put("roles", JSONArray(m.roles.toList()))
                .put("ports", JSONArray(m.ports.map { p ->
                    JSONObject().put("id", p.id).put("nom", p.nom).put("sens", p.sens.name).put("signal", p.signal.name).put("voie", p.voie.name)
                        .put("mhz", p.mhz ?: JSONObject.NULL).put("connecteur", p.connecteur).put("multiple", p.multiple)
                        .put("familles", JSONArray(p.familles.map { it.name }))
                }))
        }))
        .put("cables", JSONArray(Cable.entries.map { JSONObject().put("id", it.name).put("nom", it.nom).put("famille", it.famille.name) }))
        .put("prereglages", JSONArray(CatalogueMateriel.PREREGLAGES.map { p ->
            JSONObject().put("id", p.id).put("nom", p.nom).put("quoi", p.quoi).put("chaine", ecrit(p.chaine))
        }))

    /** A new element's id, not yet in [c]. */
    fun nouvelId(c: Chaine, prefixe: String = "e"): String {
        var i = c.elements.size + c.liens.size + 1
        while (c.elements.any { it.id == "$prefixe$i" } || c.liens.any { it.id == "$prefixe$i" }) i++
        return "$prefixe$i"
    }

    /**
     * A small builder for the ready-made chains and the suggestions: elements
     * added by model, cables by port, the default cable when none is given.
     */
    class Bati(nom: String = "") {
        private var c = Chaine(nom = nom)
        private var n = 0

        fun ajoute(modele: String, nom: String = ""): String {
            require(CatalogueMateriel.modele(modele) != null) { "modèle inconnu : $modele" }
            val id = "e" + (++n)
            c = c.copy(elements = c.elements + Element(id, modele, nom))
            return id
        }

        /** A cable from [a].[pa] to [b].[pb]; false (nothing added) when it cannot be. */
        fun relie(a: String, pa: String, b: String, pb: String, cable: Cable? = null): Boolean {
            val de = Bout(a, pa); val vers = Bout(b, pb)
            val k = cable ?: run {
                val x = port(c, de) ?: return false
                val y = port(c, vers) ?: return false
                cableParDefaut(x, y) ?: return false
            }
            if (refus(c, de, vers, k) != null) return false
            c = c.copy(liens = c.liens + Lien("l" + (c.liens.size + 1), de, vers, k))
            return true
        }

        fun chaine(): Chaine = c
    }
}
