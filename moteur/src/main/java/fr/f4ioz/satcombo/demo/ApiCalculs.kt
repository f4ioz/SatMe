/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.data.AgendaStore
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.domain.TraceSked
import fr.f4ioz.satcombo.location.Maidenhead
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the station page's Agenda, Squares, Timeline and Sked tabs compute,
 * written once: SatMe-PC and the phone give their satellites, position and
 * log; the answers are the same, in the same words.
 */
object ApiCalculs {

    // ------------------------------------------------------------ timeline

    /** One satellite on the timeline: its elevation each minute (negative: below the horizon). */
    class LigneFrise(val sat: TleEntry, val elevations: List<Double>)

    /** The satellites of the timeline: the favourites, the one followed, those of the agenda to come; 30 at most. */
    fun satellitesFrise(tous: List<TleEntry>, favoris: Set<Int>, suivi: TleEntry?, agenda: List<AgendaStore.AgendaEvent>, debut: Long): List<TleEntry> {
        val parNom = { n: String -> tous.firstOrNull { it.name.equals(n, true) } }
        val nums = (favoris + listOfNotNull(suivi?.catalogNumber) + agenda.filter { it.endOrStartMs >= debut }.mapNotNull { parNom(it.satName)?.catalogNumber }).distinct()
        return nums.mapNotNull { n -> tous.firstOrNull { it.catalogNumber == n } }.sortedBy { it.name.uppercase() }.take(30)
    }

    /** Their elevation over [heures] hours from [debut], one value a minute. */
    fun frise(p: PassPredictor, obs: Observer, sats: List<TleEntry>, debut: Long, heures: Int): List<LigneFrise> = sats.map { s ->
        LigneFrise(s, (0 until heures * 60).map { k -> runCatching { p.positionAt(s, obs, debut + k * 60_000L).elevationDeg }.getOrDefault(-90.0) })
    }

    fun friseJson(debut: Long, lignes: List<LigneFrise>): JSONObject = JSONObject().put("debut", debut)
        .put("lignes", JSONArray(lignes.map { l -> JSONObject().put("nom", l.sat.name).put("catnum", l.sat.catalogNumber)
            .put("el", JSONArray(l.elevations.map { Math.round(it * 10) / 10.0 })) }))

    // ---------------------------------------------------------------- skeds

    /** A window both stations see the satellite: when, and how high for each. */
    class FenetreSked(val deMs: Long, val aMs: Long, val elMoi: Int, val elAutre: Int, val azMoi: Double, val azAutre: Double,
                      val debutTrace: Long, val finTrace: Long)

    /**
     * The windows of the next 48 hours in which I (above [minElMoi]°) and the
     * station in square [locator] (above [minElAutre]°) both see [sat], a
     * minute at least; null when the square is not one.
     */
    fun sked(p: PassPredictor, sat: TleEntry, moi: Observer, locator: String, minElMoi: Double, minElAutre: Double, maintenant: Long): List<FenetreSked>? {
        val ll = Maidenhead.centre(locator.trim()) ?: return null
        val b = Observer(ll.first, ll.second, 0.0, locator.trim().uppercase())
        val pa = runCatching { p.upcomingPasses(sat, moi, maintenant, 48, minElMoi) }.getOrDefault(emptyList())
        val pb = runCatching { p.upcomingPasses(sat, b, maintenant, 48, minElAutre) }.getOrDefault(emptyList())
        val out = ArrayList<FenetreSked>()
        for (x in pa) for (y in pb) {
            val de = maxOf(x.aosEpochMs, y.aosEpochMs); val fin = minOf(x.losEpochMs, y.losEpochMs)
            if (fin - de < 60_000L) continue
            val mi = (de + fin) / 2
            val pA = runCatching { p.positionAt(sat, moi, mi) }.getOrNull()
            val pB = runCatching { p.positionAt(sat, b, mi) }.getOrNull()
            out += FenetreSked(de, fin, x.maxElevationDeg.toInt(), y.maxElevationDeg.toInt(), pA?.azimuthDeg ?: 0.0, pB?.azimuthDeg ?: 0.0,
                minOf(x.aosEpochMs, y.aosEpochMs), maxOf(x.losEpochMs, y.losEpochMs))
        }
        return out.sortedBy { it.deMs }
    }

