/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
        /** Mode imposé par l'opérateur, ou null pour suivre l'en-tête VIS. */
        val forcedMode: String? = null,
        /** La dernière panne du moteur, à montrer plutôt qu'à taire. */
        val erreur: String? = null,
        /** Vrai quand une trame est engagée — en-tête reçu ou départ forcé. */
        val decoding: Boolean = false,
        /**
         * Le satellite sur lequel l'écoute a été lancée.
         *
         * Une image sans satellite ne vaut pas grand-chose : six mois plus tard
         * on ne sait plus si elle vient de l'ISS ou d'un relais. Le nom est déjà
         * écrit dans le fichier annexe ; il est aussi porté ici pour que la
         * bande de la page du passage puisse dire à qui appartient l'image
         * qu'elle affiche.
         */
        val satName: String = ""
    )

    private val _state = MutableStateFlow(SstvState())
    val state: StateFlow<SstvState> = _state

    /**
     * Locator de la station, tenu à jour par le modèle. Le hub vit dans un
     * service et n'a pas accès au GPS ; sans cela une image archivée ne saurait
     * pas d'où elle a été reçue, ce qui est la moitié de l'intérêt d'une
     * archive quand on opère en portable.
     */
    @Volatile var qthLocator: String = ""

    /**
     * Mode imposé, mémorisé ici pour qu'un décodeur créé plus tard — au
     * branchement de la clé, au démarrage d'un enregistrement — le reprenne
     * sans que l'écran ait à repasser derrière.
     */
    @Volatile private var forced: SstvMode? = null

    /** Charge le mode imposé enregistré dans les réglages. */
    fun loadForced(ctx: Context) {
        val name = runCatching { SettingsStore(ctx).sstvForcedMode }.getOrDefault("")
        forced = if (name.isBlank()) null else SstvMode.byName(name)
        live?.forcedMode = forced
        _state.value = _state.value.copy(forcedMode = forced?.name)
    }

    /**
     * Impose un mode, ou revient à la lecture de l'en-tête avec [name] vide.
     * Le choix est retenu : sur un satellite qui émet toujours dans le même
     * mode, on ne veut pas le redire à chaque passage.
     */
    fun setForcedMode(ctx: Context, name: String?) {
        val m = if (name.isNullOrBlank()) null else SstvMode.byName(name)
        forced = m
        live?.forcedMode = m
        runCatching { SettingsStore(ctx).sstvForcedMode = m?.name ?: "" }
        _state.value = _state.value.copy(forcedMode = m?.name)
    }

    /**
     * Démarre le décodage tout de suite, sans attendre d'en-tête.
     *
     * Sans mode imposé il n'y a rien à décoder — le mode ne se devine pas — et
     * l'appel ne fait rien. Renvoie vrai quand le décodage est bien engagé.
     */
    fun forceStart(): Boolean {
        val d = live ?: run {
            _state.value = _state.value.copy(erreur = "capture arrêtée")
            return false
        }
        val m = forced ?: run {
            // Un appui sans effet passe pour une panne : mieux vaut dire ce
            // qui manque.
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

    /** Abandonne la trame en cours et se remet à l'écoute. */
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
     * Appelé depuis le fil de capture pour chaque tampon.
     *
     * Une panne du décodeur ne doit pas tuer la capture — d'où le filet — mais
     * elle ne doit pas non plus disparaître : l'écran continuerait d'afficher
     * « à l'écoute » devant un moteur mort. On la retient et on la montre.
     */
    fun feedLive(pcm: ShortArray, count: Int) {
        val d = live ?: return
        runCatching { d.feed(pcm, count) }.onFailure { e ->
            val n = ++pannes
            _state.value = _state.value.copy(
                erreur = e.javaClass.simpleName + (if (n > 1) " ×$n" else ""))
        }
    }

    /** Combien de fois le décodeur a lâché depuis le début de l'écoute. */
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
     * Writes the picture as a PNG, drops a small sidecar next to it and mirrors
     * the image into the export folder.
     *
     * The sidecar is what turns a folder of pictures into an archive: six
     * months later the operator wants to know which pass a picture came from,
     * and where he was standing when he took it. The file name keeps carrying
     * satellite, UTC stamp and mode so that a picture shared by e-mail still
     * says what it is; the rest lives beside it.
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
            // Une image d'avant les fichiers annexes n'a pas d'horodatage
            // lisible dans son nom : la date du fichier fera l'affaire.
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
