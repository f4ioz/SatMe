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
            private val portee: CoroutineScope, private val horloge: () -> Long = System::currentTimeMillis) {

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

    /** Asked from the page: the strongest signal near the listened frequency. */
    @Volatile private var vaAuPic = false
    fun calePic() { vaAuPic = true }

    // ------------------------------------------------------------ running

    @Volatile private var cle: RtlTcp? = null
    @Volatile private var processus: Process? = null
    @Volatile private var generation = 0
    private var suivi: Job? = null
    /** The dongle frequency wanted (Hz, after converters), set by the Doppler loop. */
    @Volatile private var voulueHz = 0L

    /** The last spectrum: dB per bin from −88.2 to +88.2 kHz around the PLL. */
    @Volatile var spectre: FloatArray? = null; private set
    @Volatile var trames = 0L; private set

    fun demarre(): Boolean {
        arrete()
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
        son.prendPour(NOM_SOURCE, Dsp.AUDIO_RATE)
        thread(isDaemon = true, name = "satme-sdr") { boucle(r, gen) }
        suivi = portee.launch {
            while (generation == gen) { runCatching { cibleMaintenant() }; delay(250) }
        }
        return true
    }

    fun arrete() {
        generation++
        suivi?.cancel(); suivi = null
        cle?.let { runCatching { it.close() } }
        cle = null
        arreteProcessus()
        son.rendPar(NOM_SOURCE)
        spectre = null
        voulueHz = 0L
        _etat.update { Etat() }
    }

    /** rtl_tcp started here when asked and nothing answers yet; then the connection, retried while it starts. */
    private fun connecte(): RtlTcp {
        runCatching { return RtlTcp.connecte(hote, port, 1500) }
        if (!lance || !estLocal(hote)) throw java.io.IOException("rtl_tcp ne répond pas sur $hote:$port")
        val p = runCatching {
            ProcessBuilder("rtl_tcp", "-a", "127.0.0.1", "-p", port.toString(), "-s", Dsp.RTL_RATE.toString())
                .redirectErrorStream(true).redirectOutput(File(System.getProperty("java.io.tmpdir"), "satme-rtl_tcp.log")).start()
        }.getOrElse { throw java.io.IOException("rtl_tcp introuvable : installer rtl-sdr (sudo apt install rtl-sdr)") }
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

    /** The mode to demodulate: chosen, or the transmitter's. */
    private fun mode(): RxMode = when (val m = modeChoisi) {
        "AUTO" -> modeDe(station.ecouteSdr()?.mode.orEmpty())
        else -> runCatching { RxMode.valueOf(m) }.getOrDefault(RxMode.NFM)
    }

    /** The Doppler loop: where the dongle should be now. */
    private fun cibleMaintenant() {
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
        val hp = hautParleur()
        try {
            while (generation == gen) {
                val voulue = voulueHz
                if (voulue > 0) {
                    val plan = DopplerTuner.plan(voulue, pll, fine)
                    if (plan.retune) { r.setCenterFreq(plan.pllHz); pll = plan.pllHz }
                    if (plan.fineHz != fine) { fine = plan.fineHz; chaine.dopplerFineHz = fine.toDouble() }
                }
                val m = mode()
                if (chaine.mode != m) { chaine.mode = m; chaine.reset() }
                val bw = settings.sdrBandwidthHz.toDouble()
                if (chaine.bandwidthHz != bw) chaine.bandwidthHz = bw
                val sq = settings.sdrSquelchDb.toFloat()
                if (chaine.squelch.thresholdDb != sq) chaine.squelch.thresholdDb = sq
                val dec = decalageHz.toDouble()
                if (chaine.offsetHz != dec) chaine.offsetHz = dec
                if (chaine.deemphasis != settings.sdrDeemph) chaine.deemphasis = settings.sdrDeemph

                val n = r.read(iq, 200)
                if (n < 0) { if (generation == gen) _etat.update { it.copy(actif = false, statut = "arrêtée", erreur = "rtl_tcp a coupé la liaison") }; break }
                if (n == 0) continue
                val maintenant = horloge()
                val veutSpectre = maintenant - dernierSpectre >= 90L
                val produit = chaine.process(iq, n, pcm, feedSpectrum = veutSpectre)
                if (veutSpectre) dernierSpectre = maintenant
                if (chaine.spectrum.frames != trameVue) {
                    trameVue = chaine.spectrum.frames
                    spectre = chaine.spectrum.magDb.copyOf(); trames++
                    if (vaAuPic) {
                        vaAuPic = false
                        // Around the listened frequency: ±15 kHz, as the phone's button.
                        val ici = (fine + decalageHz).toDouble()
                        val p = chaine.peakOffsetHz(ici - 15_000.0, ici + 15_000.0)
                        if (p != 0.0) decalageHz = (Math.round(p) - fine).toInt()
                    }
                }
                if (produit > 0) {
                    if (settings.sdrAudio && hp != null) {
                        // Never wait for the speaker: a late block is dropped, the reading goes on.
                        val octets = ByteArray(produit * 2)
                        for (i in 0 until produit) { octets[2 * i] = pcm[i].toByte(); octets[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                        if (hp.available() >= octets.size) hp.write(octets, 0, octets.size)
                    }
                    if (son.etat.value.appareil == NOM_SOURCE && son.etat.value.actif) son.distribue(pcm, produit, Dsp.AUDIO_RATE)
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
