/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.sonde.Geo

/** How a frame reached the station. */
enum class Voie {
    /** Heard straight from the station: no digipeater used. */
    DIRECT,
    /** Relayed by one or more ground digipeaters. */
    DIGI,
    /** Relayed by a satellite's digipeater (the ISS…), or sent by the satellite itself. */
    SATELLITE,
    /** Came from the internet (APRS-IS): a gateway put it on the air, or it says TCPIP / qA… in its path. */
    INTERNET,
}

/**
 * The stations heard, each with what is known of it: where (and where it
 * went: its trail), how it was heard (direct, through a digipeater, through
 * a satellite, from the internet: each a dated fact), its symbol, its last
 * comment, status, weather, message, telemetry, its last raw frame. Read
 * from the frames alone (pure functions): SatMe-PC's map and list, the
 * phone's page, the tests.
 */
object StationsAprs {

    /** The digipeaters on satellites (their callsigns and the aliases they answer), with the satellite's name. */
    val SATELLITES: Map<String, String> = mapOf(
        "RS0ISS" to "ISS", "NA1SS" to "ISS", "ARISS" to "ISS", "OR4ISS" to "ISS", "DP0ISS" to "ISS",
        "APRSAT" to "satellite", "PSAT" to "PSAT", "PSAT2" to "PSAT2", "PCSAT" to "PCSAT", "W3ADO" to "PCSAT",
        "SATGTE" to "satellite",
    )

    /** In a path: the frame went through the internet. */
    private fun internet(a: Adresse): Boolean {
        val c = a.indicatif.uppercase()
        return c == "TCPIP" || c == "TCPXX" || c == "NOGATE" || (c.length == 3 && c.startsWith("QA"))
    }

    /** The satellite named by an address, or null. */
    fun satellite(a: Adresse): String? = SATELLITES[a.indicatif.uppercase()]

    /** How [t] was heard: from the internet, through a satellite, through ground digipeaters, or direct. */
    fun voie(t: Trame): Voie = when {
        t.relais.any { internet(it) } -> Voie.INTERNET
        satellite(t.source) != null -> Voie.SATELLITE
        t.relais.any { it.repete && satellite(it) != null } -> Voie.SATELLITE
        t.relais.any { it.repete } -> Voie.DIGI
        else -> Voie.DIRECT
    }

    /** The relays that repeated [t] ("F1ZZZ-2", "WIDE1"…), in order. */
    fun digis(t: Trame): List<String> = t.relais.filter { it.repete }.map { it.copy(repete = false).toString() }

    /** The satellite that relayed (or sent) [p]: the one SatMe followed when it is known, else the digipeater's name. */
    fun nomSatellite(p: Paquet): String? {
        val d = satellite(p.trame.source) ?: p.trame.relais.firstNotNullOfOrNull { a -> if (a.repete) satellite(a) else null } ?: return null
        return p.satellite.takeIf { it.isNotBlank() && it != "↑" } ?: d
    }

