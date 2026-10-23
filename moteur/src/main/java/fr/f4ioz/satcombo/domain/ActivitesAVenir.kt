/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.AgendaStore
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.sstv.SstvMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * « Activités à venir »: what satellites will send that SatMe decodes (ISS
 * SSTV, ARISS school contacts in FM, SSDV, METEOR LRPT), as online sources
 * announce or report it. Here the model, the merging of what several sources
 * say about the same thing, what is kept, the passes that hear it, what to
 * signal, and the drafts handed to the owner (an SSTV campaign, an agenda
 * entry) — never saved without him. The sources are read by
 * [LectureActivites], fetched by [fr.f4ioz.satcombo.data.ActivitesEnLigne].
 */
object ActivitesAVenir {

    enum class Type(val libelle: String) { SSTV("SSTV"), CONTACT("Contact ARISS"), SSDV("SSDV"), LRPT("LRPT") }

    /** How sure: announced by a schedule or a post, confirmed by listeners' reports, or seen recently (SatNOGS). */
    enum class Confiance(val libelle: String) { ANNONCE("annoncé"), VU_RECEMMENT("vu récemment"), CONFIRME("confirmé par écoutes") }

    /** How well the period is known: to the minute, whole days, or not at all (a post's date). */
    enum class Precision { EXACTE, JOURS, INCONNUE }

    data class Source(val nom: String, val lien: String = "")

    data class Activite(
        /** Stable within its source (the calendar's UID, the post's link…). */
        val id: String,
        val type: Type,
        val satNom: String,
        /** NORAD number, 0 when the text named no satellite known. */
        val catnum: Int,
        val debutMs: Long,
        val finMs: Long,
        val precision: Precision = Precision.EXACTE,
        /** Downlink (sky Hz), 0 unknown. */
        val freqHz: Long = 0L,
        val modulation: String = "FM",
        /** An SSTV mode's name (« Robot 36 »), "" unknown. */
        val mode: String = "",
        /** « 36 s on / 2 min off ». */
        val horaire: String = "",
        val titre: String,
        val details: String = "",
        val sources: List<Source>,
        val liens: List<String> = emptyList(),
        val confiance: Confiance = Confiance.ANNONCE,
        /** Listeners' reports (AMSAT) or good observations (SatNOGS) counted, and the latest. */
        val ecoutes: Int = 0,
        val derniereEcouteMs: Long = 0L,
    ) {
        fun enCours(t: Long) = t in debutMs..finMs
    }

    const val ISS = 25544
    const val METEOR_M2_3 = 57166
    const val METEOR_M2_4 = 59051
    /** Two announcements of the same thing may disagree by this much and still be one. */
    const val MARGE_MS = 60 * 60_000L
    /** LRPT seen this recently is still shown. */
    const val LRPT_VU_MS = 3 * 86_400_000L

    // ------------------------------------------------------------------ merging

    private fun normal(s: String) = s.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")

    /** Same satellite: by number when both have one, else by name. */
    fun memeSatellite(a: Activite, b: Activite): Boolean =
        if (a.catnum > 0 && b.catnum > 0) a.catnum == b.catnum
        else normal(a.satNom).isNotEmpty() && normal(a.satNom) == normal(b.satNom)

    /** Same thing said twice: same type, same satellite, periods meeting (a contact: starting within half an hour). */
    fun meme(a: Activite, b: Activite): Boolean {
        if (a.type != b.type || !memeSatellite(a, b)) return false
        if (a.type == Type.LRPT) return a.freqHz == b.freqHz || a.freqHz == 0L || b.freqHz == 0L
        if (a.type == Type.CONTACT && a.precision == Precision.EXACTE && b.precision == Precision.EXACTE)
            return kotlin.math.abs(a.debutMs - b.debutMs) <= 30 * 60_000L
        return a.debutMs <= b.finMs + MARGE_MS && a.finMs >= b.debutMs - MARGE_MS
    }

