/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import fr.f4ioz.satcombo.meteor.MeteorHub
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * METEOR-M pictures: the reception under way (from the SDR dongle), a
 * recording of the raw signal decoded again, and the pictures received.
 */
@Composable
fun MeteorScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by MeteorHub.etat.collectAsState()
    var galerie by remember { mutableStateOf(MeteorHub.shots(ctx)) }
    var vue by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(st.enregistres, st.dernierEnregistre) { galerie = MeteorHub.shots(ctx) }

    val choisit = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { MeteorHub.decodeDocument(ctx, uri) } }
            galerie = MeteorHub.shots(ctx)
        }
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
                            tint = if (st.verrou) Aurora else if (st.actif) Cyan else TextLo,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("meteor_direct_titre"), color = TextHi,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    val enCours = st.actif || st.progression >= 0f
                    Text(
                        when {
                            enCours && st.verrou -> tf("meteor_verrou", st.lignes, st.trames)
                            enCours -> tf("meteor_cherche", "%.1f".format(st.qualiteDb))
                            st.lignes > 0 -> tf("meteor_lignes", st.lignes)
                            else -> t("meteor_repos")
                        },
                        color = if (st.verrou) Aurora else if (enCours) Cyan else TextLo, fontSize = 13.sp)
                    if (enCours) {
                        Spacer(Modifier.height(4.dp))
                        Text(tf("meteor_mesures", "%.1f".format(st.qualiteDb), st.frequenceHz,
                            st.tramesRatees, st.canaux.joinToString(" ")),
                            color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        if (st.perdus > 0) Text(tf("meteor_perdus", st.perdus), color = Amber, fontSize = 11.sp)
                    }
                    st.apercu?.let { bmp ->
                        Spacer(Modifier.height(10.dp))
                        Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(10.dp)))
                    }
                    if (st.actif && st.lignes >= 80) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { MeteorHub.enregistreMaintenant(ctx) }
                                galerie = MeteorHub.shots(ctx)
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Aurora)) {
                            Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("meteor_enregistre_maintenant"), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    st.dernierEnregistre?.let { n ->
                        Spacer(Modifier.height(8.dp))
                        Text(tf("apt_saved", n), color = TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (!st.actif) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("meteor_direct_aide"), color = TextLo, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(t("meteor_beta"), color = Amber, fontSize = 10.sp)
                }
            }
        }

        // ------------------------------------------------------------ automatic
        item {
            var auto by remember { mutableStateOf(vm.meteorAuto()) }
            val prochain = remember(ui.nowMs / 60_000, ui.satellites.size) { vm.meteorProchainPassage() }
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("meteor_auto"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.weight(1f))
                        Switch(checked = auto, onCheckedChange = { auto = it; vm.setMeteorAuto(it) })
                    }
                    Text(t("meteor_auto_aide"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    val fmt = remember(ui.useUtc) {
                        java.text.SimpleDateFormat("EEE dd/MM HH:mm", java.util.Locale.getDefault()).apply {
                            if (ui.useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }
                    }
                    Text(
                        if (prochain == null) {
                            if (vm.meteorSatellites().isEmpty()) t("meteor_aucun_satellite") else t("meteor_pas_de_passage")
                        } else tf("meteor_prochain", prochain.first.name,
                            fmt.format(java.util.Date(prochain.second.aosEpochMs)) + (if (ui.useUtc) " UTC" else ""),
                            prochain.second.maxElevationDeg.toInt()),
                        color = if (prochain == null) Amber else Cyan, fontSize = 12.sp)
                }
            }
        }

        // ------------------------------------------------------------ coastlines
        item {
            var cotes by remember { mutableStateOf(vm.meteorCotes()) }
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("meteor_cotes"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.weight(1f))
                        Switch(checked = cotes, onCheckedChange = { cotes = it; vm.setMeteorCotes(it) })
                    }
                    Text(t("meteor_cotes_aide"), color = TextLo, fontSize = 11.sp)
                }
            }
        }

        // ------------------------------------------------------------ replay
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("meteor_fichier_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(t("meteor_fichier_aide"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                    if (st.progression >= 0f) {
                        Text(st.fichier ?: "", color = Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { st.progression },
                            modifier = Modifier.fillMaxWidth().height(4.dp), color = Cyan, trackColor = SpaceSurface)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { MeteorHub.annuleFichier() }) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("cancel"))
                        }
                    } else {
                        Button(onClick = { choisit.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) },
                            enabled = !st.actif,
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("meteor_fichier_choisir"), color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        if (st.erreur == "meteor_fichier_format") {
                            Spacer(Modifier.height(6.dp))
                            Text(t("meteor_fichier_format"), color = Amber, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ gallery
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Icon(Icons.Default.ImageIcon, null, tint = TextLo, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tf("meteor_galerie", galerie.size), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        if (galerie.isEmpty()) item {
            Text(t("meteor_galerie_vide"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(galerie, key = { it.first.absolutePath }) { (f, shot) ->
            AptThumb(f, shot, ui.useUtc, onOpen = { vue = f }, onDeleted = {
                MeteorHub.supprime(f)
                galerie = MeteorHub.shots(ctx)
            })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    vue?.let { f -> AptViewer(f) { vue = null } }
}
