/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Ce que la clé SDR et le décodeur SSTV montrent sur la page du passage.
 *
 * L'idée est simple à énoncer et change tout à l'usage : quand on reçoit un
 * satellite, on ne veut pas choisir entre voir la boussole et régler la clé.
 * Sur un passage de dix minutes, aller-retour entre deux écrans pour toucher
 * au gain, c'est du temps où l'on ne pointe plus l'antenne. Ces deux blocs se
 * glissent donc juste sous la boussole : l'image SSTV en train d'arriver
 * d'abord, la clé et ses réglages ensuite.
 *
 * L'écran SDR complet reste là pour tout le reste (débit, ppm, dossier des
 * images) ; ici on ne met que ce qui se règle une antenne à la main.
 */

/**
 * La bande image du passage : SSTV ou NOAA, au choix.
 *
 * Un satellite ne fait jamais les deux à la fois et l'écran n'a la place que
 * d'une image : la puce de titre porte donc le choix, d'une touche, avec un
 * raccourci vers les réglages du décodeur concerné.
 *
 * Tant que rien n'est reçu, la bande se réduit à une ligne — la puce et le
 * bouton d'enregistrement. C'est justement là qu'on veut lancer la capture :
 * on est sur la page du passage, le satellite se lève, et il serait absurde
 * d'aller la chercher dans un menu. Dès que l'enregistrement est coupé,
 * l'image disparaît : une image figée sous la boussole laisse croire qu'on
 * reçoit encore.
 */
