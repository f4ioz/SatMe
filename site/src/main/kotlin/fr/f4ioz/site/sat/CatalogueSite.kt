/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.sat

import fr.f4ioz.satcombo.data.FusionGp
import fr.f4ioz.satcombo.data.LectureElements
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.ServeurGp
import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.TleSource
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.location.Maidenhead
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The satellites the site knows: the SatMe GP server's groups
 * (gp.f4ioz.fr by default — `/gp/amsat.json`, AMSAT's bulletin; others on
 * demand: `amateur`, `satnogs`, `images` for the picture satellites asked one
 * by one), the fresher element per satellite, as SatMe does. **Only the GP
 * server is asked** — never CelesTrak, AMSAT or SatNOGS themselves
 * ([ServeurGp.adresses] with `seul`): the server fetches them for everyone.
 * Fetched again every six hours, kept on disk; a server that answers 403 or
 * 429 is left alone for an hour, then two, up to a day (the pause is written
 * to disk, a restart does not knock again), and what is on disk is served
 * meanwhile. The requests say who asks ([agent]) so the server can let the
 * site in. The passes are SatMe's own ([PassPredictor]), worked out here for
 * the home page and the Passages page (a few minutes in a cache), in the
 * visitor's browser for the globes.
 */
class CatalogueSite(
    private val dossier: File,
    private val serveurGp: String,
    private val horsLigne: Boolean,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The GP server's groups to read: `amsat`, `amateur`, `satnogs`, `images`… (SatMe's source ids also accepted). */
    groupes: List<String> = listOf("amsat"),
    /** The User-Agent of the requests: « f4ioz-site/<version> (+https://f4ioz.fr) ». */
    private val agent: String = "f4ioz-site (+https://f4ioz.fr)",
    /** The download (tests give their own; nothing leaves then): the HTTP status and the body. */
    private val telechargeur: ((String) -> Pair<Int, String>)? = null,
) {
    /** A source as it was last read: when, from where, how many satellites. */
    data class EtatSource(val id: String, val nom: String, val adresse: String, val lu: Long, val nombre: Int, val erreur: String = "")

    @Volatile var satellites: List<TleEntry> = emptyList(); private set
    @Volatile var etats: List<EtatSource> = emptyList(); private set
    @Volatile var dernierEssai = 0L; private set
    /** No request to the GP server before this instant (ms) — after a 403 or a 429. */
    @Volatile var pauseJusqua = 0L; private set
    @Volatile private var pauseMs = 0L

    val predicteur = PassPredictor()
    val sources: List<TleSource> = sourcesDe(groupes)

    /** The server, as the TLE page names it (« gp.f4ioz.fr »). */
    val serveurNom: String get() = ServeurGp.normalise(serveurGp).removePrefix("https://").removePrefix("http://")

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun fichier(id: String) = File(dossier, id.replace(Regex("[^a-z0-9_-]"), "_") + ".txt")
    private val fichierPause get() = File(dossier, "pause-gp.txt")

    /** What is on disk, at startup (before any download). */
    fun charge() {
        dossier.mkdirs()
        val listes = sources.mapNotNull { s -> fichier(s.id).takeIf { it.isFile }?.let { f -> runCatching { LectureElements.lis(f.readText()) }.getOrNull() } }
        if (listes.isNotEmpty()) satellites = FusionGp.fusionne(listes, horloge()).sortedBy { it.name.uppercase() }
        val avant = etats.associateBy { it.id }
        etats = sources.map { s ->
            val f = fichier(s.id)
            EtatSource(s.id, s.label, avant[s.id]?.adresse.orEmpty(), if (f.isFile) f.lastModified() else 0L,
                if (f.isFile) runCatching { LectureElements.lis(f.readText()).size }.getOrDefault(0) else 0, avant[s.id]?.erreur.orEmpty())
        }
        runCatching { fichierPause.takeIf { it.isFile }?.readText()?.trim()?.split(' ')?.let { p -> pauseJusqua = p[0].toLong(); pauseMs = p.getOrNull(1)?.toLong() ?: 0L } }
    }

    /** The addresses asked: the GP server's only, one per source. */
    fun adresses(): List<Pair<TleSource, String>> = sources.map { it to ServeurGp.adresses(serveurGp, it, seul = true).first() }

    /** Downloads the groups again from the GP server; what is on disk stays when it is silent, refuses, or is paused. */
    fun rafraichis(): String {
        dernierEssai = horloge()
        if (horsLigne) return "hors ligne : éléments du disque"
        if (ServeurGp.normalise(serveurGp).isEmpty()) return "pas de serveur GP"
        if (horloge() < pauseJusqua) return "serveur GP en pause jusqu'au " + java.time.Instant.ofEpochMilli(pauseJusqua)
        dossier.mkdirs()
        val nouveaux = ArrayList<EtatSource>()
        var refus = ""
        for ((s, a) in adresses()) {
            val avant = etats.firstOrNull { it.id == s.id } ?: EtatSource(s.id, s.label, "", 0L, 0)
            if (refus.isNotEmpty()) { nouveaux += avant.copy(erreur = refus); continue }
            val (code, corps) = runCatching { telecharge(a) }.getOrElse { 0 to "" }
            when {
                code == 403 || code == 429 -> {
                    refus = "refusé par le serveur GP (HTTP $code)"
                    pauseMs = (if (pauseMs <= 0L) PAUSE_MIN_MS else pauseMs * 2).coerceAtMost(PAUSE_MAX_MS)
                    pauseJusqua = horloge() + pauseMs
                    runCatching { fichierPause.writeText("$pauseJusqua $pauseMs") }
                    nouveaux += avant.copy(erreur = refus)
                }
                code !in 200..299 -> nouveaux += avant.copy(erreur = if (code == 0) "serveur GP muet" else "HTTP $code")
                else -> {
                    val l = runCatching { LectureElements.lis(corps) }.getOrDefault(emptyList())
                    if (l.isEmpty()) nouveaux += avant.copy(erreur = "réponse vide")
                    else {
                        fichier(s.id).writeText(corps)
                        nouveaux += EtatSource(s.id, s.label, a, horloge(), l.size)
                    }
                }
            }
        }
        if (refus.isEmpty() && nouveaux.any { it.erreur.isEmpty() }) { pauseMs = 0L; pauseJusqua = 0L; fichierPause.delete() }
        etats = nouveaux
        charge()
        etats = etats.map { e -> nouveaux.firstOrNull { it.id == e.id }?.let { n -> e.copy(adresse = n.adresse.ifEmpty { e.adresse }, erreur = n.erreur) } ?: e }
        passages.clear()
        return if (refus.isNotEmpty()) refus else "${satellites.size} satellites"
    }

    private fun telecharge(url: String): Pair<Int, String> {
        telechargeur?.let { return it(url) }
        val req = Request.Builder().url(url).header("User-Agent", agent).header("Accept", "application/json").build()
        client.newCall(req).execute().use { r ->
            val b = r.body
            if (!r.isSuccessful) return r.code to ""
            val octets = b?.byteStream()?.use { e -> fr.f4ioz.site.hf.Amont.litBorne(e, 64 * 1024 * 1024) } ?: ByteArray(0)
            return r.code to String(octets, Charsets.UTF_8)
        }
    }

    /** When the elements on disk were last fetched (ms), 0 when never. */
    val luLe: Long get() = etats.maxOfOrNull { it.lu } ?: 0L

    /** A satellite by catalogue number, or by name (« ISS », « AO-7 »: the bulletin's own, upper or lower case). */
    fun cherche(nomOuNumero: String): TleEntry? {
        val t = nomOuNumero.trim()
        t.toIntOrNull()?.let { n -> return satellites.firstOrNull { it.catalogNumber == n } }
        return satellites.firstOrNull { it.name.equals(t, true) }
            ?: satellites.firstOrNull { it.name.replace("-", "").equals(t.replace("-", ""), true) }
            ?: if (t.equals("ISS", true)) satellites.firstOrNull { it.catalogNumber == 25544 } else null
    }

    fun parNumero(n: Int): TleEntry? = satellites.firstOrNull { it.catalogNumber == n }

    /** An observer at a locator's centre (4 or 6 characters), null when it is not one. */
    fun observateur(locator: String): Observer? {
        val l = locator.trim().uppercase()
        if (!LOCATOR.matches(l)) return null
        val (la, lo) = Maidenhead.toLatLon(l) ?: return null
        return Observer(la, lo, 0.0, l)
    }

    private class Cache(val t: Long, val l: List<SatPass>)
    private val passages = ConcurrentHashMap<String, Cache>()

    /**
     * The passes of [sats] over [obs] in the next [heures] hours above [minEl]°, sorted by AOS;
     * kept five minutes (the same question from many visitors is worked out once).
     */
    fun passages(sats: List<TleEntry>, obs: Observer, heures: Int, minEl: Double, depuis: Long = horloge()): List<SatPass> {
        val cle = sats.joinToString(",") { it.catalogNumber.toString() } + "|" + obs.name + "|" + heures + "|" + minEl
        passages[cle]?.takeIf { horloge() - it.t < 300_000L }?.let { c -> return c.l.filter { it.losEpochMs > depuis } }
        val l = sats.flatMap { s -> runCatching { predicteur.upcomingPasses(s, obs, depuis - 30 * 60_000L, heures, minEl) }.getOrDefault(emptyList()) }
            .filter { it.losEpochMs > depuis }.sortedBy { it.aosEpochMs }
        if (passages.size > 500) passages.clear()
        passages[cle] = Cache(horloge(), l)
        return l
    }

    companion object {
        /** The first pause after a refusal, doubled at each one, a day at most. */
        const val PAUSE_MIN_MS = 3_600_000L
        const val PAUSE_MAX_MS = 24 * 3_600_000L

        /** SatMe's sources for the server's group names (`amsat` → AMSAT's bulletin, `images` → the picture satellites). */
        fun sourcesDe(groupes: List<String>): List<TleSource> {
            val toutes = Sources.ALL.associateBy { it.id }
            return groupes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().flatMap { g ->
                when (g) {
                    "amsat", "amsat_gp" -> listOfNotNull(toutes["amsat_gp"])
                    "amsat_tle" -> listOf(Sources.AMSAT_TLE)
                    Sources.IMAGES -> fr.f4ioz.satcombo.data.SatellitesImages.sources()
                    else -> listOfNotNull(toutes[g])
                }
            }.distinctBy { it.id }.ifEmpty { listOfNotNull(toutes["amsat_gp"]) }
        }

        val LOCATOR = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")
    }
}
