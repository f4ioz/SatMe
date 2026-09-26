/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Le pont entre le décodeur APT et le reste de l'application.
 *
 * Même dessin que pour la SSTV, et pour les mêmes raisons : le son vient du
 * fil de capture du service d'enregistrement, l'image doit arriver sur un écran
 * Compose qui n'est peut-être même pas affiché, et il n'y a jamais qu'un seul
 * récepteur à la fois.
 *
 * Une différence de taille avec la SSTV : une image APT n'a ni début ni fin
 * annoncés. Le satellite émet en continu tant qu'il est en vue, et l'image est
 * simplement ce qui a été reçu entre le lever et le coucher — dix à quinze
 * minutes, soit jusqu'à deux mille lignes. On garde donc les lignes brutes au
 * fil de l'eau et on ne fabrique l'image qu'au moment de l'afficher ou de
 * l'écrire, ce qui permet de recalculer le contraste sur l'ensemble du passage
 * plutôt que ligne par ligne — sans quoi l'image serait zébrée dès qu'un nuage
 * passe.
 */
object AptHub {

    /** Environ dix-sept minutes. Au-delà, le satellite est couché depuis un moment. */
    const val MAX_LINES = 2000

    data class AptState(
        /** Le décodeur est branché sur la prise de son. */
        val listening: Boolean = false,
        /** Une ligne a été trouvée et le rythme est tenu. */
        val locked: Boolean = false,
        /** Qualité de la dernière salve de synchronisation, 0 à 1. */
        val quality: Float = 0f,
        /** Lignes reçues depuis le début de l'écoute. */
        val lines: Int = 0,
        /** Aperçu réduit, rafraîchi une fois par seconde. */
        val preview: Bitmap? = null,
        /** Nom du dernier PNG écrit. */
        val lastSaved: String? = null,
        /** Images écrites depuis le démarrage. */
        val savedCount: Int = 0,
        /** 0 à 1 pendant la relecture d'un enregistrement, -1 au repos. */
        val fileProgress: Float = -1f,
        /** Fichier en cours de relecture, ou le dernier traité. */
        val fileName: String? = null,
        /** Images trouvées lors de la dernière relecture. */
        val fileImages: Int = 0,
        /** Le satellite sur lequel l'écoute a été lancée. */
        val satName: String = ""
    )

    private val _state = MutableStateFlow(AptState())
    val state: StateFlow<AptState> = _state

    /** Locator de la station, tenu à jour par le modèle. */
    @Volatile var qthLocator: String = ""

    /** Où vivent les images décodées. */
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "apt").apply { mkdirs() }

    fun images(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ------------------------------------------------------------ écoute directe

    private var live: AptDecoder? = null
    private var liveCtx: Context? = null
    private var liveSat: String = "NOAA"
    private val liveLines = ArrayList<FloatArray>()
    private var lastPreviewMs = 0L

    /** Branche le décodeur sur une capture tournant à [sampleRate]. */
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

    /** Appelé depuis le fil de capture pour chaque paquet d'échantillons. */
    fun feedLive(pcm: ShortArray, count: Int) {
        val d = live ?: return
        runCatching { d.feed(pcm, count) }
    }

    /** Coupe l'écoute et écrit ce qui a été reçu. */
    @Synchronized
    fun stopLive() {
        val d = live
        if (d != null) runCatching { d.finish() }
        live = null
        val ctx = liveCtx
        // Moins de trente lignes, c'est quinze secondes : un bout de bruit,
        // pas une image. Au-delà, même tronquée, elle vaut d'être gardée.
        if (ctx != null && liveLines.size >= 30) {
            val name = save(ctx, ArrayList(liveLines), liveSat, "live")
            _state.value = _state.value.copy(
                lastSaved = name ?: _state.value.lastSaved,
                savedCount = _state.value.savedCount + (if (name != null) 1 else 0))
        }
        liveLines.clear()
        // L'aperçu reste : l'écran APT complet le montre comme dernière image
        // reçue. C'est la bande de la page du passage qui, elle, disparaît —
        // là-bas une image figée laisserait croire qu'on reçoit encore.
        _state.value = _state.value.copy(listening = false, locked = false, quality = 0f)
    }

    /**
     * Écrit tout de suite ce qui est reçu, sans couper l'écoute.
     *
     * Un passage NOAA dure un quart d'heure pendant lequel le téléphone est
     * dehors, batterie qui descend et système qui peut décider de tuer
     * l'application. Pouvoir mettre l'image à l'abri en cours de route évite de
     * tout perdre pour une mauvaise raison.
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

    // ------------------------------------------------------------ relecture

    @Volatile private var cancelFile = false

    /** Demande à une relecture en cours d'abandonner. */
    fun cancelFileDecode() { cancelFile = true }

    /**
     * Relit un enregistrement déjà fait. Bloquant : à appeler hors du fil principal.
     *
     * C'est ce qui rend la fonction utilisable : pendant le passage l'opérateur
     * tient l'antenne, et il trie ses images une fois rentré. Un enregistrement
     * fait avant même que le décodage APT existe reste exploitable.
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
     * Fabrique l'image à partir des lignes brutes.
     *
     * Les bornes de contraste sont reprises sur l'ensemble des lignes à chaque
     * fabrication : une image APT s'éclaircit et s'assombrit au fil du passage,
     * selon l'angle du soleil et l'affaiblissement du signal, et un étalonnage
     * figé sur les premières lignes donnerait une moitié d'image brûlée.
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

    /** Aperçu allégé : un mot sur deux, et jamais plus de neuf cents lignes. */
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
        // Une image complète ferait deux mille lignes ; en dessous de trois
        // cents, le passage a été pris en route ou coupé, et l'archive doit le
        // dire plutôt que de laisser croire à une image entière.
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

    /** Chaque image enregistrée avec ce qu'on en sait, la plus récente devant. */
    fun shots(ctx: Context): List<Pair<File, SstvMeta.SstvShot>> =
        images(ctx).map { f ->
            val side = File(f.parentFile, SstvMeta.sidecarName(f.name))
            val text = runCatching { if (side.isFile) side.readText(Charsets.UTF_8) else null }
                .getOrNull()
            var shot = SstvMeta.decode(f.name, text)
            if (shot.timeMs <= 0L) shot = shot.copy(timeMs = f.lastModified())
            f to shot
        }

    /** Supprime une image et son fichier annexe ensemble. */
    fun delete(file: File): Boolean {
        runCatching { File(file.parentFile, SstvMeta.sidecarName(file.name)).delete() }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** Même dossier d'export que les enregistrements audio, s'il est configuré. */
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
