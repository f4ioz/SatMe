/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The one and only "SatMe" signature burned into every picture the app exports
 * — QTH photo, QRV card, sked card. One implementation means the mark never
 * drifts from one export to the next, and it names the application, never an
 * operator: a card shared by any station must read "SatMe", nothing else.
 */
object AppMark {

    private const val WORD = "SatMe"
    private val CYAN = 0xFF38E1D4.toInt()

    /** The launcher icon, rounded like a tile, ready to be stamped. */
    fun icon(context: Context, sizePx: Int = 288): Bitmap? = QthPhoto.appIcon(context, sizePx)

    /**
     * Draws the icon followed by the wordmark on the [baselineY] baseline.
     * [u] is the layout unit: the word is 4.6·u tall and the icon 6.4·u wide,
     * so the mark scales with the picture instead of being pinned to pixels.
     * [align] tells how [x] is understood: the right edge of the whole mark,
     * or its centre.
     */
    fun draw(
        c: Canvas,
        x: Float,
        baselineY: Float,
        u: Float,
        icon: Bitmap? = null,
        align: Paint.Align = Paint.Align.RIGHT,
        wordColor: Int = Color.WHITE,
        wordAlpha: Int = 210,
        glyphColor: Int = CYAN
    ) {
        val pWord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = wordColor; textSize = 4.6f * u; isFakeBoldText = true
            alpha = wordAlpha; textAlign = Paint.Align.LEFT
        }
        val wordW = pWord.measureText(WORD)
        val side = 6.4f * u
        val gap = 1.0f * u
        val total = side + gap + wordW
        val left = when (align) {
            Paint.Align.RIGHT -> x - total
            Paint.Align.CENTER -> x - total / 2f
            else -> x
        }
        drawGlyph(c, left + side / 2f, baselineY - 1.6f * u, u, icon, glyphColor)
        c.drawText(WORD, left + side + gap, baselineY, pWord)
    }

    /**
     * The icon itself when the package manager gives it up — that is the mark
     * users see on the Play Store — and a drawn planet-and-orbit otherwise, so
     * an export is never left unsigned.
     */
    private fun drawGlyph(c: Canvas, cx: Float, cy: Float, u: Float, icon: Bitmap?, glyphColor: Int) {
        if (icon != null) {
            val side = 6.4f * u
            val dst = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
            val ok = runCatching {
                c.drawBitmap(icon, null, dst, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true; alpha = 235
                })
            }.isSuccess
            if (ok) return
        }
        val r = 2.4f * u
        val pGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glyphColor; style = Paint.Style.STROKE; strokeWidth = 0.55f * u; alpha = 220
        }
        c.drawCircle(cx, cy, r * 0.55f, Paint(pGlyph).apply { style = Paint.Style.FILL })
        val orbit = Path().apply {
            addOval(cx - r * 1.5f, cy - r * 0.6f, cx + r * 1.5f, cy + r * 0.6f, Path.Direction.CW)
        }
        c.save()
        c.rotate(-24f, cx, cy)
        c.drawPath(orbit, pGlyph)
        c.restore()
    }
}
