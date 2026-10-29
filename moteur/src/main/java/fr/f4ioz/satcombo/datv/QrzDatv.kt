/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.domain.Indicatifs
import fr.f4ioz.satcombo.domain.QrzReponse
import org.json.JSONObject

/**
 * The QRZ.com record of a station seen in DATV (its card in the stream, the
 * SDT service name, a callsign read in the picture): which callsigns to ask,
 * how often, and what the page shows of the answer.
 *
 * QRZ counts lookups and a received picture repeats its callsign every
 * second: each one is asked once (kept 7 days), never twice within 10
 * minutes, never more than one request every 5 s, and less and less often
 * while QRZ fails.
 */
object QrzDatv {

    const val DUREE_MS = 7L * 24 * 3600 * 1000
    const val PAUSE_INDICATIF_MS = 10L * 60 * 1000
    const val PAUSE_GLOBALE_MS = 5_000L
    /** Longest wait after repeated failures, per callsign and overall. */
    const val PAUSE_MAX_INDICATIF_MS = 6L * 3600 * 1000
    const val PAUSE_MAX_GLOBALE_MS = 10L * 60 * 1000
    /** The photo proxied for the page: larger is refused. */
    const val PHOTO_MAX = 512 * 1024

    /** The callsign QRZ is asked for: the base one (no /P), upper case; empty when not plausible. */
    fun cle(brut: String): String {
        val b = Indicatifs.base(brut.trim())
        return if (Indicatifs.plausible(b)) b else ""
    }

    /**
     * The callsign in a name (a service name, a signal's label on the wide
     * band spectrum: « G4ABC Simon », « OZ9XYZ_John », « G0XYZ-James »): its
     * first word that is one, upper case; empty when none (« QO-100 BEACON »,
     * a channel number, a name alone).
     */
    fun indicatifDans(nom: String): String =
        nom.uppercase().split(Regex("[^A-Z0-9/]+")).firstOrNull { cle(it).isNotEmpty() }.orEmpty()

    /**
     * The callsigns of the reception, best first: the station's card, the
     * first service name word that is a callsign, the one in the label of
     * the signal selected on the wide band spectrum ([spectre], locked or
     * not), the callsigns the picture's reading confirmed. Distinct, at most [max].
     */
    fun candidats(carte: String, service: String, ocr: List<String>, max: Int = 4, spectre: String = ""): List<String> {
        val l = ArrayList<String>()
        l += carte.trim().uppercase()
        l += indicatifDans(service)
        l += indicatifDans(spectre)
        l += ocr.map { it.trim().uppercase() }
        return l.filter { cle(it).isNotEmpty() }.distinctBy { cle(it) }.take(max)
    }

    /** The entry of [candidats] that is [indicatif] (as the list writes it: « F4XYZ/P » for « F4XYZ »), or empty. */
    fun parmi(candidats: List<String>, indicatif: String): String {
        val c = cle(indicatif)
        return if (c.isEmpty()) "" else candidats.firstOrNull { cle(it) == c }.orEmpty()
    }

    /** A QRZ answer sorted: found, not in QRZ (an answer too), or a failure to retry later. */
    enum class Sorte { TROUVE, INCONNU, ECHEC }

    fun sorte(f: QrzReponse.Fiche?): Sorte = when {
        f == null -> Sorte.ECHEC
        f.erreur.startsWith("Not found", ignoreCase = true) -> Sorte.INCONNU
        f.erreur.isNotBlank() -> Sorte.ECHEC
        f.vide -> Sorte.INCONNU
        else -> Sorte.TROUVE
    }

    /**
     * The lookups' memory and pace. Not thread safe: the caller holds it
     * under one lock. [horloge] in ms.
     */
    class Cache(private val horloge: () -> Long) {
        class Entree(val fiche: QrzReponse.Fiche, val ms: Long)

        private val fiches = HashMap<String, Entree>()
        private val essais = HashMap<String, Long>()
        private val echecs = HashMap<String, Int>()
        private var dernierEnvoi = Long.MIN_VALUE / 2
        private var echecsSuite = 0
        private var enCours: String? = null
        /** QRZ's last refusal or the network's, for the page; empty once a lookup succeeds. */
        var erreur = ""
            private set

