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
import fr.f4ioz.satcombo.datv.Emetteur
import fr.f4ioz.satcombo.datv.PlanInterieur
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SignalQo100
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.BladerfEmission
import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.ModulateurAudio
import fr.f4ioz.satcombo.sdr.ModulationAudio
import fr.f4ioz.satcombo.sdr.PlutoEmission
import fr.f4ioz.satcombo.sdr.PlutoSdr
import fr.f4ioz.satcombo.sstv.DemandeSstvRf
import fr.f4ioz.satcombo.sstv.IndicatifSstv
import fr.f4ioz.satcombo.sstv.NiveauSstvQo100
import fr.f4ioz.satcombo.sstv.ProgrammeSstv
import fr.f4ioz.satcombo.sstv.ReglesSstvRf
import fr.f4ioz.satcombo.sstv.SstvEncoder
import fr.f4ioz.satcombo.sstv.SstvMode
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/**
 * SSTV on the air from SatMe-PC: a picture (the test card with the
 * callsign, a sheet, a file), its callsign written over it, encoded
 * ([ProgrammeSstv]), modulated in narrow FM or SSB ([ModulateurAudio]) and
 * sent by [PlutoEmission] or [BladerfEmission] — the only code that
 * writes to a transmitter, with all their guards.
 *
 * **Only on the operator's word**: a start needs the page's confirmation;
 * nothing is sent at startup, nothing of a transmission is kept but its
 * parameters (« vers un satellite » not even that). Every rule of
 * [ReglesSstvRf] applies. The transmitter stops by itself once the
 * pictures (and the CW) are out, at the time allowed, on any error, or
 * when the page stops saying it is there ([vie]).
 */
