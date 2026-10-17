/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.AdaptationModcod
import fr.f4ioz.satcombo.datv.BoucleNiveau
import fr.f4ioz.satcombo.datv.CarteStation
import fr.f4ioz.satcombo.datv.InserteurCarte
import fr.f4ioz.satcombo.datv.LigneChat
import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.DecalageHackrf
import fr.f4ioz.satcombo.datv.DemandeEmission
import fr.f4ioz.satcombo.datv.Emetteur
import fr.f4ioz.satcombo.datv.PlanInterieur
import fr.f4ioz.satcombo.datv.PlanQo100
import fr.f4ioz.satcombo.datv.ReglesEmission
import fr.f4ioz.satcombo.datv.s2.ModulateurDvbs2
import fr.f4ioz.satcombo.datv.s2.Modcod
import fr.f4ioz.satcombo.datv.s2.Rolloff
import fr.f4ioz.satcombo.datv.s2.SourceTs
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.Bladerf
import fr.f4ioz.satcombo.sdr.BladerfEmission
import fr.f4ioz.satcombo.sdr.CorrectionsIq
import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.HackrfEmission
import fr.f4ioz.satcombo.sdr.Melangeur
import fr.f4ioz.satcombo.sdr.PlutoEmission
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * DATV transmission to QO-100's wide band transponder with an ADALM-PLUTO:
 * a picture (SatMe's test card with the callsign, an image, or a camera)
 * made into an MPEG transport stream by ffmpeg at exactly the rate the
 * MODCOD carries, modulated in DVB-S2 here ([ModulateurDvbs2]) and sent by
 * [PlutoEmission]. The MiniTiouner (DATV tab) checks it from the downlink.
 *
 * A HackRF One ([HackrfEmission]) sends too, for tests only: the indoor
 * test ([PlanInterieur]) and the dummy load, never QO-100. The Pluto does
 * the indoor test as well, at −40 dB at most. A bladeRF 2.0
 * ([BladerfEmission], an AD9361 as the Pluto) follows the Pluto's rules.
 *
 * **Only on the operator's word**: a start needs the page's confirmation;
 * nothing transmits at startup and nothing of a transmission is kept in the
 * settings but its parameters. Every rule of [ReglesEmission] applies; the
 * page must keep saying it is there ([vie]), else the transmission stops.
 */
class EmissionDatvPc(
    private val stockage: Stockage,
    private val dossierImages: File,
    /** The station's callsign and locator (Réglages). */
    private val indicatif: () -> String,
    private val locator: () -> String = { "" },
    /** Where the Pluto answers (the SDR tab's setting). */
    private val plutoHote: () -> String = { fr.f4ioz.satcombo.sdr.PlutoSdr.HOTE },
    private val plutoPort: () -> Int = { fr.f4ioz.satcombo.sdr.PlutoSdr.PORT },
    /** Why the Pluto is busy (the SDR receiving with it), or null. */
    private val plutoOccupe: () -> String? = { null },
    /** Why the bladeRF is busy (the SDR receiving with it), or null. */
    private val bladerfOccupe: () -> String? = { null },
    /** bladeRF-cli's path (the SDR tab's setting, else the PATH), or null when missing. */
    private val bladerfProgramme: () -> String? = { OutilsPc.trouve("bladeRF-cli") },
    /** What the MiniTiouner sees now, for the closed loop. */
    private val reception: () -> Reception? = { null },
    /** The transport stream's maker: ffmpeg here, something else in the tests. */
    private val fabrique: FabriqueTs = FfmpegTs(),
    /** The HackRF's tools (hackrf_transfer, hackrf_info) when no folder is set: the PATH. */
    private val outilParDefaut: (String) -> String? = { OutilsPc.trouve(it) },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Silence from the page longer than this: stopped. */
    private val vieMs: Long = 15_000L,
    /** The operator's name (Réglages), for the station card. */
    private val nom: () -> String = { "" },
    /** The software named in the station card. */
    private val logiciel: String = "SatMe-PC",
    /** The talkback offered when none is set: the cockpit's narrow band frequency, or null. */
    private val retourParDefaut: () -> Long? = { null },
    /** The MODCOD's adaptation for each transmission (its timings shortened in the tests). */
    private val nouvelleAdaptation: () -> AdaptationModcod = { AdaptationModcod() },
) {
    /**
     * The MiniTiouner's view: locked, MER, where it is tuned (sky kHz), the
     * service's name; the station card's callsign (a recent one), packets
     * lost now (continuity or transport errors).
     */
    data class Reception(val verrouille: Boolean, val merDb: Double?, val cielKhz: Long, val service: String,
                         val carteIndicatif: String = "", val pertes: Boolean = false)

    /** What makes the transport stream: started with its exact rate, gives a byte stream. */
    interface FabriqueTs {
        fun demarre(p: ParametresTs): Processus
    }

    interface Processus {
        val sortie: InputStream
        fun arrete()
        val vivant: Boolean
        fun erreur(): String = ""
    }

    data class ParametresTs(
        val debitTs: Long,
        val source: String,
        val image: File?,
        val camera: String,
        val indicatif: String,
        val hevc: Boolean,
        val son: Boolean,
        /** Text and the mark over the picture (see [IncrustationDatvPc]); null: none, the command unchanged. */
        val incrustation: IncrustationDatvPc.IncrustationTs? = null,
    )

    // ------------------------------------------------------------ settings (parameters only)

    var modcodTexte: String
        get() = stockage.getString("datv_tx_modcod", "QPSK 2/3") ?: "QPSK 2/3"
        set(v) { if (Modcod.de(v) != null) stockage.edit().putString("datv_tx_modcod", v).apply() }
    var srKs: Int
        get() = stockage.getInt("datv_tx_sr", 333).takeIf { it in Datv.DEBITS } ?: 333
        set(v) { if (v in Datv.DEBITS) stockage.edit().putInt("datv_tx_sr", v).apply() }
    var rolloff: Rolloff
        get() = Rolloff.entries.firstOrNull { it.name == stockage.getString("datv_tx_ro", "A25") } ?: Rolloff.A25
        set(v) { stockage.edit().putString("datv_tx_ro", v.name).apply() }
    var courtes: Boolean
        get() = stockage.getBoolean("datv_tx_courtes", false)
        set(v) { stockage.edit().putBoolean("datv_tx_courtes", v).apply() }
    var pilotes: Boolean
        get() = stockage.getBoolean("datv_tx_pilotes", true)
        set(v) { stockage.edit().putBoolean("datv_tx_pilotes", v).apply() }
    /** Uplink frequency (kHz). */
    var frequenceKhz: Long
        get() = stockage.getLong("datv_tx_khz", 2_409_750L)
        set(v) { stockage.edit().putLong("datv_tx_khz", v).apply() }
    var gainDb: Double
        get() = stockage.getFloat("datv_tx_gain", ReglesEmission.GAIN_DEFAUT_DB.toFloat()).toDouble()
        set(v) { stockage.edit().putFloat("datv_tx_gain", v.coerceIn(ReglesEmission.GAIN_MIN_DB, 0.0).toFloat()).apply() }
    /** The highest TX gain ever applied (Réglages). */
    var gainMaxDb: Double
        get() = stockage.getFloat("datv_tx_gain_max", -10f).toDouble()
        set(v) { stockage.edit().putFloat("datv_tx_gain_max", v.coerceIn(ReglesEmission.GAIN_MIN_DB, 0.0).toFloat()).apply() }
    var dureeS: Int
        get() = ReglesEmission.dureeS(stockage.getInt("datv_tx_duree", ReglesEmission.DUREE_DEFAUT_S))
        set(v) { stockage.edit().putInt("datv_tx_duree", ReglesEmission.dureeS(v)).apply() }
    /** "mire", "animee" (the test card with a moving picture and a beep), "image", "video" (a file, looped) or "camera". */
    var source: String
        get() = stockage.getString("datv_tx_source", "mire").takeIf { it in SOURCES } ?: "mire"
        set(v) { if (v in SOURCES) stockage.edit().putString("datv_tx_source", v).apply() }
    var image: String
        get() = stockage.getString("datv_tx_image", "") ?: ""
        set(v) { stockage.edit().putString("datv_tx_image", v.trim()).apply() }
    var video: String
        get() = stockage.getString("datv_tx_video", "") ?: ""
        set(v) { stockage.edit().putString("datv_tx_video", v.trim()).apply() }
    var camera: String
        get() = stockage.getString("datv_tx_camera", "") ?: ""
        set(v) { stockage.edit().putString("datv_tx_camera", v.trim()).apply() }
    var hevc: Boolean
        get() = stockage.getBoolean("datv_tx_hevc", false)
        set(v) { stockage.edit().putBoolean("datv_tx_hevc", v).apply() }
    var son: Boolean
        get() = stockage.getBoolean("datv_tx_son", true)
        set(v) { stockage.edit().putBoolean("datv_tx_son", v).apply() }
    var emetteur: Emetteur
        get() = Emetteur.de(stockage.getString("datv_tx_emetteur", Emetteur.PLUTO.cle))
        set(v) { stockage.edit().putString("datv_tx_emetteur", v.cle).apply() }
    /** The HackRF's TX VGA gain (dB), asked; applied at [ReglesEmission.HACKRF_VGA_MAX] at most. */
    var vgaHackrf: Int
        get() = stockage.getInt("datv_tx_hackrf_vga", 0).coerceIn(0, ReglesEmission.HACKRF_VGA_MAX)
        set(v) { stockage.edit().putInt("datv_tx_hackrf_vga", v.coerceIn(0, ReglesEmission.HACKRF_VGA_MAX)).apply() }
    /** The bladeRF's attenuation (dB, ≤ 0), as the Pluto's gain; low by default. */
    var gainBladerf: Double
        get() = stockage.getFloat("datv_tx_bladerf_gain", GAIN_BLADERF_DEFAUT_DB.toFloat()).toDouble()
        set(v) { if (!v.isNaN()) stockage.edit().putFloat("datv_tx_bladerf_gain", v.coerceIn(BladerfEmission.GAIN_MIN_DB.toDouble(), 0.0).toFloat()).apply() }
    /** Where hackrf_transfer is (the program or its folder); empty: the PATH. */
    var hackrfOutil: String
        get() = stockage.getString("datv_tx_hackrf_outil", "") ?: ""
        set(v) { stockage.edit().putString("datv_tx_hackrf_outil", v.trim()).apply() }
    /** The HackRF's LO tuned beside the signal ([DecalageHackrf]): its leakage out of the occupied band. */
    var hackrfDecalage: Boolean
        get() = stockage.getBoolean("datv_tx_hackrf_decalage", true)
        set(v) { stockage.edit().putBoolean("datv_tx_hackrf_decalage", v).apply() }
    /** « Adapter le MODCOD à la réception »: off unless the operator checks it. */
    var adaptOn: Boolean
        get() = stockage.getBoolean("datv_tx_adapt", false)
        set(v) { stockage.edit().putBoolean("datv_tx_adapt", v).apply() }
    /** The range the adaptation may use: its most robust and its most efficient MODCOD. */
    var adaptMin: String
        get() = stockage.getString("datv_tx_adapt_min", "QPSK 1/4")?.takeIf { Modcod.de(it) != null } ?: "QPSK 1/4"
        set(v) { if (Modcod.de(v) != null) stockage.edit().putString("datv_tx_adapt_min", v).apply() }
    var adaptMax: String
        get() = stockage.getString("datv_tx_adapt_max", "8PSK 3/4")?.takeIf { Modcod.de(it) != null } ?: "8PSK 3/4"
        set(v) { if (Modcod.de(v) != null) stockage.edit().putString("datv_tx_adapt_max", v).apply() }
    /** The margin kept above the MODCOD's threshold (dB). */
    var adaptMarge: Double
        get() = stockage.getFloat("datv_tx_adapt_marge", 2f).toDouble().coerceIn(0.5, 10.0)
        set(v) { if (!v.isNaN()) stockage.edit().putFloat("datv_tx_adapt_marge", v.coerceIn(0.5, 10.0).toFloat()).apply() }
    /** The indoor test's frequency (kHz). */
    var frequenceInterieurKhz: Long
        get() = stockage.getLong("datv_tx_int_khz", PlanInterieur.DEFAUT_KHZ)
        set(v) { stockage.edit().putLong("datv_tx_int_khz", v).apply() }
    var dureeInterieurS: Int
        get() = PlanInterieur.dureeS(stockage.getInt("datv_tx_int_duree", PlanInterieur.DUREE_DEFAUT_S))
        set(v) { stockage.edit().putInt("datv_tx_int_duree", PlanInterieur.dureeS(v)).apply() }

    // The station card carried in the stream (see [fr.f4ioz.satcombo.datv.CarteTs]).
    var carteOn: Boolean
        get() = stockage.getBoolean("datv_tx_carte", true)
        set(v) { stockage.edit().putBoolean("datv_tx_carte", v).apply() }
    var qth: String
        get() = stockage.getString("datv_tx_qth", "") ?: ""
        set(v) { stockage.edit().putString("datv_tx_qth", v.trim().take(48)).apply(); majCarte() }
    var puissance: String
        get() = stockage.getString("datv_tx_puissance", "") ?: ""
        set(v) { stockage.edit().putString("datv_tx_puissance", v.trim().take(24)).apply(); majCarte() }
    var antenne: String
        get() = stockage.getString("datv_tx_antenne", "") ?: ""
        set(v) { stockage.edit().putString("datv_tx_antenne", v.trim().take(48)).apply(); majCarte() }
    var message: String
        get() = stockage.getString("datv_tx_message", "") ?: ""
        set(v) { stockage.edit().putString("datv_tx_message", v.trim().take(fr.f4ioz.satcombo.datv.CarteTs.CHAT_MAX)).apply(); majCarte() }

    /**
     * Talkback on QO-100's narrow band (downlink Hz), 0: none. Never set: the
     * cockpit's frequency ([retourParDefaut]); cleared by the operator: none.
     */
    var retourHz: Long
        get() = stockage.getLong("datv_tx_retour_hz", -1L).let { if (it < 0) retourParDefaut() ?: 0L else it }
        set(v) { stockage.edit().putLong("datv_tx_retour_hz", v.coerceAtLeast(0L)).apply(); majCarte() }
    val retourDonne: Boolean get() = stockage.getLong("datv_tx_retour_hz", -1L) >= 0
    var retourMode: String
        get() = CarteStation.modeRetour(stockage.getString("datv_tx_retour_mode", "USB") ?: "USB")
        set(v) { stockage.edit().putString("datv_tx_retour_mode", CarteStation.modeRetour(v)).apply(); majCarte() }

    /** The card for a transmission on [khz] in [m]. */
    fun carteStation(khz: Long, m: Modcod, sr: Int = srKs): CarteStation = CarteStation(
        indicatif = indicatif().trim().uppercase(), locator = locator().trim().uppercase(), nom = nom().trim(), qth = qth,
        puissance = puissance, antenne = antenne, logiciel = logiciel, frequenceKhz = khz,
        modcod = m.constellation.libelle + " " + m.taux.libelle, srKs = sr, message = message,
        retourHz = retourHz, retourMode = retourMode)

    private fun majCarte() {
        val i = inserteur ?: return
        val e = etat
        if (e.emet) i.carte = carteStation(e.frequenceKhz, modcodEnCours ?: modcod(), srEnCours)
    }

    /** A chat line sent, and when. */
    data class Dit(val seq: Int, val texte: String, val ms: Long)
    private val dits = ArrayList<Dit>()
    private var seqChat = -1
    private var dernierDitMs = 0L

    /**
     * A chat line in the stream being sent: only while a transmission the
     * operator started runs (typing never starts one), 140 characters, one line every 2 s.
     * Null once queued, else why not.
     */
    @Synchronized
    fun dis(texte: String): String? {
        val i = inserteur
        if (!etat.emet || i == null) return "aucune émission en cours : le chat passe dans le flux émis"
        val t = texte.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return "message vide"
        if (t.codePointCount(0, t.length) > fr.f4ioz.satcombo.datv.CarteTs.CHAT_MAX) return "140 caractères au plus"
        val maintenant = horloge()
        if (maintenant - dernierDitMs < CHAT_INTERVALLE_MS) return "un message toutes les 2 s au plus"
        if (seqChat < 0) seqChat = ((maintenant / 1000) and 0xFFFF).toInt()
        seqChat = (seqChat + 1) and 0xFFFF
        i.ajoute(LigneChat(seqChat, indicatif().trim().uppercase(), t))
        dernierDitMs = maintenant
        synchronized(dits) { dits += Dit(seqChat, t, maintenant); while (dits.size > 30) dits.removeAt(0) }
        return null
    }

    fun dits(): List<Dit> = synchronized(dits) { dits.toList() }

    /** One of the HackRF's tools: in the folder set, else where [outilParDefaut] finds it; null when missing. */
    fun outilHackrf(nom: String): String? {
        val r = hackrfOutil
        if (r.isNotEmpty()) {
            val f = File(r)
            val exe = if (OutilsPc.windows) "$nom.exe" else nom
            val c = when {
                f.isDirectory -> File(f, exe)
                f.isFile && f.name.substringBefore('.') == nom -> f
                f.isFile -> File(f.parentFile, exe)
                else -> return null
            }
            return c.takeIf { it.isFile && (OutilsPc.windows || it.canExecute()) }?.path
        }
        return outilParDefaut(nom)
    }

    /** What to do when the HackRF's tools are missing. */
    fun conseilHackrf(): String =
        if (OutilsPc.windows) "hackrf_transfer.exe introuvable : installer les outils HackRF pour Windows (et le pilote WinUSB du HackRF), puis donner leur dossier dans « Outils HackRF »"
        else "hackrf_transfer introuvable : installer hackrf (sudo apt install hackrf), ou donner son chemin dans « Outils HackRF »"

    /** The last read of hackrf_info: what it found, or why not. */
    data class InfoHackrf(val cartes: List<HackrfEmission.Carte> = emptyList(), val message: String = "", val lu: Boolean = false)
    @Volatile var infoHackrf = InfoHackrf(); private set

    /**
     * Runs `hackrf_info` (read-only: it lists the boards) and keeps what it
     * says. Never while transmitting.
     */
    fun detecteHackrf(): InfoHackrf {
        if (etat.emet) return infoHackrf
        val prog = outilHackrf("hackrf_info")
        val i = if (prog == null) InfoHackrf(message = "hackrf_info introuvable : " + conseilHackrf().substringAfter(": "), lu = true)
        else runCatching {
            val pr = ProcessBuilder(prog).redirectErrorStream(true).start()
            runCatching { pr.outputStream.close() }
            val lu = StringBuilder()
            val t = thread(isDaemon = true, name = "satme-hackrf-info") { runCatching { lu.append(pr.inputStream.bufferedReader().readText()) } }
            if (!pr.waitFor(5, TimeUnit.SECONDS)) { pr.destroyForcibly(); InfoHackrf(message = "hackrf_info ne répond pas", lu = true) }
            else {
                t.join(1000)
                val texte = synchronized(lu) { lu.toString() }
                val c = HackrfEmission.lisInfo(texte)
                InfoHackrf(c, if (c.isEmpty()) (HackrfEmission.traduis(texte.lines().firstOrNull { HackrfEmission.traduis(it) != null }.orEmpty())
                    ?: "aucun HackRF trouvé (le brancher)") else "", true)
            }
        }.getOrElse { InfoHackrf(message = "hackrf_info : " + (it.message ?: "erreur"), lu = true) }
        infoHackrf = i
        return i
    }

    /** What to do when bladeRF-cli is missing. */
    fun conseilBladerf(): String =
        if (OutilsPc.windows) "bladeRF-cli.exe introuvable ; et sous Windows SatMe ne sait pas encore lui passer les échantillons (tube nommé) : bladeRF sous Linux"
        else "bladeRF-cli introuvable : installer bladerf (sudo apt install bladerf), ou donner son chemin dans l'onglet SDR"

    /** The last read of `bladeRF-cli -e info`: the board, or why not. */
    data class InfoBladerf(val carte: Bladerf.Carte? = null, val message: String = "", val lu: Boolean = false)
    @Volatile var infoBladerf = InfoBladerf(); private set

    /** Reads what the bladeRF is (read-only: `bladeRF-cli -e info -e version`). Never while it transmits or receives. */
    fun detecteBladerf(): InfoBladerf {
        if (etat.emet) return infoBladerf
        bladerfOccupe()?.let { return InfoBladerf(infoBladerf.carte, it, true).also { i -> infoBladerf = i } }
        val prog = bladerfProgramme()
        val i = if (prog == null) InfoBladerf(message = conseilBladerf(), lu = true)
            else Bladerf.detecte(prog).let { (c, m) -> InfoBladerf(c, m, true) }
        infoBladerf = i
        return i
    }

    fun modcod(): Modcod = Modcod.de(modcodTexte, courtes, pilotes) ?: Modcod.de("QPSK 2/3", courtes, pilotes)!!

    /** The transport stream rate (bit/s) for the current parameters. */
    fun debitTs(): Long = modcod().debitTs(srKs * 1000.0).toLong()

    // ------------------------------------------------------------ the transmission

    data class Etat(
        val emet: Boolean = false,
        val chargeFictive: Boolean = false,
        val interieur: Boolean = false,
        val emetteur: Emetteur = Emetteur.PLUTO,
        val frequenceKhz: Long = 0L,
        val debitTs: Long = 0L,
        val raison: String = "",
        val erreur: String = "",
    )

    @Volatile var etat = Etat(); private set
    private var tx: EmetteurDatv? = null
    /** Why another transmission runs (SSTV), or null: DATV waits for it. */
    @Volatile var autreEmission: () -> String? = { null }
    /** The transmitters' I/Q corrections, each board its own (none: the samples as made). */
    @Volatile var corrections: CorrectionsIq? = null
    @Volatile private var processus: Processus? = null
    /** The modulator making the frames now: a new one for each MODCOD (its counts on the page). */
    @Volatile private var modulateur: ModulateurDvbs2? = null
    /** The one the transmitter reads (its shaping filter): the frames from [modulateur] once the MODCOD changed. */
    @Volatile private var sortie: ModulateurDvbs2? = null
    @Volatile private var flux: FluxTs? = null
    @Volatile private var inserteur: InserteurCarte? = null
    @Volatile private var modcodEnCours: Modcod? = null
    @Volatile private var srEnCours = 0
    @Volatile private var spsEnCours = 0
    @Volatile private var parametresEnCours: ParametresTs? = null
    /** The MODCOD fitted to our own reception, for the transmission running (or the last one). */
    @Volatile var adaptation: AdaptationModcod? = null; private set
    /** A MODCOD change under way (a second ffmpeg starting). */
    @Volatile private var commutation = false
    @Volatile private var derniereVie = 0L
    @Volatile private var generation = 0
    val boucle = BoucleNiveau()
    /** The beacon's MER, the last time the MiniTiouner held it (and when). */
    @Volatile var merBalise: Double? = null; private set
    @Volatile var merBaliseMs = 0L; private set

    /** The page is there (each second while transmitting). */
    fun vie() { derniereVie = horloge() }

    /**
     * Starts transmitting, on the operator's confirmed action only
     * ([confirme]), into a dummy load ([chargeFictive]), for the indoor test
     * ([interieur]) or to QO-100 (the Pluto only); null once transmitting,
     * else why not.
     */
    @Synchronized
    fun demarre(confirme: Boolean, chargeFictive: Boolean, interieur: Boolean = false): String? {
        if (!confirme) return "émission non confirmée"
        if (etat.emet) return "déjà en émission"
        autreEmission()?.let { return it }
        val qui = emetteur
        val hackrf = qui == Emetteur.HACKRF
        val blade = qui == Emetteur.BLADERF
        if (blade) bladerfOccupe()?.let { return it } else if (!hackrf) plutoOccupe()?.let { return it }
        val m = modcod()
        val khz = if (interieur) frequenceInterieurKhz else frequenceKhz
        val duree = if (interieur) dureeInterieurS else dureeS
        val gain = if (hackrf) vgaHackrf.toDouble() else if (blade) gainBladerf else gainDb
        val d = DemandeEmission(khz, srKs, m, rolloff, gain, duree, chargeFictive, indicatif(), interieur, qui)
        ReglesEmission.refus(d, gainMaxDb)?.let { return it }
        val programme = if (hackrf) outilHackrf("hackrf_transfer") ?: return conseilHackrf()
            else if (blade) bladerfProgramme() ?: return conseilBladerf() else ""
        // The HackRF's LO beside the signal: the band rules above stay on the signal itself.
        val plan = if (hackrf && hackrfDecalage) DecalageHackrf.plan(khz, srKs, rolloff.alpha, interieur)
            ?: return DecalageHackrf.refus(khz, srKs, rolloff.alpha, interieur) else null
        // The bladeRF: 4 samples a symbol at least (kept; the frames lost at 1 MS indoors do not depend on it, 4 to 8 tried).
        val sps = plan?.sps ?: if (blade) maxOf(4, ReglesEmission.sps(srKs)) else ReglesEmission.sps(srKs)
        val debit = srKs * 1000L * sps
        val debitTs = m.debitTs(srKs * 1000.0).toLong()
        val img = when (source) {
            "image" -> File(image).takeIf { it.isFile } ?: return "image introuvable : " + image.ifBlank { "(aucune)" }
            "video" -> File(video).takeIf { it.isFile } ?: return "vidéo introuvable : " + video.ifBlank { "(aucune)" }
            "camera" -> null.also { if (camera.isBlank()) return "aucune caméra choisie" }
            else -> mire()
        }
        val gen = ++generation
        // The station card and the chat in place of null packets: the rate unchanged.
        val ins = if (carteOn) InserteurCarte(horloge).also { it.carte = carteStation(khz, m) } else null
        val f = FluxTs(maxOf(64, (debitTs / 1504 * 2).toInt()), ins)
        val inc = runCatching { incrustation?.pour(debitTs, source, d.indicatif, locator(), File(dossierImages, "incrustation-datv.png")) }.getOrNull()
        val pts = ParametresTs(debitTs, source, img, camera, d.indicatif.trim().uppercase(), hevc, son, inc)
        val p = runCatching { fabrique.demarre(pts) }
            .getOrElse { return "ffmpeg ne démarre pas : " + (it.message ?: "") }
        processus = p; flux = f
        thread(isDaemon = true, name = "satme-datv-tx-ts") { f.lit(p.sortie) { generation == gen && processus === p } }
        val mod = ModulateurDvbs2(m, rolloff, sps, f)
        modulateur = mod; sortie = mod
        derniereVie = horloge()
        val e: EmetteurDatv
        val refus: String?
        if (hackrf) {
            val hx = HackrfEmission(programme, ReglesEmission.HACKRF_VGA_MAX)
            val r = HackrfEmission.Reglage(
                frequenceHz = plan?.loHz ?: (khz * 1000L), debit = debit, vgaDb = ReglesEmission.vgaHackrf(gain), dureeMs = duree * 1000L,
                bandeHz = plan?.bandeHz ?: Math.round(PlanQo100.occupeKhz(srKs, rolloff.alpha) * 1000), plafondVgaDb = ReglesEmission.HACKRF_VGA_MAX,
                paires = paires(debit), serie = infoHackrf.cartes.singleOrNull()?.serie.orEmpty(), garde = { garde(gen) },
                correction = { s -> corrections?.aAppliquer(CorrectionsIq.HACKRF, s) })
            // The baseband moved up to the wanted frequency, before the 8-bit conversion.
            val mel = plan?.let { Melangeur(it.decalageHz.toDouble(), debit.toDouble()) }
            refus = hx.demarre(r) { b -> mod.remplis(b, b.size / 2); mel?.applique(b) }
            e = hx
        } else if (blade) {
            val bx = BladerfEmission(programme, gainMaxDb = minOf(gainMaxDb, 0.0))
            val r = BladerfEmission.Reglage(
                frequenceHz = khz * 1000L, debit = debit, gainDb = gain, dureeMs = duree * 1000L,
                plafondDb = when { chargeFictive -> ReglesEmission.GAIN_CHARGE_DB; interieur -> PlanInterieur.GAIN_PLUTO_DB; else -> 0.0 },
                paires = paires(debit), serie = infoBladerf.carte?.serie.orEmpty(), garde = { garde(gen) },
                correction = { s -> corrections?.aAppliquer(CorrectionsIq.BLADERF, s) })
            refus = bx.demarre(r) { b -> mod.remplis(b, b.size / 2) }
            e = bx
        } else {
            val px = PlutoEmission(plutoHote(), plutoPort(), gainMaxDb = minOf(gainMaxDb, 0.0))
            val r = PlutoEmission.Reglage(
                frequenceHz = khz * 1000L, debit = debit, gainDb = gainDb, dureeMs = duree * 1000L,
                plafondDb = when { chargeFictive -> ReglesEmission.GAIN_CHARGE_DB; interieur -> PlanInterieur.GAIN_PLUTO_DB; else -> 0.0 },
                paires = paires(debit),
                garde = { garde(gen) }, correction = { s -> corrections?.aAppliquer(CorrectionsIq.PLUTO, s) })
            refus = px.demarre(r) { b -> mod.remplis(b, b.size / 2) }
            e = px
        }
        if (refus != null) { generation++; p.arrete(); processus = null; return refus }
        tx = e
        inserteur = ins; modcodEnCours = m; srEnCours = srKs; spsEnCours = sps; parametresEnCours = pts
        adaptation = nouvelleAdaptation()
        etat = Etat(emet = true, chargeFictive = chargeFictive, interieur = interieur, emetteur = qui, frequenceKhz = khz, debitTs = debitTs)
        return null
    }

    /** Why the transmission must stop now, or null: the ffmpeg running now (it changes with the MODCOD). */
    private fun garde(gen: Int): String? {
        if (generation != gen) return "arrêtée"
        if (horloge() - derniereVie > vieMs) return "page SatMe fermée ou injoignable"
        val p = processus ?: return "arrêtée"
        return if (!p.vivant) "ffmpeg s'est arrêté" + p.erreur().let { if (it.isBlank()) "" else " : $it" } else null
    }

    /**
     * A new MODCOD while transmitting, the frequency, symbol rate, power and
     * transmitter unchanged: a second ffmpeg at the new rate, the new
     * modulator taking over once its stream flows (or after 3 s: dummy
     * frames meanwhile), then the old ffmpeg stopped. The receiver relocks.
     * Null once switched, else why not.
     */
    fun commute(m: Modcod): String? {
        val gen: Int
        val f: FluxTs
        val p: Processus
        val pts: ParametresTs
        synchronized(this) {
            if (!etat.emet || tx == null) return "aucune émission en cours"
            if (m.refus() != null) return m.refus()
            if (m == modcodEnCours) return null
            gen = generation
            val debitTs = m.debitTs(srEnCours * 1000.0).toLong()
            val avant = parametresEnCours ?: return "paramètres inconnus"
            // The overlay drawn again: the picture's size follows the new stream rate.
            val inc = avant.incrustation?.let { runCatching { incrustation?.pour(debitTs, avant.source, avant.indicatif, locator(), File(dossierImages, "incrustation-datv.png")) }.getOrNull() }
            pts = avant.copy(debitTs = debitTs, incrustation = inc)
            f = FluxTs(maxOf(64, (debitTs / 1504 * 2).toInt()), inserteur)
            p = runCatching { fabrique.demarre(pts) }.getOrElse { return "ffmpeg ne démarre pas : " + (it.message ?: "") }
            commutation = true
        }
        try {
            thread(isDaemon = true, name = "satme-datv-tx-ts") { f.lit(p.sortie) { generation == gen && (processus === p || commutation) } }
            // A frame's worth of the new stream before taking over.
            val fin = System.currentTimeMillis() + 3000
            val besoin = m.dfl / 1504 + 2
            while (System.currentTimeMillis() < fin && f.disponibles() < besoin && p.vivant && generation == gen) Thread.sleep(20)
            synchronized(this) {
                if (generation != gen || !etat.emet) { p.arrete(); return "émission arrêtée pendant le changement" }
                if (!p.vivant) { p.arrete(); return "ffmpeg ne démarre pas au nouveau débit" + p.erreur().let { if (it.isBlank()) "" else " : $it" } }
                val ancien = processus
                val s = sortie ?: run { p.arrete(); return "aucune émission en cours" }
                // Its frames follow the current one's at the next frame boundary.
                val nouveau = ModulateurDvbs2(m, s.rolloff, s.sps, f)
                s.suite = nouveau
                modulateur = nouveau
                flux = f; processus = p; parametresEnCours = pts; modcodEnCours = m
                ancien?.arrete()
                inserteur?.carte = carteStation(etat.frequenceKhz, m, srEnCours)
                etat = etat.copy(debitTs = pts.debitTs)
            }
            return null
        } finally {
            commutation = false
        }
    }

    /** Stops transmitting (the Pluto's LO powered down); the picture's maker stopped. */
    @Synchronized
    fun arrete(raison: String = "arrêt demandé") {
        val px = tx
        px?.arrete(raison)
        generation++
        processus?.arrete(); processus = null
        tx = null
        inserteur = null
        adaptation?.arrete()
        val pe = px?.etatCommun()
        if (etat.emet) etat = etat.copy(emet = false, raison = pe?.raison ?: raison, erreur = pe?.erreur.orEmpty())
    }

    /** A new gain while transmitting (clamped as at the start), kept for next time. */
    fun regleGain(db: Double): Double? {
        if (emetteur == Emetteur.BLADERF) gainBladerf = db else gainDb = db
        val px = tx
        // The HackRF takes its gain at the start; the Pluto's only goes to the Pluto, the bladeRF's to the bladeRF.
        return when (px) { is PlutoEmission -> px.regleGain(db); is BladerfEmission -> px.regleGain(db); else -> null }
    }

    /**
     * Each second: the transmission's end noticed (the Pluto stopped by
     * itself), the beacon's MER noted when the MiniTiouner holds it, and
     * the closed loop on our own signal.
     */
    fun tic() {
        val px = tx
        if (px != null && !px.etatCommun().emet && etat.emet) {
            val pe = px.etatCommun()
            synchronized(this) { arrete(pe.raison) }
            etat = etat.copy(raison = pe.raison, erreur = pe.erreur)
        }
        val r0 = reception()
        adapte(r0)
        val r = r0 ?: return
        val t = horloge()
        if (r.verrouille && r.merDb != null && kotlin.math.abs(r.cielKhz - Datv.BALISE_KHZ) < 100 && r.service != indicatif().trim().uppercase()) {
            merBalise = r.merDb; merBaliseMs = t
        }
        // The closed loop on the beacon: on QO-100 only (the Pluto, the bladeRF).
        if (!etat.emet || etat.chargeFictive || etat.interieur || !(px is PlutoEmission || px is BladerfEmission)) return
        val nous = r.verrouille && (r.service.equals(indicatif().trim(), true) ||
            kotlin.math.abs(r.cielKhz - (etat.frequenceKhz + PlanQo100.DECALAGE_KHZ)) < srKs / 2)
        val g = boucle.mesure(if (nous) r.merDb else null, merBalise, 0L, px.etatCommun().gainDb, t) ?: return
        px.regleGain(g)
    }

    /** Our own signal in what the receiver holds: locked, and our callsign as the service or on a recent station card. */
    fun nousRecoit(r: Reception?): Boolean {
        val ind = indicatif().trim().uppercase()
        if (r == null || !r.verrouille || ind.isEmpty()) return false
        return r.service.trim().equals(ind, true) || r.carteIndicatif.trim().equals(ind, true)
    }

    /**
     * The MODCOD fitted to our reception: only with the option checked,
     * while a transmission the operator started runs, on our own signal.
     * The MODCOD only: nothing else of the transmission changes.
     */
    private fun adapte(r: Reception?) {
        val a = adaptation ?: return
        if (!etat.emet || tx == null || commutation) return
        if (!adaptOn) { if (a.etat.actif) a.arrete(); return }
        val courant = modcodEnCours ?: return
        val min = Modcod.de(adaptMin, courant.courte, courant.pilotes) ?: return
        val max = Modcod.de(adaptMax, courant.courte, courant.pilotes) ?: return
        val nous = nousRecoit(r)
        val qo100 = !etat.chargeFictive && !etat.interieur && (tx is PlutoEmission || tx is BladerfEmission)
        val m = AdaptationModcod.Mesure(nous && r?.merDb != null, if (nous) r?.merDb else null, nous && r?.pertes == true)
        val vers = a.tic(courant, min, max, adaptMarge, m, horloge(), qo100) ?: return
        erreurAdaptation = commute(vers).orEmpty()
    }

    /** Why the last MODCOD change could not be made, or empty. */
    @Volatile var erreurAdaptation = ""; private set

    /** The test card with the callsign, as a PNG for ffmpeg. */
    private fun mire(): File {
        dossierImages.mkdirs()
        val f = File(dossierImages, "mire-datv.png")
        val im = MirePc.carte(1280, 720, indicatif().trim().uppercase(), locator(), "DATV")
        javax.imageio.ImageIO.write(im, "png", f)
        return f
    }

    // ------------------------------------------------------------ overlay and preview (nothing transmitted)

    /** Text and the mark over the picture (set before the first start). */
    @Volatile var incrustation: IncrustationDatvPc? = null

    /** The last preview's picture. */
    val fichierApercu: File get() = File(dossierImages, "apercu-datv.jpg")

    /**
     * One frame of what a start would send now (source, overlay), as a JPEG
     * in [fichierApercu], made by ffmpeg from the same command: no radio
     * involved. Null once made, else why not.
     */
    fun apercu(ff: String? = RenduPc.ffmpeg(), delaiS: Long = 20): String? {
        if (ff == null) return "ffmpeg introuvable (" + OutilsPc.conseil("ffmpeg") + ")"
        val src = source
        val img = when (src) {
            "image" -> File(image).takeIf { it.isFile } ?: return "image introuvable : " + image.ifBlank { "(aucune)" }
            "video" -> File(video).takeIf { it.isFile } ?: return "vidéo introuvable : " + video.ifBlank { "(aucune)" }
            "camera" -> null.also { if (camera.isBlank()) return "aucune caméra choisie" }
            else -> mire()
        }
        val ts = debitTs()
        val call = indicatif().trim().uppercase()
        val inc = runCatching { incrustation?.pour(ts, src, call, locator(), File(dossierImages, "incrustation-apercu.png"), direct = false) }.getOrNull()
        val sortie = fichierApercu
        sortie.delete()
        val c = IncrustationDatvPc.commandeApercu(commande(ff, ParametresTs(ts, src, img, camera, call, hevc, son, inc)), sortie)
        return runCatching {
            val pr = ProcessBuilder(c).redirectErrorStream(true).start()
            runCatching { pr.outputStream.close() }
            val lu = StringBuilder()
            val t = thread(isDaemon = true, name = "satme-datv-apercu") { runCatching { lu.append(pr.inputStream.bufferedReader().readText()) } }
            if (!pr.waitFor(delaiS, TimeUnit.SECONDS)) { pr.destroyForcibly(); return "ffmpeg ne répond pas" }
            t.join(1000)
            if (pr.exitValue() != 0 || !sortie.isFile) "ffmpeg : " + synchronized(lu) { lu.toString() }.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(200)
            else null
        }.getOrElse { "ffmpeg : " + (it.message ?: "erreur") }
    }

    /** The status for the page. */
    fun json(): JSONObject {
        val px = tx
        val pe = px?.etatCommun()
        val m = modulateur
        val e = etat
        val t = horloge()
        val mc = modcod()
        val o = JSONObject().put("emet", e.emet && pe?.emet == true).put("charge", e.chargeFictive)
            .put("modcod", modcodTexte).put("sr", srKs).put("ro", rolloff.alpha).put("courtes", courtes).put("pilotes", pilotes)
            .put("khz", frequenceKhz).put("descenteKhz", frequenceKhz + PlanQo100.DECALAGE_KHZ)
            .put("gain", if (emetteur == Emetteur.BLADERF) gainBladerf else gainDb).put("gainMax", gainMaxDb).put("gainCharge", ReglesEmission.GAIN_CHARGE_DB)
            .put("duree", dureeS).put("dureeMax", ReglesEmission.DUREE_MAX_S)
            .put("source", source).put("image", image).put("video", video).put("camera", camera).put("hevc", hevc).put("son", son)
            .put("debitTs", mc.debitTs(srKs * 1000.0).toLong()).put("sps", ReglesEmission.sps(srKs)).put("spsEnCours", spsEnCours)
            .put("echantillons", srKs * 1000L * ReglesEmission.sps(srKs))
            .put("occupeKhz", PlanQo100.occupeKhz(srKs, rolloff.alpha))
            .put("refusBande", PlanQo100.refus(frequenceKhz, srKs, rolloff.alpha) ?: "")
            .put("indicatif", indicatif().trim().uppercase())
            .put("raison", e.raison).put("erreur", e.erreur).put("khzEnCours", e.frequenceKhz)
            .put("cameras", JSONArray(cameras()))
            .put("modcods", JSONArray(Modcod.tous(false, false).map { it.constellation.libelle + " " + it.taux.libelle }))
            .put("rolloffs", JSONArray(Rolloff.entries.map { it.alpha }))
            .put("canaux", JSONArray(PlanQo100.CANAUX.map { c -> JSONObject().put("descente", c.descenteKhz).put("montee", c.monteeKhz).put("srMax", c.srMaxKs) }))
            .put("bas", PlanQo100.BAS_KHZ).put("haut", PlanQo100.HAUT_KHZ)
            .put("merBalise", merBalise ?: JSONObject.NULL).put("merBaliseAge", if (merBalise == null) JSONObject.NULL else (t - merBaliseMs) / 1000)
        // The HackRF, the indoor test and what the MiniTiouner needs for it.
        val ik = frequenceInterieurKhz
        val ih = infoHackrf
        o.put("emetteur", emetteur.cle).put("emetteurNom", emetteur.libelle).put("interieur", e.interieur).put("emetteurEnCours", e.emetteur.cle)
            .put("vga", vgaHackrf).put("vgaMax", ReglesEmission.HACKRF_VGA_MAX).put("hackrfOutil", hackrfOutil)
            .put("hackrfProgramme", outilHackrf("hackrf_transfer") ?: "").put("hackrfConseil", conseilHackrf())
            .put("hackrfPasQo100", ReglesEmission.HACKRF_PAS_QO100)
            .put("hackrf", JSONObject().put("lu", ih.lu).put("message", ih.message)
                .put("cartes", JSONArray(ih.cartes.map { c -> JSONObject().put("serie", c.serie).put("libelle", c.libelle) })))
            .put("intKhz", ik).put("intDuree", dureeInterieurS).put("intDureeMax", PlanInterieur.DUREE_MAX_S)
            .put("intGainPluto", PlanInterieur.GAIN_PLUTO_DB).put("intDefautKhz", PlanInterieur.DEFAUT_KHZ)
            .put("intRefus", PlanInterieur.refus(ik, srKs, rolloff.alpha) ?: "")
            .put("intAvert", PlanInterieur.avertissement(ik, srKs, rolloff.alpha) ?: "")
            .put("filtreHackrf", HackrfEmission.filtre(Math.round(PlanQo100.occupeKhz(srKs, rolloff.alpha) * 1000)))
            .put("hackrfDecalage", hackrfDecalage)
        // The bladeRF: its tool, what bladeRF-cli -e info found.
        val ib = infoBladerf
        o.put("bladerfProgramme", bladerfProgramme() ?: "").put("bladerfConseil", conseilBladerf()).put("bladerfDecalageGain", Bladerf.DECALAGE_GAIN_TX)
            .put("bladerf", JSONObject().put("lu", ib.lu).put("message", ib.message).put("libelle", ib.carte?.libelle ?: "").put("serie", ib.carte?.serie ?: ""))
        // The HackRF's LO beside the signal (indoor test): where it goes, or why not.
        if (emetteur == Emetteur.HACKRF && hackrfDecalage) {
            val pl = DecalageHackrf.plan(ik, srKs, rolloff.alpha, true)
            o.put("decalage", JSONObject().put("refus", DecalageHackrf.refus(ik, srKs, rolloff.alpha, true) ?: "")
                .put("loKhz", pl?.let { it.loHz / 1000.0 } ?: JSONObject.NULL).put("decalageKhz", pl?.let { it.decalageHz / 1000.0 } ?: JSONObject.NULL))
            if (pl != null) o.put("sps", pl.sps).put("echantillons", pl.debit).put("filtreHackrf", HackrfEmission.filtre(pl.bandeHz))
        }
        if (pe != null) {
            o.put("restant", maxOf(0L, (pe.finMs - t) / 1000)).put("tampons", pe.tampons).put("manques", pe.manques).put("gainApplique", pe.gainDb)
            (tx as? BladerfEmission)?.let { o.put("sousCharges", it.sousCharges) }
        }
        if (m != null) {
            // The share of the air time in dummy frames (the stream late, or starting).
            val vide = m.tramesVides.toDouble() * fr.f4ioz.satcombo.datv.s2.CouchePhysique.SYMBOLES_VIDE
            val tot = vide + m.tramesDonnees.toDouble() * m.modcod.symboles
            o.put("tramesVidesPct", if (tot > 0) 100.0 * vide / tot else 0.0).put("ecretages", (sortie ?: m).ecretages).put("paquets", m.paquets)
        }
        flux?.let { o.put("tsPerdus", it.perdus).put("tsKbps", it.kbps()) }
        val b = boucle.etat
        o.put("boucle", JSONObject().put("ecart", b.ecartDb ?: JSONObject.NULL).put("tropFort", b.tropFort).put("message", b.message).put("baisses", b.baisses))
        // The MODCOD fitted to our reception.
        val a = adaptation
        val ae = a?.etat
        o.put("modcodEnCours", modcodEnCours?.let { AdaptationModcod.nom(it) } ?: "")
        o.put("adapt", JSONObject().put("on", adaptOn).put("min", adaptMin).put("max", adaptMax).put("marge", adaptMarge)
            .put("actif", e.emet && ae?.actif == true).put("boucle", ae?.boucle == true).put("fige", ae?.fige == true)
            .put("courant", ae?.courant ?: "").put("cible", ae?.cible ?: "").put("margeMesuree", ae?.margeDb ?: JSONObject.NULL)
            .put("seuil", ae?.seuilDb ?: JSONObject.NULL).put("prochaine", ae?.prochaine ?: "").put("suggestion", ae?.suggestion ?: "")
            .put("erreur", erreurAdaptation)
            .put("historique", JSONArray((a?.historique() ?: emptyList()).map { c ->
                JSONObject().put("t", c.ms).put("de", c.de).put("vers", c.vers).put("raison", c.raison) })))
        o.put("cpu", cpu())
        o.put("carteOn", carteOn).put("qth", qth).put("puissance", puissance).put("antenne", antenne).put("message", message)
            .put("retourHz", retourHz).put("retourMode", retourMode).put("retourDonne", retourDonne)
            .put("carteEnvoyee", inserteur?.remplaces ?: 0L)
            .put("chat", JSONArray(dits().map { d -> JSONObject().put("seq", d.seq).put("texte", d.texte).put("t", d.ms) }))
        return o
    }

    companion object {
        val SOURCES = setOf("mire", "animee", "image", "video", "camera")

        /** The bladeRF's attenuation when none was set: low (66 − 50 = 16 dB of libbladeRF's overall gain). */
        const val GAIN_BLADERF_DEFAUT_DB = -50.0

        /** One chat line every 2 s at most. */
        const val CHAT_INTERVALLE_MS = 2000L

        /**
         * Why the settings asked ([sr] in kS/s, [modcod], with [courtes]
         * frames) cannot be taken, or null: a rate outside [Datv.DEBITS] or
         * an unknown MODCOD is refused, never left at the previous one.
         */
        fun refusReglage(sr: String?, modcod: String?, courtes: Boolean): String? {
            if (sr != null) {
                val v = sr.trim().toIntOrNull()
                if (v == null || v !in Datv.DEBITS)
                    return "débit de symboles non prévu : ${sr.trim()} kS/s (prévus : ${Datv.DEBITS.joinToString(", ")} kS/s)"
            }
            if (modcod != null) {
                if (Modcod.de(modcod) == null) return "MODCOD inconnu : ${modcod.trim()}"
                if (Modcod.de(modcod, courtes) == null) return "${modcod.trim()} n'existe pas en trames courtes"
            }
            return null
        }

        /** About 50 ms per buffer, a multiple of 1024 pairs. */
        fun paires(debit: Long): Int = (((debit / 20) + 1023) / 1024 * 1024).toInt().coerceIn(4096, 1 shl 18)

        /** The cameras' device names, listed only (never opened here). */
        fun cameras(): List<String> =
            if (OutilsPc.windows) emptyList()
            else (File("/dev").listFiles { f -> f.name.matches(Regex("video\\d+")) } ?: emptyArray()).map { it.path }.sortedBy { it.removePrefix("/dev/video").toIntOrNull() ?: 0 }

        fun cpu(): Double = runCatching {
            (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuLoad * 100
        }.getOrDefault(-1.0)

        /**
         * ffmpeg's command line: the picture coded to fill the transport
         * stream at exactly [p]'s rate (`-muxrate`, null packets padding it
         * out), the callsign as the service's name and provider.
         */
        /** The picture's size for a transport stream of [ts] bit/s. */
        fun taille(ts: Long): Pair<Int, Int> = when {
            ts < 250_000 -> 426 to 240
            ts < 700_000 -> 640 to 360
            ts < 1_600_000 -> 960 to 540
            else -> 1280 to 720
        }

        fun commande(ff: String, p: ParametresTs): List<String> {
            val ts = p.debitTs
            val audio = if (p.son) 32_000L else 0L
            // What the video may take: the stream less the audio, the tables and a margin for the multiplexer.
            val video = ((ts - audio) * 0.78 - 8_000).toLong().coerceAtLeast(20_000L)
            val (w, h) = taille(ts)
            val fixe = p.source == "mire" || p.source == "image"
            val anime = p.source == "animee"
            val ips = if (fixe) 10 else 25
            val cadre = "scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2"
            val l = ArrayList<String>()
            l += listOf(ff, "-hide_banner", "-loglevel", "error", "-nostdin")
            when (p.source) {
                "camera" -> l += listOf("-f", "v4l2", "-framerate", "25", "-i", p.camera)
                "video" -> l += listOf("-re", "-stream_loop", "-1", "-i", p.image!!.path)
                else -> l += listOf("-re", "-loop", "1", "-framerate", ips.toString(), "-i", p.image!!.path)
            }
            // The animated card: a moving test picture (its own counter turning) over the card's colour bars, the callsign left in sight.
            if (anime) l += listOf("-re", "-f", "lavfi", "-i", "testsrc2=size=" + (w / 3 / 2 * 2) + "x" + (h / 3 / 2 * 2) + ":rate=25")
            // The sound: the file's own, a beep each second with the animated card, else silence.
            val entreeSon = if (anime) 2 else 1
            if (p.son && p.source != "video") l += listOf("-re", "-f", "lavfi", "-i",
                if (anime) "sine=frequency=1000:beep_factor=4:sample_rate=48000" else "anullsrc=r=48000:cl=mono")
            // The overlay: the last input, laid over the picture last.
            val inc = p.incrustation
            val entreeInc = 1 + (if (anime) 1 else 0) + (if (p.son && p.source != "video") 1 else 0)
            if (inc != null) l += IncrustationDatvPc.entree(inc)
            val anim = "[0:v]$cadre[f];[f][1:v]overlay=x=(W-w)/2+(W-w)/2.2*sin(t*0.8):y=H/14,format=yuv420p"
            if (anime) l += listOf("-filter_complex", if (inc == null) "$anim[v]" else IncrustationDatvPc.filtre(anim, entreeInc, w, h),
                "-map", "[v]")
            else if (inc != null) l += listOf("-filter_complex", IncrustationDatvPc.filtre("[0:v]$cadre", entreeInc, w, h), "-map", "[v]")
            else l += listOf("-map", "0:v:0")
            if (p.son) l += listOf("-map", if (p.source == "video") "0:a:0?" else "$entreeSon:a:0")
            if (!anime && inc == null) l += listOf("-vf", "$cadre,format=yuv420p")
            l += listOf("-r", ips.toString())
            l += if (p.hevc) listOf("-c:v", "libx265", "-preset", "veryfast", "-tune", "zerolatency", "-x265-params", "log-level=error:keyint=$ips:repeat-headers=1")
                else listOf("-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-g", ips.toString())
            l += listOf("-b:v", video.toString(), "-maxrate", video.toString(), "-bufsize", (video / 2).toString())
            if (p.son) l += listOf("-c:a", "aac", "-b:a", "32k", "-ac", "1")
            l += listOf("-f", "mpegts", "-muxrate", ts.toString(), "-mpegts_original_network_id", "1", "-mpegts_transport_stream_id", "1",
                "-mpegts_service_id", "1", "-mpegts_pmt_start_pid", "4095", "-mpegts_start_pid", "256", "-pcr_period", "40",
                "-metadata", "service_provider=" + p.indicatif, "-metadata", "service_name=" + p.indicatif, "pipe:1")
            return l
        }
    }

    /** ffmpeg as the transport stream's maker. */
    class FfmpegTs : FabriqueTs {
        override fun demarre(p: ParametresTs): Processus {
            val ff = RenduPc.ffmpeg() ?: throw java.io.IOException("ffmpeg introuvable (" + OutilsPc.conseil("ffmpeg") + ")")
            val journal = File(System.getProperty("java.io.tmpdir"), "satme-datv-tx-ffmpeg.log")
            val pr = ProcessBuilder(commande(ff, p)).redirectError(journal).start()
            // The time over the picture: a fresh overlay into ffmpeg's input, until it stops.
            p.incrustation?.takeIf { it.direct }?.let { inc -> thread(isDaemon = true, name = "satme-datv-incrustation") { inc.alimente(pr.outputStream) { pr.isAlive } } }
            return object : Processus {
                override val sortie: InputStream = pr.inputStream
                override val vivant: Boolean get() = pr.isAlive
                override fun arrete() { pr.destroy(); if (!pr.waitFor(2, TimeUnit.SECONDS)) pr.destroyForcibly() }
                override fun erreur() = runCatching { journal.readLines().lastOrNull { it.isNotBlank() }.orEmpty().take(160) }.getOrDefault("")
            }
        }
    }

    /**
     * The transport stream between ffmpeg and the modulator: whole packets,
     * resynchronised on 0x47, the station card put in place of null packets
     * ([inserteur]); when it backs up (the source a little faster
     * than the Pluto's clock), null packets go first.
     */
    class FluxTs(capacite: Int, private val inserteur: InserteurCarte? = null) : SourceTs {
        private val file = ArrayBlockingQueue<ByteArray>(capacite)
        private val seuil = capacite * 3 / 4
        @Volatile var perdus = 0L; private set
        @Volatile private var octets = 0L
        private val t0 = System.currentTimeMillis()

        fun ajoute(p: ByteArray) {
            octets += 188
            val nul = ((p[1].toInt() and 0x1F) shl 8 or (p[2].toInt() and 0xFF)) == 0x1FFF
            if (nul && file.size >= seuil) { perdus++; return }
            if (!file.offer(p)) { file.poll(); perdus++; file.offer(p) }
        }

        @Volatile private var repereT = t0
        @Volatile private var repereOctets = 0L
        @Volatile private var precedentT = t0
        @Volatile private var precedentOctets = 0L

        /**
         * The rate the stream comes in at, over the last 5 to 10 s (not since
         * the start: ffmpeg's first seconds without a packet made it read low).
         */
        @Synchronized
        fun kbps(maintenant: Long = System.currentTimeMillis()): Long {
            if (maintenant - repereT >= 5_000L) {
                precedentT = repereT; precedentOctets = repereOctets
                repereT = maintenant; repereOctets = octets
            }
            return (octets - precedentOctets) * 8 / maxOf(1L, maintenant - precedentT)
        }

        override fun disponibles(): Int = file.size

        override fun lis(dst: ByteArray) {
            val p = file.poll() ?: ByteArray(188).also { it[0] = 0x47; it[1] = 0x1F; it[2] = 0xFF.toByte(); it[3] = 0x10 }
            System.arraycopy(p, 0, dst, 0, 188)
        }

        /** Reads [e] into packets while [encore]. */
        fun lit(e: InputStream, encore: () -> Boolean) {
            val b = ByteArray(188 * 64)
            var n = 0
            while (encore()) {
                val r = runCatching { e.read(b, n, b.size - n) }.getOrDefault(-1)
                if (r < 0) break
                n += r
                var k = 0
                while (n - k >= 188) {
                    if (b[k].toInt() != 0x47) { k++; continue }
                    val p = b.copyOfRange(k, k + 188)
                    ajoute(inserteur?.traite(p) ?: p)
                    k += 188
                }
                System.arraycopy(b, k, b, 0, n - k)
                n -= k
            }
        }
    }

}
