/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.adsb

import fr.f4ioz.satcombo.sonde.Geo
import org.json.JSONArray
import org.json.JSONObject

/**
 * The aircraft heard, by 24-bit address: what each last said, its position
 * worked out from its CPR reports, and forgotten [oubliMs] after its last
 * reply.
 *
 * **Positions.** An even and an odd airborne report less than 10 s apart
 * give a global fix; after it, each report is decoded locally against the
 * aircraft's last fix (if under a minute old). Before any global fix, a
 * report decoded locally against the station (its locator) is kept only
 * when it lands within [porteeKm] — the reach of a receiver on 1090 MHz —
 * and is marked as such until a global fix confirms it. Surface reports
 * always need a reference: the station.
 */
class TableAvions(private val horloge: () -> Long = System::currentTimeMillis) {

    class Avion(val icao: Int) {
        var indicatif = ""
        var categorie = ""
        var squawk = ""
        var altitudePieds: Int? = null
        var altitudeGnss: Int? = null
        var vitesseNoeuds: Double? = null
        var routeDeg: Double? = null
        var air = false
        var vertical: Int? = null
        var lat: Double? = null
        var lon: Double? = null
        var posMs = 0L
        /** Fixed by an even/odd pair (or locally from such a fix), not only against the station. */
        var posSure = false
        var surface = false
        var dernierMs = 0L
        var premierMs = 0L
        var messages = 0L
        var rssiDb = -99.0
        /** The last even and odd reports: CPR latitude, longitude, time. */
        internal var pair = longArrayOf(-1, -1, 0)
        internal var impair = longArrayOf(-1, -1, 0)
        internal var cprSurface = false
        /** The fixes of the last minutes, for the track on the map: lat, lon, time. */
        val trace = ArrayDeque<Triple<Double, Double, Long>>()

        fun hex(): String = "%06X".format(icao)
    }

    private val avions = LinkedHashMap<Int, Avion>()

    /** Forget an aircraft this long after its last reply. */
    @Volatile var oubliMs = 60_000L
    /** Locally decoded positions against the station further than this are dropped. */
    @Volatile var porteeKm = 450.0
    /** The station: reference for the local decoding and the distances. */
    @Volatile var station: Pair<Double, Double>? = null

    @Volatile var total = 0L; private set
    @Volatile var positions = 0L; private set

    /** Lines handed out as replies come (the SBS-1 output). */
    @Volatile var ecouteur: ((MessageAdsb, Avion) -> Unit)? = null

    @Synchronized fun connu(icao: Int): Boolean {
        val a = avions[icao] ?: return false
        return horloge() - a.dernierMs < oubliMs
    }

    @Synchronized fun vide() { avions.clear(); total = 0; positions = 0 }

    /** One reply checked by the demodulator. */
    fun recoit(t: DemodAdsb.Trame) {
        val m = MessageAdsb.lis(t.octets, t.nBits, t.icaoParite) ?: return
        recoit(m, t.rssiDb)
    }

    @Synchronized fun recoit(m: MessageAdsb, rssiDb: Double) {
        val now = horloge()
        val a = avions.getOrPut(m.icao) { Avion(m.icao).also { it.premierMs = now } }
        a.dernierMs = now
        a.messages++
        total++
        // Smoothed: one strong reply does not make the aircraft near.
        a.rssiDb = if (a.messages == 1L) rssiDb else a.rssiDb * 0.8 + rssiDb * 0.2
        when (m) {
            is MessageAdsb.Identification -> { if (m.indicatif.isNotEmpty()) a.indicatif = m.indicatif; if (m.categorie.isNotEmpty()) a.categorie = m.categorie }
            is MessageAdsb.Vitesse -> { m.vitesseNoeuds?.let { a.vitesseNoeuds = it }; m.routeDeg?.let { a.routeDeg = it }; a.air = m.air; a.vertical = m.vertical; a.surface = false }
            is MessageAdsb.Altitude -> m.altitudePieds?.let { a.altitudePieds = it }
            is MessageAdsb.Identite -> a.squawk = m.squawk
            is MessageAdsb.Position -> position(a, m, now)
            is MessageAdsb.Autre -> {}
        }
        ecouteur?.let { runCatching { it(m, a) } }
    }

