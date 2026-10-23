/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.data.ActivitesEnLigne
import fr.f4ioz.satcombo.data.ActivitesTelephone
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Activite
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Precision
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Type
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.notify.ActivitesWorker
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * « Activités à venir »: what the online sources announce that SatMe decodes
 * (ISS SSTV, ARISS contacts, SSDV, METEOR LRPT), grouped by day, each with
 * its period (local time and UTC), frequency, mode, sources, and the
 * owner's passes during it. The switches (the whole, each source, each kind
 * of notice) sit on top. « Préparer la campagne SSTV » opens the agenda on a
 * filled entry to check — nothing is saved by itself.
 */
@Composable
fun ActivitesScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val reglages = remember { SettingsStore(ctx) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var liste by remember { mutableStateOf<List<Activite>>(emptyList()) }
    var etats by remember { mutableStateOf<List<ActivitesEnLigne.Etat>>(emptyList()) }
    var passes by remember { mutableStateOf<Map<String, List<SatPass>>>(emptyMap()) }
    var charge by remember { mutableStateOf(false) }

    suspend fun relis() {
        val en = ActivitesTelephone.enLigne(ctx)
        val l = withContext(Dispatchers.IO) { en.liste(reglages.reglagesActivites) }
        liste = l; etats = en.etats()
        val sats = ui.satellites.ifEmpty { withContext(Dispatchers.IO) { ActivitesTelephone.satellites(ctx) } }
        val obs = ui.observer ?: ActivitesTelephone.observateur(ctx)
        passes = withContext(Dispatchers.Default) { ActivitesTelephone.passages(l, sats, obs, reglages.minElevDeg.toDouble()) }
    }

    fun rafraichit(force: Boolean) {
        scope.launch {
            charge = true
            relis()
            if (reglages.activitesOn) {
                val sats = ui.satellites.map { it.name to it.catalogNumber }
                withContext(Dispatchers.IO) { runCatching { ActivitesTelephone.enLigne(ctx).rafraichit(reglages.reglagesActivites, sats, force) } }
                relis()
                // The notices from the new list (pass reminders laid down again).
                withContext(Dispatchers.IO) { runCatching { ActivitesWorker.verifie(ctx) } }
            }
            charge = false
        }
    }

    LaunchedEffect(version) { rafraichit(false) }

    val now = System.currentTimeMillis()
    val vus = liste.filter { it.type == Type.LRPT }
    val annonces = liste.filter { it.type != Type.LRPT }
    val jourFmt = remember { SimpleDateFormat("EEEE d MMMM", fr.f4ioz.satcombo.i18n.I18n.locale()) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Reglages(reglages, etats, charge, onChange = { version++ }, onActualise = { rafraichit(true) })
        }
        if (!reglages.activitesOn) {
            item { Text(t("act_coupe"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(4.dp)) }
        } else {
            if (vus.isNotEmpty()) {
                item { Entete(t("act_vu_recemment")) }
                items(vus, key = { it.id }) { a -> Carte(a, passes[a.id].orEmpty(), ui.useUtc, now, vm) }
            }
            var jour = ""
            annonces.forEach { a ->
                val j = jourFmt.format(Date(maxOf(a.debutMs, minOf(now, a.finMs)))).replaceFirstChar { it.uppercase() }
                if (j != jour) { jour = j; item(key = "j:$j") { Entete(j) } }
                item(key = a.id) { Carte(a, passes[a.id].orEmpty(), ui.useUtc, now, vm) }
            }
            if (liste.isEmpty()) item {
                Text(if (etats.any { it.succesMs > 0 }) t("act_vide") else t("act_vide_jamais"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Entete(texte: String) {
    Text(texte.uppercase(), color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp))
}

@Composable
private fun Interrupteur(texte: String, on: Boolean, actif: Boolean = true, petit: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = on, enabled = actif, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(texte, color = if (actif) TextHi else TextLo, fontSize = 13.sp)
            if (petit.isNotBlank()) Text(petit, color = TextLo, fontSize = 10.sp)
        }
        Switch(checked = on, onCheckedChange = null, enabled = actif,
            colors = SwitchDefaults.colors(checkedThumbColor = Cyan, checkedTrackColor = Cyan.copy(alpha = 0.4f)))
    }
}

private fun quand(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ms))

/** A source's state, said quietly: when it was last read, or why not. */
private fun etatSource(l: List<ActivitesEnLigne.Etat>, on: Boolean): String {
    if (!on) return ""
    val e = l.filter { it.tentativeMs > 0 }
    if (e.isEmpty()) return t("act_pas_lu")
    val succes = e.maxOf { it.succesMs }
    val err = e.firstOrNull { it.erreur.isNotBlank() }?.erreur?.take(40)
    return when {
        err != null && succes > 0 -> tf("act_erreur", err, quand(succes))
        err != null -> tf("act_erreur_jamais", err)
        else -> tf("act_lu", quand(succes))
    }
}

@Composable
private fun Reglages(s: SettingsStore, etats: List<ActivitesEnLigne.Etat>, charge: Boolean, onChange: () -> Unit, onActualise: () -> Unit) {
    var maj by remember { mutableIntStateOf(0) }
    fun change(f: () -> Unit) { f(); maj++; onChange() }
    key(maj) {
        Surface(color = SpaceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("act_avenir_intro"), color = TextLo, fontSize = 11.sp)
                Interrupteur(t("act_switch"), s.activitesOn) { v -> change { s.activitesOn = v } }
                val on = s.activitesOn
                val de = { ids: List<ActivitesEnLigne.Src> -> etats.filter { it.src in ids } }
                Text(t("act_sources"), color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Interrupteur(t("act_src_ariss"), s.activitesAriss, on, etatSource(de(listOf(ActivitesEnLigne.Src.ARISS)), on && s.activitesAriss)) { v -> change { s.activitesAriss = v } }
                Interrupteur(t("act_src_rss"), s.activitesRss, on, etatSource(de(listOf(ActivitesEnLigne.Src.AMSAT_UK, ActivitesEnLigne.Src.AMSAT_F)), on && s.activitesRss)) { v -> change { s.activitesRss = v } }
                Interrupteur(t("act_src_satnogs"), s.activitesSatnogs, on, etatSource(de(listOf(ActivitesEnLigne.Src.SATNOGS_M23, ActivitesEnLigne.Src.SATNOGS_M24)), on && s.activitesSatnogs)) { v -> change { s.activitesSatnogs = v } }
                Interrupteur(t("act_src_amsat"), s.activitesAmsat, on, etatSource(de(listOf(ActivitesEnLigne.Src.AMSAT)), on && s.activitesAmsat).takeIf { it != t("act_pas_lu") } ?: "") { v -> change { s.activitesAmsat = v } }
                Text(t("act_prevenir"), color = TextLo, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Interrupteur(t("act_n_nouvelle"), s.activitesNotifNouvelle, on) { v -> change { s.activitesNotifNouvelle = v } }
                Interrupteur(t("act_n_passage"), s.activitesNotifPassage, on, tf("act_n_passage_min", s.activitesAvantMin)) { v -> change { s.activitesNotifPassage = v } }
                if (on && s.activitesNotifPassage) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 30).forEach { m ->
                        FilterChip(selected = s.activitesAvantMin == m, onClick = { change { s.activitesAvantMin = m } }, label = { Text("$m min", fontSize = 11.sp) })
                    }
                }
                Interrupteur(t("act_n_emet"), s.activitesNotifEmet, on) { v -> change { s.activitesNotifEmet = v } }
                Text(tf("act_elmin", s.minElevDeg), color = TextLo, fontSize = 10.sp)
                if (on) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onActualise, enabled = !charge) { Text(t("act_actualiser"), fontSize = 12.sp, color = Cyan) }
                    if (charge) { Spacer(Modifier.width(10.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Cyan) }
                }
            }
        }
    }
}

