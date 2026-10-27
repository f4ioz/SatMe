/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.hf

import fr.f4ioz.site.securite.Limiteur
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * WSPR spots from wspr.live (WSPRnet's data in ClickHouse, table `wspr.rx`,
 * HTTP interface `?query=`). wspr.live asks for fast queries filtered by
 * time and band, for free and non-commercial use, and allows 20 requests a
 * minute: each answer is kept two minutes and the whole site sends at most
 * ten a minute. Nothing a visitor typed reaches the SQL but numbers clamped
 * here, band codes from our own table and a callsign stripped to
 * `[A-Z0-9/]`, twelve characters.
 */
class WsprLive(private val amont: Amont, serveur: String, horloge: () -> Long = System::currentTimeMillis) {

    private val base = serveur.trimEnd('/')
    private val memo = Memo<JSONObject>(amont, 2 * 60_000L, 2 * 60_000L, 64, horloge)
    private val debit = Limiteur(10, 60_000L, horloge)

    private fun requete(sql: String): JSONObject {
        if (!debit.accepte("wspr")) throw IOException("wspr.live : trop de requêtes, patienter")
        val t = amont.texte("$base/?query=" + URLEncoder.encode("$sql FORMAT JSON", Charsets.UTF_8), 8 * 1024 * 1024, 30)
        return JSONObject(t)
    }

    private fun garde(cle: String, calcul: () -> JSONObject): JSONObject {
        val l = memo.lis(cle, 12_000L, calcul)
        return l.valeur ?: JSONObject().put("error", l.erreur ?: "wspr.live ne répond pas encore")
    }

    /** Activity per band over the last [minutes] (5..180). */
    fun bandes(minutes: Int): JSONObject {
        val m = minutes.coerceIn(5, 180)
        return garde("bandes_$m") {
            JSONObject().put("bands", activite(requete(
                "SELECT band, count() AS spots, uniqExact(rx_sign) AS reporters, round(quantile(0.5)(distance)) AS dist_med, " +
                    "round(max(distance)) AS dist_max, round(avg(snr),1) AS snr_avg FROM wspr.rx WHERE time > subtractMinutes(now(), $m) " +
                    "GROUP BY band ORDER BY band"))).put("minutes", m)
        }
    }

    /** The propagation map's blobs (receivers' 6° cells) and counts per band, last [minutes] (5..120). */
    fun carte(minutes: Int, bande: String): JSONObject {
        val m = minutes.coerceIn(5, 120)
        val code = Bandes.wsprCode(bande)
        val filtre = if (code != null) "AND band = $code " else ""
        return garde("carte_${m}_${code ?: ""}") {
            val blobs = JSONArray()
            for (r in lignes(requete("SELECT round(avg(rx_lat),2) AS lat, round(avg(rx_lon),2) AS lon, count() AS count, round(avg(snr),1) AS snr, " +
                    "arrayElement(topK(1)(band),1) AS top_band FROM wspr.rx WHERE time > subtractMinutes(now(), $m) $filtre" +
                    "GROUP BY round(rx_lat/6), round(rx_lon/6) ORDER BY count DESC LIMIT 160"))) {
                val n = entier(r, "count"); val la = reel(r, "lat"); val lo = reel(r, "lon")
                if (n <= 0 || la == null || lo == null) continue
                blobs.put(JSONObject().put("lat", la).put("lon", lo).put("count", n).put("snr", reel(r, "snr") ?: JSONObject.NULL)
                    .put("bands", JSONObject().put(Bandes.wsprNom(entier(r, "top_band").toInt()), n)).put("dxcc", JSONArray()))
            }
            val parBande = JSONObject(); var total = 0L
            for (r in lignes(requete("SELECT band, count() AS spots FROM wspr.rx WHERE time > subtractMinutes(now(), $m) $filtre" +
                    "GROUP BY band ORDER BY spots DESC"))) {
                val c = entier(r, "spots"); parBande.put(Bandes.wsprNom(entier(r, "band").toInt()), c); total += c
            }
            JSONObject().put("source", "wspr").put("total", total).put("by_band", parBande).put("blobs", blobs).put("qth_paths", JSONArray())
        }
    }

