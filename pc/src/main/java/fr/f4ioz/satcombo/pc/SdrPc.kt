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
import fr.f4ioz.satcombo.sdr.RtlTcp
import fr.f4ioz.satcombo.sdr.RxChain
import fr.f4ioz.satcombo.sdr.RxMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * The SDR dongle on the PC, through `rtl_tcp`: the phone's reception, the
 * same engine.
 *
 * - **rtl_tcp** is started here when the dongle is on this PC (and stopped
 *   with it), or reached on another machine of the network (a small
 *   computer by the antenna).
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
        if (meteor == null || !settings.meteorAuto) { meteorAutoArme = 0; return }
        val now = horloge()
        if (meteorAutoArme != 0) {
            if (!_etat.value.actif) { meteorAutoArme = 0; return }
            if (now > meteorAutoLos + 30_000L) { meteorAutoArme = 0; arrete() }
            return
        }
        if (_etat.value.actif) return
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
        meteorAutoArme = sat.catalogNumber
        meteorAutoLos = p.losEpochMs
        station.suis(sat)
        suitSatellite = true
        if (!demarre()) {
            // The dongle did not start: the satellite followed before put back.
            meteorAutoArme = 0
            if (avant != null && avant.catalogNumber != sat.catalogNumber) station.suis(avant)
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
        set(v) { stockage.edit().putBoolean(CLE_BIAS, v).apply(); cle?.let { runCatching { it.setBiasTee(v) } } }
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

    // ------------------------------------------------------------ running

    @Volatile private var cle: RtlTcp? = null
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

    /**
     * The panorama's axis, in QO-100's nominal sky: its centre and signed
     * width. The dongle's PLL back through the LNB, minus the calibration
     * already applied — so the beacon's offset measured on it is exactly what
     * to add to the calibration (one step), and the band plan lines up.
     * The same axis for the measurement and the drawing, as on the phone.
     */
    fun axeQo100(): Pair<Double, Double>? {
        val pll = panoramaPllHz.takeIf { it > 0 && _etat.value.actif } ?: return null
        val q = station.qo100.etat.value
        val centre = station.depuisCle(pll).toDouble() - q.calageHz
        val conv = station.convRx()
        val inverse = conv.configure && conv.inverseur && conv.couvre(q.descenteHz + q.calageHz)
        return centre to (if (inverse) -Dsp.RTL_RATE.toDouble() else Dsp.RTL_RATE.toDouble())
    }

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

    /**
     * The dongle on the satellite (or QO-100, or the manual frequency).
     * Starting and stopping take turns: an automatic start (a METEOR pass)
     * crossing one asked from the page left an rtl_tcp holding the dongle.
     */
    @Synchronized fun demarre(): Boolean {
        arrete()
        return lance()
    }

    private fun lance(): Boolean {
        val gen = ++generation
        _etat.update { Etat(statut = "connexion…") }
        val r = runCatching { connecte() }.getOrElse { e ->
            arreteProcessus()
            _etat.update { Etat(erreur = e.message ?: "rtl_tcp injoignable") }
            return false
        }
        cle = r
        runCatching {
            r.setSampleRate(Dsp.RTL_RATE)
            if (settings.sdrPpm != 0) r.setFreqCorrection(settings.sdrPpm)
            r.setAgc(settings.sdrAgc)
            r.setGain(settings.sdrGainTenthDb.takeIf { it >= 0 })
            if (biasTee) r.setBiasTee(true)
        }
        cibleMaintenant()
        _etat.update { it.copy(actif = true, statut = "réception", erreur = "", tuner = RtlTcp.nomTuner(r.tuner)) }
        // METEOR: 120 kHz of digital signal, nothing to hear — the raw samples to the picture decoder.
        val m = meteor
        if (m != null && !pourSonde && !pourQo100() && suitSatellite && station.estMeteor()) {
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

    /** rtl_tcp started here when asked and nothing answers yet; then the connection, retried while it starts. */
    private fun connecte(): RtlTcp {
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
    private fun mode(): RxMode = when (val m = modeChoisi) {
        "AUTO" -> if (pourQo100()) RxMode.USB else modeDe(station.ecouteSdr()?.mode.orEmpty())
        else -> runCatching { RxMode.valueOf(m) }.getOrDefault(RxMode.NFM)
    }

    /** The Doppler loop: where the dongle should be now. */
    private fun cibleMaintenant() {
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
        val e = if (suitSatellite) station.ecouteSdr() else null
        val (repos, ciel) = if (e != null) {
            if (settings.sdrDopplerTrack) e.reposHz to e.cibleHz else e.reposHz to e.reposHz
        } else manuelleHz to manuelleHz
        voulueHz = station.versCle(ciel)
        _etat.update { it.copy(cielHz = ciel, reposHz = repos, satellite = e?.satellite.orEmpty()) }
    }

    private fun boucle(r: RtlTcp, gen: Int) {
        val chaine = RxChain()
        val iq = ByteArray(RtlTcp.BLOC)
        val pcm = ShortArray(chaine.maxAudio(iq.size))
        var pll = 0L; var fine = 0L
        var dernierSpectre = 0L; var dernierEtat = 0L; var dernierOctets = 0L
        var trameVue = 0L
        var dernierPano = 0L; var panoVue = 0L
        var dernierRecentre = 0L
        val hp = hautParleur()
        try {
            while (generation == gen) {
                val voulue = voulueHz
                if (voulue > 0) {
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
                val bw = (if (sonde) fr.f4ioz.satcombo.sonde.SondeModel.bandwidthFor(settings.sondeModel) else settings.sdrBandwidthHz).toDouble()
                if (chaine.bandwidthHz != bw) chaine.bandwidthHz = bw
                val sq = if (sonde) -200f else settings.sdrSquelchDb.toFloat()
                if (chaine.squelch.thresholdDb != sq) chaine.squelch.thresholdDb = sq
                val dec = decalageHz.toDouble()
                if (chaine.offsetHz != dec) chaine.offsetHz = dec
                val deemph = !sonde && settings.sdrDeemph
                if (chaine.deemphasis != deemph) chaine.deemphasis = deemph

                val n = r.read(iq, 200)
                if (n < 0) { if (generation == gen) _etat.update { it.copy(actif = false, statut = "arrêtée", erreur = "rtl_tcp a coupé la liaison") }; break }
                if (n == 0) continue
                // METEOR pictures: the raw samples, before any audio chain, decoded on their own thread.
                if (pourMeteor) meteor?.recepteur?.iq(iq, n, (fine + decalageHz).toDouble())
                val maintenant = horloge()
                val veutSpectre = maintenant - dernierSpectre >= 90L
                val veutPano = maintenant - panoramaDemandeMs < 3000L && maintenant - dernierPano >= 300L
                val produit = chaine.process(iq, n, pcm, feedSpectrum = veutSpectre, feedPanorama = veutPano)
                if (veutSpectre) dernierSpectre = maintenant
                if (veutPano) dernierPano = maintenant
                if (chaine.panorama.frames != panoVue) {
                    panoVue = chaine.panorama.frames
                    panoramaPllHz = pll
                    panorama = chaine.panorama.magDb.copyOf(); panoramaTrames++
                }
                if (chaine.spectrum.frames != trameVue) {
                    trameVue = chaine.spectrum.frames
                    spectre = chaine.spectrum.magDb.copyOf(); trames++
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
                    if (settings.sdrAudio && hp != null) {
                        // Never wait for the speaker: a late block is dropped, the reading goes on.
                        val octets = ByteArray(produit * 2)
                        for (i in 0 until produit) { octets[2 * i] = pcm[i].toByte(); octets[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                        if (hp.available() >= octets.size) hp.write(octets, 0, octets.size)
                    }
                    if (son.etat.value.appareil == NOM_SOURCE && son.etat.value.actif) son.distribue(pcm, produit, Dsp.AUDIO_RATE)
                    // The browsers listening to the dongle (the page open on another PC).
                    diffusion?.ajoute("sdr", pcm, produit, Dsp.AUDIO_RATE)
                }
                if (maintenant - dernierEtat >= 250L) {
                    val debit = ((r.octets - dernierOctets) * 1000 / maxOf(1L, maintenant - dernierEtat) / 1024).toInt()
                    dernierOctets = r.octets; dernierEtat = maintenant
                    _etat.update { it.copy(pllHz = pll, fineHz = fine, niveauDb = chaine.levelDb, saturation = chaine.clipRatio,
                        debitKo = debit, perdus = r.perdus) }
                }
            }
        } finally {
            hp?.let { runCatching { it.stop(); it.close() } }
        }
    }

    private fun hautParleur(): SourceDataLine? = runCatching {
        val f = AudioFormat(Dsp.AUDIO_RATE.toFloat(), 16, 1, true, false)
        (AudioSystem.getLine(javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, f)) as SourceDataLine).also {
            it.open(f, Dsp.AUDIO_RATE / 2 * 2)   // half a second
            it.start()
        }
    }.getOrNull()

    companion object {
        const val NOM_SOURCE = "Clé SDR (rtl_tcp)"
        const val CLE_HOTE = "pc_sdr_hote"
        const val CLE_PORT = "pc_sdr_port"
        const val CLE_LANCE = "pc_sdr_lance"
        const val CLE_BIAS = "pc_sdr_bias"
        const val CLE_MODE = "pc_sdr_mode"
        const val CLE_MANUELLE = "pc_sdr_manuelle"
        const val CLE_SUIT = "pc_sdr_suit"

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
