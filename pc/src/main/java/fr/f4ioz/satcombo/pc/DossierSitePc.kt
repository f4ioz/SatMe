/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset

/**
 * « Publication web » towards a folder ([SitePc.Cible.DOSSIER]): instead of
 * a web address, SatMe-PC writes what it publishes into a local folder, for a
 * static site (Hugo, Jekyll, plain HTML) or a sync tool the owner already has
 * (Nextcloud, Syncthing, an rsync or FTP mirror — SatMe-PC never does FTP
 * itself). The layout is stable, the same words as the web API's `meta`:
 *
 * - `<dossier>/<type>/<AAAA>/<id>.<ext>` the file, `<id>.json` its meta;
 * - `index.json`: the last [MAX_INDEX] items, newest first;
 * - `flux.xml`: an Atom feed of the last [MAX_FLUX];
 * - `direct/direct.json`, `direct/station.json`, `direct/datv.jpg`, `direct/sstv.jpg`: the live parts, overwritten in place.
 *
 * Every file is written beside its place then renamed (atomic where the
 * system allows): a reader never sees half a file, and the index never names
 * a file not yet there.
 */
class DossierSitePc(private val racine: () -> File, private val horloge: () -> Long = System::currentTimeMillis) {

    /**
     * Writes [source] (the item's file) as [type] under [id], with [meta] (the API's v1 meta): 201 and
     * `{"id","chemin"}`, or 0 and why (the queue tries again later). The same [cle] already there: replaced.
     */
    @Synchronized fun ecrit(type: String, id: String, meta: JSONObject, ext: String, source: File): Pair<Int, String> = try {
        val r = racine()
        val annee = annee(meta.optString("utc"))
        val nomId = sur(id)
        val rel = sur(type) + "/" + annee + "/" + nomId
        val d = File(r, sur(type) + "/" + annee); d.mkdirs()
        val chemin = "$rel.${sur(ext)}"
        copieAtomique(source, File(r, chemin))
        val m = JSONObject(meta.toString())
        m.optJSONObject("fichier")?.put("chemin", chemin)
        m.put("chemin", chemin).put("ajoute", iso(horloge()))
        ecritAtomique(File(r, "$rel.json"), m.toString(2).toByteArray(Charsets.UTF_8))
        // The index: this one first, an older version of the same item (its key) or the same id gone, files and all.
        val avant = index(r)
        val garde = ArrayList<JSONObject>()
        for (e in avant) {
            val meme = e.optString("id") == id || (m.optString("cle").isNotEmpty() && e.optString("cle") == m.optString("cle") && e.optString("type") == type)
            if (meme) { if (e.optString("id") != id) efface(r, e) } else garde += e
        }
        val liste = (listOf(m) + garde).take(MAX_INDEX)
        ecritIndex(r, liste)
        201 to JSONObject().put("ok", true).put("id", id).put("url", chemin).toString()
    } catch (e: Exception) { 0 to ("dossier : " + (e.message ?: e.javaClass.simpleName)) }

    /** Withdraws the item of [type] under [cle] (its files and its line). */
    @Synchronized fun retire(type: String, cle: String): Pair<Int, String> = try {
        val r = racine()
        val l = index(r)
        val (partis, restent) = l.partition { it.optString("type") == type && it.optString("cle") == cle }
        partis.forEach { efface(r, it) }
        if (partis.isEmpty()) 404 to "{\"ok\":false,\"erreur\":\"inconnu\"}" else { ecritIndex(r, restent); 200 to "{\"ok\":true}" }
    } catch (e: Exception) { 0 to ("dossier : " + (e.message ?: e.javaClass.simpleName)) }

    private val direct = JSONObject()

    /** The live parts: `direct.json` (merged, then overwritten), the DATV and SSTV pictures; no spectrum to a folder. */
    @Synchronized fun direct(type: String, o: JSONObject?, jpeg: ByteArray?): Pair<Int, String> = try {
        val d = File(racine(), "direct")
        when (type) {
            "direct" -> {
                o?.keySet()?.filter { it != "type" }?.forEach { k -> direct.put(k, o.get(k)) }
                d.mkdirs(); ecritAtomique(File(d, "direct.json"), direct.toString(2).toByteArray(Charsets.UTF_8)); 200 to "{\"ok\":true}"
            }
            "direct-datv-image", "direct-sstv-image" -> {
                if (jpeg == null) 400 to "{\"ok\":false,\"erreur\":\"image manquante\"}"
                else { d.mkdirs(); ecritAtomique(File(d, type.removePrefix("direct-").removeSuffix("-image") + ".jpg"), jpeg); 200 to "{\"ok\":true}" }
            }
            "favoris" -> {
                val f = JSONObject().put("version", SitePc.VERSION_API).put("type", "favoris")
                o?.keySet()?.filter { it != "type" && it != "version" }?.forEach { k -> f.put(k, o.get(k)) }
                racine().mkdirs(); ecritAtomique(File(racine(), "favoris.json"), f.toString(2).toByteArray(Charsets.UTF_8)); 200 to "{\"ok\":true}"
            }
            "diplomes" -> {
                val f = JSONObject().put("version", SitePc.VERSION_API).put("type", "diplomes")
                o?.keySet()?.filter { it != "type" && it != "version" }?.forEach { k -> f.put(k, o.get(k)) }
                racine().mkdirs(); ecritAtomique(File(racine(), "diplomes.json"), f.toString(2).toByteArray(Charsets.UTF_8)); 200 to "{\"ok\":true}"
            }
            "direct-station" -> {
                val f = JSONObject()
                o?.keySet()?.filter { it != "type" && it != "version" }?.forEach { k -> f.put(k, o.get(k)) }
                d.mkdirs(); ecritAtomique(File(d, "station.json"), f.toString(2).toByteArray(Charsets.UTF_8)); 200 to "{\"ok\":true}"
            }
            "direct-efface" -> { direct.keySet().toList().forEach { direct.remove(it) }; d.deleteRecursively(); 200 to "{\"ok\":true}" }
            else -> 415 to "{\"ok\":false,\"erreur\":\"pas de spectre vers un dossier\"}"
        }
    } catch (e: Exception) { 0 to ("dossier : " + (e.message ?: e.javaClass.simpleName)) }

