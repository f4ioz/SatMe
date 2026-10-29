/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.SonDistant
import fr.f4ioz.satcombo.domain.GardeNiveauQo100
import fr.f4ioz.satcombo.domain.JournalEmissionQo100
import fr.f4ioz.satcombo.domain.MacrosCw
import fr.f4ioz.satcombo.domain.MonteeQo100
import fr.f4ioz.satcombo.domain.MonteeQo100.CorrectionPluto
import fr.f4ioz.satcombo.domain.MonteeQo100.Mode
import fr.f4ioz.satcombo.domain.MonteeQo100.PlanPluto
import fr.f4ioz.satcombo.domain.OccupationQo100
import fr.f4ioz.satcombo.domain.ProfilMontee
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SignalQo100
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.ManipulateurCw
import fr.f4ioz.satcombo.sdr.MelangeurNco
import fr.f4ioz.satcombo.sdr.ModulateurAudio
import fr.f4ioz.satcombo.sdr.ModulationAudio
import fr.f4ioz.satcombo.sdr.PlutoEmission
import fr.f4ioz.satcombo.sdr.PlutoSdr
import fr.f4ioz.satcombo.sdr.TamponAudio
import fr.f4ioz.satcombo.sstv.NiveauSstvQo100
import fr.f4ioz.satcombo.sstv.ReglesSstvRf
import fr.f4ioz.satcombo.station.EmissionPoste
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * QO-100's narrow band from the ADALM-PLUTO straight on 2.4 GHz: SSB from
 * the page's microphone or a sound card of this PC (FT8 through WSJT-X and a
 * loopback card), CW keyed from the page — the PTT held by a button as for
 * the IC-9700 ([EmissionPoste]: one client, its 2 s watchdog, the time-out),
 * the Pluto started at each press and stopped at each release by
 * [PlutoEmission], the only code that writes to it, with all its guards.
 *
 * **Nothing transmits by itself**: no PTT at start, none from a stored
 * setting, none after a reconnection; « essai sur charge » (the gain held
 * at −40 dB) is where every SatMe-PC start puts it back. On the air: the
 * band plan per mode ([MonteeQo100.refus]), a callsign, the gain under its
 * cap, one's own signal measured against the beacon when SatMe hears the
 * downlink, and LEILA heard while transmitting **cuts at once**.
 *
 * Also the uplink profiles (fixed, portable: chain, antenna, amplifier,
 * cable, the Pluto's cap for each; [ProfilMontee]) and the two chains'
 * frequency checks.
 *
 * Opt-in helpers, none of which ever keys anything: the **level guard**
 * ([GardeNiveauQo100]: the gain lowered, or the transmission cut, when our
 * signal comes back stronger than the beacon), the **listen-before-talk**
 * warning ([OccupationQo100]), the CW **memories** with their fields
 * ([MacrosCw]) and a keyer fed key by key, the **voice memories**
 * ([MemoiresVoixPc], played under the PTT the operator clicks, released at
 * their end), the **simulation** ([EmetteurSimulePc]: nothing radiated, no
 * device opened — the demonstration's only way), and the **journal** of
 * every over ([JournalEmissionQo100]).
 */
class EmissionBandeEtroitePc(
    private val stockage: Stockage,
    private val indicatif: () -> String,
    private val plutoHote: () -> String = { PlutoSdr.HOTE },
    private val plutoPort: () -> Int = { PlutoSdr.PORT },
    /** Why the Pluto is busy (receiving), or null. */
    private val plutoOccupe: () -> String? = { null },
    /** Why another transmission runs (DATV, SSTV, the I/Q calibration, the IC-9700), or null. */
    private val autreEmission: () -> String? = { null },
    /** The highest TX gain ever applied (Réglages › Émission, shared with DATV and SSTV). */
    private val gainMaxDb: () -> Double = { -10.0 },
    /** « émission par le réseau autorisée » (Réglages › Émission, this PC only). */
    private val reseauAutorise: () -> Boolean = { false },
    /** Where the QO-100 cockpit listens (sky, nominal), for the tied tuning. */
    private val descenteCockpit: () -> Long? = { null },
    /** QO-100's narrow band signals while SatMe hears it, or null. */
    private val signauxQo100: () -> List<SignalQo100>? = { null },
    /** LEILA heard lately. */
    private val leila: () -> Boolean = { false },
    /** The sound cards that can record ([SonPc.appareils]). */
    private val cartes: () -> List<String> = { emptyList() },
    /** Opens a card for this transmitter: its closer, or null and why ([SonPc.capture]). */
    private val ouvreCarte: (String, (ShortArray, Int, Int) -> Unit) -> Pair<(() -> Unit)?, String> = { _, _ -> null to "pas de carte son" },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Starts the transmitter (the tests' fake); by default a [PlutoEmission]. The transmitter, or null and why. */
    private val fabrique: ((PlutoEmission.Reglage, Double, (ShortArray) -> Unit) -> Pair<EmetteurDatv?, String?>)? = null,
    /** Is the Pluto answering on its address? (a TCP connection opened and closed, nothing sent); null: not probed. */
    private val sonde: (() -> Boolean)? = null,
    /** The station's locator (Réglages), for the CW memories' {LOC}. */
    private val locator: () -> String = { "" },
    /** Where the voice memories are kept; null: none. */
    dossierVoix: java.io.File? = null,
    /** Only the simulation (the demonstration: never a device). */
    private val simulationSeule: () -> Boolean = { ModeDemo.actif },
    /** Starts the simulated transmitter (the tests' own); by default an [EmetteurSimulePc]. */
    private val fabriqueSimulation: ((PlutoEmission.Reglage, Double, (ShortArray) -> Unit) -> EmetteurDatv)? = null,
) {
    // ------------------------------------------------------------ settings (parameters only, never a transmission)

    var mode: Mode
        get() = Mode.de(stockage.getString("be_mode", "blu")) ?: Mode.BLU
        set(v) { stockage.edit().putString("be_mode", v.cle).apply() }
    /** The uplink dial follows the cockpit's downlink. */
    var lie: Boolean
        get() = stockage.getBoolean("be_lie", true)
        set(v) { stockage.edit().putBoolean("be_lie", v).apply() }
    /** The dial typed (Hz, uplink) when not tied. */
    var cadranManuelHz: Long
        get() = stockage.getLong("be_cadran", MonteeQo100.cadranParDefaut(Mode.BLU))
        set(v) { stockage.edit().putLong("be_cadran", v).apply() }
    /** "micro" (the page's) or "carte" (a sound card of this PC). */
    var source: String
        get() = stockage.getString("be_source", "micro").takeIf { it in SOURCES } ?: "micro"
        set(v) { if (v in SOURCES) stockage.edit().putString("be_source", v).apply() }
    var carte: String
        get() = stockage.getString("be_carte", "") ?: ""
        set(v) { stockage.edit().putString("be_carte", v.trim().take(200)).apply() }
    /** The Pluto's attenuation asked (dB, ≤ 0); low by default. */
    var gainDb: Double
        get() = stockage.getFloat("be_gain", GAIN_DEFAUT_DB.toFloat()).toDouble()
        set(v) { if (!v.isNaN()) stockage.edit().putFloat("be_gain", v.coerceIn(PlutoEmission.GAIN_MIN_DB, 0.0).toFloat()).apply() }
    /** This transmitter's own cap (dB), under the shared one. */
    var plafondDb: Double
        get() = stockage.getFloat("be_plafond", PLAFOND_DEFAUT_DB.toFloat()).toDouble()
        set(v) { if (!v.isNaN()) stockage.edit().putFloat("be_plafond", v.coerceIn(PlutoEmission.GAIN_MIN_DB, 0.0).toFloat()).apply() }
    /** The time-out (minutes, 1 to 10). */
    var totMin: Int
        get() = stockage.getInt("be_tot_min", 3).coerceIn(1, 10)
        set(v) { stockage.edit().putInt("be_tot_min", v.coerceIn(1, 10)).apply(); poste.totMs = v.coerceIn(1, 10) * 60_000L }
    var wpm: Int
        get() = stockage.getInt("be_wpm", 20).coerceIn(5, 40)
        set(v) { stockage.edit().putInt("be_wpm", v.coerceIn(5, 40)).apply(); cw.wpm = v.coerceIn(5, 40) }
    /** The Pluto's error at 2 400 MHz (Hz, + : it transmits high). */
    var correctionHz: Long
        get() = stockage.getLong("be_pluto_corr", 0L)
        set(v) { if (CorrectionPluto.credible(v)) stockage.edit().putLong("be_pluto_corr", v).apply() }

    /**
     * Into a dummy load: the gain held at −40 dB. **Not stored**: every
     * SatMe-PC start comes back to it; leaving it takes the operator's
     * word on the page.
     */
    @Volatile var charge = true

    /**
     * Simulation: the whole chain runs (PTT, guards, keyer, memories) but
     * the samples go nowhere — no device opened, nothing radiated. Stored;
     * the demonstration has nothing else.
     */
    var simulation: Boolean
        get() = simulationSeule() || stockage.getBoolean("be_simulation", false)
        set(v) { stockage.edit().putBoolean("be_simulation", v).apply() }

    /** Where the next over goes: "simulation", "charge" or "air". */
    val destination: String get() = if (simulation) "simulation" else if (charge) "charge" else "air"

    // ------------------------------------------------------------ the level guard (opt-in)

    /** Lower the gain, or cut, when our signal comes back stronger than the beacon. Off by default. */
    var gardeNiveau: Boolean
        get() = stockage.getBoolean("be_garde_niveau", false)
        set(v) { stockage.edit().putBoolean("be_garde_niveau", v).apply() }
    /** The level not to exceed, dB relative to the beacon (0, −3, −6, −10). */
    var gardeSeuilDb: Double
        get() = stockage.getFloat("be_garde_seuil", 0f).toDouble().let { s -> GardeNiveauQo100.SEUILS.minByOrNull { kotlin.math.abs(it - s) } ?: 0.0 }
        set(v) { stockage.edit().putFloat("be_garde_seuil", v.coerceIn(-10.0, 0.0).toFloat()).apply() }
    @Volatile private var gardeNiv = GardeNiveauQo100(gardeSeuilDb)
    @Volatile private var gardeMessage = ""

    // ------------------------------------------------------------ the CW memories, the voice memories, the journal

    var memoiresCw: List<MacrosCw.Memoire>
        get() = MacrosCw.lisListe(stockage.getString("be_cw_mem", null))
        set(v) { stockage.edit().putString("be_cw_mem", MacrosCw.ecrisListe(v)).apply() }
    /** The other station ({LUI}). */
    var lui: String
        get() = stockage.getString("be_cw_lui", "") ?: ""
        set(v) { stockage.edit().putString("be_cw_lui", MacrosCw.indicatif(v)).apply() }
    /** The report sent ({RST}). */
    var rst: String
        get() = stockage.getString("be_cw_rst", "599") ?: "599"
        set(v) { stockage.edit().putString("be_cw_rst", v.trim().uppercase().filter { it.isLetterOrDigit() }.take(3).ifBlank { "599" }).apply() }

    val voix = MemoiresVoixPc(dossierVoix)
    /** The voice memory asked for the next press (by [appuie]), then playing. */
    @Volatile private var voixDemandee: Int? = null
    @Volatile private var lecture: LectureVoix? = null
    @Volatile private var lectureFinMs = 0L

    val journal = JournalEmissionQo100().also { j -> runCatching { j.reprends(stockage.getString("be_journal", null)) } }

    // ------------------------------------------------------------ profiles

    var profils: List<ProfilMontee>
        get() = ProfilMontee.lisListe(stockage.getString("be_profils", null))
        set(v) { stockage.edit().putString("be_profils", ProfilMontee.ecrisListe(v)).apply() }
    var profilNom: String
        get() = stockage.getString("be_profil", "Fixe") ?: "Fixe"
        set(v) { stockage.edit().putString("be_profil", v.trim().take(40)).apply() }
    val profil: ProfilMontee get() = profils.let { l -> l.firstOrNull { it.nom.equals(profilNom, true) } ?: l.first() }

    // ------------------------------------------------------------ the frequency

    /** The uplink dial now: the cockpit's, tied; else the one typed. */
    val cadranHz: Long get() = if (lie) descenteCockpit()?.let { MonteeQo100.cadranLie(it) } ?: cadranManuelHz else cadranManuelHz

    /** Where the signal's centre (the CW carrier) lands on the uplink. */
    fun porteuseHz(m: Mode = mode, c: Long = cadranHz): Long = if (m == Mode.CW) c + MonteeQo100.TON_CW_HZ.toLong() else c + 1_500L

    // ------------------------------------------------------------ the transmission

    private val cw = ManipulateurCw(AUDIO, wpm, MonteeQo100.TON_CW_HZ)
    private val tampon = TamponAudio(AUDIO)
    @Volatile private var tx: EmetteurDatv? = null
    @Volatile private var nco: MelangeurNco? = null
    @Volatile private var plan: PlanPluto? = null
    /** The over under way: its mode, its dial, where it goes, what it sends, since when, the most seen against the beacon. */
    private data class EnCours(val mode: Mode, val cadran: Long, val destination: String, val source: String, val debutMs: Long,
                               val ecartMaxDb: Double? = null) {
        val surLAir: Boolean get() = destination == "air"
    }
    @Volatile private var enCours: EnCours? = null
    @Volatile private var fermeCarte: (() -> Unit)? = null
    @Volatile private var carteErreur = ""
    @Volatile private var dernierRefus: String? = null
    @Volatile private var generation = 0
    @Volatile var niveau = NiveauSstvQo100.Mesure(null, null); private set
    @Volatile private var leilaCoupeMs = 0L
    private val trames = AtomicLong()
    private val ignorees = AtomicLong()
    @Volatile private var crete = 0.0
    @Volatile private var creteMs = 0L

    /** Why [m] on [c] may not go now, or null (the checks before any keying). */
    fun refus(m: Mode = mode, c: Long = cadranHz): String? {
        if (!simulation) ModeDemo.refus("l'émission BLU par le Pluto")?.let { return it }
        if (!ReglesSstvRf.indicatifValide(indicatif())) return "indicatif manquant : le donner dans Réglages › Station avant toute émission"
        autreEmission()?.let { return it }
        // The simulation opens no device: the Pluto may be receiving meanwhile.
        if (!simulation) plutoOccupe()?.let { return it }
        MonteeQo100.refus(m, c)?.let { return it }
        if (leila() || horloge() - leilaCoupeMs < LEILA_ATTENTE_MS) return "LEILA entendue à l'instant : attendre et baisser le gain avant de réémettre"
        return null
    }

    val poste = EmissionPoste(object : EmissionPoste.Lien {
        override fun pret(): String? {
            val e = tx
            if (e != null && !e.etatCommun().emet) return "émetteur arrêté : " + (e.etatCommun().erreur.ifBlank { e.etatCommun().raison })
            return if (e != null) MonteeQo100.refus(mode, cadranHz)?.let { "fréquence devenue interdite : $it" } else refus()
        }
        override fun joignable(): Boolean = true
        override suspend fun ptt(on: Boolean): Boolean = if (on) allume() else { eteint("PTT relâché"); true }
    }, horloge).also { it.totMs = totMin * 60_000L }

    /** The transmitter up for the press: its frequency plan, its source, its gain under every cap. */
    private fun allume(): Boolean {
        dernierRefus = null
        if (tx != null) eteint("relancé")
        val m = mode
        val c = cadranHz
        val corr = correctionHz
        val p = PlanPluto.pour(c, corr)
        val gen = ++generation
        val dest = destination
        val nv = voixDemandee
        voixDemandee = null
        lecture = null; lectureFinMs = 0L
        val src: (ShortArray) -> Int = when {
            nv != null -> {
                if (m != Mode.BLU) { dernierRefus = "mémoire vocale : en BLU seulement"; return false }
                val pcm = voix.lis(nv) ?: run { dernierRefus = "mémoire vocale $nv vide"; return false }
                LectureVoix(pcm).also { lecture = it }
            }
            m == Mode.CW -> cw
            source == "carte" -> {
                tampon.vide()
                val nom = carte.ifBlank { dernierRefus = "carte son à choisir (source « carte son du PC »)"; return false }
                val (f, err) = runCatching { ouvreCarte(nom) { pcm, n, taux -> tampon.pousse(pcm, n, taux) } }.getOrElse { null to (it.message ?: "carte son") }
                if (f == null) { dernierRefus = "carte son : $err"; return false }
                fermeCarte = f; carteErreur = ""
                tampon
            }
            else -> { tampon.vide(); tampon }
        }
        val mod = ModulateurAudio(ModulationAudio.USB, AUDIO, (PlanPluto.DEBIT / AUDIO).toInt(), niveau = NIVEAU, source = src)
        val melangeur = MelangeurNco(PlanPluto.DEBIT, p.ncoHz)
        val plafond = if (dest == "charge") ReglesSstvRf.GAIN_CHARGE_DB else 0.0
        val max = minOf(gainMaxDb(), plafondDb, profil.plafondPlutoDb ?: 0.0, 0.0)
        val r = PlutoEmission.Reglage(p.loHz, PlanPluto.DEBIT, gainDb, totMin * 60_000L + 5_000L, plafondDb = plafond,
            paires = EmissionDatvPc.paires(PlanPluto.DEBIT), garde = { garde(gen) })
        val producteur: (ShortArray) -> Unit = { b -> mod.remplis(b, b.size / 2); melangeur.applique(b, b.size / 2) }
        val (e, refus) = runCatching {
            if (dest == "simulation") (fabriqueSimulation?.invoke(r, max, producteur) ?: EmetteurSimulePc(r, max, producteur, horloge)) to null
            else fabrique?.invoke(r, max, producteur) ?: PlutoEmission(plutoHote(), plutoPort(), gainMaxDb = max).let { px ->
                val non = px.demarre(r, producteur)
                if (non == null) px to null else null to non
            }
        }.getOrElse { null to (it.message ?: "ADALM-PLUTO : erreur") }
        if (e == null) {
            generation++
            fermeCarte?.let { runCatching { it() } }; fermeCarte = null
            dernierRefus = refus ?: "ADALM-PLUTO : refus"
            return false
        }
        tx = e; nco = melangeur; plan = p
        val quoi = when { nv != null -> "mémoire vocale « " + (voix.liste().firstOrNull { it.n == nv }?.nom ?: nv.toString()) + " »"; m == Mode.CW -> "CW"
            source == "carte" -> "carte son"; else -> "micro" }
        enCours = EnCours(m, c, dest, quoi, horloge())
        niveau = NiveauSstvQo100.Mesure(null, null)
        gardeNiv = GardeNiveauQo100(gardeSeuilDb); gardeMessage = ""
        return true
    }

    /** The transmitter off (its LO powered down first), the card closed. */
    private fun eteint(raison: String) {
        generation++
        val ec = enCours
        val e = tx
        if (e != null && ec != null) {
            journal.ajoute(JournalEmissionQo100.Passage(ec.debutMs, (horloge() - ec.debutMs).coerceAtLeast(0), ec.mode.cle, ec.cadran,
                e.etatCommun().gainDb, ec.destination, ec.source, ec.ecartMaxDb, raison))
            runCatching { stockage.edit().putString("be_journal", journal.texte()).apply() }
        }
        enCours = null
        tx?.let { runCatching { it.arrete(raison) } }
        tx = null; nco = null
        fermeCarte?.let { runCatching { it() } }; fermeCarte = null
        cw.touche(false)
    }

    /** Asked by the transmitter every 200 ms: a reason to stop, or null; the dial followed meanwhile. */
    private fun garde(gen: Int): String? {
        if (generation != gen) return "arrêtée"
        if (leila()) return "LEILA"
        val ec = enCours ?: return null
        val c = cadranHz
        if (c != ec.cadran) {
            if (MonteeQo100.refus(ec.mode, c) != null) return "fréquence devenue interdite"
            val p = plan ?: return null
            nco?.ncoHz = PlanPluto.ncoPour(p.loHz, c, correctionHz)
            enCours = ec.copy(cadran = c)
        }
        return null
    }

    /**
     * Keys for [client]; null once keyed, else why not. From another
     * machine only with « émission par le réseau autorisée »; on the air
     * (not into the load) only [confirme]d by the operator.
     */
    fun appuie(client: String, distant: Boolean, adresse: String, confirme: Boolean, voixN: Int? = null): String? {
        if (distant && !reseauAutorise()) return "émission par le réseau non autorisée (Réglages › Émission, sur le PC de la station)"
        if (destination == "air" && !confirme) return "émission vers QO-100 non confirmée"
        if (voixN != null) {
            if (poste.etat.emet) return "relâcher le PTT avant de jouer une mémoire vocale"
            if (mode != Mode.BLU) return "mémoire vocale : en BLU seulement"
            if (voix.lis(voixN) == null) return "mémoire vocale $voixN vide"
        }
        if (!poste.etat.emet) { poste.totMs = totMin * 60_000L; voixDemandee = voixN }
        val r = runBlocking { poste.appuie(client, distant, adresse) }
        // Refused before the transmitter started: the voice memory asked is not left for the next press.
        if (r != null) voixDemandee = null
        if (r != null && dernierRefus != null) return dernierRefus
        return r
    }

    fun vie(client: String): Boolean = poste.vie(client)

    fun relache(raison: String) = runBlocking { poste.relache(raison) }

    /** Everything released now (SatMe-PC closing). */
    fun arreteTout(raison: String) {
        runBlocking { withTimeoutOrNull(2_000L) { poste.relache(raison) } }
        eteint(raison)
    }

    val emet: Boolean get() = poste.etat.emet

    /** A few times a second: LEILA, the guards, the end noticed, our level against the beacon's. */
    fun tic() {
        if (poste.etat.emet && leila()) {
            leilaCoupeMs = horloge()
            relache("LEILA détectée : émission coupée")
            return
        }
        runBlocking { poste.tic() }
        if (!poste.etat.emet && tx != null) eteint(poste.etat.raison.ifBlank { "relâché" })
        val ec = enCours
        if (poste.etat.emet && ec != null && ec.surLAir) {
            val o = MonteeQo100.occupe(ec.mode, ec.cadran)
            niveau = signauxQo100()?.let { NiveauSstvQo100.mesure(it, o.first, o.last) } ?: NiveauSstvQo100.Mesure(null, null)
            val ecart = niveau.ecartDb
            if (ecart != null && (ec.ecartMaxDb == null || ecart > ec.ecartMaxDb)) enCours = ec.copy(ecartMaxDb = ecart)
            if (gardeNiveau) {
                val e = tx
                val g = e?.etatCommun()?.gainDb
                if (g != null) gardeNiv.tic(ecart, g, PlutoEmission.GAIN_MIN_DB, horloge())?.let { d ->
                    val coupe = d.coupe
                    if (coupe != null) { gardeMessage = coupe; relache("garde de niveau : $coupe"); return }
                    d.gainDb?.let { ng -> gainDb = ng; e.regleGain(ng); gardeMessage = d.message }
                }
            }
        }
        // A voice memory played out: released once its last samples left (the modulator's cushion).
        val l = lecture
        if (poste.etat.emet && l != null && l.fini) {
            if (lectureFinMs == 0L) lectureFinMs = horloge()
            else if (horloge() - lectureFinMs >= 400L) { lecture = null; relache("mémoire vocale terminée") }
        }
        sondeTic()
    }

    /** Starts the guards' loop (5 times a second). */
    fun demarre() {
        thread(isDaemon = true, name = "satme-tx-be") {
            while (true) {
                runCatching { tic() }
                Thread.sleep(200)
            }
        }
    }

    // ------------------------------------------------------------ CW, the microphone

    /** CW from the page: text queued, the key down or up; only by the client holding the PTT (the text: anyone may queue it). */
    fun cw(client: String, q: Map<String, String>): JSONObject {
        q["wpm"]?.toIntOrNull()?.let { wpm = it }
        q["annule"]?.let { cw.annule() }
        q["lui"]?.let { lui = it }
        q["rst"]?.let { rst = it }
        // A memory, or text with fields: filled now ({MOI}, {LUI}, {LOC}, {RST}).
        val brut = q["memoire"]?.toIntOrNull()?.let { n -> memoiresCw.getOrNull(n - 1)?.texte ?: return refusJ("mémoire CW $n inconnue") } ?: q["texte"]
        brut?.let { t ->
            // One key typed (the keyboard keyer): sent as it comes, a space being the word gap.
            if (q["frappe"] == "1") {
                val c = MacrosCw.nettoie(t.take(1)).ifBlank { if (t.startsWith(" ")) " " else "" }
                if (c.isNotEmpty()) cw.envoie(c, enchaine = true)
            } else {
                val (s, err) = MacrosCw.developpe(t, indicatif(), lui, locator(), rst)
                if (s == null) return refusJ(err ?: "texte vide")
                cw.envoie(s)
            }
        }
        q["touche"]?.let { v ->
            if (v == "1" && !poste.tenuPar(client)) return JSONObject().put("ok", false).put("erreur", "PTT non tenu par ce client")
            cw.touche(v == "1")
            if (v == "1") poste.vie(client)
        }
        return JSONObject().put("ok", true).put("enAttente", cw.enAttente).put("occupe", cw.occupe)
    }

    /** A frame of microphone from [client]: to the transmitter while it holds the PTT, else dropped. */
    fun micro(client: String, l: SonDistant.Lue) {
        trames.incrementAndGet()
        var c = 0.0
        for (v in l.pcm) { val a = kotlin.math.abs(v.toInt()) / 32768.0; if (a > c) c = a }
        val t = horloge()
        if (t - creteMs > 300L || c > crete) { crete = c; creteMs = t }
        if (!poste.tenuPar(client) || source != "micro" || mode == Mode.CW) { ignorees.incrementAndGet(); return }
        poste.vie(client)
        tampon.pousse(l.pcm, l.pcm.size, l.rate)
    }

    /** Serves the microphone's WebSocket (the opening already answered); closed while its client holds the PTT, released. */
    fun sertMicro(c: Socket, client: String) {
        runCatching { c.tcpNoDelay = true; c.soTimeout = 0 }
        val e = c.getInputStream()
        val s = c.getOutputStream()
        val verrou = Any()
        fun ecrit(b: ByteArray) = synchronized(verrou) { runCatching { s.write(b); s.flush() }.isSuccess }
        if (!ecrit(WebSocketPc.texte(JSONObject().put("t", "bonjour").put("micro", true).put("taux", AUDIO).toString()))) return
        try {
            while (true) {
                val t = WebSocketPc.litTrame(e) ?: break
                when (t.opcode) {
                    WebSocketPc.BINAIRE -> SonDistant.litAmont(t.donnees)?.let { micro(client, it) }
                    WebSocketPc.TEXTE -> runCatching { JSONObject(String(t.donnees, Charsets.UTF_8)) }.getOrNull()?.let { m ->
                        if (m.optString("t") == "ping") ecrit(WebSocketPc.texte(JSONObject().put("t", "pong").put("c", m.optDouble("c")).put("emet", poste.tenuPar(client)).toString()))
                    }
                    WebSocketPc.PING -> ecrit(WebSocketPc.trame(WebSocketPc.PONG, t.donnees))
                    WebSocketPc.FERMETURE -> { ecrit(WebSocketPc.trame(WebSocketPc.FERMETURE, t.donnees.copyOf(minOf(2, t.donnees.size)))); break }
                }
            }
        } finally {
            if (poste.tenuPar(client) && source == "micro" && mode != Mode.CW) relache("micro coupé")
        }
    }

    // ------------------------------------------------------------ the measurements

    /**
     * The Pluto's error from our own signal on the downlink: transmitting on
     * the air, SatMe hearing QO-100 (the cockpit's receiver, calibrated on the
     * beacon), the strongest signal within 20 kHz of where ours should be.
     * A CW carrier (key down) gives the clearest line.
     */
    fun mesureCorrection(): JSONObject {
        val ec = enCours
        if (!poste.etat.emet || ec == null) return refusJ("émettre d'abord sur l'air (CW, touche enfoncée), puis mesurer")
        if (!ec.surLAir) return refusJ((if (ec.destination == "simulation") "en simulation" else "sur charge") + ", rien n'arrive au satellite : passer sur l'air pour mesurer")
        val s = signauxQo100() ?: return refusJ("SatMe n'entend pas QO-100 : démarrer la réception (onglet QO-100, clé SDR)")
        val attendu = Qo100.descenteDepuisMontee(porteuseHz(ec.mode, ec.cadran))
        val vu = s.filter { kotlin.math.abs(it.hz - attendu) <= 20_000L && it.vuIlYaMs < 3_000L }.maxByOrNull { it.snrDb }
            ?: return refusJ("notre signal n'est pas vu à ±20 kHz de " + MonteeQo100.mhz(attendu) + " MHz")
        val avant = correctionHz
        val n = CorrectionPluto.mesure(attendu, vu.hz, porteuseHz(ec.mode, ec.cadran), avant)
        if (!CorrectionPluto.credible(n)) return refusJ("mesure incroyable (" + n + " Hz) : un autre signal ?")
        correctionHz = n
        return JSONObject().put("ok", true).put("avant", avant).put("correction", n).put("ecart", vu.hz - attendu)
            .put("message", String.format(Locale.FRANCE, "entendu %+d Hz de l'attendu : correction %+d Hz à 2400 MHz (%+.2f ppm), appliquée au prochain PTT",
                vu.hz - attendu, n, CorrectionPluto.ppm(n)))
    }

    /**
     * The up-converter's oscillator from the IC-9700's own signal: [posteHz]
     * the rig's TX dial (in CW its carrier), heard on the downlink near where
     * the chain says it should be ([attenduDescenteHz]).
     */
    fun mesureOlPoste(posteHz: Long, attenduDescenteHz: Long): Pair<Long?, String> {
        val s = signauxQo100() ?: return null to "SatMe n'entend pas QO-100 : démarrer la réception (onglet QO-100, clé SDR)"
        val vu = s.filter { kotlin.math.abs(it.hz - attenduDescenteHz) <= 50_000L && it.vuIlYaMs < 3_000L }.maxByOrNull { it.snrDb }
            ?: return null to "aucun signal à ±50 kHz de " + MonteeQo100.mhz(attenduDescenteHz) + " MHz"
        val ol = MonteeQo100.olMonteeMesure(vu.hz, posteHz)
        if (!fr.f4ioz.satcombo.domain.ChaineQo100.monteeCredible(ol)) return null to "oscillateur incroyable : " + MonteeQo100.mhz(ol) + " MHz"
        return ol to "entendu sur " + MonteeQo100.mhz(vu.hz) + " MHz : oscillateur de montée " + MonteeQo100.mhz(ol) + " MHz"
    }

    private fun refusJ(m: String) = JSONObject().put("ok", false).put("erreur", m)

    // ------------------------------------------------------------ is the Pluto there?

    @Volatile private var vu: Boolean? = null
    @Volatile private var vuMs = 0L
    @Volatile private var demandeMs = 0L
    @Volatile private var sondeEnCours = false

    /** Every 5 s while a page looks and nothing transmits: a TCP connection opened and closed (no command). */
    private fun sondeTic() {
        val t = horloge()
        if (sondeEnCours || t - demandeMs > 10_000L || t - vuMs < 5_000L || poste.etat.emet) return
        sondeEnCours = true
        vuMs = t
        thread(isDaemon = true, name = "satme-be-sonde") {
            try {
                vu = sonde?.invoke() ?: runCatching { Socket().use { it.connect(InetSocketAddress(plutoHote(), plutoPort()), 400) }; true }.getOrDefault(false)
            } finally { sondeEnCours = false }
        }
    }

    // ------------------------------------------------------------ the page

    fun json(client: String?): JSONObject {
        demandeMs = horloge()
        val e = poste.etat
        val t = horloge()
        val m = mode
        val c = cadranHz
        val pe = tx?.etatCommun()
        val pr = profil
        val o = JSONObject().put("ok", true)
            .put("mode", m.cle).put("modes", JSONArray(Mode.entries.map { JSONObject().put("cle", it.cle).put("nom", it.libelle) }))
            .put("lie", lie).put("cadran", c).put("cadranManuel", cadranManuelHz).put("descente", Qo100.descenteDepuisMontee(c))
            .put("porteuse", porteuseHz(m, c)).put("cockpit", descenteCockpit() ?: JSONObject.NULL)
            .put("refusFreq", MonteeQo100.refus(m, c) ?: "").put("avertFreq", MonteeQo100.avertissement(m, c) ?: "")
            .put("defaut", MonteeQo100.cadranParDefaut(m))
            .put("pret", e.emet || refus(m, c) == null).put("pourquoi", if (e.emet) "" else refus(m, c) ?: "")
            .put("emet", e.emet && pe?.emet != false).put("raison", e.raison).put("erreur", dernierRefus ?: e.erreur)
            .put("restant", poste.restantS(t) ?: JSONObject.NULL).put("tot", totMin).put("chienS", poste.chienMs / 1000.0)
            .put("charge", charge).put("gainCharge", ReglesSstvRf.GAIN_CHARGE_DB)
            .put("gain", gainDb).put("plafond", plafondDb).put("gainMax", gainMaxDb())
            .put("gainEffectif", minOf(gainDb, plafondDb, gainMaxDb(), pr.plafondPlutoDb ?: 0.0, if (destination == "charge") ReglesSstvRf.GAIN_CHARGE_DB else 0.0))
            .put("plafondProfil", pr.plafondPlutoDb ?: JSONObject.NULL)
            .put("simulation", simulation).put("simulationSeule", simulationSeule()).put("destination", destination)
            .put("source", source).put("carte", carte).put("cartes", JSONArray(runCatching { cartes() }.getOrDefault(emptyList())))
            .put("carteErreur", carteErreur)
            .put("wpm", wpm).put("cwAttente", cw.enAttente).put("cwOccupe", cw.occupe).put("ton", MonteeQo100.TON_CW_HZ)
            .put("correction", correctionHz).put("ppm", Math.round(CorrectionPluto.ppm(correctionHz) * 1000) / 1000.0)
            .put("pluto", plutoHote() + ":" + plutoPort()).put("plutoVu", vu ?: JSONObject.NULL)
            .put("indicatif", indicatif().trim().uppercase()).put("reseauAutorise", reseauAutorise())
            .put("leila", leila()).put("microDb", if (t - creteMs < 1_000L && crete > 0) Math.round(20 * Math.log10(crete) * 10) / 10.0 else JSONObject.NULL)
            .put("trames", trames.get()).put("ignorees", ignorees.get()).put("manques", tampon.manques).put("retardMs", tampon.retardMs)
        PlanPluto.pour(c, correctionHz).let { p -> o.put("lo", p.loHz).put("nco", Math.round(p.ncoHz)) }
        if (pe != null) o.put("gainApplique", pe.gainDb).put("tampons", pe.tampons).put("sousCharge", enCours?.let { !it.surLAir } ?: (destination != "air"))
            .put("destinationEnCours", enCours?.destination ?: destination)
        if (e.emet) o.put("client", if (e.distant) "un autre appareil" + (if (e.adresse.isNotBlank()) " (" + e.adresse + ")" else "") else "la page de ce PC")
            .put("moi", client != null && client == e.client).put("depuisS", (t - e.depuisMs) / 1000)
        val n = niveau
        o.put("niveau", JSONObject().put("nous", n.nousDb ?: JSONObject.NULL).put("balise", n.baliseDb ?: JSONObject.NULL)
            .put("ecart", n.ecartDb ?: JSONObject.NULL).put("tropFort", (n.ecartDb ?: -99.0) > 0.0)
            .put("max", enCours?.ecartMaxDb ?: JSONObject.NULL))
        // The level guard (opt-in): its setting, its last action.
        o.put("garde", JSONObject().put("on", gardeNiveau).put("seuil", gardeSeuilDb).put("coupure", GardeNiveauQo100.COUPURE_DB)
            .put("seuils", JSONArray(GardeNiveauQo100.SEUILS)).put("message", gardeMessage).put("pas", gardeNiv.pas))
        // Listen before talk: somebody already there (not while we transmit: that would be us).
        val occ = if (e.emet) null else signauxQo100()?.let { OccupationQo100.occupant(it, m, c) }
        o.put("occupe", occ?.let { OccupationQo100.texte(it) } ?: "")
        // The simulated transmitter's output.
        (tx as? EmetteurSimulePc)?.let { sx -> o.put("simule", JSONObject().put("crete", sx.creteDbfs ?: JSONObject.NULL).put("rms", sx.rmsDbfs ?: JSONObject.NULL)) }
        // The CW memories, the voice memories, the voice memory playing.
        o.put("memoiresCw", JSONArray(memoiresCw.map { it.json() })).put("lui", lui).put("rst", rst)
        o.put("voix", voix.json()).put("voixMax", MemoiresVoixPc.DUREE_MAX_S)
        lecture?.let { o.put("lecture", Math.round(it.avance * 100)) }
        // The journal: the last overs, today's time on the air.
        o.put("journal", JSONArray(journal.liste().take(20).map { it.json() })).put("airAujourdhuiS", journal.surLAirAujourdhui(t) / 1000)
        // The profiles: the one chosen, its estimates (orders of magnitude).
        o.put("profils", JSONArray(profils.map { it.json() })).put("profil", pr.nom)
        o.put("estimation", JSONObject().put("gainAntenne", r1(pr.gainDbi)).put("pireMax", pr.pireMaxDbw?.let { r1(it) } ?: JSONObject.NULL)
            .put("pireMaxW", pr.pireMaxDbw?.let { r1(Math.pow(10.0, it / 10)) } ?: JSONObject.NULL)
            .put("balise", ProfilMontee.PIRE_BALISE_DBW).put("marge", ProfilMontee.marge(m))
            .put("conseilleW", r1(pr.conseilleW(m))).put("gainPluto", pr.gainPlutoConseille(m)?.let { r1(it) } ?: JSONObject.NULL)
            .put("montage", pr.prereglage).put("resume", pr.resume(m)))
        o.put("antennes", JSONArray(ProfilMontee.Antenne.entries.map { JSONObject().put("cle", it.cle).put("nom", it.libelle) }))
            .put("chaines", JSONArray(MonteeQo100.Chaine.entries.map { JSONObject().put("cle", it.cle).put("nom", it.libelle) }))
        return o
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0

    /**
     * The page's settings. [local]: asked from this PC — only it may change
     * the guards (the cap, the time-out) or leave the dummy load. Nothing
     * here ever keys the transmitter.
     */
    fun reglage(q: Map<String, String>, local: Boolean): JSONObject {
        if (!local && listOf("plafond", "tot", "charge", "carte", "simulation", "garde", "gardeSeuil", "journalEfface").any { it in q })
            return refusJ("ce réglage se change seulement sur le PC de la station")
        val emet = poste.etat.emet
        if (emet && listOf("mode", "source", "carte", "charge", "correction", "simulation", "profil", "profilJson").any { it in q }) return refusJ("relâcher le PTT d'abord")
        q["mode"]?.let { Mode.de(it)?.let { m -> if (m != mode) { mode = m; if (!lie) cadranManuelHz = MonteeQo100.cadranParDefaut(m) } } }
        q["lie"]?.let { lie = it == "1" }
        q["cadran"]?.toLongOrNull()?.let { if (it in Qo100.NB.monteeBasHz - 10_000..Qo100.NB.monteeHautHz + 10_000) { cadranManuelHz = it; lie = false } }
        q["source"]?.let { source = it }
        q["carte"]?.let { carte = it }
        q["plafond"]?.toDoubleOrNull()?.let { plafondDb = it }
        q["gain"]?.toDoubleOrNull()?.let { g ->
            gainDb = g
            // Applied at once while transmitting (clamped by the transmitter).
            tx?.regleGain(minOf(g, plafondDb))
        }
        q["tot"]?.toIntOrNull()?.let { totMin = it }
        q["wpm"]?.toIntOrNull()?.let { wpm = it }
        q["charge"]?.let { charge = it != "0" }
        q["simulation"]?.let { if (it == "0" && simulationSeule()) return refusJ("la démonstration ne fait que simuler"); simulation = it == "1" }
        q["garde"]?.let { gardeNiveau = it == "1" }
        q["gardeSeuil"]?.toDoubleOrNull()?.let { gardeSeuilDb = it }
        q["lui"]?.let { lui = it }
        q["rst"]?.let { rst = it }
        q["cwMem"]?.toIntOrNull()?.let { n ->
            if (n !in 1..MacrosCw.NOMBRE) return refusJ("mémoire CW 1 à " + MacrosCw.NOMBRE)
            memoiresCw = MacrosCw.change(memoiresCw, n - 1, q["cwNom"].orEmpty(), q["cwTexte"].orEmpty())
        }
        q["cwMemDefaut"]?.let { memoiresCw = MacrosCw.PAR_DEFAUT }
        q["voixEfface"]?.toIntOrNull()?.let { if (!emet) voix.efface(it) }
        q["journalEfface"]?.let { journal.efface(); stockage.edit().putString("be_journal", journal.texte()).apply() }
        q["correction"]?.toLongOrNull()?.let { if (!CorrectionPluto.credible(it)) return refusJ("correction de ±" + CorrectionPluto.MAX_HZ / 1000 + " kHz au plus à 2400 MHz (±50 ppm)"); correctionHz = it }
        q["profil"]?.let { n -> if (profils.any { it.nom.equals(n, true) }) profilNom = n }
        q["profilJson"]?.let { s ->
            val p = runCatching { ProfilMontee.lis(JSONObject(s)) }.getOrNull() ?: return refusJ("profil illisible")
            profils = ProfilMontee.range(profils, p); profilNom = p.nom
        }
        q["profilRetire"]?.let { n -> val l = profils.filter { !it.nom.equals(n, true) }; if (l.isNotEmpty()) profils = l }
        return JSONObject().put("ok", true)
    }

    companion object {
        val SOURCES = setOf("micro", "carte")
        const val AUDIO = 48_000
        const val NIVEAU = 0.5
        /** Low by default: an amplifier follows. */
        const val GAIN_DEFAUT_DB = -40.0
        const val PLAFOND_DEFAUT_DB = -10.0
        /** After LEILA cut a transmission: no keying for this long. */
        const val LEILA_ATTENTE_MS = 15_000L
    }
}
