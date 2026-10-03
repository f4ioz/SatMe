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
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import kotlin.random.Random

/** Draws a [Planche.Modele]: the same drawing for the preview and the export. */
object PlancheRendu {

    /** What the texts say. */
    data class Valeurs(
        val indicatif: String = "",
        val nom: String = "",
        val locator: String = "",
        val dates: String = "",
        val titre: String = ""
    ) {
        fun de(t: Planche.Texte): String = when (t.champ) {
            Planche.Champ.INDICATIF -> indicatif
            Planche.Champ.NOM_LOCATOR -> listOf(nom, if (nom.isNotBlank() && locator.isNotBlank()) "@ $locator" else locator)
                .filter { it.isNotBlank() }.joinToString("\n")
            Planche.Champ.DATES -> dates
            Planche.Champ.TITRE -> titre
            Planche.Champ.LIBRE -> t.libre
        }
    }

    /** An image file decoded no larger than needed. */
    fun charge(f: File, largeurMax: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var n = 1
        while (o.outWidth / (n * 2) >= largeurMax) n *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = n })
    }.getOrNull()

    /**
     * The sheet [largeur] pixels wide. [images]: the pictures by box, with
     * what is known of them (for the line under each).
     */
    fun dessine(
        m: Planche.Modele, fond: Bitmap?, largeur: Int,
        images: Map<Int, Pair<Bitmap, SstvMeta.SstvShot>>, v: Valeurs,
        logo: android.graphics.drawable.Drawable? = null
    ): Bitmap {
        val ratio = if (fond != null) fond.width.toFloat() / fond.height else m.ratio
        val w = largeur; val h = (largeur / ratio).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (fond != null) c.drawBitmap(fond, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        else fondGenerique(c, w, h)
        val k = w / 1000f
        val n = m.cases.size
        m.cases.forEachIndexed { i, z ->
            val r = RectF(z.x * w, z.y * h, (z.x + z.w) * w, (z.y + z.h) * h)
            val img = images[i]
            if (img != null) {
                // The line under the picture takes its share of the box.
                val bande = if (m.legende) (r.height() * 0.09f).coerceAtLeast(10f * k) else 0f
                val ri = RectF(r.left, r.top, r.right, r.bottom - bande)
                dessineRempli(c, img.first, ri)
                if (bande > 0f) {
                    c.drawRect(RectF(r.left, ri.bottom, r.right, r.bottom), Paint().apply { color = Color.WHITE })
                    texteAjuste(c, Planche.legende(img.second, v.indicatif),
                        RectF(r.left + 3 * k, ri.bottom, r.right - 3 * k, r.bottom), Color.BLACK, gras = false)
                }
                c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * k })
            } else if (fond == null) {
                // A generic sheet shows its empty boxes; an imported one has its own.
                c.drawRect(r, Paint().apply { color = 0xCC05080D.toInt() })
                c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * k })
            }
            if (m.numeros) {
                val etiquette = "${i + 1}/$n"
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = r.height() * 0.11f; typeface = Typeface.DEFAULT_BOLD }
                val tw = p.measureText(etiquette)
                val boite = RectF(r.left + 4 * k, r.top + 4 * k, r.left + 4 * k + tw + 10 * k, r.top + 4 * k + p.textSize * 1.25f)
                c.drawRoundRect(boite, 4 * k, 4 * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0000000.toInt() })
                p.color = Color.WHITE
                c.drawText(etiquette, boite.left + 5 * k, boite.bottom - p.textSize * 0.3f, p)
            }
        }
        for (t in m.textes) {
            val r = RectF(t.zone.x * w, t.zone.y * h, (t.zone.x + t.zone.w) * w, (t.zone.y + t.zone.h) * h)
            if (t.fond != 0) c.drawRoundRect(r, 6 * k, 6 * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.fond })
            texteAjuste(c, v.de(t), RectF(r.left + r.width() * 0.04f, r.top, r.right - r.width() * 0.04f, r.bottom),
                t.couleur, gras = true)
            // The SatMe logo beside the callsign: right of it, else left, else inside.
            if (t.champ == Planche.Champ.INDICATIF && logo != null) {
                val cote = r.height() * 1.1f
                val ecart = r.height() * 0.15f
                val haut = r.centerY() - cote / 2
                // Never over a picture box.
                fun libre(x: Float): Boolean {
                    if (x < 0 || x + cote > w) return false
                    val l = RectF(x, haut, x + cote, haut + cote)
                    return m.cases.none { z -> RectF.intersects(l, RectF(z.x * w, z.y * h, (z.x + z.w) * w, (z.y + z.h) * h)) }
                }
                val gauche = listOf(r.right + ecart, r.left - ecart - cote).firstOrNull { libre(it) } ?: (r.right - cote)
                logo.setBounds(gauche.toInt(), haut.toInt(), (gauche + cote).toInt(), (haut + cote).toInt())
                logo.draw(c)
            }
        }
        return b
    }

    /** The picture filling its box, cut to its shape rather than stretched. */
    private fun dessineRempli(c: Canvas, img: Bitmap, r: RectF) {
        val ri = img.width.toFloat() / img.height; val rr = r.width() / r.height()
        val src = if (ri > rr) {
            val sw = (img.height * rr).toInt(); Rect((img.width - sw) / 2, 0, (img.width + sw) / 2, img.height)
        } else {
            val sh = (img.width / rr).toInt(); Rect(0, (img.height - sh) / 2, img.width, (img.height + sh) / 2)
        }
        c.drawBitmap(img, src, r, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** The text as large as fits its box, lines centred. */
    private fun texteAjuste(c: Canvas, texte: String, r: RectF, couleur: Int, gras: Boolean) {
        if (texte.isBlank() || r.width() <= 2 || r.height() <= 2) return
        val lignes = texte.split('\n')
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = couleur; textAlign = Paint.Align.CENTER
            typeface = if (gras) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        var taille = r.height() / lignes.size * 0.82f
        p.textSize = taille
        val plusLongue = lignes.maxOf { p.measureText(it) }
        if (plusLongue > r.width()) { taille *= r.width() / plusLongue; p.textSize = taille }
        val pas = taille * 1.15f
        val hauteur = pas * lignes.size
        var y = r.centerY() - hauteur / 2 + pas * 0.78f
        for (l in lignes) { c.drawText(l, r.centerX(), y, p); y += pas }
    }

    /** Night sky: deep blue to black, stars, a thin horizon glow. */
    private fun fondGenerique(c: Canvas, w: Int, h: Int) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF02040A.toInt(), 0xFF0A1630.toInt(), 0xFF123A6B.toInt()),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        })
        val r = Random(73)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(w * h / 4000) {
            p.color = Color.argb(r.nextInt(90, 255), 255, 255, 255)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.85f, r.nextFloat() * w / 900f + 0.5f, p)
        }
    }
}
