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
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Tour
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.SkedWindow
import fr.f4ioz.satcombo.Screen
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.data.LocationMode
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.ui.theme.*
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- countdown helpers ----------

private fun Double.format1(): String = "%.1f".format(this)

private fun fmtAge(ms: Long): String {
    val h = ms / 3600_000
    return if (h >= 48) "${h / 24} j" else if (h >= 1) "$h h" else "${ms / 60_000} min"
}

internal fun fmtCountdown(ms: Long): String {
    val total = ms / 1000
    val d = total / 86400
    val h = (total % 86400) / 3600; val m = (total % 3600) / 60; val s = total % 60
    return when {
        d > 0 -> "J-%d %02dh%02d".format(d, h, m)
        h > 0 -> "T-%d:%02d:%02d".format(h, m, s)
        else -> "T-%02d:%02d".format(m, s)
    }
}

private sealed class PassPhase {
    data class Upcoming(val inMs: Long) : PassPhase()
    data class Active(val progress: Float, val remainingMs: Long) : PassPhase()
    object Done : PassPhase()
}

private fun passPhase(p: SatPass, now: Long): PassPhase = when {
    now < p.aosEpochMs -> PassPhase.Upcoming(p.aosEpochMs - now)
    now <= p.losEpochMs -> PassPhase.Active(
        ((now - p.aosEpochMs).toFloat() / (p.losEpochMs - p.aosEpochMs).coerceAtLeast(1)).coerceIn(0f, 1f),
        p.losEpochMs - now
    )
    else -> PassPhase.Done
}

// ---------- scaffold ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatComboApp(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.bootstrap() }

    // "Prepare the pass": over everything else while open.
    val preparation by vm.preparation.collectAsState()
    preparation?.let { PreparationScreen(ui, vm, it) }

    // SSTV ISS keeps recording the ISS whatever satellite is shown: say so when another one is picked.
    val avertSstvIss by vm.sstvIssAvertissement.collectAsState()
    if (avertSstvIss) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.fermeAvertissementSstvIss() },
            title = { Text(t("sstv_iss_avert_titre")) },
            text = { Text(t("sstv_iss_avert_texte")) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.fermeAvertissementSstvIss() }) { Text(t("sstv_iss_avert_garder")) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.fermeAvertissementSstvIss(); vm.sstvIssDesactive() }) {
                    Text(t("sstv_iss_avert_arreter"))
                }
            })
    }

    // System back navigates within the app instead of quitting: a page
    // returns to the page it was opened from (Chemin), detail -> list,
    // selection mode -> off. Only the top-level passes screen lets back fall
    // through to leave the app.
    //
    // Branch order is RetourArriere's and it matters: close what is visible.
    // With the detail sheet first, back from the Rotor screen cleared the
    // selection under a screen still shown, silently stopping tracking.
    val sectionReglages = ui.settingsSection != null
    val canHandleBack = RetourArriere.intercepte(
        ui.screen, sectionReglages, ui.selected != null, ui.selectionMode)
    androidx.activity.compose.BackHandler(enabled = canHandleBack) {
        when (RetourArriere.geste(
            ui.screen, sectionReglages, ui.selected != null, ui.selectionMode)) {
            RetourArriere.Geste.SECTION_REGLAGES -> vm.closeSettingsSection()
            RetourArriere.Geste.FERMER_REGLAGES -> vm.closeSettings()
            RetourArriere.Geste.FERMER_LOCATOR -> vm.closeLocator()
            RetourArriere.Geste.FERMER_FT8 -> vm.fermeFt8()
            RetourArriere.Geste.FERMER_GLOBE -> vm.fermeGlobe()
            RetourArriere.Geste.FERMER_SKED -> vm.closeSked()
            RetourArriere.Geste.FERMER_TIMELINE -> vm.closeTimeline()
            RetourArriere.Geste.FERMER_PHOTO -> vm.closePhoto()
            RetourArriere.Geste.FERMER_ACTIVATION -> vm.closeActivation()
            RetourArriere.Geste.FERMER_SSTV -> vm.closeSstv()
            RetourArriere.Geste.FERMER_APRS -> vm.closeAprs()
            RetourArriere.Geste.FERMER_SDR -> vm.closeSdr()
            RetourArriere.Geste.FERMER_APT -> vm.closeApt()
            RetourArriere.Geste.FERMER_SONDE -> vm.closeSonde()
            RetourArriere.Geste.FERMER_ROTOR -> vm.closeRotor()
            RetourArriere.Geste.FERMER_QO100 -> vm.closeQo100()
            RetourArriere.Geste.FERMER_NOMMAGE -> vm.fermeNommage()
            RetourArriere.Geste.FERMER_AGENDA -> vm.closeAgenda()
            RetourArriere.Geste.RETOUR_LISTE -> vm.backToList()
            RetourArriere.Geste.QUITTER_SELECTION -> vm.toggleSelectionMode()
            RetourArriere.Geste.RIEN -> Unit
        }
    }

    // TX border: red around the whole screen while the rig transmits. On VOX
    // nothing else warns that a stuck TX is hogging the transponder. It pulses
    // because a still frame reads as decoration.
    Box(Modifier.fillMaxSize().background(SpaceGradient)) {
        Scaffold(containerColor = Color.Transparent, topBar = { TopBar(ui, vm) }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    ui.loading && ui.satellites.isEmpty() -> LoadingState()
                    ui.screen == Screen.SKED -> SkedScreen(ui, vm)
                    ui.screen == Screen.TIMELINE -> TimelineScreen(ui, vm)
                    ui.screen == Screen.PHOTO -> PhotoScreen(ui, vm)
                    ui.screen == Screen.ACTIVATION -> ActivationScreen(ui, vm)
                    ui.screen == Screen.SSTV -> SstvScreen(ui, vm)
                    ui.screen == Screen.APRS -> AprsScreen(ui, vm)
                    ui.screen == Screen.SDR -> SdrScreen(ui, vm)
                    ui.screen == Screen.APT -> AptScreen(ui, vm)
                    ui.screen == Screen.SONDE -> SondeScreen(ui, vm)
                    ui.screen == Screen.ROTOR -> RotorScreen(ui, vm)
                    ui.screen == Screen.QO100 -> Qo100Screen(ui, vm)
                    ui.screen == Screen.NOMMAGE -> NommageScreen(ui, vm)
                    ui.screen == Screen.AGENDA -> AgendaScreen(ui, vm)
                    ui.screen == Screen.LOCATOR -> LocatorScreen(ui, vm)
                    ui.screen == Screen.FT8 -> Ft8Screen(ui, vm)
                    ui.screen == Screen.GLOBE -> GlobeScreen(ui, vm)
                    ui.screen == Screen.SETTINGS -> SettingsScreen(ui, vm)
                    ui.selected != null -> DetailScreen(ui, vm, onBack = vm::backToList)
                    else -> PassesScreen(ui, vm)
                }
            }
        }
        // Without a callsign nothing can be logged, shared or reported to AMSAT,
        // so it is asked once at start-up — with a way to never be asked again.
        if (ui.askCallsign) CallsignPromptDialog(vm)

        // Contact editor in the root Box, above any screen. It used to live
        // in the detail screen only, so tapping a log line (in settings)
        // opened nothing visible.
        ui.logEditTimeMs?.let { tEdit ->
            ui.log.firstOrNull { it.timeMs == tEdit }?.let { e ->
                LogEditDialog(e, ui.useUtc,
                    satellites = ui.satellites,
                    onCorrigeSat = { sa, quand -> vm.corrigeSatellite(e.timeMs, sa, quand) },
                    onSave = { c, g, n, m, rs, rr ->
                        vm.updateLogEntry(tEdit, c, g, n, m, rs, rr); vm.dismissLogEdit()
                    },
                    onDismiss = vm::dismissLogEdit)
            }
        }

        // TX border in the root Box, over every screen (not in the action
        // bar, where it only covered the header).
        if (ui.catUi.enEmission) {
            val pulse = rememberInfiniteTransition(label = "tx")
            val eclat by pulse.animateFloat(
                initialValue = 0.5f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700), repeatMode = RepeatMode.Reverse),
                label = "txAlpha")
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val e = 5.dp.toPx()
                drawRect(
                    color = Color(0xFFFF2D2D).copy(alpha = eclat),
                    topLeft = androidx.compose.ui.geometry.Offset(e / 2, e / 2),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - e, size.height - e),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = e))
            }
        }
    }
}

