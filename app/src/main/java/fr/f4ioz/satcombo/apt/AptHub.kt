/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.apt

import android.content.Context
import android.graphics.Bitmap
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.sstv.Mp3Pcm
import fr.f4ioz.satcombo.sstv.SstvMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Bridge between the APT decoder and the rest of the app.
 *
 * Same design as SSTV, for the same reasons: audio comes from the recording
 * service's capture thread, the image goes to a Compose screen that may not
 * even be shown, and there is only ever one receiver at a time.
 *
 * Key difference: an APT image has no announced start or end. The satellite
 * transmits continuously while in view, and the image is whatever was received
 * between AOS and LOS — ten to fifteen minutes, up to two thousand lines. Raw
 * lines are kept as they come and the image is only built when shown or
 * written, so contrast is computed over the whole pass rather than line by
 * line (otherwise the image stripes whenever a cloud goes by).
 */
object AptHub {

    /** About seventeen minutes. Beyond that, the satellite has long set. */
    const val MAX_LINES = 2000

    data class AptState(
        /** The decoder is attached to the audio tap. */
        val listening: Boolean = false,
        /** A line was found and timing is held. */
        val locked: Boolean = false,
        /** Quality of the last sync burst, 0 to 1. */
        val quality: Float = 0f,
        /** Lines received since listening started. */
        val lines: Int = 0,
        /** Reduced preview, refreshed once per second. */
        val preview: Bitmap? = null,
        /** Name of the last PNG written. */
        val lastSaved: String? = null,
        /** Images written since start. */
        val savedCount: Int = 0,
        /** 0 to 1 while replaying a recording, -1 when idle. */
        val fileProgress: Float = -1f,
        /** File being replayed, or the last one processed. */
        val fileName: String? = null,
        /** Images found in the last replay. */
        val fileImages: Int = 0,
        /** Satellite the listening was started for. */
        val satName: String = ""
    )

    private val _state = MutableStateFlow(AptState())
    val state: StateFlow<AptState> = _state

    /** Station locator, kept up to date by the ViewModel. */
    @Volatile var qthLocator: String = ""