    fun skedJson(f: List<FenetreSked>?): JSONObject =
        if (f == null) JSONObject().put("ok", false).put("erreur", "carré non reconnu")
        else JSONObject().put("ok", true).put("fenetres", JSONArray(f.map { w -> JSONObject().put("de", w.deMs).put("a", w.aMs)
            .put("elMoi", w.elMoi).put("elAutre", w.elAutre).put("azMoi", Math.round(w.azMoi)).put("azAutre", Math.round(w.azAutre))
            .put("debutTrace", w.debutTrace).put("finTrace", w.finTrace) }))

    /** The sked replayed: both skies and the satellite over the earth, every few seconds ([TraceSked]). */
    fun traceSked(p: PassPredictor, sat: TleEntry, moi: Observer, locator: String, deMs: Long, aMs: Long): TraceSked? {
        val ll = Maidenhead.centre(locator.trim()) ?: return null
        return TraceSked.calcule(p, sat, moi, Observer(ll.first, ll.second, 0.0, locator.trim().uppercase()), deMs, aMs)
    }

    fun traceSkedJson(tr: TraceSked?): JSONObject {
        fun r(x: Double, n: Int) = Math.round(x * n) / n.toDouble()
        return if (tr == null) JSONObject().put("ok", false)
        else JSONObject().put("ok", true)
            .put("moi", JSONObject().put("lat", tr.moi.latDeg).put("lon", tr.moi.lonDeg))
            .put("autre", JSONObject().put("lat", tr.autre.latDeg).put("lon", tr.autre.lonDeg))
            .put("points", JSONArray(tr.points.map { q -> JSONArray(listOf(q.t, r(q.azMoi, 10), r(q.elMoi, 10), r(q.azAutre, 10), r(q.elAutre, 10),
                r(q.lat, 100), r(q.lon, 100), Math.round(q.altKm))) }))
    }

    // ------------------------------------------------------- squares worked

    /**
     * The squares worked (4 characters), from the log: how many contacts,
     * the first one, who, on which satellites, how far; and mine.
     */
    fun carres(contacts: List<LogEntry>, monLocator: String, obs: Observer?): JSONObject {
        val l = contacts.filter { it.theirLocator.length >= 4 && Maidenhead.centre(it.theirLocator.take(4)) != null }
        val parCarre = l.groupBy { it.theirLocator.take(4).uppercase() }
        return JSONObject().put("mon", monLocator.take(4).uppercase()).put("lat", obs?.latDeg ?: JSONObject.NULL).put("lon", obs?.lonDeg ?: JSONObject.NULL)
            .put("champs", parCarre.keys.map { it.take(2) }.distinct().size)
            .put("carres", JSONArray(parCarre.entries.sortedBy { it.key }.map { (k, q) ->
                val centre = Maidenhead.centre(k)
                JSONObject().put("c", k).put("n", q.size).put("premier", q.minOf { it.timeMs }).put("dernier", q.maxOf { it.timeMs })
                    .put("indicatifs", JSONArray(q.map { it.callsign }.distinct().take(12)))
                    .put("sats", JSONArray(q.map { it.satName }.distinct()))
                    .put("km", if (obs != null && centre != null) Math.round(fr.f4ioz.satcombo.sonde.Geo.distanceKm(obs.latDeg, obs.lonDeg, centre.first, centre.second)) else JSONObject.NULL)
            }))
    }

    // ---------------------------------------------------------------- agenda

    /** The agenda as the page shows it: what is to come or under way (an hour back), a hundred at most. */
    fun agendaJson(l: List<AgendaStore.AgendaEvent>, maintenant: Long): JSONArray =
        JSONArray(l.filter { it.endOrStartMs >= maintenant - 3_600_000L }.take(100).map { a ->
            JSONObject().put("id", a.id).put("titre", a.title).put("sat", a.satName).put("de", a.timeMs).put("a", a.endOrStartMs)
                .put("actif", a.enabled).put("type", a.kind).put("note", a.note).put("freq", a.freqHz)
                .put("enCours", a.activeAt(maintenant, 0L))
        })

