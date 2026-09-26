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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
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
import fr.f4ioz.satcombo.sdr.RtlTuning
import fr.f4ioz.satcombo.sdr.SdrHub
import fr.f4ioz.satcombo.sstv.SstvHub
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.delay

/**
 * What the SDR dongle and the SSTV decoder show on the pass page.
 *
 * During a ten-minute pass, switching screens to touch the gain is time not
 * spent pointing the antenna. So these blocks sit right under the compass:
 * the incoming image first, then the dongle and its controls. The full SDR
 * screen keeps everything else (rate, ppm, image folder); here only what you
 * adjust with an antenna in hand.
 */

/**
 * The pass image strip: SSTV or NOAA.
 *
 * A satellite never does both and there is room for one image, so the title
 * chip carries the choice, with a shortcut to that decoder's settings.
 *
 * With nothing received the strip shrinks to one line — chip and record
 * button — because that is exactly where you start the capture as the
 * satellite rises. When recording stops the image goes away: a frozen image
 * under the compass suggests you are still receiving.
 */
@Composable
fun RxImageInline(ui: UiState, vm: MainViewModel) {
    // Shown only if the matching decoder is enabled in settings: an operator
    // who unchecked SSTV and NOAA doesn't want the top of the screen taken by
    // a feature they turned down.
    val hasSstv = fr.f4ioz.satcombo.data.Extensions.SSTV in ui.extensions && ui.sstvEnabled
    val hasApt = fr.f4ioz.satcombo.data.Extensions.APT in ui.extensions && ui.aptEnabled
    if (!hasSstv && !hasApt) return

    // The chosen mode, unless it isn't unlocked: better show the other than nothing.
    val noaa = when {
        !hasApt -> false
        !hasSstv -> true
        else -> ui.rxImageMode == "NOAA"
    }
    var chooser by remember { mutableStateOf(false) }

    if (noaa) AptInlineCard(ui, vm) { chooser = true }
    else SstvInlineCard(ui, vm) { chooser = true }

    if (chooser) {
        RxModeDialog(
            noaa = noaa, hasSstv = hasSstv, hasApt = hasApt,
            onPick = { vm.setRxImageMode(it) },
            onSettings = { if (noaa) vm.openApt() else vm.openSstv(); chooser = false },
            onDismiss = { chooser = false })
    }
}

/** SSTV / NOAA choice, and the way to the chosen decoder's settings. */
@Composable
private fun RxModeDialog(
    noaa: Boolean, hasSstv: Boolean, hasApt: Boolean,
    onPick: (String) -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onSettings) {
                Text(if (noaa) t("rx_open_apt") else t("rx_open_sstv"), color = Cyan)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(t("close"), color = TextLo)
            }
        },
        title = { Text(t("rx_choose_title"), color = TextHi) },
        text = {
            Column {
                Text(t("rx_choose_hint"), color = TextLo, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasSstv) MiniChip(t("rx_sstv"), !noaa) { onPick("SSTV") }
                    if (hasApt) MiniChip(t("rx_noaa"), noaa) { onPick("NOAA") }
                }
            }
        })
}

/**
 * Strip record button. Drives the same recorder as the rest of the app (one
 * capture at a time), from where you watch the image arrive.
 */
@Composable
private fun RxRecordButton(ui: UiState, vm: MainViewModel) {
    val rec = ui.recording
    IconButton(onClick = { vm.toggleRxRecording() }, modifier = Modifier.size(34.dp)) {
        Icon(
            if (rec) Icons.Default.Stop else Icons.Default.FiberManualRecord,
            if (rec) t("rx_rec_stop") else t("rx_rec_start"),
            tint = if (rec) Magenta else Amber,
            modifier = Modifier.size(20.dp))
    }
}

