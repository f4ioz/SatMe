/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import android.content.Context
import android.graphics.Bitmap
import fr.f4ioz.satcombo.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The bridge between the SSTV engine and the rest of the app.
 *
 * A single object rather than an injected service because there is only ever one
 * receiver: the audio comes from the pass recorder's capture thread, which lives
 * in a foreground service, while the picture has to reach a Compose screen that
 * may not even be on screen. A [StateFlow] carries the state across, and the
 * decoded frames are written to disk immediately — an image that arrived during
 * a pass must survive the app being killed while the operator is outside with
 * the antenna.
 */
object SstvHub {

    data class SstvState(
        /** The engine is hooked to the recorder and watching for a header. */
        val listening: Boolean = false,
        /** Mode being decoded right now, null between pictures. */
        val modeName: String? = null,
        /** 0..1 through the current frame. */
        val progress: Float = 0f,
        /** Live picture, updated a few times a second while it builds. */
        val preview: Bitmap? = null,
        /** Name of the last PNG written. */
        val lastSaved: String? = null,
        /** Pictures saved since the engine was started. */
        val savedCount: Int = 0,
        /** 0..1 while an MP3 is being re-decoded, -1 when idle. */
        val fileProgress: Float = -1f,
        /** The file being re-decoded, or the last one processed. */
        val fileName: String? = null,
        /** Pictures found in the last re-decode. */
        val fileImages: Int = 0,
        /** Mode forced by the operator, or null to follow the VIS header. */
        val forcedMode: String? = null,
        /** Continuous decoding: a train of sync pulses starts a picture. */
        val continu: Boolean = true,
        /** Pictures cleaned before they are saved (see [SstvNettoyage]). */
        val nettoyage: Boolean = true,
        /** Automatic tuning on (see [SstvAfc]). */
        val afc: Boolean = false,
        /** Its offset measured (Hz, in the sound as it arrives), null when not known. */
        val afcMesureHz: Int? = null,
        /** The correction it applied so far (Hz of sound). */
        val afcTotalHz: Int = 0,
        /** It is moving the receiver now (else advice only). */
        val afcApplique: Boolean = false,
        /** How the tones follow the receiver: +1 USB, -1 LSB, 0 unknown or FM. */
        val afcSens: Int = 0,
        /** Last engine failure — shown, not hidden. */
        val erreur: String? = null,
        /** True once a frame is under way (header received or forced start). */
        val decoding: Boolean = false,
        /**
         * Satellite the listening was started on. Already in the sidecar; also
         * here so the pass page strip can say whose image it shows.
         */
        val satName: String = ""
    )

    private val _state = MutableStateFlow(SstvState())
    val state: StateFlow<SstvState> = _state

    /** The live reception quality ([QualiteSstv]), twice a second while listening; null when not. */
    private val _qualite = MutableStateFlow<QualiteSstv.Etat?>(null)
    val qualite: StateFlow<QualiteSstv.Etat?> = _qualite
    @Volatile private var liveQualite: QualiteSstv? = null
    private var qualiteVueMs = 0L
    /** The pass recording feeding the decoder (its file name), written in each picture's sidecar. */
    @Volatile private var liveEnregistrement: String = ""

    /**
     * Station locator, kept up to date by the ViewModel. The hub lives in a
     * service with no GPS access; without this an archived image would not
     * know where it was received — half the point when operating portable.
     */
    @Volatile var qthLocator: String = ""

    /**
     * Forced mode, kept here so a decoder created later (dongle plugged in,
     * recording started) picks it up without the screen setting it again.
     */
    @Volatile private var forced: SstvMode? = null

    /** Continuous decoding, kept here for the same reason as [forced]. */
    @Volatile private var continu = true

    /** Picture cleaning, kept here for the same reason as [forced]. */
    @Volatile private var nettoyage = true

    /** Automatic tuning, kept here for the same reason as [forced]. */
    @Volatile private var afc = false

