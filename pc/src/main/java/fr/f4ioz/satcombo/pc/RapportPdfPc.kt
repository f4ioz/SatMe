/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.i18n.Lang
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.RapportEvenement
import fr.f4ioz.satcombo.sstv.RapportPdf
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.IdentityHashMap
import java.util.TimeZone
import javax.imageio.ImageIO

/**
 * The event's report as a PDF on SatMe-PC: [RapportPdf]'s pages drawn with
 * PDFBox (Apache-2.0), in DejaVu Sans (embedded, only the letters used): the
 * accents, ✓ ✗ ◷ ≈ ✎ ° → all in it. The charts stay lines, the pictures stay
 * the JPEGs they came as, the montage's drawing curves and glows (its
 * transparencies as graphics states). Nothing fetched, no browser.
 */
object RapportPdfPc {

    private fun police(nom: String) = RapportPdfPc::class.java.getResourceAsStream("/fr/f4ioz/satcombo/pc/polices/$nom")
        ?: error("police $nom absente")

    fun pdf(r: RapportEvenement.Rapport): ByteArray = PDDocument().use { doc ->
        val normale = police("DejaVuSans.ttf").use { PDType0Font.load(doc, it, true) }
        val grasse = police("DejaVuSans-Bold.ttf").use { PDType0Font.load(doc, it, true) }
        val d = Feuilles(doc, normale, grasse)
        RapportPdf.dessine(r, d)
        d.ferme()
        doc.documentInformation.apply {
            title = tf("rev_titre_page", r.evenement.nom)
            author = listOf(r.station.indicatif, r.station.nom).filter { it.isNotBlank() }.joinToString(" · ")
            subject = r.evenement.satNom
            creator = r.logiciel
            producer = r.logiciel + " (PDFBox)"
            creationDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = r.faitMs }
        }
        doc.documentCatalog.language = if (I18n.current() == Lang.EN) "en" else "fr"
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    /** The pages, one after the other; the y from the top, as [RapportPdf] draws. */
    private class Feuilles(val doc: PDDocument, val normale: PDType0Font, val grasse: PDType0Font) : RapportPdf.Document {
        private var cs: PDPageContentStream? = null
        private val hauteur = PDRectangle.A4.height
        private val images = IdentityHashMap<ByteArray, PDImageXObject?>()

        // A letter the font lacks (an emoji in a name) becomes « ? »: PDFBox refuses it otherwise.
        private val connues = HashMap<Int, Boolean>()
        private fun propre(s: String, f: PDType0Font): String = buildString {
            s.codePoints().forEach { cp ->
                val c = if (cp < 0x20) ' '.code else cp
                val ok = connues.getOrPut(c * 2 + if (f === grasse) 1 else 0) { runCatching { f.encode(String(Character.toChars(c))) }.isSuccess }
                if (ok) appendCodePoint(c) else append('?')
            }
        }

        override fun largeur(s: String, taille: Float, gras: Boolean): Float {
            val f = if (gras) grasse else normale
            return runCatching { f.getStringWidth(propre(s, f)) / 1000f * taille }.getOrDefault(s.length * taille * 0.55f)
        }

