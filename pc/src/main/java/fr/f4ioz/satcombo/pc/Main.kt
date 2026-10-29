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
 * The same three options can be kept in the launcher file (Réglages ›
 * Démarrage, [FichierLanceur]): the command line wins over it, it wins over
 * the defaults.
 */
fun main(args: Array<String>) {
    val a = ArgumentsPc.lit(args)
    if (a.version) { println("SatMe-PC " + versionPc()); return }
    if (a.aide) {
        println("SatMe-PC " + versionPc())
        println("satme [--port 8090] [--reseau|--local] [--noeud] [--dossier DIR] [--bureau|--console] [--demo [--demo-reset]] [--version]")
        println("  --port     port de la page (8090)")
        println("  --reseau   page ouverte aux autres machines du réseau (sinon ce PC seulement) ; --local : ce PC seulement")
        println("  --noeud    SatMe-Node : seulement le MiniTiouner, piloté par le réseau (Raspberry Pi près de l'antenne)")
        println("  --dossier  où ranger réglages et éléments (${dossierParDefaut()})")
        println("  --bureau   ouvrir la page dans le navigateur, icône près de l'horloge (d'origine sous Windows)")
        println("  --demo     démonstration : station et données fictives, matériel virtuel, rien de réel ouvert (${ModeDemo.dossierParDefaut()})")
        println("  --demo-reset  la démonstration remise à neuf")
        println("  options de démarrage aussi dans ${FichierLanceur.emplacement()} (Réglages › Démarrage) ;")
        println("  la ligne de commande passe avant ce fichier")
        return
    }
    // Started again by « Redémarrer SatMe-PC »: the previous one gone first (its port, its files).
    a.apres?.let { RedemarragePc.attend(it) }
    if (a.demo && a.noeud) { println("SatMe-PC : --demo et --noeud ne vont pas ensemble"); kotlin.system.exitProcess(2) }
    // The demonstration: before anything opens; its own folder, its own start options in it, never the station's.
    if (a.demo) ModeDemo.active()
    val dossierDemo = if (a.demo) (a.dossier ?: ModeDemo.dossierParDefaut()).absoluteFile else null
    // Installed by the MSI with a `donnees` folder beside the program: an update empties that folder, the data copied out first.
    val releveMsi = if (dossierDemo != null) null else runCatching {
        DonneesMsiPc.auDemarrage(DonneesMsiPc.jar(), System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File))
    }.getOrNull()
    releveMsi?.let { println("  " + it.message) }
    // Portable (a `donnees` folder beside the program): its start options kept there too, nothing on the PC.
    val portable = releveMsi?.takeIf { !it.copie }?.dossier ?: dossierPortable()
    val fichierLanceur = FichierLanceur(dossierDemo?.let { File(it, FichierLanceur.NOM) }
        ?: portable?.let { File(it, FichierLanceur.NOM) } ?: FichierLanceur.emplacement())
    val optionsLanceur = fichierLanceur.lit().let { if (dossierDemo != null) it.copy(dossier = null, copieDepuis = null) else it }
    val lanceur = LanceurPc.resout(if (dossierDemo != null) a.copy(dossier = dossierDemo) else a, optionsLanceur, fichierLanceur.fichier, dossierDemo ?: dossierParDefaut(portable))
    val port = lanceur.port
    val reseau = lanceur.reseau
    val noeud = a.noeud
    // Started from the desktop (Windows): the browser opened, an icon by the clock.
    val bureau = a.bureau ?: OutilsPc.windows
    val dossier = lanceur.dossier
    // « Copier mes données » chosen with a new data folder: copied now, before anything opens; the old folder kept as it is.
    optionsLanceur.copieDepuis?.let { depuis ->
        if (lanceur.sourceDossier == LanceurPc.Source.FICHIER) {
            val fait = runCatching { LanceurPc.copieDonnees(java.io.File(depuis), dossier) }.getOrDefault(false)
            println(if (fait) "  données copiées de $depuis vers $dossier (l'ancien dossier est gardé)" else "  données non copiées vers $dossier (dossier déjà rempli ou source absente)")
        }
        runCatching { fichierLanceur.ecrit(fichierLanceur.lit().copy(copieDepuis = null)) }
    }
    // The demonstration's made-up data: made the first time, begun again when asked, its dates kept recent.
    val ligneDemo = if (!a.demo) null else runCatching { ModeDemo.prepare(dossier, a.demoReset) }.getOrElse {
        println("SatMe-PC (démonstration) : " + (it.message ?: it.toString())); kotlin.system.exitProcess(2)
    }
    dossier.mkdirs()
    // No console on Windows's desktop: what SatMe-PC says goes to a file, read when something goes wrong.
    if (bureau && OutilsPc.windows) runCatching {
        val journal = java.io.PrintStream(java.io.FileOutputStream(File(dossier, "satme-pc.log")), true, "UTF-8")
        System.setOut(journal); System.setErr(journal)
    }
    if (noeud) { mainNoeud(port, dossier); return }
    // Already running: its page, and nothing started twice on the same files.
    if (bureau && BureauPc.dejaLance(port)) { BureauPc.ouvre("http://localhost:$port/"); return }
    // A backup received last time: put back now, before anything reads the settings.
    val sauvegarde = SauvegardePc(dossier)
    if (runCatching { sauvegarde.appliqueSiPrete() }.getOrDefault(false)) println("  sauvegarde restaurée (les réglages remplacés sont dans avant-restauration-…)")
    // The settings of a deployment file imported: put in now too (those replaced kept in avant-deploiement-…).
    if (runCatching { DeploiementPc.appliqueSiPret(dossier) }.getOrDefault(false)) println("  réglages du fichier de déploiement repris (les réglages remplacés sont dans avant-deploiement-…)")
    // No settings yet: a new install, « Configuration rapide » offered on the page.
    val neuf = !File(dossier, "reglages.properties").exists()
    val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val reglages = StockageFichier(File(dossier, "reglages.properties"))
    val station = StationPc(reglages,
        Catalogue(File(dossier, "cache")), PortsSeriePc(), portee,
        stockageSat = StockageFichier(File(dossier, "satellites.properties")),
        stockageAgenda = StockageFichier(File(dossier, "agenda.properties")))
    // The demonstration: the simulated mast whatever is chosen, no IC-9700 by the network.
    if (ModeDemo.actif) {
        station.stationRotor.simulationSeule = true
        station.icomReseau.interdit = ModeDemo.refus("l'IC-9700 par le réseau")
    }
    I18n.apply(station.settings.language, java.util.Locale.getDefault().language)
    fr.f4ioz.satcombo.data.AgentHttp.version = "pc-" + versionPc()
    fr.f4ioz.satcombo.data.AgentHttp.plateforme = System.getProperty("os.name") ?: "PC"
    // The Doppler/CAT logs (when switched on), beside the journal of passes.
    station.dossierDoppler = File(dossier, "doppler")
    station.demarre()
    val carnet = CarnetPc(station, fr.f4ioz.satcombo.data.LogStore(File(dossier, "qso_log.json")))
    // The satellite link (a local WebSocket for other programs): only when chosen in Réglages.
    val lien = LienSatellitePc(station, reseau)
    carnet.ecouteurs += { lien.qso(it) }
    lien.demarreSiActif()
    val son = SonStation(SonPc(), station, File(dossier, "enregistrements"), File(dossier, "sstv"))
    // The demonstration's sound card: the made-up series' SSTV pictures now and then, nothing opened.
    if (ModeDemo.actif) son.son.virtuelles += SonDemo().source()
    // The sound card used last time, started again: listening opens nothing on the rig.
    // The IC-9700 by the network: its receive audio listed as a source, nothing opened until chosen.
    val icomReseau = IcomReseauPc(station, son.son, dossier)
    son.carte.takeIf { it.isNotBlank() }?.let { c ->
        // The network source logs in to the rig: in the background, the page does not wait for it.
        if (c == fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau.NOM_SOURCE)
            Thread({ son.son.demarre(c) }, "satme-icom-audio").apply { isDaemon = true; start() }
        else son.son.demarre(c)
    }
    val journal = JournalPc(station, carnet, son, File(dossier, "journal"))
    // Each FT8/FT4 slot decoded kept for the journal's passes too (after PSK Reporter's, which it does not replace).
    son.ft8.auDecode.let { avant ->
        son.ft8.auDecode = { l -> avant?.invoke(l); journal.ft.recoit(l, station.etat.value.suivi?.catalogNumber ?: 0) }
    }
    // The CW decoder knows the log's callsigns: one of them decoded is right.
    son.cw.connus = { carnet.contacts().mapNotNull { fr.f4ioz.satcombo.cw.AnalyseCw.base(it.callsign) }.toSet() }
    // FT8/FT4 hashed callsigns: those heard in clear (kept between two starts), the log's, ours.
    fr.f4ioz.satcombo.domain.Ft8Indicatifs.commun.apply {
        branche(StockageFichier(File(dossier, "ft8-indicatifs.properties")))
        runCatching { amorce(carnet.contacts().map { it.callsign }) }
        moi = { station.etat.value.indicatif }
    }
    // A KISS radio on a serial port: nothing opened until asked.
    val kiss = KissPc(station, son.aprs)
    // The SDR dongle: nothing opened until asked (rtl_tcp started then, stopped with it).
    val meteor = MeteorPc(station, File(dossier, "meteor"))
    val sdr = SdrPc(station, son.son, station.stockage, portee, dossierSondes = File(dossier, "sondes"), meteor = meteor)
    // The raw I/Q kept and played back; nothing heard from a recording or the generator goes to PSK Reporter.
    val iqSdr = IqPc(sdr, station, dossier, journal)
    // The rig's IF as a receiver (« IC-9700 FI »): centred on its dial as the CAT reads it; « recentrer le poste »
    // moves its RX by the satellite's RX shift (the CAT loop writes it; receive only).
    icomReseau.sonStation = son
    sdr.ouvreFi = { icomReseau.ouvreSourceFi() }
    sdr.cadranPoste = { if (station.etat.value.catConnecte) station.stationCat.etat.value.descenteHz else null }
    sdr.deplacePoste = { d ->
        if (station.etat.value.catConnecte && station.emetteurChoisi() != null) { station.reglageSat(calibHz = station.etat.value.calibHz + d); true } else false
    }
    son.psk.bloque = { sdr.fictif || ModeDemo.actif }
    // Nor do pictures and frames from them pass for real receptions (generator: never kept; recording: marked).
    val horsAntenneSdr = { sdr.generateur?.let { SstvPc.HorsAntenne(SstvPc.ESSAI) }
        ?: sdr.lecture?.let { SstvPc.HorsAntenne(SstvPc.RELECTURE, it.momentMs(), it.donnees.name) } }
    son.horsAntenne = { when (son.son.etat.value.appareil) {
        SdrPc.NOM_SOURCE -> horsAntenneSdr()
        // The demonstration's test card: seen while it comes, never kept.
        SonDemo.NOM -> SstvPc.HorsAntenne(SstvPc.ESSAI)
        else -> null
    } }
    son.sstv.horsAntenne = { src -> if (src == SstvPc.SDR) horsAntenneSdr() else son.horsAntenne() }
    // A picture is named after the satellite its sound came from (QO-100 by the dongle on QO-100, not the LEO followed).
    son.sstv.satelliteDe = { src -> satelliteSstv(src, son, sdr, station) }
    // QO-100 with the dongle: the beacon followed, the calibration held on it when chosen.
    val qo100 = Qo100Pc(station, sdr)
    son.son.auditeurs += { pcm, n, rate -> qo100.ecouteCarte(pcm, n, rate) }
    // QO-100 from one screen; the log on QO-100 from its form or while the rig is on it.
    val trafic = TraficQo100Pc(station, sdr, qo100)
    // « Niveau dans le temps » and the Sun noise: read from the receiver's spectra; the Sun's raster moves the rotor only when asked and confirmed.
    val niveaux = NiveauxPc(sdr, qo100, station.stockage)
    val soleil = SoleilPc(station, sdr, station.stockage)
    carnet.choix = { d, cockpit -> trafic.choixCarnet(d, cockpit) }
    carnet.qo100 = { trafic.qo100Carnet() }
    // « Phonie → texte »: off unless chosen; the engine (a Python helper) started only then.
    val phonie = PhoniePc(station.stockage, InstallPhoniePc(dossier), File(dossier, "phonie"))
    phonie.connus = { carnet.contacts().mapNotNull { fr.f4ioz.satcombo.cw.AnalyseCw.base(it.callsign) }.toSet() }
    phonie.contexte = { src -> contextePhonie(station, sdr, src) }
    son.son.auditeurs += { pcm, n, rate -> phonie.ecouteCarte(pcm, n, rate) }
    sdr.ecouteVoix = { pcm, n, sq -> phonie.ecouteSdr(pcm, n, fr.f4ioz.satcombo.sdr.Dsp.AUDIO_RATE, sq) }
    // SSTV hears the dongle directly (« auto »: while it plays), not only through the Son tab.
    sdr.ecouteSstv = { pcm, n -> son.sstv.ecouteSdr(pcm, n, fr.f4ioz.satcombo.sdr.Dsp.AUDIO_RATE) }
    // The SSTV automatic tuning: the dongle's fine offset when it feeds the decoder, else the rig through the CAT loop.
    son.sstv.cibleAfc = { src -> cibleAfcSstv(src, son, sdr, station) }
    // « Décodeurs multiples » on QO-100: off unless chosen; a channel's voice written with its own dial.
    val canaux = CanauxPc(station.stockage, son.sstv, phonie)
    canaux.indicatif = { station.etat.value.indicatif }
    canaux.reglagesRtty = { son.clavier.reglagesRtty }
    sdr.canaux = canaux
    // The QO-100 calibration moves only between FT8/FT4 transmissions while they decode the dongle's sound.
    qo100.creneauMs = {
        val f = son.ft8.takeIf { it.enMarche && son.son.etat.value.appareil == SdrPc.NOM_SOURCE && son.son.etat.value.actif }
        listOfNotNull(f?.let { if (it.mode == "FT4") 7_500L else 15_000L }, if (sdr.etat.value.actif) canaux.creneauMs() else null).maxOrNull()
    }
    // BPSK telemetry: the QO-100 beacon through its channel, FUNcube-like satellites by the main receiver (off by default).
    val telemesure = TelemesurePc(station.stockage, dossier, canaux)
    telemesure.satellite = { station.etat.value.suivi?.name.orEmpty() }
    sdr.ecouteTelemesure = { pcm, n -> telemesure.ecouteSdr(pcm, n) }
    // 1200–9600 bit/s packets (FSK, GMSK, G3RUH) and SSDV on the dongle's raw I/Q (off by default); SiDS sharing off unless ticked.
    sdr.iqPaquets = { iq, iq16, n, dec, essai -> telemesure.iqSdr(iq, iq16, n, dec, essai) }
    telemesure.contextePaquets = { essai ->
        val e = sdr.etat.value
        val s = station.etat.value.suivi
        fr.f4ioz.satcombo.telemesure.RecepteurPaquets.Contexte(if (essai) e.satellite else s?.name.orEmpty(), if (essai) 0 else s?.catalogNumber ?: 0,
            e.cielHz, e.reposHz, essai)
    }
    telemesure.emetteurPaquets = {
        val e = station.etat.value
        val choisi = station.emetteurChoisi()
        val t = choisi?.takeIf { fr.f4ioz.satcombo.telemesure.RecepteurPaquets.debitPour(it.mode, it.baud) != null }
            ?: e.emetteurs.firstOrNull { it.alive && fr.f4ioz.satcombo.telemesure.RecepteurPaquets.debitPour(it.mode, it.baud) != null }
        t?.let { Triple(fr.f4ioz.satcombo.telemesure.RecepteurPaquets.debitPour(it.mode, it.baud)!!, it.description, it.mode.orEmpty()) }
    }
    // SSDV in USB (SO-127's GENESIS FSK): the card's sound (the rig in USB-D) or the SDR's, off unless chosen.
    telemesure.emetteurGenesis = {
        val e = station.etat.value
        val choisi = station.emetteurChoisi()
        val t = choisi?.takeIf { fr.f4ioz.satcombo.telemesure.RecepteurGenesis.Profil.pour(it.mode, it.baud) != null }
            ?: e.emetteurs.firstOrNull { it.alive && fr.f4ioz.satcombo.telemesure.RecepteurGenesis.Profil.pour(it.mode, it.baud) != null }
        t?.let { fr.f4ioz.satcombo.telemesure.RecepteurGenesis.Profil.pour(it.mode, it.baud)!! to it.description }
    }
    telemesure.suiviGenesis = { station.etat.value.suivi?.let { it.name to it.catalogNumber } }
    telemesure.sonFictif = { son.horsAntenne() != null }
    telemesure.enregistrement = { nom -> son.fichierEnregistrement(nom) }
    telemesure.enregistrements = { son.enregistrements().filter { it.name.endsWith(".wav") }.map { it.name } }
    son.son.auditeurs += { pcm, n, rate -> runCatching { telemesure.ecouteCarte(pcm, n, rate) } }
    // SSDV through an FM repeater in AFSK 1200 (IO-86): the APRS decoder's frames searched.
    son.aprs.surOctets = { o -> runCatching { telemesure.trameAfsk(o) } }
    telemesure.station = {
        val e = station.etat.value
        e.observateur?.let { Triple(e.indicatif, it.latDeg, it.lonDeg) }
    }
    phonie.demarre()
    portee.launch {
        while (true) {
            runCatching { son.tic() }
            runCatching { journal.tic() }
            runCatching { carnet.ticEnvoi() }
            runCatching { carnet.ticAmsat() }
            runCatching { kiss.tic() }
            runCatching { sdr.ticMeteor() }
            runCatching { iqSdr.tic() }
            runCatching { qo100.tic() }
            runCatching { trafic.tic() }
            runCatching { niveaux.tic() }
            kotlinx.coroutines.delay(1000)
        }
    }
    // The sound heard here, to the browsers listening (the dongle; the sound card).
    val diffusion = DiffusionSon()
    sdr.diffusion = diffusion
    son.son.auditeurs += { pcm, n, rate -> diffusion.ajoute("carte", pcm, n, rate) }
    val journalWeb = JournalWebPc(journal, station, dossier)
    // « Journal détaillé »: the spectrum around the downlink and the decoders' text noted per pass (off unless chosen).
    val ft8Recents = { son.ft8.entendus.map { Triple(it.tranche, it.texte, listOfNotNull(it.mode, "${it.rapportDb} dB", "${it.frequenceHz} Hz").joinToString(" · ")) } }
    val spectreJournal = SpectreJournalPc(station.stockage, File(dossier, "journal/spectres"),
        passage = {
            val e = station.etat.value; val s = e.suivi; val p = e.position
            if (s != null && p != null && p.elevationDeg > -1.0 && sdr.etat.value.actif && !sdr.fictif && sdr.suitSatellite) s.catalogNumber to s.name else null
        },
        spectre = { sdr.spectre?.let { sp -> sdr.etat.value.let { e -> Triple(sp, sdr.trames, (e.fineHz + e.decalageHz).toDouble()) } } },
        cw = { son.cw.texte() }, ft8 = ft8Recents)
    portee.launch { while (true) { runCatching { spectreJournal.tic() }; kotlinx.coroutines.delay(250) } }
    // A sked as a video, on the journal's map tiles.
    val videoSked = VideoSked(File(dossier, "exports")) { z, x, y -> journalWeb.tuile(z, x, y)?.let { f -> runCatching { javax.imageio.ImageIO.read(f) }.getOrNull() } }
    // SatMe on the phone: its passes, their contacts and pictures, to the PC.
    val telephone = TelephonePc(carnet, journal, File(dossier, "enregistrements"), File(dossier, "sstv"), File(dossier, "tmp"))
    // The phone joining by a QR code: a door of its own next to the page's port, open only while the code is shown.
    val appairage = AppairagePc(telephone, port + 1)
    // DATV with a MiniTiouner, through LongMynd: nothing started until asked.
    val datv = DatvPc(station.stockage, File(dossier, "enregistrements"))
    // The SatMe-Node joined: its SDR as the receiver « SDR du Node », its transmitter when chosen (off by default).
    val noeudDistant = NoeudDistantPc(station.stockage, datv.client, recepteurDatv = { datv.recepteur }, choisitRecepteurDatv = { datv.recepteur = it })
    sdr.ouvreNoeud = { noeudDistant.ouvreSdr(0L, station.settings.sdrGainTenthDb.takeIf { it >= 0 }, station.settings.sdrPpm) }
    // Its sound subtitled, the callsigns written in its picture: each off unless chosen in the DATV tab.
    val textesDatv = TextesDatvPc(station.stockage, TextesDatvPc.Source.de(datv), phonie,
        carnet = { carnet.contacts().map { it.callsign } }, moi = { station.settings.callsign })
    datv.ecouteSon = { pcm, n, rate -> phonie.ecouteDatv(pcm, n, rate) }
    phonie.contexte = { src ->
        val k = if (src == "sdr") canaux.cadranPhonie() else null
        if (k != null) k to fr.f4ioz.satcombo.domain.TraficQo100.NOM
        else if (src != "datv") contextePhonie(station, sdr, src)
        else datv.reglage.cielKhz.let { k -> k * 1000L to (if (fr.f4ioz.satcombo.datv.IndicatifsImage.qo100(k)) "QO-100" else "DATV") }
    }
    portee.launch { while (true) { runCatching { textesDatv.tic() }; kotlinx.coroutines.delay(1000) } }
    // QO-100's wide band spectrum from the ground station: off unless chosen, connected only while the DATV tab is open.
    val large = SpectreLargePc(station.stockage, datv)
    large.indicatifImage = {
        val t = System.currentTimeMillis()
        textesDatv.confirmes().filter { t - it.dernierMs < 30_000L }.maxByOrNull { it.dernierMs }?.indicatif
    }
    large.demarre()
    portee.launch { while (true) { runCatching { large.tic() }; kotlinx.coroutines.delay(1000) } }
    // DATV transmission with the Pluto: nothing sent until the operator asks and confirms on the page.
    val emission = EmissionDatvPc(station.stockage, File(dossier, "tmp"), indicatif = { station.settings.callsign },
        locator = { station.settings.manualLocator }, plutoHote = { sdr.plutoHote }, plutoPort = { sdr.plutoPort },
        plutoOccupe = { if (sdr.plutoEnReception) "l'ADALM-PLUTO reçoit (onglet SDR) : arrêter cette réception d'abord"
            else if (sdr.adsbTient() == SdrPc.PLUTO) SdrPc.ADSB_TIENT_PLUTO
            else if (datv.sdrTenu == fr.f4ioz.satcombo.datv.Datv.PLUTO) "l'ADALM-PLUTO reçoit la DATV : arrêter cette réception d'abord" else null },
        bladerfOccupe = { if (sdr.bladerfEnReception) "le bladeRF reçoit (onglet SDR) : arrêter cette réception d'abord"
            else if (datv.sdrTenu == fr.f4ioz.satcombo.datv.Datv.BLADERF) "le bladeRF reçoit la DATV : arrêter cette réception d'abord" else null },
        bladerfProgramme = { sdr.programmeBladerf() },
        reception = { datv.etat.let { e ->
            if (!e.actif) null else {
                // The station card received lately, and packets lost now (the quality panel's figures).
                val carte = datv.carte.carte?.takeIf { System.currentTimeMillis() - datv.carte.carteMs < 15_000 }?.indicatif.orEmpty()
                val q = datv.qualite()
                val pertes = q != null && (q.optDouble("cc", 0.0) >= 0.2 || q.optDouble("tei", 0.0) > 0)
                EmissionDatvPc.Reception(e.verrouille, e.merDb, datv.reglage.cielKhz, e.service, carte, pertes)
            }
        } },
        nom = { station.settings.plancheNom }, logiciel = "SatMe-PC " + versionPc(),
        // Talkback by default: where the rig is on QO-100's narrow band, when SatMe may propose it.
        retourParDefaut = { station.qo100.etat.value.let { q ->
            q.descenteHz.takeIf { q.auPoste && q.transpondeur == fr.f4ioz.satcombo.domain.Qo100.NB.cle && fr.f4ioz.satcombo.domain.GardeQo100.proposable(it) } } },
        noeudTx = { noeudDistant.fabriqueTx { station.stockage.getFloat("datv_tx_gain_max", -10f).toDouble() } })
    // The transmitters' I/Q correction (Réglages › Émission): kept per board, used by DATV and SSTV; calibrated indoors only.
    val corrections = fr.f4ioz.satcombo.sdr.CorrectionsIq(station.stockage)
    emission.corrections = corrections
    var sstvRef: EmissionSstvPc? = null
    var beRef: EmissionBandeEtroitePc? = null
    val beEnCours = "une émission BLU par le Pluto est en cours (onglet QO-100) : la relâcher d'abord"
    val iq = CalibrationIqPc(station.stockage, corrections,
        MaterielIqPc(sdr, emission, { sstvRef }, datv, { trafic.reprend() }, { station.settings.qo100EnCours }))
    val iqEnCours = "une correction I/Q est en cours (Réglages › Émission) : l'arrêter d'abord"
    // SSTV on the air with the Pluto or the bladeRF: as DATV, nothing sent until asked and confirmed on the page.
    val planche = PlancheWebPc(station, son, dossier)
    val emissionSstv = EmissionSstvPc(station.stockage, indicatif = { station.settings.callsign }, locator = { station.settings.manualLocator },
        plutoHote = { sdr.plutoHote }, plutoPort = { sdr.plutoPort },
        plutoOccupe = { if (sdr.plutoEnReception) "l'ADALM-PLUTO reçoit (onglet SDR) : arrêter cette réception d'abord"
            else if (sdr.adsbTient() == SdrPc.PLUTO) SdrPc.ADSB_TIENT_PLUTO
            else if (datv.sdrTenu == fr.f4ioz.satcombo.datv.Datv.PLUTO) "l'ADALM-PLUTO reçoit la DATV : arrêter cette réception d'abord" else null },
        bladerfOccupe = { if (sdr.bladerfEnReception) "le bladeRF reçoit (onglet SDR) : arrêter cette réception d'abord"
            else if (datv.sdrTenu == fr.f4ioz.satcombo.datv.Datv.BLADERF) "le bladeRF reçoit la DATV : arrêter cette réception d'abord" else null },
        bladerfProgramme = { sdr.programmeBladerf() },
        autreEmission = { if (emission.etat.emet) "une émission DATV est en cours : l'arrêter d'abord" else if (iq.enCours) iqEnCours
            else if (beRef?.emet == true) beEnCours else null },
        gainMaxDb = { emission.gainMaxDb },
        planche = { planche.image(it) }, planches = { planche.modeles() },
        signauxQo100 = { qo100.signaux.recents() },
        noeudTx = { noeudDistant.fabriqueTx { emission.gainMaxDb } })
    sstvRef = emissionSstv
    emissionSstv.corrections = corrections
    emission.autreEmission = { if (emissionSstv.etat.emet) "une émission SSTV est en cours : l'arrêter d'abord" else if (iq.enCours) iqEnCours
        else if (beRef?.emet == true) beEnCours else null }
    // DATV received with the Pluto or the bladeRF: the SDR tab's board lent, given back afterwards; never while it transmits.
    datv.materiel = MaterielSdrPc(sdr, { trafic.reprend() }, { station.settings.qo100EnCours }) { r ->
        val qui = if (r == fr.f4ioz.satcombo.datv.Datv.PLUTO) fr.f4ioz.satcombo.datv.Emetteur.PLUTO else fr.f4ioz.satcombo.datv.Emetteur.BLADERF
        if (iq.enCours) iqEnCours
        else if ((emission.etat.emet && emission.etat.emetteur == qui && !emission.etat.parNoeud) || emissionSstv.emetAvec(qui) ||
            (qui == fr.f4ioz.satcombo.datv.Emetteur.PLUTO && beRef?.emet == true))
            (if (qui == fr.f4ioz.satcombo.datv.Emetteur.PLUTO) "l'ADALM-PLUTO" else "le bladeRF") + " émet : arrêter l'émission d'abord" else null
    }
    sdr.emissionPluto = { (emission.etat.emet && !emission.etat.parNoeud) || emissionSstv.emetAvec(fr.f4ioz.satcombo.datv.Emetteur.PLUTO) || iq.tient(fr.f4ioz.satcombo.datv.Emetteur.PLUTO) ||
        beRef?.emet == true }
    sdr.emissionBladerf = { (emission.etat.emet && emission.etat.emetteur == fr.f4ioz.satcombo.datv.Emetteur.BLADERF && !emission.etat.parNoeud) ||
        emissionSstv.emetAvec(fr.f4ioz.satcombo.datv.Emetteur.BLADERF) || iq.tient(fr.f4ioz.satcombo.datv.Emetteur.BLADERF) }
    // Text and the mark over the transmitted picture; its pictures and videos chosen from the page.
    emission.incrustation = IncrustationDatvPc(station.stockage)
    val fichiersDatv = FichiersDatvPc(File(dossier, "datv-sources"), File(dossier, "tmp/vignettes"))
    portee.launch { while (true) { runCatching { emission.tic() }; runCatching { emissionSstv.tic() }; kotlinx.coroutines.delay(1000) } }
    val serveur = ServeurPc(station, port, reseau, carnet, son, journalWeb, ImportPc(station, carnet, journal), sdr, videoSked, telephone, appairage, datv,
        planche, kiss, sauvegarde, MirePc(File(dossier, "exports")), diffusion, qo100, lien, phonie, emission, trafic)
    serveur.emissionSstv = emissionSstv
    serveur.noeudDistant = noeudDistant
    JsonPc.noeudMateriel = { noeudDistant.materiel }
    serveur.iq = iq
    serveur.textesDatv = textesDatv
    serveur.icomReseau = icomReseau
    serveur.fichiersDatv = fichiersDatv
    serveur.qrzDatv = QrzDatvPc(dossier, { station.settings.qrzUser.isNotBlank() && station.settings.qrzMdp.isNotBlank() },
        { c -> kotlinx.coroutines.runBlocking { carnet.ficheQrz(c) } })
    serveur.large = large
    // The wide band from our own SDR on the same LNB: off unless chosen, the receiver held only while the DATV tab shows it.
    val spectreSdr = SpectreSdrDatvPc(station.stockage, datv, sdr, { k, sr -> large.noms.nom(k, sr, System.currentTimeMillis()) })
    spectreSdr.demarre()
    serveur.spectreSdr = spectreSdr
    serveur.montage = MontagePc(dossier, { serveur.lecturesMontage() }, { serveur.fonctionsMontage.instantane(it) }, serveur.fonctionsMontage)
    serveur.memoiresSdr = MemoiresSdrPc(dossier)
    // The SDR tab's scan (run here until stopped) and band stack (noted every second); receive only.
    val balayage = BalayagePc(sdr, serveur.memoiresSdr, station.stockage, dossier)
    serveur.balayage = balayage
    portee.launch { while (true) { runCatching { balayage.ticPile() }; kotlinx.coroutines.delay(1000) } }
    serveur.niveaux = niveaux
    serveur.soleil = soleil
    serveur.canaux = canaux
    serveur.telemesure = telemesure
    // ADS-B on 1090 MHz (Avions tab): off until asked; the SDR tab's dongle taken only when the owner chooses.
    val adsb = AdsbPc(station, sdr, station.stockage, carnet)
    sdr.adsbTient = { if (adsb.tientCleSdr) SdrPc.RTL else if (adsb.tientPluto) SdrPc.PLUTO else null }
    serveur.adsb = adsb
    // AIS on 161.975 / 162.025 MHz (Navires tab): off until asked; the SDR tab's dongle taken only when the owner chooses, never from ADS-B.
    val ais = AisPc(station, sdr, station.stockage)
    ais.adsbTientCle = { adsb.tientCleSdr }
    adsb.aisTientCle = { ais.tientCleSdr }
    sdr.aisTient = { ais.tientCleSdr }
    serveur.ais = ais
    // The functions not about satellites: their tabs only when ticked in Réglages; decided once, those the station has used kept.
    fr.f4ioz.satcombo.demo.FonctionsNonSat.migre(station.stockage) { id ->
        fun ecrit(l: List<String>) = l.any { fr.f4ioz.satcombo.demo.FonctionsNonSat.present(station.stockage, it) }
        when (id) {
            "avions" -> adsb.actif || ecrit(AdsbPc.REGLAGES)
            "ais" -> ais.actif || ecrit(AisPc.REGLAGES)
            "sondes" -> (sdr.pourSonde && sdr.etat.value.actif) || ecrit(listOf("sonde_freq", "sonde_model")) || sdr.sondes.journaux().isNotEmpty()
            else -> false
        }
    }
    // The modules (Réglages › Modules): decided once too, everything on but the functions above left unticked;
    // « Publication web » on only for a station whose site was already set (address and token), else off.
    fr.f4ioz.satcombo.demo.ModulesStation.migre(station.stockage) { it == fr.f4ioz.satcombo.demo.ModulesStation.SITE && SitePc.regle(station.stockage) }
    // The demonstration's virtual hardware plugged in; its data kept recent; « Remettre à neuf » from its band.
    if (ModeDemo.actif) {
        ModeDemo.branche(station, sdr, adsb, ais)
        portee.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(3_600_000L)
                runCatching { if (ModeDemo.rafraichit(dossier)) carnet.oublieMemoire() }
            }
        }
        serveur.demoReset = { ModeDemo.remetANeuf(dossier, RedemarragePc(args)).also { carnet.oublieMemoire() } }
    }
    // Space weather: nothing asked from the Internet until ticked in its tab; the copies kept are shown meanwhile.
    serveur.espace = MeteoSpatialePc(station, station.stockage, File(dossier, "espace")).also { it.demarre() }
    // The satellites' cards (Orbites): their photo and summary asked from Wikipedia when first shown, then kept.
    serveur.fiches = FichesSatPc(File(dossier, "fiches-sat"), emetteurs = { station.emetteursDe(it) })
    // The ISS's live video: which one NASA streams today, asked only when the window opens (at most hourly).
    serveur.issDirect = IssDirectPc()
    // The pass report (Journal › Rapport): read from the data folder, the levels and the space weather kept.
    journalWeb.rapport = RapportPc(journal, station, dossier,
        niveaux = { niveaux.series.getValue(NiveauxPc.CANAL).points(86_400_000L, System.currentTimeMillis()).map { it.t to it.moy } },
        kp = { serveur.espace?.etat()?.kp.orEmpty() }, ft8 = ft8Recents)
    // Its SSTV pictures as the journal chose them for the pass (as received unless chosen).
    journalWeb.rapport?.montre = { e -> journalWeb.sstv.montrees(e).mapValues { it.value.fichier } }
    journalWeb.sstvPc = son.sstv
    serveur.spectreJournal = spectreJournal
    serveur.carte = CartePc(station, son, sdr, adsb, datv, { textesDatv }, { serveur.qrzDatv }, ais = ais)
    serveur.iqSdr = iqSdr
    // Open to the network: a password for every other machine (drawn the first time, said in the log).
    val acces = AccesDistantPc(dossier, reseau)
    val ligneAcces = runCatching { acces.initialise() }.getOrNull()
    serveur.acces = acces
    datv.diffusion = diffusion
    // The wide band chat, tune requests by UDP: each off unless chosen in the DATV tab.
    serveur.chatLarge = ChatLargePc(station.stockage, { station.settings.callsign }).also { it.demarre() }
    serveur.accordUdp = AccordUdpPc(station.stockage, reseau, { datv.reglage },
        { r -> large.veilleArrete("arrêtée : accord reçu par UDP"); datv.demarre(r) }, { datv.etat.actif }).also { it.relance() }
    // The IC-9700 transmitting (PTT held by a page's button, its microphone, the power): nothing keyed until asked.
    val emissionPoste = EmissionPostePc(station, { son.carteDuPoste() })
    emissionPoste.leila = { qo100.leilaEntendue() }
    emissionPoste.signauxQo100 = { qo100.signaux.recents() }
    emissionPoste.demarre()
    serveur.emissionPoste = emissionPoste
    // FT8 / FT4 on the air from the FT8 tab: through the PTT above, in the decoder's slots; a contact is started by
    // the operator only, the attendance watchdog stops the rest. The demonstration simulates, its fake stations answer.
    val ft8Tx = Ft8EmissionPc(station.stockage, son.ft8, emissionPoste, { station.settings.callsign }, { station.etat.value.locator },
        liaison = { liaisonFt8(station) },
        noteur = { f, mode, note ->
            val l = liaisonFt8(station)
            carnet.ajouteNumerique(f.indicatif, f.carre, f.rapportEnvoye, f.rapportRecu, note, mode, l.satellite != null,
                station.stationCat.etat.value.descenteHz)
        },
        // « Mode données (USB-D) »: the IC-9700's data flags switched by the station between two Doppler rounds.
        modeDonnees = object : Ft8EmissionPc.ModeDonnees {
            override fun possible() = station.donneesPossibles()
            override fun pret() = station.donneesPretes()
            override fun modePoste() = station.modePoste()
        })
    station.donneesFt8 = { ft8Tx.veutDonnees }
    // No mode written while the rig is keyed, by FT8/FT4 or anyone else's PTT.
    station.posteEmet = { emissionPoste.poste.etat.emet || ft8Tx.emet }
    ft8Tx.demarreBoucle()
    serveur.ft8Tx = ft8Tx
    // « Trafic satellite » (module off until switched on): presets, SO-50's arming, CW and voice memories, APRS by
    // satellite, through the same PTT; every over from an operator's click, the beacon armed for one pass only.
    val traficLeo = TraficLeoPc(station.stockage, emissionPoste, File(dossier, "trafic"), { TraficLeoPc.situation(station) },
        { station.settings.callsign }, { station.etat.value.locator },
        sourceAprs = { fr.f4ioz.satcombo.aprs.EmissionKiss.source(station.settings) },
        positionAprs = { station.etat.value.observateur?.let { o -> fr.f4ioz.satcombo.aprs.EmissionKiss.position(station.settings, o.latDeg, o.lonDeg) } },
        aprsEmis = { t, quand -> son.aprs.ajouteEmis(t, quand) },
        rearme = { station.stationCat.oublieArmement() },
        tonArme = { station.stationCat.tonArme to station.stationCat.armements })
    station.ctcssVoulu = { traficLeo.ctcssVoulu() }
    emissionPoste.gardeTrafic = { traficLeo.garde() }
    station.posteEmet = { emissionPoste.poste.etat.emet || ft8Tx.emet || traficLeo.emet }
    traficLeo.demarreBoucle()
    serveur.traficLeo = traficLeo
    if (ModeDemo.actif) {
        val ft8Demo = Ft8DemoPc(son.ft8, { station.settings.callsign })
        ft8Tx.auEnvoi = { tx, debut, hz -> ft8Demo.envoye(tx, debut, hz) }
        kotlin.concurrent.thread(isDaemon = true, name = "satme-ft8-demo") { while (true) { runCatching { ft8Demo.tic() }; Thread.sleep(20) } }
    }
    // QO-100's narrow band by the Pluto (SSB, CW, digital) and the uplink profiles: nothing keyed until a button is held,
    // every start back into the dummy load; LEILA heard while transmitting cuts it.
    val emissionBe = EmissionBandeEtroitePc(station.stockage, indicatif = { station.settings.callsign },
        plutoHote = { sdr.plutoHote }, plutoPort = { sdr.plutoPort },
        plutoOccupe = { if (sdr.plutoEnReception) "l'ADALM-PLUTO reçoit (onglet SDR) : arrêter cette réception d'abord"
            else if (sdr.adsbTient() == SdrPc.PLUTO) SdrPc.ADSB_TIENT_PLUTO
            else if (datv.sdrTenu == fr.f4ioz.satcombo.datv.Datv.PLUTO) "l'ADALM-PLUTO reçoit la DATV : arrêter cette réception d'abord" else null },
        autreEmission = {
            if (emission.etat.emet) "une émission DATV est en cours : l'arrêter d'abord"
            else if (emissionSstv.etat.emet) "une émission SSTV est en cours : l'arrêter d'abord"
            else if (iq.enCours) iqEnCours
            else if (emissionPoste.poste.etat.emet) "l'IC-9700 émet : relâcher son PTT d'abord"
            else if (ft8Tx.arme) "FT8/FT4 en émission par l'IC-9700 (onglet FT8/FT4) : l'arrêter d'abord" else null
        },
        gainMaxDb = { emission.gainMaxDb }, reseauAutorise = { station.txReseauAutorise },
        descenteCockpit = { station.qo100.etat.value.descenteHz },
        signauxQo100 = { qo100.signaux.recents() }, leila = { qo100.leilaEntendue() },
        cartes = { son.son.appareils().map { it.nom } },
        ouvreCarte = { nom, recoit -> son.son.capture(nom, recoit).let { (r, err) -> r?.second to err } })
    beRef = emissionBe
    emissionPoste.autreEmission = { if (emissionBe.emet) beEnCours else null }
    emissionBe.demarre()
    serveur.emissionBe = emissionBe
    // The antenna polarisation switch (an ESP32 on USB, or the virtual one): nothing opened until a port is chosen;
    // never a command to an antenna SatMe transmits through (the band from the frequency, both when unknown).
    val polarisation = PolarisationPc(station, dossier)
    polarisation.emissions = {
        buildList {
            // FT8/FT4 armed: keyed in its next slot, so its antenna is held between overs too.
            if (emissionPoste.poste.etat.emet || ft8Tx.arme) add(station.stationCat.etat.value.monteeHz)
            if (emissionBe.emet) add(emissionBe.cadranHz)
            if (emissionSstv.etat.emet) add(emissionSstv.etat.frequenceHz.takeIf { it > 0 })
            if (emission.etat.emet) add(emission.etat.frequenceKhz.takeIf { it > 0 }?.let { it * 1000L })
        }
    }
    polarisation.sstvEnCours = { son.sstv.etat.value.mode.isNotEmpty() }
    polarisation.niveauSdr = { sdr.etat.value.takeIf { it.actif && !sdr.fictif && sdr.suitSatellite }?.niveauDb?.toDouble() }
    polarisation.demarre()
    serveur.polarisation = polarisation
    // HTTPS for the remote page (its microphone needs it): off unless chosen on this PC.
    serveur.certificat = CertificatPc(dossier)
    // The station's frequency for logging programs (rigctld protocol): off unless chosen in Réglages › Interfaces.
    val rigctld = RigctldPc(station, sdr, reseau) { emission.etat.emet || emissionSstv.etat.emet || iq.enCours || emissionPoste.poste.etat.emet || emissionBe.emet }
    rigctld.demarreSiActif()
    serveur.rigctld = rigctld
    // The USB volume knob as a VFO (Linux): off unless chosen in Réglages › Interfaces; receive tuning only.
    val molette = MoletteUsbPc(station.stockage, station.settings, mondeMolette(station, sdr))
    // Or relayed to another station (Réglages › Molette › Relais): its session kept here, never its password.
    val relaisMolette = RelaisMolettePc(File(dossier, "relais-molette.properties"))
    molette.relais = relaisMolette
    molette.demarre()
    serveur.molette = molette
    serveur.relaisMolette = relaisMolette
    // Spoken announcements: off unless chosen in Réglages › Annonces; read from the objects above, said by the browser or this PC.
    // The SDR speaker lowered while either speaks: the lowest level asked wins.
    val baisseSdr = BaisseSon { sdr.gainHautParleur = it }
    var annoncesRef: AnnoncesPc? = null
    val suiviCw = AnnoncesPc.SuiviCw()
    val annonces = AnnoncesPc(station.stockage, {
        val a = annoncesRef ?: return@AnnoncesPc null
        instantaneAnnonces(station, son, sdr, qo100, datv, textesDatv, adsb, suiviCw,
            avions = a.reglages.veut(fr.f4ioz.satcombo.annonces.Annonces.Sorte.AVION),
            emissions = buildMap {
                if (emission.etat.emet) put("DATV", emission.etat.frequenceKhz.takeIf { it > 0 }?.let { it * 1000L })
                if (emissionSstv.etat.emet) put("SSTV", null)
                if (iq.enCours) put("correction I Q", null)
                if (emissionPoste.poste.etat.emet) put("IC-9700", null)
                if (emissionBe.emet) put("BLU Pluto", emissionBe.cadranHz)
            })
    }, { station.settings.callsign }, gainSdr = { baisseSdr.regle("annonces", it) })
    annoncesRef = annonces
    // « Traduction parlée »: each over translated said by a synthetic voice (off unless chosen; never transmitted).
    val parole = ParolePc(station.stockage, phonie.install, { phonie.install.python(phonie.python) }, { if (phonie.cible == PhoniePc.WHISPER) "en" else phonie.cible },
        baisse = { baisseSdr.regle("parole", it) })
    phonie.ecouteParole = { parole.entendu(it) }
    // The DATV's overs said where its sound is heard, that sound lowered here meanwhile.
    parole.sortiesDatv = { datv.sorties() }
    datv.gainParole = { parole.gainDatv }
    textesDatv.parole = { parole.datvActif }
    if (parole.actif) parole.demarre()
    serveur.parole = parole
    portee.launch { while (true) { runCatching { annonces.tic() }; kotlinx.coroutines.delay(1000) } }
    serveur.annonces = annonces
    // « Agenda › SSTV »: the campaigns kept; the auto-pilot receives their passes once its master switch is on (off by default).
    val autopilote = AutoPiloteSstvPc(station, dossier)
    autopilote.son = son
    autopilote.sdr = sdr
    autopilote.montage = serveur.montage
    autopilote.telemesure = telemesure
    autopilote.prendSource = { nom -> serveur.prendSource(son, nom) }
    autopilote.emissionEnCours = {
        if (emission.etat.emet) "émission DATV" else if (emissionSstv.etat.emet) "émission SSTV" else if (iq.enCours) "correction I/Q"
        else if (emissionPoste.poste.etat.emet) "émission IC-9700" else if (emissionBe.emet) "émission BLU Pluto" else null
    }
    autopilote.annonce = { t ->
        val r = annonces.reglages
        if (r.on && r.veut(fr.f4ioz.satcombo.annonces.Annonces.Sorte.AOS)) annonces.file.propose(fr.f4ioz.satcombo.annonces.Annonces.Sorte.AOS, t)
    }
    serveur.autopilote = autopilote
    portee.launch { while (true) { runCatching { autopilote.tic() }; kotlinx.coroutines.delay(1000) } }
    // « Agenda › Activités à venir »: the online sources (on by default, each one can be turned off), read at their own pace.
    val activites = if (ModeDemo.actif) ActivitesPc(station, dossier, fictives = GenerateurDemo::activites) else ActivitesPc(station, dossier)
    // « Activer l'auto-pilote » on an SSTV activity: its campaign made, kept and switched on here.
    activites.autopilote = autopilote
    serveur.activites = activites
    // The SSTV gallery sorted by slide (marks, groups, the footer read) and the series' timetable.
    val classement = ClassementSstvPc(son.sstv, station, { activites }, { autopilote })
    serveur.classement = classement
    planche.classement = classement
    planche.montage = serveur.montage
    // The journal's SSTV versions and report panels read through the sheet's events, corrections and montage.
    journalWeb.planche = planche
    // The SSTV options: the missing slide's notice, each pass's sound and how long it is kept, the QSL, the ARISS pack.
    val optionsSstv = OptionsSstvPc(station, son, classement, { activites }, dossier)
    optionsSstv.annonce = { t ->
        val r = annonces.reglages
        if (r.on && r.veut(fr.f4ioz.satcombo.annonces.Annonces.Sorte.AOS)) annonces.file.propose(fr.f4ioz.satcombo.annonces.Annonces.Sorte.AOS, t)
    }
    son.enregistreSerie = { optionsSstv.enregistreSerie() }
    // « Re-décoder chaque image à la fin »: each picture kept live, decoded again from its sound in the background.
    val redecodageAuto = fr.f4ioz.satcombo.sstv.RedecodageAuto(classement.corrections.api.corrections, { optionsSstv.options.redecodeFin })
    son.sstv.imageGardee = { d, s -> redecodageAuto.demande(d, s) }
    // « Réunir »: two partial pictures put together from the pass recording when their own sounds are gone.
    son.sstv.enregistrementDe = { son.fichierEnregistrement(it) }
    serveur.optionsSstv = optionsSstv
    // « Réglages › Envoyer vers un site web »: the SSTV pictures, planches, reports, contacts and videos sent to the owner's site (or a folder),
    // each kind by itself, at a click or never; a queue on disk sent again until the site takes it.
    val site = SitePc(station.stockage, dossier)
    val sourcesSite = SourcesSitePc(son.sstv, planche, carnet, station, File(dossier, "exports"),
        telemesure.paquets.assemblage, File(dossier, "telemesure/ssdv"))
    // An SSDV picture growing: sent again (Automatique, or Sur clic once sent by hand) when quiet or complete.
    telemesure.paquets.assemblage.surAjout = { ph -> site.nouveauPaquetSsdv(ph.id, ph.complete) }
    site.sources = { g, ref -> sourcesSite.contenu(g, ref) }
    site.planchesRemplies = { planche.planches().filter { it.images.isNotEmpty() }.map { it.id } }
    son.sstv.nouvelleImage = { nom, _ -> site.nouvelleImage(nom) }
    carnet.ecouteurs += { c -> site.nouveauContact(c.timeMs) }
    journalWeb.videoFaite = { f -> site.nouvelleVideo(f.name) }
    serveur.site = site
    site.demarre()
    // The live parts: the auto-pilot, QO-100's spectrum and the DATV picture pushed live (each off unless chosen).
    val directSite = DirectSitePc(station.stockage, site)
    directSite.pilote = { runCatching { autopilote.etatSite() }.getOrNull() }
    directSite.derniereImage = {
        runCatching { son.images().filter { !SstvPc.horsAntenne(it) }.maxByOrNull { it.timeMs } }.getOrNull()?.let { Triple(it.satName, it.timeMs, it.mode) }
    }
    // The pass's SSTV picture: the one arriving, else the last received since AOS.
    directSite.imageSstv = { aos ->
        son.sstv.imageEnCours() ?: son.images().filter { it.timeMs >= aos - 60_000L && !SstvPc.horsAntenne(it) }.maxByOrNull { it.timeMs }
            ?.let { son.sstv.fichier(it.fileName)?.readBytes() }
    }
    directSite.datv = {
        // The station watched: the signal selected on the wide band spectrum, else (locked) the best callsign confirmed;
        // its QRZ record asked for the site too (the DATV tab's cache and pace), only while « Image DATV » is sent.
        val st = runCatching {
            val (l, sp) = serveur.indicatifsDatv(datv)
            val z = serveur.qrzDatv
            val c = sp.ifEmpty { if (datv.etat.verrouille) l.firstOrNull().orEmpty() else "" }
            if (c.isEmpty()) null else {
                z?.suit(listOf(c) + l)
                DirectSitePc.StationDatv(c, sp.isNotEmpty(), z?.fiche(c), station.etat.value.observateur)
            }
        }.getOrNull()
        DirectSitePc.Datv(datv.etat, datv.reglage, datv.marge(), datv.image, st)
    }
    directSite.panorama = {
        if (!sdr.surQo100) null else sdr.panorama?.let { p -> sdr.axeQo100()?.let { (c, w) -> DirectSitePc.Panorama(p, c, w) } }
    }
    directSite.demandePanorama = { if (sdr.surQo100) sdr.demandePanorama() }
    // The satellites marked ★, with their names (null until the catalogue is read).
    directSite.favoris = {
        val e = station.etat.value
        if (e.favoris.isNotEmpty() && e.satellites.isEmpty()) null
        else e.favoris.map { n -> (e.satellites.firstOrNull { it.catalogNumber == n }?.name ?: "") to n }
    }
    // « Station »: the satellite worked, where the antennas really point, the receivers and the rig (names, angles,
    // frequencies; never a port or an address).
    directSite.station = {
        stationSite(station, sdr, datv, son,
            enEmission = emission.etat.emet || emissionSstv.etat.emet || emissionPoste.poste.etat.emet || emissionBe.emet || ft8Tx.emet,
            passageSstv = { runCatching { autopilote.etatSite()?.optJSONObject("enCours")?.optString("phase") == "EN_COURS" }.getOrDefault(false) })
    }
    site.direct = directSite
    // « Spectre rapide » (off by default): QO-100's spectrum streamed to the site, 1 to 20 lines a second, over one connection.
    val spectreRapide = SpectreRapidePc(station.stockage, site)
    spectreRapide.panorama = directSite.panorama
    spectreRapide.trames = { sdr.panoramaTrames }
    spectreRapide.demandePanorama = { ms -> if (sdr.surQo100) sdr.demandePanorama(ms) }
    directSite.rapide = spectreRapide
    spectreRapide.demarre()
    // « Vidéo DATV en direct » (off by default): the received DATV as HLS, its subtitles and the translated voice's track.
    val videoSite = VideoSitePc(station.stockage, site)
    videoSite.source = if (ModeDemo.actif) SourceTsDemo().also { d -> d.voix = { pcm, r -> videoSite.parle(pcm, r) } } else object : VideoSitePc.SourceTs {
        override fun branche(f: ((ByteArray) -> Unit)?) { datv.versSite = f }
        override fun verrouille(): Boolean = datv.etat.actif && datv.etat.verrouille
        override fun service(): String = datv.etat.service
        override fun kbps(): Int = datv.etat.kbps
    }
    videoSite.repliques = { depuis -> phonie.bulles(depuis).filter { it.source == "datv" } }
    videoSite.derniereReplique = { phonie.dernierId() }
    videoSite.paroleDatv = { if (parole.datvActif) (if (phonie.cible == PhoniePc.WHISPER) "en" else phonie.cible) else "" }
    videoSite.traductionDatv = { if (phonie.traduire) (if (phonie.cible == PhoniePc.WHISPER) "en" else phonie.cible) else "" }
    videoSite.imageDatv = { directSite.on(DirectSitePc.Canal.DATV) }
    parole.versSiteDatv = { pcm, r -> videoSite.parle(pcm, r) }
    directSite.video = videoSite
    videoSite.demarre()
    // « FT8/FT4 » (off by default): each slot decoded sent live (direct-ft8), a journal's pass with its messages (ft8-passage).
    val ft8Site = Ft8SitePc()
    ft8Site.entendus = { son.ft8.entendus }
    ft8Site.ecoute = { son.ft8.enMarche }
    ft8Site.mode = { son.ft8.mode }
    ft8Site.cascade = { son.ft8.cascade }
    ft8Site.derniereLigneMs = { son.ft8.derniereLigneMs }
    ft8Site.moi = { station.settings.callsign }
    ft8Site.contexte = { contexteFt8Site(station, sdr) }
    ft8Site.passages = { journal.passages() }
    ft8Site.decodesDe = { e -> journal.ft.duPassage(e) }
    directSite.ft8 = ft8Site
    site.ft8 = ft8Site
    sourcesSite.ft8 = { ref -> ft8Site.contenu(ref) }
    // A pass closed with messages: published by itself when « Passages FT8/FT4 » is Automatique.
    journal.apresPassage = { e ->
        if (site.actif && site.mode(SitePc.Genre.FT8) == SitePc.Mode.AUTO)
            Thread({ runCatching { if (journal.ft.duPassage(e).isNotEmpty()) site.publie(SitePc.Genre.FT8, e.id, false) } }, "satme-site-ft8").apply { isDaemon = true; start() }
    }
    directSite.demarre()
    // « Agenda › Diplômes »: the standing awards, those announced online, the ARISS sessions; the gallery and the log matched.
    val diplomes = DiplomesPc(station, dossier,
        images = {
            val notes = runCatching { classement.etat().groupes.flatMap { g -> g.notes.entries } }.getOrDefault(emptyList()).associate { it.key to it.value }
            son.images().filter { it.source != SstvPc.ESSAI }.map { s ->
                fr.f4ioz.satcombo.domain.Diplomes.Image(s.fileName, s.satName, s.timeMs, s.complete, notes[s.fileName] ?: -1, s.mode, s.traitement.isNotEmpty())
            }
        },
        contacts = { carnet.contacts() },
        activites = { runCatching { activites.liste() }.getOrDefault(emptyList()) },
        infosImage = { optionsSstv.infosImage(it) },
        fictifs = if (ModeDemo.actif) GenerateurDemo::diplomes else { _ -> emptyList() }, enLigneAutorise = !ModeDemo.actif)
    serveur.diplomes = diplomes
    // « Publication web › Diplômes » (off by default): the awards and, on the owner's click, the picture of one received.
    diplomes.site = DiplomesSitePc(File(dossier, "diplomes-site.json"), site)
    directSite.diplomes = { diplomes.pourSite() }
    // « Description du montage »: the Montage page's plan drawing QO-100, the uplink profile, the QO-100 converters
    // (names and characteristics only).
    directSite.montage = {
        serveur.montage?.planPourSite()?.let { plan ->
            val pr = emissionBe.profil
            val montee = fr.f4ioz.satcombo.station.PublicationMontage.Montee(pr.chaine.libelle, pr.antenne.libelle, pr.antenne.montage,
                when (pr.antenne) { fr.f4ioz.satcombo.domain.ProfilMontee.Antenne.PARABOLE_80 -> 80; fr.f4ioz.satcombo.domain.ProfilMontee.Antenne.PARABOLE_35 -> 35; else -> null },
                pr.gainDbi, pr.ampliW, pr.pertesDb, pr.pireMaxDbw)
            val bandes = station.bandes()
            val rx = fr.f4ioz.satcombo.domain.TableConversion.bande(bandes, fr.f4ioz.satcombo.domain.Convertisseur.BALISE_MEDIANE_HZ, fr.f4ioz.satcombo.domain.UsageConversion.SDR)
                ?: fr.f4ioz.satcombo.domain.TableConversion.bande(bandes, fr.f4ioz.satcombo.domain.Convertisseur.BALISE_MEDIANE_HZ, fr.f4ioz.satcombo.domain.UsageConversion.CAT_RX)
            val tx = fr.f4ioz.satcombo.domain.TableConversion.bande(bandes, fr.f4ioz.satcombo.domain.TableConversion.QO100_UL_BAS + 50_000L, fr.f4ioz.satcombo.domain.UsageConversion.CAT_TX)
            val conv = listOfNotNull(rx?.let { "rx" to it }, tx?.let { "tx" to it }).map { (k, b) ->
                fr.f4ioz.satcombo.station.PublicationMontage.Conversion(k, b.olHz, b.inverse, b.fenetreFi().first, b.fenetreFi().last)
            }
            fr.f4ioz.satcombo.station.PublicationMontage.json(plan.nom, plan.chaine, montee, conv)
        }
    }
    portee.launch(kotlinx.coroutines.Dispatchers.IO) {
        kotlinx.coroutines.delay(25_000)
        while (true) { runCatching { diplomes.tic() }; repeat(60) { if (!diplomes.rafraichissementDemande) kotlinx.coroutines.delay(1000) } }
    }
    portee.launch(kotlinx.coroutines.Dispatchers.IO) {
        kotlinx.coroutines.delay(30_000)
        while (true) { runCatching { optionsSstv.tic() }; kotlinx.coroutines.delay(30_000) }
    }
    portee.launch(kotlinx.coroutines.Dispatchers.IO) {
        kotlinx.coroutines.delay(20_000)
        while (true) { runCatching { activites.tic() }; repeat(60) { if (!activites.rafraichissementDemande) kotlinx.coroutines.delay(1000) } }
    }
    // Start-up: « Configuration rapide » on a new install, « Quoi de neuf » after an update, Réglages › Démarrage,
    // the check for a new version (GitHub, once a day, off in Réglages), « Déploiement ».
    val demarrage = DemarragePc(station, lanceur, fichierLanceur,
        MiseAJourPc(station.stockage, File(dossier, "mise-a-jour.json"), versionPc(),
            api = System.getProperty("satme.maj.api")?.takeIf { it.startsWith("http") } ?: MiseAJourPc.API),
        NouveautesPc.duJar(),
        DeploiementPc(dossier, lanceur, { reglages.brutes() }),
        RedemarragePc(args),
        occupe = {
            if (emission.etat.emet || emissionSstv.etat.emet || iq.enCours || emissionPoste.poste.etat.emet || emissionBe.emet)
                "une émission est en cours : l'arrêter d'abord" else null
        },
        motDePasse = { acces.defini },
        exeWindows = ProcessHandle.current().info().command().orElse(null)?.takeIf { it.lowercase().endsWith(".exe") && !it.lowercase().endsWith("java.exe") && !it.lowercase().endsWith("javaw.exe") },
        portable = portable?.takeIf { it.absoluteFile == dossier.absoluteFile && !DonneesMsiPc.installeParMsi() },
        avisDonnees = releveMsi?.message)
    demarrage.auDemarrage(neuf)
    serveur.demarrage = demarrage
    portee.launch(kotlinx.coroutines.Dispatchers.IO) { while (true) { runCatching { demarrage.maj.tic() }; kotlinx.coroutines.delay(10_000) } }
    serveur.demarre()
    // QO-100 received when SatMe-PC stopped: taken back only when chosen in Réglages (reception only).
    Thread({ runCatching { trafic.repriseAuDemarrage() } }, "satme-qo100-reprise").apply { isDaemon = true; start() }
    println("SatMe-PC " + versionPc() + if (ModeDemo.actif) "  — DÉMONSTRATION (données fictives, matériel virtuel)" else "")
    ligneDemo?.let { println(it) }
    println("  page      : http://localhost:${serveur.portReel}/" + if (reseau) "  (ouverte au réseau)" else "")
    if (serveur.portHttpsReel > 0) println("  https     : port ${serveur.portHttpsReel} (certificat de ce PC, empreinte dans Réglages › Accès à distance)")
    else if (serveur.erreurHttps.isNotBlank()) println("  https     : " + serveur.erreurHttps)
    if (reseau) println(ligneAcces ?: if (acces.defini) "  accès à distance : mot de passe demandé aux autres machines"
        else "  accès à distance : aucun mot de passe, les autres machines n'ont rien (Réglages › Accès à distance)")
    println("  réglages  : ${dossier.absolutePath}")
    if (fichierLanceur.fichier.isFile) println("  démarrage : ${fichierLanceur.fichier.path}")
    println("  Ctrl+C pour arrêter")
    // Started again by « Redémarrer SatMe-PC »: the page already open comes back by itself, no new tab.
    if (bureau && a.apres == null) {
        val url = "http://localhost:${serveur.portReel}/"
        BureauPc.icone(url, dossier)
        BureauPc.ouvre(url)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        // The rig's PTT released first, while the CAT still reaches it.
        runCatching { ft8Tx.arrete("SatMe-PC fermé") }
        runCatching { emissionPoste.arreteTout("SatMe-PC fermé") }
        // The Pluto's narrow band: its LO powered down.
        runCatching { emissionBe.arreteTout("SatMe-PC fermé") }
        // The transmitter first: its LO powered down before anything else.
        runCatching { iq.arrete() }
        runCatching { adsb.arrete() }
        runCatching { ais.arrete() }
        runCatching { emission.arrete("SatMe-PC fermé") }
        // No hackrf_transfer outlives SatMe-PC.
        runCatching { fr.f4ioz.satcombo.sdr.HackrfEmission.arreteTout() }
        runCatching { journal.ferme() }
        runCatching { spectreJournal.ferme() }
        // The KISS radio put back as it was.
        runCatching { kiss.deconnecte() }
        runCatching { polarisation.arrete() }
        runCatching { sdr.arrete() }
        // Nor any bladeRF-cli (the receiver's stopped just above, gently).
        runCatching { fr.f4ioz.satcombo.sdr.Bladerf.arreteTout() }
        runCatching { datv.arrete() }
        runCatching { son.arreteEnregistrement(); son.son.arrete() }
        runCatching { station.deconnecteCat() }
        // The IC-9700's one network session handed back, or the owner is locked out a while.
        runCatching { station.icomReseau.ferme() }
        runCatching { station.stationRotor.disconnectRotor() }
        runCatching { lien.arrete() }
        runCatching { rigctld.arrete() }
        runCatching { molette.arrete() }
        runCatching { phonie.arrete() }
        runCatching { parole.arrete() }
        serveur.arrete()
    })
    // The server and the loops run on their own threads; this one waits.
    Thread.currentThread().join()
}

