/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.hf

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/**
 * « Ouvertures »: when the bands open from a station, read in the Reverse
 * Beacon Network's daily archives (data.reversebeacon.net/rbn_history,
 * one zipped CSV a day, ~3 MB): the spots of stations within 450 km of the
 * locator, by band and UTC hour, and where the skimmers that heard them are.
 *
 * Only the site's own locators ([locators], from the configuration) are
 * read: a visitor cannot make the site download archives for any place it
 * likes (the old site could be made to fetch thirty archives per locator
 * typed, and kept them forever). One archive at a time, in the background;
 * each day kept as a small summary on disk (counts per band and hour, the
 * skimmers, 4° cells for the map), never the raw rows; 32 days at most.
 */
class RbnArchives(
    private val dossier: File,
    val locators: List<String>,
    private val joursMax: Int,
    private val amont: Amont,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The archive of a day (tests give theirs): a zip stream handed to the reader. */
    private val archive: ((String, (InputStream) -> Unit) -> Unit)? = null,
) {
    /** A day's summary for a locator. */
    class Jour(
        val jour: String,
        val spots: Int,
        /** band → hour → [spots, sum of SNR, skimmers]. */
        val parHeure: Map<String, Map<Int, Triple<Int, Long, Set<String>>>>,
        /** "band|hour" → cell key → [spots, sum of SNR]. */
        val cellules: Map<String, Map<Long, LongArray>>,
    )

    private class Etat { @Volatile var enCours = false; @Volatile var erreur: String? = null; @Volatile var essai = 0L }

    private val etats = ConcurrentHashMap<String, Etat>()
    private val jours = ConcurrentHashMap<String, Jour>()
    private val resultats = ConcurrentHashMap<String, JSONObject>()
    private val file = Executors.newSingleThreadExecutor { r -> Thread(r, "rbn-archives").apply { isDaemon = true } }

    fun suivi(loc: String) = loc in locators

    private fun dossierDe(loc: String) = File(dossier, loc)
    private fun fichier(loc: String, jour: String) = File(dossierDe(loc), "$jour.json")

    /** The days wanted now: yesterday back to [joursMax] days ago (UTC), most recent first. */
    fun joursVoulus(): List<String> {
        val hier = LocalDate.ofEpochDay(Math.floorDiv(horloge(), 86_400_000L)).minusDays(1)
        return (0 until joursMax).map { hier.minusDays(it.toLong()).format(JOUR) }
    }

    /** Starts the reading of what is missing for [loc] (one of [locators]); nothing when already running or tried a moment ago. */
    fun assure(loc: String) {
        if (!suivi(loc)) return
        val e = etats.getOrPut(loc) { Etat() }
        synchronized(e) {
            if (e.enCours) return
            val manque = joursVoulus().filter { !fichier(loc, it).isFile }
            purge(loc)
            if (manque.isEmpty()) return
            if (e.erreur != null && horloge() - e.essai < 30 * 60_000L) return
            if (amont.horsLigne && archive == null) return
            e.enCours = true; e.essai = horloge()
            file.submit {
                try {
                    val centre = Geo.locator(loc) ?: return@submit
                    for (j in manque) {
                        try { ingere(loc, centre, j) } catch (x: Exception) { e.erreur = "$j : " + (x.message ?: x.javaClass.simpleName).take(120) }
                    }
                } finally { e.enCours = false }
            }
        }
    }

    /** Days older than 32 days removed. */
    private fun purge(loc: String) {
        val limite = LocalDate.ofEpochDay(Math.floorDiv(horloge(), 86_400_000L)).minusDays(RETENTION_JOURS.toLong()).format(JOUR)
        dossierDe(loc).listFiles()?.forEach { f -> if (f.name.endsWith(".json") && f.name.removeSuffix(".json") < limite) { f.delete(); jours.remove(loc + "/" + f.name.removeSuffix(".json")) } }
        resultats.keys.removeIf { it.startsWith("$loc/") }
    }

    private fun ingere(loc: String, centre: Pair<Double, Double>, jour: String) {
        if (fichier(loc, jour).isFile) return
        var resume: Jour? = null
        val lecteur: (InputStream) -> Unit = { flux -> resume = resumeArchive(flux, jour, centre) }
        if (archive != null) archive.invoke(jour, lecteur)
        else amont.lis("https://data.reversebeacon.net/rbn_history/$jour.zip", 180, lecteur)
        val r = resume ?: return
        dossierDe(loc).mkdirs()
        val tmp = File(dossierDe(loc), "$jour.part")
        tmp.writeText(enJson(r).toString())
        tmp.renameTo(fichier(loc, jour))
        jours[loc + "/" + jour] = r
        resultats.keys.removeIf { it.startsWith("$loc/") }
        etats[loc]?.erreur = null
    }

    private fun jour(loc: String, j: String): Jour? {
        jours[loc + "/" + j]?.let { return it }
        val f = fichier(loc, j)
        if (!f.isFile) return null
        val r = runCatching { deJson(JSONObject(f.readText())) }.getOrNull() ?: return null
        if (jours.size > 200) jours.clear()
        jours[loc + "/" + j] = r
        return r
    }

    /** The days of [loc] among the last [jours] that are read. */
    private fun lus(loc: String, n: Int): List<Jour> = joursVoulus().take(n.coerceIn(1, 30)).mapNotNull { jour(loc, it) }

    /** Band × UTC hour over the last [n] days (only those: the old site summed every day it had). */
    fun ouvertures(loc: String, n: Int): JSONObject {
        val centre = Geo.locator(loc)
        val base = JSONObject().put("loc", loc)
        if (centre == null) return base.put("error", "locator invalide")
        base.put("center", JSONArray().put(centre.first).put(centre.second))
        if (!suivi(loc)) return base.put("error", "Seuls les locators de la station sont suivis : " + locators.joinToString(", "))
            .put("allowed", JSONArray(locators))
        assure(loc)
        val e = etats[loc]
        val cle = "$loc/o$n"
        val o = resultats[cle] ?: run {
            val l = lus(loc, n)
            val mat = JSONObject()
            val bandes = ArrayList<String>()
            var total = 0L
            for (b in Bandes.ORDRE_HF) {
                val heures = JSONObject()
                for (h in 0..23) {
                    var sp = 0L; var snr = 0L; val sk = HashSet<String>()
                    for (j in l) j.parHeure[b]?.get(h)?.let { (a, s, k) -> sp += a; snr += s; sk.addAll(k) }
                    if (sp > 0) heures.put(h.toString(), JSONObject().put("spots", sp).put("skimmers", sk.size).put("snr", Math.round(snr * 10.0 / sp) / 10.0))
                    total += sp
                }
                if (heures.length() > 0) { mat.put(b, heures); bandes += b }
            }
            JSONObject().put("bands", JSONArray(bandes)).put("matrix", mat).put("days", l.size).put("total", total)
                .also { if (e?.enCours != true) resultats[cle] = it }
        }
        val r = JSONObject(o.toString())
        for (k in base.keySet()) r.put(k, base.get(k))
        return r.put("ingesting", e?.enCours == true).put("error", e?.erreur ?: JSONObject.NULL).put("days_wanted", n.coerceIn(1, 30))
    }

    /** Where the skimmers that heard [loc]'s region were (4° cells), on [bande] ("" all), at [heure] (-1 all), over [n] days. */
    fun chaleur(loc: String, bande: String, heure: Int, n: Int): JSONObject {
        val centre = Geo.locator(loc)
        val o = JSONObject().put("loc", loc).put("band", bande).put("hour", if (heure in 0..23) heure else JSONObject.NULL)
        if (centre == null) return o.put("error", "locator invalide").put("cells", JSONArray())
        o.put("center", JSONArray().put(centre.first).put(centre.second))
        if (!suivi(loc)) return o.put("error", "locator non suivi").put("cells", JSONArray())
        val cle = "$loc/h$bande|$heure|$n"
        resultats[cle]?.let { c -> return o.put("cells", c.getJSONArray("cells")) }
        val somme = HashMap<Long, LongArray>()
        for (j in lus(loc, n)) for ((k, cellules) in j.cellules) {
            val (b, h) = k.split('|').let { it[0] to it[1].toInt() }
            if ((bande.isNotEmpty() && b != bande) || (heure in 0..23 && h != heure)) continue
            for ((c, v) in cellules) { val a = somme.getOrPut(c) { LongArray(2) }; a[0] += v[0]; a[1] += v[1] }
        }
        val cells = JSONArray(somme.entries.sortedByDescending { it.value[0] }.take(400).map { (c, v) ->
            JSONObject().put("lat", latCellule(c)).put("lon", lonCellule(c)).put("count", v[0]).put("snr", Math.round(v[1] * 10.0 / v[0]) / 10.0)
        })
        if (etats[loc]?.enCours != true) { if (resultats.size > 300) resultats.clear(); resultats[cle] = JSONObject().put("cells", cells) }
        return o.put("cells", cells)
    }

    companion object {
        const val RAYON_KM = 450.0
        const val RETENTION_JOURS = 32
        val JOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val HORODATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Rounding half away from zero (SQLite's `round`, what the old site's cells used). */
        fun arrondi(x: Double): Double = sign(x) * floor(abs(x) + 0.5)

        /** A 4° cell's key: its centre's latitude and longitude (rounded to 4°), packed as (lat+90)·1000 + (lon+180). */
        fun cellule(lat: Double, lon: Double): Long {
            val gy = (arrondi(Math.round(lat * 100) / 100.0 / 4) * 4).toLong()
            val gx = (arrondi(Math.round(lon * 100) / 100.0 / 4) * 4).toLong()
            return (gy + 90) * 1000 + (gx + 180)
        }

        fun latCellule(k: Long): Int = (k / 1000 - 90).toInt()
        fun lonCellule(k: Long): Int = (k % 1000 - 180).toInt()

        /** The UTC instant of an archive's « 2026-10-25 13:05:00 » (UTC: the old site read it as the server's local time). */
        fun instant(s: String): Long? = runCatching { LocalDateTime.parse(s.trim(), HORODATE).toEpochSecond(ZoneOffset.UTC) }.getOrNull()

        /**
         * A day's archive (a zip of one CSV: `callsign,de_pfx,de_cont,freq,band,dx,dx_pfx,dx_cont,mode,db,date,…`) summed for
         * the stations within [RAYON_KM] of [centre]: the DX's prefix located (its call when the prefix is not), the skimmer too.
         */
        fun resumeArchive(zip: InputStream, jour: String, centre: Pair<Double, Double>): Jour {
            val z = ZipInputStream(zip)
            var total = 0
            val ph = HashMap<String, HashMap<Int, Triple<IntArray, LongArray, HashSet<String>>>>()
            val cel = HashMap<String, HashMap<Long, LongArray>>()
            val proche = HashMap<String, Boolean>()
            val debut = LocalDate.parse(jour, JOUR).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.name.endsWith(".csv")) continue
                val r = BufferedReader(InputStreamReader(z, Charsets.ISO_8859_1))
                var premiere = true
                while (true) {
                    val l = r.readLine() ?: break
                    if (premiere) { premiere = false; if (l.startsWith("callsign")) continue }
                    val c = l.split(',')
                    if (c.size < 11) continue
                    val bande = c[4].trim().lowercase()
                    if (bande !in Bandes.ORDRE_HF) continue
                    val t = instant(c[10]) ?: continue
                    if (t < debut || t >= debut + 86_400) continue
                    val heure = ((t - debut) / 3600).toInt()
                    val dxPfx = c[6].trim().uppercase(); val dx = c[5].trim().uppercase()
                    val pres = proche.getOrPut(dxPfx.ifEmpty { dx }) {
                        val p = (if (dxPfx.isNotEmpty()) IndicatifGeo.position(dxPfx) else null) ?: IndicatifGeo.position(dx)
                        p != null && Geo.distanceKm(centre.first, centre.second, p.first, p.second) <= RAYON_KM
                    }
                    if (!pres) continue
                    val skimmer = c[0].trim().uppercase().substringBefore('-').take(16)
                    val pos = IndicatifGeo.position(skimmer) ?: continue
                    val snr = c[9].trim().toIntOrNull() ?: continue
                    total++
                    val cellule = ph.getOrPut(bande) { HashMap() }.getOrPut(heure) { Triple(IntArray(1), LongArray(1), HashSet()) }
                    cellule.first[0]++; cellule.second[0] += snr.toLong(); cellule.third += skimmer
                    val k = cellule(pos.first, pos.second)
                    val a = cel.getOrPut("$bande|$heure") { HashMap() }.getOrPut(k) { LongArray(2) }
                    a[0]++; a[1] += snr.toLong()
                }
            }
            return Jour(jour, total, ph.mapValues { (_, m) -> m.mapValues { (_, v) -> Triple(v.first[0], v.second[0], v.third.toSet()) } }, cel)
        }

        fun enJson(j: Jour): JSONObject {
            val skim = j.parHeure.values.flatMap { m -> m.values.flatMap { it.third } }.distinct()
            val idx = skim.withIndex().associate { it.value to it.index }
            val bh = JSONObject()
            for ((b, m) in j.parHeure) {
                val o = JSONObject()
                for ((h, v) in m) o.put(h.toString(), JSONArray().put(v.first).put(v.second).put(JSONArray(v.third.map { idx[it] })))
                bh.put(b, o)
            }
            val heat = JSONObject()
            for ((k, m) in j.cellules) heat.put(k, JSONArray(m.map { (c, v) -> JSONArray().put(c).put(v[0]).put(v[1]) }))
            return JSONObject().put("day", j.jour).put("spots", j.spots).put("skimmers", JSONArray(skim)).put("bh", bh).put("heat", heat)
        }

        fun deJson(o: JSONObject): Jour {
            val skim = o.getJSONArray("skimmers").let { a -> List(a.length()) { a.getString(it) } }
            val bh = o.getJSONObject("bh")
            val ph = bh.keySet().associateWith { b ->
                val m = bh.getJSONObject(b)
                m.keySet().associate { h ->
                    val a = m.getJSONArray(h)
                    val s = a.getJSONArray(2)
                    h.toInt() to Triple(a.getInt(0), a.getLong(1), (0 until s.length()).map { skim[s.getInt(it)] }.toSet())
                }
            }
            val heat = o.getJSONObject("heat")
            val cel = heat.keySet().associateWith { k ->
                val a = heat.getJSONArray(k)
                (0 until a.length()).associate { i -> val c = a.getJSONArray(i); c.getLong(0) to longArrayOf(c.getLong(1), c.getLong(2)) }
            }
            return Jour(o.getString("day"), o.getInt("spots"), ph, cel)
        }
    }
}
