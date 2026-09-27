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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import fr.f4ioz.satcombo.apt.AptHub
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
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
 * APT: NOAA weather images received while recording a pass.
 *
 * Unlike SSTV there is nothing to wait for: NOAA transmits continuously while in
 * view. The screen shows the image building line by line, with a button to save
 * it without stopping reception — the system may kill the app before a
 * 15-minute pass ends.
 */
@Composable
fun AptScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by AptHub.state.collectAsState()

    var gallery by remember { mutableStateOf(AptHub.shots(ctx)) }
    var pickRecording by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var help by remember { mutableStateOf(false) }

    LaunchedEffect(st.savedCount, st.fileImages, st.lastSaved) {
        gallery = AptHub.shots(ctx)
    }
    LaunchedEffect(st.fileProgress >= 0f) {
        while (st.fileProgress >= 0f) { delay(1000); gallery = AptHub.shots(ctx) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // ------------------------------------------------------------ live
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Satellite, null,
                            tint = if (st.locked) Aurora else if (st.listening) Cyan else TextLo,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("apt_live_title"), color = TextHi,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { help = true }) {
                            Icon(Icons.Default.Info, t("apt_help_title"), tint = TextLo,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            st.locked -> tf("apt_locked", st.lines,
                                (st.quality * 100f).toInt().coerceIn(0, 100))
                            st.listening -> t("apt_waiting")
                            st.lines > 0 -> tf("apt_lines", st.lines)
                            else -> t("apt_idle")
                        },
                        color = if (st.locked) Aurora else if (st.listening) Cyan else TextLo,
                        fontSize = 13.sp)
                    if (st.listening && st.lines > 0) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (st.lines / AptHub.MAX_LINES.toFloat()).coerceIn(0f, 1f) },
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
                    if (st.listening && st.lines >= 10) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val n = AptHub.saveNow(ctx)
                                if (n != null) gallery = AptHub.shots(ctx)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Aurora)
                        ) {
                            Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("apt_save_now"), color = Color.Black,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                    st.lastSaved?.let { n ->
                        Spacer(Modifier.height(8.dp))
                        Text(tf("apt_saved", n), color = TextLo, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    if (!st.listening) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("apt_live_hint"), color = TextLo, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(t("apt_beta"), color = Amber, fontSize = 10.sp)
                }
            }
        }

        // ------------------------------------------------------------ replay
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("apt_file_title"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(t("apt_file_hint"), color = TextLo, fontSize = 11.sp)
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
                        OutlinedButton(onClick = { AptHub.cancelFileDecode() }) {
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
                            Text(t("apt_pick_recording"), color = Color.Black,
                                fontWeight = FontWeight.Bold)
                        }
                        if (st.fileName != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(tf("apt_file_done", st.fileName!!, st.fileImages),
                                color = TextLo, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ gallery
        item {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Icon(Icons.Default.ImageIcon, null, tint = TextLo,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tf("apt_gallery", gallery.size), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        if (gallery.isEmpty()) {
            item {
                Text(t("apt_gallery_empty"), color = TextLo, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        items(gallery, key = { it.first.absolutePath }) { (f, shot) ->
            AptThumb(f, shot, ui.useUtc, onOpen = { viewing = f }, onDeleted = {
                AptHub.delete(f)
                gallery = AptHub.shots(ctx)
            })
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (pickRecording) {
        AptRecordingPicker(
            onDismiss = { pickRecording = false },
            onPick = { mp3 ->
                pickRecording = false
                scope.launch {
                    withContext(Dispatchers.IO) { AptHub.decodeFile(ctx, mp3) }
                    gallery = AptHub.shots(ctx)
                }
            })
    }

    if (help) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { help = false }) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("apt_help_title"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(t("apt_help_body"), color = TextLo, fontSize = 12.sp,
                        lineHeight = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { help = false }, modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface)) {
                        Text(t("close"), color = TextHi)
                    }
                }
            }
        }
    }

    viewing?.let { f -> AptViewer(f) { viewing = null } }
}

/** A saved image: thumbnail, metadata, share and delete. */
@Composable
private fun AptThumb(file: File, shot: SstvMeta.SstvShot, useUtc: Boolean,
                     onOpen: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(file.absolutePath) {
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
        }.getOrNull()
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
                    if (shot.note.isNotBlank()) add(shot.note)
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
            // Share answers "to whom", save answers "where". Portable with no
            // network, the share sheet often has nothing to offer.
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

/**
 * Full-size view. An APT image is ~2000 × 2080 px; it is loaded at half size,
 * since full resolution would fill memory and show nothing more on a phone.
 */
@Composable
private fun AptViewer(file: File, onClose: () -> Unit) {
    val bmp = remember(file.absolutePath) {
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
        }.getOrNull()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(file.name, color = TextHi, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                            .clip(RoundedCornerShape(8.dp)))
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

/** Pick the recording to replay through the decoder. */
@Composable
private fun AptRecordingPicker(onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val ctx = LocalContext.current
    val files = remember {
        File(ctx.getExternalFilesDir(null), "recordings")
            .listFiles { f -> f.isFile && f.name.endsWith(".mp3") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(t("apt_pick_recording"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text(t("apt_no_recording"), color = TextLo, fontSize = 12.sp)
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