/**
 * What « Phonie → texte » writes beside an over: the sky frequency and the
 * satellite. QO-100 when it is set on the dongle or the rig; the dongle's
 * frequency; the rig's by CAT.
 */
/**
 * Where the rig is for FT8/FT4: on QO-100 (« Appliquer au poste »), on the
 * followed satellite while the Doppler loop drives its uplink (its
 * transponder, inverting or not), else terrestrial (the rig's own frequency).
 */
private fun liaisonFt8(station: StationPc): Ft8EmissionPc.Liaison {
    val e = station.etat.value
    val ce = station.stationCat.etat.value
    if (station.qo100.etat.value.auPoste) return Ft8EmissionPc.Liaison("QO-100", false, ce.monteeHz, ce.descenteHz, false)
    val sat = e.suivi ?: return Ft8EmissionPc.Liaison()
    val t = station.emetteurChoisi()
    if (!e.catConnecte || t == null || ce.monteeHz == null) return Ft8EmissionPc.Liaison()
    return Ft8EmissionPc.Liaison(sat.name, t.uplinkLowHz == null, ce.monteeHz, ce.descenteHz, t.invert,
        t.uplinkLowHz, t.uplinkHighHz ?: t.uplinkLowHz, e.position?.rangeRateKmS ?: 0.0, lineaire = t.isTransponder)
}

