/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.publication

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageInputStream

/**
 * Pictures as the site takes them: their kind told by their first bytes
 * (never by what the sender says), their size read before they are decoded
 * (a « bomb » of a hundred thousand pixels square is refused before it
 * fills the memory), and a small JPEG to show in the lists.
 */
object Vignettes {

    /** At most this many pixels in a picture sent (a planche of 4000 × 6000 is 24 Mpx). */
    const val PIXELS_MAX = 48_000_000L

    /** The kind of [b] from its first bytes: png, jpg, gif, webp, pdf, mp4, webm; null when none of them. */
    fun genre(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
        b.size >= 6 && String(b, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" } -> "gif"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        b.size >= 5 && String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-" -> "pdf"
        b.size >= 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "mp4"
        b.size >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "webm"
        else -> null
    }

    /** The first bytes of a file, for [genre]. */
    fun debut(f: File, n: Int = 16): ByteArray = f.inputStream().use { i -> val b = ByteArray(n); val k = i.read(b); if (k <= 0) ByteArray(0) else b.copyOf(k) }

    /** Width and height read from the header alone; null when the picture cannot be read. */
    fun dimensions(b: ByteArray): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(b.inputStream()).use { flux: ImageInputStream ->
            val lecteurs = ImageIO.getImageReaders(flux)
            if (!lecteurs.hasNext()) return null
            val l = lecteurs.next()
            try { l.input = flux; l.getWidth(0) to l.getHeight(0) } finally { l.dispose() }
        }
    }.getOrNull()

    /** True when [b] is a picture of an acceptable size. */
    fun imageAcceptable(b: ByteArray): Boolean {
        val (w, h) = dimensions(b) ?: return false
        return w in 1..20_000 && h in 1..20_000 && w.toLong() * h <= PIXELS_MAX
    }

    /** A JPEG of [b] at most [cote] px on its longer side; null when it cannot be decoded. */
    fun vignette(b: ByteArray, cote: Int = 480, qualite: Float = 0.82f): ByteArray? {
        if (!imageAcceptable(b)) return null
        val src = runCatching { ImageIO.read(b.inputStream()) }.getOrNull() ?: return null
        val k = minOf(1.0, cote.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * k).toInt()); val h = maxOf(1, (src.height * k).toInt())
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = java.awt.Color(0x0E, 0x17, 0x20); g.fillRect(0, 0, w, h)
        // Large ones by halves first: a smooth thumbnail without a slow algorithm.
        var cur: BufferedImage = src
        while (cur.width / 2 >= w * 2 && cur.height / 2 >= h * 2) {
            val m = BufferedImage(cur.width / 2, cur.height / 2, BufferedImage.TYPE_INT_RGB)
            val gm = m.createGraphics()
            gm.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            gm.drawImage(cur, 0, 0, m.width, m.height, null); gm.dispose()
            cur = m
        }
        g.drawImage(cur, 0, 0, w, h, null)
        g.dispose()
        return jpeg(dst, qualite)
    }

    fun jpeg(img: BufferedImage, qualite: Float): ByteArray {
        val o = ByteArrayOutputStream()
        val w = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(o).use { s ->
            w.output = s
            val p = w.defaultWriteParam
            p.compressionMode = ImageWriteParam.MODE_EXPLICIT; p.compressionQuality = qualite
            w.write(null, IIOImage(img, null, null), p)
        }
        w.dispose()
        return o.toByteArray()
    }
}
