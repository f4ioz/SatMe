/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import fr.f4ioz.satcombo.ui.theme.Magenta
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.ui.theme.Amber
import fr.f4ioz.satcombo.ui.theme.Aurora
import fr.f4ioz.satcombo.ui.theme.Cyan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Modern sky polar plot: zenith centre, horizon edge. Glowing rings, cardinal
 * labels, optional ground-track trail and a glowing live satellite marker.
 */
@Composable
fun PolarPlot(
    position: SatPosition?,
    trail: List<Pair<Double, Double>> = emptyList(),     // live (az, el) samples
    passTrack: List<Pair<Double, Double>> = emptyList(), // predicted pass arc
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val r = min(size.width, size.height) / 2f * 0.86f
        val c = Offset(size.width / 2f, size.height / 2f)
        val dark = fr.f4ioz.satcombo.ui.theme.isDarkTheme()
        val grid = if (dark) Color(0xFF2A3647) else Color(0xFFB9C6D8)
        val gridSoft = if (dark) Color(0xFF20293A) else Color(0xFFCFD9E6)

        // Filled sky disc with subtle radial glow.
        drawCircle(
            brush = Brush.radialGradient(
                colors = if (dark) listOf(Color(0xFF14202F), Color(0xFF0C131E))
                         else listOf(Color(0xFFEDF2F8), Color(0xFFDFE7F1)),
                center = c, radius = r
            ),
            radius = r, center = c
        )
        // Elevation rings 0/30/60.
        drawCircle(grid, r, c, style = Stroke(2.5f))
        drawCircle(gridSoft, r * 2f / 3f, c, style = Stroke(1.4f))
        drawCircle(gridSoft, r / 3f, c, style = Stroke(1.4f))
        drawCircle(Cyan.copy(alpha = 0.5f), 3f, c)

        // Cardinal spokes.
        drawLine(gridSoft, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.2f)
        drawLine(gridSoft, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.2f)

        // Cardinal labels.
        drawCardinal(c, r, "N", 0); drawCardinal(c, r, "E", 90)
        drawCardinal(c, r, "S", 180); drawCardinal(c, r, "W", 270)

        // Predicted pass arc (AOS -> LOS) with end markers.
        if (passTrack.size > 1) {
            val path = Path()
            passTrack.forEachIndexed { i, (az, el) ->
                val pt = azElToXy(az, el, c, r)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            drawPath(
                path, Magenta.copy(alpha = 0.75f),
                style = Stroke(width = 3.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            )
            val aosPt = azElToXy(passTrack.first().first, passTrack.first().second, c, r)
            val losPt = azElToXy(passTrack.last().first, passTrack.last().second, c, r)
            drawCircle(Magenta, 8f, aosPt)                       // AOS plein
            drawCircle(Magenta, 8f, losPt, style = Stroke(3f))   // LOS creux
            // Direction arrows along the arc.
            for (f in listOf(0.25f, 0.50f, 0.75f)) {
                val i = (passTrack.size * f).toInt().coerceIn(1, passTrack.size - 1)
                val a = azElToXy(passTrack[i - 1].first, passTrack[i - 1].second, c, r)
                val b = azElToXy(passTrack[i].first, passTrack[i].second, c, r)
                drawArrowTrianglePP(a, b, Magenta, 32f)
            }
        }

        // Ground-track trail.
        if (trail.size > 1) {
            for (i in 1 until trail.size) {
                val a = azElToXy(trail[i - 1].first, trail[i - 1].second, c, r)
                val b = azElToXy(trail[i].first, trail[i].second, c, r)
                val alpha = 0.15f + 0.55f * (i.toFloat() / trail.size)
                drawLine(Aurora.copy(alpha = alpha), a, b, 3f)
            }
        }

        // Live marker with glow.
        position?.let { p ->
            if (p.elevationDeg >= 0) {
                val pt = azElToXy(p.azimuthDeg, p.elevationDeg, c, r)
                val col = if (p.sunlit) Amber else Cyan
                drawCircle(col.copy(alpha = 0.18f), 26f, pt)
                drawCircle(col.copy(alpha = 0.30f), 16f, pt)
                drawCircle(col, 9f, pt)
                drawCircle(if (dark) Color.White else Color(0xFF1E293B), 9f, pt, style = Stroke(2.5f))
            }
        }
    }
}

private fun DrawScope.drawCardinal(c: Offset, r: Float, label: String, azDeg: Int) {
    val az = Math.toRadians(azDeg.toDouble())
    val x = c.x + (r + 26f) * sin(az).toFloat()
    val y = c.y - (r + 26f) * cos(az).toFloat()
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#8A98B0")
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        drawText(label, x, y + 10f, paint)
    }
}