private fun contextePhonie(station: StationPc, sdr: SdrPc, source: String): Pair<Long?, String> {
    val q = station.qo100.etat.value
    val e = station.etat.value
    return if (source == "sdr") {
        if (q.aLaCle) q.descenteHz to "QO-100"
        else sdr.etat.value.cielHz.takeIf { it > 0 } to sdr.etat.value.satellite
    } else {
        if (q.auPoste) q.descenteHz to "QO-100"
        else (if (e.catConnecte) station.stationCat.etat.value.descenteHz else null) to (e.suivi?.name ?: "")
    }
}

/**
 * What FT8 / FT4 is heard through, for the site ([Ft8SitePc.Contexte]): QO-100 at the rig, else the satellite followed
 * while its pass is under way (AOS, LOS, highest elevation, the transponder chosen), else nothing but the dial.
 */
internal fun contexteFt8Site(station: StationPc, sdr: SdrPc): Ft8SitePc.Contexte {
    val e = station.etat.value
    val t = System.currentTimeMillis()
    val dial = runCatching { contextePhonie(station, sdr, "son").first }.getOrNull()
    if (station.qo100.etat.value.auPoste) return Ft8SitePc.Contexte("QO-100", fr.f4ioz.satcombo.domain.Qo100.NORAD, true, dialHz = dial)
    val s = e.suivi ?: return Ft8SitePc.Contexte(dialHz = dial)
    val p = e.passage?.takeIf { it.catalogNumber == s.catalogNumber && t in (it.aosEpochMs - 60_000L)..(it.losEpochMs + 60_000L) }
        ?: return Ft8SitePc.Contexte(dialHz = dial)
    return Ft8SitePc.Contexte(s.name, s.catalogNumber, false, p.aosEpochMs, p.losEpochMs, p.maxElevationDeg, dial, station.emetteurChoisi()?.description)
}

