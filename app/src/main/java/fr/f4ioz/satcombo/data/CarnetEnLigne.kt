/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online log: Wavelog or Cloudlog, indifferently.
 *
 * Wavelog forked Cloudlog and kept the API: a grid check takes the same
 * parameters on both (key, public logbook *slug*, optional band where "SAT"
 * restricts to satellite contacts). One connector, and the operator need not
 * say which one they run.
 *
 * Every answer is cached: the docs ask to call these routes only when needed.
 */
object CarnetEnLigne {

    /** What the server knows about a grid square. */
    enum class Etat { INCONNU, TRAVAILLE, CONFIRME, JAMAIS }

    private val cache = HashMap<String, Etat>()

    /** Clears the cache — after a settings change, or on request. */
    fun oublie() { synchronized(cache) { cache.clear() }; prefixe = null }

    fun enCache(carre: String): Etat? = synchronized(cache) { cache[carre.uppercase()] }

    /**
     * Has the grid square been worked? [base] is the server URL
     * ("https://log.example.org"), [cle] a read-only key, [slug] the public
     * logbook slug.
     */
    suspend fun carre(
        base: String, cle: String, slug: String, carre: String, satellite: Boolean = true,
    ): Etat = withContext(Dispatchers.IO) {
        val k = carre.uppercase()
        synchronized(cache) { cache[k] }?.let { return@withContext it }
        val corps = JSONObject()
            .put("key", cle)
            .put("logbook_public_slug", slug)
            .put("grid", k)
            .apply { if (satellite) put("band", "SAT") }
            .toString()
        val etat = runCatching { poste(base, "api/logbook_check_grid", corps) }
            .getOrNull()
            ?.let { lis(it) }
            ?: Etat.INCONNU
        // A network failure is not an answer: not cached, or a brief outage
        // freezes the screen on "unknown".
        if (etat != Etat.INCONNU) synchronized(cache) { cache[k] = etat }
        etat
    }

