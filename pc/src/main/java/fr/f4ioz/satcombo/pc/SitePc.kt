/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * « Réglages › Site web »: what SatMe-PC publishes on the operator's web
 * site (the F4IOZ site's publication API, `site` module): the SSTV
 * pictures, the planches, the reports, the contacts, the journal's videos.
 * Each kind **Automatique** (as it comes), **Sur clic** (the « Publier sur le
 * site » button of each item) or **Jamais**.
 *
 * Nothing is sent from the request that asks: each item is copied into a
 * queue on disk (`site/`) and a thread sends it, again and again while the
 * site does not answer (30 s, 1 min, 2 min… an hour at most), until it is
 * taken or refused. The token stays on this PC (with the other services'
 * keys) and is never shown again nor written in a log. The demonstration
 * never publishes but to a site on this machine (a local test).
 */
class SitePc(
    private val stockage: Stockage,
    private val dossier: File,
    private val demo: () -> Boolean = { ModeDemo.actif },
    private val horloge: () -> Long = System::currentTimeMillis,
    private val transport: Transport = Transport.HTTP,
) {
    /** One request to the site: its answer's status and text. */
    fun interface Transport {
        fun envoie(methode: String, url: String, entetes: Map<String, String>, corps: ByteArray?, fichier: File?): Pair<Int, String>

        companion object {
            val HTTP = Transport { methode, url, entetes, corps, fichier ->
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.requestMethod = methode
                c.connectTimeout = 10_000; c.readTimeout = 120_000
                c.instanceFollowRedirects = false
                entetes.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.setRequestProperty("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT)
                if (corps != null || fichier != null) {
                    c.doOutput = true
                    val n = corps?.size?.toLong() ?: fichier!!.length()
                    c.setFixedLengthStreamingMode(n)
                    c.outputStream.use { o -> if (corps != null) o.write(corps) else fichier!!.inputStream().use { it.copyTo(o, 64 * 1024) } }
                }
                val code = c.responseCode
                val texte = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(4000) }.orEmpty()
                code to texte
            }
        }
    }

    /** The kinds, as the site's API names them. */
    enum class Genre(val nom: String, val libelle: String, val parDefaut: Mode) {
        SSTV("sstv-image", "Images SSTV", Mode.AUTO),
        PLANCHE("planche", "Planches", Mode.AUTO),
        RAPPORT("rapport", "Rapports", Mode.CLIC),
        QSO("qso", "Contacts (QSO)", Mode.CLIC),
        VIDEO("journal-video", "Vidéos du journal", Mode.CLIC);

        companion object { fun de(n: String) = values().firstOrNull { it.nom == n } }
    }

    enum class Mode(val nom: String) { AUTO("auto"), CLIC("clic"), JAMAIS("jamais");
        companion object { fun de(n: String?) = values().firstOrNull { it.nom == n } } }

    /** An item ready to go: its bytes (or a large file left in place), its words for the site. */
    class Contenu(val octets: ByteArray?, val fichier: File?, val type: String, val meta: JSONObject)

    /**
     * Where SatMe's items come from (set by Main): a picture of the gallery by its name, a planche by its id,
     * its event's report, a contact by its time, a journal's video by its file's name.
     */
    @Volatile var sources: (Genre, String) -> Contenu? = { _, _ -> null }

    /** The planches worth publishing now (ids of those with a picture), for « Automatique ». */
    @Volatile var planchesRemplies: () -> List<String> = { emptyList() }

    // ------------------------------------------------------------------ settings

    var url: String
        get() = stockage.getString("site_url", "") ?: ""
        set(v) { stockage.edit().putString("site_url", normalise(v)).apply() }

    /** The token: kept, never given back (only whether there is one). */
    private var jeton: String
        get() = stockage.getString("site_jeton", "") ?: ""
        set(v) { stockage.edit().putString("site_jeton", v.trim()).apply() }

    fun mode(g: Genre): Mode = Mode.de(stockage.getString("site_mode_" + g.nom, null)) ?: g.parDefaut
    fun regleMode(g: Genre, m: Mode) { stockage.edit().putString("site_mode_" + g.nom, m.nom).apply() }

    val configure: Boolean get() = url.isNotEmpty() && jeton.isNotEmpty()

    /** In the demonstration, a site on this machine only. */
    private fun refusDemo(): String? = if (demo() && !local(url)) "démonstration : publication seulement vers un site de cette machine (http://127.0.0.1:…)" else null

    // ------------------------------------------------------------------ the queue

    /** One item waiting: its kind, SatMe's name for it, its words, its bytes' file, its tries. */
    data class Envoi(val id: String, val genre: Genre, val ref: String, val titre: String, val meta: JSONObject, val type: String,
                     val fichier: String, val temporaire: Boolean, val ajouteMs: Long, val essais: Int = 0, val prochainMs: Long = 0L, val erreur: String = "") {
        fun json(): JSONObject = JSONObject().put("id", id).put("genre", genre.nom).put("ref", ref).put("titre", titre).put("meta", meta).put("type", type)
            .put("fichier", fichier).put("temporaire", temporaire).put("ajoute", ajouteMs).put("essais", essais).put("prochain", prochainMs).put("erreur", erreur)

        companion object {
            fun depuis(o: JSONObject): Envoi? = Genre.de(o.optString("genre"))?.let { g ->
                Envoi(o.optString("id"), g, o.optString("ref"), o.optString("titre"), o.optJSONObject("meta") ?: JSONObject(), o.optString("type"),
                    o.optString("fichier"), o.optBoolean("temporaire"), o.optLong("ajoute"), o.optInt("essais"), o.optLong("prochain"), o.optString("erreur"))
            }
        }
    }

    /** What was published: its id and address on the site, when. */
    data class Publie(val id: String, val url: String, val quandMs: Long)

    private val dossierSite = File(dossier, "site")
    private val fichierFile = File(dossierSite, "file.json")
    private val fichierPublies = File(dossierSite, "publies.json")
    private val file = ArrayList<Envoi>()
    private val publies = ConcurrentHashMap<String, Publie>()
    /** The last few answers, for the card (« publié », « refusé : … »). */
    private val historique = ArrayDeque<Pair<Long, String>>()
    @Volatile private var charge = false

    private fun cle(g: Genre, ref: String) = g.nom + "|" + ref

    @Synchronized private fun chargeDisque() {
        if (charge) return
        charge = true
        runCatching { JSONArray(fichierFile.readText()).let { a -> (0 until a.length()).mapNotNull { Envoi.depuis(a.getJSONObject(it)) } } }.getOrNull()?.let { file.addAll(it) }
        runCatching { JSONObject(fichierPublies.readText()) }.getOrNull()?.let { o ->
            o.keySet().forEach { k -> o.optJSONObject(k)?.let { p -> publies[k] = Publie(p.optString("id"), p.optString("url"), p.optLong("t")) } }
        }
    }

    @Synchronized private fun ecritDisque() {
        dossierSite.mkdirs()
        val tmp = File(dossierSite, "file.json.tmp")
        tmp.writeText(JSONArray(file.map { it.json() }).toString()); tmp.renameTo(fichierFile)
        val t2 = File(dossierSite, "publies.json.tmp")
        t2.writeText(JSONObject(publies.mapValues { (_, p) -> JSONObject().put("id", p.id).put("url", p.url).put("t", p.quandMs) }).toString()); t2.renameTo(fichierPublies)
    }

    private fun note(s: String) = synchronized(historique) { historique.addFirst(horloge() to s); while (historique.size > 12) historique.removeLast() }

    /**
     * [ref] of kind [g] into the queue: by hand ([manuel]: whatever the mode but « Jamais »), or by itself (« Automatique »
     * only). Null when queued (or already published, unchanged), else why not.
     */
    fun publie(g: Genre, ref: String, manuel: Boolean): String? {
        chargeDisque()
        val m = mode(g)
        if (m == Mode.JAMAIS) return "« ${g.libelle} » : jamais publiés (Réglages › Site web)"
        if (!manuel && m != Mode.AUTO) return null
        if (!configure) return "le site web n'est pas réglé (Réglages › Site web)"
        refusDemo()?.let { return it }
        val c = runCatching { sources(g, ref) }.getOrNull() ?: return "introuvable : $ref"
        val id = java.lang.Long.toString(horloge(), 36) + (1000..9999).random()
        val (fichier, temporaire) = if (c.octets != null) {
            File(dossierSite, "envois").mkdirs()
            val f = File(File(dossierSite, "envois"), "$id.bin"); f.writeBytes(c.octets); f.absolutePath to true
        } else (c.fichier ?: return "rien à envoyer").absolutePath to false
        val titre = c.meta.optString("titre").ifEmpty { g.libelle + " · " + ref }
        synchronized(this) {
            // The same item already waiting: the newer version only.
            file.filter { it.genre == g && it.ref == ref }.forEach { ancien -> if (ancien.temporaire) File(ancien.fichier).delete() }
            file.removeAll { it.genre == g && it.ref == ref }
            file += Envoi(id, g, ref, titre, c.meta, c.type, fichier, temporaire, horloge())
            while (file.size > MAX_FILE) { val e = file.removeAt(0); if (e.temporaire) File(e.fichier).delete() }
            ecritDisque()
        }
        reveille()
        return null
    }

    /** Whether [ref] was published, and where. */
    fun publication(g: Genre, ref: String): Publie? { chargeDisque(); return publies[cle(g, ref)] }

    // ------------------------------------------------------------------ the sender

    private val verrou = Object()
    @Volatile private var fil: Thread? = null

    fun demarre() {
        chargeDisque()
        if (fil != null) return
        fil = Thread({ while (true) { runCatching { envoieDus() }; synchronized(verrou) { verrou.wait(5_000L) } } }, "satme-site").apply { isDaemon = true; start() }
    }

    private fun reveille() = synchronized(verrou) { verrou.notifyAll() }

    /** Sends what is due, one after the other; returns how many went. */
    fun envoieDus(): Int {
        chargeDisque()
        var n = 0
        while (true) {
            val e = synchronized(this) { file.firstOrNull { it.prochainMs <= horloge() } } ?: return n
            if (!configure || refusDemo() != null) return n
            val r = runCatching { envoie(e) }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) }
            synchronized(this) {
                file.removeAll { it.id == e.id }
                when {
                    r.first in 200..299 -> {
                        val o = runCatching { JSONObject(r.second) }.getOrDefault(JSONObject())
                        publies[cle(e.genre, e.ref)] = Publie(o.optString("id"), o.optString("url"), horloge())
                        if (e.temporaire) File(e.fichier).delete()
                        note("publié : " + e.titre + if (o.optBoolean("deja")) " (déjà en ligne)" else "")
                        n++
                    }
                    // Refused for good (token, kind, size): kept out of the queue, said on the card.
                    r.first in 400..499 && r.first != 408 && r.first != 429 -> {
                        if (e.temporaire) File(e.fichier).delete()
                        note("refusé (" + r.first + ") : " + e.titre + " — " + raison(r.second))
                    }
                    else -> {
                        val k = e.essais + 1
                        file.add(0, e.copy(essais = k, prochainMs = horloge() + attente(k), erreur = if (r.first == 0) r.second.take(160) else "HTTP " + r.first))
                        ecritDisque()
                        return n
                    }
                }
                ecritDisque()
            }
        }
    }

    private fun envoie(e: Envoi): Pair<Int, String> {
        val f = File(e.fichier)
        if (!f.isFile) return 410 to "{\"erreur\":\"fichier disparu\"}"
        val meta = JSONObject(e.meta.toString()).put("cle", e.genre.nom.substringBefore('-') + ":" + e.ref)
        val entetes = mapOf("Authorization" to "Bearer $jeton", "Content-Type" to e.type,
            "X-SatMe-Meta" to Base64.getUrlEncoder().withoutPadding().encodeToString(meta.toString().toByteArray(Charsets.UTF_8)))
        return if (e.temporaire || f.length() < 32L * 1024 * 1024) transport.envoie("POST", url + "/api/publication/" + e.genre.nom, entetes, f.readBytes(), null)
        else transport.envoie("POST", url + "/api/publication/" + e.genre.nom, entetes, null, f)
    }

    /** « Tester la connexion »: the site answers, and takes the token. */
    fun essai(): String {
        if (url.isEmpty()) return "renseigner l'adresse du site"
        if (jeton.isEmpty()) return "renseigner le jeton de publication"
        refusDemo()?.let { return it }
        val r = runCatching { transport.envoie("GET", "$url/api/publication/ping", mapOf("Authorization" to "Bearer $jeton"), null, null) }
            .getOrElse { return "pas de réponse : " + (it.message ?: it.javaClass.simpleName) }
        return when (r.first) {
            200 -> runCatching { JSONObject(r.second) }.getOrNull()?.let { "ok : site de " + it.optString("indicatif") + " (" + it.optString("site") + ")" } ?: "ok"
            401 -> "jeton refusé par le site"
            403 -> "le site n'accepte pas de publication (jeton non réglé côté site)"
            else -> "HTTP " + r.first + " : " + raison(r.second)
        }
    }

    /** Withdraws [ref] from the site. */
    fun retire(g: Genre, ref: String): String? {
        chargeDisque()
        if (!configure) return "le site web n'est pas réglé"
        refusDemo()?.let { return it }
        val p = publies[cle(g, ref)]
        val u = if (p != null && p.id.isNotEmpty()) "$url/api/publication/" + p.id
            else "$url/api/publication?type=" + g.nom + "&cle=" + java.net.URLEncoder.encode(g.nom.substringBefore('-') + ":" + ref, Charsets.UTF_8)
        val r = runCatching { transport.envoie("DELETE", u, mapOf("Authorization" to "Bearer $jeton"), null, null) }.getOrElse { return "pas de réponse" }
        if (r.first !in 200..299 && r.first != 404) return "HTTP " + r.first + " : " + raison(r.second)
        publies.remove(cle(g, ref)); ecritDisque()
        return null
    }

    // ------------------------------------------------------------------ what happens in SatMe-PC

    @Volatile private var planchesT = 0L

    /** A picture received live: published if « Automatique »; the planches (and reports) after a quiet couple of minutes. */
    fun nouvelleImage(nom: String) {
        publie(Genre.SSTV, nom, false)
        if (mode(Genre.PLANCHE) == Mode.AUTO || mode(Genre.RAPPORT) == Mode.AUTO) {
            val t = horloge(); planchesT = t
            Thread({
                runCatching { Thread.sleep(APRES_IMAGE_MS) }
                if (planchesT == t) runCatching { planchesRemplies().forEach { id -> publie(Genre.PLANCHE, id, false); publie(Genre.RAPPORT, id, false) } }
            }, "satme-site-planches").apply { isDaemon = true; start() }
        }
    }

    fun nouveauContact(t: Long) { publie(Genre.QSO, t.toString(), false) }
    fun nouvelleVideo(nom: String) { publie(Genre.VIDEO, nom, false) }

    // ------------------------------------------------------------------ the page

    fun json(): JSONObject {
        chargeDisque()
        val o = JSONObject().put("ok", true).put("url", url).put("jeton", jeton.isNotEmpty()).put("configure", configure).put("demo", demo())
            .put("modes", JSONObject(Genre.values().associate { it.nom to mode(it).nom }))
            .put("libelles", JSONObject(Genre.values().associate { it.nom to it.libelle }))
            .put("publies", publies.size)
        synchronized(this) {
            o.put("file", JSONArray(file.map { JSONObject().put("titre", it.titre).put("genre", it.genre.nom).put("essais", it.essais).put("prochain", it.prochainMs).put("erreur", it.erreur) }))
        }
        synchronized(historique) { o.put("historique", JSONArray(historique.map { JSONObject().put("t", it.first).put("texte", it.second) })) }
        return o
    }

    /** `api/site…`: the settings and the queue (GET), set, test, publish, withdraw (POST). */
    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val route = chemin.removePrefix("/api/site")
        fun rep(o: JSONObject) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8), "Cache-Control: no-store\r\n")
        fun dit(err: String?, ok: String) = rep(if (err == null) JSONObject().put("ok", true).put("message", ok) else JSONObject().put("ok", false).put("erreur", err))
        if (methode != "POST") return if (route == "" || route == "/") rep(json()) else rep(JSONObject().put("ok", false).put("erreur", "inconnu"))
        return when (route) {
            "/reglages" -> {
                p("url").takeIf { it.isNotEmpty() || p("efface") == "1" }?.let { url = it }
                p("jeton").takeIf { it.isNotEmpty() }?.let { jeton = it }
                if (p("efface") == "1") jeton = ""
                Genre.values().forEach { g -> Mode.de(p("mode_" + g.nom))?.let { regleMode(g, it) } }
                reveille()
                rep(json())
            }
            "/essai" -> { val r = essai(); rep(JSONObject().put("ok", r.startsWith("ok")).put("message", r)) }
            "/publie" -> {
                val g = Genre.de(p("type")) ?: return rep(JSONObject().put("ok", false).put("erreur", "type inconnu"))
                dit(publie(g, p("ref"), true), "dans la file d'envoi vers le site")
            }
            "/retire" -> {
                val g = Genre.de(p("type")) ?: return rep(JSONObject().put("ok", false).put("erreur", "type inconnu"))
                dit(retire(g, p("ref")), "retiré du site")
            }
            "/vide" -> { synchronized(this) { file.forEach { if (it.temporaire) File(it.fichier).delete() }; file.clear(); ecritDisque() }; rep(json()) }
            "/maintenant" -> { synchronized(this) { for (i in file.indices) file[i] = file[i].copy(prochainMs = 0L) }; reveille(); rep(json()) }
            else -> rep(JSONObject().put("ok", false).put("erreur", "inconnu"))
        }
    }

    companion object {
        const val MAX_FILE = 300
        const val APRES_IMAGE_MS = 120_000L

        /** 30 s, 1 min, 2 min, 4 min… an hour at most. */
        fun attente(essais: Int): Long = (30_000L shl (essais - 1).coerceIn(0, 7)).coerceAtMost(3_600_000L)

        /** « f4ioz.fr », « https://f4ioz.fr/ » → « https://f4ioz.fr »; plain http only for this machine and the local network. */
        fun normalise(u: String): String {
            val t = u.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }

        /** A site on this machine (the demonstration's local test). */
        fun local(u: String): Boolean = runCatching { URI(u).host }.getOrNull()?.let { it == "127.0.0.1" || it == "localhost" || it == "[::1]" || it == "::1" } == true

        private fun raison(t: String): String = runCatching { JSONObject(t).optString("erreur") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: t.take(160)
    }
}
