/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.espace

import fr.f4ioz.satcombo.sonde.Geo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.log10

/**
 * Space weather (« Météo spatiale ») from the public JSON products of the
 * NOAA Space Weather Prediction Center: the planetary K index (observed,
 * estimated, predicted), the 10.7 cm solar flux, the GOES X-ray flux, the
 * solar wind at L1, the auroral probability (OVATION). Reading only: the
 * fetching, opt-in, is elsewhere. Every reader accepts both shapes the
 * products have had (rows of arrays under a header row, or objects) and
 * stray bytes after the JSON (a product ended with a NUL).
 */
object MeteoSpatiale {

    const val BASE = "https://services.swpc.noaa.gov/"

    /** The products: name → path under [BASE]. */
    val PRODUITS = linkedMapOf(
        "kp" to "products/noaa-planetary-k-index.json",
        "prevision" to "products/noaa-planetary-k-index-forecast.json",
        "f107" to "json/f107_cm_flux.json",
        "rayons" to "json/goes/primary/xrays-6-hour.json",
        "vent" to "products/summary/solar-wind-speed.json",
        "champ" to "products/summary/solar-wind-mag-field.json",
        "aurore" to "json/ovation_aurora_latest.json")

    /** Kp at or above which a storm alert is raised (G1). */
    const val KP_ALERTE = 5.0
    /** X-ray flux (W/m², 0.1–0.8 nm) at or above which a flare alert is raised: M5. */
    const val X_ALERTE = 5e-5

    data class Kp(val t: Long, val kp: Double, /** "observe", "estime", "prevu". */ val nature: String, val echelle: String? = null)
    data class Flux(val t: Long, val sfu: Double, val moyenne90: Double? = null)
    data class Rayons(val t: Long, val flux: Double)
    data class Vent(val t: Long, val vitesse: Double?, val bz: Double?, val bt: Double?)
    data class Aurore(val observation: Long, val prevision: Long, /** % at the station's cell. */ val ici: Int,
                      /** Highest % within [rayonKm] of the station, and where. */ val proche: Int, val procheLat: Double?, val procheLon: Double?,
                      val rayonKm: Double, /** Cells of 10 % or more, for the map: lon, lat, % (every second degree). */ val ovale: List<IntArray>)

    data class Etat(val kp: List<Kp> = emptyList(), val prevision: List<Kp> = emptyList(), val flux: Flux? = null,
                    val rayons: List<Rayons> = emptyList(), val vent: Vent? = null, val aurore: Aurore? = null)

    data class Alerte(val cle: String, val t: Long, val titre: String, val texte: String, val niveau: String)

    // ---------------------------------------------------------------- reading

