/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.DatvTelephone
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.delay

/**
 * QO-100's wide band on the phone: a SatMe-Node next to the antenna receives
 * with its MiniTiouner, the phone drives it and shows its stream.
 */
@Composable
fun DatvScreen(d: DatvTelephone) {
    val u by d.ui.collectAsState()
    DisposableEffect(Unit) { d.revient(); onDispose { d.quitte() } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Video(u.flux, u.etat.actif, if (!u.etat.actif) t("datv_arrete") else if (u.etat.verrouille) t("datv_attente_image") else u.etat.libelle + "…")
        Etat(u)
        if (u.message.isNotBlank()) Text(u.message, color = Amber, fontSize = 12.sp)
        if (!u.associe) Noeud(u, d) else Reglages(u, d)
        if (u.associe) Constellation(u)
        if (u.associe) Noeud(u, d, replie = true)
    }
}

/** The stream played as it comes, taken up again when it breaks. */
@OptIn(UnstableApi::class)
@Composable
private fun Video(flux: String, actif: Boolean, attente: String) {
    val ctx = LocalContext.current
    var images by remember { mutableStateOf(false) }
    var essai by remember { mutableIntStateOf(0) }
    val lecteur = remember {
        ExoPlayer.Builder(ctx)
            // Small buffers: a live picture, not a film.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 4000, 250, 500).build())
            .build()
    }
    DisposableEffect(lecteur) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { images = true }
            override fun onPlayerError(error: PlaybackException) { images = false; essai++ }
        }
        lecteur.addListener(l)
        onDispose { lecteur.removeListener(l); lecteur.release() }
    }
    LaunchedEffect(flux, essai) {
        images = false
        if (flux.isBlank()) { lecteur.stop(); lecteur.clearMediaItems(); return@LaunchedEffect }
        if (essai > 0) delay(1500)
        val extracteurs = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val source = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory().setReadTimeoutMs(0), extracteurs)
            .createMediaSource(MediaItem.fromUri(flux))
        lecteur.setMediaSource(source)
        lecteur.prepare()
        lecteur.playWhenReady = true
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        AndroidView(factory = { PlayerView(it).apply { useController = false; player = lecteur } }, modifier = Modifier.fillMaxSize())
        if (!images || !actif) Text(attente, color = TextLo, fontSize = 14.sp)
    }
}

