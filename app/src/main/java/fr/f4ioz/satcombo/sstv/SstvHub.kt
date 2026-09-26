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

    /** Loads the forced mode from settings. */
    fun loadForced(ctx: Context) {
        val name = runCatching { SettingsStore(ctx).sstvForcedMode }.getOrDefault("")
        forced = if (name.isBlank()) null else SstvMode.byName(name)
        live?.forcedMode = forced
        _state.value = _state.value.copy(forcedMode = forced?.name)
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
    private var liveCtx: Context? = null
    private var liveSat: String = "SAT"
    private var lastPreviewMs = 0L

    /** Hook the engine to a capture running at [sampleRate]. */
    @Synchronized
    fun startLive(ctx: Context, sampleRate: Int, satName: String) {
        liveCtx = ctx.applicationContext
        liveSat = satName
        lastPreviewMs = 0L
        pannes = 0
        if (forced == null) loadForced(ctx)
        live = SstvDecoder(sampleRate, liveListener).also { it.forcedMode = forced }
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
        runCatching { d.feed(pcm, count) }.onFailure { e ->
            val n = ++pannes
            _state.value = _state.value.copy(
                erreur = e.javaClass.simpleName + (if (n > 1) " ×$n" else ""))
        }
    }

    /** Decoder failures since listening started. */
    @Volatile private var pannes = 0

    @Synchronized
    fun stopLive() {
        val d = live ?: run {
            _state.value = _state.value.copy(listening = false); return
        }
        runCatching { d.finish() }
        live = null
        _state.value = _state.value.copy(
            listening = false, modeName = null, progress = 0f, decoding = false)
    }

    private val liveListener = object : SstvDecoder.Listener {
        override fun onVis(mode: SstvMode) {
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
            val bmp = toBitmap(mode, pixels)
            val ctx = liveCtx
            var saved: String? = _state.value.lastSaved
            var count = _state.value.savedCount
            if (ctx != null && linesDone >= mode.height / 8) {
                saved = save(ctx, bmp, mode, liveSat, complete, "live")
                count++
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
    fun decodeFile(ctx: Context, mp3: File): Int {
        cancelFile = false
        val app = ctx.applicationContext
        val sat = mp3.name.removePrefix("SatMe_").substringBefore("_").ifBlank { "SAT" }
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
                val bmp = toBitmap(mode, pixels)
                val name = save(app, bmp, mode, sat, complete, "file", mp3.name)
                found++
                _state.value = _state.value.copy(
                    modeName = null, progress = 0f, preview = bmp,
                    lastSaved = name, fileImages = found)
            }
        }

        runCatching {
            Mp3Pcm.decode(mp3) { pcm, n, rate, fraction ->
                if (decoder == null) decoder =
                    SstvDecoder(rate, listener).also { it.forcedMode = forced }
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

    private fun toBitmap(mode: SstvMode, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(pixels, mode.width, mode.height, Bitmap.Config.ARGB_8888)

    /**
     * Writes the PNG, its sidecar (see [SstvMeta]) and a copy in the export
     * folder.
     */
    private fun save(
        ctx: Context, bmp: Bitmap, mode: SstvMode, sat: String, complete: Boolean,
        source: String, recording: String = ""
    ): String? {
        val now = System.currentTimeMillis()
        val name = SstvMeta.fileName(sat, now, mode.name, complete)
        val out = File(dir(ctx), name)
        val ok = runCatching {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        if (!ok) return null
        runCatching {
            val st = SettingsStore(ctx)
            val shot = SstvMeta.SstvShot(
                fileName = name, satName = sat, timeMs = now, mode = mode.name,
                complete = complete,
                locator = qthLocator.ifBlank { st.manualLocator }, callsign = st.callsign,
                source = source, recording = recording)
            File(dir(ctx), SstvMeta.sidecarName(name))
                .writeText(SstvMeta.encode(shot), Charsets.UTF_8)
        }
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
