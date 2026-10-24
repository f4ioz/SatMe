/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.ClassementSstv
import fr.f4ioz.satcombo.sstv.CorrectionsGalerie
import fr.f4ioz.satcombo.sstv.CorrectionsTelephone
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** What a corrected version says of itself: how it was made, the mark before and after. */
fun ditCorrection(f: CorrectionsGalerie.Fiche): String {
    val note = if (f.noteAvant >= 0 && f.noteApres >= 0) " · " + tf(if (f.methode == CorrectionsGalerie.Methode.FUSION) "sstv_corr_note_fus" else "sstv_corr_note", f.noteAvant, f.noteApres) else ""
    return when (f.methode) {
        CorrectionsGalerie.Methode.SON -> tf("sstv_corr_son", (if (f.derivePpm > 0) "+" else "") + f.derivePpm, f.bruitHz)
        CorrectionsGalerie.Methode.IMAGE -> t("sstv_corr_image")
        CorrectionsGalerie.Methode.FUSION -> tf("sstv_corr_fus", f.copies.size, f.duSon)
    } + note
}

/**
 * The viewer's picture, as received or processed ([CorrectionsGalerie]):
 * « Brute | Corrigée | Fusion | Comparer » (raw on the left, processed on
 * the right of a line moved with the slider), how the processed one was
 * made, and the actions: correct it, decode it again from its recording,
 * fuse its slide's copies. The received picture is never changed.
 */
