/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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

// Settings screens & dialogs — extracted from SatComboApp.kt for maintainability.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(ui: UiState, vm: MainViewModel) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PillTabs(
            tabs = listOf(t("settings"), "${t("satellites_tab")} (${ui.favorites.size})"),
            selected = tab, onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
        if (tab == 0) SettingsGeneral(ui, vm) else SettingsSats(ui, vm)
    }
}

/** Météo & Marées-style pill tabs: rounded container, accent pill for active. */
@Composable
private fun PillTabs(
    tabs: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = SpaceSurface, shape = RoundedCornerShape(12.dp),
        shadowElevation = if (isDarkTheme()) 0.dp else 1.dp, modifier = modifier) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEachIndexed { i, label ->
                val active = i == selected
                Surface(
                    color = if (active) Cyan else Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.weight(1f).clickable { onSelect(i) }
                ) {
                    Text(label, textAlign = TextAlign.Center,
                        color = if (active) (if (isDarkTheme()) Color(0xFF00201D) else Color.White) else TextLo,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsGeneral(ui: UiState, vm: MainViewModel) {
    // The log tab state lives above the list: `LazyColumn` items are destroyed
    // and recreated while scrolling, so state held inside one would reset.
    var ongletCarnet by remember { mutableStateOf(0) }

    // **Each section opens at the top.** The same list is reused across
    // sections, so it kept the previous scroll position and you landed mid-log
    // without seeing its tabs.
    val defilement = rememberLazyListState()
    LaunchedEffect(ui.settingsSection) { defilement.scrollToItem(0) }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = defilement,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- Apparence ---
        val sec = ui.settingsSection
        if (sec == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MenuGroupLabel("📡 " + t("grp_station"))
                    SettingsMenuRow(Icons.Default.MyLocation, t("menu_qth")) { vm.setSettingsSection("qth") }
                    SettingsMenuRow(Icons.Default.Schedule, t("menu_time")) { vm.setSettingsSection("time") }
                    SettingsMenuRow(Icons.Default.CloudDownload, t("menu_sources")) { vm.setSettingsSection("sources") }

                    MenuGroupLabel("🧭 " + t("grp_aiming"))
                    SettingsMenuRow(Icons.Default.Explore, t("menu_aim")) { vm.setSettingsSection("aim") }
                    // The rotor is a pointing setting, so it sits here, but it
                    // opens its own screen: link, end stops, park, pre-pointing.
                    if (fr.f4ioz.satcombo.data.Extensions.ROTOR in ui.extensions) {
                        SettingsMenuRow(Icons.Default.Radar, t("menu_rotor"), page = true) { vm.openRotor() }
                    }
                    SettingsMenuRow(Icons.Default.Palette, t("menu_colors")) { vm.setSettingsSection("colors") }
                    SettingsMenuRow(Icons.Default.Language, t("menu_look")) { vm.setSettingsSection("look") }

                    MenuGroupLabel("🎙 " + t("grp_traffic"))
                    SettingsMenuRow(Icons.Default.Cable, t("menu_cat")) { vm.setSettingsSection("cat") }
                    SettingsMenuRow(Icons.Default.SwapVert, t("menu_conv")) { vm.setSettingsSection("conv") }
                    // QO-100 lives here, not under pointing: the dish is aimed
                    // once, while frequency, radio and converters change per
                    // session. Its neighbours are the settings it depends on.
                    if (fr.f4ioz.satcombo.data.Extensions.QO100 in ui.extensions) {
                        SettingsMenuRow(Icons.Default.SatelliteAlt, t("menu_qo100"), page = true) { vm.openQo100() }
                    }
                    SettingsMenuRow(Icons.Default.Mic, t("menu_recordings")) { vm.setSettingsSection("recordings") }
                    SettingsMenuRow(Icons.Default.Tune, t("menu_accord")) { vm.setSettingsSection("accord") }
                    SettingsMenuRow(Icons.Default.EditNote, t("menu_express")) { vm.setSettingsSection("express") }
                    SettingsMenuRow(Icons.Default.Dialpad, t("menu_macro")) { vm.setSettingsSection("macro") }
                    SettingsMenuRow(Icons.Default.Cast, t("menu_partage")) { vm.setSettingsSection("partage") }
                    SettingsMenuRow(Icons.Default.GpsFixed, t("menu_gps")) { vm.setSettingsSection("gps") }
                    if (fr.f4ioz.satcombo.data.Extensions.SSTV in ui.extensions) {
                        SettingsMenuRow(Icons.Default.LiveTv, t("menu_mire")) { vm.setSettingsSection("mire") }
                    }
                    if (fr.f4ioz.satcombo.data.Extensions.SONDE in ui.extensions) {
                        SettingsMenuRow(Icons.Default.Air, t("menu_sondemire")) { vm.setSettingsSection("sondemire") }
                    }
                    SettingsMenuRow(Icons.Default.MenuBook, t("menu_log")) { vm.setSettingsSection("log") }
                    SettingsMenuRow(Icons.Default.Notifications, t("menu_notif")) { vm.setSettingsSection("notif") }

                    MenuGroupLabel("🤝 " + t("grp_activities"))
                    // Mutual sked and Agenda hold no setting: they are pages,
                    // reached from the ⋮ menu like every other page.
                    SettingsMenuRow(Icons.Default.Campaign, t("menu_skeds")) { vm.setSettingsSection("skeds") }
                    SettingsMenuRow(Icons.Default.Park, t("menu_pota")) { vm.setSettingsSection("pota") }

                    MenuGroupLabel("🗂 " + t("grp_data"))
                    SettingsMenuRow(Icons.Default.PictureAsPdf, t("menu_pdf")) { vm.setSettingsSection("pdf") }
                    SettingsMenuRow(Icons.Default.Backup, t("menu_backup")) { vm.setSettingsSection("backup") }
                    SettingsMenuRow(Icons.Default.HelpOutline, t("menu_docs")) { vm.setSettingsSection("docs") }
                    SettingsMenuRow(Icons.Default.Info, t("menu_about")) { vm.setSettingsSection("about") }
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { vm.closeSettingsSection() }
                        .padding(vertical = 6.dp, horizontal = 2.dp)) {
                    Icon(Icons.Default.ArrowBack, null, tint = Cyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(settingsMenuTitle(sec), color = Cyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
        if (sec == "docs") { item { DocsContent() } }
        if (sec == "accord") {
            if (fr.f4ioz.satcombo.data.Extensions.SDR in ui.extensions) {
                item { OuvrirPage(t("sdr_title")) { vm.openSdr() } }
            }
            item { AccordFinCard(ui, vm) }
        }
        if (sec == "macro") { item { ClavierMacroCarte(ui, vm) } }
        // **One menu for both ends.** Broadcasting and listening are the same
        // mechanism; two entries forced you to remember which side you were on.
        if (sec == "partage") {
            item {
                var onglet by remember { mutableStateOf(0) }
                Column {
                    TabRow(selectedTabIndex = onglet, containerColor = SpaceBg,
                        contentColor = Cyan) {
                        Tab(selected = onglet == 0, onClick = { onglet = 0 },
                            text = { Text(t("partage_diffuser"), fontSize = 13.sp) })
                        Tab(selected = onglet == 1, onClick = { onglet = 1 },
                            text = { Text(t("partage_ecouter"), fontSize = 13.sp) })
                    }
                    Spacer(Modifier.height(12.dp))
                    if (onglet == 0) DemoCarte(vm) else EcouteCarte(vm)
                }
            }
        }
        if (sec == "express") { item { CarnetExpressCard(ui, vm) } }
        if (sec == "gps") { item { GpsEtatCard(ui) } }
        if (sec == "look") {
        item { SectionHeader(t("appearance")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("theme"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("theme_desc"),
                        color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                    PillTabs(
                        tabs = listOf(t("dark"), t("light"), t("sun")),
                        selected = ui.themeIndex,
                        onSelect = { vm.setThemeIndex(it) }
                    )
                    Text(t("sun_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(t("language_label"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("language_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("auto" to t("lang_auto"), "fr" to t("lang_fr"), "en" to t("lang_en")).forEach { (id, label) ->
                            FilterChip(selected = ui.language == id, onClick = { vm.setLanguage(id) },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                        }
                    }
                    // Units are chosen separately from the UI language: a UK ham
                    // reads English and counts in miles, a French pilot reads
                    // French and counts in nautical miles and feet.
                    Spacer(Modifier.height(14.dp))
                    Text(t("set_units"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("set_units_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(
                            fr.f4ioz.satcombo.data.Units.METRIC to t("units_metric"),
                            fr.f4ioz.satcombo.data.Units.IMPERIAL to t("units_imperial"),
                            fr.f4ioz.satcombo.data.Units.NAUTICAL to t("units_nautical")
                        ).forEach { (id, label) ->
                            FilterChip(selected = ui.units == id, onClick = { vm.setUnits(id) },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                        }
                    }
                }
            }
        }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.toggleable(value = ui.uniformUi, role = Role.Switch,
                            onValueChange = { vm.setUniformUi(it) }), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("ui_uniform"), color = TextHi, fontWeight = FontWeight.Bold)
                            Text(t("ui_uniform_desc"), color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = ui.uniformUi, onCheckedChange = null)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(t("ui_size"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("ui_size_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                    PillTabs(
                        tabs = listOf(t("ui_size_compact"), t("ui_size_normal"), t("ui_size_large")),
                        selected = ui.uiScaleStep + 1,
                        onSelect = { vm.setUiScaleStep(it - 1) }
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.toggleable(value = ui.uiFollowSystemFont, role = Role.Switch,
                            onValueChange = { vm.setUiFollowSystemFont(it) }), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("ui_system_font"), color = TextHi, fontWeight = FontWeight.Bold)
                            Text(t("ui_system_font_desc"), color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = ui.uiFollowSystemFont,
                            onCheckedChange = null)
                    }
                }
            }
        }

        // --- QTH ---
        }
        if (sec == "qth") {
        item { SectionHeader(t("station_qth")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.selectable(selected = ui.locationMode == LocationMode.AUTO,
                            role = Role.RadioButton, onClick = { vm.setLocationMode(LocationMode.AUTO) })) {
                        RadioButton(selected = ui.locationMode == LocationMode.AUTO,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Cyan))
                        Text(t("auto_gps"), color = TextHi)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.selectable(selected = ui.locationMode == LocationMode.MANUAL,
                            role = Role.RadioButton, onClick = { vm.setLocationMode(LocationMode.MANUAL) })) {
                        RadioButton(selected = ui.locationMode == LocationMode.MANUAL,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Cyan))
                        Text(t("manual_maidenhead"), color = TextHi)
                    }
                    if (ui.locationMode == LocationMode.MANUAL) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = ui.manualLocator, onValueChange = vm::setManualLocator,
                                label = { Text("Locator", color = TextLo) },
                                isError = ui.locatorError != null,
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                                    focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(onClick = vm::applyManualLocator,
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                                Text("OK", color = Color(0xFF00201D))
                            }
                        }
                        ui.locatorError?.let {
                            Text(it, color = Magenta, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    var showMap by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { showMap = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(t("pick_locator_map"), color = Cyan)
                    }
                    if (showMap) {
                        LocatorMapDialog(
                            // Squares come from LoTW, loaded when the
                            // locator page opens.
                            carresContactes = if (ui.carnet.peindre)
                                ui.carnet.lotwTravailles + ui.carnet.lotwConfirmes
                            else emptySet(),
                            carresActives = if (ui.carnet.peindre)
                                ui.carnet.lotwActives else emptySet(),
                            initialLocator = ui.observer?.let {
                                fr.f4ioz.satcombo.location.Maidenhead.fromLatLon(it.latDeg, it.lonDeg)
                            } ?: ui.manualLocator,
                            mapStyle = ui.mapStyle,
                            // Open on the exact spot already in use, so the pin
                            // starts where the station really is.
                            initialPoint = ui.manualLat?.let { la ->
                                ui.manualLon?.let { lo -> la to lo }
                            } ?: ui.observer?.let { it.latDeg to it.lonDeg },
                            showPota = ui.potaEnabled,
                            potaLoader = { a, b, c, d -> vm.potaInBounds(a, b, c, d) },
                            onPick = { loc, la, lo -> vm.pickLocator(loc, la, lo); showMap = false },
                            onDismiss = { showMap = false }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(t("map_background"), color = TextLo, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MapProviders.ALL.forEach { mp ->
                            FilterChip(
                                selected = ui.mapStyle == mp.id,
                                onClick = { vm.setMapStyle(mp.id) },
                                label = { Text(mp.label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.20f),
                                    selectedLabelColor = Cyan)
                            )
                        }
                    }
                    Text(t("offline_desc"),
                        color = TextLo.copy(alpha = 0.7f), fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp))
                    ui.observer?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(tf("current_position", "${it.name} (%.4f, %.4f)".format(it.latDeg, it.lonDeg)),
                            color = TextLo, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).toggleable(value = ui.locatorDetails, role = Role.Switch,
                        onValueChange = vm::setLocatorDetails), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("locator_details"), color = TextHi)
                        Text(t("locator_details_desc"), color = TextLo, fontSize = 11.sp)
                    }
                    Switch(checked = ui.locatorDetails, onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                }
            }
        }
        // --- nearby grid squares: "JN18cx / JN18cw" when standing on a border ---
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("near_grid_title"), color = TextHi)
                    Text(t("near_grid_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(0, 50, 100, 250, 500, 1000).forEach { m ->
                            FilterChip(
                                selected = ui.nearGridMeters == m,
                                onClick = { vm.setNearGridMeters(m) },
                                label = { Text(if (m == 0) t("off") else "$m m", fontSize = 12.sp) }
                            )
                        }
                    }
                    if (ui.nearGridMeters > 0) {
                        Text(vm.myLocatorFull(), color = Cyan, fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        // --- callsign: used by the QRV photo, the activation sheets and ADIF ---
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("callsign_title"), color = TextHi)
                    Text(t("callsign_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                    // NO remember(ui.callsign) here: each keystroke changed the
                    // key and rebuilt the field state, and a keystroke landing
                    // during that round trip was lost ("F4IOZ" came out "F4I").
                    var cs by remember { mutableStateOf(ui.callsign) }
                    OutlinedTextField(
                        value = cs,
                        onValueChange = { cs = it.uppercase(); vm.setCallsign(cs) },
                        label = { Text(t("callsign_label"), fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        // --- Extensions: beta features, unlocked by keyword ---
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("ext_title"), color = TextHi)
                    Spacer(Modifier.height(8.dp))
                    // Same trap as the callsign: no remember(ui.…) here, or fast
                    // typing loses keystrokes during recomposition.
                    var ext by remember { mutableStateOf(ui.extensionsCode) }
                    OutlinedTextField(
                        value = ext,
                        onValueChange = { ext = it; vm.setExtensionsCode(ext) },
                        label = { Text(t("ext_label"), fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        // --- QRV photo overlay ---
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text(t("photo_options"), color = TextHi,
                        modifier = Modifier.padding(start = 16.dp))
                    Text(t("photo_options_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                    listOf(
                        Triple("call", t("photo_opt_call"), ui.photoShowCallsign),
                        Triple("date", t("photo_opt_date"), ui.photoShowDate),
                        Triple("grids", t("photo_opt_grids"), ui.photoShowGrids),
                        Triple("coords", t("photo_opt_coords"), ui.photoShowCoords),
                        Triple("sat", t("photo_opt_sat"), ui.photoShowSat),
                        Triple("pass", t("photo_opt_pass"), ui.photoShowPass)
                    ).forEach { (key, label, on) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).toggleable(value = on, role = Role.Switch,
                                onValueChange = { vm.setPhotoOption(key, it) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Switch(checked = on, onCheckedChange = null,
                                colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                        }
                    }
                    OutlinedButton(onClick = { vm.openPhoto() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(t("photo_open"), color = Cyan)
                    }
                }
            }
        }

        // --- TLE sources ---
        // --- time zone ---
        }
        if (sec == "time") {
        item { SectionHeader(t("time_display")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).toggleable(value = ui.useUtc, role = Role.Switch,
                        onValueChange = vm::setUseUtc), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("utc_time"), color = TextHi)
                        Text(if (ui.useUtc) t("utc_all")
                             else t("local_time"),
                            color = TextLo, fontSize = 11.sp)
                    }
                    Switch(checked = ui.useUtc, onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                }
            }
        }

        // --- minimum elevation ---
        item { SectionHeader(t("min_elev_section")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("threshold"), color = TextHi, modifier = Modifier.weight(1f))
                        Text("${ui.minElevDeg}°", color = Cyan, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                    }
                    Slider(
                        value = ui.minElevDeg.toFloat(),
                        onValueChange = { vm.setMinElev(it.toInt()) },
                        valueRange = 0f..30f, steps = 29,
                        colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan)
                    )
                    Text(when {
                        ui.minElevDeg == 0 -> t("elev_zero_hint")
                        ui.minElevDeg < 5 -> t("elev_low_hint")
                        ui.minElevDeg <= 10 -> t("elev_std_hint")
                        else -> t("elev_high_hint")
                    }, color = TextLo, fontSize = 11.sp)
                }
            }
        }

        // --- past passes ---
        item { SectionHeader(t("past_passes")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val opts = listOf(0, 3, 6, 12)
                    PillTabs(
                        tabs = opts.map { if (it == 0) t("past_none") else tf("past_h", it) },
                        selected = opts.indexOf(ui.pastPassHours).coerceAtLeast(0),
                        onSelect = { vm.setPastPassHours(opts[it]) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(t("past_passes_desc"), color = TextLo, fontSize = 11.sp)
                }
            }
        }

        }
        if (sec == "sources") {
        item { SectionHeader(t("sources_gp")) }

        // Satellites rejected from the last bulletin. Shows nothing in the usual
        // case, so it still gets read when it does speak.
        item {
            val ecartes = fr.f4ioz.satcombo.data.TleRepository.ecartes
            if (ecartes.isNotEmpty()) {
                Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t("gp_ecartes"), color = Amber, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(t("gp_ecartes_desc"), color = TextLo, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                        ecartes.take(12).forEach {
                            Text("• " + it, color = TextHi, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Sources.ALL.forEach { src ->
                        val on = src.id in ui.enabledSources
                        FilterChip(
                            selected = on, onClick = { vm.toggleSource(src.id) },
                            label = { Text(src.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.20f),
                                selectedLabelColor = Cyan
                            )
                        )
                    }
                }
            }
        }

        // --- orbital-elements cache lifetime ---
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("tle_cache_title"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(t("tle_cache_desc"),
                        color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 6, 12, 24, 48).forEach { h ->
                            FilterChip(
                                selected = ui.tleCacheHours == h,
                                onClick = { vm.setTleCacheHours(h) },
                                label = { Text(if (h == 0) t("tle_cache_always") else "$h h", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.20f),
                                    selectedLabelColor = Cyan
                                )
                            )
                        }
                    }
                }
            }
        }

        // --- notifications ---
        // **The status source comes last.** It picks where a badge comes from,
        // not where orbits come from, so it goes after the orbital elements and
        // their cache rather than between them and their header.
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("status_source"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(t("status_source_desc"),
                        color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("BOTH" to "Les deux", "AMSAT" to t("amsat_only"), "SATNOGS" to t("satnogs_only")).forEach { (id, label) ->
                            FilterChip(selected = ui.statusSource == id, onClick = { vm.setStatusSource(id) },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                        }
                    }
                }
            }
        }
        }

        if (sec == "aim") {
        item { SectionHeader(t("aiming")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("compass_style"), color = TextHi, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = ui.compassStyle != "NEEDLE",
                            onClick = { vm.setCompassStyle("CLASSIC") },
                            label = { Text(t("style_classic"), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                        FilterChip(selected = ui.compassStyle == "NEEDLE",
                            onClick = { vm.setCompassStyle("NEEDLE") },
                            label = { Text(t("style_needle"), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                    }
                    Text(t("style_needle_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("aim_mode_title"), color = TextHi)
                    Text(t("aim_mode_desc"),
                        color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = ui.aimMode == "EDGE", onClick = { vm.setAimMode("EDGE") },
                            label = { Text(t("aim_slab")) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                        FilterChip(selected = ui.aimMode == "BACK", onClick = { vm.setAimMode("BACK") },
                            label = { Text(t("aim_back")) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp).toggleable(value = ui.showAimModeChips, role = Role.Switch,
                            onValueChange = vm::setShowAimModeChips)) {
                        Column(Modifier.weight(1f)) {
                            Text(t("aim_mode_chips"), color = TextHi)
                            Text(t("aim_chips_desc"),
                                color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = ui.showAimModeChips, onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp).toggleable(value = ui.compassHeadUp, role = Role.Switch,
                            onValueChange = vm::setCompassHeadUp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t("compass_headup"), color = TextHi)
                            Text(t("compass_headup_desc"),
                                color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = ui.compassHeadUp, onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                    }
                }
            }
        }
        // The status source moved to data sources: it picks where a satellite's
        // green/red badge comes from, which has nothing to do with pointing.
        item { BoussoleCarte(vm, ui) }

        }
        if (sec == "recordings") {
            item { SectionHeader(t("recordings_title")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.toggleable(value = ui.recorderEnabled, role = Role.Switch,
                            onValueChange = vm::setRecorderEnabled), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("recorder_enable"), color = TextHi)
                            Text(t("recorder_enable_desc"), color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = ui.recorderEnabled, onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                    }
                    if (ui.recorderEnabled) {
                        Spacer(Modifier.height(10.dp))
                        // Satellite, date and locator spoken at the start of
                        // each file: a recording found later says what it is.
                        var annonce by remember { mutableStateOf(vm.annonceVocale()) }
                        SettingSwitch(t("annonce_vocale"), t("annonce_vocale_desc"), annonce) {
                            annonce = it; vm.setAnnonceVocale(it)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(t("rec_source"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = ui.recorderSource == "MIC",
                                onClick = { vm.setRecorderSource("MIC") },
                                label = { Text(t("rec_source_mic"), fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                    selectedLabelColor = Cyan))
                            FilterChip(selected = ui.recorderSource == "BT",
                                onClick = { vm.setRecorderSource("BT") },
                                label = { Text(t("rec_source_bt"), fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                    selectedLabelColor = Cyan))
                            FilterChip(selected = ui.recorderSource == "USB",
                                onClick = { vm.setRecorderSource("USB") },
                                label = { Text(t("rec_source_usb"), fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                    selectedLabelColor = Cyan))
                        }
                        Text(when (ui.recorderSource) {
                                "BT" -> t("rec_source_bt_desc")
                                "USB" -> t("rec_source_usb_desc")
                                else -> t("rec_source_mic_desc")
                             },
                            color = TextLo, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp))
                        // What the phone actually sees on its USB port, checked
                        // here and now: a cable problem must be found before the
                        // pass, not discovered in the recording afterwards.
                        if (ui.recorderSource == "USB") {
                            val ctxUsb = LocalContext.current
                            val usb = remember(ui.recorderSource, ui.nowMs / 5000) {
                                fr.f4ioz.satcombo.audio.RecorderService.usbInputs(ctxUsb)
                            }
                            Text(
                                if (usb.isEmpty()) t("usb_audio_none")
                                else tf("usb_audio_detected", usb.joinToString(", ")),
                                color = if (usb.isEmpty()) Amber else Cyan,
                                fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        val ctxUnp = LocalContext.current
                        val unpOk = remember {
                            fr.f4ioz.satcombo.audio.RecorderService.unprocessedSupported(ctxUnp)
                        }
                        if (ui.recorderSource != "BT") {
                            Spacer(Modifier.height(10.dp))
                            Row(modifier = Modifier.toggleable(value = ui.recorderUnprocessed && unpOk, enabled = unpOk, role = Role.Switch,
                                    onValueChange = vm::setRecorderUnprocessed), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("rec_unprocessed"), color = TextHi, fontSize = 13.sp)
                                    Text(if (unpOk) t("rec_unprocessed_desc")
                                         else t("rec_unprocessed_unavailable"),
                                        color = TextLo, fontSize = 11.sp)
                                }
                                Switch(checked = ui.recorderUnprocessed && unpOk,
                                    enabled = unpOk,
                                    onCheckedChange = null,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                            }
                        }
                        // Monitoring what gets recorded: spectrum by eye, audio
                        // by ear. Both tap the same capture as the decoders and
                        // can be switched back on mid-pass.
                        Spacer(Modifier.height(10.dp))
                        Text(t("monitor_title"), color = TextHi,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 6.dp).toggleable(value = ui.monitorSpectre, role = Role.Switch,
                                onValueChange = vm::setMonitorSpectre)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("monitor_spectre"), color = TextHi, fontSize = 13.sp)
                                Text(t("monitor_spectre_desc"), color = TextLo, fontSize = 11.sp)
                            }
                            Switch(checked = ui.monitorSpectre,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp).toggleable(value = ui.monitorSpeaker && ui.recorderSource != "MIC", enabled = ui.recorderSource != "MIC", role = Role.Switch,
                                onValueChange = vm::setMonitorSpeaker)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("monitor_speaker"), color = TextHi, fontSize = 13.sp)
                                Text(if (ui.recorderSource == "MIC") t("monitor_speaker_mic")
                                     else t("monitor_speaker_desc"),
                                    color = if (ui.recorderSource == "MIC") Amber else TextLo,
                                    fontSize = 11.sp)
                            }
                            Switch(checked = ui.monitorSpeaker && ui.recorderSource != "MIC",
                                enabled = ui.recorderSource != "MIC",
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                        }
                        // SSTV rides on the same capture: no extra recording,
                        // the engine just watches the samples going past.
                        // Beta: hidden without the extension key.
                        if (fr.f4ioz.satcombo.data.Extensions.SSTV in ui.extensions) {
                            Spacer(Modifier.height(10.dp))
                            Row(modifier = Modifier.toggleable(value = ui.sstvEnabled, role = Role.Switch,
                                    onValueChange = vm::setSstvEnabled), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("sstv_enable"), color = TextHi, fontSize = 13.sp)
                                    Text(t("sstv_enable_desc"), color = TextLo, fontSize = 11.sp)
                                }
                                Switch(checked = ui.sstvEnabled,
                                    onCheckedChange = null,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                            }
                            if (ui.sstvEnabled) {
                                Spacer(Modifier.height(8.dp))
                                OuvrirPage(t("sstv_title")) { vm.openSstv() }
                            }
                        }
                        // APT uses the same capture but has no header to wait
                        // for: the decoder runs for the whole recording. So it
                        // is armed by hand before a NOAA pass, off by default.
                        if (fr.f4ioz.satcombo.data.Extensions.APT in ui.extensions) {
                            Spacer(Modifier.height(10.dp))
                            Row(modifier = Modifier.toggleable(value = ui.aptEnabled, role = Role.Switch,
                                    onValueChange = vm::setAptEnabled), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("apt_setting"), color = TextHi, fontSize = 13.sp)
                                    Text(t("apt_setting_hint"), color = TextLo, fontSize = 11.sp)
                                }
                                Switch(checked = ui.aptEnabled,
                                    onCheckedChange = null,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                            }
                            Spacer(Modifier.height(8.dp))
                            OuvrirPage(t("apt_title")) { vm.openApt() }
                        }
                    }
                }
            }
        }
            item { RecordingsSection(ui, vm) }
        }
        if (sec == "colors") {
            item { SectionHeader(t("compass_colors")) }
            item {
                Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t("color_trace"), color = TextHi, fontWeight = FontWeight.Bold)
                        Text(t("color_trace_desc"), color = TextLo, fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 8.dp))
                        ColorSwatches(ui.compassTraceColor) { vm.setCompassColor("trace", it) }
                        Spacer(Modifier.height(12.dp))
                        Text(t("trace_width"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                0.7f to t("width_thin"), 1f to t("width_normal"),
                                1.6f to t("width_thick"), 2.4f to t("width_xthick")
                            ).forEach { (w, label) ->
                                FilterChip(
                                    selected = kotlin.math.abs(ui.compassTraceWidth - w) < 0.05f,
                                    onClick = { vm.setCompassTraceWidth(w) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                        selectedLabelColor = Cyan))
                            }
                        }
                        Text(t("trace_width_desc"), color = TextLo, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item {
                ColorGroupCard(t("color_needle"), listOf(
                    Triple(t("color_far"), ui.needleFarColor, "needle_far"),
                    Triple(t("color_near"), ui.needleNearColor, "needle_near"),
                    Triple(t("color_close"), ui.needleCloseColor, "needle_close")), vm)
            }
            item {
                ColorGroupCard(t("color_bubble"), listOf(
                    Triple(t("color_far"), ui.bubbleFarColor, "bubble_far"),
                    Triple(t("color_near"), ui.bubbleNearColor, "bubble_near"),
                    Triple(t("color_close"), ui.bubbleCloseColor, "bubble_close")), vm)
            }
            item {
                ColorGroupCard(t("color_ring_az"), listOf(
                    Triple(t("color_near"), ui.ringAzNearColor, "ring_az_near"),
                    Triple(t("color_close"), ui.ringAzCloseColor, "ring_az_close")), vm)
            }
            item {
                ColorGroupCard(t("color_ring_el"), listOf(
                    Triple(t("color_near"), ui.ringElNearColor, "ring_el_near"),
                    Triple(t("color_close"), ui.ringElCloseColor, "ring_el_close")), vm)
            }
            item {
                OutlinedButton(onClick = { vm.resetCompassColors() }, modifier = Modifier.fillMaxWidth()) {
                    Text(t("color_reset"), color = Cyan)
                }
            }
        }
        if (sec == "notif") {
        item { SectionHeader(t("notifications")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.toggleable(value = ui.notifyEnabled, role = Role.Switch,
                            onValueChange = vm::setNotifyEnabled), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("notify_passes"),
                            color = TextHi, modifier = Modifier.weight(1f))
                        Switch(checked = ui.notifyEnabled, onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                    }
                    if (ui.notifyEnabled) {
                        Spacer(Modifier.height(4.dp))
                        Text(t("lead_before_aos"), color = TextLo, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(5, 10, 15).forEach { m ->
                                FilterChip(
                                    selected = ui.notifyLeadMin == m,
                                    onClick = { vm.setNotifyLead(m) },
                                    label = { Text("$m min", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cyan.copy(alpha = 0.20f),
                                        selectedLabelColor = Cyan)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(t("which_passes"), color = TextLo, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().selectable(selected = ui.notifyMode == "FAV",
                                    role = Role.RadioButton, onClick = { vm.setNotifyMode("FAV") })) {
                                RadioButton(selected = ui.notifyMode == "FAV",
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Cyan))
                                Text(t("all_followed"), color = TextHi, fontSize = 14.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().selectable(selected = ui.notifyMode == "TARGET",
                                    role = Role.RadioButton, onClick = { vm.setNotifyMode("TARGET") })) {
                                RadioButton(selected = ui.notifyMode == "TARGET",
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Cyan))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(t("targeted_passes"), color = TextHi, fontSize = 14.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Default.Notifications, null, tint = Amber,
                                            modifier = Modifier.size(15.dp))
                                    }
                                    Text(t("targeted_desc"),
                                        color = TextLo, fontSize = 11.sp)
                                }
                            }
                        }
                        if (ui.notifyMode == "TARGET") {
                            val n = ui.notifiedPassKeys.size
                            Text(if (n == 0) t("no_targeted") else tf("n_targeted_passes", n),
                                color = if (n == 0) Amber else TextLo, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }

        // --- hams.at skeds ---
        }
        if (sec == "skeds") {
        item { OuvrirPage(t("sked_page_title")) { vm.openSked() } }
        item { SectionHeader(t("skeds_hamsat")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.toggleable(value = ui.skedsEnabled, role = Role.Switch,
                            onValueChange = vm::setSkedsEnabled), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("show_skeds"),
                            color = TextHi, modifier = Modifier.weight(1f))
                        Switch(checked = ui.skedsEnabled, onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = Amber))
                    }
                    if (ui.skedsEnabled) {
                        Spacer(Modifier.height(6.dp))
                        Text(if (ui.skeds.isEmpty()) t("no_skeds")
                             else tf("n_skeds_announced_desc", ui.skeds.size),
                            color = TextLo, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.toggleable(value = ui.skedsMutualOnly, role = Role.Switch,
                                onValueChange = vm::setSkedsMutualOnly), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t("skeds_workable_only"), color = TextHi, fontSize = 14.sp)
                                Text(t("skeds_workable_desc"),
                                    color = TextLo, fontSize = 11.sp)
                            }
                            Switch(checked = ui.skedsMutualOnly, onCheckedChange = null,
                                colors = SwitchDefaults.colors(checkedTrackColor = Amber))
                        }
                    }
                    Text(t("skeds_source"),
                        color = TextLo.copy(alpha = 0.7f), fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp))
                    if (ui.skedsEnabled) {
                        Spacer(Modifier.height(10.dp))
                        var tok by remember(ui.skedsToken) { mutableStateOf(ui.skedsToken) }
                        Text(t("hamsat_token_title"), color = TextHi, fontSize = 13.sp)
                        Text(t("hamsat_token_desc"),
                            color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = tok, onValueChange = { tok = it },
                                placeholder = { Text(t("paste_token"), color = TextLo) },
                                singleLine = true, shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                                    focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { vm.setSkedsToken(tok) },
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                                Text("OK", color = Color(0xFF00201D))
                            }
                        }
                        if (ui.skedsAuthed) {
                            Text(t("token_active"),
                                color = Color(0xFF49D17F), fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }

        // --- POTA ---
        }
        if (sec == "pota") {
        item { SectionHeader(t("pota_section")) }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.toggleable(value = ui.potaEnabled, role = Role.Switch,
                            onValueChange = vm::setPotaEnabled), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("pota_show"),
                            color = TextHi, modifier = Modifier.weight(1f))
                        Switch(checked = ui.potaEnabled, onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF7FE3A0)))
                    }
                    if (ui.potaEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("search_radius"), color = TextLo, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(3, 8, 15, 30).forEach { km ->
                                FilterChip(
                                    selected = ui.potaRadiusKm == km,
                                    onClick = { vm.setPotaRadius(km) },
                                    label = { Text("$km km", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF7FE3A0).copy(alpha = 0.20f),
                                        selectedLabelColor = Color(0xFF7FE3A0))
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (ui.nearbyPota.isEmpty()) tf("no_park_radius", ui.potaRadiusKm)
                             else tf("parks_qth_nearby", ui.nearbyPota.count { it.inside }, ui.nearbyPota.size),
                            color = TextLo, fontSize = 12.sp)
                        Text(t("pota_exact"),
                            color = TextLo.copy(alpha = 0.7f), fontSize = 10.sp,
                            modifier = Modifier.padding(top = 2.dp))

                        // The zone file is produced by PotaGrab and dropped
                        // here; it is not bundled in the app.
                        Spacer(Modifier.height(14.dp))
                        val ctx = LocalContext.current
                        var zonesN by remember { mutableStateOf(vm.compteZonesPota()) }
                        val choixZones = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                        ) { uri ->
                            if (uri != null) {
                                val texte = runCatching {
                                    ctx.contentResolver.openInputStream(uri)
                                        ?.bufferedReader()?.use { it.readText() }
                                }.getOrNull()
                                val n = if (texte == null) 0 else vm.importeZonesPota(texte)
                                zonesN = n
                                android.widget.Toast.makeText(ctx,
                                    if (n > 0) tf("pota_zones_loaded", n) else t("read_failed"),
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        Text(
                            if (zonesN > 0) tf("pota_zones_count", zonesN)
                            else t("pota_zones_builtin"),
                            color = TextLo, fontSize = 12.sp)
                        Row {
                            TextButton(onClick = {
                                choixZones.launch(arrayOf("application/json", "text/plain", "*/*"))
                            }) { Text(t("pota_zones_import"), color = Cyan, fontSize = 13.sp) }
                            if (zonesN > 0) {
                                TextButton(onClick = { vm.effaceZonesPota(); zonesN = 0 }) {
                                    Text(t("pota_zones_clear"), color = TextLo, fontSize = 13.sp)
                                }
                            }
                        }

                        // The welcome banner is set here with the rest of
                        // POTA: it is about parks, not the photo.
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.toggleable(value = ui.carte.bandeauAccueil, role = Role.Switch,
                                onValueChange = vm::setPotaBandeauAccueil), verticalAlignment = Alignment.CenterVertically) {
                            Text(t("pota_home_banner"), color = TextHi,
                                modifier = Modifier.weight(1f), fontSize = 14.sp)
                            Switch(checked = ui.carte.bandeauAccueil,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = Color(0xFF7FE3A0)))
                        }

                        // **Prepare before leaving.** The network is at home,
                        // rarely at the park: fetch the outlines in advance.
                        Spacer(Modifier.height(12.dp))
                        Text(t("pota_contours"), color = TextHi, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(t("pota_contours_aide"), color = TextLo, fontSize = 11.sp)
                        Row(Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(5.0, 15.0, 40.0).forEach { r ->
                                AssistChip(
                                    onClick = { vm.chargeContoursAutour(r) },
                                    label = { Text("${r.toInt()} km", fontSize = 11.sp) })
                            }
                        }
                        if (ui.carte.contoursEtat.isNotBlank()) {
                            Text(ui.carte.contoursEtat, color = Amber, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(t("pota_db"), color = TextLo, fontSize = 12.sp)
                        Text(ui.potaRegionLabel?.let { tf("pota_region_loaded", it, ui.potaCount) }
                                ?: t("pota_embedded"),
                            color = TextLo.copy(alpha = 0.8f), fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            fr.f4ioz.satcombo.data.PotaRegion.values().forEach { region ->
                                AssistChip(
                                    onClick = { vm.updatePotaRegion(region) },
                                    enabled = !ui.potaUpdating,
                                    label = { Text(region.label, fontSize = 11.sp) }
                                )
                            }
                        }
                        if (ui.potaUpdating) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp)) {
                                CircularProgressIndicator(Modifier.size(14.dp),
                                    color = Color(0xFF7FE3A0), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(t("pota_downloading"), color = TextLo, fontSize = 11.sp)
                            }
                        }
                        Text(t("pota_update_online"),
                            color = TextLo.copy(alpha = 0.7f), fontSize = 10.sp,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }

        }
        if (sec == "log") {
        // **Two tabs for two uses.** Wavelog/QRZ/LoTW credentials are entered
        // once; contact entry is used every pass. Mixed together, you scrolled
        // past hundreds of lines of accounts to reach the button you wanted.
        item {
            Column {
                TabRow(selectedTabIndex = ongletCarnet, containerColor = SpaceBg,
                    contentColor = Cyan) {
                    Tab(selected = ongletCarnet == 0, onClick = { ongletCarnet = 0 },
                        text = { Text(t("log_onglet_carnet"), fontSize = 13.sp) })
                    Tab(selected = ongletCarnet == 1, onClick = { ongletCarnet = 1 },
                        text = { Text(t("log_onglet_comptes"), fontSize = 13.sp) })
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        item { SectionHeader(t("log_section")) }

        if (ongletCarnet == 0) {
        item {
            val ctx = LocalContext.current
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tf("n_contacts", ui.log.size), color = TextHi)

                    // Add a contact afterwards (paper log typed in back home).
                    // The time is the contact's, never the entry time: it
                    // drives azimuth and elevation.
                    var ajout by remember { mutableStateOf(false) }
                    TextButton(onClick = { ajout = true }) {
                        Text("+ " + t("log_add"), color = Cyan, fontSize = 13.sp)
                    }
                    if (ajout) {
                        var quand by remember { mutableStateOf("") }
                        var call by remember { mutableStateOf("") }
                        var grid by remember { mutableStateOf("") }
                        var sat by remember {
                            mutableStateOf(ui.selected ?: ui.satellites.firstOrNull())
                        }
                        var listeSat by remember { mutableStateOf(false) }
                        AlertDialog(
                            onDismissRequest = { ajout = false },
                            title = { Text(t("log_add"), color = TextHi) },
                            confirmButton = {
                                TextButton(
                                    enabled = sat != null && call.isNotBlank() &&
                                        quand.length >= 12,
                                    onClick = {
                                        val ms = dateHeureVersMs(quand, ui.useUtc)
                                        val sa = sat
                                        if (ms != null && sa != null) {
                                            vm.ajouteContact(ms, sa, call, grid)
                                            ajout = false
                                        }
                                    }) { Text(t("save"), color = Cyan) }
                            },
                            dismissButton = {
                                TextButton(onClick = { ajout = false }) {
                                    Text(t("cancel"), color = TextLo)
                                }
                            },
                            text = {
                                Column {
                                    OutlinedTextField(quand, { quand = it },
                                        label = { Text(t("log_add_when"), fontSize = 11.sp) },
                                        placeholder = { Text("2026-08-22 21:14") },
                                        singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    TextButton(onClick = { listeSat = true }) {
                                        Text((sat?.name ?: "—") + " ▾", color = Cyan)
                                    }
                                    if (listeSat) {
                                        AlertDialog(
                                            onDismissRequest = { listeSat = false },
                                            confirmButton = {},
                                            text = {
                                                Column(Modifier.verticalScroll(
                                                    rememberScrollState())) {
                                                    ui.satellites.forEach { sa ->
                                                        Text(sa.name, color = TextHi,
                                                            fontSize = 15.sp,
                                                            modifier = Modifier.fillMaxWidth()
                                                                .clickable {
                                                                    sat = sa; listeSat = false
                                                                }
                                                                .padding(vertical = 8.dp))
                                                    }
                                                }
                                            })
                                    }
                                    OutlinedTextField(call, { call = it.uppercase() },
                                        label = { Text(t("callsign_label"), fontSize = 11.sp) },
                                        singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(grid, { grid = it.uppercase() },
                                        label = { Text(t("contact_locator_label"), fontSize = 11.sp) },
                                        singleLine = true, modifier = Modifier.fillMaxWidth())
                                }
                            })
                    }
                    Text(tf("log_hint", ui.logTaps),
                        color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                    if (ui.log.isNotEmpty()) {
                        val enregistre = rememberEnregistrer()
                        Row(Modifier.padding(top = 10.dp)) {
                            Button(onClick = {
                                // A real .adi file, not pasted text: that is what
                                // LoTW, Club Log and desktop loggers expect, and
                                // messaging apps mangle line breaks.
                                val uri = vm.adifFileUri()
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, t("adif_subject"))
                                    if (uri != null) {
                                        type = "application/octet-stream"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    } else {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, vm.logAdif())
                                    }
                                }
                                ctx.startActivity(android.content.Intent.createChooser(send, t("export_adif_chooser")))
                            }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                                Text(t("export_adif"), color = Color(0xFF00201D))
                            }
                            // Save the log to a folder rather than share it:
                            // portable without network, share often offers nothing.
                            OutlinedButton(onClick = {
                                enregistre(nomDate("SatMe-carnet", "adi"),
                                    "application/octet-stream", depuisTexte(vm.logAdif()))
                            }, modifier = Modifier.padding(start = 8.dp)) {
                                Text(t("export_save"), color = Cyan)
                            }
                        }
                    }
                }
            }
        }
        // Full contact list, newest first.
        if (ui.log.isNotEmpty()) {
            val lf = tzFormat("EEE dd/MM HH:mm:ss", ui.useUtc)
            items(ui.log, key = { "log" + it.timeMs }) { e ->
                Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clickable { vm.editLogEntry(e.timeMs) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.satName + (e.callsign.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""),
                                color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${lf.format(Date(e.timeMs))} ${tzTag(ui.useUtc)}  ·  " + tf("az_el_short", e.azimuthDeg.toInt(), e.elevationDeg.toInt()) +
                                    (e.theirLocator.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""),
                                color = TextLo, fontSize = 12.sp)
                            // A QSO made on a grid line belongs to both squares.
                            // The log shows them, because that is what has to be
                            // claimed and what the ADIF export will carry.
                            // Mode and reports too, so they can be checked at a
                            // glance before sending the log to LoTW.
                            val tech = buildList {
                                if (e.mode.isNotBlank()) add(e.mode.uppercase())
                                if (e.rstSent.isNotBlank() || e.rstRcvd.isNotBlank()) {
                                    add(e.rstSent.ifBlank { "—" } + "/" + e.rstRcvd.ifBlank { "—" })
                                }
                                // **Both frequencies, uplink first.** The
                                // uplink goes into ADIF FREQ and once held the
                                // transponder's lower edge by mistake; a value
                                // you cannot read back you cannot check. The
                                // arrow gives the direction.
                                if (e.uplinkMhz > 0.0)
                                    add("↑" + "%.3f".format(java.util.Locale.US, e.uplinkMhz))
                                if (e.downlinkMhz > 0.0)
                                    add("↓" + "%.3f".format(java.util.Locale.US, e.downlinkMhz))
                            }.joinToString("  ·  ")
                            if (tech.isNotBlank()) {
                                Text(tech, color = Aurora, fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                            val gr = e.myGrids.split(",").map { it.trim() }.filter { it.length == 4 }
                            if (gr.size > 1) {
                                Text(gr.joinToString(" / ") { it.uppercase() },
                                    color = Amber, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                        // Upload **this** contact to the online log. The first
                        // try is where a wrong write key or station profile
                        // shows up; a whole batch uploaded wrong must be undone
                        // one by one on the server. Only shown for contacts
                        // with a callsign that are not yet uploaded.
                        if (e.callsign.isNotBlank() && ui.carnet.url.isNotBlank()) {
                            if (e.envoyeMs > 0L) {
                                Text("✓", color = Cyan, fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp))
                            } else {
                                TextButton(onClick = { vm.deposeUnContact(e.timeMs) },
                                    enabled = !ui.carnet.depotEnCours) {
                                    Text(t("carnet_envoyer_un"), color = Aurora, fontSize = 11.sp)
                                }
                            }
                        }
                        IconButton(onClick = { vm.deleteLogEntry(e.timeMs) }) {
                            Icon(Icons.Default.Close, t("rec_delete"), tint = TextLo, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        }

        if (ongletCarnet == 1) {
        // The tap count that opens entry is a set-once setting, so it lives in
        // this tab, not among the contacts.
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    // Only two choices: a single tap would open the screen
                    // every time the compass is touched.
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t("log_taps"), color = TextHi)
                            Text(t("log_taps_desc"), color = TextLo, fontSize = 11.sp)
                        }
                        Row {
                            listOf(2, 3).forEach { n ->
                                val on = ui.logTaps == n
                                Surface(
                                    color = if (on) Cyan else SpaceBg,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.padding(start = 6.dp)
                                        .clickable { vm.setLogTaps(n) }
                                ) {
                                    Text("$n×",
                                        color = if (on) Color(0xFF00201D) else TextHi,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Online log: Wavelog and Cloudlog share the same API, so we do not ask
        // which one it is.
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("carnet_title"), color = TextHi,
                        fontWeight = FontWeight.SemiBold)
                    Text(t("carnet_desc"), color = TextLo, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(
                        value = ui.carnet.url, onValueChange = vm::setCarnetUrl,
                        label = { Text(t("carnet_url"), fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ui.carnet.cle, onValueChange = vm::setCarnetCle,
                        label = { Text(t("carnet_key"), fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ui.carnet.slug, onValueChange = vm::setCarnetSlug,
                        label = { Text(t("carnet_slug"), fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.essaieCarnet() },
                            enabled = ui.carnet.configure) {
                            Text(t("carnet_test"), color = Cyan, fontSize = 13.sp)
                        }
                        if (ui.carnet.essai.isNotBlank()) {
                            Text(ui.carnet.essai,
                                color = if (ui.carnet.essai == "OK") Color(0xFF7FE3A0) else Amber,
                                fontSize = 12.sp)
                        }
                    }

                    // Upload, below the settings it needs. The profile id is
                    // asked here, not above: it is only used for writing, and
                    // someone who only wants the square map need not dig it up.
                    Spacer(Modifier.height(10.dp))
                    Text(t("profils_titre"), color = TextHi, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(t("profils_aide"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("maille_titre"), color = TextLo, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        // VUCC counts 4-character squares, hence the default.
                        listOf(4, 6).forEach { m ->
                            FilterChip(
                                selected = ui.carnet.maille == m,
                                onClick = { vm.setMailleCarres(m) },
                                label = { Text(if (m == 4) "JN06" else "JN06XJ",
                                               fontSize = 12.sp) },
                                modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { vm.relevProfils() },
                        enabled = ui.carnet.url.isNotBlank() && ui.carnet.cle.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(t("profils_relever"), color = Cyan, fontSize = 12.sp)
                    }
                    if (ui.carnet.profilsEtat.isNotBlank()) {
                        Text(ui.carnet.profilsEtat, color = Amber, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    val orphelins = ui.carnet.profils.count { it.value == null }
                    if (orphelins > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(t("profils_itu"), color = Amber, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { vm.creeProfilsManquants("227", "FRANCE", "14", "27") },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(t("profils_creer") + "  ($orphelins)",
                                 color = Amber, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // **The raw id disappears once profile names are known.**
                    // "32" says nothing about square, callsign or place. The
                    // free field only returns if fetching found nothing:
                    // without it, a server that refuses reads would block
                    // uploads entirely.
                    if (ui.carnet.profilsListe.isEmpty()) {
                        OutlinedTextField(
                            value = ui.carnet.profil, onValueChange = vm::setCarnetProfil,
                            label = { Text(t("carnet_profil"), fontSize = 12.sp) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(t("carnet_profil_desc"), color = TextLo, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                    }

                    // **Readable profiles once fetched.** With 26 profiles,
                    // typing an id from memory is a guess, and a wrong one is
                    // silent: Wavelog accepts the contact under another
                    // location's square, found out only at award time.
                    if (ui.carnet.profilsListe.isNotEmpty()) {
                        Text(t("carnet_profil_liste"), color = TextHi, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        ui.carnet.profilsListe.forEach { p ->
                            val actif = ui.carnet.profil == p.id
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { vm.setCarnetProfil(p.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (actif) Icons.Default.CheckCircle
                                    else Icons.Default.RadioButtonUnchecked,
                                    null, tint = if (actif) Cyan else TextLo,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                // Square and callsign first: that is what the
                                // operator recognises. The id is left out.
                                Text(
                                    listOf(p.carre, p.indicatif, p.nom)
                                        .filter { it.isNotBlank() }
                                        .joinToString("  ·  ")
                                        .ifBlank { p.id },
                                    color = if (actif) TextHi else TextLo,
                                    fontSize = 12.sp,
                                    fontWeight = if (actif) FontWeight.SemiBold
                                                 else FontWeight.Normal)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    // The count is on the button: you see whether there is
                    // anything to do without pressing it.
                    val attente = remember(ui.log.size, ui.carnet.depot) { vm.contactsADeposer() }

                    // **A summary before sending.** Upload is irreversible (the
                    // API writes, it does not correct) and a wrong profile files
                    // everything under the wrong square, found months later.
                    // Showing count, callsign and profile costs a second.
                    var confirme by remember { mutableStateOf(false) }
                    if (confirme) {
                        val lieux = remember(ui.log.size) { vm.carresDuCarnet(ui.carnet.maille) }
                        val nomProfil = ui.carnet.profilsListe
                            .firstOrNull { it.id == ui.carnet.profil }
                        AlertDialog(
                            onDismissRequest = { confirme = false },
                            title = { Text(t("depot_verif")) },
                            text = {
                                Column {
                                    LigneRecap(t("depot_contacts"), attente.toString())
                                    LigneRecap(t("depot_indicatif"), ui.callsign.ifBlank { "—" })
                                    LigneRecap(
                                        t("depot_profil"),
                                        nomProfil?.let {
                                            listOf(it.id, it.carre, it.indicatif)
                                                .filter { v -> v.isNotBlank() }
                                                .joinToString(" · ")
                                        } ?: ui.carnet.profil.ifBlank { "—" })
                                    LigneRecap(
                                        t("depot_carres"),
                                        lieux.keys.joinToString(", ").ifBlank { "—" })
                                    LigneRecap(t("depot_serveur"), ui.carnet.url)
                                    // Log squares and profile square must match:
                                    // this is where a portable outing filed
                                    // under the home square shows up.
                                    if (nomProfil != null && lieux.size > 1) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(t("depot_plusieurs"), color = Amber,
                                            fontSize = 11.sp)
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirme = false; vm.deposeAuCarnet()
                                }) { Text(t("depot_envoyer"), color = Cyan) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirme = false }) {
                                    Text(t("cancel"), color = TextLo)
                                }
                            })
                    }

                    Button(
                        onClick = { confirme = true },
                        enabled = !ui.carnet.depotEnCours && attente > 0 &&
                            ui.carnet.url.isNotBlank() && ui.carnet.cle.isNotBlank() &&
                            ui.carnet.profil.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (attente == 0) t("carnet_depot_rien")
                            else tf("carnet_depot", attente),
                            color = Color(0xFF00201D), fontWeight = FontWeight.Bold)
                    }
                    if (ui.carnet.depot.isNotBlank()) {
                        Text(ui.carnet.depot, color = Amber, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // ------------------------------------------------ after the pass
        //
        // Post-pass work done on the phone instead of the PC. The three steps
        // are in the only order that works: clean satellite names, fetch
        // profiles, then upload. Uploading before fetching files everything
        // under the wrong square.
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("apres_titre"), color = TextHi, fontWeight = FontWeight.SemiBold)
                    Text(t("apres_aide"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))

                    // 1. Satellite names.
                    val aNettoyer = remember(ui.log.size) { vm.nomsSatellitesANettoyer() }
                    OutlinedButton(onClick = { vm.nettoieNomsSatellites() },
                        enabled = aNettoyer > 0,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (aNettoyer == 0) t("sat_noms_propres")
                             else t("sat_noms_bouton") + "  ($aNettoyer)",
                             color = if (aNettoyer > 0) Amber else TextLo, fontSize = 12.sp)
                    }

                    // 2. QRZ: fill in missing squares.
                    Spacer(Modifier.height(12.dp))
                    Text(t("qrz_titre"), color = TextHi, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(t("qrz_aide"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = ui.carnet.qrzUser, onValueChange = { vm.setQrzUser(it) },
                            label = { Text("QRZ", fontSize = 11.sp) },
                            singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = ui.carnet.qrzMdp, onValueChange = { vm.setQrzMdp(it) },
                            label = { Text("••••", fontSize = 11.sp) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.weight(1f))
                    }
                    // **Test credentials right away**, next to the fields.
                    // Otherwise a wrong password only showed up mid-pass.
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { vm.testeQrz() },
                        enabled = ui.carnet.qrzUser.isNotBlank() &&
                            ui.carnet.qrzMdp.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(t("qrz_test"), color = Cyan, fontSize = 12.sp)
                    }

                    val manquants = remember(ui.log.size) { vm.carresManquants() }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { vm.combleParQrz() },
                        enabled = !ui.carnet.qrzEnCours && manquants > 0 &&
                            ui.carnet.qrzUser.isNotBlank() && ui.carnet.qrzMdp.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(t("qrz_bouton") + "  ($manquants)",
                             color = if (manquants > 0) Cyan else TextLo, fontSize = 12.sp)
                    }
                    if (ui.carnet.qrzEtat.isNotBlank()) {
                        Text(ui.carnet.qrzEtat, color = Amber, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }

                    // "Feed the keyboard" moved to the quick-log card (it fills
                    // the callsign memory, not the log). Station profiles moved
                    // up next to upload: fetching and choosing are one gesture.
                }
            }
        }


        // LoTW: full download on demand. Confirmed squares are what count for
        // awards, which is not the same as "already worked".
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("lotw_title"), color = TextHi, fontWeight = FontWeight.SemiBold)
                    Text(t("lotw_desc"), color = TextLo, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(
                        value = ui.carnet.lotwCall, onValueChange = vm::setLotwCall,
                        label = { Text(t("lotw_call"), fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ui.carnet.lotwMdp, onValueChange = vm::setLotwMdp,
                        label = { Text(t("lotw_pass"), fontSize = 12.sp) },
                        singleLine = true,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.rafraichisLotw() }) {
                            Text(t("lotw_refresh"), color = Cyan, fontSize = 13.sp)
                        }
                        if (ui.carnet.lotwEtat.isNotBlank()) {
                            Text(ui.carnet.lotwEtat, color = TextLo, fontSize = 11.sp)
                        }
                    }
                    Text(t("lotw_warn"), color = Amber, fontSize = 10.sp)
                }
            }
        }

        }

        }
        if (sec == "mire") {
            item { MireSection(ui, vm) }
        }
        if (sec == "sondemire") {
            item { OuvrirPage(t("sonde_title")) { vm.openSonde() } }
            item { SondeMireSection(ui, vm) }
        }
        if (sec == "cat") {
        item { SectionHeader(t("cat_control")) }
        // Master switch at the top of the section: it controls everything
        // else, and used to be buried three screens down below the test bench.
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).toggleable(value = ui.catEnabled, role = Role.Switch,
                        onValueChange = vm::setCatEnabled), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("enable_cat"), color = TextHi, fontWeight = FontWeight.Bold)
                        Text(t("cat_send_desc"), color = TextLo, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(checked = ui.catEnabled, onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                }
            }
        }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("rig"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("rig_desc"),
                        color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                    // Only radios actually supported; no "coming soon" entries
                    // on a screen usually opened three minutes before AOS.
                    val rigs = listOf(
                        Triple("IC9700", "Icom IC-9700", true),
                        Triple("FT817x2", "2× Yaesu FT-817", true),
                        Triple("FT817TX", t("qo100_poste_sdr"), true)
                    )
                    rigs.forEach { (id, label, ready) ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected = ui.rigModel == id, enabled = ready,
                                    role = Role.RadioButton, onClick = { vm.setRigModel(id) })
                                .padding(vertical = 6.dp)) {
                            RadioButton(selected = ui.rigModel == id, enabled = ready,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Cyan))
                            Spacer(Modifier.width(4.dp))
                            Text(label, color = if (ready) TextHi else TextLo,
                                fontWeight = if (ui.rigModel == id) FontWeight.Bold else FontWeight.Normal)
                            if (!ready) {
                                Spacer(Modifier.width(8.dp))
                                Surface(color = TextLo.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                    Text(t("coming_soon"), color = TextLo, fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        // --- Dual FT-817: RX/TX adapter assignment (by FTDI serial) + baud ---
        if (ui.rigModel == "FT817x2" || ui.rigModel == "FT817TX") {
        item {
            LaunchedEffect(Unit) { vm.refreshUsbDevices() }
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("ft817_pair_title"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("ft817_pair_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))

                    Text(t("ft817_baud"), color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)) {
                        listOf(4800, 9600, 38400).forEach { b ->
                            FilterChip(selected = ui.ft817Baud == b,
                                onClick = { vm.setFt817Baud(b) },
                                label = { Text("$b", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                    selectedLabelColor = Cyan))
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("ft817_adapters"), color = TextHi, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.refreshUsbDevices() }) {
                            Text("↻", color = Cyan, fontSize = 16.sp)
                        }
                    }
                    if (ui.usbDevices.isEmpty()) {
                        Text(t("ft817_no_adapters"), color = TextLo, fontSize = 12.sp)
                    }

                    // One-tap shortcut, always visible. The manual sequence is
                    // refresh, authorise, detect, connect, and order matters:
                    // enabling CAT before detecting left ports bound to the old
                    // assignment.
                    Button(onClick = { vm.prepareFt817() },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(t("ft817_prepare"), color = Color(0xFF00201D),
                            fontWeight = FontWeight.Bold)
                    }
                    Text(t("ft817_prepare_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 6.dp))

                    // Link indicator here, where "is it tracking?" is asked
                    // while plugging in.
                    //
                    // Polling only runs while this section is shown:
                    // `DisposableEffect` stops it on exit so the serial line
                    // stays free for Doppler during a pass.
                    androidx.compose.runtime.DisposableEffect(ui.catConnected) {
                        vm.veilleCat(true)
                        onDispose { vm.veilleCat(false) }
                    }
                    if (ui.catConnected) {
                        val vivant = ui.catUi.veilleVivante
                        Surface(
                            color = (if (vivant) Cyan else Amber).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(t("cat_temoin"), color = if (vivant) Cyan else Amber,
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                fun mhz(hz: Long?) =
                                    if (hz == null) "—"
                                    else String.format(java.util.Locale.US, "%.4f", hz / 1_000_000.0)
                                Text("RX " + mhz(ui.catUi.veilleRxHz) +
                                     "   TX " + mhz(ui.catUi.veilleTxHz),
                                    color = TextHi, fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                Text(t("cat_temoin_desc"), color = TextLo, fontSize = 11.sp)
                            }
                        }
                    }

                    if (ui.usbDevices.any { !it.hasPermission }) {
                        OutlinedButton(onClick = { vm.ft817RequestPermissions() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(t("ft817_grant_usb"), color = Cyan, fontSize = 13.sp)
                        }
                    }
                    ui.usbDevices.forEach { dev ->
                        Surface(color = SpaceBg, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(dev.label, color = TextHi, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        !dev.hasPermission -> t("ft817_serial_locked")
                                        dev.serial != null -> dev.serial!!
                                        // Many chips (e.g. PL2303TA) have no
                                        // serial; say so rather than show a
                                        // blank line that looks like a fault.
                                        else -> t("ft817_no_serial") + " · " + dev.deviceName
                                    },
                                    color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                // Frequency read through the cable: two
                                // identical PL2303s are indistinguishable,
                                // but the radios are on different bands.
                                Text(
                                    when {
                                        dev.freqLueHz != null ->
                                            "%.4f MHz".format(dev.freqLueHz!! / 1_000_000.0)
                                        dev.sonde -> t("usb_no_answer")
                                        else -> t("usb_probing")
                                    },
                                    color = if (dev.freqLueHz != null) Aurora else TextLo,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace)
                                // Chips depend on permission, not on the serial:
                                // gating on the serial made cables without one
                                // listed but unassignable.
                                if (dev.hasPermission) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(top = 4.dp)) {
                                        FilterChip(selected = ui.ft817RxSerial == dev.cle,
                                            onClick = { vm.setFt817Role(dev.cle, "RX") },
                                            label = { Text("RX ↓", fontSize = 12.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                                selectedLabelColor = Cyan))
                                        FilterChip(selected = ui.ft817TxSerial == dev.cle,
                                            onClick = { vm.setFt817Role(dev.cle, "TX") },
                                            label = { Text("TX ↑", fontSize = 12.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Amber.copy(alpha = 0.25f),
                                                selectedLabelColor = Amber))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.toggleable(value = ui.catUi.txSuitVite, role = Role.Switch,
                            onValueChange = vm::setTxSuitVite), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("cat_tx_fast"), color = TextHi,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(t("cat_tx_fast_desc"), color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = ui.catUi.txSuitVite,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Color(0xFF7FE3A0)))
                    }

                    // TX indicator and its cost: polling shares the link with
                    // Doppler, so the trade-off is a setting.
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.toggleable(value = ui.catUi.liseret, role = Role.Switch,
                            onValueChange = vm::setLiseretEmission), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("cat_tx_border"), color = TextHi,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(t("cat_tx_border_desc"), color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = ui.catUi.liseret,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Color(0xFFFF7A7A)))
                    }
                    if (ui.catUi.liseret && ui.catUi.txDiag.isNotBlank()) {
                        Text("PTT : " + ui.catUi.txDiag, color = TextLo, fontSize = 11.sp)
                    }
                    if (ui.catUi.liseret) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)) {
                            listOf(250 to "0,25 s", 500 to "0,5 s", 1_000 to "1 s",
                                   2_000 to "2 s").forEach { (ms, lib) ->
                                FilterChip(
                                    selected = ui.catUi.sondeMs == ms,
                                    onClick = { vm.setSondeTxMs(ms) },
                                    label = { Text(lib, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Amber.copy(alpha = 0.3f),
                                        selectedLabelColor = Amber))
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(t("cat_hold"), color = TextHi, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp)
                    Text(t("cat_hold_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 0.25 s steps: on an IC-9700 the uplink is not written
                        // while the dial moves, so resuming fast costs nothing,
                        // and the delay is felt when operating.
                        listOf(2_000 to "2 s", 1_000 to "1 s", 500 to "0,5 s",
                               250 to "0,25 s").forEach { (ms, lib) ->
                            FilterChip(
                                selected = ui.catUi.holdMs == ms,
                                onClick = { vm.setCatHold(ms) },
                                label = { Text(lib, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                    selectedLabelColor = Cyan))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    val duplex = ui.rigModel == "FT817x2" || ui.rigModel == "FT817TX"
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().toggleable(value = ui.catUi.txVfoShift && duplex, enabled = duplex, role = Role.Switch,
                            onValueChange = { vm.setCatTxVfoShift(it) })) {
                        Column(Modifier.weight(1f)) {
                            Text(t("cat_tx_vfo"), color = if (duplex) TextHi else TextLo,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(if (duplex) t("cat_tx_vfo_desc") else t("cat_tx_vfo_pair"),
                                color = TextLo, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(checked = ui.catUi.txVfoShift && duplex, enabled = duplex,
                            onCheckedChange = null)
                    }

                    if (ui.ft817RxSerial.isNotBlank() || ui.ft817TxSerial.isNotBlank()) {
                    }
                }
            }
        }
        }
        // Only when CAT is on: its whole content is CAT, and an empty card
        // was left under the rig list otherwise.
        if (ui.catEnabled) item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    var bancDeplie by rememberSaveable { mutableStateOf(false) }
                    run {
                        // The test bench (simulated radio, frame log) folds
                        // away: used only when something breaks, it pushed the
                        // everyday settings down.
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth().clickable { bancDeplie = !bancDeplie },
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (bancDeplie) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null, tint = Amber,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(t("cat_bench"), color = Amber, fontSize = 13.sp)
                        }
                        if (bancDeplie) {
                            // --- Test bench: simulated radio and frame log ---
                            Spacer(Modifier.height(10.dp))
                            Text(t("cat_bench"), color = Amber, fontSize = 13.sp)
                            Text(t("cat_bench_desc"), color = TextLo, fontSize = 11.sp)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp).toggleable(value = ui.catSimulated, role = Role.Switch,
                                    onValueChange = vm::setCatSimulated)) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("cat_sim"), color = TextHi, fontSize = 13.sp)
                                    Text(t("cat_sim_desc"), color = TextLo, fontSize = 11.sp)
                                }
                                Switch(checked = ui.catSimulated, onCheckedChange = null,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Amber))
                            }
                            Row(modifier = Modifier.toggleable(value = ui.catMonitor, role = Role.Switch,
                                    onValueChange = vm::setCatMonitor), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("cat_mon"), color = TextHi, fontSize = 13.sp)
                                    Text(t("cat_mon_desc"), color = TextLo, fontSize = 11.sp)
                                }
                                Switch(checked = ui.catMonitor, onCheckedChange = null,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Amber))
                            }
                            if (ui.catMonitor) {
                                val trames by fr.f4ioz.satcombo.cat.CatJournal.entries.collectAsState()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tf("cat_mon_count", trames.size),
                                        color = TextLo, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { fr.f4ioz.satcombo.cat.CatJournal.clear() }) {
                                        Text(t("clear"), color = Cyan, fontSize = 12.sp)
                                    }
                                }
                                if (trames.isEmpty()) {
                                    Text(t("cat_mon_empty"), color = TextLo.copy(alpha = 0.7f), fontSize = 11.sp)
                                } else {
                                    val fmt = remember {
                                        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                                    }
                                    Column(
                                        Modifier
                                            .heightIn(max = 240.dp)
                                            .verticalScroll(rememberScrollState())
                                            .background(SpaceBg, RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        // Newest first: at the bench you look at
                                        // what was just sent.
                                        trames.asReversed().forEach { trame ->
                                            Text(
                                                (if (trame.out) "▶ " else "◀ ") +
                                                    fmt.format(java.util.Date(trame.tMs)) + "  " + trame.hex,
                                                color = if (trame.out) Cyan else Aurora,
                                                fontSize = 10.sp,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                            Text("    " + trame.text, color = TextLo, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.connectCat() },
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                                Text(t("connect"), color = Color(0xFF00201D))
                            }
                            OutlinedButton(onClick = { vm.disconnectCat() }) {
                                Text(t("disconnect"), color = TextLo)
                            }
                        }
                        if (ui.catStatus.isNotBlank()) {
                            Text(ui.catStatus, fontSize = 12.sp,
                                color = if (ui.catConnected) Color(0xFF49D17F) else Amber,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        // Diagnostics row.
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.testCat() }) {
                                Text(t("test_link"), color = Cyan, fontSize = 13.sp)
                            }
                            OutlinedButton(onClick = { vm.catSendTestFreq() }) {
                                Text(t("send_test_freq"), color = Cyan, fontSize = 13.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).toggleable(value = ui.catTestSendAlways, role = Role.Switch,
                                onValueChange = vm::setCatTestSendAlways)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("send_below_horizon"), color = TextHi, fontSize = 13.sp)
                                Text(t("cat_test_desc"),
                                    color = TextLo, fontSize = 11.sp)
                            }
                            Switch(checked = ui.catTestSendAlways, onCheckedChange = null,
                                colors = SwitchDefaults.colors(checkedTrackColor = Amber))
                        }
                        // Who owns the RX dial during the pass.
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).toggleable(value = ui.catRxDoppler, role = Role.Switch,
                                onValueChange = vm::setCatRxDoppler)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("cat_rx_doppler"), color = TextHi, fontSize = 13.sp)
                                Text(t("cat_rx_doppler_desc"), color = TextLo, fontSize = 11.sp)
                            }
                            Switch(checked = ui.catRxDoppler, onCheckedChange = null,
                                colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                        }
                        // ------------------------------------------------
                        // What follows is for Icom CI-V radios ONLY. Left
                        // unconditioned, it showed in Yaesu dual mode too, with
                        // two competing "USB adapter" sections.
                        if (ui.rigModel != "FT817x2") {
                            // CI-V address + baud.
                            Spacer(Modifier.height(8.dp))
                            Text(t("civ_address"), color = TextHi, fontSize = 13.sp)
                            // Same trap as the callsign: keying the state on the
                            // value it writes rebuilds the field mid-typing, and
                            // "A2" came back as "0A" after the first character.
                            var addrTxt by remember { mutableStateOf("%02X".format(ui.civAddress)) }
                            OutlinedTextField(value = addrTxt, onValueChange = {
                                addrTxt = it.uppercase().filter { c -> c.isDigit() || c in 'A'..'F' }.take(2)
                                addrTxt.toIntOrNull(16)?.let { v -> vm.setCivAddress(v) }
                            }, singleLine = true, placeholder = { Text("A2") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                                    focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                                modifier = Modifier.width(120.dp))
                            Text(t("civ_default"), color = TextLo, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(t("usb_baud"), color = TextHi, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(19200, 38400, 57600, 115200).forEach { b ->
                                    FilterChip(selected = ui.civBaud == b, onClick = { vm.setCivBaud(b) },
                                        label = { Text("$b", fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                                }
                            }
                            // Which adapter is the radio. Not always the first
                            // found: an SDR dongle also shows up as a serial port,
                            // and the link then depended on plug-in order.
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t("cat_usb_device"), color = TextHi, fontSize = 13.sp,
                                    modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = vm::refreshCatDevices,
                                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    Icon(Icons.Default.Refresh, null, tint = Cyan,
                                        modifier = Modifier.size(16.dp))
                                }
                            }
                            if (ui.catDevices.isEmpty()) {
                                Text(t("cat_usb_none"), color = TextLo, fontSize = 11.sp)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ui.catDevices.forEachIndexed { i, _ ->
                                        FilterChip(selected = ui.civUsbIndex == i,
                                            onClick = { vm.setCivUsbIndex(i) },
                                            label = { Text("$i", fontSize = 12.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                                selectedLabelColor = Cyan))
                                    }
                                }
                                ui.catDevices.forEachIndexed { i, nom ->
                                    Text("$i · $nom",
                                        color = if (i == ui.civUsbIndex) Cyan else TextLo,
                                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                            Text(t("cat_usb_hint"), color = TextLo, fontSize = 11.sp)
                            Text(t("cat_usb_port_hint"), color = TextLo, fontSize = 11.sp)
                            // Port scan. Nothing on the radio or in its manual says
                            // which of its two ports carries CI-V, so try the other
                            // one before giving up and remember whichever answered.
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp).toggleable(value = ui.civUsbAuto, role = Role.Switch,
                                    onValueChange = vm::setCivUsbAuto)) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("cat_usb_auto"), color = TextHi, fontSize = 13.sp)
                                    Text(t("cat_usb_auto_hint"), color = TextLo, fontSize = 11.sp)
                                }
                                Switch(checked = ui.civUsbAuto, onCheckedChange = null,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                            }
                            // Report of the last attempt: without it, "I can't
                            // connect" has no answer; with it, the fault is readable.
                            if (ui.catDiag.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Text(t("cat_diag_title"), color = TextHi, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold)
                                Surface(color = SpaceSurface, shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    Column(Modifier.padding(8.dp)) {
                                        ui.catDiag.forEach { ligne ->
                                            Text(ligne, color = TextLo, fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }

                            Text(t("rig_menu_hint"),
                                color = TextLo.copy(alpha = 0.7f), fontSize = 10.sp,
                                modifier = Modifier.padding(top = 8.dp))

                        }

                        // CTCSS tone for FM birds (e.g. SO-50 uses 67.0; PSAT etc.).
                        Spacer(Modifier.height(10.dp))
                        Text(t("ctcss_tone"), color = TextHi, fontSize = 13.sp)
                        Text(t("ctcss_fm_desc"),
                            color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp).toggleable(value = ui.ctcssAuto, role = Role.Switch,
                                onValueChange = vm::setCtcssAuto)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("ctcss_auto_title"), color = TextHi, fontSize = 13.sp)
                                Text(t("ctcss_auto_desc"),
                                    color = TextLo, fontSize = 11.sp)
                            }
                            Switch(checked = ui.ctcssAuto, onCheckedChange = null,
                                colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                        }
                        Text(if (ui.ctcssAuto) t("manual_tone_auto") else t("manual_tone"),
                            color = TextLo, fontSize = 11.sp)
                        val tones = listOf(0 to "Off", 670 to "67.0", 744 to "74.4", 825 to "82.5",
                            885 to "88.5", 1000 to "100.0", 1230 to "123.0", 1365 to "136.5")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            tones.forEach { (v, label) ->
                                FilterChip(selected = ui.ctcssTenthHz == v, onClick = { vm.setCtcssTenthHz(v) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                            }
                        }

                        // ---- Test bench ----
                        // The simulated radio is not a demo: without a radio
                        // attached, it is the only way to check that commands
                        // were understood, not just acknowledged.
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color(0xFF2A3647))
                        Spacer(Modifier.height(10.dp))
                        Text(t("bench_title"), color = Cyan, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(t("bench_sim_desc"), color = TextLo, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.runCatBench() }, enabled = !ui.benchRunning) {
                                Text(if (ui.benchRunning) t("bench_running") else t("bench_run"),
                                    color = Cyan, fontSize = 13.sp)
                            }
                            Text(t("bench_sim"), color = TextLo, fontSize = 11.sp)
                        }
                        if (ui.benchReport.isNotBlank()) {
                            Text(ui.benchReport, fontSize = 12.sp,
                                color = if (ui.benchOk) Color(0xFF49D17F) else Amber,
                                modifier = Modifier.padding(top = 6.dp))
                            ui.benchSteps.forEach { st ->
                                Text("· " + st, color = TextLo, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }

                        // ---- Frame log ----
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.toggleable(value = ui.catJournalOn, role = Role.Switch,
                                onValueChange = vm::setCatJournal), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t("journal_title"), color = TextHi, fontSize = 13.sp)
                                Text(tf("journal_desc",
                                    fr.f4ioz.satcombo.cat.CatJournal.DEPTH),
                                    color = TextLo, fontSize = 11.sp)
                            }
                            Switch(checked = ui.catJournalOn, onCheckedChange = null,
                                colors = SwitchDefaults.colors(checkedTrackColor = Amber))
                        }
                        if (ui.catJournalOn) {
                            val entries by fr.f4ioz.satcombo.cat.CatJournal.entries.collectAsState()
                            TextButton(onClick = { vm.clearCatJournal() }) {
                                Text(t("journal_clear"), color = Cyan, fontSize = 12.sp)
                            }
                            if (entries.isEmpty()) {
                                Text(t("journal_empty"), color = TextLo, fontSize = 11.sp)
                            } else {
                                val hms = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
                                Column(Modifier.padding(top = 4.dp)) {
                                    // Newest first.
                                    entries.asReversed().take(60).forEach { e ->
                                        Text(
                                            (if (e.out) "▶ " else "◀ ") + hms.format(Date(e.tMs)) +
                                                "  " + e.hex,
                                            color = if (e.out) Cyan else TextHi,
                                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(top = 4.dp))
                                        Text(
                                            "    " + e.text + "  (" +
                                                (if (e.out) t("journal_out") else t("journal_in")) + ")",
                                            color = TextLo, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        }

        // --- Converters (LNB / transverter) ---
        // A page of its own, not part of CAT: a converter serves the SDR dongle
        // as much as the radio, and a QO-100 station may have no radio at all.
        if (sec == "conv") {
            // **Converters are set in one place only: the station chain in
            // the QO-100 screen.** This screen used to set them too, and the
            // two overwrote each other: the chain copies its LO into settings
            // on every apply, never the reverse, so a value fixed here silently
            // vanished at the next chain change. This screen is now read-only.
            item { SectionHeader(t("conv_title")) }
            item {
                Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(t("conv_intro"), color = TextLo, fontSize = 11.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(t("conv_lecture"), color = Amber, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        LigneConv(t("conv_rx_titre"), ui.convRx)
                        Spacer(Modifier.height(6.dp))
                        LigneConv(t("conv_tx_titre"), ui.convTx)
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = { vm.openQo100() },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(t("conv_ouvrir"), color = Cyan, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (sec == "backup") {
        item { SectionHeader(t("backup")) }
        item {
            val ctxBk = LocalContext.current
            val scope = rememberCoroutineScope()
            // Contacts selected for the next send, keyed by time: the only thing
            // that stays stable while the log changes underneath.
            var choisis by remember { mutableStateOf(emptySet<Long>()) }
            // File picker for import.
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        ctxBk.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()?.let { vm.importConfig(it) }
                        ?: android.widget.Toast.makeText(ctxBk, t("read_failed"), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // File saver for export.
            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        ctxBk.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                            it.write(vm.exportConfig())
                        }
                        android.widget.Toast.makeText(ctxBk, t("config_exported"), android.widget.Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        android.widget.Toast.makeText(ctxBk, t("export_failed"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // --- Contacts to the other phone ---
            //
            // Sits next to config backup because both travel as files, but the
            // backup **replaces** while the batch **merges**; hence the merge
            // rule is stated right where you tap.
            val lotEnvoi = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
            ) { uri ->
                if (uri != null) runCatching {
                    ctxBk.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(vm.ecritLotContacts(choisis))
                    }
                }
            }
            val lotRecu = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) runCatching {
                    ctxBk.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()?.let { vm.fusionneLotContacts(it) }
            }
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("lot_title"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("lot_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp))

                    if (ui.catUi.lotBilan.isNotBlank()) {
                        Surface(color = Cyan.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(ui.catUi.lotBilan, color = TextHi, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.effaceBilanLot() }) {
                                    Text("✕", color = TextLo)
                                }
                            }
                        }
                    }

                    // Only entries with a callsign are transferred.
                    val transferables = remember(ui.log.size) {
                        ui.log.filter { it.callsign.isNotBlank() }.sortedByDescending { it.timeMs }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { choisis = transferables.map { it.timeMs }.toSet() }) {
                            Text(t("lot_all"), color = Cyan, fontSize = 12.sp)
                        }
                        TextButton(onClick = { choisis = emptySet() }) {
                            Text(t("lot_none"), color = TextLo, fontSize = 12.sp)
                        }
                        Text(tf("lot_count", choisis.size), color = TextLo, fontSize = 11.sp)
                    }

                    val fmtLot = remember {
                        java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                    }
                    Column(Modifier.heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())) {
                        transferables.take(200).forEach { e ->
                            val pris = e.timeMs in choisis
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        choisis = if (pris) choisis - e.timeMs else choisis + e.timeMs
                                    }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (pris) "☑" else "☐",
                                    color = if (pris) Cyan else TextLo, fontSize = 15.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(e.callsign, color = TextHi, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(96.dp))
                                Text(fmtLot.format(java.util.Date(e.timeMs)) + "  " + e.satName,
                                    color = TextLo, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = { lotEnvoi.launch(vm.nomLotContacts()) },
                            enabled = choisis.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                            Text(t("lot_send"),
                                color = if (isDarkTheme()) Color(0xFF00201D) else Color.White)
                        }
                        OutlinedButton(onClick = {
                            lotRecu.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }) {
                            Text(t("lot_merge"), color = Cyan, fontSize = 12.sp)
                        }
                    }
                }
            }

            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("config"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("config_desc"),
                        color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { exportLauncher.launch(vm.configFileName()) },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                            Text(t("export"), color = if (isDarkTheme()) Color(0xFF00201D) else Color.White)
                        }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                            Text(t("import"), color = Cyan)
                        }
                    }
                    val enregistreCfg = rememberEnregistrer()
                    Row(Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = {
                            enregistreCfg(nomDate("SatMe-config", "json"),
                                "application/json", depuisTexte(vm.exportConfig()))
                        }) {
                            Text(t("export_save"), color = Cyan, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "SatMe — configuration")
                                putExtra(android.content.Intent.EXTRA_TEXT, vm.exportConfig())
                            }
                            ctxBk.startActivity(android.content.Intent.createChooser(send, t("share_config_chooser")))
                        }) {
                            Text(t("share_config"), color = TextLo, fontSize = 12.sp)
                        }
                    }
                    Text(t("import_replaces"),
                        color = TextLo.copy(alpha = 0.7f), fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // --- PDF sheets ---
        }
        if (sec == "pdf") {
        item { SectionHeader(t("pdf_sheets")) }
        item {
            val ctxPdf = LocalContext.current
            val enregistrePdf = rememberEnregistrer()
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("pdf_sheets_title"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("pdf_sheets_desc"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 10.dp))
                    Button(onClick = {
                        vm.exportSatSheets { uri ->
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctxPdf.startActivity(android.content.Intent.createChooser(send, t("pdf_sheets")))
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Icon(Icons.Default.DateRange, null,
                            tint = if (isDarkTheme()) Color(0xFF00201D) else Color.White,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("generate_sheets"), color = if (isDarkTheme()) Color(0xFF00201D) else Color.White)
                    }
                    OutlinedButton(onClick = {
                        vm.exportSatSheets { uri ->
                            enregistrePdf(nomDate("SatMe-fiches", "pdf"),
                                "application/pdf", depuisUri(ctxPdf, uri))
                        }
                    }, modifier = Modifier.padding(top = 6.dp)) {
                        Text(t("export_save"), color = Cyan)
                    }
                }
            }
        }

        // --- About ---
        }
        if (sec == "about") {
        item { SectionHeader(t("about")) }
        item { AProposReferences() }
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(Cyan.copy(alpha = 0.16f)), Alignment.Center) {
                        Text("🛰", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SatMe", color = TextHi, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        Text(t("about_tagline"), color = TextLo, fontSize = 12.sp)
                        // Version from the package manager: confirms which build is installed.
                        val ctxV = LocalContext.current
                        val ver = remember {
                            runCatching {
                                val pi = ctxV.packageManager.getPackageInfo(ctxV.packageName, 0)
                                "v${pi.versionName} (build ${
                                    if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                                })"
                            }.getOrDefault("")
                        }
                        if (ver.isNotEmpty())
                            Text(ver, color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Surface(color = Cyan.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp)) {
                        Text("F4IOZ", color = Cyan, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
        // Manual check: asks the Store here and now and always says what came
        // back — including "not installed from the Store", which is the whole
        // explanation when the automatic prompt never shows up. The Store page
        // itself stays one tap away underneath.
        item {
            val ctxU = LocalContext.current
            fun openStore() {
                val id = ctxU.packageName
                val market = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=$id"))
                val web = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                runCatching { ctxU.startActivity(market) }
                    .onFailure { runCatching { ctxU.startActivity(web) } }
            }
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    val up = fr.f4ioz.satcombo.update.PlayUpdater.active
                    if (up != null) up.recheck() else openStore()
                }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, null, tint = Cyan)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("update_check"), color = TextHi, fontSize = 14.sp)
                            Text(t("update_check_desc"), color = TextLo, fontSize = 11.sp)
                        }
                    }
                    if (ui.updateMsg.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(ui.updateMsg, color = Amber, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { openStore() }) {
                        Text(t("update_open_store"), color = Cyan, fontSize = 12.sp)
                    }
                }
            }
        }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * Mutual-visibility sked calculator (same idea as f4ioz.fr/sat/passes): enter
 * the other station's Maidenhead locator, pick its minimum elevation, and get
 * the windows where the satellite is above both horizons at once (48 h).
 */
@Composable
private fun SkedCalcDialog(vm: MainViewModel, catnum: Int, onDismiss: () -> Unit) {
    var loc by remember { mutableStateOf("") }
    var minEl by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var badLoc by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SkedWindow>?>(null) }
    // Day + hours in the timezone the user selected (UTC/local), never mixed.
    val useUtc = vm.ui.collectAsState().value.useUtc
    val dfmt = tzFormat("EEE dd/MM HH:mm", useUtc)
    val hfmt = tzFormat("HH:mm", useUtc)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SpaceCard,
        title = { Text(t("sked_calc"), color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = loc,
                    onValueChange = { loc = it.uppercase(); badLoc = false },
                    label = { Text(t("sked_loc2"), fontSize = 12.sp) },
                    singleLine = true,
                    isError = badLoc,
                    modifier = Modifier.fillMaxWidth())
                if (badLoc) Text(t("sked_bad_loc"), color = Color(0xFFD6336C), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(8.dp))
                Text(t("sked_minel2"), color = TextLo, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    listOf(0, 5, 10).forEach { v ->
                        FilterChip(selected = minEl == v, onClick = { minEl = v },
                            label = { Text("$v°", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                selectedLabelColor = Cyan))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        busy = true; results = null
                        vm.computeSked(catnum, loc, minEl.toDouble()) { r ->
                            busy = false
                            if (r == null) badLoc = true else results = r
                        }
                    },
                    enabled = !busy && loc.trim().length >= 4,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) t("sked_calcing") else t("sked_go"),
                        color = if (isDarkTheme()) Color(0xFF00201D) else Color.White)
                }
                results?.let { list ->
                    Spacer(Modifier.height(10.dp))
                    if (list.isEmpty()) {
                        Text(t("sked_none"), color = TextLo, fontSize = 12.sp)
                    } else {
                        Column(Modifier.heightIn(max = 250.dp)
                            .verticalScroll(rememberScrollState())) {
                            list.take(12).forEach { w ->
                                Surface(color = SpaceBg, shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                        Text("${dfmt.format(java.util.Date(w.startMs))} → " +
                                            hfmt.format(java.util.Date(w.endMs)) +
                                            " ${tzTag(useUtc)}" +
                                            "  ·  ${(w.endMs - w.startMs) / 60_000} min",
                                            color = TextHi, fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace)
                                        Text(t("sked_you") + " ${w.elA}°  ·  " +
                                            loc.trim().uppercase() + " ${w.elB}°",
                                            color = Aurora, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("close"), color = Cyan) } }
    )
}

/**
 * Location tracking state, in plain words.
 *
 * Built after two blind fixes failed to cure a first-launch fault. The fix
 * count is the key fact: if it stays at zero, no position ever arrived, and
 * the last status line says why.
 */
@Composable
private fun GpsEtatCard(ui: UiState) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t("gps_title"), color = TextHi, fontWeight = FontWeight.Bold)
            Text(t("gps_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

            GpsLigne(t("gps_mode"), if (ui.locationMode == LocationMode.AUTO) "AUTO" else "MANUEL")
            GpsLigne(t("gps_perm"), if (ui.suivi.permission) "OK" else "NON")
            GpsLigne(t("gps_points"), "${ui.suivi.points}")
            GpsLigne(t("gps_restarts"), "${ui.suivi.relances}")
            GpsLigne(t("gps_observer"), ui.observer?.name ?: "—")
            Spacer(Modifier.height(8.dp))
            Text(t("gps_last"), color = TextLo, fontSize = 11.sp)
            Text(ui.suivi.etat.ifBlank { "—" }, color = Aurora, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun GpsLigne(cle: String, valeur: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(cle, color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(valeur, color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * Quick log: the keyboard and the memory of worked stations.
 *
 * ADIF import lives here, not in backup, because it restores nothing: it feeds
 * prediction. What comes in is an index (callsign, square, date, satellite),
 * not a log; the device log stays the reference.
 */
@Composable
private fun CarnetExpressCard(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    var bilan by remember { mutableStateOf<String?>(null) }
    val choix = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val texte = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (texte == null) {
                android.widget.Toast.makeText(ctx, t("read_failed"),
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val b = vm.importeAdif(texte)
                bilan = tf("express_import_done",
                    b.indicatifs, b.retenus, b.enregistrementsLus, b.ecartes)
            }
        }
    }

    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t("express_title"), color = TextHi, fontWeight = FontWeight.Bold)
            // The bundled log switch used to live here. The bundled base was
            // removed: everyone now pulls in their own log.
            Text(t("express_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

            Text(t("kb_layout"), color = TextHi, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp))
            Text(t("kb_layout_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fr.f4ioz.satcombo.domain.DispositionClavier.toutes.forEach { nom ->
                    val actif = ui.express.disposition == nom
                    FilterChip(
                        selected = actif,
                        onClick = { vm.setClavierDisposition(nom) },
                        label = {
                            Text(if (nom == "abc") t("kb_abc") else nom.uppercase(),
                                fontSize = 12.sp)
                        })
                }
            }

            // The dial and three-key box have their own section: buried here,
            // nobody found them.

            SettingSwitch(t("kb_hand"), t("kb_hand_desc"), ui.express.mainGauche) {
                vm.setClavierMainGauche(it)
            }

            Spacer(Modifier.height(14.dp))
            Text(t("express_memory"), color = TextHi, fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp)
            Text(tf("express_memory_count", ui.express.memoire.size),
                color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            Text(t("express_import_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp))
            OutlinedButton(onClick = {
                choix.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            }) { Text(t("express_import")) }

            // **Purge what was imported.** An index gathers wrong squares and
            // stale callsigns. The purge clears only the index; log contacts
            // stay, as they are the only thing that cannot be fetched again.
            var confirmePurge by remember { mutableStateOf(false) }
            if (ui.express.memoire.isNotEmpty()) {
                TextButton(onClick = { confirmePurge = true }) {
                    Text(t("express_purge"), color = Magenta, fontSize = 13.sp)
                }
            }
            if (confirmePurge) {
                AlertDialog(
                    onDismissRequest = { confirmePurge = false },
                    title = { Text(t("express_purge")) },
                    text = { Text(t("express_purge_desc"), fontSize = 13.sp) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmePurge = false
                            vm.purgeIndexImporte()
                        }) { Text(t("express_purge_ok"), color = Magenta) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmePurge = false }) {
                            Text(t("cancel"), color = TextLo)
                        }
                    })
            }
            bilan?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Aurora, fontSize = 12.sp)
            }

            // **The report shows where you tapped.** The harvest message lands
            // in `carnet.depot`, which is only displayed in the online-log card,
            // so moving the button here cut it off from its feedback. We watch
            // for `depotEnCours` falling back and show the result in a dialog:
            // it carries four numbers, and one more line would go unnoticed.
            var compteRendu by remember { mutableStateOf<String?>(null) }
            var moissonnait by remember { mutableStateOf(false) }
            LaunchedEffect(ui.carnet.depotEnCours) {
                if (ui.carnet.depotEnCours) moissonnait = true
                else if (moissonnait) {
                    moissonnait = false
                    compteRendu = ui.carnet.depot.ifBlank { t("carnet_moisson_rien") }
                }
            }
            compteRendu?.let { texte ->
                AlertDialog(
                    onDismissRequest = { compteRendu = null },
                    title = { Text(t("carnet_moisson_bouton")) },
                    text = { Text(texte, fontSize = 13.sp) },
                    confirmButton = {
                        TextButton(onClick = { compteRendu = null }) {
                            Text(t("ok"), color = Cyan)
                        }
                    })
            }

            // **Feeding the keyboard from the online log lives with the
            // keyboard**, not with the Wavelog key: it fills the callsign
            // memory above, not the log. Without it a new or second phone
            // started empty, while Wavelog, fed by both devices, knows it all.
            Spacer(Modifier.height(10.dp))
            Text(t("carnet_moisson_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp))

            // What to import. Satellite by default: a station worked once on
            // 40 m has no place in the keyboard's suggestions.
            Text(t("moisson_filtre"), color = TextHi, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 4.dp)) {
                fr.f4ioz.satcombo.domain.FiltreMoisson.toutes.forEach { f ->
                    FilterChip(
                        selected = ui.carnet.filtre == f,
                        onClick = { vm.setCarnetFiltre(f) },
                        label = { Text(t("moisson_$f"), fontSize = 11.sp) })
                }
            }
            // Warn that non-satellite imports are slow, or the wait looks
            // like a fault.
            if (ui.carnet.filtre != fr.f4ioz.satcombo.domain.FiltreMoisson.SAT) {
                Text(t("moisson_lourd"), color = Amber, fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            // **Say why the button is disabled.** Without a Wavelog URL and
            // key it was just greyed out, with no hint that the setting lives
            // in another section. A dead button looks like a fault.
            if (ui.carnet.url.isBlank() || ui.carnet.cle.isBlank()) {
                Surface(color = Amber.copy(alpha = 0.13f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(t("express_sans_wavelog"), color = Amber, fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp))
                }
            }
            // **Say the online log is not set up, rather than show a dead
            // button** (see `carnetPret` below).
            var choixProfils by remember { mutableStateOf(false) }
            val coches = remember { mutableStateListOf<String>() }
            if (choixProfils) {
                AlertDialog(
                    onDismissRequest = { choixProfils = false },
                    title = { Text(t("moisson_choix_titre")) },
                    text = {
                        // **The list scrolls.** With dozens of profiles the last
                        // ones, and the OK button, fell off screen. Height is
                        // capped so the dialog does not take the whole screen.
                        Column(Modifier.heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())) {
                            Text(t("moisson_choix_desc"), color = TextLo, fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp))
                            if (ui.carnet.profilsListe.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 8.dp)) {
                                    CircularProgressIndicator(color = Cyan,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        ui.carnet.profilsEtat.ifBlank {
                                            t("moisson_choix_cherche")
                                        },
                                        color = TextLo, fontSize = 12.sp)
                                }
                            }
                            ui.carnet.profilsListe.forEach { p ->
                                val pris = p.id in coches
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).toggleable(value = pris, role = Role.Checkbox,
                                        onValueChange = {
                                        if (pris) coches.remove(p.id) else coches.add(p.id)
                                    })) {
                                    Checkbox(checked = pris, onCheckedChange = null)
                                    Text(
                                        listOf(p.carre, p.indicatif, p.nom)
                                            .filter { it.isNotBlank() }
                                            .joinToString("  ·  ").ifBlank { p.id },
                                        color = TextHi, fontSize = 13.sp)
                                }
                            }
                            // "All" is not another checkbox but a shortcut that
                            // ticks the others, so you always see what is sent.
                            if (ui.carnet.profilsListe.isNotEmpty()) {
                                TextButton(onClick = {
                                    coches.clear()
                                    coches.addAll(ui.carnet.profilsListe.map { it.id })
                                }) {
                                    Text(t("moisson_choix_tout"), color = Cyan,
                                        fontSize = 13.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = coches.isNotEmpty(), onClick = {
                            choixProfils = false
                            vm.moissonneCarnet(coches.toList())
                        }) {
                            Text(t("moisson_choix_ok"),
                                color = if (coches.isEmpty()) TextLo else Cyan)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { choixProfils = false }) {
                            Text(t("cancel"), color = TextLo)
                        }
                    })
            }

            val carnetPret = ui.carnet.url.isNotBlank() && ui.carnet.cle.isNotBlank()
            if (!carnetPret) {
                Text(t("express_sans_carnet"), color = Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        // **Choose the source before fetching.** A station often
                        // has several profiles (portable, home, club call);
                        // importing all fills the keyboard with calls worked
                        // elsewhere. If the profile list is empty, it is
                        // fetched when the dialog opens.
                        choixProfils = true
                        if (ui.carnet.profilsListe.isEmpty()) vm.relevProfils()
                    },
                    enabled = !ui.carnet.depotEnCours &&
                        ui.carnet.url.isNotBlank() && ui.carnet.cle.isNotBlank(),
                    modifier = Modifier.weight(1f)) {
                    // Show progress: a harvest takes several seconds.
                    if (ui.carnet.depotEnCours) {
                        CircularProgressIndicator(color = Cyan,
                            strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(t("carnet_moisson_bouton"), color = Cyan, fontSize = 12.sp)
                }
                // Start over: the way out when the index looks incomplete.
                TextButton(onClick = { vm.oublieMoisson() }) {
                    Text("↺", color = TextLo, fontSize = 16.sp)
                }
            }
        }
    }
}

/**
 * The three fine-tuning aids, as independent switches: they do not replace
 * each other. The magnifier shows, the vernier moves, voice lock decides; you
 * may want to see without pushing, or push without seeing on a small screen.
 */
@Composable
private fun AccordFinCard(ui: UiState, vm: MainViewModel) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t("accord_title"), color = TextHi, fontWeight = FontWeight.Bold)
            Text(t("accord_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

            SettingSwitch(t("accord_loupe"), t("accord_loupe_desc"), ui.accord.loupe) {
                vm.setSdrLoupe(it)
            }
            if (ui.accord.loupe) {
                Spacer(Modifier.height(6.dp))
                Text(t("accord_loupe_span"), color = TextHi, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fr.f4ioz.satcombo.domain.AccordFin.LOUPES.forEach { w ->
                        FilterChip(
                            selected = ui.accord.loupeSpanHz == w,
                            onClick = { vm.setSdrLoupeSpan(w) },
                            label = { Text("${w / 1000} kHz", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan, selectedLabelColor = SpaceBg))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SettingSwitch(t("accord_vernier"), t("accord_vernier_desc"), ui.accord.vernier) {
                vm.setSdrVernier(it)
            }
            if (ui.accord.vernier) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fr.f4ioz.satcombo.domain.AccordFin.RAPPORTS.forEach { r ->
                        FilterChip(
                            selected = ui.accord.vernierHzParCm == r,
                            onClick = { vm.setSdrVernierRatio(r) },
                            label = {
                                Text(
                                    if (r >= 1000) "${r / 1000} kHz/cm" else "$r Hz/cm",
                                    fontSize = 11.sp)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan, selectedLabelColor = SpaceBg))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SettingSwitch(t("accord_calage"), t("accord_calage_desc"), ui.accord.calageVoix) {
                vm.setSdrCalageVoix(it)
            }
        }
    }
}

@Composable
private fun SettingSwitch(titre: String, desc: String, coche: Boolean, sur: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().toggleable(value = coche, role = Role.Switch,
            onValueChange = sur)) {
        Column(Modifier.weight(1f)) {
            Text(titre, color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(desc, color = TextLo, fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = coche, onCheckedChange = null)
    }
}

/** One summary row: label on the left, value on the right. */
@Composable
private fun LigneRecap(titre: String, valeur: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(titre, color = TextLo, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(valeur, color = TextHi, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsMenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector,
                            title: String, page: Boolean = false, onClick: () -> Unit) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                .background(Cyan.copy(alpha = 0.14f)), Alignment.Center) {
                Icon(icon, null, tint = Cyan, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(title, color = TextHi, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, modifier = Modifier.weight(1f))
            // A row opening a whole page does not look like one opening a
            // section: the arrow promised settings, and a screen came up.
            if (page) Icon(Icons.Default.OpenInNew, t("ouvre_ecran"), tint = TextLo,
                modifier = Modifier.size(20.dp))
            else Icon(Icons.Default.ChevronRight, null, tint = TextLo)
        }
    }
}

/**
 * Top of a settings section whose feature has its own page: one way there,
 * the same in every section.
 */
@Composable
private fun OuvrirPage(titre: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(tf("ouvrir_page", titre), fontSize = 13.sp)
    }
}

/** Preset swatches for the compass colour pickers (readable in dark & light). */
private val COMPASS_PALETTE: List<Int> = listOf(
    0xFFFF6BA9, 0xFFD6336C, 0xFFF06595, 0xFFFF922B, 0xFFF59F00, 0xFFFFD43B,
    0xFF51CF66, 0xFF2FB344, 0xFF20C997, 0xFF38E1D4, 0xFF4DABF7, 0xFF6C8BFF,
    0xFFB197FC, 0xFFFFFFFF, 0xFFCED4DA
).map { it.toInt() }

@Composable
private fun ColorSwatches(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COMPASS_PALETTE.forEach { argb ->
            val isSel = argb == selected
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(argb))
                .then(if (isSel) Modifier.border(3.dp, TextHi, CircleShape)
                      else Modifier.border(1.dp, TextLo.copy(alpha = 0.4f), CircleShape))
                .clickable { onPick(argb) })
        }
    }
}

@Composable
private fun ColorGroupCard(title: String, rows: List<Triple<String, Int, String>>, vm: MainViewModel) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = TextHi, fontWeight = FontWeight.Bold)
            rows.forEach { (label, sel, key) ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(sel)))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = TextLo, fontSize = 12.sp)
                }
                Spacer(Modifier.height(5.dp))
                ColorSwatches(sel) { vm.setCompassColor(key, it) }
            }
        }
    }
}

/**
 * Recordings manager: export-folder picker (SAF), then the list of pass
 * recordings (newest first) with their QSO markers from the sidecar .txt —
 * play / share / delete on each. Markers show mm:ss offsets into the audio so
 * a logged QSO is easy to find at replay time.
 */
@Composable
private fun RecordingsSection(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val dir = remember { java.io.File(ctx.getExternalFilesDir(null), "recordings") }
    val files = remember(refresh, ui.recording) {
        (dir.listFiles { f -> f.name.endsWith(".mp3") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.setRecordingsTree(uri.toString())
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // --- export folder ---
        Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("rec_folder"), color = TextHi, fontWeight = FontWeight.Bold)
                Text(
                    if (ui.recordingsTreeUri.isBlank()) t("rec_folder_none")
                    else android.net.Uri.parse(ui.recordingsTreeUri).lastPathSegment
                        ?.substringAfterLast(':')?.ifBlank { null } ?: ui.recordingsTreeUri,
                    color = if (ui.recordingsTreeUri.isBlank()) TextLo else Aurora,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Text(t("rec_folder_desc"), color = TextLo, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }) {
                        Text(t("rec_folder_pick"), color = Cyan, fontSize = 13.sp)
                    }
                    if (ui.recordingsTreeUri.isNotBlank()) {
                        OutlinedButton(onClick = { vm.setRecordingsTree("") }) {
                            Text(t("rec_folder_clear"), color = TextLo, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // --- recordings list ---
        if (files.isEmpty()) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(t("rec_none"), color = TextLo, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            }
        }
        // One player for the whole list: opening a file closes the previous
        // one. Released with the screen.
        var joue by remember { mutableStateOf("") }          // path of the open file
        var enLecture by remember { mutableStateOf(false) }
        var positionMs by remember { mutableStateOf(0) }
        var dureeMs by remember { mutableStateOf(0) }
        val lecteur = remember { android.media.MediaPlayer() }
        DisposableEffect(Unit) { onDispose { runCatching { lecteur.release() } } }
        LaunchedEffect(joue, enLecture) {
            while (enLecture && joue.isNotEmpty()) {
                positionMs = runCatching { lecteur.currentPosition }.getOrDefault(0)
                if (runCatching { !lecteur.isPlaying }.getOrDefault(true)) enLecture = false
                kotlinx.coroutines.delay(200)
            }
        }
        fun ouvre(chemin: String, versMs: Int = 0) {
            runCatching {
                if (joue != chemin) {
                    lecteur.reset()
                    lecteur.setDataSource(chemin)
                    lecteur.prepare()
                    joue = chemin
                    dureeMs = lecteur.duration
                }
                // Start two seconds before the marker: it stamps the start of
                // the exchange, and you want to hear the call, not land on it.
                lecteur.seekTo((versMs - 2000).coerceAtLeast(0))
                lecteur.start()
                enLecture = true
            }
        }

        // One launcher for the whole list: the file to write travels with the
        // call, not the launcher.
        val enregistreRec = rememberEnregistrer()
        files.forEach { f ->
            val sidecar = java.io.File(f.parentFile, f.name.removeSuffix(".mp3") + ".txt")
            val markerLines = remember(f.path, refresh) {
                if (sidecar.exists())
                    runCatching { sidecar.readLines().drop(4).filter { it.isNotBlank() } }
                        .getOrDefault(emptyList())
                else emptyList()
            }
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.name, color = TextHi, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("${"%.1f".format(f.length() / 1_048_576.0)} ${t("size_mb")} · " +
                        tzFormat("EEE dd/MM HH:mm", ui.useUtc)
                            .format(java.util.Date(f.lastModified())) + " ${tzTag(ui.useUtc)}",
                        color = TextLo, fontSize = 11.sp)
                    if (markerLines.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Surface(color = SpaceBg, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                Text(t("rec_markers"), color = Amber, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                markerLines.take(12).forEach { line ->
                                    // Leading "MM:SS (…)" is the offset in the
                                    // file, hence the seek target.
                                    val versMs = Regex("^(\\d+):(\\d\\d)")
                                        .find(line.trim())?.let {
                                            (it.groupValues[1].toInt() * 60 +
                                             it.groupValues[2].toInt()) * 1000
                                        }
                                    Text(line,
                                        color = if (versMs != null) Cyan else TextHi,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(top = 2.dp)
                                            .then(if (versMs != null)
                                                Modifier.clickable { ouvre(f.path, versMs) }
                                            else Modifier))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Playback bar, only under the open file.
                    if (joue == f.path) {
                        Slider(
                            value = positionMs.toFloat(),
                            onValueChange = {
                                positionMs = it.toInt()
                                runCatching { lecteur.seekTo(it.toInt()) }
                            },
                            valueRange = 0f..dureeMs.coerceAtLeast(1).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Cyan, activeTrackColor = Cyan,
                                inactiveTrackColor = SpaceBg))
                        Text("%d:%02d / %d:%02d".format(
                            positionMs / 60000, positionMs / 1000 % 60,
                            dureeMs / 60000, dureeMs / 1000 % 60),
                            color = TextLo, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    Row {
                        TextButton(onClick = {
                            if (joue == f.path && enLecture) {
                                runCatching { lecteur.pause() }; enLecture = false
                            } else ouvre(f.path, if (joue == f.path) positionMs + 2000 else 0)
                        }) {
                            Text(if (joue == f.path && enLecture) "⏸ " + t("rec_pause")
                                 else "▶ " + t("rec_play"),
                                color = Cyan, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                ctx, "${ctx.packageName}.fileprovider", f)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "audio/mpeg"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                if (markerLines.isNotEmpty())
                                    putExtra(android.content.Intent.EXTRA_TEXT, markerLines.joinToString("\n"))
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(send, f.name))
                        }) { Text(t("rec_share"), color = Aurora, fontSize = 12.sp) }
                        TextButton(onClick = {
                            enregistreRec(f.name, "audio/mpeg", depuisFichier(f))
                        }) { Text(t("export_save"), color = Cyan, fontSize = 12.sp) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            f.delete(); sidecar.delete(); refresh++
                        }) { Text(t("rec_delete"), color = Magenta, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

/** Small thematic header between groups of settings rows. */
@Composable
private fun MenuGroupLabel(text: String) {
    Text(text, color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)
            .semantics { heading() })
}

private fun settingsMenuTitle(id: String): String = when (id) {
    "look" -> t("menu_look"); "qth" -> t("menu_qth"); "time" -> t("menu_time")
    "sources" -> t("menu_sources"); "aim" -> t("menu_aim"); "notif" -> t("menu_notif")
    "colors" -> t("menu_colors"); "recordings" -> t("menu_recordings")
    "skeds" -> t("menu_skeds"); "pota" -> t("menu_pota"); "log" -> t("menu_log")
    "cat" -> t("menu_cat"); "conv" -> t("menu_conv")
    "backup" -> t("menu_backup"); "pdf" -> t("menu_pdf")
    "mire" -> t("menu_mire"); "accord" -> t("menu_accord"); "macro" -> t("menu_macro")
    "partage" -> t("menu_partage")
    "express" -> t("menu_express"); "gps" -> t("menu_gps")
    "sondemire" -> t("menu_sondemire")
    "docs" -> t("menu_docs"); "about" -> t("menu_about"); else -> ""
}

/** In-app user guide: short chapters, fully localized via i18n. */
@Composable
private fun DocsContent() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("docs_intro"), color = TextLo, fontSize = 12.sp)
        // The pass-screen help used to hide behind a "?" in the crowded header
        // of the pass page; it is a chapter like any other, built from the very
        // same strings so there is a single source of truth.
        val passHelp = t("help_polar") + " · " + t("help_polar_desc") + t("dashed_future") +
            "\n\n" + t("help_corners") + " · " + t("help_corners_desc") + t("az_el_current") +
            "\n\n" + t("help_log_title") + " · " + t("help_log_desc") +
            "\n\n" + t("aim_mode_title") + " · " + t("help_aim_desc")
        val wiring = t("doc_wiring_intro") +
            "\n\n" + t("doc_wiring_mic_t") + "\n" + t("doc_wiring_mic") +
            "\n\n" + t("doc_wiring_bt_t") + "\n" + t("doc_wiring_bt") +
            "\n\n" + t("doc_wiring_usb_t") + "\n" + t("doc_wiring_usb") +
            "\n\n" + t("doc_wiring_level_t") + "\n" + t("doc_wiring_level") +
            "\n\n" + t("doc_wiring_sdr_t") + "\n" + t("doc_wiring_sdr")
        val chapters = buildList {
            add(Triple("🛰", t("doc_passes_t"), t("doc_passes_b")))
            add(Triple("❓", t("help_pass"), passHelp))
            listOf(
                "doc_aim" to "🧭", "doc_freq" to "📻",
                "doc_notif" to "🔔", "doc_skeds" to "🤝", "doc_grid" to "▦",
                "doc_pdf" to "🖨", "doc_sstv" to "🖼", "doc_sdr" to "📡"
            ).forEach { (key, emoji) -> add(Triple(emoji, t(key + "_t"), t(key + "_b"))) }
            // Audio wiring is the most frequent question: it comes right after
            // the SSTV and SDR chapters, where you wonder how to get sound in.
            add(Triple("🎚", t("doc_wiring_title"), wiring))
            add(Triple("💾", t("doc_backup_t"), t("doc_backup_b")))
        }
        chapters.forEach { (emoji, title, body) ->
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(emoji, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(title, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(body, color = TextLo, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Converters: one card, two instances.
//
// Downlink and uplink are set the same way (LO, direction, range); writing it
// twice invites divergence. Only the title, the sample figure at the bottom
// and the two routing switches (RX only) differ.
// ----------------------------------------------------------------------

/** Hertz as readable megahertz, without trailing zeros. */
private fun convMhz(hz: Long): String {
    val s = "%.6f".format(java.util.Locale.US, hz / 1_000_000.0)
    return s.trimEnd('0').trimEnd('.') + " MHz"
}

/**
 * A frequency field in MHz.
 *
 * Text state is deliberately local, not derived from the written value:
 * otherwise typing into "9750" rebuilds the field on every keystroke. Same
 * trap as the CI-V address and the callsign.
 */
@Composable
private fun ConvChampMhz(label: String, valeurHz: Long, largeur: Dp, onHz: (Long) -> Unit) {
    var txt by remember { mutableStateOf(if (valeurHz == 0L) "" else convMhz(valeurHz).removeSuffix(" MHz")) }
    OutlinedTextField(value = txt, onValueChange = { brut ->
        txt = brut.replace(',', '.').filter { it.isDigit() || it == '.' }.take(14)
        val v = txt.toDoubleOrNull()
        onHz(if (v == null) 0L else Math.round(v * 1_000_000.0))
    }, singleLine = true, label = { Text(label, fontSize = 11.sp) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
            focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
        modifier = Modifier.width(largeur))
}

/**
 * A converter, read-only.
 *
 * When off, show "none" rather than zero: a zero LO means no converter, and
 * the figure would look like a setting.
 */
@Composable
private fun LigneConv(titre: String, c: fr.f4ioz.satcombo.domain.Convertisseur) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(titre, color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            if (c.configure) "%.6f MHz".format(c.olHz / 1e6) else t("conv_aucun"),
            color = if (c.configure) TextHi else TextLo,
            fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ConvCard(ui: UiState, vm: MainViewModel, descente: Boolean) {
    val c = if (descente) ui.convRx else ui.convTx
    val essaiHz = if (descente) fr.f4ioz.satcombo.domain.Convertisseur.BALISE_MEDIANE_HZ
                  else 2_400_150_000L
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.toggleable(value = c.actif, role = Role.Switch,
                    onValueChange = { if (descente) vm.setConvRxActif(it) else vm.setConvTxActif(it) }), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t(if (descente) "conv_rx_title" else "conv_tx_title"),
                        color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t(if (descente) "conv_rx_desc" else "conv_tx_desc"),
                        color = TextLo, fontSize = 11.sp)
                }
                Switch(checked = c.actif,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
            }

            if (c.actif) {
                Spacer(Modifier.height(10.dp))
                ConvChampMhz(t("conv_ol"), c.olHz, 170.dp) {
                    if (descente) vm.setConvRxOl(it) else vm.setConvTxOl(it)
                }

                Spacer(Modifier.height(8.dp))
                Text(t("conv_range"), color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(t("conv_range_desc"), color = TextLo, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ConvChampMhz(t("conv_low"), c.basHz, 150.dp) { v ->
                        if (descente) vm.setConvRxPlage(v, c.hautHz) else vm.setConvTxPlage(v, c.hautHz)
                    }
                    ConvChampMhz(t("conv_high"), c.hautHz, 150.dp) { v ->
                        if (descente) vm.setConvRxPlage(c.basHz, v) else vm.setConvTxPlage(c.basHz, v)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.toggleable(value = c.inverseur, role = Role.Switch,
                        onValueChange = {
                            if (descente) vm.setConvRxInverseur(it) else vm.setConvTxInverseur(it)
                        }), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("conv_invert"), color = TextHi, fontSize = 13.sp)
                        Text(t("conv_invert_desc"), color = TextLo, fontSize = 11.sp)
                    }
                    Switch(checked = c.inverseur,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                }

                if (descente) {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.toggleable(value = ui.convRxPoste, role = Role.Switch,
                            onValueChange = vm::setConvRxPoste), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("conv_to_rig"), color = TextHi, fontSize = 13.sp)
                            Text(t("conv_to_rig_desc"), color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = ui.convRxPoste, onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                    }
                    Row(modifier = Modifier.toggleable(value = ui.convRxCle, role = Role.Switch,
                            onValueChange = vm::setConvRxCle), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("conv_to_sdr"), color = TextHi, fontSize = 13.sp)
                            Text(t("conv_to_sdr_desc"), color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = ui.convRxCle, onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                    }
                }

                // Sanity check: what a known frequency becomes through the box.
                Spacer(Modifier.height(10.dp))
                Surface(color = SpaceBg, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(t("conv_check"), color = TextLo, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        val fi = c.versPoste(essaiHz)
                        Text(tf(if (descente) "conv_check_rx" else "conv_check_tx",
                                if (c.couvre(essaiHz)) convMhz(fi) else t("conv_check_off")),
                            color = TextHi, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 3.dp))
                        if (c.couvre(essaiHz)) {
                            val bande = fr.f4ioz.satcombo.cat.BandPlan.band(fi)
                            Text(
                                if (bande == fr.f4ioz.satcombo.cat.BandPlan.Band.AUTRE)
                                    t("conv_rig_unreach") else tf("conv_rig_reach", bande.name),
                                color = TextLo, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSats(ui: UiState, vm: MainViewModel) {
    // Silent satellites hidden unless asked for; a followed one always stays,
    // since the operator chose it.
    val inactifs by vm.satInactifs.collectAsState()
    val montreInactifs by vm.montreInactifs.collectAsState()
    val caches = if (montreInactifs) 0 else
        ui.filteredSatellites.count { it.catalogNumber in inactifs && it.catalogNumber !in ui.favorites }
    val liste = if (montreInactifs) ui.filteredSatellites else
        ui.filteredSatellites.filter { it.catalogNumber !in inactifs || it.catalogNumber in ui.favorites }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = ui.query, onValueChange = vm::setQuery,
            placeholder = { Text(t("search_hint"), color = TextLo) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = TextLo) },
            trailingIcon = {
                if (ui.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) {
                    Icon(Icons.Default.Close, t("clear"), tint = TextLo)
                }
            },
            singleLine = true, shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
            Text("⭐ " + tf("n_followed", ui.favorites.size), color = TextLo, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            FilterChip(selected = ui.satActiveOnly, onClick = { vm.setSatActiveOnly(!ui.satActiveOnly) },
                label = { Text(t("active_today"), fontSize = 11.sp) },
                leadingIcon = {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF49D17F)))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF49D17F).copy(alpha = 0.20f),
                    selectedLabelColor = Color(0xFF49D17F)))
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { vm.refreshAmsatStatus(force = true) }) {
                Icon(Icons.Default.Refresh, t("refresh_amsat"), tint = Cyan,
                    modifier = Modifier.size(18.dp))
            }
        }
        if (ui.satActiveOnly && ui.amsatReports.isEmpty()) {
            Text(t("amsat_not_loaded"), color = Amber, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(liste, key = { it.catalogNumber }) { sat ->
                SatRow(sat, isFav = sat.catalogNumber in ui.favorites,
                    amsatStatus = if (ui.statusSource != "SATNOGS") vm.amsatFor(sat.name)?.recent else null,
                    agenda = vm.agendaForSat(sat.name)) {
                    vm.toggleFavorite(sat.catalogNumber)
                }
            }
            if (liste.isEmpty()) {
                item { Text(
                    if (ui.satActiveOnly) t("no_active_sats")
                    else tf("no_sat_for_query", ui.query),
                    color = TextLo, modifier = Modifier.padding(16.dp)) }
            }
            // At the end of the list, where one looks for the missing one.
            if (caches > 0 || montreInactifs && inactifs.isNotEmpty()) {
                item {
                    TextButton(onClick = { vm.setMontreInactifs(!montreInactifs) },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (montreInactifs) t("inactifs_masquer")
                             else tf("inactifs_montrer", caches),
                            color = Cyan, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Parses "YYYY-MM-DD HH:MM" into an instant.
 *
 * Typed format rather than a date picker: when copying a paper log, typing is
 * faster than two dialogs.
 */
private fun dateHeureVersMs(txt: String, useUtc: Boolean): Long? = runCatching {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
    if (useUtc) f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    f.isLenient = false
    f.parse(txt.trim())?.time
}.getOrNull()

@Composable
private fun SectionHeader(text: String) {
    // A heading for TalkBack: it can jump from one to the next.
    Text(text, color = TextLo, fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp, fontSize = 12.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() })
}

@Composable
private fun SatRow(sat: TleEntry, isFav: Boolean,
                  amsatStatus: fr.f4ioz.satcombo.data.AmsatStatus? = null,
                  agenda: fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent? = null,
                  onFav: () -> Unit) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .clickable { onFav() }, verticalAlignment = Alignment.CenterVertically) {
            amsatStatus?.let { st ->
                val c = when (st) {
                    fr.f4ioz.satcombo.data.AmsatStatus.ACTIVE -> Color(0xFF49D17F)
                    fr.f4ioz.satcombo.data.AmsatStatus.BEACON -> Amber
                    fr.f4ioz.satcombo.data.AmsatStatus.NOT_HEARD -> Magenta
                    fr.f4ioz.satcombo.data.AmsatStatus.CONFLICT -> Color(0xFFFE6100)
                    else -> null
                }
                if (c != null) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(10.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(sat.name, color = TextHi, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                val sub = sat.downlinkHz?.let { "↓ " + Doppler.formatMHzShort(it) } ?: "NORAD #${sat.catalogNumber}"
                Text(listOfNotNull(sub, sat.mode).joinToString("  ·  "),
                    color = TextLo, fontSize = 12.sp)
                // An upcoming sked shows in the list itself: that is where you
                // choose what to follow, not inside the detail.
                agenda?.let { ev ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)) {
                        Icon(Icons.Default.EventNote, t("agenda_on_pass_cd"), tint = Magenta,
                            modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(listOfNotNull(ev.kind.ifBlank { null }, ev.title)
                            .joinToString("  \u00B7  "),
                            color = Magenta, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            IconButton(onClick = onFav) {
                if (isFav) Icon(Icons.Default.Star, t("followed_cd"), tint = Amber)
                else Icon(Icons.Outlined.StarBorder, t("follow_cd"), tint = TextLo)
            }
        }
    }
}

// ---------- pass card ----------

/** Readable name of a dial target. */
private fun nomCible(id: String): String = when (id) {
    "SHIFT_RX" -> t("macro_rx")
    "SHIFT_TX" -> t("macro_tx")
    else -> t("macro_vfo")
}