    /** Where decoded images live. */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "apt").apply { mkdirs() }

    fun images(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ------------------------------------------------------------ live listening

    private var live: AptDecoder? = null
    private var liveCtx: Context? = null
    private var liveSat: String = "NOAA"
    private val liveLines = ArrayList<FloatArray>()
    private var lastPreviewMs = 0L

    /** Attaches the decoder to a capture running at [sampleRate]. */
    @Synchronized
    fun startLive(ctx: Context, sampleRate: Int, satName: String) {
        liveCtx = ctx.applicationContext
        liveSat = satName
        lastPreviewMs = 0L
        liveLines.clear()
        live = AptDecoder(sampleRate, liveListener)
        _state.value = _state.value.copy(
            listening = true, locked = false, quality = 0f, lines = 0,
            preview = null, savedCount = 0, satName = satName)
    }

    /** Called from the capture thread for each block of samples. */
    fun feedLive(pcm: ShortArray, count: Int) {
        val d = live ?: return
        runCatching { d.feed(pcm, count) }
    }

    /** Stops listening and writes what was received. */
    @Synchronized
    fun stopLive() {
        val d = live
        if (d != null) runCatching { d.finish() }
        live = null
        val ctx = liveCtx
        // Under thirty lines (15 s) it is a scrap of noise, not an image.
        // Above that, even truncated, it is worth keeping.
        if (ctx != null && liveLines.size >= 30) {
            val name = save(ctx, ArrayList(liveLines), liveSat, "live")
            _state.value = _state.value.copy(
                lastSaved = name ?: _state.value.lastSaved,
                savedCount = _state.value.savedCount + (if (name != null) 1 else 0))
        }
        liveLines.clear()
        // The preview stays: the full APT screen shows it as the last image.
        // Only the strip on the pass page disappears — a frozen image there
        // would suggest we are still receiving.
        _state.value = _state.value.copy(listening = false, locked = false, quality = 0f)
    }

    /**
     * Writes what was received so far, without stopping.
     *
     * A NOAA pass lasts a quarter of an hour outdoors, battery draining, and
     * the system may kill the app. Saving mid-pass avoids losing everything.
     */
    @Synchronized
    fun saveNow(ctx: Context): String? {
        if (liveLines.size < 10) return null
        val name = save(ctx, ArrayList(liveLines), liveSat, "live")
        if (name != null) {
            _state.value = _state.value.copy(
                lastSaved = name, savedCount = _state.value.savedCount + 1)
        }
        return name
    }

    private val liveListener = object : AptDecoder.Listener {
        override fun onLine(index: Int, line: FloatArray) {
            if (liveLines.size < MAX_LINES) liveLines.add(line)
            val now = System.currentTimeMillis()
            if (now - lastPreviewMs < 1000L) {
                _state.value = _state.value.copy(lines = liveLines.size)
                return
            }
            lastPreviewMs = now
            _state.value = _state.value.copy(
                lines = liveLines.size, preview = preview(liveLines))
        }

        override fun onSync(locked: Boolean, quality: Float) {
            _state.value = _state.value.copy(locked = locked, quality = quality)
        }
    }

    // ------------------------------------------------------------ replay

    @Volatile private var cancelFile = false

    /** Asks a running replay to stop. */
    fun cancelFileDecode() { cancelFile = true }

    /**
     * Replays an existing recording. Blocking: call off the main thread.
     *
     * During the pass the operator holds the antenna and sorts images once back
     * home. Recordings made before APT decoding existed remain usable.
     */
    fun decodeFile(ctx: Context, mp3: File): Int {
        cancelFile = false
        val app = ctx.applicationContext
        val sat = mp3.name.removePrefix("SatMe_").substringBefore("_").ifBlank { "NOAA" }
        val lines = ArrayList<FloatArray>()
        _state.value = _state.value.copy(
            fileProgress = 0f, fileName = mp3.name, fileImages = 0, lines = 0)

        var decoder: AptDecoder? = null
        val listener = object : AptDecoder.Listener {
            override fun onLine(index: Int, line: FloatArray) {
                if (lines.size < MAX_LINES) lines.add(line)
                val now = System.currentTimeMillis()
                if (now - lastPreviewMs < 1000L) return
                lastPreviewMs = now
                _state.value = _state.value.copy(lines = lines.size, preview = preview(lines))
            }
            override fun onSync(locked: Boolean, quality: Float) {
                _state.value = _state.value.copy(locked = locked, quality = quality)
            }
        }

        runCatching {
            Mp3Pcm.decode(mp3) { pcm, n, rate, fraction ->
                if (decoder == null) decoder = AptDecoder(rate, listener)
                decoder?.feed(pcm, n)
                _state.value = _state.value.copy(fileProgress = fraction)
                !cancelFile
            }
        }
        runCatching { decoder?.finish() }

        var found = 0
        var name: String? = null
        if (lines.size >= 30) {
            name = save(app, lines, sat, "file", mp3.name)
            if (name != null) found = 1
        }
        _state.value = _state.value.copy(
            fileProgress = -1f, fileImages = found, preview = preview(lines),
            lastSaved = name ?: _state.value.lastSaved,
            lines = lines.size)
        return found
    }

    // ------------------------------------------------------------------ images

    /**
     * Builds the image from raw lines.
     *
     * Contrast bounds are recomputed over all lines each time: an APT image
     * brightens and darkens along the pass (sun angle, signal fading), and a
     * calibration frozen on the first lines would burn half the image.
     */
    private fun renderBitmap(lines: List<FloatArray>, wStep: Int, hStep: Int): Bitmap? {
        if (lines.isEmpty()) return null
        val lv = Apt.levels(lines)
        val w = (Apt.WORDS_PER_LINE + wStep - 1) / wStep
        val h = (lines.size + hStep - 1) / hStep
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        var row = 0
        var i = 0
        while (i < lines.size && row < h) {
            val g = Apt.gray(lines[i], lv[0], lv[1])
            var x = 0
            var k = 0
            while (k < Apt.WORDS_PER_LINE && x < w) {
                val v = if (k < g.size) g[k] else 0
                px[row * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                x++
                k += wStep
            }
            row++
            i += hStep
        }
        return runCatching {
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    /** Light preview: every other word, never more than 900 lines. */
    private fun preview(lines: List<FloatArray>): Bitmap? {
        if (lines.isEmpty()) return null
        val hStep = maxOf(1, (lines.size + 899) / 900)
        return renderBitmap(lines, 2, hStep)
    }

    private fun save(
        ctx: Context, lines: List<FloatArray>, sat: String, source: String,
        recording: String = ""
    ): String? {
        val bmp = renderBitmap(lines, 1, 1) ?: return null
        val now = System.currentTimeMillis()
        // A full image is ~2000 lines; under 300 the pass was joined late or
        // cut, and the archive must say so.
        val complete = lines.size >= 300
        val name = SstvMeta.fileName(sat, now, "APT", complete, kind = "APT")
        val out = File(dir(ctx), name)
        val ok = runCatching {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        if (!ok) return null
        runCatching {
            val st = SettingsStore(ctx)
            val shot = SstvMeta.SstvShot(
                fileName = name, satName = sat, timeMs = now, mode = "APT",
                complete = complete,
                locator = qthLocator.ifBlank { st.manualLocator }, callsign = st.callsign,
                source = source, recording = recording,
                note = lines.size.toString() + " lignes")
            File(dir(ctx), SstvMeta.sidecarName(name))
                .writeText(SstvMeta.encode(shot), Charsets.UTF_8)
        }
        exportCopy(ctx, out)
        return name
    }

    /** Each saved image with its metadata, newest first. */
    fun shots(ctx: Context): List<Pair<File, SstvMeta.SstvShot>> =
        images(ctx).map { f ->
            val side = File(f.parentFile, SstvMeta.sidecarName(f.name))
            val text = runCatching { if (side.isFile) side.readText(Charsets.UTF_8) else null }
                .getOrNull()
            var shot = SstvMeta.decode(f.name, text)
            if (shot.timeMs <= 0L) shot = shot.copy(timeMs = f.lastModified())
            f to shot
        }

    /** Deletes an image together with its sidecar file. */
    fun delete(file: File): Boolean {
        runCatching { File(file.parentFile, SstvMeta.sidecarName(file.name)).delete() }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** Same export folder as audio recordings, if configured. */
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
