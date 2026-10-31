/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The pictures received in telemetry, as the page's common « Images »
 * gallery lists them next to the SSTV ones (`GET api/telemesure/images`):
 *
 * - the SSDV pictures put together over the passes ([AssemblageSsdv]),
 *   under way or finished, then the finished ones only on disk
 *   (`telemesure/ssdv/<id>.jpg`, out of the memory's list);
 * - the Geoscan pictures (JPEG chunks, [Geoscan.Images]), then those
 *   finished on disk (`telemesure/geoscan/<id>.jpg`).
 *
 * One shape for all, newest first ([cle] unique): its kind, time,
 * satellite, how complete, the address of its JPEG (with a version that
 * changes as packets come, so a picture growing during a pass is fetched
 * again), and the decoder's own JSON in « detail » for the viewer. Only
 * reads: nothing is decoded, written or sent here.
 */
object GalerieTelemesure {

    const val SSDV = "ssdv"
    const val GEOSCAN = "geoscan"
    const val MAX = 200

    /** The address of a picture's JPEG, from the page (the route of ServeurPc). */
    private const val JPEG = "api/telemesure/ssdv.jpg?"

    /** A saved SSDV picture's name: `yyyyMMdd-HHmmss-<callsign>-<image number>[-k].jpg` (AssemblageSsdv). */
    private val NOM_SSDV = Regex("(\\d{8})-(\\d{6})-([A-Za-z0-9_-]+?)-(\\d{1,3})(?:-\\d+)?(-essai)?\\.jpg")
    /** A saved Geoscan picture's name: `geoscan-yyyyMMdd-HHmmss-<satellite id>-<file|v1>.jpg` (Geoscan.Images). */
    private val NOM_GEOSCAN = Regex("geoscan-(\\d{8})-(\\d{6})-(\\d{1,3})-(\\d+|v1)\\.jpg")
    /** What a saved file's name may be to be served (TelemesurePc.jpegSsdv). */
    val FICHIER = Regex("[A-Za-z0-9_.-]+\\.jpg")

    /**
     * The list: [ssdv] the assemblies (the station's, the test's), [geoscan]
     * the Geoscan pictures (with true for a test's), [dossier] the data
     * folder whose saved pictures complete the list. At most [max], newest first.
     */
    fun json(ssdv: List<AssemblageSsdv>, geoscan: List<Pair<Geoscan.Images, Boolean>>, dossier: File?, max: Int = MAX): JSONObject {
        val l = ArrayList<JSONObject>()
        val fichiers = HashSet<String>()
        for (a in ssdv) for (ph in a.photos()) {
            ph.fichier?.let { fichiers += it }
            fichiers += ph.id + ".jpg"
            l += ssdv(a, ph)
        }
        dossier?.let { d -> sauvees(File(d, "telemesure/ssdv")).filter { it.name !in fichiers }.forEach { l += ssdvSauvee(it) } }
        val ids = HashSet<String>()
        for ((g, essai) in geoscan) for (i in g.liste()) { ids += i.id; l += geoscan(i, essai) }
        dossier?.let { d -> sauvees(File(d, "telemesure/geoscan")).filter { it.name.removeSuffix(".jpg") !in ids }.forEach { l += geoscanSauvee(it) } }
        val tri = l.sortedByDescending { it.optLong("t") }
        val n = JSONObject().put(SSDV, tri.count { it.optString("type") == SSDV }).put(GEOSCAN, tri.count { it.optString("type") == GEOSCAN })
            .put("enCours", tri.count { it.optBoolean("enCours") })
        return JSONObject().put("ok", true).put("n", n).put("images", JSONArray(tri.take(max)))
    }

    private fun sauvees(d: File): List<File> =
        d.listFiles { f -> f.isFile && FICHIER.matches(f.name) }.orEmpty().sortedByDescending { it.lastModified() }.take(MAX)

    private fun ssdv(a: AssemblageSsdv, ph: AssemblageSsdv.Photo): JSONObject {
        val d = a.json(ph)
        return JSONObject().put("type", SSDV).put("cle", "ssdv:" + ph.id).put("t", ph.miseAJour).put("debut", ph.debut)
            .put("satellite", ph.satellite).put("titre", ph.indicatif + " n° " + ph.numero)
            .put("complete", ph.complete).put("enCours", !ph.complete).put("essai", ph.essai)
            .put("recus", ph.recus).put("total", d.optInt("total")).put("estime", d.optBoolean("estime"))
            .put("manquants", d.optInt("manquants")).put("pourcent", d.optInt("pourcent"))
            .put("largeur", ph.largeur).put("hauteur", ph.hauteur).put("passages", ph.passages.size)
            .put("src", JPEG + "cle=" + enc(ph.id) + "&v=" + ph.recus).put("detail", d)
    }

    private fun ssdvSauvee(f: File): JSONObject {
        val m = NOM_SSDV.matchEntire(f.name)
        return JSONObject().put("type", SSDV).put("cle", "ssdv-f:" + f.name).put("t", f.lastModified())
            .put("debut", m?.let { utc(it.groupValues[1], it.groupValues[2]) } ?: f.lastModified())
            .put("satellite", "").put("titre", m?.let { it.groupValues[3] + " n° " + it.groupValues[4] } ?: f.name.removeSuffix(".jpg"))
            .put("complete", true).put("enCours", false).put("essai", m?.groupValues?.get(5)?.isNotEmpty() == true)
            .put("fichier", f.name).put("src", JPEG + "f=" + enc(f.name))
    }

    private fun geoscan(i: Geoscan.Images.Image, essai: Boolean): JSONObject {
        val m = i.manquants()
        return JSONObject().put("type", GEOSCAN).put("cle", "geoscan:" + i.id).put("t", i.miseAJour).put("debut", i.debut)
            .put("satellite", i.nomSat).put("titre", i.nomSat + if (i.fichier >= 0) " · fichier " + i.fichier else "")
            .put("complete", i.complete).put("enCours", !i.complete).put("essai", essai)
            .put("morceaux", i.morceaux.size).put("octets", i.octets).put("manquants", m)
            // Nothing to show before the chunk starting with FF D8.
            .put("src", if (i.base != null) JPEG + "geo=" + enc(i.id) + "&v=" + i.morceaux.size else JSONObject.NULL)
            .put("detail", JSONObject().put("cle", i.id).put("satellite", i.nomSat).put("sat", i.sat).put("fichier", i.fichier).put("octets", i.octets)
                .put("morceaux", i.morceaux.size).put("debut", i.base != null).put("manquants", m).put("complete", i.complete)
                .put("t", i.miseAJour).put("premier", i.debut).put("essai", essai))
    }

    private fun geoscanSauvee(f: File): JSONObject {
        val m = NOM_GEOSCAN.matchEntire(f.name)
        val sat = m?.groupValues?.get(3)?.toIntOrNull()
        val nom = sat?.let { Geoscan.SATELLITES[it] ?: "Geoscan #$it" } ?: "Geoscan"
        val fichier = m?.groupValues?.get(4)?.takeIf { it != "v1" }
        return JSONObject().put("type", GEOSCAN).put("cle", "geoscan-f:" + f.name).put("t", f.lastModified())
            .put("debut", m?.let { utc(it.groupValues[1], it.groupValues[2]) } ?: f.lastModified())
            .put("satellite", nom).put("titre", nom + (fichier?.let { " · fichier $it" } ?: ""))
            .put("complete", true).put("enCours", false).put("essai", false)
            .put("fichier", f.name).put("src", JPEG + "f=" + enc(f.name))
    }

    private fun utc(jour: String, heure: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(jour + heure)?.time
    }.getOrNull()

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
