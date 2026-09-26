/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.data.Units
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sdr.SdrHub
import fr.f4ioz.satcombo.sonde.Geo
import fr.f4ioz.satcombo.sonde.SondeHub
import fr.f4ioz.satcombo.sonde.SondeModel
import fr.f4ioz.satcombo.sonde.SondeSites
import fr.f4ioz.satcombo.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La chasse aux radiosondes.
 *
 * Deux fois par jour, chaque station météo lâche un ballon qui monte à trente
 * kilomètres, éclate et redescend sous parachute. La sonde accrochée dessous
 * émet sa position en clair sur la bande des 400 MHz jusqu'à ce que la pile
 * lâche. Personne ne va la rechercher : c'est du matériel perdu, et le
 * retrouver est un jeu de piste que beaucoup d'OM pratiquent.
 *
 * L'écran est organisé dans l'ordre où l'on s'en sert sur le terrain : la
 * fréquence, la réception, le dernier point reçu avec le cap et la distance
 * depuis chez soi, puis le vol complet à exporter dans le GPS. Le cap et la
 * distance restent affichés en permanence, gros et lisibles — c'est ce qu'on
 * regarde en marchant, souvent d'une main, souvent sous la pluie.
 */
@Composable
fun SondeScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    // Un seul lanceur pour l'écran : il sert les traces et les journaux.
    val enregistreSonde = rememberEnregistrer()
    val st by SondeHub.state.collectAsState()
    val sdr by SdrHub.state.collectAsState()

    var present by remember { mutableStateOf(SdrHub.devicePresent(ctx) != null) }
    LaunchedEffect(Unit) {
        while (true) {
            present = SdrHub.devicePresent(ctx) != null
            kotlinx.coroutines.delay(1500)
        }
    }

    // Le micro n'est demandé qu'au moment où l'on choisit vraiment d'écouter
    // par l'audio : la clé RTL, elle, n'a besoin d'aucune permission.
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[android.Manifest.permission.RECORD_AUDIO] == true) vm.startSondeRx()
    }

    var logs by remember { mutableStateOf(SondeHub.logs(ctx)) }
    LaunchedEffect(st.frames) { if (st.frames % 25 == 0) logs = SondeHub.logs(ctx) }
    var help by remember { mutableStateOf(false) }

    val obs = ui.observer
    val sites = remember(obs?.latDeg, obs?.lonDeg) {
        if (obs != null) SondeSites.nearest(obs.latDeg, obs.lonDeg, max = 6,
            includeOccasional = true)
        else emptyList()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // ------------------------------------------------------------- bêta
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("sonde_beta"), color = Amber,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { help = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Info, t("sonde_help_title"), tint = TextLo,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t("sonde_intro"), color = TextLo, fontSize = 12.sp)
                }
            }
        }

        // -------------------------------------------------------- fréquence
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("sonde_freq_title"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { vm.stepSondeFreq(-1) },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Icon(Icons.Default.Remove, null, Modifier.size(18.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Text("%.3f MHz".format(Locale.US, ui.sondeFreqHz / 1e6),
                            color = Cyan, fontWeight = FontWeight.Black, fontSize = 22.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { vm.stepSondeFreq(+1) },
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t("sonde_band_note"), color = TextLo, fontSize = 11.sp)

                    if (sites.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(t("sonde_sites"), color = TextHi, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        for ((site, km) in sites) {
                            val hz = site.mainHz
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = hz > 0) { vm.setSondeFreq(hz) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(site.name + "  ·  " + site.sonde,
                                        color = if (hz == ui.sondeFreqHz) Cyan else TextHi,
                                        fontSize = 13.sp,
                                        fontWeight = if (hz == ui.sondeFreqHz)
                                            FontWeight.Bold else FontWeight.Normal)
                                    Text(
                                        Units.distanceRound(km, ui.units) +
                                            (if (site.occasional) "  ·  " + t("sonde_occasional")
                                            else "") +
                                            (if (site.launchesUtc.isNotEmpty())
                                                "  ·  " + site.launchesUtc.joinToString(", ") {
                                                    "%02dh%02d".format(
                                                        it.toInt(),
                                                        Math.round((it - it.toInt()) * 60.0))
                                                } + " UTC" else ""),
                                        color = TextLo, fontSize = 10.sp)
                                }
                                Text("%.3f".format(Locale.US, hz / 1e6),
                                    color = TextLo, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                            HorizontalDivider(color = SpaceSurface)
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------- réception
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    val bySdr = ui.sondeSource == "SDR"
                    val ready = !bySdr || present
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (bySdr) Icons.Default.Usb else Icons.Default.Mic, null,
                            tint = if (st.running) Aurora else if (ready) Cyan else TextLo,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                !bySdr -> t("sonde_by_audio")
                                present -> t("sdr_device")
                                else -> t("sdr_no_device")
                            },
                            color = if (ready) TextHi else TextLo,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    if (bySdr) sdr.error?.let { e ->
                        Spacer(Modifier.height(8.dp))
                        Text(t("sdr_err_$e"), color = Magenta, fontSize = 12.sp)
                    }

                    // D'où vient le son. Une sonde ne s'écoute pas forcément
                    // avec la clé : un poste convenable et un cordon font
                    // aussi bien, et c'est ce que beaucoup ont sous la main.
                    Spacer(Modifier.height(12.dp))
                    Text(t("sonde_source"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "SDR" to t("sonde_src_sdr"),
                            "MIC" to t("rec_source_mic"),
                            "USB" to t("rec_source_usb")
                        ).forEach { (code, label) ->
                            val on = ui.sondeSource == code
                            Surface(
                                color = if (on) Cyan else SpaceSurface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).clickable(enabled = !st.running) {
                                    vm.setSondeSource(code)
                                }
                            ) {
                                Text(label,
                                    color = if (on) Color.Black else TextLo,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center, maxLines = 2,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 7.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (ui.sondeSource) {
                            "MIC" -> t("sonde_src_mic_desc")
                            "USB" -> t("sonde_src_usb_desc")
                            else -> t("sonde_src_sdr_desc")
                        },
                        color = TextLo, fontSize = 10.sp)

                    // Le modèle écouté. En automatique les trois décodeurs
                    // tournent ensemble et le filtre reste au plus large ;
                    // nommer la sonde resserre le filtre sur sa largeur exacte,
                    // ce qui vaut deux à trois décibels — souvent la différence
                    // entre une sonde décodée à cent kilomètres et une sonde
                    // perdue. Les fréquences de la liste des stations disent
                    // déjà le modèle : la lecture est faite.
                    Spacer(Modifier.height(12.dp))
                    Text(t("sonde_model"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            SondeModel.AUTO to t("sonde_model_auto"),
                            "RS41" to "RS41",
                            "M20" to "M20",
                            "M10" to "M10"
                        ).forEach { (code, label) ->
                            val on = ui.sondeModel == code
                            Surface(
                                color = if (on) Aurora else SpaceSurface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).clickable(enabled = !st.running) {
                                    vm.setSondeModel(code)
                                }
                            ) {
                                Text(label,
                                    color = if (on) Color.Black else TextLo,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center, maxLines = 1,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 7.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t("sonde_model_desc"), color = TextLo, fontSize = 10.sp)
                    if (st.running && st.marginal) {
                        Spacer(Modifier.height(6.dp))
                        Text(t("sonde_marginal"), color = Amber, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(tf("sonde_notyet", SondeModel.NOT_YET.joinToString(" · ")),
                        color = TextLo.copy(alpha = 0.7f), fontSize = 9.sp)

                    Spacer(Modifier.height(10.dp))
                    if (!st.running) {
                        Button(
                            onClick = {
                                val granted = androidx.core.content.ContextCompat
                                    .checkSelfPermission(ctx,
                                        android.Manifest.permission.RECORD_AUDIO) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (bySdr || granted) vm.startSondeRx()
                                else micLauncher.launch(
                                    arrayOf(android.Manifest.permission.RECORD_AUDIO))
                            },
                            enabled = ready,
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("sonde_start"), color = Color.Black,
                                fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { vm.stopSondeRx() },
                            colors = ButtonDefaults.buttonColors(containerColor = Magenta),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("sonde_stop"), color = Color.Black,
                                fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        // Le niveau vu par le discriminateur : sans trame
                        // décodée, c'est le seul indice qu'il y a quelque
                        // chose sur la fréquence, et qu'il faut chercher à
                        // côté plutôt que remonter l'antenne.
                        Text(t("sonde_swing"), color = TextLo, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(3.dp)).background(SpaceSurface)) {
                            Box(Modifier.fillMaxWidth(st.swing / 100f).fillMaxHeight()
                                .background(if (st.frames > 0) Aurora else Cyan))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(tf("sonde_counters", st.frames, st.rejected),
                            color = TextLo, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)

                        // ------------------------------------ l'accord
                        // Une sonde n'est presque jamais pile sur la fréquence
                        // annoncée : le quartz dérive avec le froid, et la clé
                        // a la sienne. Deux kilohertz d'écart suffisent à faire
                        // tomber le décodage d'une RS41 — c'est mesuré au banc,
                        // six trames sur six deviennent une. D'où ce bouton :
                        // il lit le spectre, y trouve le centre de gravité du
                        // signal, et pose l'accord fin dessus.
                        if (bySdr) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = SpaceSurface)
                            Spacer(Modifier.height(10.dp))
                            Text(t("sonde_tune"), color = TextLo, fontSize = 11.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { SdrHub.tuneCentroid() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.CenterFocusStrong, null,
                                        Modifier.size(16.dp), tint = Cyan)
                                    Spacer(Modifier.width(6.dp))
                                    Text(t("sonde_tune_now"), color = Cyan,
                                        fontSize = 12.sp)
                                }
                                Surface(
                                    color = if (sdr.autoTune) Aurora else SpaceSurface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).clickable {
                                        SdrHub.setAutoTune(!sdr.autoTune)
                                    }
                                ) {
                                    Text(t("sonde_tune_auto"),
                                        color = if (sdr.autoTune) Color.Black else TextLo,
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center, maxLines = 1,
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 9.dp))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (sdr.tunedAtMs > 0L)
                                    tf("sonde_tune_offset", sdr.offsetHz)
                                else t("sonde_tune_none"),
                                color = if (sdr.tunedAtMs > 0L) Amber else TextLo,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(4.dp))
                            Text(t("sonde_tune_desc"), color = TextLo, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // ------------------------------------------------- le point à suivre
        val last = st.last
        if (last != null) {
            item {
                Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Explore, null, tint = Aurora,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(st.type + "  " + st.serial, color = TextHi,
                                fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace)
                        }

                        // Cap et distance, en gros : c'est ce que l'on regarde
                        // en marchant, le reste peut attendre la voiture.
                        if (obs != null) {
                            val km = Geo.distanceKm(obs.latDeg, obs.lonDeg, last.lat, last.lon)
                            val br = Geo.bearingDeg(obs.latDeg, obs.lonDeg, last.lat, last.lon)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(Units.distance(km, ui.units), color = Cyan,
                                    fontWeight = FontWeight.Black, fontSize = 30.sp)
                                Spacer(Modifier.width(14.dp))
                                Text("%.0f° %s".format(Locale.US, br, Geo.compass(br)),
                                    color = Amber, fontWeight = FontWeight.Black,
                                    fontSize = 24.sp)
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        SondeLine(t("sonde_pos"),
                            "%.5f  %.5f".format(Locale.US, last.lat, last.lon))
                        SondeLine(t("sonde_alt"), Units.altitude(last.altM, ui.units))
                        SondeLine(t("sonde_speed"),
                            Units.speed(last.speedMps, ui.units) + "  " +
                                Geo.compass(last.headingDeg))
                        SondeLine(t("sonde_climb"), Units.vertical(last.climbMps, ui.units))
                        if (last.sats > 0) SondeLine(t("sonde_sats"), last.sats.toString())
                        if (last.timeUtcMs > 0L) {
                            val f = remember {
                                SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).apply {
                                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                                }
                            }
                            SondeLine(t("sonde_time"), f.format(Date(last.timeUtcMs)) + " UTC")
                        }
                        if (last.freqHz > 0L) {
                            SondeLine(t("sonde_freq"),
                                "%.3f MHz".format(Locale.US, last.freqHz / 1e6))
                        }
                        if (!last.trusted) {
                            Spacer(Modifier.height(6.dp))
                            Text(t("sonde_unsure"), color = Amber, fontSize = 11.sp)
                        }
                        // D'où vient-elle ? Une présomption, pas une certitude,
                        // et l'écran le dit avec ses mots.
                        val org = remember(last.lat, last.lon, last.type) {
                            SondeSites.likelyOrigin(last.lat, last.lon, last.type)
                        }
                        if (org != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(tf("sonde_from", org.name), color = TextLo, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------- vol
        item {
            val fl = SondeHub.currentFlight
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("sonde_flight"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    if (fl == null || fl.count == 0) {
                        Text(t("sonde_flight_empty"), color = TextLo, fontSize = 12.sp)
                    } else {
                        SondeLine(t("sonde_points"), fl.count.toString())
                        SondeLine(t("sonde_duration"),
                            "%d min".format(fl.durationSec / 60))
                        if (fl.hasBurst) {
                            SondeLine(t("sonde_burst"),
                                Units.altitude(fl.burstAltM, ui.units))
                            SondeLine(t("sonde_descent"),
                                Units.vertical(-fl.descentRate(), ui.units))
                        }
                        fl.estimatedLanding()?.let { (la, lo) ->
                            SondeLine(t("sonde_landing"),
                                "%.5f  %.5f".format(Locale.US, la, lo))
                            if (obs != null) {
                                SondeLine(t("sonde_landing_dist"),
                                    Units.distance(
                                        Geo.distanceKm(obs.latDeg, obs.lonDeg, la, lo),
                                        ui.units) + "  " +
                                        Geo.compass(Geo.bearingDeg(
                                            obs.latDeg, obs.lonDeg, la, lo)))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(t("sonde_landing_note"), color = TextLo, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                SondeHub.export(ctx, kml = false)?.let { share(ctx, it) }
                            }) { Text("GPX") }
                            OutlinedButton(onClick = {
                                SondeHub.export(ctx, kml = true)?.let { share(ctx, it) }
                            }) { Text("KML") }
                            // Une trace qu'on veut garder n'a pas de
                            // destinataire : elle a un dossier.
                            OutlinedButton(onClick = {
                                SondeHub.export(ctx, kml = false)?.let {
                                    enregistreSonde(it.name, typeSonde(it), depuisFichier(it)) }
                            }) { Text(t("export_save") + " GPX", fontSize = 12.sp) }
                            OutlinedButton(onClick = {
                                SondeHub.export(ctx, kml = true)?.let {
                                    enregistreSonde(it.name, typeSonde(it), depuisFichier(it)) }
                            }) { Text(t("export_save") + " KML", fontSize = 12.sp) }
                            OutlinedButton(onClick = { vm.clearSondeFlight() }) {
                                Text(t("sonde_clear"))
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- journaux
        item {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(tf("sonde_logs", logs.size), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                if (logs.isNotEmpty()) {
                    OutlinedButton(onClick = { logs = SondeHub.logs(ctx) },
                        contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(t("refresh"), fontSize = 12.sp)
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Text(t("sonde_logs_empty"), color = TextLo, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
        }

        items(logs, key = { it.absolutePath }) { f ->
            Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = TextHi, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace)
                        Text("%.1f ko".format(f.length() / 1024.0),
                            color = TextLo, fontSize = 10.sp)
                    }
                    IconButton(onClick = { share(ctx, f) }) {
                        Icon(Icons.Default.Share, t("rec_share"), tint = Cyan,
                            modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = {
                        enregistreSonde(f.name, typeSonde(f), depuisFichier(f))
                    }) {
                        Icon(Icons.Default.SaveAlt, t("export_save"), tint = Cyan,
                            modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { f.delete(); logs = SondeHub.logs(ctx) }) {
                        Icon(Icons.Default.Delete, t("delete"), tint = Magenta,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (help) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { help = false }) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("sonde_help_title"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(t("sonde_help_body"), color = TextLo, fontSize = 12.sp,
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
}

/** Une ligne « intitulé — valeur », alignée comme les autres fiches. */
@Composable
private fun SondeLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextHi, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Le type MIME d'une trace de sonde, déduit de son extension. */
private fun typeSonde(f: File): String = when {
    f.name.endsWith(".kml") -> "application/vnd.google-earth.kml+xml"
    f.name.endsWith(".gpx") -> "application/gpx+xml"
    else -> "text/csv"
}

/** Partage d'un fichier par le sélecteur du système. */
private fun share(ctx: android.content.Context, f: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", f)
        val type = typeSonde(f)
        val i = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType(type)
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(android.content.Intent.createChooser(i, f.name))
    }
}
