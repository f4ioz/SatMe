/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The « live clock » test card for DATV: a dial whose second hand moves,
 * the UTC time and date in figures, the callsign and locator, drawn once a
 * second and fed to ffmpeg (a beep each second goes with it). Lets a
 * receiving station judge the picture's delay and smoothness at a glance.
 * SatMe's own drawing.
 */
object HorlogeDatvPc {
    private val HEURE = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)
    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)

    /** The card at [w]×[h] for the instant [ms] (UTC). */
    fun rendu(w: Int, h: Int, indicatif: String, locator: String, ms: Long): BufferedImage {
        val im = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = im.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, Color(0x10, 0x1C, 0x2A), 0f, h.toFloat(), Color(0x06, 0x0C, 0x14))
            g.fillRect(0, 0, w, h)
            // The dial on the left, as high as the picture allows.
            val r = minOf(h * 0.42, w * 0.22)
            val cx = r + h * 0.08
            val cy = h / 2.0
            g.color = Color(0xF2, 0xF5, 0xF8)
            g.fill(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
            g.color = Color(0x30, 0x3A, 0x44)
            g.stroke = BasicStroke((r * 0.03).toFloat())
            g.draw(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
            for (k in 0 until 60) {
                val a = Math.toRadians(k * 6.0)
                val heure = k % 5 == 0
                val r0 = r * (if (heure) 0.80 else 0.88)
                g.stroke = BasicStroke((r * (if (heure) 0.045 else 0.015)).toFloat())
                g.color = Color(0x1A, 0x22, 0x2B)
                g.draw(Line2D.Double(cx + r0 * Math.sin(a), cy - r0 * Math.cos(a), cx + r * 0.94 * Math.sin(a), cy - r * 0.94 * Math.cos(a)))
            }
            val t = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
            val s = t.second
            val mn = t.minute + s / 60.0
            val hr = (t.hour % 12) + mn / 60.0
            fun aiguille(angleDeg: Double, longueur: Double, epaisseur: Double, c: Color) {
                val a = Math.toRadians(angleDeg)
                g.color = c
                g.stroke = BasicStroke(epaisseur.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Double(cx - r * 0.12 * Math.sin(a), cy + r * 0.12 * Math.cos(a), cx + longueur * Math.sin(a), cy - longueur * Math.cos(a)))
            }
            aiguille(hr * 30, r * 0.52, r * 0.07, Color(0x1A, 0x22, 0x2B))
            aiguille(mn * 6, r * 0.78, r * 0.05, Color(0x1A, 0x22, 0x2B))
            aiguille(s * 6.0, r * 0.86, r * 0.022, Color(0x0E, 0x9F, 0x8C))
            g.color = Color(0x0E, 0x9F, 0x8C)
            g.fill(Ellipse2D.Double(cx - r * 0.04, cy - r * 0.04, r * 0.08, r * 0.08))
            // The figures on the right: the callsign, the time, the date, the locator.
            val x0 = (cx + r + h * 0.07).toFloat()
            val place = w - x0 - h * 0.04f
            fun ecrit(texte: String, taille: Float, gras: Boolean, c: Color, y: Float) {
                if (texte.isEmpty()) return
                var f = Font(Font.SANS_SERIF, if (gras) Font.BOLD else Font.PLAIN, 1).deriveFont(taille)
                val l = g.getFontMetrics(f).stringWidth(texte)
                if (l > place && l > 0) f = f.deriveFont(taille * place / l)
                g.font = f
                g.color = c
                g.drawString(texte, x0, y)
            }
            val call = indicatif.trim().uppercase()
            ecrit(call.ifEmpty { "SatMe" }, h * 0.17f, true, Color(0xFF, 0xFF, 0xFF), h * 0.30f)
            ecrit(HEURE.format(t), h * 0.15f, true, Color(0x9F, 0xE8, 0xFF), h * 0.53f)
            ecrit("UTC · " + DATE.format(t), h * 0.06f, false, Color(0xC8, 0xD3, 0xDD), h * 0.64f)
            ecrit(locator.trim().uppercase(), h * 0.07f, false, Color(0xFF, 0xB4, 0x54), h * 0.78f)
            ecrit("DATV · SatMe", h * 0.045f, false, Color(0x6F, 0x7D, 0x8A), h * 0.90f)
        } finally {
            g.dispose()
        }
        return im
    }
}
