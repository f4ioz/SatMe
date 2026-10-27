/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.Diplomes
import fr.f4ioz.satcombo.domain.Diplomes.Diplome
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * « Agenda › Diplômes »: the awards announced online, fetched politely as
 * [ActivitesEnLigne] does (its [ActivitesEnLigne.Telechargeur], conditional
 * GETs, every 12 h at most, an error retried after an hour) and kept on disk
 * (`diplomes-cache.json`) so the list survives offline.
 *
 * The sources, checked on 27/10/2026: the ARISS SSTV award's own page (each
 * session's award, its dates), and the news feeds that relay one-off
 * certificates (AMSAT's weekly bulletin, AMSAT-UK, AMSAT-F). ARISS has no
 * news feed of its own (ariss.org/feed/ answers 404); the REF's feed carries
 * no satellite award.
 *
 * Blocking: call it off the main thread.
 */
class DiplomesEnLigne(
    dossier: File,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val telecharge: ActivitesEnLigne.Telechargeur = ActivitesEnLigne.Telechargeur.okhttp(),
) {
    enum class Src(val nom: String, val url: String, val lien: String) {
        PZK("ARISS SSTV Award (PZK)", Diplomes.PZK, Diplomes.PZK),
        AMSAT("AMSAT News Service", "https://www.amsat.org/feed/", "https://www.amsat.org/"),
        AMSAT_UK("AMSAT-UK", "https://amsat-uk.org/feed/", "https://amsat-uk.org/"),
        AMSAT_F("AMSAT-F", "https://site.amsat-f.org/feed/", "https://site.amsat-f.org/");
    }

    data class Etat(val src: Src, val tentativeMs: Long = 0L, val succesMs: Long = 0L, val erreur: String = "",
                    val etag: String? = null, val modifie: String? = null, val diplomes: List<Diplome> = emptyList())

    private val fichier = File(dossier, "diplomes-cache.json")
    private val etats = HashMap<Src, Etat>()

    init { charge() }

    @Synchronized fun etats(): List<Etat> = Src.entries.map { etats[it] ?: Etat(it) }

    /** The awards the sources last said. */
    @Synchronized fun liste(): List<Diplome> = Src.entries.flatMap { etats[it]?.diplomes.orEmpty() }

    private fun du(s: Src, t: Long, force: Boolean): Boolean {
        val e = etats[s] ?: return true
        val pas = if (force) 5 * 60_000L else if (e.erreur.isNotEmpty()) H else 12 * H
        return t - e.tentativeMs >= pas
    }

    /** The sources due fetched; true when something new was read. */
    fun rafraichit(satellites: List<Pair<String, Int>> = emptyList(), force: Boolean = false): Boolean {
        var change = false
        for (s in Src.entries) {
            val t = horloge()
            if (!synchronized(this) { du(s, t, force) }) continue
            val e0 = synchronized(this) { etats[s] ?: Etat(s) }
            val rep = runCatching { telecharge.get(s.url, e0.etag, e0.modifie) }
            val r = rep.getOrNull()
            val e = when {
                r != null && r.code == 304 -> e0.copy(tentativeMs = t, succesMs = t, erreur = "")
                r != null && r.code == 200 && r.corps != null -> runCatching { analyse(s, r.corps, t, satellites) }.fold(
                    { e0.copy(tentativeMs = t, succesMs = t, erreur = "", etag = r.etag, modifie = r.modifie, diplomes = it) },
                    { e0.copy(tentativeMs = t, erreur = "illisible : " + (it.message ?: "").take(80)) })
                else -> e0.copy(tentativeMs = t, erreur = rep.exceptionOrNull()?.let { x -> (x.javaClass.simpleName + (x.message?.let { ": $it" } ?: "")).take(120) }
                    ?: r?.let { "HTTP " + it.code } ?: "erreur")
            }
            synchronized(this) { etats[s] = e }
            change = change || e.diplomes != e0.diplomes
        }
        synchronized(this) { ecrit() }
        return change
    }

    private fun analyse(s: Src, corps: String, t: Long, satellites: List<Pair<String, Int>>): List<Diplome> = when (s) {
        Src.PZK -> {
            require(corps.contains("ARISS SSTV", true)) { "page inattendue" }
            Diplomes.lisPzk(corps, t)
        }
        else -> {
            require(corps.contains("<rss", true) || corps.contains("<feed", true)) { "pas un flux RSS" }
            Diplomes.lisRss(corps, s.nom, s.lien, t, satellites)
        }
    }

    private fun charge() = runCatching {
        if (!fichier.exists()) return@runCatching
        val o = JSONObject(fichier.readText())
        for (s in Src.entries) {
            val j = o.optJSONObject(s.name) ?: continue
            val a = j.optJSONArray("diplomes") ?: JSONArray()
            etats[s] = Etat(s, j.optLong("tentative"), j.optLong("succes"), j.optString("erreur"), j.optString("etag").ifEmpty { null },
                j.optString("modifie").ifEmpty { null }, (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Diplomes::lis) })
        }
    }

    private fun ecrit() = runCatching {
        val o = JSONObject().put("v", 1)
        for ((s, e) in etats) o.put(s.name, JSONObject().put("tentative", e.tentativeMs).put("succes", e.succesMs).put("erreur", e.erreur)
            .put("etag", e.etag ?: "").put("modifie", e.modifie ?: "").put("diplomes", JSONArray(e.diplomes.map(Diplomes::json))))
        fichier.parentFile?.mkdirs()
        val tmp = File(fichier.parentFile, fichier.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
    }
}

private const val H = 3_600_000L
