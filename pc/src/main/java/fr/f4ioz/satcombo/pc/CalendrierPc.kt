/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Agenda › Calendrier »: everything planned between two instants, as one
 * list for the page's month, week and day views — the agenda's appointments,
 * the SSTV/SSDV campaigns (their spans), every pass of the campaigns (to come:
 * the plan's status; gone: what became of it, its pictures; a pass of the
 * period nobody received: « manqué »), the passes of the favourite satellites
 * and of the automatic mode's, and the activities announced online.
 * Read only: cancelling a pass goes through `/api/autopilote/annule`.
 */
class CalendrierPc(
    private val station: StationPc,
    private val autopilote: () -> AutoPiloteSstvPc?,
    private val activites: () -> ActivitesPc?,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** A satellite's passes between two instants (the station's predictor). */
    private val passagesDe: (Int, Long, Long) -> List<SatPass> = station::passagesDe,
    private val zone: () -> java.time.ZoneId = { java.time.ZoneId.systemDefault() },
) {
    companion object {
        /** The longest range asked at once (a month view and its edges). */
        const val PORTEE_MAX_MS = 45 * 86_400_000L
        /** The favourite satellites' passes: the predictor's horizon. */
        const val HORIZON_MS = 15 * 86_400_000L
        /** At most this many followed satellites (favourites first). */
        const val MAX_SATS = 12
        private const val CACHE_MS = 10 * 60_000L
    }

    private val cache = HashMap<String, Pair<Long, List<SatPass>>>()

    /** [catnum]'s passes over [de]…[a], worked out again every 10 minutes (whole hours, so the same range hits the cache). */
    private fun passages(catnum: Int, de: Long, a: Long): List<SatPass> {
        val t = horloge()
        val h = 3_600_000L
        val d0 = de - Math.floorMod(de, h); val a0 = a - Math.floorMod(a, h) + h
        val cle = "$catnum:$d0:$a0"
        synchronized(cache) { cache[cle]?.let { (quand, l) -> if (t - quand < CACHE_MS) return l } }
        val l = runCatching { passagesDe(catnum, d0, a0) }.getOrDefault(emptyList())
        synchronized(cache) { if (cache.size > 200) cache.clear(); cache[cle] = t to l }
        return l
    }

    /** `GET /api/calendrier?de=&a=` (epoch ms): the items between the two. */
    fun sert(p: (String) -> String): ReponseWeb {
        val t = horloge()
        val de = p("de").toLongOrNull() ?: (t - 7 * 86_400_000L)
        val a = p("a").toLongOrNull() ?: (t + 28 * 86_400_000L)
        val o = if (a <= de) JSONObject().put("ok", false).put("erreur", "période vide")
            else if (a - de > PORTEE_MAX_MS) JSONObject().put("ok", false).put("erreur", "45 jours au plus")
            else json(de, a)
        return ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    }

    fun json(de: Long, a: Long): JSONObject {
        val t = horloge()
        val z = zone()
        val e = station.etat.value
        val items = JSONArray()
        // The agenda's appointments.
        for (r in e.agenda) if (r.overlaps(de, a)) items.put(JSONObject().put("sorte", "agenda").put("id", r.id).put("titre", r.title)
            .put("sat", r.satName).put("de", r.timeMs).put("a", r.endOrStartMs).put("type", r.kind).put("note", r.note)
            .put("actif", r.enabled).put("freq", r.freqHz).put("passe", r.endOrStartMs < t))
        // The campaigns, their passes to come and those gone.
        val ap = autopilote()
        val campagnes = ap?.campagnes.orEmpty()
        val satsCampagnes = HashSet<Int>()
        if (ap != null) {
            val plan = runCatching { ap.plan() }.getOrDefault(emptyList())
            val hist = ap.historique
            for (c in campagnes) {
                if (c.finMs < de || c.debutMs > a) continue
                satsCampagnes += c.catnum
                val h = hist.filter { it.campagne == c.id }
                items.put(JSONObject().put("sorte", "campagne").put("id", c.id).put("titre", c.nom).put("sat", c.satNom).put("catnum", c.catnum)
                    .put("de", c.debutMs).put("a", c.finMs).put("active", c.active).put("terminee", c.terminee(t)).put("ssdv", c.chaine.ssdv && !c.chaine.sstv)
                    .put("periode", CampagneSstv.periodeTexte(c.debutMs, c.finMs, z)).put("activite", c.activite)
                    .put("recus", h.count { it.statut == CampagneSstv.Statut.FAIT }).put("images", h.sumOf { it.nbImages }))
                // Gone: what became of each pass (the history), and those of the period nobody received.
                val vus = ArrayList<Long>()
                for (tr in h) if (tr.losMs >= de && tr.aosMs <= a) {
                    vus += tr.aosMs
                    items.put(passage("campagne", tr.catnum, c.satNom, tr.aosMs, tr.losMs, tr.elMax).put("campagne", c.id).put("nom", c.nom)
                        .put("statut", tr.statut.name).put("raison", tr.raison).put("images", tr.nbImages).put("enregistrement", tr.enregistrement)
                        .put("ssdvImages", tr.ssdvImages).put("ssdvPaquets", tr.ssdvPaquets)
                        .put("passe", true))
                }
                // To come: the plan's (a campaign switched off: what it would receive, said « suspendue »).
                val avenir = runCatching { ap.passagesCampagne(c, plan) }.getOrDefault(emptyList())
                for (pr in avenir) if (pr.passage.losEpochMs >= de && pr.passage.aosEpochMs <= a && vus.none { kotlin.math.abs(it - pr.passage.aosEpochMs) <= CampagneSstv.TOLERANCE_MS }) {
                    vus += pr.passage.aosEpochMs
                    items.put(passage("campagne", pr.passage.catalogNumber, c.satNom, pr.passage.aosEpochMs, pr.passage.losEpochMs, pr.passage.maxElevationDeg)
                        .put("campagne", c.id).put("nom", c.nom).put("statut", pr.statut.name).put("raison", pr.raison).put("suspendue", !c.active)
                        .put("prep", pr.passage.aosEpochMs - c.avantMs).put("passe", false))
                }
                // The period's passes already gone with no trace: missed (the auto-pilot off, the campaign suspended…).
                val d = maxOf(de, c.debutMs); val f = minOf(a, c.finMs, t)
                if (f > d && f - d <= PORTEE_MAX_MS) for (p in CampagneSstv.passages(c, passages(c.catnum, d - 30 * 60_000L, f)))
                    if (p.losEpochMs < t && p.aosEpochMs < f && vus.none { kotlin.math.abs(it - p.aosEpochMs) <= CampagneSstv.TOLERANCE_MS })
                        items.put(passage("campagne", p.catalogNumber, c.satNom, p.aosEpochMs, p.losEpochMs, p.maxElevationDeg).put("campagne", c.id)
                            .put("nom", c.nom).put("statut", "MANQUE").put("raison", if (c.annule(p.aosEpochMs)) "passage annulé à la main" else "pas de trace de l’auto-pilote")
                            .put("passe", true))
            }
        }
        // The favourite satellites' and the automatic mode's: from now to the predictor's horizon.
        val noms = e.satellites.associate { it.catalogNumber to it.name }
        val suivis = LinkedHashMap<Int, String>()
        e.favoris.sorted().forEach { suivis[it] = "favori" }
        station.settings.veilleSats.sorted().forEach { suivis.putIfAbsent(it, "veille") }
        val pd = maxOf(de, t - 3_600_000L); val pa = minOf(a, t + HORIZON_MS)
        val elMin = station.settings.minElevDeg.toDouble()
        if (pa > pd) for ((n, sorte) in suivis.entries.take(MAX_SATS)) {
            for (p in passages(n, pd, pa)) if (p.maxElevationDeg >= elMin && p.losEpochMs >= pd && p.aosEpochMs <= pa && !campagneLe(campagnes, n, p))
                items.put(passage(sorte, n, noms[n] ?: p.satName.ifBlank { "#$n" }, p.aosEpochMs, p.losEpochMs, p.maxElevationDeg).put("passe", p.losEpochMs < t))
        }
        // The activities announced online.
        val act = activites()
        if (act != null && station.settings.activitesOn) for (x in runCatching { act.liste() }.getOrDefault(emptyList())) {
            if (x.type == ActivitesAVenir.Type.LRPT) continue
            val (d, f) = ActivitesAVenir.periodeCampagne(x, x.debutMs, x.finMs, z).let { if (x.precision == ActivitesAVenir.Precision.EXACTE) x.debutMs to x.finMs else it }
            if (f < de || d > a) continue
            val c = ap?.campagneDe(x)
            items.put(JSONObject().put("sorte", "activite").put("id", x.id).put("titre", x.titre).put("sat", x.satNom).put("catnum", x.catnum)
                .put("type", x.type.name).put("libelle", x.type.libelle).put("de", d).put("a", f).put("precision", x.precision.name)
                .put("campagne", c?.id ?: JSONObject.NULL).put("campagneSupprimee", ap?.supprimee(x) == true)
                .put("lien", x.liens.firstOrNull().orEmpty()).put("passe", f < t))
        }
        return JSONObject().put("ok", true).put("de", de).put("a", a).put("maintenant", t).put("zone", CampagneSstv.nomZone(z))
            .put("maitre", ap?.maitre ?: false).put("items", items)
    }

    /** A campaign already lists this pass of [catnum] (the favourite's own is then left out). */
    private fun campagneLe(l: List<CampagneSstv.Campagne>, catnum: Int, p: SatPass) =
        l.any { it.catnum == catnum && it.active && p.losEpochMs > it.debutMs && p.aosEpochMs < it.finMs && p.maxElevationDeg >= it.elMin }

    private fun passage(source: String, catnum: Int, sat: String, aos: Long, los: Long, el: Double) = JSONObject().put("sorte", "passage")
        .put("source", source).put("catnum", catnum).put("sat", sat).put("aos", aos).put("los", los).put("el", Math.round(el))
}