/** The satellite an SSTV picture's sound came from, by [source] (see [SstvPc.satelliteDuSon]). */
internal fun satelliteSstv(source: String, son: SonStation, sdr: SdrPc, station: StationPc): String {
    val q = station.qo100.etat.value
    return SstvPc.satelliteDuSon(source == SstvPc.SDR || son.son.etat.value.appareil == SdrPc.NOM_SOURCE,
        q.aLaCle, sdr.etat.value.satellite, q.auPoste, station.etat.value.suivi?.name.orEmpty())
}

/**
 * What the SSTV automatic tuning may move for [source]: the dongle's fine
 * offset when the dongle's sound feeds the decoder (moved between two of its
 * own blocks: coupled); otherwise the rig, through the CAT loop's own writes,
 * only while that loop drives the dial (connected, not on hold, nobody
 * turning it); otherwise nothing, advice only.
 */
internal fun cibleAfcSstv(source: String, son: SonStation, sdr: SdrPc, station: StationPc): fr.f4ioz.satcombo.sstv.CibleAfc {
    val parLaCle = source == SstvPc.SDR || son.son.etat.value.appareil == SdrPc.NOM_SOURCE
    // A channel of « Décodeurs multiples » feeds the picture: its dial is what moves, never the main receiver.
    val canal = sdr.canaux?.takeIf { parLaCle && it.tientSstv() }
    return if (canal != null) object : fr.f4ioz.satcombo.sstv.CibleAfc {
        override fun sens() = 1
        override fun disponible() = sdr.etat.value.actif
        override val couplee = true
        override fun position() = canal.cadranSstv() ?: 0L
        override fun deplace(hz: Int) { canal.decaleSstv(hz) }
    } else if (parLaCle) object : fr.f4ioz.satcombo.sstv.CibleAfc {
        override fun sens() = fr.f4ioz.satcombo.sstv.SstvAfc.sensDuMode(sdr.modeEffectif())
        override fun disponible() = sdr.etat.value.actif
        override val couplee = true
        override fun position() = sdr.decalageHz.toLong()
        override fun deplace(hz: Int) { sdr.decalageHz = sdr.decalageHz + hz }
    } else object : fr.f4ioz.satcombo.sstv.CibleAfc {
        override fun sens() = fr.f4ioz.satcombo.sstv.SstvAfc.sensDuMode(station.emetteurChoisi()?.mode)
        override fun disponible(): Boolean {
            val e = station.etat.value
            return e.catConnecte && !e.maintienDoppler && !station.qo100.etat.value.auPoste && station.stationCat.etat.value.rxPilote
        }
        override val couplee = false
        override fun position() = station.afcSstvHz
        override fun deplace(hz: Int) { station.afcSstvHz = station.afcSstvHz + hz }
    }
}

