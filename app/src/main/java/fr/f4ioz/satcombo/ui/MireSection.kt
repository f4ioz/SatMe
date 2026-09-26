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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.SstvEncoder
import fr.f4ioz.satcombo.sstv.SstvMode
import fr.f4ioz.satcombo.sstv.SstvPattern
import fr.f4ioz.satcombo.sstv.SstvPlayer
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Test pattern: check a receive chain without a satellite.
 *
 * When a received image is bad you cannot tell decoder, audio wiring, input
 * level or the far transmitter apart. A known pattern from a device you fully
 * control settles it: if it decodes clean, the receiver is cleared.
 *
 * Use it speaker-to-phone, or as a file replayed through a radio to test the
 * whole RF chain. Export defaults to MP3 (a PD 290 WAV is ~25 MB and no
 * messenger takes it); WAV stays available for the uncompressed source.
 */
@Composable
fun MireSection(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by SstvPlayer.state.collectAsState()

    // PD 120 by default: the ISS mode, and only two minutes long.
    var mode by remember {
        mutableStateOf(SstvMode.byName("PD 120") ?: SstvMode.ALL.first())
    }
    var open by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val call = ui.callsign
    val loc = remember(ui.callsign, ui.observer) { runCatching { vm.myLocator() }.getOrDefault("") }
    val bmp = remember(mode, call, loc) {
        runCatching { SstvPattern.render(ctx, mode, call, loc) }.getOrNull()
    }

    /** Writes the file then offers to share it; only encoder and MIME type differ. */
    fun export(mp3: Boolean) {
        saving = true
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                if (mp3) SstvPlayer.exportMp3(ctx, mode, call, loc)
                else SstvPlayer.exportWav(ctx, mode, call, loc)
            }
            saving = false
            // Never fail silently. The likely cause is the encoder being busy:
            // there is only one per process.
            saved = f?.name ?: if (mp3) t("mp3_occupe") else t("mire_export_echec")
            if (f != null) runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", f)
                val send = android.content.Intent(
                    android.content.Intent.ACTION_SEND).apply {
                    type = if (mp3) "audio/mpeg" else "audio/wav"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(
                    android.content.Intent.createChooser(send, t("mire_export")))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(t("mire_title"), color = TextHi, fontWeight = FontWeight.Bold)
                Text(t("mire_desc"), color = TextLo, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

                // --- mode choice ---------------------------------------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("mire_mode"), color = TextLo, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    Box {
                        OutlinedButton(
                            onClick = { open = true },
                            enabled = !st.playing,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(mode.name, color = Amber, fontWeight = FontWeight.Bold,
                                fontSize = 13.sp)
                            Icon(Icons.Default.ArrowDropDown, null, tint = TextLo,
                                modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            SstvMode.ALL.forEach { m ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            m.name + "   " +
                                                fmtDur(SstvEncoder.seconds(m).toInt()),
                                            color = if (m.name == mode.name) Amber else TextHi)
                                    },
                                    onClick = { mode = m; open = false })
                            }
                        }
                    }
                }
                Text(
                    tf("mire_size", "${mode.width}×${mode.height}",
                        fmtDur(SstvEncoder.seconds(mode).toInt())),
                    color = TextLo, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp))

                // --- preview -------------------------------------------------
                bmp?.let {
                    Spacer(Modifier.height(12.dp))
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = t("mire_title"),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)))
                }

                // --- transmit ------------------------------------------------
                Spacer(Modifier.height(14.dp))
                if (st.playing) {
                    LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Aurora, trackColor = SpaceSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tf("mire_playing", st.modeName ?: mode.name,
                            fmtDur(((1f - st.progress) * st.seconds).toInt())),
                        color = Aurora, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { SstvPlayer.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = Magenta)
                    ) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("mire_stop"))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { SstvPlayer.play(ctx, mode, call, loc) },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp),
                                tint = if (isDarkTheme()) SpaceBg else androidx.compose.ui.graphics.Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(t("mire_play"),
                                color = if (isDarkTheme()) SpaceBg else androidx.compose.ui.graphics.Color.White)
                        }
                        OutlinedButton(
                            onClick = { export(true) },
                            enabled = !saving
                        ) {
                            Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (saving) t("mire_exporting") else t("mire_export"))
                        }
                    }
                    // WAV as second choice: heavier, but uncompressed for fine measurement.
                    TextButton(onClick = { export(false) }, enabled = !saving) {
                        Text(t("mire_export_wav"), color = TextLo, fontSize = 11.sp)
                    }
                }
                saved?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Aurora, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(t("mire_hint"), color = TextLo.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }
    }
}

/** m:ss, the readable form for one-to-five-minute durations. */
private fun fmtDur(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
