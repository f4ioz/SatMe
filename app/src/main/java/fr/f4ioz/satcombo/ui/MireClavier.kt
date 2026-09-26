/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.ui.theme.*
import kotlin.math.*

/**
 * Azimuth and elevation on one line, for the keyboard screen, where the
 * keyboard claims every pixel and nothing scrolls. A small azimuth dial (a
 * circle reads at a glance, three digits need interpreting) and a vertical
 * elevation bar, read bottom to top.
 */
@Composable
fun MireClavier(pos: SatPosition?, modifier: Modifier = Modifier) {
    if (pos == null) return
    val az = pos.azimuthDeg
    val el = pos.elevationDeg

    // The dial follows the phone, like on the pass page: the needle stays on
    // the device axis and the satellite moves around it; you turn until they
    // overlap. North-up would force mental conversion, antenna in hand.
    val orient = rememberDeviceOrientation()
    val cap = orient.value.azimuthDeg.toDouble()
    val ecart = ((az - cap + 540.0) % 360.0) - 180.0
    val dansLAxe = orient.value.available && kotlin.math.abs(ecart) <= 10.0 && el >= 0
    // Saturated green: read from the corner of the eye, in full sun.
    val teinte = if (dansLAxe) Color(0xFF00C853) else Cyan

    // Background turns green when on axis: visible while turning, no reading needed.
    Row(
        modifier
            .background(
                if (dansLAxe) Color(0xFF00C853).copy(alpha = 0.34f) else Color.Transparent,
                RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        // ---- azimuth dial ----
        Canvas(Modifier.size(52.dp)) {
            val r = size.minDimension / 2f - 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(SpaceSurface, r, c)
            drawCircle(TextLo.copy(alpha = 0.35f), r, c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
            // North: the only labelled mark.
            drawCircle(TextLo, 1.6f, Offset(c.x, c.y - r + 3f))
            // Phone axis: the line to bring onto the satellite.
            if (orient.value.available) {
                drawLine(teinte.copy(alpha = 0.45f),
                    Offset(c.x, c.y), Offset(c.x, c.y - r + 3f), strokeWidth = 1.5f)
            }
            // Needle: satellite bearing relative to the phone heading (plain
            // azimuth when no orientation is available).
            val a = Math.toRadians((if (orient.value.available) ecart else az) - 90.0)
            val bout = Offset(
                c.x + (r - 4f) * cos(a).toFloat(),
                c.y + (r - 4f) * sin(a).toFloat())
            drawLine(
                color = if (el >= 0) teinte else TextLo,
                start = c, end = bout, strokeWidth = 3f)
            drawCircle(if (el >= 0) teinte else TextLo, 2.5f, bout)
        }

        Spacer(Modifier.width(8.dp))
        Column {
            Text("%.0f°".format(az), color = TextHi, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("AZ", color = TextLo, fontSize = 10.sp)
        }

        Spacer(Modifier.width(14.dp))

        // ---- elevation bar ----
        //
        // −10° to +90°: you prepare before AOS, and seeing the mark rise toward
        // the horizon line beats having it pop up.
        Canvas(Modifier.width(10.dp).height(44.dp)) {
            val h = size.height
            drawRoundRect(color = SpaceSurface,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2))
            val part = ((el + 10.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
            val y = h - h * part
            // Horizon reference line.
            val yH = h - h * 0.1f
            drawLine(TextLo.copy(alpha = 0.5f),
                Offset(0f, yH), Offset(size.width, yH), strokeWidth = 1f)
            drawRect(
                color = if (el >= 0) teinte else Amber,
                topLeft = Offset(0f, y - 1.5f),
                size = Size(size.width, 3f))
        }

        Spacer(Modifier.width(8.dp))
        Column {
            Text("%.0f°".format(el),
                color = if (el >= 0) TextHi else Amber, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("ÉL", color = TextLo, fontSize = 10.sp)
        }
    }
}
