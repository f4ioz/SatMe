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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import fr.f4ioz.satcombo.aprs.AprsHub
import fr.f4ioz.satcombo.aprs.Paquet
import fr.f4ioz.satcombo.aprs.TypeAprs
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * APRS, laid out like the APRS apps people know: a status strip on top (the
 * radio, where it works, the one button that matters), then tabs — the map
 * and stations, messages, sending, the raw packets, trophies, and the
 * settings, kept apart from what is sent.
 */
@Composable
fun AprsScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val st by AprsHub.etat.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AprsHub.charge(ctx) } }
    var mode by remember { mutableStateOf(vm.aprs.mode()) }
    var travail by remember { mutableStateOf(vm.aprs.travail()) }
    var onglet by rememberSaveable { mutableStateOf("CARTE") }
    // In KISS mode the operator vouches for an unknown frequency; shared by every tab that transmits.
    var frequenceOk by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BanniereFete()
        BandeauAprs(ui, vm, mode, travail, st) { onglet = "REGLAGES" }
        // Six tabs that all fit: an icon and a short name each, no sideways scrolling.
        androidx.compose.material3.TabRow(selectedTabIndex = ONGLETS.indexOf(onglet).coerceAtLeast(0),
            containerColor = SpaceBg, contentColor = Cyan) {
            ONGLETS.forEach { o ->
                // Content drawn here, without the tab's side padding that cut the names short.
                androidx.compose.material3.Tab(selected = onglet == o, onClick = { onglet = o },
                    selectedContentColor = Cyan, unselectedContentColor = TextLo) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 6.dp)) {
                        androidx.compose.material3.Icon(ICONES.getValue(o), contentDescription = null,
                            modifier = Modifier.size(22.dp))
                        Text(t("aprs_onglet_" + o.lowercase()), fontSize = 10.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        when (onglet) {
            "CARTE" -> OngletCarte(ui, vm, st.paquets)
            "MESSAGES" -> OngletMessages(ui, vm, st.paquets, mode, frequenceOk) { frequenceOk = it }
            "EMETTRE" -> OngletEmettre(ui, vm, mode, frequenceOk) { frequenceOk = it }
            "PAQUETS" -> OngletPaquets(ui, st)
            "TROPHEES" -> OngletTrophees(ui, vm, st.paquets)
            else -> OngletReglages(ui, vm, mode, { mode = it; vm.aprs.setMode(it) }, travail,
                { travail = it; vm.aprs.setTravail(it) }, st)
        }
    }
}

private val ONGLETS = listOf("CARTE", "MESSAGES", "EMETTRE", "PAQUETS", "TROPHEES", "REGLAGES")

private val ICONES = mapOf(
    "CARTE" to androidx.compose.material.icons.Icons.Default.Map,
    "MESSAGES" to androidx.compose.material.icons.Icons.Default.Forum,
    "EMETTRE" to androidx.compose.material.icons.Icons.AutoMirrored.Filled.Send,
    "PAQUETS" to androidx.compose.material.icons.Icons.AutoMirrored.Filled.ViewList,
    "TROPHEES" to androidx.compose.material.icons.Icons.Default.EmojiEvents,
    "REGLAGES" to androidx.compose.material.icons.Icons.Default.Settings,
)

/** "Auto", "ISS · 145,825", "Terrestre · 144,800"… */
private fun nomTravail(travail: String): String = when (travail) {
    "ISS" -> t("aprs_travail_iss"); "TERRE" -> t("aprs_travail_terre"); "POSTE" -> t("aprs_kiss_freq_poste")
    else -> t("aprs_travail_auto")
}

/**
 * Always on top: which radio, where it works, how it is doing, and the
 * button that starts it — listen (phone + IC-9700) or connect (KISS, FT3D).
 */