    /** An award's picture or PDF (`diplome-image`): `diplomes/<id>.<ext>`, replacing the one before. */
    @Synchronized fun diplome(id: String, ext: String, octets: ByteArray): Pair<Int, String> = try {
        val d = File(racine(), "diplomes")
        d.mkdirs()
        val base = sur(id.replace(':', '-'))
        listOf("png", "jpg", "pdf").filter { it != ext }.forEach { File(d, "$base.$it").delete() }
        ecritAtomique(File(d, "$base.$ext"), octets)
        201 to JSONObject().put("ok", true).put("url", "diplomes/$base.$ext").toString()
    } catch (e: Exception) { 0 to ("dossier : " + (e.message ?: e.javaClass.simpleName)) }

    /** « Tester »: the folder made if needed, then a file written and removed. Null when it works, else why. */
    fun essai(): String? {
        val r = racine()
        if (!r.isAbsolute) return "un chemin complet est attendu (par exemple /home/moi/site/satme ou C:\\Users\\moi\\Nextcloud\\satme)"
        return try {
            r.mkdirs()
            if (!r.isDirectory) return "impossible de créer le dossier"
            val f = File(r, ".satme-essai-" + horloge())
            ecritAtomique(f, "essai".toByteArray()); f.delete()
            null
        } catch (e: Exception) { "écriture impossible : " + (e.message ?: e.javaClass.simpleName) }
    }

    // ------------------------------------------------------------------ files

    private fun index(r: File): List<JSONObject> = runCatching {
        val a = JSONObject(File(r, "index.json").readText()).optJSONArray("elements") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun ecritIndex(r: File, l: List<JSONObject>) {
        val o = JSONObject().put("version", SitePc.VERSION_API).put("logiciel", "SatMe-PC").put("maj", iso(horloge())).put("elements", JSONArray(l))
        ecritAtomique(File(r, "index.json"), o.toString(2).toByteArray(Charsets.UTF_8))
        ecritAtomique(File(r, "flux.xml"), atom(l.take(MAX_FLUX)).toByteArray(Charsets.UTF_8))
    }

    private fun efface(r: File, e: JSONObject) {
        val c = e.optString("chemin")
        if (c.isEmpty() || c.contains("..")) return
        File(r, c).delete()
        File(r, c.substringBeforeLast('.') + ".json").delete()
    }

    private fun atom(l: List<JSONObject>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<feed xmlns=\"http://www.w3.org/2005/Atom\">\n")
        sb.append("  <title>Publications SatMe-PC</title>\n  <id>urn:satme-pc:publications</id>\n")
        sb.append("  <updated>").append(l.firstOrNull()?.optString("ajoute")?.takeIf { it.isNotEmpty() } ?: iso(horloge())).append("</updated>\n")
        sb.append("  <generator>SatMe-PC</generator>\n")
        for (e in l) {
            sb.append("  <entry>\n    <title>").append(xml(e.optString("titre").ifEmpty { e.optString("type") })).append("</title>\n")
            sb.append("    <id>urn:satme-pc:").append(xml(e.optString("id"))).append("</id>\n")
            sb.append("    <updated>").append(xml(e.optString("ajoute"))).append("</updated>\n")
            sb.append("    <link href=\"").append(xml(e.optString("chemin"))).append("\" type=\"").append(xml(e.optJSONObject("fichier")?.optString("type").orEmpty())).append("\"/>\n")
            sb.append("    <category term=\"").append(xml(e.optString("type"))).append("\"/>\n")
            val r = listOf(e.optString("satellite"), e.optString("mode"), e.optString("utc"), e.optString("evenement")).filter { it.isNotEmpty() }.joinToString(" · ")
            if (r.isNotEmpty()) sb.append("    <summary>").append(xml(r)).append("</summary>\n")
            sb.append("  </entry>\n")
        }
        return sb.append("</feed>\n").toString()
    }

    companion object {
        const val MAX_INDEX = 500
        const val MAX_FLUX = 50

        private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
        private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** A name for a path: letters, digits, `.`, `-`, `_` only. */
        fun sur(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').take(100).ifEmpty { "_" }

        /** The year of an ISO time, else this year. */
        fun annee(utc: String): String = runCatching { Instant.parse(utc).atZone(ZoneOffset.UTC).year.toString() }.getOrNull()
            ?: Instant.now().atZone(ZoneOffset.UTC).year.toString()

        /** [b] written beside [f], then renamed onto it. */
        fun ecritAtomique(f: File, b: ByteArray) {
            val tmp = File(f.parentFile, "." + f.name + ".tmp")
            tmp.writeBytes(b)
            remplace(tmp, f)
        }

        fun copieAtomique(source: File, f: File) {
            val tmp = File(f.parentFile, "." + f.name + ".tmp")
            Files.copy(source.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            remplace(tmp, f)
        }

        private fun remplace(tmp: File, f: File) {
            try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (e: AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}
