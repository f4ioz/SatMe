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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.ClassementGalerie
import fr.f4ioz.satcombo.sstv.ClassementSstv
import fr.f4ioz.satcombo.sstv.SerieSstv
import fr.f4ioz.satcombo.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The SSTV gallery by slide (« Images » tab): each group's heading, the
 * dialog that numbers a picture or moves it, and the series' card — the
 * slides received, the timetable, the slides expected on the passes to come.
 */

/** Stars for a mark (0–100). */
internal fun etoilesSstv(note: Int): String {
    if (note < 0) return ""
    val n = ((note + 10) / 20).coerceIn(0, 5)
    return "★".repeat(n) + "☆".repeat(5 - n)
}

/** « Diapo 7/12 », « Diapo ≈7/12 (estimée) », « Diapo ? ». */
internal fun nomDiapoSstv(g: ClassementSstv.Groupe): String = when {
    g.diapo <= 0 -> t("sstv_cl_diapo_inconnue")
    g.origine == ClassementSstv.Origine.ESTIME -> tf("sstv_cl_diapo_estimee", g.diapo, g.total)
    g.origine == ClassementSstv.Origine.LU -> tf("sstv_cl_diapo_lue", g.diapo, g.total)
    else -> tf("sstv_cl_diapo", g.diapo, g.total)
}

/**
 * The groups as shown: the series' first (with the numbers the timetable
 * estimates), by slide, then the others, newest first.
 */
internal fun groupesAffiches(e: ClassementGalerie.Etat, p: SerieSstv.Prevision?, sat: String): List<Pair<ClassementSstv.Groupe, Boolean>> {
    val serie = p?.groupes?.associateBy { it.id }.orEmpty()
    val l = e.groupes.filter { sat.isBlank() || it.meilleure.satName == sat }.map { g ->
        val s = serie[g.id]
        (if (s != null && g.origine == ClassementSstv.Origine.AUCUNE && s.origine == ClassementSstv.Origine.ESTIME)
            g.copy(diapo = s.diapo, total = s.total, origine = s.origine) else g) to (s != null)
    }
    val der = { g: ClassementSstv.Groupe -> g.images.maxOf { it.timeMs } }
    return l.filter { it.second }.sortedWith(compareBy<Pair<ClassementSstv.Groupe, Boolean>> { if (it.first.diapo > 0) it.first.diapo else 99 }.thenByDescending { der(it.first) }) +
        l.filter { !it.second }.sortedByDescending { der(it.first) }
}

@Composable
internal fun EnteteGroupeSstv(g: ClassementSstv.Groupe, ouvert: Boolean, onBascule: () -> Unit) {
    val best = g.notes[g.meilleure.fileName] ?: -1
    Row(Modifier.fillMaxWidth().clickable(enabled = g.images.size > 1) { onBascule() }.padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(nomDiapoSstv(g), color = if (g.diapo > 0) TextHi else TextLo, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(listOf(g.meilleure.satName, tf("sstv_cl_receptions", g.images.size),
                if (best >= 0) tf("sstv_cl_meilleure", etoilesSstv(best), best) else "").filter { it.isNotBlank() }.joinToString(" · "),
                color = TextLo, fontSize = 11.sp)
        }
        if (g.images.size > 1) Text(if (ouvert) t("sstv_cl_replier") else tf("sstv_cl_voir", g.images.size), color = Cyan, fontSize = 12.sp)
    }
}

