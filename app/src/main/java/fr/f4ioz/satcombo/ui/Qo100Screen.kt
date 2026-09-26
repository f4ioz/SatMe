/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

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
 * L'écran QO-100.
 *
 * Il est à part, et pas replié dans la fiche d'un satellite, pour une raison
 * de fond : sur QO-100 il n'y a rien de ce qui fait une fiche de satellite. Ni
 * heure de passage, ni compte à rebours, ni trace au sol, ni Doppler qui
 * défile. Le satellite est là en permanence, à un azimut qui ne change pas, et
 * tout ce qu'un opérateur a à faire tient en quatre gestes : choisir sa
 * fréquence dans le transpondeur, vérifier que le poste et la clé suivent,
 * pointer la parabole une fois pour toutes, et caler l'ensemble sur la balise.
 *
 * L'écran est donc ordonné dans cet ordre-là, du plus fréquent au plus rare.
 * La fréquence en haut, gros et lisible, parce que c'est la seule chose qu'on
 * touche pendant un QSO. Le pointage tout en bas : on le lit une fois le jour
 * de l'installation, et plus jamais.
 *
 * ### Ce qui s'affiche, et dans quel monde
 *
 * Les fréquences en gros sont **dans le ciel** : 10 489,750 pour la balise
 * médiane, comme dans tous les carnets de trafic et sur tous les tableaux
 * publiés. Ce que le poste et la clé affichent réellement — 145,750 et
 * 432,250 sur la station visée — est écrit en dessous, plus petit, comme une
 * vérification. C'est le sens qui compte : l'opérateur travaille sur 10 489 et
 * la conversion est une plomberie, pas un sujet.
 *
 * Quand un convertisseur manque ou ne couvre pas la bande, la ligne du poste
 * passe à l'ambre plutôt que de disparaître. Un champ qui s'efface ne dit rien ;
 * un champ qui s'allume dit où regarder.
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

        // ---- l'en-tête ------------------------------------------------------
        //
        // Le nom en gros, parce que cet écran ne ressemble à aucun autre de
        // l'application et qu'on y arrive par plusieurs chemins. Savoir d'un
        // coup d'œil qu'on est sur QO-100, et non sur un passage ordinaire,
        // évite de chercher un compte à rebours qui n'existera jamais.
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
                // Le témoin de chaîne : vert quand le poste peut réellement
                // aller là où l'écran pointe. C'est la seule vérification
                // possible avant d'entendre quoi que ce soit.
                Icon(
                    if (q.posteAtteignable) Icons.Default.CheckCircle
                    else Icons.Default.Warning,
                    null,
                    tint = if (q.posteAtteignable) Aurora else Amber,
                    modifier = Modifier.size(22.dp))
            }
        }

        // ---- la fréquence ---------------------------------------------------
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
                // Les pas : 1 kHz pour se poser sur un correspondant, 100 Hz
                // pour affiner une SSB, 10 kHz pour traverser le transpondeur.
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

                // La position dans le transpondeur, en clair. Sans elle, un
                // opérateur posé à 3 kHz du bord ne le sait pas — et sur un
                // géostationnaire il n'y a pas de fin de passage pour lui
                // apprendre son erreur.
                Spacer(Modifier.height(10.dp))
                val depuisBas = (q.descenteHz - tp.descenteBasHz) / 1000
                val restant = (tp.descenteHautHz - q.descenteHz) / 1000
                Text("${t("qo100_edges")}  −${depuisBas} kHz / +${restant} kHz",
                    color = if (depuisBas < 5 || restant < 5) Amber else TextLo,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // ---- ce que voit le matériel ---------------------------------------
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

        // ---- les mémoires ---------------------------------------------------
        //
        // Se poser au bon endroit dans 492 kHz est tout le travail sur QO-100.
        // Les repères du plan de bande ne se modifient pas — ce sont des faits
        // publiés, pas des préférences ; les mémoires posées par l'opérateur
        // s'ajoutent en dessous et se retirent d'un appui long.
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

        // ---- la réglette du transpondeur ------------------------------------
        // Seulement sur l'étroit : le large fait huit mégahertz et n'a pas de
        // plan de bande en segments, une réglette n'y voudrait rien dire.
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
                        // Hors réglette : ni segment, ni couleur, ni droit
                        // d'émettre. Le cas ne devrait pas se produire, le
                        // modèle bornant la fréquence — on le dit quand même.
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

        // ---- le panorama et le témoin de balise -----------------------------
        // Même condition que la réglette, et pour la même raison : l'axe du
        // panorama *est* celui de la réglette. Sur le large il n'y aurait ni
        // échelle commune, ni balise à surveiller.
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

                    // ------------------------------------- l'accord fin
                    // Le panorama ci-dessus étale 490 kHz sur la largeur de
                    // l'écran : environ 1,4 kHz par dp, soit une douzaine de
                    // kilohertz sous une pulpe de doigt. Il sert à trouver, pas
                    // à se poser. Ce qui suit sert à se poser.
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
                            // Le doigt désigne un écart par rapport à l'accord
                            // de la clé ; la page, elle, ne connaît que des
                            // descentes. On convertit l'un en l'autre plutôt que
                            // de laisser deux chiffres vivre côte à côte.
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

                    // --- le témoin de balise ---
                    // La balise médiane est allumée en permanence et à une
                    // fréquence connue au hertz : c'est le seul étalon dont
                    // dispose une station QO-100. Deux chiffres en sortent, et
                    // ils ne disent pas la même chose. L'écart, c'est la dérive
                    // du LNB — un défaut de fréquence, qui se corrige d'un
                    // bouton. Le rapport au plancher, c'est la qualité de
                    // réception — un défaut de pointage ou de câble, qui ne se
                    // corrige qu'à la main sur le mât.
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

        // ---- le calage sur la balise ---------------------------------------
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
                // La saisie est en fréquence du ciel : l'opérateur lit sa
                // cascade, il n'a pas à faire la soustraction lui-même.
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

        // ---- le transpondeur ------------------------------------------------
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

        // ---- les réglages de la station, repliés ----------------------------
        //
        // Les convertisseurs vivaient dans les réglages généraux, à côté de
        // ceux qui servent aux satellites bas. Or un oscillateur à 10 345 MHz
        // n'a de sens que pour QO-100 : le réglage se cherchait dans un écran
        // où l'on n'avait aucune raison d'aller.
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
                        // Le résumé se lit sans déplier : c'est ce qu'on veut
                        // vérifier d'un coup d'œil avant d'émettre.
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
                        // Sortir du transpondeur pour écouter ailleurs.
                        //
                        // La bride empêche la porteuse de partir chez le
                        // voisin, ce qui est bien en émission. Mais elle
                        // empêchait aussi d'**écouter** : chercher une balise,
                        // voir si le transpondeur large travaille, retrouver
                        // quelqu'un qui s'est déplacé. Décochée par défaut,
                        // celui qui n'a rien demandé garde la garde.
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

        // ---- le réglage initial, replié -------------------------------------
        //
        // Le pointage et l'alignement solaire ne servent **qu'une fois** : une
        // parabole visant un géostationnaire ne se retouche plus. Les laisser
        // déployés en permanence poussait vers le bas ce dont on se sert à
        // chaque passage — les fréquences, les mémoires. Repliés par défaut,
        // donc, et non supprimés : le jour où l'on déplace la station, ils
        // redeviennent la première chose dont on a besoin.
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
        // ---- le pointage de la parabole --------------------------------------
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
                    // Le seul cas où l'écran doit dire non : depuis l'Amérique
                    // ou le Pacifique, le satellite est de l'autre côté de la
                    // Terre et aucune parabole n'y peut rien.
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

        // ---- l'alignement par le Soleil --------------------------------------
        // Rien à afficher là où le satellite est sous l'horizon : il n'y a pas
        // d'ombre à suivre vers une direction qui n'existe pas.
        if (q.azDeg != null && q.elDeg != null && q.elDeg > 0.0) {
            Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(t("qo100_sun"), color = TextHi,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(t("qo100_sun_desc"), color = TextLo, fontSize = 11.sp)

                    val quand = tzFormat("EEE dd/MM HH:mm", ui.useUtc)

                    // --- le service de tous les jours ---
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

                    // --- le réglage fin, deux fois l'an ---
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

        // ---- ce qu'est ce satellite ----------------------------------------
        //
        // L'avertissement sur la montée non mesurée occupait cinq lignes en
        // tête d'écran, à chaque visite, alors qu'il ne concerne qu'un geste :
        // émettre. Il est descendu **à côté du convertisseur de montée**, là
        // où l'on peut agir, et réduit à une ligne. Il disparaîtra de lui-même
        // le jour où cet oscillateur sera mesuré.
        //
        // Un avertissement qu'on lit dix fois sans pouvoir rien en faire finit
        // par ne plus être lu du tout.
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(t("qo100_desc"), color = TextLo, fontSize = 12.sp)
            }
        }
}

