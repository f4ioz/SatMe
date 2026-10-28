/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * « Publication web » (Réglages › Services en ligne › Envoyer vers un site
 * web): what SatMe-PC sends to a web site of the owner's — the SSTV
 * pictures, the planches, the reports, the contacts, the journal's videos,
 * and as an option live data ([DirectSitePc]). Each kind **Sur clic** (the
 * 🌐 button of each item; the default), **Automatique** (as it comes) or
 * **Jamais**.
 *
 * Two targets ([Cible]): a web address, where each item is POSTed over HTTPS
 * in a documented, versioned format (docs/publication-web.md: the site
 * receives it as it likes — PHP, WordPress, a small Python or Node server,
 * SatMe's own `site` module…), or a local folder ([DossierSitePc]) for a
 * static site or a sync tool. Always pushed by SatMe-PC: nothing is opened
 * on the station.
 *
 * An optional module ([ModulesStation.SITE]), off by default: while it is off
 * nothing at all goes out (no queue, no test, no live data). Switched on, the
 * target is ready only once it passed **Tester** ([pret]).
 *
 * Nothing is sent from the request that asks: each item is copied into a
 * queue on disk (`site/`) and a thread sends it, again and again while the
 * site does not answer (30 s, 1 min, 2 min… an hour at most), until it is
 * taken or refused; its `X-SatMe-Id` stays the same from one try to the
 * next. The token (optional) stays on this PC and is never shown again nor
 * written in a log. The demonstration never publishes but to a site on this
 * machine (a local test).
 */
class SitePc(
    private val stockage: Stockage,
    private val dossier: File,
    private val demo: () -> Boolean = { ModeDemo.actif },
    private val horloge: () -> Long = System::currentTimeMillis,
    private val transport: Transport = Transport.HTTP,
    private val module: () -> Boolean = { ModulesStation.actif(stockage, ModulesStation.SITE) },
    /** `User-Agent` of every request. */
    private val agent: String = "SatMe-PC/" + versionPc() + " (+https://github.com/f4ioz/SatMe)",
) {
    /** One request to the site: its answer's status and text. */
    fun interface Transport {
        fun envoie(methode: String, url: String, entetes: Map<String, String>, corps: Corps?): Pair<Int, String>

        companion object {
            val HTTP = Transport { methode, url, entetes, corps ->
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.requestMethod = methode
                c.connectTimeout = 10_000; c.readTimeout = 120_000
                c.instanceFollowRedirects = false
                entetes.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (corps != null) {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(corps.longueur)
                    c.outputStream.use { o -> corps.ecrit(o) }
                }
                val code = c.responseCode
                val texte = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(4000) }.orEmpty()
                code to texte
            }
        }
    }

    /** A request's body: bytes and files one after the other (a multipart's parts around a video read as it goes). */
    class Corps(val morceaux: List<Any>) {
        val longueur: Long get() = morceaux.sumOf { if (it is ByteArray) it.size.toLong() else (it as File).length() }
        fun ecrit(o: OutputStream) = morceaux.forEach { m -> if (m is ByteArray) o.write(m) else (m as File).inputStream().use { it.copyTo(o, 64 * 1024) } }
        fun octets(): ByteArray = ByteArrayOutputStream().also { ecrit(it) }.toByteArray()

        companion object {
            fun json(o: JSONObject) = Corps(listOf(o.toString().toByteArray(Charsets.UTF_8)))

            /**
             * `multipart/form-data`: `type` (text), `meta` (JSON), then `fichier` ([contenu]: bytes or a file, named
             * [nom], of type [mime]). Its Content-Type (with the boundary) and the body.
             */
            fun multipart(type: String, meta: JSONObject, nom: String, mime: String, contenu: Any,
                          frontiere: String = "SatMe-" + UUID.randomUUID().toString().replace("-", "")): Pair<String, Corps> {
                val avant = "--$frontiere\r\nContent-Disposition: form-data; name=\"type\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n$type\r\n" +
                    "--$frontiere\r\nContent-Disposition: form-data; name=\"meta\"\r\nContent-Type: application/json; charset=utf-8\r\n\r\n$meta\r\n" +
                    "--$frontiere\r\nContent-Disposition: form-data; name=\"fichier\"; filename=\"$nom\"\r\nContent-Type: $mime\r\n\r\n"
                val apres = "\r\n--$frontiere--\r\n"
                return "multipart/form-data; boundary=$frontiere" to Corps(listOf(avant.toByteArray(Charsets.UTF_8), contenu, apres.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    /** Where it goes: a web address, or a folder on this PC. */
    enum class Cible(val nom: String) { WEB("web"), DOSSIER("dossier");
        companion object { fun de(n: String?) = values().firstOrNull { it.nom == n } } }

    /** The kinds, as the API names them ([prefixe]: the start of an item's `cle`). */
    enum class Genre(val nom: String, val libelle: String, val parDefaut: Mode, val prefixe: String) {
        SSTV("sstv-image", "Images SSTV", Mode.CLIC, "sstv"),
        PLANCHE("planche", "Planches", Mode.CLIC, "planche"),
        RAPPORT("rapport", "Rapports", Mode.CLIC, "rapport"),
        QSO("qso", "Contacts (QSO)", Mode.CLIC, "qso"),
        VIDEO("journal-video", "Vidéos du journal", Mode.CLIC, "journal");

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

    /** The live parts (set by Main), sent beside the publications. */
    @Volatile var direct: DirectSitePc? = null

    // ------------------------------------------------------------------ settings

    init {
        // Before the generic format, the address was the site's root and SatMe's `site` module was the only receiver: its API's path added once.
        if (stockage.getInt(CLE_FORMAT, 0) < 1) {
            val ancienne = stockage.getString("site_url", "") ?: ""
            val e = stockage.edit().putInt(CLE_FORMAT, 1)
            if (ancienne.isNotEmpty() && TYPE !in ancienne) e.putString("site_url", "$ancienne/api/publication/$TYPE")
            e.apply()
        }
    }

    /** Web address or folder; another choice is to be tested again. */
    var cible: Cible
        get() = Cible.de(stockage.getString(CLE_CIBLE, null)) ?: Cible.WEB
        set(v) { if (v != cible) stockage.edit().putString(CLE_CIBLE, v.nom).putBoolean(CLE_PRET, false).apply() }

    /** The address (with `{type}`, or one address for every kind); a new one is to be tested again. */
    var url: String
        get() = stockage.getString("site_url", "") ?: ""
        set(v) { val n = normalise(v); if (n != url) stockage.edit().putString("site_url", n).putBoolean(CLE_PRET, false).apply() }

    /** The folder ([Cible.DOSSIER]); a new one is to be tested again. */
    var chemin: String
        get() = stockage.getString(CLE_DOSSIER, "") ?: ""
        set(v) { val n = v.trim(); if (n != chemin) stockage.edit().putString(CLE_DOSSIER, n).putBoolean(CLE_PRET, false).apply() }

    /** The token (optional): kept, never given back (only whether there is one); a new one is to be tested again. */
    private var jeton: String
        get() = stockage.getString("site_jeton", "") ?: ""
        set(v) { val n = v.trim(); if (n != jeton) stockage.edit().putString("site_jeton", n).putBoolean(CLE_PRET, false).apply() }

    private val ecrivain = DossierSitePc({ File(chemin) }, horloge)

    fun mode(g: Genre): Mode = Mode.de(stockage.getString("site_mode_" + g.nom, null)) ?: g.parDefaut
    fun regleMode(g: Genre, m: Mode) { stockage.edit().putString("site_mode_" + g.nom, m.nom).apply() }

    /** « Publication web » switched on in Réglages › Modules: else nothing goes out. */
    val actif: Boolean get() = runCatching { module() }.getOrDefault(false)

    /** The target set and through « Tester ». A station set before the test was asked (the key absent) counts as tested. */
    val pret: Boolean get() = (if (cible == Cible.WEB) url.isNotEmpty() else chemin.isNotEmpty()) && stockage.getBoolean(CLE_PRET, true)

    /** Publishing can go: the module on, the target set and tested. */
    val configure: Boolean get() = actif && pret

    /** Why nothing can go now, else null. */
    private fun refus(): String? = when {
        !actif -> MODULE_COUPE
        cible == Cible.DOSSIER -> when {
            demo() -> "démonstration : pas d'écriture dans un dossier"
            chemin.isEmpty() -> "le dossier n'est pas choisi ($OU)"
            !pret -> "le dossier n'a pas encore été testé ($OU)"
            else -> null
        }
        url.isEmpty() -> "l'adresse du site n'est pas réglée ($OU)"
        else -> refusHttp(url) ?: refusDemo() ?: if (!pret) "la connexion au site n'a pas encore été testée ($OU)" else null
    }

    /** In the demonstration, a site on this machine only. */
    private fun refusDemo(u: String = url): String? = if (demo() && !local(u)) "démonstration : publication seulement vers un site de cette machine (http://127.0.0.1:…)" else null

    /** The address for [type]: `{type}` replaced, else the one address. */
    fun adresse(type: String): String = url.replace(TYPE, type)

    /** The headers of every request (the token only when there is one). */
    fun entetes(type: String, id: String, contenu: String): Map<String, String> {
        val e = LinkedHashMap<String, String>()
        e["User-Agent"] = agent
        e["X-SatMe-Version"] = VERSION_API.toString()
        e["X-SatMe-Type"] = type
        e["X-SatMe-Id"] = id
        e["Content-Type"] = contenu
        if (jeton.isNotEmpty()) e["Authorization"] = "Bearer $jeton"
        return e
    }

    // ------------------------------------------------------------------ the queue

    /** One item waiting: its kind, SatMe's name for it, its words, its bytes' file, its tries. [id] is its `X-SatMe-Id`. */
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
     * only). Null when queued, else why not.
     */
    fun publie(g: Genre, ref: String, manuel: Boolean): String? {
        chargeDisque()
        val m = mode(g)
        if (!actif) return if (manuel) MODULE_COUPE else null
        if (m == Mode.JAMAIS) return "« ${g.libelle} » : jamais envoyés ($OU)"
        if (!manuel && m != Mode.AUTO) return null
        refus()?.let { return it }
        val c = runCatching { sources(g, ref) }.getOrNull() ?: return "introuvable : $ref"
        val id = UUID.randomUUID().toString()
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
            if (refus() != null) return n
            val r = runCatching { envoie(e) }.getOrElse { 0 to (it.message ?: it.javaClass.simpleName) }
            synchronized(this) {
                file.removeAll { it.id == e.id }
                when {
                    r.first in 200..299 -> {
                        val o = runCatching { JSONObject(r.second) }.getOrDefault(JSONObject())
                        publies[cle(e.genre, e.ref)] = Publie(o.optString("id"), o.optString("url"), horloge())
                        if (e.temporaire) File(e.fichier).delete()
                        note("envoyé : " + e.titre + if (o.optBoolean("deja")) " (déjà en ligne)" else "")
                        n++
                    }
                    // The token refused: everything stops (the item kept) until « Tester » passes again.
                    r.first == 401 -> {
                        file.add(0, e.copy(essais = e.essais + 1, prochainMs = horloge() + attente(e.essais + 1), erreur = "HTTP 401"))
                        stockage.edit().putBoolean(CLE_PRET, false).apply()
                        note("jeton refusé par le site : envoi arrêté — retester la connexion")
                        ecritDisque()
                        return n
                    }
                    // Refused for good (kind, size, format, an address that redirects): kept out of the queue, said on the card.
                    (r.first in 300..499 && r.first != 408 && r.first != 429) -> {
                        if (e.temporaire) File(e.fichier).delete()
                        note("refusé (" + r.first + ") : " + e.titre + " — " + if (r.first < 400) "redirection : vérifier l'adresse" else raison(r.second))
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

    /** The v1 meta of [e]: SatMe's words, its kind, id, key and file. */
    fun meta(e: Envoi, nom: String, f: File): JSONObject = JSONObject(e.meta.toString()).put("version", VERSION_API).put("type", e.genre.nom)
        .put("id", e.id).put("cle", e.genre.prefixe + ":" + e.ref).put("titre", e.titre)
        .put("fichier", JSONObject().put("nom", nom).put("type", e.type).put("taille", f.length()).put("sha256", sha256(f)))

    private fun envoie(e: Envoi): Pair<Int, String> {
        val f = File(e.fichier)
        if (!f.isFile) return 410 to "{\"erreur\":\"fichier disparu\"}"
        val ext = extension(e.type, e.ref)
        val nom = DossierSitePc.sur(e.ref.substringAfterLast('/').substringBeforeLast('.')) + "." + ext
        val meta = meta(e, nom, f)
        if (cible == Cible.DOSSIER) return ecrivain.ecrit(e.genre.nom, e.id, meta, ext, f)
        val (type, corps) = Corps.multipart(e.genre.nom, meta, nom, e.type, if (e.temporaire || f.length() < 32L * 1024 * 1024) f.readBytes() else f)
        return transport.envoie("POST", adresse(e.genre.nom), entetes(e.genre.nom, e.id, type), corps)
    }

    /**
     * « Tester »: the site answers a `ping` (and takes the token, if any), or the folder can be written. Only then is it
     * [pret] (a failure leaves it not ready: nothing goes until a test passes).
     */
    fun essai(): String {
        if (!actif) return MODULE_COUPE
        if (cible == Cible.DOSSIER) {
            if (demo()) return "démonstration : pas d'écriture dans un dossier"
            if (chemin.isEmpty()) return "choisir le dossier"
            val err = ecrivain.essai()
            stockage.edit().putBoolean(CLE_PRET, err == null).apply()
            return if (err == null) "ok : dossier prêt ($chemin)" else err
        }
        if (url.isEmpty()) return "renseigner l'adresse du site"
        refusHttp(url)?.let { return it }
        refusDemo()?.let { return it }
        val ping = JSONObject().put("type", "ping").put("version", VERSION_API).put("logiciel", agent)
            .put("types", JSONArray(Genre.values().filter { mode(it) != Mode.JAMAIS }.map { it.nom }))
        val r = runCatching { transport.envoie("POST", adresse("ping"), entetes("ping", UUID.randomUUID().toString(), JSON), Corps.json(ping)) }
            .getOrElse { stockage.edit().putBoolean(CLE_PRET, false).apply(); return "pas de réponse : " + (it.message ?: it.javaClass.simpleName) }
        stockage.edit().putBoolean(CLE_PRET, r.first in 200..299).apply()
        return when (r.first) {
            in 200..299 -> runCatching { JSONObject(r.second) }.getOrNull()?.let { o ->
                // The kinds the site does not take now.
                val refuses = o.optJSONArray("refuses")?.let { a -> (0 until a.length()).mapNotNull { Genre.de(a.optString(it))?.libelle } }.orEmpty()
                val qui = listOf(o.optString("indicatif"), o.optString("nom"), o.optString("site")).filter { it.isNotBlank() }
                "ok : " + (if (qui.isEmpty()) "le site a répondu" else "site de " + qui[0] + if (qui.size > 1) " (" + qui[1] + ")" else "") +
                    if (refuses.isEmpty()) "" else " — refusés par le site : " + refuses.joinToString(", ")
            } ?: "ok : le site a répondu (HTTP ${r.first})"
            401 -> "jeton refusé par le site"
            403 -> "le site refuse la publication (jeton non réglé côté site ?)"
            in 300..399 -> "HTTP ${r.first} : le site redirige — mettre l'adresse finale (https://…)"
            else -> "HTTP " + r.first + " : " + raison(r.second)
        }
    }

    /**
     * One live request of [DirectSitePc]: [type] (`direct`, `direct-spectre`, `direct-datv-image`, `direct-sstv-image`, `direct-station`,
     * `direct-efface`), JSON [o] or a JPEG [jpeg]. Its answer, or null when the target is not set or the demonstration
     * forbids it. Never queued: live data that does not go is dropped.
     */
    fun envoieDirect(type: String, o: JSONObject?, jpeg: ByteArray? = null): Pair<Int, String>? {
        if (refus() != null) return null
        if (cible == Cible.DOSSIER) return ecrivain.direct(type, o, jpeg)
        val id = UUID.randomUUID().toString()
        if (jpeg != null) {
            val meta = JSONObject().put("version", VERSION_API).put("type", type).put("id", id).put("t", horloge())
                .put("fichier", JSONObject().put("nom", "image.jpg").put("type", "image/jpeg").put("taille", jpeg.size))
            val (ct, corps) = Corps.multipart(type, meta, "image.jpg", "image/jpeg", jpeg)
            return transport.envoie("POST", adresse(type), entetes(type, id, ct), corps)
        }
        val corps = JSONObject().put("type", type).put("version", VERSION_API)
        o?.keySet()?.forEach { k -> corps.put(k, o.get(k)) }
        return transport.envoie("POST", adresse(type), entetes(type, id, JSON), Corps.json(corps))
    }

    /**
     * One file sent at once, outside the queue (an award received, `diplome-image`: the owner's click): [octets] of type
     * [mime] named [nom], with [meta] (the award's id). Its answer, or null when the target is not set.
     */
    fun envoieDocument(type: String, meta: JSONObject, nom: String, mime: String, ext: String, octets: ByteArray): Pair<Int, String>? {
        if (refus() != null) return null
        if (cible == Cible.DOSSIER) return ecrivain.diplome(meta.optString("diplome"), ext, octets)
        val id = UUID.randomUUID().toString()
        val m = JSONObject(meta.toString()).put("version", VERSION_API).put("type", type).put("id", id).put("t", horloge())
            .put("fichier", JSONObject().put("nom", nom).put("type", mime).put("taille", octets.size))
        val (ct, corps) = Corps.multipart(type, m, nom, mime, octets)
        return transport.envoie("POST", adresse(type), entetes(type, id, ct), corps)
    }

    /** Withdraws [ref] from the site (`retrait`). */
    fun retire(g: Genre, ref: String): String? {
        chargeDisque()
        refus()?.let { return it }
        val p = publies[cle(g, ref)]
        val cleItem = g.prefixe + ":" + ref
        val r = if (cible == Cible.DOSSIER) ecrivain.retire(g.nom, cleItem) else {
            val o = JSONObject().put("type", "retrait").put("version", VERSION_API).put("typeRetire", g.nom).put("cle", cleItem).put("id", p?.id.orEmpty())
            runCatching { transport.envoie("POST", adresse("retrait"), entetes("retrait", UUID.randomUUID().toString(), JSON), Corps.json(o)) }.getOrElse { return "pas de réponse" }
        }
        if (r.first !in 200..299 && r.first != 404) return "HTTP " + r.first + " : " + raison(r.second)
        publies.remove(cle(g, ref)); ecritDisque()
        return null
    }

    // ------------------------------------------------------------------ what happens in SatMe-PC

    @Volatile private var planchesT = 0L

    /** A picture received live: sent if « Automatique »; the planches (and reports) after a quiet couple of minutes. */
    fun nouvelleImage(nom: String) {
        if (!actif) return
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
        val o = JSONObject().put("ok", true).put("module", actif).put("cible", cible.nom).put("url", url).put("dossier", chemin)
            .put("modele", TYPE in url).put("jeton", jeton.isNotEmpty())
            .put("pret", pret).put("configure", configure).put("demo", demo())
            .put("modes", JSONObject(Genre.values().associate { it.nom to mode(it).nom }))
            .put("libelles", JSONObject(Genre.values().associate { it.nom to it.libelle }))
            .put("publies", publies.size)
        direct?.let { o.put("direct", it.json()) }
        synchronized(this) {
            o.put("file", JSONArray(file.map { JSONObject().put("titre", it.titre).put("genre", it.genre.nom).put("essais", it.essais).put("prochain", it.prochainMs).put("erreur", it.erreur) }))
        }
        synchronized(historique) { o.put("historique", JSONArray(historique.map { JSONObject().put("t", it.first).put("texte", it.second) })) }
        return o
    }

    /** The settings sent with `reglages` or `essai`: the target, the address, the folder, the token. */
    private fun regleCible(p: (String) -> String) {
        Cible.de(p("cible"))?.let { cible = it }
        p("url").takeIf { it.isNotEmpty() || p("efface") == "1" }?.let { url = it }
        p("dossier").takeIf { it.isNotEmpty() || p("efface") == "1" }?.let { chemin = it }
        p("jeton").takeIf { it.isNotEmpty() }?.let { jeton = it }
        if (p("efface") == "1" || p("sansJeton") == "1") jeton = ""
    }

    /** `api/site…`: the settings and the queue (GET), set, test, publish, withdraw (POST). */
    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val route = chemin.removePrefix("/api/site")
        fun rep(o: JSONObject) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8), "Cache-Control: no-store\r\n")
        fun dit(err: String?, ok: String) = rep(if (err == null) JSONObject().put("ok", true).put("message", ok) else JSONObject().put("ok", false).put("erreur", err))
        if (methode != "POST") return if (route == "" || route == "/") rep(json()) else rep(JSONObject().put("ok", false).put("erreur", "inconnu"))
        // Switched off in Réglages › Modules: nothing set, tested or sent from here.
        if (!actif) return rep(JSONObject().put("ok", false).put("module", ModulesStation.SITE).put("erreur", MODULE_COUPE))
        return when (route) {
            "/reglages" -> {
                regleCible(p)
                Genre.values().forEach { g -> Mode.de(p("mode_" + g.nom))?.let { regleMode(g, it) } }
                reveille()
                rep(json())
            }
            // The assistant's « Tester »: the target typed (when given) kept, then tried; ready only if it passes.
            "/essai" -> {
                regleCible(p)
                val r = essai()
                rep(JSONObject().put("ok", r.startsWith("ok")).put("message", r).put("etat", json()))
            }
            "/publie" -> {
                val g = Genre.de(p("type")) ?: return rep(JSONObject().put("ok", false).put("erreur", "type inconnu"))
                dit(publie(g, p("ref"), true), if (cible == Cible.DOSSIER) "dans la file d'écriture vers le dossier" else "dans la file d'envoi vers le site")
            }
            "/retire" -> {
                val g = Genre.de(p("type")) ?: return rep(JSONObject().put("ok", false).put("erreur", "type inconnu"))
                dit(retire(g, p("ref")), "retiré du site")
            }
            "/direct" -> { direct?.regle(p); rep(json()) }
            "/vide" -> { synchronized(this) { file.forEach { if (it.temporaire) File(it.fichier).delete() }; file.clear(); ecritDisque() }; rep(json()) }
            "/maintenant" -> { synchronized(this) { for (i in file.indices) file[i] = file[i].copy(prochainMs = 0L) }; reveille(); rep(json()) }
            else -> rep(JSONObject().put("ok", false).put("erreur", "inconnu"))
        }
    }

    companion object {
        const val MAX_FILE = 300
        const val APRES_IMAGE_MS = 120_000L
        /** The wire format's version (`X-SatMe-Version`, `version` in every meta). */
        const val VERSION_API = 1
        const val TYPE = "{type}"
        const val JSON = "application/json; charset=utf-8"
        const val CLE_PRET = "site_pret"
        const val CLE_CIBLE = "site_cible"
        const val CLE_DOSSIER = "site_dossier"
        /** 1: the address is the whole receiving address (`{type}` or one address), no longer a root. */
        const val CLE_FORMAT = "site_format"
        private const val OU = "Réglages › Services en ligne › Envoyer vers un site web"
        const val MODULE_COUPE = "Module « Publication web » désactivé — l'activer dans Réglages › Modules"

        /** An address and a token are set (before any test): the station's own site, kept on by [ModulesStation.migre]. */
        fun regle(st: Stockage): Boolean = !st.getString("site_url", "").isNullOrEmpty() && !st.getString("site_jeton", "").isNullOrEmpty()

        /** 30 s, 1 min, 2 min, 4 min… an hour at most. */
        fun attente(essais: Int): Long = (30_000L shl (essais - 1).coerceIn(0, 7)).coerceAtMost(3_600_000L)

        /** « mon-site.fr/recevoir.php », « https://mon-site.fr/api/{type}/ » → with `https://`, without the last `/`. */
        fun normalise(u: String): String {
            val t = u.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }

        private fun hote(u: String): String? = runCatching { URI(u.replace(TYPE, "ping")).host }.getOrNull()?.lowercase()?.removePrefix("[")?.removeSuffix("]")

        /** A site on this machine (the demonstration's local test). */
        fun local(u: String): Boolean = hote(u)?.let { it == "127.0.0.1" || it == "localhost" || it == "::1" } == true

        /** This machine or the local network: loopback, private IPv4 ranges, `.local`/`.lan`/`.home.arpa`, a bare name, IPv6 ULA and link-local. */
        fun reseauLocal(u: String): Boolean {
            val h = hote(u) ?: return false
            if (h == "localhost" || h == "::1" || !h.contains('.') && !h.contains(':')) return true
            if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            val ip = h.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && h.count { c -> c == '.' } == 3 }
            if (ip != null) return ip[0] == 127 || ip[0] == 10 || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 169 && ip[1] == 254)
            return h.contains(':') && (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80"))
        }

        /** Plain `http://` only towards this machine or the local network. */
        fun refusHttp(u: String): String? = if (u.startsWith("http://") && !reseauLocal(u))
            "http:// seulement vers cette machine ou le réseau local : utiliser https://" else null

        /** The file's extension for [mime] (else [ref]'s). */
        fun extension(mime: String, ref: String): String = when (mime.substringBefore(';').trim().lowercase()) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/gif" -> "gif"; "application/pdf" -> "pdf"; "video/mp4" -> "mp4"; "video/webm" -> "webm"
            "text/plain" -> "adi"; "application/json" -> "json"; "text/html" -> "html"
            else -> ref.substringAfterLast('.', "bin").let(DossierSitePc::sur)
        }

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun raison(t: String): String = runCatching { JSONObject(t).optString("erreur") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: t.take(160)
    }
}
