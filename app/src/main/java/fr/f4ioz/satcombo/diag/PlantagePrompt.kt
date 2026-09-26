/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.diag

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.Amber
import fr.f4ioz.satcombo.ui.theme.SpaceCard
import fr.f4ioz.satcombo.ui.theme.TextHi
import fr.f4ioz.satcombo.ui.theme.TextLo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * « La dernière fois, SatMe s'est arrêté. Tu me l'envoies ? »
 *
 * Une seule fenêtre, au lancement qui suit la chute, refusable. Le rapport
 * part par courrier, avec le client de courrier du testeur : rien ne quitte le
 * téléphone sans qu'il l'ait vu et validé, et il peut écrire au-dessus la
 * seule chose que la trace ne contiendra jamais — ce qu'il était en train de
 * faire.
 *
 * Dans les deux cas le fichier est effacé. Une fenêtre qui revient à chaque
 * lancement finit par être fermée sans être lue, et l'on perd le rapport
 * suivant, le vrai.
 */
@Composable
fun PlantagePrompt() {
    val ctx = LocalContext.current
    var rapport by remember { mutableStateOf<String?>(null) }
    var montre by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        rapport = withContext(Dispatchers.IO) {
            runCatching { PlantageDisque.lit(ctx.filesDir) }.getOrNull()
        }
    }

    val r = rapport
    if (r == null || !montre) return

    val ferme = {
        montre = false
        runCatching { PlantageDisque.efface(ctx.filesDir) }
        Unit
    }

    AlertDialog(
        onDismissRequest = ferme,
        containerColor = SpaceCard,
        icon = { Icon(Icons.Default.BugReport, null, tint = Amber) },
        title = { Text(t("plantage_titre"), color = TextHi, fontWeight = FontWeight.Bold) },
        text = { Text(t("plantage_corps"), color = TextLo, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val i = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:${Plantage.DESTINATAIRE}")
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(Plantage.DESTINATAIRE))
                        putExtra(Intent.EXTRA_SUBJECT, Plantage.SUJET)
                        putExtra(Intent.EXTRA_TEXT, Plantage.corpsDuMail(r))
                    }
                    ctx.startActivity(Intent.createChooser(i, Plantage.SUJET))
                }
                ferme()
            }) {
                Text(t("plantage_envoyer"), color = Amber, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = ferme) {
                Text(t("plantage_jeter"), color = TextLo)
            }
        })
}
