/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.DopplerTuner
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.Bladerf
import fr.f4ioz.satcombo.sdr.BladerfSdr
import fr.f4ioz.satcombo.sdr.PlutoSdr
import fr.f4ioz.satcombo.sdr.RtlTcp
import fr.f4ioz.satcombo.sdr.RxChain
import fr.f4ioz.satcombo.sdr.RxCleaning
import fr.f4ioz.satcombo.sdr.RxMode
import fr.f4ioz.satcombo.sdr.SourceIq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

/**
 * The SDR receiver on the PC: an RTL-SDR dongle through `rtl_tcp`, or an
 * ADALM-PLUTO on the network ([PlutoSdr], its receive side only) — the
 * phone's reception, the same engine.
 *
 * - **rtl_tcp** is started here when the dongle is on this PC (and stopped
 *   with it), or reached on another machine of the network (a small
 *   computer by the antenna).
 * - **The Pluto** is reached at its address (192.168.2.1 over its USB
 *   cable); it gives the same rate as the dongle, in 16 bits.
 * - **A bladeRF 2.0** on USB, through `bladeRF-cli` ([BladerfSdr]): the
 *   same rate, in 16 bits, as the Pluto.
 * - **The frequency** is the followed satellite's downlink with its Doppler,
 *   asked every 250 ms; between blocks, [DopplerTuner] decides whether the
 *   software mixer absorbs it (no click) or the PLL must move.
 * - **The sound** (44.1 kHz) goes to the speaker and, in place of the sound
 *   card, to every decoder of the PC (SSTV, APRS, FT8, the recording): it
 *   is the rig's sound for them. Starting the card again takes it back.
 * - **The spectrum** (4096 bins over 176.4 kHz, anchored on the PLL) is
 *   kept for the page's waterfall.
 *
 * Receive only: the dongle does not transmit, nothing here opens a serial
 * port.
 */
