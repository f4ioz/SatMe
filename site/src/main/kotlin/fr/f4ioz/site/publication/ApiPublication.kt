/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.publication

import fr.f4ioz.site.Config
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.http.TropGros
import fr.f4ioz.site.securite.Jetons
import fr.f4ioz.site.securite.Limiteur
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * The publication API, for SatMe-PC (« Réglages › Site web »): HTTPS through
 * nginx, `Authorization: Bearer <jeton>` checked against the hash in
 * site.conf (in constant time; ten wrong tokens from an address and it waits
 * a quarter of an hour).
 *
 * - `GET  /api/publication/ping` — the token is right: the site's name, the types and their limits;
 * - `POST /api/publication/{type}` — the file as the body, its words in `X-SatMe-Meta` (JSON in
 *   base64url): satellite, utc, frequence, mode, passage, evenement, station, titre, cle…;
 *   `201` with its id, `200` when the same content was already there (idempotent);
 * - `GET  /api/publication` — what is published (`?type=`);
 * - `DELETE /api/publication/{id}`, or `DELETE /api/publication?type=…&cle=…` — withdrawn.
 */
class ApiPublication(
    private val config: Config,
    private val pubs: Publications,
    private val apresPublication: (Publication) -> Unit = {},
    /** Whether the site takes this kind now (« Admin › Paramètres »): refused with a 403 SatMe-PC shows. */
    private val accepte: (TypePublication) -> Boolean = { true },
) : ModuleSite {

    private val echecs = Limiteur(10, 15 * 60_000L)
    private val debit = Limiteur(600, 60_000L)
    private val tmp = File(config.donnees, "tmp")

    override fun installe(r: Routeur) {
        r.get("/api/publication/ping") { q -> autorise(q) { ping() } }
        r.get("/api/publication") { q -> autorise(q) { liste(q) } }
        r.post("/api/publication/{type}") { q -> autorise(q) { publie(q) } }
        r.delete("/api/publication/{id}") { q -> autorise(q) { retire(q.variable("id")) } }
        r.delete("/api/publication") { q -> autorise(q) { retireCle(q) } }
    }

    /** [suite] when [q] carries the token (the live pushes of [fr.f4ioz.site.direct.ApiDirect] too). */
    fun autorise(q: Requete, suite: () -> Reponse): Reponse {
        if (config.jetonSha256.isBlank()) return Reponse.erreur(403, "publication non configurée sur ce site")
        if (echecs.nombre(q.ip) >= echecs.limite) return Reponse.erreur(429, "trop d'essais, réessayer plus tard").entete("Retry-After", "900")
        if (!Jetons.verifieJeton(Jetons.porteur(q.entete("authorization")), config.jetonSha256)) {
            echecs.accepte(q.ip)
            return Reponse.erreur(401, "jeton refusé").entete("WWW-Authenticate", "Bearer")
        }
        if (!debit.accepte(q.ip)) return Reponse.erreur(429, "trop de requêtes").entete("Retry-After", "30")
        return suite()
    }

    private fun ping(): Reponse = Reponse.json(JSONObject().put("ok", true).put("site", config.urlBaseNette).put("indicatif", config.indicatif)
        .put("version", VERSION).put("types", JSONObject(TypePublication.values().associate { it.nom to limite(it) }))
        .put("refuses", JSONArray(TypePublication.values().filter { !accepte(it) }.map { it.nom })))

    private fun limite(t: TypePublication): Long = if (t == TypePublication.VIDEO) config.videoMaxMo.toLong() shl 20 else t.maxOctets

    private fun liste(q: Requete): Reponse {
        val t = q.param("type")?.let { TypePublication.de(it) }
        val l = pubs.toutes().filter { t == null || it.type == t }
        return Reponse.json(JSONObject().put("ok", true).put("publications", JSONArray(l.map { it.json().put("url", urlDe(it)) })))
    }

    private fun publie(q: Requete): Reponse {
        val type = TypePublication.de(q.variable("type")) ?: return Reponse.erreur(404, "type inconnu : " + q.variable("type").take(40))
        if (!accepte(type)) return Reponse.json(JSONObject().put("ok", false).put("refuse", true)
            .put("erreur", "le site ne prend plus les « ${type.libelle} » (Admin › Paramètres)"), 403)
        val meta = runCatching { meta(q.entete("x-satme-meta")) }.getOrElse { return Reponse.erreur(400, "X-SatMe-Meta illisible") }
        val annonce = q.entete("content-type").orEmpty().lowercase()
        return try {
            val res = if (type == TypePublication.VIDEO) {
                tmp.mkdirs()
                val f = File.createTempFile("video", ".part", tmp)
                val (sha, taille) = q.corpsVers(f, limite(type))
                pubs.publieFichier(type, f, sha, taille, meta)
            } else pubs.publie(type, q.corps(limite(type)), meta, annonce)
            if (res.nouvelle) runCatching { apresPublication(res.p) }
            Reponse.json(JSONObject().put("ok", true).put("id", res.p.id).put("deja", !res.nouvelle).put("url", urlDe(res.p))
                .put("remplace", res.remplacee ?: JSONObject.NULL), if (res.nouvelle) 201 else 200)
        } catch (e: TropGros) {
            Reponse.erreur(413, "trop gros pour ${type.nom} (max ${limite(type) shr 20} Mo)")
        } catch (e: Publications.Refus) {
            Reponse.erreur(e.statut, e.message ?: "refusé")
        }
    }

    private fun retire(id: String): Reponse =
        if (pubs.supprime(id)) Reponse.json(JSONObject().put("ok", true).put("id", id)) else Reponse.erreur(404, "publication inconnue")

    private fun retireCle(q: Requete): Reponse {
        val t = q.param("type")?.let { TypePublication.de(it) } ?: return Reponse.erreur(400, "type manquant")
        val p = pubs.parCle(t, q.param("cle").orEmpty()) ?: return Reponse.erreur(404, "publication inconnue")
        pubs.supprime(p.id)
        return Reponse.json(JSONObject().put("ok", true).put("id", p.id))
    }

    /** Where it is seen on the site. */
    private fun urlDe(p: Publication): String = config.urlBaseNette + when (p.type) {
        TypePublication.QSO -> "/logbook"
        else -> "/gallery/item/" + p.id
    }

    companion object {
        const val VERSION = 1

        /** `X-SatMe-Meta`: JSON in base64url (or plain JSON), 8 KB at most. */
        fun meta(entete: String?): JSONObject {
            val e = entete?.trim().orEmpty()
            if (e.isEmpty()) return JSONObject()
            require(e.length <= 12_000)
            val texte = if (e.startsWith("{")) e else String(Base64.getUrlDecoder().decode(e.trimEnd('=')), Charsets.UTF_8)
            return JSONObject(texte)
        }
    }
}