@Composable
fun RxImageInline(ui: UiState, vm: MainViewModel) {
    // Deux conditions, et la seconde est celle qui compte à l’usage :
    // la bande ne s’affiche que si le décodeur correspondant est armé dans
    // les réglages. Un opérateur qui a décoché SSTV et NOAA a dit qu’il ne
    // faisait pas d’images ; lui laisser la bande sous la boussole, c’est lui
    // occuper le haut de l’écran avec une fonction qu’il a refusée.
    val hasSstv = fr.f4ioz.satcombo.data.Extensions.SSTV in ui.extensions && ui.sstvEnabled
    val hasApt = fr.f4ioz.satcombo.data.Extensions.APT in ui.extensions && ui.aptEnabled
    if (!hasSstv && !hasApt) return

    // Le mode retenu, sauf s'il n'est pas déverrouillé : mieux vaut montrer
    // l'autre que rien du tout.
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

/** Le choix SSTV / NOAA, et la porte vers les réglages du décodeur choisi. */
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
 * Le bouton d'enregistrement de la bande : rouge à l'arrêt, carré pendant.
 *
 * Il commande le même magnétophone que le reste de l'application — il n'y a
 * qu'une capture à la fois — mais depuis l'endroit où l'on regarde l'image
 * arriver.
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

/** L'image SSTV en cours de construction, sous la boussole. */
@Composable
private fun SstvInlineCard(ui: UiState, vm: MainViewModel, onTitleClick: () -> Unit) {
    val st by SstvHub.state.collectAsState()
    // L'image ne survit pas à l'arrêt de l'écoute : le décodeur garde la
    // dernière trame pour l'écran SSTV complet, la page du passage non.
    val bmp = if (st.listening) st.preview else null

    // La prévisualisation est le même objet Bitmap rempli ligne à ligne : on
    // reconstruit l'ImageBitmap dès que la progression bouge, sinon Compose ne
    // voit aucune raison de redessiner et l'image resterait figée.
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
            // Le satellite auquel l'image appartient : c'est ce qui distingue
            // une archive d'un tas de PNG.
            val sat = st.satName.ifBlank { ui.selected?.name.orEmpty() }
            if (st.listening && sat.isNotBlank()) {
                Text(sat, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (st.listening) {
                // Le mode imposé et le départ à la main, à portée de pouce
                // pendant le passage : c'est là, antenne en l'air, qu'on
                // s'aperçoit que l'en-tête est passé sans être vu.
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
 * L'image NOAA en cours de construction, sous la boussole.
 *
 * Même dessin que la SSTV, à une différence près : une image APT n'a pas de
 * fin annoncée, elle s'allonge tant que le satellite est en vue. On montre donc
 * le nombre de lignes et l'accrochage de la synchronisation plutôt qu'un
 * pourcentage de trame, et un bouton permet de mettre à l'abri ce qui est reçu
 * sans couper l'écoute — un quart d'heure dehors, le système peut décider de
 * tuer l'application avant le coucher.
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
 * La clé SDR, en condensé, sur la page du passage.
 *
 * Invisible tant qu'aucune clé n'est branchée. Dès qu'elle l'est : un bouton
 * pour recevoir, la fréquence réellement affichée (Doppler compris), le niveau,
 * et les trois interrupteurs qu'on touche en l'air — le son, le SSTV, le MP3 —
 * plus le gain. Le logo passe au vert dès que la réception tourne.
 */
@Composable
fun SdrInline(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val st by SdrHub.state.collectAsState()

    // La clé se branche en plein passage : on regarde régulièrement plutôt que
    // d'attendre un événement, comme sur l'écran SDR complet.
    var present by remember { mutableStateOf(SdrHub.devicePresent(ctx) != null) }
    LaunchedEffect(Unit) {
        while (true) {
            present = SdrHub.devicePresent(ctx) != null
            delay(2000)
        }
    }
    // **L'absence de clé se dit, quand on l'attendait.**
    //
    // Se retirer en silence est juste tant que la clé n'a pas de rôle : un
    // cadre vide ne renseigne personne. Mais l'opérateur qui a choisi
    // « FT-817 + clé SDR » attend une cascade, et son absence ressemble à un
    // défaut du logiciel alors que c'est un câble. On nomme donc la condition
    // qui manque, plutôt que de laisser chercher.
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
                // Le logo de la clé : vert pendant la réception, cyan à l'arrêt.
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
                    // Toujours la fréquence du ciel, jamais la FI : c'est
                    // celle-là qu'on compare au panneau du passage juste
                    // au-dessus.
                    Text("%.4f MHz".format(vm.cleVersSat(st.centerHz) / 1_000_000.0),
                        color = Aurora, fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text((if (st.dopplerHz >= 0) "+" else "") + "${st.dopplerHz} Hz",
                        color = TextLo, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                // **Sur quoi la clé est réellement accordée.**
                //
                // La ligne du dessus donne la fréquence du ciel, parce que
                // c'est celle sur laquelle on travaille et qu'on note. Mais
                // avec un convertisseur dans la chaîne, ce n'est pas ce que la
                // clé reçoit — et rien ne permettait de vérifier qu'elle était
                // pilotée au bon endroit. On ne pouvait que constater
                // l'absence de signal, sans savoir si la clé cherchait en 144,
                // en 739, ou en 10 489 où aucune clé du commerce ne va.
                //
                // Affichée seulement quand un convertisseur s'applique
                // vraiment à la clé : sans lui, les deux nombres seraient
                // identiques.
                if (vm.convertisseurSurLaCle) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("sdr_sur_cle"), color = TextLo, fontSize = 10.sp)
                        Spacer(Modifier.weight(1f))
                        Text("%.4f MHz".format(st.centerHz / 1_000_000.0),
                            color = Aurora.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
                // **Mode et largeur, ici aussi.**
                //
                // Ils n'existaient que dans l'écran SDR complet. Or c'est en
                // pleine écoute qu'on s'aperçoit que la bande est trop étroite
                // ou le mode mal choisi — sur QO-100 le FT8 tient dans 3 kHz
                // quand la voix se contente de 2,4, et une bande trop serrée
                // rabote le signal sans qu'on comprenne pourquoi il paraît
                // faible. Aller le corriger dans un autre écran fait perdre le
                // passage.
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
                            // Zéro n'est pas une largeur : c'est « laisse le
                            // mode décider », et le dire vaut mieux que
                            // d'afficher « 0 Hz ».
                            label = {
                                Text(if (b == 0) t("sdr_bw_auto") else "${b / 1000.0} k",
                                    fontSize = 10.sp)
                            })
                    }
                }

                Spacer(Modifier.height(6.dp))
                InlineLevelBar(st.levelDb)

                // La petite cascade : sur la page du passage on n'a pas la
                // place d'un spectre en plus, mais la trace suffit pour voir
                // le satellite arriver et poser le curseur dessus.
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

                // Le vernier, juste sous la cascade.
                //
                // La cascade sert à voir le satellite arriver, pas à s'accorder
                // dessus : le doigt y désigne une position absolue, et la main
                // masque précisément le signal qu'on vise. Le vernier fait
                // l'inverse — il pousse la fréquence sans rien cacher, à raison
                // de tant de hertz par centimètre. C'est ce qui permet de
                // rattraper un Doppler résiduel ou de se poser sur une bande
                // latérale pendant qu'on suit le satellite à l'antenne.
                if (ui.accord.vernier) {
                    Spacer(Modifier.height(6.dp))
                    Vernier(
                        // Le cadran gradue la grandeur qu'il déplace : le canal
                        // du transpondeur quand il y en a un, l'accord de la
                        // clé sinon. Graduer autre chose ferait défiler des
                        // chiffres qui ne correspondent à rien de ce qui bouge.
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

            // Les trois interrupteurs qu'on touche pendant un passage.
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniChip(t("sdr_audio"), ui.sdrAudio) { vm.setSdrAudio(!ui.sdrAudio) }
                MiniChip(t("sdr_sstv"), ui.sdrSstv) { vm.setSdrSstv(!ui.sdrSstv) }
                MiniChip(t("sdr_record"), ui.sdrRecord) { vm.setSdrRecord(!ui.sdrRecord) }
            }

            // Le gain : le seul réglage qui se retouche vraiment en l'air, quand
            // le satellite monte et que le signal passe de rien à saturé.
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

/** Barre de niveau compacte : -80 dBFS à gauche, 0 dBFS à droite. */
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
