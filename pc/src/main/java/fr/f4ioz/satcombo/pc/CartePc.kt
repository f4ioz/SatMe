/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ApiCarte
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.location.Maidenhead
import org.json.JSONArray
import org.json.JSONObject

/**
 * The shared map (« Carte »): its coastlines from SatMe's own data, and
 * every layer's points in one answer — satellites, APRS, the sonde, the
 * aircraft, the DATV stations heard with a locator, the scatter path.
 * Read only; nothing here goes to the Internet.
 */
class CartePc(private val station: StationPc, private val son: SonStation?, private val sdr: SdrPc?,
              private val adsb: AdsbPc?, private val datv: DatvPc?, private val textesDatv: () -> TextesDatvPc?,
              private val qrzDatv: () -> QrzDatvPc?, private val horloge: () -> Long = System::currentTimeMillis) {

    private val traits: ByteArray by lazy {
        val t = CartePc::class.java.getResourceAsStream("/traits/land.json")?.bufferedReader()?.use { it.readText() }
        (if (t == null) "[]" else ApiCarte.traits(t)).toByteArray(Charsets.UTF_8)
    }

    /** DATV stations whose card gave a locator: callsign → locator, when. */
    private val datvVus = LinkedHashMap<String, Pair<String, Long>>()

    @Synchronized private fun stationsDatv(): JSONArray {
        val now = horloge()
        datv?.carte?.carte?.let { c ->
            if (c.indicatif.isNotBlank() && Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?.*$").matches(c.locator.uppercase()))
                datvVus[c.indicatif.uppercase()] = c.locator.uppercase() to (datv.carte.carteMs.takeIf { it > 0 } ?: now)
        }
        // The callsigns read in the picture, located by their QRZ record when one came.
        runCatching {
            val q = qrzDatv()
            textesDatv()?.confirmes()?.forEach { l ->
                val f = q?.fiche(l.indicatif) ?: return@forEach
                if (f.carre.length >= 4 && !datvVus.containsKey(l.indicatif.uppercase())) datvVus[l.indicatif.uppercase()] = f.carre.uppercase() to l.dernierMs
            }
        }
        while (datvVus.size > 100) datvVus.remove(datvVus.keys.first())
        return JSONArray(datvVus.mapNotNull { (call, v) ->
            val (la, lo) = Maidenhead.centre(v.first) ?: return@mapNotNull null
            ApiCarte.point(call, la, lo, "loc" to v.first, "t" to v.second)
        })
    }

    fun couches(): JSONObject {
        val e = station.etat.value
        val now = horloge()
        val o = JSONObject().put("ok", true).put("t", now)
        e.observateur?.let { o.put("moi", ApiCarte.point(e.indicatif.ifBlank { "Moi" }, it.latDeg, it.lonDeg, "loc" to e.locator)) }
        // The favourites and the followed satellite, where they are now.
        val nums = (listOfNotNull(e.suivi?.catalogNumber) + e.favoris).distinct().take(40)
        o.put("satellites", JSONArray(nums.mapNotNull { n ->
            val s = e.satellites.firstOrNull { it.catalogNumber == n } ?: return@mapNotNull null
            val p = station.positionSatellite(n, now) ?: return@mapNotNull null
            ApiCarte.point(s.name, p.latDeg, p.lonDeg, "catnum" to n, "alt" to Math.round(p.altKm), "el" to Math.round(p.elevationDeg),
                "suivi" to (n == e.suivi?.catalogNumber))
        }))
        son?.aprs?.let { a -> o.put("aprs", ApiCarte.aprs(fr.f4ioz.satcombo.aprs.AprsJeu.stations(a.paquets, e.observateur?.latDeg, e.observateur?.lonDeg))) }
        sdr?.sondes?.let { s ->
            val d = s.etat.derniere
            val v = s.vol
            if (d != null && (d.lat != 0.0 || d.lon != 0.0)) {
                val so = ApiCarte.point(d.serial.ifBlank { d.type }, d.lat, d.lon, "alt" to Math.round(d.altM), "type" to d.type)
                if (v != null) so.put("trace", JSONArray(v.track.filterIndexed { i, _ -> i % maxOf(1, v.track.size / 300) == 0 }
                    .map { JSONArray(listOf(Math.round(it.lat * 1e4) / 1e4, Math.round(it.lon * 1e4) / 1e4)) }))
                o.put("sonde", so)
            }
        }
        adsb?.let { a ->
            a.table.station = e.observateur?.let { it.latDeg to it.lonDeg }
            o.put("avions", a.table.json(trace = true))
            a.reglageDiffusion()?.let { r ->
                val ouvertes = fr.f4ioz.satcombo.adsb.DiffusionAvions.fenetres(r, a.table.liste(), now).filter { it.ouverte }.map { it.icao }
                o.put("diffusion", fr.f4ioz.satcombo.adsb.DiffusionAvions.geometrieJson(r).put("cible", a.cible).put("bande", a.bande).put("ouvertes", JSONArray(ouvertes)))
            }
        }
        o.put("datv", stationsDatv())
        return o
    }

    fun sert(chemin: String): ReponseWeb = when (chemin) {
        "/api/carte/traits" -> ReponseWeb("200 OK", "application/json; charset=utf-8", traits, "Cache-Control: max-age=86400\r\n")
        else -> ReponseWeb("200 OK", "application/json; charset=utf-8", runCatching { couches() }
            .getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }.toString().toByteArray(Charsets.UTF_8))
    }
}
