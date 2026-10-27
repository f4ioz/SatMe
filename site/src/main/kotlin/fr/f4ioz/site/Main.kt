/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.securite.Jetons
import java.io.File

/**
 * `f4ioz-site [--config site.conf] [--port N] [--ecoute ADR]`: the site.
 * `f4ioz-site mot-de-passe`: hashes the admin password for site.conf (read from the console).
 * `f4ioz-site jeton`: a new publication token, and its hash for site.conf.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "mot-de-passe" -> { motDePasse(); return }
        "jeton" -> { jeton(); return }
        "-h", "--help", "aide" -> { aide(); return }
    }
    var conf: File? = System.getenv("F4IOZ_SITE_CONF")?.let { File(it) } ?: File("/etc/f4ioz-site-kt/site.conf").takeIf { it.isFile }
    var port: Int? = null
    var ecoute: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--config" -> conf = File(args.getOrNull(++i) ?: error("--config sans fichier"))
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull()
            "--ecoute" -> ecoute = args.getOrNull(++i)
            else -> { System.err.println("option inconnue : " + args[i]); aide(); kotlin.system.exitProcess(2) }
        }
        i++
    }
    var c = Config.lis(conf)
    if (port != null) c = c.copy(port = port)
    if (ecoute != null) c = c.copy(ecoute = ecoute)
    val site = Site(c)
    val p = site.demarre()
    println("Site ${c.indicatif} sur http://${c.ecoute}:$p/ (données ${c.donnees}, contenu ${c.contenu}" +
        (conf?.let { ", configuration $it" } ?: ", configuration par défaut") + ")")
    Runtime.getRuntime().addShutdownHook(Thread { site.arrete() })
    Thread.currentThread().join()
}

private fun aide() {
    println("""f4ioz-site [--config site.conf] [--port 8001] [--ecoute 127.0.0.1]
f4ioz-site mot-de-passe   hache le mot de passe de l'admin pour site.conf (admin.mot_de_passe)
f4ioz-site jeton          un nouveau jeton de publication (à coller dans SatMe-PC) et son empreinte pour site.conf""")
}

private fun motDePasse() {
    val console = System.console()
    val mdp = if (console != null) {
        val a = String(console.readPassword("Mot de passe de l'admin : ") ?: CharArray(0))
        val b = String(console.readPassword("Encore une fois : ") ?: CharArray(0))
        if (a != b) { System.err.println("Les deux ne sont pas les mêmes."); kotlin.system.exitProcess(1) }
        a
    } else readLine().orEmpty()
    if (mdp.length < 10) { System.err.println("Au moins 10 caractères."); kotlin.system.exitProcess(1) }
    println("admin.mot_de_passe = " + Jetons.hacheMotDePasse(mdp))
}

private fun jeton() {
    val j = Jetons.nouveau()
    println("Jeton (à coller dans SatMe-PC › Réglages › Site web, puis à oublier) :\n  $j")
    println("Ligne pour site.conf :\n  publication.jeton_sha256 = " + Jetons.sha256Hex(j))
}
