/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.Amber
import fr.f4ioz.satcombo.ui.theme.Cyan
import fr.f4ioz.satcombo.ui.theme.SpaceCard
import fr.f4ioz.satcombo.ui.theme.TextHi
import fr.f4ioz.satcombo.ui.theme.TextLo

/**
 * Un seul moment, une seule fenêtre, refusable : « il y a du neuf sur le
 * Store ». Un bouton pour y aller, un pour plus tard, et c'est tout.
 *
 * Il y en avait deux avant — une pour lancer le téléchargement, une pour
 * réclamer le redémarrage — et c'est la seconde qui coinçait. Une fenêtre qui
 * demande de redémarrer alors que rien ne se termine au redémarrage est pire
 * qu'aucune fenêtre du tout.
 *
 * Le bandeau du bas reste, mais il n'a plus de barre de progression : il ne
 * sert qu'à porter un message quand quelque chose n'a pas marché.
 */
@Composable
fun UpdatePrompt(vm: MainViewModel, updater: PlayUpdater) {
    val ui by vm.ui.collectAsState()

    UpdateBanner(vm)

    if (ui.updateCode <= 0) return
    AlertDialog(
        onDismissRequest = { vm.updateLater() },
        containerColor = SpaceCard,
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = Cyan) },
        title = { Text(t("update_title"), color = TextHi, fontWeight = FontWeight.Bold) },
        text = { Text(t("update_body"), color = TextLo, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = { updater.openStore() }) {
                Text(t("update_now"), color = Cyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.updateLater() }) {
                Text(t("update_later"), color = TextLo)
            }
        })
}

/**
 * Bande d'état en bas de l'écran, qui ne bloque rien : la raison pour laquelle
 * la mise à jour n'a pas pu se faire. Le message reste tant qu'on ne l'a pas
 * touché — une erreur qui disparaît toute seule n'a pas été lue.
 */
@Composable
private fun UpdateBanner(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    if (ui.updateMsg.isEmpty()) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = SpaceCard,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .fillMaxWidth()
                .clickable { vm.updateMsgClear() }
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(ui.updateMsg, color = Amber, fontSize = 13.sp)
                Text(t("tap_to_dismiss"), color = TextLo, fontSize = 10.sp)
            }
        }
    }
}