    /** [a] and [b] as one: the earliest start and latest end of the dated ones, every source, the most sure. */
    fun unit(a: Activite, b: Activite): Activite {
        val dates = listOf(a, b).filter { it.precision != Precision.INCONNUE }.ifEmpty { listOf(a, b) }
        val pref = listOf(a, b).minByOrNull { it.precision.ordinal }!!
        val autre = if (pref === a) b else a
        return pref.copy(
            debutMs = dates.minOf { it.debutMs }, finMs = dates.maxOf { it.finMs },
            precision = listOf(a.precision, b.precision).minByOrNull { it.ordinal }!!,
            freqHz = pref.freqHz.takeIf { it > 0 } ?: autre.freqHz,
            mode = pref.mode.ifBlank { autre.mode }, horaire = pref.horaire.ifBlank { autre.horaire },
            catnum = pref.catnum.takeIf { it > 0 } ?: autre.catnum, satNom = pref.satNom.ifBlank { autre.satNom },
            details = pref.details.ifBlank { autre.details },
            sources = (a.sources + b.sources).distinctBy { it.nom },
            liens = (a.liens + b.liens).distinct().take(6),
            confiance = maxOf(a.confiance, b.confiance),
            ecoutes = maxOf(a.ecoutes, b.ecoutes), derniereEcouteMs = maxOf(a.derniereEcouteMs, b.derniereEcouteMs),
        )
    }

    /** Duplicates merged (sources may disagree: 15:10 here, 15:30 there), soonest first. */
    fun fusionne(l: List<Activite>): List<Activite> {
        val out = ArrayList<Activite>()
        for (a in l.sortedWith(compareBy({ it.precision.ordinal }, { it.debutMs }))) {
            val i = out.indexOfFirst { meme(it, a) }
            if (i >= 0) out[i] = unit(out[i], a) else out += a
        }
        return out.sortedBy { it.debutMs }
    }

    /** Never NOAA APT (all retired in 2025). */
    private fun retire(a: Activite) = a.satNom.contains("NOAA", true) || a.titre.contains("NOAA APT", true)

    /** What is kept: what SatMe decodes, not over (LRPT: seen within 3 days), soonest first. */
    fun aGarder(l: List<Activite>, maintenant: Long): List<Activite> = l.filter { a ->
        !retire(a) && when (a.type) {
            Type.LRPT -> a.finMs >= maintenant - LRPT_VU_MS
            else -> a.finMs >= maintenant - 30 * 60_000L && a.debutMs <= maintenant + 180L * 86_400_000L
        }
    }.sortedBy { it.debutMs }

    // ------------------------------------------------------------------ AMSAT reports

    /** One listener's report (AMSAT status). */
    data class Ecoute(val nom: String, val quandMs: Long, val indicatif: String, val rapport: String, val locator: String) {
        val entendu: Boolean get() = rapport.startsWith("Heard", true) || rapport.startsWith("Telemetry", true)
    }

    /** AMSAT status names carrying pictures (the page's list, October 2026). */
    val NOMS_AMSAT: List<String> = listOf(
        "ISS_[SSTV]", "AO-123_[SSDV]", "BY70-4_[SSDV]", "HC1PX_[SSTV]", "IO-86_[SSDV]", "JAMX01_[SSTV]", "Luca_[SSDV]",
        "Marina_[SSDV]", "RS18S_[SSTV]", "RS27S_[SSTV]", "RS38S_[SSTV]", "RS40S_[SSTV]", "RS57S_[SSTV]", "RS58S_[SSTV]",
        "RS59S_[SSDV]", "RS61S_[SSDV]", "RS66S_[SSDV]", "RS83S_[SSDV]", "RS83S_[SSTV]", "RS90S_[SSDV]", "RS92S2_[SSDV]",
        "RS92S4_[SSDV]", "RS92S5_[SSDV]", "RS95S_[SSTV]", "SO-127_[SSDV]", "SONATE-2_[SSTV]")

