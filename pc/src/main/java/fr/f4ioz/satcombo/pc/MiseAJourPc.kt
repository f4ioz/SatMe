/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * « Nouvelle version disponible » : SatMe-PC's latest release read from
 * GitHub's public API (read only, no account), at start and then once a
 * day, and compared with the version running. Nothing is downloaded or
 * installed: the page shows the release's notes and links.
 *
 * Silent on errors and rate limits (tried again later); the answer kept in
 * `mise-a-jour.json`, so a restart does not ask again. A build by hand
 * ("dev") never asks. Off with « Vérifier les mises à jour » (Réglages ›
 * Démarrage).
 */
class MiseAJourPc(private val stockage: Stockage, private val fichier: File, private val actuelle: String,
                  private val http: Http = Http.reel(), private val horloge: () -> Long = System::currentTimeMillis,
                  private val windows: Boolean = OutilsPc.windows, private val api: String = API) {

    fun interface Http {
        fun get(url: String): Reponse

        companion object {
            /** OkHttp, SatMe's User-Agent, GitHub's JSON. */
            fun reel(): Http {
                val client = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
                return Http { url ->
                    val r = okhttp3.Request.Builder().url(url).header("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT)
                        .header("Accept", "application/vnd.github+json").build()
                    client.newCall(r).execute().use { rep ->
                        val corps = rep.body?.let { b -> b.byteStream().use { String(it.readNBytes(MAX_OCTETS), Charsets.UTF_8) } }.orEmpty()
                        Reponse(rep.code, corps, rep.headers.names().associateWith { rep.header(it).orEmpty() }.mapKeys { it.key.lowercase() })
                    }
                }
            }
        }
    }

    data class Reponse(val code: Int, val corps: String, val entetes: Map<String, String> = emptyMap())

    /** A release: its version, its page, its notes (Markdown) and its files (name → address). */
    data class Publication(val version: String, val titre: String, val page: String, val notes: String,
                           val fichiers: Map<String, String>, val dateMs: Long) {
        fun json(): JSONObject = JSONObject().put("version", version).put("titre", titre).put("page", page).put("notes", notes)
            .put("fichiers", JSONObject(fichiers)).put("date", dateMs)

        companion object {
            fun de(o: JSONObject?): Publication? = o?.let {
                val f = it.optJSONObject("fichiers")
                Publication(it.optString("version"), it.optString("titre"), it.optString("page"), it.optString("notes"),
                    f?.keys()?.asSequence()?.associateWith { k -> f.optString(k) }.orEmpty(), it.optLong("date"))
            }?.takeIf { VersionPc.publiee(it.version) }
        }
    }

    /** « Vérifier les mises à jour » (on by default). */
    var actif: Boolean
        get() = stockage.getBoolean(CLE_ACTIF, true)
        set(v) { stockage.edit().putBoolean(CLE_ACTIF, v).apply(); if (v) prochainMs = 0L }

    @Volatile var derniere: Publication? = null; private set
    @Volatile var verifieMs = 0L; private set
    @Volatile var erreur = ""; private set
    @Volatile private var prochainMs = 0L

    init {
        runCatching {
            if (fichier.isFile) {
                val o = JSONObject(fichier.readText())
                derniere = Publication.de(o.optJSONObject("publication"))
                verifieMs = o.optLong("verifie"); prochainMs = o.optLong("prochain"); erreur = o.optString("erreur")
            }
        }
        // A restart soon after a check does not ask again; a new start otherwise asks in a moment.
        if (prochainMs == 0L || prochainMs - horloge() > JOUR_MS) prochainMs = horloge() + PREMIER_MS
        else prochainMs = maxOf(prochainMs, horloge() + PREMIER_MS)
    }

    /** A release newer than this one is known. */
    val disponible: Boolean get() = derniere?.let { VersionPc.plusRecente(it.version, actuelle) } == true

    /** Called every few seconds: asks when it is time (on, a release running, the pause after the last answer over). */
    fun tic() {
        if (!actif || !VersionPc.publiee(actuelle) || horloge() < prochainMs) return
        verifie()
    }

    /** Asks now (« Vérifier maintenant », or [tic]); never throws. */
    @Synchronized fun verifie(): JSONObject {
        if (!VersionPc.publiee(actuelle)) return etat()
        val t = horloge()
        try {
            val r = http.get("$api/releases/latest")
            var p = if (r.code == 200) runCatching { publication(JSONObject(r.corps)) }.getOrNull() else null
            // The latest release of the repository may be another program's: the list then.
            if (p == null && (r.code == 200 || r.code == 404)) {
                val l = http.get("$api/releases?per_page=30")
                if (l.code == 200) p = runCatching { choisit(JSONArray(l.corps)) }.getOrNull()
                else return limite(l, t)
            } else if (p == null) return limite(r, t)
            derniere = p ?: derniere
            verifieMs = t; erreur = ""; prochainMs = t + JOUR_MS
        } catch (e: Exception) {
            erreur = "pas de réponse de GitHub"; prochainMs = t + REESSAI_MS
        }
        ecrit()
        return etat()
    }

    /** Refused (rate limit, an error): quiet, asked again after GitHub's reset or a few hours. */
    private fun limite(r: Reponse, t: Long): JSONObject {
        val reset = r.entetes["x-ratelimit-reset"]?.toLongOrNull()?.times(1000)
        val apres = r.entetes["retry-after"]?.toLongOrNull()?.times(1000)?.plus(t)
        erreur = if (r.code == 403 || r.code == 429) "limite de GitHub atteinte, nouvel essai plus tard" else "GitHub a répondu ${r.code}"
        prochainMs = listOfNotNull(reset, apres).maxOrNull()?.coerceIn(t + 60_000L, t + JOUR_MS) ?: (t + REESSAI_MS)
        ecrit()
        return etat()
    }

    private fun ecrit() {
        runCatching {
            val o = JSONObject().put("verifie", verifieMs).put("prochain", prochainMs).put("erreur", erreur)
            derniere?.let { o.put("publication", it.json()) }
            fichier.parentFile?.mkdirs()
            val tmp = File(fichier.path + ".tmp")
            tmp.writeText(o.toString(2))
            if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
        }
    }

    /** The file this PC wants: the installer on Windows (the zip otherwise), the package elsewhere. */
    fun telechargement(p: Publication): Pair<String, String>? {
        val noms = if (windows) listOf("satme-pc-windows.msi", "satme-pc-windows.zip") else listOf("satme-pc.tar", "satme-pc-${p.version}.tar")
        return noms.firstNotNullOfOrNull { n -> p.fichiers[n]?.let { n to it } }
    }

    /** What the page shows: on or not, this version, the latest known, and whether it is newer. */
    fun etat(): JSONObject {
        val o = JSONObject().put("ok", true).put("actif", actif).put("actuelle", actuelle).put("dev", !VersionPc.publiee(actuelle))
            .put("verifie", verifieMs).put("erreur", erreur).put("disponible", actif && disponible)
        derniere?.let { p ->
            o.put("version", p.version).put("titre", p.titre).put("page", p.page).put("date", p.dateMs)
                .put("notesHtml", fr.f4ioz.satcombo.demo.Markdown.html(extrait(p.notes, fr.f4ioz.satcombo.i18n.I18n.current() == fr.f4ioz.satcombo.i18n.Lang.EN)))
            telechargement(p)?.let { (n, u) -> o.put("fichier", n).put("telechargement", u) }
            o.put("texte", fr.f4ioz.satcombo.i18n.I18n.tf("pcd_maj_dispo", p.version))
        }
        return o
    }

    companion object {
        const val API = "https://api.github.com/repos/f4ioz/SatMe"
        const val CLE_ACTIF = "pc_maj_verifie"
        const val JOUR_MS = 86_400_000L
        const val REESSAI_MS = 6 * 3_600_000L
        const val PREMIER_MS = 20_000L
        const val MAX_OCTETS = 2 * 1024 * 1024

        /** A release of SatMe-PC (its tag "satme-pc-…"), published (not a draft, not a pre-release); else null. */
        fun publication(o: JSONObject): Publication? {
            val tag = o.optString("tag_name")
            if (!tag.startsWith(VersionPc.PREFIXE_TAG) || o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val v = tag.removePrefix(VersionPc.PREFIXE_TAG)
            if (!VersionPc.publiee(v)) return null
            val f = LinkedHashMap<String, String>()
            o.optJSONArray("assets")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { x ->
                    val n = x.optString("name"); val u = x.optString("browser_download_url")
                    if (n.isNotBlank() && u.startsWith("https://")) f[n] = u
                }
            }
            val page = o.optString("html_url").takeIf { it.startsWith("https://github.com/") } ?: "https://github.com/f4ioz/SatMe/releases/tag/$tag"
            val date = runCatching { java.time.Instant.parse(o.optString("published_at")).toEpochMilli() }.getOrDefault(0L)
            return Publication(v, o.optString("name").ifBlank { "SatMe-PC $v" }, page, o.optString("body").take(20_000), f, date)
        }

        /** The newest release of SatMe-PC in GitHub's list (other programs', drafts and pre-releases left out). */
        fun choisit(l: JSONArray): Publication? =
            (0 until l.length()).mapNotNull { l.optJSONObject(it)?.let(::publication) }
                .maxWithOrNull { a, b -> VersionPc.compare(a.version, b.version) ?: 0 }

        /** The notes' part in the page's language (their `### fr` / `### en`), else their beginning; a few lines at most. */
        fun extrait(notes: String, anglais: Boolean): String {
            val n = NouveautesPc.lit("## 0.0\n" + notes).firstOrNull()?.texte(anglais) ?: notes
            val lignes = n.lines().filter { it.isNotBlank() }
            val court = lignes.take(12).joinToString("\n")
            return if (court.length > 1500) court.take(1500).substringBeforeLast(' ') + " …" else court
        }
    }
}
