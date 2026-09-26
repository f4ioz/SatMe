/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.ft8.Ft8Hub
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.*

/**
 * L'écoute FT8 et FT4.
 *
 * Rien n'est émis depuis cet écran, et ce n'est pas un oubli : décoder se
 * vérifie tout seul — on lit ce que d'autres envoient — tandis qu'émettre
 * engage l'indicatif de l'opérateur sur l'air. L'émission viendra quand la
 * réception aura fait ses preuves au terrain.
 */
@Composable
fun Ft8Screen(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val st by Ft8Hub.state.collectAsState()

    val demandeMicro = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { accorde ->
        // Un refus doit se voir : sinon le bouton semble ne rien faire.
        if (accorde) Ft8Hub.demarre(ctx, st.mode)
    }

    fun micOk() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    // Un appel général est ce qu'on cherche quand on veut contacter quelqu'un ;
    // le reste du trafic est du bavardage entre deux stations déjà en contact.
    var seulementCq by remember { mutableStateOf(false) }
    val visibles = remember(st.entendus, seulementCq) {
        if (seulementCq) st.entendus.filter { it.texte.startsWith("CQ") } else st.entendus
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {

        // --- le mode ---
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf("FT8", "FT4").forEach { m ->
                FilterChip(
                    selected = st.mode == m,
                    // Changer de mode en pleine écoute n'aurait pas de sens :
                    // les tranches n'ont pas la même durée, et l'on décoderait
                    // du FT4 dans une fenêtre de quinze secondes.
                    onClick = { Ft8Hub.choisitMode(ctx, m) },
                    label = { Text(m, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Cyan.copy(alpha = 0.25f),
                        selectedLabelColor = Cyan))
            }
            Spacer(Modifier.weight(1f))
            Text("${st.tranches} ${t("ft8_tranches")}", color = TextLo, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))

        // --- la marche ---
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (st.enMarche) Ft8Hub.arrete()
                    else if (micOk()) Ft8Hub.demarre(ctx, st.mode)
                    else demandeMicro.launch(Manifest.permission.RECORD_AUDIO)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (st.enMarche) Magenta else Cyan)
            ) { Text(if (st.enMarche) t("ft8_arreter") else t("ft8_ecouter")) }

            OutlinedButton(onClick = { Ft8Hub.vide() }) { Text(t("ft8_effacer")) }
        }

        // --- où l'on en est dans la tranche ---
        if (st.enMarche) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { st.avancement },
                color = Cyan, trackColor = SpaceSurface,
                modifier = Modifier.fillMaxWidth())
            Text(
                "${t("ft8_niveau")} ${Math.round(st.niveau * 100)} %",
                color = if (st.niveau < 0.01f) Amber else TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp))
            // Un micro muet ressemble à une bande vide : on le dit.
            if (st.niveau < 0.01f) {
                Text(t("ft8_muet"), color = Amber, fontSize = 11.sp)
            }
        }

        if (st.panne.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(messagePanne(st.panne), color = Magenta, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
        }

        // --- le rappel qui compte ---
        Spacer(Modifier.height(10.dp))
        Surface(color = Amber.copy(alpha = 0.13f), shape = RoundedCornerShape(8.dp)) {
            Text(t("ft8_limite"), color = Amber, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }

        // --- la cascade ---
        //
        // C'est ce qui manque le plus à une liste seule : voir la bande. Un
        // opérateur y lit d'un coup d'œil si le poste est accordé, si la bande
        // est chargée, et si le signal qu'il attend est bien là — trois
        // questions auxquelles une colonne de texte ne répond pas.
        if (st.cascade.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))

            // Le spectre de l'instant, au-dessus de l'histoire.
            //
            // Les deux ne servent pas à la même chose : la cascade dit qui a
            // émis et quand, le spectre dit ce qui entre **maintenant**. C'est
            // le second qu'on regarde en accordant le poste, quand rien ne
            // décode encore et que la cascade est vide.
            if (st.spectre.isNotEmpty()) {
                Canvas(Modifier.fillMaxWidth().height(46.dp)) {
                    val pas = size.width / st.spectre.size
                    for (c in st.spectre.indices) {
                        val h = (st.spectre[c].coerceIn(0f, 1f)) * size.height
                        drawRect(
                            color = Cyan.copy(alpha = 0.35f + 0.65f * st.spectre[c]),
                            topLeft = androidx.compose.ui.geometry.Offset(
                                c * pas, size.height - h),
                            size = androidx.compose.ui.geometry.Size(pas + 1f, h))
                    }
                }
            }

            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val lignes = st.cascade.size
                val hauteur = size.height / lignes
                st.cascade.forEachIndexed { i, ligne ->
                    val largeur = size.width / ligne.size
                    for (c in ligne.indices) {
                        val v = (ligne[c].toInt() and 0xFF) / 255f
                        drawRect(
                            color = teinteCascade(v),
                            topLeft = androidx.compose.ui.geometry.Offset(
                                c * largeur, i * hauteur),
                            size = androidx.compose.ui.geometry.Size(
                                largeur + 1f, hauteur + 1f))
                    }
                }
                // Les stations décodées de la dernière tranche, marquées sur
                // l'axe : c'est ce qui relie la cascade à la liste.
                st.entendus.take(12).forEach { e ->
                    val x = (e.frequenceHz - st.basseHz).toFloat() /
                        (st.hauteHz - st.basseHz) * size.width
                    if (x in 0f..size.width) {
                        drawRect(
                            color = if (e.texte.startsWith("CQ")) Amber else Cyan,
                            topLeft = androidx.compose.ui.geometry.Offset(x - 1f, 0f),
                            size = androidx.compose.ui.geometry.Size(2f, size.height * 0.18f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("${st.basseHz} Hz", color = TextLo, fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text("${st.hauteHz} Hz", color = TextLo, fontSize = 9.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- le filtre ---
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = seulementCq,
                onClick = { seulementCq = !seulementCq },
                label = { Text(t("ft8_cq_seuls"), fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Amber.copy(alpha = 0.25f),
                    selectedLabelColor = Amber))
            Text("${visibles.size} / ${st.entendus.size}", color = TextLo, fontSize = 11.sp)
        }

        Spacer(Modifier.height(8.dp))

        if (st.entendus.isEmpty()) {
            Text(
                if (st.enMarche) t("ft8_rien_encore") else t("ft8_pret"),
                color = TextLo, fontSize = 12.sp)
        }

        LazyColumn(Modifier.weight(1f)) {
            items(visibles) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(e.heure, color = TextLo, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%+3d".format(e.rapportDb),
                        color = when {
                            e.rapportDb >= 0 -> Color(0xFF2FB344)
                            e.rapportDb >= -12 -> Cyan
                            else -> Amber
                        },
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text("${e.frequenceHz}", color = TextLo, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(10.dp))
                    // Un appel général se repère d'un coup d'œil : c'est la
                    // seule ligne sur laquelle on peut agir.
                    val estCq = e.texte.startsWith("CQ")
                    Text(e.texte,
                        color = if (estCq) Amber else TextHi, fontSize = 13.sp,
                        fontWeight = if (estCq) FontWeight.Black else FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = SpaceSurface)
            }
        }
    }
}

/**
 * La teinte d'un point de cascade.
 *
 * Du noir au cyan puis au jaune : une échelle qui monte en luminosité autant
 * qu'en teinte, pour rester lisible en plein soleil comme de nuit — et pour
 * qu'un daltonien y voie encore quelque chose, la luminosité portant déjà
 * l'information.
 */
private fun teinteCascade(v: Float): Color = when {
    v < 0.35f -> Color(0xFF05080F).let {
        Color(red = 0.02f + v * 0.1f, green = 0.03f + v * 0.5f, blue = 0.06f + v * 0.7f)
    }
    v < 0.7f -> Color(red = 0.05f, green = 0.2f + (v - 0.35f) * 2f, blue = 0.3f + (v - 0.35f) * 1.4f)
    else -> Color(red = (v - 0.7f) * 3f, green = 0.9f, blue = 0.8f - (v - 0.7f) * 2.5f)
}

/** Chaque panne a son mot : un écran muet n'apprend rien. */
private fun messagePanne(quoi: String): String = when (quoi) {
    "permission" -> t("ft8_err_permission")
    "micro" -> t("ft8_err_micro")
    "occupe" -> t("ft8_err_occupe")
    "cadence" -> t("ft8_err_cadence")
    else -> "${t("ft8_err_autre")} ($quoi)"
}
