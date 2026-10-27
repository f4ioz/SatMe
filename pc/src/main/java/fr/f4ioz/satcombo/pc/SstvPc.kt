/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sstv.CibleAfc
import fr.f4ioz.satcombo.sstv.SstvAccord
import fr.f4ioz.satcombo.sstv.ProchaineImageSstv
import fr.f4ioz.satcombo.sstv.QualiteSstv
import fr.f4ioz.satcombo.sstv.ReunionSstv
import fr.f4ioz.satcombo.sstv.SstvAfc
import fr.f4ioz.satcombo.sstv.SstvDecoder
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.sstv.SstvMode
import fr.f4ioz.satcombo.sstv.SstvNettoyage
import fr.f4ioz.satcombo.sstv.SstvRythme
import fr.f4ioz.satcombo.sstv.SstvSon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.imageio.ImageIO
import kotlin.concurrent.thread

/**
 * SSTV on the PC, as the phone: decoded live from the sound card or the SDR
 * dongle (« auto »: the dongle while it plays, else the card), a forced mode
 * and a start by hand, continuous decoding, cleaning, each picture kept with
 * its own sound; a recording decoded again; a picture's GIF.
 *
 * One decoder at a time, for one source at one rate: a block from another
 * source (or rate) starts a new one, and feeding is serialised, so the card's
 * thread and the dongle's never mix in the same decoder.
 */
