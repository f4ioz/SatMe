/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.QslSstv
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The QSL picture drawn on the PC (Java2D) from [QslSstv]'s layout. The
 * picture is read, never written: the card is new bytes, PNG or JPEG.
 */
object QslRenduPc {

    /** The card of [png] as [infos] say, laid out [d]; null when the picture cannot be read. */
    fun rend(png: File, infos: QslSstv.Infos, d: QslSstv.Disposition, jpeg: Boolean): ByteArray? {
        val src = runCatching { ImageIO.read(png) }.getOrNull() ?: return null
        return rend(src, infos, d, jpeg)
    }

    fun rend(src: BufferedImage, infos: QslSstv.Infos, d: QslSstv.Disposition, jpeg: Boolean): ByteArray {
        val m = QslSstv.mise(src.width, src.height, infos, d)
        val out = BufferedImage(m.largeur, m.hauteur, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color(0x0E1A2B)
            g.fillRect(0, 0, m.largeur, m.hauteur)
            g.drawImage(src, m.image.x.toInt(), m.image.y.toInt(), Math.round(m.image.l), Math.round(m.image.h), null)
            for (r in m.fonds) {
                g.color = Color(r.couleur, true)
                g.fillRect(Math.round(r.x), Math.round(r.y), Math.round(r.l), Math.round(r.h))
            }
            for (t in m.textes) {
                if (t.texte.isBlank()) continue
                var taille = t.taille
                var f = Font(Font.SANS_SERIF, if (t.gras) Font.BOLD else Font.PLAIN, 1).deriveFont(taille)
                // Shrunk to its width: a long satellite name never runs off the card.
                while (g.getFontMetrics(f).stringWidth(t.texte) > t.maxL && taille > 6f) { taille *= 0.94f; f = f.deriveFont(taille) }
                g.font = f
                g.color = Color(t.couleur, true)
                val fm = g.fontMetrics
                val l = fm.stringWidth(t.texte)
                val x = if (t.align == QslSstv.Align.DROITE) t.x - l else t.x
                g.drawString(t.texte, x, t.y + fm.ascent)
            }
        } finally { g.dispose() }
        val o = ByteArrayOutputStream()
        if (jpeg) {
            val w = ImageIO.getImageWritersByFormatName("jpg").next()
            val p = w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.92f }
            ImageIO.createImageOutputStream(o).use { s -> w.output = s; w.write(null, IIOImage(out, null, null), p) }
            w.dispose()
        } else ImageIO.write(out, "png", o)
        return o.toByteArray()
    }
}
