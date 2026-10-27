/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.carnet

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.data.AdifImport
import fr.f4ioz.satcombo.data.CarnetEnLigne
import fr.f4ioz.satcombo.domain.FiltreMoisson
import fr.f4ioz.site.publication.Publication
import fr.f4ioz.site.publication.Publications
import fr.f4ioz.site.publication.TypePublication
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A contact as the public logbook shows it: who, when (UTC), on which band
 * or satellite, which mode, the square (four characters: no one's house on
 * the site). Nothing else of the log is kept here.
 */
data class Qso(
    val indicatif: String,
    val quandMs: Long,
    val bande: String,
    val mode: String,
    val satellite: String,
    val carre: String,
    val pays: String,
    /** "wavelog" or "satme" (published from SatMe-PC). */
    val source: String,
) {
    fun json(): JSONObject = JSONObject().put("c", indicatif).put("t", quandMs).put("b", bande).put("m", mode).put("s", satellite)
        .put("g", carre).put("p", pays).put("o", source)

    companion object {
        fun depuis(o: JSONObject) = Qso(o.optString("c"), o.optLong("t"), o.optString("b"), o.optString("m"), o.optString("s"),
            o.optString("g"), o.optString("p"), o.optString("o"))

        private val INDICATIF = Regex("^[A-Z0-9/]{3,15}$")

        /** One ADIF record's fields as the logbook keeps them; null without a callsign or a time. */
        fun deChamps(f: Map<String, String>, source: String): Qso? {
            val call = f["CALL"].orEmpty().trim().uppercase().takeIf { INDICATIF.matches(it) } ?: return null
            val t = AdifImport.instant(f["QSO_DATE"].orEmpty(), f["TIME_ON"].orEmpty()) ?: return null
            val freq = f["FREQ"]?.trim()?.toDoubleOrNull() ?: 0.0
            val bande = f["BAND"].orEmpty().trim().lowercase().ifEmpty { if (freq > 0) Adif.band(freq).lowercase() else "" }
            val mode = f["SUBMODE"].orEmpty().ifBlank { f["MODE"].orEmpty() }.trim().uppercase().take(12)
            val sat = f["SAT_NAME"].orEmpty().trim().take(24)
            return Qso(call, t, bande.take(8), mode, sat, f["GRIDSQUARE"].orEmpty().trim().uppercase().take(4),
                f["COUNTRY"].orEmpty().trim().take(40), source)
        }

        /** Every record of an ADIF text. */
        fun adif(texte: String, source: String): List<Qso> {
            val corps = texte.split(Regex("<EOH>", RegexOption.IGNORE_CASE), 2).let { if (it.size == 2) it[1] else texte }
            return corps.split(Regex("<EOR>", RegexOption.IGNORE_CASE)).mapNotNull { b -> if ('<' in b) deChamps(AdifImport.champs(b), source) else null }
        }

        /** A contact SatMe published as JSON: `{indicatif|call, utc (ms or ISO), satellite, mode, bande, carre|locator}` (or a list of them). */
        fun json(texte: String): List<Qso> {
            val t = texte.trim()
            val l = if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } else listOf(JSONObject(t))
            return l.mapNotNull { o ->
                val call = (o.optString("indicatif").ifEmpty { o.optString("call") }).trim().uppercase().takeIf { INDICATIF.matches(it) } ?: return@mapNotNull null
                val quand = o.optLong("utc", 0L).takeIf { it > 0 } ?: Publication.parseIso(o.optString("utc")) ?: return@mapNotNull null
                val freq = o.optDouble("frequence", 0.0).let { if (it.isNaN()) 0.0 else it }
                Qso(call, quand, o.optString("bande").ifEmpty { if (freq > 0) Adif.band(freq).lowercase() else "" }.take(8),
                    o.optString("mode").uppercase().take(12), o.optString("satellite").take(24),
                    o.optString("carre").ifEmpty { o.optString("locator") }.uppercase().take(4), o.optString("pays").take(40), "satme")
            }
        }
    }
}

/**
 * The logbook: the Wavelog log (read-only key; incremental, each profile
 * from the last contact read on, as Wavelog asks), kept on disk and read
 * again every quarter of an hour, with the contacts SatMe-PC published —
 * a contact in both counted once.
 */
class Logbook(
    private val fichier: File,
    private val wavelogUrl: String,
    private val wavelogCle: String,
    private val stations: List<String>,
    private val publications: Publications,
    private val horsLigne: Boolean,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var wavelog: List<Qso> = emptyList()
    private val derniersIds = HashMap<String, Long>()
    @Volatile var luLe = 0L; private set
    @Volatile var erreur = ""; private set

    val configure: Boolean get() = wavelogUrl.isNotBlank() && wavelogCle.isNotBlank()

    fun charge() {
        runCatching {
            val o = JSONObject(fichier.readText())
            val a = o.optJSONArray("qsos") ?: JSONArray()
            wavelog = (0 until a.length()).map { Qso.depuis(a.getJSONObject(it)) }
            o.optJSONObject("ids")?.let { ids -> ids.keySet().forEach { derniersIds[it] = ids.optLong(it) } }
            luLe = o.optLong("lu")
        }
    }

    /** Reads Wavelog again (from the last contact of each profile); what was read stays when it does not answer. */
    @Synchronized fun rafraichis(): String {
        if (!configure) return "Wavelog non configuré"
        if (horsLigne) return "hors ligne"
        return try {
            val profils = stations.ifEmpty { runBlocking { CarnetEnLigne.profils(wavelogUrl, wavelogCle) }.map { it.id } }
            val nouveaux = ArrayList<Qso>()
            for (p in profils) {
                val m = runBlocking { CarnetEnLigne.moissonne(wavelogUrl, wavelogCle, listOf(p), derniersIds[p] ?: 0L, FiltreMoisson.TOUT) }
                nouveaux += Qso.adif(m.adif, "wavelog")
                derniersIds[p] = m.dernierId
            }
            val tous = (wavelog + nouveaux).distinctBy { it.indicatif + "|" + it.quandMs / 60_000L + "|" + it.bande + it.satellite }
            wavelog = tous.sortedByDescending { it.quandMs }
            luLe = horloge(); erreur = ""
            fichier.parentFile?.mkdirs()
            fichier.writeText(JSONObject().put("lu", luLe).put("ids", JSONObject(derniersIds as Map<*, *>))
                .put("qsos", JSONArray(wavelog.map { it.json() })).toString())
            "${nouveaux.size} nouveaux contacts"
        } catch (e: Exception) {
            erreur = e.message ?: "erreur"; "Wavelog : $erreur"
        }
    }

    /** Every contact, the newest first: Wavelog's and SatMe's (the same one within two minutes counted once). */
    fun tous(): List<Qso> {
        val publies = publications.visibles(TypePublication.QSO).flatMap { p ->
            runCatching {
                val t = publications.fichierDe(p).readText()
                if (p.fichier.endsWith(".json")) Qso.json(t) else Qso.adif(t, "satme").map { it.copy(source = "satme") }
            }.getOrDefault(emptyList())
        }
        val w = wavelog
        val ajout = publies.filter { q -> w.none { it.indicatif == q.indicatif && kotlin.math.abs(it.quandMs - q.quandMs) < 120_000L } }
        return (w + ajout).sortedByDescending { it.quandMs }
    }
}
