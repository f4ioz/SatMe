/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap

/**
 * The event's report as a PDF on the phone: [RapportPdf]'s pages drawn on
 * the platform's own PdfDocument (no library, no WebView), in the phone's
 * font (its fallbacks give ✓ ✗ ◷ ≈ ✎). The charts stay lines (the colours'
 * alpha kept, the curves drawn as curves); the pictures
 * are drawn as bitmaps (Android keeps them lossless: a bigger file than
 * SatMe-PC's).
 */
object RapportPdfTelephone {

    fun pdf(r: RapportEvenement.Rapport): ByteArray {
        val doc = PdfDocument()
        val d = Feuilles(doc)
        try {
            RapportPdf.dessine(r, d)
            d.ferme()
            return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        } finally {
            d.libere()
            doc.close()
        }
    }

    private class Feuilles(val doc: PdfDocument) : RapportPdf.Document {
        private var page: PdfDocument.Page? = null
        private var n = 0
        private val bitmaps = IdentityHashMap<ByteArray, Bitmap?>()
        private val texte = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fond = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val image = Paint(Paint.FILTER_BITMAP_FLAG)

        private fun police(taille: Float, gras: Boolean) = texte.apply {
            textSize = taille; typeface = if (gras) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        override fun largeur(s: String, taille: Float, gras: Boolean): Float = police(taille, gras).measureText(s)

        override fun taille(image: ByteArray): Pair<Int, Int>? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, o)
            return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }

        fun ferme() { page?.let { doc.finishPage(it) }; page = null }
        fun libere() { bitmaps.values.forEach { it?.recycle() }; bitmaps.clear() }

        override fun page(): RapportPdf.Toile {
            ferme()
            val p = doc.startPage(PdfDocument.PageInfo.Builder(RapportPdf.LARGEUR.toInt(), RapportPdf.HAUTEUR.toInt(), ++n).create())
            page = p
            return Toile(p.canvas)
        }

        private inner class Toile(val c: Canvas) : RapportPdf.Toile {
            private fun trace(couleur: Int, epaisseur: Float, pointilles: FloatArray?, rond: Boolean) = trait.apply {
                color = couleur; strokeWidth = epaisseur.coerceAtLeast(0.1f)
                pathEffect = pointilles?.let { DashPathEffect(it, 0f) }
                strokeCap = if (rond) Paint.Cap.ROUND else Paint.Cap.BUTT
                strokeJoin = if (rond) Paint.Join.ROUND else Paint.Join.MITER
            }
            private fun remplit(couleur: Int) = fond.apply { color = couleur }

            override fun texte(s: String, x: Float, y: Float, taille: Float, couleur: Int, gras: Boolean) {
                if (s.isBlank()) return
                c.drawText(s, x, y, police(taille, gras).apply { color = couleur })
            }

            override fun ligne(xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, ferme: Boolean, fond: Int?) {
                if (xy.size < 4) return
                val p = Path().apply {
                    moveTo(xy[0], xy[1])
                    var i = 2
                    while (i + 1 < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
                    if (ferme) close()
                }
                fond?.let { c.drawPath(p, remplit(it)) }
                couleur?.let { c.drawPath(p, trace(it, epaisseur, pointilles, rond)) }
            }

            override fun rect(x: Float, y: Float, w: Float, h: Float, fond: Int?, trait: Int?, epaisseur: Float, rayon: Float, pointilles: FloatArray?) {
                if (w <= 0f || h <= 0f) return
                val r = RectF(x, y, x + w, y + h)
                val rr = rayon.coerceAtMost(minOf(w, h) / 2)
                fond?.let { c.drawRoundRect(r, rr, rr, remplit(it)) }
                trait?.let { c.drawRoundRect(r, rr, rr, trace(it, epaisseur, pointilles, false)) }
            }

            override fun cercle(x: Float, y: Float, r: Float, fond: Int?, trait: Int?, epaisseur: Float) {
                if (r <= 0f) return
                fond?.let { c.drawCircle(x, y, r, remplit(it)) }
                trait?.let { c.drawCircle(x, y, r, trace(it, epaisseur, null, false)) }
            }

            override fun chemin(ops: CharArray, xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, fond: Int?) {
                if (ops.isEmpty()) return
                val p = Path()
                var i = 0
                for (o in ops) when (o) {
                    'M' -> { p.moveTo(xy[i], xy[i + 1]); i += 2 }
                    'L' -> { p.lineTo(xy[i], xy[i + 1]); i += 2 }
                    'C' -> { p.cubicTo(xy[i], xy[i + 1], xy[i + 2], xy[i + 3], xy[i + 4], xy[i + 5]); i += 6 }
                    'Z' -> p.close()
                }
                fond?.let { c.drawPath(p, remplit(it)) }
                couleur?.let { c.drawPath(p, trace(it, epaisseur, pointilles, rond)) }
            }

            override fun image(octets: ByteArray, x: Float, y: Float, w: Float, h: Float) {
                val b = bitmaps.getOrPut(octets) { runCatching { BitmapFactory.decodeByteArray(octets, 0, octets.size) }.getOrNull() } ?: return
                c.drawBitmap(b, null, RectF(x, y, x + w, y + h), image)
            }
        }
    }
}
