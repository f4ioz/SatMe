/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.ContenuPassage
import fr.f4ioz.satcombo.domain.JournalFt
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.ZonesNumeriques
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * What each journal pass holds ([ContenuPassage]), for the list's badges and
 * its filter — from the files the station already keeps, never a recording
 * read again:
 *
 *  - SSTV: the pictures tied to the pass (their `.meta`, [JournalPc.Liens]);
 *  - FT8 / FT4: the pass's messages (`journal/<id>.ft`);
 *  - digital not identified: the recordings' zones (`.numerique`), only when
 *    already worked out (opening the pass or « Rechercher le numérique »);
 *  - APRS: the frames received (`aprs/<day>.txt`, the ones sent left out);
 *  - telemetry and SSDV / Geoscan pictures: `telemesure/paquets/<passage>.jsonl` and
 *    `telemesure/<profile>-<day>.jsonl`, of the pass's satellite;
 *  - voice: the log's contacts in a voice mode, the overs written down
 *    (`phonie/<day>.jsonl`) and the voices the recordings' zones found (`.numerique` v2); CW: the log's CW contacts;
 *  - DATV: the transport streams recorded during the pass (`SatMe_DATV_….ts`).
 *
 * Each file is read once and kept until it changes (its date and length); each
 * pass's summary is kept until one of the files it came from changes: a list of
 * hundreds of passes costs a few hundred `stat`s once read.
 */
