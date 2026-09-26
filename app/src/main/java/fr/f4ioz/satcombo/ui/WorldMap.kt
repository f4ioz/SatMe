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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import fr.f4ioz.satcombo.R
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.ui.theme.Amber
import fr.f4ioz.satcombo.ui.theme.Aurora
import fr.f4ioz.satcombo.ui.theme.Cyan
import fr.f4ioz.satcombo.ui.theme.Magenta
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equirectangular world map (GPredict/Look4Sat-style): ground track over one
 * orbit, live footprint circle, satellite dot and QTH marker.
 */
@Composable
fun WorldMap(
    groundTrack: List<Pair<Double, Double>>, // (lat, lon)
    position: SatPosition?,
    observer: Observer?,
    modifier: Modifier = Modifier
) {
    val dark = fr.f4ioz.satcombo.ui.theme.isDarkTheme()
    Box(modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(14.dp))
        .background(if (dark) Color(0xFF0B1018) else Color(0xFFDDE6F1))) {
        // **Les côtes sont dessinées, plus photographiées.**
        //
        // Le planisphère était une image dont la provenance s'était perdue —
        // impossible à redistribuer dans une source publique sans savoir sous
        // quelles conditions elle avait été faite. Les mêmes côtes sont tracées
        // depuis `land.json`, qui vient de Natural Earth, dans le domaine
        // public. C'est aussi le tracé qu'emploie la carte des locators : une
        // seule source pour un seul monde.
        val terres = rememberLandPath()
        Canvas(Modifier.matchParentSize()) {
            val p = terres ?: return@Canvas
            // Le tracé est en degrés : 360 de large, 180 de haut.
            withTransform({ scale(size.width / 360f, size.height / 180f, Offset.Zero) }) {
                drawPath(p, color = if (dark) Color(0xFF16232F) else Color(0xFFBFD2E4))
                drawPath(p, color = if (dark) Color(0xFF2A3D4E) else Color(0xFF8FA8BF),
                    style = Stroke(width = 360f / size.width))
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            fun xy(lat: Double, lon: Double) =
                Offset(((lon + 180.0) / 360.0 * w).toFloat(), ((90.0 - lat) / 180.0 * h).toFloat())

            // Ground track, split at the antimeridian.
            if (groundTrack.size > 1) {
                for (i in 1 until groundTrack.size) {
                    val (la1, lo1) = groundTrack[i - 1]
                    val (la2, lo2) = groundTrack[i]
                    if (kotlin.math.abs(lo2 - lo1) > 180.0) continue
                    drawLine(Aurora.copy(alpha = 0.8f), xy(la1, lo1), xy(la2, lo2), 2.5f)
                }
            }

            position?.let { p ->
                // Footprint: angular radius acos(Re/(Re+h)) around the SSP.
                val re = 6371.0
                val hKm = if (p.altKm > 0) p.altKm else 500.0
                val theta = acos((re / (re + hKm)).coerceIn(0.0, 1.0))
                val lat1 = Math.toRadians(p.latDeg)
                val lon1 = Math.toRadians(p.lonDeg)
                var prev: Offset? = null
                var prevLon = 0.0
                for (b in 0..72) {
                    val brg = Math.toRadians(b * 5.0)
                    val lat2 = asin(sin(lat1) * cos(theta) + cos(lat1) * sin(theta) * cos(brg))
                    val lon2 = lon1 + atan2(
                        sin(brg) * sin(theta) * cos(lat1),
                        cos(theta) - sin(lat1) * sin(lat2))
                    var lonD = Math.toDegrees(lon2)
                    while (lonD > 180) lonD -= 360; while (lonD < -180) lonD += 360
                    val pt = xy(Math.toDegrees(lat2), lonD)
                    if (prev != null && kotlin.math.abs(lonD - prevLon) < 180.0)
                        drawLine(Cyan.copy(alpha = 0.55f), prev!!, pt, 1.8f)
                    prev = pt; prevLon = lonD
                }
                // Satellite dot.
                val sp = xy(p.latDeg, p.lonDeg)
                val col = if (p.sunlit) Amber else Aurora
                drawCircle(col.copy(alpha = 0.25f), 12f, sp)
                drawCircle(col, 6f, sp)
                drawCircle(if (dark) Color.White else Color(0xFF1E293B), 6f, sp, style = Stroke(1.6f))
            }

            // QTH marker.
            observer?.let { o ->
                val qp = xy(o.latDeg, o.lonDeg)
                drawCircle(Magenta, 5f, qp)
                drawCircle(if (dark) Color.White else Color(0xFF1E293B), 5f, qp, style = Stroke(1.5f))
            }
        }
    }
}

