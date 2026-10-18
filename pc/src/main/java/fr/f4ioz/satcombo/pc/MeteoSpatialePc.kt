/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.espace.MeteoSpatiale
import fr.f4ioz.satcombo.plateforme.Stockage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * « Météo spatiale »: off unless the owner ticks « Récupérer la météo
 * spatiale (Internet) ». Then the NOAA SWPC products are asked every
 * [periodeMs] (the aurora map, larger, every [periodeAuroreMs]), each kept
 * in [dossier] as it came; at startup the copies on disk are shown with
 * their age and nothing is asked until the box is ticked. A product that
 * fails keeps its last copy; the failure is said quietly on the card.
 * Only services.swpc.noaa.gov is asked.
 */
class MeteoSpatialePc(private val station: StationPc, private val stockage: Stockage, private val dossier: File,
                      private val horloge: () -> Long = System::currentTimeMillis,
                      /** The fetcher (the tests' own): the text of a URL, or an exception. */
                      private val telecharge: (String) -> String = ::telechargeHttp,
                      private val periodeMs: Long = 15 * 60_000L, private val periodeAuroreMs: Long = 30 * 60_000L) {

    var actif: Boolean
        get() = stockage.getBoolean(CLE_ACTIF, false)
        set(v) { stockage.edit().putBoolean(CLE_ACTIF, v).apply(); if (v) reveille() }

    /** Called once for each new alert (a hook for other announcements). */
    @Volatile var surAlerte: ((MeteoSpatiale.Alerte) -> Unit)? = null

    private val textes = HashMap<String, String>()
    private val recus = HashMap<String, Long>()
    private val erreurs = HashMap<String, String>()
    @Volatile var dernierEssai = 0L; private set
    @Volatile private var enCours = false
    private val vues = LinkedHashSet<String>()
    @Volatile private var etatCache: MeteoSpatiale.Etat? = null
    private val reveil = java.util.concurrent.Semaphore(0)
    @Volatile private var positionCache: Pair<Double, Double>? = null

    init { charge() }

    /** The copies kept on disk (no network). */
    private fun charge() {
        for (n in MeteoSpatiale.PRODUITS.keys) {
            val f = File(dossier, "$n.json")
            if (f.isFile) runCatching { synchronized(textes) { textes[n] = f.readText(); recus[n] = f.lastModified() } }
        }
        etatCache = null
        // What was already said before a restart is not said again.
        runCatching { etat().let { e -> MeteoSpatiale.alertes(e, horloge()).forEach { vues += it.cle } } }
    }

    /** The background loop: started once; asks nothing while [actif] is off. */
    fun demarre() {
        thread(isDaemon = true, name = "satme-meteo-spatiale") {
            while (true) {
                runCatching { tic() }
                runCatching { reveil.tryAcquire(60, TimeUnit.SECONDS); reveil.drainPermits() }
            }
        }
    }

    private fun reveille() { reveil.release() }

    /** Asks what is due (each product after its period; nothing when off). */
    fun tic() {
        if (!actif || enCours) return
        val now = horloge()
        val dus = MeteoSpatiale.PRODUITS.keys.filter { n ->
            val p = if (n == "aurore") periodeAuroreMs else periodeMs
            val dernier = synchronized(textes) { maxOf(recus[n] ?: 0L, if (erreurs.containsKey(n)) dernierEssai else 0L) }
            now - dernier >= p
        }
        if (dus.isEmpty()) return
        enCours = true
        try {
            dernierEssai = now
            for (n in dus) {
                val url = MeteoSpatiale.BASE + MeteoSpatiale.PRODUITS.getValue(n)
                runCatching {
                    require(!Catalogue.interdit(url) && url.startsWith(MeteoSpatiale.BASE)) { "source interdite" }
                    val t = telecharge(url)
                    check(t.trimStart().startsWith("[") || t.trimStart().startsWith("{")) { "réponse inattendue" }
                    dossier.mkdirs()
                    val f = File(dossier, "$n.json")
                    val tmp = File(dossier, "$n.json.tmp")
                    tmp.writeText(t); tmp.renameTo(f)
                    synchronized(textes) { textes[n] = t; recus[n] = horloge(); erreurs.remove(n) }
                }.onFailure { e -> synchronized(textes) { erreurs[n] = (e.message ?: e.javaClass.simpleName).take(120) } }
            }
            etatCache = null
            annonceNouvelles()
        } finally { enCours = false }
    }

    private fun annonceNouvelles() {
        val e = etat()
        for (a in MeteoSpatiale.alertes(e, horloge())) if (vues.add(a.cle)) runCatching { surAlerte?.invoke(a) }
        while (vues.size > 200) vues.remove(vues.first())
    }

    /** What the copies say, read once per new copy (the station's position for the aurora). */
    fun etat(): MeteoSpatiale.Etat {
        val o = station.etat.value.observateur
        val ici = o?.let { it.latDeg to it.lonDeg }
        etatCache?.takeIf { ici == positionCache }?.let { return it }
        positionCache = ici
        val t = synchronized(textes) { HashMap(textes) }
        val e = MeteoSpatiale.Etat(
            t["kp"]?.let { runCatching { MeteoSpatiale.lisKp(it) }.getOrNull() } ?: emptyList(),
            t["prevision"]?.let { runCatching { MeteoSpatiale.lisPrevision(it) }.getOrNull() } ?: emptyList(),
            t["f107"]?.let { runCatching { MeteoSpatiale.lisFlux(it) }.getOrNull() },
            t["rayons"]?.let { runCatching { MeteoSpatiale.lisRayons(it) }.getOrNull() } ?: emptyList(),
            runCatching { MeteoSpatiale.lisVent(t["vent"], t["champ"]) }.getOrNull(),
            t["aurore"]?.let { runCatching { MeteoSpatiale.lisAurore(it, o?.latDeg, o?.lonDeg) }.getOrNull() })
        etatCache = e
        return e
    }

    fun json(): JSONObject {
        val now = horloge()
        val o = MeteoSpatiale.json(etat(), now).put("ok", true).put("actif", actif).put("maintenant", now)
        val (r, er) = synchronized(textes) { HashMap(recus) to HashMap(erreurs) }
        // When each product came (« données de … »), and which failed last time.
        o.put("recus", JSONObject(r.mapValues { it.value }))
        o.put("erreurs", JSONObject(er))
        o.put("dernierEssai", dernierEssai)
        o.put("source", MeteoSpatiale.BASE.removeSuffix("/"))
        o.put("station", station.etat.value.observateur != null)
        return o
    }

    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val o: JSONObject = runCatching {
            when {
                methode == "POST" && chemin == "/api/espace/reglage" -> {
                    if (p("actif").isNotEmpty()) actif = p("actif") == "1"
                    JSONObject().put("ok", true).put("actif", actif)
                }
                methode == "POST" && chemin == "/api/espace/maintenant" -> {
                    // « Mettre à jour »: only when the owner has opted in.
                    if (!actif) JSONObject().put("ok", false).put("erreur", "cocher d'abord « Récupérer la météo spatiale (Internet) »")
                    else { synchronized(textes) { recus.clear() }; thread(isDaemon = true) { runCatching { tic() } }; JSONObject().put("ok", true) }
                }
                chemin == "/api/espace/aurore" -> JSONObject().put("ok", true).put("t", etat().aurore?.prevision ?: 0L).put("ovale", MeteoSpatiale.ovaleJson(etat().aurore))
                methode == "POST" -> JSONObject().put("ok", false).put("erreur", "inconnu")
                else -> json()
            }
        }.getOrElse { JSONObject().put("ok", false).put("erreur", it.message ?: "erreur") }
        return ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val CLE_ACTIF = "pc_espace_actif"

        private val client by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build() }

        fun telechargeHttp(url: String): String {
            require(!Catalogue.interdit(url)) { "source interdite : $url" }
            val req = Request.Builder().url(url).header("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                return r.body?.string().orEmpty()
            }
        }
    }
}
