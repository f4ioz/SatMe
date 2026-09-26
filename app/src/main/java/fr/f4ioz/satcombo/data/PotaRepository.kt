/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class PotaPark(
    val reference: String,
    val name: String,
    val latDeg: Double,
    val lonDeg: Double,
    /** Official INPN / Natura 2000 code parsed from the name, if any (e.g. FR5312009). */
    val inpn: String? = null
)

/** A nearby park with distance and whether the QTH falls inside its real boundary. */
data class PotaHit(val park: PotaPark, val distanceKm: Double, val inside: Boolean)

/**
 * POTA parks: embedded Europe list (offline), optional worldwide fetch when the
 * user is logged in, and real point-in-polygon containment via OSM/Overpass
 * boundaries matched on the INPN code.
 */
class PotaRepository(private val context: Context) {

    @Volatile private var parks: List<PotaPark>? = null
    private val polyDir = File(context.filesDir, "pota_poly").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private val inpnRegex = Regex("FR\\d{7}")
    private val regionFile = File(context.filesDir, "pota_region.json")
    private val regionMeta = context.getSharedPreferences("satcombo_pota", Context.MODE_PRIVATE)

    val downloadedRegion: String? get() = regionMeta.getString("region", null)
    val downloadedCount: Int get() = regionMeta.getInt("count", 0)
    val downloadedAtMs: Long get() = regionMeta.getLong("ts", 0L)


    suspend fun ensureLoaded(): List<PotaPark> {
        parks?.let { return it }
        return withContext(Dispatchers.Default) {
            val loaded = runCatching {
                // The regional catalogue is no longer bundled: it is downloaded from
                // pota.app when first needed, or imported. See PotaZones for why.
                val text = if (regionFile.exists()) regionFile.readText() else ""
                if (text.isBlank()) emptyList() else parseArray(text)
            }.getOrDefault(emptyList())
            parks = loaded
            loaded
        }
    }

