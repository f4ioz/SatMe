/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.i18n.I18n
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * « Déploiement » : the station's settings and its startup options in one
 * small file, to set up another PC the same way (a club's PCs, a portable
 * station). Not the backup (Réglages › Sauvegarde keeps the log, the journal
 * and the pictures too): settings only, readable JSON.
 *
 * The secrets (passwords, API keys, QRZ.com's login, the node's code…) stay
 * out unless asked; the remote access password and the HTTPS certificate are
 * never in it (files of their own). Imported: a preview first, then the
 * startup options written at once and the settings put in at the next start
 * (running, SatMe-PC holds them in memory), the settings replaced kept in
 * avant-deploiement-<date>/.
 */
class DeploiementPc(private val dossier: File, private val lanceur: LanceurPc,
                    private val reglages: () -> Map<String, String>,
                    private val horloge: () -> Long = System::currentTimeMillis) {

    private val attente get() = File(dossier, ATTENTE)

    /** The file to download: the settings (secrets only when [secrets]) and the startup options in effect. */
    fun exporte(secrets: Boolean): JSONObject {
        val r = JSONObject()
        reglages().toSortedMap().forEach { (k, v) -> if (k !in INTERNES && (secrets || !secret(k))) r.put(k, v) }
        return JSONObject().put("app", "SatMe-PC").put("deploiement", 1).put("version", versionPc()).put("date", horloge())
            .put("secrets", secrets)
            .put("lanceur", JSONObject().put("port", lanceur.port).put("reseau", lanceur.reseau).put("dossier", lanceur.dossier.path))
            .put("reglages", r)
    }

    /** A deployment file read and checked: its settings and options, or why not. */
    class Lu(val reglages: Map<String, String>, val port: Int?, val reseau: Boolean?, val dossier: String?, val version: String, val secrets: Boolean)

    fun lit(octets: ByteArray): Result<Lu> = runCatching {
        if (octets.size > MAX_OCTETS) error("fichier trop gros")
        val o = runCatching { JSONObject(String(octets, Charsets.UTF_8)) }.getOrNull() ?: error("ce n'est pas un fichier de déploiement de SatMe-PC")
        if (o.optString("app") != "SatMe-PC" || o.optInt("deploiement") != 1) error("ce n'est pas un fichier de déploiement de SatMe-PC")
        val r = LinkedHashMap<String, String>()
        o.optJSONObject("reglages")?.let { j ->
            for (k in j.keys()) {
                val v = j.optString(k)
                if (r.size < MAX_CLES && CLE.matches(k) && VALEUR.matches(v) && k !in INTERNES) r[k] = v
            }
        }
        val l = o.optJSONObject("lanceur")
        Lu(r, l?.optInt("port", 0)?.takeIf { it in 1..65535 }, l?.takeIf { it.has("reseau") }?.optBoolean("reseau"),
            l?.optString("dossier")?.takeIf { it.isNotBlank() }, o.optString("version"), o.optBoolean("secrets"))
    }

    /** What importing [octets] would change: each setting (secrets masked) and each startup option, before → after. */
    fun apercu(octets: ByteArray, actuel: FichierLanceur.Options): JSONObject {
        val lu = lit(octets).getOrElse { return JSONObject().put("ok", false).put("erreur", it.message) }
        val ici = reglages()
        val changes = JSONArray()
        var pareils = 0
        for ((k, v) in lu.reglages) {
            val avant = ici[k]
            if (avant == v) { pareils++; continue }
            val s = secret(k)
            changes.put(JSONObject().put("cle", k).put("secret", s)
                .put("avant", if (s) masque(avant) else lisible(avant)).put("apres", if (s) masque(v) else lisible(v)))
        }
        val opts = JSONArray()
        fun opt(n: String, avant: Any?, apres: Any?) { if (apres != null && avant?.toString() != apres.toString()) opts.put(JSONObject().put("nom", n).put("avant", avant?.toString() ?: "—").put("apres", apres.toString())) }
        opt("port", actuel.port ?: lanceur.port, lu.port)
        fun ouiNon(b: Boolean?) = b?.let { if (it) I18n.t("pcd_oui") else I18n.t("pcd_non") }
        opt("reseau", ouiNon(actuel.reseau ?: lanceur.reseau), ouiNon(lu.reseau))
        opt("dossier", actuel.dossier ?: lanceur.dossier.path, lu.dossier)
        return JSONObject().put("ok", true).put("version", lu.version).put("secrets", lu.secrets)
            .put("reglages", changes).put("pareils", pareils).put("lanceur", opts)
    }

    /**
     * [octets] imported: the startup options ([avecLanceur]) through [ecritLanceur] now,
     * the settings ([avecReglages]) laid aside for the next start. Null when done, else why not.
     */
    fun importe(octets: ByteArray, avecReglages: Boolean, avecLanceur: Boolean, ecritLanceur: (Lu) -> String?): String? {
        val lu = lit(octets).getOrElse { return it.message }
        if (avecLanceur) ecritLanceur(lu)?.let { return it }
        if (avecReglages && lu.reglages.isNotEmpty()) {
            val tmp = File(attente.path + ".tmp")
            tmp.writeText(JSONObject().put("date", horloge()).put("version", lu.version).put("reglages", JSONObject(lu.reglages as Map<*, *>)).toString())
            SessionsPc.prive(tmp)
            if (!tmp.renameTo(attente)) { attente.delete(); tmp.renameTo(attente) }
        }
        return null
    }

    /** Settings waiting for the next start: how many, or 0. */
    fun enAttente(): Int = runCatching { JSONObject(attente.readText()).optJSONObject("reglages")?.length() ?: 0 }.getOrDefault(0)

    fun annule() { attente.delete() }

    companion object {
        const val ATTENTE = "deploiement-attente.json"
        const val MAX_OCTETS = 4 * 1024 * 1024
        const val MAX_CLES = 10_000
        private val CLE = Regex("[A-Za-z0-9_.\\-]{1,120}")
        private val VALEUR = Regex("[silfbe]:.*", RegexOption.DOT_MATCHES_ALL)

        /** Never carried: this PC's own bookkeeping (migrations done, version seen, the quick setup's state). */
        val INTERNES = setOf("reprises", CLE_VERSION_VUE, CLE_CONFIG_RAPIDE, "pending_capture", "update_skipped")

        /** The secrets by name (passwords, keys, logins, the node's codes); any key saying mdp/token/password/secret too. */
        val SECRETS = setOf("qrz_user", "qrz_mdp", "lotw_mdp", "carnet_cle", "carnet_jeton_v2", "icom_utilisateur", "icom_mdp", "icom_utilisateur_code",
            "icom_mdp_code", "skeds_token", "pota_token", "demo_mdp", "extensions_code", "datv_noeud_cle", "noeud_code")
        private val SECRET = Regex("(mdp|token|password|passwd|secret|jeton)", RegexOption.IGNORE_CASE)

        fun secret(cle: String): Boolean = cle in SECRETS || SECRET.containsMatchIn(cle)

        private fun lisible(v: String?): String = v?.let { if (it.length > 2 && it[1] == ':') it.substring(2) else it }?.replace('\u001F', ',')?.take(120) ?: "—"
        private fun masque(v: String?): String = if (v == null || v.length <= 2) "—" else "••••"

        /**
         * At start, before anything reads the settings: those of an imported
         * deployment file put in; the file they replace kept in
         * avant-deploiement-<date>/. True when done.
         */
        fun appliqueSiPret(dossier: File, horloge: () -> Long = System::currentTimeMillis): Boolean {
            val f = File(dossier, ATTENTE)
            if (!f.isFile) return false
            val r = runCatching { JSONObject(f.readText()).optJSONObject("reglages") }.getOrNull()
            if (r == null) { f.delete(); return false }
            val ici = File(dossier, "reglages.properties")
            val p = Properties()
            if (ici.isFile) {
                ici.reader(Charsets.UTF_8).use { p.load(it) }
                val avant = File(dossier, "avant-deploiement-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(horloge()))).apply { mkdirs() }
                ici.copyTo(File(avant, ici.name), overwrite = true)
            }
            for (k in r.keys()) {
                val v = r.optString(k)
                if (CLE.matches(k) && VALEUR.matches(v) && k !in INTERNES) p.setProperty(k, v)
            }
            val tmp = File(ici.path + ".tmp")
            tmp.writer(Charsets.UTF_8).use { p.store(it, "SatMe — réglages de la station PC") }
            if (!tmp.renameTo(ici)) { ici.delete(); tmp.renameTo(ici) }
            f.delete()
            return true
        }

        /** Where `bin/satme` is: beside this jar's lib/ (the package unpacked), else the installer's ~/satme. */
        fun programme(): String {
            val jar = runCatching { File(DeploiementPc::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
            val bin = jar?.parentFile?.parentFile?.let { File(File(it, "bin"), "satme") }
            return bin?.takeIf { it.isFile }?.absolutePath ?: (System.getProperty("user.home") + "/satme/bin/satme")
        }

        /** A shell word, quoted when it needs to be. */
        private fun mot(s: String) = if (s.matches(Regex("[A-Za-z0-9_./:=+-]+"))) s else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /**
         * A systemd --user unit starting SatMe-PC with the session, to install by
         * hand: the options the command line gave now in ExecStart (they would
         * win over the file anyway), the others read from the launcher file.
         */
        fun uniteSystemd(l: LanceurPc, programme: String = programme()): String {
            val ligne = buildList {
                if (l.sourcePort == LanceurPc.Source.LIGNE) { add("--port"); add(l.port.toString()) }
                if (l.sourceReseau == LanceurPc.Source.LIGNE) add(if (l.reseau) "--reseau" else "--local")
                if (l.sourceDossier == LanceurPc.Source.LIGNE) { add("--dossier"); add(l.dossier.path) }
            }
            return buildString {
                append("# ").append(I18n.tf("pcd_systemd_titre", versionPc())).append('\n')
                append("# ").append(I18n.t("pcd_systemd_installer")).append('\n')
                append("#   mkdir -p ~/.config/systemd/user && cp satme-pc.service ~/.config/systemd/user/\n")
                append("#   systemctl --user daemon-reload && systemctl --user enable --now satme-pc\n")
                append("#   loginctl enable-linger \$USER   # ").append(I18n.t("pcd_systemd_linger")).append('\n')
                append("# ").append(I18n.tf("pcd_systemd_options", l.port, if (l.reseau) I18n.t("pcd_oui") else I18n.t("pcd_non"), l.dossier.path)).append('\n')
                append("# ").append(I18n.tf("pcd_systemd_fichier", l.fichier.path)).append('\n')
                append("# ").append(I18n.t("pcd_systemd_restart")).append("\n\n")
                append("[Unit]\nDescription=SatMe-PC\nWants=network-online.target\nAfter=network-online.target\n\n")
                append("[Service]\nExecStart=").append((listOf(programme) + ligne).joinToString(" ") { mot(it) }).append('\n')
                append("Restart=on-failure\nRestartSec=5\n\n[Install]\nWantedBy=default.target\n")
            }
        }

        /** Windows: SatMe-PC with the session, by a shortcut in the Startup folder (made by hand), or this small .cmd put there. */
        fun demarrageWindows(l: LanceurPc, exe: String?): String {
            val prog = exe ?: I18n.t("pcd_win_exe_inconnu")
            val ligne = buildList {
                if (l.sourcePort == LanceurPc.Source.LIGNE) { add("--port"); add(l.port.toString()) }
                if (l.sourceReseau == LanceurPc.Source.LIGNE) add(if (l.reseau) "--reseau" else "--local")
                if (l.sourceDossier == LanceurPc.Source.LIGNE) { add("--dossier"); add("\"" + l.dossier.path + "\"") }
            }.joinToString(" ")
            return buildString {
                append(":: ").append(I18n.tf("pcd_win_titre", versionPc())).append("\r\n")
                append(":: ").append(I18n.t("pcd_win_etape1")).append("\r\n")
                append(":: ").append(I18n.t("pcd_win_etape2")).append("\r\n")
                append(":: ").append(I18n.tf("pcd_win_etape3", prog)).append("\r\n")
                append(":: ").append(I18n.tf("pcd_systemd_options", l.port, if (l.reseau) I18n.t("pcd_oui") else I18n.t("pcd_non"), l.dossier.path)).append("\r\n")
                append(":: ").append(I18n.tf("pcd_systemd_fichier", l.fichier.path)).append("\r\n")
                append("@echo off\r\nstart \"\" \"").append(prog).append("\"").append(if (ligne.isEmpty()) "" else " $ligne").append("\r\n")
            }
        }
    }
}