/**
 * La couleur d'un usage sur la réglette.
 *
 * Trois familles se distinguent au premier coup d'œil, et c'est tout ce qu'on
 * demande à ces couleurs : les balises en magenta, sur lesquelles on n'émet
 * jamais ; les fréquences réservées — diffusion, urgence — en ambre, la
 * couleur que l'application emploie partout ailleurs pour dire « attention » ;
 * le trafic ordinaire en cyan, plus pâle pour les modes numériques que pour la
 * phonie. La télégraphie prend l'aurore parce qu'elle occupe le bas de la
 * bande d'un bloc et qu'elle mérite d'être repérable de loin.
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
 * Les 500 kHz du transpondeur étroit, dessinés à l'échelle.
 *
 * C'est la vue qui manquait : un opérateur qui lit « 10 489,750 » sait où il
 * est, mais un opérateur qui *voit* où il est sait aussi ce qu'il y a autour
 * de lui — la balise à cinq kilohertz, le segment numérique où sa BLU serait
 * un abus, le bord de bande qu'il s'apprête à franchir. Le chiffre dit la
 * position, la réglette dit le voisinage.
 *
 * ### Ce qui est dessiné, du bas vers le haut
 *
 * La barre porte les douze segments de [Qo100.SEGMENTS] bout à bout, chacun de
 * sa couleur. Comme les segments se recollent sans trou — un essai le prouve
 * au kilohertz — la barre est pleine, et une couleur qui manquerait se verrait
 * comme un trou noir. Au-dessus, un trait par repère : les quatre balises et
 * les deux fréquences réservées. En dessous, une graduation tous les 50 kHz,
 * assez pour donner l'échelle sans faire une règle graduée.
 *
 * Le curseur traverse toute la hauteur, coiffé d'une pastille : il faut qu'il
 * reste visible même posé sur un segment cyan.
 *
 * ### Le doigt
 *
 * Le geste est direct — on touche l'endroit où l'on veut aller, et on peut
 * glisser sans lever le doigt. Cinq cents kilohertz sur une largeur d'écran
 * font environ un kilohertz et demi par pixel : c'est grossier, et c'est
 * voulu. La réglette sert à traverser la bande, pas à se poser au hertz près ;
 * les boutons de pas, juste au-dessus, sont là pour l'affinage.
 *
 * La fréquence rendue n'est pas bornée ici : [MainViewModel.setQo100Descente]
 * la ramène déjà dans le transpondeur. Les deux balises CW extrêmes restent
 * donc des repères visibles sur lesquels on ne peut pas se poser, ce qui est
 * exactement ce qu'on veut d'une butée.
 */
