/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.SstvMeta
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Draws an SSTV sheet ([Planche.Modele]) on the PC: the phone's drawing
 * (PlancheRendu), stroke for stroke, with Java2D — the same sheet whichever
 * draws it.
 */
object PlancheRenduPc {

    /** What the texts say. */
    data class Valeurs(val indicatif: String = "", val nom: String = "", val locator: String = "", val dates: String = "", val titre: String = "") {
        fun de(t: Planche.Texte): String = when (t.champ) {
            Planche.Champ.INDICATIF -> indicatif
            Planche.Champ.NOM_LOCATOR -> listOf(nom, if (nom.isNotBlank() && locator.isNotBlank()) "@ $locator" else locator)
                .filter { it.isNotBlank() }.joinToString("\n")
            Planche.Champ.DATES -> dates
            Planche.Champ.TITRE -> titre
            Planche.Champ.LIBRE -> t.libre
        }
    }

    /** A picture as JPEG, at quality [q]. */
    fun jpeg(b: BufferedImage, q: Float = 0.88f): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        val w = ImageIO.getImageWritersByFormatName("jpg").next()
        ImageIO.createImageOutputStream(o).use { s ->
            w.output = s
            val p = w.defaultWriteParam.apply { compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT; compressionQuality = q }
            w.write(null, javax.imageio.IIOImage(b, null, null), p)
        }
        w.dispose()
        return o.toByteArray()
    }

    /** JPEG wants no transparency: a picture with alpha is laid on black first. */
    fun rgb(b: BufferedImage): BufferedImage {
        if (b.type == BufferedImage.TYPE_INT_RGB || b.type == BufferedImage.TYPE_3BYTE_BGR) return b
        val r = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_RGB)
        r.createGraphics().apply { drawImage(b, 0, 0, null); dispose() }
        return r
    }

    /** The panel over a template's own boxes when the sheet is fitted to its pictures (the phone's). */
    const val PANNEAU = 0xF205080D.toInt()

    // Signatures by file: the pictures do not change once written.
    private val signatures = HashMap<String, Planche.Signature>()

    /** [Planche.Signature] of each picture, from a small copy, kept. */
    fun signatures(fichiers: List<File>): Map<String, Planche.Signature> = fichiers.mapNotNull { f ->
        val cle = f.name + "@" + f.lastModified()
        val s = synchronized(signatures) { signatures[cle] } ?: charge(f, 96)?.let { b ->
            Planche.signature(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height)
                .also { synchronized(signatures) { signatures[cle] = it } }
        }
        s?.let { f.name to it }
    }.toMap()

    /** An image file, made no wider than [largeurMax]. */
    fun charge(f: File, largeurMax: Int): BufferedImage? = runCatching {
        val b = ImageIO.read(f) ?: return null
        if (b.width <= largeurMax) b else reduit(b, largeurMax)
    }.getOrNull()

    fun reduit(b: BufferedImage, largeur: Int): BufferedImage {
        val h = (largeur.toLong() * b.height / b.width).toInt().coerceAtLeast(1)
        val r = BufferedImage(largeur, h, BufferedImage.TYPE_INT_RGB)
        r.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(b, 0, 0, largeur, h, null); dispose()
        }
        return r
    }

    private fun couleur(argb: Int) = Color(argb, true)

    /** The sheet [largeur] pixels wide; [images] the pictures by box, with what is known of them. */
    fun dessine(m: Planche.Modele, fond: BufferedImage?, largeur: Int, images: Map<Int, Pair<BufferedImage, SstvMeta.SstvShot>>,
                v: Valeurs, logo: BufferedImage? = MarquePc.icone): BufferedImage {
        val ratio = if (fond != null) fond.width.toFloat() / fond.height else m.ratio
        val w = largeur; val h = (largeur / ratio).toInt().coerceAtLeast(1)
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        if (fond != null) g.drawImage(fond, 0, 0, w, h, null) else fondGenerique(g, w, h)
        val k = w / 1000f
        // Fitted to fewer pictures: the template's printed boxes ("NO IMAGE") hidden under one dark panel.
        if (fond != null && m.ajustee) Planche.englobe(m.origine)?.let { z ->
            g.color = couleur(PANNEAU)
            g.fill(RoundRectangle2D.Float(z.x * w - 6 * k, z.y * h - 6 * k, z.w * w + 12 * k, z.h * h + 12 * k, 20 * k, 20 * k))
        }
        val n = m.cases.size
        m.cases.forEachIndexed { i, z ->
            val r = Rectangle2D.Float(z.x * w, z.y * h, z.w * w, z.h * h)
            val img = images[i]
            if (img != null) {
                // The line under the picture takes its share of the box.
                val bande = if (m.legende) (r.height * Planche.BANDE).coerceAtLeast(10f * k) else 0f
                val ri = Rectangle2D.Float(r.x, r.y, r.width, r.height - bande)
                dessineRempli(g, img.first, ri)
                if (bande > 0f) {
                    g.color = Color.WHITE; g.fill(Rectangle2D.Float(r.x, ri.y + ri.height, r.width, bande))
                    texteAjuste(g, Planche.legende(img.second, v.indicatif), Rectangle2D.Float(r.x + 3 * k, ri.y + ri.height, r.width - 6 * k, bande), Color.BLACK, false)
                }
                g.color = Color.WHITE; g.stroke = BasicStroke(2.5f * k); g.draw(r)
            } else if (fond == null) {
                // A generic sheet shows its empty boxes; an imported one has its own.
                g.color = couleur(0xCC05080D.toInt()); g.fill(r)
                g.color = Color.WHITE; g.stroke = BasicStroke(2.5f * k); g.draw(r)
            }
            if (m.numeros) {
                val etiquette = "${i + 1}/$n"
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(r.height * 0.11f)
                val tw = g.fontMetrics.stringWidth(etiquette)
                val taille = g.font.size2D
                val boite = RoundRectangle2D.Float(r.x + 4 * k, r.y + 4 * k, tw + 10 * k, taille * 1.25f, 8 * k, 8 * k)
                g.color = couleur(0xB0000000.toInt()); g.fill(boite)
                g.color = Color.WHITE; g.drawString(etiquette, boite.x + 5 * k, boite.y + boite.height - taille * 0.3f)
            }
        }
        for (t in m.textes) {
            val r = Rectangle2D.Float(t.zone.x * w, t.zone.y * h, t.zone.w * w, t.zone.h * h)
            if (t.fond != 0) { g.color = couleur(t.fond); g.fill(RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 12 * k, 12 * k)) }
            texteAjuste(g, v.de(t), Rectangle2D.Float(r.x + r.width * 0.04f, r.y, r.width * 0.92f, r.height), couleur(t.couleur), true)
            // The SatMe logo beside the callsign: right of it, else left, else inside — never over a picture.
            if (t.champ == Planche.Champ.INDICATIF && logo != null) {
                val cote = r.height * 1.1f; val ecart = r.height * 0.15f
                val haut = (r.y + r.height / 2) - cote / 2
                fun libre(x: Float): Boolean {
                    if (x < 0 || x + cote > w) return false
                    val l = Rectangle2D.Float(x, haut, cote, cote)
                    return m.cases.none { z -> l.intersects(Rectangle2D.Float(z.x * w, z.y * h, z.w * w, z.h * h)) }
                }
                val gauche = listOf(r.x + r.width + ecart, r.x - ecart - cote).firstOrNull { libre(it) } ?: (r.x + r.width - cote)
                g.drawImage(logo, gauche.toInt(), haut.toInt(), cote.toInt(), cote.toInt(), null)
            }
        }
        g.dispose()
        return b
    }

    /** The picture filling its box, cut to its shape rather than stretched. */
    private fun dessineRempli(g: Graphics2D, img: BufferedImage, r: Rectangle2D.Float) {
        val ri = img.width.toFloat() / img.height; val rr = r.width / r.height
        val (sx, sy, sw, sh) = if (ri > rr) {
            val sw = (img.height * rr).toInt(); listOf((img.width - sw) / 2, 0, sw, img.height)
        } else {
            val sh = (img.width / rr).toInt(); listOf(0, (img.height - sh) / 2, img.width, sh)
        }
        g.drawImage(img, r.x.toInt(), r.y.toInt(), (r.x + r.width).toInt(), (r.y + r.height).toInt(), sx, sy, sx + sw, sy + sh, null)
    }

    /** The text as large as fits its box, lines centred. */
    private fun texteAjuste(g: Graphics2D, texte: String, r: Rectangle2D.Float, c: Color, gras: Boolean) {
        if (texte.isBlank() || r.width <= 2 || r.height <= 2) return
        val lignes = texte.split('\n')
        var taille = r.height / lignes.size * 0.82f
        g.font = Font(Font.SANS_SERIF, if (gras) Font.BOLD else Font.PLAIN, 1).deriveFont(taille)
        val plusLongue = lignes.maxOf { g.fontMetrics.stringWidth(it) }
        if (plusLongue > r.width) { taille *= r.width / plusLongue; g.font = g.font.deriveFont(taille) }
        val pas = taille * 1.15f
        var y = (r.y + r.height / 2) - pas * lignes.size / 2 + pas * 0.78f
        g.color = c
        for (l in lignes) { g.drawString(l, (r.x + r.width / 2) - g.fontMetrics.stringWidth(l) / 2f, y); y += pas }
    }

    /** Night sky: deep blue to black, stars — the phone's generic background. */
    private fun fondGenerique(g: Graphics2D, w: Int, h: Int) {
        g.paint = LinearGradientPaint(0f, 0f, 0f, h.toFloat(), floatArrayOf(0f, 0.7f, 1f),
            arrayOf(Color(0x02040A), Color(0x0A1630), Color(0x123A6B)))
        g.fillRect(0, 0, w, h)
        val r = Random(73)
        repeat(w * h / 4000) {
            g.color = Color(255, 255, 255, r.nextInt(90, 255))
            val rayon = r.nextFloat() * w / 900f + 0.5f
            g.fill(Ellipse2D.Float(r.nextFloat() * w - rayon, r.nextFloat() * h * 0.85f - rayon, 2 * rayon, 2 * rayon))
        }
    }
}
