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
                            Icon(Icons.Default.Refresh, null, tint = Cyan,
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
                Text(t("rotor_title"), color = TextHi,
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
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
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
