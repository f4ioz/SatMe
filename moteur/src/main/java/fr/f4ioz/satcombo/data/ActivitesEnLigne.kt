/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Activite
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Ecoute
import fr.f4ioz.satcombo.domain.LectureActivites
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * « Activités à venir »: the online sources fetched politely and kept on
 * disk (`activites-cache.json` in [dossier]), so the list survives offline
 * with what was last read. Each source has its own pace — the ARISS
 * calendar and the news feeds every 6 h at most (the calendar by a
 * conditional GET), SatNOGS twice a day, AMSAT's reports every 30 min and
 * only while an announced SSTV/SSDV activity is on. A failure is kept, said
 * quietly with the last success; the list stays as it was.
 *
 * Blocking: call it off the main thread. Both platforms use it (the phone's
 * worker and screen, the PC's loop).
 */
class ActivitesEnLigne(
    dossier: File,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val telecharge: Telechargeur = Telechargeur.okhttp(),
) {
    /** A GET: [etag] / [modifie] for a conditional one; 304 keeps what was read. */
    fun interface Telechargeur {
        fun get(url: String, etag: String?, modifie: String?): Reponse

        companion object {
            fun okhttp(client: OkHttpClient = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()) = Telechargeur { url, etag, modifie ->
                val r = Request.Builder().url(url).header("User-Agent", AgentHttp.USER_AGENT)
                if (etag != null) r.header("If-None-Match", etag)
                if (modifie != null) r.header("If-Modified-Since", modifie)
                client.newCall(r.build()).execute().use { rep ->
                    Reponse(rep.code, if (rep.code == 200) rep.body?.string() else null, rep.header("ETag"), rep.header("Last-Modified"))
                }
            }
        }
    }

    data class Reponse(val code: Int, val corps: String?, val etag: String? = null, val modifie: String? = null)

    /** The sources, their address and their pace. */
    enum class Src(val nom: String, val url: String, val intervalleMs: Long, val lien: String) {
        ARISS("ARISS (agenda)", "https://calendar.google.com/calendar/ical/39fpkdtnqhma60vk5nd0ndur4o%40group.calendar.google.com/public/basic.ics",
            6 * H, "https://www.ariss.org/"),
        AMSAT_UK("AMSAT-UK", "https://amsat-uk.org/feed/", 6 * H, "https://amsat-uk.org/"),
        AMSAT_F("AMSAT-F", "https://site.amsat-f.org/feed/", 6 * H, "https://site.amsat-f.org/"),
        SATNOGS_M23("SatNOGS · METEOR-M2-3", "https://network.satnogs.org/api/observations/?norad_cat_id=57166&status=good", 12 * H, "https://network.satnogs.org/"),
        SATNOGS_M24("SatNOGS · METEOR-M2-4", "https://network.satnogs.org/api/observations/?norad_cat_id=59051&status=good", 12 * H, "https://network.satnogs.org/"),
        AMSAT("AMSAT (écoutes)", "https://www.amsat.org/status/api/v1/sat_info.php?hours=24&name=", 30 * 60_000L, "https://www.amsat.org/status/");
    }

    /** What the owner switched on: the whole and each kind of source. */
    data class Reglages(val actif: Boolean = true, val ariss: Boolean = true, val amsat: Boolean = true,
                        val satnogs: Boolean = true, val rss: Boolean = true) {
        fun permet(s: Src) = actif && when (s) {
            Src.ARISS -> ariss; Src.AMSAT -> amsat; Src.SATNOGS_M23, Src.SATNOGS_M24 -> satnogs; Src.AMSAT_UK, Src.AMSAT_F -> rss
        }
    }

    /** A source's state: when tried, when it last worked, its error, its validators, what it gave. */
    data class Etat(val src: Src, val tentativeMs: Long = 0L, val succesMs: Long = 0L, val erreur: String = "",
                    val etag: String? = null, val modifie: String? = null, val activites: List<Activite> = emptyList())

    private val fichier = File(dossier, "activites-cache.json")
    private val etats = HashMap<Src, Etat>()
    /** AMSAT reports by status name, and when each name was last asked. */
    private val ecoutes = HashMap<String, List<Ecoute>>()
    private val demandes = HashMap<String, Long>()

    init { charge() }

    @Synchronized fun etats(): List<Etat> = Src.entries.map { etats[it] ?: Etat(it) }

    /** The list as the sources last said it (those switched on), merged, confirmed by the reports, kept. */
    @Synchronized fun liste(r: Reglages): List<Activite> {
        if (!r.actif) return emptyList()
        val t = horloge()
        val brut = Src.entries.filter { r.permet(it) && it != Src.AMSAT }.flatMap { etats[it]?.activites.orEmpty() }
        val l = ActivitesAVenir.fusionne(brut)
        return ActivitesAVenir.aGarder(if (r.amsat) ActivitesAVenir.confirme(l, ecoutes) else l, t)
    }

    /** Is [s] due (its pace, or 5 min when [force]d; an error retried after an hour at most)? */
    private fun du(s: Src, t: Long, force: Boolean): Boolean {
        val e = etats[s] ?: return true
        val pas = if (force) 5 * 60_000L else if (e.erreur.isNotEmpty()) minOf(s.intervalleMs, H) else s.intervalleMs
        return t - e.tentativeMs >= pas
    }

    /**
     * The sources due fetched (those switched on), then AMSAT's reports for
     * the SSTV/SSDV activities on now. [satellites] (name, number) name the
     * satellites a post speaks of. True when something new was read.
     */
    fun rafraichit(r: Reglages, satellites: List<Pair<String, Int>> = emptyList(), force: Boolean = false): Boolean {
        if (!r.actif) return false
        var change = false
        for (s in Src.entries) {
            if (s == Src.AMSAT || !r.permet(s)) continue
            val t = horloge()
            if (!synchronized(this) { du(s, t, force) }) continue
            change = lis(s, t, satellites) || change
        }
        if (r.amsat) {
            val t = horloge()
            val noms = synchronized(this) { liste(r.copy(amsat = false)) }.filter { ActivitesAVenir.aEcouter(it, t) }
                .mapNotNull { ActivitesAVenir.nomAmsat(it) }.distinct()
            for (n in noms) {
                if (t - (synchronized(this) { demandes[n] } ?: 0L) < (if (force) 5 * 60_000L else Src.AMSAT.intervalleMs)) continue
                synchronized(this) { demandes[n] = t }
                val url = Src.AMSAT.url + java.net.URLEncoder.encode(n, "UTF-8")
                val rep = runCatching { telecharge.get(url, null, null) }
                synchronized(this) {
                    val e0 = etats[Src.AMSAT] ?: Etat(Src.AMSAT)
                    val rr = rep.getOrNull()
                    etats[Src.AMSAT] = if (rr != null && rr.code == 200 && rr.corps != null) {
                        ecoutes[n] = LectureActivites.amsat(rr.corps); change = true
                        e0.copy(tentativeMs = t, succesMs = t, erreur = "")
                    } else e0.copy(tentativeMs = t, erreur = erreur(rep.exceptionOrNull(), rr))
                }
            }
        }
        synchronized(this) { ecrit() }
        return change
    }

    private fun erreur(x: Throwable?, r: Reponse?): String = when {
        x != null -> (x.javaClass.simpleName + (x.message?.let { ": $it" } ?: "")).take(120)
        r != null -> "HTTP " + r.code
        else -> "erreur"
    }

    private fun lis(s: Src, t: Long, satellites: List<Pair<String, Int>>): Boolean {
        val e0 = synchronized(this) { etats[s] ?: Etat(s) }
        val rep = runCatching { telecharge.get(s.url, e0.etag, e0.modifie) }
        val r = rep.getOrNull()
        val e = when {
            r != null && r.code == 304 -> e0.copy(tentativeMs = t, succesMs = t, erreur = "")
            r != null && r.code == 200 && r.corps != null -> {
                val l = runCatching { analyse(s, r.corps, t, satellites) }
                l.fold({ e0.copy(tentativeMs = t, succesMs = t, erreur = "", etag = r.etag, modifie = r.modifie, activites = it) },
                    { e0.copy(tentativeMs = t, erreur = "illisible : " + (it.message ?: "").take(80)) })
            }
            else -> e0.copy(tentativeMs = t, erreur = erreur(rep.exceptionOrNull(), r))
        }
        synchronized(this) { etats[s] = e }
        return e.activites != e0.activites
    }

    private fun analyse(s: Src, corps: String, t: Long, satellites: List<Pair<String, Int>>): List<Activite> = when (s) {
        Src.ARISS -> LectureActivites.ariss(corps, t - 2 * 86_400_000L)
        Src.AMSAT_UK, Src.AMSAT_F -> {
            // An HTML page where the feed was expected (a redirection): an error, not an empty list.
            require(corps.contains("<rss", true) || corps.contains("<feed", true)) { "pas un flux RSS" }
            LectureActivites.rss(corps, ActivitesAVenir.Source(s.nom, s.lien), t, satellites)
        }
        Src.SATNOGS_M23 -> listOfNotNull(LectureActivites.satnogs(corps, ActivitesAVenir.METEOR_M2_3, "METEOR-M2-3", t))
        Src.SATNOGS_M24 -> listOfNotNull(LectureActivites.satnogs(corps, ActivitesAVenir.METEOR_M2_4, "METEOR-M2-4", t))
        Src.AMSAT -> emptyList()
    }

    // ------------------------------------------------------------------ the file

    private fun charge() = runCatching {
        if (!fichier.exists()) return@runCatching
        val o = JSONObject(fichier.readText())
        val so = o.optJSONObject("sources") ?: JSONObject()
        for (s in Src.entries) {
            val j = so.optJSONObject(s.name) ?: continue
            val a = j.optJSONArray("activites") ?: JSONArray()
            etats[s] = Etat(s, j.optLong("tentative"), j.optLong("succes"), j.optString("erreur"),
                j.optString("etag").ifEmpty { null }, j.optString("modifie").ifEmpty { null },
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ActivitesAVenir::lis) })
        }
        val eo = o.optJSONObject("ecoutes") ?: JSONObject()
        for (n in eo.keys()) {
            val a = eo.optJSONArray(n) ?: continue
            ecoutes[n] = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                Ecoute(n, it.optLong("t"), it.optString("c"), it.optString("r"), it.optString("g"))
            }
        }
        val d = o.optJSONObject("demandes") ?: JSONObject()
        for (n in d.keys()) demandes[n] = d.optLong(n)
    }

    private fun ecrit() = runCatching {
        val so = JSONObject()
        for ((s, e) in etats) so.put(s.name, JSONObject().put("tentative", e.tentativeMs).put("succes", e.succesMs).put("erreur", e.erreur)
            .put("etag", e.etag ?: "").put("modifie", e.modifie ?: "").put("activites", JSONArray(e.activites.map(ActivitesAVenir::json))))
        val eo = JSONObject()
        for ((n, l) in ecoutes) eo.put(n, JSONArray(l.takeLast(200).map { JSONObject().put("t", it.quandMs).put("c", it.indicatif).put("r", it.rapport).put("g", it.locator) }))
        val o = JSONObject().put("v", 1).put("sources", so).put("ecoutes", eo).put("demandes", JSONObject(demandes as Map<*, *>))
        fichier.parentFile?.mkdirs()
        val tmp = File(fichier.parentFile, fichier.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
    }

}

private const val H = 3_600_000L