    /** The AMSAT status name for [a]'s reports, null when there is none (contacts, LRPT, a satellite not listed). */
    fun nomAmsat(a: Activite): String? {
        val suffixe = when (a.type) { Type.SSTV -> "_[SSTV]"; Type.SSDV -> "_[SSDV]"; else -> return null }
        if (a.catnum == ISS) return if (a.type == Type.SSTV) "ISS_[SSTV]" else null
        val n = normal(a.satNom)
        return NOMS_AMSAT.firstOrNull { it.endsWith(suffixe) && n.isNotEmpty() && normal(it.substringBefore("_[")) == n }
            // « UMKA-1 (RS40S) »: the status name said in the title.
            ?: NOMS_AMSAT.firstOrNull { it.endsWith(suffixe) && Regex("(?<![A-Za-z0-9])" + Regex.escape(it.substringBefore("_[")) + "(?![A-Za-z0-9])").containsMatchIn(a.titre) }
    }

    /** Is [a]'s window open enough at [t] to ask AMSAT whether it is heard? */
    fun aEcouter(a: Activite, t: Long): Boolean =
        nomAmsat(a) != null && a.precision != Precision.INCONNUE && t in (a.debutMs - 30 * 60_000L)..(a.finMs + 30 * 60_000L)

    /** Reports heard during an announced SSTV/SSDV activity: confirmed, counted. */
    fun confirme(l: List<Activite>, ecoutes: Map<String, List<Ecoute>>): List<Activite> = l.map { a ->
        val nom = nomAmsat(a) ?: return@map a
        val vus = ecoutes[nom].orEmpty().filter { it.entendu && it.quandMs in (a.debutMs - 30 * 60_000L)..(a.finMs + 30 * 60_000L) }
        if (vus.isEmpty()) a else a.copy(confiance = Confiance.CONFIRME, ecoutes = vus.size, derniereEcouteMs = vus.maxOf { it.quandMs },
            sources = (a.sources + Source("AMSAT (écoutes)", "https://www.amsat.org/status/")).distinctBy { it.nom })
    }

    // ------------------------------------------------------------------ passes

    /** The passes of [a]'s satellite worth listening to: during it (LRPT: the next 24 h), high enough. */
    fun passages(a: Activite, l: List<SatPass>, elMin: Double, maintenant: Long): List<SatPass> {
        val (de, jusqua) = if (a.type == Type.LRPT) maintenant to maintenant + 86_400_000L else a.debutMs to a.finMs
        return l.filter { (a.catnum == 0 || it.catalogNumber == a.catnum) && it.maxElevationDeg >= elMin && it.losEpochMs > maxOf(de, maintenant) && it.aosEpochMs < jusqua }
            .sortedBy { it.aosEpochMs }
    }

    /** The span to compute [a]'s passes on (from now, two weeks at most). */
    fun fenetrePassages(a: Activite, maintenant: Long): Pair<Long, Long>? {
        if (a.catnum <= 0) return null
        if (a.type == Type.LRPT) return maintenant to maintenant + 86_400_000L
        val de = maxOf(a.debutMs, maintenant - 30 * 60_000L)
        val jusqua = minOf(a.finMs, maintenant + 15 * 86_400_000L)
        return if (jusqua > de) de to jusqua else null
    }

    // ------------------------------------------------------------------ what to prepare

    /** An SSTV (or SSDV) activity's campaign draft, for the owner to check in Agenda › SSTV: never saved here. */
    fun campagne(a: Activite, maintenant: Long): CampagneSstv.Campagne = CampagneSstv.Campagne(
        id = "", nom = a.titre.take(60), catnum = a.catnum, satNom = a.satNom,
        freqHz = a.freqHz, modulation = a.modulation.uppercase().takeIf { it in CampagneSstv.MODULATIONS } ?: "FM",
        modeSstv = a.mode.takeIf { SstvMode.byName(it) != null } ?: CampagneSstv.AUTO,
        debutMs = maxOf(a.debutMs, if (a.precision == Precision.INCONNUE) maintenant else a.debutMs), finMs = a.finMs,
        aConfirmer = a.precision != Precision.EXACTE, horaire = a.horaire,
        notes = ("Préparée d'après : " + a.sources.joinToString(", ") { it.nom } + (if (a.details.isNotBlank()) ". " + a.details else "")).take(600),
        // The gallery (« ARISS_SSTV ») before a tutorial: where the pictures go.
        lien = a.liens.filter { CampagneSstv.lienPropre(it).isNotEmpty() }.let { l -> l.firstOrNull { it.contains("sstv", true) } ?: l.firstOrNull() }.orEmpty(),
        creeMs = maintenant)