@Composable
private fun BandeauAprs(ui: UiState, vm: MainViewModel, mode: String, travail: String,
                        st: AprsHub.Etat, versReglages: () -> Unit) {
    val ctx = LocalContext.current
    val kiss by fr.f4ioz.satcombo.aprs.TncKiss.etat.collectAsState()
    val ft3d by fr.f4ioz.satcombo.aprs.RecepteurWaypoints.etat.collectAsState()
    val retour by vm.aprs.envoi.collectAsState()
    Surface(color = SpaceCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (mode) { "KISS" -> t("aprs_mode_kiss"); "FT3D" -> t("aprs_mode_ft3d"); else -> t("aprs_mode_audio") },
                            color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Surface(color = Amber.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                            Text(t("aprs_beta"), color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                    val etat = when (mode) {
                        "KISS" -> if (kiss.connecte) tf("aprs_bandeau_kiss", kiss.nom.substringBefore(" ·"),
                                kiss.frequenceHz?.let { "%.3f".format(Locale.US, it / 1e6) } ?: "—", kiss.recues) +
                                (if (!kiss.initialise) " · " + t("aprs_bandeau_pas_kiss") else "")
                            else t("aprs_bandeau_deconnecte")
                        "FT3D" -> if (ft3d.connecte) tf("aprs_bandeau_ft3d", ft3d.recues) else t("aprs_bandeau_deconnecte")
                        else -> if (st.ecoute) tf("aprs_ecoute", st.sat, st.nouveaux) else t("aprs_bandeau_arrete")
                    }
                    val vivant = (mode == "KISS" && kiss.connecte && kiss.initialise) || (mode == "FT3D" && ft3d.connecte) ||
                        (mode == "AUDIO" && st.ecoute)
                    Text(nomTravail(travail) + " · " + etat, color = if (vivant) Aurora else TextLo, fontSize = 12.sp)
                }
                when (mode) {
                    "KISS" -> if (kiss.connecte) TextButton(onClick = { vm.aprs.kissDeconnecte() }) {
                        Text(t("aprs_kiss_deconnecter"), color = Magenta, fontSize = 12.sp)
                    } else Button(onClick = {
                        val app = fr.f4ioz.satcombo.aprs.TncKiss.appareils(ctx)
                        val cle = vm.aprs.kissCle().takeIf { c -> app.any { it.cle == c } } ?: app.singleOrNull()?.cle
                        if (cle != null) vm.aprs.kissConnecte(cle, vm.aprs.kissVitesse()) else versReglages()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Text(t("aprs_kiss_connecter"), color = Color(0xFF00201D), fontWeight = FontWeight.Bold)
                    }
                    "FT3D" -> if (ft3d.connecte) TextButton(onClick = { vm.aprs.ft3dDeconnecte() }) {
                        Text(t("aprs_kiss_deconnecter"), color = Magenta, fontSize = 12.sp)
                    } else Button(onClick = {
                        val app = fr.f4ioz.satcombo.aprs.TncKiss.appareils(ctx)
                        val cle = vm.aprs.ft3dCle().takeIf { c -> app.any { it.cle == c } } ?: app.singleOrNull()?.cle
                        if (cle != null) vm.aprs.ft3dConnecte(cle, vm.aprs.ft3dVitesse()) else versReglages()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Text(t("aprs_kiss_connecter"), color = Color(0xFF00201D), fontWeight = FontWeight.Bold)
                    }
                    else -> if (ui.recording) OutlinedButton(onClick = { vm.aprsEcouteArrete() }) {
                        Text("■ " + t("aprs_iss_arreter"), color = Magenta, fontSize = 12.sp)
                    } else Button(onClick = { vm.aprsEcouteDemarre() },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Text("▶ " + t("aprs_ecouter"), color = Color(0xFF00201D), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (retour.isNotBlank()) Text(retour, color = TextLo, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PuceA(choisie: Boolean, nom: String, onClic: () -> Unit) {
    FilterChip(selected = choisie, onClick = onClic, label = { Text(nom, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
}

@Composable
private fun Carte(titre: String, contenu: @Composable ColumnScope.() -> Unit) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(titre, color = TextHi, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            contenu()
        }
    }
}

@Composable
private fun Interrupteur(titre: String, desc: String, valeur: Boolean, rouge: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).toggleable(value = valeur, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titre, color = TextHi, fontSize = 13.sp)
            Text(desc, color = TextLo, fontSize = 11.sp)
        }
        Switch(checked = valeur, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = if (rouge) Color(0xFFE5484D) else Cyan))
    }
}

// ------------------------------------------------------------------ send

/**
 * What to send — position, status, a message to a station or a server —
 * by which path, with the frame shown before it goes. Messages to stations
 * are easier from the Messages tab; this is the full set.
 */
@Composable
private fun OngletEmettre(ui: UiState, vm: MainViewModel, mode: String, frequenceOk: Boolean, onFrequenceOk: (Boolean) -> Unit) {
    val resultat by vm.aprs.envoi.collectAsState()
    val kiss by fr.f4ioz.satcombo.aprs.TncKiss.etat.collectAsState()
    var type by rememberSaveable { mutableStateOf("POS") }
    var texte by rememberSaveable { mutableStateOf("") }
    var destinataire by rememberSaveable { mutableStateOf("") }
    val defaut = remember { vm.aprs.cheminParDefaut() }
    var chemin by rememberSaveable { mutableStateOf(if (defaut == listOf("ARISS")) "ARISS" else "WIDE") }
    val source = remember(ui.callsign) { vm.aprs.source() }
    val pos = remember(ui.observer, vm.aprs.positionFloue()) { vm.aprs.positionEmise() }
    val E = fr.f4ioz.satcombo.aprs.AprsEmission
    fun info(id: String?): String? = when (type) {
        "POS" -> pos?.let { (la, lo) -> E.position(la, lo, "/-", texte) }
        "STATUT" -> E.statut(texte)
        "APRSPH" -> E.message("APRSPH", texte.ifBlank { "HOTG" }.let { if (it.uppercase().startsWith("HOTG")) it else "HOTG $it" }, id)
        else -> if (destinataire.isBlank()) null else E.message(destinataire, texte, id)
    }
    val cheminListe = when (chemin) { "ARISS" -> listOf("ARISS"); "WIDE" -> listOf("WIDE1-1", "WIDE2-1"); else -> emptyList() }
    val avecNumero = type == "APRSPH" || type == "MSG"
    val apercu = info(if (avecNumero) "n" else null)?.let { E.trame(source.ifBlank { "N0CALL" }, cheminListe, it).tnc2() }
    val connue = kiss.frequenceHz?.let { hz -> E.FENETRES.any { hz in it } } == true
    val pret = apercu != null && source.isNotBlank() && when (mode) {
        "KISS" -> kiss.connecte && (frequenceOk || connue)
        "FT3D" -> false
        else -> true
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Carte(t("aprs_emettre_quoi")) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("POS" to t("aprs_tx_type_pos"), "STATUT" to t("aprs_tx_type_statut"),
                        "MSG" to t("aprs_tx_type_msg"), "APRSPH" to "APRSPH").forEach { (c, n) ->
                        PuceA(type == c, n) { type = c; texte = "" }
                    }
                }
                when (type) {
                    "POS" -> Text(if (pos == null) t("aprs_tx_sans_position")
                        else if (vm.aprs.positionFloue()) t("aprs_emettre_pos_floue") else t("aprs_emettre_pos_exacte"),
                        color = if (pos == null) Amber else TextLo, fontSize = 11.sp)
                    "APRSPH" -> Text(t("aprs_tx_aprsph_aide"), color = TextLo, fontSize = 11.sp)
                    "MSG" -> Text(t("aprs_emettre_msg_aide"), color = TextLo, fontSize = 11.sp)
                    else -> {}
                }
                if (type == "MSG") OutlinedTextField(value = destinataire, onValueChange = { destinataire = it.uppercase().take(9) },
                    label = { Text(t("aprs_tx_destinataire"), fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = texte, onValueChange = { texte = it.take(E.LONGUEUR_MESSAGE) },
                    label = { Text(if (type == "POS") t("aprs_tx_commentaire") else t("aprs_tx_texte"), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            Carte(t("aprs_tx_chemin")) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PuceA(chemin == "ARISS", t("aprs_tx_chemin_iss")) { chemin = "ARISS" }
                    PuceA(chemin == "WIDE", "WIDE1-1,WIDE2-1 (" + t("aprs_travail_terre_court") + ")") { chemin = "WIDE" }
                    PuceA(chemin == "AUCUN", t("aprs_tx_chemin_aucun")) { chemin = "AUCUN" }
                }
                if (source.isBlank()) Text(t("aprs_tx_sans_indicatif"), color = Amber, fontSize = 11.sp)
                apercu?.let {
                    Text(t("aprs_tx_apercu"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(it, color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (mode == "KISS" && !connue) ConfirmeFrequenceKiss(frequenceOk, onFrequenceOk)
                if (mode == "FT3D") Text(t("aprs_ft3d_tx"), color = Amber, fontSize = 11.sp)
                Button(enabled = pret, onClick = {
                    val i = info(if (avecNumero) vm.aprs.numeroSuivant() else null) ?: return@Button
                    vm.aprsEmetSelonMode(E.trame(source, cheminListe, i), frequenceOk)
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5484D)),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (mode == "KISS") t("aprs_kiss_emettre") else t("aprs_tx_emettre"), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text(t("aprs_tx_banniere"), color = TextLo, fontSize = 11.sp)
                if (resultat.isNotBlank()) Text(resultat, color = TextHi, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- packets

/** Every frame heard and sent, newest first, with filters; tapped, the raw frame. */
@Composable
private fun OngletPaquets(ui: UiState, st: AprsHub.Etat) {
    var filtre by rememberSaveable { mutableStateOf("TOUT") }
    var ouvert by remember { mutableStateOf<Paquet?>(null) }
    val liste = remember(st.paquets, filtre) {
        when (filtre) {
            "ISS" -> st.paquets.filter { it.viaIss }
            "MSG" -> st.paquets.filter { it.type == TypeAprs.MESSAGE || it.type == TypeAprs.ACCUSE }
            "METEO" -> st.paquets.filter { it.meteo != null }
            "HOTG" -> st.paquets.filter { it.hotg }
            "EMIS" -> st.paquets.filter { it.emis }
            else -> st.paquets
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("TOUT" to t("aprs_filtre_tout"), "ISS" to t("aprs_filtre_iss"), "MSG" to t("aprs_filtre_msg"),
                    "METEO" to t("aprs_filtre_meteo"), "HOTG" to "HOTG", "EMIS" to t("aprs_filtre_emis")).forEach { (c, n) ->
                    PuceA(filtre == c, n) { filtre = c }
                }
            }
        }
        item { Text(tf("aprs_paquets_compte", liste.size), color = TextLo, fontSize = 11.sp) }
        if (liste.isEmpty()) item { Text(t("aprs_vide"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
        items(liste, key = { "${it.quand}-${it.trame.hashCode()}-${it.emis}" }) { p ->
            LignePaquet(p, ui, ouvert == p, fr.f4ioz.satcombo.aprs.AprsJeu.estAccuse(p, st.paquets)) { ouvert = if (ouvert == p) null else p }
        }
    }
}

// --------------------------------------------------------------- settings

/**
 * Everything that is set once, apart from what is sent: the radio and its
 * connection, where APRS works, the station (SSID, approximate position,
 * beacon), the IC-9700 audio and its tests, cheers, the history.
 */
@Composable
private fun OngletReglages(ui: UiState, vm: MainViewModel, mode: String, onMode: (String) -> Unit,
                           travail: String, onTravail: (String) -> Unit, st: AprsHub.Etat) {
    val ctx = LocalContext.current
    val portee = rememberCoroutineScope()
    var choix by remember { mutableStateOf(false) }
    var effacer by remember { mutableStateOf(false) }
    var actif by remember { mutableStateOf(vm.aprsActif()) }
    var ssid by remember { mutableStateOf(vm.aprs.ssid()) }
    var floue by remember { mutableStateOf(vm.aprs.positionFloue()) }
    var balise by remember { mutableStateOf(vm.aprsBaliseIss()) }
    var fetes by remember { mutableStateOf(vm.aprsFetes()) }
    var niveau by remember { mutableStateOf(vm.aprs.niveau()) }
    var doppler by remember { mutableStateOf(vm.aprs.kissDoppler()) }
    var aide by remember { mutableStateOf(false) }
    val partage: (java.io.File) -> Unit = { f ->
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            ctx.startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, t("aprs_tx_essai_wav")))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Carte(t("aprs_mode_titre")) {
                listOf("AUDIO" to t("aprs_mode_audio"), "KISS" to t("aprs_mode_kiss"), "FT3D" to t("aprs_mode_ft3d")).forEach { (c, n) ->
                    Row(Modifier.fillMaxWidth().toggleable(value = mode == c, role = Role.RadioButton, onValueChange = { onMode(c) })
                        .padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = mode == c, onClick = null)
                        Text(n, color = TextHi, fontSize = 14.sp, fontWeight = if (mode == c) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Text(when (mode) { "KISS" -> t("aprs_mode_kiss_desc"); "FT3D" -> t("aprs_mode_ft3d_desc"); else -> t("aprs_mode_audio_desc") },
                    color = TextLo, fontSize = 11.sp)
            }
        }
        if (mode != "FT3D") item {
            Carte(t("aprs_travail_titre")) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("AUTO", "ISS", "TERRE") + if (mode == "KISS") listOf("POSTE") else emptyList()).forEach { c ->
                        PuceA(travail == c, nomTravail(c)) { onTravail(c) }
                    }
                }
                Text(when (travail) {
                    "ISS" -> t("aprs_travail_iss_desc"); "TERRE" -> t("aprs_travail_terre_desc")
                    "POSTE" -> t("aprs_kiss_freq_poste_desc"); else -> t("aprs_travail_auto_desc")
                }, color = TextLo, fontSize = 11.sp)
                if (mode == "KISS" && (travail == "AUTO" || travail == "ISS")) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp).toggleable(value = doppler, role = Role.Checkbox,
                            onValueChange = { doppler = it; vm.aprs.setKissDoppler(it) }), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = doppler, onCheckedChange = null)
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(t("aprs_kiss_doppler"), color = TextHi, fontSize = 12.sp)
                            Text(t("aprs_kiss_doppler_desc"), color = TextLo, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            when (mode) {
                "KISS" -> Connexion(vm, kiss = true)
                "FT3D" -> Connexion(vm, kiss = false)
                else -> Carte(t("aprs_reception")) {
                    Text(t("aprs_iss_ecouter_desc"), color = TextLo, fontSize = 11.sp)
                    if (ui.recorderSource != "USB") Text(t("aprs_iss_source_usb"), color = Amber, fontSize = 11.sp)
                    Interrupteur(t("aprs_actif"), t("aprs_actif_desc"), actif) { actif = it; vm.setAprsActif(it) }
                    st.erreur?.let { Text(tf("aprs_erreur", it), color = Amber, fontSize = 11.sp) }
                    Spacer(Modifier.height(8.dp))
                    Text(t("aprs_relire"), color = TextHi, fontSize = 13.sp)
                    Text(t("aprs_relire_desc"), color = TextLo, fontSize = 11.sp)
                    if (st.progression >= 0f) {
                        LinearProgressIndicator(progress = { st.progression }, color = Cyan, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text(tf("aprs_relire_en_cours", st.fichier ?: "", st.trouves), color = TextLo, fontSize = 11.sp)
                        TextButton(onClick = { AprsHub.annuleFichier() }) { Text(t("cancel"), color = Cyan) }
                    } else {
                        OutlinedButton(onClick = { choix = true }, modifier = Modifier.padding(top = 6.dp)) {
                            Text(t("sstv_pick_recording"), color = Cyan, fontSize = 12.sp)
                        }
                        st.fichier?.let { Text(tf("aprs_relire_fini", it, st.trouves), color = TextLo, fontSize = 11.sp) }
                    }
                }
            }
        }
        item {
            Carte(t("aprs_station_titre")) {
                Text(t("aprs_tx_ssid"), color = TextLo, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 6, 7, 9, 10).forEach { n -> PuceA(ssid == n, if (n == 0) "—" else "-$n") { ssid = n; vm.aprs.setSsid(n) } }
                }
                Text(tf("aprs_station_indicatif", vm.aprs.source().ifBlank { "—" }), color = TextLo, fontSize = 11.sp)
                Interrupteur(t("aprs_floue_titre"), t("aprs_floue_desc"), floue) { floue = it; vm.aprs.setPositionFloue(it) }
                if (floue) TextButton(onClick = { vm.aprs.nouveauFlou() }) { Text(t("aprs_floue_nouveau"), color = Cyan, fontSize = 12.sp) }
                if (mode != "FT3D") Interrupteur(t("aprs_balise_titre"),
                    if (mode == "KISS") t("aprs_balise_desc_kiss") else t("aprs_balise_desc"), balise, rouge = true) {
                    balise = it; vm.setAprsBaliseIss(it)
                }
            }
        }
        if (mode == "AUDIO") item {
            Carte(t("aprs_audio_titre")) {
                Text(tf("aprs_tx_niveau", (niveau * 100).toInt()), color = TextLo, fontSize = 11.sp)
                androidx.compose.material3.Slider(value = niveau, valueRange = 0.05f..1f,
                    onValueChange = { niveau = it }, onValueChangeFinished = { vm.aprs.setNiveau(niveau) })
                // The audio the IC-9700 would get, without transmitting.
                val essai = remember(ui.callsign) {
                    fr.f4ioz.satcombo.aprs.AprsEmission.trame(vm.aprs.source().ifBlank { "N0CALL" }, listOf("ARISS"),
                        fr.f4ioz.satcombo.aprs.AprsEmission.statut("SatMe test"))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.aprs.essai(essai, fichier = false, partage = partage) }) {
                        Text(t("aprs_tx_essai_hp"), color = Cyan, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { vm.aprs.essai(essai, fichier = true, partage = partage) }) {
                        Text(t("aprs_tx_essai_wav"), color = Cyan, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { aide = !aide }) { Text(t("aprs_tx_aide_titre"), color = Cyan, fontSize = 12.sp) }
                if (aide) Text(t("aprs_tx_aide"), color = TextLo, fontSize = 11.sp)
            }
        }
        item {
            Carte(t("aprs_divers_titre")) {
                Interrupteur(t("aprs_fetes_titre"), t("aprs_fetes_desc"), fetes) { fetes = it; vm.setAprsFetes(it) }
                if (st.paquets.isNotEmpty()) TextButton(onClick = { effacer = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(t("aprs_effacer"), color = Magenta, fontSize = 13.sp)
                }
            }
        }
    }

    if (choix) {
        RecordingPicker(onDismiss = { choix = false }, titre = t("sstv_pick_recording"), onPick = { f ->
            choix = false
            portee.launch { withContext(Dispatchers.IO) { AprsHub.decodeFichier(ctx, f) } }
        })
    }
    if (effacer) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { effacer = false },
            confirmButton = { TextButton(onClick = { effacer = false; AprsHub.efface(ctx) }) { Text(t("aprs_effacer"), color = Magenta) } },
            dismissButton = { TextButton(onClick = { effacer = false }) { Text(t("cancel"), color = Cyan) } },
            text = { Text(t("aprs_effacer_confirme"), color = TextHi) })
    }
}

/**
 * The USB link to a KISS radio (TH-D72…) or an FT3D: adapter, speed,
 * connect; once connected, what the radio says. The strip on top connects too.
 */
@Composable
private fun Connexion(vm: MainViewModel, kiss: Boolean) {
    val ctx = LocalContext.current
    val k by fr.f4ioz.satcombo.aprs.TncKiss.etat.collectAsState()
    val f by fr.f4ioz.satcombo.aprs.RecepteurWaypoints.etat.collectAsState()
    var appareils by remember { mutableStateOf(fr.f4ioz.satcombo.aprs.TncKiss.appareils(ctx)) }
    var cle by remember { mutableStateOf(if (kiss) vm.aprs.kissCle() else vm.aprs.ft3dCle()) }
    var vitesse by remember { mutableStateOf(if (kiss) vm.aprs.kissVitesse() else vm.aprs.ft3dVitesse()) }
    var aide by remember { mutableStateOf(false) }
    val connecte = if (kiss) k.connecte else f.connecte
    Carte(t("aprs_connexion_titre")) {
        Text(if (kiss) t("aprs_kiss_desc") else t("aprs_ft3d_desc"), color = TextLo, fontSize = 11.sp)
        if (connecte) {
            if (kiss) {
                Text(tf("aprs_kiss_connecte", k.nom, k.vitesse, k.recues, k.envoyees), color = Aurora, fontSize = 12.sp)
                k.frequenceHz?.let { hz ->
                    Text(tf("aprs_kiss_frequence_lue", "%.4f".format(Locale.US, hz / 1e6), if (k.bande == 1) "B" else "A"),
                        color = TextHi, fontSize = 12.sp)
                }
                if (k.initialise) Text(t("aprs_kiss_init_envoye"), color = TextLo, fontSize = 11.sp)
                else {
                    Text(t("aprs_kiss_pas_en_kiss"), color = Amber, fontSize = 11.sp)
                    OutlinedButton(onClick = { vm.aprs.kissPasseEnKiss() }) { Text(t("aprs_kiss_passer"), color = Cyan, fontSize = 12.sp) }
                }
                k.erreur?.let { Text(t("aprs_kiss_erreur_$it"), color = Amber, fontSize = 11.sp) }
            } else {
                Text(tf("aprs_ft3d_connecte", f.nom, f.vitesse, f.recues), color = Aurora, fontSize = 12.sp)
                if (f.illisibles > 0 && f.recues == 0) Text(t("aprs_ft3d_illisibles"), color = Amber, fontSize = 11.sp)
            }
            TextButton(onClick = { if (kiss) vm.aprs.kissDeconnecte() else vm.aprs.ft3dDeconnecte() }) {
                Text(t("aprs_kiss_deconnecter"), color = Magenta, fontSize = 12.sp)
            }
        } else {
            if (appareils.isEmpty()) Text(t("aprs_kiss_aucun"), color = Amber, fontSize = 11.sp)
            appareils.forEach { a ->
                Row(Modifier.fillMaxWidth().toggleable(value = cle == a.cle, role = Role.RadioButton, onValueChange = { cle = a.cle }),
                    verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = cle == a.cle, onClick = null)
                    Text(a.nom, color = TextHi, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (if (kiss) listOf(9600, 19200, 38400, 57600) else listOf(4800, 9600, 19200, 38400)).forEach { v ->
                    PuceA(vitesse == v, "$v") { vitesse = v }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = appareils.any { it.cle == cle }, onClick = {
                    if (kiss) vm.aprs.kissConnecte(cle, vitesse) else vm.aprs.ft3dConnecte(cle, vitesse)
                }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                    Text(t("aprs_kiss_connecter"), color = Color.Black)
                }
                TextButton(onClick = { appareils = fr.f4ioz.satcombo.aprs.TncKiss.appareils(ctx) }) {
                    Text(t("aprs_kiss_rechercher"), color = Cyan, fontSize = 12.sp)
                }
            }
            (if (kiss) k.erreur else f.erreur)?.let { Text(t("aprs_kiss_erreur_$it"), color = Amber, fontSize = 11.sp) }
        }
        TextButton(onClick = { aide = !aide }) {
            Text(if (kiss) t("aprs_kiss_aide_titre") else t("aprs_ft3d_aide_titre"), color = Cyan, fontSize = 12.sp)
        }
        if (aide) Text(if (kiss) t("aprs_kiss_aide") else t("aprs_ft3d_aide"), color = TextLo, fontSize = 11.sp)
    }
}

private val heureUtc = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** One frame: when, who, how it came, what it says; tapped, the raw frame. */
@Composable
private fun LignePaquet(p: Paquet, ui: UiState, ouvert: Boolean, accuse: Boolean, onClic: () -> Unit) {
    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClic() }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.source, color = TextHi, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace)
                if (p.emis) {
                    Spacer(Modifier.width(6.dp))
                    Text("↑ " + t("aprs_emis"), color = Color(0xFFE5484D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (accuse) {
                        Spacer(Modifier.width(6.dp))
                        Text(t("aprs_accuse"), color = Aurora, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (p.viaIss) {
                    Spacer(Modifier.width(6.dp))
                    Text(t("aprs_via_iss"), color = Aurora, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (p.hotg) {
                    Spacer(Modifier.width(6.dp))
                    Text("HOTG", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(heureUtc.format(Date(p.quand)) + " UTC", color = TextLo, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
            val lignes = buildList {
                when (p.type) {
                    TypeAprs.MESSAGE -> add("✉ → ${p.destinataire} : ${p.message}")
                    TypeAprs.ACCUSE -> add("✓ → ${p.destinataire} : ${p.message}")
                    TypeAprs.OBJET -> add("◆ ${p.nom}")
                    TypeAprs.STATUT -> add("» ${p.commentaire}")
                    else -> {}
                }
                if (p.lat != null && p.lon != null) {
                    val obs = ui.observer
                    val km = obs?.let {
                        fr.f4ioz.satcombo.sonde.Geo.distanceKm(it.latDeg, it.lonDeg, p.lat, p.lon)
                    }
                    val loc = fr.f4ioz.satcombo.location.Maidenhead.fromLatLon(p.lat, p.lon)
                    add("📍 $loc · %.4f %.4f".format(Locale.US, p.lat, p.lon) +
                        (km?.let { " · %d km".format(it.toInt()) } ?: "") +
                        (p.vitesseKmh?.let { " · $it km/h" } ?: "") +
                        (p.altitudeM?.let { " · $it m" } ?: ""))
                }
                p.meteo?.let { add(ligneMeteo(it)) }
                if (p.type != TypeAprs.STATUT && p.commentaire.isNotBlank()) add(p.commentaire)
            }
            lignes.forEach { Text(it, color = TextLo, fontSize = 12.sp) }
            if (ouvert) {
                Text(p.trame.tnc2().map { if (it.code < 32 || it.code == 127) '·' else it }.joinToString(""),
                    color = TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