/**
 * What the USB knob tunes, through the page's own paths: the satellite's
 * rest and shifts (the CAT loop writes them; nothing keys the rig), the
 * dongle's fine offset, QO-100's downlink.
 */
internal fun mondeMolette(station: StationPc, sdr: SdrPc) = object : MoletteUsbPc.Monde {
    override fun contexte(page: String?): fr.f4ioz.satcombo.domain.MoletteUsb.Contexte {
        val q = station.qo100.etat.value
        return fr.f4ioz.satcombo.domain.MoletteUsb.Contexte(page, station.etat.value.catConnecte, sdr.etat.value.actif, q.auPoste, q.aLaCle)
    }
    override fun vfo(dHz: Long) {
        // The rig on CAT and nothing driving it (terrestrial SSB, Doppler on hold, the satellite down): its own dial.
        if (station.accordeDirect(dHz)) return
        // As on the phone: off a transponder there is no uplink to follow, the RX shift is the control.
        if (station.emetteurChoisi()?.isTransponder == true) station.deplaceRepos(dHz) else shiftRx(dHz)
    }
    override fun shiftRx(dHz: Long) = station.reglageSat(calibHz = station.etat.value.calibHz + dHz)
    override fun shiftTx(dHz: Long) = station.reglageSat(txHz = station.etat.value.txShiftHz + dHz)
    override fun zeroRx() = station.reglageSat(calibHz = 0L)
    override fun zeroTx() = station.reglageSat(txHz = 0L)
    override fun sdr(dHz: Long) { sdr.decalageHz = sdr.decalageHz + dHz.toInt() }
    override fun qo100(dHz: Long) = station.qo100.pas(dHz)
}

