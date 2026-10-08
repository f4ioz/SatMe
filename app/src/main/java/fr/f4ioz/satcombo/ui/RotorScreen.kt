/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import kotlin.math.roundToInt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import java.util.Locale

/**
 * Mast control.
 *
 * The first SatMe screen that moves something heavy, and it is built around
 * that. State comes first — target, read-back position and the gap between
 * them — because that is all you look at when the mast misbehaves. Emergency
 * stop is at the top, red, reachable without scrolling: a stop button you have
 * to look for is not a stop button.
 *
 * The position shown is what the controller reports, not what was requested.
 * A mast that never got the command and one still turning look identical;
 * only the read-back tells them apart.
 */
@Composable
fun RotorScreen(ui: UiState, vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---- warning -------------------------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                // The break-in banner is gone (the rotor has run on a real tower).
                // The safety note stays: a motor on top of a mast deserves the
                // emergency stop within reach, proven or not.
                Text(t("rotor_safety"), color = Amber,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(t("rotor_desc"), color = TextLo, fontSize = 12.sp)
            }
        }

        // ---- state ---------------------------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (ui.rotorConnected) Aurora else TextLo))
                    Spacer(Modifier.width(8.dp))
                    Text(t("rotor_status"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (ui.rotorStatus.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(ui.rotorStatus, color = TextLo, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                AimLine(t("rotor_target"), ui.rotorTargetAz, ui.rotorTargetEl,
                    if (ui.rotorFlipped) Magenta else Cyan)
                AimLine(t("rotor_actual"), ui.rotorActualAz, ui.rotorActualEl, TextHi)
                // The dial: the stop, the dead zone, the overlap, the pass, where the cable is.
                Spacer(Modifier.height(10.dp))
                CadranRotor(ui)
                if (ui.rotorDeroule.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(ui.rotorDeroule, color = if (ui.rotorDerouleAlerte) Magenta else Amber,
                        fontWeight = if (ui.rotorDerouleAlerte) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                }
                if (ui.rotorOutOfRange) {
                    Spacer(Modifier.height(6.dp))
                    Text(t("rotor_out_of_range"), color = Amber, fontSize = 12.sp)
                }
                if (ui.rotorEnabled && ui.livePosition == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(t("rotor_no_target"), color = TextLo, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.rotorConnected) {
                        OutlinedButton(onClick = vm::disconnectRotor,
                            modifier = Modifier.weight(1f)) {
                            Text(t("rotor_disconnect"), fontSize = 13.sp)
                        }
                    } else {
                        Button(onClick = vm::connectRotor, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                            Text(t("rotor_connect"), color = Color.Black, fontSize = 13.sp)
                        }
                    }
                    OutlinedButton(onClick = vm::rotorParkNow, modifier = Modifier.weight(1f)) {
                        Text(t("rotor_park_now"), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Emergency stop: full width, red, always in the same place. It
                // also stops tracking — otherwise it would only pause for a second.
                Button(onClick = vm::rotorStopNow, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Magenta)) {
                    Icon(Icons.Default.Stop, null, tint = Color.Black,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("rotor_stop"), color = Color.Black,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // ---- manual test ---------------------------------------------------
        // Drive the rotor to given angles and show its real position, to test
        // without a satellite. Placed high on purpose: it is the first thing
        // you do after plugging in a cable, and it must work right away.
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("rotor_manual_title"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(t("rotor_manual_hint"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                RotorNumber(t("rotor_manual_az"), ui.rotorManualAz, 5, "°") {
                    vm.setRotorManualAz(it)
                }
                RotorNumber(t("rotor_manual_el"), ui.rotorManualEl, 5, "°") {
                    vm.setRotorManualEl(it)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::rotorGotoManual, modifier = Modifier.fillMaxWidth(),
                    enabled = ui.rotorConnected,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                    Text(t("rotor_manual_go"), color = Color.Black,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(t("rotor_jog"), color = TextHi, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in listOf(-10, -1, 1, 10)) {
                        OutlinedButton(onClick = { vm.rotorJog(d, 0) },
                            enabled = ui.rotorConnected,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp)) {
                            Text((if (d > 0) "Az +" else "Az ") + d, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in listOf(-10, -1, 1, 10)) {
                        OutlinedButton(onClick = { vm.rotorJog(0, d) },
                            enabled = ui.rotorConnected,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp)) {
                            Text((if (d > 0) "Él +" else "Él ") + d, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = SpaceSurface)
                Spacer(Modifier.height(10.dp))
                // Raw TX/RX frames. Useless when everything works, the only useful
                // thing when nothing does: a port that opens with nobody answering
                // looks exactly like a mast still turning.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("rotor_frames"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = vm::rotorReadNow, enabled = ui.rotorConnected,
                        contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(t("rotor_read_now"), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("→ " + ui.rotorLastSent.ifBlank { "—" },
                    color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("← " + ui.rotorLastReply.ifBlank { t("rotor_frame_none") },
                    color = if (ui.rotorLastReply.isBlank()) Amber else Aurora,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        // ---- tracking ------------------------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                RotorToggle(t("rotor_enable"), ui.rotorEnabled, vm::setRotorEnabled)
                Text(t("rotor_enable_hint"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                RotorToggle(t("rotor_sim"), ui.rotorSim, vm::setRotorSim)
                Text(t("rotor_sim_hint"), color = TextLo, fontSize = 11.sp)
            }
        }

        // ---- link ----------------------------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("rotor_link"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RotorChip(t("rotor_link_gs232"), ui.rotorLink != "ROTCTLD") {
                        vm.setRotorLink("GS232")
                    }
                    RotorChip(t("rotor_link_rotctld"), ui.rotorLink == "ROTCTLD") {
                        vm.setRotorLink("ROTCTLD")
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (ui.rotorLink == "ROTCTLD") {
                    OutlinedTextField(
                        value = ui.rotorHost,
                        onValueChange = { vm.setRotorHost(it.trim()) },
                        label = { Text(t("rotor_host"), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    RotorNumber(t("rotor_port"), ui.rotorPort, 1, "") { vm.setRotorPort(it) }
                } else {
                    RotorNumber(t("rotor_usb_index"), ui.rotorUsbIndex, 1, "") {
                        vm.setRotorUsbIndex(it)
                    }
                    Text(t("rotor_usb_hint"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("rotor_devices"), color = TextHi,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = vm::rotorRequestPermissions,
                            contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Icon(Icons.Default.Refresh, t("refresh"), tint = Cyan,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                    if (ui.rotorDevices.isEmpty()) {
                        Text(t("rotor_none"), color = TextLo, fontSize = 12.sp)
                    } else {
                        ui.rotorDevices.forEachIndexed { i, nom ->
                            Text("$i · $nom",
                                color = if (i == ui.rotorUsbIndex) Cyan else TextLo,
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(t("rotor_baud"), color = TextHi, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (b in listOf(4800, 9600, 19200, 38400)) {
                            RotorChip(b.toString(), ui.rotorBaud == b) { vm.setRotorBaud(b) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    RotorToggle(t("rotor_auto_port"), ui.rotorAutoPort, vm::setRotorAutoPort)
                    Text(t("rotor_auto_port_hint"), color = TextLo, fontSize = 11.sp)
                    if (ui.rotorDiag.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(t("rotor_diag_title"), color = TextHi,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Surface(color = SpaceSurface, shape = RoundedCornerShape(8.dp)) {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                ui.rotorDiag.forEach { ligne ->
                                    Text(ligne, color = TextLo,
                                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- mast mechanics ------------------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                // Its own title: the screen is already "Az/el rotator".
                Text(t("rotor_mecanique"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(t("rotor_max_az"), color = TextHi, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in listOf(360, 450, 540)) {
                        RotorChip("$a°", ui.rotorMaxAz == a) { vm.setRotorMaxAz(a) }
                    }
                }
                Text(t("rotor_max_az_hint"), color = TextLo, fontSize = 11.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SpaceSurface)
                // The end stop: where the antennas cannot turn further (a G-5400 / G-5500 often south).
                Text(t("rotor_az_stop"), color = TextHi, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RotorChip(t("rotor_az_stop_north"), ui.rotorAzStop != "SOUTH") { vm.setRotorAzStop("NORTH") }
                    RotorChip(t("rotor_az_stop_south"), ui.rotorAzStop == "SOUTH") { vm.setRotorAzStop("SOUTH") }
                }
                Text(t("rotor_az_stop_hint"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                RotorToggle(t("rotor_az_from_stop"), ui.rotorAzFromStop, vm::setRotorAzFromStop)
                Text(t("rotor_az_from_stop_hint"), color = TextLo, fontSize = 11.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SpaceSurface)
                // The dead zone: within it behind the stop the antennas wait rather than go round.
                RotorNumber(t("rotor_max_error"), ui.rotorMaxError, 1, "°") { vm.setRotorMaxError(it) }
                Text(t("rotor_max_error_hint"), color = TextLo, fontSize = 11.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SpaceSurface)
                RotorNumber(t("rotor_max_el"), ui.rotorMaxEl, 5, "°") { vm.setRotorMaxEl(it) }
                RotorNumber(t("rotor_deadband"), ui.rotorDeadband, 1, "°") {
                    vm.setRotorDeadband(it)
                }
                Text(t("rotor_deadband_hint"), color = TextLo, fontSize = 11.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SpaceSurface)
                RotorToggle(t("rotor_az_only"), ui.rotorAzOnly, vm::setRotorAzOnly)
                Text(t("rotor_az_only_hint"), color = TextLo, fontSize = 11.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SpaceSurface)
                RotorToggle(t("rotor_flip"), ui.rotorFlip, vm::setRotorFlip)
                Text(t("rotor_flip_hint"), color = TextLo, fontSize = 11.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SpaceSurface)
                RotorNumber(t("rotor_min_el"), ui.rotorMinEl, 1, "°") { vm.setRotorMinEl(it) }
                Text(t("rotor_min_el_hint"), color = TextLo, fontSize = 11.sp)
                RotorNumber(t("rotor_park_az"), ui.rotorParkAz, 5, "°") { vm.setRotorParkAz(it) }
                RotorNumber(t("rotor_park_el"), ui.rotorParkEl, 5, "°") { vm.setRotorParkEl(it) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SpaceSurface)
                // Pre-positioning: the mast goes to wait where the satellite will
                // rise, instead of parking and setting off again.
                RotorNumber(t("rotor_pre_aos"), ui.rotorPreAos, 1, " min") {
                    vm.setRotorPreAos(it)
                }
                Text(t("rotor_pre_aos_hint"), color = TextLo, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** A target or position in large monospaced digits. */
/**
 * The rotor seen from above: its end stop (red), the dead zone around it
 * (orange: within it the antennas wait rather than go round), the overlap of
 * a 450° rotator, the pass (cyan, elevation inward), the antennas (white) and
 * the target (dashed), and how far the cable has turned from the stop.
 */
@Composable
private fun CadranRotor(ui: UiState) {
    val butee = if (ui.rotorAzStop == "SOUTH") 180.0 else 0.0
    val zone = ui.rotorMaxError.toDouble()
    val course = ui.rotorMaxAz.toDouble()
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 24.dp)) {
        val cx = size.width / 2; val cy = size.height / 2
        val r = kotlin.math.min(cx, cy) * 0.78f
        fun pt(az: Double, rr: Float) = androidx.compose.ui.geometry.Offset(
            cx + rr * kotlin.math.sin(Math.toRadians(az)).toFloat(), cy - rr * kotlin.math.cos(Math.toRadians(az)).toFloat())
        val boite = androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r)
        drawCircle(Color(0xFF101A23), r, androidx.compose.ui.geometry.Offset(cx, cy))
        for (e in listOf(30.0, 60.0)) drawCircle(Color(0xFF22303B), r * ((90 - e) / 90).toFloat(),
            androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1f * dens))
        drawCircle(Color(0xFF3A4A57), r, androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * dens))
        // The dead zone, each side of the stop.
        drawArc(Amber.copy(alpha = 0.28f), (butee - zone - 90).toFloat(), (2 * zone).toFloat(), true, boite.topLeft, boite.size)
        // The overlap of a 450° (or 540°) rotator: the azimuths it can reach twice.
        if (course > 360.0) {
            val rr = r * 1.10f
            drawArc(Cyan.copy(alpha = 0.55f), (butee - 90).toFloat(), (course - 360.0).toFloat(), false,
                androidx.compose.ui.geometry.Offset(cx - rr, cy - rr), androidx.compose.ui.geometry.Size(2 * rr, 2 * rr),
                style = androidx.compose.ui.graphics.drawscope.Stroke(5f * dens))
        }
        // How far the cable has turned from the stop (0 to the rotor's travel).
        ui.rotorCmdAz?.let { cmd ->
            val fait = (cmd - butee).coerceIn(0.0, course)
            val rr = r * 1.20f
            drawArc(Magenta.copy(alpha = 0.7f), (butee - 90).toFloat(), fait.toFloat(), false,
                androidx.compose.ui.geometry.Offset(cx - rr, cy - rr), androidx.compose.ui.geometry.Size(2 * rr, 2 * rr),
                style = androidx.compose.ui.graphics.drawscope.Stroke(3f * dens))
        }
        // The pass, elevation inward.
        if (ui.passTrack.size > 1) {
            val chemin = androidx.compose.ui.graphics.Path()
            ui.passTrack.forEachIndexed { i, (az, el) ->
                val p = pt(az, r * ((90 - el.coerceIn(0.0, 90.0)) / 90).toFloat())
                if (i == 0) chemin.moveTo(p.x, p.y) else chemin.lineTo(p.x, p.y)
            }
            drawPath(chemin, Cyan, style = androidx.compose.ui.graphics.drawscope.Stroke(3f * dens))
            val (az0, el0) = ui.passTrack.first()
            drawCircle(Cyan, 5f * dens, pt(az0, r * ((90 - el0.coerceIn(0.0, 90.0)) / 90).toFloat()))
        }
        // The antennas' planned path, when the pass crosses the stop (white, dotted).
        if (ui.rotorChemin.size > 1) {
            val chemin = androidx.compose.ui.graphics.Path()
            ui.rotorChemin.forEachIndexed { i, (az, el) ->
                val p = pt(az, r * ((90 - el.coerceIn(0.0, 90.0)) / 90).toFloat())
                if (i == 0) chemin.moveTo(p.x, p.y) else chemin.lineTo(p.x, p.y)
            }
            drawPath(chemin, Color.White.copy(alpha = 0.85f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * dens,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f * dens, 5f * dens))))
        }
        // The stop.
        // Outside the circle, and thin inside: the cardinal letter stays readable.
        drawLine(Color(0xFFE5484D), pt(butee, r * 0.97f), pt(butee, r * 1.16f), 5f * dens)
        drawLine(Color(0xFFE5484D).copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(cx, cy), pt(butee, r * 0.80f), 1.5f * dens)
        // The target (dashed) and the antennas (white).
        // At their elevation, as the pass: the centre is the zenith (a flipped mast shown where it really aims).
        fun rEl(el: Double?) = r * ((90 - ((if ((el ?: 0.0) > 90.0) 180.0 - el!! else el ?: 0.0)).coerceIn(0.0, 90.0)) / 90).toFloat()
        fun azVu(az: Double, el: Double?) = if ((el ?: 0.0) > 90.0) az + 180.0 else az
        ui.rotorTargetAz?.let { a ->
            val p = pt(azVu(a, ui.rotorTargetEl), rEl(ui.rotorTargetEl))
            drawLine(Cyan, androidx.compose.ui.geometry.Offset(cx, cy), p, 2f * dens,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f * dens, 6f * dens)))
            drawCircle(Cyan, 7f * dens, p, style = androidx.compose.ui.graphics.drawscope.Stroke(2f * dens))
        }
        ui.rotorActualAz?.let { a ->
            val p = pt(azVu(a, ui.rotorActualEl), rEl(ui.rotorActualEl))
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(cx, cy), p, 3f * dens)
            drawCircle(Color.White, 6f * dens, p)
        }
        drawIntoCanvasTexte(this, cx, cy, r, butee, dens)
    }
    JaugeElevation(ui)
    Spacer(Modifier.height(4.dp))
    Text(t("rotor_cadran_legende"), color = TextLo, fontSize = 11.sp)
    ui.rotorCmdAz?.let { cmd ->
        Text(tf("rotor_cadran_cable", ((cmd - butee).coerceIn(0.0, course)).roundToInt(), course.roundToInt()), color = TextLo, fontSize = 11.sp)
    }
    // Only once the plan is made (rotor connected): before, nothing is known of this pass's turn.
    if (ui.passTrack.isNotEmpty() && ui.rotorConnected && ui.rotorCoverage != null) {
        val heure = remember { java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        val texte = when {
            ui.rotorChemin.isEmpty() -> tf("rotor_cadran_pas_de_tour", ui.rotorMaxError)
            ui.rotorDerouleAt != null -> tf("rotor_chemin_tour", heure.format(java.util.Date(ui.rotorDerouleAt ?: 0L)), ui.rotorTourS)
            else -> tf("rotor_chemin_sans_tour", (ui.rotorEcartMax ?: 0.0).roundToInt(),
                ui.rotorEcartMaxAt?.let { heure.format(java.util.Date(it)) } ?: "—")
        }
        Text(texte, color = if (ui.rotorChemin.isEmpty()) TextLo else Amber, fontSize = 12.sp)
    }
}

/**
 * The elevation, seen from the side: the horizon at the bottom, the zenith up
 * (and the far horizon on a rotator that flips, 0 to 180°); the antennas in
 * white, the target dashed.
 */
@Composable
private fun JaugeElevation(ui: UiState) {
    val max = if (ui.rotorFlip) 180.0 else ui.rotorMaxEl.toDouble().coerceIn(90.0, 180.0)
    val dens = androidx.compose.ui.platform.LocalDensity.current.density
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(if (max > 90.0) 90.dp else 110.dp)) {
            // Pivot bottom-left for 0..90, bottom-centre for 0..180.
            val r = if (max > 90.0) kotlin.math.min(size.width / 2, size.height) * 0.92f else kotlin.math.min(size.width * 0.8f, size.height) * 0.92f
            val ox = if (max > 90.0) size.width / 2 else size.width * 0.12f
            val oy = size.height - 4f * dens
            fun pt(el: Double, rr: Float) = androidx.compose.ui.geometry.Offset(
                ox + rr * kotlin.math.cos(Math.toRadians(el)).toFloat(), oy - rr * kotlin.math.sin(Math.toRadians(el)).toFloat())
            // The scale: an arc from the horizon to the zenith (and beyond when flipping), every 30°.
            val chemin = androidx.compose.ui.graphics.Path()
            var e = 0.0
            while (e <= max + 1e-9) { val p = pt(e, r); if (e == 0.0) chemin.moveTo(p.x, p.y) else chemin.lineTo(p.x, p.y); e += 2.0 }
            drawPath(chemin, Color(0xFF3A4A57), style = androidx.compose.ui.graphics.drawscope.Stroke(2f * dens))
            drawLine(Color(0xFF3A4A57), pt(0.0, r), pt(if (max > 90.0) 180.0 else 0.0, if (max > 90.0) r else 0f), 1.5f * dens)
            var g = 0.0
            while (g <= max + 1e-9) { drawLine(Color(0xFF5A6A77), pt(g, r * 0.92f), pt(g, r), 1.5f * dens); g += 30.0 }
            ui.rotorTargetEl?.let { el ->
                drawLine(Cyan, androidx.compose.ui.geometry.Offset(ox, oy), pt(el.coerceIn(0.0, max), r), 2f * dens,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f * dens, 6f * dens)))
            }
            ui.rotorActualEl?.let { el ->
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(ox, oy), pt(el.coerceIn(0.0, max), r), 3f * dens)
                drawCircle(Color.White, 5f * dens, pt(el.coerceIn(0.0, max), r))
            }
        }
        Column(Modifier.padding(start = 8.dp)) {
            Text(t("rotor_elevation"), color = TextLo, fontSize = 11.sp)
            Text(ui.rotorActualEl?.let { "%.0f°".format(Locale.US, it) } ?: "—", color = TextHi,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(ui.rotorTargetEl?.let { "→ %.0f°".format(Locale.US, it) } ?: "", color = Cyan,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
    }
}

/** N, E, S, W and the stop's name, in the dial. */
private fun drawIntoCanvasTexte(d: androidx.compose.ui.graphics.drawscope.DrawScope, cx: Float, cy: Float, r: Float, butee: Double, dens: Float) {
    d.drawIntoCanvas { c ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(154, 167, 180); textSize = 13f * dens; textAlign = android.graphics.Paint.Align.CENTER }
        for ((n, a) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, if (fr.f4ioz.satcombo.i18n.I18n.current() == fr.f4ioz.satcombo.i18n.Lang.FR) "O" to 270.0 else "W" to 270.0)) {
            val rr = r * 0.88f
            c.nativeCanvas.drawText(n, cx + rr * kotlin.math.sin(Math.toRadians(a)).toFloat(),
                cy - rr * kotlin.math.cos(Math.toRadians(a)).toFloat() + 5f * dens, p)
        }
        p.color = android.graphics.Color.rgb(229, 72, 77); p.textSize = 11f * dens
        val rr = r * 1.30f
        c.nativeCanvas.drawText(t("rotor_cadran_butee"), cx + rr * kotlin.math.sin(Math.toRadians(butee)).toFloat(),
            cy - rr * kotlin.math.cos(Math.toRadians(butee)).toFloat() + 4f * dens, p)
    }
}

@Composable
private fun AimLine(label: String, az: Double?, el: Double?, tint: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            if (az == null || el == null) "—"
            else String.format(Locale.US, "%.1f°  /  %.1f°", az, el),
            color = tint, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun RotorToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).toggleable(value = checked, role = Role.Switch,
            onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
    }
}

@Composable
private fun RotorChip(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (on) Cyan else SpaceSurface,
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(label, color = if (on) Color.Black else TextHi, fontSize = 12.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

/**
 * A stepped numeric setting.
 *
 * No keyboard on purpose: set once, counted in tens, and clamping lives in
 * the settings — typing 5000 and seeing it silently turn into 540 teaches
 * nobody anything.
 */
@Composable
private fun RotorNumber(label: String, value: Int, step: Int, unit: String,
                        onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(value - step) },
            contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text("−", fontSize = 15.sp)
        }
        Text("$value$unit", color = Cyan, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 10.dp))
        OutlinedButton(onClick = { onChange(value + step) },
            contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text("+", fontSize = 15.sp)
        }
    }
}
