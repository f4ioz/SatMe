/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.audio.MoniteurAudio
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Spectrum of the audio being recorded, plus the monitor-by-ear switch.
 * Only shown while recording: otherwise there are no samples, and an empty card
 * would just take space from the compass.
 */
@Composable
fun MoniteurAudioCard(ui: UiState, vm: MainViewModel) {
    if (!ui.recording) return
    if (!ui.monitorSpectre && !ui.monitorSpeaker) return
    val etat by MoniteurAudio.etat.collectAsState()

    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Aurora.copy(alpha = 0.20f), shape = RoundedCornerShape(6.dp)) {
                    Text(t("monitor_title"), color = Aurora, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
                // Peak stuck at 1 = clipping; stuck at 0 = unplugged cable.
                // Both show better here than by ear.
                Text(niveauTexte(etat.crete), color = teinteNiveau(etat.crete),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                // Speaker monitoring only for external sources: on the phone
                // mic it would just howl (feedback).
                if (ui.recorderSource != "MIC") {
                    IconButton(onClick = { vm.setMonitorSpeaker(!ui.monitorSpeaker) },
                        modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (ui.monitorSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            t("monitor_speaker"),
                            tint = if (ui.monitorSpeaker) Aurora else TextLo,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (ui.monitorSpectre) {
                Spacer(Modifier.height(6.dp))
                BarresSpectre(etat.bandes)
                Text(t("monitor_scale"), color = TextLo, fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (ui.recorderSource == "MIC" && ui.monitorSpeaker) {
                Text(t("monitor_speaker_mic"), color = Amber, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** "Peak −6 dB" rather than a bare number: what an operator reads. */
private fun niveauTexte(crete: Float): String {
    if (crete <= 0.0005f) return "—"
    val db = 20.0 * kotlin.math.log10(crete.toDouble())
    return String.format(java.util.Locale.US, "%.0f dB", db)
}

private fun teinteNiveau(crete: Float): Color = when {
    crete >= 0.95f -> Magenta      // clipping
    crete >= 0.05f -> Aurora       // usable level
    else -> TextLo                 // no input
}

/** The bars, in a single Canvas: 30 bars redrawn 15 times a second. */
@Composable
private fun BarresSpectre(bandes: List<Float>, hauteur: androidx.compose.ui.unit.Dp = 56.dp) {
    Canvas(Modifier.fillMaxWidth().height(hauteur)) {
        val n = bandes.size
        if (n == 0) return@Canvas
        val ecart = 1.5f
        val largeur = (size.width - ecart * (n - 1)) / n
        if (largeur <= 0f) return@Canvas
        for (i in 0 until n) {
            val v = bandes[i].coerceIn(0f, 1f)
            val h = (size.height * v).coerceAtLeast(1f)
            val x = i * (largeur + ecart)
            // Green when comfortable, amber then magenta near clipping: tells
            // at once whether to turn the radio's volume down.
            val c = when {
                v >= 0.92f -> Magenta
                v >= 0.75f -> Amber
                else -> Aurora
            }
            drawRect(color = c.copy(alpha = 0.25f + 0.75f * v),
                topLeft = Offset(x, size.height - h),
                size = Size(largeur, h))
        }
    }
}
