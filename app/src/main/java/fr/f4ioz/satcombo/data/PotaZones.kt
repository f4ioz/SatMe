/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import fr.f4ioz.satcombo.R
import fr.f4ioz.satcombo.domain.Pays
import org.json.JSONObject

/**
 * Les contours des parcs POTA — l'emprise réelle, pas le point central.
 *
 * Être « au parc » se juge au polygone : à deux kilomètres du centre on peut
 * être dehors, et dans un grand parc on peut être dedans à cinq. Les contours
 * viennent de pota-map.fr (merci à son auteur), récupérés un par un avec une
 * seconde et demie de pause, simplifiés à une dizaine de mètres — assez fin
 * pour dire dedans/dehors, assez court pour être embarqués.
 *
 * Trois étages, du plus rapide au plus lent : l'embarqué (les parcs autour du
 * pays de l'auteur pour cette première version), le cache disque (ce qu'on a
 * déjà été chercher), le réseau (pota-map.fr, à la demande, mis en cache
 * aussitôt). Sans réseau et sans contour, l'application retombe sur le rayon
 * de trois kilomètres autour du point central — un plus quand il est là,
 * jamais une condition.
 */
object PotaZones {

    class Zone(val ref: String, val nom: String, val anneaux: List<DoubleArray>) {
        /**
         * La boîte englobante de chaque anneau, calculée une fois au
         * chargement. Le test « suis-je dedans » compare d'abord quatre
         * bornes — moins d'une microseconde — et ne parcourt le polygone que
         * si le point est dans la boîte : mesuré à 0,03 ms pour 158 parcs au
         * lieu de 1,2 ms en parcours complet, et la marge tient la France
         * entière.
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

    /** Le fichier importé par l'opérateur, s'il en a posé un. */
    fun fichierImporte(context: Context) = java.io.File(context.filesDir, "pota_zones.json")

    /** Combien de parcs dans le fichier importé, 0 s'il n'y en a pas. */
    fun compteImporte(context: Context): Int =
        if (fichierImporte(context).exists()) charge(context).size else 0

    /** Oublie ce qui est chargé : à appeler après un import. */
    fun rafraichis() { embarque = null }

    private fun charge(context: Context): Map<String, Zone> {
        embarque?.let { return it }
        val lu = runCatching {
            // Le fichier posé par l'opérateur prime sur l'embarqué : il couvre
            // plus large, et c'est tout son intérêt. L'embarqué reste le
            // filet — 158 parcs autour du QTH d'origine, pour que
            // l'application marche sans rien faire.
            // **Plus rien d'embarqué.** Les contours de parcs venaient d'un
            // relevé personnel sur pota-map.fr : les redistribuer dans une
            // source publique supposerait que leurs conditions l'autorisent,
            // ce qui n'est pas acquis. L'opérateur charge son propre fichier,
            // et à défaut les contours se demandent un par un au réseau — ce
            // que l'application savait déjà faire.
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

    /** Le contour d'un parc, embarqué ou en cache disque, sinon rien. */
    fun zone(context: Context, ref: String): Zone? {
        charge(context)[ref]?.let { return it }
        return litCache(context, ref)
    }

    /** Le parc dont l'emprise contient le point, s'il y en a un. */
    fun zoneContenant(context: Context, lat: Double, lon: Double): Zone? =
        charge(context).values.firstOrNull { it.contient(lat, lon) }
            ?: cacheContenant(context, lat, lon)

    // ---- cache disque : un fichier par parc, jamais expiré -----------------

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
     * Va chercher un contour sur pota-map.fr et le met en cache.
     *
     * Appelé hors du fil principal, pour un parc à la fois — jamais de
     * rafale : le site est celui d'un radioamateur, pas un CDN. Le contour
     * est simplifié à la volée au même grain que l'embarqué.
     */
    fun telecharge(context: Context, ref: String): Zone? {
        litCache(context, ref)?.let { return it }
        return runCatching {
            val url = java.net.URL("https://pota-map.fr/api/boundary/$ref")
            val co = url.openConnection() as java.net.HttpURLConnection
            co.connectTimeout = 8000; co.readTimeout = 12000
            co.setRequestProperty("User-Agent", "SatMe (f4ioz.fr)")
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
            // En cache, au format de l'embarqué.
            val o = JSONObject().put("n", "").put("r", org.json.JSONArray(ann.map {
                org.json.JSONArray(it.toList())
            }))
            java.io.File(dossier(context), "$ref.json").writeText(o.toString())
            z
        }.getOrNull()
    }
}
