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
import fr.f4ioz.satcombo.station.DetectionMontage
import fr.f4ioz.satcombo.station.Detourage
import fr.f4ioz.satcombo.station.DispositionMontage
import fr.f4ioz.satcombo.station.EditionMontage
import fr.f4ioz.satcombo.station.FonctionsMontage
import fr.f4ioz.satcombo.station.Montage
import fr.f4ioz.satcombo.station.ProfilsFonction
import fr.f4ioz.satcombo.station.VerificationMontage
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
 *
 * The drawing's edits one gesture at a time (`/edition`), undone and done
 * again (`/annule`, `/refait`); the proposal from what SatMe knows and what
 * the system lists of its USB devices and sound cards ([systeme]: files
 * read, never a port opened), taken piece by piece (`/fusion`).
 */
class MontagePc(dossier: File, private val lectures: () -> ActiviteMontage.Lectures,
                private val instantane: ((ActiviteMontage.Lectures) -> FonctionsMontage.Instantane)? = null,
                private val executant: ProfilsFonction.Executant? = null,
                private val systeme: () -> Pair<List<DetectionMontage.Usb>, List<DetectionMontage.CarteSon>> = {
                    runCatching { DetectionMontage.litUsb() }.getOrDefault(emptyList()) to runCatching { DetectionMontage.litCartes() }.getOrDefault(emptyList())
                },
                private val horloge: () -> Long = System::currentTimeMillis) {

    private val fichier = File(dossier, "montage.json")
    private val photos = File(dossier, "montage-photos")
    private val fichierProfils = File(dossier, "montage-profils.json")
    @Volatile private var profils: List<ProfilsFonction.Profil> = runCatching { ProfilsFonction.lit(fichierProfils.readText()) }.getOrDefault(emptyList())

    @Volatile private var chaine: Montage.Chaine = runCatching { Montage.lit(fichier.readText()) }.getOrDefault(Montage.Chaine())
    private val historique = EditionMontage.Historique()

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
            "/edition" -> if (methode != "POST") refus("POST seulement") else edite(p)
            "/annule", "/refait" -> if (methode != "POST") refus("POST seulement") else synchronized(this) {
                val c = if (route == "/annule") historique.annule(chaine) else historique.refait(chaine)
                if (c == null) refus(if (route == "/annule") "rien à annuler" else "rien à rétablir")
                else { garde(c); json(JSONObject().put("ok", true).put("historique", etatHistorique())) }
            }
            "/detection" -> json(detection())
            "/fusion" -> if (methode != "POST") refus("POST seulement") else fusion(corps(MAX_CHAINE))
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
                    val f = (if (p("detoure") == "1") detouree(id) else photo(id)) ?: return@runCatching NON
                    ReponseWeb("200 OK", TYPES[f.extension] ?: "application/octet-stream", f.readBytes(), "X-Content-Type-Options: nosniff\r\n")
                } else {
                    val e = chaine.element(id) ?: return@runCatching refus("appareil inconnu")
                    photos.mkdirs()
                    if (p("retire") == "1") { synchronized(this) { photo(id)?.delete(); detoureFichiers(e).forEach { it.delete() } }; return@runCatching json(JSONObject().put("ok", true)) }
                    if (p("detourage") == "1") {
                        val f = photo(id) ?: return@runCatching refus("pas de photo")
                        val r = detoure(e, f.readBytes(), p("tolerance").toIntOrNull()) ?: return@runCatching refus("photo illisible ici (JPEG, PNG ou GIF)")
                        return@runCatching json(JSONObject().put("ok", true).put("uniforme", r.uniforme).put("tolerance", r.tolerance))
                    }
                    val b = corps(PHOTO_MAX) ?: return@runCatching refus("photo trop grosse (8 Mo au plus) ou coupée")
                    val ext = genre(b) ?: return@runCatching refus("seulement une image JPEG, PNG, WebP ou GIF")
                    synchronized(this) {
                        photo(id)?.delete(); detoureFichiers(e).forEach { it.delete() }
                        File(photos, "${e.id}-${e.modele}.$ext").writeBytes(b)
                    }
                    // Cut out at once: the owner then chooses to show it in place of the drawing.
                    val r = runCatching { detoure(e, b, null) }.getOrNull()
                    json(JSONObject().put("ok", true).put("detoure", r != null).put("uniforme", r?.uniforme ?: JSONObject.NULL))
                }
            }
            else -> NON
        }
    }.getOrElse { refus(it.message ?: "erreur") }

    private fun enregistre(b: ByteArray?): ReponseWeb {
        val texte = b?.toString(Charsets.UTF_8) ?: return refus("chaîne trop grosse")
        val c = Montage.lit(texte)
        synchronized(this) {
            historique.pousse(chaine, c)
            garde(c)
        }
        return json(JSONObject().put("ok", true).put("chaine", Montage.ecrit(c)))
    }

    private fun etatHistorique() = JSONObject().put("annuler", historique.peutAnnuler).put("refaire", historique.peutRefaire)

    /** One edit of the drawing (a piece dropped, a cable drawn, a piece moved…), kept for « Annuler ». */
    private fun edite(p: (String) -> String): ReponseWeb {
        val op = EditionMontage.lit(p) ?: return refus("geste inconnu")
        synchronized(this) {
            val avant = chaine
            val r = EditionMontage.applique(avant, op)
            if (!r.ok) return refus(r.erreur!!)
            historique.pousse(avant, r.chaine)
            if (r.chaine != avant) garde(r.chaine)
            return json(JSONObject().put("ok", true).put("id", r.id ?: "").put("dit", r.dit).put("historique", etatHistorique()))
        }
    }

    /** What SatMe proposes now: its state, the USB devices and sound cards the system lists (read, nothing opened). */
    private fun detection(): JSONObject {
        val (usb, cartes) = systeme()
        val p = DetectionMontage.propose(lectures(), usb, cartes)
        val actuelle = chaine
        // Which proposed pieces are already drawn (a model already there): unticked by default on the page.
        val deja = HashMap<String, Int>()
        actuelle.elements.forEach { deja.merge(it.modele, 1, Int::plus) }
        val dessines = JSONArray()
        for (e in p.chaine.elements) { val n = deja[e.modele] ?: 0; if (n > 0) { dessines.put(e.id); deja[e.modele] = n - 1 } }
        return DetectionMontage.json(p).put("deja", dessines)
            .put("usb", JSONArray(usb.map { JSONObject().put("id", it.id).put("nom", it.nom).put("fabricant", it.fabricant) }))
            .put("cartes", JSONArray(cartes.map { JSONObject().put("n", it.index).put("nom", it.nom).put("usb", it.usb) }))
    }

    /** The pieces the owner kept of a proposal (`{"chaine": …, "garde": [ids]}`) merged into the chain. */
    private fun fusion(b: ByteArray?): ReponseWeb {
        val o = runCatching { JSONObject(b?.toString(Charsets.UTF_8) ?: "") }.getOrNull() ?: return refus("proposition illisible")
        val prop = Montage.lit(o.optJSONObject("chaine") ?: return refus("proposition illisible"))
        val g = o.optJSONArray("garde") ?: JSONArray()
        val gardes = (0 until minOf(g.length(), Montage.ELEMENTS_MAX)).map { g.optString(it) }.toSet()
        synchronized(this) {
            val avant = chaine
            val f = DetectionMontage.fusionne(avant, prop, gardes)
            historique.pousse(avant, f.chaine)
            if (f.chaine != avant) garde(f.chaine)
            return json(JSONObject().put("ok", true).put("ajoutes", f.ajoutes).put("relies", f.relies).put("historique", etatHistorique()))
        }
    }

    /** [c] kept, in memory and in the data folder; the photos of pieces gone deleted. */
    private fun garde(c: Montage.Chaine) {
        synchronized(this) {
            chaine = c
            val tmp = File(fichier.parentFile, fichier.name + ".tmp")
            tmp.writeText(Montage.ecrit(c).toString(2))
            if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
            // The photos of pieces no longer there go.
            val gardees = c.elements.map { "${it.id}-${it.modele}" }.toSet()
            photos.listFiles()?.forEach { f -> if (f.nameWithoutExtension.removeSuffix("-detoure") !in gardees) f.delete() }
        }
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
        // SatMe-PC itself always stays in the simplified drawing.
        val garde = if (simple) al.enOeuvre().takeIf { it.isNotEmpty() }
            ?.plus(c.elements.filter { Montage.modele(it)?.genre == Montage.Genre.ORDINATEUR }.map { it.id }) else null
        val o = JSONObject().put("ok", true).put("chaine", Montage.ecrit(c))
            .put("plan", DispositionMontage.json(DispositionMontage.dispose(c, garde)))
            .put("ports", Montage.portsJson(c)).put("historique", etatHistorique())
            .put("etat", ActiviteMontage.json(a, al))
            .put("verification", VerificationMontage.json(VerificationMontage.verifie(c)))
            .put("simple", simple).put("simpleVide", simple && garde == null)
            .put("essai", if (demo) essai else "")
            .put("photos", JSONArray(c.elements.filter { photo(it.id) != null }.map { it.id }))
            .put("detoures", JSONArray(c.elements.filter { detouree(it.id) != null }.map { it.id }))
            .put("detourage", JSONObject().also { o -> c.elements.forEach { e -> infoDetoure(e)?.let { o.put(e.id, it) } } })
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

    /** The cut-out photo of [e] and what was said of it. */
    private fun detoureFichiers(e: Montage.Element) = listOf(File(photos, "${e.id}-${e.modele}-detoure.png"), File(photos, "${e.id}-${e.modele}-detoure.json"))
    private fun detouree(id: String): File? = chaine.element(id)?.let { detoureFichiers(it)[0] }?.takeIf { it.isFile && photo(id) != null }
    private fun infoDetoure(e: Montage.Element): JSONObject? = detoureFichiers(e)[1].takeIf { it.isFile && detouree(e.id) != null }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    /** [e]'s photo [b] cut out ([tolerance] null: from its border), kept as a PNG beside it; null when it cannot be read. */
    private fun detoure(e: Montage.Element, b: ByteArray, tolerance: Int?): Detourage.Resultat? {
        val r = decoupe(b, tolerance) ?: return null
        val (png, info) = detoureFichiers(e)
        synchronized(this) {
            png.writeBytes(r.second)
            info.writeText(JSONObject().put("tolerance", r.first.tolerance).put("uniforme", r.first.uniforme).toString())
        }
        return r.first
    }

    companion object {
        const val MAX_CHAINE = 256L * 1024
        /** The longest side a photo is cut out at (larger ones are reduced first). */
        const val DETOURAGE_MAX = 1200

        /** A photo's bytes cut out: the result and its PNG; null when the picture cannot be read. */
        fun decoupe(b: ByteArray, tolerance: Int?): Pair<Detourage.Resultat, ByteArray>? {
            var img = runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(b)) }.getOrNull() ?: return null
            val k = DETOURAGE_MAX.toDouble() / maxOf(img.width, img.height)
            if (k < 1) {
                val w = maxOf(3, (img.width * k).toInt()); val h = maxOf(3, (img.height * k).toInt())
                val s = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val g = s.createGraphics()
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, 0, 0, w, h, null); g.dispose(); img = s
            }
            if (img.width < 3 || img.height < 3) return null
            val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            val r = Detourage.detoure(px, img.width, img.height, tolerance)
            val o = java.awt.image.BufferedImage(r.l, r.h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            o.setRGB(0, 0, r.l, r.h, r.pixels, 0, r.l)
            val sortie = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(o, "png", sortie)
            return r to sortie.toByteArray()
        }
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