    /**
     * What the automatic tuning moves when the capture is the phone's sound
     * (a rig): the rig through the CAT loop's own writes, set by the
     * ViewModel. The SDR dongle gives its own when it starts the capture.
     */
    @Volatile var cibleCat: CibleAfc? = null

    @Volatile private var loaded = false

    /**
     * Loads the settings once, for the screen: before any capture had started,
     * the controls showed the defaults, not what the operator had chosen.
     */
    fun ensureLoaded(ctx: Context) { if (!loaded) loadForced(ctx) }

    /** Loads the forced mode and continuous decoding from settings. */
    fun loadForced(ctx: Context) {
        loaded = true
        val name = runCatching { SettingsStore(ctx).sstvForcedMode }.getOrDefault("")
        forced = if (name.isBlank()) null else SstvMode.byName(name)
        continu = runCatching { SettingsStore(ctx).sstvContinu }.getOrDefault(true)
        nettoyage = runCatching { SettingsStore(ctx).sstvNettoyage }.getOrDefault(true)
        afc = runCatching { SettingsStore(ctx).sstvAfc }.getOrDefault(false)
        live?.forcedMode = forced
        live?.continuous = continu
        _state.value = _state.value.copy(forcedMode = forced?.name, continu = continu, nettoyage = nettoyage, afc = afc)
    }

    /**
     * Turns the automatic tuning on or off. Persisted. Off: the receiver goes
     * back where the operator left it, the decoder is left alone.
     */
    fun setAfc(ctx: Context, on: Boolean) {
        afc = on
        runCatching { SettingsStore(ctx).sstvAfc = on }
        if (!on) synchronized(verrouAfc) { lacheAfc() }
        _state.value = _state.value.copy(afc = on, afcMesureHz = null, afcTotalHz = 0, afcApplique = false)
    }

    /** The tuning's « reset »: the receiver back where the operator left it, the measurements forgotten. */
    fun remiseAZeroAfc() {
        synchronized(verrouAfc) { liveAfc?.let { a -> runCatching { a.remiseAZero(cibleLive()) } } }
        _state.value = _state.value.copy(afcMesureHz = null, afcTotalHz = 0)
    }

    private val verrouAfc = Any()
    private var liveAfc: SstvAfc? = null
    @Volatile private var liveCible: CibleAfc? = null
    private var liveRate = 0
    private var afcVuMs = 0L

    private fun cibleLive(): CibleAfc? = liveCible ?: cibleCat

    /** The tuning dropped: its receiver moved back, the decoder no longer reports to it. */
    private fun lacheAfc() {
        liveAfc?.let { a -> runCatching { a.remiseAZero(cibleLive()) } }
        liveAfc = null
        live?.mesureSync = null
    }

    /** Turns picture cleaning on or off. Persisted. */
    fun setNettoyage(ctx: Context, on: Boolean) {
        nettoyage = on
        runCatching { SettingsStore(ctx).sstvNettoyage = on }
        _state.value = _state.value.copy(nettoyage = on)
    }

    /** The picture as it will be saved: cleaned when that is on. */
    private fun finale(mode: SstvMode, pixels: IntArray, linesDone: Int): IntArray =
        if (nettoyage) runCatching { SstvNettoyage.nettoie(pixels, mode.width, mode.height, linesDone) }.getOrDefault(pixels)
        else pixels

    /** Turns continuous decoding on or off. Persisted. */
    fun setContinu(ctx: Context, on: Boolean) {
        continu = on
        live?.continuous = on
        runCatching { SettingsStore(ctx).sstvContinu = on }
        _state.value = _state.value.copy(continu = on)
    }

    /**
     * Forces a mode, or back to VIS detection when [name] is empty. Persisted:
     * a satellite that always uses the same mode should not need it every pass.
     */
    fun setForcedMode(ctx: Context, name: String?) {
        val m = if (name.isNullOrBlank()) null else SstvMode.byName(name)
        forced = m
        live?.forcedMode = m
        runCatching { SettingsStore(ctx).sstvForcedMode = m?.name ?: "" }
        _state.value = _state.value.copy(forcedMode = m?.name)
    }