/** First-run nudge: the callsign is missing, here is the field to fill it in. */
@Composable
private fun CallsignPromptDialog(vm: MainViewModel) {
    var call by remember { mutableStateOf("") }
    var never by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.dismissCallsignPrompt(never) },
        title = { Text(t("callsign_ask_title"), color = TextHi, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t("callsign_ask_desc"), color = TextLo, fontSize = 13.sp)
                OutlinedTextField(
                    value = call,
                    onValueChange = { call = it.uppercase().filter { c -> !c.isWhitespace() } },
                    singleLine = true,
                    label = { Text(t("callsign_label")) },
                    placeholder = { Text("F4IOZ") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(value = never, role = Role.Checkbox,
                        onValueChange = { never = it })) {
                    Checkbox(checked = never, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = Cyan))
                    Text(t("callsign_ask_never"), color = TextLo, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (call.isNotBlank()) vm.setCallsign(call.trim())
                    vm.dismissCallsignPrompt(never)
                },
                enabled = call.isNotBlank()
            ) { Text(t("save"), color = Cyan, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissCallsignPrompt(never) }) {
                Text(t("later"), color = TextLo)
            }
        },
        containerColor = SpaceCard
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(ui: UiState, vm: MainViewModel) {
    val fiche = if (ui.screen == Screen.PASSES) ui.selected else null
    Column {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent, titleContentColor = TextHi
        ),
        title = {
            if (fiche != null) TitreSatellite(fiche)
            else Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Cyan))
                    Spacer(Modifier.width(8.dp))
                    Text(when (ui.screen) {
                            Screen.SETTINGS -> t("settings_title")
                            Screen.FT8 -> t("ft8_titre")
                            Screen.GLOBE -> t("globe_title")
                            Screen.SKED -> t("sked_page_title")
                            Screen.TIMELINE -> t("timeline_title")
                            Screen.PHOTO -> t("photo_title")
                            Screen.ACTIVATION -> t("act_title")
                            Screen.SSTV -> t("sstv_title")
                            Screen.APRS -> t("aprs_titre")
                            Screen.SDR -> t("sdr_title")
                            Screen.APT -> t("apt_title")
                            Screen.SONDE -> t("sonde_title")
                            Screen.ROTOR -> t("rotor_title")
                            Screen.QO100 -> t("qo100_title")
                            Screen.NOMMAGE -> t("entry_title")
                            Screen.AGENDA -> t("agenda_title")
                            else -> "SatMe"
                        },
                        fontWeight = FontWeight.Black, letterSpacing = 0.5.sp,
                        fontSize = 18.sp, maxLines = 1, softWrap = false,
                        // Ellipsis rather than a hard clip: a narrow phone used
                        // to cut "SatMe" into "Satl" with no visual clue.
                        overflow = TextOverflow.Ellipsis)
                }
                ui.observer?.let { obs ->
                    // Extract just the Maidenhead locator from the observer name
                    // ("GPS · JN18FS" -> "JN18FS", or a plain manual locator).
                    val loc = obs.name.substringAfterLast("· ").trim().ifEmpty { obs.name }
                    val isGps = ui.locationMode == LocationMode.AUTO
                    // In automatic mode before the first fix, the square is
                    // the hard-coded fallback of `LocationProvider.defaultObserver`
                    // (F6KMX's QTH), not ours. Showing it as measured would lie
                    // about the value every azimuth depends on, so say we are
                    // searching until the first fix.
                    val cherche = isGps && ui.suivi.points == 0
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.openLocator() }
                            .padding(vertical = 2.dp, horizontal = 2.dp)) {
                        Icon(
                            imageVector = when {
                                cherche -> Icons.Default.GpsNotFixed
                                isGps -> Icons.Default.MyLocation
                                else -> Icons.Default.EditLocationAlt
                            },
                            contentDescription = when {
                                cherche -> t("gps_searching")
                                isGps -> t("gps_position")
                                else -> t("manual_locator")
                            },
                            tint = when {
                                cherche -> Amber
                                isGps -> Color(0xFF2DBE6B)
                                else -> Amber
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (cherche) t("gps_searching") else loc,
                            color = if (cherche) Amber else TextHi,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (cherche) 13.sp else 15.sp,
                            fontFamily = if (cherche) FontFamily.Default else FontFamily.Monospace,
                            letterSpacing = 0.5.sp,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        navigationIcon = {
            // **One screen list only**, `RetourArriere`'s. The top bar had its
            // own hand-written list, which lacked FT8 and the logging screen
            // (no way out). Now the arrow and the phone's back button do the
            // same thing, and the test walking all screens covers both.
            //
            // `sectionReglages = false` is intended: in settings the arrow
            // closes the whole page, while the back button first closes the
            // subsection.
            val fermeture: (() -> Unit)? = when (
                RetourArriere.geste(ui.screen, sectionReglages = false,
                    selection = false, modeSelection = false)
            ) {
                RetourArriere.Geste.FERMER_REGLAGES -> vm::closeSettings
                RetourArriere.Geste.FERMER_FT8 -> vm::fermeFt8
                RetourArriere.Geste.FERMER_GLOBE -> vm::fermeGlobe
                RetourArriere.Geste.FERMER_LOCATOR -> vm::closeLocator
                RetourArriere.Geste.FERMER_SKED -> vm::closeSked
                RetourArriere.Geste.FERMER_TIMELINE -> vm::closeTimeline
                RetourArriere.Geste.FERMER_PHOTO -> vm::closePhoto
                RetourArriere.Geste.FERMER_ACTIVATION -> vm::closeActivation
                RetourArriere.Geste.FERMER_SSTV -> vm::closeSstv
                RetourArriere.Geste.FERMER_APRS -> vm::closeAprs
                RetourArriere.Geste.FERMER_SDR -> vm::closeSdr
                RetourArriere.Geste.FERMER_APT -> vm::closeApt
                RetourArriere.Geste.FERMER_SONDE -> vm::closeSonde
                RetourArriere.Geste.FERMER_ROTOR -> vm::closeRotor
                RetourArriere.Geste.FERMER_QO100 -> vm::closeQo100
                RetourArriere.Geste.FERMER_AGENDA -> vm::closeAgenda
                RetourArriere.Geste.FERMER_NOMMAGE -> vm::fermeNommage
                // The satellite page: its header is the bar, so is its way back.
                else -> if (ui.screen == Screen.PASSES && ui.selected != null) vm::backToList else null
            }
            if (fermeture != null) {
                IconButton(onClick = fermeture) {
                    Icon(Icons.Default.ArrowBack, t("back"), tint = Cyan)
                }
            }
        },
        actions = {
            // Scroll padlock — detail screen only: open = free, closed = the
            // page stays where you put it (no accidental scrolls in the field).

            ui.selected?.takeIf { ui.screen == Screen.PASSES }?.let { sat ->
                // QRV photo, satellite already attached: the photo screen then
                // knows which pass to draw as a polar plot.
                val shownPass = ui.focusedPassAos?.let { f -> ui.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
                    ?: ui.passes.firstOrNull { it.losEpochMs > ui.nowMs }
                IconButton(onClick = { vm.openPhoto(sat.catalogNumber, shownPass?.aosEpochMs) }) {
                    Icon(Icons.Default.PhotoCamera, t("photo_title"), tint = Cyan)
                }
                val isFav = sat.catalogNumber in ui.favorites
                IconButton(onClick = { vm.toggleFavorite(sat.catalogNumber) }) {
                    if (isFav) Icon(Icons.Default.Star, t("favorite"), tint = Amber)
                    else Icon(Icons.Outlined.StarBorder, t("favorite"), tint = TextLo)
                }
            }
            if (ui.screen == Screen.PASSES && ui.selected != null) {
                IconButton(onClick = { vm.toggleUiLock() }) {
                    Icon(if (ui.uiLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        t(if (ui.uiLocked) "unlock_screen" else "lock_screen"),
                        tint = if (ui.uiLocked) Amber else TextLo)
                }
            }
            // Audio recorder toggle — hidden unless enabled in settings; then
            // shown on the passes list and the satellite detail screen.
            if (ui.screen == Screen.PASSES && ui.recorderEnabled) {
                RecButton(ui, vm)
            }
            if (ui.screen == Screen.PASSES && ui.selected == null) {
                // Timeline stays a one-tap icon (it is the "what's coming" view);
                // the rarer pages moved to the overflow so the title keeps room.
                IconButton(onClick = { vm.openTimeline() }) {
                    Icon(Icons.Default.Timeline, t("timeline_title"), tint = TextLo)
                }
                // (LOC/UTC selector lives in Settings > Time: the bar was
                // crushing the title.)
                IconButton(onClick = { vm.bootstrap(force = true) }) {
                    Icon(Icons.Default.Refresh, t("refresh"), tint = Cyan)
                }
                OverflowMenu(ui, vm)
            }
            if (ui.screen != Screen.SETTINGS) {
                IconButton(onClick = { vm.openSettings(Chemin.sectionDe(ui.screen)) }) {
                    Icon(Icons.Default.Settings, t("settings_title"), tint = TextLo)
                }
            }
        }
    )
    // The satellite's badges under the bar, on the full width.
    if (fiche != null) BadgesSatellite(ui, vm, fiche,
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
    }
}

/**
 * "⋮" menu of the passes list: the pages that do not deserve a permanent icon —
 * sked, QRV photo, field session — plus the two list tools (selection export and
 * date filter) that used to crowd the bar.
 */
@Composable
private fun OverflowMenu(ui: UiState, vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    val running = ui.activations.any { it.running }
    // Lights up while a picture is actually coming in, so the menu is worth
    // opening mid-pass.
    val sstvState by fr.f4ioz.satcombo.sstv.SstvHub.state.collectAsState()
    val sstvOn = sstvState.modeName != null
    val sdrState by fr.f4ioz.satcombo.sdr.SdrHub.state.collectAsState()
    val sdrOn = sdrState.running
    val aptState by fr.f4ioz.satcombo.apt.AptHub.state.collectAsState()
    val aptOn = aptState.locked
    val sondeState by fr.f4ioz.satcombo.sonde.SondeHub.state.collectAsState()
    val sondeOn = sondeState.running
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, t("more"),
                tint = if (running) Amber else TextLo)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Three kinds of entries, three groups: pages to get ready before
            // a pass, pages used on the air, and what changes the list behind.
            MenuHeader(t("menu_grp_preparer"))
            // Globe: in the menu, the top bar is full.
            DropdownMenuItem(
                text = { Text(t("globe_title")) },
                leadingIcon = { Icon(Icons.Default.Public, null, tint = Cyan) },
                onClick = { open = false; vm.ouvreGlobe() })
            DropdownMenuItem(
                text = { Text(t("agenda_title")) },
                leadingIcon = { Icon(Icons.Default.CalendarMonth, null, tint = Cyan) },
                onClick = { open = false; vm.openAgenda() })
            DropdownMenuItem(
                text = { Text(t("sked_page_title")) },
                leadingIcon = { Icon(Icons.Default.Handshake, null, tint = Cyan) },
                onClick = { open = false; vm.openSked() })
            HorizontalDivider()
            MenuHeader(t("grp_traffic"))
            DropdownMenuItem(
                text = { Text(if (running) t("act_title_running") else t("act_title")) },
                leadingIcon = {
                    Icon(Icons.Default.Tour, null,
                        tint = if (running) Amber else Cyan)
                },
                onClick = { open = false; vm.openActivation() })
            DropdownMenuItem(
                text = { Text(t("photo_title")) },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, null, tint = Cyan) },
                onClick = { open = false; vm.openPhoto() })
            DropdownMenuItem(
                text = { Text(t("ft8_titre")) },
                leadingIcon = { Icon(Icons.Default.GraphicEq, null, tint = Cyan) },
                onClick = { open = false; vm.ouvreFt8() })
            // Beta features: shown only with the key (callsign F4IOZ or the
            // "Extensions" settings field).
            if (fr.f4ioz.satcombo.data.Extensions.SSTV in ui.extensions) {
                DropdownMenuItem(
                    text = { Text(t("sstv_title")) },
                    leadingIcon = {
                        Icon(Icons.Default.LiveTv, null,
                            tint = if (sstvOn) Aurora else Cyan)
                    },
                    onClick = { open = false; vm.openSstv() })
            }
            if (fr.f4ioz.satcombo.data.Extensions.APRS in ui.extensions) {
                DropdownMenuItem(
                    text = { Text(t("aprs_titre")) },
                    leadingIcon = { Icon(Icons.Default.Place, null, tint = Cyan) },
                    onClick = { open = false; vm.openAprs() })
            }
            if (fr.f4ioz.satcombo.data.Extensions.APT in ui.extensions) {
                DropdownMenuItem(
                    text = { Text(t("apt_title")) },
                    leadingIcon = {
                        Icon(Icons.Default.WbCloudy, null,
                            tint = if (aptOn) Aurora else Cyan)
                    },
                    onClick = { open = false; vm.openApt() })
            }
            if (fr.f4ioz.satcombo.data.Extensions.SDR in ui.extensions) {
                DropdownMenuItem(
                    text = { Text(t("sdr_menu")) },
                    leadingIcon = {
                        Icon(Icons.Default.Usb, null,
                            tint = if (sdrOn) Aurora else Cyan)
                    },
                    onClick = { open = false; vm.openSdr() })
            }
            if (fr.f4ioz.satcombo.data.Extensions.SONDE in ui.extensions) {
                DropdownMenuItem(
                    text = { Text(t("sonde_title")) },
                    leadingIcon = {
                        Icon(Icons.Default.Air, null,
                            tint = if (sondeOn) Aurora else Cyan)
                    },
                    onClick = { open = false; vm.openSonde() })
            }
            if (fr.f4ioz.satcombo.data.Extensions.QO100 in ui.extensions) {
                DropdownMenuItem(
                    text = { Text(t("menu_qo100")) },
                    leadingIcon = {
                        Icon(Icons.Default.SatelliteAlt, null,
                            tint = if (ui.qo100.auPoste || ui.qo100.aLaCle) Aurora else Cyan)
                    },
                    onClick = { open = false; vm.openQo100() })
            }
            if (fr.f4ioz.satcombo.data.Extensions.ROTOR in ui.extensions) {
                DropdownMenuItem(
                    text = { Text(t("menu_rotor")) },
                    leadingIcon = {
                        Icon(Icons.Default.Radar, null,
                            tint = if (ui.rotorConnected) Aurora else Cyan)
                    },
                    onClick = { open = false; vm.openRotor() })
            }
            HorizontalDivider()
            MenuHeader(t("menu_grp_liste"))
            DropdownMenuItem(
                text = { Text(t("selection_export")) },
                leadingIcon = {
                    Icon(Icons.Default.Checklist, null,
                        tint = if (ui.selectionMode) Cyan else TextLo)
                },
                onClick = { open = false; vm.toggleSelectionMode() })
            DropdownMenuItem(
                text = { Text(t("filter_by_dates")) },
                leadingIcon = {
                    Icon(Icons.Default.FilterAlt, null,
                        tint = if (ui.dateFilter != null) Amber else TextLo)
                },
                onClick = { open = false; vm.requestDatePicker() })
        }
    }
}

