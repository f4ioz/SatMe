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
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.sstv.EvenementSstv
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.RapportEvenement
import fr.f4ioz.satcombo.sstv.SstvMeta
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * The words the « Publication web » adds about SSTV (docs/publication-web.md,
 * all optional, a receiver may ignore them): the pass a picture or a video
 * came on, the auto-pilot's campaign that received it; a planche's series,
 * its slides received and missing, the best picture of each, the passes that
 * brought them, its report and the journal's videos of those passes. Only
 * what SatMe-PC already knows (the gallery, the campaigns and their
 * history, the sheet and its event); pure, so tested without a station.
 */
object MetaSitePc {

    private val minute = SimpleDateFormat("yyyyMMdd'T'HHmm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val nomVideo = Regex("^SatMe_(.+?)_(\\d{8})_(\\d{6})Z_(passage|session|moment)(_.*)?\\.mp4$")

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun el(v: Double) = Math.round(v * 10.0) / 10.0
    private fun normal(s: String) = s.uppercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^A-Z0-9]"), "")

    /** A pass's id, the same in every item about it: « 25544-20261003T1351Z » (the satellite's NORAD number, its AOS to the minute, UTC). */
    @Synchronized fun idPassage(catnum: Int, aosMs: Long): String = catnum.toString() + "-" + minute.format(Date(aosMs))

    /** The pass [t] fell in (a minute before AOS, two after LOS: a picture ends after it began). */
    fun passageDe(passes: List<SatPass>, t: Long): SatPass? =
        passes.firstOrNull { t in (it.aosEpochMs - 60_000L)..(it.losEpochMs + 120_000L) }

    /** What the auto-pilot did on this pass ([CampagneSstv.Trace]): its campaign received it (not set aside). */
    fun traceDe(traces: List<CampagneSstv.Trace>, catnum: Int, aosMs: Long): CampagneSstv.Trace? =
        traces.lastOrNull { it.catnum == catnum && abs(it.aosMs - aosMs) <= CampagneSstv.TOLERANCE_MS && it.statut in RECU_PAR_PILOTE }

    private val RECU_PAR_PILOTE = setOf(CampagneSstv.Statut.FAIT, CampagneSstv.Statut.ARRETE, CampagneSstv.Statut.INTERROMPU, CampagneSstv.Statut.EN_COURS)

    /** The campaign named in a picture's note by the auto-pilot (« Campagne : ARISS octobre 2026 »), "" none. */
    fun campagneNote(note: String): String = Regex("^Campagne\\s*:\\s*(.+)$").find(note.trim())?.groupValues?.get(1)?.trim()?.take(80).orEmpty()

    /** [o] given its pass: `catnum`, `passage`, `aos`, `los`, `elMax`. */
    private fun passage(o: JSONObject, catnum: Int, p: SatPass?) {
        if (catnum > 0) o.put("catnum", catnum)
        if (p == null) return
        o.put("passage", idPassage(catnum.takeIf { it > 0 } ?: p.catalogNumber, p.aosEpochMs)).put("aos", iso(p.aosEpochMs)).put("los", iso(p.losEpochMs))
            .put("elMax", el(p.maxElevationDeg))
    }

    /**
     * An SSTV picture's words beside the old ones ([o]): its pass ([passes], the satellite's around it),
     * whether the auto-pilot received it (its note, or its history naming the picture) and the campaign.
     */
    fun sstv(o: JSONObject, s: SstvMeta.SstvShot, catnum: Int, passes: List<SatPass>, traces: List<CampagneSstv.Trace>): JSONObject {
        val p = if (s.timeMs > 0) passageDe(passes, s.timeMs) else null
        passage(o, catnum, p)
        val tr = traces.lastOrNull { s.fileName in it.images && it.statut in RECU_PAR_PILOTE }
            ?: p?.let { traceDe(traces, catnum.takeIf { c -> c > 0 } ?: it.catalogNumber, it.aosEpochMs) }
        val campagne = tr?.nom?.trim()?.take(80)?.ifEmpty { null } ?: campagneNote(s.note)
        val pilote = tr != null || campagneNote(s.note).isNotEmpty()
        o.put("autopilote", pilote)
        if (campagne.isNotEmpty()) o.put("campagne", campagne)
        if (s.diapo > 0) { o.put("diapo", s.diapo); if (s.diapos > 0) o.put("diapos", s.diapos) }
        return o
    }