/** The SSTV image being built, under the compass. */
@Composable
private fun SstvInlineCard(ui: UiState, vm: MainViewModel, onTitleClick: () -> Unit) {
    val st by SstvHub.state.collectAsState()
    // Hidden once listening stops: the decoder keeps the last frame for the
    // full SSTV screen, not for the pass page.
    val bmp = if (st.listening) st.preview else null

    // The preview is the same Bitmap filled line by line: rebuild the
    // ImageBitmap on each progress change or Compose never redraws.
    val img = remember(bmp, st.progress) { bmp?.asImageBitmap() }

    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Aurora.copy(alpha = 0.20f), shape = RoundedCornerShape(6.dp),
                    onClick = onTitleClick) {
                    Text(t("sstv_inline_title"), color = Aurora, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(sstvStatusLine(st),
                    color = if (st.decoding) Aurora else TextLo, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                RxRecordButton(ui, vm)
            }
            // The satellite name: what makes an archive rather than a pile of PNGs.
            val sat = st.satName.ifBlank { ui.selected?.name.orEmpty() }
            if (st.listening && sat.isNotBlank()) {
                Text(sat, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (st.listening) {
                // Forced mode and manual start within thumb reach: it's during
                // the pass that you notice the VIS header was missed.
                Spacer(Modifier.height(6.dp))
                SstvModeControls(st, compact = true)
            }
            if (img != null) {
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = img,
                    contentDescription = t("sstv_inline_title"),
                    contentScale = ContentScale.FillWidth,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                if (st.progress > 0.001f && st.progress < 0.999f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { st.progress },
                        color = Aurora, trackColor = SpaceSurface,
                        modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
    }
}

/**
 * The NOAA image being built, under the compass.
 *
 * Unlike SSTV, an APT image has no known end; it grows while the satellite is
 * in view. So show line count and sync lock instead of a percentage, plus a
 * button to save what's received without stopping — over fifteen minutes the
 * system may kill the app before LOS.
 */
@Composable
private fun AptInlineCard(ui: UiState, vm: MainViewModel, onTitleClick: () -> Unit) {
    val ctx = LocalContext.current
    val st by fr.f4ioz.satcombo.apt.AptHub.state.collectAsState()
    val bmp = if (st.listening) st.preview else null
    val img = remember(bmp, st.lines) { bmp?.asImageBitmap() }
    val accent = if (st.locked) Aurora else Amber

    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accent.copy(alpha = 0.20f), shape = RoundedCornerShape(6.dp),
                    onClick = onTitleClick) {
                    Text(t("apt_inline_title"), color = accent, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        st.locked -> tf("apt_locked", st.lines, (st.quality * 100).toInt())
                        st.listening -> t("apt_waiting")
                        else -> t("apt_idle")
                    },
                    color = if (st.locked) Aurora else TextLo, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                RxRecordButton(ui, vm)
            }
            val sat = st.satName.ifBlank { ui.selected?.name.orEmpty() }
            if (st.listening && sat.isNotBlank()) {
                Text(sat, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (img != null) {
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = img,
                    contentDescription = t("apt_inline_title"),
                    contentScale = ContentScale.FillWidth,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        (st.lines / fr.f4ioz.satcombo.apt.AptHub.MAX_LINES.toFloat())
                            .coerceIn(0f, 1f)
                    },
                    color = accent, trackColor = SpaceSurface,
                    modifier = Modifier.fillMaxWidth().height(3.dp))
            }
            if (st.listening && st.lines >= 10) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniChip(t("apt_save_now"), false) {
                        fr.f4ioz.satcombo.apt.AptHub.saveNow(ctx)
                    }
                }
            }
        }
    }
}

/**
 * Condensed SDR dongle panel on the pass page.
 *
 * Hidden while no dongle is plugged in. Then: receive button, actual frequency
 * (Doppler included), level, the three toggles used mid-pass (audio, SSTV,
 * MP3) and gain. The icon turns green while receiving.
 */