class ContenuPassagesPc(
    private val donnees: File,
    private val journal: File = File(donnees, "journal"),
    private val enregistrements: File = File(donnees, "enregistrements"),
) {
    private class Lu<T>(val marque: String, val valeur: T)
    private val fichiers = ConcurrentHashMap<String, Lu<*>>()

    private fun marque(f: File) = "${f.lastModified()}:${f.length()}"

    /** [f] read by [lecture], again only when it changed; [vide] when it is not there or cannot be read. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> lu(f: File, vide: T, lecture: (File) -> T): T {
        if (!f.isFile) { fichiers.remove(f.path); return vide }
        val m = marque(f)
        (fichiers[f.path] as Lu<T>?)?.takeIf { it.marque == m }?.let { return it.valeur }
        val v = runCatching { lecture(f) }.getOrDefault(vide)
        fichiers[f.path] = Lu(m, v)
        return v
    }

    /** A frame of telemetry: when, of which satellite, the pictures it carried a piece of. */
    private class Trame(val t: Long, val sat: String, val norad: Int, val images: List<String>)

    /** Something dated of a satellite (an over written down). */
    private class Date(val t: Long, val sat: String)

    /** Everything dated of a folder, read through the files' cache, in time order; and what it was made of. */
    private class Index<T>(val l: List<T>, val t: LongArray, val marque: String)

    private fun <T> index(fs: List<File>, lecture: (File) -> List<T>, temps: (T) -> Long): Index<T> {
        val l = fs.flatMap { f -> lu(f, emptyList(), lecture) }.sortedBy(temps)
        return Index(l, LongArray(l.size) { temps(l[it]) }, fs.joinToString(",") { it.name + "@" + marque(it) }.hashCode().toString())
    }

    /** Those of [x] in [f] (binary search: the list may hold weeks). */
    private fun <T> dans(x: Index<T>, f: LongRange): List<T> {
        var bas = 0; var haut = x.t.size
        while (bas < haut) { val m = (bas + haut) ushr 1; if (x.t[m] < f.first) bas = m + 1 else haut = m }
        val r = ArrayList<T>()
        var i = bas
        while (i < x.t.size && x.t[i] <= f.last) { r += x.l[i]; i++ }
        return r
    }

    private fun liste(d: File, garde: (File) -> Boolean): List<File> = (d.listFiles { f -> f.isFile && garde(f) } ?: emptyArray()).sortedBy { it.name }

    private fun trames(f: File): List<Trame> = f.readLines().mapNotNull { li ->
        val o = runCatching { JSONObject(li) }.getOrNull() ?: return@mapNotNull null
        if (o.optBoolean("essai")) return@mapNotNull null
        val t = o.optLong("t").takeIf { it > 0 } ?: return@mapNotNull null
        val im = ArrayList<String>()
        o.optJSONArray("ssdv")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("cle")?.takeIf { it.isNotBlank() }?.let { im += "ssdv:$it" } }
        o.opt("geoscan")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }?.let { im += "geoscan:$it" }
        Trame(t, o.optString("sat").ifBlank { o.optString("satellite") }, o.optInt("norad"), im)
    }

    private fun paroles(f: File): List<Date> = f.readLines().mapNotNull { li ->
        val o = runCatching { JSONObject(li) }.getOrNull() ?: return@mapNotNull null
        if (o.optString("texte").isBlank()) return@mapNotNull null
        o.optLong("t").takeIf { it > 0 }?.let { Date(it, o.optString("satellite")) }
    }

    /** The APRS frames received of a day's file (`ms⇥satellite⇥frame`), their times: those sent left out. */
    private fun aprs(f: File): List<Long> = f.readLines().mapNotNull { li ->
        val c = li.split('\t', limit = 3)
        if (c.size < 3 || c[1] == AprsPc.EMIS) null else c[0].toLongOrNull()
    }

    private fun ft(f: File): Pair<Int, Int> = JournalFt.litTout(f.readText()).let { l ->
        l.count { it.mode.equals("FT8", true) } to l.count { it.mode.equals("FT4", true) }
    }

    private fun zones(f: File): List<String> = ZonesNumeriques.lit(f.readText(), 0L).map { it.mode }

    private val DATV = Regex("^SatMe_DATV_(\\d{8}_\\d{6})Z.*\\.ts$")
    private fun debutDatv(nom: String): Long? = DATV.find(nom)?.groupValues?.get(1)?.let {
        runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(it)?.time }.getOrNull()
    }

    private class Resume(val marque: String, val resume: Map<String, Int>)
    private val resumes = ConcurrentHashMap<String, Resume>()

    /**
     * The passes' summaries ([ContenuPassage.resume]), by pass id; [liens] are the list's (the log's contacts and the
     * SSTV pictures already tied to each pass, and the APRS frames still in memory).
     */
    fun resumes(l: List<JournalPassage.Entree>, liens: Map<String, JournalPc.Liens>): Map<String, Map<String, Int>> {
        val tlm = index(liste(File(donnees, "telemesure/paquets")) { it.name.endsWith(".jsonl") } +
            liste(File(donnees, "telemesure")) { it.name.endsWith(".jsonl") }, ::trames) { it.t }
        val ph = index(liste(File(donnees, "phonie")) { it.name.endsWith(".jsonl") }, ::paroles) { it.t }
        val ap = index(liste(File(donnees, "aprs")) { it.name.endsWith(".txt") }, ::aprs) { it }
        val datv = liste(enregistrements) { it.name.startsWith("SatMe_DATV_") && it.name.endsWith(".ts") }.mapNotNull { debutDatv(it.name) }.sorted()
        val commun = tlm.marque + "/" + ph.marque + "/" + ap.marque + "/" + datv.size + ":" + datv.lastOrNull()
        val vus = HashSet<String>()
        val r = l.associate { e ->
            vus += e.id
            val k = liens[e.id]
            val fFt = File(journal, e.id + ".ft")
            val fNum = e.enregistrements.distinct().map { File(enregistrements, File(it).name.substringBeforeLast('.') + ".numerique") }
            val m = listOf(commun, e.debutMs, e.finMs, e.enregistrements.size, marque(fFt), fNum.joinToString(",") { marque(it) },
                k?.images?.size, k?.trames?.size, k?.qsos?.joinToString(",") { it.timeMs.toString() + it.mode }?.hashCode()).joinToString("|")
            resumes[e.id]?.takeIf { it.marque == m }?.let { return@associate e.id to it.resume }
            val f = JournalPassage.fenetre(e)
            val (ft8, ft4) = lu(fFt, 0 to 0, ::ft)
            val trames = dans(tlm, f).filter { (it.norad > 0 && it.norad == e.catnum) || JournalPassage.memeSatellite(it.sat, e.satName) }
            val x = ContenuPassage.Elements(
                imagesSstv = k?.images?.size ?: 0,
                imagesSsdv = trames.flatMap { it.images }.toSet().size,
                messagesFt8 = ft8, messagesFt4 = ft4,
                zones = fNum.flatMap { lu(it, emptyList(), ::zones) },
                tramesAprs = maxOf(dans(ap, f).size, k?.trames?.size ?: 0),
                tramesTelemesure = trames.size,
                paroles = dans(ph, f).count { it.sat.isBlank() || JournalPassage.memeSatellite(it.sat, e.satName) },
                modesQso = k?.qsos?.map { it.mode }.orEmpty(),
                enregistrementsDatv = datv.count { it in f },
            )
            val s = ContenuPassage.resume(x)
            resumes[e.id] = Resume(m, s)
            e.id to s
        }
        // Passes taken out of the journal: forgotten.
        resumes.keys.retainAll(vus)
        return r
    }
}
