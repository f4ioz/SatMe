/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.domain.Indicatifs
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.Aurora
import fr.f4ioz.satcombo.ui.theme.Amber
import fr.f4ioz.satcombo.ui.theme.Cyan
import fr.f4ioz.satcombo.ui.theme.SpaceBg
import fr.f4ioz.satcombo.ui.theme.SpaceCard
import fr.f4ioz.satcombo.ui.theme.TextHi
import fr.f4ioz.satcombo.ui.theme.TextLo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logging a contact during the pass.
 *
 * Full screen, keyboard in the lower half: type the callsign, validate, repeat.
 * The contact carries the displayed satellite and the time of validation.
 *
 * **The old queue is gone** (stamp during the contact, name it later). Its
 * leftover "⏱ stamp" button saved a second contact without clearing the
 * fields and logged the same callsign twice one second apart.
 *
 * The header (satellite, time, azimuth, elevation) holds the only things that
 * cannot be recovered afterwards.
 */

@Composable
fun NommageScreen(ui: UiState, vm: MainViewModel) {
    // **The contact is timestamped at validation, always**, and carries the
    // displayed satellite.
    val satCourant = ui.selected

    val enCw = ui.opMode == "CW"
    val rstDefaut = if (enCw) "599" else "59"
    // RS in phone (two digits), RST in CW (three): T is tone, meaningless in
    // FM or SSB.
    val rstMax = if (enCw) 3 else 2
    var rstEnvoye by remember { mutableStateOf(rstDefaut) }
    var rstRecu by remember { mutableStateOf(rstDefaut) }
    // Field receiving the keyboard: 0 = RS sent, 1 = RS received,
    // 2 = locator, null = callsign.
    //
    // **No remember key.** These were keyed on `entree.timeMs`, i.e.
    // `System.currentTimeMillis()`, which changed on every position refresh:
    // a second after tapping "Locator" the focus silently fell back to the
    // callsign and the next letters went there. Only the fingers move the
    // focus; validation resets it itself.
    var rstActif by remember { mutableStateOf<Int?>(null) }
    var carreAvantFocus by remember { mutableStateOf("") }
    // Was the suffix set by the "/P /M" key? The text cannot tell: that key
    // and the "/" key produce the same slash. Only the gesture knows.
    var suffixePose by remember { mutableStateOf(false) }

    // **Cleared only on validation.** Clearing it whenever the presented entry
    // changed (every refresh) sent half-typed callsigns to the log.
    var saisie by remember { mutableStateOf("") }
    var carre by remember { mutableStateOf("") }
    var carreTouche by remember { mutableStateOf(false) }

    val memoire = ui.express.memoire

    // TX border, **here too**. The root Box already draws one around the
    // screen, but here the eyes are on the keys and a thin edge line goes
    // unnoticed. Same pulse, same red: one signal shown twice.
    val eclatTx = if (ui.catUi.enEmission) {
        val pulse = rememberInfiniteTransition(label = "txSaisie")
        pulse.animateFloat(
            initialValue = 0.5f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700), repeatMode = RepeatMode.Reverse),
            label = "txSaisieAlpha").value
    } else 0f

    Column(Modifier.fillMaxSize().background(SpaceBg).padding(10.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { vm.fermeNommage() }) {
                Icon(Icons.Default.ArrowBack, null, tint = Cyan)
            }
            Text(t("entry_title"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            // No queue counter or discard cross: the cross called
            // `supprimeEntree` with the current time, a key that never
            // existed, so it deleted nothing. The back arrow is enough.
            // ⚑ Something heard, not (yet) logged: the moment kept for the journal.
            BoutonSignet(ui, vm)
            IconButton(onClick = {
                vm.openSettings("express")
            }) {
                Icon(Icons.Default.Keyboard, t("menu_express"),
                    tint = TextLo, modifier = Modifier.size(20.dp))
            }
        }

        // Only a missing satellite prevents logging.
        if (satCourant == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(t("nommage_no_sat"), color = TextLo, fontSize = 14.sp)
            }
            return@Column
        }

        // ------------------------------------------------ the three figures
        val tf = remember(ui.useUtc) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
                if (ui.useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
        }
        Surface(color = SpaceCard, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // The satellite is tappable: fix it here before the
                    // contact goes to ADIF.
                    var choixSat by remember { mutableStateOf(false) }
                    // Name and aim widget on one row. **The widget gets its
                    // width first**; the name yields, never the widget.
                    // Otherwise a long name ("JAS-2 (FO-29)") overflows and
                    // Compose wraps the widget text one letter per line,
                    // doubling the card height.
                    //
                    // The name **fills** the remaining width (default `fill`),
                    // pushing the widget to the right edge so the figures stay
                    // in the same place. With `fill = false` the widget stuck
                    // to the name, mid-screen.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(satCourant.name + " ▾", color = TextHi,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (satCourant.name.length > 9) 13.sp else 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                                .clickable { choixSat = true }
                                .padding(end = 6.dp))
                        // Stations already worked this pass, to avoid
                        // calling one twice.
                        var listeFaits by remember { mutableStateOf(false) }
                        val faits = remember(ui.log.size, satCourant.catalogNumber,
                            ui.passes.size) { vm.indicatifsDuPassage() }
                        if (faits.isNotEmpty()) {
                            TextButton(
                                onClick = { listeFaits = true },
                                contentPadding = androidx.compose.foundation.layout
                                    .PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                Text("✓ ${faits.size}", color = Cyan, fontSize = 12.sp)
                            }
                        }
                        if (listeFaits) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { listeFaits = false },
                                confirmButton = {
                                    TextButton(onClick = { listeFaits = false }) {
                                        Text(t("close"), color = Cyan)
                                    }
                                },
                                title = { Text(t("worked_this_pass")) },
                                text = {
                                    Column(Modifier.verticalScroll(rememberScrollState())) {
                                        faits.forEach { ind ->
                                            Text(ind, color = TextHi, fontSize = 16.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(vertical = 3.dp))
                                        }
                                    }
                                })
                        }
                        // No "⏱ stamp" button here: it called the same
                        // `ajouteContactDirect` without clearing the fields,
                        // so two taps made two identical contacts. There is
                        // one way to save, and it is SAVE.
                        MireClavier(ui.livePosition, Modifier)
                    }

                    if (choixSat) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { choixSat = false },
                            confirmButton = {},
                            title = { Text(t("nommage_sat_change")) },
                            text = {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    ui.satellites.forEach { s ->
                                        Text(s.name,
                                            color = if (s.name == satCourant.name) Cyan else TextHi,
                                            fontSize = 15.sp,
                                            modifier = Modifier.fillMaxWidth()
                                                .clickable {
                                                    vm.select(s)
                                                    choixSat = false
                                                }
                                                .padding(vertical = 8.dp))
                                    }
                                }
                            })
                    }
                    // **Time and countdown on one line**: every row taken
                    // here is taken from the keyboard. Nothing on the right
                    // when the satellite never sets (QO-100); a dash would
                    // look like missing data.
                    val passage = ui.passes.firstOrNull {
                        ui.nowMs in it.aosEpochMs..it.losEpochMs
                    } ?: ui.passes.firstOrNull { it.aosEpochMs > ui.nowMs }
                    val maintenantTexte = tf.format(Date(ui.nowMs))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Running clock (the contact is dated at validation).
                        // Az/el are on the dial; repeating them as figures
                        // would steal keyboard space.
                        Text(maintenantTexte + (if (ui.useUtc) " UTC" else " LOC"),
                            color = TextLo, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        if (passage != null) {
                            val leve = ui.nowMs in passage.aosEpochMs..passage.losEpochMs
                            val reste = if (leve) passage.losEpochMs - ui.nowMs
                                        else passage.aosEpochMs - ui.nowMs
                            Text(
                                (if (leve) t("los") else t("aos")) + " " + fmtCountdown(reste),
                                color = if (leve) Cyan else Amber,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        val connu = memoire.firstOrNull { it.indicatif == Indicatifs.cle(saisie) }
        // Full width, or the flag aligns to the field width and ends up in
        // the middle.
        Box(Modifier.fillMaxWidth().clickable {
            if (rstActif == 2 && carre.isEmpty()) {
                carre = carreAvantFocus; carreTouche = false
            }
            rstActif = null
        }) {
            ChampIndicatif(saisie, Indicatifs.etat(saisie, memoire), nom = connu?.nom.orEmpty())

            // Flag and country drawn **over** the callsign field, not below:
            // the saved row goes to the keyboard.
            val pays = remember(saisie) { fr.f4ioz.satcombo.domain.Dxcc.entite(saisie) }
            if (pays != null) {
                val f = fr.f4ioz.satcombo.data.Flags.ALL
                    .firstOrNull { it.code == pays.drapeau }
                Row(Modifier.align(Alignment.CenterEnd).padding(end = 30.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (f != null) {
                        val bmp = remember(pays.drapeau) {
                            fr.f4ioz.satcombo.data.FlagDraw.bitmap(f, 22)
                        }
                        Image(bmp.asImageBitmap(), null)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(pays.nom, color = TextLo, fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        // Position within the transponder: display only. A finger aiming at
        // a letter must not move the VFO.
        BandePassante(
            // Transmitters are loaded only for the satellite open in the
            // detail screen; elsewhere the bar hides rather than lie.
            basHz = ui.transmitters.getOrNull(ui.selectedTxIndex)?.downlinkLowHz,
            hautHz = ui.transmitters.getOrNull(ui.selectedTxIndex)?.downlinkHighHz,
            courantHz = ui.rxRestHz ?: ui.catRadioDownlinkHz,
            modifier = Modifier.padding(top = 4.dp))



        Spacer(Modifier.height(6.dp))
        if (rstActif == 2) {
            // Grid squares already seen for this station, newest first,
            // filtered by what is typed. A tap fills and returns focus to
            // the callsign.
            val candidats = (connu?.locators ?: emptyList())
                .map { it.locator }
                .filter { carre.isEmpty() || it.startsWith(carre) }
                .distinct().take(5)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                candidats.forEach { g ->
                    Surface(color = SpaceCard, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 6.dp)
                            .clickable { carre = g; carreTouche = true; rstActif = null }) {
                        Text(g, color = Cyan, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
        } else
        LigneSuggestions(
            suggestions = Indicatifs.suggestions(
                saisie, memoire, System.currentTimeMillis(), satCourant.name),
            onChoisir = { c ->
                // Taken as is. Entries carry their own suffix; re-appending
                // the typed suffix produced F5RRO/P/P.
                saisie = c.indicatif
                // An explicit choice: its grid square is installed even if
                // the field was touched (otherwise a locator cleared on focus
                // never refilled).
                carre = Indicatifs.locatorPropose(c, c.indicatif)
                carreTouche = false
                rstActif = null
            })

        Spacer(Modifier.weight(1f))

        // Report prefilled 59 (phone) or 599 (CW). A tap gives a field the
        // focus (cyan) and keyboard digits go there; a second tap returns to
        // the callsign. The row is deliberately low: a taller one pushed the
        // save button off screen.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Locator, editable like the reports (six chars). Cyan means
            // "proposed, not verified" until touched.
            Text(t("contact_locator_label"), color = TextLo, fontSize = 12.sp,
                modifier = Modifier.padding(end = 6.dp))
            Surface(
                color = if (rstActif == 2) Cyan.copy(alpha = 0.18f) else SpaceCard,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(end = 10.dp)
                    .clickable {
                        if (rstActif == 2) {
                            // Leaving focus with nothing typed restores the
                            // previous square: a mistaken tap costs nothing.
                            if (carre.isEmpty()) { carre = carreAvantFocus; carreTouche = false }
                            rstActif = null
                        } else {
                            carreAvantFocus = carre
                            carre = ""
                            carreTouche = true
                            rstActif = 2
                        }
                    },
            ) {
                Text(carre.ifBlank { "—" },
                    color = when {
                        rstActif == 2 -> Cyan
                        carreTouche -> TextHi
                        else -> Cyan
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
            }
            Text(if (enCw) "RST" else "RS", color = TextLo, fontSize = 12.sp,
                modifier = Modifier.padding(end = 6.dp))
            listOf(0 to rstEnvoye, 1 to rstRecu).forEach { (idx, valeur) ->
                Surface(
                    color = if (rstActif == idx) Cyan.copy(alpha = 0.18f) else SpaceCard,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(end = 8.dp)
                        .clickable {
                            if (rstActif == 2 && carre.isEmpty()) {
                                carre = carreAvantFocus; carreTouche = false
                            }
                            rstActif = if (rstActif == idx) null else idx
                        },
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (idx == 0) "↑" else "↓",
                            color = if (rstActif == idx) Cyan else TextLo, fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(valeur.ifBlank { "—" },
                            color = if (rstActif == idx) Cyan else TextHi,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }

        ClavierIndicatif(
            liseretTx = if (ui.catUi.enEmission)
                Color(0xFFFF2D2D).copy(alpha = eclatTx) else null,
            saisie = saisie,
            memoire = memoire,
            mainGauche = ui.express.mainGauche,
            disposition = ui.express.disposition,
            onCaractere = { c ->
                val actif = rstActif
                if (actif == 2) {
                    carre = (carre + c).take(6).uppercase()
                    carreTouche = true
                } else if (actif != null && c.isDigit()) {
                    // Same keyboard as the callsign. The first digit
                    // replaces the default value.
                    if (actif == 0) {
                        rstEnvoye = ((if (rstEnvoye == rstDefaut) "" else rstEnvoye) + c).take(rstMax)
                    } else {
                        rstRecu = ((if (rstRecu == rstDefaut) "" else rstRecu) + c).take(rstMax)
                    }
                } else {
                    saisie = Indicatifs.ajoute(saisie, c, suffixePose)
                    if (!carreTouche || carre.isEmpty()) {
                        val connu = memoire.firstOrNull { it.indicatif == Indicatifs.cle(saisie) }
                        carre = Indicatifs.locatorPropose(connu, saisie)
                    }
                }
            },
            onBarre = {
                // Slash appended at the end only, never leading, never doubled.
                if (saisie.isNotEmpty() && !saisie.endsWith("/")) saisie += "/"
                // What follows belongs to the callsign, not a suffix: letters
                // go in the order heard.
                suffixePose = false
                // A country prefix changes the entity: the home grid square
                // no longer applies.
                if (!carreTouche || carre.isEmpty()) carre = ""
            },
            onSuffixe = {
                val (b, suf) = Indicatifs.separe(saisie)
                saisie = b + Indicatifs.suffixeSuivant(suf)
                // The cycle passes through "no suffix": drop the flag then, or
                // letters keep being inserted before a slash that is gone.
                suffixePose = Indicatifs.suffixe(saisie).isNotEmpty()
                // A new suffix means the station moved: the inherited square
                // no longer applies.
                if (!carreTouche || carre.isEmpty()) {
                    val connu = memoire.firstOrNull { it.indicatif == Indicatifs.cle(saisie) }
                    carre = Indicatifs.locatorPropose(connu, saisie)
                }
            },
            onEfface = {
                val actif = rstActif
                if (actif == 2 && carre.isNotEmpty()) { carre = carre.dropLast(1); carreTouche = true }
                else if (actif == 0 && rstEnvoye.isNotEmpty()) rstEnvoye = rstEnvoye.dropLast(1)
                else if (actif == 1 && rstRecu.isNotEmpty()) rstRecu = rstRecu.dropLast(1)
                else if (saisie.isNotEmpty()) {
                    saisie = saisie.dropLast(1)
                    // Erasing the suffix clears the flag ("F4IOZ/P" -> "F4IOZ/").
                    if (Indicatifs.suffixe(saisie).isEmpty()) suffixePose = false
                }
            },
            onValide = {
                val origine = when {
                    carre.isBlank() -> Indicatifs.OrigineLocator.INCONNU
                    carreTouche -> Indicatifs.OrigineLocator.SAISI
                    else -> Indicatifs.OrigineLocator.PROPOSE
                }
                // New contact **at the current time**, on the displayed
                // satellite: you validate at the end of the contact.
                vm.ajouteContactDirect(satCourant, saisie, carre,
                    rstEnvoye, rstRecu, origine.name)
                // Everything is cleared here and only here, focus included,
                // or the next report would go into the field left open.
                saisie = ""; carre = ""; carreTouche = false
                rstEnvoye = rstDefaut; rstRecu = rstDefaut
                rstActif = null; carreAvantFocus = ""; suffixePose = false
            },
        )
    }
}
