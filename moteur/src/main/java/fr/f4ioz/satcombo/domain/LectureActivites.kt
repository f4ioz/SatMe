/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.domain.ActivitesAVenir.Activite
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Ecoute
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Precision
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Source
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Type
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The online sources read into [Activite]s: the ARISS calendar (iCal), the
 * AMSAT status reports (JSON), SatNOGS observations (JSON), news feeds (RSS).
 * Text only, nothing fetched here; what cannot be read is skipped.
 */
object LectureActivites {

    val SOURCE_ARISS = Source("ARISS (agenda)", "https://www.ariss.org/")

    // ------------------------------------------------------------------ text

    private val ENTITES = mapOf("nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "hellip" to "…", "ndash" to "–", "mdash" to "—", "deg" to "°",
        "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "ecirc" to "ê", "ocirc" to "ô", "raquo" to "»", "laquo" to "«")

    /** HTML to plain text: lines kept at <br> and paragraphs, entities read, no-break spaces as spaces. */
    fun texte(html: String): String {
        var s = html.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        s = s.replace(Regex("<\\s*(br|/p|/div|/li|/h\\d|p|li)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]*>"), "")
        s = s.replace(Regex("&#(x?)([0-9a-fA-F]{1,6});")) { m ->
            runCatching { String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isNotEmpty()) 16 else 10))) }.getOrDefault(" ")
        }
        s = s.replace(Regex("&([a-zA-Z]{2,8});")) { m -> ENTITES[m.groupValues[1].lowercase()] ?: m.value }
        return s.replace(' ', ' ').replace(' ', ' ').replace(Regex("[ \\t]+"), " ")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private val LIEN = Regex("https?://[^\\s<>\"']+")

    /** The links of an HTML text (href first), without the trailing punctuation. */
    fun liens(html: String): List<String> =
        (Regex("href=\"(https?://[^\"]+)\"", RegexOption.IGNORE_CASE).findAll(html).map { it.groupValues[1] } + LIEN.findAll(html).map { it.value })
            .map { it.replace("&amp;", "&").trimEnd('.', ',', ')', ';') }.filter { CampagneSstv.lienPropre(it).isNotEmpty() }.distinct().take(6).toList()

    /** « 36 seconds on with 2 minutes off », « 36 s on / 2 min off ». */
    private val HORAIRE = Regex("(\\d+)\\s*(?:sec(?:ond)?s?|s)\\b\\.?\\s*on\\b[\\s,/-]*(?:with\\s+|and\\s+|then\\s+)?(\\d+(?:[.,]\\d+)?)\\s*(?:min(?:ute)?s?|m)\\b\\.?\\s*(?:of\\s+)?off", RegexOption.IGNORE_CASE)

    fun horaire(t: String): String = HORAIRE.find(t)?.let { it.groupValues[1] + " s on / " + it.groupValues[2].replace('.', ',') + " min off" } ?: ""

    /** « around 15:10 UTC », « approximately 15:10 »: read as « at 15:10 » (the flyer reader's word). */
    private fun pourAffiche(t: String) = t.replace(Regex("\\b(around|approximately|approx\\.?|about|circa|vers)\\s+(?=\\d{1,2}[:h]\\d{2})", RegexOption.IGNORE_CASE), "at ")

    private fun utc(f: String) = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }

    /** « 2026-10-23T19:30:00Z » (fractions and offsets too), null when not. */
    fun iso(s: String): Long? {
        val t = s.trim()
        val m = Regex("^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$").find(t) ?: return null
        val z = m.groupValues[3].ifEmpty { "Z" }.replace(":", "")
        return runCatching { utc("yyyy-MM-dd'T'HH:mm:ssZ").parse(m.groupValues[1] + "T" + m.groupValues[2] + (if (z == "Z") "+0000" else z))!!.time }.getOrNull()
    }

    // ------------------------------------------------------------------ the ARISS calendar

    /** One VEVENT: its properties (name without parameters → value, the parameters kept apart). */
    private class Evenement(val p: Map<String, String>, val params: Map<String, String>)

    private fun desechappe(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun evenements(ics: String): List<Evenement> {
        val out = ArrayList<Evenement>()
        var p: HashMap<String, String>? = null
        var params: HashMap<String, String>? = null
        // Unfolded: a line starting with a space or a tab goes on the previous one.
        val lignes = ArrayList<String>()
        for (l in ics.split('\n')) {
            val x = l.trimEnd('\r')
            if ((x.startsWith(" ") || x.startsWith("\t")) && lignes.isNotEmpty()) lignes[lignes.size - 1] = lignes.last() + x.substring(1)
            else lignes += x
        }
        for (l in lignes) {
            when {
                l == "BEGIN:VEVENT" -> { p = HashMap(); params = HashMap() }
                l == "END:VEVENT" -> { p?.let { out += Evenement(it, params!!) }; p = null }
                p != null -> {
                    val i = l.indexOf(':'); if (i <= 0) continue
                    val tete = l.substring(0, i); val nom = tete.substringBefore(';').uppercase()
                    if (nom !in p) { p[nom] = l.substring(i + 1); params!![nom] = tete.substringAfter(';', "") }
                }
            }
        }
        return out
    }

    /** An iCal date: « 20261022T151000Z », a TZID's local time, or a whole day (its end the next day, exclusive). */
    private fun dateIcs(v: String, params: String): Long? {
        val tz = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getTimeZone("UTC")
        val s = v.trim()
        return runCatching {
            when {
                Regex("^\\d{8}T\\d{6}Z$").matches(s) -> utc("yyyyMMdd'T'HHmmss'Z'").parse(s)!!.time
                Regex("^\\d{8}T\\d{6}$").matches(s) -> SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = tz }.parse(s)!!.time
                Regex("^\\d{8}$").matches(s) -> utc("yyyyMMdd").parse(s)!!.time
                else -> null
            }
        }.getOrNull()
    }

    private val SERIE = Regex("\\bSeries\\s+(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * The ARISS calendar: its ISS SSTV events and its school contacts (FM
     * voice) ending after [depuisMs]. HamTV and the rest are left out.
     */
    fun ariss(ics: String, depuisMs: Long): List<Activite> = evenements(ics).mapNotNull { e ->
        val debut = e.p["DTSTART"]?.let { dateIcs(it, e.params["DTSTART"].orEmpty()) } ?: return@mapNotNull null
        val jour = e.params["DTSTART"].orEmpty().contains("VALUE=DATE")
        val fin = e.p["DTEND"]?.let { dateIcs(it, e.params["DTEND"].orEmpty()) }?.let { if (jour) it - 60_000L else it }
            ?: (debut + if (jour) 86_400_000L - 60_000L else 10 * 60_000L)
        if (fin < depuisMs) return@mapNotNull null
        val resume = texte(desechappe(e.p["SUMMARY"].orEmpty())).replace('\n', ' ').trim()
        val html = desechappe(e.p["DESCRIPTION"].orEmpty())
        val desc = texte(html)
        val type = when {
            Regex("HamTV|DATV", RegexOption.IGNORE_CASE).containsMatchIn(resume) -> return@mapNotNull null
            Regex("SSTV", RegexOption.IGNORE_CASE).containsMatchIn(resume) -> Type.SSTV
            // « OR4ISS (astronaut) - 53 deg - F4KKM (Manosque, France) »: a school contact.
            Regex("^(?:OR4ISS|NA1SS|RS[0Ø]ISS|DP0ISS|IR0ISS|ON4ISS|GB1SS|OR4ISS|KJ5\\w+|NA1\\w+)\\b.* - ", RegexOption.IGNORE_CASE).containsMatchIn(resume) -> Type.CONTACT
            else -> return@mapNotNull null
        }
        val p = AfficheSstv.lis(desc, debut, TimeZone.getTimeZone("UTC"))
        val mode = if (type == Type.SSTV) p.modeSstv.takeIf { it != CampagneSstv.AUTO }.orEmpty() else ""
        val titre = when (type) {
            Type.SSTV -> SERIE.find(desc)?.let { "ARISS SSTV Series " + it.groupValues[1] } ?: resume.replace(Regex("\\s+-\\s+"), " · ")
            else -> resume
        }
        val details = when (type) {
            Type.SSTV -> desc.lines().firstOrNull { SERIE.containsMatchIn(it) }?.let { l -> l.split(Regex("(?<=\\.)\\s+")).firstOrNull { SERIE.containsMatchIn(it) } }.orEmpty()
            else -> desc.lines().firstOrNull { it.startsWith("RX", true) }.orEmpty()
        }.take(200)
        Activite(id = "ariss:" + (e.p["UID"] ?: (resume + debut)), type = type, satNom = "ISS", catnum = ActivitesAVenir.ISS,
            debutMs = debut, finMs = fin, precision = if (jour) Precision.JOURS else Precision.EXACTE,
            freqHz = p.freqHz, modulation = "FM", mode = mode, horaire = if (type == Type.SSTV) horaire(desc) else "",
            titre = titre.take(120), details = details, sources = listOf(SOURCE_ARISS), liens = liens(html))
    }.sortedBy { it.debutMs }

    // ------------------------------------------------------------------ AMSAT status

    /** The AMSAT status API's answer (`sat_info.php?name=…`): its reports. */
    fun amsat(json: String): List<Ecoute> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val t = iso(o.optString("reported_time")) ?: return@mapNotNull null
            Ecoute(o.optString("name"), t, o.optString("callsign").substringBefore('/').trim(), o.optString("report"), o.optString("grid_square"))
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ SatNOGS observations

    /**
     * SatNOGS observations of a METEOR ([catnum], [satNom]): the good ones of
     * the last 3 days make one « seen recently » LRPT activity on their
     * commonest downlink. Vetting is noisy: an indication, not ground truth.
     */
    fun satnogs(json: String, catnum: Int, satNom: String, maintenant: Long): Activite? {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return null
        data class Obs(val de: Long, val a: Long, val hz: Long, val station: String)
        val bonnes = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            if (o.optInt("norad_cat_id", catnum) != catnum) return@mapNotNull null
            if (!o.optString("status").equals("good", true)) return@mapNotNull null
            val de = iso(o.optString("start")) ?: return@mapNotNull null
            val fin = iso(o.optString("end")) ?: de
            val hz = o.optLong("transmitter_downlink_low", 0L).takeIf { it > 0 } ?: o.optLong("observation_frequency", 0L)
            Obs(de, fin, hz, o.optString("station_name"))
        }.filter { it.a >= maintenant - ActivitesAVenir.LRPT_VU_MS && it.hz > 0 }
        if (bonnes.isEmpty()) return null
        val hz = bonnes.groupingBy { it.hz }.eachCount().maxByOrNull { it.value }!!.key
        val l = bonnes.filter { it.hz == hz }
        return Activite(id = "satnogs:$catnum:$hz", type = Type.LRPT, satNom = satNom, catnum = catnum,
            debutMs = l.minOf { it.de }, finMs = l.maxOf { it.a }, freqHz = hz, modulation = "LRPT", mode = "LRPT",
            titre = "Images météo LRPT", details = l.size.toString() + " observation" + (if (l.size > 1) "s" else "") + " bonne" + (if (l.size > 1) "s" else "") + " (SatNOGS)",
            sources = listOf(Source("SatNOGS", "https://network.satnogs.org/observations/?norad=$catnum&future=0&bad=0&unknown=0&failed=0")),
            confiance = ActivitesAVenir.Confiance.VU_RECEMMENT, ecoutes = l.size, derniereEcouteMs = l.maxOf { it.de })
    }

    // ------------------------------------------------------------------ news feeds

    private val MOTS = Regex("\\bSSTV\\b|slow[\\s-]?scan|\\bSSDV\\b", RegexOption.IGNORE_CASE)

    internal fun balise(item: String, nom: String): String? =
        Regex("<$nom(?:\\s[^>]*)?>(.*?)</$nom>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(item)?.groupValues?.get(1)
            ?.replace(Regex("^\\s*<!\\[CDATA\\[(.*?)]]>\\s*$", RegexOption.DOT_MATCHES_ALL), "$1")

    /** A satellite named in a post without being known: « UMKA-1 », « SAKHACUBE-CHOLBON », else an AMSAT status name (« RS40S »). */
    internal fun nomCite(t: String): String =
        Regex("\\b([A-Z][A-Z0-9]{2,}(?:-[A-Z0-9]+)*-[A-Z0-9]{1,4})\\b").findAll(t).map { it.value }.firstOrNull { !it.startsWith("AMSAT") && !it.startsWith("ARISS") }
            ?: ActivitesAVenir.NOMS_AMSAT.map { it.substringBefore("_[") }.filter { it != "ISS" }
                .firstOrNull { n -> Regex("(?<![A-Za-z0-9])" + Regex.escape(n) + "(?![A-Za-z0-9])").containsMatchIn(t) } ?: ""

    internal fun datePost(s: String): Long? = runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(s.trim())!!.time }.getOrNull()
        ?: iso(s)

    /**
     * A news feed (RSS 2.0 or Atom) by [source]: its posts about SSTV or SSDV
     * published within [ageMaxMs]. The period is read in the text as a
     * flyer's (dates, « around 15:10 UTC »); a post without one is an
     * activity of unknown period from its date, with its link.
     */
    fun rss(xml: String, source: Source, maintenant: Long, satellites: List<Pair<String, Int>> = emptyList(),
            ageMaxMs: Long = 45L * 86_400_000L): List<Activite> {
        val items = Regex("<(item|entry)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(xml).map { it.groupValues[2] }
        return items.mapNotNull { it ->
            val titre = texte(balise(it, "title").orEmpty()).replace('\n', ' ')
            val html = balise(it, "content:encoded") ?: balise(it, "content") ?: balise(it, "description") ?: balise(it, "summary") ?: ""
            val cats = Regex("<category[^>]*>(.*?)</category>", RegexOption.DOT_MATCHES_ALL).findAll(it).joinToString(" ") { m -> texte(m.groupValues[1]) }
            val corps = texte(html)
            if (!MOTS.containsMatchIn(titre + " " + cats + " " + corps)) return@mapNotNull null
            if (Regex("\\bNOAA\\b").containsMatchIn(titre)) return@mapNotNull null
            val publie = (balise(it, "pubDate") ?: balise(it, "published") ?: balise(it, "updated") ?: balise(it, "dc:date"))?.let(::datePost) ?: return@mapNotNull null
            if (publie < maintenant - ageMaxMs) return@mapNotNull null
            val lien = balise(it, "link")?.trim()?.takeIf { l -> l.startsWith("http") }
                ?: Regex("<link[^>]*href=\"([^\"]+)\"").find(it)?.groupValues?.get(1) ?: source.lien
            val type = if (Regex("\\bSSDV\\b", RegexOption.IGNORE_CASE).containsMatchIn(titre + " " + corps) &&
                !Regex("\\bSSTV\\b|slow[\\s-]?scan", RegexOption.IGNORE_CASE).containsMatchIn(titre + " " + corps)) Type.SSDV else Type.SSTV
            // The body first (« Oct 22-26 » in a title would hide its times), the title with it when the body has no date.
            val p = AfficheSstv.lis(pourAffiche(corps), publie, TimeZone.getTimeZone("UTC"), satellites).takeIf { it.debutMs > 0 }
                ?: AfficheSstv.lis(pourAffiche(titre + "\n" + corps), publie, TimeZone.getTimeZone("UTC"), satellites)
            var precision = if (p.debutMs <= 0) Precision.INCONNUE else if (p.aConfirmer) Precision.JOURS else Precision.EXACTE
            var de = p.debutMs; var a = p.finMs
            // A period read too long, or over before the post: not trusted.
            if (precision != Precision.INCONNUE && (a - de > CampagneSstv.DUREE_MAX_MS || a < publie - 86_400_000L)) precision = Precision.INCONNUE
            if (precision == Precision.INCONNUE) { de = publie; a = publie + 7L * 86_400_000L }
            val (satNom, catnum) = when {
                p.catnum > 0 -> (satellites.firstOrNull { s -> s.second == p.catnum }?.first ?: p.satNom) to p.catnum
                else -> nomCite(titre).ifEmpty { nomCite(corps) } to 0
            }
            Activite(id = "rss:" + lien, type = type, satNom = satNom, catnum = catnum, debutMs = de, finMs = a, precision = precision,
                freqHz = p.freqHz, mode = if (type == Type.SSTV) p.modeSstv.takeIf { m -> m != CampagneSstv.AUTO }.orEmpty() else "",
                horaire = horaire(corps).ifBlank { p.horaire }, titre = titre.take(120),
                details = corps.lines().firstOrNull { l -> MOTS.containsMatchIn(l) }.orEmpty().take(200),
                sources = listOf(source.copy(lien = lien)), liens = listOf(lien))
        }.toList()
    }
}
