/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.FusionGp
import fr.f4ioz.satcombo.data.OmmParser
import fr.f4ioz.satcombo.data.ServeurGp
import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.data.TleSource
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.Transmitter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The satellites (orbital elements) and their transmitters, for the PC.
 *
 * **AMSAT only for the elements**: its JSON bulletin (names "AO-07"…) and its
 * TLE file, which is often fresher; the transmitters from SatNOGS DB. Both
 * are kept on disk: the station starts and predicts without a network.
 * CelesTrak is never asked from here ([interdit]).
 */
class Catalogue(
    private val dossier: File,
    private val client: OkHttpClient = clientParDefaut,
    /** False: the disk only, nothing downloaded (the tests). */
    val enLigne: Boolean = true
) {

    /** Where the downloads are kept (also the AMSAT status page). */
    val dossierCache: File? get() = dossier

    private val fichierGp = File(dossier, "amsat-gp.json")
    private val fichierTle = File(dossier, "amsat-tle.txt")
    /** Elements AMSAT does not carry (the METEOR-M picture satellites), from SatNOGS DB. */
    private val fichierSatnogs = File(dossier, "satnogs-tle.txt")
    private val dossierEmetteurs = File(dossier, "emetteurs")
    private val emetteurs = java.util.concurrent.ConcurrentHashMap<Int, List<Transmitter>>()

    /**
     * What to download, as on the phone: the sources ticked (AMSAT's bulletin
     * alone by default), through the SatMe GP server first (gp.f4ioz.fr), or
     * through it only. CelesTrak is never asked from here, the server only.
     */
    data class Choix(val sources: Set<String> = Sources.DEFAULT_IDS, val serveur: String = ServeurGp.DEFAUT, val seul: Boolean = false)

    @Volatile var choix = Choix()

    /** Each source's last good answer, as it came. */
    private val dossierSources = File(dossier, "sources")
    private fun fichierSource(id: String) = File(dossierSources, id.replace(':', '_') + ".txt")
    private val fait = File(dossierSources, "fait")

    /** The sources wanted now: those chosen, then the METEOR-M AMSAT lacks (their LRPT pictures). */
    private fun voulues(c: Choix): List<TleSource> =
        Sources.aTelecharger(c.sources) + SATNOGS_EN_PLUS.map { TleSource("plus:$it", "METEOR #$it", Sources.satnogsNumero(it)) }

    /** The satellites known now, from the disk (empty before the first download). */
    fun satellites(): List<TleEntry> {
        val listes = voulues(choix).mapNotNull { s -> fichierSource(s.id).takeIf { it.isFile }?.let { f -> runCatching { lis(f.readText()) }.getOrNull() } }
        if (listes.isNotEmpty()) return FusionGp.fusionne(listes).sortedBy { it.name.uppercase() }
        return ancien()
    }

    /** What a version before the sources' choice kept (AMSAT's two files, SatNOGS' METEOR). */
    private fun ancien(): List<TleEntry> {
        val amsat = fusionne(
            runCatching { OmmParser.parse(fichierGp.readText()) }.getOrDefault(emptyList()),
            runCatching { lisTle(fichierTle.readText()) }.getOrDefault(emptyList()))
        // SatNOGS' only for what AMSAT lacks: AMSAT's names and elements first.
        val connus = amsat.map { it.catalogNumber }.toSet()
        val autres = runCatching { lisTle(fichierSatnogs.readText()) }.getOrDefault(emptyList()).filter { it.catalogNumber !in connus }
        return (amsat + autres).sortedBy { it.name.uppercase() }
    }

    /** Age of the elements on disk (ms), null when there are none. */
    fun age(maintenant: Long = System.currentTimeMillis()): Long? =
        (fait.takeIf { it.isFile } ?: fichierGp.takeIf { it.isFile })?.let { maintenant - it.lastModified() }

    /** The sources chosen are not those on disk (a source ticked since): download again. */
    fun choixChange(): Boolean = runCatching { fait.readText().trim() }.getOrDefault("") != signature(choix)

    private fun signature(c: Choix) = (c.sources.sorted() + ServeurGp.normalise(c.serveur) + c.seul.toString()).joinToString(",")

    /**
     * Downloads each source chosen, the SatMe GP server first, the source
     * itself next unless the server only is wanted; what was on disk stays
     * for a source that does not answer. Returns a word for the operator.
     */
    fun rafraichis(): String {
        val c = choix
        dossierSources.mkdirs()
        val serveur = ServeurGp.normalise(c.serveur)
        var parServeur = 0; var directes = 0
        val muettes = ArrayList<String>()
        for (s in voulues(c)) {
            val adresses = ServeurGp.adresses(c.serveur, s, c.seul).filterNot(::interdit)
            var lu: Pair<String, String>? = null
            for (a in adresses) {
                val t = runCatching { telecharge(a) }.getOrNull() ?: continue
                if (lis(t).isNotEmpty()) { lu = a to t; break }
            }
            if (lu == null) {
                // A picture satellite the server does not know yet is not worth a word each.
                if (':' !in s.id || s.id.startsWith("plus:")) muettes += s.label
                continue
            }
            fichierSource(s.id).writeText(lu.second)
            if (serveur.isNotEmpty() && lu.first.startsWith(serveur)) parServeur++ else directes++
        }
        if (parServeur + directes > 0) fait.writeText(signature(c))
        val n = satellites().size
        val ou = when {
            parServeur + directes == 0 -> return "aucune source ne répond : éléments du disque gardés" + (if (muettes.isEmpty()) "" else " (" + muettes.joinToString(", ") + ")")
            directes == 0 -> serveur.removePrefix("https://").removePrefix("http://")
            parServeur == 0 -> "les sources elles-mêmes"
            else -> serveur.removePrefix("https://").removePrefix("http://") + " et les sources"
        }
        return "éléments à jour : $n satellites ($ou)" + (if (muettes.isEmpty()) "" else " · sans réponse : " + muettes.joinToString(", "))
    }

    /** The transmitters of a satellite, from SatNOGS DB (then from disk). */
    fun emetteurs(catnum: Int): List<Transmitter> {
        emetteurs[catnum]?.let { return it }
        dossierEmetteurs.mkdirs()
        val f = File(dossierEmetteurs, "$catnum.json")
        val texte = runCatching {
            telecharge("https://db.satnogs.org/api/transmitters/?satellite__norad_cat_id=$catnum&format=json")
        }.getOrNull()?.also { runCatching { f.writeText(it) } } ?: runCatching { f.readText() }.getOrNull()
        val l = texte?.let { lisEmetteurs(it) }.orEmpty()
        if (texte != null) emetteurs[catnum] = l
        return l
    }

    private fun telecharge(url: String): String {
        require(!interdit(url)) { "source interdite : $url" }
        check(enLigne) { "hors ligne" }
        val req = Request.Builder().url(url).header("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            return r.body?.string().orEmpty()
        }
    }

    companion object {
        const val AMSAT_GP = "https://newark192.amsat.org/gpdata/current/daily-bulletin.json"
        const val AMSAT_TLE = "https://www.amsat.org/tle/current/nasabare.txt"

        /** Asked from SatNOGS DB as AMSAT's bulletin lacks them: METEOR-M2-3 and M2-4 (LRPT pictures). */
        val SATNOGS_EN_PLUS = listOf(57166, 59051)

        /** SatNOGS DB's elements: [{"tle0": name, "tle1", "tle2", …}]. */
        fun lisTleSatnogs(json: String): List<TleEntry> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                runCatching { TleEntry(o.optString("tle0").removePrefix("0 ").trim(), o.getString("tle1"), o.getString("tle2")) }.getOrNull()
            }
        }.getOrDefault(emptyList())

        /** Elements of any source, the format recognised: OMM JSON, SatNOGS' TLE in JSON, or TLE; usable ones only. */
        fun lis(texte: String): List<TleEntry> {
            val t = texte.trimStart()
            val brut = when {
                t.startsWith("[") && t.contains("\"tle1\"") -> lisTleSatnogs(texte)
                t.startsWith("[") || t.startsWith("{") -> runCatching { OmmParser.parse(texte) }.getOrDefault(emptyList())
                else -> lisTle(texte)
            }
            return brut.filter { fr.f4ioz.satcombo.domain.PassPredictor.elementsUtilisables(it) }
        }

        /** CelesTrak refuses this station's address: never asked from the PC. */
        fun interdit(url: String): Boolean = url.contains("celestrak", ignoreCase = true)

        private val clientParDefaut by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        /** A TLE file: name, line 1, line 2, again and again. */
        fun lisTle(texte: String): List<TleEntry> {
            val l = texte.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val out = ArrayList<TleEntry>()
            var i = 0
            while (i + 2 < l.size) {
                if (l[i + 1].startsWith("1 ") && l[i + 2].startsWith("2 ")) {
                    runCatching { TleEntry(l[i].trim(), l[i + 1], l[i + 2]) }.getOrNull()
                        ?.takeIf { it.catalogNumber > 0 }?.let { out += it }
                    i += 3
                } else i++
            }
            return out
        }

        /**
         * One satellite per number: the name of the JSON bulletin (AMSAT's
         * "AO-07"), the freshest elements of the two.
         */
        fun fusionne(gp: List<TleEntry>, tle: List<TleEntry>): List<TleEntry> {
            val parNumero = LinkedHashMap<Int, TleEntry>()
            for (e in gp) parNumero[e.catalogNumber] = e
            for (e in tle) {
                val a = parNumero[e.catalogNumber]
                parNumero[e.catalogNumber] = when {
                    a == null -> e
                    (e.epochMs ?: 0L) > (a.epochMs ?: 0L) -> e.copy(name = a.name)
                    else -> a
                }
            }
            return parNumero.values.sortedBy { it.name.uppercase() }
        }

        /** SatNOGS transmitters: active first, those with a downlink before the others. */
        fun lisEmetteurs(json: String): List<Transmitter> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                fun hz(k: String) = o.optLong(k, -1).takeIf { it > 0 }
                Transmitter(
                    description = o.optString("description", "Transmitter"),
                    mode = o.optString("mode").takeIf { it.isNotBlank() && it != "null" },
                    uplinkLowHz = hz("uplink_low"), uplinkHighHz = hz("uplink_high"),
                    downlinkLowHz = hz("downlink_low"), downlinkHighHz = hz("downlink_high"),
                    invert = o.optBoolean("invert", false), alive = o.optBoolean("alive", false),
                    type = o.optString("type", "Transmitter"))
            }.sortedWith(compareByDescending<Transmitter> { it.alive }.thenByDescending { it.downlinkLowHz != null })
        }.getOrDefault(emptyList())
    }
}
