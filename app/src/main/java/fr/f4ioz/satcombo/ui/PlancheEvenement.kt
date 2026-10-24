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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.sstv.ClassementSstv
import fr.f4ioz.satcombo.sstv.EvenementSstv
import fr.f4ioz.satcombo.ui.theme.*
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * The sheet's event on the phone's screen: what each picture is (its mark,
 * its slide and where the number comes from, its group), the event chosen,
 * the report asked, the sheet renamed — the same as the page
 * ([fr.f4ioz.satcombo.demo.ApiPlancheEvenement]).
 */

/** A picture as the sheet's picker shows it. */
internal data class InfoImage(
    val note: Int, val groupe: String, val diapo: Int, val origine: ClassementSstv.Origine, val badge: String,
    val meilleure: Boolean, val copies: Int, val dansEvenement: Boolean,
)

/** [fr.f4ioz.satcombo.demo.ApiPlancheEvenement.galerie]'s answer, by picture name. */
internal fun infosImages(a: JSONArray): Map<String, InfoImage> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associate { o ->
    o.optString("f") to InfoImage(o.optInt("n", -1), o.optString("g"), o.optInt("di"),
        when (o.optString("o")) { "main" -> ClassementSstv.Origine.MAIN; "lu" -> ClassementSstv.Origine.LU; "estime" -> ClassementSstv.Origine.ESTIME; else -> ClassementSstv.Origine.AUCUNE },
        o.optString("b"), o.optBoolean("best", true), o.optInt("copies", 1), o.optBoolean("ev"))
}

/** Where a slide's number comes from, said in words. */
internal fun origineSstv(o: ClassementSstv.Origine): String = when (o) {
    ClassementSstv.Origine.LU -> t("sstv_cl_origine_lu"); ClassementSstv.Origine.MAIN -> t("sstv_cl_origine_main")
    ClassementSstv.Origine.ESTIME -> t("sstv_cl_origine_estime"); ClassementSstv.Origine.AUCUNE -> ""
}

/** The slide's badge: « 2/12 » read (white), « 2/12✎ » by hand (amber), « ≈2/12 » estimated (grey, slanted). */
@Composable
internal fun BadgeDiapo(texte: String, origine: ClassementSstv.Origine, modifier: Modifier = Modifier, grand: Boolean = false) {
    if (texte.isBlank()) return
    Text(texte, color = when (origine) { ClassementSstv.Origine.MAIN -> Amber; ClassementSstv.Origine.ESTIME -> Color(0xFFCFD8E0); else -> Color.White },
        fontStyle = if (origine == ClassementSstv.Origine.ESTIME) FontStyle.Italic else FontStyle.Normal,
        fontSize = if (grand) 16.sp else 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(3.dp).background(if (origine == ClassementSstv.Origine.ESTIME) Color(0xD03C4650) else Color(0xC0000000), RoundedCornerShape(5.dp))
            .padding(horizontal = if (grand) 8.dp else 5.dp, vertical = if (grand) 2.dp else 0.dp))
}

/** A mark (0–100) on a thumbnail. */
@Composable
internal fun NoteImage(note: Int, modifier: Modifier = Modifier) {
    if (note < 0) return
    Text(note.toString(), color = if (note < 40) Magenta else if (note < 70) Amber else Color(0xFFFFD54F), fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(3.dp).background(Color(0xB8000000), RoundedCornerShape(5.dp)).padding(horizontal = 4.dp))
}

