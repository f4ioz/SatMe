/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.pc.PortsSeriePc
import fr.f4ioz.satcombo.pc.StockageFichier
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.Ouverture
import fr.f4ioz.satcombo.plateforme.PortSerie
import fr.f4ioz.satcombo.plateforme.PortsSerie
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** TellusMe's own folder: never SatMe's (~/.satme). $TELLUSME_HOME, else ~/.tellusme (%APPDATA%\TellusMe on Windows). */
fun dossierTellus(): File {
    System.getenv("TELLUSME_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val appdata = System.getenv("APPDATA")
    return if (System.getProperty("os.name").orEmpty().startsWith("Windows") && !appdata.isNullOrBlank())
        File(appdata, "TellusMe") else File(System.getProperty("user.home"), ".tellusme")
}

/**
 * `tellusme [--port 8092] [--reseau] [--dossier DIR] [--simulation]`.
 *
 * `--simulation`: an IC-7000 and a Brick2 in memory (the Brick2 on the
 * loopback), a DX cluster on the loopback and a stored HamQSL answer —
 * nothing reaches a real rig, a serial port or the network. Its settings are
 * kept in memory only.
 */
fun main(args: Array<String>) {
    fun opt(n: String) = args.indexOf(n).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val port = opt("--port")?.toIntOrNull() ?: 8092
    val reseau = "--reseau" in args
    val simulation = "--simulation" in args
    val dossier = opt("--dossier")?.let { File(it) } ?: dossierTellus()
    dossier.mkdirs()
    val version = ServeurTellus::class.java.`package`?.implementationVersion ?: "dev"

    val sim = if (simulation) Simulation() else null
    val stockage = if (sim != null) StockageMemoire() else StockageFichier(File(dossier, "reglages.properties"))
    val reglages = ReglagesTellus(stockage)
    sim?.prepare(reglages)
    val station = StationTellus(reglages, sim?.ports ?: PortsSeriePc(), pacingMs = if (sim != null) 0L else 40L)
    val propagation = Propagation(File(dossier, "propagation-hamqsl.xml"),
        telecharge = if (sim != null) ({ Simulation.HAMQSL }) else ({ Http.texte(Propagation.URL) }))
    val cluster = ClusterDx()
    if (reglages.clusterActif) cluster.demarre(reglages.clusterHote, reglages.clusterPort, reglages.indicatif)
    val serveur = ServeurTellus(station, CarnetTellus(dossier), propagation, cluster, port, reseau, version)
    serveur.demarre()
    println("TellusMe $version — la station terrestre de l'écosystème SatMe (ébauche, réception seule)")
    println("Page : http://localhost:${serveur.portReel}/  — dossier : ${dossier.absolutePath}" + if (sim != null) "  — SIMULATION" else "")
    Runtime.getRuntime().addShutdownHook(Thread { station.arrete(); cluster.arrete(); serveur.arrete(); sim?.ferme() })
    Thread.currentThread().join()
}

/** `--simulation`: the rigs and the cluster in memory, on the loopback. */
class Simulation {
    val ic7000 = SimulateurIc7000()
    val brick = SimulateurBrick()
    private val cluster = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }

    val ports: PortsSerie = object : PortsSerie {
        private val p = PortSerie(0, 0, 0, 1, "IC-7000 simulé (CI-V en mémoire)", "simulation:ic7000", 0, "simulation")
        override fun liste() = listOf(p)
        override fun autorise(p: PortSerie) = true
        override fun demande(p: PortSerie) {}
        override suspend fun demandeEtAttends(p: PortSerie) = true
        override suspend fun ouvre(p: PortSerie, baud: Int, lignes: Lignes, deuxArrets: Boolean) =
            Ouverture(object : SerialLink by ic7000 { override fun close() {} }, "")
    }

    init {
        Thread({
            while (!cluster.isClosed) {
                val c = runCatching { cluster.accept() }.getOrNull() ?: continue
                Thread({ runCatching { clusterSimule(c) }; runCatching { c.close() } }, "cluster-simule").apply { isDaemon = true; start() }
            }
        }, "cluster-simule-ecoute").apply { isDaemon = true; start() }
    }

    private fun clusterSimule(c: java.net.Socket) {
        val o = c.getOutputStream()
        o.write("Cluster simulé TellusMe\r\nlogin: ".toByteArray()); o.flush()
        c.getInputStream().bufferedReader().readLine()
        o.write("Bonjour, cluster en lecture seule.\r\n".toByteArray())
        val exemples = listOf("7010.5  EA8XYZ       CW 579", "7074.6  JA1ABC       FT8 -12 dB", "7090.0  OH2XX        59 tnx",
            "14025.0 K1ABC        CW up 1", "14074.8 VK3DEF       FT8 -18", "14210.0 ZS6AAA       SSB 5/9", "3573.0  DL1XYZ       FT8",
            "21074.0 PY2ZZ        FT8 -08", "28074.0 LU5HF        FT8 open", "50313.0 CT1XYZ       FT8 Es", "10116.0 G4ABC        CW")
        var i = 0
        while (!c.isClosed) {
            val e = exemples[i % exemples.size]
            val h = java.time.LocalTime.now(java.time.ZoneOffset.UTC)
            o.write("DX de F5SIM-#:    $e                %02d%02dZ\r\n".format(h.hour, h.minute).toByteArray()); o.flush()
            i++
            Thread.sleep(if (i < exemples.size) 150 else 6000)
        }
    }

    fun prepare(r: ReglagesTellus) {
        r.indicatif = "F0SIM"; r.locator = "JN18EU"; r.portCat = "simulation:ic7000"
        r.brickHote = "127.0.0.1"; r.brickPort = brick.port
        r.clusterHote = "127.0.0.1"; r.clusterPort = cluster.localPort; r.clusterActif = true
        r.poste = "BRICK2"
    }

    fun ferme() { brick.ferme(); runCatching { cluster.close() } }

    companion object {
        /** A stored HamQSL answer (its shape, made-up values). */
        val HAMQSL = """<?xml version="1.0" encoding="ISO-8859-1"?>
<solar><solardata><source url="http://www.hamqsl.com/solar.html">N0NBH</source><updated> 31 Oct 2026 1200 GMT</updated>
<solarflux>142</solarflux><aindex> 8</aindex><kindex> 2</kindex><kindexnt>No Report</kindexnt><xray>B6.4</xray><sunspots>118</sunspots>
<calculatedconditions><band name="80m-40m" time="day">Fair</band><band name="30m-20m" time="day">Good</band><band name="17m-15m" time="day">Good</band>
<band name="12m-10m" time="day">Fair</band><band name="80m-40m" time="night">Good</band><band name="30m-20m" time="night">Good</band>
<band name="17m-15m" time="night">Poor</band><band name="12m-10m" time="night">Poor</band></calculatedconditions></solardata></solar>"""
    }
}