/**
 * La mesure d'un oscillateur par deux fréquences observées.
 *
 * L'opérateur ne calcule rien : il recopie ce qu'il lit sur une référence et
 * ce qu'affiche son poste, pour **le même signal**. La soustraction ne se
 * trompe jamais de sens, contrairement à celui qui la fait de tête à
 * vingt-deux heures.
 *
 * Le résultat est affiché en clair plutôt que réduit à un « validé » : un
 * oscillateur faux mais plausible ne se détecte qu'à l'oreille, en cherchant
 * la balise, et il faut donc pouvoir comparer le chiffre obtenu à celui qu'on
 * attendait.
 */
/**
 * Le poste qui tient la station, choisi ici plutôt qu'ailleurs.
 *
 * Le modèle vivait dans les réglages CAT, à trois écrans de là. Or c'est le
 * même geste que le reste de ce bloc : on arrive quelque part, on branche ce
 * qu'on a, et l'on dit à l'appareil ce que c'est. Séparer le poste des
 * convertisseurs obligeait à traverser l'application pour décrire une seule
 * installation.
 *
 * **Un seul endroit conserve la valeur.** Ce sélecteur écrit dans le même
 * réglage que celui des réglages CAT — il ne la recopie pas. C'est ce qui a
 * fait diverger les convertisseurs, et la leçon vaut ici : deux copies d'une
 * même valeur finissent par ne plus dire la même chose.
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
 * L'appareil qui reçoit, et son écart propre.
 *
 * La même mesure ne donne pas le même oscillateur au FT-817 et à la clé, alors
 * qu'il n'y a qu'un LNB : la différence est le quartz de l'appareil, et elle
 * lui appartient. La ranger dans la chaîne confondrait deux erreurs
 * indépendantes, et corriger l'une déplacerait l'autre.
 *
 * Zéro par défaut, référence non réglable : tant qu'on n'a rien mesuré, rien
 * ne bouge — sur QO-100 comme sur les satellites à défilement.
 */
