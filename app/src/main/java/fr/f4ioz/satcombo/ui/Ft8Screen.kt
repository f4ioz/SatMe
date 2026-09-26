/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.ft8.Ft8Hub
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.*

/**
 * FT8 and FT4 listening.
 *
 * Nothing is transmitted from this screen, on purpose: decoding checks itself
 * (you read what others send), while transmitting puts the operator's callsign
 * on the air. TX will come once reception has proven itself in the field.
 */
@Composable
fun Ft8Screen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val st by Ft8Hub.state.collectAsState()

    val demandeMicro = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { accorde ->
        // A refusal must be visible, or the button seems to do nothing.
        if (accorde) Ft8Hub.demarre(ctx, st.mode)
    }

    fun micOk() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    // A CQ is what you look for when you want a contact; the rest is
    // traffic between two stations already in QSO.
    var seulementCq by remember { mutableStateOf(false) }
    val visibles = remember(st.entendus, seulementCq) {
        if (seulementCq) st.entendus.filter { it.texte.startsWith("CQ") } else st.entendus
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {

        // --- mode ---
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf("FT8", "FT4").forEach { m ->
                FilterChip(
                    selected = st.mode == m,
                    // No mode change while listening: slots differ in length, and FT4
                    // would be decoded in a 15-second window.
                    onClick = { Ft8Hub.choisitMode(ctx, m) },
                    label = { Text(m, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Cyan.copy(alpha = 0.25f),
                        selectedLabelColor = Cyan))
            }
            Spacer(Modifier.weight(1f))
            Text("${st.tranches} ${t("ft8_tranches")}", color = TextLo, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))

        // --- run ---
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (st.enMarche) Ft8Hub.arrete()
                    else if (micOk()) Ft8Hub.demarre(ctx, st.mode)
                    else demandeMicro.launch(Manifest.permission.RECORD_AUDIO)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (st.enMarche) Magenta else Cyan)
            ) { Text(if (st.enMarche) t("ft8_arreter") else t("ft8_ecouter")) }

            OutlinedButton(onClick = { Ft8Hub.vide() }) { Text(t("ft8_effacer")) }
        }

        // --- position within the slot ---
        if (st.enMarche) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { st.avancement },
                color = Cyan, trackColor = SpaceSurface,
                modifier = Modifier.fillMaxWidth())
            Text(
                "${t("ft8_niveau")} ${Math.round(st.niveau * 100)} %",
                color = if (st.niveau < 0.01f) Amber else TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp))
            // A silent microphone looks like an empty band: say so.
            if (st.niveau < 0.01f) {
                Text(t("ft8_muet"), color = Amber, fontSize = 11.sp)
            }
        }

        if (st.panne.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(messagePanne(st.panne), color = Magenta, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
        }

        // --- the reminder that matters ---
        Spacer(Modifier.height(10.dp))
        Surface(color = Amber.copy(alpha = 0.13f), shape = RoundedCornerShape(8.dp)) {
            Text(t("ft8_limite"), color = Amber, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }

        // --- waterfall ---
        //
        // What a bare list lacks most: seeing the band. At a glance the operator
        // sees whether the rig is tuned, whether the band is busy, and whether
        // the expected signal is there.
        if (st.cascade.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))

            // Instant spectrum above the history.
            //
            // The waterfall says who transmitted and when; the spectrum says what
            // comes in **now**. That is what you watch while tuning, when nothing
            // decodes yet and the waterfall is empty.
            if (st.spectre.isNotEmpty()) {
                Canvas(Modifier.fillMaxWidth().height(46.dp)) {
                    val pas = size.width / st.spectre.size
                    for (c in st.spectre.indices) {
                        val h = (st.spectre[c].coerceIn(0f, 1f)) * size.height
                        drawRect(
                            color = Cyan.copy(alpha = 0.35f + 0.65f * st.spectre[c]),
                            topLeft = androidx.compose.ui.geometry.Offset(
                                c * pas, size.height - h),
                            size = androidx.compose.ui.geometry.Size(pas + 1f, h))
                    }
                }
            }

            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val lignes = st.cascade.size
                val hauteur = size.height / lignes
                st.cascade.forEachIndexed { i, ligne ->
                    val largeur = size.width / ligne.size
                    for (c in ligne.indices) {
                        val v = (ligne[c].toInt() and 0xFF) / 255f
                        drawRect(
                            color = teinteCascade(v),
                            topLeft = androidx.compose.ui.geometry.Offset(
                                c * largeur, i * hauteur),
                            size = androidx.compose.ui.geometry.Size(
                                largeur + 1f, hauteur + 1f))
                    }
                }
                // Stations decoded in the last slot, marked on the axis: this links
                // the waterfall to the list.
                st.entendus.take(12).forEach { e ->
                    val x = (e.frequenceHz - st.basseHz).toFloat() /
                        (st.hauteHz - st.basseHz) * size.width
                    if (x in 0f..size.width) {
                        drawRect(
                            color = if (e.texte.startsWith("CQ")) Amber else Cyan,
                            topLeft = androidx.compose.ui.geometry.Offset(x - 1f, 0f),
                            size = androidx.compose.ui.geometry.Size(2f, size.height * 0.18f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("${st.basseHz} Hz", color = TextLo, fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text("${st.hauteHz} Hz", color = TextLo, fontSize = 9.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- filter ---
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = seulementCq,
                onClick = { seulementCq = !seulementCq },
                label = { Text(t("ft8_cq_seuls"), fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Amber.copy(alpha = 0.25f),
                    selectedLabelColor = Amber))
            Text("${visibles.size} / ${st.entendus.size}", color = TextLo, fontSize = 11.sp)
        }

        Spacer(Modifier.height(8.dp))

        if (st.entendus.isEmpty()) {
            Text(
                if (st.enMarche) t("ft8_rien_encore") else t("ft8_pret"),
                color = TextLo, fontSize = 12.sp)
        }

        LazyColumn(Modifier.weight(1f)) {
            items(visibles) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(e.heure, color = TextLo, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%+3d".format(e.rapportDb),
                        color = when {
                            e.rapportDb >= 0 -> Color(0xFF2FB344)
                            e.rapportDb >= -12 -> Cyan
                            else -> Amber
                        },
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text("${e.frequenceHz}", color = TextLo, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(10.dp))
                    // A CQ stands out at a glance: it is the only line you can act on.
                    val estCq = e.texte.startsWith("CQ")
                    Text(e.texte,
                        color = if (estCq) Amber else TextHi, fontSize = 13.sp,
                        fontWeight = if (estCq) FontWeight.Black else FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = SpaceSurface)
            }
        }
    }
}

/**
 * Colour of a waterfall point.
 *
 * Black to cyan to yellow: brightness rises along with hue, so it stays
 * readable in sunlight and at night, and for colour-blind users, since
 * brightness alone carries the information.
 */
private fun teinteCascade(v: Float): Color = when {
    v < 0.35f -> Color(0xFF05080F).let {
        Color(red = 0.02f + v * 0.1f, green = 0.03f + v * 0.5f, blue = 0.06f + v * 0.7f)
    }
    v < 0.7f -> Color(red = 0.05f, green = 0.2f + (v - 0.35f) * 2f, blue = 0.3f + (v - 0.35f) * 1.4f)
    else -> Color(red = (v - 0.7f) * 3f, green = 0.9f, blue = 0.8f - (v - 0.7f) * 2.5f)
}

/** Each failure has its own message: a silent screen teaches nothing. */
private fun messagePanne(quoi: String): String = when (quoi) {
    "permission" -> t("ft8_err_permission")
    "micro" -> t("ft8_err_micro")
    "occupe" -> t("ft8_err_occupe")
    "cadence" -> t("ft8_err_cadence")
    else -> "${t("ft8_err_autre")} ($quoi)"
}