        override fun taille(image: ByteArray): Pair<Int, Int>? = runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(image)).use { iis ->
                val rd = ImageIO.getImageReaders(iis).next()
                try { rd.input = iis; rd.getWidth(0) to rd.getHeight(0) } finally { rd.dispose() }
            }
        }.getOrNull()

        fun ferme() { cs?.close(); cs = null }

        override fun page(): RapportPdf.Toile {
            ferme()
            val p = PDPage(PDRectangle.A4)
            doc.addPage(p)
            val c = PDPageContentStream(doc, p)
            cs = c
            return Toile(c)
        }

        /** The transparencies used, one graphics state each (the montage's glows). */
        private val alphas = HashMap<Pair<Int, Boolean>, PDExtendedGraphicsState>()

        private inner class Toile(val c: PDPageContentStream) : RapportPdf.Toile {
            private fun y(v: Float) = hauteur - v
            // The alpha in force on this page, for strokes and for fills.
            private var aTrait = 255
            private var aFond = 255
            private fun couleur(argb: Int, fond: Boolean) {
                val r = (argb shr 16 and 0xFF) / 255f; val g = (argb shr 8 and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
                if (fond) c.setNonStrokingColor(r, g, b) else c.setStrokingColor(r, g, b)
                val a = (argb ushr 24) and 0xFF
                if (a != (if (fond) aFond else aTrait)) {
                    val gs = alphas.getOrPut(a to fond) {
                        PDExtendedGraphicsState().apply { if (fond) nonStrokingAlphaConstant = a / 255f else strokingAlphaConstant = a / 255f }
                    }
                    c.setGraphicsStateParameters(gs)
                    if (fond) aFond = a else aTrait = a
                }
            }
            private fun trait(couleur: Int, epaisseur: Float, pointilles: FloatArray?, rond: Boolean) {
                couleur(couleur, false)
                c.setLineWidth(epaisseur.coerceAtLeast(0.1f))
                c.setLineDashPattern(pointilles ?: FloatArray(0), 0f)
                c.setLineCapStyle(if (rond) 1 else 0); c.setLineJoinStyle(if (rond) 1 else 0)
            }
            private fun peint(fond: Int?, bord: Int?) = when {
                fond != null && bord != null -> c.fillAndStroke()
                fond != null -> c.fill()
                else -> c.stroke()
            }

            override fun texte(s: String, x: Float, y: Float, taille: Float, couleur: Int, gras: Boolean) {
                val f = if (gras) grasse else normale
                val t = propre(s, f)
                if (t.isBlank()) return
                c.beginText(); c.setFont(f, taille); couleur(couleur, true)
                c.newLineAtOffset(x, y(y)); c.showText(t); c.endText()
            }

            override fun ligne(xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, ferme: Boolean, fond: Int?) {
                if (xy.size < 4 || couleur == null && fond == null) return
                couleur?.let { trait(it, epaisseur, pointilles, rond) }
                fond?.let { couleur(it, true) }
                c.moveTo(xy[0], y(xy[1]))
                var i = 2
                while (i + 1 < xy.size) { c.lineTo(xy[i], y(xy[i + 1])); i += 2 }
                if (ferme) c.closePath()
                peint(fond, couleur)
            }

            override fun rect(x: Float, y: Float, w: Float, h: Float, fond: Int?, trait: Int?, epaisseur: Float, rayon: Float, pointilles: FloatArray?) {
                if (w <= 0f || h <= 0f || fond == null && trait == null) return
                trait?.let { trait(it, epaisseur, pointilles, false) }
                fond?.let { couleur(it, true) }
                val rr = rayon.coerceAtMost(minOf(w, h) / 2)
                if (rr <= 0f) c.addRect(x, y(y + h), w, h)
                else {
                    // The corners as quarter circles (Bézier, 0.552).
                    val k = rr * 0.5523f
                    val x1 = x + w; val yb = y(y + h); val yh = y(y)
                    c.moveTo(x + rr, yb)
                    c.lineTo(x1 - rr, yb); c.curveTo(x1 - rr + k, yb, x1, yb + rr - k, x1, yb + rr)
                    c.lineTo(x1, yh - rr); c.curveTo(x1, yh - rr + k, x1 - rr + k, yh, x1 - rr, yh)
                    c.lineTo(x + rr, yh); c.curveTo(x + rr - k, yh, x, yh - rr + k, x, yh - rr)
                    c.lineTo(x, yb + rr); c.curveTo(x, yb + rr - k, x + rr - k, yb, x + rr, yb)
                    c.closePath()
                }
                peint(fond, trait)
            }

            override fun cercle(x: Float, y: Float, r: Float, fond: Int?, trait: Int?, epaisseur: Float) {
                if (r <= 0f || fond == null && trait == null) return
                trait?.let { trait(it, epaisseur, null, false) }
                fond?.let { couleur(it, true) }
                val k = r * 0.5523f; val cy = y(y)
                c.moveTo(x + r, cy)
                c.curveTo(x + r, cy + k, x + k, cy + r, x, cy + r)
                c.curveTo(x - k, cy + r, x - r, cy + k, x - r, cy)
                c.curveTo(x - r, cy - k, x - k, cy - r, x, cy - r)
                c.curveTo(x + k, cy - r, x + r, cy - k, x + r, cy)
                c.closePath()
                peint(fond, trait)
            }

            override fun image(octets: ByteArray, x: Float, y: Float, w: Float, h: Float) {
                val im = images.getOrPut(octets) { runCatching { PDImageXObject.createFromByteArray(doc, octets, "image") }.getOrNull() } ?: return
                // A picture drawn whole, whatever transparency the shapes before it left.
                if (aFond != 255) couleur(0xFF000000.toInt(), true)
                c.drawImage(im, x, y(y + h), w, h)
            }

            override fun chemin(ops: CharArray, xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, fond: Int?) {
                if (ops.isEmpty() || couleur == null && fond == null) return
                couleur?.let { trait(it, epaisseur, pointilles, rond) }
                fond?.let { couleur(it, true) }
                var i = 0
                for (o in ops) when (o) {
                    'M' -> { c.moveTo(xy[i], y(xy[i + 1])); i += 2 }
                    'L' -> { c.lineTo(xy[i], y(xy[i + 1])); i += 2 }
                    'C' -> { c.curveTo(xy[i], y(xy[i + 1]), xy[i + 2], y(xy[i + 3]), xy[i + 4], y(xy[i + 5])); i += 6 }
                    'Z' -> c.closePath()
                }
                peint(fond, couleur)
            }
        }
    }
}