/** Group title in the ⋮ menu: not clickable, read by TalkBack as a heading. */
@Composable
private fun MenuHeader(text: String) {
    Text(text, color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp)
            .semantics { heading() })
}

/**
 * REC/STOP toggle for the pass audio recorder. Idle: a red record dot. Recording:
 * a stop square + running mm:ss timer. Requests the microphone permission the
 * first time. Recording itself (mic -> MP3) and the LOS+5s auto-stop live in the
 * ViewModel, so it keeps going across screen changes within the app.
 */
@Composable
private fun RecButton(ui: UiState, vm: MainViewModel) {
    val recColor = Color(0xFFE5484D)
    val demarre = rememberDemarrageEnregistrement(ui) { vm.startRecording() }
    if (ui.recording) {
        val secs = ((ui.nowMs - ui.recordStartMs) / 1000).coerceAtLeast(0)
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable { vm.stopRecording() }
                .padding(horizontal = 8.dp, vertical = 5.dp)) {
            Icon(Icons.Default.Stop, t("rec_stop"), tint = recColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("%d:%02d".format(secs / 60, secs % 60), color = recColor,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        IconButton(onClick = demarre) {
            Icon(Icons.Default.FiberManualRecord, t("rec_start"), tint = recColor,
                modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Starts a recording once the permissions are there: RECORD_AUDIO always,
 * BLUETOOTH_CONNECT too when the BT source is chosen (runtime permission on
 * Android 12+, needed to open the HFP/SCO link). Shared by every button that
 * records, so none of them fails silently on a first use.
 */
@Composable
internal fun rememberDemarrageEnregistrement(ui: UiState, demarre: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[android.Manifest.permission.RECORD_AUDIO] == true) demarre()
    }
    return {
        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (ui.recorderSource == "BT" && android.os.Build.VERSION.SDK_INT >= 31)
            perms.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        val missing = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) demarre() else launcher.launch(missing.toTypedArray())
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Cyan)
            Spacer(Modifier.height(16.dp))
            Text(t("downloading_elements"), color = TextLo)
        }
    }
}

// ---------- main screen: upcoming passes of favorites only ----------


/** Date/time formatter honoring the UTC/local preference, with a tz tag. */
internal fun tzFormat(pattern: String, useUtc: Boolean): SimpleDateFormat =
    SimpleDateFormat(pattern, if (pattern.any { it.isLetter() && it in "EMMMd" }) fr.f4ioz.satcombo.i18n.I18n.locale() else Locale.getDefault())
        .apply { if (useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC") }

internal fun tzTag(useUtc: Boolean) = if (useUtc) "UTC" else "LOC"

/** Day label honoring the UTC/local preference (for list day separators). */
private fun dayLabel(ms: Long, useUtc: Boolean): String =
    tzFormat("EEEE dd MMMM", useUtc).format(java.util.Date(ms))

@Composable
private fun PassesScreen(ui: UiState, vm: MainViewModel) {
    if (ui.favorites.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t("no_followed_sats"), color = TextHi, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(t("pick_sats_hint"),
                    color = TextLo, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.openSettings() },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                    Text(t("open_settings"), color = Color(0xFF00201D))
                }
            }
        }
        return
    }
    val passes = (if (ui.visualOnly) ui.favoritePasses.filter { it.visualPass } else ui.favoritePasses)
        .filter { it.losEpochMs > ui.nowMs - ui.pastPassHours * 3_600_000L }
    // Finished passes are kept but parked ABOVE the viewport: the page opens on
    // the current/next pass, and you swipe down to reveal what is already gone.
    val pastPasses = passes.filter { it.losEpochMs <= ui.nowMs }
    val futurePasses = passes.filter { it.losEpochMs > ui.nowMs }
    val next = futurePasses.firstOrNull()

    val listState = rememberLazyListState()
    var anchored by remember { mutableStateOf(false) }
    LaunchedEffect(pastPasses.isNotEmpty()) {
        // Only once per visit, and only while the user has not scrolled himself.
        if (!anchored && pastPasses.isNotEmpty() && listState.firstVisibleItemIndex == 0) {
            listState.scrollToItem(1)
            anchored = true
        }
    }

    // Reaching the bottom extends the horizon by two days. `canScrollBackward`
    // prevents firing on a list shorter than the screen, which would run away
    // on its own up to fifteen days.
    val atListEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 &&
                listState.canScrollBackward
        }
    }
    LaunchedEffect(atListEnd, ui.passHorizonHours, ui.favPassesLoading, ui.dateFilter) {
        if (atListEnd && ui.dateFilter == null && !ui.favPassesLoading &&
            ui.passHorizonHours < MAX_PASS_HOURS) {
            vm.extendPassHorizon()
        }
    }

    // Upcoming skeds, optionally filtered to workable-only, sorted by time.
    val upcomingSkeds = ui.skeds
        .filter { !ui.skedsMutualOnly || it.isWorkable != false }
        .sortedBy { it.workableStartMs ?: it.aosMs }
    var skedsExpanded by remember { mutableStateOf(false) }  // collapsed by default
    var potaExpanded by remember { mutableStateOf(false) }   // collapsed by default

    if (ui.showDatePicker) {
        TripDatesDialog(
            useUtc = ui.useUtc,
            onConfirm = { s, e -> vm.setDateFilter(s, e) },
            onDismiss = vm::dismissDatePicker
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Item 0 = the past passes. Keeping them in ONE item is what makes the
        // "open at index 1" trick exact whatever the banners above show.
        if (pastPasses.isNotEmpty()) {
            item(key = "past") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("past_hidden"), color = TextLo, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    pastPasses.forEach { p ->
                        PassCard(p, ui.nowMs, showSat = true, useUtc = ui.useUtc,
                            agendaTitle = vm.agendaForPass(p)?.title,
                            amsatStatus = if (ui.statusSource != "SATNOGS") vm.amsatFor(p.satName, p.catalogNumber)?.recent else null
                        ) { vm.selectByCatnum(p.catalogNumber, p.aosEpochMs) }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A3647)))
                }
            }
        }
        ui.dateFilter?.let { (s, e) ->
            item {
                val df = tzFormat("EEE dd MMM", ui.useUtc)
                Surface(color = Cyan.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("📅 ${df.format(Date(s))} → ${df.format(Date(e - 1))}  ·  ${passes.size} " + t("on_period"),
                            color = Cyan, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = vm::clearDateFilter) {
                            Icon(Icons.Default.Close, t("clear_filter"), tint = Cyan,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            // Date filter prepares a given day; if it covers an announced
            // slot, say so here rather than hunting for the badge pass by pass.
            val dayEvents = vm.agendaInRange(s, e)
            if (dayEvents.isNotEmpty()) {
                item {
                    Surface(color = Magenta.copy(alpha = 0.13f), shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().clickable { vm.openAgenda() }) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.EventNote, null, tint = Magenta,
                                    modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("agenda_period_banner"), color = Magenta,
                                    fontWeight = FontWeight.Black, fontSize = 11.sp,
                                    letterSpacing = 1.sp)
                            }
                            dayEvents.take(3).forEach { ev ->
                                Text(listOfNotNull(
                                    ev.kind.ifBlank { null },
                                    ev.title,
                                    ev.satName.ifBlank { null },
                                    agendaWhen(ev, ui.useUtc),
                                    if (ev.freqHz > 0L) "\u2193 " + agendaFreqLabel(ev.freqHz) else null
                                ).joinToString("  \u00B7  "),
                                    color = TextHi, fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp))
                            }
                            if (dayEvents.size > 3)
                                Text("+${dayEvents.size - 3}", color = Magenta, fontSize = 10.sp)
                        }
                    }
                }
            }
            if (s > ui.nowMs + 7L * 86_400_000) {
                item {
                    Surface(color = Amber.copy(alpha = 0.13f), shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(t("stale_before_trip"),
                            color = Amber, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
        ui.tleCacheAgeMs?.let { age ->
            item {
                Surface(color = Amber.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(tf("offline_cache", fmtAge(age)),
                        color = Amber, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                }
            }
        }
        ui.error?.let {
            item {
                Surface(color = Magenta.copy(alpha = 0.18f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(10.dp), color = Magenta, fontSize = 12.sp)
                }
            }
        }
        if (ui.selectionMode) {
            item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Surface(color = Cyan.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    // Two lines: the French labels squeezed the PDF button to nothing on one.
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tf("n_selected", ui.selectedPassKeys.size),
                            color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.selectAllVisible(passes) }) {
                            Text(t("all_sel"), color = TextLo, fontSize = 12.sp)
                        }
                        TextButton(onClick = { vm.clearPassSelection() }) {
                            Text(t("none_sel"), color = TextLo, fontSize = 12.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        val enregistreFiches = rememberEnregistrer()
                        // Save or share: first, how many coming passes per satellite.
                        var demande by remember { mutableStateOf<String?>(null) }
                        demande?.let { quoi ->
                            QuestionPassagesPdf(vm.satellitesSelectionnes(), vm.pdfNbPassages(),
                                onAnnule = { demande = null }) { n ->
                                demande = null
                                vm.exportSelectionPdf(n) { uri ->
                                    if (quoi == "SAVE") enregistreFiches(nomDate("SatMe-fiches", "pdf"),
                                        "application/pdf", depuisUri(ctx, uri))
                                    else {
                                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "application/pdf"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        ctx.startActivity(android.content.Intent.createChooser(share, t("pdf_sheets")))
                                    }
                                }
                            }
                        }
                        TextButton(
                            onClick = { demande = "SAVE" },
                            enabled = ui.selectedPassKeys.isNotEmpty()
                        ) {
                            Text(t("export_save"), color = Cyan, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { demande = "SHARE" },
                            enabled = ui.selectedPassKeys.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, null, Modifier.size(16.dp), tint = Color(0xFF00201D))
                            Spacer(Modifier.width(4.dp))
                            Text("PDF", color = Color(0xFF00201D), fontSize = 13.sp)
                        }
                    }
                    }
                }
            }
        }
        next?.let { np ->
            item {
                // The hero card carries the sked marker too: an announced sked on the
                // very next (or current) pass has to be visible on the main page,
                // without opening the satellite.
                HeroNextPass(np, ui.nowMs, ui.useUtc,
                    if (ui.skedsEnabled) vm.skedsForPass(np.catalogNumber, np.aosEpochMs, np.losEpochMs)
                    else emptyList(),
                    agenda = vm.agendaForPass(np),
                    amsatStatus = if (ui.statusSource != "SATNOGS")
                        vm.amsatFor(np.satName, np.catalogNumber)?.recent else null
                ) { vm.selectByCatnum(np.catalogNumber, np.aosEpochMs) }
            }
            // Before the pass: is the station ready? (SatMe 21)
            item {
                OutlinedButton(onClick = { vm.ouvrePreparation(np.catalogNumber) }, modifier = Modifier.fillMaxWidth()) {
                    Text("✓ " + t("rd_preparer"), color = Cyan, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Announced skeds (hams.at) — collapsible, hidden when empty, optionally
        // filtered to workable-only. Shown regardless of favorites / 48h window.
        if (ui.skedsEnabled && upcomingSkeds.isNotEmpty()) {
            item {
                Surface(color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)
                        .clickable { skedsExpanded = !skedsExpanded }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (skedsExpanded) "▾" else "▸", color = Amber,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.padding(end = 6.dp))
                        Text(t("upcoming_skeds"), color = Amber, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        Surface(color = Amber.copy(alpha = 0.16f), shape = RoundedCornerShape(10.dp)) {
                            Text("${upcomingSkeds.size}", color = Amber, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp))
                        }
                        if (ui.skedsAuthed) {
                            Spacer(Modifier.width(8.dp))
                            Text("· hams.at", color = TextLo, fontSize = 11.sp)
                        }
                        if (ui.skedsMutualOnly) {
                            Spacer(Modifier.width(6.dp))
                            Text("· visibles", color = Aurora, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (skedsExpanded) {
                val day = tzFormat("EEE dd/MM HH:mm", ui.useUtc)
                items(upcomingSkeds, key = { "sk" + it.callsign + it.aosMs }) { s ->
                    val startMs = s.workableStartMs ?: s.aosMs
                    val workable = s.isWorkable
                    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clickable { vm.selectByCatnum(s.satNorad, startMs) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Amber.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp)) {
                                Text("RV", color = Amber, fontWeight = FontWeight.Black, fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${s.callsign}  ·  ${s.satName}", color = TextHi,
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(day.format(Date(startMs)) + " ${tzTag(ui.useUtc)}" +
                                        (s.mode?.let { "  ·  $it" } ?: "") +
                                        (s.grids.firstOrNull()?.let { "  ·  $it" } ?: ""),
                                    color = TextLo, fontSize = 12.sp)
                            }
                            if (s.maxElevationDeg != null) {
                                Text("${s.maxElevationDeg.toInt()}°", color = Cyan,
                                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                            }
                            when (workable) {
                                true -> Text("✓", color = Color(0xFF49D17F), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                false -> Text("✗", color = TextLo, fontSize = 16.sp)
                                null -> {}
                            }
                        }
                    }
                }
            }
        }

        // POTA — just below the skeds section.
        if (ui.potaEnabled && ui.nearbyPota.isNotEmpty()) {
            item {
                val anyInside = ui.nearbyPota.any { it.inside }
                // The green must stay readable on all four themes: light on
                // dark, dark on light.
                val sombre = SpaceBg.luminance() < 0.4f
                val green = if (sombre) Color(0xFF7FE3A0) else Color(0xFF1B6B3A)
                Surface(color = if (sombre) Color(0xFF13261C) else Color(0xFFE2F5E9),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { potaExpanded = !potaExpanded }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (potaExpanded) "▾" else "▸", color = green,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                modifier = Modifier.padding(end = 6.dp))
                            Text(if (anyInside) t("pota_in_zone").uppercase() else t("pota_nearby"),
                                color = green, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp, fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                            Surface(color = green.copy(alpha = 0.16f), shape = RoundedCornerShape(10.dp)) {
                                Text("${ui.nearbyPota.size}", color = green, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp))
                            }
                        }
                        // Collapsed, still show WHICH park we are in.
                        val dedans = ui.nearbyPota.firstOrNull { it.inside }
                        if (!potaExpanded && dedans != null) {
                            Text("${dedans.park.reference} · ${dedans.park.name}",
                                color = green, fontSize = 12.sp, maxLines = 2,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        if (potaExpanded) {
                            Spacer(Modifier.height(6.dp))
                            ui.nearbyPota.forEach { hit ->
                                val tag = if (hit.inside) "✓ " else ""
                                Text("$tag${hit.park.reference} · ${hit.park.name}  " +
                                    "(${fr.f4ioz.satcombo.data.Units.distance(hit.distanceKm, ui.units)})",
                                    color = if (hit.inside) green else TextHi,
                                    fontSize = 13.sp,
                                    fontWeight = if (hit.inside) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                Text(
                    when {
                        ui.dateFilter != null -> t("passes_in_period")
                        ui.passHorizonHours >= 72 ->
                            tf("next_passes_days", ui.passHorizonHours / 24)
                        else -> tf("next_passes_hours", ui.passHorizonHours)
                    },
                    color = TextLo, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp, fontSize = 12.sp)
                if (ui.favPassesLoading) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(Modifier.size(14.dp), color = Cyan, strokeWidth = 2.dp)
                }
            }
        }
        if (passes.isEmpty() && !ui.favPassesLoading) {
            item { Text(t("no_upcoming_passes"), color = TextLo) }
        }
        val listPasses = futurePasses.drop(if (next != null) 1 else 0)
        itemsIndexed(listPasses,
            key = { _, it -> "p" + it.catalogNumber + it.aosEpochMs }) { idx, p ->
            // Day separator when the calendar day changes.
            val showDay = idx == 0 ||
                dayLabel(listPasses[idx - 1].aosEpochMs, ui.useUtc) != dayLabel(p.aosEpochMs, ui.useUtc)
            Column {
                if (showDay) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = if (idx == 0) 2.dp else 12.dp, bottom = 4.dp)) {
                        Text(dayLabel(p.aosEpochMs, ui.useUtc).replaceFirstChar { it.uppercase() },
                            color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            letterSpacing = 0.5.sp)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF2A3647)))
                    }
                }
                PassCard(p, ui.nowMs, showSat = true,
                    selectable = ui.selectionMode,
                    checked = vm.passKey(p) in ui.selectedPassKeys,
                    onCheck = { vm.togglePassSelected(p) },
                    skedCount = if (ui.skedsEnabled) vm.skedCountForPass(p.catalogNumber, p.aosEpochMs, p.losEpochMs) else 0,
                    agendaTitle = vm.agendaForPass(p)?.title,
                    useUtc = ui.useUtc,
                    amsatStatus = if (ui.statusSource != "SATNOGS") vm.amsatFor(p.satName, p.catalogNumber)?.recent else null,
                    showBell = ui.notifyEnabled && ui.notifyMode == "TARGET",
                    bellOn = vm.isPassNotified(p),
                    onBell = { vm.togglePassNotify(p) }
                ) {
                    if (ui.selectionMode) vm.togglePassSelected(p) else vm.selectByCatnum(p.catalogNumber, p.aosEpochMs)
                }
            }
        }
        // Footer: shows where the list stops, and extends it by hand when the
        // list fits on screen and scrolling cannot trigger it.
        if (ui.dateFilter == null && passes.isNotEmpty()) {
            item(key = "horizon") {
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center) {
                    when {
                        ui.favPassesLoading ->
                            CircularProgressIndicator(Modifier.size(18.dp),
                                color = Cyan, strokeWidth = 2.dp)
                        ui.passHorizonHours >= MAX_PASS_HOURS ->
                            Text(tf("passes_horizon_max", MAX_PASS_HOURS / 24),
                                color = TextLo, fontSize = 11.sp)
                        else -> TextButton(onClick = vm::extendPassHorizon) {
                            Text(t("passes_load_more"), color = Cyan, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Fifteen days: beyond that, orbital elements are no longer reliable. */
private const val MAX_PASS_HOURS = 15 * 24

@Composable
private fun HeroNextPass(
    p: SatPass, now: Long, useUtc: Boolean,
    skeds: List<fr.f4ioz.satcombo.data.SkedAlert> = emptyList(),
    /** Agenda event whose slot covers this pass, if any. */
    agenda: fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent? = null,
    /** AMSAT status, for the badge next to the name. */
    amsatStatus: fr.f4ioz.satcombo.data.AmsatStatus? = null,
    onClick: () -> Unit
) {
    val phase = passPhase(p, now)
    Surface(color = SpaceCard, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (phase is PassPhase.Active) t("current_pass") else t("next_pass_hero"),
                color = if (phase is PassPhase.Active) Cyan else TextLo,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 11.sp
            )
            // Announced sked(s) on this very pass: right under the title, so the
            // information is caught at a glance on the main page.
            if (skeds.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val hmS = tzFormat("HH:mm", useUtc)
                Surface(color = Amber.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("\uD83D\uDCFB " + skeds.take(2).joinToString("  ·  ") { it.callsign } +
                                (if (skeds.size > 2) "  +${skeds.size - 2}" else ""),
                            color = Amber, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        val s0 = skeds.first()
                        val ws = s0.workableStartMs
                        val we = s0.workableEndMs
                        // Cheap fields only. The expensive mutual-window solver
                        // stays where it already runs, on the detail page.
                        Text(
                            if (ws != null && we != null)
                                tf("common_window_line", hmS.format(Date(ws)), hmS.format(Date(we)), tzTag(useUtc))
                            else tf("hamsat_window", hmS.format(Date(s0.aosMs)), hmS.format(Date(s0.losMs)), tzTag(useUtc)),
                            color = Amber.copy(alpha = 0.85f), fontSize = 10.sp)
                    }
                }
            }
            // An agenda event on this pass is the reason to go out: show it
            // before the countdown.
            agenda?.let { ev ->
                Spacer(Modifier.height(6.dp))
                Surface(color = Magenta.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventNote, contentDescription = t("agenda_on_pass_cd"),
                            tint = Magenta, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(listOfNotNull(ev.kind.ifBlank { null }, ev.title)
                            .joinToString("  \u00B7  "),
                            color = Magenta, fontWeight = FontWeight.Black, fontSize = 12.sp,
                            maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        if (ev.freqHz > 0L) {
                            Spacer(Modifier.width(6.dp))
                            Text("\u2193 " + agendaFreqLabel(ev.freqHz), color = Amber,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Status badge after the name, as in the list: knowing a
                    // satellite is silent saves a trip out.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape)
                                .background(couleurStatut(amsatStatus)))
                            Spacer(Modifier.width(8.dp))
                        Text(p.satName, color = TextHi, fontWeight = FontWeight.Black,
                            fontSize = 20.sp)
                    }
                    val hm = tzFormat("HH:mm:ss", useUtc)
                    Text(tf("aos_elmax_line", hm.format(Date(p.aosEpochMs)), tzTag(useUtc), p.maxElevationDeg.toInt()) +
                            if (p.visualPass) " · 👁" else "",
                        color = TextLo, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    when (phase) {
                        is PassPhase.Upcoming -> Text(fmtCountdown(phase.inMs), color = Amber,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 26.sp)
                        is PassPhase.Active -> Text("LOS " + fmtCountdown(phase.remainingMs).removePrefix("T-"),
                            color = Cyan, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black, fontSize = 22.sp)
                        else -> {}
                    }
                }
                if (p.track.size > 1) {
                    val arc = when {
                        p.maxElevationDeg >= 50 -> Cyan
                        p.maxElevationDeg >= 25 -> Aurora
                        else -> TextLo
                    }
                    MiniPolarPlot(p.track, arc, Modifier.size(96.dp))
                }
            }
            if (phase is PassPhase.Active) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { phase.progress },
                    color = Cyan, trackColor = Color(0xFF20293A),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

// ---------- settings ----------

@Composable
internal fun PassCard(
    p: SatPass, now: Long, showSat: Boolean = false,
    selectable: Boolean = false, checked: Boolean = false, onCheck: (() -> Unit)? = null,
    skedCount: Int = 0, highlight: Boolean = false, useUtc: Boolean = false,
    /** Title of the agenda event on this pass, or null. */
    agendaTitle: String? = null,
    amsatStatus: fr.f4ioz.satcombo.data.AmsatStatus? = null,
    showBell: Boolean = false, bellOn: Boolean = false, onBell: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val day = tzFormat("EEE dd/MM", useUtc)
    val hm = tzFormat("HH:mm:ss", useUtc)
    val phase = passPhase(p, now)
    val elColor = when {
        p.maxElevationDeg >= 50 -> Cyan
        p.maxElevationDeg >= 25 -> Aurora
        else -> TextLo
    }
    val base = Modifier.fillMaxWidth().alpha(if (phase is PassPhase.Done) 0.55f else 1f)
    val mod = if (onClick != null) base.clickable { onClick() } else base
    Surface(color = if (highlight) Cyan.copy(alpha = 0.14f) else SpaceCard,
        shape = RoundedCornerShape(14.dp), modifier = mod,
        border = if (highlight) androidx.compose.foundation.BorderStroke(1.5.dp, Cyan) else null) {
        Column {
            // **Three lines, not five.** The countdown shares the first line,
            // the bell stands alone on the right: the pass line gets the whole
            // width and no longer wraps, so six passes fit where four did.
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (selectable) {
                    Checkbox(checked = checked, onCheckedChange = { onCheck?.invoke() },
                        colors = CheckboxDefaults.colors(checkedColor = Cyan))
                    Spacer(Modifier.width(4.dp))
                }
                if (p.track.size > 1) {
                    MiniPolarPlot(p.track, elColor, Modifier.size(48.dp))
                } else {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(elColor.copy(alpha = 0.15f)),
                        Alignment.Center) {
                        Text("${p.maxElevationDeg.toInt()}°", color = elColor, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = if (showBell && onBell != null) 0.dp else 10.dp)) {
                    val date = "${day.format(Date(p.aosEpochMs))} · ${hm.format(Date(p.aosEpochMs))} ${tzTag(useUtc)}"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Name (or date) and agenda badge take what the
                        // countdown leaves; only one weight in the row, or
                        // Compose splits the width in halves.
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            if (showSat) {
                                Box(Modifier.size(8.dp).clip(CircleShape)
                                        .background(couleurStatut(amsatStatus)))
                                Spacer(Modifier.width(6.dp))
                                Text(p.satName, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false))
                            } else {
                                Text(date, color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false))
                            }
                            // Agenda badge on the first line: that is what the
                            // eye scans while scrolling.
                            if (agendaTitle != null) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.EventNote, contentDescription = t("agenda_on_pass_cd"),
                                    tint = Amber, modifier = Modifier.size(15.dp))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        when (phase) {
                            is PassPhase.Upcoming -> Text(fmtCountdown(phase.inMs), color = Amber,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            is PassPhase.Active -> Text(t("in_pass"), color = Cyan,
                                fontWeight = FontWeight.Black, fontSize = 12.sp)
                            // A finished pass stays visible (past-pass window) but is
                            // clearly tagged so it is never mistaken for an upcoming one.
                            else -> Surface(color = TextLo.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(6.dp)) {
                                Text(t("pass_done"), color = TextLo, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    val ligne = tf("pass_card_line", hm.format(Date(p.losEpochMs)), p.durationSec,
                        p.maxElevationDeg.toInt(), p.aosAzimuthDeg.toInt(), p.losAzimuthDeg.toInt())
                    // Second line: the date when the name took the first one,
                    // else the pass line. The eye (visible pass) closes it.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showSat) Text(date, color = TextLo, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        else Text(ligne, color = TextLo, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        if (p.visualPass) Text("👁", fontSize = 14.sp)
                    }
                    if (showSat) Text(ligne, color = TextLo, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Event title under the pass line: the badge says there is
                    // something, the title says what.
                    if (agendaTitle != null) {
                        Spacer(Modifier.height(4.dp))
                        Surface(color = Amber.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.EventNote, contentDescription = null,
                                    tint = Amber, modifier = Modifier.size(11.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(agendaTitle, color = Amber, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                    if (skedCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Surface(color = Amber.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                            Text(tf("skeds_announced_count", skedCount),
                                color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                if (showBell && onBell != null) {
                    IconButton(onClick = onBell) {
                        Icon(
                            if (bellOn) Icons.Default.Notifications else Icons.Outlined.NotificationsNone,
                            contentDescription = if (bellOn) t("unnotify_this_pass") else t("notify_this_pass"),
                            tint = if (bellOn) Amber else TextLo,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (phase is PassPhase.Active) {
                LinearProgressIndicator(
                    progress = { phase.progress },
                    color = Cyan, trackColor = Color(0xFF20293A),
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }
        }
    }
}

// ---------- detail ----------

/**
 * Status badge colour, defined **once** (list, hero card and detail page used
 * to each copy the same `when`).
 *
 * **Grey is an answer, not a default.** Drawing nothing without a recent
 * report made missing data look like a missing feature; grey says "nobody
 * reported it".
 */
@Composable
fun couleurStatut(st: fr.f4ioz.satcombo.data.AmsatStatus?): Color = when (st) {
    fr.f4ioz.satcombo.data.AmsatStatus.ACTIVE -> Color(0xFF49D17F)
    fr.f4ioz.satcombo.data.AmsatStatus.BEACON -> Amber
    fr.f4ioz.satcombo.data.AmsatStatus.NOT_HEARD -> Magenta
    fr.f4ioz.satcombo.data.AmsatStatus.CONFLICT -> Color(0xFFFE6100)
    else -> TextLo.copy(alpha = 0.45f)
}

/**
 * Before the PDF of the selection: how many coming passes for the selected
 * satellite(s) — or only the passes ticked. The last answer is offered again.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuestionPassagesPdf(satellites: List<String>, dernier: Int, onAnnule: () -> Unit, onChoix: (Int) -> Unit) {
    var n by remember { mutableStateOf(dernier) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onAnnule,
        title = { Text(t("pdf_passages_titre")) },
        text = {
            Column {
                Text(tf("pdf_passages_question", satellites.joinToString(", ")), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 2, 3, 5, 10, 20).forEach { k ->
                        androidx.compose.material3.FilterChip(selected = n == k, onClick = { n = k },
                            label = { Text(if (k == 0) t("pdf_passages_coches") else "$k", fontSize = 12.sp) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                    }
                }
                Text(if (n == 0) t("pdf_passages_coches_desc") else tf("pdf_passages_n_desc", n),
                    color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onChoix(n) }) { Text(t("pdf_passages_creer"), color = Cyan) } },
        dismissButton = { TextButton(onClick = onAnnule) { Text(t("cancel"), color = TextLo) } })
}
