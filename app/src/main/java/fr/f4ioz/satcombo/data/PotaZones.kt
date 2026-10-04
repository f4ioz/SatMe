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
import fr.f4ioz.satcombo.R
import fr.f4ioz.satcombo.domain.Pays
import org.json.JSONObject

/**
 * POTA park outlines — the real extent, not the centre point.
 *
 * Being "in the park" is decided by the polygon: two km from the centre can be
 * outside, five km inside a large park. Outlines come from pota-map.fr (thanks
 * to its author), simplified to about ten metres.
 *
 * Three tiers, fastest first: the file imported by the operator, the disk
 * cache, then the network (pota-map.fr, on demand, cached at once). With no
 * outline, the app falls back to a 3 km radius around the centre — outlines
 * are a bonus, never a requirement.
 */
object PotaZones {

    class Zone(val ref: String, val nom: String, val anneaux: List<DoubleArray>) {
        /**
         * Bounding box of each ring, computed once on load. The inside test
         * compares four bounds first and walks the polygon only inside the box:
         * 0.03 ms for 158 parks instead of 1.2 ms.
         */
        val boites: List<DoubleArray> = anneaux.map { a ->
            var la = Double.MAX_VALUE; var La = -Double.MAX_VALUE
            var lo = Double.MAX_VALUE; var Lo = -Double.MAX_VALUE
            var i = 0
            while (i < a.size) {
                if (a[i] < la) la = a[i]; if (a[i] > La) La = a[i]
                if (a[i + 1] < lo) lo = a[i + 1]; if (a[i + 1] > Lo) Lo = a[i + 1]
                i += 2
            }
            doubleArrayOf(la, La, lo, Lo)
        }

        fun contient(lat: Double, lon: Double): Boolean {
            for (i in anneaux.indices) {
                val b = boites[i]
                if (lat < b[0] || lat > b[1] || lon < b[2] || lon > b[3]) continue
                if (Pays.dansAnneau(anneaux[i], lat, lon)) return true
            }
            return false
        }
    }

    @Volatile private var embarque: Map<String, Zone>? = null

    /** The file imported by the operator, if any. */
    fun fichierImporte(context: Context) = java.io.File(context.filesDir, "pota_zones.json")

    /** Number of parks in the imported file, 0 if none. */
    fun compteImporte(context: Context): Int =
        if (fichierImporte(context).exists()) charge(context).size else 0

    /** Forgets what is loaded: call after an import. */
    fun rafraichis() { embarque = null }

    private fun charge(context: Context): Map<String, Zone> {
        embarque?.let { return it }
        val lu = runCatching {
            // **Nothing bundled any more** (`embarque` now holds the imported
            // file). The outlines came from a personal scrape of pota-map.fr,
            // and redistributing them publicly is not clearly allowed. The
            // operator imports their own file; otherwise outlines are fetched
            // one by one from the network.
            val f = fichierImporte(context)
            val txt = if (f.exists() && f.length() > 100) f.readText() else ""
            if (txt.isBlank()) return emptyMap<String, Zone>().also { embarque = it }
            val racine = JSONObject(txt)
            val m = HashMap<String, Zone>(racine.length())
            val cles = racine.keys()
            while (cles.hasNext()) {
                val ref = cles.next()
                val o = racine.getJSONObject(ref)
                val arr = o.getJSONArray("r")
                val ann = ArrayList<DoubleArray>(arr.length())
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    val d = DoubleArray(a.length())
                    for (j in 0 until a.length()) d[j] = a.getDouble(j)
                    ann.add(d)
                }
                m[ref] = Zone(ref, o.optString("n", ref), ann)
            }
            m
        }.getOrDefault(emptyMap())
        embarque = lu
        return lu
    }

    /** A park's outline, from the imported file or disk cache, else null. */
    fun zone(context: Context, ref: String): Zone? {
        charge(context)[ref]?.let { return it }
        return litCache(context, ref)
    }

    /** The park whose outline contains the point, if any. */
    fun zoneContenant(context: Context, lat: Double, lon: Double): Zone? =
        charge(context).values.firstOrNull { it.contient(lat, lon) }
            ?: cacheContenant(context, lat, lon)

    // ---- disk cache: one file per park, never expires ----------------------

    private fun dossier(context: Context) =
        java.io.File(context.filesDir, "pota_zones").apply { mkdirs() }

    private fun litCache(context: Context, ref: String): Zone? {
        val f = java.io.File(dossier(context), "$ref.json")
        if (!f.exists()) return null
        return runCatching { litZone(ref, JSONObject(f.readText())) }.getOrNull()
    }

    private fun cacheContenant(context: Context, lat: Double, lon: Double): Zone? {
        val fichiers = dossier(context).listFiles() ?: return null
        for (f in fichiers) {
            val z = runCatching {
                litZone(f.name.removeSuffix(".json"), JSONObject(f.readText()))
            }.getOrNull() ?: continue
            if (z.contient(lat, lon)) return z
        }
        return null
    }

    private fun litZone(ref: String, o: JSONObject): Zone {
        val arr = o.getJSONArray("r")
        val ann = ArrayList<DoubleArray>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            val d = DoubleArray(a.length())
            for (j in 0 until a.length()) d[j] = a.getDouble(j)
            ann.add(d)
        }
        return Zone(ref, o.optString("n", ref), ann)
    }

    /**
     * Fetches an outline from pota-map.fr and caches it.
     *
     * Off the main thread, one park at a time — never in bursts: the site is
     * a fellow ham's, not a CDN. Simplified on the fly to the usual grain.
     */
    fun telecharge(context: Context, ref: String): Zone? {
        litCache(context, ref)?.let { return it }
        return runCatching {
            val url = java.net.URL("https://pota-map.fr/api/boundary/$ref")
            val co = url.openConnection() as java.net.HttpURLConnection
            co.connectTimeout = 8000; co.readTimeout = 12000
            co.setRequestProperty("User-Agent", TleRepository.USER_AGENT)
            val txt = co.inputStream.bufferedReader().use { it.readText() }
            val b = JSONObject(txt).optJSONObject("boundary") ?: return null
            val coords = b.getJSONArray("coordinates")
            val multi = b.optString("type") == "MultiPolygon"
            val ann = ArrayList<DoubleArray>()
            val nPolys = if (multi) coords.length() else 1
            for (i in 0 until nPolys) {
                val ring = (if (multi) coords.getJSONArray(i) else coords).getJSONArray(0)
                val pts = ArrayList<DoubleArray>(ring.length())
                for (j in 0 until ring.length()) {
                    val c = ring.getJSONArray(j)
                    pts.add(doubleArrayOf(c.getDouble(1), c.getDouble(0))) // lat, lon
                }
                val simple = fr.f4ioz.satcombo.domain.Simplifie.anneau(pts, 0.0001)
                if (simple.size >= 4) {
                    val plat = DoubleArray(simple.size * 2)
                    simple.forEachIndexed { k, p -> plat[2*k] = p[0]; plat[2*k+1] = p[1] }
                    ann.add(plat)
                }
            }
            if (ann.isEmpty()) return null
            ann.sortByDescending { it.size }
            val z = Zone(ref, "", ann)
            // Cached in the same format as the imported file.
            val o = JSONObject().put("n", "").put("r", org.json.JSONArray(ann.map {
                org.json.JSONArray(it.toList())
            }))
            java.io.File(dossier(context), "$ref.json").writeText(o.toString())
            z
        }.getOrNull()
    }
}