    private fun position(a: Avion, m: MessageAdsb.Position, now: Long) {
        if (m.surface) {
            a.surface = true
            a.altitudePieds = null
            m.vitesseNoeuds?.let { a.vitesseNoeuds = it }
            m.routeDeg?.let { a.routeDeg = it }
        } else {
            a.surface = false
            if (m.gnss) m.altitudePieds?.let { a.altitudeGnss = it } else m.altitudePieds?.let { a.altitudePieds = it }
        }
        // Surface and airborne reports do not pair.
        if (a.cprSurface != m.surface) { a.pair = longArrayOf(-1, -1, 0); a.impair = longArrayOf(-1, -1, 0); a.cprSurface = m.surface }
        val ce = longArrayOf(m.latCpr.toLong(), m.lonCpr.toLong(), now)
        if (m.impair) a.impair = ce else a.pair = ce
        val st = station
        var fix: Pair<Double, Double>? = null
        var sure = false
        val p = a.pair; val i = a.impair
        val recente = a.lat != null && now - a.posMs < (if (m.surface) 25_000L else 60_000L) && a.posSure
        if (recente) {
            fix = Cpr.locale(m.latCpr, m.lonCpr, m.impair, a.lat!!, a.lon!!, m.surface)
            // A jump beyond what an aircraft can fly since: a bad report, the pair redone.
            if (Geo.distanceKm(a.lat!!, a.lon!!, fix.first, fix.second) > 15.0 + (now - a.posMs) / 1000.0 * 0.4) fix = null
            else sure = true
        }
        if (fix == null && p[0] >= 0 && i[0] >= 0 && kotlin.math.abs(p[2] - i[2]) <= (if (m.surface) 25_000L else 10_000L)) {
            fix = Cpr.globale(p[0].toInt(), p[1].toInt(), i[0].toInt(), i[1].toInt(), m.impair, m.surface, st)
            if (fix != null && st != null && Geo.distanceKm(st.first, st.second, fix.first, fix.second) > porteeKm * 1.5) fix = null
            sure = fix != null
        }
        if (fix == null && st != null && !recente) {
            val l = Cpr.locale(m.latCpr, m.lonCpr, m.impair, st.first, st.second, m.surface)
            if (Geo.distanceKm(st.first, st.second, l.first, l.second) <= porteeKm) { fix = l; sure = false }
        }
        if (fix == null) return
        a.lat = fix.first; a.lon = fix.second; a.posMs = now; a.posSure = sure
        positions++
        a.trace.addLast(Triple(fix.first, fix.second, now))
        while (a.trace.size > 2 && now - a.trace.first().third > 600_000L) a.trace.removeFirst()
        while (a.trace.size > 400) a.trace.removeFirst()
    }

    /** Forgets the silent ones; the others, nearest reply first. */
    @Synchronized fun liste(): List<Avion> {
        val now = horloge()
        avions.values.removeAll { now - it.dernierMs > oubliMs }
        return avions.values.sortedByDescending { it.dernierMs }
    }

    /** The aircraft as JSON, read under the table's lock. */
    @Synchronized fun json(trace: Boolean = false): JSONArray {
        val now = horloge()
        return JSONArray(liste().map { json(it, station, now, trace) })
    }

    /** An aircraft by its address (hex). */
    @Synchronized fun avion(hex: String): Avion? = hex.toIntOrNull(16)?.let { avions[it] }

    companion object {
        fun json(a: Avion, station: Pair<Double, Double>?, maintenant: Long, trace: Boolean = false): JSONObject {
            val o = JSONObject().put("icao", a.hex()).put("indicatif", a.indicatif).put("categorie", a.categorie)
                .put("squawk", a.squawk).put("messages", a.messages).put("rssi", Math.round(a.rssiDb * 10) / 10.0)
                .put("vu", maintenant - a.dernierMs).put("surface", a.surface)
            a.altitudePieds?.let { o.put("alt", it) }
            a.altitudeGnss?.let { o.put("altGnss", it) }
            a.vitesseNoeuds?.let { o.put("vitesse", Math.round(it)) }
            a.routeDeg?.let { o.put("route", Math.round(it)) }
            if (a.air) o.put("cap", true)
            a.vertical?.let { o.put("vz", it) }
            val la = a.lat; val lo = a.lon
            if (la != null && lo != null) {
                o.put("lat", Math.round(la * 1e5) / 1e5).put("lon", Math.round(lo * 1e5) / 1e5).put("posAge", maintenant - a.posMs).put("sure", a.posSure)
                if (station != null) {
                    o.put("km", Math.round(Geo.distanceKm(station.first, station.second, la, lo) * 10) / 10.0)
                    o.put("az", Math.round(Geo.bearingDeg(station.first, station.second, la, lo)))
                }
                if (trace) o.put("trace", JSONArray(a.trace.map { JSONArray(listOf(Math.round(it.first * 1e4) / 1e4, Math.round(it.second * 1e4) / 1e4)) }))
            }
            return o
        }
    }
}
