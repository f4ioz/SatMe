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
import fr.f4ioz.satcombo.station.FonctionsMontage
import fr.f4ioz.satcombo.station.Montage
import fr.f4ioz.satcombo.station.ProfilsFonction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The « Montage » page's routes: the station's chain kept in the data
 * folder (`montage.json`), the photos the owner adds to its pieces
 * (`montage-photos/`), the drawing laid out, lit by what the station does.
 *
 * The drawing starts, stops or opens nothing — the activity is read from
 * the same state the other tabs read. Beside it, SatMe's functions: who
 * holds which board or sound input, one function's chain, the receive
 * gestures (`/action`) and the profiles (`/profils`), all done by
 * [executant] — receive settings only, refused while a transmission is on.
 */
class MontagePc(dossier: File, private val lectures: () -> ActiviteMontage.Lectures,
                private val instantane: ((ActiviteMontage.Lectures) -> FonctionsMontage.Instantane)? = null,
                private val executant: ProfilsFonction.Executant? = null,
                private val horloge: () -> Long = System::currentTimeMillis) {

    private val fichier = File(dossier, "montage.json")
    private val photos = File(dossier, "montage-photos")
    private val fichierProfils = File(dossier, "montage-profils.json")
    @Volatile private var profils: List<ProfilsFonction.Profil> = runCatching { ProfilsFonction.lit(fichierProfils.readText()) }.getOrDefault(emptyList())

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
            "", "/" -> if (methode == "POST") enregistre(corps(MAX_CHAINE)) else etat(p("simple") == "1", p("essai"), p("fonction"))
            "/action" -> if (methode != "POST") refus("POST seulement") else geste(p("cle"), p("valeur"))
            "/profils" -> profils(methode, p)
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
     * [fonction]: that function's whole chain too. With the functions: who
     * holds what, the conflicts, the software half of the drawing.
     */
    private fun etat(simple: Boolean, essai: String, fonction: String): ReponseWeb {
        val c = chaine
        val demo = essai in FonctionsMontage.ESSAIS
        val l = if (demo) null else lectures()
        val a = if (l == null) ActiviteMontage.essai(essai) else ActiviteMontage.depuis(l)
        val al = ActiviteMontage.allume(c, a)
        val garde = if (simple) al.enOeuvre().takeIf { it.isNotEmpty() } else null
        val o = JSONObject().put("ok", true).put("chaine", Montage.ecrit(c))
            .put("plan", DispositionMontage.json(DispositionMontage.dispose(c, garde)))
            .put("etat", ActiviteMontage.json(a, al))
            .put("simple", simple).put("simpleVide", simple && garde == null)
            .put("essai", if (demo) essai else "")
            .put("photos", JSONArray(c.elements.filter { photo(it.id) != null }.map { it.id }))
        val i = if (l == null) FonctionsMontage.essai(essai) else instantane?.let { f -> runCatching { f(l) }.getOrNull() }
        if (i != null) {
            o.put("fonctions", FonctionsMontage.json(i, c))
            if (fonction.isNotBlank()) FonctionsMontage.vue(fonction, c, i)?.let { o.put("vue", it.json().put("id", fonction)) }
        }
        return json(o)
    }

    /** The state now for a gesture: never a demonstration. */
    private fun maintenant(): FonctionsMontage.Instantane? = instantane?.let { f -> runCatching { f(lectures()) }.getOrNull() }

    /** One receive gesture of the page (a node's or a conflict's button): a single step, as a profile's. */
    private fun geste(cle: String, valeur: String): ReponseWeb {
        val ex = executant ?: return refus("pas ici")
        if (!ProfilsFonction.admis(cle, valeur)) return refus("geste refusé")
        val e = ProfilsFonction.Etape(cle, valeur, "", ProfilsFonction.dit(cle, valeur), cle == "datv.actif" && valeur == "1")
        val b = ProfilsFonction.applique(listOf(e), ex)
        b.refus?.let { return refus(it) }
        val r = b.resultats.single()
        val fait = r.ok && !r.etape.manuel
        return json(JSONObject().put("ok", fait).put("erreur", if (fait) "" else r.message).put("dit", r.etape.texte))
    }

    /**
     * The profiles: GET the list (with what each would change now; one
     * with ?id=), POST ?nom= keeps the receive settings now under a name
     * (?id= of one to replace), POST ?id=&applique=1 puts them back,
     * DELETE (or POST ?retire=1) ?id= drops one.
     */
    private fun profils(methode: String, p: (String) -> String): ReponseWeb {
        val i = maintenant()
        if (methode == "GET") {
            val l = profils.filter { p("id").isBlank() || it.id == p("id") }
            val actuels = i?.let { ProfilsFonction.capture(it) } ?: LinkedHashMap()
            return json(JSONObject().put("ok", true).put("profils", JSONArray(l.map { ProfilsFonction.json(it, i) }))
                .put("emission", i?.emission() ?: "").put("actuels", JSONArray(actuels.map { (k, v) -> JSONObject().put("cle", k).put("valeur", v).put("texte", ProfilsFonction.dit(k, v)) })))
        }
        val id = p("id")
        if (methode == "DELETE" || p("retire") == "1") {
            synchronized(this) { profils = profils.filter { it.id != id }; ecritProfils() }
            return json(JSONObject().put("ok", true))
        }
        if (p("applique") == "1") {
            val ex = executant ?: return refus("pas ici")
            val pr = profils.firstOrNull { it.id == id } ?: return refus("profil inconnu")
            val etat = i ?: return refus("état de la station illisible")
            val b = ProfilsFonction.applique(ProfilsFonction.plan(pr.reglages, etat), ex)
            return json(b.json().put("nom", pr.nom).put("erreur", b.refus ?: ""))
        }
        val nom = ProfilsFonction.propre(p("nom"))
        if (nom.isBlank()) return refus("un nom, par exemple « SSTV par IC-9700 »")
        val etat = i ?: return refus("état de la station illisible")
        val r = ProfilsFonction.capture(etat)
        if (r.isEmpty()) return refus("rien à garder ici")
        synchronized(this) {
            val ancien = profils.firstOrNull { it.id == id }
            if (ancien == null && profils.size >= ProfilsFonction.MAX) return refus("${ProfilsFonction.MAX} profils au plus")
            val pr = ProfilsFonction.Profil(ancien?.id ?: ProfilsFonction.nouvelId(nom, profils), nom, r, horloge())
            profils = if (ancien != null) profils.map { if (it.id == ancien.id) pr else it } else profils + pr
            ecritProfils()
            return json(JSONObject().put("ok", true).put("profil", ProfilsFonction.json(pr, etat)))
        }
    }

    private fun ecritProfils() {
        fichierProfils.parentFile?.mkdirs()
        val tmp = File(fichierProfils.parentFile, fichierProfils.name + ".tmp")
        tmp.writeText(ProfilsFonction.ecrit(profils).toString(2))
        if (!tmp.renameTo(fichierProfils)) { fichierProfils.delete(); tmp.renameTo(fichierProfils) }
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