    /**
     * Starts decoding now, without waiting for a header. Needs a forced mode
     * (it cannot be guessed); otherwise does nothing. True when started.
     */
    fun forceStart(): Boolean {
        val d = live ?: run {
            _state.value = _state.value.copy(erreur = "capture arrêtée")
            return false
        }
        val m = forced ?: run {
            // A button with no effect looks broken: say what is missing.
            _state.value = _state.value.copy(erreur = "choisir un mode d'abord")
            return false
        }
        runCatching { d.forceStart(m) }.onFailure {
            _state.value = _state.value.copy(erreur = it.javaClass.simpleName)
            return false
        }
        runCatching { liveQualite?.debut(m) }
        _state.value = _state.value.copy(modeName = m.name, progress = 0f, decoding = true)
        return true
    }

    /** Drops the current frame and goes back to listening. */
    fun abortFrame() {
        val d = live ?: return
        runCatching { d.abort() }
        _state.value = _state.value.copy(modeName = null, progress = 0f, decoding = false)
    }

    /** Where the decoded pictures live. */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "sstv").apply { mkdirs() }

    fun images(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ------------------------------------------------------------- live decode

    private var live: SstvDecoder? = null
    /** The sound just heard, to keep each picture's own. */
    private var liveSon: SstvSon.Memoire? = null
    private var liveCtx: Context? = null
    private var liveSat: String = "SAT"
    private var lastPreviewMs = 0L

    /** Hook the engine to a capture running at [sampleRate]. */
    @Synchronized
    fun startLive(ctx: Context, sampleRate: Int, satName: String, cible: CibleAfc? = null, enregistrement: String = "") {
        synchronized(verrouAfc) { lacheAfc(); liveCible = cible; liveRate = sampleRate }
        liveCtx = ctx.applicationContext
        liveSat = satName
        lastPreviewMs = 0L
        pannes = 0
        if (forced == null) loadForced(ctx)
        val q = QualiteSstv(sampleRate).also { liveQualite = it }
        liveEnregistrement = enregistrement
        live = SstvDecoder(sampleRate, liveListener).also { it.forcedMode = forced; it.continuous = continu; it.mesureLigne = { _, l, t, n, b -> q.ligne(l, t, n, b) } }
        liveSon = SstvSon.Memoire(sampleRate)
        _state.value = _state.value.copy(
            listening = true, modeName = null, progress = 0f,
            preview = null, savedCount = 0, decoding = false, erreur = null,
            forcedMode = forced?.name, satName = satName)
    }

    /**
     * Called from the capture thread for each buffer. A decoder failure must
     * not kill the capture, but must not vanish either — the screen would keep
     * saying "listening" over a dead engine. It is recorded and shown.
     */
    fun feedLive(pcm: ShortArray, count: Int) {
        val d = live ?: return
        // Kept before decoding: a picture ending in this buffer takes it all.
        runCatching { liveSon?.ajoute(pcm, count) }
        runCatching { liveQualite?.let { q -> q.son(pcm, count); publieQualite(q) } }
        runCatching {
            if (afc) synchronized(verrouAfc) {
                val a = liveAfc ?: SstvAfc(liveRate).also { liveAfc = it }
                a.nourrit(d, pcm, count, cibleLive())
                publieAfc(a)
            } else d.feed(pcm, count)
        }.onFailure { e ->
            val n = ++pannes
            _state.value = _state.value.copy(
                erreur = e.javaClass.simpleName + (if (n > 1) " ×$n" else ""))
        }
    }

    /** The tuning's numbers for the screens, twice a second. */
    private fun publieAfc(a: SstvAfc) {
        val now = System.currentTimeMillis()
        if (now - afcVuMs < 500L) return
        afcVuMs = now
        val e = a.etat
        _state.value = _state.value.copy(afcMesureHz = e.mesureHz, afcTotalHz = e.totalHz, afcApplique = e.applique, afcSens = e.sens)
    }

    private fun publieQualite(q: QualiteSstv) {
        val now = System.currentTimeMillis()
        if (now - qualiteVueMs < 500L) return
        qualiteVueMs = now
        _qualite.value = q.etat
    }

    /** Decoder failures since listening started. */
    @Volatile private var pannes = 0

    @Synchronized
    fun stopLive() {
        val d = live ?: run {
            _state.value = _state.value.copy(listening = false); return
        }
        runCatching { d.finish() }
        synchronized(verrouAfc) { lacheAfc(); liveCible = null }
        live = null
        liveSon = null
        liveQualite = null; liveEnregistrement = ""; _qualite.value = null
        _state.value = _state.value.copy(
            listening = false, modeName = null, progress = 0f, decoding = false)
    }

    private val liveListener = object : SstvDecoder.Listener {
        override fun onVis(mode: SstvMode) {
            runCatching { liveQualite?.debut(mode) }
            _state.value = _state.value.copy(
                modeName = mode.name, progress = 0f, decoding = true)
        }

        override fun onProgress(mode: SstvMode, pixels: IntArray, linesDone: Int) {
            // Four refreshes a second is enough to watch a picture paint itself
            // and cheap enough not to disturb the capture thread.
            val now = System.currentTimeMillis()
            if (now - lastPreviewMs < 250L) return
            lastPreviewMs = now
            _state.value = _state.value.copy(
                modeName = mode.name, decoding = true,
                progress = (linesDone.toFloat() / mode.height).coerceIn(0f, 1f),
                preview = toBitmap(mode, pixels))
        }

        override fun onImage(
            mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean
        ) {
            // A picture cut short by the end of the pass is still worth keeping;
            // a couple of lines of noise is not.
            runCatching { liveQualite?.let { it.fin(); _qualite.value = it.etat } }
            val bmp = toBitmap(mode, finale(mode, pixels, linesDone))
            val ctx = liveCtx
            var saved: String? = _state.value.lastSaved
            var count = _state.value.savedCount
            if (ctx != null && linesDone >= mode.height / 8) {
                saved = save(ctx, bmp, mode, liveSat, complete, "live", recording = liveEnregistrement, son = sonDe(live, liveSon))
                count++
                // Decoded again from its own sound, off this thread (the option, battery saver off).
                saved?.let { CorrectionsTelephone.apresImage(ctx, File(dir(ctx), it)) }
            }
            _state.value = _state.value.copy(
                modeName = null, progress = 0f, preview = bmp, decoding = false,
                lastSaved = saved, savedCount = count)
        }
    }

    // ------------------------------------------------------------- file decode

    @Volatile private var cancelFile = false

    /** Ask a running [decodeFile] to give up. */
    fun cancelFileDecode() { cancelFile = true }

    /**
     * Re-decodes an already-recorded MP3. Blocking — call it off the main thread.
     *
     * This is what makes the feature usable in practice: the operator points the
     * antenna during the pass and sorts the pictures out afterwards, and a
     * recording that was made before SSTV decoding was even switched on can
     * still be mined for images.
     */
    fun decodeFile(ctx: Context, mp3: File, origine: SstvMeta.SstvShot? = null): Int {
        cancelFile = false
        val app = ctx.applicationContext
        val sat = origine?.satName?.ifBlank { null }
            ?: mp3.name.removePrefix("SatMe_").substringBefore("_").ifBlank { "SAT" }
        var son: SstvSon.Memoire? = null
        var rateFichier = 0
        // When the sound starts, UTC: a picture keeps the time it was received.
        //  - a picture's own sound: it ends half a second after the picture;
        //  - a recording: its start is in its name.
        //    The spoken header before the pass shifts it by its length.
        val info = if (origine == null) fr.f4ioz.satcombo.audio.InfoEnregistrement.lit(mp3) else null
        // Older recordings did not keep it: estimated from the file's length and end.
        val annonce = if (origine == null) fr.f4ioz.satcombo.audio.InfoEnregistrement.annonceMs(mp3) else 0L
        fun debutMs(): Long? {
            if (origine != null && origine.timeMs > 0L && rateFichier > 0)
                return origine.timeMs - ((mp3.length() - 44) / 2 * 1000 / rateFichier - 500)
            // The file ends when the capture stopped: its start is known exactly from there.
            return SstvMeta.debutEnregistrement(mp3.name).takeIf { it > 0L }?.let { nom ->
                fr.f4ioz.satcombo.audio.InfoEnregistrement.origine(nom, mp3.lastModified(),
                    fr.f4ioz.satcombo.audio.InfoEnregistrement.dureeMs(mp3), annonce)
            }
        }
        // The same pictures received live during that pass: their exact time and place.
        val enDirect = if (origine == null) runCatching {
            shots(app).map { it.second }.filter { it.source == "live" && it.satName == sat }
        }.getOrDefault(emptyList()) else emptyList()
        fun memeImage(mode: SstvMode, recu: Long): SstvMeta.SstvShot? =
            enDirect.filter { it.mode == mode.name && it.timeMs in (recu - 30_000L)..(recu + 10_000L) }
                .minByOrNull { kotlin.math.abs(it.timeMs - recu) }
        var found = 0
        _state.value = _state.value.copy(
            fileProgress = 0f, fileName = mp3.name, fileImages = 0)

        var decoder: SstvDecoder? = null
        val listener = object : SstvDecoder.Listener {
            override fun onVis(mode: SstvMode) {
                _state.value = _state.value.copy(modeName = mode.name)
            }
            override fun onProgress(mode: SstvMode, pixels: IntArray, linesDone: Int) {
                val now = System.currentTimeMillis()
                if (now - lastPreviewMs < 250L) return
                lastPreviewMs = now
                _state.value = _state.value.copy(
                    modeName = mode.name, preview = toBitmap(mode, pixels),
                    progress = (linesDone.toFloat() / mode.height).coerceIn(0f, 1f))
            }
            override fun onImage(
                mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean
            ) {
                if (linesDone < mode.height / 8) return
                val bmp = toBitmap(mode, finale(mode, pixels, linesDone))
                var recu = debutMs()?.let { d -> d + (decoder?.finTrame ?: 0L) * 1000 / rateFichier.coerceAtLeast(1) }
                val direct = recu?.let { memeImage(mode, it) }
                if (direct != null) recu = direct.timeMs
                // Where it was received: never where the phone happens to be today.
                val lieu = origine?.locator ?: direct?.locator?.ifBlank { null } ?: info?.locator ?: ""
                val name = save(app, bmp, mode, sat, complete, "file", origine?.recording?.ifBlank { null } ?: mp3.name,
                    sonDe(decoder, son), recuMs = recu, origine = origine, locatorRecu = lieu)
                found++
                _state.value = _state.value.copy(
                    modeName = null, progress = 0f, preview = bmp,
                    lastSaved = name, fileImages = found)
            }
        }

        runCatching {
            Mp3Pcm.decode(mp3) { pcm, n, rate, fraction ->
                if (decoder == null) {
                    decoder = SstvDecoder(rate, listener).also { it.forcedMode = forced; it.continuous = continu }
                    son = SstvSon.Memoire(rate)
                    rateFichier = rate
                }
                son?.ajoute(pcm, n)
                decoder?.feed(pcm, n)
                _state.value = _state.value.copy(fileProgress = fraction)
                !cancelFile
            }
        }
        runCatching { decoder?.finish() }
        _state.value = _state.value.copy(
            fileProgress = -1f, modeName = null, progress = 0f, fileImages = found)
        return found
    }

    // ------------------------------------------------------------------ output

    /**
     * The sound of the picture just finished: from its header (or its first
     * sync pulses) to now, and half a second more when already received.
     */
    private fun sonDe(d: SstvDecoder?, m: SstvSon.Memoire?): Pair<ShortArray, Int>? {
        if (d == null || m == null) return null
        val rateEntree = m.rate * m.facteur
        return runCatching { m.extrait(d.debutTrame, d.finTrame + rateEntree / 2) to m.rate }.getOrNull()
    }

    private fun toBitmap(mode: SstvMode, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(pixels, mode.width, mode.height, Bitmap.Config.ARGB_8888)

    /**
     * Writes the PNG, its sidecar (see [SstvMeta]) and a copy in the export
     * folder.
     */
    private fun save(
        ctx: Context, bmp: Bitmap, mode: SstvMode, sat: String, complete: Boolean,
        source: String, recording: String = "", son: Pair<ShortArray, Int>? = null,
        /** Decoded again: when it was received (null if unknown), and where from. */
        recuMs: Long? = null, origine: SstvMeta.SstvShot? = null,
        /** Where it was received, when decoded again ("" = unknown); null = here, now. */
        locatorRecu: String? = null
    ): String? {
        val now = System.currentTimeMillis()
        val quand = recuMs ?: now
        // Named after its reception; a second decoding of it does not overwrite the first.
        var variante = 0
        var name = SstvMeta.fileName(sat, quand, mode.name, complete)
        while (File(dir(ctx), name).exists() && variante < 99)
            name = SstvMeta.fileName(sat, quand, mode.name, complete, variante = ++variante + 1)
        val out = File(dir(ctx), name)
        val ok = runCatching {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        if (!ok) return null
        runCatching {
            val st = SettingsStore(ctx)
            val shot = SstvMeta.SstvShot(
                fileName = name, satName = sat, timeMs = quand, mode = mode.name,
                complete = complete,
                redecodeMs = if (source == "file") now else 0L,
                recale = source == "file" && recuMs != null,
                locator = locatorRecu ?: qthLocator.ifBlank { st.manualLocator },
                callsign = origine?.callsign?.ifBlank { null } ?: st.callsign,
                source = source, recording = recording)
            File(dir(ctx), SstvMeta.sidecarName(name))
                .writeText(SstvMeta.encode(shot), Charsets.UTF_8)
        }
        // The picture's own sound, to hear it again or make a video of it.
        son?.let { (pcm, rate) -> if (pcm.isNotEmpty()) runCatching { SstvSon.ecritWav(SstvSon.fichier(out), pcm, rate) } }
        exportCopy(ctx, out)
        return name
    }

    /** Every saved picture with what is known about it, newest first. */
    fun shots(ctx: Context): List<Pair<File, SstvMeta.SstvShot>> =
        images(ctx).map { f ->
            val side = File(f.parentFile, SstvMeta.sidecarName(f.name))
            val text = runCatching { if (side.isFile) side.readText(Charsets.UTF_8) else null }
                .getOrNull()
            var shot = SstvMeta.decode(f.name, text)
            // Images older than sidecars have no parsable stamp: use the file date.
            if (shot.timeMs <= 0L) shot = shot.copy(timeMs = f.lastModified())
            f to shot
        }

    /** Removes a picture and its sidecar together. */
    fun delete(file: File): Boolean {
        runCatching { File(file.parentFile, SstvMeta.sidecarName(file.name)).delete() }
        runCatching { SstvSon.fichier(file).delete() }
        runCatching { SstvSon.video(file).delete() }
        runCatching { SstvSon.video(file, hd = true).delete() }
        runCatching { SstvSon.gif(file).delete() }
        CorrectionsTelephone.oublie(file)
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** Same SAF export folder as the audio recordings, when one is configured. */
    private fun exportCopy(ctx: Context, file: File) {
        val uriStr = SettingsStore(ctx).recordingsTreeUri
        if (uriStr.isBlank()) return
        runCatching {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                ctx, android.net.Uri.parse(uriStr)) ?: return
            tree.findFile(file.name)?.delete()
            val doc = tree.createFile("image/png", file.name) ?: return
            ctx.contentResolver.openOutputStream(doc.uri)?.use { o ->
                file.inputStream().use { it.copyTo(o) }
            }
        }
    }
}