    /** A pass into the agenda, or out of it when already there; true when put in. */
    fun basculePassage(s: fr.f4ioz.satcombo.plateforme.Stockage, l: List<AgendaStore.AgendaEvent>, p: fr.f4ioz.satcombo.data.SatPass, id: Long): Boolean {
        val deja = l.firstOrNull { it.satName == p.satName && it.covers(p.aosEpochMs, p.losEpochMs, 0L) }
        if (deja != null) AgendaStore.remove(s, deja.id)
        else AgendaStore.put(s, AgendaStore.AgendaEvent(id = id, title = "Passage " + p.satName, timeMs = p.aosEpochMs,
            satName = p.satName, leadMin = 5, endMs = p.losEpochMs))
        return deja == null
    }

    /** A sked into the agenda (its window), as an appointment of kind SKED. */
    fun skedAgenda(s: fr.f4ioz.satcombo.plateforme.Stockage, sat: TleEntry, locator: String, deMs: Long, aMs: Long, id: Long) {
        AgendaStore.put(s, AgendaStore.AgendaEvent(id = id, title = "Sked " + locator.trim().uppercase(),
            timeMs = deMs, satName = sat.name, leadMin = 10, endMs = aMs, kind = "SKED"))
    }

    // ------------------------------------------------- a contact afterwards

    /** The modes a contact typed afterwards can carry (the log's, the ADIF's). */
    val MODES_APRES = listOf("FM", "USB", "LSB", "CW", "DATA")

    /** [sat]'s passes over in the last [heures] hours, the newest first: where a contact typed afterwards belongs. */
    fun passagesFinis(p: PassPredictor, sat: TleEntry, obs: Observer, maintenant: Long, heures: Int, minEl: Double): List<fr.f4ioz.satcombo.data.SatPass> =
        runCatching { p.upcomingPasses(sat, obs, maintenant - heures * 3_600_000L, heures, minEl) }.getOrDefault(emptyList())
            .filter { it.aosEpochMs <= maintenant }.sortedByDescending { it.aosEpochMs }

    fun passagesFinisJson(l: List<fr.f4ioz.satcombo.data.SatPass>): JSONObject =
        JSONObject().put("passages", JSONArray(l.map { JSONObject().put("aos", it.aosEpochMs).put("los", it.losEpochMs).put("elMax", Math.round(it.maxElevationDeg)) }))
            .put("modes", JSONArray(MODES_APRES))

    /** When a contact typed afterwards may be: the past, a month back at most (a minute ahead for clocks). */
    fun heureApresValable(t: Long, maintenant: Long): Boolean = t in (maintenant - 31L * 86_400_000L)..(maintenant + 60_000L)

    /**
     * A contact typed afterwards (a paper log, a pass without the keyboard):
     * at [quandMs], on [sat], its az/el where the satellite was then; the
     * mode given, no frequency (the rig said nothing of it then).
     */
    fun contactApres(p: PassPredictor, sat: TleEntry, obs: Observer?, quandMs: Long, indicatif: String, locator: String,
                     rstEnvoye: String, rstRecu: String, note: String, mode: String, monLocator: String): LogEntry {
        val pos = obs?.let { runCatching { p.positionAt(sat, it, quandMs) }.getOrNull() }
        return LogEntry(timeMs = quandMs, satName = sat.name, catnum = sat.catalogNumber,
            azimuthDeg = pos?.azimuthDeg ?: 0.0, elevationDeg = pos?.elevationDeg ?: 0.0,
            myLocator = monLocator, callsign = indicatif.trim().uppercase(), theirLocator = locator.trim().uppercase(),
            rstSent = rstEnvoye.trim(), rstRcvd = rstRecu.trim(), note = note.trim(), mode = mode.trim().uppercase(),
            locatorOrigine = if (locator.isBlank()) "INCONNU" else "SAISI")
    }
}