    /** Checks that the settings work; returns a readable message. */
    suspend fun essai(base: String, cle: String, slug: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val r = poste(base, "api/logbook_check_grid", JSONObject()
                    .put("key", cle).put("logbook_public_slug", slug)
                    .put("grid", "JN18").toString())
                when {
                    r.contains("\"result\"", true) -> "OK"
                    // An HTML page tells nothing: say that is what came back.
                    r.trimStart().startsWith("<") ->
                        "le serveur répond une page, pas du JSON — vérifier l'adresse"
                    r.startsWith("HTTP 500") ->
                        "erreur 500 : slug ou clé refusés par le serveur"
                    r.startsWith("HTTP 401") || r.startsWith("HTTP 403") ->
                        "clé refusée"
                    r.startsWith("HTTP 404") -> "adresse introuvable"
                    else -> r.take(90)
                }
            }.getOrElse { "échec : " + it.javaClass.simpleName }
        }

    /**
     * Uploads a contact and says whether the server took it.
     *
     * [profil] is the Wavelog station profile id. Required server-side: without
     * it the contact is refused or, worse, filed under the wrong station
     * callsign — which only shows when LoTW fails to match.
     *
     * The read key used for grid checks **is not enough here**: uploading needs
     * a write key. The server's answer is what tells the operator, so it is
     * returned verbatim rather than reduced to "failed".
     */
    suspend fun depose(
        base: String, cle: String, profil: String, adif: String,
    ): String = withContext(Dispatchers.IO) {
        val corps = JSONObject()
            .put("key", cle)
            .put("station_profile_id", profil)
            .put("type", "adif")
            .put("string", adif)
            .toString()
        runCatching { poste(base, "api/qso", corps) }.getOrElse {
            "échec : " + it.javaClass.simpleName
        }
    }

    /**
     * Station locations declared in Wavelog; returns the list or throws. Used
     * to attach each contact to the profile of its grid square: **Wavelog files
     * by profile and ignores `MY_GRIDSQUARE` in the ADIF.**
     */
    suspend fun profils(base: String, cle: String):
        List<fr.f4ioz.satcombo.domain.ProfilsStation.Profil> = withContext(Dispatchers.IO) {
        // **The key goes in the URL, not a JSON body.**
        //
        // `station_info` and `statistics` are GET with the key in the URL;
        // only `qso` and `create_station` take a body. A POST with
        // `{"key": …}` returns 401 — "key refused" while the key is fine and
        // the request is malformed, which sends you checking the wrong thing.
        val reponse = lit(base, "api/station_info/" + cle.trim())
        val tableau = runCatching { org.json.JSONArray(reponse) }.getOrElse {
            throw IllegalStateException(reponse.take(200))
        }
        (0 until tableau.length()).map { i ->
            val o = tableau.getJSONObject(i)
            fr.f4ioz.satcombo.domain.ProfilsStation.Profil(
                id = o.optString("station_id"),
                carre = o.optString("station_gridsquare"),
                indicatif = o.optString("station_callsign"),
                nom = o.optString("station_profile_name"))
        }
    }

    /**
     * Creates a station location.
     *
     * **The ITU zone is not optional.** Wavelog creates the profile without it,
     * then refuses every contact attached to it — far from the cause. So we
     * refuse here rather than create a dead profile.
     */
    suspend fun creeProfil(
        base: String, cle: String, nom: String, carre: String, indicatif: String,
        dxcc: String, pays: String, cq: String, itu: String,
    ): String = withContext(Dispatchers.IO) {
        if (itu.isBlank()) return@withContext "zone ITU obligatoire"
        val o = JSONObject()
            .put("station_profile_name", nom)
            .put("station_gridsquare", carre.trim().uppercase())
            .put("station_callsign", indicatif.trim().uppercase())
            .put("station_dxcc", dxcc.trim()).put("dxccname", pays.trim())
            .put("station_cq", cq.trim()).put("station_itu", itu.trim())
            .put("station_active", "1").put("link_active_logbook", "1")
            .put("station_city", "").put("station_iota", "").put("station_sota", "")
            .put("station_wwff", "").put("station_pota", "")
            .put("station_sig", "").put("station_sig_info", "").put("station_cnty", "")
        val corps = org.json.JSONArray().put(o).toString()
        runCatching { poste(base, "api/create_station/" + cle.trim(), corps) }
            .getOrElse { "échec : " + it.javaClass.simpleName }
    }

    /**
     * Is the profile in place? "dupe" counts as success: it already existed,
     * which is what a replayed creation wants.
     */
    fun profilEnPlace(reponse: String): Boolean {
        val r = reponse.lowercase()
        return r.contains("\"success\"") || r.contains("\"dupe\"")
    }

    /**
     * Did the server take the contact? Installations answer differently (JSON
     * with "created", or a sentence). We recognise acceptance and **reject
     * everything else**: an uncertain upload must not be marked as done, or the
     * contact is never sent again.
     */
    /** What the server did with the contact. */
    enum class Issue { PRIS, REFUS, DOUTE }

    /**
     * Accepted, refused, or unknown — **three outcomes, not two**.
     *
     * A read timeout is not a refusal: the request left, the server may have
     * processed it, only the answer was lost (seen: timeout reported, contact
     * recorded in Wavelog). Treating doubt as refusal re-uploads and **aborts
     * the batch** on the first slow server; treating it as success loses the
     * contact when the request really failed.
     *
     * So: mark uploaded only on acknowledgement, stop only on a clear refusal,
     * and report doubt as doubt.
     */
    fun issue(reponse: String): Issue {
        val r = reponse.lowercase()
        if (r.contains("timed out") || r.contains("timeout") ||
            r.contains("aucune réponse") || r.startsWith("échec") ||
            r.contains("connection reset")) return Issue.DOUTE
        if (r.startsWith("http ") || r.contains("\"error\"") ||
            r.contains("auth error")) return Issue.REFUS
        if (r.contains("created") || r.contains("\"status\":\"ok\"") ||
            r.contains("qso added") || r.contains("success")) return Issue.PRIS
        // The server answered something unrecognised: neither refusal nor
        // success.
        return Issue.DOUTE
    }

    /**
     * Why the server refused, in a line: Wavelog's "messages" (its ADIF
     * import errors), else "reason" or "message", else the text without HTML.
     * Its answer also echoes the whole ADIF sent, which drowned the reason.
     */
    fun raison(reponse: String): String {
        val code = Regex("^HTTP (\\d{3})").find(reponse)?.groupValues?.get(1)
        val corps = reponse.substringAfter('{', "").let { if (it.isEmpty()) "" else "{$it" }
        val texte = runCatching {
            val o = JSONObject(corps)
            val m = o.optJSONArray("messages")
            val messages = if (m == null) "" else
                (0 until m.length()).map { m.optString(it).trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
            messages.ifBlank { o.optString("reason").ifBlank { o.optString("message") } }
        }.getOrDefault("").ifBlank {
            reponse.removePrefix("HTTP ${code ?: ""}").replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ").trim()
        }
        return listOfNotNull(code?.let { "HTTP $it" }, texte.take(160).ifBlank { null }).joinToString(" : ")
    }

    /**
     * Wavelog refused the contact because it already has it: then it is in
     * the online log, which is all the upload wanted.
     */
    fun doublon(reponse: String): Boolean = reponse.startsWith("HTTP 400") &&
        raison(reponse).contains("duplicate", ignoreCase = true)

    /** Is the contact unambiguously acknowledged? */
    fun accepte(reponse: String): Boolean = issue(reponse) == Issue.PRIS

    private fun lis(reponse: String): Etat? = runCatching {
        val o = JSONObject(reponse)
        when (o.optString("result").lowercase()) {
            "confirmed" -> Etat.CONFIRME
            "workedbefore", "worked" -> Etat.TRAVAILLE
            "nope", "no", "notworked" -> Etat.JAMAIS
            else -> null
        }
    }.getOrNull()

    /**
     * The radio's state for the log ([RelaisRadio.json]). Returns null when
     * accepted, else a short reason for the settings screen.
     */
    suspend fun radio(base: String, corps: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val r = poste(base, "api/radio", corps)
            when {
                r.startsWith("HTTP ") || r == "aucune réponse" -> r
                r.contains("\"status\":\"failed\"", true) || r.contains("\"reason\"", true) ->
                    runCatching { JSONObject(r).optString("reason").ifBlank { r.take(80) } }.getOrDefault(r.take(80))
                else -> null
            }
        }.getOrElse { it.javaClass.simpleName }
    }

    /**
     * Sends the request, trying both URL forms. The docs say "base/api/qso"
     * but their own curl examples use "base/index.php/api/qso"; depending on
     * URL rewriting one fails — often with a 500 rather than a 404. Try the
     * short form, then the other.
     */
    @Volatile private var prefixe: String? = null

    private fun poste(base: String, route: String, corps: String): String {
        val racine = base.trim().trimEnd('/')
        val formes = prefixe?.let { listOf(it) } ?: listOf("", "index.php/")
        var derniere = ""
        for (f in formes) {
            val r = runCatching { envoie("$racine/$f$route", corps) }.getOrNull()
            if (r != null && r.second in 200..299) { prefixe = f; return r.first }
            // **The body with the code.** Wavelog says why in it ("messages"):
            // a bare "HTTP 400" left the operator guessing. A 404 is only the
            // wrong URL form; the other form's answer is the one that counts.
            if (r != null && (derniere.isEmpty() || r.second != 404))
                derniere = "HTTP ${r.second} ${r.first}".trimEnd()
        }
        return derniere.ifBlank { "aucune réponse" }
    }

    /**
     * Fetches the online log's ADIF to feed the callsign keypad.
     *
     * Wavelog returns JSON **wrapping** the ADIF, with the contact count and the
     * id of the last exported contact. That id drives incremental loading: keep
     * it and resume from there. The docs ask not to pull the whole log every
     * time; instances rate-limit.
     *
     * A **read-only key is enough** here, unlike uploading. Only the prediction
     * index is kept (callsign, grid square, date, satellite), not full contacts.
     */
    data class Moisson(val adif: String, val nombre: Int, val dernierId: Long,
                       val message: String)

    suspend fun moissonne(
        base: String, cle: String, profils: List<String>, depuisId: Long,
        filtre: String = fr.f4ioz.satcombo.domain.FiltreMoisson.SAT,
    ): Moisson = withContext(Dispatchers.IO) {
        val corps = JSONObject()
            .put("key", cle.trim())
            .put("fetchfromid", depuisId)
            .apply {
                // Since 2.5.1 the endpoint accepts an array of profiles (all of a
                // rover's locations in one call); a single profile still works
                // for older versions.
                if (profils.size == 1) put("station_id", profils.first())
                else put("station_id", org.json.JSONArray(profils))
            }
            .apply {
                // The server filter takes one band and no mode: for other choices
                // fetch everything and filter here — much heavier, and the screen
                // must say so.
                fr.f4ioz.satcombo.domain.FiltreMoisson.bandeServeur(filtre)
                    ?.let { put("band", it) }
            }
            .toString()
        val reponse = poste(base, "api/get_contacts_adif", corps)
        val o = runCatching { JSONObject(reponse) }.getOrElse {
            return@withContext Moisson("", 0, depuisId, reponse.take(200))
        }
        Moisson(
            adif = o.optString("adif"),
            nombre = o.optInt("exported_qsos"),
            // No id returned: **keep the previous one**, or the next call
            // reloads the whole log.
            dernierId = o.optLong("lastfetchedid", depuisId),
            message = o.optString("message"))
    }

    /** A plain GET, trying both URL forms. */
    private fun lit(base: String, route: String): String {
        val racine = base.trim().trimEnd('/')
        val formes = prefixe?.let { listOf(it) } ?: listOf("", "index.php/")
        var derniere = ""
        for (f in formes) {
            val r = runCatching { demande("$racine/$f$route") }.getOrNull()
            if (r != null && r.second in 200..299) { prefixe = f; return r.first }
            if (r != null) derniere = "HTTP ${r.second}"
        }
        return derniere.ifBlank { "aucune réponse" }
    }

    private fun demande(adresse: String): Pair<String, Int> {
        val co = URL(adresse).openConnection() as HttpURLConnection
        co.requestMethod = "GET"
        co.connectTimeout = 8000
        co.readTimeout = 90000
        co.setRequestProperty("Accept", "application/json")
        co.setRequestProperty("User-Agent", "SatMe (f4ioz.fr)")
        val code = co.responseCode
        val flux = if (code in 200..299) co.inputStream else co.errorStream
        return (flux?.bufferedReader()?.use { it.readText() } ?: "") to code
    }

    private fun envoie(adresse: String, corps: String): Pair<String, Int> {
        val url = URL(adresse)
        val co = url.openConnection() as HttpURLConnection
        co.requestMethod = "POST"
        co.connectTimeout = 8000
        co.readTimeout = 90000
        co.doOutput = true
        co.setRequestProperty("Content-Type", "application/json")
        // Required by the docs: without it some installations return HTML.
        co.setRequestProperty("Accept", "application/json")
        co.setRequestProperty("User-Agent", "SatMe (f4ioz.fr)")
        co.outputStream.use { it.write(corps.toByteArray()) }
        val code = co.responseCode
        val flux = if (code in 200..299) co.inputStream else co.errorStream
        return (flux?.bufferedReader()?.use { it.readText() } ?: "") to code
    }
}
