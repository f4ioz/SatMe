/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * La mire d'essai : l'image que SatMe émet pour se faire décoder ailleurs.
 *
 * Une mire ne sert pas à être jolie, elle sert à rendre visible ce qui ne va
 * pas. Chaque zone répond à une question précise :
 *
 *  - les barres de couleur montrent une inversion de canaux (un mode Martin
 *    décodé comme un Scottie sort avec le rouge et le vert échangés) ;
 *  - le dégradé de gris montre une échelle de niveaux fausse — un dégradé qui
 *    sature avant la fin veut dire que le blanc n'est pas à 2300 Hz ;
 *  - les bandes verticales de bord, vert à gauche et rouge à droite, montrent
 *    le décalage horizontal : c'est exactement le défaut qui fait apparaître
 *    une bande colorée sur un côté de l'image reçue. Si le vert se retrouve à
 *    droite, la synchro est prise un demi-bloc trop tôt ;
 *  - les repères en damier du haut et du bas donnent la mesure de ce décalage,
 *    un carreau valant un seizième de la largeur ;
 *  - le texte, enfin, dit d'où vient l'image et dans quel mode elle est partie.
 *
 * L'image est rendue aux dimensions exactes du mode, sans redimensionnement :
 * un PD 290 fait 800 × 616, un Robot 36 fait 320 × 240, et la mire s'adapte.
 */
object SstvPattern {

    /** Barres de couleur classiques, du blanc au noir. */
    private val BARS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(),
        0xFFFF00FF.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt())

    /**
     * Fabrique la mire d'un mode.
     *
     * [callsign] et [locator] peuvent être vides : la ligne correspondante
     * disparaît simplement.
     */
    fun render(
        ctx: Context, mode: SstvMode, callsign: String = "", locator: String = ""
    ): Bitmap {
        val w = mode.width
        val h = mode.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Fond : le bleu nuit de l'application, pour que la zone de texte ne
        // soit pas un aplat noir indistinct d'une perte de signal.
        c.drawColor(0xFF0B1020.toInt())

        val edge = maxOf(4, w / 40)          // largeur des bandes de bord
        val tick = maxOf(4, h / 40)          // hauteur des damiers
        val barsTop = tick
        val barsBot = (h * 0.42f).toInt()
        val greyBot = (h * 0.54f).toInt()

        // --- barres de couleur ------------------------------------------------
        p.style = Paint.Style.FILL
        for (i in BARS.indices) {
            val x0 = edge + (w - 2 * edge) * i / BARS.size
            val x1 = edge + (w - 2 * edge) * (i + 1) / BARS.size
            p.color = BARS[i]
            c.drawRect(x0.toFloat(), barsTop.toFloat(), x1.toFloat(), barsBot.toFloat(), p)
        }

        // --- dégradé de gris, par marches de 1/16 ----------------------------
        val steps = 16
        for (i in 0 until steps) {
            val x0 = edge + (w - 2 * edge) * i / steps
            val x1 = edge + (w - 2 * edge) * (i + 1) / steps
            val v = (i * 255 / (steps - 1)).coerceIn(0, 255)
            p.color = Color.rgb(v, v, v)
            c.drawRect(x0.toFloat(), barsBot.toFloat(), x1.toFloat(), greyBot.toFloat(), p)
        }

        // --- damiers de repère, en haut et en bas ----------------------------
        for (i in 0 until 16) {
            val x0 = edge + (w - 2 * edge) * i / 16
            val x1 = edge + (w - 2 * edge) * (i + 1) / 16
            p.color = if (i % 2 == 0) Color.WHITE else Color.BLACK
            c.drawRect(x0.toFloat(), 0f, x1.toFloat(), tick.toFloat(), p)
            c.drawRect(x0.toFloat(), (h - tick).toFloat(), x1.toFloat(), h.toFloat(), p)
        }

        // --- bandes de bord : vert à gauche, rouge à droite -------------------
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

        // --- texte -------------------------------------------------------------
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

        // Dimensions, en petit, contre le bord droit : elles disent d'un coup
        // d'œil si l'image a été décodée dans le bon mode.
        p.color = 0xFF8AA0C0.toInt()
        p.textSize = avail * 0.13f
        val dim = "${w}x$h"
        val r = Rect()
        p.getTextBounds(dim, 0, dim.length, r)
        c.drawText(dim, (w - edge - w / 40 - r.width()).toFloat(),
            (h - tick - avail * 0.06f), p)

        return bmp
    }

    /** La mire sous la forme attendue par l'émetteur : ARGB, ligne par ligne. */
    fun pixels(bmp: Bitmap): IntArray {
        val out = IntArray(bmp.width * bmp.height)
        bmp.getPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return out
    }
}
