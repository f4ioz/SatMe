/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import android.content.Context
import fr.f4ioz.satcombo.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * GENESIS FSK in USB on the phone — HADES-SA (SO-127) and AMSAT-EA's other
 * satellites, 200 or 800 bit/s — from the sound the app already receives
 * (the pass recorder's capture, the SDR dongle's audio), receive only. The
 * engine's [RecepteurGenesis] does the work, as on SatMe-PC:
 *
 * - telemetry frames kept in [ArchiveGenesis] (read by [TelemesureGenesis]);
 * - SSDV packets put together by [AssemblageSsdv], over several passes,
 *   kept on disk (`telemesure/ssdv/paquets`), the finished picture as a JPEG;
 * - Codec2 voice kept as `.c2` (played on SatMe-PC: no Codec2 here).
 *
 * When it listens: [SettingsStore.genesisMode] — by default only while
 * recording a satellite whose SatNOGS transmitters say GENESIS (or SSDV,
 * or narrow FSK) at 200 or 800 bit/s ([profilSatellite], set by the view
 * model); « always » or « never » otherwise. The rate: the one chosen, else
 * the transmitter's ([RecepteurGenesis.Profil.AUTO]: both at once).
 */
object GenesisHub {

    data class Etat(
        /** Hooked to a recording, and decoding. */
        val ecoute: Boolean = false,
        /** The satellite of that recording. */
        val sat: String = "",
        /** Why it listens or not: "satellite", "toujours", "attente", "non", "coupe". */
        val decision: String = "",
        val profil: String = RecepteurGenesis.Profil.AUTO.cle,
        val rx: RecepteurGenesis.Etat = RecepteurGenesis.Etat(),
        /** Bumped when a frame or a packet is kept: the page reads the disk again. */
        val rev: Int = 0,
        /** Reading a recording again: its name, 0..1 (-1 when idle), frames and packets found. */
        val fichier: String? = null,
        val progression: Float = -1f,
        val trouves: Int = 0,
        /** The test signal: running, and what it gave (frames, SSDV packets). */
        val essai: Boolean = false,
        val essaiTrames: Int = -1,
        val essaiSsdv: Int = 0,
        val erreur: String? = null,
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    /**
     * The satellite named so (as the recorder has it) → its catalogue number
     * and the GENESIS profile its SatNOGS transmitters give, or null when none
     * is GENESIS. Set by the view model; may block (a SatNOGS download): it
     * is called off the capture thread.
     */
    @Volatile var profilSatellite: (String) -> Pair<Int, RecepteurGenesis.Profil?>? = { null }

    @Volatile private var dossier: File? = null
    @Volatile private var recepteur: RecepteurGenesis? = null
    @Volatile private var essaiRecepteur: RecepteurGenesis? = null

    /** The base folder: `telemesure/…` goes under it (the same layout as SatMe-PC's). */
    fun dossier(ctx: Context): File = dossier ?: (ctx.getExternalFilesDir(null) ?: ctx.filesDir).also { dossier = it }

    /** The receiver, its pictures read back from disk on first use. */
    @Synchronized
    fun recepteur(ctx: Context): RecepteurGenesis = recepteur ?: run {
        val d = dossier(ctx.applicationContext)
        RecepteurGenesis(d, AssemblageSsdv(d)).also { r ->
            r.surSsdv = { bump() }
            r.contexte = { live?.let { RecepteurGenesis.Contexte(it.sat, it.norad) } ?: RecepteurGenesis.Contexte() }
            recepteur = r
        }
    }

    fun assemblage(ctx: Context): AssemblageSsdv = recepteur(ctx).assemblage

    fun archive(ctx: Context): ArchiveGenesis? = recepteur(ctx).archive

    /** The test's pictures (in memory only). */
    fun essais(): List<AssemblageSsdv.Photo> = essaiRecepteur?.assemblage?.photos().orEmpty()

    private fun bump() { _etat.value = _etat.value.copy(rev = _etat.value.rev + 1) }

    /** Forgets the receivers and their folder (the tests: a new app each time). */
    internal fun oublie() {
        live = null; session++
        recepteur = null; essaiRecepteur = null; dossier = null
        _etat.value = Etat()
    }

    /** Something on disk changed (a picture dropped): the page reads it again. */
    fun rafraichit() = bump()

    // ---------------------------------------------------------------- live

    private class Live(val rate: Int, val sat: String, val norad: Int)

    @Volatile private var live: Live? = null
    @Volatile private var session = 0
    private var dernierEtatMs = 0L
    private var dernieresTrames = 0

    /**
     * A recording starts at [rate] Hz for [sat]: decides, off the capture
     * thread, whether GENESIS listens to it. Nothing is decoded before.
     */
    fun startLive(ctx: Context, rate: Int, sat: String) {
        val app = ctx.applicationContext
        val reg = runCatching { SettingsStore(app) }.getOrNull() ?: return
        val mode = reg.genesisMode
        live = null
        val s = ++session
        if (mode == "COUPE") { _etat.value = _etat.value.copy(ecoute = false, sat = sat, decision = "coupe"); return }
        _etat.value = _etat.value.copy(ecoute = false, sat = sat, decision = "attente", erreur = null)
        Thread({
            val r = runCatching { profilSatellite(sat) }.getOrNull()
            if (s != session) return@Thread
            val genesis = r?.second
            if (mode != "TOUJOURS" && genesis == null) {
                _etat.value = _etat.value.copy(decision = "non"); return@Thread
            }
            val p = RecepteurGenesis.Profil.de(reg.genesisProfil) ?: genesis ?: RecepteurGenesis.Profil.AUTO
            val rec = recepteur(app)
            rec.profil = p
            rec.source = AssemblageSsdv.SOURCE_RADIO
            live = Live(rate, sat, r?.first ?: 0)
            _etat.value = _etat.value.copy(ecoute = true, decision = if (genesis != null) "satellite" else "toujours", profil = p.cle)
        }, "genesis-decision").apply { isDaemon = true }.start()
    }

    /** From the capture thread. A decoder failure must not stop the recording, nor go unseen. */
    fun feedLive(pcm: ShortArray, n: Int) {
        val l = live ?: return
        val r = recepteur ?: return
        runCatching { r.traite(pcm, n, l.rate) }.onFailure { e ->
            _etat.value = _etat.value.copy(erreur = e.javaClass.simpleName)
        }
        val now = System.currentTimeMillis()
        if (now - dernierEtatMs >= 1_000L) {
            dernierEtatMs = now
            runCatching { r.finVoix() }
            val e = r.etat
            val nouvelles = e.trames != dernieresTrames
            dernieresTrames = e.trames
            _etat.value = _etat.value.copy(rx = e, rev = _etat.value.rev + if (nouvelles) 1 else 0)
        }
    }

    fun stopLive() {
        session++
        val r = recepteur
        live = null
        if (r != null) runCatching { r.finVoix() }
        _etat.value = _etat.value.copy(ecoute = false, rx = r?.etat ?: _etat.value.rx, rev = _etat.value.rev + 1)
    }

    // ----------------------------------------------------------- from file

    @Volatile private var annule = false
    fun annuleFichier() { annule = true }

    /**
     * GENESIS in a recording (SatMe MP3, or an imported WAV/MP3). Blocking:
     * call off the main thread. Timed from the recording's start when its
     * name says it (« SatMe_HADES-SA_20261102_101500Z »), else from the
     * file's date. Returns frames + SSDV packets found.
     */
    fun decodeFichier(ctx: Context, f: File): Int {
        val app = ctx.applicationContext
        annule = false
        val sat = f.name.removePrefix("SatMe_").substringBefore("_").ifBlank { "" }
        val debut = runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(Regex("\\d{8}_\\d{6}Z").find(f.name)!!.value)!!.time
        }.getOrDefault(f.lastModified())
        val reg = runCatching { SettingsStore(app) }.getOrNull()
        val r0 = runCatching { profilSatellite(sat) }.getOrNull()
        val principal = recepteur(app)
        var lus = 0L
        var frequence = 0
        val r = RecepteurGenesis(dossier(app), principal.assemblage) { debut + if (frequence > 0) lus * 1000 / frequence else 0L }
        r.archive = principal.archive
        r.source = AssemblageSsdv.SOURCE_ENREGISTREMENT + f.name
        r.profil = RecepteurGenesis.Profil.de(reg?.genesisProfil) ?: r0?.second ?: RecepteurGenesis.Profil.AUTO
        r.contexte = { RecepteurGenesis.Contexte(sat, r0?.first ?: 0) }
        _etat.value = _etat.value.copy(fichier = f.name, progression = 0f, trouves = 0)
        val bloc: (ShortArray, Int, Int, Float) -> Boolean = { pcm, n, rate, fraction ->
            frequence = rate
            r.traite(pcm, n, rate)
            lus += n
            _etat.value = _etat.value.copy(progression = fraction, trouves = r.etat.trames + r.etat.ssdv)
            !annule
        }
        runCatching {
            // A WAV read here (any rate, 8/16/32 bits); an MP3 through Android's decoder.
            val wav = if (f.name.endsWith(".wav", ignoreCase = true) && f.length() < 400L * 1024 * 1024)
                LectureWav.lis(f.readBytes()) else null
            if (wav != null) {
                val (son, rate) = wav
                var k = 0
                while (k < son.size) {
                    val m = minOf(4096, son.size - k)
                    if (!bloc(son.copyOfRange(k, k + m), m, rate, (k + m).toFloat() / son.size)) break
                    k += m
                }
            } else fr.f4ioz.satcombo.sstv.Mp3Pcm.decode(f, bloc)
        }
        runCatching { r.finVoix() }
        val trouves = r.etat.trames + r.etat.ssdv
        _etat.value = _etat.value.copy(progression = -1f, trouves = trouves, rev = _etat.value.rev + 1)
        return trouves
    }

