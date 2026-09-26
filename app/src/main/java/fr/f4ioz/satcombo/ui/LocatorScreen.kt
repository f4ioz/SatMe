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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.data.Units
import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.ui.theme.*
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf

/**
 * Maidenhead locator dossier (GridHopper-style): the current 6-char locator
 * decomposed into Field/Square/Subsquare, the exact coordinates, the distance
 * to each of the 4 borders of the enclosing square (with the adjacent square),
 * and an interactive grid map below.
 */
@Composable
fun LocatorScreen(ui: UiState, vm: MainViewModel) {
    val obs = ui.observer ?: return
    val lat = obs.latDeg; val lon = obs.lonDeg
    val loc6 = Maidenhead.fromLatLon(lat, lon)
    val field = loc6.take(2)
    val square = loc6.substring(2, 4)
    val subsquare = loc6.substring(4, 6)
    // All eight squares that touch ours, closest first. Those the app already
    // announces (QRV photo, log, ADIF) are highlighted, so the page and the
    // picture can never tell two different stories.
    val around = remember(lat, lon) { Maidenhead.aroundSquares(lat, lon) }
    val announced = remember(lat, lon, ui.nearGridMeters) { vm.myGridSquares().drop(1).toSet() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- Big locator card ---
        Surface(color = SpaceCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t("grid_locator_title"), color = TextLo, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(10.dp))
                // JO37 in bright, mx dimmer — emphasize the 4-char square.
                // One locator, one size: "JN18XX" is read and announced as a
                // whole, so the subsquare is lettered like the square and only
                // the colour tells the two levels apart.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(loc6.take(4), color = Cyan, fontWeight = FontWeight.Black,
                        fontSize = 46.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    Text(subsquare.uppercase(), color = TextHi, fontWeight = FontWeight.Black,
                        fontSize = 46.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                }
                // Coordinates + Field/Square/Subsquare — hideable via settings.
                if (ui.locatorDetails) {
                    Spacer(Modifier.height(8.dp))
                    Text("◉  %.5f°, %.5f°".format(lat, lon), color = TextHi, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(16.dp))
                    // Field / Square / Subsquare breakdown
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        LevelChip(t("grid_field"), field, Cyan)
                        LevelChip(t("grid_square"), square, Aurora)
                        LevelChip("Subsquare", subsquare.lowercase(), TextLo)
                    }
                }
            }
        }

        // --- The eight surrounding squares, nearest first ---
        Surface(color = SpaceCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(t("grid_neighbours"), color = TextLo, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(tf("grid_square_label", loc6.take(4)), color = TextHi, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                // One request per neighbour list; the connector never re-asks
                // for a square it already knows.
                LaunchedEffect(Unit) { vm.chargeLotwLocal() }
                LaunchedEffect(loc6.take(4), ui.carnet.configure) {
                    if (ui.carnet.configure) {
                        vm.demandeCarres(around.map { it.square } + loc6.take(4))
                    }
                }
                around.forEach { n ->
                    BorderRow(arrowOf(n.dir), dirLabel(n.dir), n.km, n.square,
                        units = ui.units, announced = n.square in announced,
                        // LoTW confirms, Wavelog tells what is logged; the
                        // stronger wins — a confirmed square stays confirmed.
                        carnet = etatCarre(ui, n.square))
                }
                // Legend shows as soon as any source answers: LoTW alone is
                // enough, Wavelog is optional.
                if (ui.carnet.configure || ui.carnet.lotwTravailles.isNotEmpty() ||
                    ui.carnet.lotwConfirmes.isNotEmpty()) {
                    Text(t("carnet_legend"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
                if (announced.isNotEmpty()) {
                    Text(t("grid_announced_hint"), color = Amber, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        // --- Interactive grid map ---
        Text(t("map_tap_hint"),
            color = TextLo, fontSize = 12.sp)
        var probed by remember { mutableStateOf<String?>(null) }
        Surface(color = SpaceCard, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().height(420.dp)) {
            LocatorMapCanvas(
                selected = probed ?: loc6,
                provider = MapProviders.byId(ui.mapStyle),
                marker = lat to lon,          // exact GPS dot, refreshed live
                onSelect = { probed = it },
                modifier = Modifier.fillMaxSize()
            )
        }
        probed?.let {
            Text(tf("probed_square", it), color = Aurora, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun LevelChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.height(6.dp))
        Text(value, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            fontFamily = FontFamily.Monospace)
        Text(label, color = TextLo, fontSize = 11.sp)
    }
}

@Composable
private fun BorderRow(arrow: String, dir: String, km: Double, square: String,
                      units: String, announced: Boolean = false,
                      carnet: fr.f4ioz.satcombo.data.CarnetEnLigne.Etat? = null) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(arrow, color = if (announced) Amber else Cyan, fontSize = 17.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
        Text(dir, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.width(86.dp))
        Text(square, color = if (announced) Amber else Aurora, fontSize = 14.sp,
            fontWeight = if (announced) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace)
        // What the online log knows about this square. For a rover this is
        // what decides whether a few km of driving is worth it.
        when (carnet) {
            fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.JAMAIS ->
                Text("  ★", color = Color(0xFF7FE3A0), fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
            fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.TRAVAILLE ->
                Text("  ✓", color = TextLo, fontSize = 13.sp)
            fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.CONFIRME ->
                Text("  ✓✓", color = TextLo, fontSize = 13.sp)
            else -> Unit
        }
        Spacer(Modifier.weight(1f))
        Text(Units.distance(km, units), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            fontFamily = FontFamily.Monospace)
    }
}

/**
 * Square status across all sources. A LoTW confirmation wins (the only one
 * valid for awards), then the online log, then LoTW worked-only squares.
 */
private fun etatCarre(
    ui: UiState, carre: String
): fr.f4ioz.satcombo.data.CarnetEnLigne.Etat? {
    val k = carre.uppercase().take(4)
    if (k in ui.carnet.lotwConfirmes)
        return fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.CONFIRME
    ui.carnet.carres[carre.uppercase()]?.let { return it }
    if (k in ui.carnet.lotwTravailles)
        return fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.TRAVAILLE
    // LoTW answered and does not know this square: still needed.
    if (ui.carnet.lotwTravailles.isNotEmpty())
        return fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.JAMAIS
    return null
}

/** The arrow drawn in front of a neighbour, one per compass direction. */
private fun arrowOf(dir: String): String = when (dir) {
    "N" -> "\u2191"; "S" -> "\u2193"; "E" -> "\u2192"; "W" -> "\u2190"
    "NE" -> "\u2197"; "NW" -> "\u2196"; "SE" -> "\u2198"; else -> "\u2199"
}

@Composable
private fun dirLabel(dir: String): String = when (dir) {
    "N" -> t("dir_north"); "S" -> t("dir_south")
    "E" -> t("dir_east"); "W" -> t("dir_west")
    "NE" -> t("dir_ne"); "NW" -> t("dir_nw")
    "SE" -> t("dir_se"); else -> t("dir_sw")
}


