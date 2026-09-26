/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sonde.SondeMire
import fr.f4ioz.satcombo.sonde.SondeMirePlayer
import fr.f4ioz.satcombo.sonde.SondeModel
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La mire radiosonde, pendant exact de la mire SSTV.
 *
 * Une sonde part deux fois par jour et ne repasse pas ; quand rien ne se décode
 * il est déjà trop tard pour chercher pourquoi. La mire répond à la question
 * hors antenne et à la demande : elle fabrique de vraies trames, au format du
 * constructeur, portant un vol plausible parti du carré de l'opérateur.
 *
 * Trois usages, du plus utile au plus démonstratif : la démonstration, qui
 * verse le signal directement dans le décodeur et remplit l'écran des sondes
 * d'un vol entier ; le fichier, qu'on repasse dans une radio ou qu'on envoie à
 * un camarade ; et le haut-parleur, pour éprouver la chaîne micro d'en face.
 */
@Composable
fun SondeMireSection(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by SondeMirePlayer.state.collectAsState()

    // La RS41 par défaut : c'est la sonde la plus répandue en Europe, et celle
    // dont le format est le mieux vérifié par les essais.
    var model by remember { mutableStateOf("RS41") }
    var seconds by remember { mutableIntStateOf(60) }
    var saved by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Décochée par défaut : la mire sert d'abord à éprouver une chaîne, et un
    // signal propre est le seul qui dise sans ambiguïté si le cordon marche.
    var ambiance by remember { mutableStateOf(false) }

    val lat = ui.observer?.latDeg ?: 48.0
    val lon = ui.observer?.lonDeg ?: -4.0

    fun export(mp3: Boolean) {
        saving = true
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                if (mp3) SondeMirePlayer.exportMp3(ctx, model, lat, lon, seconds, ambiance)
                else SondeMirePlayer.exportWav(ctx, model, lat, lon, seconds, ambiance)
            }
            saving = false
            // Voir MireSection : le silence sur échec coûte plus cher qu'un
            // message un peu long.
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
                    android.content.Intent.createChooser(send, t("sondemire_export")))
            }
        }
    }

    val busy = st.playing || st.demo

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(t("sondemire_title"), color = TextHi, fontWeight = FontWeight.Bold)
                Text(t("sondemire_desc"), color = TextLo, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

                // --- modèle émis ---------------------------------------------
                Text(t("sondemire_model"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("RS41", "M20", "M10").forEach { code ->
                        val on = model == code
                        Surface(
                            color = if (on) Cyan else SpaceSurface,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                                .clickable(enabled = !busy) { model = code }
                        ) {
                            Text(SondeModel.byId(code).label,
                                color = if (on) Color.Black else TextLo,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, maxLines = 2,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 7.dp))
                        }
                    }
                }

                // --- durée ----------------------------------------------------
                Spacer(Modifier.height(12.dp))
                Text(t("sondemire_duration"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SondeMire.DURATIONS.forEach { s ->
                        val on = seconds == s
                        Surface(
                            color = if (on) Amber else SpaceSurface,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                                .clickable(enabled = !busy) { seconds = s }
                        ) {
                            Text("%d:%02d".format(s / 60, s % 60),
                                color = if (on) Color.Black else TextLo,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(vertical = 7.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(tf("sondemire_frames", seconds), color = TextLo, fontSize = 10.sp)

                // --- ambiance -------------------------------------------------
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !busy) { ambiance = !ambiance }
                ) {
                    Checkbox(
                        checked = ambiance,
                        onCheckedChange = { ambiance = it },
                        enabled = !busy,
                        colors = CheckboxDefaults.colors(checkedColor = Amber))
                    Column {
                        Text(t("sondemire_ambiance"), color = TextHi, fontSize = 12.sp)
                        Text(t("sondemire_ambiance_hint"), color = TextLo, fontSize = 10.sp)
                    }
                }

                // --- ce qui tourne --------------------------------------------
                Spacer(Modifier.height(14.dp))
                if (busy) {
                    LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Aurora, trackColor = SpaceSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (st.demo) tf("sondemire_demo_running", st.model)
                        else tf("sondemire_playing", st.model),
                        color = Aurora, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { SondeMirePlayer.stopDemo() },
                        colors = ButtonDefaults.buttonColors(containerColor = Magenta)
                    ) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("mire_stop"))
                    }
                } else {
                    // La démonstration d'abord : c'est elle qui répond à la
                    // question « est-ce le décodeur ou est-ce la réception ? »
                    Button(
                        onClick = {
                            SondeMirePlayer.demo(ctx, model, lat, lon, seconds, ambiance)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Aurora),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Science, null, Modifier.size(18.dp),
                            tint = if (isDarkTheme()) SpaceBg else Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(t("sondemire_demo"),
                            color = if (isDarkTheme()) SpaceBg else Color.White,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                SondeMirePlayer.play(ctx, model, lat, lon, seconds, ambiance)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp),
                                tint = if (isDarkTheme()) SpaceBg else Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(t("mire_play"),
                                color = if (isDarkTheme()) SpaceBg else Color.White)
                        }
                        OutlinedButton(onClick = { export(false) }, enabled = !saving) {
                            Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (saving) t("mire_exporting") else t("sondemire_export"))
                        }
                    }
                    // Le MP3 en second rideau : il s'envoie, mais il arrondit
                    // les fronts. Pour éprouver une chaîne, c'est le WAV.
                    TextButton(onClick = { export(true) }, enabled = !saving) {
                        Text(t("sondemire_export_mp3"), color = TextLo, fontSize = 11.sp)
                    }
                }
                saved?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Aurora, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(t("sondemire_hint"), color = TextLo.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }

        // ------------------------------------------------- modèles connus
        Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(t("sondemire_models_title"), color = TextHi,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                for (p in SondeModel.ALL.drop(1)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp)
                            .background(Aurora, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.label, color = TextHi, fontSize = 13.sp)
                            Text(
                                "%.0f bauds  ·  %d kHz".format(
                                    p.baud, p.bandwidthHz / 1000) +
                                    (if (p.biphase) "  ·  bi-phase" else ""),
                                color = TextLo, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                    HorizontalDivider(color = SpaceSurface)
                }
                Spacer(Modifier.height(10.dp))
                Text(t("sondemire_notyet"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(SondeModel.NOT_YET.joinToString(" · "),
                    color = TextLo.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }
    }
}