@Composable
fun SstvImageCorrigee(file: File, shot: SstvMeta.SstvShot, brute: Bitmap?, surImage: @Composable BoxScope.() -> Unit = {}) {
    val ctx = LocalContext.current
    val api = remember { CorrectionsTelephone.api(ctx) }
    val dossier = file.parentFile ?: return
    var version by remember { mutableIntStateOf(0) }
    var vue by rememberSaveable { mutableStateOf("brute") }
    var cible by rememberSaveable { mutableStateOf("corr") }
    var curseur by remember { mutableFloatStateOf(0.5f) }
    var occupe by remember { mutableStateOf(api.enCours.isNotEmpty()) }
    var erreur by remember { mutableStateOf<String?>(null) }

    class Etat(val corr: CorrectionsGalerie.Fiche?, val fus: CorrectionsGalerie.Fiche?, val groupe: ClassementSstv.Groupe?, val son: Boolean,
               val bCorr: Bitmap?, val bFus: Bitmap?)
    val etat by produceState<Etat?>(null, file.absolutePath, version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val c = api.corrections
                val g = fr.f4ioz.satcombo.sstv.ClassementTelephone.etat(ctx).groupes.firstOrNull { gr -> gr.images.any { it.fileName == file.name } }?.takeIf { it.images.size > 1 }
                val corr = c.corrigee(dossier, file.name)
                val fus = g?.let { c.fusion(dossier, it.id) }
                fun bmp(f: CorrectionsGalerie.Fiche?) = f?.let { android.graphics.BitmapFactory.decodeFile(File(CorrectionsGalerie.dossier(dossier), it.fichier).absolutePath) }
                Etat(corr, fus, g, c.aUnSon(dossier, shot), bmp(corr), bmp(fus))
            }.getOrNull()
        }
    }
    // The work runs in the background (a second or two, ten for a group): its end shown when it comes.
    LaunchedEffect(occupe) {
        while (occupe) {
            delay(700)
            if (api.enCours.isEmpty()) {
                occupe = false
                erreur = api.erreur.takeIf { it.isNotEmpty() && (api.erreurSur == file.name || api.erreurSur == etat?.groupe?.id) }?.let { e ->
                    when (e) {
                        CorrectionsGalerie.SANS_SON -> t("sstv_corr_err_sans_son")
                        CorrectionsGalerie.RIEN_DANS_LE_SON -> t("sstv_corr_err_rien")
                        else -> tf("sstv_corr_err_autre", e)
                    }
                }
                version++
            }
        }
    }
    fun lance(route: String, methode: String, nouvelleCible: String) {
        val r = api.action(route) { k -> when (k) { "nom" -> file.name; "methode" -> methode; else -> "" } }
        if (r?.optBoolean("ok") == true) { occupe = true; erreur = null; cible = nouvelleCible; vue = "cmp" }
        else erreur = if (r?.optString("erreur") == "occupe") t("sstv_corr_err_occupe") else tf("sstv_corr_err_autre", r?.optString("erreur").orEmpty())
    }

    val e = etat
    val corr = e?.corr; val fus = e?.fus
    // What can be shown now.
    var montre = vue
    if (montre == "corr" && corr == null) montre = if (fus != null) "fus" else "brute"
    if (montre == "fus" && fus == null) montre = if (corr != null) "corr" else "brute"
    if (montre == "cmp" && corr == null && fus == null) montre = "brute"
    val c = when { cible == "fus" && fus != null -> "fus"; corr != null -> "corr"; else -> "fus" }
    val fiche = when (if (montre == "cmp") c else montre) { "corr" -> corr; "fus" -> fus; else -> null }
    val traitee = when (if (montre == "cmp") c else montre) { "corr" -> e?.bCorr; "fus" -> e?.bFus; else -> null }

    if (corr != null || fus != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = montre == "brute", onClick = { vue = "brute" }, label = { Text(t("sstv_corr_brute"), fontSize = 12.sp) })
        if (corr != null) FilterChip(selected = montre == "corr", onClick = { vue = "corr"; cible = "corr" }, label = { Text(t("sstv_corr_corrigee"), fontSize = 12.sp) })
        if (fus != null) FilterChip(selected = montre == "fus", onClick = { vue = "fus"; cible = "fus" }, label = { Text(t("sstv_corr_fusion"), fontSize = 12.sp) })
        FilterChip(selected = montre == "cmp", onClick = { vue = "cmp" }, label = { Text(t("sstv_corr_comparer"), fontSize = 12.sp) })
    }
    val forme = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
    if (montre == "cmp" && traitee != null && brute != null) {
        Box(forme) {
            Image(brute.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            Image(traitee.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()
                .drawWithContent { clipRect(left = size.width * curseur) { this@drawWithContent.drawContent() } })
            surImage()
        }
        Slider(curseur, { curseur = it })
        Text(t("sstv_corr_glisser"), color = TextLo, fontSize = 10.sp)
    } else {
        val b = if (montre == "brute") brute else traitee ?: brute
        if (b != null) Box(forme) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            surImage()
        }
    }
    if (fiche != null) {
        Text(t("sstv_corr_traitee"), color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Text(ditCorrection(fiche), color = TextHi, fontSize = 11.sp)
    } else if (corr != null || fus != null) Text(t("sstv_corr_brute_desc"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    if (occupe) Text(t("sstv_corr_en_cours"), color = Amber, fontSize = 11.sp)
    erreur?.let { Text(it, color = Magenta, fontSize = 11.sp) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(modifier = Modifier.weight(1f), enabled = !occupe, onClick = { lance("/api/sstv/corriger", "auto", "corr") }) {
            Text("✨ " + t(if (corr != null) "sstv_corr_corriger_encore" else "sstv_corr_corriger"), color = Cyan, fontSize = 12.sp)
        }
        e?.groupe?.let { g ->
            OutlinedButton(modifier = Modifier.weight(1f), enabled = !occupe, onClick = { lance("/api/sstv/fusionner", "", "fus") }) {
                Text("⧉ " + tf(if (fus != null) "sstv_corr_refusionner" else "sstv_corr_fusionner", g.images.size), color = Cyan, fontSize = 12.sp)
            }
        }
    }
    if (e?.son == true) OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !occupe, onClick = { lance("/api/sstv/corriger", "son", "corr") }) {
        Text("⟳ " + t("sstv_corr_redecoder"), color = Cyan, fontSize = 12.sp)
    }
    Text(t("sstv_corr_desc"), color = TextLo, fontSize = 10.sp)
}