private fun azElToXy(azDeg: Double, elDeg: Double, c: Offset, r: Float): Offset {
    val radius = r * ((90.0 - elDeg.coerceIn(0.0, 90.0)) / 90.0).toFloat()
    val az = Math.toRadians(azDeg)
    val x = c.x + radius * sin(az).toFloat()
    val y = c.y - radius * cos(az).toFloat()
    return Offset(x, y)
}

/**
 * Tiny polar plot for pass cards (Look4Sat-style per-pass sky path).
 * Shows horizon + 30/60 rings, the pass arc, AOS dot and N tick.
 */
@Composable
fun MiniPolarPlot(
    track: List<Pair<Double, Double>>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val r = min(size.width, size.height) / 2f * 0.92f
        val c = Offset(size.width / 2f, size.height / 2f)
        val miniDark = fr.f4ioz.satcombo.ui.theme.isDarkTheme()
        val grid = if (miniDark) Color(0xFF2A3647) else Color(0xFFB9C6D8)

        drawCircle(if (miniDark) Color(0xFF101824) else Color(0xFFE8EEF6), r, c)
        drawCircle(grid, r, c, style = Stroke(1.5f))
        drawCircle(grid.copy(alpha = 0.6f), r * 2f / 3f, c, style = Stroke(0.8f))
        drawCircle(grid.copy(alpha = 0.6f), r / 3f, c, style = Stroke(0.8f))
        // N tick.
        drawLine(grid, Offset(c.x, c.y - r), Offset(c.x, c.y - r + 6f), 1.5f)

        if (track.size > 1) {
            val path = Path()
            track.forEachIndexed { i, (az, el) ->
                val pt = azElToXy(az, el, c, r)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            drawPath(path, accent, style = Stroke(2.5f))
            val aos = azElToXy(track.first().first, track.first().second, c, r)
            drawCircle(accent, 4f, aos)
            val mid = (track.size / 2).coerceIn(1, track.size - 1)
            val ma = azElToXy(track[mid - 1].first, track[mid - 1].second, c, r)
            val mb = azElToXy(track[mid].first, track[mid].second, c, r)
            drawArrowTrianglePP(ma, mb, accent, 9f)
        }
    }
}


private fun DrawScope.drawArrowTrianglePP(from: Offset, tip: Offset, color: Color, size: Float) {
    val ang = kotlin.math.atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble())
    fun pt(a: Double, d: Float) = Offset(
        tip.x + d * kotlin.math.cos(a).toFloat(),
        tip.y + d * kotlin.math.sin(a).toFloat())
    val p = Path().apply {
        val nose = pt(ang, size * 0.7f)
        moveTo(nose.x, nose.y)
        val b1 = pt(ang + 2.5, size * 0.7f)
        val b2 = pt(ang - 2.5, size * 0.7f)
        lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close()
    }
    drawPath(p, color)
    drawPath(p, (if (fr.f4ioz.satcombo.ui.theme.isDarkTheme()) Color.White else Color(0xFF1E293B)).copy(alpha = 0.5f), style = Stroke(1.2f))
}

private fun DrawScope.drawArrowHead(at: Offset, towards: Offset, color: Color, len: Float) {
    val ang = kotlin.math.atan2((towards.y - at.y).toDouble(), (towards.x - at.x).toDouble())
    val a1 = ang + 2.6; val a2 = ang - 2.6
    val tip = towards
    drawLine(color, tip, Offset(tip.x + len * kotlin.math.cos(a1).toFloat(),
        tip.y + len * kotlin.math.sin(a1).toFloat()), 3.5f)
    drawLine(color, tip, Offset(tip.x + len * kotlin.math.cos(a2).toFloat(),
        tip.y + len * kotlin.math.sin(a2).toFloat()), 3.5f)
}