    /** The phone's agenda entry for [a], for the owner to check: a window, the satellite, the frequency, the mode. */
    fun rendezVous(a: Activite, maintenant: Long): AgendaStore.AgendaEvent = AgendaStore.AgendaEvent(
        id = maintenant, title = a.titre.take(60), timeMs = a.debutMs, endMs = a.finMs, satName = a.satNom,
        kind = when (a.type) { Type.SSTV -> "SSTV"; Type.CONTACT -> "SKED"; else -> "" }, freqHz = a.freqHz,
        note = listOf(a.mode, a.horaire, if (a.precision != Precision.EXACTE) "horaire à confirmer" else "").filter { it.isNotBlank() }.joinToString(" · "),
        leadMin = 60)

    // ------------------------------------------------------------------ what to signal

    /** What was already signalled, kept by each platform (a file). */
    data class Memoire(
        val connues: List<Trace> = emptyList(),
        /** « ça émet » already said, by activity key. */
        val emises: Set<String> = emptySet(),
        /** Passes already announced (catnum:AOS minute). */
        val passages: Set<String> = emptySet(),
        /** False until the first list was seen: then only the SSTV/SSDV still to come are signalled. */
        val initialisee: Boolean = false,
    )

    data class Trace(val type: Type, val sat: String, val debutMs: Long, val finMs: Long, val freqHz: Long)

    private fun trace(a: Activite) = Trace(a.type, if (a.catnum > 0) a.catnum.toString() else normal(a.satNom), a.debutMs, a.finMs, a.freqHz)

    private fun connue(m: Memoire, a: Activite): Boolean {
        val t = trace(a)
        return m.connues.any { c ->
            c.type == t.type && c.sat == t.sat && when (t.type) {
                Type.LRPT -> c.freqHz == t.freqHz
                else -> c.debutMs <= t.finMs + MARGE_MS && c.finMs >= t.debutMs - MARGE_MS
            }
        }
    }

    /** The key of an activity for « ça émet » (its satellite and start day). */
    fun cle(a: Activite): String = a.type.name + ":" + (if (a.catnum > 0) a.catnum.toString() else normal(a.satNom)) + ":" + a.debutMs / 86_400_000L

    /**
     * The activities to signal as new: not known yet, not over; a contact only
     * when it can be heard from here ([utiles] passes); LRPT only when its
     * frequency changed. The first time, only SSTV/SSDV still to come.
     */
    fun aSignaler(m: Memoire, l: List<Activite>, maintenant: Long, utiles: (Activite) -> List<SatPass>): List<Activite> =
        l.filter { a ->
            !connue(m, a) && when (a.type) {
                Type.SSTV, Type.SSDV -> a.finMs > maintenant
                Type.CONTACT -> m.initialisee && a.finMs > maintenant && utiles(a).isNotEmpty()
                Type.LRPT -> m.initialisee && a.finMs > maintenant - LRPT_VU_MS && m.connues.any { it.type == Type.LRPT && it.sat == trace(a).sat }
            }
        }

    /** [m] knowing [l] (old traces dropped after 60 days). */
    fun retiens(m: Memoire, l: List<Activite>, maintenant: Long): Memoire {
        val n = (m.connues + l.filter { !connue(m, it) }.map(::trace)).filter { it.finMs > maintenant - 60L * 86_400_000L }.takeLast(400)
        return m.copy(connues = n, initialisee = true)
    }

    /** SSTV/SSDV heard now (AMSAT reports within 2 h) during their window, not said yet. */
    fun emetMaintenant(m: Memoire, l: List<Activite>, maintenant: Long): List<Activite> = l.filter { a ->
        (a.type == Type.SSTV || a.type == Type.SSDV) && a.confiance == Confiance.CONFIRME &&
            maintenant in (a.debutMs - MARGE_MS)..(a.finMs + MARGE_MS) && a.derniereEcouteMs >= maintenant - 2 * 3_600_000L && cle(a) !in m.emises
    }

