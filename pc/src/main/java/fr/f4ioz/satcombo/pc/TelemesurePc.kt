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
import fr.f4ioz.satcombo.visu.SondeSignal
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
        set(v) { stockage.edit().putBoolean(CLE_PAQUETS, v).apply(); if (!v) paquets.arrete(); appliqueSonPaquets() }
    /**
     * Where the packets come from: [SOURCE_SDR] (the main SDR's raw I/Q, by
     * default) or [SOURCE_SON] (a rig's 9600 output on a sound card: the
     * TS-2000's ACC2 with its external TNC speed at 9600 bps).
     */
    var paquetsSource: String
        get() = stockage.getString(CLE_PAQUETS_SOURCE, SOURCE_SDR)?.takeIf { it == SOURCE_SON } ?: SOURCE_SDR
        set(v) { stockage.edit().putString(CLE_PAQUETS_SOURCE, if (v == SOURCE_SON) SOURCE_SON else SOURCE_SDR).apply(); appliqueSonPaquets() }
    /** The card for [SOURCE_SON]: "" the Son tab's card (as it runs), else a card opened for the packets alone. */
    var paquetsCarte: String
        get() = stockage.getString(CLE_PAQUETS_CARTE, "") ?: ""
        set(v) { stockage.edit().putString(CLE_PAQUETS_CARTE, v.take(200)).apply(); appliqueSonPaquets() }

    /** The sound cards that can record (names), for the choice. */
    @Volatile var cartes: () -> List<String> = { emptyList() }
    /** Opens a card for one listener of its own: its closer, or null and why (the Son tab's [SonPc.capture]). */
    @Volatile var ouvreCarte: (String, (ShortArray, Int, Int) -> Unit) -> Pair<(() -> Unit)?, String> = { _, _ -> null to "pas de carte son" }
    /** What frames heard by a sound card are stamped with: the CAT's downlink, not the SDR's. */
    @Volatile var contextePaquetsSon: (essai: Boolean) -> RecepteurPaquets.Contexte = { RecepteurPaquets.Contexte(essai = it) }
    private var fermeCarte: (() -> Unit)? = null
    @Volatile private var carteOuverte: String? = null
    @Volatile private var erreurCarte = ""
    @Volatile private var fictifSon = false
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
    /** Each AX.25 frame of the packet decoder heard on the air: the KISS TCP and AGWPE servers (listening only). */
    @Volatile var surAx25: ((ByteArray) -> Unit)? = null

    init {
        paquets.kiss = kiss
        paquets.contexte = { if (paquets.sourceEnMarche == RecepteurPaquets.SOURCE_SON) contextePaquetsSon(fictifSon) else contextePaquets(fictif) }
        paquets.surTrame = { t ->
            partage(t)
            // An AX.25 frame from the air (not the test's) for the KISS TCP and AGWPE servers.
            if (!t.essai && t.ax25 != null && t.format == RecepteurPaquets.FORMAT_HDLC) surAx25?.let { f -> runCatching { f(t.octets) } }
        }
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
    /** The satellite followed is above the horizon (or near it). */
    @Volatile var suiviEnVue: () -> Boolean = { true }
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
    /** "" from the transmitter (both rates for AMSAT-EA's), else a profile's key (auto, 800, 200). */
    var genesisProfilChoisi: String
        get() = stockage.getString(CLE_GENESIS_PROFIL, "") ?: ""
        set(v) { stockage.edit().putString(CLE_GENESIS_PROFIL, if (RecepteurGenesis.Profil.de(v) != null) v else "").apply() }
    /** The demodulated bits kept per pass (telemesure/genesis). */
    var genesisCapture: Boolean
        get() = stockage.getBoolean(CLE_GENESIS_CAPTURE, false)
        set(v) { stockage.edit().putBoolean(CLE_GENESIS_CAPTURE, v).apply(); genesis.capture = v }
    /** Each voice message written down by « Phonie → texte » when it ends (real receptions only); on by default. */
    var genesisTranscrit: Boolean
        get() = stockage.getBoolean(CLE_GENESIS_TRANSCRIT, true)
        set(v) { stockage.edit().putBoolean(CLE_GENESIS_TRANSCRIT, v).apply(); if (!v) voixFinies.clear() }
    /** Turned on for a pass by the auto-pilot (an SSDV campaign), whatever the option. */
    @Volatile var genesisPourPassage = false

    /**
     * Turned on for a pass by the auto-pilot (an SSDV campaign sent in FSK/GMSK at 1200–9600 bit/s, Geoscan…):
     * the packet decoder on the main SDR's I/Q, whatever the option; off again, it stops unless the option holds it.
     */
    @Volatile var paquetsPourPassage = false
        set(v) { field = v; if (!v && !(paquetsActif && paquetsSource == SOURCE_SDR) && paquets.sourceEnMarche == RecepteurPaquets.SOURCE_IQ) paquets.arrete() }

    /**
     * The SSDV received between [deMs] and [aMs] (both decoders, the test's left out): the packets placed,
     * and the pictures they went into — a pass's share, for the auto-pilot's history.
     */
    fun bilanSsdv(deMs: Long, aMs: Long): Pair<Int, Int> = runCatching {
        var n = 0; var im = 0
        for (ph in paquets.assemblage.photos()) {
            if (ph.essai) continue
            val k = ph.passages.toList().filter { it.fin >= deMs && it.debut <= aMs }.sumOf { it.paquets }
            if (k > 0) { n += k; im++ }
        }
        n to im
    }.getOrDefault(0 to 0)

    /** The profile decoded: the operator's, or the transmitter's, both rates at once otherwise. */
    fun genesisProfil(): RecepteurGenesis.Profil = RecepteurGenesis.Profil.de(genesisProfilChoisi) ?: emetteurGenesis()?.first ?: RecepteurGenesis.Profil.AUTO

    private fun genesisEnMarche() = genesisActif || genesisPourPassage

    init {
        genesis.capture = genesisCapture
        // A message ended (from the sound's thread): queued, written down by [verifieVoix].
        genesis.voix.surFin = { o -> if (genesisTranscrit && voixFinies.size < MAX_VOIX_FINIES) voixFinies.add(o) }
        genesis.contexte = {
            val s = suiviGenesis()
            RecepteurGenesis.Contexte(s?.first.orEmpty(), s?.second ?: 0, essai = sonFictif(), enVue = s != null && suiviEnVue())
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
        if (paquetsActif && paquetsSource == SOURCE_SON && paquetsCarte.isBlank()) sonPaquets(pcm, n, rate, sonFictif())
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
                val choisi = genesisProfil()
                // Both rates heard at once: the test sends at 800 bit/s.
                val p = if (choisi == RecepteurGenesis.Profil.AUTO) RecepteurGenesis.Profil.G800 else choisi
                val n = (horloge() / 1000 % 200).toInt()
                // At 200 bit/s a whole picture is 2 min of sound: the first packets only.
                val paquets = fr.f4ioz.satcombo.telemesure.SignalPaquets.paquetsMire(n).let { if (p == RecepteurGenesis.Profil.G200) it.take(8) else it }
                    .map { fr.f4ioz.satcombo.telemesure.Ssdv.recachete(it, fr.f4ioz.satcombo.telemesure.Genesis.INDICATIF_SSDV) }
                val o = paquets.flatMap { (fr.f4ioz.satcombo.telemesure.Genesis.codeSsdv(it) + ByteArray(4) { 0xAA.toByte() }).toList() }.toByteArray()
                val bits = fr.f4ioz.satcombo.telemesure.Genesis.bits(fr.f4ioz.satcombo.telemesure.Genesis.code(2, 3, ByteArray(15))) +
                    fr.f4ioz.satcombo.telemesure.Genesis.bits(o, preambule = 0)
                val audio = fr.f4ioz.satcombo.telemesure.SignalFskAudio.audio(bits, Dsp.AUDIO_RATE, p.debit, p.ecartHz, 1_650.0, 12.0, 3.0, graine = horloge())
                essaiGenesis.profil = choisi
                var k = 0
                val t0 = horloge()
                while (k < audio.size) {
                    val m = minOf(4096, audio.size - k); essaiGenesis.traite(audio.copyOfRange(k, k + m), m, Dsp.AUDIO_RATE); k += m
                    rythme(essaiGenesis.sonde, t0, k.toLong(), Dsp.AUDIO_RATE.toDouble())
                }
            } finally { essaiGenesisEnCours = false }
        }.apply { isDaemon = true; name = "essai-genesis" }.start()
        return true
    }

    /**
     * A recording read again by the GENESIS receiver: a WAV (the Son tab's,
     * or another program's, USB audio) or a capture of bits (`.bits`). Its
     * SSDV packets join the pictures (the pass labelled with the file).
     */
    fun relitGenesis(octets: ByteArray, nom: String, sat: Pair<String, Int>? = suiviGenesis()): JSONObject {
        val source = "enregistrement " + nom.substringAfterLast('/').take(60)
        val avant = paquets.assemblage.photos().sumOf { it.recus }
        var trames = 0
        var voix = 0
        if (nom.endsWith(".bits")) {
            // Its own assembler: the live message is left alone, and what is read again is not written down by itself.
            val av = fr.f4ioz.satcombo.telemesure.AssemblageVoix(dossier)
            fr.f4ioz.satcombo.telemesure.RecepteurGenesis.relit(octets, { trames++ }, { p ->
                paquets.assemblage.ajoute(p, horloge(), sat?.first.orEmpty(), source)
            }) { n, o -> if (av.ajoute(n, o, horloge(), sat?.first.orEmpty())) voix++ }
        } else {
            val (pcm, rate) = fr.f4ioz.satcombo.telemesure.LectureWav.lis(octets) ?: return erreur("pas un WAV lisible (PCM 8/16/24/32 bits ou flottant)")
            // The recording's own time when its name tells it (SatMe_<sat>_yyyyMMdd_HHmmssZ.wav), now otherwise.
            val debut = Regex("(\\d{8}_\\d{6})Z").find(nom)?.groupValues?.get(1)?.let { d ->
                runCatching { java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d)?.time }.getOrNull()
            } ?: horloge()
            var lus = 0L
            // Its own receiver (the voice messages written beside the live ones', never a capture of bits).
            val r = RecepteurGenesis(dossier, paquets.assemblage) { debut + lus * 1000 / rate }
            r.source = source
            r.profil = genesisProfil()
            r.contexte = { RecepteurGenesis.Contexte(sat?.first.orEmpty(), sat?.second ?: 0) }
            var k = 0
            while (k < pcm.size) { val m = minOf(8192, pcm.size - k); r.traite(pcm.copyOfRange(k, k + m), m, rate); k += m; lus = k.toLong() }
            trames = r.etat.trames
            val apres = paquets.assemblage.photos().sumOf { it.recus }
            return JSONObject().put("ok", true).put("trames", trames).put("ssdv", r.etat.ssdv).put("nouveaux", apres - avant)
                .put("secondes", pcm.size / rate).put("profil", r.profil.nom).put("syncs", r.etat.syncs).put("voix", r.etat.voix)
                .put("recoit", r.etat.recoit)
        }
        val apres = paquets.assemblage.photos().sumOf { it.recus }
        return JSONObject().put("ok", true).put("trames", trames).put("nouveaux", apres - avant).put("voix", voix)
    }

    /** « Relire les enregistrements du satellite »: its WAVs of the Son tab, oldest first, in the background. */
    @Volatile private var relecture = JSONObject()

    /** The recordings of [satellite] (the Son tab's names: SatMe_<name>_<yyyyMMdd>_<HHmmss>Z.wav). */
    fun enregistrementsDe(satellite: String): List<String> {
        val cle = satellite.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-')
        if (cle.isEmpty()) return emptyList()
        return enregistrements().filter { n ->
            n.removePrefix("SatMe_").substringBeforeLast('_').substringBeforeLast('_').trim('-') == cle
        }.sorted()
    }

    fun relitEnregistrements(satellite: String, norad: Int): Boolean {
        if (relecture.optBoolean("enCours")) return false
        val noms = enregistrementsDe(satellite)
        relecture = JSONObject().put("enCours", true).put("sat", satellite).put("fichiers", noms.size).put("faits", 0)
            .put("nouveaux", 0).put("voix", 0).put("trames", 0)
        Thread {
            var nouveaux = 0; var voix = 0; var trames = 0; var faits = 0
            try {
                for (n in noms) {
                    val f = enregistrement(n) ?: continue
                    if (f.length() > MAX_RELECTURE) continue
                    val r = runCatching { relitGenesis(f.readBytes(), f.name, satellite to norad) }.getOrNull()
                    faits++
                    if (r != null && r.optBoolean("ok")) { nouveaux += r.optInt("nouveaux"); voix += r.optInt("voix"); trames += r.optInt("trames") }
                    relecture = JSONObject(relecture.toString()).put("faits", faits).put("nouveaux", nouveaux).put("voix", voix).put("trames", trames)
                }
            } finally {
                relecture = JSONObject(relecture.toString()).put("enCours", false).put("fin", horloge())
            }
        }.apply { isDaemon = true; name = "relecture-genesis"; priority = Thread.MIN_PRIORITY }.start()
        return true
    }

    // ---------------------------------------------------------------- the voice messages (Codec2)

    /** A voice message (its `.json`) given to « Phonie → texte »: null when queued, else why not (set by Main). */
    @Volatile var transcritVoix: ((JSONObject) -> String?)? = null
    /** The live messages ended, waiting to be written down. */
    private val voixFinies = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    /** Why the last message ended was not written down ("" when it was). */
    @Volatile var voixErreur = ""
        private set

    /**
     * On a timer (a few seconds, low priority): the live voice message ended by time or by the satellite set
     * ([RecepteurGenesis.finVoix]), then each message ended given to « Phonie → texte » ([transcritVoix]), once.
     * Never from the sound's thread: making the WAV runs libcodec2.
     */
    fun verifieVoix() {
        runCatching { genesis.finVoix() }
        while (true) {
            val o = voixFinies.poll() ?: return
            if (!genesisTranscrit) continue
            val f = transcritVoix ?: continue
            voixErreur = runCatching { f(o) }.getOrElse { it.message ?: "échec" } ?: ""
        }
    }

    /** A voice message's `.c2` (playable with `c2dec 700C`). */
    fun voixC2(nom: String): File? = fr.f4ioz.satcombo.telemesure.AssemblageVoix.fichier(dossier, nom)

    /** A voice message made into a WAV (8 kHz) by `satme_codec2.py` and libcodec2, kept beside its `.c2`; null when it cannot be. */
    fun voixWav(nom: String): File? {
        val c2 = fr.f4ioz.satcombo.telemesure.AssemblageVoix.fichier(dossier, nom) ?: return null
        val wav = File(c2.parentFile, "$nom.wav")
        if (wav.isFile && wav.lastModified() >= c2.lastModified()) return wav
        val script = File(c2.parentFile, "satme_codec2.py")
        val contenu = TelemesurePc::class.java.getResourceAsStream("/genesis/satme_codec2.py")?.use { it.readBytes() } ?: return null
        if (!script.isFile || !script.readBytes().contentEquals(contenu)) script.writeBytes(contenu)
        for (py in if (OutilsPc.windows) listOf("python", "py") else listOf("python3", "python")) {
            val ok = runCatching {
                val pr = ProcessBuilder(py, script.absolutePath, c2.absolutePath, wav.absolutePath).redirectErrorStream(true).start()
                pr.inputStream.readBytes()
                pr.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && pr.exitValue() == 0
            }.getOrDefault(false)
            if (ok && wav.isFile) return wav
        }
        return null
    }

    private fun jsonGenesis(): JSONObject {
        val em = emetteurGenesis()
        val o = genesis.json().put("option", genesisActif).put("pourPassage", genesisPourPassage).put("source", genesisSource)
            .put("choisi", genesisProfilChoisi).put("profilNom", genesisProfil().nom).put("captureOption", genesisCapture)
            .put("transcritOption", genesisTranscrit).put("transcritErreur", voixErreur)
            .put("essaiEnCours", essaiGenesisEnCours).put("ssdvAfsk", ssdvAfsk)
            .put("messagesVoix", JSONArray(fr.f4ioz.satcombo.telemesure.AssemblageVoix.liste(dossier, 12)))
            .put("relecture", relecture)
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
        // The option on the SDR, or a campaign's SSDV pass (unless the rig's 9600 output already feeds the decoder).
        val viaIq = (paquetsActif && paquetsSource == SOURCE_SDR) || (paquetsPourPassage && paquets.sourceEnMarche != RecepteurPaquets.SOURCE_SON)
        if (!viaIq) { if (paquets.sourceEnMarche == RecepteurPaquets.SOURCE_IQ) paquets.arrete(); return }
        if (!paquets.actif) paquets.demarre(fr.f4ioz.satcombo.sdr.Dsp.RTL_RATE.toDouble())
        fictif = essai
        paquets.debit = debitVoulu()
        if (iq16 != null) paquets.iq16(iq16, n, decalageHz) else if (iq != null) paquets.iq(iq, n, decalageHz)
    }

    /**
     * A rig's 9600 output (the TS-2000's ACC2, its external TNC speed at
     * 9600 bps), from the Son tab's card or one opened for it. Receive only:
     * nothing here keys the rig.
     */
    private fun sonPaquets(pcm: ShortArray, n: Int, rate: Int, essai: Boolean) {
        if (!paquetsActif || paquetsSource != SOURCE_SON) return
        if (paquets.sourceEnMarche != RecepteurPaquets.SOURCE_SON) { paquets.arrete(); paquets.demarreSon() }
        fictifSon = essai
        paquets.debit = debitVoulu()
        paquets.audio(pcm, n, rate)
    }

    /**
     * The packets' source as chosen: the decoder started on the sound, the
     * card opened (or closed) for it. Called by each setting and once at the
     * start; opens nothing while the option is off or on the SDR.
     */
    @Synchronized
    fun appliqueSonPaquets() {
        val son = paquetsActif && paquetsSource == SOURCE_SON
        val nom = paquetsCarte.takeIf { son && it.isNotBlank() }
        if (carteOuverte != null && carteOuverte != nom) {
            runCatching { fermeCarte?.invoke() }
            fermeCarte = null; carteOuverte = null
        }
        if (!son) { if (paquets.sourceEnMarche == RecepteurPaquets.SOURCE_SON) paquets.arrete(); erreurCarte = ""; return }
        if (paquets.sourceEnMarche != RecepteurPaquets.SOURCE_SON) { paquets.arrete(); paquets.demarreSon() }
        if (nom == null) { erreurCarte = ""; return }
        if (carteOuverte == nom) return
        val (f, err) = runCatching { ouvreCarte(nom) { pcm, n, rate -> sonPaquets(pcm, n, rate, false) } }.getOrElse { null to (it.message ?: "carte son") }
        if (f == null) { erreurCarte = err.ifBlank { "carte son indisponible" }; return }
        fermeCarte = f; carteOuverte = nom; erreurCarte = ""
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
                val bits = fr.f4ioz.satcombo.telemesure.G3ruh.bits(trames) + fr.f4ioz.satcombo.telemesure.Geoscan.bits(geo)
                essaiPaquets.debit = 9_600
                if (paquetsSource == SOURCE_SON) {
                    // As a TS-2000's 9600 output on a 48 kHz card: its discriminator upside down, a DC offset, the card's clock off.
                    val son = fr.f4ioz.satcombo.telemesure.SignalPaquets.bandeBase(bits, 48_000, 9_600, 16.0, horloge(), inverse = true, dc = 0.05, horlogePpm = 120.0)
                    var k = 0
                    val t0 = horloge()
                    while (k < son.size) {
                        val m = minOf(4_096, son.size - k); essaiPaquets.traiteSon(son.copyOfRange(k, k + m), m, 48_000); k += m
                        rythme(essaiPaquets.sonde, t0, k.toLong(), 48_000.0)
                    }
                    return@Thread
                }
                val (re, im) = fr.f4ioz.satcombo.telemesure.SignalPaquets.baseband(bits, fs, 9_600, 1_800.0, 16.0, horloge())
                var k = 0
                val t0 = horloge()
                while (k < re.size) {
                    val m = minOf(8_192, re.size - k)
                    essaiPaquets.traiteBaseband(re.copyOfRange(k, k + m), im.copyOfRange(k, k + m), m, fs); k += m
                    rythme(essaiPaquets.sonde, t0, k.toLong(), fs)
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
            .put("source", paquetsSource).put("carte", paquetsCarte).put("carteOuverte", carteOuverte ?: "").put("erreurCarte", erreurCarte)
            .put("cartes", JSONArray(if (paquetsSource == SOURCE_SON) runCatching { cartes() }.getOrDefault(emptyList()) else emptyList()))
        if (em != null) o.put("emetteur", JSONObject().put("debit", em.first).put("description", em.second).put("mode", em.third)
            .put("note", RecepteurPaquets.noteMode(em.third) ?: ""))
        o.put("etat", JSONObject().put("actif", e.actif).put("bits", e.bits).put("trames", e.trames).put("crcFaux", e.crcFaux)
            .put("afcHz", Math.round(e.afcHz)).put("niveauDb", Math.round(e.niveauDb * 10) / 10.0).put("plancherDb", Math.round(e.plancherDb * 10) / 10.0)
            .put("presence", e.presence).put("signal", e.signal).put("perdus", e.perdus).put("derniereMs", e.derniereMs)
            .put("source", e.source).put("crete", Math.round(e.crete * 1000) / 1000.0).put("rate", e.rate))
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
        // The views open: this test's tap from its first sample (it goes at the air's pace then).
        if (horloge() - visuLuMs < SondeSignal.BAIL_MS) d.sonde.abonne()
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
                val t0 = horloge()
                while (k < audio.size) {
                    val n = minOf(4096, audio.size - k)
                    d.traite(audio.copyOfRange(k, k + n), n); k += n
                    rythme(d.sonde, t0, k.toLong(), Dsp.AUDIO_RATE.toDouble())
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
            "paquets/source" -> paquetsSource = q["v"].orEmpty()
            "paquets/carte" -> paquetsCarte = q["v"].orEmpty()
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
            "genesis/transcrit" -> genesisTranscrit = q["on"] == "1"
            "genesis/essai" -> if (!lanceEssaiGenesis()) return erreur("un essai est déjà en cours")
            // The satellite's recordings read again (receive only): the pictures and voice they hold.
            "genesis/relire-sat" -> {
                val s = q["sat"]?.takeIf { it.isNotBlank() }?.let { it to (q["norad"]?.toIntOrNull() ?: 0) } ?: suiviGenesis()
                    ?: return erreur("aucun satellite suivi")
                if (enregistrementsDe(s.first).isEmpty()) return erreur("aucun enregistrement de " + s.first)
                if (!relitEnregistrements(s.first, s.second)) return erreur("une relecture est déjà en cours")
            }
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

    // ---------------------------------------------------------------- the signal views (« modem scope »)

    /** The APRS decoder's tap (the Son tab's card), for the views. */
    @Volatile var sondeAfsk: () -> SondeSignal? = { null }

    /** The views' sources: each a key, a name, and its taps (the receiver's own, its test's: true). */
    private fun sourcesVisu(): List<Triple<String, String, List<Pair<SondeSignal, Boolean>>>> {
        val es = essai
        return listOf(
            Triple("paquets", "Paquets FSK · GMSK", listOf(paquets.sonde to false, essaiPaquets.sonde to true)),
            Triple("genesis", "SSDV en BLU · GENESIS", listOf(genesis.sonde to false, essaiGenesis.sonde to true)),
            Triple("funcube", "FUNcube · BPSK 1200", listOfNotNull(leo?.sonde?.let { it to false }, es?.takeIf { it.profil == Profil.FUNCUBE }?.sonde?.let { it to true })),
            Triple("balise", "Balise QO-100 · BPSK 400", listOfNotNull(canaux?.balise()?.second?.sonde?.let { it to false }, es?.takeIf { it.profil == Profil.QO100 }?.sonde?.let { it to true })),
            Triple("afsk", "APRS · AFSK 1200", listOfNotNull(runCatching { sondeAfsk() }.getOrNull()?.let { it to false })),
        )
    }
    @Volatile private var visuLuMs = Long.MIN_VALUE / 2

    @Volatile private var visuCache: Pair<Long, Pair<String, JSONObject>>? = null

    /**
     * The views' reading, `GET /api/telemesure/visu?src=` (« auto » or a
     * source's key): the taps of what is looked at subscribed (« auto »: all
     * of them, the one that moved last shown), the sources with how lately
     * each moved. At most ~6 readings a second are worked out; between, the
     * last one is served again. Receive only: nothing here touches a radio.
     */
    fun visu(q: Map<String, String>): JSONObject {
        val voulu = q["src"].orEmpty().ifBlank { "auto" }
        val now = horloge()
        visuCache?.let { (t, c) -> if (c.first == voulu && now - t < VISU_MIN_MS) return c.second }
        visuLuMs = now
        val sources = sourcesVisu()
        val vues = if (voulu == "auto") sources else sources.filter { it.first == voulu }
        for (s in vues) for ((sd, _) in s.third) sd.abonne()
        val liste = JSONArray()
        var meilleur: Pair<String, Pair<SondeSignal, Boolean>>? = null
        var meilleurMs = 0L
        for ((cle, nom, l) in sources) {
            val p = l.maxByOrNull { it.first.vivant() }
            val v = p?.first?.vivant() ?: 0L
            liste.put(JSONObject().put("cle", cle).put("nom", nom).put("depuis", if (v > 0) now - v else -1))
            if (p != null && vues.any { it.first == cle } && (meilleur == null || v > meilleurMs)) { meilleur = cle to p; meilleurMs = v }
        }
        val o = JSONObject().put("ok", true).put("src", voulu).put("sources", liste)
        meilleur?.let { (cle, p) ->
            o.put("choisi", cle)
            if (p.first.ecrits > 0) o.put("visu", p.first.cliche().json(now).put("essai", p.second))
        }
        visuCache = now to (voulu to o)
        return o
    }

    /**
     * A test's pace: while its views are looked at, the made-up signal goes
     * at the speed of the air (the views move as they would), else as fast
     * as it can (the tests, nobody looking).
     */
    private fun rythme(sd: SondeSignal, debutMs: Long, echantillons: Long, fs: Double) {
        if (!sd.actif) return
        val du = (echantillons * 1000 / fs).toLong() - (horloge() - debutMs)
        if (du > 5) runCatching { Thread.sleep(minOf(du, 500L)) }
    }

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
        const val CLE_PAQUETS_SOURCE = "pc_tlm_paquets_source"
        const val CLE_PAQUETS_CARTE = "pc_tlm_paquets_carte"
        const val CLE_SIDS = "pc_tlm_sids"
        const val CLE_GENESIS = "pc_tlm_genesis"
        const val CLE_GENESIS_SOURCE = "pc_tlm_genesis_source"
        const val CLE_GENESIS_PROFIL = "pc_tlm_genesis_profil"
        const val CLE_GENESIS_CAPTURE = "pc_tlm_genesis_capture"
        const val CLE_GENESIS_TRANSCRIT = "pc_tlm_genesis_transcrit"
        /** The most messages ended waiting to be written down (a pass holds a handful). */
        const val MAX_VOIX_FINIES = 20
        const val SOURCE_CARTE = "carte"
        const val SOURCE_SDR = "sdr"
        /** The packets from a rig's 9600 output on a sound card. */
        const val SOURCE_SON = "son"
        /** A recording read again: 400 MB (a pass at 48 kHz is some 60 MB). */
        const val MAX_RELECTURE = 400L * 1024 * 1024
        /** The views' readings at most this often (~6 a second), whatever the page asks. */
        const val VISU_MIN_MS = 160L

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