class SdrPc(val station: StationPc, private val son: SonPc, private val stockage: Stockage,
            private val portee: CoroutineScope, private val horloge: () -> Long = System::currentTimeMillis,
            dossierSondes: File? = null, val meteor: MeteorPc? = null) {

    /** Receiving METEOR pictures now (the followed satellite is one): its samples go to the decoder. */
    @Volatile var pourMeteor = false; private set

    /** The automatic start on METEOR passes: the satellite it is receiving (0: none), and until when. */
    @Volatile private var meteorAutoArme = 0
    private var meteorAutoLos = 0L
    private var dernierTicMeteor = 0L
    /** The METEOR passes already tried (their AOS): one attempt each. */
    private val meteorEssayes = java.util.Collections.synchronizedSet(HashSet<Long>())

    /**
     * A minute before a METEOR pass the dongle starts on it (the satellite
     * followed then), after LOS + 30 s it stops — the pictures are written
     * then. Only when the dongle is free: a reception started by hand is never
     * taken over. Called every few seconds.
     */
    fun ticMeteor() {
        // A recording or the generator chosen: no antenna to take the pass with.
        if (meteor == null || !settings.meteorAuto || (meteorAutoArme == 0 && (choixFictif() || recepteur == FI_POSTE))) { meteorAutoArme = 0; return }
        val now = horloge()
        if (meteorAutoArme != 0) {
            if (!_etat.value.actif) { meteorAutoArme = 0; rendSatellite(); return }
            if (now > meteorAutoLos + 30_000L) { meteorAutoArme = 0; arrete(); rendSatellite() }
            return
        }
        if (_etat.value.actif) return
        // The dongle set on QO-100 (behind its converter, 137 MHz is out of reach): METEOR leaves it alone.
        if (station.qo100.etat.value.aLaCle) return
        // The receiver chosen held by ADS-B (Avions tab): METEOR leaves it too.
        if (adsbTient() == recepteur) return
        if (aisTient() && recepteur == RTL) return
        // The passes asked every 15 s, not every second.
        if (now - dernierTicMeteor < 15_000L) return
        dernierTicMeteor = now
        val (sat, p) = station.prochainPassageMeteor(2) ?: return
        if (now < p.aosEpochMs - 60_000L || now >= p.losEpochMs) return
        // Never from under the operator working another satellite; one attempt a pass.
        val e = station.etat.value
        if (!fr.f4ioz.satcombo.meteor.MeteorAuto.peutPrendre(p.aosEpochMs, meteorEssayes, station.estMeteor(), e.position?.elevationDeg)) return
        meteorEssayes += p.aosEpochMs
        val avant = e.suivi
        meteorAutoAvant = avant?.takeIf { it.catalogNumber != sat.catalogNumber }
        meteorAutoArme = sat.catalogNumber
        meteorAutoLos = p.losEpochMs
        station.suis(sat)
        suitSatellite = true
        if (!demarre(pourImages = true)) {
            // The dongle did not start: the satellite followed before put back.
            meteorAutoArme = 0
            if (avant != null && avant.catalogNumber != sat.catalogNumber) station.suis(avant)
        }
    }

    /** The satellite followed before an automatic METEOR pass took over: given back after it. */
    @Volatile private var meteorAutoAvant: fr.f4ioz.satcombo.data.TleEntry? = null

    private fun rendSatellite() {
        val avant = meteorAutoAvant ?: return
        meteorAutoAvant = null
        // Only if the operator has not chosen another one meanwhile.
        if (station.estMeteor()) station.suis(avant)
    }

    /**
     * The dongle left METEOR (QO-100 set on it, another satellite followed):
     * the picture receiver stopped, the sound back — it stayed silent all
     * morning after a night's METEOR pass (15 October).
     */
    private fun quitteMeteorSiBesoin() {
        if (!pourMeteor || (!pourQo100() && suitSatellite && station.estMeteor())) return
        synchronized(this) {
            if (!pourMeteor) return
            pourMeteor = false
            meteor?.let { m -> val l = m.recepteur.arrete(); if (l.isNotEmpty()) thread(isDaemon = true, name = "satme-meteor-ecrit") { m.ecrit(l) } }
            if (!pourSonde) son.prendPour(NOM_SOURCE, Dsp.AUDIO_RATE)
        }
    }

    /** The radiosondes: their decoder, fed by the dongle while it listens to one. */
    val sondes = fr.f4ioz.satcombo.sonde.DecodeurSondes(dossierSondes, horloge)

    /** Listening to a radiosonde rather than a satellite. */
    @Volatile var pourSonde = false; private set

    /** Re-centring on the sonde (the spectrum's centre of gravity): once asked, or every 2 s. */
    @Volatile var recentreAuto = true
    @Volatile private var recentreVoulu = false
    fun recentre() { recentreVoulu = true }

    /** The dongle for a radiosonde: its frequency, its model's filter, no audio, no squelch, no de-emphasis. */
    @Synchronized fun demarreSondes(): Boolean {
        arrete()
        pourSonde = true
        decalageHz = 0
        sondes.demarre(Dsp.AUDIO_RATE, settings.sondeFreqHz, settings.sondeModel)
        if (!lance()) { pourSonde = false; sondes.arrete(); return false }
        recentreVoulu = true
        return true
    }

    /** Another sonde frequency (a step, a site): the dongle and the frames follow. */
    fun frequenceSonde(hz: Long) {
        settings.sondeFreqHz = hz.coerceIn(400_000_000L, 406_000_000L)
        sondes.frequence(settings.sondeFreqHz)
        decalageHz = 0
        if (pourSonde) { recentreVoulu = true; cibleMaintenant() }
    }

    fun modeleSonde(m: String) {
        settings.sondeModel = m
        // The decoders follow the model: armed again on the same frequency.
        if (pourSonde) sondes.demarre(Dsp.AUDIO_RATE, settings.sondeFreqHz, m)
    }

    data class Etat(
        val actif: Boolean = false,
        val statut: String = "arrêtée",
        val erreur: String = "",
        val tuner: String = "",
        /** Sky frequencies: listened to now, and at rest (no Doppler). */
        val cielHz: Long = 0L,
        val reposHz: Long = 0L,
        /** The dongle: its PLL, the software share, the operator's offset. */
        val pllHz: Long = 0L,
        val fineHz: Long = 0L,
        val decalageHz: Int = 0,
        val niveauDb: Float = -120f,
        val saturation: Float = 0f,
        val debitKo: Int = 0,
        val perdus: Long = 0L,
        val satellite: String = "",
        /** The CTCSS tone heard in NFM (0: none), its share of the sub-tone band, its deviation; the tone squelch shut. */
        val ctcssHz: Double = 0.0,
        val ctcssConfiance: Float = 0f,
        val ctcssDevHz: Int = 0,
        val tonFerme: Boolean = false,
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    // ------------------------------------------------------------ settings (the phone's keys where they exist)

    private val settings get() = station.settings
    var hote: String
        get() = stockage.getString(CLE_HOTE, "127.0.0.1") ?: "127.0.0.1"
        set(v) { stockage.edit().putString(CLE_HOTE, v.trim().ifEmpty { "127.0.0.1" }).apply() }
    var port: Int
        get() = stockage.getInt(CLE_PORT, RtlTcp.PORT)
        set(v) { stockage.edit().putInt(CLE_PORT, v.coerceIn(1, 65535)).apply() }
    /** Start rtl_tcp here (the dongle on this PC), or only connect to it. */
    var lance: Boolean
        get() = stockage.getBoolean(CLE_LANCE, true)
        set(v) { stockage.edit().putBoolean(CLE_LANCE, v).apply() }
    var biasTee: Boolean
        get() = stockage.getBoolean(CLE_BIAS, false)
        set(v) { stockage.edit().putBoolean(CLE_BIAS, v).apply(); (cle as? RtlTcp)?.let { runCatching { it.setBiasTee(v) } } }
    /** The receiver: [RTL] (the dongle, rtl_tcp), [PLUTO], [BLADERF], the rig's IF [FI_POSTE], or no hardware: [FICHIER], [GENERATEUR]; taken at the next start. */
    var recepteur: String
        get() = stockage.getString(CLE_RECEPTEUR, RTL).takeIf { it in RECEPTEURS } ?: RTL
        set(v) { stockage.edit().putString(CLE_RECEPTEUR, if (v in RECEPTEURS) v else RTL).apply() }

    // ------------------------------------------------------------ no hardware: a recording, the test generator

    /** The recording played back (its path; a SigMF file, or raw with [fichierDebit]). */
    var fichierIq: String
        get() = stockage.getString(CLE_FICHIER, "") ?: ""
        set(v) { stockage.edit().putString(CLE_FICHIER, v).apply() }
    /** A raw file's rate (S/s) and frequency (sky Hz), given by hand. */
    var fichierDebit: Long
        get() = stockage.getLong(CLE_FICHIER_DEBIT, Dsp.RTL_RATE.toLong())
        set(v) { stockage.edit().putLong(CLE_FICHIER_DEBIT, v.coerceIn(1_000L, 20_000_000L)).apply() }
    var fichierFrequence: Long
        get() = stockage.getLong(CLE_FICHIER_FREQ, 0L)
        set(v) { stockage.edit().putLong(CLE_FICHIER_FREQ, v.coerceIn(0L, 12_000_000_000L)).apply() }
    /** The test generator's signals (kept; applied at once while it runs). */
    var reglagesGenerateur: fr.f4ioz.satcombo.sdr.GenerateurIq.Reglages
        get() = runCatching {
            val o = org.json.JSONObject(stockage.getString(CLE_GENERATEUR, "{}") ?: "{}")
            fr.f4ioz.satcombo.sdr.GenerateurIq.Reglages().avec { k -> if (o.has(k)) o.get(k).toString() else null }
        }.getOrDefault(fr.f4ioz.satcombo.sdr.GenerateurIq.Reglages())
        set(v) { stockage.edit().putString(CLE_GENERATEUR, v.plat().toString()).apply(); generateur?.reglages = v }
    /** The recording being played, or null. */
    val lecture: fr.f4ioz.satcombo.sdr.SourceFichierIq? get() = cle as? fr.f4ioz.satcombo.sdr.SourceFichierIq
    /** The test generator running, or null. */
    val generateur: fr.f4ioz.satcombo.sdr.GenerateurIq? get() = cle as? fr.f4ioz.satcombo.sdr.GenerateurIq
    /** Receiving from no antenna now (a recording, the generator): nothing heard is sent anywhere (PSK Reporter). */
    val fictif: Boolean get() = lecture != null || generateur != null
    private fun choixFictif() = recepteur == FICHIER || recepteur == GENERATEUR
    // ------------------------------------------------------------ the rig's IF (« IC-9700 FI »)

    /** Opens the rig's IF as a receiver (by the network or its USB card; set by Main), throws with the reason. */
    @Volatile var ouvreFi: (() -> SourceIq)? = null
    /** The rig's RX dial (sky Hz) as the CAT reads it, null when not known: the IF's centre. */
    @Volatile var cadranPoste: () -> Long? = { null }
    /** Moves the rig's RX dial by [Int] Hz by CAT (receive only); false when it cannot. */
    @Volatile var deplacePoste: (Int) -> Boolean = { false }
    /** Opens a SatMe-Node's SDR (its bladeRF or Pluto) as a receiver (set by Main), throws with the reason. */
    @Volatile var ouvreNoeud: (() -> SourceIq)? = null
    /** The node's SDR being received now, or null. */
    val sdrNoeud: fr.f4ioz.satcombo.sdr.SourceNoeudIq? get() = cle as? fr.f4ioz.satcombo.sdr.SourceNoeudIq
    /** The rig's IF being received now, or null. */
    val fiPoste: fr.f4ioz.satcombo.sdr.SourceFiPoste? get() = cle as? fr.f4ioz.satcombo.sdr.SourceFiPoste
    /**
     * With the rig's IF: a tuning on the waterfall moves the rig (its RX
     * dial, by CAT) rather than the listening point within the IF.
     */
    var recentrePoste: Boolean
        get() = stockage.getBoolean(CLE_FI_RECENTRE, false)
        set(v) { stockage.edit().putBoolean(CLE_FI_RECENTRE, v).apply() }
    /** The IF's centre (sky Hz) now: the rig's dial, or where it should be when the CAT says nothing. */
    @Volatile private var cadranFiHz = 0L
    @Volatile private var cadranLu = false

    /** The generator's centre (sky Hz), fixed at its start. */
    @Volatile private var centreGenerateurHz = 0L
    /** Counted at each start: the « remonter le temps » ring starts again with it. */
    @Volatile var seance = 0; private set
    /**
     * The raw I/Q of a real receiver (not a recording played back), for « Remonter le
     * temps » and « Enregistrer »: 8-bit bytes or 16-bit shorts, the count of values,
     * the sky frequency of the PLL, the PLL, the software share of the Doppler.
     */
    @Volatile var iqBrut: ((ByteArray?, ShortArray?, Int, Long, Long, Double) -> Unit)? = null
    /** Where bladeRF-cli is (the program or its folder); empty: the PATH. */
    var bladerfOutil: String
        get() = stockage.getString(CLE_BLADERF_OUTIL, "") ?: ""
        set(v) { stockage.edit().putString(CLE_BLADERF_OUTIL, v.trim()).apply() }
    private fun estBladerf() = recepteur == BLADERF
    /** The bladeRF transmitting (DATV tab): its receiver may not start meanwhile (one program holds the board). */
    var emissionBladerf: () -> Boolean = { false }
    /** The bladeRF receiving here now. */
    val bladerfEnReception: Boolean get() = cle is BladerfSdr
    /** The receiver DATV's demodulator holds now ("pluto", "bladerf"), or null: this tab may not start on it meanwhile. */
    @Volatile var datvTient: String? = null
    /** The receiver ADS-B holds now ([RTL] for this tab's dongle, [PLUTO]), or null: this tab may not start on it meanwhile. */
    @Volatile var adsbTient: () -> String? = { null }
    /** AIS (Navires tab) holds this tab's dongle: this tab may not start on it meanwhile. */
    @Volatile var aisTient: () -> Boolean = { false }

    /** bladeRF-cli: in the folder set, else in the PATH; null when missing. */
    fun programmeBladerf(): String? {
        val r = bladerfOutil
        if (r.isEmpty()) return OutilsPc.trouve("bladeRF-cli")
        val f = File(r)
        val c = if (f.isDirectory) File(f, if (OutilsPc.windows) "bladeRF-cli.exe" else "bladeRF-cli") else f
        return c.takeIf { it.isFile && (OutilsPc.windows || it.canExecute()) }?.path
    }

    /** The last `bladeRF-cli -e info`: the board, or why not. */
    data class InfoBladerf(val carte: Bladerf.Carte? = null, val message: String = "", val lu: Boolean = false)
    @Volatile var infoBladerf = InfoBladerf(); private set

    /** Reads what the bladeRF is (read-only); not while it receives here, nor while it transmits. */
    fun detecteBladerf(): InfoBladerf {
        val i = when {
            ModeDemo.actif -> InfoBladerf(message = ModeDemo.refus("le bladeRF").orEmpty(), lu = true)
            bladerfEnReception -> infoBladerf.copy(message = "", lu = true)
            emissionBladerf() -> InfoBladerf(infoBladerf.carte, "le bladeRF émet (onglet DATV ou SSTV)", true)
            else -> programmeBladerf()?.let { p -> Bladerf.detecte(p).let { (c, m) -> InfoBladerf(c, m, true) } }
                ?: InfoBladerf(message = conseilBladerf(), lu = true)
        }
        infoBladerf = i
        return i
    }

    fun conseilBladerf(): String = if (OutilsPc.windows)
        "bladeRF-cli.exe introuvable ; et sous Windows SatMe ne sait pas encore lui passer les échantillons (tube nommé) : bladeRF sous Linux"
        else "bladeRF-cli introuvable : installer bladerf (sudo apt install bladerf), ou donner son chemin ci-dessous"
    var plutoHote: String
        get() = stockage.getString(CLE_PLUTO_HOTE, PlutoSdr.HOTE) ?: PlutoSdr.HOTE
        set(v) { stockage.edit().putString(CLE_PLUTO_HOTE, v.trim().ifEmpty { PlutoSdr.HOTE }).apply() }
    var plutoPort: Int
        get() = stockage.getInt(CLE_PLUTO_PORT, PlutoSdr.PORT)
        set(v) { stockage.edit().putInt(CLE_PLUTO_PORT, v.coerceIn(1, 65535)).apply() }
    private fun estPluto() = recepteur == PLUTO

    /** The Pluto transmitting (DATV tab): its receiver may not start meanwhile (they share the sample rate). */
    var emissionPluto: () -> Boolean = { false }
    /** The Pluto receiving here now. */
    val plutoEnReception: Boolean get() = cle is PlutoSdr
    /**
     * Power the Pluto's TX LO down when receiving with it (Réglages; off by
     * default). Done by the transmitter's class, the only one that writes TX.
     */
    var coupeTxEnReception: Boolean
        get() = stockage.getBoolean(CLE_COUPE_TX, false)
        set(v) { stockage.edit().putBoolean(CLE_COUPE_TX, v).apply() }

    /** The frequencies the chosen receiver tunes to (Hz): the dongle's, or the Pluto's. */
    val gammeHz: LongRange get() = (cle as? PlutoSdr)?.let { it.frequenceMinHz..it.frequenceMaxHz }
        ?: if (choixFictif() || recepteur == FI_POSTE) 24_000_000L..12_000_000_000L
        else if (estPluto() || recepteur == NOEUD) 70_000_000L..6_000_000_000L
        else if (estBladerf()) BladerfSdr.FREQ_MIN_HZ..BladerfSdr.FREQ_MAX_HZ else 24_000_000L..1_766_000_000L
    /** "AUTO": the transmitter's mode; or NFM, USB, LSB, AM. */
    var modeChoisi: String
        get() = stockage.getString(CLE_MODE, "AUTO") ?: "AUTO"
        set(v) { stockage.edit().putString(CLE_MODE, v).apply(); settings.sdrBandwidthHz = 0 }
    /** The frequency listened to when no satellite is followed (or Doppler off), sky Hz. */
    var manuelleHz: Long
        get() = stockage.getLong(CLE_MANUELLE, 145_800_000L)
        set(v) { stockage.edit().putLong(CLE_MANUELLE, v.coerceIn(24_000_000L, 12_000_000_000L)).apply() }
    /** Follow the satellite (its frequency and Doppler), or stay on the manual frequency. */
    var suitSatellite: Boolean
        get() = stockage.getBoolean(CLE_SUIT, true)
        set(v) { stockage.edit().putBoolean(CLE_SUIT, v).apply() }

    fun gain(tenthDb: Int?) { settings.sdrGainTenthDb = tenthDb ?: -1; cle?.let { runCatching { it.setGain(tenthDb) } } }
    fun agc(on: Boolean) { settings.sdrAgc = on; cle?.let { runCatching { it.setAgc(on) } } }
    fun ppm(v: Int) { settings.sdrPpm = v.coerceIn(-200, 200); cle?.let { runCatching { it.setFreqCorrection(settings.sdrPpm) } } }

    /** The operator's offset from the frequency listened to (Hz): a click on the waterfall. */
    @Volatile var decalageHz: Int = 0
        set(v) { field = v.coerceIn(-80_000, 80_000); _etat.update { it.copy(decalageHz = field) } }

    /** The sound sent to the browsers that listen (see [DiffusionSon]). */
    @Volatile var diffusion: DiffusionSon? = null

    /** Asked from the page: the strongest signal near the listened frequency. */
    @Volatile private var vaAuPic = false
    fun calePic() { vaAuPic = true }

    /**
     * Asked from the page (fine tuning, as the phone's): the voice heard set
     * where a sideband voice sits — its centre of gravity, ±3 kHz around
     * where we listen, put 1.5 kHz above (USB) or below (LSB). Useless in FM.
     */
    @Volatile private var vaVoix = false
    fun caleVoix(): Boolean { if (fr.f4ioz.satcombo.domain.AccordFin.cibleVoixHz(mode().name) == 0) return false; vaVoix = true; return true }

    /** The mode demodulated now (the chosen one, or the transmitter's). */
    fun modeEffectif(): String = mode().name

    // ------------------------------------------------------------ the waterfall's scale

    /** What the receiver follows now, for the scale: the satellite and its transmitter, or null (manual, QO-100, a sonde, a scan). */
    fun suiviEchelle(): Pair<Int, String>? {
        if (!suitSatellite || passager != null || pourSonde || pourQo100()) return null
        val s = station.etat.value.suivi ?: return null
        val t = station.emetteurChoisi() ?: return null
        return s.catalogNumber to t.description
    }

    /** The scale's markers over all the receiver sees (its centre ± half the rate). */
    fun echelle(): List<fr.f4ioz.satcombo.domain.EchelleSdr.Marque> {
        val c = _etat.value.cielHz.takeIf { it > 0 } ?: return emptyList()
        val demi = Dsp.RTL_RATE / 2L
        return station.echelleSdr(c - demi, c + demi, suiviEchelle())
    }

    /**
     * A click on the scale at sky [hz] ([accord] what it means): a
     * transmitter or a transponder followed with its Doppler, or a plain
     * frequency (the receiver's centre stays, the offset goes there). With a
     * recording or the generator the centre is fixed: the offset goes to the
     * place clicked in every case. Null when done, else why not.
     */
    fun accordeEchelle(accord: fr.f4ioz.satcombo.domain.EchelleSdr.Accord, hz: Long): String? {
        if (pourQo100()) return "le récepteur est sur QO-100 (onglet QO-100)"
        if (pourSonde) return "le récepteur écoute une radiosonde"
        val centre = _etat.value.cielHz.takeIf { it > 0 } ?: hz
        val fixe = fictif
        val ecart = { (hz - centre).coerceIn(-DECALAGE_MAX_HZ, DECALAGE_MAX_HZ).toInt() }
        when (accord) {
            is fr.f4ioz.satcombo.domain.EchelleSdr.Accord.Emetteur -> {
                if (!station.suisEmetteur(accord.catnum, accord.emetteur)) return "satellite ou émetteur inconnu"
                passager = null; suitSatellite = true
                decalageHz = if (fixe) ecart() else 0
            }
            is fr.f4ioz.satcombo.domain.EchelleSdr.Accord.Bande -> {
                if (!station.suisEmetteur(accord.catnum, accord.emetteur, accord.reposHz)) return "satellite ou transpondeur inconnu"
                passager = null; suitSatellite = true
                decalageHz = if (fixe) ecart() else 0
            }
            is fr.f4ioz.satcombo.domain.EchelleSdr.Accord.Terrestre -> {
                passager = null
                if (!fixe && fiPoste == null) { suitSatellite = false; manuelleHz = centre }
                decalageHz = ecart()
            }
        }
        return null
    }

    // ------------------------------------------------------------ running

    @Volatile private var cle: SourceIq? = null
    @Volatile private var processus: Process? = null
    @Volatile private var generation = 0
    private var suivi: Job? = null
    /** The dongle frequency wanted (Hz, after converters), set by the Doppler loop. */
    @Volatile private var voulueHz = 0L

    /**
     * The whole band the dongle sees (16k bins over 1.0584 MHz, around the
     * PLL), computed only while a page asks for it — a 16k FFT three times a
     * second has no business running otherwise.
     */
    @Volatile var panorama: FloatArray? = null; private set
    @Volatile var panoramaPllHz = 0L; private set
    @Volatile var panoramaTrames = 0L; private set
    @Volatile private var panoramaDemandeMs = 0L
    fun demandePanorama() { panoramaDemandeMs = horloge() }
    /** The dongle's sound while on QO-100 ([Dsp.AUDIO_RATE]): LEILA listened for. */
    @Volatile var ecouteQo100: ((ShortArray, Int) -> Unit)? = null
    /** The PC speaker's level (1 as received): lowered while a spoken announcement plays. */
    @Volatile var gainHautParleur = 1f
    /**
     * The raw IQ while on QO-100, for the signal classifier (8-bit bytes or
     * 16-bit shorts, the count of values, the PLL): set by [SignauxQo100Pc],
     * which returns at once unless a page asked lately.
     */
    @Volatile var iqQo100: ((ByteArray?, ShortArray?, Int, Long) -> Unit)? = null
    /** The dongle's sound for « Phonie → texte » ([Dsp.AUDIO_RATE]), with the squelch's gate when one is set. */
    @Volatile var ecouteVoix: ((ShortArray, Int, Boolean?) -> Unit)? = null
    /** The dongle's sound for the SSTV decoder ([Dsp.AUDIO_RATE]), whatever the Son tab's source. */
    @Volatile var ecouteSstv: ((ShortArray, Int) -> Unit)? = null
    /** The dongle's sound off QO-100 ([Dsp.AUDIO_RATE]), for the 1200 bit/s BPSK telemetry ([TelemesurePc]). */
    @Volatile var ecouteTelemesure: ((ShortArray, Int) -> Unit)? = null
    /**
     * The raw I/Q off QO-100 and METEOR, for the 1200–9600 bit/s packet
     * decoder ([TelemesurePc]): bytes or 16-bit pairs, the count, the
     * listened frequency's offset from the centre (Hz), the receiver being
     * the test generator or a recording. Returns at once when the option is off.
     */
    @Volatile var iqPaquets: ((ByteArray?, ShortArray?, Int, Double, Boolean) -> Unit)? = null
    /**
     * « Décodeurs multiples » on QO-100 ([CanauxPc]): more channels from the
     * same IQ, each with its decoder; null or off, nothing more runs.
     */
    @Volatile var canaux: CanauxPc? = null

    /**
     * The panorama's axis, in QO-100's nominal sky: its centre and signed
     * width. The dongle's PLL back through the LNB, minus the calibration
     * already applied — so the beacon's offset measured on it is exactly what
     * to add to the calibration (one step), and the band plan lines up.
     * The same axis for the measurement and the drawing, as on the phone.
     */
    fun axeQo100(): Pair<Double, Double>? {
        val pll = panoramaPllHz.takeIf { it > 0 && _etat.value.actif } ?: return null
        return axeQo100(pll)
    }

    /** The same axis for a stream read with the PLL at [pll]. */
    fun axeQo100(pll: Long): Pair<Double, Double> {
        val q = station.qo100.etat.value
        val centre = (if (cle is fr.f4ioz.satcombo.sdr.SourceFiPoste) pll else station.depuisCle(pll)).toDouble() - q.calageHz
        val conv = station.convRx()
        val inverse = conv.configure && conv.inverseur && conv.couvre(q.descenteHz + q.calageHz)
        return centre to (if (inverse) -Dsp.RTL_RATE.toDouble() else Dsp.RTL_RATE.toDouble())
    }

    /** The running chain's lock on the middle beacon (null before the receiver first runs). */
    @Volatile var verrou: fr.f4ioz.satcombo.sdr.VerrouBalise? = null
        private set

    /** The middle beacon in the last panorama: its offset and how far above the noise. */
    fun mesureBalise(): fr.f4ioz.satcombo.domain.MesureBalise.Mesure? {
        val pan = panorama ?: return null
        val (c, e) = axeQo100() ?: return null
        return fr.f4ioz.satcombo.domain.MesureBalise.mesurer(pan, c, e)
    }

    /**
     * The beacon's centre (its two lobes weighed, not one of them), searched
     * within [fenetreHz] of where it should be — narrow once it is known,
     * so a strong station 20 kHz away is never taken for it.
     */
    fun mesureBaliseCentre(fenetreHz: Double): fr.f4ioz.satcombo.domain.MesureBalise.Mesure? {
        val pan = panorama ?: return null
        val (c, e) = axeQo100() ?: return null
        return fr.f4ioz.satcombo.domain.MesureBalise.mesurerCentre(pan, c, e, fenetreHz = fenetreHz)
    }

    /** The last spectrum: dB per bin from −88.2 to +88.2 kHz around the PLL. */
    @Volatile var spectre: FloatArray? = null; private set
    @Volatile var trames = 0L; private set
    /** The PLL the last spectrum was taken with. */
    @Volatile var spectrePllHz = 0L; private set
    /** The squelch open on the last block (always open when it is off). */
    @Volatile var squelchOuvert = false; private set

    /** The sky frequency in the middle of a spectrum taken with the PLL at [pll] (the generator's, a recording's own). */
    fun cielDePll(pll: Long): Long = generateur?.let { centreGenerateurHz } ?: lecture?.capture()?.frequenceHz
        // The rig's IF: its dial is the sky already.
        ?: if (cle is fr.f4ioz.satcombo.sdr.SourceFiPoste) pll else station.depuisCle(pll)

    /** −1 when an inverting converter turns the spectrum over at [cielHz] (sky above, bins below). */
    fun sensSpectre(cielHz: Long): Int {
        if (fictif || cle is fr.f4ioz.satcombo.sdr.SourceFiPoste) return 1
        val c = station.convRx()
        return if (c.configure && c.inverseur && c.couvre(cielHz)) -1 else 1
    }

    /** QO-100's tab holds the receiver (its frequency, its mode). */
    val surQo100: Boolean get() = pourQo100()

    /**
     * The dongle on the satellite (or QO-100, or the manual frequency).
     * Starting and stopping take turns: an automatic start (a METEOR pass)
     * crossing one asked from the page left an rtl_tcp holding the dongle.
     */
    @Synchronized fun demarre(pourImages: Boolean = false): Boolean {
        arrete()
        imagesVoulues = pourImages
        return lance()
    }

    /**
     * METEOR's pictures asked (its tab's "Recevoir", or the automatic pass):
     * only then does the dongle go silent for the picture decoder. Started
     * from the SDR or QO-100 tab it gives sound, whatever the satellite
     * followed — it stayed silent after a night's METEOR pass left
     * METEOR-M2 4 as the satellite followed (15 October).
     */
    @Volatile private var imagesVoulues = false

    private fun lance(): Boolean {
        val gen = ++generation
        seance++
        _etat.update { Etat(statut = "connexion…") }
        if (recepteur == GENERATEUR) centreGenerateurHz = if (pourQo100()) station.qo100.etat.value.let { it.descenteHz + it.calageHz } else manuelleHz
        val r = runCatching { connecte() }.getOrElse { e ->
            arreteProcessus()
            _etat.update { Etat(erreur = e.message ?: if (estPluto()) "ADALM-PLUTO injoignable" else if (estBladerf()) "bladeRF injoignable"
                else if (recepteur == FI_POSTE) "FI de l'IC-9700 indisponible" else if (recepteur == NOEUD) "SatMe-Node injoignable" else "rtl_tcp injoignable") }
            return false
        }
        cle = r
        runCatching {
            r.setSampleRate(Dsp.RTL_RATE)
            if (settings.sdrPpm != 0) r.setFreqCorrection(settings.sdrPpm)
            r.setAgc(settings.sdrAgc)
            r.setGain(settings.sdrGainTenthDb.takeIf { it >= 0 })
            if (biasTee && r is RtlTcp) r.setBiasTee(true)
        }
        cibleMaintenant()
        val nom = when (r) { is RtlTcp -> RtlTcp.nomTuner(r.tuner); is PlutoSdr -> r.description; is BladerfSdr -> r.description
            is fr.f4ioz.satcombo.sdr.SourceNoeudIq -> r.description
            is fr.f4ioz.satcombo.sdr.SourceFichierIq -> "Fichier I/Q " + r.donnees.name.removeSuffix(fr.f4ioz.satcombo.sdr.SigMF.EXT_DATA)
            is fr.f4ioz.satcombo.sdr.GenerateurIq -> "Générateur d'essai"
            is fr.f4ioz.satcombo.sdr.SourceFiPoste -> "IC-9700 FI · " + (if (r.transport == "usb") "carte USB" else "réseau") +
                " ±" + String.format(java.util.Locale.FRANCE, "%.1f", r.plan.demiUtileHz / 1000) + " kHz"
            else -> "" }
        _etat.update { it.copy(actif = true, statut = "réception", erreur = "", tuner = nom) }
        // METEOR: 120 kHz of digital signal, nothing to hear — the raw samples to the picture decoder.
        val m = meteor
        if (m != null && imagesVoulues && !pourSonde && !pourQo100() && suitSatellite && station.estMeteor()) {
            pourMeteor = true
            m.recepteur.cotes = settings.meteorCotes
            m.recepteur.demarre(Dsp.RTL_RATE.toDouble(), station.etat.value.suivi?.name ?: "METEOR", station.monteVersLeNord(), station.etat.value.suivi)
        }
        if (!pourSonde && !pourMeteor) son.prendPour(NOM_SOURCE, Dsp.AUDIO_RATE)
        thread(isDaemon = true, name = "satme-sdr") { boucle(r, gen) }
        suivi = portee.launch {
            while (generation == gen) { runCatching { cibleMaintenant() }; delay(250) }
        }
        return true
    }

    @Synchronized fun arrete() {
        if (pourSonde) { sondes.arrete(); pourSonde = false }
        if (pourMeteor) {
            pourMeteor = false
            meteor?.let { m -> val l = m.recepteur.arrete(); if (l.isNotEmpty()) thread(isDaemon = true, name = "satme-meteor-ecrit") { m.ecrit(l) } }
        }
        generation++
        suivi?.cancel(); suivi = null
        cle?.let { runCatching { it.close() } }
        cle = null
        arreteProcessus()
        son.rendPar(NOM_SOURCE)
        spectre = null; panorama = null
        voulueHz = 0L
        _etat.update { Etat() }
    }

    /** The DATV tab's local spectrum holds the receiver now: this tab may not start on it meanwhile. */
    @Volatile var spectreDatvTient = false; private set

    /**
     * The receiver as set in this tab, opened for the DATV tab's local
     * spectrum (receive only, its own tuning): refused while this tab
     * receives or another holds it, and for what has no wide band to show
     * (a recording, the rig's IF). Given back by [rendSpectreDatv].
     */
    @Synchronized fun ouvrePourSpectreDatv(): SourceIq {
        if (spectreDatvTient) throw java.io.IOException("le spectre DATV tient déjà le récepteur")
        if (etat.value.actif) throw java.io.IOException("l'onglet SDR reçoit avec ce récepteur : l'y arrêter d'abord")
        if (recepteur == FICHIER || recepteur == FI_POSTE) throw java.io.IOException("choisir dans l'onglet SDR un récepteur branché sur la tête QO-100 (RTL-SDR, ADALM-PLUTO, bladeRF, SatMe-Node)")
        val r = connecte()
        spectreDatvTient = true
        return r
    }

    /** The receiver given back by the DATV tab's spectrum (closed already). */
    @Synchronized fun rendSpectreDatv() { spectreDatvTient = false; arreteProcessus() }

    /** rtl_tcp started here when asked and nothing answers yet; then the connection, retried while it starts. */
    private fun connecte(): SourceIq {
        if (spectreDatvTient) throw java.io.IOException("le récepteur sert au spectre de l'onglet DATV : l'y couper d'abord")
        // The demonstration: the test generator or a recording, never a receiver.
        if (recepteur != GENERATEUR && recepteur != FICHIER) ModeDemo.refus("ce récepteur")?.let { throw java.io.IOException(it + " : choisir « Générateur d'essai »") }
        // No hardware: nothing to lend to DATV, nothing that could transmit.
        if (recepteur == GENERATEUR) return fr.f4ioz.satcombo.sdr.GenerateurIq(reglagesGenerateur)
        // The rig's IF: no SDR hardware, the rig tunes itself (CAT).
        if (recepteur == FI_POSTE) return (ouvreFi ?: throw java.io.IOException("la FI du poste n'est pas disponible ici"))()
        // A SatMe-Node's SDR: the board is there, its guards there too.
        if (recepteur == NOEUD) return (ouvreNoeud ?: throw java.io.IOException("aucun SatMe-Node associé : l'associer dans l'onglet DATV (Changer : son adresse et son code)"))()
        if (recepteur == FICHIER) {
            val f = File(fichierIq)
            if (fichierIq.isBlank() || !f.isFile) throw java.io.IOException("aucun enregistrement choisi : « I/Q » › Rejouer")
            val (o, pourquoi) = fr.f4ioz.satcombo.sdr.SourceFichierIq.meta(f, fichierDebit.toDouble(), fichierFrequence.takeIf { it > 0 } ?: manuelleHz)
            val (d, m) = o ?: throw java.io.IOException(pourquoi)
            return fr.f4ioz.satcombo.sdr.SourceFichierIq(d, m)
        }
        if (estBladerf()) {
            if (emissionBladerf()) throw java.io.IOException("le bladeRF émet (onglet DATV ou SSTV) : arrêter l'émission d'abord")
            if (datvTient == BLADERF) throw java.io.IOException(DATV_TIENT_BLADERF)
            val prog = programmeBladerf() ?: throw java.io.IOException(conseilBladerf())
            val c = infoBladerf.carte
            return BladerfSdr.ouvre(prog, c?.serie.orEmpty(), c?.libelle ?: "bladeRF 2.0")
        }
        if (estPluto()) {
            if (emissionPluto()) throw java.io.IOException("l'ADALM-PLUTO émet (onglet DATV ou SSTV) : arrêter l'émission d'abord")
            if (datvTient == PLUTO) throw java.io.IOException(DATV_TIENT_PLUTO)
            if (adsbTient() == PLUTO) throw java.io.IOException(ADSB_TIENT_PLUTO)
            val p = PlutoSdr.connecte(plutoHote, plutoPort)
            if (coupeTxEnReception) {
                val (h, n) = plutoHote to plutoPort
                thread(isDaemon = true, name = "satme-pluto-coupe-tx") { fr.f4ioz.satcombo.sdr.PlutoEmission.coupeOscillateur(h, n) }
            }
            return p
        }
        if (adsbTient() == RTL) throw java.io.IOException(ADSB_TIENT_CLE)
        if (aisTient()) throw java.io.IOException(AIS_TIENT_CLE)
        runCatching { return RtlTcp.connecte(hote, port, 1500) }
        if (!lance || !estLocal(hote)) throw java.io.IOException("rtl_tcp ne répond pas sur $hote:$port")
        val p = runCatching {
            ProcessBuilder(OutilsPc.trouve("rtl_tcp") ?: "rtl_tcp", "-a", "127.0.0.1", "-p", port.toString(), "-s", Dsp.RTL_RATE.toString())
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "satme-rtl_tcp.log")).start()
        }.getOrElse { throw java.io.IOException("rtl_tcp introuvable : " + OutilsPc.conseil("rtl_tcp")) }
        processus = p
        val fin = horloge() + 6000
        while (horloge() < fin) {
            if (!p.isAlive) throw java.io.IOException("rtl_tcp s'est arrêté : clé absente ou déjà prise (" + journalRtl() + ")")
            runCatching { return RtlTcp.connecte("127.0.0.1", port, 1000) }
            Thread.sleep(300)
        }
        throw java.io.IOException("rtl_tcp ne répond pas")
    }

    private fun journalRtl(): String = runCatching {
        File(System.getProperty("java.io.tmpdir"), "satme-rtl_tcp.log").readLines().lastOrNull { it.isNotBlank() }.orEmpty()
    }.getOrDefault("")

    private fun arreteProcessus() {
        processus?.let { p -> runCatching { p.destroy(); if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } }
        processus = null
    }

    private fun estLocal(h: String) = h == "127.0.0.1" || h == "localhost" || h == "::1"

    /** QO-100's tab has the dongle: its frequency (no Doppler on a geostationary), in USB. */
    private fun pourQo100(): Boolean = station.qo100.etat.value.aLaCle

    /** The mode to demodulate: chosen, or the transmitter's. */
    /**
     * A tuning held in memory only, over the kept one (the scan's steps):
     * sky frequency, mode ("": the chosen one), width (−1: the chosen one).
     * Never written to the settings — a scan writes nothing several times a
     * second, and a scan step is never the frequency found at the next start.
     */
    data class AccordPassager(val hz: Long, val mode: String = "", val largeurHz: Int = -1)
    @Volatile var passager: AccordPassager? = null

    /** The width demodulated now (the transient tuning's, else the chosen one). */
    private fun largeur(): Int = passager?.largeurHz?.takeIf { it >= 0 } ?: settings.sdrBandwidthHz

    private fun mode(): RxMode = passager?.mode?.takeIf { it.isNotEmpty() }?.let { m -> runCatching { RxMode.valueOf(m) }.getOrNull() } ?: when (val m = modeChoisi) {
        "AUTO" -> if (pourQo100()) RxMode.USB else modeDe(station.ecouteSdr()?.mode.orEmpty())
        else -> runCatching { RxMode.valueOf(m) }.getOrDefault(RxMode.NFM)
    }

    /** Sound cleaning per demodulation mode ([RxCleaning]), kept in the settings. */
    @Volatile var nettoyages: Map<RxMode, RxCleaning> = RxCleaning.parMode(settings.sdrNettoyage); private set

    /** The mode being demodulated, for the cleaning settings shown. */
    fun modeNettoyage(): RxMode = mode()

    /** [q]'s cleaning settings applied to [m]'s (the mode demodulated if null) and kept. */
    fun regleNettoyage(q: Map<String, String>, m: RxMode? = null) {
        val k = m ?: mode()
        val t = nettoyages.toMutableMap()
        t[k] = (t[k] ?: RxCleaning.OFF).avec(q)
        nettoyages = t
        settings.sdrNettoyage = RxCleaning.texte(t)
    }

    /** What the cleaning does now: carriers notched (audio Hz), share of IQ blanked. */
    @Volatile var notchesVus: List<Double> = emptyList(); private set
    @Volatile var tauxBlanc: Float = 0f; private set

    /** Listening to CW (the automatic notch then waits 2 s), from the width, the transmitter or the log's QO-100 mode. */
    @Volatile private var ecouteCw = false

    /** The Doppler loop: where the dongle should be now. */
    private fun cibleMaintenant() {
        ecouteCw = largeur() in 1..1_000 ||
            (if (pourQo100()) settings.qo100ModeCarnet == "CW" else station.ecouteSdr()?.mode.orEmpty().uppercase().contains("CW"))
        // A recording: its own frequency (and its Doppler's software share as heard); the generator: its centre.
        lecture?.let { l ->
            val c = l.capture()
            val ciel = c.frequenceHz + Math.round(c.fineHz)
            voulueHz = 0L
            _etat.update { it.copy(cielHz = ciel, reposHz = ciel, satellite = l.meta.satellite.ifBlank { "Fichier I/Q" }) }
            return
        }
        if (generateur != null) {
            voulueHz = 0L
            _etat.update { it.copy(cielHz = centreGenerateurHz, reposHz = centreGenerateurHz, satellite = "Générateur d'essai") }
            return
        }
        if (cle is fr.f4ioz.satcombo.sdr.SourceFiPoste) {
            // The IF is centred on the rig's dial, which the CAT Doppler loop keeps tuning: the axis follows it.
            val pas = passager
            val e = if (suitSatellite && pas == null) station.ecouteSdr() else null
            val lu = cadranPoste()
            cadranLu = lu != null
            val dial = lu ?: e?.let { if (settings.sdrDopplerTrack) it.cibleHz else it.reposHz } ?: pas?.hz ?: manuelleHz
            cadranFiHz = dial
            voulueHz = 0L
            // « Recentrer le poste »: the tuning moves the rig's RX dial instead, the IF's listening point back in the middle.
            val d = decalageHz
            if (recentrePoste && d != 0 && lu != null && deplacePoste(d)) decalageHz = 0
            _etat.update { it.copy(cielHz = dial, reposHz = e?.reposHz ?: dial, satellite = e?.satellite.orEmpty()) }
            return
        }
        if (pourSonde) {
            val f = settings.sondeFreqHz
            voulueHz = f
            _etat.update { it.copy(cielHz = f, reposHz = f, satellite = "Radiosonde") }
            return
        }
        if (pourQo100()) {
            val q = station.qo100.etat.value
            val ciel = q.descenteHz + q.calageHz
            voulueHz = station.versCle(ciel)
            _etat.update { it.copy(cielHz = ciel, reposHz = ciel, satellite = "QO-100") }
            return
        }
        val pas = passager
        val e = if (suitSatellite && pas == null) station.ecouteSdr() else null
        val (repos, ciel) = if (e != null) {
            if (settings.sdrDopplerTrack) e.reposHz to e.cibleHz else e.reposHz to e.reposHz
        } else (pas?.hz ?: manuelleHz).let { it to it }
        voulueHz = station.versCle(ciel)
        _etat.update { it.copy(cielHz = ciel, reposHz = repos, satellite = e?.satellite.orEmpty()) }
    }

    private fun boucle(r: SourceIq, gen: Int) {
        val chaine = RxChain()
        val iq = ByteArray(RtlTcp.BLOC)
        // A receiver with more bits (the Pluto): its 16-bit samples, the same count of pairs per block.
        val iq16 = if (r.iq16) ShortArray(RtlTcp.BLOC) else null
        val pcm = ShortArray(chaine.maxAudio(iq.size))
        var pll = 0L; var fine = 0L
        var dernierSpectre = 0L; var dernierEtat = 0L; var dernierOctets = 0L
        var trameVue = 0L
        var dernierPano = 0L; var panoVue = 0L
        var dernierRecentre = 0L
        val hp = HautParleurPc(Dsp.AUDIO_RATE)
        // The secondary channels: run inside the chain on each block, for those given then.
        var cibles = emptyList<fr.f4ioz.satcombo.sdr.CanauxSecondaires.Cible>()
        val cp0 = canaux
        cp0?.dsp?.vide()
        if (cp0 != null) chaine.canaux = { aI, aQ, na, bI, bQ, n1 ->
            val c = cibles
            if (c.isNotEmpty()) cp0.dsp.traite(aI, aQ, na, bI, bQ, n1, c) { id, p, k -> cp0.recoit(id, p, k) }
        }
        try {
            val fichier = r as? fr.f4ioz.satcombo.sdr.SourceFichierIq
            val essai = r is fr.f4ioz.satcombo.sdr.GenerateurIq
            val fi = r as? fr.f4ioz.satcombo.sdr.SourceFiPoste
            while (generation == gen) {
                val voulue = voulueHz
                if (fichier != null) {
                    // Tuned as it was recorded: its PLL (seen through today's converter), its Doppler's software share.
                    val c = fichier.capture()
                    pll = station.versCle(c.frequenceHz)
                    val f = Math.round(c.fineHz)
                    if (f != fine) { fine = f; chaine.dopplerFineHz = fine.toDouble() }
                } else if (essai) {
                    pll = station.versCle(centreGenerateurHz)
                } else if (fi != null) {
                    // The rig's dial, sky Hz: the IF's centre, nothing to tune here.
                    pll = cadranFiHz
                } else if (voulue > 0) {
                    val plan = DopplerTuner.plan(voulue, pll, fine)
                    if (plan.retune) { r.setCenterFreq(plan.pllHz); pll = plan.pllHz }
                    if (plan.fineHz != fine) { fine = plan.fineHz; chaine.dopplerFineHz = fine.toDouble() }
                }
                val sonde = pourSonde
                // A sonde modulates bits, not a voice: NFM at its model's width, no squelch
                // (a distant sonde drops below it long before it stops decoding), and above all
                // no de-emphasis — it rounds the edges and nothing decodes.
                val m = if (sonde) RxMode.NFM else mode()
                if (chaine.mode != m) { chaine.mode = m; chaine.reset() }
                val bw = (if (sonde) fr.f4ioz.satcombo.sonde.SondeModel.bandwidthFor(settings.sondeModel) else largeur()).toDouble()
                if (chaine.bandwidthHz != bw) chaine.bandwidthHz = bw
                val sq = if (sonde) -200f else settings.sdrSquelchDb.toFloat()
                if (chaine.squelch.thresholdDb != sq) chaine.squelch.thresholdDb = sq
                val dec = decalageHz.toDouble()
                if (chaine.offsetHz != dec) chaine.offsetHz = dec
                val deemph = !sonde && settings.sdrDeemph
                if (chaine.deemphasis != deemph) chaine.deemphasis = deemph
                // A sonde's bits, METEOR's samples: never cleaned.
                val net = if (sonde || pourMeteor) RxCleaning.OFF else nettoyages[m] ?: RxCleaning.OFF
                if (chaine.cleaning !== net) chaine.cleaning = net
                chaine.cw = ecouteCw
                // CTCSS read in NFM (receive only), and the tone squelch when one is chosen; never for a sonde or METEOR.
                chaine.subTones = m == RxMode.NFM && !sonde && !pourMeteor
                val tsq = if (sonde || pourMeteor) 0.0 else fr.f4ioz.satcombo.sdr.DetecteurSousTon.normalise(settings.sdrSilencieuxTon / 10.0)
                if (chaine.toneSquelchHz != tsq) chaine.toneSquelchHz = tsq

                val n = if (iq16 != null) r.read16(iq16, 200) else r.read(iq, 200)
                if (n < 0) {
                    val pourquoi = if (r is PlutoSdr) r.erreur ?: "l'ADALM-PLUTO a coupé la liaison"
                        else if (r is BladerfSdr) r.erreur ?: "bladeRF-cli s'est arrêté"
                        else if (r is fr.f4ioz.satcombo.sdr.SourceNoeudIq) r.erreur ?: "le SatMe-Node a coupé la liaison"
                        else if (fichier != null) "lecture terminée (fin de l'enregistrement)"
                        else if (essai) "générateur arrêté" else if (fi != null) "la FI du poste ne vient plus" else "rtl_tcp a coupé la liaison"
                    if (generation == gen) _etat.update { it.copy(actif = false, statut = "arrêtée", erreur = pourquoi) }
                    break
                }
                if (n == 0 && fichier != null) _etat.update { it.copy(statut = if (fichier.enPause) "en pause" else "calage sur les tranches FT8…") }
                if (n == 0) continue
                // The raw I/Q kept (« Remonter le temps », « Enregistrer »): a real receiver's, or the generator's — never a recording's again.
                if (fichier == null) iqBrut?.let { runCatching { it(if (iq16 == null) iq else null, iq16, n, if (essai) centreGenerateurHz else if (fi != null) pll else station.depuisCle(pll), pll, fine.toDouble()) } }
                // METEOR pictures: the raw samples, before any audio chain, decoded on their own thread.
                if (pourMeteor) meteor?.recepteur?.let { m ->
                    if (iq16 != null) m.iq16(iq16, n, (fine + decalageHz).toDouble()) else m.iq(iq, n, (fine + decalageHz).toDouble())
                }
                iqQo100?.let { if (pourQo100()) it(if (iq16 == null) iq else null, iq16, n, pll) }
                if (!pourMeteor && !sonde && !pourQo100()) iqPaquets?.let { runCatching { it(if (iq16 == null) iq else null, iq16, n, (fine + decalageHz).toDouble(), essai || fichier != null) } }
                val maintenant = horloge()
                // Still for METEOR? (QO-100 set on the dongle, another satellite followed: the sound back.)
                if (pourMeteor && maintenant - dernierEtat >= 200L) quitteMeteorSiBesoin()
                val veutSpectre = maintenant - dernierSpectre >= 90L
                cibles = if (cp0 != null && !sonde && !pourMeteor && pll > 0 && pourQo100()) cp0.cibles(axeQo100(pll)) else emptyList()
                cp0?.tourne = cibles.isNotEmpty()
                val veutPano = maintenant - panoramaDemandeMs < 3000L && maintenant - dernierPano >= 300L
                // Locked on the middle beacon when chosen: QO-100 on the dongle, its axis known.
                val vb = chaine.verrou
                val verrouVoulu = settings.qo100VerrouBalise && !sonde && !pourMeteor && pll > 0 && pourQo100() && fi == null
                if (verrouVoulu != vb.actif) { vb.reset(); vb.actif = verrouVoulu }
                if (verrouVoulu) {
                    val (c, e) = axeQo100(pll)
                    vb.decalageHz = (fr.f4ioz.satcombo.domain.Qo100.BALISE_MEDIANE_HZ - c) * Math.signum(e)
                }
                verrou = vb
                val produit = if (iq16 != null) chaine.process16(iq16, n, pcm, feedSpectrum = veutSpectre, feedPanorama = veutPano)
                    else chaine.process(iq, n, pcm, feedSpectrum = veutSpectre, feedPanorama = veutPano)
                if (veutSpectre) dernierSpectre = maintenant
                squelchOuvert = chaine.squelch.open
                // A channel heard in place of the main receiver; its decoders fed by the channels, not by it.
                val ecoute = if (cibles.isNotEmpty()) cp0?.prendEcoute() else null
                val canauxSstv = cibles.isNotEmpty() && cp0?.tientSstv() == true
                val canauxVoix = cibles.isNotEmpty() && cp0?.tientPhonie() == true
                if (veutPano) dernierPano = maintenant
                if (chaine.panorama.frames != panoVue) {
                    panoVue = chaine.panorama.frames
                    panoramaPllHz = pll
                    panorama = chaine.panorama.magDb.copyOf(); panoramaTrames++
                }
                if (chaine.spectrum.frames != trameVue) {
                    trameVue = chaine.spectrum.frames
                    spectrePllHz = pll; spectre = chaine.spectrum.magDb.copyOf(); trames++
                    // The sonde: the centre of gravity of what stands above the noise, ±25 kHz
                    // around the tuning; nothing found (silence): the tuning stays.
                    if (sonde && (recentreVoulu || (recentreAuto && maintenant - dernierRecentre >= 2_000L))) {
                        recentreVoulu = false; dernierRecentre = maintenant
                        val c = chaine.centroidOffsetHz(searchHz = 25_000.0)
                        if (c != 0.0) decalageHz = (fr.f4ioz.satcombo.domain.AccordFin.accordVise(c, 0) - fine).toInt()
                    }
                    if (vaVoix) {
                        vaVoix = false
                        val cible = fr.f4ioz.satcombo.domain.AccordFin.cibleVoixHz(mode().name)
                        val c = chaine.centroidOffsetHz(searchHz = fr.f4ioz.satcombo.domain.AccordFin.RECHERCHE_VOIX_HZ, centreHz = (fine + decalageHz).toDouble())
                        if (cible != 0 && c != 0.0) decalageHz = (fr.f4ioz.satcombo.domain.AccordFin.accordVise(c, cible) - fine).toInt()
                    }
                    if (vaAuPic) {
                        vaAuPic = false
                        // Around the listened frequency: ±15 kHz, as the phone's button.
                        val ici = (fine + decalageHz).toDouble()
                        val p = chaine.peakOffsetHz(ici - 15_000.0, ici + 15_000.0)
                        if (p != 0.0) decalageHz = (Math.round(p) - fine).toInt()
                    }
                }
                if (produit > 0 && sonde) sondes.ecoute(pcm, produit)
                else if (produit > 0 && !pourMeteor) {
                    // Never waits for the speaker: kept in its ring, played at the card's pace.
                    val (ePcm, eN) = ecoute ?: (pcm to produit)
                    // A recording played faster: the decoders take it all, the speaker would only choke.
                    hp.gain = gainHautParleur
                    if (settings.sdrAudio && (fichier == null || fichier.vitesse == 1.0)) hp.pousse(ePcm, eN)
                    if (son.etat.value.appareil == NOM_SOURCE && son.etat.value.actif) son.distribue(ePcm, eN, Dsp.AUDIO_RATE)
                    if (pourQo100()) ecouteQo100?.invoke(pcm, produit)
                    if (!canauxVoix) ecouteVoix?.invoke(pcm, produit,
                        if (settings.sdrSquelchDb > -120 || chaine.toneSquelchHz > 0.0) chaine.squelch.open && !chaine.toneSquelchClosed else null)
                    if (!canauxSstv) ecouteSstv?.let { runCatching { it(pcm, produit) } }
                    if (!pourQo100()) ecouteTelemesure?.let { runCatching { it(pcm, produit) } }
                    // The browsers listening to the dongle (the page open on another PC).
                    diffusion?.ajoute("sdr", ePcm, eN, Dsp.AUDIO_RATE)
                }
                if (maintenant - dernierEtat >= 250L) {
                    val debit = ((r.octets - dernierOctets) * 1000 / maxOf(1L, maintenant - dernierEtat) / 1024).toInt()
                    dernierOctets = r.octets; dernierEtat = maintenant
                    notchesVus = chaine.autoNotches(); tauxBlanc = if (net.nb) chaine.blankedRatio() else 0f
                    val statut = if (fichier == null) "réception" else if (fichier.enPause) "en pause"
                        else if (fichier.attenteJusqua > maintenant) "calage sur les tranches FT8…" else "lecture"
                    val st = chaine.subTone
                    _etat.update { it.copy(statut = statut, pllHz = pll, fineHz = fine, niveauDb = chaine.levelDb, saturation = chaine.clipRatio,
                        ctcssHz = st.hz, ctcssConfiance = st.confiance, ctcssDevHz = st.deviationHz, tonFerme = chaine.toneSquelchClosed,
                        debitKo = debit, perdus = r.perdus, erreur = when (r) { is PlutoSdr -> r.avertissement; is BladerfSdr -> r.avertissement
                            is fr.f4ioz.satcombo.sdr.SourceNoeudIq -> r.avertissement
                            is fr.f4ioz.satcombo.sdr.SourceFiPoste -> r.avertissement()
                                ?: if (!cadranLu) "cadran du poste non lu (CAT non connecté) : l'axe suit la fréquence visée" else ""
                            else -> it.erreur }) }
                }
            }
        } finally {
            cp0?.tourne = false
            hp.ferme()
        }
    }

    companion object {
        const val DATV_TIENT_PLUTO = "l'ADALM-PLUTO reçoit la DATV (onglet DATV) : arrêter cette réception d'abord (l'onglet SDR reprendra ensuite)"
        const val DATV_TIENT_BLADERF = "le bladeRF reçoit la DATV (onglet DATV) : arrêter cette réception d'abord (l'onglet SDR reprendra ensuite)"
        const val AIS_TIENT_CLE = "la clé reçoit l'AIS (onglet Navires) : arrêter cette réception d'abord"
        const val ADSB_TIENT_CLE = "la clé reçoit l'ADS-B (onglet Avions) : arrêter cette réception d'abord"
        const val ADSB_TIENT_PLUTO = "l'ADALM-PLUTO reçoit l'ADS-B (onglet Avions) : arrêter cette réception d'abord"
        const val NOM_SOURCE = "Clé SDR (rtl_tcp)"
        const val CLE_HOTE = "pc_sdr_hote"
        const val CLE_PORT = "pc_sdr_port"
        const val CLE_LANCE = "pc_sdr_lance"
        const val CLE_BIAS = "pc_sdr_bias"
        const val CLE_MODE = "pc_sdr_mode"
        const val CLE_MANUELLE = "pc_sdr_manuelle"
        /** The listening offset's reach from the centre (Hz), as the vernier's. */
        const val DECALAGE_MAX_HZ = 80_000L
        const val CLE_SUIT = "pc_sdr_suit"
        const val CLE_RECEPTEUR = "pc_sdr_recepteur"
        const val CLE_PLUTO_HOTE = "pc_sdr_pluto_hote"
        const val CLE_PLUTO_PORT = "pc_sdr_pluto_port"
        const val CLE_COUPE_TX = "pc_sdr_pluto_coupe_tx"
        const val CLE_BLADERF_OUTIL = "pc_sdr_bladerf_outil"
        const val RTL = "rtl"
        const val PLUTO = "pluto"
        const val BLADERF = "bladerf"
        /** A recording played back (« Fichier I/Q »). */
        const val FICHIER = "fichier"
        /** The test generator: noise and test signals, no hardware. */
        const val GENERATEUR = "generateur"
        /** The rig's IF output (IC-9700 set to IF), by its network audio or its USB sound card. */
        const val FI_POSTE = "fiposte"
        const val CLE_FI_RECENTRE = "pc_sdr_fi_recentre"
        /** A SatMe-Node's SDR (its bladeRF or Pluto), over the network. */
        const val NOEUD = "noeud"
        val RECEPTEURS = setOf(RTL, PLUTO, BLADERF, FICHIER, GENERATEUR, FI_POSTE, NOEUD)
        const val CLE_FICHIER = "pc_sdr_fichier_iq"
        const val CLE_FICHIER_DEBIT = "pc_sdr_fichier_debit"
        const val CLE_FICHIER_FREQ = "pc_sdr_fichier_freq"
        const val CLE_GENERATEUR = "pc_sdr_generateur"

        /** A transmitter's mode as the dongle demodulates it. */
        fun modeDe(m: String): RxMode {
            val u = m.uppercase()
            return when {
                u.contains("LSB") -> RxMode.LSB
                u.contains("USB") || u.contains("SSB") || u.contains("CW") || u.contains("BPSK") || u.contains("LINEAR") -> RxMode.USB
                u == "AM" -> RxMode.AM
                else -> RxMode.NFM
            }
        }
    }
}