    data class AlertePassage(val activite: Activite, val passage: SatPass, val quandMs: Long) {
        val cle: String get() = passage.catalogNumber.toString() + ":" + passage.aosEpochMs / 60_000L
    }

    /** A notice [avantMin] before each useful pass during an SSTV, SSDV or contact activity, not past. */
    fun alertesPassages(l: List<Activite>, utiles: (Activite) -> List<SatPass>, maintenant: Long, avantMin: Int): List<AlertePassage> =
        l.filter { it.type != Type.LRPT && it.precision != Precision.INCONNUE && it.catnum > 0 }
            .flatMap { a -> utiles(a).map { AlertePassage(a, it, it.aosEpochMs - avantMin * 60_000L) } }
            .filter { it.passage.aosEpochMs > maintenant }
            .distinctBy { it.cle }.sortedBy { it.quandMs }

    // ------------------------------------------------------------------ files

    fun json(a: Activite): JSONObject = JSONObject().put("id", a.id).put("type", a.type.name).put("sat", a.satNom).put("catnum", a.catnum)
        .put("de", a.debutMs).put("a", a.finMs).put("precision", a.precision.name).put("freqHz", a.freqHz).put("modulation", a.modulation)
        .put("mode", a.mode).put("horaire", a.horaire).put("titre", a.titre).put("details", a.details)
        .put("sources", JSONArray(a.sources.map { JSONObject().put("nom", it.nom).put("lien", it.lien) }))
        .put("liens", JSONArray(a.liens)).put("confiance", a.confiance.name).put("ecoutes", a.ecoutes).put("derniere", a.derniereEcouteMs)

    fun lis(o: JSONObject): Activite? = runCatching {
        val s = o.optJSONArray("sources") ?: JSONArray()
        val li = o.optJSONArray("liens") ?: JSONArray()
        Activite(o.getString("id"), Type.valueOf(o.getString("type")), o.optString("sat"), o.optInt("catnum"), o.getLong("de"), o.getLong("a"),
            Precision.valueOf(o.optString("precision", "EXACTE")), o.optLong("freqHz"), o.optString("modulation", "FM"), o.optString("mode"),
            o.optString("horaire"), o.optString("titre"), o.optString("details"),
            (0 until s.length()).mapNotNull { s.optJSONObject(it) }.map { Source(it.optString("nom"), it.optString("lien")) },
            (0 until li.length()).map { li.optString(it) }, Confiance.valueOf(o.optString("confiance", "ANNONCE")),
            o.optInt("ecoutes"), o.optLong("derniere"))
    }.getOrNull()

    fun ecrit(m: Memoire): JSONObject = JSONObject().put("v", 1).put("initialisee", m.initialisee)
        .put("connues", JSONArray(m.connues.map { JSONObject().put("t", it.type.name).put("s", it.sat).put("d", it.debutMs).put("f", it.finMs).put("hz", it.freqHz) }))
        .put("emises", JSONArray(m.emises.toList().takeLast(200))).put("passages", JSONArray(m.passages.toList().takeLast(300)))

    fun litMemoire(texte: String?): Memoire = runCatching {
        val o = JSONObject(texte ?: "")
        val c = o.optJSONArray("connues") ?: JSONArray()
        val e = o.optJSONArray("emises") ?: JSONArray()
        val p = o.optJSONArray("passages") ?: JSONArray()
        Memoire((0 until c.length()).mapNotNull { c.optJSONObject(it) }.mapNotNull { j ->
            runCatching { Trace(Type.valueOf(j.getString("t")), j.optString("s"), j.optLong("d"), j.optLong("f"), j.optLong("hz")) }.getOrNull()
        }, (0 until e.length()).map { e.optString(it) }.toSet(), (0 until p.length()).map { p.optString(it) }.toSet(), o.optBoolean("initialisee"))
    }.getOrDefault(Memoire())
}