/** Number a picture's slide (its whole group), or put it with another group, on its own, or back to the automatic sorting. */
@Composable
internal fun DialogueClasseSstv(nom: String, e: ClassementGalerie.Etat, p: SerieSstv.Prevision?, useUtc: Boolean,
                                onDiapo: (Int) -> Unit, onGroupe: (String) -> Unit, onFerme: () -> Unit) {
    val g = e.groupes.firstOrNull { gr -> gr.images.any { it.fileName == nom } } ?: run { onFerme(); return }
    val s = g.images.first { it.fileName == nom }
    val a = e.analyses[nom]
    val note = g.notes[nom] ?: a?.note ?: -1
    val fmt = remember(useUtc) { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).apply { if (useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC") } }
    val nb = p?.serie?.nb ?: 12
    AlertDialog(onDismissRequest = onFerme, containerColor = SpaceCard,
        title = { Text(t("sstv_cl_titre"), color = TextHi, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(fmt.format(Date(s.timeMs)) + (if (useUtc) " UTC" else "") + " · " + nomDiapoSstv(g), color = TextHi, fontSize = 13.sp)
                if (a != null) Text(tf("sstv_cl_note_detail", note, Math.round(a.manque * 100), Math.round(a.abimees * 100),
                    if (a.lu > 0) tf("sstv_cl_lue", a.lu) else "") + " " + etoilesSstv(note), color = TextLo, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(t("sstv_cl_numero"), color = TextLo, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..nb).forEach { n ->
                        val choisi = g.origine == ClassementSstv.Origine.MAIN && g.diapo == n || (n == 0 && g.origine != ClassementSstv.Origine.MAIN)
                        FilterChip(selected = choisi, onClick = { onDiapo(n) },
                            label = { Text(if (n == 0) "—" else n.toString(), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan, labelColor = TextLo))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(t("sstv_cl_groupe"), color = TextLo, fontSize = 12.sp)
                TextButton(onClick = { onGroupe("") }) { Text(t("sstv_cl_auto"), color = Cyan, fontSize = 13.sp) }
                TextButton(onClick = { onGroupe(ClassementGalerie.SEULE) }) { Text(t("sstv_cl_seule"), color = Cyan, fontSize = 13.sp) }
                for ((o, _) in groupesAffiches(e, p, s.satName)) {
                    if (o.id == g.id) continue
                    TextButton(onClick = { onGroupe(o.id) }) {
                        Text(tf("sstv_cl_avec", nomDiapoSstv(o) + " · " + o.images.size + " · " + fmt.format(Date(o.meilleure.timeMs))), color = Cyan, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onFerme) { Text(t("close"), color = Cyan) } })
}

/** The series under way: slides received, the timetable, the passes to come, a picture to number. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CarteSerieSstv(p: SerieSstv.Prevision, useUtc: Boolean, onVoir: (String) -> Unit, onNumerote: (String, Int) -> Unit) {
    val fmt = remember(useUtc) { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).apply { if (useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC") } }
    val hms = remember(useUtc) { SimpleDateFormat("HH:mm", Locale.getDefault()).apply { if (useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC") } }
    val z = if (useUtc) " UTC" else ""
    val s = p.serie
    val now = System.currentTimeMillis()
    var numeroter by remember { mutableStateOf(false) }
    Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(t("sstv_cl_serie"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(s.nom + " · " + fmt.format(Date(s.debutMs)) + " → " + fmt.format(Date(s.finMs)) + z + " · " +
                t(if (s.enCours(now)) "sstv_cl_en_cours" else "sstv_cl_terminee"), color = TextLo, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            // 1..12: received (the surest group of each), missing.
            val parDiapo = p.groupes.filter { it.diapo > 0 }.groupBy { it.diapo }.mapValues { (_, l) ->
                l.minWith(compareBy<ClassementSstv.Groupe> { it.origine.ordinal }.thenByDescending { it.images.size }) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..s.nb).forEach { n ->
                    val g = parDiapo[n]
                    Box(Modifier.background(if (g != null) Aurora.copy(alpha = 0.18f) else Magenta.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .clickable(enabled = g != null) { g?.let { onVoir(it.meilleure.fileName) } }.padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text((if (g?.origine == ClassementSstv.Origine.ESTIME) "≈" else "") + n, color = if (g != null) Aurora else Magenta,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val m = p.modele
            val f = { v: Double, d: Int -> String.format(Locale.getDefault(), "%." + d + "f", v) }
            Text(if (m == null) t("sstv_cl_sans_repere") else buildString {
                append(if (m.ajustee) tf("sstv_cl_horaire", f(m.periodeS, 2), f(m.sigmaPeriodeS, 2), f(s.periodeS, 0), m.reperes, f(m.residuS, 1))
                    else tf("sstv_cl_horaire_annonce", f(m.periodeS, 0)))
                if (m.ruptures.isNotEmpty()) append(" ").append(tf("sstv_cl_ruptures", m.ruptures.size))
                if (s.enCours(now)) append(" ").append(tf("sstv_cl_maintenant",
                    fr.f4ioz.satcombo.sstv.HoraireDiapos.diapo(m, fr.f4ioz.satcombo.sstv.HoraireDiapos.creneauA(m, now))))
            }, color = TextLo, fontSize = 12.sp)
            TextButton(onClick = { numeroter = true }) { Text(t("sstv_cl_numeroter"), color = Cyan, fontSize = 13.sp) }
            val tab = p.tableau
            if (m != null) {
                val manquent = tab?.manquantes ?: (1..s.nb).filter { it !in p.recues }
                Text(if (manquent.isEmpty()) t("sstv_cl_toutes") else tf("sstv_cl_manquent", manquent.joinToString(", ")),
                    color = if (manquent.isEmpty()) Aurora else Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                for (n in manquent) {
                    val l = tab?.prochaines?.get(n).orEmpty()
                    Text(if (l.isEmpty()) tf("sstv_cl_aucun_passage", n) else tf("sstv_cl_prochains", n, l.take(3).joinToString(", ") { ps ->
                        val d = ps.prevues.first { it.diapo == n && it.entiere }
                        fmt.format(Date(d.debutMs)) + z + " (" + Math.round(ps.passage.maxElevationDeg) + "°)" }), color = TextLo, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(t("sstv_cl_passages"), color = TextHi, fontSize = 13.sp)
                if (tab == null || tab.passages.isEmpty()) Text(t("sstv_cl_plus_de_passage"), color = TextLo, fontSize = 12.sp)
                else for (ps in tab.passages) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt.format(Date(ps.passage.aosEpochMs)) + "–" + hms.format(Date(ps.passage.losEpochMs)) + " " + Math.round(ps.passage.maxElevationDeg) + "°",
                        color = TextLo, fontSize = 11.sp, modifier = Modifier.width(150.dp))
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (d in ps.prevues) {
                            val manque = d.diapo !in p.recues
                            Text(if (d.entiere) d.diapo.toString() else "(" + d.diapo + ")",
                                color = if (!d.entiere) TextLo else if (manque) Magenta else TextHi,
                                fontWeight = if (manque && d.entiere) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp,
                                modifier = Modifier.background(if (manque && d.entiere) Magenta.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 3.dp))
                        }
                    }
                    Text("±" + Math.round(ps.incertitudeS) + " s", color = TextLo, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(tf("sstv_cl_estimation", Math.round(p.elMin)), color = TextLo, fontSize = 10.sp)
        }
    }
    if (numeroter) {
        var choix by remember { mutableStateOf(p.images.lastOrNull()?.first?.fileName.orEmpty()) }
        var n by remember { mutableIntStateOf(0) }
        AlertDialog(onDismissRequest = { numeroter = false }, containerColor = SpaceCard,
            title = { Text(t("sstv_cl_numeroter"), color = TextHi, fontSize = 16.sp) },
            text = {
                Column {
                    Text(t("sstv_cl_choisir"), color = TextLo, fontSize = 12.sp)
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        val parPassage = p.images.groupBy { it.second }
                        for ((im, aos) in p.images.reversed()) {
                            val l = parPassage[aos].orEmpty()
                            val g = p.groupes.firstOrNull { gr -> gr.images.any { it.fileName == im.fileName } }
                            val ligne = fmt.format(Date(im.timeMs)) + z + (if (aos > 0) " · " + tf("sstv_cl_passage", hms.format(Date(aos)),
                                l.indexOfFirst { it.first.fileName == im.fileName } + 1, l.size) else "") +
                                (g?.let { if (it.diapo > 0) " · " + (if (it.origine == ClassementSstv.Origine.ESTIME) "≈" else "") + it.diapo + "/" + s.nb else " · ?" } ?: "")
                            Text(ligne, color = if (choix == im.fileName) Cyan else TextHi, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().clickable { choix = im.fileName }
                                    .background(if (choix == im.fileName) Cyan.copy(alpha = 0.15f) else Color.Transparent).padding(vertical = 4.dp, horizontal = 4.dp))
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..s.nb).forEach { k ->
                            FilterChip(selected = n == k, onClick = { n = k }, label = { Text(k.toString(), fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan, labelColor = TextLo))
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = n > 0 && choix.isNotEmpty(), onClick = { numeroter = false; onNumerote(choix, n) }) { Text(t("sstv_cl_valider"), color = Cyan) } },
            dismissButton = { TextButton(onClick = { numeroter = false }) { Text(t("cancel"), color = Cyan) } })
    }
}
