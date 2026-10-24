/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.RapportPdf
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.WeakHashMap
import javax.imageio.ImageIO

/**
 * The report's drawing ([RapportPdf.Toile]) on a Java2D picture: the
 * journal's videos and pictures draw the report's panels with it
 * ([fr.f4ioz.satcombo.sstv.ElementsJournal]), in DejaVu Sans as the PDF
 * (✓ ✗ ≈ ° → in it), measured exactly.
 */
class ToileJava2dPc(private val g: Graphics2D) : RapportPdf.Toile, RapportPdf.Mesure {

    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    companion object {
        private fun charge(nom: String): Font? = runCatching {
            ToileJava2dPc::class.java.getResourceAsStream("/fr/f4ioz/satcombo/pc/polices/$nom")?.use { Font.createFont(Font.TRUETYPE_FONT, it) }
        }.getOrNull()
        private val normale: Font by lazy { charge("DejaVuSans.ttf") ?: Font(Font.SANS_SERIF, Font.PLAIN, 1) }
        private val grasse: Font by lazy { charge("DejaVuSans-Bold.ttf") ?: Font(Font.SANS_SERIF, Font.BOLD, 1) }
        private val frc = FontRenderContext(null, true, true)
        fun police(taille: Float, gras: Boolean): Font = (if (gras) grasse else normale).deriveFont(taille.coerceAtLeast(0.5f))

        // The pictures decoded once (the same bytes come back every frame).
        private val images = WeakHashMap<ByteArray, BufferedImage?>()
        fun image(o: ByteArray): BufferedImage? = synchronized(images) {
            if (images.containsKey(o)) images[o] else runCatching { ImageIO.read(ByteArrayInputStream(o)) }.getOrNull().also { images[o] = it }
        }
    }

    private fun couleur(argb: Int) = Color(argb, true)

    override fun largeur(s: String, taille: Float, gras: Boolean): Float = police(taille, gras).getStringBounds(s, frc).width.toFloat()

    override fun taille(image: ByteArray): Pair<Int, Int>? =
        fr.f4ioz.satcombo.sstv.PlancheFichier.dimensions(image) ?: image(image)?.let { it.width to it.height }

    private fun trait(c: Int, ep: Float, tirets: FloatArray?, rond: Boolean) {
        g.color = couleur(c)
        g.stroke = BasicStroke(ep.coerceAtLeast(0.1f), if (rond) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT,
            if (rond) BasicStroke.JOIN_ROUND else BasicStroke.JOIN_MITER, 10f, tirets?.takeIf { t -> t.isNotEmpty() && t.all { it > 0f } }, 0f)
    }

    private fun peint(forme: java.awt.Shape, fond: Int?, bord: Int?, ep: Float, tirets: FloatArray?, rond: Boolean) {
        fond?.let { g.color = couleur(it); g.fill(forme) }
        bord?.let { trait(it, ep, tirets, rond); g.draw(forme) }
    }

    override fun texte(s: String, x: Float, y: Float, taille: Float, couleur: Int, gras: Boolean) {
        if (s.isBlank()) return
        g.font = police(taille, gras); g.color = couleur(couleur)
        g.drawString(s, x, y)
    }

    override fun ligne(xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, ferme: Boolean, fond: Int?) {
        if (xy.size < 4) return
        val p = Path2D.Float()
        p.moveTo(xy[0], xy[1])
        var i = 2
        while (i + 1 < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
        if (ferme) p.closePath()
        peint(p, fond, couleur, epaisseur, pointilles, rond)
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, fond: Int?, trait: Int?, epaisseur: Float, rayon: Float, pointilles: FloatArray?) {
        if (w <= 0f || h <= 0f) return
        val rr = rayon.coerceAtMost(minOf(w, h) / 2)
        val f = if (rr > 0f) RoundRectangle2D.Float(x, y, w, h, 2 * rr, 2 * rr) else Rectangle2D.Float(x, y, w, h)
        peint(f, fond, trait, epaisseur, pointilles, false)
    }

    override fun cercle(x: Float, y: Float, r: Float, fond: Int?, trait: Int?, epaisseur: Float) {
        if (r <= 0f) return
        peint(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r), fond, trait, epaisseur, null, false)
    }

    override fun image(octets: ByteArray, x: Float, y: Float, w: Float, h: Float) {
        val b = image(octets) ?: return
        g.drawImage(b, Math.round(x), Math.round(y), Math.round(w).coerceAtLeast(1), Math.round(h).coerceAtLeast(1), null)
    }

    override fun chemin(ops: CharArray, xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, fond: Int?) {
        if (ops.isEmpty()) return
        val p = Path2D.Float()
        var i = 0
        for (o in ops) when (o) {
            'M' -> { p.moveTo(xy[i], xy[i + 1]); i += 2 }
            'L' -> { p.lineTo(xy[i], xy[i + 1]); i += 2 }
            'C' -> { p.curveTo(xy[i], xy[i + 1], xy[i + 2], xy[i + 3], xy[i + 4], xy[i + 5]); i += 6 }
            'Z' -> p.closePath()
        }
        peint(p, fond, couleur, epaisseur, pointilles, rond)
    }
}
