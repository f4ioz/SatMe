/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ais

import fr.f4ioz.satcombo.sonde.Geo
import org.json.JSONArray
import org.json.JSONObject

/**
 * The vessels heard, by MMSI: positions from 1/2/3/18/19/21, names and
 * the rest from 5/19/24/21, merged; forgotten [oubliMs] after the last
 * message (a ship at anchor reports every three minutes, its static data
 * every six).
 */
class TableNavires(private val horloge: () -> Long = System::currentTimeMillis) {

    class Navire(val mmsi: Int) {
        var classe = "A"
        var nom = ""
        var indicatif = ""
        var imo: Int? = null
        var type: Int? = null
        var aide: Int? = null
        var statut: Int? = null
        var lat: Double? = null
        var lon: Double? = null
        var vitesse: Double? = null
        var route: Double? = null
        var cap: Int? = null
        var destination = ""
        var dimensions: IntArray? = null
        var tirant: Double? = null
        var horsPosition = false
        var dernierMs = 0L
        var posMs = 0L
        var messages = 0
        var canaux = ""
        var niveauDb = -99.0
        val trace = ArrayDeque<Triple<Double, Double, Long>>()
        /** Length and beam in metres, from the reference point's distances. */
        fun longueur() = dimensions?.let { it[0] + it[1] }?.takeIf { it > 0 }
        fun largeur() = dimensions?.let { it[2] + it[3] }?.takeIf { it > 0 }
    }

    private val navires = LinkedHashMap<Int, Navire>()
    @Volatile var oubliMs = 20 * 60_000L
    @Volatile var station: Pair<Double, Double>? = null
    @Volatile var total = 0L; private set

    @Synchronized fun vide() { navires.clear(); total = 0 }

    @Synchronized fun recoit(m: MessageAis, canal: Char = 'A', niveauDb: Double = -99.0) {
        if (m.mmsi <= 0) return
        val now = horloge()
        val n = navires.getOrPut(m.mmsi) { Navire(m.mmsi) }
        total++
        n.messages++; n.dernierMs = now; n.niveauDb = niveauDb
        if (canal !in n.canaux) n.canaux = (n.canaux + canal).toList().sorted().joinToString("")
        n.classe = m.classe.takeIf { m.type != 24 || n.classe != "AtoN" } ?: n.classe
        m.nom?.let { n.nom = it }
        m.indicatif?.let { n.indicatif = it }
        m.imo?.let { n.imo = it }
        if (m.type != 21) m.typeNavire?.takeIf { it > 0 }?.let { n.type = it }
        m.aide?.let { n.aide = it }
        m.statut?.let { n.statut = it }
        m.destination?.let { n.destination = it }
        m.dimensions?.let { n.dimensions = it }
        m.tirant?.takeIf { it > 0 }?.let { n.tirant = it }
        m.horsPosition?.let { n.horsPosition = it }
        if (m.lat != null && m.lon != null) {
            n.lat = m.lat; n.lon = m.lon; n.posMs = now
            n.trace.addLast(Triple(m.lat, m.lon, now))
            while (n.trace.size > 120 || (n.trace.isNotEmpty() && now - n.trace.first().third > 3_600_000L)) n.trace.removeFirst()
        }
        if (m.type != 21 && m.type != 24 && m.type != 5) { n.vitesse = m.vitesse; n.route = m.route; n.cap = m.cap }
    }

    @Synchronized fun liste(): List<Navire> {
        val now = horloge()
        navires.values.removeAll { now - it.dernierMs > oubliMs }
        return navires.values.sortedByDescending { it.dernierMs }
    }

    @Synchronized fun json(trace: Boolean = false): JSONArray {
        val now = horloge()
        return JSONArray(liste().map { json(it, station, now, trace) })
    }

