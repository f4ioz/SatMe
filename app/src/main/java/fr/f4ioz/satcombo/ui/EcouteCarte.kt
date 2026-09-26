/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.demo.EcouteDeportee
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Remote listening: hear a SatMe station from another phone.
 *
 * **Audio only.** The pass screen and web page already show position,
 * frequencies and QSOs; a third representation would end up contradicting them
 * (frequencies once drifted 5 kHz apart for three releases because they were
 * computed in two places).
 */
@Composable
fun EcouteCarte(vm: MainViewModel) {
    val etat by EcouteDeportee.etat.collectAsState()
    val st by EcouteDeportee.station.collectAsState()
    var adresse by remember { mutableStateOf(vm.ecouteAdresse()) }

    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Headphones, null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("ecoute_titre"), color = TextHi, fontWeight = FontWeight.Bold)
            }
            Text(t("ecoute_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))

            // --- stations found on the network ---
            //
            // Announcement listening runs only while this screen is shown; a
            // permanent listener would drain the battery for a rarely used feature.
            DisposableEffect(Unit) {
                fr.f4ioz.satcombo.demo.AnnonceReseau.ecoute()
                onDispose { fr.f4ioz.satcombo.demo.AnnonceReseau.cesse() }
            }
            val trouvees by fr.f4ioz.satcombo.demo.AnnonceReseau.stations.collectAsState()

            if (trouvees.isEmpty()) {
                Text(t("ecoute_cherche"), color = TextLo, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp))
            } else {
                Text(t("ecoute_trouvees"), color = TextHi, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                trouvees.forEach { st ->
                    TextButton(enabled = !etat.actif, onClick = {
                        adresse = st.url
                        vm.setEcouteAdresse(st.url)
                        EcouteDeportee.demarre(st.url)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("▶  " + st.nom, color = if (etat.actif) TextLo else Cyan,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Text(t("ecoute_main"), color = TextLo, fontSize = 11.sp)
            OutlinedTextField(
                value = adresse,
                onValueChange = { adresse = it },
                singleLine = true,
                enabled = !etat.actif,
                label = { Text(t("ecoute_adresse"), fontSize = 11.sp) },
                placeholder = { Text("http://192.168.0.12:8080/d/abcd1234/",
                    fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            etat.connecte -> tf("ecoute_connecte", etat.koRecus)
                            etat.actif -> t("ecoute_attente")
                            else -> t("ecoute_arrete")
                        },
                        color = if (etat.connecte) Cyan else TextLo, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                    if (etat.muette) {
                        Text(t("ecoute_muette"), color = Amber, fontSize = 11.sp)
                    }
                    if (etat.incident.isNotBlank()) {
                        Text(etat.incident, color = Magenta, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                TextButton(onClick = {
                    if (etat.actif) EcouteDeportee.arrete()
                    else {
                        // Saved on connect, not on each keystroke: never store a half-typed address.
                        vm.setEcouteAdresse(adresse.trim())
                        EcouteDeportee.demarre(adresse)
                    }
                }) {
                    Text(if (etat.actif) t("ecoute_couper") else t("ecoute_brancher"),
                        color = if (etat.actif) Amber else Cyan,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            // --- what the station reports ---
            //
            // **Nothing is recomputed here.** The station alone computes
            // position, frequencies and Doppler; we display what it sends.
            // The dial is the app's own `PolarPlot`, fed with the received position.
            if (etat.connecte && st.satellite.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = SpaceSurface)
                Spacer(Modifier.height(10.dp))

                Text(st.satellite, color = Cyan, fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (st.nom.isNotBlank()) {
                    Text(st.nom, color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }

                val pos = st.elevation?.let { el ->
                    fr.f4ioz.satcombo.data.SatPosition(
                        azimuthDeg = st.azimut ?: 0.0, elevationDeg = el,
                        rangeKm = 0.0, rangeRateKmS = 0.0, altKm = 0.0,
                        latDeg = 0.0, lonDeg = 0.0, sunlit = false)
                }
                PolarPlot(position = pos, passTrack = st.trace,
                    modifier = Modifier.fillMaxWidth().height(230.dp)
                        .padding(vertical = 6.dp))

                if (st.enEmission) {
                    Surface(color = Magenta, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(t("ecoute_emission"), color = SpaceBg, fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                LigneChiffre(t("ecoute_azimut"),
                    st.elevation?.let { "%.0f°".format(st.azimut ?: 0.0) } ?: "—", Cyan)
                LigneChiffre(t("ecoute_elevation"),
                    st.elevation?.let { "%.0f°".format(it) } ?: "—", Cyan)
                LigneChiffre(t("ecoute_rx"), mhz(st.rxHz), Cyan)
                LigneChiffre(t("ecoute_tx"), mhz(st.txHz), Amber)
                if (st.antenneAz != null) {
                    LigneChiffre(t("ecoute_antenne"),
                        "%.0f° · %.0f°".format(st.antenneAz!!, st.antenneEl ?: 0.0), Amber)
                }

                if (st.contacts.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(t("ecoute_contacts"), color = TextHi, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold)
                    st.contacts.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(c.heure, color = TextLo, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(48.dp))
                            Text(c.indicatif, color = TextHi, fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            if (c.locator.isNotBlank()) {
                                Text(c.locator, color = Cyan, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (st.heure.isNotBlank()) {
                    Text(st.heure, color = TextLo, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

/** A "label — value" row, as elsewhere in settings. */
@Composable
private fun LigneChiffre(libelle: String, valeur: String, teinte: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(libelle, color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(valeur, color = teinte, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
    }
}

/** Same format as everywhere: four decimals, French decimal comma. */
private fun mhz(hz: Long?): String =
    if (hz == null) "—" else "%.4f MHz".format(hz / 1e6).replace('.', ',')