        /** The record kept for [brut] (found or not in QRZ), when still fresh. */
        fun entree(brut: String): Entree? {
            val c = cle(brut)
            val e = fiches[c] ?: return null
            if (horloge() - e.ms > DUREE_MS) { fiches.remove(c); return null }
            return e
        }

        /** True when [brut] failed and waits for its next try. */
        fun echoue(brut: String): Boolean = (echecs[cle(brut)] ?: 0) > 0

        private fun pauseGlobale(): Long =
            if (echecsSuite == 0) PAUSE_GLOBALE_MS
            else minOf(PAUSE_MAX_GLOBALE_MS, PAUSE_GLOBALE_MS shl minOf(echecsSuite, 12))

        private fun pauseIndicatif(c: String): Long {
            val n = echecs[c] ?: 0
            return if (n <= 1) PAUSE_INDICATIF_MS else minOf(PAUSE_MAX_INDICATIF_MS, PAUSE_INDICATIF_MS shl minOf(n - 1, 8))
        }

        /**
         * The callsign to ask QRZ now among [candidats], or null (all known,
         * one in flight, or too soon). The request is counted as sent.
         */
        fun prochain(candidats: List<String>): String? {
            if (enCours != null) return null
            val t = horloge()
            if (t - dernierEnvoi < pauseGlobale()) return null
            val c = candidats.map { cle(it) }.firstOrNull { c ->
                c.isNotEmpty() && entree(c) == null && essais[c].let { it == null || t - it >= pauseIndicatif(c) }
            } ?: return null
            enCours = c; essais[c] = t; dernierEnvoi = t
            return c
        }

        /** QRZ's answer for [brut] ([f] null: no account or no network). */
        fun recu(brut: String, f: QrzReponse.Fiche?) {
            val c = cle(brut)
            if (enCours == c) enCours = null
            if (sorte(f) == Sorte.ECHEC) {
                echecs[c] = (echecs[c] ?: 0) + 1
                echecsSuite++
                erreur = f?.erreur?.takeIf { it.isNotBlank() } ?: "QRZ injoignable"
                return
            }
            echecs.remove(c); echecsSuite = 0; erreur = ""
            fiches[c] = Entree(f!!, horloge())
        }

        /** What is kept, for the disk (the fresh entries only). */
        fun exporte(): JSONObject {
            val o = JSONObject()
            val t = horloge()
            fiches.filter { t - it.value.ms <= DUREE_MS }.forEach { (c, e) -> o.put(c, versJson(e.fiche).put("t", e.ms)) }
            return o
        }

        fun importe(o: JSONObject) {
            val t = horloge()
            o.keys().forEach { c ->
                val j = o.optJSONObject(c) ?: return@forEach
                val ms = j.optLong("t", 0L)
                if (cle(c) == c && t - ms in 0..DUREE_MS) fiches[c] = Entree(deJson(j), ms)
            }
        }
    }

    fun versJson(f: QrzReponse.Fiche): JSONObject = JSONObject().put("indicatif", f.indicatif).put("carre", f.carre)
        .put("nom", f.nom).put("prenom", f.prenom).put("qth", f.qth).put("courriel", f.courriel).put("pays", f.pays)
        .put("erreur", f.erreur).put("image", f.image).put("lat", f.lat ?: JSONObject.NULL).put("lon", f.lon ?: JSONObject.NULL)
        .put("etat", f.etat).put("classe", f.classe).put("lotw", f.lotw).put("eqsl", f.eqsl).put("qslPapier", f.qslPapier)
        .put("qslManager", f.qslManager)

    fun deJson(j: JSONObject): QrzReponse.Fiche = QrzReponse.Fiche(
        indicatif = j.optString("indicatif"), carre = j.optString("carre"), nom = j.optString("nom"), prenom = j.optString("prenom"),
        qth = j.optString("qth"), courriel = j.optString("courriel"), pays = j.optString("pays"), erreur = j.optString("erreur"),
        image = j.optString("image"), lat = if (j.isNull("lat")) null else j.optDouble("lat"), lon = if (j.isNull("lon")) null else j.optDouble("lon"),
        etat = j.optString("etat"), classe = j.optString("classe"), lotw = j.optBoolean("lotw"), eqsl = j.optBoolean("eqsl"),
        qslPapier = j.optBoolean("qslPapier"), qslManager = j.optString("qslManager"))