    /** A video of the journal's editor, as its file's name says: its satellite, its start (UTC ms), one pass or more. */
    data class Video(val fichier: String, val satellite: String, val debutMs: Long, val sorte: String)

    fun video(nom: String): Video? {
        val m = nomVideo.matchEntire(nom) ?: return null
        val t = runCatching { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(m.groupValues[2] + m.groupValues[3])!!.time }.getOrNull()
            ?: return null
        return Video(nom, m.groupValues[1], t, m.groupValues[4])
    }

    /** Is the video [v] of the pass [p] of [satellite]: its satellite, its start inside the pass (with its margin before AOS)? */
    fun videoDuPassage(v: Video, satellite: String, aosMs: Long, losMs: Long): Boolean {
        val a = normal(v.satellite); val b = normal(satellite)
        if (a.isEmpty() || b.isEmpty() || !(a.startsWith(b) || b.startsWith(a))) return false
        return v.debutMs in (aosMs - 15 * 60_000L)..(losMs + 60_000L)
    }

    /**
     * A journal video's words beside the old ones: its pass, the auto-pilot and its campaign, and the planches
     * whose event it belongs to ([planches]: their `cle`).
     */
    fun video(o: JSONObject, v: Video, catnum: Int, passes: List<SatPass>, traces: List<CampagneSstv.Trace>, planches: List<String>): JSONObject {
        // Its start is the first moment followed (about AOS), or a margin before it.
        val p = passes.firstOrNull { v.debutMs in (it.aosEpochMs - 15 * 60_000L)..it.losEpochMs }
        passage(o, catnum, p)
        val tr = p?.let { traceDe(traces, catnum.takeIf { c -> c > 0 } ?: it.catalogNumber, it.aosEpochMs) }
        o.put("autopilote", tr != null)
        tr?.nom?.trim()?.takeIf { it.isNotEmpty() }?.let { o.put("campagne", it.take(80)) }
        if (planches.isNotEmpty()) o.put("planches", JSONArray(planches))
        return o
    }

