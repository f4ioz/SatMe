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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.TimelineTrack
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import java.util.Date

/**
 * Timeline: the elevation curve of every followed satellite over a rolling
 * window that starts *now*. One glance tells you what is coming up, how high it
 * gets and whether two birds overlap — the thing a paper schedule never shows.
 */
@Composable
fun TimelineScreen(ui: UiState, vm: MainViewModel) {
    LaunchedEffect(ui.favorites, ui.satellites.size, ui.observer?.name) {
        if (ui.timelineTracks.isEmpty() && !ui.timelineLoading) vm.computeTimeline()
    }
    // Recompute every 5 min so the window keeps sliding while the page is open.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5 * 60_000L)
            vm.computeTimeline()
        }
    }

    if (ui.favorites.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(t("no_followed_sats"), color = TextLo)
        }
        return
    }

    val minEl = ui.minElevDeg.toDouble()
    val visible = ui.timelineTracks.filter { it.peakDeg >= minEl }
    val quiet = ui.timelineTracks.filter { it.peakDeg < minEl }
    val from = ui.timelineFromMs
    val span = ui.timelineHours * 3_600_000L

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(3, 6, 12, 24).forEach { h ->
                    FilterChip(
                        selected = ui.timelineHours == h,
                        onClick = { vm.setTimelineHours(h) },
                        label = { Text("$h h", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.22f),
                            selectedLabelColor = Cyan)
                    )
                }
                if (ui.timelineLoading) {
                    Spacer(Modifier.width(4.dp))
                    CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                }
            }
        }
        item {
            Text(tf("timeline_header", ui.timelineHours, tzTag(ui.useUtc)),
                color = TextLo, fontSize = 11.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold)
        }
        if (from > 0L) item { TimeRuler(from, span, ui.useUtc) }

        if (visible.isEmpty() && !ui.timelineLoading) {
            item { Text(t("timeline_empty"), color = TextLo, fontSize = 13.sp) }
        }

        items(visible, key = { "tl" + it.catnum }) { track ->
            TimelineRow(track, from, span, ui.nowMs, minEl) {
                // Leave the timeline first, otherwise the detail page would be
                // hidden behind it.
                vm.closeTimeline()
                vm.selectByCatnum(track.catnum)
            }
        }

        if (quiet.isNotEmpty()) {
            item {
                Text(tf("timeline_quiet", quiet.joinToString(", ") { it.name }),
                    color = TextLo.copy(alpha = 0.75f), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Hour marks shared by every row underneath. */
@Composable
private fun TimeRuler(fromMs: Long, spanMs: Long, useUtc: Boolean) {
    val hm = tzFormat("HH:mm", useUtc)
    // Ticks on whole hours inside the window.
    val firstHour = ((fromMs / 3_600_000L) + 1) * 3_600_000L
    val ticks = generateSequence(firstHour) { it + 3_600_000L }
        .takeWhile { it < fromMs + spanMs }
        .toList()
    val step = if (ticks.size > 8) 3 else if (ticks.size > 5) 2 else 1

    val tickColor = TextLo
    // Hour labels are painted straight into the canvas: positioning text at an
    // arbitrary x is trivial there and impossible with a plain Row.
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(
                (tickColor.alpha * 255).toInt(), (tickColor.red * 255).toInt(),
                (tickColor.green * 255).toInt(), (tickColor.blue * 255).toInt())
            textSize = 9.sp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val native = drawContext.canvas.nativeCanvas
        ticks.forEachIndexed { i, ms ->
            if (i % step != 0) return@forEachIndexed
            val x = size.width * ((ms - fromMs).toFloat() / spanMs)
            native.drawText(hm.format(Date(ms)), x, size.height - 6f, paint)
            drawLine(tickColor.copy(alpha = 0.35f), Offset(x, size.height - 4f),
                Offset(x, size.height), strokeWidth = 1.5f)
        }
    }
}

/** One satellite: name + peak on the left, elevation curve filling the row. */
@Composable
private fun TimelineRow(
    track: TimelineTrack, fromMs: Long, spanMs: Long, nowMs: Long,
    minElDeg: Double, onClick: () -> Unit
) {
    val peak = track.peakDeg
    val color = when {
        peak >= 50 -> Cyan
        peak >= 25 -> Aurora
        else -> Amber
    }
    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.name, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 1, modifier = Modifier.weight(1f))
                Text("${peak.toInt()}°", color = color, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            }
            Spacer(Modifier.height(4.dp))
            Canvas(Modifier.fillMaxWidth().height(54.dp)) {
                val w = size.width
                val h = size.height
                fun x(ms: Long) = w * ((ms - fromMs).toFloat() / spanMs)
                fun y(el: Double) = h - (el.coerceIn(0.0, 90.0) / 90.0).toFloat() * h

                // Horizon + minimum-elevation guides.
                drawLine(TextLo.copy(alpha = 0.25f), Offset(0f, h - 1f), Offset(w, h - 1f),
                    strokeWidth = 1f)
                if (minElDeg > 0) {
                    drawLine(TextLo.copy(alpha = 0.20f), Offset(0f, y(minElDeg)), Offset(w, y(minElDeg)),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                }
                drawLine(TextLo.copy(alpha = 0.15f), Offset(0f, y(45.0)), Offset(w, y(45.0)),
                    strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 10f)))

                // Filled lobes: one path per continuous above-horizon segment.
                var i = 0
                val s = track.samples
                while (i < s.size) {
                    if (s[i].second <= 0.0) { i++; continue }
                    val start = i
                    while (i < s.size && s[i].second > 0.0) i++
                    val end = i - 1
                    if (end <= start) continue
                    val fill = Path().apply {
                        moveTo(x(s[start].first), h)
                        for (k in start..end) lineTo(x(s[k].first), y(s[k].second))
                        lineTo(x(s[end].first), h)
                        close()
                    }
                    drawPath(fill, color.copy(alpha = 0.18f))
                    val line = Path().apply {
                        moveTo(x(s[start].first), y(s[start].second))
                        for (k in start + 1..end) lineTo(x(s[k].first), y(s[k].second))
                    }
                    drawPath(line, color, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
                }

                // "Now" marker.
                if (nowMs in fromMs..(fromMs + spanMs)) {
                    val nx = x(nowMs)
                    drawLine(Magenta.copy(alpha = 0.8f), Offset(nx, 0f), Offset(nx, h),
                        strokeWidth = 1.6f)
                }
            }
        }
    }
}
