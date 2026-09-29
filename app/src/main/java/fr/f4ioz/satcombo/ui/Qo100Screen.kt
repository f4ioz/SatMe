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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SoleilQo100
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * The QO-100 screen.
 *
 * Separate from the satellite detail page because QO-100 has none of what
 * makes one: no pass time, no countdown, no ground track, no moving Doppler.
 * The operator's work is four gestures: pick a frequency in the transponder,
 * check the rig and dongle follow, aim the dish once, calibrate on the beacon.
 * The screen is ordered that way, most frequent first: the frequency on top
 * (the only thing touched during a QSO), dish aiming at the bottom.
 *
 * ### Which frequencies are shown
 *
 * The big figures are **sky frequencies** (10 489.750 for the middle beacon),
 * as in every log and published band plan. What the rig and dongle actually
 * show (e.g. 145.750 / 432.250) is printed smaller below, as a check: the
 * conversion is plumbing, not the subject.
 *
 * When a converter is missing or does not cover the band, the rig line turns
 * amber instead of disappearing: a field that lights up says where to look.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Qo100Screen(ui: UiState, vm: MainViewModel) {
    val q = ui.qo100
    val tp = Qo100.TRANSPONDEURS.firstOrNull { it.cle == q.transpondeur } ?: Qo100.NB

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        // ---- header -------------------------------------------------------
        //
        // Big name: the screen is reached several ways and looks like no
        // other; knowing at a glance it is QO-100 saves looking for a
        // countdown that will never exist.
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.SatelliteAlt, null, tint = Cyan,
                    modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("QO-100", color = Cyan, fontSize = 26.sp,
                        fontWeight = FontWeight.Bold)
                    Text(t("qo100_sous_titre"), color = TextLo, fontSize = 11.sp)
                }
                // Chain indicator: green when the rig can really reach the
                // displayed frequency. The only check possible before hearing
                // anything.
                Icon(
                    if (q.posteAtteignable) Icons.Default.CheckCircle
                    else Icons.Default.Warning,
                    null,
                    tint = if (q.posteAtteignable) Aurora else Amber,
                    modifier = Modifier.size(22.dp))
            }
        }

        // ---- frequency ----------------------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("qo100_downlink"), color = TextLo, fontSize = 11.sp)
                Text(qoMhz(q.descenteHz), color = Cyan, fontSize = 30.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(2.dp))
                Text(t("qo100_uplink") + "  " +
                    qoMhz(Qo100.monteeDepuisDescente(q.descenteHz)),
                    color = TextLo, fontSize = 13.sp, fontFamily = FontFamily.Monospace)

                Spacer(Modifier.height(12.dp))
                // Steps: 10 kHz to cross the transponder, 1 kHz to land on a
                // station, 100 Hz to fine-tune SSB.
                PasLigne(vm, 10_000L)
                Spacer(Modifier.height(6.dp))
                PasLigne(vm, 1_000L)
                Spacer(Modifier.height(6.dp))
                PasLigne(vm, 100L)

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::qo100AllerBalise,
                        modifier = Modifier.weight(1f)) {
                        Text(t("qo100_go_beacon"), fontSize = 13.sp,
                            color = if (q.surBalise) Aurora else Cyan)
                    }
                    OutlinedButton(
                        onClick = { vm.setQo100Descente(tp.centreDescenteHz) },
                        modifier = Modifier.weight(1f)) {
                        Text(t("qo100_go_centre"), fontSize = 13.sp)
                    }
                }

                // Distance to the transponder edges. Without it an operator
                // 3 kHz from the edge does not know it, and on a geostationary
                // satellite no end of pass reveals the mistake.
                Spacer(Modifier.height(10.dp))
                val depuisBas = (q.descenteHz - tp.descenteBasHz) / 1000
                val restant = (tp.descenteHautHz - q.descenteHz) / 1000
                Text("${t("qo100_edges")}  −${depuisBas} kHz / +${restant} kHz",
                    color = if (depuisBas < 5 || restant < 5) Amber else TextLo,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // ---- what the hardware sees ---------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("qo100_hardware"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(t("qo100_hardware_desc"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))

                Interrupteur(t("qo100_to_rig"), q.auPoste, vm::setQo100AuPoste)
                if (q.auPoste) {
                    LigneMateriel(t("qo100_rig_rx"), q.posteRxHz, q.posteAtteignable)
                    LigneMateriel(t("qo100_rig_tx"), q.posteTxHz, q.posteAtteignable)
                    if (!q.posteAtteignable) {
                        Text(t("qo100_rig_unreachable"), color = Amber, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = Color(0x22FFFFFF))
                Spacer(Modifier.height(4.dp))

                Interrupteur(t("qo100_to_dongle"), q.aLaCle, vm::setQo100ALaCle)
                if (q.aLaCle) {
                    LigneMateriel(t("qo100_dongle_rx"), q.cleRxHz, q.cleAtteignable)
                    if (!q.cleAtteignable) {
                        Text(t("qo100_dongle_unreachable"), color = Amber, fontSize = 11.sp)
                    }
                }

                if (q.statut.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(q.statut, color = Amber, fontSize = 11.sp)
                }
            }
        }

        // ---- memories -----------------------------------------------------
        //
        // Band plan markers are read-only (published facts, not preferences);
        // operator memories are added after them and removed with a long
        // press.
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("qo100_memoires"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                val toutes = fr.f4ioz.satcombo.domain.MemoiresQo100
                    .toutes(q.memoires)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toutes.forEach { m ->
                        val ici = kotlin.math.abs(m.hz - q.descenteHz) < 500L
                        AssistChip(
                            onClick = { vm.qo100Aller(m.hz) },
                            label = {
                                Text(m.libelle { c -> t(c) }, fontSize = 11.sp,
                                    color = if (ici) Aurora else Cyan)
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (ici) SpaceCard else Color.Transparent))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.qo100PoseMemoire("") },
                        modifier = Modifier.weight(1f)) {
                        Text(t("qo100_mem_poser"), fontSize = 12.sp, color = Cyan)
                    }
                    OutlinedButton(onClick = { vm.qo100RetireMemoire(q.descenteHz) },
                        modifier = Modifier.weight(1f)) {
                        Text(t("qo100_mem_retirer"), fontSize = 12.sp, color = TextLo)
                    }
                }
            }
        }

        // ---- transponder ruler --------------------------------------------
        // Narrowband only: the 8 MHz wideband has no segmented band plan.
        if (q.transpondeur == Qo100.NB.cle) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("qo100_ruler"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(t("qo100_ruler_desc"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))

                    Reglette(q.descenteHz) { vm.setQo100Descente(it) }

                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(qoMhz(Qo100.REGLETTE_BAS_HZ), color = TextLo,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(qoMhz(Qo100.REGLETTE_HAUT_HZ), color = TextLo,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    Spacer(Modifier.height(10.dp))
                    val seg = Qo100.segment(q.descenteHz)
                    if (seg == null) {
                        // Off the ruler. Should not happen (the model clamps
                        // the frequency), but say so anyway.
                        Text(t("qo100_warn_hors"), color = Amber, fontSize = 12.sp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t("qo100_ruler_here"), color = TextLo, fontSize = 10.sp)
                                Text(t("qo100_seg_" + seg.cle),
                                    color = couleurUsage(seg.usage),
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            if (seg.largeurMaxHz > 0) {
                                Text(tf("qo100_seg_width", seg.largeurMaxHz),
                                    color = TextLo, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(t("qo100_warn_" + seg.usage.cleAvertissement),
                            color = if (seg.emissionPermise) TextLo else Amber,
                            fontSize = 11.sp)
                        if (!seg.emissionPermise) {
                            Spacer(Modifier.height(6.dp))
                            Text(t("qo100_tx_forbidden"), color = Amber,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ---- panorama and beacon indicator --------------------------------
        // Same condition as the ruler: the panorama shares its axis. On the
        // wideband there is no common scale and no beacon to watch.
        if (q.transpondeur == Qo100.NB.cle) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("qo100_pano"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(t("qo100_pano_desc"), color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))

                    val pan by fr.f4ioz.satcombo.sdr.SdrHub.panorama.collectAsState()
                    val axe = vm.qo100AxeCiel()
                    if (pan.isEmpty() || axe == null) {
                        Text(t("qo100_pano_off"), color = TextLo, fontSize = 12.sp)
                    } else {
                        PanoramaQo100(
                            pan = pan,
                            centreCielHz = axe.first,
                            etendueCielHz = axe.second,
                            descenteHz = q.descenteHz,
                        ) { vm.setQo100Descente(it) }
                    }

                    // ------------------------------------- fine tuning
                    // The panorama spreads 490 kHz over the screen (~1.4 kHz
                    // per dp, a dozen kHz under a fingertip): it is for
                    // finding. What follows is for landing.
                    val stAf by fr.f4ioz.satcombo.sdr.SdrHub.state.collectAsState()
                    if (ui.accord.loupe && stAf.running) {
                        Spacer(Modifier.height(12.dp))
                        Text(t("accord_loupe"), color = TextHi, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        val spec by fr.f4ioz.satcombo.sdr.SdrHub.spectrum.collectAsState()
                        SpectrumWaterfall(
                            spectrum = spec,
                            fullSpanHz = if (stAf.spanHz > 0.0) stAf.spanHz else 176_400.0,
                            spanHz = ui.accord.loupeSpanHz,
                            offsetHz = stAf.offsetHz,
                            bandwidthHz = 2_400.0,
                            spectrumHeight = 64.dp,
                            waterfallHeight = 54.dp,
                            // The tap gives an offset from the dongle tuning;
                            // this page only knows downlink frequencies, so
                            // convert rather than keep two numbers side by side.
                            onTune = { vm.qo100Pas((it - stAf.offsetHz).toLong()) })
                    }

                    if (ui.accord.vernier) {
                        Spacer(Modifier.height(12.dp))
                        Text(t("accord_vernier"), color = TextHi, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Vernier(
                            freqHz = q.descenteHz,
                            hzParCm = ui.accord.vernierHzParCm,
                            onRapport = { vm.setSdrVernierRatio(it) },
                            onDelta = { vm.qo100Pas(it) })
                    }

                    if (ui.accord.calageVoix && stAf.running) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { vm.qo100CaleSurLaVoix() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(t("accord_cale_btn"), fontSize = 13.sp) }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0x22FFFFFF))
                    Spacer(Modifier.height(12.dp))

                    // --- beacon indicator ---
                    // The middle beacon is always on at a frequency known to
                    // the hertz: a QO-100 station's only reference. Two
                    // different figures come out of it. The offset is LNB
                    // drift, fixed with a button. The SNR is reception quality
                    // (aiming or cabling), fixed only by hand at the mast.
                    Text(t("qo100_beacon"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    val b = q.balise
                    if (b == null) {
                        Text(t("qo100_beacon_none"), color = TextLo, fontSize = 12.sp)
                    } else {
                        val bon = fr.f4ioz.satcombo.domain.MesureBalise.calageSuffisant(b)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(t("qo100_beacon_offset"), color = TextLo,
                                fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(qoHzSigne(b.ecartArrondiHz),
                                color = if (bon) Aurora else Amber,
                                fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(t("qo100_beacon_snr"), color = TextLo,
                                fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(String.format(Locale.US, "%.0f dB", b.rapportDb),
                                color = if (b.rapportDb >= 15f) Aurora else Amber,
                                fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(if (bon) t("qo100_beacon_ok") else t("qo100_beacon_drift"),
                            color = if (bon) Aurora else TextLo, fontSize = 11.sp)
                        if (!bon) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = vm::qo100CalerSurLaMesure,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                                Text(t("qo100_beacon_apply"),
                                    color = Color.Black, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // ---- beacon calibration -------------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("qo100_calib"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(t("qo100_calib_desc"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("qo100_calib_current"), color = TextLo,
                        fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(qoKhzSigne(q.calageHz),
                        color = if (q.calageHz == 0L) TextLo else Aurora,
                        fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(10.dp))
                // Entered as a sky frequency, read off the waterfall: no
                // subtraction for the operator.
                var entendu by remember(q.calageHz) {
                    mutableStateOf(qoMhz(Qo100.BALISE_MEDIANE_HZ + q.calageHz))
                }
                OutlinedTextField(
                    value = entendu,
                    onValueChange = { entendu = it },
                    label = { Text(t("qo100_calib_heard"), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            qoDepuisMhz(entendu)?.let { vm.qo100CalerSurLaBalise(it) }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Text(t("qo100_calib_apply"), color = Color.Black, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = vm::qo100AnnulerCalage,
                        modifier = Modifier.weight(1f)) {
                        Text(t("qo100_calib_clear"), fontSize = 13.sp)
                    }
                }
            }
        }

        // ---- transponder --------------------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("qo100_transponder"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Qo100.TRANSPONDEURS.forEach { candidat ->
                    val choisi = candidat.cle == q.transpondeur
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.setQo100Transpondeur(candidat.cle) }
                            .padding(vertical = 8.dp, horizontal = 4.dp)) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp))
                            .background(if (choisi) Aurora else Color(0x33FFFFFF)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("qo100_tp_" + candidat.cle),
                                color = if (choisi) TextHi else TextLo, fontSize = 13.sp,
                                fontWeight = if (choisi) FontWeight.Bold else FontWeight.Normal)
                            Text("${qoMhz(candidat.descenteBasHz)} – ${qoMhz(candidat.descenteHautHz)}",
                                color = TextLo, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(t("qo100_wb_note"), color = TextLo, fontSize = 11.sp)
            }
        }

        // ---- station settings, collapsed ----------------------------------
        //
        // Converters live here, not in the general settings: a 10 345 MHz
        // oscillator only makes sense for QO-100.
        var chaineOuverte by rememberSaveable { mutableStateOf(false) }
        val chaineActive = fr.f4ioz.satcombo.domain.ChaineQo100
            .choisie(q.chaines, q.chaine)
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { chaineOuverte = !chaineOuverte }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Tune, null, tint = TextLo,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("qo100_chaines"), color = TextHi,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        // Summary readable without expanding: the check before
                        // transmitting.
                        Text(
                            chaineActive.nom + " · " +
                                (if (chaineActive.descenteActive)
                                    "%.3f".format(chaineActive.descenteOlHz / 1e6)
                                else t("qo100_ol_absent")) + " / " +
                                (if (chaineActive.monteeActive)
                                    "%.3f".format(chaineActive.monteeOlHz / 1e6)
                                else t("qo100_ol_absent")),
                            color = TextLo, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    Icon(
                        if (chaineOuverte) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore, null, tint = TextLo)
                }
                if (chaineOuverte) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                        Text(t("qo100_chaines_desc"), color = TextLo, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            q.chaines.forEach { c ->
                                FilterChip(
                                    selected = c.nom == q.chaine,
                                    onClick = { vm.setQo100Chaine(c.nom) },
                                    label = { Text(c.nom, fontSize = 11.sp) })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // Leave the transponder to listen elsewhere. The clamp
                        // keeps the carrier out of neighbouring bands, but also
                        // prevented **listening** (beacon search, wideband,
                        // a station that moved). Off by default, so the guard
                        // stays unless asked.
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { vm.setQo100SansBride(!q.sansBride) }
                                .padding(vertical = 4.dp)) {
                            Icon(
                                if (q.sansBride) Icons.Default.CheckCircle
                                else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint = if (q.sansBride) Amber else TextLo,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(t("qo100_sans_bride"), color = TextHi, fontSize = 12.sp)
                                Text(t("qo100_sans_bride_desc"), color = TextLo, fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0x22FFFFFF))
                        Spacer(Modifier.height(10.dp))
                        ChoixPoste(ui, vm)
                        Spacer(Modifier.height(10.dp))
                        ChoixMateriel(ui, vm)
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0x22FFFFFF))
                        Spacer(Modifier.height(10.dp))
                        ChoixConvertisseur(
                            vm, descente = true, olActuel = chaineActive.descenteOlHz,
                            reference = q.descenteHz)
                        Spacer(Modifier.height(10.dp))
                        MesureOl(vm, descente = true, ol = chaineActive.descenteOlHz)
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0x22FFFFFF))
                        Spacer(Modifier.height(10.dp))
                        if (chaineActive.monteeOlHz > 0L) {
                            Text(t("qo100_beta"), color = Amber, fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 6.dp))
                        }
                        ChoixConvertisseur(
                            vm, descente = false, olActuel = chaineActive.monteeOlHz,
                            reference = fr.f4ioz.satcombo.domain.Qo100.monteeDepuisDescente(q.descenteHz))
                        Spacer(Modifier.height(10.dp))
                        MesureOl(vm, descente = false, ol = chaineActive.monteeOlHz)
                    }
                }
            }
        }

        // ---- initial setup, collapsed -------------------------------------
        //
        // Dish aiming and sun alignment are used **once**: expanded, they
        // pushed down what is used every session. Collapsed, not removed:
        // when the station moves they are the first thing needed.
        var initialOuvert by rememberSaveable { mutableStateOf(false) }
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { initialOuvert = !initialOuvert }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SettingsInputAntenna, null, tint = TextLo,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("qo100_initial"), color = TextHi,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(t("qo100_initial_desc"), color = TextLo, fontSize = 11.sp)
                    }
                    Icon(
                        if (initialOuvert) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore,
                        null, tint = TextLo)
                }
            }
        }
        if (initialOuvert) {
        // ---- dish aiming ----------------------------------------------------
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("qo100_aim"), color = TextHi,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(t("qo100_aim_desc"), color = TextLo, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                if (q.elDeg == null) {
                    Text(t("qo100_aim_unknown"), color = TextLo, fontSize = 12.sp)
                } else if (q.elDeg <= 0.0) {
                    // From the Americas or the Pacific the satellite is below
                    // the horizon; no dish can help.
                    Text(t("qo100_aim_invisible"), color = Amber, fontSize = 13.sp)
                } else {
                    LigneAngle(t("qo100_aim_az"), q.azDeg)
                    LigneAngle(t("qo100_aim_el"), q.elDeg)
                    LigneAngle(t("qo100_aim_skew"), q.skewDeg)
                    Spacer(Modifier.height(8.dp))
                    Text(t("qo100_aim_skew_desc"), color = TextLo, fontSize = 11.sp)
                }
            }
        }

        // ---- sun alignment -------------------------------------------------
        // Hidden when the satellite is below the horizon.
        if (q.azDeg != null && q.elDeg != null && q.elDeg > 0.0) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("qo100_sun"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(t("qo100_sun_desc"), color = TextLo, fontSize = 11.sp)

                    val quand = tzFormat("EEE dd/MM HH:mm", ui.useUtc)

                    // --- daily: sun at the satellite azimuth ---
                    Spacer(Modifier.height(12.dp))
                    Text(t("qo100_sun_az"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    if (q.soleilAzimutMs == null) {
                        Text(t("qo100_sun_wait"), color = TextLo, fontSize = 12.sp)
                    } else {
                        Text(quand.format(Date(q.soleilAzimutMs)) + "  " + tzTag(ui.useUtc),
                            color = Cyan, fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(tf("qo100_sun_az_hint",
                            String.format(Locale.US, "%.0f",
                                SoleilQo100.azimutDeLOmbre(q.azDeg))),
                            color = TextLo, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0x22FFFFFF))
                    Spacer(Modifier.height(10.dp))

                    // --- fine alignment, twice a year (sun transits) ---
                    Text(t("qo100_sun_transit"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    if (q.soleilTransits.isEmpty()) {
                        Text(if (q.soleilAzimutMs == null) t("qo100_sun_wait")
                            else t("qo100_sun_none"), color = TextLo, fontSize = 12.sp)
                    } else {
                        q.soleilTransits.forEach { tr ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(quand.format(Date(tr.instantMs)),
                                    color = TextHi, fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f))
                                Text(tf("qo100_sun_gap",
                                    String.format(Locale.US, "%.2f", tr.ecartDeg)),
                                    color = Amber, fontSize = 11.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(tf("qo100_sun_dur", (tr.dureeS + 30L) / 60L),
                                    color = TextLo, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(t("qo100_sun_transit_hint"), color = TextLo, fontSize = 11.sp)
                    }
                }
            }
        }
    }
        }

        // ---- about this satellite -----------------------------------------
        //
        // The unmeasured-uplink warning sits **next to the uplink converter**,
        // where one can act, not at the top of the screen: a warning read ten
        // times without being actionable stops being read. It disappears once
        // that oscillator is measured.
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("qo100_desc"), color = TextLo, fontSize = 12.sp)
            }
        }
}