    /** A time as the products write it ("2026-10-11T00:00:00", with or without Z, a space for the T, milliseconds): ms UTC, or null. */
    fun temps(s: String?): Long? {
        val m = Regex("(\\d{4})-(\\d\\d)-(\\d\\d)[T ](\\d\\d):(\\d\\d)(?::(\\d\\d)(?:\\.\\d+)?)?").find(s ?: return null) ?: return null
        val g = m.groupValues
        return runCatching {
            java.time.LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun nettoie(t: String) = t.trim { it <= ' ' || it == '\u0000' }

    /** Rows of a product as objects: arrays under a header row become objects with its names. */
    fun lignes(texte: String): List<JSONObject> {
        val a = runCatching { JSONArray(nettoie(texte)) }.getOrNull() ?: return emptyList()
        if (a.length() == 0) return emptyList()
        val premier = a.opt(0)
        if (premier is JSONArray) {
            val noms = (0 until premier.length()).map { premier.optString(it) }
            return (1 until a.length()).mapNotNull { i ->
                val r = a.optJSONArray(i) ?: return@mapNotNull null
                JSONObject().also { o -> noms.forEachIndexed { k, n -> if (k < r.length()) o.put(n, r.opt(k)) } }
            }
        }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** A number given as a number or a string ("1.67"); null for null or "". */
    private fun nombre(o: JSONObject, vararg noms: String): Double? {
        for (n in noms) {
            if (!o.has(n) || o.isNull(n)) continue
            val v = o.opt(n)
            val d = when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }
            if (d != null && !d.isNaN()) return d
        }
        return null
    }

    private fun nature(s: String?): String = when (s?.lowercase()) { "observed" -> "observe"; "estimated" -> "estime"; "predicted" -> "prevu"; else -> "observe" }

    /** The observed planetary K index (3-hour values), oldest first. */
    fun lisKp(texte: String): List<Kp> = lignes(texte).mapNotNull { o ->
        val t = temps(o.optString("time_tag")) ?: return@mapNotNull null
        val k = nombre(o, "Kp", "kp", "kp_index") ?: return@mapNotNull null
        Kp(t, k, "observe")
    }.sortedBy { it.t }

    /** The forecast product: observed, estimated and predicted 3-hour values, oldest first. */
    fun lisPrevision(texte: String): List<Kp> = lignes(texte).mapNotNull { o ->
        val t = temps(o.optString("time_tag")) ?: return@mapNotNull null
        val k = nombre(o, "kp", "Kp") ?: return@mapNotNull null
        Kp(t, k, nature(o.optString("observed")), o.optString("noaa_scale").takeIf { it.isNotBlank() && it != "null" })
    }.sortedBy { it.t }

    /** The latest 10.7 cm flux (the daily noon value preferred, as the index is quoted), or null. */
    fun lisFlux(texte: String): Flux? {
        val l = lignes(texte).mapNotNull { o ->
            val t = temps(o.optString("time_tag").ifEmpty { o.optString("TimeStamp") }) ?: return@mapNotNull null
            val f = nombre(o, "flux", "Flux") ?: return@mapNotNull null
            Triple(Flux(t, f, nombre(o, "ninety_day_mean")), o.optString("reporting_schedule"), o.optInt("frequency", 2800))
        }.filter { it.third == 2800 }
        val dernier = l.maxByOrNull { it.first.t } ?: return null
        val midi = l.filter { it.second.equals("Noon", true) }.maxByOrNull { it.first.t }
        // The noon value of the same day or the day before, else the latest one.
        return if (midi != null && dernier.first.t - midi.first.t < 30 * 3_600_000L) midi.first else dernier.first
    }

    /** GOES X-ray flux in the long band (0.1–0.8 nm), oldest first. */
    fun lisRayons(texte: String): List<Rayons> = lignes(texte).mapNotNull { o ->
        if (o.has("energy") && o.optString("energy") != "0.1-0.8nm") return@mapNotNull null
        val t = temps(o.optString("time_tag")) ?: return@mapNotNull null
        val f = nombre(o, "flux") ?: return@mapNotNull null
        if (f <= 0) null else Rayons(t, f)
    }.sortedBy { it.t }

    /** The solar wind now: speed (km/s) from one summary, Bz and Bt (nT) from the other. */
    fun lisVent(vitesse: String?, champ: String?): Vent? {
        val v = vitesse?.let { lignes(it).lastOrNull() }
        val c = champ?.let { lignes(it).lastOrNull() }
        if (v == null && c == null) return null
        val t = listOfNotNull(v?.let { temps(it.optString("time_tag").ifEmpty { it.optString("TimeStamp") }) },
            c?.let { temps(it.optString("time_tag").ifEmpty { it.optString("TimeStamp") }) }).maxOrNull() ?: 0L
        return Vent(t, v?.let { nombre(it, "proton_speed", "WindSpeed", "speed") }, c?.let { nombre(it, "bz_gsm", "Bz") }, c?.let { nombre(it, "bt", "Bt") })
    }

    /**
     * The OVATION aurora map at [lat], [lon]: the probability at the
     * nearest cell and the highest within [rayonKm] (on 2 m, a radio aurora
     * is worked by beaming at the oval, up to about a thousand kilometres
     * away), and the oval itself, coarsely, for the map.
     */
    fun lisAurore(texte: String, lat: Double?, lon: Double?, rayonKm: Double = 1200.0): Aurore? {
        val o = runCatching { JSONObject(nettoie(texte)) }.getOrNull() ?: return null
        val c = o.optJSONArray("coordinates") ?: return null
        var ici = 0; var proche = 0; var pLat: Double? = null; var pLon: Double? = null
        var meilleur = Double.MAX_VALUE
        val ovale = ArrayList<IntArray>()
        for (i in 0 until c.length()) {
            val p = c.optJSONArray(i) ?: continue
            val lo = p.optInt(0); val la = p.optInt(1); val v = p.optInt(2)
            if (v >= 10 && lo % 2 == 0 && la % 2 == 0) ovale += intArrayOf(if (lo > 180) lo - 360 else lo, la, v)
            if (lat == null || lon == null) continue
            val lo180 = if (lo > 180) lo - 360.0 else lo.toDouble()
            // Cells are a degree apart: a quick filter before the distance.
            if (Math.abs(la - lat) > rayonKm / 111.0 + 1) continue
            val d = Geo.distanceKm(lat, lon, la.toDouble(), lo180)
            if (d < meilleur) { meilleur = d; ici = v }
            if (d <= rayonKm && v > proche) { proche = v; pLat = la.toDouble(); pLon = lo180 }
        }
        return Aurore(temps(o.optString("Observation Time")) ?: 0L, temps(o.optString("Forecast Time")) ?: 0L, ici, proche, pLat, pLon, rayonKm, ovale)
    }

    // ---------------------------------------------------------------- meaning

    /** The flare class of an X-ray flux: "C3.4", "M5.0", "X1.2"… */
    fun classeX(flux: Double): String {
        if (flux <= 0) return "—"
        val e = floor(log10(flux)).toInt()
        val lettre = when { e <= -8 -> "A"; e == -7 -> "B"; e == -6 -> "C"; e == -5 -> "M"; else -> "X" }
        val base = when (lettre) { "A" -> 1e-8; "B" -> 1e-7; "C" -> 1e-6; "M" -> 1e-5; else -> 1e-4 }
        val v = Math.round(flux / base * 10) / 10.0
        return lettre + String.format(java.util.Locale.ROOT, "%.1f", v)
    }

    /** The NOAA geomagnetic storm scale for a Kp: 0 (none), 1 (G1, Kp 5) … 5 (G5, Kp 9). */
    fun echelleG(kp: Double): Int = when {
        kp >= 9 - 1e-6 -> 5; kp >= 8 - 0.34 -> 4; kp >= 7 - 0.34 -> 3; kp >= 6 - 0.34 -> 2; kp >= 5 - 0.34 -> 1; else -> 0
    }

    /** The radio blackout scale for an X-ray flux: R1 at M1, R2 at M5, R3 at X1, R4 at X10, R5 at X20. */
    fun echelleR(flux: Double): Int = when { flux >= 2e-3 -> 5; flux >= 1e-3 -> 4; flux >= 1e-4 -> 3; flux >= 5e-5 -> 2; flux >= 1e-5 -> 1; else -> 0 }

    /** "calme" (Kp < 4), "agite" (4), "orage" (5–6), "fort" (7+): the gauge's colour. */
    fun niveauKp(kp: Double): String = when { kp >= 6.67 -> "fort"; kp >= 4.67 -> "orage"; kp >= 3.67 -> "agite"; else -> "calme" }

    /** Kp as it is written: 3.33 → "3+", 3.67 → "4−", 4.0 → "4". */
    fun kpTexte(kp: Double): String {
        val tiers = Math.round(kp * 3).toInt()
        val e = (tiers + 1) / 3
        return when (tiers - 3 * e) { -1 -> "$e−"; 1 -> "$e+"; else -> "$e" }
    }

    /** The latest Kp that is not a prediction (the forecast product's estimate, else the observed one). */
    fun kpActuel(e: Etat, maintenant: Long): Kp? =
        (e.prevision.filter { it.nature != "prevu" && it.t <= maintenant } + e.kp.filter { it.t <= maintenant }).maxByOrNull { it.t }

    /** The highest X-ray flux of the last [ms]. */
    fun rayonsMax(e: Etat, maintenant: Long, ms: Long = 3_600_000L): Rayons? = e.rayons.filter { it.t > maintenant - ms && it.t <= maintenant }.maxByOrNull { it.flux }

    /**
     * The alerts of now: a storm (Kp ≥ 5 observed or estimated in the last
     * six hours), a strong flare (X-ray ≥ M5 in the last hour). Each has a
     * key: its 3-hour interval, its hour; shown once.
     */
    fun alertes(e: Etat, maintenant: Long): List<Alerte> {
        val l = ArrayList<Alerte>()
        val k = (e.prevision.filter { it.nature != "prevu" } + e.kp).filter { it.t in (maintenant - 6 * 3_600_000L)..maintenant && it.kp >= KP_ALERTE - 0.34 }
            .maxByOrNull { it.kp }
        if (k != null) {
            val g = echelleG(k.kp)
            l += Alerte("kp-" + k.t, k.t, "Orage géomagnétique G$g (Kp ${kpTexte(k.kp)})",
                "HF perturbée, surtout sur les trajets polaires ; aurore possible sur 2 m et 6 m (antenne vers le nord, son « rauque »)" +
                    (if (g >= 3) " ; trafic satellite gêné par la scintillation" else ""), if (g >= 3) "fort" else "orage")
        }
        val r = rayonsMax(e, maintenant)
        if (r != null && r.flux >= X_ALERTE) {
            l += Alerte("x-" + (r.t / 3_600_000L), r.t, "Éruption solaire " + classeX(r.flux) + " (R" + echelleR(r.flux) + ")",
                "Coupure HF possible du côté jour (évanouissement brusque), surtout sous 30 MHz ; particules et orage possibles un à trois jours plus tard", "fort")
        }
        return l
    }

    // ---------------------------------------------------------------- the page

    fun json(e: Etat, maintenant: Long): JSONObject {
        val o = JSONObject()
        kpActuel(e, maintenant)?.let { k ->
            o.put("kp", JSONObject().put("t", k.t).put("kp", k.kp).put("texte", kpTexte(k.kp)).put("niveau", niveauKp(k.kp)).put("g", echelleG(k.kp)).put("nature", k.nature))
        }
        // The bars: from a day back to the end of the forecast.
        val barres = e.prevision.filter { it.t >= maintenant - 24 * 3_600_000L }.ifEmpty { e.kp.filter { it.t >= maintenant - 72 * 3_600_000L } }
        o.put("barres", JSONArray(barres.map { JSONObject().put("t", it.t).put("kp", it.kp).put("nature", it.nature).put("niveau", niveauKp(it.kp)).put("echelle", it.echelle ?: "") }))
        val prevus = e.prevision.filter { it.nature == "prevu" && it.t > maintenant }
        prevus.maxByOrNull { it.kp }?.let { o.put("kpMaxPrevu", JSONObject().put("t", it.t).put("kp", it.kp).put("texte", kpTexte(it.kp)).put("g", echelleG(it.kp))) }
        e.flux?.let { o.put("f107", JSONObject().put("t", it.t).put("sfu", Math.round(it.sfu)).put("moyenne90", it.moyenne90 ?: JSONObject.NULL)) }
        e.rayons.lastOrNull()?.let { r ->
            val x = JSONObject().put("t", r.t).put("flux", r.flux).put("classe", classeX(r.flux)).put("r", echelleR(r.flux))
            rayonsMax(e, maintenant, 6 * 3_600_000L)?.let { m -> x.put("max6h", classeX(m.flux)).put("max6hT", m.t) }
            // A point every 5 minutes for the sparkline: log10 of the flux.
            x.put("courbe", JSONArray(e.rayons.filter { it.t % 300_000L == 0L }.map { JSONArray(listOf(it.t, Math.round(log10(it.flux) * 100) / 100.0)) }))
            o.put("rayons", x)
        }
        e.vent?.let { v ->
            o.put("vent", JSONObject().put("t", v.t).put("vitesse", v.vitesse ?: JSONObject.NULL).put("bz", v.bz ?: JSONObject.NULL).put("bt", v.bt ?: JSONObject.NULL))
        }
        e.aurore?.let { a ->
            o.put("aurore", JSONObject().put("t", a.observation).put("prevision", a.prevision).put("ici", a.ici).put("proche", a.proche)
                .put("procheLat", a.procheLat ?: JSONObject.NULL).put("procheLon", a.procheLon ?: JSONObject.NULL).put("rayonKm", a.rayonKm))
        }
        o.put("alertes", JSONArray(alertes(e, maintenant).map { JSONObject().put("cle", it.cle).put("t", it.t).put("titre", it.titre).put("texte", it.texte).put("niveau", it.niveau) }))
        return o
    }

    /** The oval for the map: [lon, lat, %] every second degree, 10 % or more. */
    fun ovaleJson(a: Aurore?): JSONArray = JSONArray((a?.ovale ?: emptyList()).map { JSONArray(listOf(it[0], it[1], it[2])) })
}
