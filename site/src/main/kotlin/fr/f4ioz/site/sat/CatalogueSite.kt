/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.sat

import fr.f4ioz.satcombo.data.AgentHttp
import fr.f4ioz.satcombo.data.FusionGp
import fr.f4ioz.satcombo.data.LectureElements
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.ServeurGp
import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.location.Maidenhead
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The satellites the site knows: AMSAT's elements (its JSON bulletin and its
 * TLE file, the fresher of the two per satellite, as SatMe-PC does), through
 * the SatMe GP server first, kept on disk and fetched again every six hours.
 * The passes are SatMe's own ([PassPredictor]), worked out here for the
 * home page and the Passages page (a few minutes in a cache), in the
 * visitor's browser for the globes.
 */
class CatalogueSite(
    private val dossier: File,
    private val serveurGp: String,
    private val horsLigne: Boolean,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** A source as it was last read: when, from where, how many satellites. */
    data class EtatSource(val id: String, val nom: String, val adresse: String, val lu: Long, val nombre: Int, val erreur: String = "")

    @Volatile var satellites: List<TleEntry> = emptyList(); private set
    @Volatile var etats: List<EtatSource> = emptyList(); private set
    @Volatile var dernierEssai = 0L; private set

    val predicteur = PassPredictor()
    private val sources = Sources.aTelecharger(Sources.DEFAULT_IDS)

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun fichier(id: String) = File(dossier, id.replace(Regex("[^a-z0-9_-]"), "_") + ".txt")

    /** What is on disk, at startup (before any download). */
    fun charge() {
        dossier.mkdirs()
        val listes = sources.mapNotNull { s -> fichier(s.id).takeIf { it.isFile }?.let { f -> runCatching { LectureElements.lis(f.readText()) }.getOrNull() } }
        if (listes.isNotEmpty()) satellites = FusionGp.fusionne(listes, horloge()).sortedBy { it.name.uppercase() }
        etats = sources.map { s ->
            val f = fichier(s.id)
            EtatSource(s.id, s.label, "", if (f.isFile) f.lastModified() else 0L, if (f.isFile) runCatching { LectureElements.lis(f.readText()).size }.getOrDefault(0) else 0)
        }
    }

    /** Downloads the sources again (the SatMe GP server, then the source itself); what is on disk stays for a silent one. */
    fun rafraichis(): String {
        dernierEssai = horloge()
        if (horsLigne) return "hors ligne : éléments du disque"
        dossier.mkdirs()
        val nouveaux = ArrayList<EtatSource>()
        for (s in sources) {
            var fait: EtatSource? = null
            var erreur = ""
            for (a in ServeurGp.adresses(serveurGp, s).filterNot { it.contains("celestrak", true) }) {
                val t = runCatching { telecharge(a) }.onFailure { erreur = it.message ?: "erreur" }.getOrNull() ?: continue
                val l = LectureElements.lis(t)
                if (l.isEmpty()) { erreur = "réponse vide"; continue }
                fichier(s.id).writeText(t)
                fait = EtatSource(s.id, s.label, a, horloge(), l.size); break
            }
            nouveaux += fait ?: (etats.firstOrNull { it.id == s.id }?.copy(erreur = erreur) ?: EtatSource(s.id, s.label, "", 0L, 0, erreur))
        }
        etats = nouveaux
        charge()
        passages.clear()
        return "${satellites.size} satellites"
    }

    private fun telecharge(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", AgentHttp.USER_AGENT).build()
        client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            return r.body?.string().orEmpty()
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
        val LOCATOR = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")
    }
}