    /** The station's QRZ page. */
    fun lien(brut: String): String = "https://www.qrz.com/db/" + java.net.URLEncoder.encode(cle(brut), "UTF-8")

    /** Only QRZ's own hosts (http or https, default port, no credentials) are fetched for the photo. */
    fun photoPermise(url: String): Boolean {
        val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        val h = u.host?.lowercase() ?: return false
        if (u.scheme?.lowercase() !in setOf("https", "http") || u.userInfo != null) return false
        if (u.port != -1 && u.port != 80 && u.port != 443) return false
        return h == "qrz.com" || h.endsWith(".qrz.com")
    }

    /** The picture's type from its first bytes (JPEG, PNG, GIF, WebP), or null: anything else is not served. */
    fun typeImage(b: ByteArray): String? {
        fun o(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            o(0) == 0xFF && o(1) == 0xD8 && o(2) == 0xFF -> "image/jpeg"
            o(0) == 0x89 && o(1) == 'P'.code && o(2) == 'N'.code && o(3) == 'G'.code -> "image/png"
            o(0) == 'G'.code && o(1) == 'I'.code && o(2) == 'F'.code && o(3) == '8'.code -> "image/gif"
            o(0) == 'R'.code && o(1) == 'I'.code && o(2) == 'F'.code && o(3) == 'F'.code &&
                o(8) == 'W'.code && o(9) == 'E'.code && o(10) == 'B'.code && o(11) == 'P'.code -> "image/webp"
            else -> null
        }
    }

    /**
     * What the DATV tab shows of a record: name, town, country and flag,
     * square with distance and bearing from [moi], licence class, how it
     * confirms, the photo (through SatMe's proxy) and the QRZ page.
     */
    fun carte(f: QrzReponse.Fiche, brut: String, moi: fr.f4ioz.satcombo.data.Observer?): JSONObject {
        val c = cle(brut)
        val o = JSONObject().put("ok", true).put("indicatif", f.indicatif.ifBlank { c }).put("nom", f.nom).put("prenom", f.prenom)
            .put("qth", f.qth).put("etat", f.etat).put("pays", f.pays).put("carre", f.carre).put("classe", f.classe)
            .put("lotw", f.lotw).put("eqsl", f.eqsl).put("qslPapier", f.qslPapier).put("qslManager", f.qslManager)
            .put("lien", lien(c))
        fr.f4ioz.satcombo.domain.Dxcc.entite(f.indicatif.ifBlank { c })?.let { o.put("drapeau", it.drapeau).put("paysDxcc", it.nom) }
        if (photoPermise(f.image)) o.put("photo", "api/datv/qrz/photo?c=" + java.net.URLEncoder.encode(c, "UTF-8"))
        fr.f4ioz.satcombo.demo.ApiStation.ouEstIl(o, moi, f.lat, f.lon, f.carre)
        return o
    }

    /** For each of [candidats] (as shown), its record, « attente », « inconnu » or the failure; [compte]: QRZ credentials given. */
    fun json(cache: Cache, candidats: List<String>, compte: Boolean, moi: fr.f4ioz.satcombo.data.Observer?): JSONObject {
        val o = JSONObject().put("compte", compte).put("erreur", cache.erreur)
        val fiches = JSONObject()
        if (compte) candidats.forEach { b ->
            val e = cache.entree(b)
            fiches.put(b, when {
                e != null && sorte(e.fiche) == Sorte.TROUVE -> carte(e.fiche, b, moi)
                e != null -> JSONObject().put("inconnu", true).put("lien", lien(b))
                cache.echoue(b) -> JSONObject().put("echec", cache.erreur.ifBlank { "QRZ injoignable" }).put("lien", lien(b))
                else -> JSONObject().put("attente", true)
            })
        }
        return o.put("fiches", fiches)
    }
}
