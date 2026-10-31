/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.DecodeursMultiples
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.telemesure.Ao40Fec
import fr.f4ioz.satcombo.telemesure.BlocP3
import fr.f4ioz.satcombo.telemesure.DecodeurTelemesure
import fr.f4ioz.satcombo.telemesure.DecodeurTelemesure.Profil
import fr.f4ioz.satcombo.telemesure.RecepteurGenesis
import fr.f4ioz.satcombo.telemesure.RecepteurPaquets
import fr.f4ioz.satcombo.telemesure.Sids
import fr.f4ioz.satcombo.telemesure.SignalEssai
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BPSK telemetry on SatMe-PC (receive only):
 *
 * - **the QO-100 middle beacon**: a « Balise BPSK » channel of the
 *   « Décodeurs multiples » ([CanauxPc]) decodes it; the cockpit's
 *   « Balise » panel shows its frames, quality and errors, and adds that
 *   channel in one click;
 * - **FUNcube-like satellites at 1200 bit/s** on a LEO pass: the main SDR
 *   receiver's audio (USB, Doppler followed by the tracking), off by default;
 * - **a test mode**: a made-up signal (coded per the published format,
 *   with noise and an offset) through the same chain, never kept.
 *
 * Frames received are written locally, one JSON line each, in
 * `telemesure/` (nothing is sent anywhere).
 */
