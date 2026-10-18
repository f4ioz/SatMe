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
    var noeud = false
    // Started from the desktop (Windows): the browser opened, an icon by the clock.
    var bureau = OutilsPc.windows
    var dossier = dossierParDefaut()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: port
            "--reseau" -> reseau = true
            "--noeud" -> noeud = true
            "--bureau" -> bureau = true
            "--console" -> bureau = false
            "--dossier" -> dossier = args.getOrNull(++i)?.let(::File) ?: dossier
            "-v", "--version" -> { println("SatMe-PC " + versionPc()); return }
            "-h", "--help", "--aide" -> {
                println("SatMe-PC " + versionPc())
                println("satme [--port 8090] [--reseau] [--noeud] [--dossier DIR] [--bureau|--console] [--version]")
                println("  --port     port de la page (8090)")
                println("  --reseau   page ouverte aux autres machines du réseau (sinon ce PC seulement)")
                println("  --noeud    SatMe-Node : seulement le MiniTiouner, piloté par le réseau (Raspberry Pi près de l'antenne)")
                println("  --dossier  où ranger réglages et éléments (${dossierParDefaut()})")
                println("  --bureau   ouvrir la page dans le navigateur, icône près de l'horloge (d'origine sous Windows)")
                return
            }
        }
        i++
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
    val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val station = StationPc(StockageFichier(File(dossier, "reglages.properties")),
        Catalogue(File(dossier, "cache")), PortsSeriePc(), portee,
        stockageSat = StockageFichier(File(dossier, "satellites.properties")),
        stockageAgenda = StockageFichier(File(dossier, "agenda.properties")))
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
    // The sound card used last time, started again: listening opens nothing on the rig.
    son.carte.takeIf { it.isNotBlank() }?.let { son.son.demarre(it) }
    val journal = JournalPc(station, carnet, son, File(dossier, "journal"))
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
    // QO-100 with the dongle: the beacon followed, the calibration held on it when chosen.
    val qo100 = Qo100Pc(station, sdr)
    son.son.auditeurs += { pcm, n, rate -> qo100.ecouteCarte(pcm, n, rate) }
    // QO-100 from one screen; the log on QO-100 while the receiver or the rig is on it.
    val trafic = TraficQo100Pc(station, sdr, qo100)
    carnet.contexte = { trafic.contexte() }
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
    phonie.demarre()
    portee.launch {
        while (true) {
            runCatching { son.tic() }
            runCatching { journal.tic() }
            runCatching { carnet.ticEnvoi() }
            runCatching { carnet.ticAmsat() }
            runCatching { kiss.tic() }
            runCatching { sdr.ticMeteor() }
            runCatching { qo100.tic() }
            runCatching { trafic.tic() }
            kotlinx.coroutines.delay(1000)
        }
    }
    // The sound heard here, to the browsers listening (the dongle; the sound card).
    val diffusion = DiffusionSon()
    sdr.diffusion = diffusion
    son.son.auditeurs += { pcm, n, rate -> diffusion.ajoute("carte", pcm, n, rate) }
    val journalWeb = JournalWebPc(journal, station, dossier)
    // A sked as a video, on the journal's map tiles.
    val videoSked = VideoSked(File(dossier, "exports")) { z, x, y -> journalWeb.tuile(z, x, y)?.let { f -> runCatching { javax.imageio.ImageIO.read(f) }.getOrNull() } }
    // SatMe on the phone: its passes, their contacts and pictures, to the PC.
    val telephone = TelephonePc(carnet, journal, File(dossier, "enregistrements"), File(dossier, "sstv"), File(dossier, "tmp"))
    // The phone joining by a QR code: a door of its own next to the page's port, open only while the code is shown.
    val appairage = AppairagePc(telephone, port + 1)
    // DATV with a MiniTiouner, through LongMynd: nothing started until asked.
    val datv = DatvPc(station.stockage, File(dossier, "enregistrements"))
    // Its sound subtitled, the callsigns written in its picture: each off unless chosen in the DATV tab.
    val textesDatv = TextesDatvPc(station.stockage, TextesDatvPc.Source.de(datv), phonie,
        carnet = { carnet.contacts().map { it.callsign } }, moi = { station.settings.callsign })
    datv.ecouteSon = { pcm, n, rate -> phonie.ecouteDatv(pcm, n, rate) }
    phonie.contexte = { src ->
        if (src != "datv") contextePhonie(station, sdr, src)
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
            q.descenteHz.takeIf { q.auPoste && q.transpondeur == fr.f4ioz.satcombo.domain.Qo100.NB.cle && fr.f4ioz.satcombo.domain.GardeQo100.proposable(it) } } })
    // The transmitters' I/Q correction (Réglages › Émission): kept per board, used by DATV and SSTV; calibrated indoors only.
    val corrections = fr.f4ioz.satcombo.sdr.CorrectionsIq(station.stockage)
    emission.corrections = corrections
    var sstvRef: EmissionSstvPc? = null
    val iq = CalibrationIqPc(station.stockage, corrections,
        MaterielIqPc(sdr, emission, { sstvRef }, datv, { trafic.reprend() }, { station.settings.qo100EnCours }))
    val iqEnCours = "une correction I/Q est en cours (Réglages › Émission) : l'arrêter d'abord"
    // SSTV on the air with the Pluto or the bladeRF: as DATV, nothing sent until asked and confirmed on the page.
    val planche = PlancheWebPc(station, son, dossier)
    val emissionSstv = EmissionSstvPc(station.stockage, indicatif = { station.settings.callsign }, locator = { station.settings.manualLocator },
        plutoHote = { sdr.plutoHote }, plutoPort = { sdr.plutoPort },
        plutoOccupe = { if (sdr.plutoEnReception) "l'ADALM-PLUTO reçoit (onglet SDR) : arrêter cette réception d'abord"
            else if (datv.sdrTenu == fr.f4ioz.satcombo.datv.Datv.PLUTO) "l'ADALM-PLUTO reçoit la DATV : arrêter cette réception d'abord" else null },
        bladerfOccupe = { if (sdr.bladerfEnReception) "le bladeRF reçoit (onglet SDR) : arrêter cette réception d'abord"
            else if (datv.sdrTenu == fr.f4ioz.satcombo.datv.Datv.BLADERF) "le bladeRF reçoit la DATV : arrêter cette réception d'abord" else null },
        bladerfProgramme = { sdr.programmeBladerf() },
        autreEmission = { if (emission.etat.emet) "une émission DATV est en cours : l'arrêter d'abord" else if (iq.enCours) iqEnCours else null },
        gainMaxDb = { emission.gainMaxDb },
        planche = { planche.image(it) }, planches = { planche.modeles() },
        signauxQo100 = { qo100.signaux.recents() })
    sstvRef = emissionSstv
    emissionSstv.corrections = corrections
    emission.autreEmission = { if (emissionSstv.etat.emet) "une émission SSTV est en cours : l'arrêter d'abord" else if (iq.enCours) iqEnCours else null }
    // DATV received with the Pluto or the bladeRF: the SDR tab's board lent, given back afterwards; never while it transmits.
    datv.materiel = MaterielSdrPc(sdr, { trafic.reprend() }, { station.settings.qo100EnCours }) { r ->
        val qui = if (r == fr.f4ioz.satcombo.datv.Datv.PLUTO) fr.f4ioz.satcombo.datv.Emetteur.PLUTO else fr.f4ioz.satcombo.datv.Emetteur.BLADERF
        if (iq.enCours) iqEnCours
        else if ((emission.etat.emet && emission.etat.emetteur == qui) || emissionSstv.emetAvec(qui))
            (if (qui == fr.f4ioz.satcombo.datv.Emetteur.PLUTO) "l'ADALM-PLUTO" else "le bladeRF") + " émet : arrêter l'émission d'abord" else null
    }
    sdr.emissionPluto = { emission.etat.emet || emissionSstv.emetAvec(fr.f4ioz.satcombo.datv.Emetteur.PLUTO) || iq.tient(fr.f4ioz.satcombo.datv.Emetteur.PLUTO) }
    sdr.emissionBladerf = { (emission.etat.emet && emission.etat.emetteur == fr.f4ioz.satcombo.datv.Emetteur.BLADERF) ||
        emissionSstv.emetAvec(fr.f4ioz.satcombo.datv.Emetteur.BLADERF) || iq.tient(fr.f4ioz.satcombo.datv.Emetteur.BLADERF) }
    // Text and the mark over the transmitted picture; its pictures and videos chosen from the page.
    emission.incrustation = IncrustationDatvPc(station.stockage)
    val fichiersDatv = FichiersDatvPc(File(dossier, "datv-sources"), File(dossier, "tmp/vignettes"))
    portee.launch { while (true) { runCatching { emission.tic() }; runCatching { emissionSstv.tic() }; kotlinx.coroutines.delay(1000) } }
    val serveur = ServeurPc(station, port, reseau, carnet, son, journalWeb, ImportPc(station, carnet, journal), sdr, videoSked, telephone, appairage, datv,
        planche, kiss, sauvegarde, MirePc(File(dossier, "exports")), diffusion, qo100, lien, phonie, emission, trafic)
    serveur.emissionSstv = emissionSstv
    serveur.iq = iq
    serveur.textesDatv = textesDatv
    serveur.fichiersDatv = fichiersDatv
    serveur.qrzDatv = QrzDatvPc(dossier, { station.settings.qrzUser.isNotBlank() && station.settings.qrzMdp.isNotBlank() },
        { c -> kotlinx.coroutines.runBlocking { carnet.ficheQrz(c) } })
    serveur.large = large
    serveur.montage = MontagePc(dossier, { serveur.lecturesMontage() }, { serveur.fonctionsMontage.instantane(it) }, serveur.fonctionsMontage)
    serveur.memoiresSdr = MemoiresSdrPc(dossier)
    // Open to the network: a password for every other machine (drawn the first time, said in the log).
    val acces = AccesDistantPc(dossier, reseau)
    val ligneAcces = runCatching { acces.initialise() }.getOrNull()
    serveur.acces = acces
    datv.diffusion = diffusion
    // The wide band chat, tune requests by UDP: each off unless chosen in the DATV tab.
    serveur.chatLarge = ChatLargePc(station.stockage, { station.settings.callsign }).also { it.demarre() }
    serveur.accordUdp = AccordUdpPc(station.stockage, reseau, { datv.reglage },
        { r -> large.veilleArrete("arrêtée : accord reçu par UDP"); datv.demarre(r) }, { datv.etat.actif }).also { it.relance() }
    // The station's frequency for logging programs (rigctld protocol): off unless chosen in Réglages › Interfaces.
    val rigctld = RigctldPc(station, sdr, reseau) { emission.etat.emet || emissionSstv.etat.emet || iq.enCours }
    rigctld.demarreSiActif()
    serveur.rigctld = rigctld
    serveur.demarre()
    // QO-100 received when SatMe-PC stopped: taken back only when chosen in Réglages (reception only).
    Thread({ runCatching { trafic.repriseAuDemarrage() } }, "satme-qo100-reprise").apply { isDaemon = true; start() }
    println("SatMe-PC " + versionPc())
    println("  page      : http://localhost:${serveur.portReel}/" + if (reseau) "  (ouverte au réseau)" else "")
    if (reseau) println(ligneAcces ?: if (acces.defini) "  accès à distance : mot de passe demandé aux autres machines"
        else "  accès à distance : aucun mot de passe, les autres machines n'ont rien (Réglages › Accès à distance)")
    println("  réglages  : ${dossier.absolutePath}")
    println("  Ctrl+C pour arrêter")
    if (bureau) {
        val url = "http://localhost:${serveur.portReel}/"
        BureauPc.icone(url, dossier)
        BureauPc.ouvre(url)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        // The transmitter first: its LO powered down before anything else.
        runCatching { iq.arrete() }
        runCatching { emission.arrete("SatMe-PC fermé") }
        // No hackrf_transfer outlives SatMe-PC.
        runCatching { fr.f4ioz.satcombo.sdr.HackrfEmission.arreteTout() }
        runCatching { journal.ferme() }
        // The KISS radio put back as it was.
        runCatching { kiss.deconnecte() }
        runCatching { sdr.arrete() }
        // Nor any bladeRF-cli (the receiver's stopped just above, gently).
        runCatching { fr.f4ioz.satcombo.sdr.Bladerf.arreteTout() }
        runCatching { datv.arrete() }
        runCatching { son.arreteEnregistrement(); son.son.arrete() }
        runCatching { station.deconnecteCat() }
        runCatching { station.stationRotor.disconnectRotor() }
        runCatching { lien.arrete() }
        runCatching { rigctld.arrete() }
        runCatching { phonie.arrete() }
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
 * What the SSTV automatic tuning may move for [source]: the dongle's fine
 * offset when the dongle's sound feeds the decoder (moved between two of its
 * own blocks: coupled); otherwise the rig, through the CAT loop's own writes,
 * only while that loop drives the dial (connected, not on hold, nobody
 * turning it); otherwise nothing, advice only.
 */
internal fun cibleAfcSstv(source: String, son: SonStation, sdr: SdrPc, station: StationPc): fr.f4ioz.satcombo.sstv.CibleAfc {
    val parLaCle = source == SstvPc.SDR || son.son.etat.value.appareil == SdrPc.NOM_SOURCE
    return if (parLaCle) object : fr.f4ioz.satcombo.sstv.CibleAfc {
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

/** ~/.satme on Linux and macOS, %APPDATA%\SatMe on Windows. */
fun dossierParDefaut(): File {
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
    datv.materiel = MaterielNoeud(stockage)
    val n = NoeudPc(datv, stockage, port)
    n.demarre()
    println("SatMe-Node ${versionPc()} « ${n.nom} » : port ${n.portReel}")
    println("  LongMynd   : " + (datv.programme() ?: "INTROUVABLE (le compiler, ou --dossier/chemin)"))
    println("  Code à donner à SatMe-PC ou au téléphone : ${n.code}")
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { datv.arrete() }; n.arrete() })
    while (true) Thread.sleep(3_600_000L)
}

/** SatMe-PC's version, from its jar (a release's date), "dev" when built by hand. */
fun versionPc(): String = Main::class.java.`package`?.implementationVersion ?: "dev"

private object Main
