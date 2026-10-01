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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * APRS: the radio is chosen once at the top — the phone's recordings with the
 * IC-9700 to transmit, or a KISS radio (TH-D72…) for both — and the reception
 * and transmission zones follow it. Then what was heard and sent.
 */
@Composable
fun AprsScreen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val portee = rememberCoroutineScope()
    val st by AprsHub.etat.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AprsHub.charge(ctx) } }
    var actif by remember { mutableStateOf(vm.aprsActif()) }
    var mode by remember { mutableStateOf(vm.aprsMode()) }
    var choix by remember { mutableStateOf(false) }
    var filtre by remember { mutableStateOf("TOUT") }
    var ouvert by remember { mutableStateOf<Paquet?>(null) }
    var effacer by remember { mutableStateOf(false) }

    val liste = remember(st.paquets, filtre) {
        when (filtre) {
            "ISS" -> st.paquets.filter { it.viaIss }
            "MSG" -> st.paquets.filter { it.type == TypeAprs.MESSAGE || it.type == TypeAprs.ACCUSE }
            "HOTG" -> st.paquets.filter { it.hotg }
            else -> st.paquets
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The radio, chosen once: reception and transmission both follow it.
        item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("aprs_mode_titre"), color = TextHi, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        Surface(color = Amber.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                            Text(t("aprs_beta"), color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    listOf("AUDIO" to t("aprs_mode_audio"), "KISS" to t("aprs_mode_kiss"),
                        "FT3D" to t("aprs_mode_ft3d")).forEach { (cle, nom) ->
                        Row(Modifier.fillMaxWidth().toggleable(value = mode == cle, role = Role.RadioButton,
                                onValueChange = { mode = cle; vm.setAprsMode(cle) }).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = mode == cle, onClick = null)
                            Text(nom, color = TextHi, fontSize = 14.sp,
                                fontWeight = if (mode == cle) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    Text(when (mode) { "KISS" -> t("aprs_mode_kiss_desc"); "FT3D" -> t("aprs_mode_ft3d_desc")
                            else -> t("aprs_mode_audio_desc") },
                        color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        // Reception, for the radio chosen above.
        if (mode == "KISS") {
            item { CarteTnc(vm) }
        } else if (mode == "FT3D") {
            item { CarteFt3d(vm) }
        } else item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("aprs_reception"), color = TextHi, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().toggleable(value = actif, role = Role.Switch,
                            onValueChange = { actif = it; vm.setAprsActif(it) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("aprs_actif"), color = TextHi, fontSize = 13.sp)
                            Text(t("aprs_actif_desc"), color = TextLo, fontSize = 11.sp)
                        }
                        Switch(checked = actif, onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
                    }
                    Text(
                        when {
                            st.ecoute -> tf("aprs_ecoute", st.sat, st.nouveaux)
                            actif -> t("aprs_pret")
                            else -> t("aprs_arrete")
                        },
                        color = if (st.ecoute) Aurora else TextLo, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp))
                    st.erreur?.let { Text(tf("aprs_erreur", it), color = Amber, fontSize = 11.sp) }
                    // An older recording, or a WAV from the rig's SD card.
                    Spacer(Modifier.height(10.dp))
                    Text(t("aprs_relire"), color = TextHi, fontSize = 13.sp)
                    Text(t("aprs_relire_desc"), color = TextLo, fontSize = 11.sp)
                    if (st.progression >= 0f) {
                        LinearProgressIndicator(progress = { st.progression }, color = Cyan,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text(tf("aprs_relire_en_cours", st.fichier ?: "", st.trouves),
                            color = TextLo, fontSize = 11.sp)
                        TextButton(onClick = { AprsHub.annuleFichier() }) { Text(t("cancel"), color = Cyan) }
                    } else {
                        androidx.compose.material3.OutlinedButton(onClick = { choix = true },
                            modifier = Modifier.padding(top = 6.dp)) {
                            Text(t("sstv_pick_recording"), color = Cyan, fontSize = 12.sp)
                        }
                        st.fichier?.let {
                            Text(tf("aprs_relire_fini", it, st.trouves), color = TextLo, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        // Transmission, through the same radio — the FT3D transmits from its own menu.
        if (mode == "FT3D") item {
            Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(t("aprs_tx_titre"), color = TextHi, fontWeight = FontWeight.Bold)
                    Text(t("aprs_ft3d_tx"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else item { CarteEmission(ui, vm, mode) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("TOUT" to t("aprs_filtre_tout"), "ISS" to t("aprs_filtre_iss"),
                    "MSG" to t("aprs_filtre_msg"), "HOTG" to "HOTG").forEach { (cle, nom) ->
                    FilterChip(selected = filtre == cle, onClick = { filtre = cle },
                        label = { Text(nom, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
                }
            }
        }
        if (liste.isEmpty()) {
            item { Text(t("aprs_vide"), color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
        }
        items(liste, key = { "${it.quand}-${it.trame.hashCode()}-${it.emis}" }) { p ->
            // A sent message is acknowledged when an "ack<number>" comes back to us.
            val accuse = p.emis && p.idMessage != null && st.paquets.any {
                it.type == TypeAprs.ACCUSE && it.idMessage == p.idMessage &&
                    it.destinataire?.substringBefore('-') == p.trame.source.indicatif
            }
            LignePaquet(p, ui, ouvert == p, accuse) { ouvert = if (ouvert == p) null else p }
        }
        if (st.paquets.isNotEmpty()) {
            item {
                TextButton(onClick = { effacer = true }, modifier = Modifier.fillMaxWidth()) {
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
            confirmButton = {
                TextButton(onClick = { effacer = false; AprsHub.efface(ctx) }) { Text(t("aprs_effacer"), color = Magenta) }
            },
            dismissButton = { TextButton(onClick = { effacer = false }) { Text(t("cancel"), color = Cyan) } },
            text = { Text(t("aprs_effacer_confirme"), color = TextHi) })
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

/**
 * Transmit: what to send (APRS Thursday, APRSPH, message, position, status),
 * how (path, SSID, level), a preview of the frame, tests without transmitting,
 * and the IC-9700 button.
 */
@Composable
private fun CarteEmission(ui: UiState, vm: MainViewModel, mode: String) {
    val ctx = LocalContext.current
    val resultat by vm.aprsEnvoi.collectAsState()
    var type by remember { mutableStateOf("JEUDI") }
    var texte by remember { mutableStateOf("") }
    var destinataire by remember { mutableStateOf("") }
    var chemin by remember { mutableStateOf("ARISS") }
    var ssid by remember { mutableStateOf(vm.aprsSsid()) }
    var niveau by remember { mutableStateOf(vm.aprsNiveau()) }
    var aide by remember { mutableStateOf(false) }
    val kiss by fr.f4ioz.satcombo.aprs.TncKiss.etat.collectAsState()
    val par = if (mode == "KISS") "KISS" else "CAT"
    var frequenceOk by remember { mutableStateOf(false) }
    // The message number is drawn when sending, so the preview shows a placeholder.
    val source = remember(ssid, ui.callsign) { vm.aprsSource() }
    val obs = ui.observer
    fun info(id: String?): String? = when (type) {
        "JEUDI" -> fr.f4ioz.satcombo.aprs.AprsEmission.message("ANSRVR",
            texte.ifBlank { "CQ HOTG" }.let { if (it.uppercase().let { u -> u.startsWith("CQ HOTG") || u.startsWith("K HOTG") || u.startsWith("U HOTG") }) it else "CQ HOTG $it" }, id)
        "APRSPH" -> fr.f4ioz.satcombo.aprs.AprsEmission.message("APRSPH",
            texte.ifBlank { "HOTG" }.let { if (it.uppercase().startsWith("HOTG")) it else "HOTG $it" }, id)
        "MSG" -> if (destinataire.isBlank()) null else fr.f4ioz.satcombo.aprs.AprsEmission.message(destinataire, texte, id)
        "POS" -> obs?.let { fr.f4ioz.satcombo.aprs.AprsEmission.position(it.latDeg, it.lonDeg, "/-", texte) }
        else -> fr.f4ioz.satcombo.aprs.AprsEmission.statut(texte)
    }
    val cheminListe = when (chemin) { "ARISS" -> listOf("ARISS"); "WIDE" -> listOf("WIDE2-1"); else -> emptyList() }
    val avecNumero = type in setOf("JEUDI", "APRSPH", "MSG")
    val apercu = info(if (avecNumero) "n" else null)?.let {
        fr.f4ioz.satcombo.aprs.AprsEmission.trame(source.ifBlank { "N0CALL" }, cheminListe, it).tnc2()
    }
    fun trame(): fr.f4ioz.satcombo.aprs.Trame? {
        val i = info(if (avecNumero) vm.aprsNumeroSuivant() else null) ?: return null
        return fr.f4ioz.satcombo.aprs.AprsEmission.trame(source, cheminListe, i)
    }
    val partage: (java.io.File) -> Unit = { f ->
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            ctx.startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    this.type = "audio/wav"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, t("aprs_tx_essai_wav")))
        }
    }

    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t("aprs_tx_titre"), color = TextHi, fontWeight = FontWeight.Bold)
            Text(t("aprs_tx_banniere"), color = TextLo, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("JEUDI" to "APRS Thursday", "APRSPH" to "APRSPH", "MSG" to t("aprs_tx_type_msg"),
                    "POS" to t("aprs_tx_type_pos"), "STATUT" to t("aprs_tx_type_statut")).forEach { (cle, nom) ->
                    FilterChip(selected = type == cle, onClick = { type = cle; texte = "" },
                        label = { Text(nom, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
                }
            }
            when (type) {
                "JEUDI" -> Text(t("aprs_tx_jeudi_aide"), color = TextLo, fontSize = 11.sp)
                "APRSPH" -> Text(t("aprs_tx_aprsph_aide"), color = TextLo, fontSize = 11.sp)
                "POS" -> if (obs == null) Text(t("aprs_tx_sans_position"), color = Amber, fontSize = 11.sp)
                else -> {}
            }
            if (type == "JEUDI") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("K HOTG", "U HOTG").forEach { c ->
                        TextButton(onClick = { texte = c }) { Text(c, color = Cyan, fontSize = 12.sp) }
                    }
                }
            }
            if (type == "MSG") {
                androidx.compose.material3.OutlinedTextField(value = destinataire,
                    onValueChange = { destinataire = it.uppercase().take(9) },
                    label = { Text(t("aprs_tx_destinataire"), fontSize = 12.sp) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
            androidx.compose.material3.OutlinedTextField(value = texte,
                onValueChange = { texte = it.take(fr.f4ioz.satcombo.aprs.AprsEmission.LONGUEUR_MESSAGE) },
                label = { Text(if (type == "POS") t("aprs_tx_commentaire") else t("aprs_tx_texte"), fontSize = 12.sp) },
                placeholder = { Text(when (type) { "JEUDI" -> "CQ HOTG 73 de JN18"; "APRSPH" -> "HOTG 73"; else -> "" }, color = TextLo) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(t("aprs_tx_chemin"), color = TextLo, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ARISS" to t("aprs_tx_chemin_iss"), "WIDE" to t("aprs_tx_chemin_terre"),
                    "AUCUN" to t("aprs_tx_chemin_aucun")).forEach { (cle, nom) ->
                    FilterChip(selected = chemin == cle, onClick = { chemin = cle },
                        label = { Text(nom, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
                }
            }
            Text(t("aprs_tx_ssid"), color = TextLo, fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 6, 7, 9, 10).forEach { n ->
                    FilterChip(selected = ssid == n, onClick = { ssid = n; vm.setAprsSsid(n) },
                        label = { Text(if (n == 0) "—" else "-$n", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
                }
            }
            // The audio level is the IC-9700's business; a KISS radio sets its own.
            if (par == "CAT") {
                Text(tf("aprs_tx_niveau", (niveau * 100).toInt()), color = TextLo, fontSize = 11.sp)
                androidx.compose.material3.Slider(value = niveau, valueRange = 0.05f..1f,
                    onValueChange = { niveau = it }, onValueChangeFinished = { vm.setAprsNiveau(niveau) })
            }
            if (source.isBlank()) Text(t("aprs_tx_sans_indicatif"), color = Amber, fontSize = 11.sp)
            apercu?.let {
                Text(t("aprs_tx_apercu"), color = TextLo, fontSize = 11.sp)
                Text(it, color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            // Tests without transmitting: the audio the IC-9700 would get. A KISS radio modulates by itself.
            if (par == "CAT") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedButton(enabled = apercu != null && source.isNotBlank(),
                    onClick = { trame()?.let { vm.aprsEssai(it, fichier = false, partage = partage) } }) {
                    Text(t("aprs_tx_essai_hp"), color = Cyan, fontSize = 12.sp)
                }
                androidx.compose.material3.OutlinedButton(enabled = apercu != null && source.isNotBlank(),
                    onClick = { trame()?.let { vm.aprsEssai(it, fichier = true, partage = partage) } }) {
                    Text(t("aprs_tx_essai_wav"), color = Cyan, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (par == "KISS") {
                // In KISS mode the radio's frequency cannot be read: the operator vouches for it.
                Row(Modifier.fillMaxWidth().toggleable(value = frequenceOk, role = Role.Checkbox,
                        onValueChange = { frequenceOk = it }), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = frequenceOk, onCheckedChange = null)
                    Text(t("aprs_kiss_frequence_ok"), color = TextHi, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
            Button(enabled = apercu != null && source.isNotBlank() && (par == "CAT" || (kiss.connecte && frequenceOk)),
                onClick = {
                    trame()?.let { if (par == "KISS") vm.aprsEmetKiss(it, frequenceOk) else vm.aprsEmet(it) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5484D)),
                modifier = Modifier.fillMaxWidth()) {
                Text(if (par == "KISS") t("aprs_kiss_emettre") else t("aprs_tx_emettre"),
                    color = Color.White, fontWeight = FontWeight.Bold)
            }
            if (resultat.isNotBlank()) Text(resultat, color = TextHi, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
            if (par == "CAT") {
                TextButton(onClick = { aide = !aide }) { Text(t("aprs_tx_aide_titre"), color = Cyan, fontSize = 12.sp) }
                if (aide) Text(t("aprs_tx_aide"), color = TextLo, fontSize = 11.sp)
            }
        }
    }
}

/**
 * A radio with a KISS TNC on USB (TH-D72…): choose the adapter and speed,
 * connect, switch it to KISS; frames it receives join the list.
 */
@Composable
private fun CarteTnc(vm: MainViewModel) {
    val ctx = LocalContext.current
    val etat by fr.f4ioz.satcombo.aprs.TncKiss.etat.collectAsState()
    var appareils by remember { mutableStateOf(fr.f4ioz.satcombo.aprs.TncKiss.appareils(ctx)) }
    var cle by remember { mutableStateOf(vm.aprsKissCle()) }
    var vitesse by remember { mutableStateOf(vm.aprsKissVitesse()) }
    var aide by remember { mutableStateOf(false) }
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t("aprs_reception"), color = TextHi, fontWeight = FontWeight.Bold)
            Text(t("aprs_kiss_desc"), color = TextLo, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            if (etat.connecte) {
                Text(tf("aprs_kiss_connecte", etat.nom, etat.vitesse, etat.recues, etat.envoyees),
                    color = Aurora, fontSize = 12.sp)
                if (etat.initialise) Text(t("aprs_kiss_init_envoye"), color = TextLo, fontSize = 11.sp)
                etat.frequenceHz?.let { hz ->
                    Text(tf("aprs_kiss_frequence_lue", "%.4f".format(java.util.Locale.US, hz / 1e6),
                        if (etat.bande == 1) "B" else "A"), color = TextHi, fontSize = 12.sp)
                }
                etat.erreur?.let { Text(t("aprs_kiss_erreur_$it"), color = Amber, fontSize = 11.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { vm.kissPasseEnKiss() }) {
                        Text(t("aprs_kiss_passer"), color = Cyan, fontSize = 12.sp)
                    }
                    TextButton(onClick = { vm.kissDeconnecte() }) {
                        Text(t("aprs_kiss_deconnecter"), color = Magenta, fontSize = 12.sp)
                    }
                }
            } else {
                if (appareils.isEmpty()) Text(t("aprs_kiss_aucun"), color = Amber, fontSize = 11.sp)
                appareils.forEach { a ->
                    Row(Modifier.fillMaxWidth().toggleable(value = cle == a.cle, role = Role.RadioButton,
                            onValueChange = { cle = a.cle }), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = cle == a.cle, onClick = null)
                        Text(a.nom, color = TextHi, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(9600, 19200, 38400, 57600).forEach { v ->
                        FilterChip(selected = vitesse == v, onClick = { vitesse = v },
                            label = { Text("$v", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = appareils.any { it.cle == cle }, onClick = { vm.kissConnecte(cle, vitesse) },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Text(t("aprs_kiss_connecter"), color = Color.Black)
                    }
                    TextButton(onClick = { appareils = fr.f4ioz.satcombo.aprs.TncKiss.appareils(ctx) }) {
                        Text(t("aprs_kiss_rechercher"), color = Cyan, fontSize = 12.sp)
                    }
                }
                etat.erreur?.let { Text(t("aprs_kiss_erreur_$it"), color = Amber, fontSize = 11.sp) }
            }
            TextButton(onClick = { aide = !aide }) { Text(t("aprs_kiss_aide_titre"), color = Cyan, fontSize = 12.sp) }
            if (aide) Text(t("aprs_kiss_aide"), color = TextLo, fontSize = 11.sp)
        }
    }
}

/**
 * Reception from a Yaesu FT3D: it writes each APRS station it decodes as a
 * waypoint line on its USB port (OUTPUT = WAY.P). Adapter, speed, connect.
 */
@Composable
private fun CarteFt3d(vm: MainViewModel) {
    val ctx = LocalContext.current
    val etat by fr.f4ioz.satcombo.aprs.RecepteurWaypoints.etat.collectAsState()
    var appareils by remember { mutableStateOf(fr.f4ioz.satcombo.aprs.TncKiss.appareils(ctx)) }
    var cle by remember { mutableStateOf(vm.aprsFt3dCle()) }
    var vitesse by remember { mutableStateOf(vm.aprsFt3dVitesse()) }
    var aide by remember { mutableStateOf(false) }
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t("aprs_reception"), color = TextHi, fontWeight = FontWeight.Bold)
            Text(t("aprs_ft3d_desc"), color = TextLo, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            if (etat.connecte) {
                Text(tf("aprs_ft3d_connecte", etat.nom, etat.vitesse, etat.recues), color = Aurora, fontSize = 12.sp)
                if (etat.illisibles > 0 && etat.recues == 0)
                    Text(t("aprs_ft3d_illisibles"), color = Amber, fontSize = 11.sp)
                TextButton(onClick = { vm.ft3dDeconnecte() }) {
                    Text(t("aprs_kiss_deconnecter"), color = Magenta, fontSize = 12.sp)
                }
            } else {
                if (appareils.isEmpty()) Text(t("aprs_kiss_aucun"), color = Amber, fontSize = 11.sp)
                appareils.forEach { a ->
                    Row(Modifier.fillMaxWidth().toggleable(value = cle == a.cle, role = Role.RadioButton,
                            onValueChange = { cle = a.cle }), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = cle == a.cle, onClick = null)
                        Text(a.nom, color = TextHi, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(4800, 9600, 19200, 38400).forEach { v ->
                        FilterChip(selected = vitesse == v, onClick = { vitesse = v },
                            label = { Text("$v", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.2f), selectedLabelColor = Cyan))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = appareils.any { it.cle == cle }, onClick = { vm.ft3dConnecte(cle, vitesse) },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan)) {
                        Text(t("aprs_kiss_connecter"), color = Color.Black)
                    }
                    TextButton(onClick = { appareils = fr.f4ioz.satcombo.aprs.TncKiss.appareils(ctx) }) {
                        Text(t("aprs_kiss_rechercher"), color = Cyan, fontSize = 12.sp)
                    }
                }
                etat.erreur?.let { Text(t("aprs_kiss_erreur_$it"), color = Amber, fontSize = 11.sp) }
            }
            TextButton(onClick = { aide = !aide }) { Text(t("aprs_ft3d_aide_titre"), color = Cyan, fontSize = 12.sp) }
            if (aide) Text(t("aprs_ft3d_aide"), color = TextLo, fontSize = 11.sp)
        }
    }
}