class TelemesurePc(
    private val stockage: Stockage,
    private val dossier: File,
    private val canaux: CanauxPc?,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Where SiDS frames go: SatNOGS DB, or a test server (SATME_SIDS_URL, the tests). */
    private val urlSids: String = System.getenv("SATME_SIDS_URL")?.takeIf { it.startsWith("http") } ?: Sids.URL_DB,
) {
    /** The tracked satellite's name, for the LEO frames' log. */
    @Volatile var satellite: () -> String = { "" }

    /** The main receiver's 1200 bit/s decoder: off by default. */
    var leoActif: Boolean
        get() = stockage.getBoolean(CLE_LEO, false)
        set(v) { stockage.edit().putBoolean(CLE_LEO, v).apply(); if (!v) leo = null }

    @Volatile private var leo: DecodeurTelemesure? = null
    @Volatile private var essai: DecodeurTelemesure? = null
    @Volatile private var essaiEnCours = false

    init {
        canaux?.auTrame = { t -> garde(t, Profil.QO100, "QO-100") }
    }

    // ---------------------------------------------------------------- packets 1200–9600 bit/s, SSDV, SiDS

    /** The packet decoder on the main SDR's raw I/Q (off by default); its frames and pictures under `telemesure/`. */
    val paquets = RecepteurPaquets(dossier, horloge)
    /** The test mode's own decoder: a made-up signal, nothing written nor shared. */
    private val essaiPaquets = RecepteurPaquets(null, horloge).also { it.contexte = { RecepteurPaquets.Contexte("Essai", essai = true) } }
    @Volatile private var essaiPaquetsEnCours = false

    /** What the frames are stamped with (the followed satellite, the frequencies); [essai]: the generator or a recording. */
    @Volatile var contextePaquets: (essai: Boolean) -> RecepteurPaquets.Contexte = { RecepteurPaquets.Contexte(essai = it) }
    /** The followed satellite's packet transmitter (SatNOGS DB): rate, description, mode; null: none known. */
    @Volatile var emetteurPaquets: () -> Triple<Int, String, String>? = { null }
    /** The station's callsign and position, for SiDS. */
    @Volatile var station: () -> Triple<String, Double, Double>? = { null }

    var paquetsActif: Boolean
        get() = stockage.getBoolean(CLE_PAQUETS, false)
        set(v) { stockage.edit().putBoolean(CLE_PAQUETS, v).apply(); if (!v) paquets.arrete() }
    /** 0: from the transmitter (SatNOGS DB), else 1200 … 9600. */
    var debitChoisi: Int
        get() = stockage.getInt(CLE_DEBIT, 0)
        set(v) { stockage.edit().putInt(CLE_DEBIT, v).apply() }
    var kiss: Boolean
        get() = stockage.getBoolean(CLE_KISS, false)
        set(v) { stockage.edit().putBoolean(CLE_KISS, v).apply(); paquets.kiss = v }
    /** Sharing with SatNOGS DB (SiDS): off unless ticked. */
    var sidsActif: Boolean
        get() = stockage.getBoolean(CLE_SIDS, false)
        set(v) { stockage.edit().putBoolean(CLE_SIDS, v).apply() }

    val sids = Sids(urlSids, horloge = horloge)
    @Volatile private var fictif = false

    init {
        paquets.kiss = kiss
        paquets.contexte = { contextePaquets(fictif) }
        paquets.surTrame = { t -> partage(t) }
        Thread({
            while (true) {
                runCatching { Thread.sleep(5_000) }
                if (sidsActif && sids.enAttente > 0) runCatching { sids.vide() }
            }
        }, "satme-sids").apply { isDaemon = true; start() }
    }

    /** A frame received for real: queued for SatNOGS DB when the operator chose so. */
    private fun partage(t: RecepteurPaquets.TramePaquet) {
        if (t.essai || !sidsActif || t.norad <= 0 || ModeDemo.actif) return
        val (indicatif, lat, lon) = station() ?: return
        if (indicatif.isBlank()) return
        sids.ajoute(fr.f4ioz.satcombo.telemesure.Sids.Envoi(t.norad, indicatif, t.t, t.hex(), lat, lon))
    }

    // ---------------------------------------------------------------- SSDV in USB: GENESIS FSK (SO-127)

    /**
     * Narrow FSK heard in USB (SO-127 / HADES-SA, « GENESIS FSK »): the
     * audio of the rig's card (IC-9700 in USB-D) or of the SDR receiver,
     * its SSDV packets into the same pictures as the packet decoder's.
     */
    val genesis = RecepteurGenesis(dossier, paquets.assemblage, horloge)
    private val essaiGenesis = RecepteurGenesis(null, essaiPaquets.assemblage, horloge)
        .also { it.contexte = { RecepteurGenesis.Contexte("Essai", essai = true) } }
    @Volatile private var essaiGenesisEnCours = false

    /** The followed satellite's GENESIS-like transmitter (SatNOGS DB): its profile and description; null: none. */
    @Volatile var emetteurGenesis: () -> Pair<RecepteurGenesis.Profil, String>? = { null }
    /** The satellite followed (name, number), for the pictures. */
    @Volatile var suiviGenesis: () -> Pair<String, Int>? = { null }
    /** The card's sound comes from the SDR's test generator or a replayed recording. */
    @Volatile var sonFictif: () -> Boolean = { false }
    /** A recording of the « Son » tab by its name, to be read again. */
    @Volatile var enregistrement: (String) -> File? = { null }
    /** The Son tab's recordings, newest first (names). */
    @Volatile var enregistrements: () -> List<String> = { emptyList() }

    var genesisActif: Boolean
        get() = stockage.getBoolean(CLE_GENESIS, false)
        set(v) { stockage.edit().putBoolean(CLE_GENESIS, v).apply() }
    /** « carte »: the sound card (as the Son tab runs it, the rig's in USB-D); « sdr »: the SDR receiver's audio. */
    var genesisSource: String
        get() = stockage.getString(CLE_GENESIS_SOURCE, SOURCE_CARTE)?.takeIf { it == SOURCE_SDR } ?: SOURCE_CARTE
        set(v) { stockage.edit().putString(CLE_GENESIS_SOURCE, if (v == SOURCE_SDR) SOURCE_SDR else SOURCE_CARTE).apply() }
    /** "" from the transmitter, else a profile's key (800, 200). */
    var genesisProfilChoisi: String
        get() = stockage.getString(CLE_GENESIS_PROFIL, "") ?: ""
        set(v) { stockage.edit().putString(CLE_GENESIS_PROFIL, if (RecepteurGenesis.Profil.de(v) != null) v else "").apply() }
    /** The demodulated bits kept per pass (telemesure/genesis). */
    var genesisCapture: Boolean
        get() = stockage.getBoolean(CLE_GENESIS_CAPTURE, false)
        set(v) { stockage.edit().putBoolean(CLE_GENESIS_CAPTURE, v).apply(); genesis.capture = v }
    /** Turned on for a pass by the auto-pilot (an SSDV campaign), whatever the option. */
    @Volatile var genesisPourPassage = false

    /** The profile decoded: the operator's, or the transmitter's, 800 bit/s otherwise. */
    fun genesisProfil(): RecepteurGenesis.Profil = RecepteurGenesis.Profil.de(genesisProfilChoisi) ?: emetteurGenesis()?.first ?: RecepteurGenesis.Profil.G800

    private fun genesisEnMarche() = genesisActif || genesisPourPassage

    init {
        genesis.capture = genesisCapture
        genesis.contexte = {
            val s = suiviGenesis()
            RecepteurGenesis.Contexte(s?.first.orEmpty(), s?.second ?: 0, essai = sonFictif())
        }
    }

    /**
     * A frame heard in AFSK 1200 on the card (the APRS decoder's, AX.25 or
     * bare): SSDV sent through an FM repeater (IO-86 / LAPAN-A2, ssdv2sat:
     * whole or shorter packets) joins the pictures. Counted in [ssdvAfsk].
     */
    fun trameAfsk(o: ByteArray) {
        val l = fr.f4ioz.satcombo.telemesure.Ssdv.cherche(o)
        if (l.isEmpty()) return
        val s = suiviGenesis()
        for (p in l) if (paquets.assemblage.ajoute(p, horloge(), s?.first.orEmpty(), "AFSK 1200").resultat ==
            fr.f4ioz.satcombo.telemesure.AssemblageSsdv.Resultat.NOUVEAU) ssdvAfsk++
    }
    @Volatile var ssdvAfsk = 0; private set

    /** The sound card's audio (the Son tab's source). */
    fun ecouteCarte(pcm: ShortArray, n: Int, rate: Int) {
        if (!genesisEnMarche() || genesisSource != SOURCE_CARTE) return
        genesis.profil = genesisProfil()
        genesis.traite(pcm, n, rate)
    }

    private fun ecouteSdrGenesis(pcm: ShortArray, n: Int) {
        if (!genesisEnMarche() || genesisSource != SOURCE_SDR) return
        genesis.profil = genesisProfil()
        genesis.traite(pcm, n, Dsp.AUDIO_RATE)
    }

    /** The test: a made-up GENESIS signal (a test picture, noise, drift) through a receiver of its own, nothing kept. */
    fun lanceEssaiGenesis(): Boolean {
        if (essaiGenesisEnCours) return false
        essaiGenesisEnCours = true
        Thread {
            try {
                val p = genesisProfil()
                val n = (horloge() / 1000 % 200).toInt()
                // At 200 bit/s a whole picture is 2 min of sound: the first packets only.
                val paquets = fr.f4ioz.satcombo.telemesure.SignalPaquets.paquetsMire(n).let { if (p == RecepteurGenesis.Profil.G200) it.take(8) else it }
                    .map { fr.f4ioz.satcombo.telemesure.Ssdv.recachete(it, fr.f4ioz.satcombo.telemesure.Genesis.INDICATIF_SSDV) }
                val o = paquets.flatMap { (fr.f4ioz.satcombo.telemesure.Genesis.codeSsdv(it) + ByteArray(4) { 0xAA.toByte() }).toList() }.toByteArray()
                val bits = fr.f4ioz.satcombo.telemesure.Genesis.bits(fr.f4ioz.satcombo.telemesure.Genesis.code(2, 3, ByteArray(15))) +
                    fr.f4ioz.satcombo.telemesure.Genesis.bits(o, preambule = 0)
                val audio = fr.f4ioz.satcombo.telemesure.SignalFskAudio.audio(bits, Dsp.AUDIO_RATE, p.debit, p.ecartHz, 1_650.0, 12.0, 3.0, graine = horloge())
                essaiGenesis.profil = p
                var k = 0
                while (k < audio.size) { val m = minOf(4096, audio.size - k); essaiGenesis.traite(audio.copyOfRange(k, k + m), m, Dsp.AUDIO_RATE); k += m }
            } finally { essaiGenesisEnCours = false }
        }.apply { isDaemon = true; name = "essai-genesis" }.start()
        return true
    }

    /**
     * A recording read again by the GENESIS receiver: a WAV (the Son tab's,
     * or another program's, USB audio) or a capture of bits (`.bits`). Its
     * SSDV packets join the pictures (the pass labelled with the file).
     */
    fun relitGenesis(octets: ByteArray, nom: String): JSONObject {
        val sat = suiviGenesis()
        val source = "enregistrement " + nom.substringAfterLast('/').take(60)
        val avant = paquets.assemblage.photos().sumOf { it.recus }
        var trames = 0
        if (nom.endsWith(".bits")) {
            fr.f4ioz.satcombo.telemesure.RecepteurGenesis.relit(octets, { trames++ }) { p ->
                paquets.assemblage.ajoute(p, horloge(), sat?.first.orEmpty(), source)
            }
        } else {
            val (pcm, rate) = fr.f4ioz.satcombo.telemesure.LectureWav.lis(octets) ?: return erreur("pas un WAV lisible (PCM 8/16/24/32 bits ou flottant)")
            // The recording's own time when its name tells it (SatMe_<sat>_yyyyMMdd_HHmmssZ.wav), now otherwise.
            val debut = Regex("(\\d{8}_\\d{6})Z").find(nom)?.groupValues?.get(1)?.let { d ->
                runCatching { java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d)?.time }.getOrNull()
            } ?: horloge()
            var lus = 0L
            val r = RecepteurGenesis(null, paquets.assemblage) { debut + lus * 1000 / rate }
            r.source = source
            r.profil = genesisProfil()
            r.contexte = { RecepteurGenesis.Contexte(sat?.first.orEmpty(), sat?.second ?: 0) }
            var k = 0
            while (k < pcm.size) { val m = minOf(8192, pcm.size - k); r.traite(pcm.copyOfRange(k, k + m), m, rate); k += m; lus = k.toLong() }
            trames = r.etat.trames
            val apres = paquets.assemblage.photos().sumOf { it.recus }
            return JSONObject().put("ok", true).put("trames", trames).put("ssdv", r.etat.ssdv).put("nouveaux", apres - avant)
                .put("secondes", pcm.size / rate).put("profil", r.profil.nom).put("syncs", r.etat.syncs)
        }
        val apres = paquets.assemblage.photos().sumOf { it.recus }
        return JSONObject().put("ok", true).put("trames", trames).put("nouveaux", apres - avant)
    }

    private fun jsonGenesis(): JSONObject {
        val em = emetteurGenesis()
        val o = genesis.json().put("option", genesisActif).put("pourPassage", genesisPourPassage).put("source", genesisSource)
            .put("choisi", genesisProfilChoisi).put("profilNom", genesisProfil().nom).put("captureOption", genesisCapture)
            .put("essaiEnCours", essaiGenesisEnCours).put("ssdvAfsk", ssdvAfsk)
        if (em != null) o.put("emetteur", JSONObject().put("description", em.second).put("profil", em.first.cle))
        val es = essaiGenesis.json()
        if (es.optLong("bits") > 0) o.put("essai", es)
        o.put("enregistrements", JSONArray(runCatching { enregistrements().take(15) }.getOrDefault(emptyList())))
        // The captures kept (the latest 12).
        o.put("captures", JSONArray(File(dossier, "telemesure/genesis").listFiles { f -> f.name.endsWith(".bits") }.orEmpty()
            .sortedByDescending { it.lastModified() }.take(12).map { JSONObject().put("f", it.name).put("octets", it.length()).put("t", it.lastModified()) }))
        return o
    }

    /** The rate to decode at: the operator's, or the transmitter's, 9600 when unknown. */
    fun debitVoulu(): Int = debitChoisi.takeIf { it in RecepteurPaquets.DEBITS } ?: emetteurPaquets()?.first ?: 9_600

    /** The main SDR's raw I/Q off QO-100 (from [SdrPc]). */
    fun iqSdr(iq: ByteArray?, iq16: ShortArray?, n: Int, decalageHz: Double, essai: Boolean) {
        if (!paquetsActif) { if (paquets.actif) paquets.arrete(); return }
        if (!paquets.actif) paquets.demarre(fr.f4ioz.satcombo.sdr.Dsp.RTL_RATE.toDouble())
        fictif = essai
        paquets.debit = debitVoulu()
        if (iq16 != null) paquets.iq16(iq16, n, decalageHz) else if (iq != null) paquets.iq(iq, n, decalageHz)
    }

    /** The test mode for packets: a made-up 9600 bit/s burst (telemetry, part of a picture) through its own decoder. */
    fun lanceEssaiPaquets(): Boolean {
        if (essaiPaquetsEnCours) return false
        essaiPaquetsEnCours = true
        Thread {
            try {
                val fs = RecepteurPaquets.FS_CIBLE
                val image = fr.f4ioz.satcombo.telemesure.SignalPaquets.paquetsMire((horloge() / 1000 % 200).toInt())
                val trames = listOf(fr.f4ioz.satcombo.telemesure.SignalPaquets.trameTelemesure(1)) +
                    image.map { fr.f4ioz.satcombo.telemesure.SignalPaquets.trameSsdv(it) }.filterIndexed { i, _ -> i != 4 }
                // Then a Geoscan picture (its own framing, NRZ): a small JPEG in chunks, one missing.
                val geo = fr.f4ioz.satcombo.telemesure.Geoscan.morceaux(0x0C, (horloge() / 1000 % 900).toInt(),
                    fr.f4ioz.satcombo.telemesure.Geoscan.jpegEssai()).filterIndexed { i, _ -> i != 7 }
                val (re, im) = fr.f4ioz.satcombo.telemesure.SignalPaquets.baseband(fr.f4ioz.satcombo.telemesure.G3ruh.bits(trames) +
                    fr.f4ioz.satcombo.telemesure.Geoscan.bits(geo), fs, 9_600, 1_800.0, 16.0, horloge())
                essaiPaquets.debit = 9_600
                var k = 0
                while (k < re.size) {
                    val m = minOf(8_192, re.size - k)
                    essaiPaquets.traiteBaseband(re.copyOfRange(k, k + m), im.copyOfRange(k, k + m), m, fs); k += m
                }
            } finally { essaiPaquetsEnCours = false }
        }.apply { isDaemon = true; name = "essai-paquets" }.start()
        return true
    }

    /** One SSDV picture's JPEG (live or the test's), or a saved one by its file name. */
    fun jpegSsdv(q: Map<String, String>): ByteArray? {
        // A Geoscan picture (JPEG chunks): as it stands, cut after the last chunk received.
        q["geo"]?.let { c -> return (paquets.geoscan.image(c) ?: essaiPaquets.geoscanEssai.image(c))?.jpeg() }
        q["cle"]?.let { c -> return paquets.jpeg(c) ?: essaiPaquets.jpeg(c) }
        val f = q["f"]?.takeIf { fr.f4ioz.satcombo.telemesure.GalerieTelemesure.FICHIER.matches(it) } ?: return null
        // A finished Geoscan picture is kept with the Geoscan ones (its name says it).
        val rep = if (f.startsWith("geoscan-")) "telemesure/geoscan" else "telemesure/ssdv"
        return File(dossier, "$rep/$f").takeIf { it.isFile }?.readBytes()
    }

    /** The SSDV and Geoscan pictures (the station's and the test's, then those saved), as the common « Images » gallery lists them. */
    fun galerie(): JSONObject = fr.f4ioz.satcombo.telemesure.GalerieTelemesure.json(
        listOf(paquets.assemblage, essaiPaquets.assemblage), listOf(paquets.geoscan to false, essaiPaquets.geoscanEssai to true), dossier)

    /** A picture (live, test, or saved) as PNG, for « Exporter ». */
    fun pngSsdv(q: Map<String, String>): ByteArray? {
        val j = jpegSsdv(q) ?: return null
        val im = runCatching { javax.imageio.ImageIO.read(j.inputStream()) }.getOrNull() ?: return null
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(im, "png", it) }.toByteArray()
    }

    /** The file name a picture is downloaded under. */
    fun nomSsdv(q: Map<String, String>, ext: String): String =
        "SSDV_" + RecepteurPaquets.nomFichier(q["cle"] ?: q["geo"] ?: q["f"]?.substringBeforeLast('.') ?: "image") + "." + ext

    /** SSDV packets from a file (a missed pass caught by another station, an older log): put with the pictures. */
    fun importeSsdv(octets: ByteArray, nom: String): JSONObject {
        if (octets.isEmpty()) return erreur("fichier vide")
        val (n, d) = paquets.assemblage.importe(octets, nom.ifBlank { "fichier" })
        if (n == 0 && d == 0) return erreur("aucun paquet SSDV valide dans ce fichier")
        return JSONObject().put("ok", true).put("nouveaux", n).put("doublons", d)
    }

    private fun jsonPaquets(): JSONObject {
        val e = paquets.etat
        val em = emetteurPaquets()
        val o = JSONObject().put("option", paquetsActif).put("debit", debitVoulu()).put("choisi", debitChoisi)
            .put("kiss", kiss).put("essaiEnCours", essaiPaquetsEnCours)
        if (em != null) o.put("emetteur", JSONObject().put("debit", em.first).put("description", em.second).put("mode", em.third)
            .put("note", RecepteurPaquets.noteMode(em.third) ?: ""))
        o.put("etat", JSONObject().put("actif", e.actif).put("bits", e.bits).put("trames", e.trames).put("crcFaux", e.crcFaux)
            .put("afcHz", Math.round(e.afcHz)).put("niveauDb", Math.round(e.niveauDb * 10) / 10.0).put("plancherDb", Math.round(e.plancherDb * 10) / 10.0)
            .put("presence", e.presence).put("signal", e.signal).put("perdus", e.perdus).put("derniereMs", e.derniereMs))
        val l = (paquets.trames.take(40) + essaiPaquets.trames.take(40)).sortedByDescending { it.t }.take(40)
        o.put("trames", JSONArray(l.map { RecepteurPaquets.json(it).put("hex", it.hex()) }))
        val imgs = JSONArray()
        for (j in listOf(paquets.imagesJson(), essaiPaquets.imagesJson())) for (i in 0 until j.length()) imgs.put(j.get(i))
        o.put("images", imgs)
        val geo = JSONArray()
        for (j in listOf(paquets.geoscan.json(), essaiPaquets.geoscanEssai.json().also { a -> for (i in 0 until a.length()) a.getJSONObject(i).put("essai", true) }))
            for (i in 0 until j.length()) geo.put(j.get(i))
        o.put("geoscan", geo)
        // Saved pictures (the latest 24).
        val sauvees = File(dossier, "telemesure/ssdv").listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
            .sortedByDescending { it.lastModified() }.take(24).map { JSONObject().put("f", it.name).put("t", it.lastModified()) }
        o.put("sauvees", JSONArray(sauvees))
        val st = station()
        o.put("sids", JSONObject().put("option", sidsActif).put("indicatif", st?.first.orEmpty()).put("envoyees", sids.envoyees)
            .put("attente", sids.enAttente).put("echecs", sids.echecs).put("erreur", sids.derniereErreur ?: "").put("dernierMs", sids.dernierEnvoiMs)
            .put("essai", urlSids != Sids.URL_DB))
        return o
    }

    /** The main receiver's audio ([Dsp.AUDIO_RATE]) when it is not on QO-100. */
    fun ecouteSdr(pcm: ShortArray, n: Int) {
        runCatching { ecouteSdrGenesis(pcm, n) }
        if (!leoActif) return
        val d = leo ?: DecodeurTelemesure(Profil.FUNCUBE, Dsp.AUDIO_RATE, horloge = horloge) { t -> garde(t, Profil.FUNCUBE, satellite()) }
            .also { leo = it }
        d.traite(pcm, n)
    }

    /** A frame written to the day's file (test frames are not). */
    private fun garde(t: DecodeurTelemesure.Trame, p: Profil, sat: String) {
        if (t.essai) return
        runCatching {
            val d = File(dossier, "telemesure").apply { mkdirs() }
            val jour = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(t.t))
            val o = JSONObject().put("t", t.t).put("profil", p.cle).put("satellite", sat).put("format", t.format).put("valide", t.valide)
                .put("corriges", t.octetsCorriges).put("faux", t.symbolesFaux).put("hex", t.hex())
            File(d, "${p.cle}-$jour.jsonl").appendText(o.toString() + "\n")
        }
    }

    /** The test mode: a made-up signal for [p] through a decoder of its own (a few seconds of work). */
    fun lanceEssai(p: Profil): Boolean {
        if (essaiEnCours) return false
        essaiEnCours = true
        val d = DecodeurTelemesure(p, Dsp.AUDIO_RATE, horloge = horloge).also { it.essai = true }
        essai = d
        Thread {
            try {
                val audio = when (p) {
                    Profil.QO100 -> {
                        val fec = SignalEssai.texteBalise("E HI de SatMe : essai du décodeur de balise, signal fabriqué".replace('é', 'e').replace('à', 'a'), Ao40Fec.OCTETS)
                        val p3 = SignalEssai.texteBalise("E HI de SatMe : bloc non codé d'essai".replace('é', 'e'), BlocP3.OCTETS)
                        SignalEssai.audio(Ao40Fec.code(fec) + BlocP3.code(p3) + IntArray(40) { it and 1 }, p.debit, manchester = true,
                            porteuseHz = 1_640.0, deriveHzS = 1.0, ebN0Db = 11.0, graine = horloge())
                    }
                    Profil.FUNCUBE -> {
                        val champs = ESSAI_FUNCUBE
                        val t1 = SignalEssai.trameFuncube(14, champs, "Essai SatMe : trame FUNcube fabriquee".toByteArray())
                        val t2 = SignalEssai.trameFuncube(1, champs)
                        SignalEssai.audio(Ao40Fec.code(t1) + IntArray(240) { (it / 2) and 1 } + Ao40Fec.code(t2), p.debit, manchester = false,
                            porteuseHz = 1_220.0, deriveHzS = 12.0, ebN0Db = 9.0, graine = horloge())
                    }
                }
                var k = 0
                while (k < audio.size) {
                    val n = minOf(4096, audio.size - k)
                    d.traite(audio.copyOfRange(k, k + n), n); k += n
                }
            } finally { essaiEnCours = false }
        }.apply { isDaemon = true; name = "essai-telemesure" }.start()
        return true
    }

    fun action(chemin: String, q: Map<String, String>): JSONObject {
        val ok = JSONObject().put("ok", true)
        when (chemin.removePrefix("/api/telemesure").trim('/')) {
            // The beacon channel in one click (the option « Décodeurs multiples » turned on with it).
            "balise" -> {
                val k = canaux ?: return erreur("absent")
                if (q["on"] == "0") {
                    k.liste.firstOrNull { it.decodeur == DecodeursMultiples.Decodeur.BALISE }?.let { k.action("/api/canaux/retire", mapOf("id" to it.id.toString())) }
                    return ok
                }
                if (k.balise() != null) return ok
                return k.action("/api/canaux/ajoute", mapOf("hz" to DecodeursMultiples.CADRAN_BALISE_HZ.toString(),
                    "cadran" to DecodeursMultiples.CADRAN_BALISE_HZ.toString(), "decodeur" to "balise"))
            }
            "leo" -> leoActif = q["on"] == "1"
            "essai" -> if (!lanceEssai(Profil.de(q["profil"]) ?: Profil.QO100)) return erreur("un essai est déjà en cours")
            "vide" -> { leo?.vide(); canaux?.balise()?.second?.vide(); essai = null }
            "paquets" -> paquetsActif = q["on"] == "1"
            "paquets/debit" -> debitChoisi = q["v"]?.toIntOrNull()?.takeIf { it == 0 || it in RecepteurPaquets.DEBITS } ?: return erreur("débit inconnu")
            "paquets/kiss" -> kiss = q["on"] == "1"
            "paquets/sids" -> {
                val on = q["on"] == "1"
                if (on && station()?.first.isNullOrBlank()) return erreur("indicatif de la station manquant (Réglages)")
                sidsActif = on
            }
            "paquets/essai" -> if (!lanceEssaiPaquets()) return erreur("un essai est déjà en cours")
            "paquets/vide" -> { paquets.vide(); essaiPaquets.vide() }
            "genesis" -> genesisActif = q["on"] == "1"
            "genesis/source" -> genesisSource = q["v"].orEmpty()
            "genesis/profil" -> genesisProfilChoisi = q["v"].orEmpty()
            "genesis/capture" -> genesisCapture = q["on"] == "1"
            "genesis/essai" -> if (!lanceEssaiGenesis()) return erreur("un essai est déjà en cours")
            "genesis/vide" -> { genesis.vide(); essaiGenesis.vide(); essaiPaquets.assemblage.videEssais() }
            // A recording of the Son tab, or a capture kept, read again by name.
            "genesis/relit" -> {
                val nom = q["f"].orEmpty()
                val f = if (nom.endsWith(".bits") && Regex("[A-Za-z0-9_.-]+").matches(nom)) File(dossier, "telemesure/genesis/$nom").takeIf { it.isFile }
                    else enregistrement(nom)
                f ?: return erreur("fichier introuvable")
                if (f.length() > MAX_RELECTURE) return erreur("fichier trop gros")
                return relitGenesis(f.readBytes(), f.name)
            }
            // A picture under way dropped (its packets' file removed; a finished one's JPEG stays).
            "ssdv/abandon" -> {
                val c = q["cle"]?.takeIf { fr.f4ioz.satcombo.telemesure.AssemblageSsdv.idValide(it) } ?: return erreur("image inconnue")
                if (!paquets.assemblage.abandonne(c) && !essaiPaquets.assemblage.abandonne(c)) return erreur("image inconnue")
            }
            else -> return erreur("geste inconnu")
        }
        return ok
    }

    private fun erreur(m: String) = JSONObject().put("ok", false).put("erreur", m)

    fun json(): JSONObject {
        val o = JSONObject()
        val b = canaux?.balise()
        o.put("balise", JSONObject().put("canal", b != null).put("cadran", b?.first?.cadranHz ?: DecodeursMultiples.CADRAN_BALISE_HZ)
            .put("tourne", canaux?.tourne ?: false).also { j -> b?.second?.let { decodeur(j, it) } })
        o.put("leo", JSONObject().put("option", leoActif).put("satellite", satellite()).also { j -> leo?.let { decodeur(j, it) } })
        o.put("essai", JSONObject().put("enCours", essaiEnCours).also { j -> essai?.let { decodeur(j, it) } })
        o.put("paquets", jsonPaquets())
        o.put("genesis", jsonGenesis())
        return o.put("ok", true)
    }

    private fun decodeur(j: JSONObject, d: DecodeurTelemesure) {
        val e = d.etat
        j.put("profil", d.profil.cle).put("nom", d.profil.nom)
            .put("etat", JSONObject().put("bits", e.bits).put("trames", e.trames).put("echecs", e.echecs).put("blocs", e.blocsP3)
                .put("crcFaux", e.crcFaux).put("decalageHz", Math.round(e.decalageHz * 10) / 10.0)
                .put("verrou", Math.round(e.verrouillage * 100) / 100.0).put("manchester", e.manchester)
                .put("presenceDb", Math.round(e.presenceDb * 10) / 10.0).put("derniereMs", e.derniereMs)
                .put("sync", Math.round(e.meilleureSync * 100) / 100.0))
            .put("trames", JSONArray(d.trames.take(12).map { DecodeurTelemesure.json(it, d.profil) }))
    }

    companion object {
        const val CLE_LEO = "pc_tlm_leo"
        const val CLE_PAQUETS = "pc_tlm_paquets"
        const val CLE_DEBIT = "pc_tlm_paquets_debit"
        const val CLE_KISS = "pc_tlm_paquets_kiss"
        const val CLE_SIDS = "pc_tlm_sids"
        const val CLE_GENESIS = "pc_tlm_genesis"
        const val CLE_GENESIS_SOURCE = "pc_tlm_genesis_source"
        const val CLE_GENESIS_PROFIL = "pc_tlm_genesis_profil"
        const val CLE_GENESIS_CAPTURE = "pc_tlm_genesis_capture"
        const val SOURCE_CARTE = "carte"
        const val SOURCE_SDR = "sdr"
        /** A recording read again: 400 MB (a pass at 48 kHz is some 60 MB). */
        const val MAX_RELECTURE = 400L * 1024 * 1024

        /** Plausible real-time values for the test frames (raw counts, in the document's order). */
        val ESSAI_FUNCUBE: List<Pair<Int, Long>> = buildList {
            addAll(listOf(16 to 4810L, 16 to 4790L, 16 to 3020L, 16 to 312L, 16 to 8190L, 16 to 245L, 16 to 12L, 16 to 0L))
            addAll(listOf(8 to 18L, 8 to 19L, 8 to 18L, 8 to 16L, 8 to 0L, 8 to 0L, 8 to 0L, 8 to 1L))
            addAll(listOf(10 to 420L, 10 to 80L, 10 to 610L, 10 to 690L, 10 to 700L, 10 to 705L, 10 to 698L, 10 to 330L, 10 to 120L, 10 to 500L))
            addAll(listOf(8 to 163L, 8 to 182L, 8 to 200L, 8 to 30L, 8 to 60L, 8 to 70L))
            addAll(listOf(8 to 20L, 8 to 140L, 8 to 180L, 8 to 160L))
            addAll(listOf(8 to 120L, 8 to 121L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L))
            addAll(listOf(24 to 31_415L, 6 to 0L, 5 to 0L, 1 to 0L))
            addAll(listOf(1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 0L, 1 to 0L, 1 to 0L, 1 to 0L, 1 to 0L))
        }
    }
}
