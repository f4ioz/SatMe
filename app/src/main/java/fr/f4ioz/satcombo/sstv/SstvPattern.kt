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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import fr.f4ioz.satcombo.R

/**
 * The test card SatMe transmits to be decoded elsewhere. Each zone exposes
 * one fault:
 *
 *  - colour bars: swapped channels (Martin decoded as Scottie swaps red/green);
 *  - grey steps: a wrong level scale — saturating early means white is not
 *    at 2300 Hz;
 *  - edge strips, green left and red right: horizontal offset, the fault that
 *    puts a coloured band on one side. Green on the right means sync was taken
 *    half a block too early;
 *  - top/bottom checkers measure that offset, one square = 1/16 of the width;
 *  - the text says who sent it and in which mode.
 *
 * Rendered at the exact mode size (PD 290 800 × 616, Robot 36 320 × 240).
 */
object SstvPattern {

    /** Classic colour bars, white to black. */
    private val BARS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(),
        0xFFFF00FF.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt())

    /** Renders the card for [mode]. An empty [callsign]/[locator] drops that line. */
    fun render(
        ctx: Context, mode: SstvMode, callsign: String = "", locator: String = ""
    ): Bitmap {
        val w = mode.width
        val h = mode.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // App navy background, so the text area can't be mistaken for signal loss.
        c.drawColor(0xFF0B1020.toInt())

        val edge = maxOf(4, w / 40)          // edge strip width
        val tick = maxOf(4, h / 40)          // checker height
        val barsTop = tick
        val barsBot = (h * 0.42f).toInt()
        val greyBot = (h * 0.54f).toInt()

        // --- colour bars -----------------------------------------------------
        p.style = Paint.Style.FILL
        for (i in BARS.indices) {
            val x0 = edge + (w - 2 * edge) * i / BARS.size
            val x1 = edge + (w - 2 * edge) * (i + 1) / BARS.size
            p.color = BARS[i]
            c.drawRect(x0.toFloat(), barsTop.toFloat(), x1.toFloat(), barsBot.toFloat(), p)
        }

        // --- grey steps, 1/16 each ------------------------------------------
        val steps = 16
        for (i in 0 until steps) {
            val x0 = edge + (w - 2 * edge) * i / steps
            val x1 = edge + (w - 2 * edge) * (i + 1) / steps
            val v = (i * 255 / (steps - 1)).coerceIn(0, 255)
            p.color = Color.rgb(v, v, v)
            c.drawRect(x0.toFloat(), barsBot.toFloat(), x1.toFloat(), greyBot.toFloat(), p)
        }

        // --- reference checkers, top and bottom -----------------------------
        for (i in 0 until 16) {
            val x0 = edge + (w - 2 * edge) * i / 16
            val x1 = edge + (w - 2 * edge) * (i + 1) / 16
            p.color = if (i % 2 == 0) Color.WHITE else Color.BLACK
            c.drawRect(x0.toFloat(), 0f, x1.toFloat(), tick.toFloat(), p)
            c.drawRect(x0.toFloat(), (h - tick).toFloat(), x1.toFloat(), h.toFloat(), p)
        }

        // --- edge strips: green left, red right -----------------------------
        p.color = 0xFF00C000.toInt()
        c.drawRect(0f, 0f, edge.toFloat(), h.toFloat(), p)
        p.color = 0xFFC00000.toInt()
        c.drawRect((w - edge).toFloat(), 0f, w.toFloat(), h.toFloat(), p)

        // --- logo --------------------------------------------------------------
        val textTop = greyBot + h / 40
        val logoSize = ((h - textTop - tick) * 0.72f).toInt().coerceAtLeast(24)
        val logoX = edge + w / 24
        val logoY = textTop + ((h - textTop - tick) - logoSize) / 2
        runCatching {
            ContextCompat.getDrawable(ctx, R.drawable.ic_sat_foreground)?.let { d ->
                d.setBounds(logoX, logoY, logoX + logoSize, logoY + logoSize)
                d.draw(c)
            }
        }

        // --- text --------------------------------------------------------------
        val tx = (logoX + logoSize + w / 40).toFloat()
        val avail = h - textTop - tick
        p.color = Color.WHITE
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textSize = avail * 0.30f
        c.drawText("SatMe", tx, textTop + avail * 0.30f, p)

        p.typeface = Typeface.SANS_SERIF
        p.textSize = avail * 0.19f
        p.color = 0xFF9FE8FF.toInt()
        val line2 = listOf(callsign.trim(), locator.trim())
            .filter { it.isNotEmpty() }.joinToString("  ")
        if (line2.isNotEmpty()) c.drawText(line2, tx, textTop + avail * 0.56f, p)

        p.color = 0xFFFFC65C.toInt()
        c.drawText(mode.name, tx, textTop + avail * 0.82f, p)

        // Small dimensions, right-aligned: shows at a glance if the mode was right.
        p.color = 0xFF8AA0C0.toInt()
        p.textSize = avail * 0.13f
        val dim = "${w}x$h"
        val r = Rect()
        p.getTextBounds(dim, 0, dim.length, r)
        c.drawText(dim, (w - edge - w / 40 - r.width()).toFloat(),
            (h - tick - avail * 0.06f), p)

        return bmp
    }

    /** The card as the encoder expects it: ARGB, row by row. */
    fun pixels(bmp: Bitmap): IntArray {
        val out = IntArray(bmp.width * bmp.height)
        bmp.getPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return out
    }
}