@Composable
private fun ChoixPoste(ui: UiState, vm: MainViewModel) {
    Text(t("qo100_poste"), color = TextHi,
        fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Spacer(Modifier.height(2.dp))
    Text(t("qo100_poste_desc"), color = TextLo, fontSize = 10.sp)
    Spacer(Modifier.height(6.dp))
    listOf(
        "IC9700" to "Icom IC-9700",
        "FT817x2" to "2× Yaesu FT-817",
        "FT817TX" to t("qo100_poste_sdr"),
    ).forEach { (id, libelle) ->
        LigneChoix(
            actif = ui.rigModel == id,
            titre = libelle,
            detail = "",
            onClick = { vm.setRigModel(id) })
    }
}

/**
 * Le choix du convertisseur, avec **la fréquence qu'il donnera en face**.
 *
 * Un oscillateur local est un nombre à onze chiffres : personne ne le
 * reconnaît, et personne ne peut dire de mémoire lequel correspond à son
 * matériel. Une fréquence intermédiaire, si — « 144,750 », on sait
 * immédiatement si c'est la bonne, parce que c'est ce qu'on lit sur la face
 * avant du poste depuis toujours.
 *
 * Chaque choix montre donc **ce que le poste afficherait** pour la fréquence
 * du ciel où l'on se trouve à cet instant. On ne choisit plus un oscillateur
 * en aveugle : on choisit un résultat, et on le voit avant de le poser.
 *
 * Le dernier choix, « prise directe », retire le convertisseur — le poste ou
 * la clé travaille alors sur la fréquence du ciel.
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
        // La fréquence que ce choix donnerait, ici et maintenant. Hors de la
        // plage du préréglage, on n'invente rien : le convertisseur ne
        // s'appliquerait pas, et l'annoncer serait mentir.
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

/** Une ligne de choix : une coche, un nom, et le résultat en face. */
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
                // Une virgule pour un point : c'est ce que donne le clavier
                // français, et refuser la saisie serait la refuser à tous.
                val c = ciel.replace(',', '.').trim().toDoubleOrNull()
                val p = poste.replace(',', '.').trim().toDoubleOrNull()
                // Arrondi et non troncature : « 10489.498 » vaut
                // 10489497999,999998 en virgule flottante, et couper la
                // partie décimale ôterait un hertz au passage. Un hertz ne se
                // voit pas, mais un calage qu'on croit exact et qui ne l'est
                // pas se paie plus tard, quand on cherche d'où vient l'écart.
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
        // Vert pour une mesure prise, ambre pour un refus : la couleur dit
        // l'issue avant qu'on ait lu la phrase.
        Text(message, color = if (reussi) Aurora else Amber, fontSize = 11.sp)
    }
}

