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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.data.SkedCard
import fr.f4ioz.satcombo.data.SkedPlan
import fr.f4ioz.satcombo.data.SkedStationTrack
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen mutual-visibility "sked" page (hams.at style): pick a satellite and
 * the DX station's grid, compute the windows where the bird is above both
 * horizons over 48 h, then inspect a window with two live polar plots, a time
 * scrubber that animates both sky tracks together, and a one-tap PNG export to
 * share the plan with the sked OM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkedScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val satName = ui.satellites.firstOrNull { it.catalogNumber == ui.skedSatCat }?.name
        ?: t("sked_pick_sat")
    val plan = ui.skedPlans.getOrNull(ui.skedSelectedIndex)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- Inputs card ---
        Surface(color = SpaceCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t("sked_intro"), color = TextLo, fontSize = 12.sp)

                // Satellite picker (dropdown).
                Text(t("sked_sat"), color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                SatDropdown(ui, vm, satName)

                // DX locator + min elevation.
                OutlinedTextField(
                    value = ui.skedOtherLoc,
                    onValueChange = { vm.setSkedOtherLoc(it) },
                    label = { Text(t("sked_loc2"), fontSize = 12.sp) },
                    singleLine = true,
                    isError = ui.skedError == "bad",
                    modifier = Modifier.fillMaxWidth()
                )
                if (ui.skedError == "bad") {
                    Text(t("sked_bad_loc"), color = Magenta, fontSize = 11.sp)
                }

                Text(t("sked_minel2"), color = TextLo, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 5, 10).forEach { v ->
                        FilterChip(
                            selected = ui.skedMinElOther == v,
                            onClick = { vm.setSkedMinElOther(v) },
                            label = { Text("$v°", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                selectedLabelColor = Cyan)
                        )
                    }
                }

                // Local time is only meaningful to the local station; a DX in
                // another timezone reads UTC. The switch is here — and not
                // buried in the settings — because it is a per-sked decision.
                Text(t("sked_tz"), color = TextLo, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to "LOC", true to "UTC").forEach { (utc, lbl) ->
                        FilterChip(
                            selected = ui.useUtc == utc,
                            onClick = { vm.setUseUtc(utc) },
                            label = { Text(lbl, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Amber.copy(alpha = 0.25f),
                                selectedLabelColor = Amber)
                        )
                    }
                }

                Button(
                    onClick = { vm.computeSkedPlans() },
                    enabled = !ui.skedComputing && ui.skedOtherLoc.trim().length >= 4
                        && ui.skedSatCat != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (ui.skedComputing) {
                        CircularProgressIndicator(
                            color = if (isDarkTheme()) Color(0xFF00201D) else Color.White,
                            strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (ui.skedComputing) t("sked_calcing") else t("sked_go"),
                        color = if (isDarkTheme()) Color(0xFF00201D) else Color.White,
                        fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- Empty / no-window states ---
        if (ui.skedComputed && !ui.skedComputing && ui.skedPlans.isEmpty()) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(t("sked_none"), color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
        }

        // --- Window selector ---
        if (ui.skedPlans.isNotEmpty()) {
            Text(t("sked_windows"), color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            // The day label MUST follow the same timezone as the hours below it,
            // otherwise a window at 23:40 UTC shows the *local* (next) day.
            val dfmt = remember(ui.useUtc) {
                SimpleDateFormat("EEE dd/MM", fr.f4ioz.satcombo.i18n.I18n.locale())
                    .apply { if (ui.useUtc) timeZone = TimeZone.getTimeZone("UTC") }
            }
            val hfmt = remember(ui.useUtc) {
                SimpleDateFormat("HH:mm", fr.f4ioz.satcombo.i18n.I18n.locale())
                    .apply { if (ui.useUtc) timeZone = TimeZone.getTimeZone("UTC") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.skedPlans.size) { i ->
                    val p = ui.skedPlans[i]
                    val active = i == ui.skedSelectedIndex
                    Surface(
                        color = if (active) Cyan.copy(alpha = 0.22f) else SpaceCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable { vm.setSkedSelectedIndex(i) }
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(dfmt.format(Date(p.mutualStartMs)) + " " + tzTag(ui.useUtc),
                                color = if (active) Cyan else TextHi,
                                fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("${hfmt.format(Date(p.mutualStartMs))} → ${hfmt.format(Date(p.mutualEndMs))}",
                                color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("${p.mutualDurationSec / 60} min", color = Aurora, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // --- Selected plan: polar plots + animation + export ---
        plan?.let { SkedPlanPanel(it, ui, ctx, scope) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SatDropdown(ui: UiState, vm: MainViewModel, satName: String) {
    var open by remember { mutableStateOf(false) }
    val favs = ui.satellites.filter { it.catalogNumber in ui.favorites }
    val list = (if (favs.isNotEmpty()) favs else ui.satellites).sortedBy { it.name }
    Box {
        Surface(color = SpaceBg, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { open = true }) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(satName, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null, tint = Cyan)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            list.forEach { sat ->
                DropdownMenuItem(
                    text = { Text(sat.name, fontSize = 14.sp) },
                    onClick = { vm.setSkedSat(sat.catalogNumber); open = false }
                )
            }
        }
    }
}

@Composable
private fun SkedPlanPanel(plan: SkedPlan, ui: UiState, ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
    // Animation progress across the union of both passes (0..1). Resets per window.
    var progress by remember(plan) { mutableStateOf(0f) }
    var playing by remember(plan) { mutableStateOf(false) }
    val animMs = 11_000f  // wall-clock duration of one full replay

    LaunchedEffect(playing, plan) {
        if (playing) {
            while (true) {
                delay(33)
                progress = (progress + 33f / animMs).let { if (it >= 1f) 0f else it }
            }
        }
    }

    val spanStart = plan.spanStartMs
    val spanEnd = plan.spanEndMs
    val curT = spanStart + ((spanEnd - spanStart) * progress).toLong()

    val youNow = plan.you.sampleAt(curT)
    val dxNow = plan.dx.sampleAt(curT)
    val mutualNow = plan.bothVisibleAt(curT)

    val hfmt = remember(ui.useUtc) {
        SimpleDateFormat("HH:mm:ss", fr.f4ioz.satcombo.i18n.I18n.locale())
            .apply { if (ui.useUtc) timeZone = TimeZone.getTimeZone("UTC") }
    }
    // The other clock, always shown next to the first: local time only means
    // something to the local station, and a DX in another timezone reads UTC.
    val hfmtAlt = remember(ui.useUtc) {
        SimpleDateFormat("HH:mm:ss", fr.f4ioz.satcombo.i18n.I18n.locale())
            .apply { if (!ui.useUtc) timeZone = TimeZone.getTimeZone("UTC") }
    }
    val altTag = if (ui.useUtc) "LOC" else "UTC"
    // "toi" only makes sense to us — the DX reading the card wants a callsign.
    val meLabel = ui.callsign.trim().uppercase().ifBlank { t("sked_you") }

    val youMutual = remember(plan) {
        plan.you.samples.filter { it.elDeg >= 0.0 && it.tMs in plan.mutualStartMs..plan.mutualEndMs }
            .map { it.azDeg to it.elDeg }
    }
    val dxMutual = remember(plan) {
        plan.dx.samples.filter { it.elDeg >= 0.0 && it.tMs in plan.mutualStartMs..plan.mutualEndMs }
            .map { it.azDeg to it.elDeg }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Two polar plots.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkedStationBlock(
                title = meLabel, locator = plan.you.locator, accent = Cyan,
                track = plan.you, mutualArc = youMutual,
                marker = youNow?.takeIf { it.elDeg >= 0 }?.let { it.azDeg to it.elDeg },
                modifier = Modifier.weight(1f))
            SkedStationBlock(
                title = "DX", locator = plan.dx.locator, accent = Amber,
                track = plan.dx, mutualArc = dxMutual,
                marker = dxNow?.takeIf { it.elDeg >= 0 }?.let { it.azDeg to it.elDeg },
                modifier = Modifier.weight(1f))
        }

        // Live readouts + mutual indicator.
        Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(hfmt.format(Date(curT)) + (if (ui.useUtc) " UTC" else " LOC"),
                            color = TextHi, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(hfmtAlt.format(Date(curT)) + " " + altTag,
                            color = TextLo, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape)
                        .background(if (mutualNow) Color(0xFF2DBE6B) else TextLo))
                    Spacer(Modifier.width(6.dp))
                    Text(if (mutualNow) t("sked_both_visible") else t("sked_not_mutual"),
                        color = if (mutualNow) Color(0xFF2DBE6B) else TextLo,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth()) {
                    AzElReadout(meLabel, Cyan, youNow?.azDeg, youNow?.elDeg, Modifier.weight(1f))
                    AzElReadout("DX", Amber, dxNow?.azDeg, dxNow?.elDeg, Modifier.weight(1f))
                }
                // Scrubber.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { playing = !playing }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) t("sked_pause") else t("sked_play"),
                            tint = Cyan)
                    }
                    Slider(
                        value = progress,
                        onValueChange = { playing = false; progress = it },
                        colors = SliderDefaults.colors(
                            thumbColor = Cyan, activeTrackColor = Cyan),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Summary + export.
        Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryRow(t("sked_sat"), plan.satName)
                SummaryRow(t("sked_common"),
                    "${plan.mutualDurationSec / 60} min · " +
                        fr.f4ioz.satcombo.data.Units.distanceRound(plan.distanceKm, ui.units))
                SummaryRow("$meLabel · ${plan.you.locator}",
                    tf("elmax_short", plan.you.maxElDeg.toInt()))
                SummaryRow("DX · ${plan.dx.locator}",
                    tf("elmax_short", plan.dx.maxElDeg.toInt()))
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                SkedCard.build(ctx, plan, ui.useUtc, ui.callsign, ui.units)
                            }
                            val uri = SkedCard.uri(ctx, file)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_TEXT, SkedCard.shareText(plan, ui.useUtc, ui.callsign, ui.units))
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(send, t("sked_share")))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Aurora),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("sked_share"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SkedStationBlock(
    title: String, locator: String, accent: Color,
    track: SkedStationTrack, mutualArc: List<Pair<Double, Double>>,
    marker: Pair<Double, Double>?, modifier: Modifier = Modifier
) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(5.dp))
                Text(title, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Text(locator, color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            SkedPolarPlot(
                arc = track.arc, mutualArc = mutualArc, marker = marker, accent = accent,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            Text(tf("elmax_short", track.maxElDeg.toInt()), color = TextHi, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun AzElReadout(label: String, accent: Color, az: Double?, el: Double?, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (el != null && el >= 0) {
            Text("Az ${az?.toInt() ?: 0}°  ·  ${"%.1f".format(el)}°",
                color = TextHi, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        } else {
            Text(t("sked_below_horizon"), color = TextLo, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SummaryRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace)
    }
}

/** Sky polar plot: zenith centre, horizon edge, pass arc, highlighted common
 *  window, AOS/LOS markers and an optional live animated marker. */
@Composable
private fun SkedPolarPlot(
    arc: List<Pair<Double, Double>>,
    mutualArc: List<Pair<Double, Double>>,
    marker: Pair<Double, Double>?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val dark = isDarkTheme()
    Canvas(modifier = modifier) {
        val r = min(size.width, size.height) / 2f * 0.84f
        val c = Offset(size.width / 2f, size.height / 2f)
        val grid = if (dark) Color(0xFF2A3647) else Color(0xFFB9C6D8)
        val gridSoft = if (dark) Color(0xFF20293A) else Color(0xFFCFD9E6)

        drawCircle(if (dark) Color(0xFF101824) else Color(0xFFE8EEF6), r, c)
        drawCircle(grid, r, c, style = Stroke(2.2f))
        drawCircle(gridSoft, r * 2f / 3f, c, style = Stroke(1.2f))
        drawCircle(gridSoft, r / 3f, c, style = Stroke(1.2f))
        drawLine(gridSoft, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(gridSoft, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        drawSkedCardinal(c, r, "N", 0); drawSkedCardinal(c, r, "E", 90)
        drawSkedCardinal(c, r, "S", 180); drawSkedCardinal(c, r, "W", 270)

        // Full pass arc (dim accent, dashed).
        if (arc.size > 1) {
            val path = Path()
            arc.forEachIndexed { i, (az, el) ->
                val pt = skedAzElToXy(az, el, c, r)
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            drawPath(path, accent.copy(alpha = 0.45f),
                style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))))
            val aos = skedAzElToXy(arc.first().first, arc.first().second, c, r)
            val los = skedAzElToXy(arc.last().first, arc.last().second, c, r)
            drawCircle(accent, 6f, aos)
            drawCircle(accent, 6f, los, style = Stroke(2.5f))
        }

        // Highlighted common-visibility window (bright magenta).
        if (mutualArc.size > 1) {
            val mp = Path()
            mutualArc.forEachIndexed { i, (az, el) ->
                val pt = skedAzElToXy(az, el, c, r)
                if (i == 0) mp.moveTo(pt.x, pt.y) else mp.lineTo(pt.x, pt.y)
            }
            drawPath(mp, Magenta, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }

        // Live animated marker.
        marker?.let { (az, el) ->
            if (el >= 0) {
                val pt = skedAzElToXy(az, el, c, r)
                drawCircle(accent.copy(alpha = 0.20f), 18f, pt)
                drawCircle(accent, 7f, pt)
                drawCircle(if (dark) Color.White else Color(0xFF1E293B), 7f, pt, style = Stroke(2f))
            }
        }
    }
}

private fun DrawScope.drawSkedCardinal(c: Offset, r: Float, label: String, azDeg: Int) {
    val az = Math.toRadians(azDeg.toDouble())
    val x = c.x + (r + 20f) * sin(az).toFloat()
    val y = c.y - (r + 20f) * cos(az).toFloat()
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#8A98B0")
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        drawText(label, x, y + 8f, paint)
    }
}

private fun skedAzElToXy(azDeg: Double, elDeg: Double, c: Offset, r: Float): Offset {
    val radius = r * ((90.0 - elDeg.coerceIn(0.0, 90.0)) / 90.0).toFloat()
    val az = Math.toRadians(azDeg)
    return Offset(c.x + radius * sin(az).toFloat(), c.y - radius * cos(az).toFloat())
}
