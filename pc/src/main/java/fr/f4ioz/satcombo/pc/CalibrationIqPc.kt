/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.Emetteur
import fr.f4ioz.satcombo.datv.PlanInterieur
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.AnalyseTonIq
import fr.f4ioz.satcombo.sdr.BancIq
import fr.f4ioz.satcombo.sdr.BladerfEmission
import fr.f4ioz.satcombo.sdr.BladerfSdr
import fr.f4ioz.satcombo.sdr.CalibrationIq
import fr.f4ioz.satcombo.sdr.CorrectionIq
import fr.f4ioz.satcombo.sdr.CorrectionsIq
import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.HackrfEmission
import fr.f4ioz.satcombo.sdr.Iiod
import fr.f4ioz.satcombo.sdr.ParametresIq
import fr.f4ioz.satcombo.sdr.PlutoEmission
import fr.f4ioz.satcombo.sdr.PlutoSdr
import fr.f4ioz.satcombo.sdr.SourceIq
import fr.f4ioz.satcombo.sdr.TonIq
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A calibration tone's transmission: always indoors, low power. */
data class TonEssai(
    val frequenceHz: Long,
    val debit: Long,
    /** The attenuation (dB, ≤ −40) for the Pluto and the bladeRF; the HackRF's TX VGA stays at 0. */
    val gainDb: Double,
    val dureeMs: Long,
    val paires: Int,
    val serie: String,
    val correction: (String) -> CorrectionIq?,
    val garde: () -> String?,
)

/** What the calibration needs of the station: its transmitters, their serial numbers, a receiver lent. */
interface MaterielIq {
    /** The board's serial number, read when [lis] (a tool run, a context read) and not known yet; "" when unknown. */
    fun serie(app: Emetteur, lis: Boolean): String
    /** Why [app] may not transmit now (another transmission, the board receiving DATV, its tool missing), or null. */
    fun refus(app: Emetteur): String?
    /** The other SDR that hears [app] ([Datv.BLADERF], [Datv.PLUTO]), or null. */
    fun mesureur(app: Emetteur): String?
    /** The tone started: its transmitter, or why not. */
    fun emet(app: Emetteur, t: TonEssai, producteur: (ShortArray) -> Unit): Pair<EmetteurDatv?, String?>
    /** [rx] opened for the measurement, the SDR tab stopped if it used [rx] or [app]; an IOException says why not. */
    fun ouvreRx(rx: String, app: Emetteur): SourceIq
    /** Done with it (closed): the SDR tab's reception taken back as it was. */
    fun rendRx(rx: String)
}

/**
 * The transmitters' I/Q correction from the page (Réglages › Émission):
 * the sliders (live: what transmits with that board takes them on its next
 * buffer), a test tone, and the automatic calibration in closed loop.
 *
 * **Only indoors**: the tone at [FREQUENCE_HZ] (23 cm), the Pluto's and
 * the bladeRF's attenuation at −40 dB at most, the HackRF's TX VGA at 0
 * dB, for [DUREE_AUTO_MS] at most; only on the operator's confirmed action,
 * stopped when the page stops asking ([json]).
 *
 * **The measurement**: another SDR of the station ([MaterielIq.mesureur])
 * tuned [DECALAGE_RX_HZ] below the transmitter's LO hears the tone
 * [TON_HZ] above it, the LO leakage and the image ([BancIq]);
 * [CalibrationIq] finds the correction, kept for that board. The crystal's
 * error is read from the tone's frequency, relative to the receiver's.
 */