    /**
     * Who hears [indicatif] (`tx`) or whom it hears (`rx`) over the last [heures] (1..48). The bearing is
     * always from the station: `azimuth` (tx→rx) when it transmits, `rx_azimuth` (rx→tx) when it receives
     * (the old site gave the remote's bearing in that case).
     */
    fun empreinte(indicatif: String, heures: Int, direction: String): JSONObject {
        val call = indicatifNet(indicatif)
        val h = heures.coerceIn(1, 48)
        val tx = direction != "rx"
        val dir = if (tx) "tx" else "rx"
        if (call.isEmpty()) return JSONObject().put("call", "").put("direction", dir).put("hours", h).put("spots", JSONArray()).put("count", 0)
            .put("reporters", 0).put("bands", JSONArray())
        val o = garde("fp_${call}_${h}_$dir") {
            val autre = if (tx) "rx" else "tx"; val ici = if (tx) "tx" else "rx"
            val az = if (tx) "azimuth" else "rx_azimuth"
            empreinteDe(requete("SELECT band, ${autre}_sign AS call, ${autre}_loc AS loc, ${autre}_lat AS lat, ${autre}_lon AS lon, " +
                "${ici}_lat AS home_lat, ${ici}_lon AS home_lon, distance AS dist, $az AS az, snr, toUnixTimestamp(time) AS ts FROM wspr.rx " +
                "WHERE time > subtractHours(now(), $h) AND ${ici}_sign = '$call' ORDER BY time DESC LIMIT 3000"), call, dir, h)
        }
        return if (o.has("call")) o else JSONObject(o.toString()).put("call", call).put("direction", dir).put("hours", h)
    }

    companion object {
        /** A callsign for the SQL: upper case, `[A-Z0-9/]` only, twelve characters at most. */
        fun indicatifNet(s: String?): String = (s ?: "").uppercase().replace(Regex("[^A-Z0-9/]"), "").take(12)

        fun lignes(j: JSONObject): List<JSONObject> = j.optJSONArray("data")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

        /** ClickHouse gives 64-bit numbers as strings: read either. */
        fun reel(r: JSONObject, k: String): Double? = when (val v = r.opt(k)) {
            is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null
        }?.takeIf { !it.isNaN() && !it.isInfinite() }

        fun entier(r: JSONObject, k: String): Long = reel(r, k)?.toLong() ?: 0L

        private fun r1(d: Double) = Math.round(d * 10) / 10.0

        fun activite(j: JSONObject): JSONArray {
            val l = lignes(j).map { r ->
                val code = entier(r, "band").toInt()
                JSONObject().put("band", Bandes.wsprNom(code)).put("band_code", code).put("spots", entier(r, "spots"))
                    .put("dist_med", entier(r, "dist_med")).put("dist_max", entier(r, "dist_max")).put("snr_avg", r1(reel(r, "snr_avg") ?: 0.0))
                    .put("reporters", entier(r, "reporters"))
            }.sortedBy { it.getInt("band_code") }
            return JSONArray(l)
        }

        fun empreinteDe(j: JSONObject, call: String, dir: String, heures: Int): JSONObject {
            val rows = lignes(j)
            val spots = JSONArray()
            val stations = HashSet<String>(); val bandes = LinkedHashSet<String>()
            var meilleur: JSONObject? = null
            for (r in rows) {
                val la = reel(r, "lat") ?: continue; val lo = reel(r, "lon") ?: continue
                val s = JSONObject().put("band", Bandes.wsprNom(entier(r, "band").toInt())).put("call", r.optString("call").take(16))
                    .put("loc", r.optString("loc").take(10)).put("lat", Math.round(la * 1000) / 1000.0).put("lon", Math.round(lo * 1000) / 1000.0)
                    .put("dist", entier(r, "dist")).put("az", entier(r, "az")).put("snr", entier(r, "snr")).put("ts", entier(r, "ts"))
                spots.put(s); stations += s.getString("call"); bandes += s.getString("band")
                if (meilleur == null || s.getLong("dist") > meilleur.getLong("dist")) meilleur = s
            }
            val o = JSONObject().put("call", call).put("direction", dir).put("hours", heures).put("spots", spots).put("count", spots.length())
                .put("reporters", stations.size).put("bands", JSONArray(bandes.sortedBy { b -> Bandes.WSPR.entries.firstOrNull { it.value == b }?.key ?: 9999 }))
                .put("best_dx", meilleur ?: JSONObject.NULL)
            rows.firstOrNull()?.let { r -> val la = reel(r, "home_lat"); val lo = reel(r, "home_lon")
                if (la != null && lo != null) o.put("home", JSONObject().put("lat", Math.round(la * 1000) / 1000.0).put("lon", Math.round(lo * 1000) / 1000.0)) }
            return o
        }
    }
}