/**
 * Colour of a band plan usage on the ruler.
 *
 * Beacons magenta (never transmit there); reserved frequencies (broadcast,
 * emergency) amber, the app-wide "caution" colour; ordinary traffic cyan,
 * paler for digital than for voice. CW gets aurora: it fills the bottom of the
 * band in one block and should stand out.
 */
private fun couleurUsage(u: Qo100.Usage): Color = when (u) {
    Qo100.Usage.BALISE -> Magenta
    Qo100.Usage.CW -> Aurora
    Qo100.Usage.NUMERIQUE -> Cyan.copy(alpha = 0.45f)
    Qo100.Usage.PHONIE -> Cyan
    Qo100.Usage.DIFFUSION -> Amber.copy(alpha = 0.65f)
    Qo100.Usage.URGENCE -> Amber
    Qo100.Usage.MIXTE -> TextLo.copy(alpha = 0.45f)
}

/**
 * The 500 kHz narrowband transponder drawn to scale. The figure gives the
 * position; the ruler shows the neighbourhood (beacon 5 kHz away, digital
 * segment where SSB would be abuse, band edge).
 *
 * The bar holds the [Qo100.SEGMENTS] end to end; they join without gaps (a
 * test checks it to the kHz), so a missing colour shows as a black hole. Ticks
 * above mark beacons and reserved frequencies; a scale tick every 50 kHz below.
 * The cursor spans the full height with a dot on top, to stay visible on cyan.
 *
 * Tap or drag. About 1.5 kHz per pixel is coarse on purpose: the ruler is for
 * crossing the band, the step buttons for fine tuning. No clamping here:
 * [MainViewModel.setQo100Descente] already clamps, so the outer CW beacons are
 * visible stops you cannot land on.
 */