@Composable
fun SdrInline(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val st by SdrHub.state.collectAsState()

    // The dongle may be plugged in mid-pass: poll rather than wait for an
    // event, as on the full SDR screen.
    var present by remember { mutableStateOf(SdrHub.devicePresent(ctx) != null) }
    LaunchedEffect(Unit) {
        while (true) {
            present = SdrHub.devicePresent(ctx) != null
            delay(2000)
        }
    }
    // **Say the dongle is missing when it was expected.** Staying silent is
    // right when the dongle has no role. But with "FT-817 + SDR dongle" the
    // operator expects a waterfall, and its absence looks like a software bug
    // when it's a cable. Name the missing condition.
    if (!present && !st.running) {
        if (ui.rigModel == "FT817TX") {
            Text(t("sdr_absente"), color = Amber, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        return
    }

    val rx = st.running
    val accent = if (rx) Aurora else Cyan

    Surface(color = accent.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(10.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Surface(color = accent.copy(alpha = 0.20f), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        if (rx) t("sdr_inline_title") + " · " + t("sdr_rx")
                        else t("sdr_inline_title"),
                        color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.openSdr() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Tune, t("sdr_full"), tint = TextLo,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { if (rx) vm.stopSdr() else vm.startSdr() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (rx) Icons.Default.Stop else Icons.Default.PlayArrow,
                        if (rx) t("sdr_stop") else t("sdr_start"),
                        tint = if (rx) Magenta else Cyan,
                        modifier = Modifier.size(22.dp))
                }
            }

            if (rx) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Always the sky frequency, never the IF: that's what you
                    // compare with the pass panel above.
                    Text("%.4f MHz".format(vm.cleVersSat(st.centerHz) / 1_000_000.0),
                        color = Aurora, fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text((if (st.dopplerHz >= 0) "+" else "") + "${st.dopplerHz} Hz",
                        color = TextLo, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                // **What the dongle is actually tuned to.** With a converter in
                // the chain, the sky frequency above is not what the dongle
                // receives, and without this line you could not tell whether
                // it was looking at 144, 739, or 10 489 MHz (which no dongle
                // reaches). Shown only when a converter applies to the dongle;
                // otherwise both numbers are identical.
                if (vm.convertisseurSurLaCle) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("sdr_sur_cle"), color = TextLo, fontSize = 10.sp)
                        Spacer(Modifier.weight(1f))
                        Text("%.4f MHz".format(st.centerHz / 1_000_000.0),
                            color = Aurora.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
                // **Mode and bandwidth here too.** It's while listening that you
                // find the filter too narrow or the mode wrong (on QO-100 FT8
                // needs 3 kHz, voice 2.4); a too-tight filter clips the signal
                // and it just seems weak. Fixing it on another screen loses the pass.
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    fr.f4ioz.satcombo.sdr.RxMode.entries.forEach { m ->
                        FilterChip(
                            selected = ui.sdrMode == m.name,
                            onClick = { vm.setSdrMode(m) },
                            label = { Text(modeLabel(m), fontSize = 10.sp) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BANDWIDTHS.forEach { b ->
                        FilterChip(
                            selected = ui.sdrBandwidthHz == b,
                            onClick = { vm.setSdrBandwidth(b) },
                            // Zero means "let the mode decide"; say so rather
                            // than show "0 Hz".
                            label = {
                                Text(if (b == 0) t("sdr_bw_auto") else "${b / 1000.0} k",
                                    fontSize = 10.sp)
                            })
                    }
                }

                Spacer(Modifier.height(6.dp))
                InlineLevelBar(st.levelDb)

                // Small waterfall only: no room for a spectrum on the pass page,
                // but it's enough to see the satellite arrive and tap onto it.
                if (ui.sdrInlineWaterfall) {
                    val spec by SdrHub.spectrum.collectAsState()
                    Spacer(Modifier.height(6.dp))
                    SpectrumWaterfall(
                        spectrum = spec,
                        fullSpanHz = if (st.spanHz > 0.0) st.spanHz else 176_400.0,
                        spanHz = ui.sdrSpanHz,
                        offsetHz = st.offsetHz,
                        bandwidthHz = effectiveBw(ui.sdrMode, ui.sdrBandwidthHz),
                        spectrumHeight = 0.dp,
                        waterfallHeight = 64.dp,
                        onTune = { vm.setSdrOffset(it) })
                }

                // Vernier under the waterfall. On the waterfall a finger picks
                // an absolute spot and hides the very signal you aim at. The
                // vernier nudges frequency (Hz per cm) without hiding anything:
                // for residual Doppler or settling on a sideband while tracking.
                if (ui.accord.vernier) {
                    Spacer(Modifier.height(6.dp))
                    Vernier(
                        // The dial is graduated in what it moves: the
                        // transponder channel if any, else the dongle tuning.
                        freqHz = ui.rxRestHz ?: (st.centerHz + st.offsetHz),
                        hzParCm = ui.accord.vernierHzParCm,
                        onRapport = { vm.setSdrVernierRatio(it) },
                        onDelta = { vm.vernierPas(it) },
                        compact = true)
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(t("sdr_plug_hint"), color = TextLo, fontSize = 11.sp)
            }

            st.error?.let { e ->
                Spacer(Modifier.height(6.dp))
                Text(t("sdr_err_$e"), color = Magenta, fontSize = 11.sp)
            }
            if (st.awaitingPermission) {
                Spacer(Modifier.height(6.dp))
                Text(t("sdr_permission_wait"), color = Cyan, fontSize = 11.sp)
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniChip(t("sdr_audio"), ui.sdrAudio) { vm.setSdrAudio(!ui.sdrAudio) }
                MiniChip(t("sdr_sstv"), ui.sdrSstv) { vm.setSdrSstv(!ui.sdrSstv) }
                MiniChip(t("sdr_record"), ui.sdrRecord) { vm.setSdrRecord(!ui.sdrRecord) }
            }

            // Gain: the one setting really touched mid-pass, as the signal goes
            // from nothing to saturated.
            Spacer(Modifier.height(6.dp))
            val gains = remember { listOf<Int?>(null) + RtlTuning.GAINS.toList().filter { it > 0 } }
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                gains.forEach { g ->
                    val sel = (g == null && st.gainTenthDb == null) ||
                              (g != null && st.gainTenthDb == g)
                    MiniChip(
                        if (g == null) t("sdr_gain_auto") else "%.1f dB".format(g / 10.0),
                        sel) { vm.setSdrGain(g) }
                }
            }
        }
    }
}

@Composable
private fun MiniChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 10.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Cyan,
            selectedLabelColor = SpaceBg))
}

/** Compact level bar: -80 dBFS left, 0 dBFS right. */
@Composable
private fun InlineLevelBar(levelDb: Float) {
    val frac = ((levelDb + 80f) / 80f).coerceIn(0f, 1f)
    val color = when {
        frac > 0.9f -> Magenta
        frac > 0.15f -> Aurora
        else -> TextLo
    }
    Box(Modifier.fillMaxWidth().height(5.dp)
        .clip(RoundedCornerShape(3.dp)).background(SpaceSurface)) {
        Box(Modifier.fillMaxWidth(frac).height(5.dp)
            .clip(RoundedCornerShape(3.dp)).background(color))
    }
}
