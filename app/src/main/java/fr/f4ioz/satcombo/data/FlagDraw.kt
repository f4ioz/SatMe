/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Le trace des drapeaux decrits par [Flags], au Canvas.
 *
 * Tout est vectoriel et calcule a partir de la hauteur demandee : le meme code
 * sert au selecteur (une vignette de vingt-huit points) et a la photo QRV (un
 * drapeau haut comme l'indicatif, soit deux cents pixels sur un cliche de
 * telephone). Le selecteur montre donc exactement ce qui sera imprime, ce qui
 * evite la mauvaise surprise au moment du partage.
 *
 * A cette taille, un drapeau est une silhouette : on garde les bandes, les
 * croix, les cantons, et on renonce aux armoiries qui ne feraient qu'une tache.
 */
object FlagDraw {

    /** La largeur qu'occupera ce drapeau dessine a la hauteur [h]. */
    fun widthFor(f: Flags.Flag, h: Float): Float = h * f.ratio

    /** Trace le drapeau dans le rectangle ([left], [top], largeur deduite, [h]). */
    fun draw(c: Canvas, f: Flags.Flag, left: Float, top: Float, h: Float) {
        val w = widthFor(f, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val save = c.save()
        c.clipRect(left, top, left + w, top + h)
        when (f.kind) {
            Flags.STRIPES_V -> stripes(c, p, left, top, w, h, f, true)
            Flags.STRIPES_H -> stripes(c, p, left, top, w, h, f, false)
            Flags.NORDIC -> nordic(c, p, left, top, w, h, f)
            Flags.CROSS -> swiss(c, p, left, top, w, h, f)
            Flags.DISC -> disc(c, p, left, top, w, h, f)
            Flags.TRIANGLE -> triangle(c, p, left, top, w, h, f)
            Flags.UNION -> union(c, p, left, top, w, h,
                f.colors[0], f.colors[1], f.colors[2])
            Flags.CANTON_UNION -> cantonUnion(c, p, left, top, w, h, f)
            Flags.USA -> usa(c, p, left, top, w, h, f)
            Flags.GREECE -> greece(c, p, left, top, w, h, f)
            Flags.CANADA -> canada(c, p, left, top, w, h, f)
            Flags.LOZENGE -> lozenge(c, p, left, top, w, h, f)
            Flags.ERMINE -> ermineFlag(c, p, left, top, w, h, f)
            Flags.ERMINE_HOIST -> ermineHoist(c, p, left, top, w, h, f)
            Flags.STAR -> star(c, p, left, top, w, h, f)
            Flags.CRESCENT -> crescent(c, p, left, top, w, h, f)
            else -> fill(c, p, left, top, w, h, f.colors.firstOrNull() ?: 0xFF808080.toInt())
        }
        c.restoreToCount(save)
        // Un filet sombre autour : un drapeau a bande blanche pose sur un ciel
        // clair se dissoudrait sans lui, et le bord du drapeau fait partie du
        // dessin autant que ses couleurs.
        p.style = Paint.Style.STROKE
        p.strokeWidth = (h * 0.05f).coerceAtLeast(1f)
        p.color = 0x77000000
        c.drawRect(left, top, left + w, top + h, p)
        p.style = Paint.Style.FILL
    }

    /** Une vignette isolee, pour le selecteur. */
    fun bitmap(f: Flags.Flag, h: Int): Bitmap {
        val hh = h.coerceAtLeast(4)
        val ww = Math.max(4, Math.round(hh * f.ratio))
        val bmp = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), f, 0f, 0f, hh.toFloat())
        return bmp
    }

    // ------------------------------------------------------------------ base

    private fun fill(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float, col: Int) {
        p.color = col
        c.drawRect(l, t, l + w, t + h, p)
    }

    private fun stripes(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float,
                        f: Flags.Flag, vertical: Boolean) {
        val n = f.colors.size
        val ws = if (f.weights.size == n) f.weights else List(n) { 1f }
        val total = ws.sum().takeIf { it > 0f } ?: 1f
        var acc = 0f
        for (i in 0 until n) {
            val a = acc / total
            acc += ws[i]
            val b = acc / total
            p.color = f.colors[i]
            if (vertical) c.drawRect(l + a * w, t, l + b * w, t + h, p)
            else c.drawRect(l, t + a * h, l + w, t + b * h, p)
        }
    }

    /** Croix scandinave : le montant est decale vers le guindant. */
    private fun nordic(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float, f: Flags.Flag) {
        fill(c, p, l, t, w, h, f.colors[0])
        val bar = h * 0.222f
        val cx = l + h * 0.40f
        val cy = t + h / 2f
        p.color = f.colors[1]
        c.drawRect(l, cy - bar / 2f, l + w, cy + bar / 2f, p)
        c.drawRect(cx - bar / 2f, t, cx + bar / 2f, t + h, p)
        if (f.colors.size > 2) {
            val inner = bar * 0.44f
            p.color = f.colors[2]
            c.drawRect(l, cy - inner / 2f, l + w, cy + inner / 2f, p)
            c.drawRect(cx - inner / 2f, t, cx + inner / 2f, t + h, p)
        }
    }

    private fun swiss(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float, f: Flags.Flag) {
        fill(c, p, l, t, w, h, f.colors[0])
        val arm = h * 0.30f
        val bar = h * 0.20f
        val cx = l + w / 2f
        val cy = t + h / 2f
        p.color = f.colors[1]
        c.drawRect(cx - arm, cy - bar / 2f, cx + arm, cy + bar / 2f, p)
        c.drawRect(cx - bar / 2f, cy - arm, cx + bar / 2f, cy + arm, p)
    }

    private fun disc(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float, f: Flags.Flag) {
        fill(c, p, l, t, w, h, f.colors[0])
        p.color = f.colors[1]
        c.drawCircle(l + w / 2f, t + h / 2f, h * 0.30f, p)
    }

    private fun triangle(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float, f: Flags.Flag) {
        p.color = f.colors[0]
        c.drawRect(l, t, l + w, t + h / 2f, p)
        p.color = f.colors[1]
        c.drawRect(l, t + h / 2f, l + w, t + h, p)
        p.color = f.colors[2]
        val path = Path()
        path.moveTo(l, t)
        path.lineTo(l + w * 0.5f, t + h / 2f)
        path.lineTo(l, t + h)
        path.close()
        c.drawPath(path, p)
    }

    /** Union Jack simplifie : les sautoirs ne sont pas contre-ecartelees. */
    private fun union(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float,
                      blue: Int, white: Int, red: Int) {
        fill(c, p, l, t, w, h, blue)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        // sautoirs blancs puis rouges
        p.color = white
        p.strokeWidth = h * 0.20f
        c.drawLine(l, t, l + w, t + h, p)
        c.drawLine(l, t + h, l + w, t, p)
        p.color = red
        p.strokeWidth = h * 0.085f
        c.drawLine(l, t, l + w, t + h, p)
        c.drawLine(l, t + h, l + w, t, p)
        // croix droite, blanche bordant le rouge
        p.style = Paint.Style.FILL
        val cw = h * 0.33f
        val cr = h * 0.20f
        val cx = l + w / 2f
        val cy = t + h / 2f
        p.color = white
        c.drawRect(l, cy - cw / 2f, l + w, cy + cw / 2f, p)
        c.drawRect(cx - cw / 2f, t, cx + cw / 2f, t + h, p)
        p.color = red
        c.drawRect(l, cy - cr / 2f, l + w, cy + cr / 2f, p)
        c.drawRect(cx - cr / 2f, t, cx + cr / 2f, t + h, p)
    }

    private fun cantonUnion(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float,
                            f: Flags.Flag) {
        fill(c, p, l, t, w, h, f.colors[0])
        union(c, p, l, t, w / 2f, h / 2f, f.colors[0], f.colors[1], f.colors[2])
        p.color = f.colors[1]
        val r = h * 0.055f
        c.drawCircle(l + w * 0.25f, t + h * 0.80f, r * 1.5f, p)
        c.drawCircle(l + w * 0.72f, t + h * 0.28f, r, p)
        c.drawCircle(l + w * 0.80f, t + h * 0.50f, r, p)
        c.drawCircle(l + w * 0.70f, t + h * 0.72f, r, p)
        c.drawCircle(l + w * 0.62f, t + h * 0.50f, r * 0.7f, p)
    }

    private fun usa(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float, f: Flags.Flag) {
        val n = 13
        val sh = h / n
        for (i in 0 until n) {
            p.color = if (i % 2 == 0) f.colors[0] else f.colors[1]
            c.drawRect(l, t + i * sh, l + w, t + (i + 1) * sh, p)
        }
        val cw = w * 0.40f
        val ch = sh * 7f
        p.color = f.colors[2]
        c.drawRect(l, t, l + cw, t + ch, p)
        p.color = f.colors[1]
        val r = ch * 0.045f
        for (row in 0 until 5) for (col in 0 until 6) {
            val odd = row % 2
            if (odd == 1 && col == 5) continue
            val x = l + cw * (0.09f + col * 0.166f) + (if (odd == 1) cw * 0.083f else 0f)
            val y = t + ch * (0.12f + row * 0.19f)
            c.drawCircle(x, y, r, p)
        }
    }

    private fun greece(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float, f: Flags.Flag) {
        val n = 9
        val sh = h / n
        for (i in 0 until n) {
            p.color = if (i % 2 == 0) f.colors[0] else f.colors[1]
            c.drawRect(l, t + i * sh, l + w, t + (i + 1) * sh, p)
        }
        val side = sh * 5f
        p.color = f.colors[0]
        c.drawRect(l, t, l + side, t + side, p)
        p.color = f.colors[1]
        val bar = side * 0.20f
        val cx = l + side / 2f
        val cy = t + side / 2f
        c.drawRect(l, cy - bar / 2f, l + side, cy + bar / 2f, p)
        c.drawRect(cx - bar / 2f, t, cx + bar / 2f, t + side, p)
    }

    private fun canada(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float, f: Flags.Flag) {
        p.color = f.colors[0]
        c.drawRect(l, t, l + w * 0.25f, t + h, p)
        c.drawRect(l + w * 0.75f, t, l + w, t + h, p)
        p.color = f.colors[1]
        c.drawRect(l + w * 0.25f, t, l + w * 0.75f, t + h, p)
        // Une feuille stylisee : a cette taille, une silhouette a trois lobes
        // dit "Canada" bien mieux que onze pointes ecrasees en bouillie.
        p.color = f.colors[0]
        val cx = l + w / 2f
        val cy = t + h / 2f
        val s = h * 0.34f
        val path = Path()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s * 0.32f, cy - s * 0.25f)
        path.lineTo(cx + s * 0.85f, cy - s * 0.40f)
        path.lineTo(cx + s * 0.55f, cy + s * 0.25f)
        path.lineTo(cx + s * 0.18f, cy + s * 0.30f)
        path.lineTo(cx + s * 0.10f, cy + s)
        path.lineTo(cx - s * 0.10f, cy + s)
        path.lineTo(cx - s * 0.18f, cy + s * 0.30f)
        path.lineTo(cx - s * 0.55f, cy + s * 0.25f)
        path.lineTo(cx - s * 0.85f, cy - s * 0.40f)
        path.lineTo(cx - s * 0.32f, cy - s * 0.25f)
        path.close()
        c.drawPath(path, p)
    }

    private fun lozenge(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float, f: Flags.Flag) {
        fill(c, p, l, t, w, h, f.colors[0])
        val cx = l + w / 2f
        val cy = t + h / 2f
        p.color = f.colors[1]
        val path = Path()
        path.moveTo(cx, t + h * 0.09f)
        path.lineTo(l + w * 0.93f, cy)
        path.lineTo(cx, t + h * 0.91f)
        path.lineTo(l + w * 0.07f, cy)
        path.close()
        c.drawPath(path, p)
        p.color = f.colors[2]
        c.drawCircle(cx, cy, h * 0.19f, p)
    }

    // --------------------------------------------------------------- hermines

    /**
     * Gwenn ha Du et Melen ha Ruz : des bandes alternees et un canton
     * d'hermines au guindant. Le canton mesure 0,44 de la hauteur et environ
     * 0,32 de la largeur, comme sur le drapeau breton d'usage.
     */
    private fun ermineFlag(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float,
                           f: Flags.Flag) {
        val n = if (f.weights.isNotEmpty()) f.weights.size else 9
        val sh = h / n
        for (i in 0 until n) {
            p.color = if (i % 2 == 0) f.colors[0] else f.colors[1]
            c.drawRect(l, t + i * sh, l + w, t + (i + 1) * sh, p)
        }
        val cw = w * 0.317f
        val ch = h * 0.44f
        p.color = f.colors[2]
        c.drawRect(l, t, l + cw, t + ch, p)
        p.color = f.colors[3]
        val rows = Flags.ermineRows(f.spots)
        if (rows.isEmpty()) return
        val rowH = ch / rows.size
        val size = minOf(rowH * 0.78f, cw / (rows.maxOrNull() ?: 1) * 0.80f)
        rows.forEachIndexed { r, count ->
            val y = t + rowH * (r + 0.5f)
            for (i in 0 until count) {
                val x = l + cw * (i + 0.5f) / count
                ermine(c, p, x, y, size)
            }
        }
    }

    /**
     * Melen ha Ruz, le drapeau du Pays bigouden : cinq bandes rouge et jaune,
     * et au guindant un panneau jaune sur toute la hauteur seme d'hermines
     * rouges. La difference avec le Gwenn ha Du n'est pas cosmetique -- le
     * canton breton est haut de moins de la moitie du drapeau, celui-ci
     * descend jusqu'en bas.
     */
    private fun ermineHoist(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float,
                            f: Flags.Flag) {
        val n = if (f.weights.isNotEmpty()) f.weights.size else 5
        val sh = h / n
        for (i in 0 until n) {
            p.color = if (i % 2 == 0) f.colors[0] else f.colors[1]
            c.drawRect(l, t + i * sh, l + w, t + (i + 1) * sh, p)
        }
        val cw = w * 0.34f
        p.color = f.colors[2]
        c.drawRect(l, t, l + cw, t + h, p)
        p.color = f.colors[3]
        val rows = Flags.ermineRowsOf(f.spots, 3)
        if (rows.isEmpty()) return
        val rowH = h / rows.size
        val size = minOf(rowH * 0.80f, cw / (rows.maxOrNull() ?: 1) * 0.82f)
        rows.forEachIndexed { r, count ->
            val y = t + rowH * (r + 0.5f)
            for (i in 0 until count) {
                val x = l + cw * (i + 0.5f) / count
                ermine(c, p, x, y, size)
            }
        }
    }

    /** Fond plein et etoile a cinq branches au centre. */
    private fun star(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float,
                     f: Flags.Flag) {
        fill(c, p, l, t, w, h, f.colors[0])
        p.color = f.colors[1]
        drawStar(c, p, l + w / 2f, t + h / 2f, h * 0.32f)
    }

    /**
     * Fond plein, croissant et petite etoile : la silhouette turque. Le
     * croissant se creuse en repeignant un disque decale avec la couleur du
     * fond, ce qui evite un trace de croissant en Bezier a trente pixels.
     */
    private fun crescent(c: Canvas, p: Paint, l: Float, t: Float, w: Float, h: Float,
                         f: Flags.Flag) {
        fill(c, p, l, t, w, h, f.colors[0])
        val cx = l + w * 0.40f
        val cy = t + h / 2f
        val r = h * 0.28f
        p.color = f.colors[1]
        c.drawCircle(cx, cy, r, p)
        p.color = f.colors[0]
        c.drawCircle(cx + r * 0.32f, cy, r * 0.82f, p)
        p.color = f.colors[1]
        drawStar(c, p, l + w * 0.60f, cy, h * 0.15f)
    }

    /** Une etoile a cinq branches, pointe en haut, de rayon exterieur [r]. */
    private fun drawStar(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
        val path = Path()
        val inner = r * 0.382f
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else inner
            val a = (-Math.PI / 2.0) + i * Math.PI / 5.0
            val x = cx + (rad * Math.cos(a)).toFloat()
            val y = cy + (rad * Math.sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    /** Une moucheture : le fer de lance et ses trois mouchets. */
    private fun ermine(c: Canvas, p: Paint, cx: Float, cy: Float, s: Float) {
        val path = Path()
        path.moveTo(cx, cy + s * 0.52f)
        path.lineTo(cx - s * 0.28f, cy - s * 0.12f)
        path.lineTo(cx + s * 0.28f, cy - s * 0.12f)
        path.close()
        c.drawPath(path, p)
        val r = (s * 0.11f).coerceAtLeast(0.5f)
        c.drawCircle(cx, cy - s * 0.44f, r, p)
        c.drawCircle(cx - s * 0.28f, cy - s * 0.30f, r, p)
        c.drawCircle(cx + s * 0.28f, cy - s * 0.30f, r, p)
    }
}