    /**
     * A planche's words beside the old ones ([o]): its satellite, its event (id, kind, dates), the auto-pilot's
     * campaign, the most frequent mode, the first and last picture, the slides expected / received / missing, the best
     * picture of each slide (its `sstv:` key), the passes that brought pictures (with their videos), its report's key.
     * [r]: the event's report (null: the sheet has no event; then what its boxes hold). [shots]: the gallery by name.
     */
    fun planche(o: JSONObject, m: Planche.Modele, r: RapportEvenement.Rapport?, shots: Map<String, SstvMeta.SstvShot>,
                campagne: CampagneSstv.Campagne?, traces: List<CampagneSstv.Trace>, videos: List<Video>): JSONObject {
        val ev = r?.evenement ?: m.evenement
        val placees = m.images.values.mapNotNull { shots[it] }
        val images = r?.images?.mapNotNull { shots[it.nom] }?.ifEmpty { null } ?: placees
        val sat = ev?.satNom?.ifBlank { null } ?: images.map { it.satName }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        if (sat.isNotEmpty()) o.put("satellite", sat)
        val catnum = ev?.catnum ?: 0
        if (catnum > 0) o.put("catnum", catnum)
        if (ev != null) {
            o.put("evenement", ev.nom).put("evenementId", ev.id).put("evenementGenre", EvenementSstv.genreCle(ev.genre))
                .put("evenementDebut", iso(ev.debutMs)).put("evenementFin", iso(ev.finMs))
        }
        if (campagne != null) o.put("campagne", campagne.nom).put("campagneId", campagne.id)
        images.map { it.mode }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let { o.put("mode", it) }
        images.map { it.timeMs }.filter { it > 0 }.let { t -> if (t.isNotEmpty()) o.put("premiere", iso(t.min())).put("derniere", iso(t.max())) }
        o.put("images", images.size)
        if (r != null) {
            val recues = r.diapos.filter { it.recue }.map { it.n }
            o.put("diapos", r.evenement.nb).put("diaposRecues", recues.size).put("diaposManquantes", JSONArray(r.diapos.filter { !it.recue }.map { it.n }))
            o.put("meilleures", JSONArray(r.diapos.filter { it.recue }.map { d ->
                JSONObject().put("diapo", d.n).put("image", "sstv:" + d.meilleure).put("copies", d.copies).also { j ->
                    if (d.note >= 0) j.put("note", d.note)
                    shots[d.meilleure]?.takeIf { it.timeMs > 0 }?.let { j.put("utc", iso(it.timeMs)) }
                    if (d.origine == fr.f4ioz.satcombo.sstv.ClassementSstv.Origine.ESTIME) j.put("estimee", true)
                }
            }))
            val ps = r.passages.filter { it.images.isNotEmpty() }.map { p ->
                val tr = traceDe(traces, catnum, p.aosMs)
                val vs = videos.filter { videoDuPassage(it, sat, p.aosMs, p.losMs) }
                JSONObject().put("id", idPassage(catnum, p.aosMs)).put("aos", iso(p.aosMs)).put("los", iso(p.losMs)).put("elMax", el(p.elMax))
                    .put("images", p.images.size).put("diapos", JSONArray(p.recues)).put("autopilote", tr != null).also { j ->
                        tr?.nom?.takeIf { it.isNotBlank() }?.let { j.put("campagne", it.take(80)) }
                        vs.firstOrNull()?.let { j.put("video", "journal:" + it.fichier) }
                    }
            }
            o.put("passages", JSONArray(ps))
            if (ps.any { it.optBoolean("autopilote") }) o.put("autopilote", true)
            val vs = r.passages.filter { it.images.isNotEmpty() }.flatMap { p -> videos.filter { videoDuPassage(it, sat, p.aosMs, p.losMs) } }.distinct()
            if (vs.isNotEmpty()) o.put("videos", JSONArray(vs.map { "journal:" + it.fichier }))
        } else {
            // No event: what its boxes hold (box n is slide n).
            val (n, manquent) = Planche.suivi(m) { it in shots }
            o.put("diapos", m.cases.size).put("diaposRecues", n).put("diaposManquantes", JSONArray(manquent))
            o.put("meilleures", JSONArray(m.images.entries.sortedBy { it.key }.filter { it.value in shots }.map { (i, f) ->
                JSONObject().put("diapo", i + 1).put("image", "sstv:$f").also { j -> shots[f]?.takeIf { it.timeMs > 0 }?.let { j.put("utc", iso(it.timeMs)) } }
            }))
        }
        o.put("rapport", "rapport:" + m.id)
        return o
    }

    /** The report's words beside the old ones: its planche's key, its satellite and event, the first and last picture. */
    fun rapport(o: JSONObject, m: Planche.Modele, r: RapportEvenement.Rapport?, campagne: CampagneSstv.Campagne?): JSONObject {
        o.put("planche", "planche:" + m.id)
        val ev = r?.evenement ?: m.evenement
        if (ev != null) {
            if (ev.satNom.isNotBlank()) o.put("satellite", ev.satNom)
            if (ev.catnum > 0) o.put("catnum", ev.catnum)
            o.put("evenementId", ev.id).put("evenementGenre", EvenementSstv.genreCle(ev.genre))
        }
        if (campagne != null) o.put("campagne", campagne.nom).put("campagneId", campagne.id)
        if (r != null && r.stats.premiereMs > 0) o.put("premiere", iso(r.stats.premiereMs)).put("derniere", iso(r.stats.derniereMs))
        return o
    }
}