/** What the spoken announcements compare from one second to the next: the states the tabs show. */
internal fun instantaneAnnonces(station: StationPc, son: SonStation, sdr: SdrPc, qo100: Qo100Pc, datv: DatvPc, textesDatv: TextesDatvPc,
                                adsb: AdsbPc, cw: AnnoncesPc.SuiviCw, avions: Boolean, emissions: Map<String, Long?>): fr.f4ioz.satcombo.annonces.DetecteurAnnonces.Instantane {
    val t = System.currentTimeMillis()
    val e = station.etat.value
    val moi = station.settings.callsign
    val passage = e.suivi?.let { s -> e.passage?.let { p -> fr.f4ioz.satcombo.annonces.DetecteurAnnonces.Passage(s.name, p.aosEpochMs, p.losEpochMs, Math.round(p.maxElevationDeg).toInt()) } }
    val ft8 = son.ft8.entendus.takeLast(60).filter { it.appele != null && it.appelant != null }.map {
        fr.f4ioz.satcombo.annonces.DetecteurAnnonces.Decode(it.mode + ":" + it.tranche + ":" + it.texte, it.appelant!!, it.appele!!, it.mode, it.rapportDb)
    }
    val d = datv.etat
    val lu = textesDatv.confirmes().filter { t - it.dernierMs < 30_000L }.maxByOrNull { it.dernierMs }?.indicatif.orEmpty()
    val r = station.stationRotor.etat.value
    val s = sdr.etat.value
    val fenetres = if (!avions || !adsb.actif) emptyMap() else adsb.reglageDiffusion()?.let { rg ->
        fr.f4ioz.satcombo.adsb.DiffusionAvions.fenetres(rg, adsb.table.liste(), t).filter { it.ouverte }
            .associate { f -> f.indicatif.ifBlank { f.icao } to (if (f.finConnue) Math.max(1, ((f.finMs - t) / 60_000L).toInt()) else null) }
    } ?: emptyMap()
    return fr.f4ioz.satcombo.annonces.DetecteurAnnonces.Instantane(
        ms = t, moi = moi, passage = passage, emissions = emissions, leila = qo100.leilaEntendue(),
        sstvGardees = son.sstv.etat.value.gardees, sstvMode = son.sstv.etat.value.mode,
        decodes = ft8 + cw.nouveaux(son.cw.texte(), moi),
        datvActif = d.actif, datvVerrou = d.verrouille, datvHz = datv.reglage.cielKhz.takeIf { it > 0 }?.let { it * 1000L },
        datvMerDb = d.merDb?.let { Math.round(it).toInt() }, datvIndicatif = lu, avions = fenetres,
        rotorConnecte = r.rotorConnected, rotorButee = r.rotorConnected && r.rotorOutOfRange, rotorStatut = r.rotorStatus,
        // A recording come to its end, the generator stopped: not a receiver lost.
        sdrActif = s.actif, sdrErreur = s.erreur.takeUnless { it.startsWith("lecture terminée") || it.startsWith("générateur") }.orEmpty(),
        molettePasHz = station.settings.molettePasHz.takeIf { station.settings.moletteVfo })
}

