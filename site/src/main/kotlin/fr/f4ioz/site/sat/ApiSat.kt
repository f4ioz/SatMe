/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.sat

import fr.f4ioz.satcombo.demo.ApiCalculs
import fr.f4ioz.satcombo.demo.ApiCarte
import fr.f4ioz.satcombo.demo.PagePc
import fr.f4ioz.satcombo.demo.QrSvg
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Reponse
import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.securite.Limiteur
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The routes SatMe's tabs ask for (Orbites, Sked), answered by the site as
 * SatMe-PC answers them — read-only: the satellites and their elements,
 * the windows of a sked and its replay (SatMe's own [ApiCalculs]), the
 * coastline, three.js and satellite.js, the flags' font, the map's tiles,
 * a QR code. Under `/api/…`, and `/sat/api/…` too (the tabs ask relatively
 * to their page).
 */
class ApiSat(private val s: Site) : ModuleSite {

    private val calculs = Limiteur(40, 60_000L)
    private val tuilesParIp = Limiteur(900, 60_000L)
    val tuiles = Tuiles(File(s.config.donnees, "cache/tuiles"), s.config.horsLigne)

    private val traits: ByteArray by lazy {
        val t = ApiSat::class.java.getResourceAsStream("/fr/f4ioz/site/traits/land.json")?.bufferedReader()?.use { it.readText() }
        (if (t == null) "[]" else ApiCarte.traits(t)).toByteArray(Charsets.UTF_8)
    }

    override fun installe(r: Routeur) {
        for (base in listOf("/api", "/sat/api")) {
            r.get("$base/orbites") { q -> orbites(q) }
            r.get("$base/satellites") { _ -> Reponse.json(JSONArray(s.catalogue.satellites.map { JSONObject().put("nom", it.name).put("catnum", it.catalogNumber) })).cache("public, max-age=600") }
            r.get("$base/sked") { q -> sked(q) }
            r.get("$base/sked/trace") { q -> trace(q) }
            r.get("$base/carte/traits") { _ -> Reponse.octets(traits, Reponse.JSON).cache("public, max-age=86400") }
            r.get("$base/lib/{nom}") { q -> PagePc.bibliotheque(q.variable("nom"))?.let { b ->
                Reponse.octets(b, if (q.variable("nom").endsWith(".png")) "image/png" else "text/javascript; charset=utf-8").cache("public, max-age=604800") } ?: s.pageErreur(q, 404) }
            r.get("$base/police-drapeaux") { q -> PagePc.POLICE_DRAPEAUX?.let { Reponse.octets(it, "font/woff2").cache("public, max-age=604800") } ?: s.pageErreur(q, 404) }
            r.get("$base/carnet") { _ -> carnet() }
            // The satellites' cards (photos from Wikipedia) are SatMe-PC's: the site's tab shows SatMe's own drawing.
            r.get("$base/satfiche") { _ -> Reponse.json(JSONObject().put("ok", false).put("absent", true)).cache("public, max-age=3600") }
            r.get("$base/iss-direct/source") { _ -> Reponse.json(JSONObject().put("ok", true).put("id", JSONObject.NULL).put("titre", JSONObject.NULL)
                .put("verifie", JSONObject.NULL).put("chaine", ISS_CHAINE).put("dernier", ISS_DERNIER)) }
            r.get("$base/qr") { q -> q.param("t")?.trim()?.take(300)?.takeIf { it.isNotEmpty() }?.let { Reponse.texte(QrSvg.svg(it), type = "image/svg+xml").cache("public, max-age=86400") } ?: s.pageErreur(q, 404) }
        }
        for (base in listOf("/journal", "/sat/journal")) r.get("$base/tuile") { q -> tuile(q) }
    }

    /** The globes' data: the station (the site's), the home page's satellites as favourites, every satellite's elements. */
    private fun orbites(q: Requete): Reponse {
        val c = s.config
        val ici = q.param("loc")?.uppercase()?.let { s.catalogue.observateur(it) } ?: s.catalogue.observateur(c.locator)
        val favoris = c.satellitesAccueil.mapNotNull { s.catalogue.cherche(it)?.catalogNumber }.distinct()
        val o = JSONObject().put("indicatif", c.indicatif).put("locator", ici?.name ?: c.locator).put("suivi", 0).put("favoris", JSONArray(favoris))
        ici?.let { o.put("obs", JSONObject().put("lat", it.latDeg).put("lon", it.lonDeg).put("alt", it.altMeters)) }
        o.put("sats", JSONArray(s.catalogue.satellites.map { e ->
            JSONObject().put("nom", e.name).put("catnum", e.catalogNumber).put("l1", e.line1).put("l2", e.line2).also { j -> e.downlinkHz?.let { j.put("dl", it / 1e6) } }
        }))
        return Reponse.octets(o.toString().toByteArray(Charsets.UTF_8), Reponse.JSON).cache("public, max-age=600")
    }

    /** « My » station for a sked: the visitor's (`moi`), else the site's. */
    private fun moi(q: Requete) = q.param("moi")?.uppercase()?.let { s.catalogue.observateur(it) } ?: s.catalogue.observateur(s.config.locator)

    private fun sked(q: Requete): Reponse {
        if (!calculs.accepte(q.ip)) return Reponse.erreur(429, "trop de calculs, patienter une minute")
        val sat = s.catalogue.parNumero(q.param("catnum")?.toIntOrNull() ?: 0) ?: return Reponse.json(JSONObject().put("ok", false).put("erreur", "satellite inconnu"))
        val moi = moi(q) ?: return Reponse.json(JSONObject().put("ok", false).put("erreur", "mon carré n'est pas reconnu"))
        val loc = q.param("loc")?.trim()?.uppercase()?.take(6).orEmpty()
        if (s.catalogue.observateur(loc) == null) return Reponse.json(ApiCalculs.skedJson(null))
        val minEl = (q.param("minEl")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 60.0)
        val f = ApiCalculs.sked(s.catalogue.predicteur, sat, moi, loc, 0.0, minEl, System.currentTimeMillis())
        return Reponse.json(ApiCalculs.skedJson(f))
    }

    private fun trace(q: Requete): Reponse {
        if (!calculs.accepte(q.ip)) return Reponse.erreur(429, "trop de calculs, patienter une minute")
        val sat = s.catalogue.parNumero(q.param("catnum")?.toIntOrNull() ?: 0) ?: return Reponse.json(JSONObject().put("ok", false))
        val moi = moi(q) ?: return Reponse.json(JSONObject().put("ok", false))
        val de = q.param("de")?.toLongOrNull() ?: 0L
        val a = q.param("a")?.toLongOrNull() ?: 0L
        val loc = q.param("loc")?.trim()?.uppercase()?.take(6).orEmpty()
        if (a <= de || a - de > 4 * 3_600_000L || s.catalogue.observateur(loc) == null) return Reponse.json(JSONObject().put("ok", false))
        return Reponse.json(ApiCalculs.traceSkedJson(ApiCalculs.traceSked(s.catalogue.predicteur, sat, moi, loc, de, a)))
    }

    /** The logbook's squares for the globes' « Contacts »: callsign, square (four characters), when. */
    private fun carnet(): Reponse {
        val l = s.logbook.tous().filter { it.carre.length == 4 }.take(3000)
        return Reponse.json(JSONObject().put("contacts", JSONArray(l.map { JSONObject().put("c", it.indicatif).put("l", it.carre).put("t", it.quandMs) })))
            .cache("public, max-age=300")
    }

    private fun tuile(q: Requete): Reponse {
        if (!tuilesParIp.accepte(q.ip)) return Reponse.erreur(429, "trop de tuiles")
        val z = q.param("z")?.toIntOrNull(); val x = q.param("x")?.toIntOrNull(); val y = q.param("y")?.toIntOrNull()
        if (z == null || x == null || y == null) return Reponse(404, "image/png")
        val f = tuiles.tuile(z, x, y) ?: return Reponse(404, "image/png").cache("public, max-age=300")
        return Reponse.fichier(f, "image/png").cache("public, max-age=604800")
    }

    companion object {
        /** NASA's YouTube channel and the ISS live known when written (SatMe-PC's IssDirectPc looks for today's). */
        const val ISS_CHAINE = "UCLA_DiR1FfKNvjuUpBHmylQ"
        const val ISS_DERNIER = "ROboUHfPUrU"
    }
}
