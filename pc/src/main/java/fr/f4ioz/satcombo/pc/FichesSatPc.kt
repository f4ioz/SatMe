/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.demo.ReponseWeb
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The satellites' cards (Orbites, its dashboard, the Vitrine): a real photo
 * and what to know about the well-known satellites of [FichesSat].
 *
 * The facts are SatMe's own ([FichesSat]); the frequencies are the station's
 * (SatNOGS, as everywhere in SatMe). The photo and the article's summary
 * are asked once, when a card is first shown, from Wikipedia (French, then
 * English) and Wikimedia Commons, and kept in [dossier]: afterwards nothing
 * leaves, and without the network the card shows its facts with the app's
 * own drawing. A photo is taken only under a free licence (public domain,
 * CC0, CC BY, CC BY-SA, read from Commons' metadata), its author and
 * licence kept to be shown under it; a picture held by Wikipedia itself
 * (often not free) is never taken. The requests carry the app's generic
 * User-Agent, nothing about the station.
 */
class FichesSatPc(private val dossier: File,
                  /** [catnum]'s transmitters as the station knows them. */
                  private val emetteurs: (Int) -> List<Transmitter> = { emptyList() },
                  /** The fetcher (the tests' own): the bytes of a URL, or an exception. */
                  private val telecharge: (String) -> ByteArray = ::telechargeHttp,
                  private val horloge: () -> Long = System::currentTimeMillis,
                  /** Fetching in the background (the tests: at once). */
                  private val lance: (() -> Unit) -> Unit = { f -> thread(isDaemon = true, name = "satme-fiches-sat") { f() } }) {

    /** When each card was last tried (a failure is not tried again for a while). */
    private val essais = ConcurrentHashMap<String, Long>()
    private val enCours = ConcurrentHashMap.newKeySet<String>()
    private val frequences = ConcurrentHashMap<Int, Pair<Long, List<Transmitter>>>()

    fun sert(chemin: String, p: (String) -> String): ReponseWeb = runCatching {
        when (chemin) {
            "/api/satfiche/photo" -> {
                val f = FichesSat.toutes.firstOrNull { it.cle == p("cle") }?.let { photo(it) }
                if (f == null) ReponseWeb("404 Not Found", "text/plain", ByteArray(0))
                else ReponseWeb("200 OK", typeImage(f.readBytes()), f.readBytes(), "Cache-Control: max-age=86400\r\n")
            }
            else -> json(p("catnum").toIntOrNull() ?: 0, p("nom"), p("internet") != "0")
        }
    }.getOrElse { ReponseWeb("200 OK", "application/json; charset=utf-8", JSONObject().put("ok", false).put("erreur", it.message ?: "erreur").toString().toByteArray()) }

    /**
     * The card of [catnum] (or [nom]): the facts, the frequencies, the summary and photo once fetched; asks for them when
     * missing, unless the page said not to ([internet] off: what is kept is still shown).
     */
    fun json(catnum: Int, nom: String, internet: Boolean = true): ReponseWeb {
        val e = FichesSat.cherche(catnum, nom)
        val o = JSONObject().put("ok", true).put("connu", e != null)
        // The station's transmitters, read again every ten minutes at most (SatNOGS' answer is kept on disk by the station).
        val modes = frequences[catnum]?.takeIf { horloge() - it.first < 600_000L }?.second
            ?: runCatching { emetteurs(catnum).filter { it.alive } }.getOrDefault(emptyList()).also { if (catnum > 0) frequences[catnum] = horloge() to it }
        o.put("frequences", JSONArray(modes.filter { amateur(it.downlinkLowHz) }.take(6).map { t ->
            JSONObject().put("desc", t.description).put("mode", if (t.isTransponder) "transpondeur" + (if (t.invert) " inversé" else "") else t.mode.orEmpty())
                .put("dl", t.downlinkLowHz?.let { it / 1e6 } ?: JSONObject.NULL).put("ul", t.uplinkLowHz?.let { it / 1e6 } ?: JSONObject.NULL)
        }))
        if (e == null) return reponse(o)
        o.put("cle", e.cle).put("nom", e.nom)
        o.put("faits", JSONObject().put("pays", e.pays).put("operateur", e.operateur).put("lancement", e.lancement)
            .put("mission", e.mission).put("orbite", e.orbite).put("anecdote", e.anecdote).put("taille", e.taille))
        val cache = lisCache(e)
        if (internet && (cache == null || horloge() - cache.optLong("t") > VALIDITE_MS)) demande(e)
        if (cache == null) { o.put("attente", e.cle in enCours); return reponse(o) }
        cache.optJSONObject("resume")?.let { o.put("resume", it) }
        if (photo(e) != null) cache.optJSONObject("photo")?.let { o.put("photo", JSONObject(it.toString()).put("url", "api/satfiche/photo?cle=" + e.cle)) }
        return reponse(o)
    }

    private fun reponse(o: JSONObject) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private fun lisCache(e: FichesSat.Fiche): JSONObject? = runCatching { JSONObject(File(dossier, e.cle + ".json").readText()) }.getOrNull()
    private fun photo(e: FichesSat.Fiche): File? = File(dossier, e.cle + ".img").takeIf { it.isFile && it.length() > 0 }

    /** Fetches [e]'s summary and photo in the background, unless under way or tried a short while ago. */
    private fun demande(e: FichesSat.Fiche) {
        val t = horloge()
        if (t - (essais[e.cle] ?: 0L) < REESSAI_MS || !enCours.add(e.cle)) return
        essais[e.cle] = t
        lance { try { recupere(e) } catch (_: Throwable) {} finally { enCours.remove(e.cle) } }
    }

    /** The summary (the first article that has one) and a free photo (the first article whose picture is on Commons). */
    fun recupere(e: FichesSat.Fiche) {
        var resume: Resume? = null
        var licence: Licence? = null
        var image: ByteArray? = null
        // No article to ask: kept at once as it is (its facts and the drawing).
        var joint = e.articles.isEmpty()
        for ((langue, titre) in e.articles) {
            val r = runCatching { lisResume(String(telecharge(urlResume(langue, titre)), Charsets.UTF_8), langue) }.getOrNull() ?: continue
            joint = true
            if (resume == null && r.extrait.isNotBlank()) resume = r
            if (image == null && r.fichier != null) {
                val l = runCatching { lisLicence(String(telecharge(urlCommons(r.fichier)), Charsets.UTF_8)) }.getOrNull()
                if (l != null && l.libre && l.vignette.isNotEmpty()) {
                    val b = runCatching { telecharge(l.vignette) }.getOrNull()
                    if (b != null && b.size in 200..MAX_IMAGE && typeImage(b) != "application/octet-stream") { image = b; licence = l }
                }
            }
            if (resume != null && image != null) break
        }
        // Nothing reached: tried again later; reached without a free photo: the card keeps its drawing.
        if (!joint) return
        dossier.mkdirs()
        image?.let { File(dossier, e.cle + ".img").writeBytes(it) }
        val o = JSONObject().put("t", horloge())
        resume?.let { o.put("resume", JSONObject().put("titre", it.titre).put("extrait", it.extrait).put("lien", it.lien).put("langue", it.langue)) }
        licence?.takeIf { image != null }?.let {
            o.put("photo", JSONObject().put("auteur", it.auteur).put("licence", it.nom).put("lienLicence", it.lienLicence).put("source", it.page))
        }
        File(dossier, e.cle + ".json").writeText(o.toString())
    }

    /** An article's summary: its title, its first lines, its page, its picture's file name when it is on Commons. */
    data class Resume(val titre: String, val extrait: String, val lien: String, val langue: String, val fichier: String?)

    /** A Commons file's licence: its short name, free or not, its author, where it is described, its 640 px picture. */
    data class Licence(val nom: String, val code: String, val libre: Boolean, val auteur: String, val lienLicence: String, val page: String, val vignette: String)

    companion object {
        /** A card fetched is kept a month; a failure tried again after a quarter of an hour. */
        const val VALIDITE_MS = 30L * 86_400_000L
        const val REESSAI_MS = 15L * 60_000L
        const val MAX_IMAGE = 3_000_000

        /** The amateur bands (10 m to 3 cm) and the weather satellites' 137 MHz: a station's other transmitters (Soyuz, telemetry) left out. */
        fun amateur(hz: Long?): Boolean {
            val m = (hz ?: return false) / 1e6
            return m in 28.0..29.7 || m in 137.0..138.0 || m in 144.0..146.0 || m in 430.0..440.0 || m in 1240.0..1300.0 || m in 2300.0..2450.0 || m in 10450.0..10500.0
        }

        fun urlResume(langue: String, titre: String) =
            "https://$langue.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(titre.replace(' ', '_'), "UTF-8").replace("+", "%20")

        fun urlCommons(fichier: String) = "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=640" +
            "&iiextmetadatafilter=Artist%7CCredit%7CLicenseShortName%7CLicense%7CLicenseUrl%7CUsageTerms&titles=" +
            URLEncoder.encode("File:$fichier", "UTF-8").replace("+", "%20")

        /** Wikipedia's REST summary ([texte]): null when it is not an article (a disambiguation page, an error). */
        fun lisResume(texte: String, langue: String): Resume? {
            val o = JSONObject(texte)
            if (o.optString("type") != "standard") return null
            val image = o.optJSONObject("originalimage")?.optString("source") ?: o.optJSONObject("thumbnail")?.optString("source")
            return Resume(o.optString("title"), o.optString("extract").trim(),
                o.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty(), langue, image?.let { fichierCommons(it) })
        }

        /**
         * The Commons file name in a picture's address ("…/wikipedia/commons/thumb/5/59/Name.jpg/330px-Name.jpg" or
         * "…/wikipedia/commons/5/59/Name.jpg"), decoded; null for a picture held by a Wikipedia itself (".../wikipedia/en/…").
         */
        fun fichierCommons(url: String): String? {
            val chemin = url.substringBefore('?').substringBefore('#')
            val s = chemin.split('/')
            val i = s.indexOf("commons").takeIf { it > 0 && s[it - 1] == "wikipedia" } ?: return null
            val reste = s.drop(i + 1).let { if (it.firstOrNull() == "thumb") it.drop(1) else it }
            val nom = reste.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
            return URLDecoder.decode(nom.replace("+", "%2B"), "UTF-8")
        }

        /** Commons' answer ([texte]) about one file: its licence, author and picture; null when it holds none. */
        fun lisLicence(texte: String): Licence? {
            val pages = JSONObject(texte).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val p = pages.keys().asSequence().map { pages.getJSONObject(it) }.firstOrNull() ?: return null
            val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
            val m = ii.optJSONObject("extmetadata") ?: JSONObject()
            fun v(k: String) = m.optJSONObject(k)?.optString("value").orEmpty()
            val code = v("License").trim().lowercase()
            val nom = texteSimple(v("LicenseShortName")).ifBlank { texteSimple(v("UsageTerms")) }
            val auteur = texteSimple(v("Artist")).ifBlank { texteSimple(v("Credit")) }.take(140)
            return Licence(nom, code, licenceLibre(code, nom), auteur, v("LicenseUrl"), ii.optString("descriptionurl"), ii.optString("thumburl"))
        }

        /** Public domain, CC0, CC BY and CC BY-SA (any version): free to show; NC, ND, « fair use » and unknown ones are not. */
        fun licenceLibre(code: String, nom: String): Boolean {
            val c = code.lowercase().trim()
            if (c.isNotEmpty()) return c == "pd" || c == "cc0" || c.startsWith("pd-") || Regex("^cc-by(-sa)?(-\\d(\\.\\d)?)?([a-z-]*)?$").matches(c) && !c.contains("nc") && !c.contains("nd")
            val n = nom.lowercase().trim()
            return n == "public domain" || n == "domaine public" || n == "cc0" || Regex("^cc by(-sa)? \\d(\\.\\d)?$").matches(n)
        }

        /** Commons' HTML (an author's link, a credit) as plain text. */
        fun texteSimple(html: String): String = html.replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()

        /** A picture's type from its first bytes (JPEG, PNG, WebP, GIF). */
        fun typeImage(b: ByteArray): String = when {
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
            b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
            b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            b.size > 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif"
            else -> "application/octet-stream"
        }

        private val client by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

        fun telechargeHttp(url: String): ByteArray {
            require(url.startsWith("https://") && (url.contains(".wikipedia.org/") || url.contains(".wikimedia.org/"))) { "source interdite : $url" }
            val req = Request.Builder().url(url).header("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                return r.body?.bytes() ?: ByteArray(0)
            }
        }
    }
}