class SstvPc(
    private val stockage: Stockage,
    val dossier: File,
    /** Who receives: the satellite followed, the locator, the callsign, the recording under way. */
    private val contexte: () -> Contexte,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    class Contexte(val satellite: String = "", val locator: String = "", val indicatif: String = "", val enregistrement: String = "",
                   /** Written on each live picture (« Campagne : … » while the SSTV auto-pilot runs). */
                   val note: String = "")

    /**
     * The sound is not on the air: [ESSAI] (the test generator) or [RELECTURE]
     * (an I/Q recording played back, [momentMs] when it was recorded, [fichier]).
     */
    class HorsAntenne(val genre: String, val momentMs: Long? = null, val fichier: String = "")

    /** What the sound of a source ([SDR], [CARTE]) is now, when not on the air; null: a real reception. */
    @Volatile var horsAntenne: (String) -> HorsAntenne? = { null }

    /**
     * The satellite a source ([SDR], [CARTE]) hears now (see [satelliteDuSon]);
     * null: the one followed. A picture is named after the sound that made it:
     * QO-100's, decoded from the dongle on QO-100, came out named after the
     * LEO followed meanwhile (22/10).
     */
    @Volatile var satelliteDe: (String) -> String? = { null }

    data class Etat(
        /** Mode of the picture under way ("" between pictures). */
        val mode: String = "",
        val lignes: Int = 0,
        val hauteur: Int = 0,
        /** The last picture kept (its file name). */
        val derniere: String = "",
        /** Bumps on every new line, so the page knows to reload the picture. */
        val version: Long = 0L,
        /** Pictures kept since SatMe-PC started. */
        val gardees: Int = 0,
        /** Pictures decoded from the test generator: shown while they come, never kept. */
        val essais: Int = 0,
        /** Last failure, shown. */
        val erreur: String = "",
        /** A recording decoded again: its name, progress (0..1, -1 idle), pictures found. */
        val redecode: String = "",
        val redecodeProgres: Float = -1f,
        val redecodeImages: Int = 0,
        /** A GIF being made (the picture's name), "" when none. */
        val gif: String = "",
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    // ------------------------------------------------------------------ settings

    var actif: Boolean
        get() = stockage.getBoolean("pc_sstv", true)
        set(v) { stockage.edit().putBoolean("pc_sstv", v).apply(); if (!v) oublie() }
    /** "auto" (the dongle while it plays, else the card), "carte", "sdr". */
    var source: String
        get() = (stockage.getString("pc_sstv_source", AUTO) ?: AUTO).takeIf { it in SOURCES } ?: AUTO
        set(v) { if (v in SOURCES && v != source) { stockage.edit().putString("pc_sstv_source", v).apply(); oublie() } }
    /** Mode forced (null: the header says it). */
    var force: SstvMode?
        get() = (stockage.getString("pc_sstv_force", "") ?: "").let { SstvMode.byName(it) }
        set(v) { stockage.edit().putString("pc_sstv_force", v?.name ?: "").apply(); decodeur?.forcedMode = v }
    /** A train of sync pulses starts a picture without its header (as the phone, on by default). */
    var continu: Boolean
        get() = stockage.getBoolean("pc_sstv_continu", true)
        set(v) { stockage.edit().putBoolean("pc_sstv_continu", v).apply(); decodeur?.continuous = v }
    /** Lines and dashes lost to noise mended before saving. */
    var nettoyage: Boolean
        get() = stockage.getBoolean("pc_sstv_nettoyage", true)
        set(v) { stockage.edit().putBoolean("pc_sstv_nettoyage", v).apply() }
    /**
     * Automatic tuning (off by default): the tones' offset measured, the
     * receiver nudged to bring them home (see [SstvAfc]). Off again: the
     * receiver goes back where the operator left it.
     */
    var afc: Boolean
        get() = stockage.getBoolean("pc_sstv_afc", false)
        set(v) { stockage.edit().putBoolean("pc_sstv_afc", v).apply(); if (!v) coupeAfc() }

    /**
     * What the tuning may move for a source ("carte", "sdr"): the dongle's
     * fine offset, the rig through SatMe's CAT writes, or nothing (advice
     * only). Set by whoever knows the station.
     */
    @Volatile var cibleAfc: (source: String) -> CibleAfc? = { null }

    // ------------------------------------------------------------------ the sound in

    @Volatile private var sdrVuMs = 0L
    @Volatile private var decodeur: SstvDecoder? = null
    @Volatile private var memoire: SstvSon.Memoire? = null
    @Volatile private var accord: SstvAccord? = null
    @Volatile private var calage: SstvAfc? = null
    /** The live reception quality ([QualiteSstv]): measured beside the decoder, never in its way. */
    @Volatile private var qualite: QualiteSstv? = null
    /** The source feeding the decoder now ("carte", "sdr", "" before any sound). */
    @Volatile var sourceActive = ""; private set
    @Volatile var rateActif = 0; private set
    /** When the last block reached the decoder (0: never). */
    @Volatile var derniereMs = 0L; private set
    @Volatile private var enCours: Pair<SstvMode, IntArray>? = null
    @Volatile private var pannes = 0

    /**
     * In « auto », the card even while the dongle plays: the rig's own card
     * feeds it and the rig receives the satellite followed. Set by whoever
     * knows the station.
     */
    @Volatile var posteEntend: () -> Boolean = { false }

    /**
     * A picture just kept with its own sound (its folder, its sidecar): for the
     * re-decoding at its end ([fr.f4ioz.satcombo.sstv.RedecodageAuto]), which
     * only queues it — the decoder goes on at once. Set by whoever has the
     * gallery's corrections.
     */
    @Volatile var imageGardee: (File, SstvMeta.SstvShot) -> Unit = { _, _ -> }

    /** Told of each picture received on the air and kept (not a test, not a recording played back): « Réglages › Site web ». */
    @Volatile var nouvelleImage: (String, SstvMeta.SstvShot) -> Unit = { _, _ -> }

    /** The sound card's blocks (the dongle's too when it is the card's source). */
    fun ecouteCarte(pcm: ShortArray, n: Int, rate: Int) {
        if (!actif) return
        if (choisie(source, horloge() - sdrVuMs < SDR_VU_MS, source == AUTO && runCatching { posteEntend() }.getOrDefault(false)) != CARTE) return
        verse(CARTE, pcm, n, rate)
    }

    /** The dongle's demodulated sound. */
    fun ecouteSdr(pcm: ShortArray, n: Int, rate: Int) {
        sdrVuMs = horloge()
        if (!actif || source == CARTE) return
        if (source == AUTO && runCatching { posteEntend() }.getOrDefault(false)) return
        verse(SDR, pcm, n, rate)
    }

    @Synchronized private fun verse(src: String, pcm: ShortArray, n: Int, rate: Int) {
        val d = decodeur?.takeIf { sourceActive == src && rateActif == rate } ?: nouveau(src, rate)
        derniereMs = horloge()
        // Kept before decoding: a picture ending in this block takes it all.
        runCatching { memoire?.ajoute(pcm, n) }
        runCatching { accord?.ajoute(pcm, n) }
        runCatching { qualite?.son(pcm, n) }
        val a = if (afc) calage ?: SstvAfc(rate).also { calage = it } else null
        runCatching { if (a != null) a.nourrit(d, pcm, n, runCatching { cibleAfc(src) }.getOrNull()) else d.feed(pcm, n) }.onFailure { e ->
            val k = ++pannes
            _etat.update { it.copy(erreur = e.javaClass.simpleName + (if (k > 1) " ×$k" else "")) }
        }
    }

    /** Another source or rate: a fresh decoder (the picture under way is dropped). */
    private fun nouveau(src: String, rate: Int): SstvDecoder {
        // The tuning belonged to the old source: its receiver back where the operator left it.
        calage?.let { c -> runCatching { c.remiseAZero(cibleAfc(sourceActive)) } }
        calage = null
        sourceActive = src; rateActif = rate
        enCours = null; pannes = 0; debutEnCoursMs = 0L
        memoire = SstvSon.Memoire(rate)
        accord = SstvAccord(rate)
        val q = QualiteSstv(rate, horloge).also { qualite = it }
        _etat.update { it.copy(mode = "", lignes = 0, hauteur = 0, erreur = "") }
        return SstvDecoder(rate, ecouteur).also { it.forcedMode = force; it.continuous = continu; it.mesureLigne = { _, l, t, n, b -> q.ligne(l, t, n, b) }; decodeur = it }
    }

    /** Settings changed so that the decoder must start over (source, off). */
    @Synchronized private fun oublie() {
        calage?.let { c -> runCatching { c.remiseAZero(cibleAfc(sourceActive)) } }
        calage = null
        decodeur = null; memoire = null; accord = null; enCours = null; qualite = null
        sourceActive = ""; rateActif = 0; derniereMs = 0L
        _etat.update { it.copy(mode = "", lignes = 0, hauteur = 0) }
    }

    private val ecouteur = object : SstvDecoder.Listener {
        override fun onVis(mode: SstvMode) {
            runCatching { qualite?.debut(mode) }
            _etat.update { it.copy(mode = mode.name, lignes = 0, hauteur = mode.height, erreur = "") }
        }
        override fun onProgress(mode: SstvMode, pixels: IntArray, linesDone: Int) {
            // When this picture began (its row 0), once: a picture continued after a lost sync keeps its own.
            val d = decodeur
            if (debutEnCoursMs == 0L && d != null && rateActif > 0) {
                debutEnCoursMs = horloge() - (d.echantillons - d.debutImage).coerceAtLeast(0L) * 1000L / rateActif
                enCoursEntete = d.avecEntete
            }
            enCours = mode to pixels.copyOf()
            _etat.update { it.copy(mode = mode.name, lignes = linesDone, hauteur = mode.height, version = it.version + 1) }
        }
        override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
            val c = runCatching { contexte() }.getOrDefault(Contexte())
            val h = runCatching { horsAntenne(sourceActive) }.getOrNull()
            runCatching { qualite?.fin() }
            enCours = null
            // Its start, for the countdown to the next picture (one begun on its header only: one joined late says nothing of the rhythm).
            if (debutEnCoursMs > 0L && enCoursEntete && h?.genre != ESSAI) synchronized(debuts) {
                debuts += Triple(debutEnCoursMs, runCatching { satelliteDe(sourceActive) }.getOrNull() ?: c.satellite, mode.name)
                while (debuts.size > 40) debuts.removeAt(0)
            }
            debutEnCoursMs = 0L
            // The test generator's pictures: seen while they come, never in the gallery (nor anything fed from it).
            if (h?.genre == ESSAI) { _etat.update { it.copy(mode = "", lignes = 0, essais = it.essais + 1, version = it.version + 1) }; return }
            // The satellite of the sound that made it; the campaign's note only on the campaign's satellite.
            val sat = runCatching { satelliteDe(sourceActive) }.getOrNull() ?: c.satellite
            // A picture held while its sync was lost ends where it was given up, not now.
            val d = decodeur
            val avantMs = if (d != null && rateActif > 0) (d.echantillons - d.finTrame).coerceAtLeast(0L) * 1000L / rateActif else 0L
            val finMs = (h?.momentMs ?: horloge()) - avantMs
            val ligne0 = if (d != null && rateActif > 0) finMs - (d.finTrame - d.debutImage).coerceAtLeast(0L) * 1000L / rateActif else 0L
            // A recording played back: kept, marked, at the time it was received; no « new picture » for it.
            val nom = if (linesDone >= minLignes(mode)) garde(mode, pixels, linesDone, complete, sat, finMs,
                if (h != null) RELECTURE else "live", if (h != null) h.fichier else if (sat == c.satellite) c.enregistrement else "", c.locator, c.indicatif,
                sonDe(decodeur, memoire), note = if (h == null && sat == c.satellite) c.note else "", entree = sourceActive,
                trous = d?.trous.orEmpty(), ligne0Ms = ligne0) else null
            if (nom != null) runCatching {
                val s = SstvMeta.decode(nom, File(dossier, SstvMeta.sidecarName(nom)).readText(Charsets.UTF_8))
                if (SstvSon.fichier(File(dossier, nom)).isFile) imageGardee(dossier, s)
                if (h == null) nouvelleImage(nom, s)
            }
            _etat.update { it.copy(mode = "", lignes = 0, derniere = if (h == null) nom ?: it.derniere else it.derniere,
                gardees = it.gardees + (if (nom != null) 1 else 0), version = it.version + 1) }
        }
    }

    // ------------------------------------------------------------------ the next picture

    /** When the picture arriving began (UTC ms, 0: none), and whether on its header. */
    @Volatile private var debutEnCoursMs = 0L
    @Volatile private var enCoursEntete = false
    /** The starts of the pictures received on their header: when, the satellite, the mode. */
    private val debuts = ArrayList<Triple<Long, String, String>>()

    /**
     * The countdown to the next picture of [sat] ([ProchaineImageSstv]):
     * the starts received since [aosMs] (the pass's AOS; else the last half
     * hour), the picture arriving, [horaire] the announced timetable
     * (« 36 s on / 2 min off », "" none), [losMs] the pass's LOS.
     */
    fun prochaine(sat: String, aosMs: Long?, losMs: Long?, horaire: String = "", modeAnnonce: String = ""): ProchaineImageSstv.Etat {
        val t = horloge()
        val depuis = (aosMs ?: (t - 30 * 60_000L)) - 60_000L
        fun n(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        val l = synchronized(debuts) { debuts.filter { n(it.second) == n(sat) && it.first >= depuis } }
        val d = decodeur
        val m = d?.currentMode
        val ec = if (m != null && debutEnCoursMs > 0L) ProchaineImageSstv.EnCours(debutEnCoursMs, _etat.value.lignes, m) else null
        val mode = m ?: l.lastOrNull()?.third?.let { SstvMode.byName(it) } ?: SstvMode.byName(modeAnnonce)
        return ProchaineImageSstv.etat(t, l.map { it.first }, ec, fr.f4ioz.satcombo.sstv.HoraireDiapos.periodeOuNull(horaire), losMs, mode)
    }

    // ------------------------------------------------------------------ by hand

    /** Starts a picture now, without its header: in [mode], else the mode forced. Null when started, else why not. */
    @Synchronized fun cale(mode: SstvMode? = null): String? {
        val m = mode ?: force ?: return "choisir un mode d'abord"
        val d = decodeur?.takeIf { horloge() - derniereMs < SON_VU_MS } ?: return "aucun son n'arrive au décodeur SSTV"
        runCatching { d.forceStart(m) }.onFailure { return it.javaClass.simpleName }
        runCatching { qualite?.debut(m) }
        return null
    }

    /** The tuning off: the receiver back, the decoder left alone. */
    @Synchronized private fun coupeAfc() {
        calage?.let { c -> runCatching { c.remiseAZero(cibleAfc(sourceActive)) } }
        calage = null
        decodeur?.mesureSync = null
    }

    /** The tuning's « reset »: the receiver back where the operator left it, the measurements forgotten. */
    @Synchronized fun remiseAZeroAfc() {
        calage?.let { c -> runCatching { c.remiseAZero(cibleAfc(sourceActive)) } }
    }

    /** The tuning for the page: on, measured, applied, and what to do by hand. */
    class Calage(val actif: Boolean, val mesureHz: Int?, val totalHz: Int, val applique: Boolean, val sens: Int,
                 val visHz: Int?, val corrections: Int, val voie: String, val conseil: String)

    fun calage(voie: String): Calage {
        val e = calage?.etat ?: SstvAfc.Etat()
        val frais = derniereMs > 0 && horloge() - derniereMs < SON_VU_MS
        val m = if (frais) e.mesureHz else null
        return Calage(afc, m, e.totalHz, e.applique, e.sens, e.visHz, e.corrections, voie, conseilAfc(afc, m, e.applique, e.sens))
    }

    /** Ends the picture under way (kept when it has enough lines) and listens again. */
    @Synchronized fun abandonne() {
        val d = decodeur ?: return
        runCatching { d.abort() }
        enCours = null
        _etat.update { it.copy(mode = "", lignes = 0) }
    }

    /** The live reception quality while the sound arrives; null when none comes. */
    fun qualite(): QualiteSstv.Etat? = qualite?.etat?.takeIf { derniereMs > 0 && horloge() - derniereMs < SON_VU_MS }

    /** The decoder's state for the page. */
    class Ecoute(val decodage: Boolean, val mode: String, val progres: Float, val niveau: Float, val ecartHz: Int?,
                 val ecartSyncHz: Int?, val spectre: FloatArray?, val depuisMs: Long)

    fun ecoute(): Ecoute {
        val d = decodeur
        val a = accord
        val vu = derniereMs
        val frais = vu > 0 && horloge() - vu < SON_VU_MS
        return Ecoute(d?.decoding == true, d?.armedMode?.name.orEmpty(), d?.progress ?: 0f,
            if (frais) a?.niveau ?: 0f else 0f,
            d?.correctionHz?.takeIf { it != 0f }?.let { Math.round(it) },
            if (frais) a?.ecartSyncHz else null, if (frais) a?.spectre else null,
            if (vu == 0L) -1L else horloge() - vu)
    }

    /**
     * What to tell the operator when the decoder hears nothing useful: the
     * source has no sound, a muted or saturated one. Null when all is well.
     * [carteActive]: the sound card runs; [sdrActive]: the dongle receives.
     */
    fun conseil(carteActive: Boolean, sdrActive: Boolean): String? {
        if (!actif) return "Décodage SSTV coupé : le cocher ci-dessous (ou onglet Son)."
        val e = ecoute()
        if (e.depuisMs < 0 || e.depuisMs > SON_VU_MS) return when (source) {
            SDR -> if (sdrActive) "La clé SDR reçoit mais ne donne pas de son (METEOR ou radiosondes ?) : la démarrer depuis l'onglet SDR."
                else "Source « clé SDR » : la clé n'est pas démarrée (onglet SDR)."
            CARTE -> if (carteActive) "La carte son ne donne rien : vérifier la carte choisie (onglet Son)."
                else "Source « carte son » : la carte n'est pas démarrée — la choisir dans « Source » (Réception)."
            else -> "Aucun son n'arrive : choisir une carte son dans « Source » (Réception), ou démarrer la clé SDR (onglet SDR)."
        }
        if (e.niveau < 0.003f) return "Le son de la " + nomSource(sourceActive) + " est presque muet : monter le volume ou le gain."
        if (e.niveau > 0.97f) return "Le son de la " + nomSource(sourceActive) + " sature : baisser le niveau."
        return null
    }

    // ------------------------------------------------------------------ pictures

    private fun sonDe(d: SstvDecoder?, m: SstvSon.Memoire?): Pair<ShortArray, Int>? {
        if (d == null || m == null) return null
        val rateEntree = m.rate * m.facteur
        return runCatching { m.extrait(d.debutTrame, d.finTrame + rateEntree / 2) to m.rate }.getOrNull()
    }

    private val verrouFichiers = Any()

    /** A picture received: PNG, sidecar and its own sound, named as on the phone; a second decoding never overwrites the first. */
    private fun garde(mode: SstvMode, pixels: IntArray, lignes: Int, complete: Boolean, sat: String, quandMs: Long,
                      origine: String, enregistrement: String, locator: String, indicatif: String,
                      son: Pair<ShortArray, Int>?, recale: Boolean = false, note: String = "", entree: String = "",
                      trous: List<IntRange> = emptyList(), ligne0Ms: Long = 0L, reunion: List<String> = emptyList(), nettoie: Boolean = nettoyage): String? = synchronized(verrouFichiers) {
        dossier.mkdirs()
        val px = if (nettoie) runCatching { SstvNettoyage.nettoie(pixels, mode.width, mode.height, lignes) }.getOrDefault(pixels) else pixels
        var nom = SstvMeta.fileName(sat.ifBlank { "SAT" }, quandMs, mode.name, complete)
        var v = 0
        while (File(dossier, nom).exists() && v < 99) nom = SstvMeta.fileName(sat.ifBlank { "SAT" }, quandMs, mode.name, complete, variante = ++v + 1)
        val h = lignes.coerceIn(1, mode.height)
        val img = BufferedImage(mode.width, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, mode.width, h, px, 0, mode.width)
        val png = File(dossier, nom)
        if (runCatching { ImageIO.write(img, "png", png) }.isFailure) return null
        val meta = SstvMeta.SstvShot(fileName = nom, satName = sat, timeMs = quandMs, mode = mode.name, complete = complete,
            redecodeMs = if (origine == "file") horloge() else 0L, recale = recale,
            locator = locator, callsign = indicatif, source = origine, recording = enregistrement, note = note, entree = entree,
            trous = trous, ligne0Ms = ligne0Ms, reunion = reunion)
        runCatching { File(dossier, SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(meta), Charsets.UTF_8) }
        son?.let { (pcm, rate) -> if (pcm.isNotEmpty()) runCatching { SstvSon.ecritWav(SstvSon.fichier(png), pcm, rate) } }
        nom
    }

    /** The picture arriving, as PNG; null when none. */
    fun imageEnCours(): ByteArray? {
        val (m, px) = enCours ?: return null
        val h = _etat.value.lignes.coerceIn(1, m.height)
        // The whole frame at its own size, the lines still to come dark, the line arriving marked:
        // the picture fills in from the top, never stretched.
        val img = BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, m.width, h, px, 0, m.width)
        if (h < m.height) {
            val g = img.createGraphics()
            g.color = java.awt.Color(0x0E, 0x17, 0x20); g.fillRect(0, h, m.width, m.height - h)
            g.color = java.awt.Color(0x3F, 0xE0, 0xC8); g.fillRect(0, h, m.width, 1)
            g.dispose()
        }
        val o = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", o)
        return o.toByteArray()
    }

    /**
     * The pictures kept, newest first, with what their sidecars say — the
     * partial ones put together in another ([reunit]) left out while it is
     * there (see [imagesToutes]).
     */
    fun images(): List<SstvMeta.SstvShot> = imagesToutes().let { l ->
        val noms = l.mapTo(HashSet()) { it.fileName }
        l.filter { it.reunieDans.isEmpty() || it.reunieDans !in noms }
    }

    /** Every picture kept, the partial ones put together in another too. */
    fun imagesToutes(): List<SstvMeta.SstvShot> =
        (dossier.listFiles { f -> f.name.endsWith(".png") } ?: emptyArray())
            .map { f ->
                val s = SstvMeta.decode(f.name, runCatching { File(dossier, SstvMeta.sidecarName(f.name)).readText() }.getOrNull())
                if (s.timeMs <= 0L) s.copy(timeMs = f.lastModified()) else s
            }
            .sortedByDescending { it.timeMs }

    /** A picture's file: the PNG, its sound (.wav) or its GIF; only a plain name inside the folder. */
    fun fichier(nom: String): File? {
        val n = File(nom).name
        if (n != nom || n.startsWith('.') || !(n.endsWith(".png") || n.endsWith(".wav") || n.endsWith(".gif"))) return null
        return File(dossier, n).takeIf { it.isFile }
    }

    /** Removes a picture with its sidecar, sound and GIF. */
    /** A picture named after another satellite (its sidecar only: the file keeps its name). False when not found. */
    fun renommeSatellite(nom: String, sat: String): Boolean {
        val png = fichier(nom)?.takeIf { it.name.endsWith(".png") } ?: return false
        return synchronized(verrouFichiers) { SstvMeta.changeSatellite(png, sat) }
    }

    fun supprime(nom: String): Boolean {
        val png = fichier(nom)?.takeIf { it.name.endsWith(".png") } ?: return false
        // A picture put together: its partial ones back in the gallery.
        runCatching { libereMorceaux(png.name) }
        runCatching { File(dossier, SstvMeta.sidecarName(png.name)).delete() }
        runCatching { SstvSon.fichier(png).delete() }
        runCatching { SstvSon.gif(png).delete() }
        // Its corrected version with it (a fused picture of its group stays: the other copies made it too).
        runCatching { fr.f4ioz.satcombo.sstv.CorrectionsGalerie.dossier(dossier).let { d -> val c = File(d, fr.f4ioz.satcombo.sstv.CorrectionsGalerie.nomCorrigee(png.name)); File(d, c.name.removeSuffix(".png") + ".meta").delete(); c.delete() } }
        return png.delete()
    }

    // ------------------------------------------------------------------ put together

    /** The pass recording named in a picture's sidecar, when still kept (set by whoever keeps them). */
    @Volatile var enregistrementDe: (String) -> File? = { null }

    /** The partial pictures put together in [nom] marked so no longer (their sidecars). */
    private fun libereMorceaux(nom: String) = synchronized(verrouFichiers) {
        val s = SstvMeta.decode(nom, runCatching { File(dossier, SstvMeta.sidecarName(nom)).readText() }.getOrNull())
        for (m in s.reunion) File(dossier, File(m).name).takeIf { it.isFile }?.let { f -> SstvMeta.change(f) { if (it.reunieDans == nom) it.copy(reunieDans = "") else it } }
    }

    /** The other half of partial picture [nom], when it has one ([ReunionSstv.paires]). */
    fun partenaire(nom: String): SstvMeta.SstvShot? = ReunionSstv.partenaire(images().filter { it.source != ESSAI }, nom)

    /** Picture [s]'s pixels at its mode's full height (rows not received grey), or null. */
    private fun morceau(s: SstvMeta.SstvShot, m: SstvMode): ReunionSstv.Morceau? {
        val img = runCatching { ImageIO.read(File(dossier, s.fileName)) }.getOrNull() ?: return null
        if (img.width != m.width) return null
        val h = minOf(img.height, m.height)
        val px = IntArray(m.width * m.height) { ReunionSstv.GRIS }
        img.getRGB(0, 0, m.width, h, px, 0, m.width)
        return ReunionSstv.Morceau(px, h, s.trous)
    }

    /** Both pictures' sound as one: their own `.wav`s, else the pass recording around them; null when none. */
    private fun sonReuni(a: SstvMeta.SstvShot, b: SstvMeta.SstvShot, m: SstvMode): ReunionSstv.Son? {
        fun propre(s: SstvMeta.SstvShot) = SstvSon.litWav(SstvSon.fichier(File(dossier, s.fileName)))?.let { (pcm, r) -> ReunionSstv.sonDe(s, pcm, r) }
        val sa = propre(a); val sb = propre(b)
        if (sa != null && sb != null && sa.rate == sb.rate) return ReunionSstv.colle(sa, sb)
        // The pass recording: from a picture's length before the first one's end to the second one's end.
        val f = a.recording.ifBlank { b.recording }.takeIf { it.isNotBlank() }?.let { runCatching { enregistrementDe(it) }.getOrNull() } ?: return sa ?: sb
        val debutEnr = SstvMeta.debutEnregistrement(f.name).takeIf { it > 0L } ?: return sa ?: sb
        val (pcm, r) = SstvSon.litWav(f) ?: return sa ?: sb
        val de = ((a.ligne0Ms.takeIf { it > 0L } ?: (a.timeMs - (m.frameSeconds * 1000).toLong())) - 3_000L - debutEnr) * r / 1000L
        val a1 = ((b.timeMs + 1_500L - debutEnr) * r / 1000L)
        val i0 = de.coerceIn(0L, pcm.size.toLong()).toInt(); val i1 = a1.coerceIn(0L, pcm.size.toLong()).toInt()
        if (i1 - i0 < r) return sa ?: sb
        return ReunionSstv.Son(pcm.copyOfRange(i0, i1), r, debutEnr + i0 * 1000L / r)
    }

    /**
     * Partial pictures [nomA] and [nomB] put together in one ([ReunionSstv]):
     * kept as a picture of its own (its sound too), the two marked
     * « put together » and hidden while it is there. Its name, else why not.
     */
    fun reunit(nomA: String, nomB: String): Result<String> = runCatching {
        val tout = imagesToutes()
        val (a, b) = listOf(tout.firstOrNull { it.fileName == nomA } ?: error("introuvable"), tout.firstOrNull { it.fileName == nomB } ?: error("introuvable"))
            .sortedBy { it.timeMs }.let { it[0] to it[1] }
        if (!ReunionSstv.peutReunir(a, b)) error("pas_reunissables")
        val m = SstvMode.byName(a.mode) ?: error("mode")
        val pa = morceau(a, m) ?: error("illisible"); val pb = morceau(b, m) ?: error("illisible")
        val son = sonReuni(a, b, m)
        val r = ReunionSstv.reunit(m, pa, pb, son, ReunionSstv.decalageTemps(m, a, b)) ?: error("non_placee")
        val pcm = son?.let { it.pcm to it.rate }
        val nom = garde(m, r.px, r.lignes, r.complete, a.satName, b.timeMs, a.source, a.recording.ifBlank { b.recording }, a.locator.ifBlank { b.locator },
            a.callsign.ifBlank { b.callsign }, pcm, note = a.note.ifBlank { b.note }, entree = a.entree, trous = r.trous,
            ligne0Ms = a.ligne0Ms, reunion = listOf(a.fileName, b.fileName), nettoie = false) ?: error("ecriture")
        synchronized(verrouFichiers) { for (s in listOf(a, b)) SstvMeta.change(File(dossier, s.fileName)) { it.copy(reunieDans = nom) } }
        _etat.update { it.copy(version = it.version + 1) }
        nom
    }

    /** Picture [nom] put together undone: it is deleted, its partial ones come back. */
    fun separe(nom: String): Boolean {
        val s = imagesToutes().firstOrNull { it.fileName == nom }?.takeIf { it.reunion.isNotEmpty() } ?: return false
        return supprime(s.fileName).also { _etat.update { it.copy(version = it.version + 1) } }
    }

    /**
     * At a pass's end: every pair of partial pictures received from [deMs]
     * to [aMs] that are one picture, put together ([reunit]). The names made.
     */
    fun reunitPartielles(deMs: Long, aMs: Long): List<String> =
        ReunionSstv.paires(images().filter { it.timeMs in deMs..aMs && !horsAntenne(it) })
            .mapNotNull { (a, b) -> reunit(a.fileName, b.fileName).getOrNull() }

    // ------------------------------------------------------------------ decoded again

    @Volatile private var annuleRedecode = false

    fun annuleRedecodage() { annuleRedecode = true }

    /**
     * Decodes a recording again (a WAV; or a picture's own sound when [origine]
     * is that picture), in the background. Null when started, else why not.
     */
    fun redecode(f: File, origine: SstvMeta.SstvShot? = null): String? {
        if (_etat.value.redecodeProgres >= 0f) return "un décodage est déjà en cours"
        if (!f.isFile) return "fichier introuvable"
        if (!f.name.endsWith(".wav", ignoreCase = true)) return "seuls les WAV se décodent ici (un MP3 du téléphone : le décoder sur le téléphone)"
        annuleRedecode = false
        _etat.update { it.copy(redecode = f.name, redecodeProgres = 0f, redecodeImages = 0, erreur = "") }
        thread(isDaemon = true, name = "satme-sstv-redecode") {
            val n = runCatching { decodeFichier(f, origine) }.getOrElse { e ->
                _etat.update { it.copy(erreur = "décodage : " + (e.message ?: e.javaClass.simpleName)) }; -1
            }
            _etat.update { it.copy(redecodeProgres = -1f, redecodeImages = n.coerceAtLeast(0)) }
        }
        return null
    }

    /** Blocking: the pictures found in [f]. */
    fun decodeFichier(f: File, origine: SstvMeta.SstvShot? = null): Int {
        val ais0 = javax.sound.sampled.AudioSystem.getAudioInputStream(f)
        val voulu = javax.sound.sampled.AudioFormat(ais0.format.sampleRate, 16, 1, true, false)
        val ais = if (ais0.format.matches(voulu)) ais0 else javax.sound.sampled.AudioSystem.getAudioInputStream(voulu, ais0)
        val rate = voulu.sampleRate.toInt()
        val total = ais0.frameLength.takeIf { it > 0 } ?: (f.length() / 2)
        val sat = origine?.satName?.ifBlank { null } ?: f.name.removePrefix("SatMe_").substringBefore("_").ifBlank { "SAT" }
        // When the sound starts: a picture's own sound ends half a second after it; a recording's start is in its name.
        val debut = if (origine != null && origine.timeMs > 0L) origine.timeMs - (total * 1000 / rate - 500)
            else SstvMeta.debutEnregistrement(f.name).takeIf { it > 0L }
        // The same pictures received live: their exact time and place.
        val directs = if (origine == null) images().filter { it.source == "live" && it.satName.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-') == sat } else emptyList()
        val memoire = SstvSon.Memoire(rate)
        var trouvees = 0
        lateinit var d: SstvDecoder
        val ecoute = object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                if (linesDone < minLignes(mode)) return
                var recu = debut?.let { it + d.finTrame * 1000 / rate }
                val direct = recu?.let { r -> directs.filter { it.mode == mode.name && it.timeMs in (r - 30_000L)..(r + 10_000L) }.minByOrNull { kotlin.math.abs(it.timeMs - r) } }
                if (direct != null) recu = direct.timeMs
                val lieu = origine?.locator ?: direct?.locator ?: ""
                val nom = garde(mode, pixels, linesDone, complete, origine?.satName?.ifBlank { null } ?: direct?.satName ?: sat,
                    recu ?: horloge(), "file", origine?.recording?.ifBlank { null } ?: f.name, lieu,
                    origine?.callsign?.ifBlank { null } ?: direct?.callsign ?: "", sonDe(d, memoire), recale = recu != null,
                    trous = d.trous, ligne0Ms = recu?.let { it - (d.finTrame - d.debutImage).coerceAtLeast(0L) * 1000 / rate } ?: 0L)
                if (nom != null) { trouvees++; _etat.update { it.copy(derniere = nom, redecodeImages = trouvees, version = it.version + 1) } }
            }
        }
        d = SstvDecoder(rate, ecoute).also { it.forcedMode = force; it.continuous = continu }
        ais.use { s ->
            val octets = ByteArray(8192)
            val pcm = ShortArray(4096)
            var lus = 0L
            var reste = -1
            while (!annuleRedecode) {
                val k = s.read(octets, 0, octets.size)
                if (k <= 0) break
                var n = 0; var i = 0
                if (reste >= 0) { pcm[n++] = ((reste and 0xFF) or (octets[0].toInt() shl 8)).toShort(); i = 1; reste = -1 }
                while (i + 1 < k) { pcm[n++] = ((octets[i].toInt() and 0xFF) or (octets[i + 1].toInt() shl 8)).toShort(); i += 2 }
                if (i < k) reste = octets[i].toInt() and 0xFF
                memoire.ajoute(pcm, n)
                d.feed(pcm, n)
                lus += n
                _etat.update { it.copy(redecodeProgres = (lus.toFloat() / total).coerceIn(0f, 1f)) }
            }
        }
        runCatching { d.finish() }
        return trouvees
    }

    // ------------------------------------------------------------------ GIF

    /** Makes the GIF of a picture arriving, in the background. Null when started, else why not. */
    fun gif(nom: String): String? {
        val png = fichier(nom)?.takeIf { it.name.endsWith(".png") } ?: return "image introuvable"
        if (_etat.value.gif.isNotEmpty()) return "un GIF est déjà en cours"
        _etat.update { it.copy(gif = png.name) }
        thread(isDaemon = true, name = "satme-sstv-gif") {
            runCatching { fabriqueGif(png) }.onFailure { e -> _etat.update { it.copy(erreur = "GIF : " + (e.message ?: e.javaClass.simpleName)) } }
            _etat.update { it.copy(gif = "", version = it.version + 1) }
        }
        return null
    }

    /** Blocking: the GIF next to [png], its pace that of its own sound when kept. */
    fun fabriqueGif(png: File): File? {
        val image = ImageIO.read(png) ?: return null
        val shot = SstvMeta.decode(png.name, runCatching { File(dossier, SstvMeta.sidecarName(png.name)).readText() }.getOrNull())
        val son = SstvSon.litWav(SstvSon.fichier(png))
        val cal = son?.let { SstvRythme.calendrier(it.first, it.second) } ?: emptyList()
        val total = son?.first?.size?.toLong() ?: 0L
        val w = LARGEUR_GIF
        val hImage = (image.height * w / image.width + 1) and 1.inv()
        val h = hImage + BANDEAU
        val pleine = cadre(image, shot, w, h, hImage, true)
        val vide = cadre(image, shot, w, h, hImage, false)
        val sortie = SstvSon.gif(png)
        sortie.outputStream().buffered().use { o ->
            SstvRythme.ecritGif(o, pleine, vide, w, h, hImage, image.height, SstvRythme.etapesGif(cal, total, image.height))
        }
        return sortie.takeIf { it.length() > 0 }
    }

    /** The picture (or its empty place) over a caption: satellite, mode, time, locator, callsign. */
    private fun cadre(image: BufferedImage, s: SstvMeta.SstvShot, w: Int, h: Int, hImage: Int, avecImage: Boolean): IntArray {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = Color(11, 16, 22); g.fillRect(0, 0, w, h)
        if (avecImage) g.drawImage(image, 0, 0, w, hImage, null)
        g.color = Color(19, 28, 36); g.fillRect(0, hImage, w, h - hImage)
        val quand = if (s.timeMs > 0) SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(s.timeMs)) else ""
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 17); g.color = Color(0x3F, 0xE0, 0xC8)
        g.drawString(listOf(s.satName, s.mode).filter { it.isNotBlank() }.joinToString(" · "), 10, hImage + 22)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14); g.color = Color(0xE8, 0xF0, 0xF7)
        g.drawString(listOf(quand, s.locator, s.callsign).filter { it.isNotBlank() }.joinToString(" · "), 10, hImage + 42)
        g.color = Color(0x9A, 0xA7, 0xB4)
        val m = g.fontMetrics.stringWidth("SatMe")
        g.drawString("SatMe", w - m - 10, hImage + 42)
        g.dispose()
        return IntArray(w * h).also { b.getRGB(0, 0, w, h, it, 0, w) }
    }

    companion object {
        const val AUTO = "auto"
        const val CARTE = "carte"
        const val SDR = "sdr"
        /** A picture's source: the test generator (never kept), an I/Q recording played back. */
        const val ESSAI = "essai"
        const val RELECTURE = "relecture"
        /** Pictures not received on the air at the time: kept out of the journal and the board. */
        fun horsAntenne(s: SstvMeta.SstvShot) = s.source == ESSAI || s.source == RELECTURE
        val SOURCES = listOf(AUTO, CARTE, SDR)
        /** The dongle counts as playing this long after its last block. */
        const val SDR_VU_MS = 1500L
        /** No block for this long: the source is silent. */
        const val SON_VU_MS = 2500L
        private const val LARGEUR_GIF = 640
        private const val BANDEAU = 52

        /**
         * Which source feeds the decoder: the one chosen, or in « auto » the
         * dongle while it plays — unless the rig's own card hears the
         * satellite followed ([posteEntend]).
         */
        /**
         * The satellite a picture's sound came from: by the dongle ([parLaCle]:
         * the decoder's own SDR source, or the card when the dongle feeds it),
         * QO-100 while QO-100's tab holds it, else what the dongle receives
         * ([satelliteCle]: the satellite it follows; on a frequency of its own,
         * the one followed); by the rig, QO-100 while the rig is on it, else the satellite
         * followed ([suivi]).
         */
        fun satelliteDuSon(parLaCle: Boolean, cleSurQo100: Boolean, satelliteCle: String, posteSurQo100: Boolean, suivi: String): String =
            if (parLaCle) { if (cleSurQo100) QO100 else satelliteCle.ifBlank { suivi } } else if (posteSurQo100) QO100 else suivi
        const val QO100 = "QO-100"

        fun choisie(reglage: String, sdrJoue: Boolean, posteEntend: Boolean = false): String = when (reglage) {
            CARTE -> CARTE
            SDR -> SDR
            else -> if (sdrJoue && !posteEntend) SDR else CARTE
        }

        /** A picture is kept from an eighth of its lines, as on the phone. */
        fun minLignes(m: SstvMode) = (m.height / 8).coerceAtLeast(1)

        fun nomSource(s: String) = when (s) { SDR -> "clé SDR"; CARTE -> "carte son"; else -> "source" }

        /** What to tell when the tuning measures but cannot move the receiver. */
        fun conseilAfc(actif: Boolean, mesureHz: Int?, applique: Boolean, sens: Int): String {
            if (mesureHz == null || kotlin.math.abs(mesureHz) <= SstvAfc.ZONE_MORTE_HZ) return ""
            if (actif && applique) return ""
            val hz = if (sens != 0) sens * mesureHz else mesureHz
            val signe = if (hz > 0) "+" else "−"
            return when (sens) {
                0 -> "Tons décalés de " + (if (mesureHz > 0) "+" else "−") + kotlin.math.abs(mesureHz) + " Hz : en BLU, accorder de " +
                    (if (mesureHz > 0) "+" else "−") + kotlin.math.abs(mesureHz) + " Hz en USB (" + (if (mesureHz > 0) "−" else "+") +
                    kotlin.math.abs(mesureHz) + " en LSB) ; en FM l'accord n'y change rien."
                else -> "Accorder de " + signe + kotlin.math.abs(hz) + " Hz."
            }
        }
    }
}
