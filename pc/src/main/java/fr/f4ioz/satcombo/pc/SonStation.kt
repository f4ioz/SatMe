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
    /** FT8 / FT4 on the card, as the phone's FT8 screen. */
    val ft8 = Ft8Pc(horloge)
    /** APRS heard on the card, kept as the phone keeps it. */
    val aprs = AprsPc(File(dossierEnregistrements.parentFile, "aprs"), { station.etat.value.suivi?.name ?: "" }, horloge)
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
        }
    }

    // ------------------------------------------------------------ recording

    fun demarreEnregistrement(auto: Boolean = false): String? {
        if (wav != null) return null
        val rate = son.etat.value.rate.takeIf { son.etat.value.actif } ?: return "la carte son n'est pas démarrée"
        dossierEnregistrements.mkdirs()
        val sat = station.etat.value.suivi?.name ?: "SAT"
        val safe = sat.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').ifBlank { "SAT" }
        val f = File(dossierEnregistrements, "SatMe_${safe}_${stamp(horloge())}.wav")
        wav = EnregistreurWav(f, rate)
        _etat.update { it.copy(enregistrement = f.name, depuisMs = horloge(), secondes = 0, auto = auto) }
        return null
    }

    fun arreteEnregistrement() {
        val w = wav ?: return
        wav = null
        w.ferme()
        _etat.update { it.copy(enregistrement = "", depuisMs = 0L, auto = false) }
    }

    /**
     * Every second: with automatic recording on, starts at AOS of the
     * satellite followed (the card running) and stops 30 s after LOS — only
     * a recording it started itself.
     */
    fun tic() {
        if (!enregAuto) return
        val p = station.etat.value.position
        val leve = p != null && p.elevationDeg >= 0
        val e = _etat.value
        if (leve && wav == null && son.etat.value.actif) demarreEnregistrement(auto = true)
        if (e.auto && wav != null) {
            if (leve) basDepuis = 0L
            else {
                if (basDepuis == 0L) basDepuis = horloge()
                if (horloge() - basDepuis >= 30_000L) { arreteEnregistrement(); basDepuis = 0L }
            }
        }
    }
    private var basDepuis = 0L

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
