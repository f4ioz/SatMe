/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.publication

import fr.f4ioz.site.securite.Jetons
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What SatMe publishes: the type's name in the API, its files and its size at most. */
enum class TypePublication(val nom: String, val libelle: String, val genres: Set<String>, val maxOctets: Long) {
    SSTV("sstv-image", "Image SSTV", setOf("png", "jpg", "gif"), 12L shl 20),
    PLANCHE("planche", "Planche", setOf("png", "jpg"), 30L shl 20),
    RAPPORT("rapport", "Rapport", setOf("pdf", "html"), 40L shl 20),
    QSO("qso", "QSO", setOf("adif", "json"), 1L shl 20),
    VIDEO("journal-video", "Vidéo du journal", setOf("mp4", "webm"), 200L shl 20);

    val image: Boolean get() = this == SSTV || this == PLANCHE

    companion object {
        fun de(nom: String): TypePublication? = values().firstOrNull { it.nom == nom }
    }
}

/**
 * One thing published: its id (the start of its content's SHA-256), its type,
 * its file, what SatMe said of it ([meta]: satellite, UTC time, frequency,
 * mode, pass, campaign, station, title…), when it came, hidden or not.
 * [cle]: SatMe's own name for it (a planche's model) — a new version
 * replaces the old one.
 */
data class Publication(
    val id: String,
    val type: TypePublication,
    val sha256: String,
    val fichier: String,
    val typeMime: String,
    val taille: Long,
    val recuMs: Long,
    val meta: JSONObject,
    val cle: String = "",
    val cache: Boolean = false,
    val vignette: Boolean = false,
    val largeur: Int = 0,
    val hauteur: Int = 0,
) {
    fun texte(k: String): String = meta.optString(k, "").trim().take(300)

    /** When it happened (the reception, the contact), else when it came. */
    val quandMs: Long get() = meta.optLong("utc", 0L).takeIf { it > 0 } ?: meta.optString("utc").let { parseIso(it) } ?: recuMs

    val satellite: String get() = texte("satellite")
    val titre: String get() = texte("titre").ifEmpty { listOf(type.libelle, satellite).filter { it.isNotEmpty() }.joinToString(" · ") }
    /** The event, the campaign or the satellite it belongs with in the galleries. */
    val groupe: String get() = texte("evenement").ifEmpty { texte("campagne") }.ifEmpty { satellite }.ifEmpty { "Divers" }

    fun json(): JSONObject = JSONObject().put("id", id).put("type", type.nom).put("sha256", sha256).put("fichier", fichier)
        .put("typeMime", typeMime).put("taille", taille).put("recuMs", recuMs).put("meta", meta).put("cle", cle)
        .put("cache", cache).put("vignette", vignette).put("largeur", largeur).put("hauteur", hauteur)

    companion object {
        fun parseIso(s: String): Long? = if (s.isBlank()) null else runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()

        fun depuis(o: JSONObject): Publication? {
            val t = TypePublication.de(o.optString("type")) ?: return null
            val id = o.optString("id").takeIf { Publications.ID.matches(it) } ?: return null
            return Publication(id, t, o.optString("sha256"), o.optString("fichier"), o.optString("typeMime"), o.optLong("taille"),
                o.optLong("recuMs"), o.optJSONObject("meta") ?: JSONObject(), o.optString("cle"), o.optBoolean("cache"),
                o.optBoolean("vignette"), o.optInt("largeur"), o.optInt("hauteur"))
        }
    }
}

/**
 * What SatMe published, on disk under `publications/<id>/` (the file, its
 * thumbnail, `meta.json`), all of it in memory too (a few thousand
 * entries). An id is the start of the content's SHA-256: the same picture
 * sent twice is one publication (idempotent); a new version of something
 * with a [Publication.cle] replaces the old. Files are named by the site,
 * never by the sender.
 */
class Publications(private val dossier: File, private val horloge: () -> Long = System::currentTimeMillis) {

    class Refus(val statut: Int, message: String) : RuntimeException(message)

    /** What [publie] did: [nouvelle] false when the same content was already there. */
    data class Resultat(val p: Publication, val nouvelle: Boolean, val remplacee: String? = null)

    private val parId = ConcurrentHashMap<String, Publication>()

    fun charge() {
        dossier.mkdirs()
        parId.clear()
        dossier.listFiles { f -> f.isDirectory && ID.matches(f.name) }?.forEach { d ->
            runCatching { Publication.depuis(JSONObject(File(d, "meta.json").readText())) }.getOrNull()?.let { parId[it.id] = it }
        }
    }

    /** The kinds shown to the public now (« Admin › Paramètres »); a hidden kind is kept, only not shown. */
    @Volatile var typePublic: (TypePublication) -> Boolean = { true }

    fun toutes(): List<Publication> = parId.values.sortedByDescending { it.quandMs }
    fun visibles(type: TypePublication? = null): List<Publication> = toutes().filter { !it.cache && typePublic(it.type) && (type == null || it.type == type) }
    /** [id] when the public may see it (not hidden, nor its kind). */
    fun publique(id: String): Publication? = parId(id)?.takeIf { !it.cache && typePublic(it.type) }
    fun parId(id: String): Publication? = if (ID.matches(id)) parId[id] else null

    /** The folder of [p]; its files only by the names the site gave them. */
    fun dossierDe(id: String): File? = if (ID.matches(id)) File(dossier, id) else null
    fun fichierDe(p: Publication): File = File(File(dossier, p.id), p.fichier)
    fun vignetteDe(p: Publication): File? = if (p.vignette) File(File(dossier, p.id), "vignette.jpg").takeIf { it.isFile } else null