    private fun parseArray(text: String): List<PotaPark> {
        val arr = JSONArray(text)
        return ArrayList<PotaPark>(arr.length()).apply {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                val name = p.getString(3)
                add(PotaPark(p.getString(2), name, p.getDouble(1), p.getDouble(0),
                    inpnRegex.find(name)?.value))
            }
        }
    }

    /**
     * Download the public POTA park export and keep only [region], cached on disk.
     * Returns the number of parks stored, or null on failure.
     */
    suspend fun updateRegion(region: PotaRegion): Int? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://pota.app/all_parks_ext.csv")
            .header("User-Agent", "SatCombo/3.6 amateur-radio app (F4IOZ)")
            .build()
        runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val out = StringBuilder("[")
                var n = 0
                r.body?.charStream()?.buffered()?.useLines { lines ->
                    var first = true
                    for (line in lines) {
                        if (first) { first = false; continue } // header
                        val f = parseCsvLine(line)
                        if (f.size < 8) continue
                        if (f[2] != "1") continue // active
                        val lat = f[5].toDoubleOrNull() ?: continue
                        val lon = f[6].toDoubleOrNull() ?: continue
                        if (!region.contains(lat, lon)) continue
                        val ref = f[0]; val name = f[1].replace("\"", "'")
                        if (n > 0) out.append(',')
                        out.append("[").append(lon).append(',').append(lat)
                            .append(",\"").append(ref).append("\",\"")
                            .append(name.take(48)).append("\"]")
                        n++
                    }
                }
                out.append(']')
                regionFile.writeText(out.toString())
                regionMeta.edit()
                    .putString("region", region.label)
                    .putInt("count", n)
                    .putLong("ts", System.currentTimeMillis())
                    .apply()
                parks = null // force reload
                n
            }
        }.getOrNull()
    }

    /** Minimal CSV splitter handling the quoted fields in the POTA export. */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inq = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inq = !inq
                c == ',' && !inq -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out.map { it.trim() }
    }

    /** All parks within a lat/lon window (for drawing on the map). */
    suspend fun inBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
                         limit: Int = 400): List<PotaPark> = withContext(Dispatchers.Default) {
        ensureLoaded().asSequence()
            .filter { it.latDeg in minLat..maxLat && it.lonDeg in minLon..maxLon }
            .take(limit).toList()
    }

    /**
     * Parks near (lat, lon). Computes precise containment for the closest few by
     * fetching their OSM boundary (cached). Sorted: inside first, then distance.
     */
    /**
     * Nearby parks — distance measured to the **boundary**, not the centre.
     *
     * A park's centre says nothing of its extent: a coastal reserve can stretch
     * for kilometres, so you can be inside far from the centre and outside close
     * to it. With a known outline (bundled or cached) the distance is to the
     * nearest edge, zero when inside; otherwise to the centre.
     *
     * The pre-filter stays centre-based with a 25 km margin, which covers the
     * largest parks.
     */
    suspend fun near(
        lat: Double, lon: Double, radiusKm: Double = 8.0, context: Context? = null
    ): List<PotaHit> =
        withContext(Dispatchers.Default) {
            val candidates = ensureLoaded().asSequence()
                .map { it to haversineKm(lat, lon, it.latDeg, it.lonDeg) }
                .filter { it.second <= radiusKm + 25.0 }
                .sortedBy { it.second }
                .take(40)
                .toList()

            val hits = candidates.mapNotNull { (park, distCentre) ->
                val zone = context?.let {
                    runCatching { PotaZones.zone(it, park.reference) }.getOrNull()
                }
                val dedans = when {
                    zone != null -> zone.contient(lat, lon)
                    park.inpn != null -> runCatching {
                        polygonFor(park.inpn)?.let { pointInMultiPolygon(lat, lon, it) } ?: false
                    }.getOrDefault(false)
                    else -> false
                }
                val dist = when {
                    dedans -> 0.0
                    zone != null -> distanceAuBordKm(lat, lon, zone)
                    else -> distCentre
                }
                if (dist > radiusKm) null else PotaHit(park, dist, dedans)
            }
            hits.sortedWith(compareByDescending<PotaHit> { it.inside }.thenBy { it.distanceKm })
        }

    /**
     * Distance to the nearest edge of a zone, in km. Computed on vertices only:
     * the outline is simplified to about ten metres, so point-to-segment distance
     * would be finer than the data.
     */
    private fun distanceAuBordKm(
        lat: Double, lon: Double, zone: PotaZones.Zone
    ): Double {
        var min = Double.MAX_VALUE
        for (a in zone.anneaux) {
            var i = 0
            while (i < a.size) {
                val d = haversineKm(lat, lon, a[i], a[i + 1])
                if (d < min) min = d
                i += 2
            }
        }
        return if (min == Double.MAX_VALUE) Double.MAX_VALUE else min
    }

    // ---------- OSM boundary polygons (cached) ----------

    /** List of rings; each ring is a flat [lat,lon,lat,lon,...] array. */
    private suspend fun polygonFor(inpn: String): List<DoubleArray>? = withContext(Dispatchers.IO) {
        val cache = File(polyDir, "$inpn.json")
        val raw = if (cache.exists()) cache.readText()
        else fetchOverpass(inpn)?.also { cache.writeText(it) }
        raw?.let { parseRings(it) }
    }

    private fun fetchOverpass(inpn: String): String? {
        val query = "[out:json][timeout:30];relation[\"ref:FR:INPN\"=\"$inpn\"];out geom;"
        val req = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(okhttp3.FormBody.Builder().add("data", query).build())
            .header("User-Agent", "SatCombo/3.5 amateur-radio app (F4IOZ)")
            .build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        }.getOrNull()
    }

    private fun parseRings(json: String): List<DoubleArray>? = runCatching {
        val root = JSONObject(json)
        val els = root.optJSONArray("elements") ?: return null
        val rings = ArrayList<DoubleArray>()
        for (i in 0 until els.length()) {
            val rel = els.optJSONObject(i) ?: continue
            val members = rel.optJSONArray("members") ?: continue
            for (m in 0 until members.length()) {
                val mem = members.optJSONObject(m) ?: continue
                if (mem.optString("type") != "way") continue
                val geom = mem.optJSONArray("geometry") ?: continue
                val ring = DoubleArray(geom.length() * 2)
                for (g in 0 until geom.length()) {
                    val pt = geom.getJSONObject(g)
                    ring[g * 2] = pt.getDouble("lat")
                    ring[g * 2 + 1] = pt.getDouble("lon")
                }
                if (ring.size >= 6) rings.add(ring)
            }
        }
        rings.ifEmpty { null }
    }.getOrNull()

    /** True if the point is inside any ring (ray casting). */
    private fun pointInMultiPolygon(lat: Double, lon: Double, rings: List<DoubleArray>): Boolean {
        for (ring in rings) if (pointInRing(lat, lon, ring)) return true
        return false
    }

    private fun pointInRing(lat: Double, lon: Double, r: DoubleArray): Boolean {
        var inside = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val yi = r[i * 2]; val xi = r[i * 2 + 1]
            val yj = r[j * 2]; val xj = r[j * 2 + 1]
            if (((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) inside = !inside
            j = i
        }
        return inside
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** Geographic regions selectable for the online POTA update. */
enum class PotaRegion(val label: String, val minLat: Double, val maxLat: Double,
                      val minLon: Double, val maxLon: Double) {
    EUROPE("Europe", 35.0, 72.0, -12.0, 32.0),
    NORTH_AMERICA("Amérique du Nord", 14.0, 72.0, -170.0, -52.0),
    SOUTH_AMERICA("Amérique du Sud", -56.0, 14.0, -82.0, -34.0),
    AFRICA("Afrique", -35.0, 38.0, -19.0, 52.0),
    ASIA("Asie", 5.0, 75.0, 32.0, 150.0),
    OCEANIA("Océanie", -50.0, 0.0, 110.0, 180.0),
    WORLD("Monde entier", -90.0, 90.0, -180.0, 180.0);

    fun contains(lat: Double, lon: Double) =
        lat in minLat..maxLat && lon in minLon..maxLon
}
