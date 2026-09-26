/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sdr.RtlTuning
import fr.f4ioz.satcombo.sdr.RxMode
import fr.f4ioz.satcombo.sdr.SdrHub
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.delay

/**
 * RTL-SDR dongle screen.
 *
 * Plug in, start, see it receive — then pick mode, channel width, squelch,
 * and tap the waterfall to tune. NFM covers FM transponders, but linear
 * transponders (RS-44, FO-xx) are SSB only: without SSB you hear Donald Duck.
 *
 * Everything here applies live: fine tuning goes through the chain's software
 * oscillator, not the tuner PLL, so it's instant and never drops the stream.
 */
@Composable
fun SdrScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val st by SdrHub.state.collectAsState()

    // The dongle may be plugged in after the screen opens: poll rather than
    // wait for a system event.
    var present by remember { mutableStateOf(SdrHub.devicePresent(ctx) != null) }
    var volumeZero by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            present = SdrHub.devicePresent(ctx) != null
            volumeZero = SdrHub.musicVolumeZero(ctx)
            delay(1500)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // -------------------------------------------------- intro
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("sdr_intro"), color = TextLo, fontSize = 12.sp)
                }
            }
        }

        // --------------------------------------------------------- dongle
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Usb, null,
                            tint = if (st.running) Aurora else if (present) Cyan else TextLo,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                if (present) t("sdr_device") else t("sdr_no_device"),
                                color = if (present) TextHi else TextLo,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            st.deviceName?.let {
                                Text(it, color = TextLo, fontSize = 11.sp)
                            }
                        }
                    }

                    st.error?.let { e ->
                        Spacer(Modifier.height(8.dp))
                        Text(t("sdr_err_$e"), color = Magenta, fontSize = 12.sp)
                    }
                    if (st.awaitingPermission) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("sdr_permission_wait"), color = Cyan, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(10.dp))
                    if (!st.running) {
                        Button(
                            onClick = { vm.startSdr() },
                            enabled = present,
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("sdr_start"))
                        }
                    } else {
                        Button(
                            onClick = { vm.stopSdr() },
                            colors = ButtonDefaults.buttonColors(containerColor = Magenta),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("sdr_stop"))
                        }
                    }
                    if (ui.selected == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("sdr_no_sat"), color = TextLo, fontSize = 11.sp)
                    }
                }
            }
        }

        // ------------------------------------------------------- reception
        if (st.running) {
            item {
                Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SettingsInputAntenna, null,
                                tint = Aurora, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            // With an LNB the dongle sits at 739 MHz and the
                            // satellite at 10 489: show the sky frequency big,
                            // the IF only below.
                            Text(mhz(vm.cleVersSat(st.centerHz + st.offsetHz)), color = Aurora,
                                fontWeight = FontWeight.Bold, fontSize = 22.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (vm.convertisseurSurLaCle) {
                            InfoLine(t("sdr_if"), mhz(st.centerHz + st.offsetHz))
                        }
                        InfoLine(t("sdr_rest"), mhz(vm.cleVersSat(st.restHz)))
                        if (st.offsetHz != 0) {
                            InfoLine(t("sdr_offset"),
                                (if (st.offsetHz > 0) "+" else "") + "${st.offsetHz} Hz")
                        }
                        // The correction actually in effect, not the last value
                        // written to the dongle: tracking slides the software
                        // offset instead of reprogramming the PLL, so the dongle
                        // frequency alone would lie.
                        val dop = st.centerHz + st.dopplerFineHz - st.restHz
                        InfoLine(t("sdr_doppler"),
                            (if (dop >= 0) "+" else "") + "$dop Hz")
                        if (ui.sdrDopplerTrack) {
                            // The PLL recentre count is the real test: a whole
                            // pass should stay at zero or one.
                            InfoLine(
                                t("sdr_track"),
                                tf("sdr_track_val", st.dopplerFineHz, st.pllWrites))
                        }
                        InfoLine(t("sdr_rate"),
                            "%.3f Ms/s".format(st.sampleRate / 1_000_000.0))
                        InfoLine(t("sdr_data"), "%.1f Mo".format(st.mbRead))

                        Spacer(Modifier.height(10.dp))
                        Text(t("sdr_level") + "  %.0f dBFS".format(st.levelDb),
                            color = TextLo, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        LevelBar(st.levelDb)

                        // Demodulated audio meter. High RF with flat AF means an
                        // unmodulated carrier — or a saturated front end.
                        Spacer(Modifier.height(8.dp))
                        Text(t("sdr_af_level"), color = TextLo, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        AfBar(st.afLevel)

                        if (st.clipping) {
                            Spacer(Modifier.height(8.dp))
                            Text(t("sdr_overload"), color = Magenta, fontSize = 11.sp)
                        }

                        if (volumeZero && st.audio) {
                            Spacer(Modifier.height(8.dp))
                            Text(t("sdr_volume_zero"), color = Amber, fontSize = 11.sp)
                        }
                        if (st.sstv) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { vm.openSstv() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ImageIcon, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("sdr_open_sstv"))
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------- spectrum and waterfall
        if (st.running) {
            item {
                val spec by SdrHub.spectrum.collectAsState()
                Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(t("sdr_spectrum"), color = TextHi,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        SpectrumWaterfall(
                            spectrum = spec,
                            fullSpanHz = if (st.spanHz > 0.0) st.spanHz else 176_400.0,
                            spanHz = ui.sdrSpanHz,
                            offsetHz = st.offsetHz,
                            bandwidthHz = effectiveBw(ui.sdrMode, ui.sdrBandwidthHz),
                            onTune = { vm.setSdrOffset(it) })
                        Spacer(Modifier.height(6.dp))
                        Text(t("sdr_tune_hint"), color = TextLo, fontSize = 11.sp)

                        // ---------------------------------- fine tuning
                        // Below the waterfall: look wide to find, then narrow to
                        // settle; screen order follows the gesture.
                        if (ui.accord.loupe) {
                            Spacer(Modifier.height(10.dp))
                            Text(t("accord_loupe"), color = TextHi, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            SpectrumWaterfall(
                                spectrum = spec,
                                fullSpanHz = if (st.spanHz > 0.0) st.spanHz else 176_400.0,
                                spanHz = ui.accord.loupeSpanHz,
                                offsetHz = st.offsetHz,
                                bandwidthHz = effectiveBw(ui.sdrMode, ui.sdrBandwidthHz),
                                spectrumHeight = 64.dp,
                                waterfallHeight = 54.dp,
                                onTune = { vm.setSdrOffset(it) })
                        }

                        if (ui.accord.vernier) {
                            Spacer(Modifier.height(10.dp))
                            Text(t("accord_vernier"), color = TextHi, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Vernier(
                                freqHz = st.centerHz + st.offsetHz,
                                hzParCm = ui.accord.vernierHzParCm,
                                onRapport = { vm.setSdrVernierRatio(it) },
                                onDelta = { vm.sdrPasFin(it) })
                        }

                        if (ui.accord.calageVoix) {
                            Spacer(Modifier.height(10.dp))
                            val utile = fr.f4ioz.satcombo.domain.AccordFin
                                .calageUtile(ui.sdrMode)
                            OutlinedButton(
                                onClick = { vm.sdrCaleSurLaVoix() },
                                enabled = utile,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(t("accord_cale_btn"), fontSize = 13.sp) }
                            if (!utile) {
                                Text(t("accord_cale_hint"), color = TextLo, fontSize = 10.sp)
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(
                                t("sdr_offset") + "  " +
                                    (if (st.offsetHz > 0) "+" else "") + "${st.offsetHz} Hz",
                                color = TextHi, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { vm.tuneSdrPeak(ui.sdrSpanHz) },
                                    contentPadding =
                                        PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) { Text(t("sdr_peak"), fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = { vm.setSdrOffset(0) },
                                    contentPadding =
                                        PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) { Text(t("sdr_recenter"), fontSize = 12.sp) }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(t("sdr_span"), color = TextHi, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SPANS.forEach { sp ->
                                FilterChip(
                                    selected = ui.sdrSpanHz == sp,
                                    onClick = { vm.setSdrSpan(sp) },
                                    label = { Text(khz(sp), fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cyan,
                                        selectedLabelColor = SpaceBg))
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------- settings
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    ToggleLine(t("sdr_audio"), ui.sdrAudio) { vm.setSdrAudio(it) }
                    ToggleLine(t("sdr_record"), ui.sdrRecord) { vm.setSdrRecord(it) }
                    ToggleLine(t("sdr_sstv"), ui.sdrSstv) { vm.setSdrSstv(it) }
                    ToggleLine(t("sdr_agc"), ui.sdrAgc) { vm.setSdrAgc(it) }
                    ToggleLine(t("sdr_deemph"), ui.sdrDeemph) { vm.setSdrDeemph(it) }
                    ToggleLine(t("sdr_track_sw"), ui.sdrDopplerTrack) {
                        vm.setSdrDopplerTrack(it)
                    }
                    ToggleLine(t("set_sdr_inline_wf"), ui.sdrInlineWaterfall) {
                        vm.setSdrInlineWaterfall(it)
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(t("sdr_mode"), color = TextHi, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RxMode.entries.forEach { m ->
                            FilterChip(
                                selected = ui.sdrMode == m.name,
                                onClick = { vm.setSdrMode(m) },
                                label = { Text(modeLabel(m), fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Aurora,
                                    selectedLabelColor = SpaceBg))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(t("sdr_bw"), color = TextHi, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BANDWIDTHS.forEach { b ->
                            FilterChip(
                                selected = ui.sdrBandwidthHz == b,
                                onClick = { vm.setSdrBandwidth(b) },
                                label = {
                                    Text(
                                        if (b == 0) t("sdr_bw_auto") else khz(b),
                                        fontSize = 11.sp)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan,
                                    selectedLabelColor = SpaceBg))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        t("sdr_squelch") + "  " +
                            (if (ui.sdrSquelchDb <= -120) t("sdr_squelch_off")
                             else "${ui.sdrSquelchDb} dBFS"),
                        color = TextHi, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SQUELCHES.forEach { q ->
                            FilterChip(
                                selected = ui.sdrSquelchDb == q,
                                onClick = { vm.setSdrSquelch(q) },
                                label = {
                                    Text(
                                        if (q <= -120) t("sdr_squelch_off") else "$q",
                                        fontSize = 11.sp)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = SpaceBg))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(t("sdr_gain"), color = TextHi, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    val gains = remember {
                        listOf<Int?>(null) + RtlTuning.GAINS.toList().filter { it > 0 }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        gains.forEach { g ->
                            val sel = (g == null && st.gainTenthDb == null) ||
                                      (g != null && st.gainTenthDb == g)
                            FilterChip(
                                selected = sel,
                                onClick = { vm.setSdrGain(g) },
                                label = {
                                    Text(
                                        if (g == null) t("sdr_gain_auto")
                                        else "%.1f dB".format(g / 10.0),
                                        fontSize = 11.sp)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan,
                                    selectedLabelColor = SpaceBg))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("${t("sdr_ppm")}  ${ui.sdrPpm}", color = TextHi, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(-10, -1, 1, 10).forEach { d ->
                            OutlinedButton(
                                onClick = { vm.setSdrPpm(ui.sdrPpm + d) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) { Text(if (d > 0) "+$d" else "$d", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

private fun mhz(hz: Long): String = "%.4f MHz".format(hz / 1_000_000.0)

/** Spectrum display spans offered. */
private val SPANS = listOf(6_000, 12_000, 24_000, 48_000, 96_000, 176_400)

/**
 * Channel widths offered. Zero lets the mode decide, usually the right
 * choice: 2.4 kHz SSB, 16 kHz NFM.
 */
internal val BANDWIDTHS = listOf(0, 1_200, 1_800, 2_400, 3_000, 6_000, 9_000, 12_000, 16_000, 24_000)

/** Squelch thresholds in dBFS; the first one disables it. */
private val SQUELCHES = listOf(-120, -60, -55, -50, -45, -40, -35, -30, -25, -20)

private fun khz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000.0)

@Composable
internal fun modeLabel(m: RxMode): String = when (m) {
    RxMode.NFM -> t("sdr_mode_nfm")
    RxMode.USB -> t("sdr_mode_usb")
    RxMode.LSB -> t("sdr_mode_lsb")
    RxMode.AM -> t("sdr_mode_am")
}

/**
 * Width actually used by the chain, for the channel band over the waterfall.
 * Must stay in sync with RxChain.effectiveBandwidthHz.
 */
internal fun effectiveBw(mode: String, bandwidthHz: Int): Double {
    if (bandwidthHz > 0) return bandwidthHz.coerceIn(500, 24_000).toDouble()
    return when (mode) {
        "USB", "LSB" -> 2_400.0
        "AM" -> 6_000.0
        else -> 16_000.0
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextLo, fontSize = 12.sp)
        Text(value, color = TextHi, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextHi, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
    }
}

/**
 * Demodulated audio meter, linear 0 to full scale. Answers what the RF level
 * can't: "strong, but is there anything in it?" On a bare carrier or a
 * saturated front end it stays at the floor while the RF bar is maxed.
 */
@Composable
private fun AfBar(level: Float) {
    val frac = level.coerceIn(0f, 1f)
    val color = when {
        frac > 0.95f -> Magenta
        frac > 0.03f -> Cyan
        else -> TextLo
    }
    Box(Modifier.fillMaxWidth().height(8.dp)
        .clip(RoundedCornerShape(4.dp)).background(SpaceSurface)) {
        Box(Modifier.fillMaxWidth(frac).height(8.dp)
            .clip(RoundedCornerShape(4.dp)).background(color))
    }
}

/** Simple level bar: -80 dBFS left, 0 dBFS right. */
@Composable
private fun LevelBar(levelDb: Float) {
    val frac = ((levelDb + 80f) / 80f).coerceIn(0f, 1f)
    val color = when {
        frac > 0.9f -> Magenta
        frac > 0.15f -> Aurora
        else -> TextLo
    }
    Box(Modifier.fillMaxWidth().height(8.dp)
        .clip(RoundedCornerShape(4.dp)).background(SpaceSurface)) {
        Box(Modifier.fillMaxWidth(frac).height(8.dp)
            .clip(RoundedCornerShape(4.dp)).background(color))
    }
}