private fun couleur(type: Type): Color = when (type) {
    Type.SSTV -> Cyan; Type.CONTACT -> Color(0xFF8FB0FF); Type.SSDV -> Amber; Type.LRPT -> Aurora
}

@Composable
private fun Puce(texte: String, c: Color) {
    Surface(color = c.copy(alpha = 0.16f), shape = RoundedCornerShape(5.dp)) {
        Text(texte, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** A period in the owner's time and in UTC. */
private fun periode(a: Activite, useUtc: Boolean): String {
    val loc = TimeZone.getDefault()
    val utc = TimeZone.getTimeZone("UTC")
    fun f(p: String, z: TimeZone, ms: Long) = SimpleDateFormat(p, Locale.getDefault()).apply { timeZone = z }.format(Date(ms))
    val z = if (useUtc) utc else loc
    return when {
        a.type == Type.LRPT -> tf("act_vu_le", f("dd/MM HH:mm", z, a.derniereEcouteMs) + if (useUtc) " UTC" else " (" + f("HH:mm", utc, a.derniereEcouteMs) + " UTC)")
        a.precision == Precision.INCONNUE -> tf("act_annonce_le", f("dd/MM", z, a.debutMs))
        a.precision == Precision.JOURS -> tf("act_jours", f("dd/MM", z, a.debutMs), f("dd/MM", z, a.finMs))
        else -> {
            val meme = f("yyyyDDD", z, a.debutMs) == f("yyyyDDD", z, a.finMs)
            val l = f("dd/MM HH:mm", z, a.debutMs) + " → " + f(if (meme) "HH:mm" else "dd/MM HH:mm", z, a.finMs)
            if (useUtc) "$l UTC" else l + "  (" + f("HH:mm", utc, a.debutMs) + " → " + f("HH:mm", utc, a.finMs) + " UTC)"
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Carte(a: Activite, passes: List<SatPass>, useUtc: Boolean, now: Long, vm: MainViewModel) {
    val ctx = LocalContext.current
    val enCours = a.type != Type.LRPT && a.enCours(now)
    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        border = if (enCours) androidx.compose.foundation.BorderStroke(1.dp, Amber) else null) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.satNom.ifBlank { "?" }, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Puce(t("act_type_" + a.type.name.lowercase()), couleur(a.type))
                Puce(t("act_conf_" + a.confiance.name.lowercase()) + if (a.ecoutes > 0 && a.type != Type.LRPT) " · " + a.ecoutes else "",
                    if (a.confiance == ActivitesAVenir.Confiance.CONFIRME) Aurora else TextLo)
                if (enCours) Puce(t("act_en_cours"), Amber)
            }
            Text(a.titre, color = TextHi, fontSize = 13.sp)
            Text(periode(a, useUtc), color = Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text((if (a.freqHz > 0) "↓ " + "%.3f MHz".format(Locale.US, a.freqHz / 1e6) + (if (a.modulation.isNotBlank() && a.modulation != "LRPT") " " + a.modulation else "") else t("act_freq_inconnue")) +
                (if (a.mode.isNotBlank() && a.mode != "LRPT") " · " + a.mode else "") + (if (a.horaire.isNotBlank()) " · " + a.horaire else ""),
                color = if (a.freqHz > 0) Amber else TextLo, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (a.details.isNotBlank()) Text(a.details, color = TextLo, fontSize = 11.sp)
            if (passes.isNotEmpty()) {
                val f = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).apply { if (useUtc) timeZone = TimeZone.getTimeZone("UTC") }
                Text((if (a.type == Type.LRPT) t("act_passages_24h") else t("act_passages")) + " " +
                    passes.take(8).joinToString("  ·  ") { f.format(Date(it.aosEpochMs)) + " " + it.maxElevationDeg.toInt() + "°" },
                    color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            } else if (a.catnum > 0) Text(if (a.type == Type.CONTACT) t("act_aucun_passage_contact") else t("act_aucun_passage"), color = TextLo, fontSize = 11.sp)
            // The sources, each a link to its page.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tf("act_sources_l", ""), color = TextLo, fontSize = 11.sp)
                (a.sources.map { it.nom to it.lien } + a.liens.filter { l -> a.sources.none { it.lien == l } }.take(1).map { t("act_lien") to it }).forEach { (n, l) ->
                    Text(n, color = if (l.isNotBlank()) Cyan else TextLo, fontSize = 11.sp,
                        modifier = if (l.isNotBlank()) Modifier.clickable {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(l))) }
                        } else Modifier)
                }
            }
            if (a.type == Type.SSTV && a.catnum > 0) {
                Button(onClick = { vm.prepareRendezVous(ActivitesAVenir.rendezVous(a, System.currentTimeMillis())) },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan), modifier = Modifier.padding(top = 4.dp)) {
                    Text(t("act_preparer"), color = if (isDarkTheme()) SpaceBg else Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

/** The way to « Activités à venir » from the SSTV and METEOR pages: one line, tapped. */
@Composable
internal fun LienActivites(vm: MainViewModel) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { vm.openActivites() }) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(t("act_ouvrir"), color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(t("act_ouvrir_aide"), color = TextLo, fontSize = 11.sp)
        }
    }
}
