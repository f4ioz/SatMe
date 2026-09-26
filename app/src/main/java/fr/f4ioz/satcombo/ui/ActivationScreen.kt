/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.data.Activation
import fr.f4ioz.satcombo.data.ActivationStore
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Field sessions ("activations"): start one when you get on site, everything
 * logged while it runs belongs to it, stop it when you pack up. The session can
 * then be exported as an A4 sheet (PDF) or as an ADIF subset ready to be merged
 * into the logbook — no manual copying of times, locator and satellites.
 */
@Composable
fun ActivationScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val running = ui.activations.firstOrNull { it.running }

    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    // Ticks once a second so the running duration stays alive.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running?.startMs) {
        while (running != null) { nowMs = System.currentTimeMillis(); delay(1000) }
    }

    val enregistre = rememberEnregistrer()

    /** Saves the activation sheet to a folder, bypassing the share sheet. */
    fun savePdf(a: Activation) {
        vm.exportActivationPdf(a) { uri ->
            enregistre("SatMe-${a.locator.uppercase()}-${nomDate("activation", "pdf")}",
                "application/pdf", depuisUri(ctx, uri))
        }
    }

    fun saveAdif(a: Activation) {
        enregistre(nomDate("SatMe-${a.locator.uppercase()}", "adi"),
            "application/octet-stream", depuisTexte(vm.activationAdif(a)))
    }

    fun sharePdf(a: Activation) {
        vm.exportActivationPdf(a) { uri ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT,
                    "SatMe · ${a.locator.uppercase()}")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(send, t("act_pdf")))
        }
    }

    fun shareAdif(a: Activation) {
        // Same as the full log: a .adi file, importable as is.
        val uri = vm.adifFileUri(vm.activationQsos(a), "activation-" + a.locator.uppercase())
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            putExtra(android.content.Intent.EXTRA_SUBJECT,
                "ADIF · ${a.locator.uppercase()}")
            if (uri != null) {
                type = "application/octet-stream"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, vm.activationAdif(a))
            }
        }
        ctx.startActivity(android.content.Intent.createChooser(send, t("export_adif_chooser")))
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(t("act_intro"), color = TextLo, fontSize = 12.sp)
        }

        // ---------- running session, or the start form ----------
        if (running != null) {
            item {
                Surface(color = SpaceCard, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF2DBE6B)))
                            Spacer(Modifier.width(7.dp))
                            Text(t("act_running"), color = Color(0xFF2DBE6B),
                                fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                letterSpacing = 1.1.sp, modifier = Modifier.weight(1f))
                            Text(hms(running.durationMs(nowMs)), color = TextHi,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
                                fontSize = 18.sp)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(running.locator.uppercase(), color = Cyan,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
                                fontSize = 22.sp)
                            if (running.name.isNotBlank()) {
                                Spacer(Modifier.width(10.dp))
                                Text(running.name, color = TextHi, fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                        val started = tzFormat("EEE dd/MM HH:mm", ui.useUtc).format(Date(running.startMs))
                        Text("$started ${tzTag(ui.useUtc)}", color = TextLo, fontSize = 12.sp)
                        ActivationStats(running, ui)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { vm.stopActivation(running.startMs) },
                                colors = ButtonDefaults.buttonColors(containerColor = Magenta),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Stop, null, tint = Color.White,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(t("act_stop"), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { vm.openPhoto() }, modifier = Modifier.weight(1f)) {
                                Text(t("act_add_photo"), color = Cyan, fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Surface(color = SpaceCard, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.myLocator(), color = Cyan, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black, fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (ui.callsign.isBlank()) t("photo_no_callsign") else ui.callsign,
                                color = if (ui.callsign.isBlank()) Amber else TextHi,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text(t("act_name"), fontSize = 12.sp) },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = note, onValueChange = { note = it },
                            label = { Text(t("act_note"), fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { vm.startActivation(name, note); name = ""; note = "" },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, null,
                                tint = if (isDarkTheme()) Color(0xFF00201D) else Color.White,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t("act_start"),
                                color = if (isDarkTheme()) Color(0xFF00201D) else Color.White,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ---------- past sessions ----------
        val past = ui.activations.filterNot { it.running }
        item {
            Text(t("act_past"), color = TextLo, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (past.isEmpty()) {
            item { Text(t("act_none"), color = TextLo, fontSize = 13.sp) }
        }
        items(past, key = { "act" + it.startMs }) { a ->
            var open by remember(a.startMs) { mutableStateOf(false) }
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.locator.uppercase(), color = Cyan, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (a.name.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(a.name, color = TextHi, fontSize = 13.sp,
                                maxLines = 1, modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                        Text(hms(a.durationMs()), color = TextLo,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    val df = tzFormat("EEE dd/MM HH:mm", ui.useUtc)
                    val hm = tzFormat("HH:mm", ui.useUtc)
                    val period = df.format(Date(a.startMs)) + " → " +
                        (a.endMs?.let { hm.format(Date(it)) } ?: "…") + " " + tzTag(ui.useUtc)
                    Text(period, color = TextLo, fontSize = 12.sp)
                    ActivationStats(a, ui)

                    if (open) {
                        if (a.note.isNotBlank()) {
                            Text(a.note, color = TextLo, fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { sharePdf(a) },
                                colors = ButtonDefaults.buttonColors(containerColor = Aurora),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = Color.White,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("act_pdf"), color = Color.White, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { shareAdif(a) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null, tint = Cyan, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("ADIF", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { confirmDelete = a.startMs }) {
                                Icon(Icons.Default.Delete, t("act_delete"), tint = Magenta)
                            }
                        }
                        // Save rather than share: in the field you want to store it, not send it.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { savePdf(a) }, modifier = Modifier.weight(1f)) {
                                Text("${t("export_save")} PDF", color = Cyan, fontSize = 12.sp)
                            }
                            TextButton(onClick = { saveAdif(a) }, modifier = Modifier.weight(1f)) {
                                Text("${t("export_save")} ADIF", color = Cyan, fontSize = 12.sp)
                            }
                        }
                        if (confirmDelete == a.startMs) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(t("act_confirm_delete"), color = Amber, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                                Button(
                                    onClick = { vm.deleteActivation(a.startMs); confirmDelete = null },
                                    colors = ButtonDefaults.buttonColors(containerColor = Magenta)
                                ) { Text(t("act_delete"), color = Color.White, fontSize = 12.sp) }
                                OutlinedButton(onClick = { confirmDelete = null }) {
                                    Text(t("cancel"), color = TextLo, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Whole-session PDF for the running one is handy too.
        if (running != null) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sharePdf(running) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PictureAsPdf, null, tint = Aurora, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("act_pdf_now"), color = Aurora, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { shareAdif(running) }, modifier = Modifier.weight(1f)) {
                        Text("ADIF", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** QSO count + satellites worked, derived from the log on the fly. */
@Composable
private fun ActivationStats(a: Activation, ui: UiState) {
    val qsos = ActivationStore.qsosOf(a, ui.log)
    val sats = qsos.map { it.satName }.filter { it.isNotBlank() }.distinct()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tf("act_qso_n", qsos.size), color = Amber, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
        if (sats.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(sats.joinToString(" · "), color = TextLo, fontSize = 11.sp, maxLines = 2)
        }
    }
}

/** "1h 04m 12s" style duration. */
private fun hms(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%dh %02dm %02ds".format(h, m, sec) else "%02dm %02ds".format(m, sec)
}