@Composable
private fun Etat(u: DatvTelephone.Ui) {
    val e = u.etat
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = (if (e.verrouille) Aurora else if (e.actif) Amber else TextLo).copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
            Text(if (e.actif) e.libelle else t("datv_arrete"), color = if (e.verrouille) Aurora else if (e.actif) Amber else TextLo,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Text("MER " + (e.merDb?.takeIf { e.verrouille }?.let { "%.1f dB".format(it) } ?: "—"), color = TextHi, fontSize = 18.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        if (e.verrouille) Text(e.modcod, color = Cyan, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
    val ligne = listOfNotNull(
        if (e.fiTrouveeKhz > 0) "%.3f MHz".format((e.fiTrouveeKhz + u.reglage.olKhz) / 1000.0) else null,
        if (e.srTrouveKs > 0) "${e.srTrouveKs} kS/s" else null,
        if (e.verrouille) tf("datv_ecart", e.fiTrouveeKhz - u.reglage.fiKhz) else null,
        listOf(e.fournisseur, e.service).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
        e.flux.takeIf { it.isNotEmpty() }?.joinToString(", ") { Datv.typeFlux(it.second) })
    if (ligne.isNotEmpty()) Text(ligne.joinToString("  ·  "), color = TextLo, fontSize = 12.sp)
}

@Composable
private fun Reglages(u: DatvTelephone.Ui, d: DatvTelephone) {
    val r = u.reglage
    var ciel by remember(r.cielKhz) { mutableStateOf("%.3f".format(java.util.Locale.US, r.cielKhz / 1000.0)) }
    var ol by remember(r.olKhz) { mutableStateOf("%.3f".format(java.util.Locale.US, r.olKhz / 1000.0)) }
    fun khz(s: String) = s.replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 1000.0) }
    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ciel, { ciel = it; khz(it)?.let { k -> d.regle(r.copy(cielKhz = k)) } }, label = { Text(t("datv_ciel"), fontSize = 12.sp) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { d.regle(r.copy(cielKhz = Datv.BALISE_KHZ, srKs = 1500)) }) { Text(t("datv_balise"), color = Cyan) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Datv.CANAUX_KHZ.forEach { k ->
                    FilterChip(selected = r.cielKhz == k, onClick = { d.regle(r.copy(cielKhz = k)) },
                        label = { Text("%.2f".format(java.util.Locale.US, k / 1000.0 - 10_000), fontSize = 11.sp) })
                }
            }
            Text(t("datv_debit"), color = TextLo, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Datv.DEBITS.forEach { s ->
                    FilterChip(selected = r.srKs == s, onClick = { d.regle(r.copy(srKs = s)) }, label = { Text("$s", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(alpha = 0.25f)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ol, { ol = it; khz(it)?.let { k -> d.regle(r.copy(olKhz = k)) } }, label = { Text(t("datv_ol"), fontSize = 12.sp) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { d.cale() }) { Text(t("datv_caler"), color = Cyan, fontSize = 12.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("datv_entree"), color = TextLo, fontSize = 12.sp)
                FilterChip(selected = !r.entreeB, onClick = { d.regle(r.copy(entreeB = false)) }, label = { Text("A") })
                FilterChip(selected = r.entreeB, onClick = { d.regle(r.copy(entreeB = true)) }, label = { Text("B") })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("datv_lnb"), color = TextLo, fontSize = 12.sp)
                listOf("" to t("datv_lnb_coupee"), "v" to "13 V", "h" to "18 V").forEach { (v, l) ->
                    FilterChip(selected = r.lnb == v, onClick = { d.regle(r.copy(lnb = v)) }, label = { Text(l, fontSize = 11.sp) })
                }
            }
            if (r.lnb.isNotEmpty()) Text(t("datv_lnb_avert"), color = Amber, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { d.recois() }, enabled = !u.occupe, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                    Text(t("datv_recevoir"), color = Color(0xFF06131A), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { d.arrete() }) { Text(t("datv_arreter"), color = TextHi) }
            }
            Text(tf("datv_fi", "%.3f".format(java.util.Locale.US, r.fiKhz / 1000.0)), color = TextLo, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Constellation(u: DatvTelephone.Ui) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val couleur = if (u.etat.verrouille) Cyan else Amber
            Canvas(Modifier.size(150.dp).background(Color(0xFF0E1720), RoundedCornerShape(8.dp))) {
                val w = size.width
                drawLine(Color(0xFF22303B), Offset(w / 2, 0f), Offset(w / 2, w)); drawLine(Color(0xFF22303B), Offset(0f, w / 2), Offset(w, w / 2))
                u.points.forEach { (i, q) -> drawCircle(couleur.copy(alpha = 0.8f), 2.2f, Offset(w / 2 + i * w / 256f, w / 2 - q * w / 256f)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t("datv_constellation"), color = TextLo, fontSize = 11.sp)
                val e = u.etat
                if (e.actif) {
                    Text("AGC1 ${e.agc1} · AGC2 ${e.agc2}", color = TextHi, fontSize = 12.sp)
                    // Measured once locked only.
                    if (e.verrouille) e.trames?.let { Text(if (it) t("datv_trames_courtes") else t("datv_trames_normales"), color = TextHi, fontSize = 12.sp) }
                    if (e.verrouille) e.pilotes?.let { Text(if (it) t("datv_pilotes") else t("datv_sans_pilotes"), color = TextHi, fontSize = 12.sp) }
                    if (e.nulsPct > 0) Text(tf("datv_vide", e.nulsPct), color = TextHi, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Joining the node (its address and its code), or which one it is, with the way to change it. */
@Composable
private fun Noeud(u: DatvTelephone.Ui, d: DatvTelephone, replie: Boolean = false) {
    var ouvert by remember { mutableStateOf(!replie) }
    var adresse by remember(u.adresse) { mutableStateOf(u.adresse) }
    var code by remember { mutableStateOf("") }
    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("datv_noeud_titre"), color = TextHi, fontWeight = FontWeight.SemiBold)
                    Text(if (u.associe) tf("datv_noeud_associe", u.nom.ifBlank { u.adresse }, u.adresse) else t("datv_noeud_desc"),
                        color = TextLo, fontSize = 11.sp)
                }
                if (replie) TextButton(onClick = { ouvert = !ouvert }) { Text(t("datv_changer"), color = Cyan) }
            }
            if (ouvert) {
                OutlinedTextField(adresse, { adresse = it }, label = { Text(t("datv_adresse"), fontSize = 12.sp) }, placeholder = { Text("192.168.1.30") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(t("datv_code"), fontSize = 12.sp) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.weight(1f))
                    Button(onClick = { d.rejoins(adresse, code); code = "" }, enabled = !u.occupe) { Text(t("datv_associer")) }
                }
            }
        }
    }
}
