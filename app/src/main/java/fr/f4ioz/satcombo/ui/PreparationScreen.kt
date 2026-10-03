/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.domain.StationReadiness
import fr.f4ioz.satcombo.domain.StationReadiness.Niveau
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Rouge = Color(0xFFE5484D)
private val Vert = Color(0xFF49D17F)

private fun couleur(n: Niveau): Color = when (n) {
    Niveau.OK -> Vert; Niveau.WARNING -> Amber; Niveau.ERROR -> Rouge; Niveau.NOT_REQUIRED -> TextLo
}

private fun badge(n: Niveau): String = when (n) {
    Niveau.OK -> "OK"; Niveau.WARNING -> "!"; Niveau.ERROR -> "✕"; Niveau.NOT_REQUIRED -> "—"
}

/** "04:38", "1:04:38", from milliseconds. */
private fun duree(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/**
 * Before the pass: is the station ready? The satellite and its pass, the
 * station profile (Fixe, Portable…), then one line per domain — OK, to
 * check, wrong, or not needed for this profile — each line a shortcut to
 * what fixes it. Nothing here transmits; a warning never stops the operator
 * from going to the pass.
 */
@Composable
fun PreparationScreen(ui: UiState, vm: MainViewModel, prep: MainViewModel.Preparation) {
    val catnum = prep.catnum
    val (sat, passage) = remember(prep, ui.nowMs / 30_000L, ui.satellites.size) { vm.passagePreparation(catnum, prep.aosMs) }
    var profils by remember { mutableStateOf(vm.profilsStation()) }
    var actif by remember { mutableStateOf(vm.profilActif().id) }
    var voyants by remember { mutableStateOf<List<StationReadiness.Voyant>>(emptyList()) }
    var edite by remember { mutableStateOf<StationReadiness.ProfilNomme?>(null) }
    var tour by remember { mutableStateOf(0) }
    // The station check: null = not run yet; the time it ran.
    var test by remember(catnum) { mutableStateOf<List<StationReadiness.Voyant>?>(null) }
    var testEnCours by remember { mutableStateOf(false) }
    var testHeure by remember { mutableStateOf(0L) }
    val portee = rememberCoroutineScope()
    // Refreshed every two seconds: the rig connects, the GPS gets a fix, the lights follow.
    LaunchedEffect(catnum, actif, tour) {
        while (true) { voyants = vm.stationReadiness(catnum); delay(2_000) }
    }
    // CAT connected: the rig goes to the satellite being prepared (and comes
    // back to it when the rig connects while the screen is open).
    LaunchedEffect(catnum, passage?.aosEpochMs, ui.catConnected) {
        vm.accordePreparation(catnum, passage?.aosEpochMs)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tour++ }
    val global = StationReadiness.global(voyants)
    val heure = remember(ui.useUtc) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply {
            if (ui.useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }

    Dialog(onDismissRequest = { vm.fermePreparation() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SpaceBg, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("rd_titre"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.fermePreparation() }) { Text("✕", color = TextLo, fontSize = 18.sp) }
                    }
                }
                // Several passes close together (ISS and JO-97): pick the one to prepare.
                item {
                    val autres = remember(ui.nowMs / 30_000L, ui.favoritePasses.size) { vm.passagesAPreparer() }
                    if (autres.size > 1) Column {
                        Text(t("rd_autres_passages"), color = TextLo, fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            autres.forEach { p ->
                                val enCours = ui.nowMs >= p.aosEpochMs
                                // Overlaps the pass being prepared: both up at the same time.
                                val ensemble = passage != null && p.catalogNumber != catnum &&
                                    p.aosEpochMs < passage.losEpochMs && p.losEpochMs > passage.aosEpochMs
                                FilterChip(selected = p.catalogNumber == catnum && (passage == null || kotlin.math.abs(p.aosEpochMs - passage.aosEpochMs) < 10 * 60_000L), onClick = { vm.ouvrePreparation(p.catalogNumber, p.aosEpochMs) },
                                    label = {
                                        Text(p.satName + " · " + (if (enCours) t("rd_en_cours") else heure.format(java.util.Date(p.aosEpochMs))) +
                                            (if (ensemble) " ⇄" else ""), fontSize = 12.sp)
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                            }
                        }
                        if (autres.any { p -> passage != null && p.catalogNumber != catnum &&
                                p.aosEpochMs < passage.losEpochMs && p.losEpochMs > passage.aosEpochMs })
                            Text(t("rd_chevauchement"), color = Amber, fontSize = 11.sp)
                    }
                }
                // The satellite and its pass.
                item {
                    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sat?.name ?: "—", color = TextHi, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                                    modifier = Modifier.weight(1f))
                                passage?.let { p ->
                                    val enCours = ui.nowMs >= p.aosEpochMs
                                    Text(if (enCours) "LOS " + duree(p.losEpochMs - ui.nowMs) else "AOS −" + duree(p.aosEpochMs - ui.nowMs),
                                        color = if (enCours) Aurora else Amber, fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                                }
                            }
                            passage?.let { p ->
                                Text(heure.format(java.util.Date(p.aosEpochMs)) + " → " + heure.format(java.util.Date(p.losEpochMs)) +
                                    (if (ui.useUtc) " UTC" else "") + " · " + tf("rd_el_max", p.maxElevationDeg.toInt()),
                                    color = TextLo, fontSize = 13.sp)
                            }
                        }
                    }
                }
                // The station profile: which one, and editing it.
                item {
                    Column {
                        Text(t("rd_profil"), color = TextLo, fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            profils.forEach { p ->
                                FilterChip(selected = actif == p.id, onClick = { actif = p.id; vm.setProfilActif(p.id); tour++ },
                                    label = { Text(p.nom, fontSize = 13.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
                            }
                            TextButton(onClick = { edite = profils.firstOrNull { it.id == actif } }) {
                                Text("✎ " + t("rd_profil_modifier"), color = Cyan, fontSize = 13.sp)
                            }
                            TextButton(onClick = {
                                val n = vm.nouveauProfil(""); profils = vm.profilsStation(); actif = n.id; edite = n
                            }) { Text("+ " + t("rd_profil_ajouter"), color = Cyan, fontSize = 13.sp) }
                            TextButton(onClick = {
                                val n = vm.profilDepuisConfiguration(""); profils = vm.profilsStation(); actif = n.id; edite = n
                            }) { Text("⤓ " + t("rd_profil_depuis_actuel"), color = Cyan, fontSize = 13.sp) }
                        }
                        // What the active profile puts in place.
                        profils.firstOrNull { it.id == actif }?.let { p ->
                            val postes = remember { vm.postesProposes().toMap() }
                            val parties = listOfNotNull(
                                p.poste?.let { postes[it] ?: it },
                                p.boussole?.let { if (it == "BLE") t("rd_boussole_ble") else t("rd_boussole_tel") },
                                p.audio?.let { when (it) { "USB" -> t("rd_audio_usb"); "BT" -> t("rd_audio_bt"); else -> t("rd_audio_micro") } })
                            Text(if (parties.isEmpty()) t("rd_profil_sans_materiel") else parties.joinToString(" · "),
                                color = TextLo, fontSize = 12.sp)
                        }
                    }
                }
                // Overall.
                item {
                    val (texte, c) = when (global) {
                        Niveau.ERROR -> t("rd_global_erreur") to Rouge
                        Niveau.WARNING -> t("rd_global_verifier") to Amber
                        else -> t("rd_global_pret") to Vert
                    }
                    Surface(color = c.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(if (voyants.isEmpty()) "…" else texte, color = c, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                }
                // The station check: ask the equipment itself.
                item {
                    OutlinedButton(enabled = !testEnCours, onClick = {
                        testEnCours = true
                        portee.launch {
                            // On the satellite first, so that the rig reads back its frequency.
                            if (ui.catConnected) { vm.accordePreparation(catnum, passage?.aosEpochMs); delay(1_500) }
                            test = runCatching { vm.testeStation() }.getOrNull()
                            testHeure = System.currentTimeMillis(); testEnCours = false; tour++
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (testEnCours) t("rd_test_en_cours") else "⚙ " + t("rd_tester"), color = Cyan, fontWeight = FontWeight.Bold)
                    }
                    Text(t("rd_tester_desc"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
                test?.let { r ->
                    item {
                        Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(tf("rd_test_resultat", heure.format(java.util.Date(testHeure))), color = TextHi,
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                r.forEach { v ->
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable(enabled = v.action != StationReadiness.Action.AUCUNE) {
                                        if (v.action == StationReadiness.Action.PERMISSION_MICRO) permission.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                                        else vm.actionReadiness(v.action, catnum)
                                    }, verticalAlignment = Alignment.CenterVertically) {
                                        Text(badge(v.niveau), color = couleur(v.niveau), fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                            modifier = Modifier.width(28.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(t("rd_dom_" + v.domaine.name.lowercase()), color = TextHi, fontSize = 12.sp)
                                            Text(tf(v.raison, *v.args.toTypedArray()), color = TextLo, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // One line per domain, the whole line a shortcut.
                items(voyants, key = { it.domaine.name }) { v ->
                    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = v.action != StationReadiness.Action.AUCUNE) {
                            if (v.action == StationReadiness.Action.PERMISSION_MICRO) {
                                val p = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
                                if (android.os.Build.VERSION.SDK_INT >= 31) p += android.Manifest.permission.BLUETOOTH_CONNECT
                                permission.launch(p.toTypedArray())
                            } else vm.actionReadiness(v.action, catnum)
                        }) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).background(couleur(v.niveau).copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(badge(v.niveau), color = couleur(v.niveau), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t("rd_dom_" + v.domaine.name.lowercase()), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(tf(v.raison, *v.args.toTypedArray()), color = TextLo, fontSize = 12.sp)
                            }
                            if (v.action != StationReadiness.Action.AUCUNE) Text("›", color = TextLo, fontSize = 20.sp)
                        }
                    }
                }
                item {
                    Text(t("rd_note"), color = TextLo, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                item {
                    Button(onClick = { vm.fermePreparation(); passage?.let { vm.selectByCatnum(catnum, it.aosEpochMs) } ?: vm.selectByCatnum(catnum) },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan), modifier = Modifier.fillMaxWidth()) {
                        Text(t("rd_aller_passage"), color = Color(0xFF00201D), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    edite?.let { e ->
        EditeurProfil(e, vm.postesProposes(), peutSupprimer = profils.size > 1,
            onFerme = { edite = null },
            onSupprime = { vm.supprimeProfil(e.id); profils = vm.profilsStation(); actif = vm.profilActif().id; edite = null; tour++ },
            onEnregistre = { n ->
                vm.enregistreProfil(n); profils = vm.profilsStation(); edite = null
                // The profile being edited is the active one: put the station as it now says.
                if (n.id == actif) vm.setProfilActif(n.id)
                tour++
            })
    }
}

/** A profile's name and what it expects. */
@Composable
private fun EditeurProfil(p: StationReadiness.ProfilNomme, postes: List<Pair<String, String>>, peutSupprimer: Boolean, onFerme: () -> Unit,
                          onSupprime: () -> Unit, onEnregistre: (StationReadiness.ProfilNomme) -> Unit) {
    var nom by remember { mutableStateOf(p.nom) }
    var cat by remember { mutableStateOf(p.profil.catRequis) }
    var rotor by remember { mutableStateOf(p.profil.pointage == StationReadiness.Pointage.ROTOR) }
    var rec by remember { mutableStateOf(p.profil.enregistrementRequis) }
    var sync by remember { mutableStateOf(p.profil.synchroRequise) }
    var poste by remember { mutableStateOf(p.poste) }
    var boussole by remember { mutableStateOf(p.boussole) }
    var audio by remember { mutableStateOf(p.audio) }
    @Composable
    fun Choix(titre: String, options: List<Pair<String?, String>>, valeur: String?, onChoix: (String?) -> Unit) {
        Text(titre, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (id, nom) ->
                FilterChip(selected = valeur == id, onClick = { onChoix(id) }, label = { Text(nom, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan.copy(alpha = 0.25f), selectedLabelColor = Cyan))
            }
        }
    }
    @Composable
    fun Interrupteur(titre: String, desc: String, valeur: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).toggleable(value = valeur, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(titre, fontSize = 14.sp)
                Text(desc, color = TextLo, fontSize = 11.sp)
            }
            Switch(checked = valeur, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
        }
    }
    AlertDialog(
        onDismissRequest = onFerme,
        title = { Text(t("rd_profil_titre")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = nom, onValueChange = { nom = it.take(24) }, singleLine = true,
                    label = { Text(t("rd_profil_nom")) }, modifier = Modifier.fillMaxWidth())
                Choix(t("rd_profil_poste"), listOf<Pair<String?, String>>(null to t("rd_profil_inchange")) + postes, poste) {
                    poste = it; if (it != null) cat = true
                }
                Choix(t("rd_profil_boussole"), listOf(null to t("rd_profil_inchange"), "TEL" to t("rd_boussole_tel"),
                    "BLE" to t("rd_boussole_ble")), boussole) { boussole = it }
                Choix(t("rd_profil_audio"), listOf(null to t("rd_profil_inchange"), "MIC" to t("rd_audio_micro"),
                    "BT" to t("rd_audio_bt"), "USB" to t("rd_audio_usb")), audio) { audio = it }
                Interrupteur(t("rd_profil_cat"), t("rd_profil_cat_desc"), cat) { cat = it }
                Interrupteur(t("rd_profil_rotor"), t("rd_profil_rotor_desc"), rotor) { rotor = it }
                Interrupteur(t("rd_profil_rec"), t("rd_profil_rec_desc"), rec) { rec = it }
                Interrupteur(t("rd_profil_sync"), t("rd_profil_sync_desc"), sync) { sync = it }
                if (peutSupprimer) TextButton(onClick = onSupprime, modifier = Modifier.padding(top = 6.dp)) {
                    Text(t("rd_profil_supprimer"), color = Rouge)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onEnregistre(p.copy(nom = nom.ifBlank { p.nom }, profil = StationReadiness.Profil(cat,
                    if (rotor) StationReadiness.Pointage.ROTOR else StationReadiness.Pointage.MANUEL, rec, sync),
                    poste = poste, boussole = boussole, audio = audio))
            }) { Text(t("save"), color = Cyan) }
        },
        dismissButton = { TextButton(onClick = onFerme) { Text(t("cancel"), color = TextLo) } })
}
