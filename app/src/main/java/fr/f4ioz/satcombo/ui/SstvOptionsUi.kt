/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.OptionsSstvTelephone
import fr.f4ioz.satcombo.sstv.QslSstv
import fr.f4ioz.satcombo.sstv.QualiteSstv
import fr.f4ioz.satcombo.sstv.SstvHub
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The SSTV options on the phone's SSTV screen: the reception quality under
 * the live picture, the « Options SSTV » card, and the viewer's QSL, ARISS
 * pack and « à garder ».
 */

private val Rouge = Color(0xFFE5484D)

/** The live reception quality: the level's bar and verdict, each line, the SNR, the mark it heads for. */
@Composable
fun QualiteSstvDirect() {
    val q by SstvHub.qualite.collectAsState()
    val e = q ?: return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(t("sq_titre"), color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        val db = e.niveauDb
        val couleur = when (e.niveau) {
            QualiteSstv.Niveau.SATURE -> Rouge
            QualiteSstv.Niveau.FORT, QualiteSstv.Niveau.FAIBLE -> Amber
            QualiteSstv.Niveau.SILENCE -> TextLo
            QualiteSstv.Niveau.BON -> Aurora
        }
        fun part(d: Double?) = (((d ?: -96.0) + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(SpaceSurface)) {
            Box(Modifier.fillMaxWidth(part(db)).fillMaxHeight().background(couleur))
        }
        if (db != null) Text(tf("sq_niveau", Math.round(db), Math.round(e.creteDb ?: db)), color = TextLo, fontSize = 11.sp)
        val avis = when (e.niveau) {
            QualiteSstv.Niveau.SATURE -> t("sq_sature"); QualiteSstv.Niveau.FORT -> t("sq_fort")
            QualiteSstv.Niveau.FAIBLE -> t("sq_faible"); QualiteSstv.Niveau.SILENCE -> t("sq_silence"); QualiteSstv.Niveau.BON -> t("sq_bon")
        }
        Text(avis, color = if (e.niveau == QualiteSstv.Niveau.BON) TextLo else couleur, fontSize = 11.sp)
        if (e.qualites.isNotEmpty()) {
            val n = maxOf(e.hauteur, e.qualites.size, 1)
            Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(SpaceSurface)) {
                val w = size.width / n
                e.qualites.forEachIndexed { i, v ->
                    val h = maxOf(3f, v / 100f * size.height)
                    drawRect(if (v >= 80) Aurora else if (v >= 35) Amber else Rouge, Offset(i * w, size.height - h), Size(maxOf(1f, w), h))
                }
            }
        }
        val snr = if (e.snrSousSeuil) t("sq_snr_sous") else e.snrDb?.let { tf("sq_snr", Math.round(it)) }
        if (snr != null) Text(snr, color = TextLo, fontSize = 11.sp)
        if (e.lignes > 0) Text(tf("sq_sync", e.trouvees, e.lignes, e.nettes), color = TextLo, fontSize = 11.sp)
        val note = if (e.image) e.noteEstimee?.let { tf("sq_note", it) } else e.noteFinale?.let { tf("sq_note_finale", it) }
        val n = if (e.image) e.noteEstimee else e.noteFinale
        if (note != null) Text(note, color = if ((n ?: 0) >= 80) Aurora else if ((n ?: 0) >= 50) Amber else Rouge, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        else if (!e.image) Text(t("sq_attente"), color = TextLo, fontSize = 11.sp)
        Text(t("sq_aide"), color = TextLo, fontSize = 10.sp)
    }
}

@Composable
private fun Coche(texte: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = change)
        Text(texte, color = TextHi, fontSize = 13.sp)
    }
}