class CalibrationIqPc(
    private val stockage: Stockage,
    val corrections: CorrectionsIq,
    private val materiel: MaterielIq,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val vieMs: Long = 15_000L,
    /** From a correction set to its effect on the air, for each transmitter (its buffers). */
    private val attenteMs: (Emetteur) -> Long = { ATTENTES.getValue(it) },
) {
    data class Etat(
        val actif: Boolean = false,
        /** "essai" (the tone, the sliders) or "auto". */
        val mode: String = "",
        val appareil: Emetteur? = null,
        val serie: String = "",
        val recepteur: String = "",
        val etape: String = "",
        val mesure: AnalyseTonIq.Resultat? = null,
        /** The transmitter's own error (ppm), relative to the receiver's crystal. */
        val ppmMesure: Double? = null,
        val erreur: String = "",
        val journal: List<String> = emptyList(),
        val resultat: String = "",
    )

    @Volatile var etat = Etat(); private set
    @Volatile private var generation = 0
    @Volatile private var tx: EmetteurDatv? = null
    @Volatile private var derniereVie = 0L

    /** The attenuation for the tone (dB): −40 at most. */
    var gainTxDb: Double
        get() = stockage.getFloat("iq_gain_tx", GAIN_DEFAUT_DB.toFloat()).toDouble().coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)
        set(v) { if (!v.isNaN()) stockage.edit().putFloat("iq_gain_tx", v.coerceIn(GAIN_MIN_DB, GAIN_MAX_DB).toFloat()).apply() }

    /** A calibration or a tone uses [e] now: nothing else may transmit with it nor open it. */
    fun tient(e: Emetteur): Boolean = etat.actif && etat.appareil == e
    val enCours: Boolean get() = etat.actif

    /** The correction of [e]'s board ([serie] read if needed), or null when its serial is unknown. */
    fun correction(e: Emetteur, lis: Boolean = false): CorrectionIq? {
        val s = materiel.serie(e, lis)
        return if (s.isEmpty()) null else corrections.pour(cle(e), s)
    }

    /** New values from the sliders: kept, and live on what transmits with that board. Null when done, else why not. */
    fun regle(e: Emetteur, p: ParametresIq): String? {
        val s = materiel.serie(e, true).ifEmpty { return "numéro de série du " + e.libelle + " inconnu : le brancher" }
        corrections.enregistre(cle(e), s, p)
        return null
    }

    /**
     * The tone transmitted with [e] ([auto]: and the calibration run), on
     * the operator's confirmed action only; [mesurer]: heard by another SDR
     * for the sliders' effect. Null once started, else why not.
     */
    @Synchronized
    fun demarre(e: Emetteur, auto: Boolean, mesurer: Boolean, confirme: Boolean): String? {
        if (!confirme) return "émission non confirmée"
        if (etat.actif) return "une correction I/Q est déjà en cours"
        materiel.refus(e)?.let { return it }
        val rx = if (auto || mesurer) materiel.mesureur(e) ?: return "aucun autre SDR pour mesurer le " + e.libelle else null
        val serie = materiel.serie(e, true)
        if (serie.isEmpty() && e != Emetteur.PLUTO) return "numéro de série du " + e.libelle + " inconnu : le brancher"
        val gen = ++generation
        derniereVie = horloge()
        etat = Etat(actif = true, mode = if (auto) "auto" else "essai", appareil = e, serie = serie, recepteur = rx.orEmpty(), etape = "préparation…")
        Thread({ deroule(gen, e, serie, rx, auto) }, "satme-iq").apply { isDaemon = true; start() }
        return null
    }

    /** Stops the tone and the calibration (the correction found so far not kept). */
    fun arrete() {
        generation++
        tx?.arrete("arrêt demandé")
    }

    private fun garde(gen: Int): String? = when {
        gen != generation -> "arrêtée"
        horloge() - derniereVie > vieMs -> "page SatMe fermée ou injoignable"
        else -> null
    }

    private fun journal(gen: Int, l: String) {
        if (gen != generation) return
        etat = etat.copy(journal = (etat.journal + l).takeLast(40))
    }

    private fun deroule(gen: Int, e: Emetteur, serie0: String, rx: String?, auto: Boolean) {
        var src: SourceIq? = null
        var em: EmetteurDatv? = null
        var holder: CorrectionIq? = null
        var avant: ParametresIq? = null
        var retenu = false
        try {
            if (rx != null) {
                etat = etat.copy(etape = "ouverture du récepteur " + Datv.libelleRecepteur(rx) + "…")
                src = materiel.ouvreRx(rx, e)
                src.setSampleRate(DEBIT.toInt())
                src.setFreqCorrection(0)
                src.setCenterFreq(FREQUENCE_HZ - DECALAGE_RX_HZ.toLong())
                src.setGain(GAIN_RX_DEPART * 10)
                // Its stream started and flowing before anything is transmitted.
                val b = ShortArray(262_144)
                val fin = horloge() + 15_000
                while (true) {
                    val n = src.read16(b, 500)
                    if (n > 0) break
                    if (n < 0 || horloge() > fin || gen != generation)
                        throw IOException(Datv.libelleRecepteur(rx) + " ne donne pas d'échantillons")
                }
            }
            if (gen != generation) return
            etat = etat.copy(etape = "émission de la porteuse d'essai…")
            val ton = TonIq(TON_HZ, DEBIT.toDouble())
            var serie = serie0
            val t = TonEssai(FREQUENCE_HZ, DEBIT, gainTxDb, if (auto) DUREE_AUTO_MS else DUREE_ESSAI_MS, if (e == Emetteur.HACKRF) PAIRES_HACKRF else PAIRES, serie0,
                correction = { s ->
                    if (serie.isEmpty()) serie = s
                    if (s.isEmpty()) null else corrections.pour(cle(e), s).also { c -> holder = c; avant = c.parametres }
                },
                garde = { garde(gen) })
            val (x, refus) = materiel.emet(e, t) { b -> ton.remplis(b) }
            if (refus != null || x == null) { etat = etat.copy(erreur = refus ?: "émission impossible"); return }
            em = x; tx = x
            etat = etat.copy(serie = serie)
            val c = holder ?: throw IOException("numéro de série du " + e.libelle + " inconnu")
            if (src == null) {
                etat = etat.copy(etape = "porteuse d'essai à 1245 MHz")
                while (gen == generation && x.etatCommun().emet) Thread.sleep(200)
                return
            }
            // Narrow bins (18 Hz): the tone, clean, stands far above a weak link's noise.
            val banc = BancIq(c, src, DEBIT.toDouble(), TON_HZ, DECALAGE_RX_HZ, attenteMs(e), paires = 2 * POINTS_FFT,
                analyse = AnalyseTonIq(DEBIT.toDouble(), POINTS_FFT))
            etat = etat.copy(etape = "réglage du gain de réception…")
            val premier = cale(gen, src, banc, rx!!) ?: throw IOException(
                Datv.libelleRecepteur(rx) + " n'entend pas la porteuse à 1245 MHz (antenne ? câble ?)")
            ppm(gen, premier, c)
            if (!auto) {
                etat = etat.copy(etape = "porteuse d'essai à 1245 MHz, mesurée par " + Datv.libelleRecepteur(rx))
                while (gen == generation && x.etatCommun().emet) {
                    val r = banc.ecoute() ?: continue
                    if (gen != generation) break
                    ppm(gen, r, c)
                }
                return
            }
            etat = etat.copy(etape = "calibration automatique…")
            val depart = c.parametres
            val cal = CalibrationIq({ p -> banc.mesure(p)?.also { r -> if (gen == generation) etat = etat.copy(mesure = r) } },
                { journal(gen, it) }, { gen != generation || !x.etatCommun().emet })
            val r = cal.lance(depart)
            if (r == null || gen != generation) {
                if (gen == generation) etat = etat.copy(erreur = x.etatCommun().erreur.ifEmpty { "porteuse perdue pendant la calibration" })
                return
            }
            val ppmTx = depart.ppm + r.apres.ecartHz / FREQUENCE_HZ * 1e6
            // The bladeRF's crystal is the station's reference: its own error not corrected from another SDR's.
            val trouve = r.parametres.copy(ppm = if (e == Emetteur.BLADERF) depart.ppm else ppmTx)
            val s = serie.ifEmpty { throw IOException("numéro de série inconnu") }
            corrections.enregistre(cle(e), s, trouve)
            retenu = true
            val texte = String.format(Locale.FRANCE, "%s : fuite %.1f → %.1f dBc, image %.1f → %.1f dBc, erreur %+.2f ppm (par rapport au %s), %d mesures",
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(horloge())),
                r.avant.fuiteDbc, r.apres.fuiteDbc, r.avant.imageDbc, r.apres.imageDbc, ppmTx, Datv.libelleRecepteur(rx), r.mesures)
            corrections.gardeResultat(cle(e), s, texte)
            etat = etat.copy(resultat = texte, mesure = r.apres, ppmMesure = ppmTx, etape = "calibration terminée")
        } catch (x: InterruptedException) {
            // stopping
        } catch (x: Exception) {
            if (gen == generation) etat = etat.copy(erreur = x.message ?: x.javaClass.simpleName)
        } finally {
            em?.arrete(if (retenu) "calibration terminée" else "arrêtée")
            tx = null
            // A calibration not finished: the correction as it was.
            if (auto && !retenu) avant?.let { a -> holder?.parametres = a }
            src?.let { runCatching { it.close() } }
            if (rx != null) runCatching { materiel.rendRx(rx) }
            val fin = em?.etatCommun()
            etat = etat.copy(actif = false, etape = if (etat.erreur.isEmpty() && !retenu) (fin?.erreur?.takeIf { it.isNotEmpty() } ?: "arrêtée") else etat.etape,
                erreur = etat.erreur.ifEmpty { fin?.erreur.orEmpty() })
        }
    }

    /** The receiver's gain set so that the tone stands well above its noise without clipping; the first levels heard. */
    private fun cale(gen: Int, src: SourceIq, banc: BancIq, rx: String): AnalyseTonIq.Resultat? {
        val max = if (rx == Datv.PLUTO) 70 else 60
        var g = GAIN_RX_DEPART
        var dernier: AnalyseTonIq.Resultat? = null
        repeat(8) {
            if (gen != generation) return null
            src.setGain(g * 10)
            val r = banc.ecoute()
            journal(gen, "gain de réception $g dB : " + (r?.let { String.format(Locale.FRANCE, "crête %.0f dBFS, bruit %.0f dBc", it.creteDbfs, it.bruitDbc) } ?: "porteuse non entendue"))
            when {
                // Not 20 dB above the noise: not the tone (a noise peak).
                r == null || r.bruitDbc > -20 -> { if (g >= max) return dernier; g = minOf(max, g + 10) }
                r.creteDbfs > -4 -> { if (g <= 0) return r; g = maxOf(0, g - 10); dernier = r }
                r.creteDbfs < -30 && g < max -> { g = minOf(max, g + 10); dernier = r }
                else -> return r
            }
        }
        return dernier
    }

    private fun ppm(gen: Int, r: AnalyseTonIq.Resultat, c: CorrectionIq) {
        if (gen != generation) return
        etat = etat.copy(mesure = r, ppmMesure = c.parametres.ppm + r.ecartHz / FREQUENCE_HZ * 1e6)
    }

    /** For the page (and its sign of life while a tone is on). */
    fun json(): JSONObject {
        derniereVie = horloge()
        val e = etat
        val o = JSONObject().put("actif", e.actif).put("mode", e.mode).put("appareil", e.appareil?.cle.orEmpty())
            .put("serie", e.serie).put("recepteur", e.recepteur).put("recepteurNom", if (e.recepteur.isEmpty()) "" else Datv.libelleRecepteur(e.recepteur))
            .put("etape", e.etape).put("erreur", e.erreur).put("resultat", e.resultat).put("journal", JSONArray(e.journal))
            .put("frequenceMhz", FREQUENCE_HZ / 1e6).put("tonKhz", TON_HZ / 1000).put("gainTx", gainTxDb)
            .put("gainTxMax", GAIN_MAX_DB).put("gainTxMin", GAIN_MIN_DB)
        e.mesure?.let { m ->
            o.put("mesure", JSONObject().put("fuite", arrondi(m.fuiteDbc)).put("image", arrondi(m.imageDbc)).put("bruit", arrondi(m.bruitDbc))
                .put("crete", arrondi(m.creteDbfs)).put("ecartHz", arrondi(m.ecartHz)))
        }
        e.ppmMesure?.let { o.put("ppmMesure", Math.round(it * 1000) / 1000.0) }
        val l = JSONArray()
        for (a in listOf(Emetteur.PLUTO, Emetteur.BLADERF, Emetteur.HACKRF)) {
            // Not read yet since SatMe started: the only board of that kind kept here.
            val s = materiel.serie(a, false).ifEmpty { corrections.connues().singleOrNull { it.first == cle(a) }?.second.orEmpty() }
            val j = JSONObject().put("cle", a.cle).put("nom", a.libelle).put("serie", s).put("mesureur", materiel.mesureur(a)?.let { Datv.libelleRecepteur(it) }.orEmpty())
            if (s.isNotEmpty()) {
                val p = corrections.pour(cle(a), s).parametres
                j.put("p", JSONObject().put("dcI", p.dcI).put("dcQ", p.dcQ).put("gain", p.gain).put("phase", p.phaseDeg).put("ppm", p.ppm))
                    .put("resultat", corrections.resultat(cle(a), s))
            }
            l.put(j)
        }
        return o.put("appareils", l)
    }

    private fun arrondi(v: Double) = Math.round(v * 10) / 10.0

    companion object {
        /** 23 cm, inside the indoor test's band, below the part shared with radionavigation satellites. */
        const val FREQUENCE_HZ = 1_245_000_000L
        /** Above the AD936x's lowest rate without its FIR (2.083 MS/s), within the HackRF's. */
        const val DEBIT = 2_400_000L
        const val TON_HZ = 200_000.0
        /** The transmitter's LO this far above the receiver's centre: tone, LO and image at +600, +400, +200 kHz. */
        const val DECALAGE_RX_HZ = 400_000.0
        const val PAIRES = 16_384
        /** hackrf_transfer takes 128 k pairs at a time: smaller buffers would look like underruns. */
        const val PAIRES_HACKRF = 65_536
        const val POINTS_FFT = 131_072
        const val DUREE_AUTO_MS = 150_000L
        const val DUREE_ESSAI_MS = 60_000L
        const val GAIN_MAX_DB = PlanInterieur.GAIN_PLUTO_DB
        const val GAIN_MIN_DB = -89.0
        const val GAIN_DEFAUT_DB = -50.0
        const val GAIN_RX_DEPART = 30
        /** The transmitters' queues: the Pluto's few buffers, the HackRF's USB transfers, bladeRF-cli's 2 M samples. */
        val ATTENTES = mapOf(Emetteur.PLUTO to 250L, Emetteur.HACKRF to 900L, Emetteur.BLADERF to 1_600L)

        fun cle(e: Emetteur): String = when (e) {
            Emetteur.PLUTO -> CorrectionsIq.PLUTO
            Emetteur.BLADERF -> CorrectionsIq.BLADERF
            Emetteur.HACKRF -> CorrectionsIq.HACKRF
        }

        init { check(PlanInterieur.BANDES.any { FREQUENCE_HZ - 1_000_000 >= it.basKhz * 1000 && FREQUENCE_HZ + 1_000_000 <= it.hautKhz * 1000 }) }
    }
}

