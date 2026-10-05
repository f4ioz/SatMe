/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

// Satellite detail screen & dialogs — extracted from SatComboApp.kt.

@Composable
internal fun DetailScreen(ui: UiState, vm: MainViewModel, onBack: () -> Unit) {
    val sat = ui.selected ?: return
    // AMSAT report dialog, opened from the pass row.
    var statusOpen by remember { mutableStateOf(false) }
    // Keep the screen awake while tracking (field use with gloves/antenna).
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    if (ui.showSatConfig) SatConfigDialog(ui, vm, sat)

    val ctxDetail = LocalContext.current
    var aimFullscreen by remember { mutableStateOf(false) }
    val declFs = remember(ui.observer) {
        ui.observer?.let {
            android.hardware.GeomagneticField(
                it.latDeg.toFloat(), it.lonDeg.toFloat(),
                it.altMeters.toFloat(), System.currentTimeMillis()
            ).declination
        } ?: 0f
    }
    val liveFs = ui.livePosition
    val aboveFs = (liveFs?.elevationDeg ?: -1.0) >= 0
    if (aimFullscreen) {
        // Full-screen aiming view: just the compass, big, for field use.
        Box(Modifier.fillMaxSize().background(SpaceBg)) {
            Column(Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sat.name, fontWeight = FontWeight.Black, color = TextHi, fontSize = 22.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { aimFullscreen = false }) {
                        Icon(Icons.Default.Close, t("exit_fullscreen"), tint = Cyan)
                    }
                }
                Spacer(Modifier.height(8.dp))
                CompassAim(liveFs, ui.passTrack, ui.trail, declFs, ui.aimMode, vm::setAimMode,
                    onSaisie = { vm.ouvreSaisie() },
                    logTaps = ui.logTaps,
                    compact = true, showModeChips = ui.showAimModeChips, headUp = ui.compassHeadUp, needleStyle = ui.compassStyle == "NEEDLE",
                    cornerTL = CoinsAim.coin(
                        if (ui.rotorConnected) ui.rotorAimAz else null,
                        liveFs?.azimuthDeg, aboveFs, t("az_mast_label"), "AZ")
                        .let { it.libelle to it.valeur },
                    cornerTR = CoinsAim.coin(
                        if (ui.rotorConnected) ui.rotorAimEl else null,
                        liveFs?.elevationDeg, aboveFs, t("el_mast_label"), t("el_label"))
                        .let { it.libelle to it.valeur },
                    traceColor = Color(ui.compassTraceColor), traceWidth = ui.compassTraceWidth,
                    needleFar = Color(ui.needleFarColor), needleNear = Color(ui.needleNearColor),
                    needleClose = Color(ui.needleCloseColor),
                    bubbleFar = Color(ui.bubbleFarColor), bubbleNear = Color(ui.bubbleNearColor),
                    bubbleClose = Color(ui.bubbleCloseColor),
                    ringAzNear = Color(ui.ringAzNearColor), ringAzClose = Color(ui.ringAzCloseColor),
                    ringElNear = Color(ui.ringElNearColor), ringElClose = Color(ui.ringElCloseColor),
                    rotorAzDeg = if (ui.rotorConnected) ui.rotorAimAz else null,
                    rotorElDeg = if (ui.rotorConnected) ui.rotorAimEl else null,
                    modifier = Modifier.fillMaxWidth().weight(1f))
                if (aboveFs) {
                    Text(tf("az_el_big", liveFs!!.azimuthDeg.toInt(), liveFs.elevationDeg.toInt()),
                        color = TextHi, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        modifier = Modifier.padding(8.dp))
                } else {
                    Text(t("below_horizon"), color = TextLo, fontSize = 16.sp,
                        modifier = Modifier.padding(8.dp))
                }
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        // Padlock in the top bar: freeze the scroll where the user put it
        // (taps, triple-tap logging and buttons keep working).
        userScrollEnabled = !ui.uiLocked
    ) {
        // Name, badges, favourite and photo live in the top bar
        // (EnteteSatellite): one header instead of two stacked ones, and the
        // frequencies come up into the first screen.

        // Sked marker, high on the page. The full sked card sits far below (map,
        // telemetry, log...) and the user could not tell a sked was announced
        // without scrolling. Cheap fields only here (workableStart/End as sent by
        // hams.at) — the expensive mutual-window solver stays in the card below.
        if (ui.skedsEnabled) {
            val shownPass = ui.focusedPassAos?.let { f -> ui.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
                ?: ui.passes.firstOrNull { it.losEpochMs > ui.nowMs }
            val topSkeds = if (shownPass != null)
                vm.skedsForPass(sat.catalogNumber, shownPass.aosEpochMs, shownPass.losEpochMs)
            else vm.skedsFor(sat.catalogNumber)
            if (topSkeds.isNotEmpty()) {
                item {
                    // Tapping opens the mutual sked pre-filled from the
                    // announcement: nothing to retype.
                    Surface(color = Amber.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                            .clickable { vm.openSked(topSkeds.first()) }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (topSkeds.size == 1) t("sked_top_banner")
                                 else tf("sked_top_banner_n", topSkeds.size),
                                color = Amber, fontWeight = FontWeight.Black,
                                fontSize = 11.sp, letterSpacing = 1.sp)
                            Spacer(Modifier.width(10.dp))
                            val hmb = tzFormat("HH:mm", ui.useUtc)
                            Column(Modifier.weight(1f)) {
                                topSkeds.take(2).forEach { s ->
                                    val ws = s.workableStartMs
                                    val we = s.workableEndMs
                                    val win = if (ws != null && we != null)
                                        "${hmb.format(Date(ws))}\u2013${hmb.format(Date(we))} ${tzTag(ui.useUtc)}"
                                    else "${hmb.format(Date(s.aosMs))}\u2013${hmb.format(Date(s.losMs))} ${tzTag(ui.useUtc)}"
                                    // The other station's grid square belongs
                                    // here too: it is what you look for in an
                                    // announcement (where = azimuth and
                                    // distance), and this banner exists to
                                    // spare the scroll to the card below.
                                    val carre = s.grids.firstOrNull { it.isNotBlank() }
                                    Text("${s.callsign}  \u00B7  $win" +
                                        (s.mode?.let { "  \u00B7  $it" } ?: "") +
                                        (carre?.let { "  \u00B7  $it" } ?: ""),
                                        color = TextHi, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                }
                                if (topSkeds.size > 2)
                                    Text("+${topSkeds.size - 2}", color = Amber, fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(t("sked_scroll_hint"), color = Amber.copy(alpha = 0.8f), fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // Agenda banner only for the pass shown on the page: announcing an SSTV
        // event over the wrong pass makes you miss the real one.
        val bannerPass = ui.focusedPassAos?.let { f -> ui.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
            ?: ui.passes.firstOrNull { it.losEpochMs > ui.nowMs }
        bannerPass?.let { bp -> vm.agendaForPass(bp) }?.let { ev ->
            item {
                val live = ev.activeAt(ui.nowMs, 0L)
                Surface(color = Magenta.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().clickable { vm.openAgenda() }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventNote, null, tint = Magenta,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (ev.kind.isNotBlank()) {
                                    Text(ev.kind, color = Cyan, fontSize = 10.sp,
                                        fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(ev.title, color = TextHi, fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp)
                                if (live) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(t("agenda_live"), color = Aurora, fontSize = 9.sp,
                                        fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                }
                            }
                            Text(agendaWhen(ev, ui.useUtc), color = TextLo, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        if (ev.freqHz > 0L) {
                            Spacer(Modifier.width(6.dp))
                            Text("\u2193 " + agendaFreqLabel(ev.freqHz), color = Amber,
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val shownPass = remember(ui.focusedPassAos, ui.passes, ui.nowMs) {
                        ui.focusedPassAos?.let { f -> ui.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
                            ?: ui.passes.firstOrNull { it.losEpochMs > ui.nowMs }
                    }
                    val hm = tzFormat("HH:mm:ss", ui.useUtc)
                    // One line: day, AOS→LOS, time zone, calendar, small AMSAT
                    // globe. A full-width button used to push the compass
                    // off-screen on small phones. On a stationary sat only the
                    // globe remains. Too narrow a phone wraps the date rather
                    // than cutting a time.
                    if (shownPass != null || ui.callsign.isNotBlank())
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (shownPass != null) {
                            val dfull = tzFormat("EEE dd MMM", ui.useUtc)
                            // Minutes only: with the calendar icon, the seconds
                            // pushed "LOC" onto a second line on a phone. The
                            // countdown and the pass list keep them.
                            val hmCourt = tzFormat("HH:mm", ui.useUtc)
                            var taille by remember(shownPass.aosEpochMs) { mutableStateOf(14.sp) }
                            Text("${dfull.format(Date(shownPass.aosEpochMs)).replaceFirstChar { it.uppercase() }} · " +
                                    "${hmCourt.format(Date(shownPass.aosEpochMs))} → ${hmCourt.format(Date(shownPass.losEpochMs))} ${tzTag(ui.useUtc)}",
                                color = TextHi, fontWeight = FontWeight.Bold, fontSize = taille,
                                maxLines = 1, softWrap = false,
                                // Too long for the row (large display size in
                                // Appearance): a step smaller, down to 11 sp,
                                // rather than "LOC" alone on a second line.
                                onTextLayout = { if (it.hasVisualOverflow && taille > 11.sp) taille *= 0.93f },
                                modifier = Modifier.weight(1f, fill = false))
                            // The phone's calendar, pre-filled: SatMe writes
                            // nothing, the operator saves there.
                            IconButton(onClick = {
                                if (!fr.f4ioz.satcombo.data.AgendaTelephone.ouvre(ctxDetail, shownPass, vm.myLocator()))
                                    android.widget.Toast.makeText(ctxDetail, t("cal_aucun"),
                                        android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.EventAvailable, t("cal_ajouter"), tint = Cyan,
                                    modifier = Modifier.size(21.dp))
                            }
                            // ⚑ During the pass: a moment marked for the journal (heard, not logged).
                            if (ui.nowMs in (shownPass.aosEpochMs - 120_000L)..(shownPass.losEpochMs + 120_000L)) BoutonSignet(ui, vm)
                        }
                        // AMSAT rejects anonymous reports: no callsign, no button.
                        if (ui.callsign.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = { vm.clearAmsatSubmitState(); statusOpen = true }
                            ) {
                                // Untinted: the logo is red and white.
                                Icon(
                                    painterResource(fr.f4ioz.satcombo.R.drawable.ic_amsat),
                                    contentDescription = t("status_btn"),
                                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                                    modifier = Modifier.size(21.dp))
                            }
                        }
                    }
                    if (statusOpen) {
                        AmsatStatusDialog(ui, sat.name,
                            onSend = { heard -> vm.submitAmsatStatus(sat.name, heard, sat.catalogNumber) },
                            onDismiss = { statusOpen = false; vm.clearAmsatSubmitState() })
                    }
                    val decl = remember(ui.observer) {
                        ui.observer?.let {
                            android.hardware.GeomagneticField(
                                it.latDeg.toFloat(), it.lonDeg.toFloat(),
                                it.altMeters.toFloat(), System.currentTimeMillis()
                            ).declination
                        } ?: 0f
                    }
                    // The edit dialog lives in the root Box (SatComboApp): one
                    // instance, visible from every screen.
                    // Corner telemetry: time left, max el, current az, current el.
                    val live = ui.livePosition
                    val above = live != null && live.elevationDeg >= 0
                    val timeLeft = shownPass?.let {
                        if (ui.nowMs in it.aosEpochMs..it.losEpochMs) "LOS " + fmtCountdown(it.losEpochMs - ui.nowMs)
                        else if (it.aosEpochMs > ui.nowMs) "AOS " + fmtCountdown(it.aosEpochMs - ui.nowMs)
                        else "—"
                    } ?: "—"
                    // With a rotor connected, the corners show the mast's read
                    // position, even below the horizon (otherwise just "—").
                    // The label changes too, so a mast figure is never taken
                    // for the satellite's; it reverts to "AZ" when the rotor
                    // goes silent.
                    val coinAz = CoinsAim.coin(
                        if (ui.rotorConnected) ui.rotorAimAz else null,
                        live?.azimuthDeg, above, t("az_mast_label"), "AZ")
                    val coinEl = CoinsAim.coin(
                        if (ui.rotorConnected) ui.rotorAimEl else null,
                        live?.elevationDeg, above, t("el_mast_label"), t("el_label"))
                    Spacer(Modifier.height(2.dp))
                    // **On a stationary satellite, the compass collapses.**
                    // Once the dish is aimed at Es'hail-2 it teaches nothing and
                    // eats the space the frequencies need. Decided from the TLE,
                    // not a list of catalog numbers, so it covers every
                    // geostationary sat. Collapsed, not removed: one tap reopens
                    // it, per satellite (needed again when the station moves).
                    var boussoleOuverte by rememberSaveable(sat.catalogNumber) {
                        mutableStateOf(!sat.estImmobile)
                    }
                    if (sat.estImmobile) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { boussoleOuverte = !boussoleOuverte }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(t("boussole_fixe"), color = TextLo, fontSize = 11.sp,
                                modifier = Modifier.weight(1f))
                            Icon(
                                if (boussoleOuverte) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                null, tint = TextLo, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (boussoleOuverte)
                    CompassAim(ui.livePosition, ui.passTrack, ui.trail, decl, ui.aimMode,
                        vm::setAimMode,
                        // **The gesture opens the keypad, it no longer logs.**
                        // It used to log callsign-less contacts, which leaked
                        // into the ADIF export. A contact without a callsign is
                        // a lost callsign, not half a contact.
                        onSaisie = { vm.ouvreSaisie() },
                        logTaps = ui.logTaps,
                        compact = true,
                        showModeChips = ui.showAimModeChips,
                        headUp = ui.compassHeadUp, needleStyle = ui.compassStyle == "NEEDLE",
                        cornerTL = t("time") to timeLeft,
                        cornerTR = t("el_max") to (shownPass?.let { "${it.maxElevationDeg.toInt()}°" } ?: "—"),
                        cornerBL = coinAz.libelle to coinAz.valeur,
                        cornerBR = coinEl.libelle to coinEl.valeur,
                        traceColor = Color(ui.compassTraceColor), traceWidth = ui.compassTraceWidth,
                        needleFar = Color(ui.needleFarColor), needleNear = Color(ui.needleNearColor),
                        needleClose = Color(ui.needleCloseColor),
                        bubbleFar = Color(ui.bubbleFarColor), bubbleNear = Color(ui.bubbleNearColor),
                        bubbleClose = Color(ui.bubbleCloseColor),
                        ringAzNear = Color(ui.ringAzNearColor), ringAzClose = Color(ui.ringAzCloseColor),
                        ringElNear = Color(ui.ringElNearColor), ringElClose = Color(ui.ringElCloseColor),
                        rotorAzDeg = if (ui.rotorConnected) ui.rotorAimAz else null,
                        rotorElDeg = if (ui.rotorConnected) ui.rotorAimEl else null,
                        modifier = Modifier.fillMaxWidth())
                    // The compass tap is the keypad's only entry on this
                    // screen, so a collapsed compass gets a button instead.
                    if (!boussoleOuverte) {
                        OutlinedButton(
                            onClick = { vm.ouvreSaisie() },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(t("entry_title"), color = Cyan, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Right under the compass: incoming SSTV image, then the
                    // SDR dongle, so you receive and aim on the same screen.
                    RxImageInline(ui, vm)
                    MoniteurAudioCard(ui, vm)
                    // **Say why the waterfall is missing.** The SDR panel hides
                    // silently when the extension is off. Fine in general, but
                    // an operator who chose a rig + SDR dongle expects a
                    // waterfall and would hunt for a fault. Only in that case.
                    val recoitParCle = fr.f4ioz.satcombo.cat.Postes.emetSeul(ui.rigModel)
                    val sdrOuvert = fr.f4ioz.satcombo.data.Extensions.SDR in ui.extensions
                    if (sdrOuvert) SdrInline(ui, vm)
                    if (recoitParCle && !sdrOuvert) {
                        Text(t("sdr_ext_fermee"), color = Amber, fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                    // Frequencies up/down right under the plot.
                    if (ui.catConnected && (ui.catRadioDownlinkHz != null ||
                            ui.catRadioUplinkHz != null || ui.dopplerHold)) {
                        Surface(color = Cyan.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = Cyan.copy(alpha = 0.20f), shape = RoundedCornerShape(6.dp)) {
                                    Text(t("rig_panel"), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("↓ RX " + (ui.catRadioDownlinkHz?.let { "%.4f MHz".format(it/1e6) } ?: "—"),
                                        color = TextHi, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold)
                                    Text("↑ TX " + (ui.catRadioUplinkHz?.let { "%.4f MHz".format(it/1e6) } ?: "—"),
                                        color = Aurora, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold)
                                    // Who owns the VFO knob: app-driven or manual.
                                    Text(if (ui.catRxDriven) t("cat_rx_driven") else t("cat_rx_manual"),
                                        color = if (ui.catRxDriven) Cyan else TextLo, fontSize = 10.sp)
                                    // The mode read back from the rig, not the one
                                    // we think we set: that is what matters when
                                    // the other station turns unintelligible.
                                    if (ui.catRadioMode != null) {
                                        Text(tf("cat_radio_mode", ui.catRadioMode) +
                                            (if (ui.catModeMismatch) " " + t("cat_mode_fixed") else ""),
                                            color = if (ui.catModeMismatch) Amber else TextLo,
                                            fontSize = 10.sp)
                                    }
                                    if (ui.dopplerHold) {
                                        Text(t("doppler_hold_on"), color = Amber, fontSize = 10.sp)
                                    }
                                }
                                // Doppler hold: computation and display go on,
                                // only writes to the rig stop, so you can take
                                // the knob without losing pass tracking.
                                IconButton(onClick = { vm.toggleDopplerHold() },
                                    modifier = Modifier.size(40.dp)) {
                                    Icon(
                                        if (ui.dopplerHold) Icons.Default.PlayArrow
                                        else Icons.Default.Pause,
                                        t("doppler_hold_toggle"),
                                        tint = if (ui.dopplerHold) Amber else Cyan,
                                        modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                    TransmittersSection(ui, vm, sat, if (above) live!!.rangeRateKmS else null)
                    // SO-50 arming (74.4 Hz timer) — only for SO-50.
                    if (ui.catConnected && sat.name.uppercase().let { it.contains("SO-50") || it.contains("SAUDISAT 1C") }) {
                        Surface(color = Amber.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("so50_arm_title"), color = Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(t("so50_arm_desc"),
                                        color = TextLo, fontSize = 11.sp)
                                }
                                Button(onClick = { vm.armSo50() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Amber)) {
                                    Text(t("arm"), color = Color(0xFF2A1A00), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    val activeTx = vm.activeTransmitters().getOrNull(ui.selectedTxIndex)
                    if (activeTx?.isTransponder == true) {
                        // Voice / CW op-mode + per-mode RX offset (OscarWatch-style).
                        Surface(color = SpaceSurface, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                val rxOff = if (ui.opMode == "CW") ui.rxOffsetCwHz else ui.rxOffsetVoiceHz
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                    Text(t("offset_rx"), color = TextHi, fontSize = 13.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text((if (rxOff >= 0) "+" else "") + "$rxOff Hz",
                                        color = Aurora, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(Modifier.weight(1f))
                                    if (rxOff != 0L) {
                                        TextButton(onClick = { vm.setRxOffset(0) }) {
                                            Text("Reset", color = TextLo, fontSize = 12.sp)
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(-500L, -100L, -50L, 50L, 100L, 500L).forEach { d ->
                                        OutlinedButton(onClick = { vm.nudgeRxOffset(d) },
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                            modifier = Modifier.height(32.dp)) {
                                            Text((if (d > 0) "+" else "") + "$d", fontSize = 11.sp, color = Cyan)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Surface(color = SpaceSurface, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t("shift_tx"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(Modifier.width(8.dp))
                                Text((if (ui.txShiftHz >= 0) "+" else "") + "${ui.txShiftHz} Hz",
                                    color = Aurora, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.weight(1f))
                                if (ui.txShiftHz != 0L) {
                                    TextButton(onClick = { vm.setTxShift(0) }) {
                                        Text("Reset", color = TextLo, fontSize = 12.sp)
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 4.dp)) {
                                listOf(-1000L, -100L, -10L, 10L, 100L, 1000L).forEach { d ->
                                    OutlinedButton(onClick = { vm.nudgeTxShift(d) },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(34.dp)) {
                                        Text((if (d > 0) "+" else "") + (d / 1000.0).let {
                                            if (kotlin.math.abs(d) >= 1000) "${d/1000}k" else "$d" }, fontSize = 12.sp, color = Cyan)
                                    }
                                }
                            }
                        }
                    }

                    // Doppler table AFTER the adjustments, collapsed: it is read
                    // once before the pass, while offsets are touched during the
                    // contact with one hand on the antenna.
                    DopplerPassPanel(ui, vm)
                }
            }
        }

        // This satellite's contacts, under the card.
        val satLog = ui.log.filter { it.catnum == sat.catalogNumber }
        // **The count only counts real contacts.** Old logs still hold
        // callsign-less entries from the former compass-tap logging. They stay
        // visible (the editor can name or delete them) but are counted apart.
        val nommes = satLog.count { it.callsign.isNotBlank() }
        val anonymes = satLog.size - nommes
        if (satLog.isNotEmpty()) {
            // Collapsible list: twenty contacts push the compass and
            // frequencies off-screen. Closed by default above three contacts.
            item {
                Row(Modifier.fillMaxWidth().clickable { vm.basculeListeContacts() },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(t("logged_contacts") + "  ($nommes)", color = Aurora,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontSize = 11.sp)
                    if (anonymes > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(tf("log_no_call", anonymes), color = Amber, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (ui.carte.listeContactsOuverte) "▾" else "▸", color = Aurora,
                        fontSize = 14.sp)
                }
            }
            val lf = tzFormat("dd/MM HH:mm:ss", ui.useUtc)
            items(if (ui.carte.listeContactsOuverte) satLog.take(20) else emptyList(),
                key = { "slog" + it.timeMs }) { e ->
                Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { vm.editLogEntry(e.timeMs) }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${lf.format(Date(e.timeMs))} ${tzTag(ui.useUtc)}" +
                                    (e.callsign.takeIf { it.isNotBlank() }?.let { "  ·  $it" }
                                        ?: "  ·  " + t("log_no_call_short")),
                                color = if (e.callsign.isBlank()) Amber else TextHi,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            // Uplink shown: it is what goes into FREQ on export,
                            // so it must be checkable before a logbook rejects it.
                            Text(tf("az_el_line", e.azimuthDeg.toInt(), e.elevationDeg.toInt()) +
                                    (e.theirLocator.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "") +
                                    (e.uplinkMhz.takeIf { it > 0.0 }?.let {
                                        "  ·  ↑" + String.format(java.util.Locale.US, "%.4f", it)
                                    } ?: ""),
                                color = TextLo, fontSize = 12.sp)
                            // The squares the QSO was made from, when the station
                            // was sitting close enough to a grid line to claim two.
                            val gr = e.myGrids.split(",").map { it.trim() }.filter { it.length == 4 }
                            if (gr.size > 1) {
                                Text(gr.joinToString(" / ") { it.uppercase() },
                                    color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        // Upload **this** contact to the online logbook. Batch
                        // upload alone was a trap: the first try is where you
                        // find the key is read-only or the station profile is
                        // missing, and a bad batch must be untangled by hand
                        // on the server. Button only when configured, with a
                        // callsign and not yet sent; otherwise a check mark.
                        val carnetPret = ui.carnet.url.isNotBlank() &&
                            ui.carnet.cle.isNotBlank() && ui.carnet.profil.isNotBlank()
                        if (e.callsign.isNotBlank() && carnetPret) {
                            if (e.envoyeMs > 0L) {
                                Text("✓", color = Aurora, fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp))
                            } else {
                                TextButton(onClick = { vm.deposeUnContact(e.timeMs) },
                                    contentPadding = androidx.compose.foundation.layout
                                        .PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                    Text("↗", color = Cyan, fontSize = 16.sp)
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

        if (ui.skedsEnabled) {
            val allSkeds = vm.skedsFor(sat.catalogNumber)
            // When a specific pass is focused, show only the sked(s) matching THAT
            // pass (by temporal proximity, tolerant to TLE-drift offset). Without a
            // focused pass, show all the satellite's skeds.
            val focusPass = ui.focusedPassAos?.let { f ->
                ui.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) }
            }
            val skeds = if (focusPass != null) {
                val passMid = (focusPass.aosEpochMs + focusPass.losEpochMs) / 2
                allSkeds.filter { s ->
                    val sMid = ((s.workableStartMs ?: s.aosMs) + (s.workableEndMs ?: s.losMs)) / 2
                    kotlin.math.abs(sMid - passMid) < 50 * 60_000L
                }
            } else allSkeds
            // In local mode (no token), make sure the TLE is fresh before trusting
            // the local visibility computation.
            if (!ui.skedsAuthed && skeds.isNotEmpty()) {
                item {
                    LaunchedEffect(sat.catalogNumber) {
                        vm.ensureFreshTleForLocalSked(sat.catalogNumber)
                    }
                }
            }
            if (skeds.isNotEmpty()) {
                item {
                    Surface(color = SpaceCard, shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(t("skeds_announced") + if (focusPass != null) t("this_pass_suffix") else "",
                                color = Amber, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            val day = tzFormat("EEE dd/MM HH:mm", ui.useUtc)
                            val hm = tzFormat("HH:mm:ss", ui.useUtc)
                            skeds.forEach { s ->
                                val win = remember(s.callsign, s.aosMs, ui.observer) {
                                    vm.skedMutualWindow(s)
                                }
                                // Each announcement opens the mutual sked with
                                // its own data, same gesture as the top banner.
                                Column(Modifier.fillMaxWidth()
                                    .clickable { vm.openSked(s) }
                                    .padding(vertical = 5.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(color = Amber.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp)) {
                                            Text(s.callsign, color = Amber, fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(day.format(Date(s.aosMs)) + " ${tzTag(ui.useUtc)}" +
                                                    (s.mode?.let { "  ·  $it" } ?: "") +
                                                    (s.grids.firstOrNull()?.let { "  ·  $it" } ?: ""),
                                                color = TextHi, fontSize = 13.sp)
                                            s.comment?.let { Text(it, color = TextLo, fontSize = 11.sp) }
                                        }
                                    }
                                    // hams.at announced window (the station's own AOS/LOS).
                                    Text(tf("hamsat_window", hm.format(Date(s.aosMs)), hm.format(Date(s.losMs)), tzTag(ui.useUtc)),
                                        color = TextLo, fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 3.dp, start = 2.dp))
                                    // Prefer hams.at's authenticated window (computed for
                                    // your QTH); fall back to the local SGP4 estimate.
                                    val staleDays = sat.epochMs?.let {
                                        kotlin.math.abs(s.aosMs - it) / 86_400_000.0 } ?: 0.0
                                    val estimate = staleDays > 3
                                    if (s.workableStartMs != null && s.workableEndMs != null) {
                                        Surface(color = Color(0xFF49D17F).copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.padding(top = 3.dp, start = 2.dp)) {
                                            Text("✓ visible ${hm.format(Date(s.workableStartMs))}–${hm.format(Date(s.workableEndMs))} ${tzTag(ui.useUtc)}" +
                                                    (s.maxElevationDeg?.let { " · max ${it.toInt()}°" } ?: "") +
                                                    (s.matchPercent?.let { " · ${it}%" } ?: ""),
                                                color = Color(0xFF49D17F), fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    } else if (s.isWorkable == false && ui.skedsAuthed) {
                                        Text(t("not_visible_qth"),
                                            color = TextLo.copy(alpha = 0.8f), fontSize = 10.sp,
                                            modifier = Modifier.padding(top = 3.dp, start = 2.dp))
                                    } else if (win != null) {
                                        val ageD = vm.tleEpochAgeDays(sat.catalogNumber)
                                        Surface(color = Color(0xFF49D17F).copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.padding(top = 3.dp, start = 2.dp)) {
                                            Text(tf("common_window_line", hm.format(Date(win.first)), hm.format(Date(win.second)), tzTag(ui.useUtc)) +
                                                    if (estimate) t("estimate_suffix") else "",
                                                color = Color(0xFF49D17F), fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                        Text(t("local_calc") +
                                                (ageD?.let { tf("dated_days", "%.1f".format(it)) } ?: t("age_unknown")) +
                                                (if (ageD != null && ageD > 1.5) t("reduced_accuracy_suffix") else ""),
                                            color = TextLo.copy(alpha = 0.7f), fontSize = 10.sp,
                                            modifier = Modifier.padding(top = 1.dp, start = 2.dp))
                                    } else {
                                        val ageD = vm.tleEpochAgeDays(sat.catalogNumber)
                                        Text(if (estimate)
                                                tf("uncertain_window", ageD?.toInt() ?: staleDays.toInt())
                                             else t("local_no_common") +
                                                if (s.grids.isEmpty()) t("grid_unknown_suffix") else tf("grid_approx_suffix", s.grids.first()),
                                            color = TextLo.copy(alpha = 0.8f), fontSize = 10.sp,
                                            modifier = Modifier.padding(top = 3.dp, start = 2.dp))
                                    }
                                }
                            }
                            Text(t("sked_window_desc"),
                                color = TextLo.copy(alpha = 0.7f),
                                fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }

        if (ui.groundTrack.isNotEmpty()) {
            item {
                Surface(color = SpaceCard, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        WorldMap(ui.groundTrack, ui.livePosition, ui.observer, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("— " + t("ground_track") + "  ·  ◌ " + t("footprint") + "  ·  ● QTH",
                            color = TextLo, fontSize = 11.sp)
                    }
                }
            }
        }

        item {
            Text(t("next_passes"), color = TextLo, fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp, fontSize = 12.sp)
        }
        val shown = (if (ui.visualOnly) ui.passes.filter { it.visualPass } else ui.passes)
            .filter { it.losEpochMs > ui.nowMs }
        if (shown.isEmpty()) {
            item {
                Text(if (ui.loading) t("computing_passes") else t("no_passes"),
                    color = TextLo, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        items(shown, key = { it.aosEpochMs }) { p ->
            val isFocused = ui.focusedPassAos?.let { kotlin.math.abs(p.aosEpochMs - it) < 1000 } == true
            PassCard(p, ui.nowMs, highlight = isFocused, useUtc = ui.useUtc,
                agendaTitle = vm.agendaForPass(p)?.title,
                skedCount = if (ui.skedsEnabled)
                    vm.skedCountForPass(sat.catalogNumber, p.aosEpochMs, p.losEpochMs) else 0
            ) { vm.focusPass(p.aosEpochMs) }
        }
    }
}

@Composable
private fun LiveTelemetry(ui: UiState, vm: MainViewModel, sat: TleEntry) {
    val p = ui.livePosition
    val above = p != null && p.elevationDeg >= 0
    if (p == null) {
        Text(t("acquisition"), color = TextLo)
    } else if (!above) {
        val next = ui.passes.firstOrNull { it.aosEpochMs > ui.nowMs }
        Text("● " + t("below_horizon") + (next?.let { "  ·  AOS ${fmtCountdown(it.aosEpochMs - ui.nowMs)}" } ?: ""),
            color = TextLo, fontFamily = FontFamily.Monospace)
    } else {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            Stat("AZ", "${p.azimuthDeg.toInt()}°")
            Stat("EL", "${p.elevationDeg.toInt()}°", Cyan)
            Stat("RANGE", fr.f4ioz.satcombo.data.Units.distanceRound(p.rangeKm, ui.units))
            Stat("☀", if (p.sunlit) t("sunlit_label") else t("shadow_label"), if (p.sunlit) Amber else TextLo)
        }
    }
    Spacer(Modifier.height(12.dp))
    TransmittersSection(ui, vm, sat, if (above) p!!.rangeRateKmS else null)
}

/** Satellite VFO panel: pick one transponder, see band limits, normal/reverse,
 *  a RX slider that drives the matching TX, all Doppler-corrected live. */
@Composable
private fun TransmittersSection(ui: UiState, vm: MainViewModel, sat: TleEntry, rangeRateKmS: Double?) {
    val active = ui.transmitters.filter { it.alive && (it.downlinkLowHz != null || it.uplinkLowHz != null) }

    // Doppler state and source: the second line of the transponder card.
    // They had a title row of their own above it.
    val etat = if (rangeRateKmS != null) t("doppler_live") else t("at_rest")

    if (active.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("vfo_satellite"), color = TextLo, fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            if (ui.transmittersLoading)
                CircularProgressIndicator(Modifier.size(12.dp), color = Cyan, strokeWidth = 2.dp)
        }
        if (!ui.transmittersLoading) Text(t("no_active_transmitter"), color = TextLo, fontSize = 13.sp)
        return
    }

    val idx = ui.selectedTxIndex.coerceIn(0, active.size - 1)
    val tx = active[idx]

    // Band limits + normal/reverse.
    val dlLow = tx.downlinkLowHz; val dlHigh = tx.downlinkHighHz ?: dlLow
    val ulLow = tx.uplinkLowHz; val ulHigh = tx.uplinkHighHz ?: ulLow

    // Transponder chooser opens a config window (clearer when many FM channels).
    // One card holds the mode, normal/reverse, the Doppler state and the source:
    // the three rows it took pushed RX and TX below the fold.
    Surface(color = SpaceSurface, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { vm.openSatConfig() }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tx.description, color = TextHi, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Surface(color = if (tx.invert) Magenta.copy(alpha = 0.18f) else Aurora.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)) {
                        Text(if (tx.invert) "INVERSE" else "NORMAL",
                            color = if (tx.invert) Magenta else Aurora, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${active.size} " + t("transponders_tap") + " · " + etat + " · SatNOGS",
                        color = TextLo, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ui.transmittersLoading) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(Modifier.size(12.dp), color = Cyan, strokeWidth = 2.dp)
                    }
                }
                if (tx.isTransponder && dlLow != null && dlHigh != null)
                    Text(tf("vfo_bande_rx", Doppler.formatMHz(dlLow), Doppler.formatMHz(dlHigh)),
                        color = TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Icon(Icons.Default.Settings, t("configurer"), tint = Cyan)
        }
    }

    // When an agenda event imposes the downlink, say so, or the gap with the
    // shown transponder looks like a bug. Touching the slider clears it.
    ui.rxFromAgendaHz?.let { hz ->
        Surface(color = Magenta.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(tf("agenda_freq_loaded", agendaFreqLabel(hz)), color = Magenta,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }

    // **QO-100 converter active on a non-QO-100 satellite.** With a local
    // oscillator that puts QO-100 on 2 m, the LNB IF window necessarily
    // overlaps the 2 m downlinks of LEO sats: no frequency rule can tell an
    // ISS downlink from a QO-100 IF. Only the selected satellite can, and
    // otherwise the screen shows gigahertz with no explanation. So warn, with
    // the fix one tap away while the sat is in view.
    if (sat.catalogNumber != fr.f4ioz.satcombo.domain.Qo100.NORAD &&
        ui.convRx.configure && (ui.convRxPoste || ui.convRxCle)
    ) {
        Surface(color = Amber.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("conv_hors_qo100"), color = Amber, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    vm.setConvRxPoste(false); vm.setConvRxCle(false)
                }) { Text(t("conv_desactiver"), color = Amber, fontSize = 12.sp) }
            }
        }
    }

    // **What the band plan allows here.** Each QO-100 segment has a max
    // width (500 Hz narrow digital, 2700 Hz SSB); ignoring it spills onto
    // neighbours. QO-100 only: other transponders have no per-segment plan,
    // and no line beats a wrong one.
    val rxRestPourPlan = ui.rxRestHz ?: dlLow
    if (sat.catalogNumber == fr.f4ioz.satcombo.domain.Qo100.NORAD && rxRestPourPlan != null) {
        fr.f4ioz.satcombo.domain.Qo100.segment(rxRestPourPlan)?.let { seg ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("qo100_seg_" + seg.cle), color = Cyan, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (seg.largeurMaxHz > 0) tf("qo100_largeur", seg.largeurMaxHz)
                    else t("qo100_largeur_libre"),
                    color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        // Drawn band plan with the cursor on it (beacons, CW, digital, SSB).
        // Same component as the QO-100 screen, so the plan cannot differ
        // between pages.
        Spacer(Modifier.height(6.dp))
        // Not bounded to the transponder: it draws all of QO-100 and you can
        // land anywhere on it. The transponder follows the frequency.
        Reglette(rxRestPourPlan) { vm.allerLibre(it) }
    }

    Spacer(Modifier.height(10.dp))

    // Chosen RX rest freq (slider for transponders, fixed for channels).
    val rxRest = ui.rxRestHz ?: dlLow
    if (tx.isTransponder && dlLow != null && dlHigh != null && rxRest != null) {
        Slider(
            value = rxRest.toFloat(),
            onValueChange = { vm.setRxRest(it.toLong()) },
            valueRange = minOf(dlLow, dlHigh).toFloat()..maxOf(dlLow, dlHigh).toFloat(),
            colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan)
        )
    }

    // Live Doppler-corrected RX/TX.
    // RX = channel + Doppler + calibration + the per-mode Offset RX (listening
    // point only). TX = mirror of the CHANNEL (never of the offset) + Shift TX.
    // Each knob therefore moves exactly one field, and NOR/REV follows the
    // user's override chip, not just the catalogue flag.
    val calib = ui.calibShiftHz
    // **Computed by the ViewModel, not here.** This screen and the public
    // page each had their own computation and drifted 5 kHz apart. One
    // function, two callers.
    val (rxShown, txShown) = vm.freqAffichees()


    // **Sky and rig frequencies together.** These are satellite frequencies;
    // with a converter in the chain the rig shows something else (QO-100:
    // 10 489.802 on screen, 144.830 on the FT-817). The rig line appears only
    // when a converter actually covers the frequency.
    rxShown?.let { f ->
        VfoChip("RX ↓", Doppler.formatMHz(f), Cyan)
        if (ui.convRx.couvre(f)) LignePoste(ui.convRx.versPoste(f), Cyan)
    }
    txShown?.let { f ->
        Spacer(Modifier.height(6.dp))
        VfoChip("TX ↑", Doppler.formatMHz(f), Amber)
        if (ui.convTx.couvre(f)) LignePoste(ui.convTx.versPoste(f), Amber)
    }

    // Readouts: live Doppler shift + the operator's stored calibration.
    Spacer(Modifier.height(6.dp))
    val dop = if (rangeRateKmS != null && rxRest != null)
        (Doppler.downlink(rxRest, rangeRateKmS) - rxRest) else 0L
    Text("Doppler RX %+d Hz   ·   calibration %+d Hz".format(dop, calib),
        color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

}

/**
 * Doppler over the whole pass, as a table: time, elevation, RX ↓, TX ↑, plus
 * total excursion. Answers the pre-pass question the live readout cannot: how
 * far and which way will the frequency move.
 *
 * The two VFOs move in opposite directions, as they should: downlink falls,
 * uplink rises. About 10 kHz on 435 MHz, a third of that on 145 — hence the
 * old rule of thumb: with only one VFO to follow, correct the downlink.
 */
@Composable
private fun DopplerPassPanel(ui: UiState, vm: MainViewModel) {
    val table = remember(ui.focusedPassAos, ui.passes, ui.rxRestHz,
        ui.selectedTxIndex, ui.invertOverride, ui.selected?.catalogNumber) {
        vm.dopplerPassRows()
    }
    if (table.isEmpty) return
    val fmt = tzFormat("HH:mm:ss", ui.useUtc)
    // Survives list scrolling, not leaving the screen: always reopens collapsed.
    var deplie by rememberSaveable { mutableStateOf(false) }

    Spacer(Modifier.height(10.dp))
    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // Header stays, table collapses (collapsed by default).
            Surface(color = SpaceSurface, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()
                .clickable { deplie = !deplie }
                .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (deplie) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(t("doppler_pass_title"), color = TextHi, fontWeight = FontWeight.Bold,
                    fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                // Collapsed, the excursion stays visible: the one figure
                // people take from the table.
                if (!deplie) {
                    Text(
                        "±" + fr.f4ioz.satcombo.domain.DopplerPass.kHz(table.rxExcursionHz),
                        color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                }
                Text(tzTag(ui.useUtc), color = TextLo, fontSize = 10.sp)
            }
            }

            if (!deplie) return@Column

            Spacer(Modifier.height(2.dp))
            Text(t(when {
                table.txRestHz == null -> "doppler_pass_hint_rx"
                ui.invertOverride == true -> "doppler_pass_hint_invert"
                else -> "doppler_pass_hint_tx"
            }), color = TextLo, fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth()) {
                Text(t("time"), color = TextLo, fontSize = 10.sp, modifier = Modifier.weight(1.5f))
                Text("EL", color = TextLo, fontSize = 10.sp, textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.8f))
                Text("RX ↓", color = Cyan, fontSize = 10.sp, textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.6f))
                Text("TX ↑", color = Amber, fontSize = 10.sp, textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.6f))
            }
            HorizontalDivider(color = SpaceSurface)
            for (r in table.rows) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (r.mark.isNotEmpty()) r.mark + " " else "") +
                            fmt.format(java.util.Date(r.timeMs)),
                        color = if (r.mark.isNotEmpty()) TextHi else TextLo,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = if (r.mark.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1.5f))
                    Text("%d°".format(r.elevationDeg.toInt()), color = TextLo, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.8f))
                    Text(Doppler.formatMHzShort(r.rxHz).removeSuffix(" MHz"),
                        color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1.6f))
                    Text(r.txHz?.let { Doppler.formatMHzShort(it).removeSuffix(" MHz") } ?: "—",
                        color = Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1.6f))
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = SpaceSurface)
            Spacer(Modifier.height(6.dp))
            // The key holds `%.1f` placeholders, so it needs `tf`; plain
            // concatenation showed the raw placeholders before the values.
            Text(
                if (table.txExcursionHz > 0)
                    tf("doppler_pass_span",
                        table.rxExcursionHz / 1000.0, table.txExcursionHz / 1000.0)
                else
                    tf("doppler_pass_span_rx", table.rxExcursionHz / 1000.0),
                color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * What the rig actually shows, under the sky frequency. Indented and smaller:
 * it only serves to check the chain, or to tune by hand if CAT drops out.
 */
@Composable
private fun LignePoste(posteHz: Long, accent: Color) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(t("vfo_poste"), color = TextLo, fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        Text(Doppler.formatMHz(posteHz), color = accent.copy(alpha = 0.85f),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun VfoChip(tag: String, value: String, accent: Color) {
    Surface(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tag, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(value, color = TextHi, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SatStatusBadge(status: String?) {
    if (status == null) return
    val dark = isDarkTheme()
    val (label, color) = when (status.lowercase()) {
        "alive" -> t("operational") to (if (dark) Color(0xFF49D17F) else Color(0xFF14823E))
        "dead" -> t("out_of_service") to (if (dark) Magenta else Color(0xFFB02356))
        "re-entered", "reentered" -> t("reentered") to TextLo
        "future" -> t("future_upcoming") to (if (dark) Amber else Color(0xFFB36A00))
        else -> status.uppercase() to TextLo
    }
    Surface(color = color.copy(alpha = if (dark) 0.18f else 0.12f), shape = RoundedCornerShape(6.dp),
        border = if (dark) null else androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f))) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(5.dp))
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AmsatBadge(report: fr.f4ioz.satcombo.data.AmsatReport?) {
    if (report == null) return
    val dark = isDarkTheme()
    val (label, color) = when (report.recent) {
        fr.f4ioz.satcombo.data.AmsatStatus.ACTIVE -> t("heard") to (if (dark) Color(0xFF49D17F) else Color(0xFF14823E))
        fr.f4ioz.satcombo.data.AmsatStatus.BEACON -> t("beacon") to (if (dark) Amber else Color(0xFFB36A00))
        fr.f4ioz.satcombo.data.AmsatStatus.NOT_HEARD -> t("not_heard") to (if (dark) Magenta else Color(0xFFB02356))
        fr.f4ioz.satcombo.data.AmsatStatus.CONFLICT -> t("uncertain") to Color(0xFFD9540B)
        else -> return
    }
    Surface(color = color.copy(alpha = if (dark) 0.16f else 0.12f),
        shape = RoundedCornerShape(6.dp),
        border = if (dark) null else androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f))) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)) {
            Text("AMSAT", color = color.copy(alpha = if (dark) 0.7f else 0.85f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = TextHi) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = TextLo, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun FreqChip(tag: String, value: String, accent: Color) {
    Surface(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tag, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(value, color = TextHi, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripDatesDialog(useUtc: Boolean = false,
                             onConfirm: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    // Day boundaries follow the displayed timezone: with "UTC" selected a day
    // runs from 00:00 UTC to 24:00 UTC, so the filtered passes match the days
    // and hours shown in the list.
    val zone: java.time.ZoneId =
        if (useUtc) java.time.ZoneOffset.UTC else java.time.ZoneId.systemDefault()
    val todayUtc = java.time.LocalDate.now(zone)
        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    fun dayStartLocal(utcMs: Long): Long =
        java.time.Instant.ofEpochMilli(utcMs).atZone(java.time.ZoneOffset.UTC)
            .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    var startUtc by remember { mutableStateOf<Long?>(null) }

    if (startUtc == null) {
        // --- Step 1: departure date ---
        val st = rememberDatePickerState(
            initialSelectedDateMillis = todayUtc,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
            }
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = st.selectedDateMillis != null,
                    onClick = { startUtc = st.selectedDateMillis }
                ) { Text(t("next_btn"), color = Cyan) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel"), color = TextLo) } }
        ) {
            DatePicker(state = st, showModeToggle = false,
                title = { Text(t("date_departure"), color = TextHi,
                    fontWeight = FontWeight.Bold) })
        }
    } else {
        // --- Step 2: return date (>= departure) ---
        val minUtc = startUtc!!
        val st2 = rememberDatePickerState(
            initialSelectedDateMillis = minUtc,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= minUtc
            }
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = st2.selectedDateMillis != null,
                    onClick = {
                        val s = dayStartLocal(minUtc)
                        val e = dayStartLocal(st2.selectedDateMillis ?: minUtc) + 86_400_000
                        onConfirm(s, e)
                    }
                ) { Text(t("apply"), color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { startUtc = null }) { Text(t("dep_back"), color = TextLo) }
            }
        ) {
            DatePicker(state = st2, showModeToggle = false,
                title = { Text(t("date_return"), color = TextHi,
                    fontWeight = FontWeight.Bold) })
        }
    }
}

@Composable
private fun SatConfigDialog(ui: UiState, vm: MainViewModel, sat: TleEntry) {
    val active = ui.transmitters.filter { it.alive && (it.downlinkLowHz != null || it.uplinkLowHz != null) }
    var shiftText by remember(sat.catalogNumber) { mutableStateOf(ui.calibShiftHz.toString()) }
    var txShiftText by remember(sat.catalogNumber) { mutableStateOf(ui.txShiftHz.toString()) }

    androidx.compose.ui.window.Dialog(onDismissRequest = vm::closeSatConfig) {
        Surface(color = SpaceSurface, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f)) {
            Column(Modifier.padding(16.dp)) {
            // **One scroll for the whole dialog.** A `weight(1f)` transponder
            // list only got what the rest left: two visible rows out of up to
            // fifteen. A lazy list is pointless for fifteen entries, so all
            // scrolls together; only the save button stays pinned.
            Column(
                Modifier.weight(1f)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                Text(tf("configure_sat", sat.name), color = TextHi,
                    fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("NORAD #${sat.catalogNumber}", color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))

                Text(t("transponder_channel"), color = TextLo, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    active.forEachIndexed { i, t ->
                        val sel = i == ui.selectedTxIndex
                        Surface(
                            color = if (sel) Cyan.copy(alpha = 0.15f) else SpaceCard,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().clickable { vm.selectTransmitter(i) }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.description, color = if (sel) Cyan else TextHi,
                                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    val dl = t.downlinkLowHz?.let { "↓ " + Doppler.formatMHzShort(it) } ?: ""
                                    val ul = t.uplinkLowHz?.let { "↑ " + Doppler.formatMHzShort(it) } ?: ""
                                    Text(listOf(dl, ul).filter { it.isNotEmpty() }.joinToString("   "),
                                        color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                        maxLines = 2)
                                    Row(Modifier.padding(top = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        // SatNOGS mode only where it means
                                        // something (beacons). A linear
                                        // transponder has no mode, and the
                                        // database says "FM" on QO-100's
                                        // SSB-only segments.
                                        if (t.modeSignifiant) t.mode?.let { Badge2(it, Aurora) }
                                        if (t.isTransponder) Badge2(if (t.invert) fr.f4ioz.satcombo.i18n.t("inverting") else "NORMAL",
                                            if (t.invert) Magenta else Aurora)
                                    }
                                }
                                if (sel) Icon(Icons.Default.Checklist, null, tint = Cyan)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // Transponder sense and operating mode: set once, as part of
                // choosing the transponder, so they live here rather than as
                // two full-width cards on the pass page.
                val txChoisi = active.getOrNull(ui.selectedTxIndex)
                val effT = ui.invertOverride ?: (txChoisi?.invert == true)
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(t("transponder"), color = TextHi, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    FilterChip(selected = !effT, onClick = { vm.setInvertOverride(false) },
                        label = { Text("NOR") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = effT, onClick = { vm.setInvertOverride(true) },
                        label = { Text("REV") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                    Spacer(Modifier.weight(1f))
                    if (ui.invertOverride != null) {
                        TextButton(onClick = { vm.setInvertOverride(null) }) {
                            Text("Auto", color = TextLo, fontSize = 12.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)) {
                    Text(t("mode"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    FilterChip(selected = ui.opMode == "VOICE", onClick = { vm.setOpMode("VOICE") },
                        label = { Text(t("phone_mode")) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = ui.opMode == "CW", onClick = { vm.setOpMode("CW") },
                        label = { Text("CW") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                }

                Text(t("calibration_title"), color = TextLo,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontSize = 11.sp)
                Text(t("calibration_desc"),
                    color = TextLo.copy(alpha = 0.7f), fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = shiftText,
                        onValueChange = { shiftText = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("Shift (Hz)", color = TextLo) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                            focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    listOf(-1000L, 1000L).forEach { step ->
                        TextButton(onClick = {
                            val v = (shiftText.toLongOrNull() ?: 0L) + step
                            shiftText = v.toString(); vm.setCalibShift(v)
                        }) { Text(if (step > 0) "+1k" else "-1k", color = Cyan) }
                    }
                }

                // TX shift, stored under the same satellite key as the RX one.
                // Showing only RX here made +480 Hz on the pass page look like
                // a fault. The TX knob now changes it silently, so it needs a
                // place to check and reset it outside a pass.
                Spacer(Modifier.height(12.dp))
                Text(t("calibration_tx_title"), color = TextLo,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontSize = 11.sp)
                Text(t("calibration_tx_desc"),
                    color = TextLo.copy(alpha = 0.7f), fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = txShiftText,
                        onValueChange = { txShiftText = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("Shift TX (Hz)", color = TextLo) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                            focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        txShiftText = "0"; vm.setTxShift(0L)
                    }) { Text(t("reset"), color = TextLo) }
                }

                // Reference: the shift that worked. A shift tuned by ear is
                // lost silently when a finger slips on the TX knob, and the
                // only other backup is the whole configuration. Saving is
                // **explicit**: auto-save cannot tell a converging adjustment
                // from a slip.
                Spacer(Modifier.height(10.dp))
                val refRx = ui.catUi.refCalibShiftHz
                val refTx = ui.catUi.refTxShiftHz
                Text(
                    if (refRx == null) t("shift_none") else tf("shift_ref", refRx, refTx ?: 0L),
                    color = if (refRx == null) TextLo else Cyan, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = { vm.memoriseDecalages() }) {
                        Text(t("shift_memo"), color = Cyan, fontSize = 12.sp)
                    }
                    if (refRx != null) {
                        OutlinedButton(onClick = {
                            vm.rappelleDecalages()
                            shiftText = refRx.toString()
                            txShiftText = (refTx ?: 0L).toString()
                        }) { Text(t("shift_recall"), color = Amber, fontSize = 12.sp) }
                    }
                    // Safety net without a saved reference: back to the values
                    // on arrival, shown only if something changed since.
                    val arrRx = ui.catUi.arriveeCalibShiftHz
                    val arrTx = ui.catUi.arriveeTxShiftHz
                    if (arrRx != null &&
                        (arrRx != ui.calibShiftHz || (arrTx ?: 0L) != ui.txShiftHz)) {
                        OutlinedButton(onClick = {
                            vm.rappelleArrivee()
                            shiftText = arrRx.toString()
                            txShiftText = (arrTx ?: 0L).toString()
                        }) { Text(t("shift_undo"), color = TextLo, fontSize = 12.sp) }
                    }
                }

            }

                // Pinned below the scrolling area: never scroll to save.
                Spacer(Modifier.height(8.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        vm.setCalibShift(shiftText.toLongOrNull() ?: 0L)
                        vm.setTxShift(txShiftText.toLongOrNull() ?: 0L)
                        vm.closeSatConfig()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Text(t("save"), color = Color(0xFF00201D))
                    }
                }
            }
        }
    }
}

/**
 * "HH:MM" to an instant, keeping the contact's day: this corrects a time, not
 * a date.
 */
internal fun heureVersMs(txt: String, refMs: Long, useUtc: Boolean): Long? {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(txt.trim()) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val mi = m.groupValues[2].toIntOrNull() ?: return null
    if (h > 23 || mi > 59) return null
    val cal = java.util.Calendar.getInstance(
        if (useUtc) java.util.TimeZone.getTimeZone("UTC") else java.util.TimeZone.getDefault())
    cal.timeInMillis = refMs
    cal.set(java.util.Calendar.HOUR_OF_DAY, h)
    cal.set(java.util.Calendar.MINUTE, mi)
    cal.set(java.util.Calendar.SECOND, 0)
    return cal.timeInMillis
}

@Composable
private fun Badge2(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
internal fun LogEditDialog(entry: fr.f4ioz.satcombo.data.LogEntry, useUtc: Boolean,
                          onSave: (String, String, String, String, String, String) -> Unit,
                          onDismiss: () -> Unit,
                          /**
                           * Available satellites and the correction callback.
                           * Changing the satellite recomputes az/el: they
                           * describe where the antenna pointed, not user input.
                           */
                          satellites: List<fr.f4ioz.satcombo.data.TleEntry> = emptyList(),
                          onCorrigeSat: ((fr.f4ioz.satcombo.data.TleEntry, Long?) -> Unit)? = null) {
    // Chosen satellite and time, **not yet written**. Writing on tap made
    // "Cancel" a lie; a dialog writes only on confirm.
    var satChoisi by remember(entry.timeMs) {
        mutableStateOf<fr.f4ioz.satcombo.data.TleEntry?>(null)
    }
    var heure by remember(entry.timeMs) {
        mutableStateOf(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            .apply { if (useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date(entry.timeMs)))
    }
    var call by remember { mutableStateOf(entry.callsign) }
    var grid by remember { mutableStateOf(entry.theirLocator) }
    var note by remember { mutableStateOf(entry.note) }
    var mode by remember { mutableStateOf(entry.mode) }
    // Default report follows the mode: 599 in CW, 59 otherwise. Editable.
    val defaultRst = if (entry.mode.uppercase().contains("CW")) "599" else "59"
    var rstS by remember { mutableStateOf(entry.rstSent.ifBlank { defaultRst }) }
    var rstR by remember { mutableStateOf(entry.rstRcvd.ifBlank { defaultRst }) }
    val heureFmt = remember(useUtc) {
        SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).apply {
            if (useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // Satellite/time correction first: it changes the entry key
                // (`timeMs`) that `onSave` works on.
                val nouvelleHeure = heureVersMs(heure, entry.timeMs, useUtc)
                    ?.takeIf { it != entry.timeMs }
                val sa = satChoisi
                if (onCorrigeSat != null && (sa != null || nouvelleHeure != null)) {
                    onCorrigeSat(
                        sa ?: satellites.firstOrNull { it.name == entry.satName }
                            ?: return@TextButton,
                        nouvelleHeure)
                }
                onSave(call, grid, note, mode, rstS, rstR)
            }) {
                Text(t("save"), color = Cyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("cancel"), color = TextLo) }
        },
        title = { Text(t("contact_logged2"), color = TextHi) },
        text = {
            Column {
                // Satellite and time are fixable here: a misattributed contact
                // corrupts the log, the ADIF and the worked grid squares.
                var choixSat by remember(entry.timeMs) { mutableStateOf(false) }
                if (choixSat && onCorrigeSat != null) {
                    AlertDialog(
                        onDismissRequest = { choixSat = false },
                        confirmButton = {},
                        title = { Text(t("nommage_sat_change")) },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                satellites.forEach { sa ->
                                    Text(sa.name,
                                        color = if (sa.name == entry.satName) Cyan else TextHi,
                                        fontSize = 15.sp,
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { satChoisi = sa; choixSat = false }
                                            .padding(vertical = 8.dp))
                                }
                            }
                        })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = heure,
                        onValueChange = { heure = it },
                        label = { Text(t("log_time_label"), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.width(110.dp))
                    Spacer(Modifier.width(10.dp))
                    if (onCorrigeSat != null) {
                        TextButton(onClick = { choixSat = true }) {
                            // The name shown is the one that will be saved.
                            Text((satChoisi?.name ?: entry.satName) + " ▾",
                                color = if (satChoisi != null) Amber else Cyan,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("${entry.satName}  ·  ${heureFmt.format(Date(entry.timeMs))} ${if (useUtc) "UTC" else "LOC"}",
                    color = TextLo, fontSize = 13.sp)
                Text(tf("az_el_line", entry.azimuthDeg.toInt(), entry.elevationDeg.toInt()) + "  ·  ${entry.myLocator}",
                    color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = call, onValueChange = { call = it.uppercase() },
                    label = { Text(t("callsign_label")) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                        focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = grid, onValueChange = { grid = it.uppercase() },
                    label = { Text(t("contact_locator_label")) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                        focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = mode, onValueChange = { mode = it.uppercase() },
                        label = { Text(t("log_mode_label")) }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                            focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                        modifier = Modifier.weight(1.2f))
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(value = rstS, onValueChange = { rstS = it },
                        label = { Text(t("log_rst_sent")) }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                            focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(value = rstR, onValueChange = { rstR = it },
                        label = { Text(t("log_rst_rcvd")) }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                            focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = note, onValueChange = { note = it },
                    label = { Text("Note") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF2A3647),
                        focusedTextColor = TextHi, unfocusedTextColor = TextHi, cursorColor = Cyan),
                    modifier = Modifier.fillMaxWidth())
            }
        },
        containerColor = SpaceCard
    )
}

/**
 * "Active / not active?" — one tap, and the answer goes to the AMSAT Live OSCAR
 * Status table that SatMe already reads back. Reporting an empty pass is as
 * useful as reporting a good one, so both answers are equally prominent.
 */
@Composable
private fun AmsatStatusDialog(
    ui: UiState, satName: String,
    onSend: (Boolean) -> Unit, onDismiss: () -> Unit
) {
    val busy = ui.amsatSubmitState == "busy"
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(t("status_title"), color = TextHi, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tf("status_desc", satName, ui.callsign.uppercase()),
                    color = TextLo, fontSize = 13.sp)
                when (ui.amsatSubmitState) {
                    "busy" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("status_sending"), color = Cyan, fontSize = 12.sp)
                    }
                    "ok" -> Text("✓ " + t("status_ok"), color = Aurora, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold)
                    "fail" -> Text("⚠ " + t("status_fail"), color = Amber, fontSize = 12.sp)
                }
                if (ui.amsatSubmitState != "ok") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onSend(true) }, enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = Aurora),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(t("status_heard"), color = Color.White,
                                fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { onSend(false) }, enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(t("status_not_heard"), color = TextLo,
                                fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(t("close"), color = Cyan)
            }
        },
        containerColor = SpaceCard
    )
}

/**
 * The satellite page's header: the name is the top bar's title, the badges
 * run on a line of their own right under the bar (BadgesSatellite).
 *
 * It used to be a row of its own under the bar, plus a full-width banner for
 * old elements: two headers stacked, and the RX/TX frequencies pushed below
 * the fold during the pass. The badges then sat under the name, squeezed
 * between the arrow and five icons: on a narrow phone the AMSAT badge was cut.
 */
@Composable
internal fun TitreSatellite(sat: TleEntry) {
    Text(sat.name, fontWeight = FontWeight.Black, color = TextHi, fontSize = 18.sp,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

/** Age of the elements, rig, rotor, status and NORAD: the full screen width. */
@Composable
internal fun BadgesSatellite(ui: UiState, vm: MainViewModel, sat: TleEntry, modifier: Modifier = Modifier) {
    Column(modifier) {
        // Horizontally scrollable, should a phone still be too narrow.
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())) {
            ElementsAges(ui, vm, sat)
            if (ui.catConnected) {
                Surface(color = Cyan.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                    Text("CAT", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            // A forgotten Doppler hold alone can ruin a whole pass, so
            // it shows next to the CAT badge, in amber.
            if (ui.catConnected && ui.dopplerHold) {
                Surface(color = Amber.copy(alpha = 0.20f), shape = RoundedCornerShape(6.dp)) {
                    Text(t("doppler_hold_badge"), color = Amber, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            // Rotor badge: the mast is the only thing that physically
            // moves, so show at a glance that it is connected and
            // whether it is pre-positioning for the pass.
            if (ui.rotorConnected) {
                val teinte = if (ui.rotorPrePositioning) Amber else Aurora
                Surface(color = teinte.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                    Text(if (ui.rotorPrePositioning) t("rotor_badge_pre") else t("rotor_badge"),
                        color = teinte, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            val showSatnogs = ui.statusSource != "AMSAT"
            val showAmsat = ui.statusSource != "SATNOGS"
            val amsat = if (showAmsat) vm.amsatFor(sat.name, sat.catalogNumber) else null
            if (showSatnogs && ui.satStatus != null) SatStatusBadge(ui.satStatus)
            if (amsat != null) AmsatBadge(amsat)
            Text("#${sat.catalogNumber}", color = TextLo, fontSize = 11.sp,
                maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

/**
 * Old elements, as a chip: "⚠ 5 d". A tap fetches fresh ones. It was a
 * full-width banner above the compass; the warning matters, its size did not.
 */
@Composable
private fun ElementsAges(ui: UiState, vm: MainViewModel, sat: TleEntry) {
    val shownPass = ui.focusedPassAos?.let { f -> ui.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
        ?: ui.passes.firstOrNull { it.losEpochMs > ui.nowMs }
    val epochMs = sat.epochMs ?: return
    if (shownPass == null) return
    val jours = (kotlin.math.abs(shownPass.aosEpochMs - epochMs) / 86_400_000.0).toInt()
    if (jours <= 3) return
    val libelle = tf("stale_elements", jours) + " " + t("refresh")
    // A badge like the others, not a 48 dp button: Surface(onClick) enforces
    // that height and the chip towered over its neighbours. Compose still
    // widens the touch area around it.
    Surface(color = Amber.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp),
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .clickable { vm.refreshTleFor(sat.catalogNumber, annonce = true) }
            .semantics { contentDescription = libelle }) {
        Text(tf("stale_chip", jours), color = Amber, fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