private val jourHeure = SimpleDateFormat("dd/MM HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** An event named with its kind. */
internal fun nomEvenement(e: EvenementSstv.Evenement?): String = e?.let {
    it.nom + " · " + t(when (it.genre) { EvenementSstv.Genre.CAMPAGNE -> "pw_ev_campagne"; EvenementSstv.Genre.ACTIVITE -> "pw_ev_activite"; EvenementSstv.Genre.IMAGES -> "pw_ev_images" })
} ?: t("planche_ev_aucun")

/** The sheet's event, and the button that changes it: chosen from the pictures again (""), none (« aucun »), or one of [liste]. */
@Composable
internal fun LigneEvenement(e: EvenementSstv.Evenement?, main: Boolean, liste: List<EvenementSstv.Evenement>, onChoix: (String) -> Unit) {
    var ouvert by remember { mutableStateOf(false) }
    Surface(color = SpaceCard, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("planche_evenement") + " — " + t(if (main) "planche_ev_main" else "planche_ev_auto"), color = TextLo, fontSize = 11.sp)
                Text(nomEvenement(e), color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (e != null) Text(jourHeure.format(Date(e.debutMs)) + " → " + jourHeure.format(Date(e.finMs)) + " UTC · " + e.satNom, color = TextLo, fontSize = 10.sp)
            }
            TextButton(onClick = { ouvert = true }) { Text(t("planche_ev_choisir"), color = Cyan, fontSize = 12.sp) }
        }
    }
    if (ouvert) AlertDialog(onDismissRequest = { ouvert = false }, containerColor = SpaceSurface,
        title = { Text(t("planche_ev_choisir"), color = TextHi, fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                @Composable fun ligne(texte: String, sous: String, choisi: Boolean, v: String) =
                    Column(Modifier.fillMaxWidth().clickable { ouvert = false; onChoix(v) }
                        .background(if (choisi) Cyan.copy(alpha = 0.15f) else Color.Transparent).padding(vertical = 6.dp, horizontal = 4.dp)) {
                        Text(texte, color = if (choisi) Cyan else TextHi, fontSize = 13.sp)
                        if (sous.isNotBlank()) Text(sous, color = TextLo, fontSize = 10.sp)
                    }
                ligne(t("planche_ev_auto_revenir"), "", !main, "")
                for (x in liste) ligne(nomEvenement(x), jourHeure.format(Date(x.debutMs)) + " → " + jourHeure.format(Date(x.finMs)) + " UTC", main && e?.id == x.id, x.id)
                ligne(t("planche_ev_aucun"), "", main && e == null, "aucun")
            }
        },
        confirmButton = { TextButton(onClick = { ouvert = false }) { Text(t("close"), color = Cyan) } })
}

/** The sheet's new name. */
@Composable
internal fun DialogueRenommer(nom: String, onNom: (String) -> Unit, onFerme: () -> Unit) {
    var v by remember { mutableStateOf(nom) }
    AlertDialog(onDismissRequest = onFerme, containerColor = SpaceSurface,
        title = { Text(t("planche_renommer_titre"), color = TextHi, fontSize = 16.sp) },
        text = { OutlinedTextField(v, { v = it.take(60) }, singleLine = true) },
        confirmButton = { TextButton(enabled = v.isNotBlank(), onClick = { onNom(v.trim()); onFerme() }) { Text(t("planche_renommer"), color = Cyan) } },
        dismissButton = { TextButton(onClick = onFerme) { Text(t("cancel"), color = TextLo) } })
}

/** The event's report: a web page or a PDF, with the receive chain or not, shared or saved. */
@Composable
internal fun DialogueRapport(e: EvenementSstv.Evenement?, onRapport: (chaine: Boolean, partager: Boolean, pdf: Boolean) -> Unit, onFerme: () -> Unit) {
    var chaine by remember { mutableStateOf(false) }
    var pdf by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onFerme, containerColor = SpaceSurface,
        title = { Text(t("planche_rapport"), color = TextHi, fontSize = 16.sp) },
        text = {
            Column {
                Text(t("planche_rapport_aide"), color = TextLo, fontSize = 12.sp)
                Text(if (e != null) nomEvenement(e) else t("planche_rapport_sans_ev"), color = if (e != null) TextHi else Amber,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { chaine = !chaine }) {
                    Checkbox(checked = chaine, onCheckedChange = { chaine = it })
                    Text(t("planche_rapport_chaine"), color = TextHi, fontSize = 13.sp)
                }
                for ((v, cle) in listOf(false to "planche_rapport_web", true to "planche_rapport_pdf"))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { pdf = v }) {
                        RadioButton(selected = pdf == v, onClick = { pdf = v })
                        Text(t(cle), color = TextHi, fontSize = 13.sp)
                    }
            }
        },
        confirmButton = { TextButton(enabled = e != null, onClick = { onFerme(); onRapport(chaine, true, pdf) }) { Text(t("planche_rapport_partager"), color = Cyan) } },
        dismissButton = {
            Row {
                TextButton(onClick = onFerme) { Text(t("cancel"), color = TextLo) }
                TextButton(enabled = e != null, onClick = { onFerme(); onRapport(chaine, false, pdf) }) { Text(t("planche_rapport_enregistrer"), color = Cyan) }
            }
        })
}
