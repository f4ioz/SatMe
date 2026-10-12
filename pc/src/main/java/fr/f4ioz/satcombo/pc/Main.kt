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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * SatMe on a PC: `satme [--port 8090] [--reseau] [--dossier DIR]`, then the
 * page at http://localhost:8090/.
 *
 * `--reseau` opens the page to the other machines of the network (a station
 * at the foot of the mast, driven from the house); without it, this PC only.
 */
fun main(args: Array<String>) {
    var port = 8090
    var reseau = false
    var dossier = dossierParDefaut()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: port
            "--reseau" -> reseau = true
            "--dossier" -> dossier = args.getOrNull(++i)?.let(::File) ?: dossier
            "-h", "--help", "--aide" -> {
                println("satme [--port 8090] [--reseau] [--dossier DIR]")
                println("  --port     port de la page (8090)")
                println("  --reseau   page ouverte aux autres machines du réseau (sinon ce PC seulement)")
                println("  --dossier  où ranger réglages et éléments (${dossierParDefaut()})")
                return
            }
        }
        i++
    }
    dossier.mkdirs()
    val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val station = StationPc(StockageFichier(File(dossier, "reglages.properties")),
        Catalogue(File(dossier, "cache")), PortsSeriePc(), portee)
    I18n.apply(station.settings.language, java.util.Locale.getDefault().language)
    fr.f4ioz.satcombo.data.AgentHttp.version = "pc"
    fr.f4ioz.satcombo.data.AgentHttp.plateforme = System.getProperty("os.name") ?: "PC"
    station.demarre()
    val carnet = CarnetPc(station, fr.f4ioz.satcombo.data.LogStore(File(dossier, "qso_log.json")))
    val serveur = ServeurPc(station, port, reseau, carnet)
    serveur.demarre()
    println("SatMe — station PC")
    println("  page      : http://localhost:${serveur.portReel}/" + if (reseau) "  (ouverte au réseau)" else "")
    println("  réglages  : ${dossier.absolutePath}")
    println("  Ctrl+C pour arrêter")
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { station.deconnecteCat() }
        runCatching { station.stationRotor.disconnectRotor() }
        serveur.arrete()
    })
    // The server and the loops run on their own threads; this one waits.
    Thread.currentThread().join()
}

/** ~/.satme on Linux and macOS, %APPDATA%\SatMe on Windows. */
fun dossierParDefaut(): File {
    val appdata = System.getenv("APPDATA")
    return if (System.getProperty("os.name").orEmpty().startsWith("Windows") && !appdata.isNullOrBlank())
        File(appdata, "SatMe") else File(System.getProperty("user.home"), ".satme")
}