/**
 * A portable SatMe-PC (on a USB stick): a `donnees` folder beside the program (next to `bin` and `lib`, or to
 * SatMe.exe) holds its settings and files, nothing written elsewhere on the PC. [jar]: where this program runs from.
 * Never in an MSI install: an update empties its folder ([DonneesMsiPc]).
 */
fun dossierPortable(jar: File? = DonneesMsiPc.jar()): File? {
    val ici = jar?.parentFile ?: return null
    if (DonneesMsiPc.installeParMsi(jar)) return null
    val racine = if (ici.name == "lib" || ici.name == "app") ici.parentFile ?: return null else ici
    return File(racine, DonneesMsiPc.PORTABLE).takeIf { it.isDirectory }
}

/** The portable folder when there is one, else ~/.satme on Linux and macOS, %APPDATA%\SatMe on Windows. */
fun dossierParDefaut(portable: File? = dossierPortable()): File {
    portable?.let { return it }
    val appdata = System.getenv("APPDATA")
    return if (System.getProperty("os.name").orEmpty().startsWith("Windows") && !appdata.isNullOrBlank())
        File(appdata, "SatMe") else File(System.getProperty("user.home"), ".satme")
}

/**
 * SatMe-Node: the MiniTiouner only, driven over the network — nothing else
 * loaded (no elements, no passes), so that a Raspberry Pi 3 does it.
 */