class EmissionSstvPc(
    private val stockage: Stockage,
    private val indicatif: () -> String,
    private val locator: () -> String = { "" },
    private val plutoHote: () -> String = { PlutoSdr.HOTE },
    private val plutoPort: () -> Int = { PlutoSdr.PORT },
    /** Why the Pluto is busy (the SDR receiving with it), or null. */
    private val plutoOccupe: () -> String? = { null },
    private val bladerfOccupe: () -> String? = { null },
    /** bladeRF-cli's path, or null when missing. */
    private val bladerfProgramme: () -> String? = { OutilsPc.trouve("bladeRF-cli") },
    /** Why another transmission runs (DATV), or null. */
    private val autreEmission: () -> String? = { null },
    /** The highest TX gain ever applied (Réglages, shared with DATV). */
    private val gainMaxDb: () -> Double = { -10.0 },
    /** A sheet drawn ([PlancheWebPc.image]), or null. */
    private val planche: (String) -> BufferedImage? = { null },
    private val planches: () -> List<Pair<String, String>> = { emptyList() },
    /** QO-100's narrow band signals while SatMe hears it, or null: the closed loop. */
    private val signauxQo100: () -> List<SignalQo100>? = { null },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Silence from the page longer than this: stopped. */
    private val vieMs: Long = 15_000L,
    /** The SatMe-Node's transmitter when the operator chose to transmit through it, else null (no network asked). */
    private val noeudTx: () -> fr.f4ioz.satcombo.sdr.EmetteurNoeud.Fabrique? = { null },
) {
    /** The transmitters' I/Q corrections, each board its own (none: the samples as made). */
    @Volatile var corrections: fr.f4ioz.satcombo.sdr.CorrectionsIq? = null

    // ------------------------------------------------------------ settings (parameters only)

    var emetteur: Emetteur
        get() = Emetteur.de(stockage.getString("sstv_tx_emetteur", Emetteur.BLADERF.cle)).takeIf { it != Emetteur.HACKRF } ?: Emetteur.PLUTO
        set(v) { if (v != Emetteur.HACKRF) stockage.edit().putString("sstv_tx_emetteur", v.cle).apply() }
    var modulation: ModulationAudio
        get() = ModulationAudio.de(stockage.getString("sstv_tx_modulation", "nbfm")) ?: ModulationAudio.NBFM
        set(v) { stockage.edit().putString("sstv_tx_modulation", v.cle).apply() }
    /** NBFM deviation: 2.5 or 5 kHz. */
    var deviationHz: Double
        get() = if (stockage.getInt("sstv_tx_dev", 5000) == 2500) 2_500.0 else 5_000.0
        set(v) { stockage.edit().putInt("sstv_tx_dev", if (v <= 2_500.0) 2500 else 5000).apply() }
    var preaccentuation: Boolean
        get() = stockage.getBoolean("sstv_tx_preacc", false)
        set(v) { stockage.edit().putBoolean("sstv_tx_preacc", v).apply() }
    /** On the air (kHz). */
    var frequenceKhz: Long
        get() = stockage.getLong("sstv_tx_khz", 145_500L)
        set(v) { stockage.edit().putLong("sstv_tx_khz", v).apply() }
    /** The indoor test (kHz). */
    var frequenceInterieurKhz: Long
        get() = stockage.getLong("sstv_tx_int_khz", DEFAUT_INTERIEUR_KHZ)
        set(v) { stockage.edit().putLong("sstv_tx_int_khz", v).apply() }
    /** The attenuation asked (dB, ≤ 0), each transmitter its own; low by default. */
    var gainPluto: Double
        get() = stockage.getFloat("sstv_tx_gain_pluto", GAIN_DEFAUT_DB.toFloat()).toDouble()
        set(v) { if (!v.isNaN()) stockage.edit().putFloat("sstv_tx_gain_pluto", v.coerceIn(PlutoEmission.GAIN_MIN_DB, 0.0).toFloat()).apply() }
    var gainBladerf: Double
        get() = stockage.getFloat("sstv_tx_gain_bladerf", GAIN_DEFAUT_DB.toFloat()).toDouble()
        set(v) { if (!v.isNaN()) stockage.edit().putFloat("sstv_tx_gain_bladerf", v.coerceIn(BladerfEmission.GAIN_MIN_DB.toDouble(), 0.0).toFloat()).apply() }
    val gainDb: Double get() = if (emetteur == Emetteur.BLADERF) gainBladerf else gainPluto
    var mode: SstvMode
        get() = SstvMode.byName(stockage.getString("sstv_tx_mode", "Robot 36").orEmpty()) ?: SstvMode.ALL.first()
        set(v) { stockage.edit().putString("sstv_tx_mode", v.name).apply() }
    /** "mire", "planche" or "fichier". */
    var source: String
        get() = stockage.getString("sstv_tx_source", "mire").takeIf { it in SOURCES } ?: "mire"
        set(v) { if (v in SOURCES) stockage.edit().putString("sstv_tx_source", v).apply() }
    var plancheId: String
        get() = stockage.getString("sstv_tx_planche", "") ?: ""
        set(v) { stockage.edit().putString("sstv_tx_planche", v.trim()).apply() }
    var fichier: String
        get() = stockage.getString("sstv_tx_fichier", "") ?: ""
        set(v) { stockage.edit().putString("sstv_tx_fichier", v.trim()).apply() }
    /** How many times the picture goes (one after the other), 1 to 3. */
    var repetitions: Int
        get() = stockage.getInt("sstv_tx_rep", 1).coerceIn(1, 3)
        set(v) { stockage.edit().putInt("sstv_tx_rep", v.coerceIn(1, 3)).apply() }
    /** The callsign in CW after the pictures. */
    var cwId: Boolean
        get() = stockage.getBoolean("sstv_tx_cw", true)
        set(v) { stockage.edit().putBoolean("sstv_tx_cw", v).apply() }

    // ------------------------------------------------------------ the picture

    /** The picture to send at [m]'s size, the callsign over it; or why not. */
    fun image(m: SstvMode = mode): Result<IntArray> = runCatching {
        val call = indicatif().trim().uppercase()
        val b: BufferedImage = when (source) {
            "planche" -> planche(plancheId) ?: error("planche introuvable : en choisir une")
            "fichier" -> File(fichier).takeIf { it.isFile }?.let { javax.imageio.ImageIO.read(it) } ?: error("image illisible ou introuvable : " + fichier.ifBlank { "(aucune)" })
            else -> MirePc.carte(m.width, m.height, call, locator(), m.name)
        }
        IndicatifSstv.incruste(pixels(ajuste(b, m.width, m.height)), m.width, m.height, call)
    }

    /** The picture to send, as a PNG (nothing transmitted). */
    fun apercuPng(m: SstvMode = mode): ByteArray? = image(m).getOrNull()?.let { px ->
        val b = BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_RGB)
        b.setRGB(0, 0, m.width, m.height, px, 0, m.width)
        java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(b, "png", it) }.toByteArray()
    }

    // ------------------------------------------------------------ the transmission

    data class Etat(
        val emet: Boolean = false,
        val interieur: Boolean = false,
        val chargeFictive: Boolean = false,
        val versSatellite: Boolean = false,
        val emetteur: Emetteur = Emetteur.PLUTO,
        val frequenceHz: Long = 0L,
        val modulation: ModulationAudio = ModulationAudio.NBFM,
        val mode: String = "",
        /** Sent by the SatMe-Node's transmitter, not this PC's board. */
        val parNoeud: Boolean = false,
        val raison: String = "",
        val erreur: String = "",
    )

    @Volatile var etat = Etat(); private set
    @Volatile private var tx: EmetteurDatv? = null
    @Volatile private var programme: ProgrammeSstv? = null
    @Volatile private var modulateur: ModulateurAudio? = null
    @Volatile private var derniereVie = 0L
    @Volatile private var generation = 0
    @Volatile private var occupe: Pair<Long, Long>? = null
    @Volatile private var finiMs = 0L
    val boucle = BoucleNiveau()
    @Volatile var niveauQo100 = NiveauSstvQo100.Mesure(null, null); private set

    /** Transmitting with this PC's [e] now (the node's board is the node's). */
    fun emetAvec(e: Emetteur): Boolean = etat.emet && etat.emetteur == e && !etat.parNoeud

    /** The page is there (each second while transmitting). */
    fun vie() { derniereVie = horloge() }

    /** What a start would ask now, for the page's checks and the start itself. */
    fun demande(confirme: Boolean, interieur: Boolean, charge: Boolean, satellite: Boolean, secondes: Double = secondes()): DemandeSstvRf =
        DemandeSstvRf((if (interieur) frequenceInterieurKhz else frequenceKhz) * 1000L, modulation, gainDb, secondes,
            indicatif(), emetteur, deviationHz, interieur, charge, satellite, confirme)

    /** The programme's length for the current settings (s). */
    fun secondes(): Double {
        val m = mode
        val un = SstvEncoder.seconds(m)
        val cw = if (cwId) 1.0 + fr.f4ioz.satcombo.cw.DecodeurCw.manipulation("DE " + indicatif().trim(), CW_WPM).sumOf { it.second } / 1000 else 0.0
        return un * repetitions + (repetitions - 1) * 1.0 + cw
    }

    /**
     * Starts transmitting, on the operator's confirmed action only
     * ([confirme]): indoors, into a dummy load or on the air (« vers un
     * satellite » ticked: [satellite]). Null once transmitting, else why not.
     */
    @Synchronized
    fun demarre(confirme: Boolean, interieur: Boolean = false, charge: Boolean = false, satellite: Boolean = false): String? {
        ModeDemo.refus("l'émission SSTV")?.let { return it }
        if (!confirme) return "émission non confirmée"
        if (etat.emet) return "déjà en émission"
        autreEmission()?.let { return it }
        val qui = emetteur
        val blade = qui == Emetteur.BLADERF
        // Through the node: its board of this kind, its guards there; this PC's board stays free.
        val noeud = noeudTx()
        if (noeud == null) (if (blade) bladerfOccupe() else plutoOccupe())?.let { return it }
        val m = mode
        val px = image(m).getOrElse { return it.message ?: "image impossible" }
        val call = indicatif().trim().uppercase()
        val prog = ProgrammeSstv(List(repetitions) { m to px }, DEBIT_AUDIO, cw = if (cwId) "DE $call" else null, cwWpm = CW_WPM)
        val d = demande(true, interieur, charge, satellite, prog.secondes)
        ReglesSstvRf.refus(d)?.let { return it }
        val plafond = ReglesSstvRf.plafondDb(d, minOf(gainMaxDb(), 0.0))
        val programmeCli = if (blade && noeud == null) bladerfProgramme() ?: return "bladeRF-cli introuvable : installer bladerf (sudo apt install bladerf), ou donner son chemin dans l'onglet SDR" else ""
        val facteur = if (blade) FACTEUR_BLADERF else FACTEUR_PLUTO
        val mod = ModulateurAudio(d.modulation, DEBIT_AUDIO, facteur, d.deviationHz, preaccentuation && d.modulation == ModulationAudio.NBFM, NIVEAU, prog)
        val gen = ++generation
        modulateur = mod; programme = prog; finiMs = 0L
        derniereVie = horloge()
        val duree = ReglesSstvRf.dureeEmissionMs(d)
        val paires = EmissionDatvPc.paires(mod.debit)
        val e: EmetteurDatv
        val refus: String?
        if (noeud != null) {
            val nx = noeud.cree()
            refus = nx.demarre(fr.f4ioz.satcombo.sdr.EmetteurNoeud.Reglage(qui.cle, d.frequenceHz, mod.debit, d.gainDb, duree, plafondDb = plafond, paires = paires,
                garde = { garde(gen) })) { b -> mod.remplis(b, b.size / 2) }
            e = nx
        } else if (blade) {
            val bx = BladerfEmission(programmeCli, gainMaxDb = plafond)
            refus = bx.demarre(BladerfEmission.Reglage(d.frequenceHz, mod.debit, d.gainDb, duree, plafondDb = plafond, paires = paires,
                garde = { garde(gen) }, correction = { s -> corrections?.aAppliquer(fr.f4ioz.satcombo.sdr.CorrectionsIq.BLADERF, s) })) { b -> mod.remplis(b, b.size / 2) }
            e = bx
        } else {
            val px2 = PlutoEmission(plutoHote(), plutoPort(), gainMaxDb = plafond)
            refus = px2.demarre(PlutoEmission.Reglage(d.frequenceHz, mod.debit, d.gainDb, duree, plafondDb = plafond, paires = paires,
                garde = { garde(gen) }, correction = { s -> corrections?.aAppliquer(fr.f4ioz.satcombo.sdr.CorrectionsIq.PLUTO, s) })) { b -> mod.remplis(b, b.size / 2) }
            e = px2
        }
        if (refus != null) { generation++; return refus }
        tx = e
        occupe = ReglesSstvRf.occupe(d)
        niveauQo100 = NiveauSstvQo100.Mesure(null, null)
        etat = Etat(true, interieur, charge, satellite, qui, d.frequenceHz, d.modulation, m.name, parNoeud = noeud != null)
        return null
    }

    /** Why the transmission must stop now, or null: the pictures sent, the page gone. */
    private fun garde(gen: Int): String? {
        if (generation != gen) return "arrêtée"
        // The last samples still queued (the transmitter's buffers): out before the stop.
        if (modulateur?.fini == true) {
            val t = horloge()
            if (finiMs == 0L) finiMs = t
            if (t - finiMs >= QUEUE_MS) return "image envoyée"
        }
        if (horloge() - derniereVie > vieMs) return "page SatMe fermée ou injoignable"
        return null
    }

    /** Stops transmitting (the transmitter's own stop: TX off first). */
    @Synchronized
    fun arrete(raison: String = "arrêt demandé") {
        val e = tx
        e?.arrete(raison)
        generation++
        tx = null
        val pe = e?.etatCommun()
        if (etat.emet) etat = etat.copy(emet = false, raison = pe?.raison ?: raison, erreur = pe?.erreur.orEmpty())
    }

    /** A new gain while transmitting (clamped by the transmitter), kept for next time. */
    fun regleGain(db: Double): Double? {
        if (db.isNaN()) return null
        if (emetteur == Emetteur.BLADERF) gainBladerf = db else gainPluto = db
        return tx?.regleGain(db)
    }

    /** Each second: the end noticed (the transmitter stopped by itself); on QO-100, our level against the beacon's. */
    fun tic() {
        val e = tx
        if (e != null && !e.etatCommun().emet && etat.emet) {
            val pe = e.etatCommun()
            synchronized(this) { arrete(pe.raison) }
            etat = etat.copy(raison = pe.raison, erreur = pe.erreur)
            return
        }
        val o = occupe
        if (e == null || !etat.emet || !etat.versSatellite || o == null || !ReglesSstvRf.versQo100(etat.frequenceHz)) return
        val s = signauxQo100()
        val m = if (s == null) NiveauSstvQo100.Mesure(null, null) else NiveauSstvQo100.mesure(s, o.first, o.second)
        niveauQo100 = m
        val g = boucle.mesure(m.nousDb, m.baliseDb, 0L, e.etatCommun().gainDb, horloge()) ?: return
        e.regleGain(g)
    }

    /** The status for the page. */
    fun json(): JSONObject {
        val e = etat
        val pe = tx?.etatCommun()
        val t = horloge()
        val p = programme
        val o = JSONObject().put("emet", e.emet && pe?.emet == true).put("parNoeud", noeudTx() != null).put("parNoeudEnCours", e.parNoeud)
            .put("emetteur", emetteur.cle).put("modulation", modulation.cle).put("dev", deviationHz).put("preacc", preaccentuation)
            .put("khz", frequenceKhz).put("intKhz", frequenceInterieurKhz).put("intDefautKhz", DEFAUT_INTERIEUR_KHZ)
            .put("gain", gainDb).put("gainMax", gainMaxDb()).put("gainInterieur", ReglesSstvRf.GAIN_INTERIEUR_DB).put("gainCharge", ReglesSstvRf.GAIN_CHARGE_DB)
            .put("mode", mode.name).put("source", source).put("planche", plancheId).put("fichier", fichier)
            .put("rep", repetitions).put("cw", cwId).put("secondes", Math.round(secondes()))
            .put("indicatif", indicatif().trim().uppercase())
            .put("dureeMax", ReglesSstvRf.DUREE_MAX_S).put("dureeIntMax", ReglesSstvRf.DUREE_INTERIEUR_MAX_S)
            // Stopped by itself since the last tic: its reason already.
            .put("raison", if (e.emet && pe?.emet == false) pe.raison else e.raison).put("erreur", if (e.emet && pe?.emet == false) pe.erreur else e.erreur)
            .put("modes", JSONArray(SstvMode.ALL.map { JSONObject().put("nom", it.name).put("s", Math.round(SstvEncoder.seconds(it))) }))
            .put("planches", JSONArray(runCatching { planches() }.getOrDefault(emptyList()).map { JSONObject().put("id", it.first).put("nom", it.second) }))
        // What a start would meet, in each context (the page shows it before any click).
        for ((cle, d) in listOf("air" to demande(true, false, false, false), "sat" to demande(true, false, false, true),
                "int" to demande(true, true, false, false), "charge" to demande(true, false, true, false))) {
            o.put("refus_$cle", ReglesSstvRf.refus(d) ?: "").put("avert_$cle", ReglesSstvRf.avertissement(d) ?: "")
        }
        val (b, h) = ReglesSstvRf.occupe(demande(true, false, false, false))
        o.put("occupe", ReglesSstvRf.plage(b, h)).put("qo100", ReglesSstvRf.versQo100(frequenceKhz * 1000L))
            .put("descenteKhz", if (ReglesSstvRf.versQo100(frequenceKhz * 1000L)) Qo100.descenteDepuisMontee(frequenceKhz * 1000L) / 1000 else JSONObject.NULL)
        o.put("enCours", JSONObject().put("interieur", e.interieur).put("charge", e.chargeFictive).put("satellite", e.versSatellite)
            .put("emetteur", e.emetteur.cle).put("hz", e.frequenceHz).put("modulation", e.modulation.cle).put("mode", e.mode))
        if (pe != null) o.put("restant", maxOf(0L, (pe.finMs - t) / 1000)).put("tampons", pe.tampons).put("manques", pe.manques).put("gainApplique", pe.gainDb)
        if (p != null) o.put("progres", p.progres).put("image", p.image).put("images", repetitions).put("duree", p.secondes)
        val n = niveauQo100
        val bo = boucle.etat
        o.put("boucle", JSONObject().put("nous", n.nousDb ?: JSONObject.NULL).put("balise", n.baliseDb ?: JSONObject.NULL)
            .put("ecart", n.ecartDb ?: JSONObject.NULL).put("tropFort", bo.tropFort).put("message", bo.message).put("baisses", bo.baisses))
        return o
    }

    companion object {
        val SOURCES = setOf("mire", "planche", "fichier")
        const val DEBIT_AUDIO = 44_100
        /** 2 116 800 S/s: above the Pluto's lowest rate without its FIR. */
        const val FACTEUR_PLUTO = 48
        /** 1 058 400 S/s. */
        const val FACTEUR_BLADERF = 24
        const val NIVEAU = 0.5
        const val GAIN_DEFAUT_DB = -50.0
        const val CW_WPM = 20
        /** The transmitter's queue and buffers drained after the last sample made (ms). */
        const val QUEUE_MS = 2_000L
        /** 23 cm, below the part shared with radionavigation satellites. */
        const val DEFAUT_INTERIEUR_KHZ = 1_245_000L

        init { check(PlanInterieur.BANDES.any { DEFAUT_INTERIEUR_KHZ in it.basKhz..it.hautKhz }) }

        /** [b] fitted inside [w]×[h], whole, centred on black (no stretching). */
        fun ajuste(b: BufferedImage, w: Int, h: Int): BufferedImage {
            val r = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = r.createGraphics()
            g.color = Color.BLACK; g.fillRect(0, 0, w, h)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val k = minOf(w.toDouble() / b.width, h.toDouble() / b.height)
            val dw = Math.round(b.width * k).toInt().coerceIn(1, w); val dh = Math.round(b.height * k).toInt().coerceIn(1, h)
            g.drawImage(b, (w - dw) / 2, (h - dh) / 2, dw, dh, null)
            g.dispose()
            return r
        }

        fun pixels(b: BufferedImage): IntArray = b.getRGB(0, 0, b.width, b.height, null, 0, b.width).also { a -> for (i in a.indices) a[i] = a[i] and 0xFFFFFF }
    }
}