/**
 * La réglette du plan de bande, partagée par les deux écrans.
 *
 * Elle vivait dans l'écran QO-100 seul. La page du passage, elle, n'avait
 * qu'une barre unie : on s'y déplaçait dans les 492 kHz sans savoir si l'on
 * arrivait sur la CW, sur le numérique étroit ou sur une balise — c'est-à-dire
 * sans savoir si l'on avait le droit d'y émettre.
 *
 * **Un seul composant pour les deux écrans**, plutôt qu'un second dessin à
 * tenir à jour : le plan de bande change rarement, mais quand il change, il
 * doit changer partout à la fois.
 */
@Composable
internal fun Reglette(descenteHz: Long, sur: (Long) -> Unit) {
    val bas = Qo100.REGLETTE_BAS_HZ
    val etendue = (Qo100.REGLETTE_HAUT_HZ - bas).toDouble()

    // La conversion pixel → hertz, écrite une fois pour les deux gestes.
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

        // Les douze segments, dans l'ordre du plan de bande.
        Qo100.SEGMENTS.forEach { s ->
            val x0 = x(s.basHz)
            val x1 = x(s.hautHz)
            drawRect(
                color = couleurUsage(s.usage),
                topLeft = Offset(x0, hautBarre),
                // Au moins un pixel : la balise basse ne fait que 5 kHz, soit
                // un centième de la réglette, et un arrondi à zéro l'effacerait.
                size = Size((x1 - x0).coerceAtLeast(1f), hauteurBarre),
            )
        }

        // Les repères, au-dessus de la barre.
        Qo100.SEGMENTS.forEach { s ->
            val r = s.repereHz ?: return@forEach
            drawLine(
                color = couleurUsage(s.usage),
                start = Offset(x(r), 0f),
                end = Offset(x(r), hautBarre),
                strokeWidth = 2.dp.toPx(),
            )
        }

        // La graduation, tous les 50 kHz.
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

        // Le curseur, par-dessus tout le reste.
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

/** Une ligne « − pas … + pas », symétrique, avec le pas écrit au milieu. */
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(titre, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = coche, onCheckedChange = sur,
            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
    }
}

/** Ce que le matériel affiche, ou l'ambre quand il ne peut pas y aller. */
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
 * Une fréquence en mégahertz, au hertz près.
 *
 * Trois décimales suffiraient pour lire un plan de bande, mais pas pour caler
 * un LNB : la dérive se mesure en centaines de hertz une fois l'oscillateur
 * chaud, et c'est précisément ce qu'on cherche à voir.
 */
private fun qoMhz(hz: Long): String =
    String.format(Locale.US, "%,.6f", hz / 1_000_000.0).replace(',', ' ')

/**
 * Un écart en hertz, signé.
 *
 * La mesure de balise se lit en hertz et pas en kilohertz : sous les cinq cents
 * hertz, un affichage en kilohertz écrirait « +0,000 » et ferait croire à un
 * calage parfait là où la BLU est déjà décalée d'un demi-timbre.
 */
private fun qoHzSigne(hz: Long): String = (if (hz > 0) "+" else "") + "$hz Hz"

/** Un écart, signé et en kilohertz : c'est l'ordre de grandeur d'un LNB. */
private fun qoKhzSigne(hz: Long): String {
    val signe = if (hz > 0) "+" else ""
    return signe + String.format(Locale.US, "%.3f kHz", hz / 1000.0)
}

/** Relit une saisie en mégahertz, en tolérant l'espace et la virgule. */
private fun qoDepuisMhz(texte: String): Long? {
    val propre = texte.replace(" ", "").replace(" ", "").replace(',', '.')
    val v = propre.toDoubleOrNull() ?: return null
    return Math.round(v * 1_000_000.0)
}