private fun mainNoeud(port: Int, dossier: File) {
    val stockage = StockageFichier(File(dossier, "reglages.properties"))
    val datv = DatvPc(stockage, File(dossier, "enregistrements"), lecteursLocaux = false)
    // Each board one role at a time: DATV reception, the SDR lent to SatMe-PC, the transmitter.
    var sdr: SdrNoeudPc? = null
    var tx: EmissionNoeudPc? = null
    fun datvTient(a: String) = if (datv.etat.actif && datv.recepteur == a) SdrNoeudPc.nomAppareil(a) + " reçoit la DATV sur le node : arrêter cette réception d'abord" else null
    val materiel = MaterielNoeud(stockage) { a ->
        if (sdr?.tient == a) SdrNoeudPc.nomAppareil(a) + " sert de SDR à SatMe-PC : l'arrêter d'abord"
        else if (tx?.tient == a) SdrNoeudPc.nomAppareil(a) + " émet" else null
    }
    datv.materiel = materiel
    val sdrN = SdrNoeudPc(stockage, ouvre = { materiel.ouvreLibre(it) }, occupe = { a -> datvTient(a) ?: if (tx?.tient == a) SdrNoeudPc.nomAppareil(a) + " émet" else null })
    val txN = EmissionNoeudPc(stockage, EmissionNoeudPc.reels(stockage),
        occupe = { a -> datvTient(a) ?: if (sdr?.tient == a) SdrNoeudPc.nomAppareil(a) + " sert de SDR à SatMe-PC : l'arrêter d'abord" else null })
    sdr = sdrN; tx = txN
    JournalNoeud.capture()
    val n = NoeudPc(datv, stockage, port, sdr = sdrN, tx = txN, sante = SanteNoeudPc(datv, sdrN, txN), synchronise = { stockage.synchronise() })
    n.demarre()
    println("SatMe-Node ${versionPc()} « ${n.nom} » : port ${n.portReel}")
    println("  LongMynd   : " + (datv.programme() ?: "INTROUVABLE (le compiler, ou --dossier/chemin)"))
    println("  SDR prêté  : " + SdrNoeudPc.nomAppareil(sdrN.appareil) + " · émetteur : " + SdrNoeudPc.nomAppareil(txN.appareil) +
        (if (txN.autorisee) " (émission AUTORISÉE)" else " (émission non autorisée)"))
    println("  Code à donner à SatMe-PC ou au téléphone : ${n.code}")
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { txN.arrete("SatMe-Node arrêté") }; runCatching { datv.arrete() }; n.arrete() })
    while (true) Thread.sleep(3_600_000L)
}

/** SatMe-PC's version, from its jar (a release's date), "dev" when built by hand. */
fun versionPc(): String = Main::class.java.`package`?.implementationVersion ?: "dev"

private object Main

/**
 * The station for the site's live page ([DirectSitePc.Station]): the satellite followed — or QO-100 while the station
 * works it (the SDR or the DATV receiver on it, the QO-100 tab at the rig) and the satellite followed is below the
 * horizon —, the mast as it reads back, the fixed dish's pointing for QO-100, the receivers and the rig in use, the
 * mode. Names, angles and frequencies only.
 */
/** The mast's last reading, to tell it moving between two looks ([stationSite], every few seconds). */
internal object MouvementRotor {
    private var az: Double? = null
    private var el: Double? = null
    private var ms = 0L
    /** [a], [e] read at [t]: did it move by half a degree or more since the reading of up to 12 s before? */
    @Synchronized fun bouge(a: Double?, e: Double?, t: Long): Boolean {
        val avant = if (t - ms <= 12_000L) az to el else null to null
        az = a; el = e; ms = t
        val da = if (a != null && avant.first != null) Math.abs(((a - avant.first!!) % 360.0 + 540.0) % 360.0 - 180.0) else 0.0
        val de = if (e != null && avant.second != null) Math.abs(e - avant.second!!) else 0.0
        return da >= 0.5 || de >= 0.5
    }
}

internal fun stationSite(station: StationPc, sdr: SdrPc, datv: DatvPc, son: SonStation, enEmission: Boolean, passageSstv: () -> Boolean): DirectSitePc.Station {
    val Q = fr.f4ioz.satcombo.domain.Qo100
    val e = station.etat.value
    val t = System.currentTimeMillis()
    val suivi = e.suivi
    val pos = e.position
    val datvQo = datv.etat.actif && fr.f4ioz.satcombo.datv.IndicatifsImage.qo100(datv.reglage.cielKhz)
    val surQo = suivi?.catalogNumber == Q.NORAD || (sdr.etat.value.actif && sdr.surQo100) || datvQo || station.qo100.etat.value.auPoste
    val pointage = e.observateur?.let { Q.pointage(it.latDeg, it.lonDeg) }
    fun emetteurs(l: List<fr.f4ioz.satcombo.data.Transmitter>) = l.filter { it.alive }.take(6).map {
        DirectSitePc.Emetteur(it.description, it.mode, it.downlinkLowHz, it.uplinkLowHz) }
    val sat = if (surQo && (suivi == null || suivi.catalogNumber == Q.NORAD || (pos?.elevationDeg ?: -90.0) < 0.0)) {
        val p = pos?.takeIf { suivi?.catalogNumber == Q.NORAD }
        DirectSitePc.Sat("QO-100", Q.NORAD, true, p?.azimuthDeg ?: pointage?.azDeg, p?.elevationDeg ?: pointage?.elDeg, p?.rangeKm,
            emetteurs = emetteurs(if (suivi?.catalogNumber == Q.NORAD) e.emetteurs else station.emetteursDe(Q.NORAD)),
            choisi = if (suivi?.catalogNumber == Q.NORAD) station.emetteurChoisi()?.description else null)
    } else if (suivi != null) {
        val pas = e.passage?.takeIf { it.losEpochMs > t && it.catalogNumber == suivi.catalogNumber }
            ?: e.passagesSuivi.firstOrNull { it.losEpochMs > t && it.catalogNumber == suivi.catalogNumber }
        DirectSitePc.Sat(suivi.name, suivi.catalogNumber, false, pos?.azimuthDeg, pos?.elevationDeg, pos?.rangeKm, pos?.rangeRateKmS,
            pas?.aosEpochMs, pas?.losEpochMs, pas?.maxElevationDeg, emetteurs(e.emetteurs), station.emetteurChoisi()?.description)
    } else null
    val r = station.stationRotor.etat.value
    val rotor = if (!r.rotorEnabled) null else {
        val az = r.rotorAimAz ?: r.rotorActualAz
        val el = if (r.rotorAzOnly) null else r.rotorAimEl ?: r.rotorActualEl
        val ecartAz = if (az != null && r.rotorTargetAz != null) Math.abs(((r.rotorTargetAz!! - az) % 360.0 + 540.0) % 360.0 - 180.0) else 0.0
        val ecartEl = if (el != null && r.rotorTargetEl != null) Math.abs(r.rotorTargetEl!! - el) else 0.0
        val seuil = maxOf(2.0, r.rotorDeadband.toDouble())
        // Moving: far from where it is sent, or read somewhere else than a moment ago.
        val bouge = MouvementRotor.bouge(az, el, t)
        DirectSitePc.Rotor(r.rotorConnected, az, el, r.rotorTargetAz, if (r.rotorAzOnly) null else r.rotorTargetEl,
            r.rotorConnected && (ecartAz > seuil || ecartEl > seuil || bouge), r.rotorOutOfRange, r.rotorAzOnly)
    }
    val parabole = if (sat?.geo == true && pointage != null && pointage.visible) pointage.azDeg to pointage.elDeg else null
    val es = sdr.etat.value
    val sdrC = DirectSitePc.Sdr(es.actif, DirectSitePc.nomSdr(sdr.recepteur), es.cielHz.takeIf { it > 0 })
    val de = datv.etat
    val datvC = DirectSitePc.DatvCourt(de.actif, de.verrouille, de.merDb, runCatching { datv.marge() }.getOrNull(), de.service, datv.reglage.cielKhz)
    val cat = station.stationCat.etat.value
    val poste = if (!e.catConnecte) null else DirectSitePc.Poste(DirectSitePc.nomPoste(e.catModele), true, cat.descenteHz, cat.monteeHz, cat.mode)
    val modePoste = cat.mode?.uppercase().orEmpty()
    val mode = when {
        de.actif -> "DATV"
        son.ft8.enMarche -> son.ft8.mode
        son.sstv.etat.value.mode.isNotEmpty() || passageSstv() -> "SSTV"
        !e.catConnecte -> null
        modePoste.startsWith("CW") -> "CW"
        cat.donnees == true || modePoste.contains("DATA") || modePoste.endsWith("-D") -> "numérique"
        modePoste.startsWith("FM") || modePoste.startsWith("USB") || modePoste.startsWith("LSB") || modePoste.startsWith("AM") -> "phonie"
        else -> null
    }
    return DirectSitePc.Station(sat, rotor, parabole, sdrC, datvC, poste, enEmission, mode)
}
