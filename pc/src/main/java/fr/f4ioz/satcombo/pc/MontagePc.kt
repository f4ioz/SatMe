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
import fr.f4ioz.satcombo.station.ActiviteMontage
import fr.f4ioz.satcombo.station.DispositionMontage
import fr.f4ioz.satcombo.station.Montage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The « Montage » page's routes: the station's chain kept in the data
 * folder (`montage.json`), the photos the owner adds to its pieces
 * (`montage-photos/`), the drawing laid out, lit by what the station does.
 *
 * **Display only**: no route here starts, stops or opens anything — the
 * activity is read from the same state the other tabs read.
 */
class MontagePc(dossier: File, private val lectures: () -> ActiviteMontage.Lectures) {

    private val fichier = File(dossier, "montage.json")
    private val photos = File(dossier, "montage-photos")

    @Volatile private var chaine: Montage.Chaine = runCatching { Montage.lit(fichier.readText()) }.getOrDefault(Montage.Chaine())

    private fun json(o: Any) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    private fun refus(t: String) = json(JSONObject().put("ok", false).put("erreur", t))
    private val NON = ReponseWeb("404 Not Found", "text/plain; charset=utf-8", "pas de photo".toByteArray())

    /** The photo of element [id] of the chain, if the owner gave one (tied to its model: another piece under the same id has none). */
    private fun photo(id: String): File? {
        val e = chaine.element(id) ?: return null
        return TYPES.keys.map { File(photos, "${e.id}-${e.modele}.$it") }.firstOrNull { it.isFile }
    }

    /** One request: [route] after `/api/montage`, [corps] read on demand (a chain, a picture). */
    fun sert(route: String, methode: String, p: (String) -> String, corps: (Long) -> ByteArray?): ReponseWeb = runCatching {
        when (route) {
            "", "/" -> if (methode == "POST") enregistre(corps(MAX_CHAINE)) else etat(p("simple") == "1", p("essai"))
            "/catalogue" -> json(Montage.catalogueJson())
            "/suggestion" -> {
                val s = ActiviteMontage.suggere(lectures())
                json(JSONObject().put("ok", true).put("chaine", Montage.ecrit(s.chaine)).put("pourquoi", JSONArray(s.pourquoi))
                    .put("plan", DispositionMontage.json(DispositionMontage.dispose(s.chaine))))
            }
            "/photo" -> {
                val id = p("id").takeIf { Montage.ID.matches(it) } ?: return@runCatching refus("quel appareil ?")
                if (methode != "POST") {
                    val f = photo(id) ?: return@runCatching NON
                    ReponseWeb("200 OK", TYPES[f.extension] ?: "application/octet-stream", f.readBytes(), "X-Content-Type-Options: nosniff\r\n")
                } else {
                    val e = chaine.element(id) ?: return@runCatching refus("appareil inconnu")
                    photos.mkdirs()
                    if (p("retire") == "1") { synchronized(this) { photo(id)?.delete() }; return@runCatching json(JSONObject().put("ok", true)) }
                    val b = corps(PHOTO_MAX) ?: return@runCatching refus("photo trop grosse (8 Mo au plus) ou coupée")
                    val ext = genre(b) ?: return@runCatching refus("seulement une image JPEG, PNG, WebP ou GIF")
                    synchronized(this) {
                        photo(id)?.delete()
                        File(photos, "${e.id}-${e.modele}.$ext").writeBytes(b)
                    }
                    json(JSONObject().put("ok", true))
                }
            }
            else -> NON
        }
    }.getOrElse { refus(it.message ?: "erreur") }

    private fun enregistre(b: ByteArray?): ReponseWeb {
        val texte = b?.toString(Charsets.UTF_8) ?: return refus("chaîne trop grosse")
        val c = Montage.lit(texte)
        synchronized(this) {
            chaine = c
            val tmp = File(fichier.parentFile, fichier.name + ".tmp")
            tmp.writeText(Montage.ecrit(c).toString(2))
            if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
            // The photos of pieces no longer there go.
            val gardees = c.elements.map { "${it.id}-${it.modele}" }.toSet()
            photos.listFiles()?.forEach { f -> if (f.nameWithoutExtension !in gardees) f.delete() }
        }
        return json(JSONObject().put("ok", true).put("chaine", Montage.ecrit(c)))
    }

    /**
     * The chain, its drawing and what is lit now. [simple]: only what takes
     * part in what happens (the whole chain when nothing does). [essai]: a
     * pretend activity for the demonstration, said so on the page.
     */
    private fun etat(simple: Boolean, essai: String): ReponseWeb {
        val c = chaine
        val a = if (essai == "rx" || essai == "tx") ActiviteMontage.essai(essai) else ActiviteMontage.depuis(lectures())
        val al = ActiviteMontage.allume(c, a)
        val garde = if (simple) al.enOeuvre().takeIf { it.isNotEmpty() } else null
        return json(JSONObject().put("ok", true).put("chaine", Montage.ecrit(c))
            .put("plan", DispositionMontage.json(DispositionMontage.dispose(c, garde)))
            .put("etat", ActiviteMontage.json(a, al))
            .put("simple", simple).put("simpleVide", simple && garde == null)
            .put("essai", if (essai == "rx" || essai == "tx") essai else "")
            .put("photos", JSONArray(c.elements.filter { photo(it.id) != null }.map { it.id })))
    }

    companion object {
        const val MAX_CHAINE = 256L * 1024
        const val PHOTO_MAX = 8L * 1024 * 1024
        private val TYPES = mapOf("jpg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif")

        /** The picture's kind from its first bytes (never from a name sent): null when it is not one. */
        fun genre(b: ByteArray): String? = when {
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
            b.size > 8 && b[0] == 0x89.toByte() && String(b, 1, 3, Charsets.ISO_8859_1) == "PNG" -> "png"
            b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
            b.size > 6 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "gif"
            else -> null
        }
    }
}