@Composable
private fun Nombre(texte: String, v: Int, change: (Int) -> Unit) {
    var s by remember(v) { mutableStateOf(v.toString()) }
    OutlinedTextField(value = s, onValueChange = { x -> s = x.filter { it.isDigit() }.take(7); s.toIntOrNull()?.let(change) },
        label = { Text(texte, fontSize = 11.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}

/** « Options SSTV »: the missing slide's notice, each pass's sound and how long it is kept, the QSL's layout. */
@Composable
fun CarteOptionsSstv() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val o = remember { OptionsSstvTelephone.options(ctx) }
    var version by remember { mutableStateOf(0) }
    var bilan by remember { mutableStateOf("") }
    var aSupprimer by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) {
        val p = withContext(Dispatchers.IO) { runCatching { OptionsSstvTelephone.planRetention(ctx) }.getOrNull() }
        fun mo(b: Long) = "%d Mo".format(b / 1_048_576L)
        bilan = p?.let { tf("so_ret_bilan", it.sstv, mo(it.octetsSstv), it.supprimer.size, mo(it.octetsLiberes)) }.orEmpty()
        aSupprimer = p?.supprimer?.size ?: 0
    }
    fun change(f: () -> Unit) { f(); version++ }
    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(t("so_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            key(version) {
                Coche(t("so_alerte"), o.alerteDiapo) { change { o.alerteDiapo = it } }
                Nombre(t("so_alerte_avant"), o.alerteAvantMin) { o.alerteAvantMin = it }
                Nombre(t("so_alerte_note"), o.alerteNoteMin) { o.alerteNoteMin = it }
                Text(t("so_alerte_aide"), color = TextLo, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Coche(t("so_redecode"), o.redecodeFin) { change { o.redecodeFin = it } }
                Text(t("so_redecode_aide"), color = TextLo, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Coche(t("so_enreg"), o.enregistrePassages) { change { o.enregistrePassages = it } }
                Text(t("so_enreg_aide"), color = TextLo, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Text(t("so_ret"), color = TextHi, fontSize = 13.sp)
                Nombre(t("so_ret_jours"), o.retentionJours) { o.retentionJours = it; version++ }
                Nombre(t("so_ret_mo"), o.retentionMaxMo) { o.retentionMaxMo = it; version++ }
                Coche(t("so_ret_journal"), o.retentionJournal) { change { o.retentionJournal = it } }
                Text(t("so_ret_aide"), color = TextLo, fontSize = 10.sp)
                if (bilan.isNotBlank()) Text(bilan, color = TextLo, fontSize = 11.sp)
                OutlinedButton(enabled = aSupprimer > 0, onClick = {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { OptionsSstvTelephone.appliqueRetention(ctx, "à la main") }
                        message = tf("so_ret_fait", n); version++
                    }
                }) { Text(t("so_ret_appliquer"), color = Cyan, fontSize = 12.sp) }
                Spacer(Modifier.height(6.dp))
                Text(t("so_qsl"), color = TextHi, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = o.qslDisposition == QslSstv.Disposition.BANDEAU, onClick = { change { o.qslDisposition = QslSstv.Disposition.BANDEAU } },
                        label = { Text(t("so_qsl_bandeau"), fontSize = 12.sp) })
                    FilterChip(selected = o.qslDisposition == QslSstv.Disposition.INCRUSTE, onClick = { change { o.qslDisposition = QslSstv.Disposition.INCRUSTE } },
                        label = { Text(t("so_qsl_incruste"), fontSize = 12.sp) })
                }
                Coche(t("so_qsl_satme"), o.qslSatme) { change { o.qslSatme = it } }
                Coche(t("so_qsl_jpeg"), o.qslJpeg) { change { o.qslJpeg = it } }
                val st = remember { SettingsStore(ctx) }
                Text(tf("so_qsl_station", st.callsign.ifBlank { "—" }, SstvHub.qthLocator.ifBlank { st.manualLocator }.ifBlank { "—" }), color = TextLo, fontSize = 10.sp)
            }
            message?.let { Text(it, color = Amber, fontSize = 11.sp) }
        }
    }
}

/** The viewer's QSL, ARISS pack and « à garder »; [partage] shares a file made. */
@Composable
fun OutilsQslSstv(file: File, shot: SstvMeta.SstvShot, partage: (File, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var garde by remember(file.name) { mutableStateOf(file.name in OptionsSstvTelephone.gardes(ctx)) }
    var occupe by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(modifier = Modifier.weight(1f), enabled = !occupe, onClick = {
            occupe = true
            scope.launch {
                val f = withContext(Dispatchers.IO) { OptionsSstvTelephone.qsl(ctx, file, shot) }
                occupe = false
                if (f != null) partage(f, if (f.name.endsWith(".jpg")) "image/jpeg" else "image/png") else message = tf("so_v_erreur", file.name)
            }
        }) { Text("🪪 " + t("so_v_qsl"), color = Cyan, fontSize = 12.sp) }
        OutlinedButton(modifier = Modifier.weight(1f), enabled = !occupe, onClick = {
            occupe = true
            scope.launch {
                val f = withContext(Dispatchers.IO) { OptionsSstvTelephone.pack(ctx, listOf(file to shot)) }
                occupe = false
                if (f != null) partage(f, "application/zip") else message = tf("so_v_erreur", file.name)
            }
        }) { Text("🏅 " + t("so_v_ariss"), color = Cyan, fontSize = 12.sp) }
        OutlinedButton(modifier = Modifier.weight(1f), onClick = {
            garde = file.name in OptionsSstvTelephone.marqueGarde(ctx, file.name, !garde)
        }) { Text((if (garde) "★ " + t("so_v_gardee") else "☆ " + t("so_v_garder")), color = if (garde) Amber else Cyan, fontSize = 12.sp) }
    }
    message?.let { Text(it, color = Amber, fontSize = 11.sp) }
}
