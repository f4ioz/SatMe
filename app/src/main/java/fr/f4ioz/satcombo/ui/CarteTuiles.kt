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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.domain.JournalPassage

/** Where a (lat, lon) falls on a [CarteTuiles] now: its projection, for the drawing over it. */
class ProjectionCarte(val cx: Double, val cy: Double, val zoom: Double, val largeur: Float, val hauteur: Float) {
    private val monde = 256.0 * Math.pow(2.0, zoom)
    fun x(lon: Double) = ((JournalPassage.mercX(lon) - cx) * monde + largeur / 2).toFloat()
    fun y(lat: Double) = ((JournalPassage.mercY(lat) - cy) * monde + hauteur / 2).toFloat()
}

/**
 * An OpenStreetMap map to draw on — the journal's, made reusable for the
 * sked replay and the radiosonde's flight: framed on [cadre] (again when
 * [cle] changes, as [cadre] grows until a finger moves the map, or on a
 * double tap), moved with a finger, zoomed with two,
 * and [dessin] drawn over the tiles with the map's projection.
 */
@Composable
internal fun CarteTuiles(
    cadre: List<Pair<Double, Double>>,
    cle: Any?,
    modifier: Modifier = Modifier,
    zoomMax: Double = 12.0,
    dessin: (android.graphics.Canvas, ProjectionCarte) -> Unit,
) {
    val fournisseur = remember { MapProviders.byId("OSM") }
    val portee = rememberCoroutineScope()
    val tuiles = remember { TileStore(portee) }
    var w by remember { mutableStateOf(0f) }
    var h by remember { mutableStateOf(0f) }
    var cx by remember(cle) { mutableStateOf(0.5) }
    var cy by remember(cle) { mutableStateOf(0.5) }
    var zoom by remember(cle) { mutableStateOf(2.0) }
    var recadre by remember(cle) { mutableStateOf(0) }
    // Framed again as what is drawn grows (a flight being received), until a finger moves the map.
    var touchee by remember(cle) { mutableStateOf(false) }
    LaunchedEffect(cle, w, h, recadre, if (touchee) -1 else cadre.size) {
        if (w <= 0f || cadre.isEmpty()) return@LaunchedEffect
        val v = JournalPassage.cadreCarte(cadre, w.toDouble(), h.toDouble(), 0.85, zoomMax)
        cx = v.cx; cy = v.cy; zoom = v.zoom
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFFDDE6F1))) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()
            .onSizeChanged { w = it.width.toFloat(); h = it.height.toFloat() }
            .pointerInput(cle) { detectTapGestures(onDoubleTap = { touchee = false; recadre++ }) }
            .pointerInput(cle) {
                detectTransformGestures { centre, dep, facteur, _ ->
                    touchee = true
                    val m = 256.0 * Math.pow(2.0, zoom)
                    val qx = cx + (centre.x - size.width / 2) / m
                    val qy = cy + (centre.y - size.height / 2) / m
                    zoom = (zoom + kotlin.math.log2(facteur.toDouble())).coerceIn(0.0, fournisseur.maxZ.toDouble())
                    val m2 = 256.0 * Math.pow(2.0, zoom)
                    cx = qx - (centre.x - size.width / 2) / m2 - dep.x / m2
                    cy = (qy - (centre.y - size.height / 2) / m2 - dep.y / m2).coerceIn(0.0, 1.0)
                }
            }) {
            val m = 256.0 * Math.pow(2.0, zoom)
            val z = kotlin.math.floor(zoom).toInt().coerceIn(0, fournisseur.maxZ)
            val n = 1 shl z
            val cote = (m / n).toFloat()
            val x0 = kotlin.math.floor((cx * m - size.width / 2) / cote).toInt()
            val x1 = kotlin.math.floor((cx * m + size.width / 2) / cote).toInt()
            val y0 = kotlin.math.floor((cy * m - size.height / 2) / cote).toInt().coerceAtLeast(0)
            val y1 = kotlin.math.floor((cy * m + size.height / 2) / cote).toInt().coerceAtMost(n - 1)
            for (tx in x0..x1) for (ty in y0..y1) {
                val img = tuiles.get(fournisseur, z, tx, ty) ?: continue
                drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset((tx * cote - cx * m + size.width / 2).toInt(), (ty * cote - cy * m + size.height / 2).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(cote.toInt() + 1, cote.toInt() + 1))
            }
            val p = ProjectionCarte(cx, cy, zoom, size.width, size.height)
            drawIntoCanvas { c -> dessin(c.nativeCanvas, p) }
        }
        Text(fournisseur.attribution, color = androidx.compose.ui.graphics.Color(0xFF333333), fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp))
    }
}
