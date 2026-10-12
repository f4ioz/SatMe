/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The "SatMe" signature on every picture the PC exports — the phone's
 * AppMark: the app's icon, rounded like a tile, then the word, both scaled
 * by one unit [u] (the word 4.6·u tall, the icon 6.4·u wide), so the mark
 * is the same on a video frame and a sked card.
 */
object MarquePc {
    private const val MOT = "SatMe"

    /** The launcher icon (the repository's, taken at build time). */
    val icone: BufferedImage? by lazy {
        runCatching { MarquePc::class.java.getResourceAsStream("/marque/icon.png")?.use { ImageIO.read(it) } }.getOrNull()
    }

    /**
     * Draws the icon then the word on the [base] baseline; [x] is the mark's
     * right edge, or its centre when [centre].
     */
    fun dessine(g: Graphics2D, x: Float, base: Float, u: Float, centre: Boolean = false,
                couleur: Color = Color(255, 255, 255, 215)) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val f = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(4.6f * u * 1.33f)
        g.font = f
        val lm = g.fontMetrics.stringWidth(MOT)
        val cote = 6.4f * u
        val ecart = 1.0f * u
        val total = cote + ecart + lm
        val gauche = if (centre) x - total / 2 else x - total
        val i = icone
        if (i != null) {
            val haut = base - 1.6f * u - cote / 2
            val clip = g.clip
            g.clip(RoundRectangle2D.Float(gauche, haut, cote, cote, cote * 0.48f, cote * 0.48f))
            g.drawImage(i, gauche.toInt(), haut.toInt(), cote.toInt(), cote.toInt(), null)
            g.clip = clip
        }
        g.color = couleur
        g.drawString(MOT, gauche + cote + ecart, base)
    }
}