    /**
     * Publishes [octets] as [type] with [meta] ([Refus] when the content is not of the type, too
     * large, or a picture that cannot be read). The same content again: the one already there.
     */
    @Synchronized fun publie(type: TypePublication, octets: ByteArray, meta: JSONObject, typeAnnonce: String = ""): Resultat {
        if (octets.isEmpty()) throw Refus(400, "contenu vide")
        if (octets.size > type.maxOctets) throw Refus(413, "trop gros pour ${type.nom} (max ${type.maxOctets shr 20} Mo)")
        val genre = genre(type, octets, typeAnnonce) ?: throw Refus(415, "contenu non reconnu pour ${type.nom} (attendu : ${type.genres.joinToString()})")
        val sha = Jetons.sha256Hex(octets)
        existant(type, sha)?.let { return Resultat(it, false) }
        var dims: Pair<Int, Int>? = null
        if (type.image) {
            dims = Vignettes.dimensions(octets)
            if (dims == null || !Vignettes.imageAcceptable(octets)) throw Refus(415, "image illisible ou trop grande")
        }
        val id = sha.take(16)
        val d = File(dossier, id); d.mkdirs()
        val nom = "fichier.$genre"
        File(d, nom).writeBytes(octets)
        val vig = if (type.image) Vignettes.vignette(octets)?.also { File(d, "vignette.jpg").writeBytes(it) } != null else false
        val p = Publication(id, type, sha, nom, MIME[genre] ?: "application/octet-stream", octets.size.toLong(), horloge(), nettoie(meta),
            cleDe(meta), false, vig, dims?.first ?: 0, dims?.second ?: 0)
        return enregistre(p)
    }

    /** A large file already written by the request ([tmp], its SHA-256 and size): a journal's video. */
    @Synchronized fun publieFichier(type: TypePublication, tmp: File, sha: String, taille: Long, meta: JSONObject): Resultat {
        try {
            if (taille <= 0) throw Refus(400, "contenu vide")
            val genre = Vignettes.genre(Vignettes.debut(tmp))?.takeIf { it in type.genres } ?: throw Refus(415, "contenu non reconnu pour ${type.nom}")
            existant(type, sha)?.let { return Resultat(it, false) }
            val id = sha.take(16)
            val d = File(dossier, id); d.mkdirs()
            val nom = "fichier.$genre"
            val f = File(d, nom)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true) }
            val p = Publication(id, type, sha, nom, MIME[genre] ?: "application/octet-stream", taille, horloge(), nettoie(meta), cleDe(meta))
            return enregistre(p)
        } finally { tmp.delete() }
    }

    private fun existant(type: TypePublication, sha: String): Publication? = parId[sha.take(16)]?.takeIf { it.sha256 == sha && it.type == type }

    private fun enregistre(p: Publication): Resultat {
        // The same key, an older version: replaced.
        val ancien = if (p.cle.isNotEmpty()) parId.values.firstOrNull { it.type == p.type && it.cle == p.cle && it.id != p.id } else null
        File(File(dossier, p.id), "meta.json").writeText(p.json().toString(2))
        parId[p.id] = p
        ancien?.let { supprime(it.id) }
        return Resultat(p, true, ancien?.id)
    }

    @Synchronized fun supprime(id: String): Boolean {
        val p = parId(id) ?: return false
        parId.remove(p.id)
        File(dossier, p.id).deleteRecursively()
        return true
    }

    /** The one published under SatMe's [cle] for [type]. */
    fun parCle(type: TypePublication, cle: String): Publication? = parId.values.firstOrNull { it.type == type && it.cle == cle }

    @Synchronized fun cache(id: String, cache: Boolean): Boolean {
        val p = parId(id) ?: return false
        val n = p.copy(cache = cache)
        File(File(dossier, p.id), "meta.json").writeText(n.json().toString(2))
        parId[p.id] = n
        return true
    }

    private fun genre(type: TypePublication, b: ByteArray, annonce: String): String? = when (type) {
        TypePublication.QSO -> {
            val t = String(b, Charsets.UTF_8).trimStart()
            when {
                t.startsWith("{") || t.startsWith("[") -> "json"
                t.contains("<eor>", true) || t.contains("<call:", true) -> "adif"
                else -> null
            }
        }
        TypePublication.RAPPORT -> Vignettes.genre(b)?.takeIf { it == "pdf" }
            ?: (if (annonce.startsWith("text/html") || String(b, 0, minOf(b.size, 512), Charsets.UTF_8).trimStart().let { it.startsWith("<!", true) || it.startsWith("<html", true) }) "html" else null)
        else -> Vignettes.genre(b)?.takeIf { it in type.genres }
    }

    /** What is kept of the sender's words: strings, numbers and booleans, short, 40 keys at most; nothing nested. */
    private fun nettoie(m: JSONObject): JSONObject {
        val o = JSONObject()
        for (k in m.keySet().take(40)) {
            if (!CLE_META.matches(k)) continue
            when (val v = m.opt(k)) {
                is String -> o.put(k, v.take(500))
                is Number, is Boolean -> o.put(k, v)
            }
        }
        return o
    }

    private fun cleDe(m: JSONObject): String = m.optString("cle").trim().take(120).takeIf { CLE.matches(it) }.orEmpty()

    companion object {
        val ID = Regex("^[0-9a-f]{16}$")
        private val CLE_META = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")
        private val CLE = Regex("^[A-Za-z0-9 ._:/-]{1,120}$")
        val MIME = mapOf("png" to "image/png", "jpg" to "image/jpeg", "gif" to "image/gif", "pdf" to "application/pdf",
            "html" to "text/html; charset=utf-8", "mp4" to "video/mp4", "webm" to "video/webm",
            "adif" to "text/plain; charset=utf-8", "json" to "application/json; charset=utf-8")
    }
}
