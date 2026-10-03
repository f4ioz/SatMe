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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A short video of one picture arriving: its own sound, and the picture
 * drawn line by line in step with it, as it was received. To share.
 */
object SstvVideo {

    const val FPS = 5
    /** Light (to send by message) or HD (sharper, about six times heavier). */
    private const val LARGEUR = 640
    private const val BANDEAU = 52
    private const val DEBIT_LEGER = 500_000
    private const val DEBIT_HD = 3_000_000
    private const val RATE_AAC = 44_100
    private const val ATTENTE_US = 10_000L

    // ------------------------------------------------------------ pure part

    /**
     * Where the picture stands along its sound: (samples, lines done), found
     * by decoding the sound again. Empty when nothing decodes.
     */
    fun calendrier(pcm: ShortArray, rate: Int): List<Pair<Long, Int>> {
        val jalons = ArrayList<Pair<Long, Int>>()
        var pos = 0L
        val d = SstvDecoder(rate, object : SstvDecoder.Listener {
            override fun onProgress(mode: SstvMode, pixels: IntArray, linesDone: Int) {
                jalons += pos to linesDone
            }
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                jalons += pos to linesDone
            }
        })
        d.continuous = true
        val bloc = ShortArray(1024)
        var i = 0
        while (i < pcm.size) {
            val n = minOf(bloc.size, pcm.size - i)
            System.arraycopy(pcm, i, bloc, 0, n)
            i += n; pos = i.toLong()
            d.feed(bloc, n)
        }
        runCatching { d.finish() }
        // Only the first picture of the sound, its lines growing.
        val premier = ArrayList<Pair<Long, Int>>()
        for (j in jalons) { if (premier.isNotEmpty() && j.second < premier.last().second) break; premier += j }
        return premier
    }

    /** Lines shown [echantillon] samples into a sound of [total] samples. */
    fun lignesA(cal: List<Pair<Long, Int>>, echantillon: Long, total: Long, hauteur: Int): Int {
        if (cal.isEmpty()) {
            // Nothing decoded: a steady progression over the sound.
            return if (total <= 0) hauteur else (echantillon * hauteur / total).toInt().coerceIn(0, hauteur)
        }
        var l = 0
        for ((p, n) in cal) { if (p <= echantillon) l = n else break }
        return l.coerceIn(0, hauteur)
    }

    // --------------------------------------------------------- the video

    /** The video of [png], next to it, or null if it cannot be made. */
    fun fabrique(ctx: Context, png: File, shot: SstvMeta.SstvShot, hd: Boolean = false,
                 progres: (Float) -> Unit = {}): File? = runCatching {
        val k = if (hd) 2 else 1
        val (pcm, rate) = SstvSon.litWav(SstvSon.fichier(png)) ?: return null
        val image = BitmapFactory.decodeFile(png.absolutePath) ?: return null
        val sortie = SstvSon.video(png, hd)
        val cal = calendrier(pcm, rate)

        val w = LARGEUR * k
        val hImage = (image.height * w / image.width + 1) and 1.inv()
        val h = ((hImage + BANDEAU * k + 15) / 16) * 16
        val logo = runCatching { ctx.packageManager.getApplicationIcon(ctx.applicationInfo) }.getOrNull()
        val pleine = cadre(image, shot, w, h, hImage, true, logo, k.toFloat())
        val vide = cadre(image, shot, w, h, hImage, false, logo, k.toFloat())

        val (nomCodec, couleur, cbr) = encodeurVideo() ?: return null
        val nv12 = couleur == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        val yuvPleine = yuv(pleine, nv12)
        val yuvVide = yuv(vide, nv12)

        // The sound first, in memory: the muxer wants every track before it starts.
        val audio = encodeAudio(SstvSon.reechantillonne(pcm, rate, RATE_AAC))
        progres(0.1f)

        val mux = MediaMuxer(sortie.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, couleur)
            setInteger(MediaFormat.KEY_BIT_RATE, if (hd) DEBIT_HD else DEBIT_LEGER)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // A steady rate keeps the file small enough to send by message.
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val enc = MediaCodec.createByCodecName(nomCodec)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val trame = ByteArray(w * h * 3 / 2)
        val images = ((pcm.size.toLong() * FPS + rate - 1) / rate).toInt().coerceAtLeast(1)
        var piste = -1
        var enCours = true
        var envoyees = 0
        val info = MediaCodec.BufferInfo()
        try {
            while (enCours) {
                if (envoyees <= images) {
                    val ib = enc.dequeueInputBuffer(ATTENTE_US)
                    if (ib >= 0) {
                        val t = envoyees * 1_000_000L / FPS
                        if (envoyees == images) {
                            enc.queueInputBuffer(ib, 0, 0, t, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val lignes = lignesA(cal, envoyees.toLong() * rate / FPS, pcm.size.toLong(), image.height)
                            compose(trame, yuvPleine, yuvVide, w, h, hImage * lignes / image.height,
                                lignes < image.height, nv12)
                            enc.getInputBuffer(ib)!!.apply { clear(); put(trame) }
                            enc.queueInputBuffer(ib, 0, trame.size, t, 0)
                            progres(0.1f + 0.9f * envoyees / images)
                        }
                        envoyees++
                    }
                }
                when (val ob = enc.dequeueOutputBuffer(info, ATTENTE_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        piste = mux.addTrack(enc.outputFormat)
                        val pisteSon = audio?.let { mux.addTrack(it.first) }
                        mux.start()
                        if (pisteSon != null) for ((donnees, i) in audio!!.second)
                            mux.writeSampleData(pisteSon, ByteBuffer.wrap(donnees), i)
                    }
                    else -> if (ob >= 0) {
                        val buf = enc.getOutputBuffer(ob)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && piste >= 0)
                            mux.writeSampleData(piste, buf, info)
                        enc.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) enCours = false
                    }
                }
            }
        } finally {
            runCatching { enc.stop() }; runCatching { enc.release() }
            runCatching { mux.stop() }; runCatching { mux.release() }
        }
        progres(1f)
        sortie.takeIf { it.length() > 0 }
    }.getOrNull()

    /**
     * The picture with the same caption as the video below it (satellite,
     * mode, time of reception, locator, callsign, SatMe), for sharing or
     * saving. At least 640 pixels wide, so the caption stays readable.
     */
    fun imageAvecBandeau(ctx: Context, png: File, shot: SstvMeta.SstvShot): File? = runCatching {
        val image = BitmapFactory.decodeFile(png.absolutePath) ?: return null
        val w = maxOf(LARGEUR, image.width)
        val k = w / LARGEUR.toFloat()
        val hImage = image.height * w / image.width
        val logo = runCatching { ctx.packageManager.getApplicationIcon(ctx.applicationInfo) }.getOrNull()
        val b = cadre(image, shot, w, hImage + (BANDEAU * k).toInt(), hImage, true, logo, k)
        val f = File(File(ctx.cacheDir, "export").apply { mkdirs() }, png.name.removeSuffix(".png") + "_info.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f
    }.getOrNull()

    /** An AVC encoder taking NV12 or I420 frames, the simplest to fill. */
    private fun encodeurVideo(): Triple<String, Int, Boolean>? {
        val voulus = listOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes.map { t -> t.lowercase() } }
        for (c in voulus) for (i in codecs) {
            val caps = runCatching { i.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: continue
            if (c in caps.colorFormats) return Triple(i.name, c, runCatching {
                caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }.getOrDefault(false))
        }
        return null
    }

    /** The whole sound as AAC: its format and its samples. */
    private fun encodeAudio(pcm: ShortArray): Pair<MediaFormat, List<Pair<ByteArray, MediaCodec.BufferInfo>>>? = runCatching {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE_AAC, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val sorties = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var format: MediaFormat? = null
        var pos = 0
        var finEnvoyee = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!finEnvoyee) {
                    val ib = enc.dequeueInputBuffer(ATTENTE_US)
                    if (ib >= 0) {
                        val buf = enc.getInputBuffer(ib)!!
                        buf.clear()
                        val n = minOf(buf.remaining() / 2, pcm.size - pos, 4096)
                        val t = pos * 1_000_000L / RATE_AAC
                        if (n <= 0) {
                            enc.queueInputBuffer(ib, 0, 0, t, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            finEnvoyee = true
                        } else {
                            val bb = ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.nativeOrder())
                            for (k in 0 until n) bb.putShort(pcm[pos + k])
                            buf.put(bb.array())
                            enc.queueInputBuffer(ib, 0, n * 2, t, 0)
                            pos += n
                        }
                    }
                }
                val ob = enc.dequeueOutputBuffer(info, ATTENTE_US)
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = enc.outputFormat
                else if (ob >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buf = enc.getOutputBuffer(ob)!!
                        val d = ByteArray(info.size)
                        buf.position(info.offset); buf.get(d)
                        val copie = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        sorties += d to copie
                    }
                    enc.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { enc.stop() }; runCatching { enc.release() }
        }
        (format ?: return null) to sorties
    }.getOrNull()

    /**
     * One frame: the picture (or its empty frame) above a caption — when it
     * was received (not decoded again), and SatMe with its logo.
     */
    private fun cadre(image: Bitmap, shot: SstvMeta.SstvShot, w: Int, h: Int, hImage: Int, avecImage: Boolean,
                      logo: android.graphics.drawable.Drawable?, k: Float): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(11, 16, 24))
        if (avecImage) c.drawBitmap(image, null, Rect(0, 0, w, hImage), Paint(Paint.FILTER_BITMAP_FLAG))
        else c.drawRect(Rect(0, 0, w, hImage), Paint().apply { color = Color.rgb(16, 20, 24) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f * k }
        val quand = if (shot.timeMs > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(shot.timeMs)) + " UTC" else ""
        val texte = listOf(shot.satName, shot.mode, quand, shot.locator, shot.callsign)
            .filter { it.isNotBlank() }.joinToString(" · ")
        c.drawText(texte, 12f * k, hImage + 34f * k, p)
        val marque = Paint(p).apply { color = Color.rgb(0, 229, 255); textAlign = Paint.Align.RIGHT; isFakeBoldText = true }
        // The launcher icon keeps a margin around its drawing: drawn large.
        val xMarque = if (logo != null) w - 62f * k else w - 12f * k
        c.drawText("SatMe", xMarque, hImage + 34f * k, marque)
        logo?.let {
            it.setBounds((w - 58f * k).toInt(), (hImage + 3f * k).toInt(), (w - 12f * k).toInt(), (hImage + 49f * k).toInt())
            it.draw(c)
        }
        return b
    }

    /** ARGB to YUV 4:2:0, NV12 or I420. */
    private fun yuv(b: Bitmap, nv12: Boolean): ByteArray {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3 / 2)
        for (i in px.indices) {
            val c = px[i]
            out[i] = yDe(c).toByte()
        }
        for (cy in 0 until h / 2) for (cx in 0 until w / 2) {
            var r = 0; var g = 0; var bl = 0
            for (dy in 0..1) for (dx in 0..1) {
                val c = px[(cy * 2 + dy) * w + cx * 2 + dx]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF
            }
            r /= 4; g /= 4; bl /= 4
            val u = (((-38 * r - 74 * g + 112 * bl + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            val v = (((112 * r - 94 * g - 18 * bl + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            if (nv12) {
                val o = w * h + cy * w + cx * 2
                out[o] = u; out[o + 1] = v
            } else {
                out[w * h + cy * (w / 2) + cx] = u
                out[w * h + w * h / 4 + cy * (w / 2) + cx] = v
            }
        }
        return out
    }

    private fun yDe(c: Int): Int {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
    }

    /**
     * Picture rows above [coupe] from the full frame, the rest from the empty
     * one, and a bright line where the picture is being drawn.
     */
    private fun compose(dst: ByteArray, pleine: ByteArray, vide: ByteArray, w: Int, h: Int, coupe: Int,
                        curseur: Boolean, nv12: Boolean) {
        for (y in 0 until h) {
            val src = if (y < coupe) pleine else vide
            System.arraycopy(src, y * w, dst, y * w, w)
        }
        val ch = h / 2
        for (cy in 0 until ch) {
            val src = if (cy * 2 < coupe) pleine else vide
            if (nv12) System.arraycopy(src, w * h + cy * w, dst, w * h + cy * w, w)
            else {
                System.arraycopy(src, w * h + cy * (w / 2), dst, w * h + cy * (w / 2), w / 2)
                System.arraycopy(src, w * h + w * h / 4 + cy * (w / 2), dst, w * h + w * h / 4 + cy * (w / 2), w / 2)
            }
        }
        if (curseur) {
            val yv = 235.toByte()
            for (y in coupe until minOf(coupe + 2, h)) java.util.Arrays.fill(dst, y * w, y * w + w, yv)
        }
    }
}
