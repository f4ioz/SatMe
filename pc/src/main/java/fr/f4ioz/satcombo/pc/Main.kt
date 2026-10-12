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
import kotlinx.coroutines.launch
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
        Catalogue(File(dossier, "cache")), PortsSeriePc(), portee,
        stockageSat = StockageFichier(File(dossier, "satellites.properties")),
        stockageAgenda = StockageFichier(File(dossier, "agenda.properties")))
    I18n.apply(station.settings.language, java.util.Locale.getDefault().language)
    fr.f4ioz.satcombo.data.AgentHttp.version = "pc"
    fr.f4ioz.satcombo.data.AgentHttp.plateforme = System.getProperty("os.name") ?: "PC"
    station.demarre()
    val carnet = CarnetPc(station, fr.f4ioz.satcombo.data.LogStore(File(dossier, "qso_log.json")))
    val son = SonStation(SonPc(), station, File(dossier, "enregistrements"), File(dossier, "sstv"))
    // The sound card used last time, started again: listening opens nothing on the rig.
    son.carte.takeIf { it.isNotBlank() }?.let { son.son.demarre(it) }
    val journal = JournalPc(station, carnet, son, File(dossier, "journal"))
    // The SDR dongle: nothing opened until asked (rtl_tcp started then, stopped with it).
    val meteor = MeteorPc(station, File(dossier, "meteor"))
    val sdr = SdrPc(station, son.son, station.stockage, portee, dossierSondes = File(dossier, "sondes"), meteor = meteor)
    portee.launch {
        while (true) {
            runCatching { son.tic() }
            runCatching { journal.tic() }
            runCatching { carnet.ticEnvoi() }
            runCatching { sdr.ticMeteor() }
            kotlinx.coroutines.delay(1000)
        }
    }
    val journalWeb = JournalWebPc(journal, station, dossier)
    // A sked as a video, on the journal's map tiles.
    val videoSked = VideoSked(File(dossier, "exports")) { z, x, y -> journalWeb.tuile(z, x, y)?.let { f -> runCatching { javax.imageio.ImageIO.read(f) }.getOrNull() } }
    // SatMe on the phone: its passes, their contacts and pictures, to the PC.
    val telephone = TelephonePc(carnet, journal, File(dossier, "enregistrements"), File(dossier, "sstv"), File(dossier, "tmp"))
    // The phone joining by a QR code: a door of its own next to the page's port, open only while the code is shown.
    val appairage = AppairagePc(telephone, port + 1)
    // DATV with a MiniTiouner, through LongMynd: nothing started until asked.
    val datv = DatvPc(station.stockage, File(dossier, "enregistrements"))
    val serveur = ServeurPc(station, port, reseau, carnet, son, journalWeb, ImportPc(station, carnet, journal), sdr, videoSked, telephone, appairage, datv)
    serveur.demarre()
    println("SatMe — station PC")
    println("  page      : http://localhost:${serveur.portReel}/" + if (reseau) "  (ouverte au réseau)" else "")
    println("  réglages  : ${dossier.absolutePath}")
    println("  Ctrl+C pour arrêter")
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { journal.ferme() }
        runCatching { sdr.arrete() }
        runCatching { datv.arrete() }
        runCatching { son.arreteEnregistrement(); son.son.arrete() }
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
