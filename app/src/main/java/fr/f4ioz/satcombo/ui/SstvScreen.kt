/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.SstvHub
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SSTV: the picture side of the pass recorder.
 *
 * While a recording runs the engine watches the audio for a VIS header and
 * paints the picture as it arrives — nothing to arm, nothing to time. Below
 * that, the pictures already on disk, and a way to re-run any past recording
 * through the decoder (an MP3 made before this feature existed still holds its
 * images).
 */
@Composable
fun SstvScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by SstvHub.state.collectAsState()

    var gallery by remember { mutableStateOf(SstvHub.shots(ctx)) }
    var pickRecording by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    // Filter by satellite: a season mixes ISS, repeaters and tests, and
    // you almost always want "the ISS pictures".
    var satFilter by remember { mutableStateOf("") }

    // Refresh the gallery whenever the engine says it has written something.
    LaunchedEffect(st.savedCount, st.fileImages, st.lastSaved) {
        gallery = SstvHub.shots(ctx)
    }
    // And once a second while decoding, so a long re-decode fills in live.
    LaunchedEffect(st.fileProgress >= 0f) {
        while (st.fileProgress >= 0f) { delay(1000); gallery = SstvHub.shots(ctx) }
    }

    val sats = remember(gallery) {
        gallery.map { it.second.satName }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val shown = remember(gallery, satFilter) {
        if (satFilter.isBlank()) gallery else gallery.filter { it.second.satName == satFilter }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // ------------------------------------------------------------- live
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, null,
                            tint = if (st.listening) Cyan else TextLo,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("sstv_live_title"), color = TextHi,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(sstvStatusLine(st),
                        color = if (st.modeName != null) Aurora
                                else if (st.listening) Cyan else TextLo,
                        fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    SstvModeControls(st)
                    Spacer(Modifier.height(4.dp))
                    Text(t("sstv_force_hint"), color = TextLo, fontSize = 10.sp)
                    if (st.modeName != null) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { st.progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Aurora, trackColor = SpaceSurface)
                    }
                    st.preview?.let { bmp ->
                        Spacer(Modifier.height(10.dp))
                        Image(
                            bitmap = bmp.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp)))
                    }
                    // Where the decoded audio comes from.
                    //
                    // USB input already worked (the recorder opens USB inputs since
                    // 18.10), but the setting lived three screens away in the recorder
                    // settings, and nothing here hinted that an IC-9700 on USB can feed
                    // the decoder directly. No audio cable between rig and phone: no
                    // hiss, no level to set, no room noise in the picture.
                    Spacer(Modifier.height(10.dp))
                    Text(t("sstv_audio_source"), color = TextHi, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)) {
                        listOf("MIC" to t("sstv_audio_phone"),
                               "BT" to t("sstv_audio_bt"),
                               "USB" to t("sstv_audio_usb")).forEach { (cle, nom) ->
                            FilterChip(selected = ui.recorderSource == cle,
                                onClick = { vm.setRecorderSource(cle) },
                                label = { Text(nom, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                    selectedLabelColor = Cyan))
                        }
                    }
                    if (ui.recorderSource == "USB") {
                        val entrees = remember(st.listening) {
                            fr.f4ioz.satcombo.audio.RecorderService.usbInputs(ctx)
                        }
                        Text(
                            if (entrees.isEmpty()) t("sstv_audio_usb_none")
                            else tf("sstv_audio_usb_found", entrees.joinToString(", ")),
                            color = if (entrees.isEmpty()) Amber else Aurora,
                            fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(t("sstv_audio_hint"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    if (!st.listening) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("sstv_live_hint"), color = TextLo, fontSize = 11.sp)
                    }
                }
            }
        }

        // -------------------------------------------------------- re-decode
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("sstv_file_title"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(t("sstv_file_hint"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                    if (st.fileProgress >= 0f) {
                        Text(st.fileName ?: "", color = Cyan, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { st.fileProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Cyan, trackColor = SpaceSurface)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { SstvHub.cancelFileDecode() }) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("cancel"))
                        }
                    } else {
                        Button(
                            onClick = { pickRecording = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("sstv_pick_recording"), color = Color.Black,
                                fontWeight = FontWeight.Bold)
                        }
                        if (st.fileName != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(tf("sstv_file_done", st.fileName!!, st.fileImages),
                                color = TextLo, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- gallery
        item {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Icon(Icons.Default.ImageIcon, null, tint = TextLo,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tf("sstv_gallery", shown.size), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        if (sats.size > 1) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = satFilter.isBlank(),
                        onClick = { satFilter = "" },
                        label = { Text(t("sstv_filter_all"), fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.22f),
                            selectedLabelColor = Cyan, labelColor = TextLo))
                    sats.forEach { sn ->
                        FilterChip(selected = satFilter == sn,
                            onClick = { satFilter = if (satFilter == sn) "" else sn },
                            label = { Text(sn, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.22f),
                                selectedLabelColor = Cyan, labelColor = TextLo))
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                Text(t("sstv_gallery_empty"), color = TextLo, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        items(shown, key = { it.first.absolutePath }) { (f, shot) ->
            SstvThumb(f, shot, ui.useUtc, onOpen = { viewing = f }, onDeleted = {
                SstvHub.delete(f)
                gallery = SstvHub.shots(ctx)
            })
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (pickRecording) {
        RecordingPicker(
            onDismiss = { pickRecording = false },
            onPick = { mp3 ->
                pickRecording = false
                scope.launch {
                    withContext(Dispatchers.IO) { SstvHub.decodeFile(ctx, mp3) }
                    gallery = SstvHub.shots(ctx)
                }
            })
    }

    viewing?.let { f -> SstvViewer(f) { viewing = null } }
}

/** One saved picture: thumbnail, what is known about it, share and delete. */
@Composable
private fun SstvThumb(file: File, shot: SstvMeta.SstvShot, useUtc: Boolean,
                      onOpen: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(file.absolutePath) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
    }
    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable { onOpen() }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 76.dp, height = 58.dp)
                        .clip(RoundedCornerShape(8.dp)).background(SpaceSurface))
            } else {
                Box(Modifier.size(width = 76.dp, height = 58.dp)
                    .clip(RoundedCornerShape(8.dp)).background(SpaceSurface))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val tfmt = remember(useUtc) {
                    SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).apply {
                        if (useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }
                }
                Text(shot.satName.ifBlank { file.name }, color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                val line = buildList {
                    if (shot.mode.isNotBlank()) add(shot.mode)
                    if (shot.locator.isNotBlank()) add(shot.locator)
                    if (!shot.complete) add(t("sstv_partial"))
                }.joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(line, color = if (shot.complete) TextLo else Amber,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Text(tfmt.format(Date(if (shot.timeMs > 0L) shot.timeMs else file.lastModified())) +
                    (if (useUtc) " UTC" else "") +
                    (if (shot.source == "file") "  ·  " + t("sstv_from_file") else ""),
                    color = TextLo, fontSize = 10.sp)
            }
            // Share answers "to whom", save answers "where". Portable and
            // offline, the share sheet often has nothing to offer.
            val enregistreImage = rememberEnregistrer()
            IconButton(onClick = {
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", file)
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("image/png")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(android.content.Intent.createChooser(i, file.name))
                }
            }) { Icon(Icons.Default.Share, t("rec_share"), tint = Cyan,
                    modifier = Modifier.size(20.dp)) }
            IconButton(onClick = {
                enregistreImage(file.name, "image/png", depuisFichier(file))
            }) { Icon(Icons.Default.SaveAlt, t("export_save"), tint = Cyan,
                    modifier = Modifier.size(20.dp)) }
            IconButton(onClick = onDeleted) {
                Icon(Icons.Default.Delete, t("delete"), tint = Magenta,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Full-screen look at one picture. */
@Composable
private fun SstvViewer(file: File, onClose: () -> Unit) {
    val bmp = remember(file.absolutePath) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(file.name, color = TextHi, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onClose, modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface)) {
                    Text(t("close"), color = TextHi)
                }
            }
        }
    }
}

/** Pick one of the MP3 recordings to push back through the decoder. */
@Composable
private fun RecordingPicker(onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val ctx = LocalContext.current
    val files = remember {
        File(ctx.getExternalFilesDir(null), "recordings")
            .listFiles { f -> f.isFile && f.name.endsWith(".mp3") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(t("sstv_pick_recording"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text(t("sstv_no_recording"), color = TextLo, fontSize = 12.sp)
                } else {
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(files, key = { it.absolutePath }) { f ->
                            Column(Modifier.fillMaxWidth()
                                .clickable { onPick(f) }.padding(vertical = 8.dp)) {
                                Text(f.name, color = TextHi, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace)
                                Text("%.1f Mo".format(f.length() / 1_048_576.0),
                                    color = TextLo, fontSize = 10.sp)
                            }
                            HorizontalDivider(color = SpaceSurface)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface)) {
                    Text(t("cancel"), color = TextHi)
                }
            }
        }
    }
}
