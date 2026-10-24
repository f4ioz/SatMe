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
import fr.f4ioz.satcombo.i18n.I18n
import org.json.JSONArray
import org.json.JSONObject

/** The last version this station started (« Quoi de neuf » shows what came after it). */
const val CLE_VERSION_VUE = "pc_version_vue"
/** "a_faire" from a new install until « Configuration rapide » is done or skipped, then "faite". */
const val CLE_CONFIG_RAPIDE = "pc_config_rapide"

/**
 * The page's start-up side: « Configuration rapide » on a new install,
 * « Quoi de neuf » after an update, the startup options (Réglages ›
 * Démarrage), the update check, « Déploiement ».
 *
 * The startup options, the restart and the deployment file change how the
 * station opens to the network and where its data lives: only from this PC
 * itself (127.0.0.1, ::1) or a session opened with the remote access
 * password ([permis]), and a POST from this page's own origin.
 */
class DemarragePc(
    private val station: StationPc,
    private val lanceur: LanceurPc,
    private val fichier: FichierLanceur,
    val maj: MiseAJourPc,
    private val nouveautes: NouveautesPc,
    val deploiement: DeploiementPc,
    private val redemarrage: RedemarragePc?,
    private val actuelle: String = versionPc(),
    /** Why a restart must wait (a transmission), or null. */
    private val occupe: () -> String? = { null },
    /** A remote access password is set (the warning of « Accès depuis le réseau »). */
    private val motDePasse: () -> Boolean = { false },
    /** The Windows launcher (SatMe-PC.exe) when known, for « Lancer avec Windows ». */
    private val exeWindows: String? = null,
) {
    private val stockage get() = station.stockage

    /** At start, after the settings are read: a new install gets the quick setup, not « Quoi de neuf ». */
    fun auDemarrage(neuf: Boolean) {
        if (neuf) {
            val e = stockage.edit().putString(CLE_CONFIG_RAPIDE, "a_faire")
            if (VersionPc.publiee(actuelle)) e.putString(CLE_VERSION_VUE, actuelle)
            e.apply()
        } else if (VersionPc.publiee(actuelle) && quoiDeNeuf().isEmpty() && vue() != actuelle) {
            // Nothing written for this version: noted as seen, nothing to show.
            stockage.edit().putString(CLE_VERSION_VUE, actuelle).apply()
        }
    }

    private fun vue(): String? = stockage.getString(CLE_VERSION_VUE, null)

    val configRapideAFaire: Boolean get() = stockage.getString(CLE_CONFIG_RAPIDE, "") == "a_faire"

    /** What to show once after an update (nothing on a new install, nor on a build by hand). */
    fun quoiDeNeuf(): List<NouveautesPc.Notes> =
        if (configRapideAFaire || vue() == actuelle) emptyList() else nouveautes.depuis(vue(), actuelle)

    private fun anglais() = I18n.current() == fr.f4ioz.satcombo.i18n.Lang.EN

    private fun notesJson(l: List<NouveautesPc.Notes>) = JSONArray(l.map { n ->
        JSONObject().put("version", n.version).put("html", fr.f4ioz.satcombo.demo.Markdown.html(n.texte(anglais())))
    })

    private fun source(s: LanceurPc.Source) = I18n.t("pcd_source_" + s.cle)

    /** Réglages › Démarrage and the windows: everything the page needs. */
    fun json(local: Boolean, session: Boolean): JSONObject {
        val f = fichier.lit()
        val l = lanceur
        fun opt(valeur: Any, s: LanceurPc.Source, enregistre: Any?) = JSONObject().put("valeur", valeur).put("source", s.cle)
            .put("sourceTexte", source(s)).put("enregistre", enregistre ?: JSONObject.NULL)
        val st = station.settings
        val refus = if (redemarrage == null) I18n.t("pcd_redem_inconnu") else redemarrage.refus()
        return JSONObject().put("ok", true).put("present", true).put("version", actuelle).put("dev", !VersionPc.publiee(actuelle))
            .put("local", local).put("permis", permis(local, session))
            .put("lanceur", JSONObject().put("fichier", fichier.fichier.path).put("existe", fichier.fichier.isFile)
                .put("port", opt(l.port, l.sourcePort, f.port)).put("reseau", opt(l.reseau, l.sourceReseau, f.reseau))
                .put("dossier", opt(l.dossier.path, l.sourceDossier, f.dossier)).put("copieDepuis", f.copieDepuis ?: JSONObject.NULL)
                .put("defauts", JSONObject().put("port", LanceurPc.PORT_DEFAUT).put("reseau", false).put("dossier", dossierParDefaut().path))
                .put("change", f.port?.let { it != l.port && l.sourcePort != LanceurPc.Source.LIGNE } == true ||
                    f.reseau?.let { it != l.reseau && l.sourceReseau != LanceurPc.Source.LIGNE } == true ||
                    f.dossier?.let { java.io.File(it).absoluteFile != l.dossier && l.sourceDossier != LanceurPc.Source.LIGNE } == true))
            .put("motDePasse", motDePasse())
            .put("redemarrage", JSONObject().put("possible", refus == null).put("raison", refus.orEmpty()))
            .put("configRapide", configRapideAFaire)
            .put("quoiDeNeuf", notesJson(quoiDeNeuf()))
            .put("maj", maj.etat())
            .put("deploiement", JSONObject().put("attente", deploiement.enAttente()))
            .put("station", JSONObject().put("indicatif", st.callsign).put("locator", st.manualLocator).put("nom", st.plancheNom)
                .put("utc", st.useUtc).put("langue", st.language).put("minEl", st.minElevDeg)
                .put("lat", st.manualLat.takeUnless { it.isNaN() } ?: JSONObject.NULL).put("lon", st.manualLon.takeUnless { it.isNaN() } ?: JSONObject.NULL))
    }

    /**
     * A request of the page; null when it is not one of these routes.
     * [corps] reads the request's body (at most the bytes given).
     */
    fun sert(chemin: String, methode: String, q: Map<String, String>, local: Boolean, session: Boolean,
             origine: String?, hote: String?, corps: (Long) -> ByteArray?): ReponseWeb? {
        if (!(chemin.startsWith("/api/demarrage") || chemin.startsWith("/api/maj") || chemin.startsWith("/api/deploiement") || chemin == "/deploiement.json")) return null
        val post = methode == "POST"
        // A POST from another site (a page that would turn the network on): refused.
        if (post && !SonDistantPc.origineAdmise(origine, hote)) return json(JSONObject().put("ok", false).put("erreur", "origine refusée"), "403 Forbidden")
        val ok = permis(local, session)
        fun refuse() = json(JSONObject().put("ok", false).put("erreur", I18n.t("pcd_refus_distant")))
        return when (chemin) {
            "/api/demarrage" -> json(json(local, session))
            "/api/demarrage/nouveautes" -> json(JSONObject().put("ok", true).put("version", actuelle).put("versions", notesJson(nouveautes.toutes(actuelle))))
            "/api/demarrage/vu" -> if (!post) null else {
                if (VersionPc.publiee(actuelle)) stockage.edit().putString(CLE_VERSION_VUE, actuelle).apply()
                json(JSONObject().put("ok", true))
            }
            "/api/demarrage/lanceur" -> if (!post) null else if (!ok) refuse() else json(regleLanceur(q))
            "/api/demarrage/redemarre" -> if (!post) null else if (!ok) refuse() else json(redemarre())
            "/api/demarrage/configuration" -> if (!post) null else json(configuration(q, ok))
            "/api/demarrage/autostart" -> if (!ok) refuse() else autostart(q["quoi"])
            "/api/maj" -> json(maj.etat())
            "/api/maj/reglage" -> if (!post) null else { q["on"]?.let { maj.actif = it == "1" }; json(maj.etat()) }
            "/api/maj/verifie" -> if (!post) null else json(maj.verifie())
            "/deploiement.json" -> if (!ok) refuse() else {
                val o = deploiement.exporte(q["secrets"] == "1")
                ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString(2).toByteArray(Charsets.UTF_8),
                    "Content-Disposition: attachment; filename=\"SatMe-PC-deploiement" + (if (q["secrets"] == "1") "-avec-secrets" else "") + ".json\"\r\n")
            }
            "/api/deploiement/apercu" -> if (!post) null else if (!ok) refuse() else {
                val b = corps(DeploiementPc.MAX_OCTETS.toLong()) ?: return json(JSONObject().put("ok", false).put("erreur", "fichier trop gros ou coupé"))
                json(deploiement.apercu(b, fichier.lit()))
            }
            "/api/deploiement/importe" -> if (!post) null else if (!ok) refuse() else {
                val b = corps(DeploiementPc.MAX_OCTETS.toLong()) ?: return json(JSONObject().put("ok", false).put("erreur", "fichier trop gros ou coupé"))
                val err = deploiement.importe(b, q["reglages"] == "1", q["lanceur"] == "1") { lu ->
                    val f = fichier.lit()
                    val dossier = lu.dossier?.takeIf { it != f.dossier }
                    dossier?.let { d -> LanceurPc.refusDossier(d, lanceur.dossier, copier = false)?.let { return@importe "dossier des données « $d » : $it" } }
                    runCatching { fichier.ecrit(f.copy(port = lu.port ?: f.port, reseau = lu.reseau ?: f.reseau, dossier = lu.dossier ?: f.dossier)) }
                        .exceptionOrNull()?.let { "options de démarrage non écrites : " + (it.message ?: "erreur") }
                }
                json(if (err == null) JSONObject().put("ok", true).put("attente", deploiement.enAttente()) else JSONObject().put("ok", false).put("erreur", err))
            }
            "/api/deploiement/annule" -> if (!post) null else if (!ok) refuse() else { deploiement.annule(); json(JSONObject().put("ok", true)) }
            else -> null
        }
    }

    /**
     * Réglages › Démarrage written to the launcher file: each option given
     * ("" = back to the default); the data folder checked, and with
     * `donnees=copier` today's data copied there at the next start.
     */
    fun regleLanceur(q: Map<String, String>): JSONObject {
        var f = fichier.lit()
        q["port"]?.trim()?.let { p ->
            f = if (p.isEmpty()) f.copy(port = null) else {
                val n = p.toIntOrNull()?.takeIf { it in 1024..65534 } ?: return erreur(I18n.t("pcd_port_refus"))
                f.copy(port = n)
            }
        }
        q["reseau"]?.trim()?.let { r -> f = f.copy(reseau = when (r) { "1" -> true; "0" -> false; else -> null }) }
        q["dossier"]?.trim()?.let { d ->
            val copier = q["donnees"] == "copier"
            if (d.isEmpty()) f = f.copy(dossier = null, copieDepuis = null)
            else {
                LanceurPc.refusDossier(d, lanceur.dossier, copier)?.let { return erreur(it) }
                val meme = runCatching { java.io.File(d).canonicalFile == lanceur.dossier.canonicalFile }.getOrDefault(false)
                f = f.copy(dossier = java.io.File(d).absolutePath, copieDepuis = if (copier && !meme) lanceur.dossier.path else null)
            }
        }
        runCatching { fichier.ecrit(f) }.onFailure { return erreur(I18n.t("pcd_fichier_refus") + " " + (it.message ?: "")) }
        val o = JSONObject().put("ok", true).put("message", I18n.t("pcd_prochain_demarrage"))
        val avert = mutableListOf<String>()
        if (f.reseau == true && !lanceur.reseau) avert += if (motDePasse()) I18n.t("pcd_reseau_avert") else I18n.t("pcd_reseau_avert_sans_mdp")
        if ((q.containsKey("port") && lanceur.sourcePort == LanceurPc.Source.LIGNE) || (q.containsKey("reseau") && lanceur.sourceReseau == LanceurPc.Source.LIGNE) ||
            (q.containsKey("dossier") && lanceur.sourceDossier == LanceurPc.Source.LIGNE)) avert += I18n.t("pcd_ligne_gagne")
        return o.put("avertissements", JSONArray(avert))
    }

    private fun redemarre(): JSONObject {
        val r = redemarrage ?: return erreur(I18n.t("pcd_redem_inconnu"))
        occupe()?.let { return erreur(it) }
        r.refus()?.let { return erreur(it) }
        // The answer first, then the new SatMe-PC started and this one stopped.
        Thread({ Thread.sleep(700); runCatching { r.lance() }.onFailure { println("  redémarrage impossible : " + it.message) } }, "satme-redemarrage")
            .apply { isDaemon = false; start() }
        return JSONObject().put("ok", true).put("message", I18n.t("pcd_redem_en_cours"))
    }

    /**
     * « Configuration rapide »: written to the same settings as Réglages
     * (callsign, locator or the exact point, name, time shown, language,
     * minimum elevation); its network part only where [permis].
     */
    fun configuration(q: Map<String, String>, permis: Boolean): JSONObject {
        val s = station.settings
        q["indicatif"]?.trim()?.uppercase()?.let { c ->
            if (c.isNotEmpty() && !INDICATIF.matches(c)) return erreur(I18n.t("pcd_indicatif_refus"))
            station.poseIndicatif(c)
        }
        val lat = q["lat"]?.replace(',', '.')?.trim()?.takeIf { it.isNotEmpty() }
        val lon = q["lon"]?.replace(',', '.')?.trim()?.takeIf { it.isNotEmpty() }
        if (lat != null || lon != null) {
            val la = lat?.toDoubleOrNull(); val lo = lon?.toDoubleOrNull()
            if (la == null || lo == null || station.posePosition(la, lo) == null) return erreur(I18n.t("pcd_position_refus"))
        } else q["locator"]?.trim()?.takeIf { it.isNotEmpty() }?.let { l ->
            if (!station.poseLocator(l)) return erreur(I18n.t("pcd_locator_refus"))
        }
        q["nom"]?.let { s.plancheNom = it.trim().take(60) }
        q["utc"]?.let { s.useUtc = it == "1" }
        q["langue"]?.takeIf { it in setOf("auto", "fr", "en") }?.let { s.language = it; I18n.apply(it, java.util.Locale.getDefault().language) }
        q["minEl"]?.trim()?.toIntOrNull()?.let { s.minElevDeg = it.coerceIn(0, 45); kotlinx.coroutines.runBlocking { station.recalcule() } }
        val reseau = q.filterKeys { it == "reseau" || it == "port" }
        var lanceurJson: JSONObject? = null
        if (reseau.isNotEmpty()) {
            if (!permis) return erreur(I18n.t("pcd_refus_distant"))
            lanceurJson = regleLanceur(reseau)
            if (!lanceurJson.optBoolean("ok")) return lanceurJson
        }
        if (q["fin"] == "1" || q["passe"] == "1") stockage.edit().putString(CLE_CONFIG_RAPIDE, "faite").apply()
        return JSONObject().put("ok", true).put("locator", s.manualLocator).put("indicatif", s.callsign)
            .put("lanceur", lanceurJson ?: JSONObject.NULL)
    }

    private fun autostart(quoi: String?): ReponseWeb = if (quoi == "windows")
        ReponseWeb("200 OK", "text/plain; charset=utf-8", DeploiementPc.demarrageWindows(lanceur, exeWindows).toByteArray(Charsets.UTF_8),
            "Content-Disposition: attachment; filename=\"SatMe-PC-demarrage.cmd\"\r\n")
    else ReponseWeb("200 OK", "text/plain; charset=utf-8", DeploiementPc.uniteSystemd(lanceur).toByteArray(Charsets.UTF_8),
        "Content-Disposition: attachment; filename=\"satme-pc.service\"\r\n")

    private fun erreur(t: String) = JSONObject().put("ok", false).put("erreur", t)

    private fun json(o: JSONObject, statut: String = "200 OK") =
        ReponseWeb(statut, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    companion object {
        /** A callsign as typed: letters, digits, slashes (F4IOZ, F/G4ABC/P). */
        private val INDICATIF = Regex("(?=.*[0-9])(?=.*[A-Z])[A-Z0-9]{1,8}(/[A-Z0-9]{1,8}){0,2}")

        /** The startup options, the restart and the deployment file: this PC itself, or a session opened with the password. */
        fun permis(local: Boolean, session: Boolean): Boolean = local || session
    }
}
