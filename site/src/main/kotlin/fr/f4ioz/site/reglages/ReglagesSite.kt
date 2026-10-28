/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.reglages

import fr.f4ioz.site.publication.TypePublication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One page of the menu: shown or not, its name and place in the rail (empty / null: the module's own). */
data class ReglageModule(val visible: Boolean = true, val libelle: String = "", val ordre: Int? = null)

/** One kind SatMe-PC publishes: taken by the site or refused, shown to the public or not. */
data class ReglagePublication(val accepte: Boolean = true, val publique: Boolean = true)

/** The banner at the top of the pages: the auto-pilot's events, each kind on or off. */
data class Bandeau(
    val actif: Boolean = false,
    val prochainPassage: Boolean = true,
    val receptionEnCours: Boolean = true,
    val derniereImage: Boolean = true,
)

/**
 * The live block: QO-100's spectrum and the DATV picture SatMe-PC sends, or
 * BATC's public pages. [source]: `station` (SatMe-PC's only), `batc` (the
 * links only), `auto` (the station's while it sends, BATC's otherwise).
 * While the auto-pilot receives an SSTV pass, the block shows it instead
 * ([basculeSstv], see [fr.f4ioz.site.direct.VueDirect]).
 */
data class Direct(
    val actif: Boolean = false,
    val source: String = "auto",
    val accueil: Boolean = true,
    val page: Boolean = true,
    val spectre: Boolean = true,
    val datv: Boolean = true,
    /** « Basculer sur un passage SSTV en cours »: the auto-pilot's pass replaces QO-100 while it is received. */
    val basculeSstv: Boolean = true,
    /** How long the pass stays after LOS or its last picture (s). */
    val maintienS: Int = 60,
) {
    val station: Boolean get() = actif && source != "batc"

    companion object {
        val SOURCES = listOf("auto", "station", "batc")
        const val MAINTIEN_MIN = 10
        const val MAINTIEN_MAX = 900
    }
}

/** The home page's cards. */
data class SectionsAccueil(
    val passages: Boolean = true,
    val qso: Boolean = true,
    val image: Boolean = true,
    val article: Boolean = true,
    val satme: Boolean = true,
    val outils: Boolean = true,
)

/**
 * The site's editorial settings, changed from « Admin › Paramètres » while it
 * runs. site.conf keeps the secrets and the infrastructure; this is what is
 * shown. Every value has the default of the site as it was before the page
 * existed: no file, nothing changes. [extra]: the keys this version does not
 * know (a newer one's), kept as they are when saving.
 */
data class Reglages(
    val modules: Map<String, ReglageModule> = emptyMap(),
    val publications: Map<TypePublication, ReglagePublication> = emptyMap(),
    val bandeau: Bandeau = Bandeau(),
    val direct: Direct = Direct(),
    val accueil: SectionsAccueil = SectionsAccueil(),
    /** The home page's satellites; null: site.conf's `satellites.accueil`. */
    val satellitesAccueil: List<String>? = null,
    val extra: JSONObject = JSONObject(),
) {
    fun module(id: String): ReglageModule = modules[id] ?: ReglageModule()
    fun publication(t: TypePublication): ReglagePublication = publications[t] ?: ReglagePublication()

    fun json(): JSONObject {
        val o = JSONObject(extra.toString())
        o.put("version", 1)
        o.put("modules", JSONObject().also { m -> modules.forEach { (k, v) ->
            m.put(k, JSONObject().put("visible", v.visible).put("libelle", v.libelle).also { j -> v.ordre?.let { j.put("ordre", it) } }) } })
        o.put("publications", JSONObject().also { m -> publications.forEach { (k, v) -> m.put(k.nom, JSONObject().put("accepte", v.accepte).put("public", v.publique)) } })
        o.put("bandeau", JSONObject().put("actif", bandeau.actif).put("prochainPassage", bandeau.prochainPassage)
            .put("receptionEnCours", bandeau.receptionEnCours).put("derniereImage", bandeau.derniereImage))
        o.put("direct", JSONObject().put("actif", direct.actif).put("source", direct.source).put("accueil", direct.accueil).put("page", direct.page)
            .put("spectre", direct.spectre).put("datv", direct.datv).put("basculeSstv", direct.basculeSstv).put("maintienS", direct.maintienS))
        o.put("accueil", JSONObject().put("passages", accueil.passages).put("qso", accueil.qso).put("image", accueil.image)
            .put("article", accueil.article).put("satme", accueil.satme).put("outils", accueil.outils))
        if (satellitesAccueil != null) o.put("satellitesAccueil", JSONArray(satellitesAccueil)) else o.remove("satellitesAccueil")
        return o
    }

    companion object {
        private val CONNUES = setOf("version", "modules", "publications", "bandeau", "direct", "accueil", "satellitesAccueil")
        private val ID_MODULE = Regex("^/[a-z0-9/_-]{0,60}$")

        /** A label as the rail shows it: one line, 40 characters at most. */
        fun libelleNet(s: String): String = s.replace(Regex("[\\p{Cntrl}<>]"), " ").trim().take(40)

        /** The satellites' list: names or catalogue numbers, 20 at most. */
        fun satellitesNets(l: List<String>): List<String> = l.map { it.trim().take(40) }.filter { it.isNotEmpty() && !it.any { c -> c.isISOControl() } }.distinct().take(20)

        /** [o] read; what is missing or wrong is the default, what is unknown is kept aside. */
        fun depuis(o: JSONObject): Reglages {
            val d = Reglages()
            fun b(j: JSONObject?, k: String, def: Boolean): Boolean = j?.opt(k).let { if (it is Boolean) it else def }
            val modules = LinkedHashMap<String, ReglageModule>()
            o.optJSONObject("modules")?.let { m ->
                for (k in m.keySet()) {
                    if (!ID_MODULE.matches(k)) continue
                    val j = m.optJSONObject(k) ?: continue
                    modules[k] = ReglageModule(b(j, "visible", true), libelleNet(j.optString("libelle", "")),
                        j.opt("ordre").let { if (it is Number) it.toInt().coerceIn(0, 999) else null })
                }
            }
            val pubs = LinkedHashMap<TypePublication, ReglagePublication>()
            o.optJSONObject("publications")?.let { m ->
                for (k in m.keySet()) {
                    val t = TypePublication.de(k) ?: continue
                    val j = m.optJSONObject(k) ?: continue
                    pubs[t] = ReglagePublication(b(j, "accepte", true), b(j, "public", true))
                }
            }
            val ba = o.optJSONObject("bandeau")
            val di = o.optJSONObject("direct")
            val ac = o.optJSONObject("accueil")
            val sats = o.optJSONArray("satellitesAccueil")?.let { a -> satellitesNets((0 until a.length()).map { a.optString(it) }) }
            val extra = JSONObject()
            for (k in o.keySet()) if (k !in CONNUES) extra.put(k, o.get(k))
            return Reglages(
                modules = modules, publications = pubs,
                bandeau = Bandeau(b(ba, "actif", d.bandeau.actif), b(ba, "prochainPassage", true), b(ba, "receptionEnCours", true), b(ba, "derniereImage", true)),
                direct = Direct(b(di, "actif", d.direct.actif), di?.optString("source")?.takeIf { it in Direct.SOURCES } ?: d.direct.source,
                    b(di, "accueil", true), b(di, "page", true), b(di, "spectre", true), b(di, "datv", true), b(di, "basculeSstv", true),
                    di?.opt("maintienS").let { if (it is Number) it.toInt().coerceIn(Direct.MAINTIEN_MIN, Direct.MAINTIEN_MAX) else 60 }),
                accueil = SectionsAccueil(b(ac, "passages", true), b(ac, "qso", true), b(ac, "image", true), b(ac, "article", true),
                    b(ac, "satme", true), b(ac, "outils", true)),
                satellitesAccueil = sats?.takeIf { it.isNotEmpty() },
                extra = extra,
            )
        }
    }
}

/**
 * [Reglages] kept in `donnees/reglages-site.json`: read at start, changed
 * live, written whole to a temporary file then moved over the old one (a
 * crash leaves the old or the new, never half of one). A file that cannot
 * be read is set aside (`.illisible`) and the defaults used.
 */
class ReglagesSite(private val fichier: File) {

    @Volatile var courant: Reglages = Reglages(); private set

    fun charge() {
        courant = if (!fichier.isFile) Reglages() else runCatching { Reglages.depuis(JSONObject(fichier.readText(Charsets.UTF_8))) }.getOrElse {
            runCatching { fichier.copyTo(File(fichier.path + ".illisible"), overwrite = true) }
            Reglages()
        }
    }

    /** [f] applied to the settings, written, then live. */
    @Synchronized fun change(f: (Reglages) -> Reglages): Reglages {
        val n = f(courant)
        ecrit(n)
        courant = n
        return n
    }

    /** Back to the defaults (the file removed: the site as before). Unknown keys stay. */
    @Synchronized fun retablis() {
        val n = Reglages(extra = courant.extra)
        if (n.extra.isEmpty) fichier.delete() else ecrit(n)
        courant = n
    }

    private fun ecrit(r: Reglages) {
        fichier.parentFile?.mkdirs()
        val tmp = File(fichier.parentFile, fichier.name + ".tmp")
        tmp.writeText(r.json().toString(2), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), fichier.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), fichier.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
