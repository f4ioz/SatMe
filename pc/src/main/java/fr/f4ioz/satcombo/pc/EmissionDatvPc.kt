/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.BoucleNiveau
import fr.f4ioz.satcombo.datv.Datv
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
import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.HackrfEmission
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
 * the indoor test as well, at −40 dB at most.
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
    /** What the MiniTiouner sees now, for the closed loop. */
    private val reception: () -> Reception? = { null },
    /** The transport stream's maker: ffmpeg here, something else in the tests. */
    private val fabrique: FabriqueTs = FfmpegTs(),
    /** The HackRF's tools (hackrf_transfer, hackrf_info) when no folder is set: the PATH. */
    private val outilParDefaut: (String) -> String? = { OutilsPc.trouve(it) },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Silence from the page longer than this: stopped. */
    private val vieMs: Long = 15_000L,
) {
    /** The MiniTiouner's view: locked, MER, where it is tuned (sky kHz), the service's name. */
    data class Reception(val verrouille: Boolean, val merDb: Double?, val cielKhz: Long, val service: String)

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
    /** Where hackrf_transfer is (the program or its folder); empty: the PATH. */
    var hackrfOutil: String
        get() = stockage.getString("datv_tx_hackrf_outil", "") ?: ""
        set(v) { stockage.edit().putString("datv_tx_hackrf_outil", v.trim()).apply() }
    /** The indoor test's frequency (kHz). */
    var frequenceInterieurKhz: Long
        get() = stockage.getLong("datv_tx_int_khz", PlanInterieur.DEFAUT_KHZ)
        set(v) { stockage.edit().putLong("datv_tx_int_khz", v).apply() }
    var dureeInterieurS: Int
        get() = PlanInterieur.dureeS(stockage.getInt("datv_tx_int_duree", PlanInterieur.DUREE_DEFAUT_S))
        set(v) { stockage.edit().putInt("datv_tx_int_duree", PlanInterieur.dureeS(v)).apply() }

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
    private var processus: Processus? = null
    private var modulateur: ModulateurDvbs2? = null
    private var flux: FluxTs? = null
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
        val qui = emetteur
        val hackrf = qui == Emetteur.HACKRF
        if (!hackrf) plutoOccupe()?.let { return it }
        val m = modcod()
        val khz = if (interieur) frequenceInterieurKhz else frequenceKhz
        val duree = if (interieur) dureeInterieurS else dureeS
        val gain = if (hackrf) vgaHackrf.toDouble() else gainDb
        val d = DemandeEmission(khz, srKs, m, rolloff, gain, duree, chargeFictive, indicatif(), interieur, qui)
        ReglesEmission.refus(d, gainMaxDb)?.let { return it }
        val programme = if (hackrf) outilHackrf("hackrf_transfer") ?: return conseilHackrf() else ""
        val sps = ReglesEmission.sps(srKs)
        val debit = srKs * 1000L * sps
        val debitTs = m.debitTs(srKs * 1000.0).toLong()
        val img = when (source) {
            "image" -> File(image).takeIf { it.isFile } ?: return "image introuvable : " + image.ifBlank { "(aucune)" }
            "video" -> File(video).takeIf { it.isFile } ?: return "vidéo introuvable : " + video.ifBlank { "(aucune)" }
            "camera" -> null.also { if (camera.isBlank()) return "aucune caméra choisie" }
            else -> mire()
        }
        val gen = ++generation
        val f = FluxTs(maxOf(64, (debitTs / 1504 * 2).toInt()))
        val p = runCatching { fabrique.demarre(ParametresTs(debitTs, source, img, camera, d.indicatif.trim().uppercase(), hevc, son)) }
            .getOrElse { return "ffmpeg ne démarre pas : " + (it.message ?: "") }
        processus = p; flux = f
        thread(isDaemon = true, name = "satme-datv-tx-ts") { f.lit(p.sortie) { generation == gen } }
        val mod = ModulateurDvbs2(m, rolloff, sps, f)
        modulateur = mod
        derniereVie = horloge()
        val e: EmetteurDatv
        val refus: String?
        if (hackrf) {
            val hx = HackrfEmission(programme, ReglesEmission.HACKRF_VGA_MAX)
            val r = HackrfEmission.Reglage(
                frequenceHz = khz * 1000L, debit = debit, vgaDb = ReglesEmission.vgaHackrf(gain), dureeMs = duree * 1000L,
                bandeHz = Math.round(PlanQo100.occupeKhz(srKs, rolloff.alpha) * 1000), plafondVgaDb = ReglesEmission.HACKRF_VGA_MAX,
                paires = paires(debit), serie = infoHackrf.cartes.singleOrNull()?.serie.orEmpty(), garde = { garde(gen, p) })
            refus = hx.demarre(r) { b -> mod.remplis(b, b.size / 2) }
            e = hx
        } else {
            val px = PlutoEmission(plutoHote(), plutoPort(), gainMaxDb = minOf(gainMaxDb, 0.0))
            val r = PlutoEmission.Reglage(
                frequenceHz = khz * 1000L, debit = debit, gainDb = gainDb, dureeMs = duree * 1000L,
                plafondDb = when { chargeFictive -> ReglesEmission.GAIN_CHARGE_DB; interieur -> PlanInterieur.GAIN_PLUTO_DB; else -> 0.0 },
                paires = paires(debit),
                garde = { garde(gen, p) })
            refus = px.demarre(r) { b -> mod.remplis(b, b.size / 2) }
            e = px
        }
        if (refus != null) { generation++; p.arrete(); processus = null; return refus }
        tx = e
        etat = Etat(emet = true, chargeFictive = chargeFictive, interieur = interieur, emetteur = qui, frequenceKhz = khz, debitTs = debitTs)
        return null
    }

    /** Why the transmission must stop now, or null. */
    private fun garde(gen: Int, p: Processus): String? = when {
        generation != gen -> "arrêtée"
        horloge() - derniereVie > vieMs -> "page SatMe fermée ou injoignable"
        !p.vivant -> "ffmpeg s'est arrêté" + p.erreur().let { if (it.isBlank()) "" else " : $it" }
        else -> null
    }

    /** Stops transmitting (the Pluto's LO powered down); the picture's maker stopped. */
    @Synchronized
    fun arrete(raison: String = "arrêt demandé") {
        val px = tx
        px?.arrete(raison)
        generation++
        processus?.arrete(); processus = null
        tx = null
        val pe = px?.etatCommun()
        if (etat.emet) etat = etat.copy(emet = false, raison = pe?.raison ?: raison, erreur = pe?.erreur.orEmpty())
    }

    /** A new gain while transmitting (clamped as at the start), kept for next time. */
    fun regleGain(db: Double): Double? {
        gainDb = db
        val px = tx
        // The HackRF takes its gain at the start; the Pluto's only goes to the Pluto.
        return if (px is PlutoEmission) px.regleGain(db) else null
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
        val r = reception() ?: return
        val t = horloge()
        if (r.verrouille && r.merDb != null && kotlin.math.abs(r.cielKhz - Datv.BALISE_KHZ) < 100 && r.service != indicatif().trim().uppercase()) {
            merBalise = r.merDb; merBaliseMs = t
        }
        // The closed loop on the beacon: on QO-100 only (the Pluto).
        if (!etat.emet || etat.chargeFictive || etat.interieur || px !is PlutoEmission) return
        val nous = r.verrouille && (r.service.equals(indicatif().trim(), true) ||
            kotlin.math.abs(r.cielKhz - (etat.frequenceKhz + PlanQo100.DECALAGE_KHZ)) < srKs / 2)
        val g = boucle.mesure(if (nous) r.merDb else null, merBalise, 0L, px.etat.gainDb, t) ?: return
        px.regleGain(g)
    }

    /** The test card with the callsign, as a PNG for ffmpeg. */
    private fun mire(): File {
        dossierImages.mkdirs()
        val f = File(dossierImages, "mire-datv.png")
        val im = MirePc.carte(1280, 720, indicatif().trim().uppercase(), locator(), "DATV")
        javax.imageio.ImageIO.write(im, "png", f)
        return f
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
            .put("gain", gainDb).put("gainMax", gainMaxDb).put("gainCharge", ReglesEmission.GAIN_CHARGE_DB)
            .put("duree", dureeS).put("dureeMax", ReglesEmission.DUREE_MAX_S)
            .put("source", source).put("image", image).put("video", video).put("camera", camera).put("hevc", hevc).put("son", son)
            .put("debitTs", mc.debitTs(srKs * 1000.0).toLong()).put("sps", ReglesEmission.sps(srKs))
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
        if (pe != null) {
            o.put("restant", maxOf(0L, (pe.finMs - t) / 1000)).put("tampons", pe.tampons).put("manques", pe.manques).put("gainApplique", pe.gainDb)
        }
        if (m != null) {
            // The share of the air time in dummy frames (the stream late, or starting).
            val vide = m.tramesVides.toDouble() * fr.f4ioz.satcombo.datv.s2.CouchePhysique.SYMBOLES_VIDE
            val tot = vide + m.tramesDonnees.toDouble() * m.modcod.symboles
            o.put("tramesVidesPct", if (tot > 0) 100.0 * vide / tot else 0.0).put("ecretages", m.ecretages).put("paquets", m.paquets)
        }
        flux?.let { o.put("tsPerdus", it.perdus).put("tsKbps", it.kbps()) }
        val b = boucle.etat
        o.put("boucle", JSONObject().put("ecart", b.ecartDb ?: JSONObject.NULL).put("tropFort", b.tropFort).put("message", b.message).put("baisses", b.baisses))
        o.put("cpu", cpu())
        return o
    }

    companion object {
        val SOURCES = setOf("mire", "animee", "image", "video", "camera")

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
        fun commande(ff: String, p: ParametresTs): List<String> {
            val ts = p.debitTs
            val audio = if (p.son) 32_000L else 0L
            // What the video may take: the stream less the audio, the tables and a margin for the multiplexer.
            val video = ((ts - audio) * 0.78 - 8_000).toLong().coerceAtLeast(20_000L)
            val (w, h) = when {
                ts < 250_000 -> 426 to 240
                ts < 700_000 -> 640 to 360
                ts < 1_600_000 -> 960 to 540
                else -> 1280 to 720
            }
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
            // The animated card: a moving test picture (its own counter turning) over the test card, the callsign still in sight.
            if (anime) l += listOf("-re", "-f", "lavfi", "-i", "testsrc2=size=" + (w / 3 / 2 * 2) + "x" + (h / 3 / 2 * 2) + ":rate=25")
            // The sound: the file's own, a beep each second with the animated card, else silence.
            val entreeSon = if (anime) 2 else 1
            if (p.son && p.source != "video") l += listOf("-re", "-f", "lavfi", "-i",
                if (anime) "sine=frequency=1000:beep_factor=4:sample_rate=48000" else "anullsrc=r=48000:cl=mono")
            if (anime) l += listOf("-filter_complex", "[0:v]$cadre[f];[f][1:v]overlay=x=(W-w)/2+(W-w)/2.2*sin(t*0.8):y=H-h-H/12,format=yuv420p[v]",
                "-map", "[v]")
            else l += listOf("-map", "0:v:0")
            if (p.son) l += listOf("-map", if (p.source == "video") "0:a:0?" else "$entreeSon:a:0")
            if (!anime) l += listOf("-vf", "$cadre,format=yuv420p")
            l += listOf("-r", ips.toString())
            l += if (p.hevc) listOf("-c:v", "libx265", "-preset", "veryfast", "-tune", "zerolatency", "-x265-params", "log-level=error:keyint=${ips * 2}:repeat-headers=1")
                else listOf("-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-g", (ips * 2).toString())
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
     * resynchronised on 0x47; when it backs up (the source a little faster
     * than the Pluto's clock), null packets go first.
     */
    class FluxTs(capacite: Int) : SourceTs {
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

        fun kbps(): Long = octets * 8 / maxOf(1L, System.currentTimeMillis() - t0)

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
                    ajoute(b.copyOfRange(k, k + 188))
                    k += 188
                }
                System.arraycopy(b, k, b, 0, n - k)
                n -= k
            }
        }
    }

}