    /** "SRC>DST,PATH:info" (a third-party frame's inside, a line from APRS-IS) as a frame, or null. */
    fun tnc2(ligne: String): Trame? {
        val k = ligne.indexOf(':'); val g = ligne.indexOf('>')
        if (k < 0 || g !in 1 until k) return null
        val tete = ligne.substring(0, k)
        val adresses = tete.substring(g + 1).split(',').filter { it.isNotBlank() }
        if (adresses.isEmpty()) return null
        val src = tete.substring(0, g).trim()
        if (src.isEmpty() || src.length > 12) return null
        return Trame(Adresse.de(adresses[0]), Adresse.de(src), adresses.drop(1).map { Adresse.de(it) },
            ligne.substring(k + 1).toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * Each frame as what it says of a station and how it came: a third-party
     * frame ("}…", put on the air by a gateway) is the inside station's,
     * from the internet when its own path says so, else relayed.
     */
    data class Vu(val p: Paquet, val voie: Voie, val brut: String)

    fun vu(p: Paquet): Vu {
        if (p.type == TypeAprs.TIERS) {
            val t = tnc2(p.commentaire)
            if (t != null) {
                val q = Aprs.lit(t, p.quand).copy(satellite = p.satellite)
                val v = if (t.relais.any { internet(it) } || voie(p.trame) == Voie.INTERNET) Voie.INTERNET else maxOf(voie(p.trame), Voie.DIGI)
                return Vu(q, v, p.trame.tnc2())
            }
        }
        return Vu(p, voie(p.trame), p.trame.tnc2())
    }

    /** What a station is, for the list's filters and the map's look. */
    enum class Genre { MOBILE, FIXE, METEO, DIGI, IGATE, OBJET, SATELLITE }

    data class Point(val t: Long, val lat: Double, val lon: Double)
    data class Message(val t: Long, val a: String, val texte: String)

    data class Fiche(
        /** The callsign (an object: its name). */
        val id: String,
        /** Who sent it (an object's sender). */
        val indicatif: String,
        val objet: Boolean,
        val symbole: String?,
        val lat: Double?,
        val lon: Double?,
        val km: Double?,
        /** From the station, degrees. */
        val azimut: Double?,
        val locator: String?,
        val cap: Int?,
        val kmh: Int?,
        val altitudeM: Int?,
        val commentaire: String,
        val statut: String,
        val premier: Long,
        val dernier: Long,
        val nombre: Int,
        /** The last time it was heard each way. */
        val vus: Map<Voie, Long>,
        /** The way of its last frame. */
        val voie: Voie,
        val chemin: String,
        val digis: List<String>,
        val brut: String,
        val satellite: String?,
        val meteo: Meteo?,
        val meteoT: Long?,
        val message: Message?,
        val telemetrie: Message?,
        val trace: List<Point>,
        val genre: Genre,
        /** Ourselves, heard back. */
        val moi: Boolean,
    )

    /** A satellite pass as heard: the frames through it within [AprsJeu.PASSAGE_MS] of each other. */
    data class Passage(val debut: Long, val fin: Long, val satellite: String, val stations: List<String>, val paquets: Int)

    data class Bilan(
        val fiches: List<Fiche>,
        val passages: List<Passage>,
        /** Stations heard since [debutJour]. */
        val jour: Int,
        /** Stations heard through a satellite. */
        val viaSatellite: Int,
        /** The farthest heard direct (no digipeater): its callsign and km. */
        val loinDirect: Pair<String, Double>?,
        /** Frames heard each hour, the last 24 hours, oldest first. */
        val parHeure: IntArray,
        val paquets: Int,
    )

    /** Moving things by their symbol: cars, trucks, bicycles, boats, aircraft, balloons, people. */
    private val MOBILES = setOf(
        "/>", "/<", "/=", "/O", "/P", "/R", "/U", "/X", "/Y", "/[", "/^", "/'", "/a", "/b", "/e", "/f", "/g", "/j", "/k",
        "/p", "/s", "/u", "/v", "/C", "/F", "/*", "/S", "\\>", "\\^", "\\k", "\\s", "\\u", "\\v", "\\O", "\\'")

    /** Points of a trail further apart than this (km) are kept; closer, only the newest. */
    private const val PAS_TRACE_KM = 0.05
    const val TRACE_MAX = 200

    /** No position: 0 N 0 E is what a station without GPS sends (the ISS's beacon among them). */
    private fun nulle(lat: Double?, lon: Double?): Boolean = lat == null || lon == null || (kotlin.math.abs(lat) < 0.01 && kotlin.math.abs(lon) < 0.01)

    /**
     * The stations heard in [paquets] (any order) since [depuis]: one [Fiche]
     * each, newest first; sent frames left out. [moi]: our callsign (our
     * frames heard back are ours); [obsLat]/[obsLon]: where we are, for the
     * distances and bearings.
     */
    fun bilan(paquets: List<Paquet>, moi: String, obsLat: Double?, obsLon: Double?, maintenant: Long,
              depuis: Long = maintenant - 48 * 3_600_000L, debutJour: Long = maintenant - 24 * 3_600_000L): Bilan {
        val vus = paquets.asSequence().filter { !it.emis && it.quand >= depuis }.map { vu(it) }.sortedBy { it.p.quand }.toList()
        val base = AprsJeu.base(moi)
        // Who repeats others' frames by name: a digipeater.
        val repeteurs = HashSet<String>()
        vus.forEach { v -> v.p.trame.relais.forEach { a -> if (a.repete && a.indicatif !in GENERIQUES) repeteurs += a.copy(repete = false).toString() } }

        val groupes = LinkedHashMap<String, MutableList<Vu>>()
        vus.forEach { v -> groupes.getOrPut(if (v.p.type == TypeAprs.OBJET && v.p.nom != null) v.p.nom else v.p.source) { ArrayList() } += v }

        val fiches = groupes.map { (id, l) -> fiche(id, l, repeteurs, base, obsLat, obsLon) }.sortedByDescending { it.dernier }

        val parHeure = IntArray(24)
        vus.forEach { v -> val h = ((maintenant - v.p.quand) / 3_600_000L).toInt(); if (h in 0..23) parHeure[23 - h]++ }

        val loin = fiches.filter { it.km != null && it.vus.containsKey(Voie.DIRECT) && !it.moi && !it.objet }.maxByOrNull { it.km!! }
        return Bilan(fiches, passages(vus), fiches.count { it.dernier >= debutJour && !it.moi },
            fiches.count { it.vus.containsKey(Voie.SATELLITE) && !it.moi && it.genre != Genre.SATELLITE },
            loin?.let { it.id to it.km!! }, parHeure, vus.size)
    }

    /** Aliases a digipeater answers, not its name. */
    private val GENERIQUES = setOf("WIDE", "WIDE1", "WIDE2", "WIDE3", "TRACE", "RELAY", "ARISS", "APRSAT", "SATGTE", "ECHO", "GATE")

    private fun fiche(id: String, l: List<Vu>, repeteurs: Set<String>, moi: String, obsLat: Double?, obsLon: Double?): Fiche {
        val dernier = l.last()
        val avecPos = l.filter { !nulle(it.p.lat, it.p.lon) }
        val pos = avecPos.lastOrNull()?.p
        val trace = ArrayList<Point>()
        avecPos.forEach { v ->
            val q = Point(v.p.quand, v.p.lat!!, v.p.lon!!)
            val prec = trace.lastOrNull()
            if (prec != null && Geo.distanceKm(prec.lat, prec.lon, q.lat, q.lon) < PAS_TRACE_KM) trace[trace.size - 1] = q else trace += q
        }
        val voies = HashMap<Voie, Long>()
        l.forEach { v -> voies[v.voie] = v.p.quand }
        val sym = l.lastOrNull { it.p.symbole != null }?.p?.symbole
        val meteo = l.lastOrNull { it.p.meteo != null }
        val statut = l.lastOrNull { it.p.type == TypeAprs.STATUT }?.p?.commentaire ?: ""
        val com = l.lastOrNull { (it.p.type == TypeAprs.POSITION || it.p.type == TypeAprs.OBJET || it.p.type == TypeAprs.METEO) && it.p.commentaire.isNotBlank() }?.p?.commentaire ?: ""
        val msg = l.lastOrNull { it.p.type == TypeAprs.MESSAGE }?.p?.let { Message(it.quand, it.destinataire ?: "", it.message ?: "") }
        val tlm = l.lastOrNull { it.p.type == TypeAprs.TELEMETRIE }?.p?.let { Message(it.quand, "", it.commentaire) }
        val dernierePos = l.lastOrNull { it.p.lat != null }?.p
        // Moving: a speed given, or no symbol to tell and a trail of more than 500 m (a fixed symbol's jitter is no trip).
        val moving = (pos?.vitesseKmh ?: 0) > 0 || sym == null && trace.size >= 2 && trace.zipWithNext().sumOf { (a, b) -> Geo.distanceKm(a.lat, a.lon, b.lat, b.lon) } > 0.5
        val objet = dernier.p.type == TypeAprs.OBJET
        val sat = l.lastOrNull { it.voie == Voie.SATELLITE }?.let { nomSatellite(it.p) }
        val genre = when {
            satellite(dernier.p.trame.source) != null && !objet -> Genre.SATELLITE
            objet -> Genre.OBJET
            meteo != null || sym?.getOrNull(1) == '_' -> Genre.METEO
            sym?.getOrNull(1) == '&' || com.contains("igate", true) -> Genre.IGATE
            sym?.getOrNull(1) == '#' || id in repeteurs -> Genre.DIGI
            moving || (sym != null && (sym in MOBILES || (sym[0] !in "/\\" && "\\" + sym[1] in MOBILES))) -> Genre.MOBILE
            else -> Genre.FIXE
        }
        val km = if (pos != null && obsLat != null && obsLon != null) Geo.distanceKm(obsLat, obsLon, pos.lat!!, pos.lon!!) else null
        val az = if (pos != null && obsLat != null && obsLon != null && (km ?: 0.0) > 0.01) Geo.bearingDeg(obsLat, obsLon, pos.lat!!, pos.lon!!) else null
        return Fiche(
            id = id, indicatif = dernier.p.source, objet = objet, symbole = sym,
            lat = pos?.lat, lon = pos?.lon, km = km, azimut = az,
            locator = pos?.let { Maidenhead.fromLatLon(it.lat!!, it.lon!!) },
            cap = dernierePos?.cap, kmh = dernierePos?.vitesseKmh, altitudeM = l.lastOrNull { it.p.altitudeM != null }?.p?.altitudeM,
            commentaire = com, statut = statut, premier = l.first().p.quand, dernier = dernier.p.quand, nombre = l.size,
            vus = voies, voie = dernier.voie, chemin = dernier.p.trame.relais.joinToString(",") { it.toString() },
            digis = digis(dernier.p.trame), brut = dernier.brut, satellite = sat,
            meteo = meteo?.p?.meteo, meteoT = meteo?.p?.quand, message = msg, telemetrie = tlm,
            trace = trace.takeLast(TRACE_MAX), genre = genre,
            moi = moi.isNotEmpty() && AprsJeu.base(dernier.p.source) == moi,
        )
    }

    /** The satellite passes as heard: frames through a satellite, a pass ending after [AprsJeu.PASSAGE_MS] of silence. */
    fun passages(vus: List<Vu>): List<Passage> {
        val l = vus.filter { it.voie == Voie.SATELLITE }.sortedBy { it.p.quand }
        val out = ArrayList<Passage>()
        var courant = ArrayList<Vu>()
        fun ferme() {
            if (courant.isEmpty()) return
            val nom = courant.mapNotNull { nomSatellite(it.p) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "satellite"
            out += Passage(courant.first().p.quand, courant.last().p.quand, nom,
                courant.filter { satellite(it.p.trame.source) == null }.map { it.p.nom?.takeIf { _ -> it.p.type == TypeAprs.OBJET } ?: it.p.source }.distinct(), courant.size)
            courant = ArrayList()
        }
        l.forEach { v -> if (courant.isNotEmpty() && v.p.quand - courant.last().p.quand > AprsJeu.PASSAGE_MS) ferme(); courant += v }
        ferme()
        return out.sortedByDescending { it.debut }
    }
}
