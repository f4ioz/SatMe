/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.SstvDecoder
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.sstv.SstvMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.imageio.ImageIO

/**
 * What the PC does with the rig's sound, as the phone: records the pass
 * (by hand, or by itself while the satellite followed is up) and decodes
 * SSTV live, the pictures kept as PNG with the phone's names and sidecars.
 */
class SonStation(
    val son: SonPc,
    private val station: StationPc,
    val dossierEnregistrements: File,
    val dossierSstv: File,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    data class Etat(
        val enregistrement: String = "",
        val depuisMs: Long = 0L,
        val secondes: Long = 0L,
        /** Started by itself for the pass (stops by itself at LOS). */
        val auto: Boolean = false,
        val sstvMode: String = "",
        val sstvLignes: Int = 0,
        val sstvHauteur: Int = 0,
        /** The last picture kept (its file name). */
        val sstvDerniere: String = "",
        /** Bumps on every new line, so the page knows to reload the picture. */
        val sstvVersion: Long = 0L,
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    // The PC's own choices, kept with its settings.
    var sstvActif: Boolean
        get() = station.stockage.getBoolean("pc_sstv", true)
        set(v) { station.stockage.edit().putBoolean("pc_sstv", v).apply() }
    var aprsActif: Boolean
        get() = station.stockage.getBoolean("pc_aprs", true)
        set(v) { station.stockage.edit().putBoolean("pc_aprs", v).apply() }
    /** CW decoded on the card (off unless chosen), for the Carnet's window. */
    val cw = CwPc(station.stockage)
    /** FT8 / FT4 on the card, as the phone's FT8 screen. */
    val ft8 = Ft8Pc(station.stockage, File(dossierEnregistrements.parentFile, "ft8-tranches"), horloge)
    /** Its stations sent to PSK Reporter, when chosen (off by default). */
    val psk = PskReporterPc(station, ft8, horloge = horloge)
    /** APRS heard on the card, kept as the phone keeps it. */
    val aprs = AprsPc(File(dossierEnregistrements.parentFile, "aprs"), { station.etat.value.suivi?.name ?: "" }, horloge) {
        Triple(station.settings.callsign, station.etat.value.observateur?.latDeg, station.etat.value.observateur?.lonDeg)
    }
    var enregAuto: Boolean
        get() = station.stockage.getBoolean("pc_enreg_auto", false)
        set(v) { station.stockage.edit().putBoolean("pc_enreg_auto", v).apply() }
    /** The sound card last used, started again at launch. */
    var carte: String
        get() = station.stockage.getString("pc_son_carte", "") ?: ""
        set(v) { station.stockage.edit().putString("pc_son_carte", v).apply() }

    @Volatile private var wav: EnregistreurWav? = null
    @Volatile private var decodeur: SstvDecoder? = null
    @Volatile private var rateDecodeur = 0
    @Volatile private var enCours: Pair<SstvMode, IntArray>? = null
    @Volatile private var debutImageMs = 0L

    init {
        son.auditeurs += { pcm, n, rate ->
            wav?.let { w -> w.ajoute(pcm, n); _etat.update { it.copy(secondes = w.secondes) } }
            if (sstvActif) sstv(pcm, n, rate)
            if (aprsActif) aprs.ecoute(pcm, n, rate)
            ft8.ecoute(pcm, n, rate)
            cw.ecoute(pcm, n, rate)
        }
    }

    // ------------------------------------------------------------ recording

    fun demarreEnregistrement(auto: Boolean = false): String? {
        if (wav != null) return null
        val rate = son.etat.value.rate.takeIf { son.etat.value.actif } ?: return "la carte son n'est pas démarrée (onglet Son)"
        dossierEnregistrements.mkdirs()
        val sat = station.etat.value.suivi?.name ?: "SAT"
        val safe = sat.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').ifBlank { "SAT" }
        val f = File(dossierEnregistrements, "SatMe_${safe}_${stamp(horloge())}.wav")
        wav = EnregistreurWav(f, rate)
        basDepuis = 0L; vuLeve = false; finPrevue = 0L
        _etat.update { it.copy(enregistrement = f.name, depuisMs = horloge(), secondes = 0, auto = auto) }
        return null
    }

    /** Stopped by hand: not started again by itself before the satellite has set. */
    fun arreteEnregistrement(parLaMain: Boolean = true) {
        val w = wav ?: return
        if (parLaMain) arreteALaMain = true
        wav = null
        w.ferme()
        _etat.update { it.copy(enregistrement = "", depuisMs = 0L, auto = false) }
    }

    /** A recording, even started by hand, stops after the pass it caught (30 s after LOS). */
    var coupeLos: Boolean
        get() = station.stockage.getBoolean("pc_enreg_coupe_los", false)
        set(v) { station.stockage.edit().putBoolean("pc_enreg_coupe_los", v).apply() }

    /**
     * Every second. Starts by itself (the card running): with automatic
     * recording on, at AOS of the satellite followed; with the automatic
     * mode, 5 s before AOS of the pass it follows. Stops 30 s after LOS a
     * recording it started — and one started by hand with [coupeLos] on,
     * once the satellite has been up during it.
     */
    fun tic() {
        val maintenant = horloge()
        runCatching { ticCw() }
        val p = station.etat.value.position
        val leve = p != null && p.elevationDeg >= 0
        // The automatic mode's pass, recorded once (not again after a stop, by hand or at LOS).
        val veille = station.passageVeilleAEnregistrer(maintenant)
            ?.takeIf { fr.f4ioz.satcombo.domain.VeilleAuto.cle(it) != veilleFaite }
        if (!leve && veille == null) arreteALaMain = false
        if (wav == null && son.etat.value.actif && !arreteALaMain && ((enregAuto && leve) || veille != null)) {
            demarreEnregistrement(auto = true)
            finPrevue = veille?.let { it.losEpochMs + fr.f4ioz.satcombo.domain.VeilleAuto.APRES_MS } ?: 0L
            veille?.let { veilleFaite = fr.f4ioz.satcombo.domain.VeilleAuto.cle(it) }
        }
        val e = _etat.value
        if (wav == null) return
        if (leve) { basDepuis = 0L; vuLeve = true; return }
        // The automatic mode's pass over, the satellite seen or not (another one followed by hand).
        if (e.auto && finPrevue > 0L && maintenant > finPrevue) { arreteEnregistrement(parLaMain = false); return }
        if (!(e.auto || coupeLos) || !vuLeve) return
        if (basDepuis == 0L) basDepuis = maintenant
        if (maintenant - basDepuis >= 30_000L) arreteEnregistrement(parLaMain = false)
    }
    private var basDepuis = 0L

    /**
     * The CW decoder and FT8 told how the satellite's Doppler moves the
     * audio: when the rig follows it (CAT, not paused) or the dongle does,
     * nothing slides.
     */
    private fun ticCw() {
        val e = station.etat.value
        val tx = station.emetteurChoisi()
        val f = tx?.downlinkLowHz
        val p = e.position
        val dop = if (f != null && p != null && p.elevationDeg > -2) -f * p.rangeRateKmS / 299_792.458 else null
        val parLaCle = son.etat.value.appareil == SdrPc.NOM_SOURCE
        val corrige = (e.catConnecte && !e.maintienDoppler) || (parLaCle && station.settings.sdrDopplerTrack)
        val signe = if ((tx?.mode ?: "").uppercase() == "LSB") -1 else 1
        // FT8 too: the slot's residual drift removed before decoding.
        ft8.indicatif = station.settings.callsign
        ft8.ticDoppler(dop, corrige, signe, p?.elevationDeg?.takeIf { it > -2 && f != null })
        if (!cw.actif) return
        cw.ticDoppler(dop, corrige, signe)
    }
    /** The satellite followed has been up during this recording. */
    private var vuLeve = false
    /** The automatic mode's recording: LOS + 30 s at the latest. */
    private var finPrevue = 0L
    private var veilleFaite = ""
    private var arreteALaMain = false

    fun enregistrements(): List<File> =
        (dossierEnregistrements.listFiles { f -> f.name.startsWith("SatMe_") && (f.name.endsWith(".wav") || f.name.endsWith(".mp3")) }
            ?: emptyArray()).sortedByDescending { it.lastModified() }

    // ----------------------------------------------------------------- SSTV

    private fun sstv(pcm: ShortArray, n: Int, rate: Int) {
        val d = decodeur?.takeIf { rateDecodeur == rate } ?: SstvDecoder(rate).also { dd ->
            rateDecodeur = rate
            dd.listener = object : SstvDecoder.Listener {
                override fun onVis(mode: SstvMode) {
                    debutImageMs = horloge()
                    _etat.update { it.copy(sstvMode = mode.name, sstvLignes = 0, sstvHauteur = mode.height) }
                }
                override fun onProgress(mode: SstvMode, pixels: IntArray, linesDone: Int) {
                    enCours = mode to pixels.copyOf()
                    _etat.update { it.copy(sstvMode = mode.name, sstvLignes = linesDone, sstvHauteur = mode.height, sstvVersion = it.sstvVersion + 1) }
                }
                override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                    garde(mode, pixels, linesDone, complete)
                }
            }
            decodeur = dd
        }
        d.feed(pcm, n)
    }

    /** A picture received: PNG and sidecar, named as on the phone (a third of it at least). */
    private fun garde(mode: SstvMode, pixels: IntArray, lignes: Int, complete: Boolean) {
        if (lignes < mode.height / 3) { _etat.update { it.copy(sstvMode = "", sstvLignes = 0) }; return }
        dossierSstv.mkdirs()
        val e = station.etat.value
        val t = horloge()
        val nom = SstvMeta.fileName(e.suivi?.name ?: "SAT", t, mode.name, complete)
        val img = BufferedImage(mode.width, lignes, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, mode.width, lignes, pixels, 0, mode.width)
        runCatching { ImageIO.write(img, "png", File(dossierSstv, nom)) }
        val meta = SstvMeta.SstvShot(fileName = nom, satName = e.suivi?.name.orEmpty(), timeMs = t, mode = mode.name,
            complete = complete, locator = e.locator, callsign = e.indicatif, source = "live",
            recording = _etat.value.enregistrement)
        runCatching { File(dossierSstv, SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(meta)) }
        enCours = null
        _etat.update { it.copy(sstvDerniere = nom, sstvMode = "", sstvLignes = 0, sstvVersion = it.sstvVersion + 1) }
    }

    /** The picture arriving, as PNG; null when none. */
    fun imageEnCours(): ByteArray? {
        val (m, px) = enCours ?: return null
        val h = _etat.value.sstvLignes.coerceIn(1, m.height)
        val img = BufferedImage(m.width, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, m.width, h, px, 0, m.width)
        val o = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", o)
        return o.toByteArray()
    }

    /** The pictures kept, newest first, with what their sidecars say. */
    fun images(): List<SstvMeta.SstvShot> =
        (dossierSstv.listFiles { f -> f.name.endsWith(".png") } ?: emptyArray())
            .map { f -> SstvMeta.decode(f.name, runCatching { File(dossierSstv, SstvMeta.sidecarName(f.name)).readText() }.getOrNull()) }
            .sortedByDescending { it.timeMs }

    fun fichierImage(nom: String): File? =
        File(dossierSstv, File(nom).name).takeIf { it.isFile && it.name.endsWith(".png") }

    fun fichierEnregistrement(nom: String): File? =
        File(dossierEnregistrements, File(nom).name).takeIf { it.isFile && it.name.startsWith("SatMe_") }

    private fun stamp(t: Long) = SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
}
