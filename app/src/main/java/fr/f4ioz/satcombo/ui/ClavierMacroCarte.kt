/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.domain.MoletteUsb
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Le mini clavier de commande : molette et trois touches.
 *
 * **Pourquoi une section à part.** Ces réglages vivaient au milieu du clavier
 * express, où personne ne pouvait les deviner — Olivier les a cherchés et ne
 * les a pas trouvés. Un boîtier physique se règle là où on le cherche : sous
 * son propre nom.
 *
 * **Pourquoi un schéma.** Trois touches noires identiques ne se distinguent que
 * par leur place. Une liste « A, B, C » oblige l'opérateur à retenir laquelle
 * est laquelle ; un dessin le lui montre, et il reflète ses propres réglages —
 * ce qu'une photo ne saurait pas faire.
 */
@Composable
fun ClavierMacroCarte(ui: UiState, vm: MainViewModel) {
    val a = ui.accord
    val enApprentissage by vm.apprentissage.collectAsState()

    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {

            Text(t("macro_titre"), color = TextHi, fontWeight = FontWeight.Bold)
            Text(t("macro_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

            // --- le schéma du boîtier ---
            //
            // Disposé comme le matériel : la molette à gauche, les trois
            // touches à sa droite. Un schéma qui ne correspondrait pas à
            // l'objet qu'on a sous les yeux serait pire que pas de schéma.
            Surface(color = SpaceSurface, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(54.dp).clip(CircleShape)
                                .background(SpaceCard)
                                .border(2.dp, Cyan, CircleShape),
                            Alignment.Center
                        ) { Text("↻", color = Cyan, fontSize = 22.sp) }
                        Text(
                            t("macro_molette") + " · " + when (a.macroActionD) {
                                "CIBLE" -> t("macro_act_cible")
                                "ZERO" -> t("macro_act_zero")
                                else -> t("macro_act_pas")
                            },
                            color = TextLo, fontSize = 9.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp).width(58.dp))
                    }

                    listOf(0, 1, 2).forEach { rang ->
                        val code = when (rang) {
                            0 -> a.macroCodeA; 1 -> a.macroCodeB; else -> a.macroCodeC
                        }
                        val cible = when (rang) {
                            0 -> a.macroCibleA; 1 -> a.macroCibleB; else -> a.macroCibleC
                        }
                        val enCours = enApprentissage == rang
                        val teinte = when {
                            enCours -> Amber
                            code == 0 -> TextLo          // pas encore apprise
                            else -> Cyan
                        }
                        Column(Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.fillMaxWidth().height(54.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SpaceCard)
                                    .border(2.dp, teinte, RoundedCornerShape(8.dp)),
                                Alignment.Center
                            ) {
                                Text("${'A' + rang}", color = teinte, fontSize = 18.sp,
                                    fontWeight = FontWeight.Black)
                            }
                            Text(nomCibleMacro(cible), color = teinte, fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp))
                            // Le code appris, ou son absence — dite, pas tue.
                            Text(
                                if (code == 0) t("macro_vide") else "#$code",
                                color = TextLo, fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            // --- l'interrupteur maître ---
            //
            // Fermé par défaut, et il le reste : ces boîtiers envoient des
            // touches de volume, et qui n'en a pas ne doit rien perdre.
            Spacer(Modifier.height(12.dp))
            // L'interrupteur est écrit ici plutôt que repris de l'écran des
            // réglages : celui-là y est privé, et l'ouvrir pour un seul appel
            // élargirait sa portée sans nécessité.
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(t("molette"), color = TextHi, fontSize = 13.sp)
                    Text(t("molette_desc"), color = TextLo, fontSize = 11.sp)
                }
                Switch(checked = ui.moletteVfo, onCheckedChange = vm::setMoletteVfo,
                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
            }

            if (!ui.moletteVfo) {
                Text(t("macro_ferme"), color = Amber, fontSize = 11.sp)
                return@Column
            }

            // --- le pas ---
            Text(t("macro_pas"), color = TextHi, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                MoletteUsb.PAS.forEach { pas ->
                    FilterChip(selected = ui.molettePasHz == pas,
                        onClick = { vm.setMolettePas(pas) },
                        label = { Text("$pas Hz", fontSize = 11.sp) })
                }
            }
            Text(t("macro_pas_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp))

            // --- les trois touches ---
            HorizontalDivider(color = SpaceSurface,
                modifier = Modifier.padding(vertical = 8.dp))

            listOf(
                Triple(0, a.macroCodeA, a.macroCibleA),
                Triple(1, a.macroCodeB, a.macroCibleB),
                Triple(2, a.macroCodeC, a.macroCibleC)
            ).forEach { (rang, code, cible) ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${'A' + rang}", color = TextHi, fontSize = 13.sp,
                        fontWeight = FontWeight.Black, modifier = Modifier.width(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)) {
                        listOf("SHIFT_RX" to t("macro_rx"),
                               "SHIFT_TX" to t("macro_tx"),
                               "VFO" to t("macro_vfo")).forEach { (id, nom) ->
                            FilterChip(selected = cible == id,
                                onClick = { vm.setMacroCible(rang, id) },
                                label = { Text(nom, fontSize = 10.sp) })
                        }
                    }
                    TextButton(onClick = {
                        if (enApprentissage == rang) vm.annuleApprentissage()
                        else vm.debuteApprentissage(rang)
                    }) {
                        Text(
                            when {
                                enApprentissage == rang -> t("macro_appuie")
                                code == 0 -> t("macro_apprendre")
                                else -> "#$code"
                            },
                            color = if (enApprentissage == rang) Amber else Cyan,
                            fontSize = 11.sp)
                    }
                }
            }

            // --- le poussoir de la molette ---
            HorizontalDivider(color = SpaceSurface,
                modifier = Modifier.padding(vertical = 8.dp))
            Text(t("macro_clic"), color = TextHi, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Text(t("macro_clic_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("↻", color = TextHi, fontSize = 15.sp,
                    modifier = Modifier.width(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)) {
                    listOf("PAS" to t("macro_act_pas"),
                           "CIBLE" to t("macro_act_cible"),
                           "ZERO" to t("macro_act_zero")).forEach { (id, nom) ->
                        FilterChip(selected = a.macroActionD == id,
                            onClick = { vm.setMacroAction(id) },
                            label = { Text(nom, fontSize = 10.sp) })
                    }
                }
                TextButton(onClick = {
                    if (enApprentissage == 3) vm.annuleApprentissage()
                    else vm.debuteApprentissage(3)
                }) {
                    Text(
                        when {
                            enApprentissage == 3 -> t("macro_appuie")
                            a.macroCodeD == 0 -> t("macro_apprendre")
                            else -> "#${a.macroCodeD}"
                        },
                        color = if (enApprentissage == 3) Amber else Cyan, fontSize = 11.sp)
                }
            }

            if (enApprentissage != null) {
                Text(t("macro_attente"), color = Amber, fontSize = 11.sp)
            }
            Text(tf("macro_cible_courante", nomCibleMacro(a.moletteCible)),
                color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp))

            // Le piège qui fait croire à une panne de matériel : une touche
            // programmée sur « volume » serait prise pour un sélecteur de
            // cible, et la molette cesserait de tourner le VFO.
            Spacer(Modifier.height(10.dp))
            Surface(color = Amber.copy(alpha = 0.13f), shape = RoundedCornerShape(8.dp)) {
                Text(t("macro_piege"), color = Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

private fun nomCibleMacro(id: String): String = when (id) {
    "SHIFT_RX" -> t("macro_rx")
    "SHIFT_TX" -> t("macro_tx")
    else -> t("macro_vfo")
}