    companion object {
        fun json(n: Navire, station: Pair<Double, Double>?, maintenant: Long, trace: Boolean = false): JSONObject {
            val o = JSONObject().put("mmsi", n.mmsi).put("classe", n.classe).put("nom", n.nom).put("indicatif", n.indicatif)
                .put("type", n.type ?: JSONObject.NULL).put("typeTexte", if (n.classe == "AtoN") aideTexte(n.aide) else typeTexte(n.type))
                .put("famille", if (n.classe == "AtoN") "aide" else famille(n.type))
                .put("statut", statutTexte(n.statut)).put("destination", n.destination)
                .put("vu", maintenant - n.dernierMs).put("messages", n.messages).put("canaux", n.canaux)
                .put("niveau", Math.round(n.niveauDb * 10) / 10.0)
            n.imo?.let { o.put("imo", it) }
            n.longueur()?.let { o.put("longueur", it) }
            n.largeur()?.let { o.put("largeur", it) }
            n.tirant?.let { o.put("tirant", it) }
            if (n.horsPosition) o.put("horsPosition", true)
            n.vitesse?.let { o.put("vitesse", it) }
            n.route?.let { o.put("route", it) }
            n.cap?.let { o.put("cap", it) }
            val la = n.lat; val lo = n.lon
            if (la != null && lo != null) {
                o.put("lat", Math.round(la * 1e5) / 1e5).put("lon", Math.round(lo * 1e5) / 1e5).put("posVu", maintenant - n.posMs)
                station?.let { (sa, so) ->
                    o.put("km", Math.round(Geo.distanceKm(sa, so, la, lo) * 10) / 10.0).put("az", Math.round(Geo.bearingDeg(sa, so, la, lo)).toInt() % 360)
                }
                if (trace && n.trace.size > 1) o.put("trace", JSONArray(n.trace.map { JSONArray(listOf(Math.round(it.first * 1e4) / 1e4, Math.round(it.second * 1e4) / 1e4)) }))
            }
            return o
        }

        /** The ship type's family, for the map's colours. */
        fun famille(t: Int?): String = when (t) {
            null, 0 -> "inconnu"
            30 -> "peche"
            31, 32, 52 -> "remorqueur"
            36, 37 -> "plaisance"
            in 40..49 -> "rapide"
            50, 51, 53, 54, 55, 58 -> "service"
            in 60..69 -> "passagers"
            in 70..79 -> "cargo"
            in 80..89 -> "citerne"
            else -> "autre"
        }

        fun typeTexte(t: Int?): String = when (t) {
            null, 0 -> ""
            in 20..29 -> "navire à effet de sol"
            30 -> "pêche"
            31, 32 -> "remorquage"
            33 -> "dragage"
            34 -> "plongée"
            35 -> "militaire"
            36 -> "voilier"
            37 -> "plaisance"
            in 40..49 -> "grande vitesse"
            50 -> "pilote"
            51 -> "recherche et sauvetage"
            52 -> "remorqueur"
            53 -> "navette portuaire"
            54 -> "antipollution"
            55 -> "police"
            58 -> "médical"
            in 60..69 -> "passagers"
            in 70..79 -> "cargo"
            in 80..89 -> "citerne"
            else -> "autre ($t)"
        }

        fun statutTexte(s: Int?): String = when (s) {
            0 -> "en route au moteur"
            1 -> "au mouillage"
            2 -> "non maître de sa manœuvre"
            3 -> "manœuvre restreinte"
            4 -> "handicapé par son tirant d'eau"
            5 -> "amarré"
            6 -> "échoué"
            7 -> "en pêche"
            8 -> "en route à la voile"
            14 -> "AIS-SART"
            else -> ""
        }

        fun aideTexte(a: Int?): String = when (a) {
            null, 0 -> "aide à la navigation"
            1 -> "point de référence"
            2 -> "RACON"
            3 -> "structure fixe en mer"
            in 5..19 -> "feu ou balise fixe"
            in 20..31 -> "bouée"
            else -> "aide à la navigation"
        }
    }
}