/**
 * The station's own boards for the calibration: the transmitters through
 * their classes (all their guards), the measuring receiver lent by the SDR
 * tab as for DATV's software receiver, and given back as it was.
 */
class MaterielIqPc(
    private val sdr: SdrPc,
    private val emission: EmissionDatvPc,
    private val emissionSstv: () -> EmissionSstvPc?,
    private val datv: DatvPc,
    private val reprendQo100: () -> String?,
    private val qo100EnCours: () -> Boolean,
) : MaterielIq {
    @Volatile private var seriePluto = ""
    @Volatile private var aRendre: Boolean? = null

    override fun serie(app: Emetteur, lis: Boolean): String = when (app) {
        Emetteur.PLUTO -> seriePluto.ifEmpty {
            if (!lis) "" else runCatching {
                Iiod.connecte(sdr.plutoHote, sdr.plutoPort, 2000).use { PlutoSdr.Contexte.lis(it.contexte()).attributs["hw_serial"].orEmpty() }
            }.getOrDefault("").also { seriePluto = it }
        }
        Emetteur.HACKRF -> emission.infoHackrf.cartes.singleOrNull()?.serie
            ?: (if (lis && !emission.etat.emet) emission.detecteHackrf().cartes.singleOrNull()?.serie else null).orEmpty()
        Emetteur.BLADERF -> (sdr.infoBladerf.carte?.serie ?: emission.infoBladerf.carte?.serie)
            ?: (if (lis && !sdr.bladerfEnReception && !emission.etat.emet && datv.sdrTenu == null) sdr.detecteBladerf().carte?.serie else null).orEmpty()
    }

    override fun refus(app: Emetteur): String? {
        if (emission.etat.emet) return "une émission DATV est en cours : l'arrêter d'abord"
        if (emissionSstv()?.etat?.emet == true) return "une émission SSTV est en cours : l'arrêter d'abord"
        if (datv.sdrTenu != null) return Datv.libelleRecepteur(datv.sdrTenu!!) + " reçoit la DATV : arrêter cette réception d'abord"
        return when (app) {
            Emetteur.HACKRF -> if (emission.outilHackrf("hackrf_transfer") == null) emission.conseilHackrf() else null
            Emetteur.BLADERF -> if (sdr.programmeBladerf() == null) sdr.conseilBladerf() else null
            Emetteur.PLUTO -> null
        }
    }

    override fun mesureur(app: Emetteur): String? {
        val blade = sdr.programmeBladerf() != null
        return when (app) {
            Emetteur.PLUTO -> if (blade) Datv.BLADERF else null
            Emetteur.HACKRF -> if (blade) Datv.BLADERF else Datv.PLUTO
            Emetteur.BLADERF -> Datv.PLUTO
        }
    }

    override fun emet(app: Emetteur, t: TonEssai, producteur: (ShortArray) -> Unit): Pair<EmetteurDatv?, String?> {
        val g = minOf(t.gainDb, CalibrationIqPc.GAIN_MAX_DB)
        return when (app) {
            Emetteur.PLUTO -> {
                val px = PlutoEmission(sdr.plutoHote, sdr.plutoPort, gainMaxDb = CalibrationIqPc.GAIN_MAX_DB)
                val r = px.demarre(PlutoEmission.Reglage(t.frequenceHz, t.debit, g, t.dureeMs, plafondDb = CalibrationIqPc.GAIN_MAX_DB,
                    paires = t.paires, garde = t.garde, correction = t.correction), producteur)
                (if (r == null) px else null) to r
            }
            Emetteur.BLADERF -> {
                val prog = sdr.programmeBladerf() ?: return null to sdr.conseilBladerf()
                val bx = BladerfEmission(prog, gainMaxDb = CalibrationIqPc.GAIN_MAX_DB)
                val r = bx.demarre(BladerfEmission.Reglage(t.frequenceHz, t.debit, g, t.dureeMs, plafondDb = CalibrationIqPc.GAIN_MAX_DB,
                    paires = t.paires, serie = t.serie, garde = t.garde, correction = t.correction), producteur)
                (if (r == null) bx else null) to r
            }
            Emetteur.HACKRF -> {
                val prog = emission.outilHackrf("hackrf_transfer") ?: return null to emission.conseilHackrf()
                val hx = HackrfEmission(prog, vgaMax = 0)
                val r = hx.demarre(HackrfEmission.Reglage(t.frequenceHz, t.debit, 0, t.dureeMs, bandeHz = 1_500_000L, plafondVgaDb = 0,
                    paires = t.paires, serie = t.serie, garde = t.garde, correction = t.correction), producteur)
                (if (r == null) hx else null) to r
            }
        }
    }

    override fun ouvreRx(rx: String, app: Emetteur): SourceIq {
        val tx = if (app == Emetteur.PLUTO) Datv.PLUTO else if (app == Emetteur.BLADERF) Datv.BLADERF else ""
        val sienne = (sdr.plutoEnReception && (rx == Datv.PLUTO || tx == Datv.PLUTO)) || (sdr.bladerfEnReception && (rx == Datv.BLADERF || tx == Datv.BLADERF))
        if (sienne) { aRendre = qo100EnCours(); sdr.arrete() } else aRendre = null
        sdr.datvTient = rx
        return try {
            when (rx) {
                Datv.PLUTO -> PlutoSdr.connecte(sdr.plutoHote, sdr.plutoPort)
                Datv.BLADERF -> {
                    val prog = sdr.programmeBladerf() ?: throw IOException(sdr.conseilBladerf())
                    val c = sdr.infoBladerf.carte
                    BladerfSdr.ouvre(prog, c?.serie.orEmpty(), c?.libelle ?: "bladeRF 2.0")
                }
                else -> throw IOException("récepteur inconnu : $rx")
            }
        } catch (x: Exception) {
            rendRx(rx)
            throw if (x is IOException) x else IOException(x.message)
        }
    }

    override fun rendRx(rx: String) {
        sdr.datvTient = null
        val r = aRendre ?: return
        aRendre = null
        Thread({
            Thread.sleep(1500)
            if (sdr.datvTient != null) return@Thread
            if (r) reprendQo100() else sdr.demarre()
        }, "satme-iq-rend-sdr").apply { isDaemon = true; start() }
    }
}