    // ---------------------------------------------------------------- test

    /**
     * A test signal through the decoder, as SatMe-PC's « Essai »: a
     * telemetry frame and a test card's first SSDV packets, in FSK at the
     * rate chosen (800 bit/s for « both »), with noise. Nothing kept on disk:
     * the test's pictures stay in memory, marked as such.
     */
    fun lanceEssai(ctx: Context) {
        if (_etat.value.essai) return
        val reg = runCatching { SettingsStore(ctx.applicationContext) }.getOrNull()
        _etat.value = _etat.value.copy(essai = true, essaiTrames = -1, essaiSsdv = 0)
        Thread({
            try {
                val choisi = RecepteurGenesis.Profil.de(reg?.genesisProfil) ?: RecepteurGenesis.Profil.AUTO
                val p = if (choisi == RecepteurGenesis.Profil.AUTO) RecepteurGenesis.Profil.G800 else choisi
                val r = RecepteurGenesis(null, AssemblageSsdv(null))
                r.contexte = { RecepteurGenesis.Contexte("Essai", essai = true) }
                r.profil = choisi
                val rate = 44_100
                val audio = signalEssai(p, rate, (System.currentTimeMillis() / 1000 % 200).toInt())
                var k = 0
                while (k < audio.size) { val m = minOf(4096, audio.size - k); r.traite(audio.copyOfRange(k, k + m), m, rate); k += m }
                essaiRecepteur = r
                _etat.value = _etat.value.copy(essaiTrames = r.etat.trames, essaiSsdv = r.etat.ssdv, rev = _etat.value.rev + 1)
            } catch (e: Exception) {
                _etat.value = _etat.value.copy(erreur = e.javaClass.simpleName)
            } finally {
                _etat.value = _etat.value.copy(essai = false)
            }
        }, "genesis-essai").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * The test's sound: a temperature frame then [n]'s test card packets (the first eight at 200 bit/s: a
     * whole picture would be two minutes of sound), FSK centred on 1650 Hz, Eb/N0 12 dB.
     */
    fun signalEssai(p: RecepteurGenesis.Profil, rate: Int, n: Int, graine: Long = 1L, garde: (Int) -> Boolean = { true }): ShortArray {
        val paquets = SignalPaquets.paquetsMire(n).let { if (p == RecepteurGenesis.Profil.G200) it.take(8) else it }
            .filterIndexed { i, _ -> garde(i) }
            .map { Ssdv.recachete(it, Genesis.INDICATIF_SSDV) }
        val o = paquets.flatMap { (Genesis.codeSsdv(it) + ByteArray(4) { 0xAA.toByte() }).toList() }.toByteArray()
        val temperatures = AssemblageSsdv.deHex(TRAME_ESSAI)!!
        val bits = Genesis.bits(Genesis.code(2, 3, temperatures)) + Genesis.bits(o, preambule = 0)
        return SignalFskAudio.audio(bits, rate, p.debit, p.ecartHz, 1_650.0, 12.0, 3.0, graine = graine)
    }

    /** A temperature frame's data (type 2), as HADES-SA sent one. */
    const val TRAME_ESSAI = "668B1F01FF747978FF6F7879FF7D"

    // ---------------------------------------------------------------- for the pages

    /** The telemetry frames kept since [depuisMs], newest first, SSDV left out. */
    fun trames(ctx: Context, depuisMs: Long, a: Long = System.currentTimeMillis(), satellite: String = "", norad: Int = 0): List<ArchiveGenesis.Entree> =
        runCatching { archive(ctx)?.entre(depuisMs, a, satellite, norad).orEmpty() }.getOrDefault(emptyList())

    /** The voice messages kept (newest first): name, satellite, start. */
    fun voix(ctx: Context, max: Int = 20): List<org.json.JSONObject> =
        runCatching { AssemblageVoix.liste(dossier(ctx), max) }.getOrDefault(emptyList())
}