/**
 * Measuring an oscillator from two observed frequencies of **the same
 * signal**: the reference reading and the rig display. The app does the
 * subtraction, which never gets the sign wrong.
 *
 * The result is shown in full, not as "OK": a wrong but plausible oscillator
 * is only caught by ear when hunting the beacon, so the figure must be
 * comparable with the expected one.
 */
/**
 * The station rig, chosen here rather than in the CAT settings three screens
 * away: describing one installation should not mean crossing the app.
 *
 * **One place holds the value.** This selector writes the same setting as the
 * CAT settings, it does not copy it. Copies are what made the converters
 * diverge: two copies of a value end up disagreeing.
 */
@Composable
private fun ChoixMateriel(ui: UiState, vm: MainViewModel) {
    val q = ui.qo100
    Text(t("mat_titre"), color = TextHi,
        fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Spacer(Modifier.height(2.dp))
    Text(t("mat_desc"), color = TextLo, fontSize = 10.sp)
    Spacer(Modifier.height(6.dp))
    q.materiels.forEach { m ->
        LigneChoix(
            actif = q.materielPoste == m.nom,
            titre = m.nom + (if (m.reference) "  ·  " + t("mat_reference") else ""),
            detail = if (m.ppm == 0.0) "0 ppm" else "%+.2f ppm".format(m.ppm),
            onClick = { vm.setMaterielPoste(m.nom) })
    }
}

/**
 * The receiving device and its own offset.
 *
 * With a single LNB, the same measurement gives different oscillators on the
 * FT-817 and the dongle: the difference is the device's crystal and belongs to
 * it. Storing it in the chain would mix two independent errors, and fixing one
 * would shift the other. Zero by default, reference not adjustable: nothing
 * moves until measured.
 */
@Composable
private fun ChoixPoste(ui: UiState, vm: MainViewModel) {
    var avertirIc705 by remember { mutableStateOf(false) }
    Text(t("qo100_poste"), color = TextHi,
        fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Spacer(Modifier.height(2.dp))
    Text(t("qo100_poste_desc"), color = TextLo, fontSize = 10.sp)
    Spacer(Modifier.height(6.dp))
    listOf(
        "IC9700" to "Icom IC-9700",
        "FT817x2" to "2× Yaesu FT-817",
        fr.f4ioz.satcombo.FT817_IC705 to t("rig_ft817_ic705"),
        fr.f4ioz.satcombo.IC705_FT817 to t("rig_ic705_ft817"),
        "FT817TX" to t("qo100_poste_sdr"),
    ).forEach { (id, libelle) ->
        LigneChoix(
            actif = ui.rigModel == id,
            titre = libelle,
            detail = "",
            onClick = {
                vm.setRigModel(id)
                if (id == fr.f4ioz.satcombo.FT817_IC705 || id == fr.f4ioz.satcombo.IC705_FT817) avertirIc705 = true
            })
    }
    if (avertirIc705) AvertissementIc705 { avertirIc705 = false }
}

/**
 * Converter choice, showing **the resulting IF frequency**.
 *
 * Nobody recognises an 11-digit LO frequency, but everyone recognises
 * "144.750" on the rig front panel. Each option shows what the rig would
 * display for the current sky frequency, so you pick a result, not an LO.
 * The last option, "direct", removes the converter: rig or dongle then works
 * on the sky frequency.
 */
@Composable
private fun ChoixConvertisseur(
    vm: MainViewModel, descente: Boolean, olActuel: Long, reference: Long,
) {
    val presets = fr.f4ioz.satcombo.domain.Convertisseur.PRESETS
        .filter { it.descente == descente }

    Text(t(if (descente) "qo100_conv_rx" else "qo100_conv_tx"),
        color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Spacer(Modifier.height(2.dp))
    Text(t("qo100_conv_desc"), color = TextLo, fontSize = 10.sp)
    Spacer(Modifier.height(6.dp))

    presets.forEach { p ->
        // Frequency this option would give now. Out of range, show nothing
        // made up: the converter would not apply.
        val fi = if (reference > 0L) reference - p.olHz else 0L
        LigneChoix(
            actif = olActuel == p.olHz,
            titre = t("preset_" + p.cle),
            detail = if (fi > 0L) "%.3f MHz".format(fi / 1e6) else t("qo100_conv_hors"),
            onClick = { vm.qo100PoseOl(descente, p.olHz) })
    }
    LigneChoix(
        actif = olActuel == 0L,
        titre = t("qo100_conv_directe"),
        detail = if (reference > 0L) "%.3f MHz".format(reference / 1e6) else "",
        onClick = { vm.qo100PoseOl(descente, 0L) })
}

/** Choice row: a check mark, a name, and the result opposite. */
@Composable
private fun LigneChoix(
    actif: Boolean, titre: String, detail: String, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (actif) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            null,
            tint = if (actif) Cyan else TextLo,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(titre, color = if (actif) TextHi else TextLo, fontSize = 12.sp,
            modifier = Modifier.weight(1f))
        Text(detail, color = if (actif) Cyan else TextLo, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MesureOl(vm: MainViewModel, descente: Boolean, ol: Long) {
    var ciel by rememberSaveable(descente) { mutableStateOf("") }
    var poste by rememberSaveable(descente) { mutableStateOf("") }
    var message by rememberSaveable(descente) { mutableStateOf("") }
    var reussi by rememberSaveable(descente) { mutableStateOf(false) }

    Text(
        (if (descente) t("qo100_mesure_desc_rx") else t("qo100_mesure_desc_tx")) +
            " · " + (if (ol > 0L) "%.6f MHz".format(ol / 1e6) else t("qo100_ol_absent")),
        color = if (ol > 0L) Cyan else Amber,
        fontWeight = FontWeight.Bold, fontSize = 13.sp,
        fontFamily = FontFamily.Monospace)
    Spacer(Modifier.height(6.dp))
    Text(t("qo100_mesure_desc"), color = TextLo, fontSize = 10.sp)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ciel, onValueChange = { ciel = it },
        label = { Text(t("qo100_mesure_ciel"), fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = poste, onValueChange = { poste = it },
        label = { Text(t("qo100_mesure_poste"), fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                // Accept a decimal comma (French keyboards).
                val c = ciel.replace(',', '.').trim().toDoubleOrNull()
                val p = poste.replace(',', '.').trim().toDoubleOrNull()
                // Round, don't truncate: "10489.498" is 10489497999.999998 in
                // floating point, and truncation silently loses a hertz.
                val (ok, texte) = vm.qo100Mesure(
                    descente,
                    Math.round((c ?: 0.0) * 1_000_000),
                    Math.round((p ?: 0.0) * 1_000_000))
                message = texte
                reussi = ok
                if (ok) { ciel = ""; poste = "" }
            },
            modifier = Modifier.weight(1f)) {
            Text(t("qo100_mesure_valider"), fontSize = 12.sp, color = Cyan)
        }
        OutlinedButton(
            onClick = { vm.qo100EffaceOl(descente); message = "" },
            modifier = Modifier.weight(1f)) {
            Text(t("qo100_mesure_effacer"), fontSize = 12.sp, color = TextLo)
        }
    }
    if (message.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        // Green accepted, amber refused: the colour tells before the text.
        Text(message, color = if (reussi) Aurora else Amber, fontSize = 11.sp)
    }
}

/**
 * Band plan ruler, shared by the QO-100 and pass screens (the pass screen
 * used to have a plain bar, so you could not tell whether you were allowed to
 * transmit where you landed).
 *
 * **One component for both screens**: when the band plan changes, it must
 * change everywhere at once.
 */
@Composable
internal fun Reglette(descenteHz: Long, sur: (Long) -> Unit) {
    val bas = Qo100.REGLETTE_BAS_HZ
    val etendue = (Qo100.REGLETTE_HAUT_HZ - bas).toDouble()

    // Pixel → Hz, shared by tap and drag.
    fun hz(x: Float, largeur: Int): Long {
        val r = (x / largeur.coerceAtLeast(1)).coerceIn(0f, 1f)
        return bas + (r * etendue).toLong()
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) {
                detectTapGestures { p -> sur(hz(p.x, size.width)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    sur(hz(change.position.x, size.width))
                }
            }
    ) {
        val l = size.width
        fun x(f: Long): Float = (((f - bas) / etendue) * l).toFloat()

        val hautBarre = 10.dp.toPx()
        val basBarre = 42.dp.toPx()
        val hauteurBarre = basBarre - hautBarre

        // Segments in band plan order.
        Qo100.SEGMENTS.forEach { s ->
            val x0 = x(s.basHz)
            val x1 = x(s.hautHz)
            drawRect(
                color = couleurUsage(s.usage),
                topLeft = Offset(x0, hautBarre),
                // At least one pixel: the 5 kHz lower beacon could round to 0.
                size = Size((x1 - x0).coerceAtLeast(1f), hauteurBarre),
            )
        }

        // Markers above the bar.
        Qo100.SEGMENTS.forEach { s ->
            val r = s.repereHz ?: return@forEach
            drawLine(
                color = couleurUsage(s.usage),
                start = Offset(x(r), 0f),
                end = Offset(x(r), hautBarre),
                strokeWidth = 2.dp.toPx(),
            )
        }

        // Scale ticks every 50 kHz.
        var g = bas
        while (g <= Qo100.REGLETTE_HAUT_HZ) {
            drawLine(
                color = TextLo.copy(alpha = 0.5f),
                start = Offset(x(g), basBarre),
                end = Offset(x(g), basBarre + 5.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            g += 50_000L
        }

        // Cursor on top of everything.
        val xc = x(descenteHz).coerceIn(0f, l)
        drawLine(
            color = TextHi,
            start = Offset(xc, 0f),
            end = Offset(xc, size.height),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(color = TextHi, radius = 4.dp.toPx(), center = Offset(xc, 4.dp.toPx()))
    }
}

/** A symmetric "− step / + step" row. */
@Composable
private fun PasLigne(vm: MainViewModel, pasHz: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { vm.qo100Pas(-pasHz) }, modifier = Modifier.weight(1f)) {
            Text("−" + libellePas(pasHz), fontSize = 13.sp)
        }
        OutlinedButton(onClick = { vm.qo100Pas(pasHz) }, modifier = Modifier.weight(1f)) {
            Text("+" + libellePas(pasHz), fontSize = 13.sp)
        }
    }
}

private fun libellePas(hz: Long): String =
    if (hz >= 1000L) "${hz / 1000} kHz" else "$hz Hz"

@Composable
private fun Interrupteur(titre: String, coche: Boolean, sur: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).toggleable(value = coche, role = Role.Switch,
            onValueChange = sur)) {
        Text(titre, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = coche, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
    }
}

/** What the hardware displays; amber when it cannot get there. */
@Composable
private fun LigneMateriel(titre: String, hz: Long, atteignable: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
        Text(titre, color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(qoMhz(hz), color = if (atteignable) TextHi else Amber,
            fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LigneAngle(titre: String, deg: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(titre, color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(if (deg == null) "—" else String.format(Locale.US, "%.1f°", deg),
            color = TextHi, fontSize = 16.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold)
    }
}

/**
 * Frequency in MHz, to the hertz. Three decimals would do for a band plan but
 * not for LNB calibration: warm drift is hundreds of hertz, which is exactly
 * what we want to see.
 */
private fun qoMhz(hz: Long): String =
    String.format(Locale.US, "%,.6f", hz / 1_000_000.0).replace(',', ' ')

/**
 * Signed offset in Hz. The beacon measurement is shown in Hz, not kHz: small
 * offsets would round to "+0.000 kHz" and suggest a perfect calibration while
 * SSB is already audibly off.
 */
private fun qoHzSigne(hz: Long): String = (if (hz > 0) "+" else "") + "$hz Hz"

/** Signed offset in kHz: the order of magnitude of an LNB error. */
private fun qoKhzSigne(hz: Long): String {
    val signe = if (hz > 0) "+" else ""
    return signe + String.format(Locale.US, "%.3f kHz", hz / 1000.0)
}

/** Parses a MHz entry, tolerating spaces and a decimal comma. */
private fun qoDepuisMhz(texte: String): Long? {
    val propre = texte.replace(" ", "").replace(" ", "").replace(',', '.')
    val v = propre.toDoubleOrNull() ?: return null
    return Math.round(v * 1_000_000.0)
}
