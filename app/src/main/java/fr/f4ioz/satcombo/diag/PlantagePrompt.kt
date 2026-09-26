/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
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
 * "SatMe stopped last time. Send the report?"
 *
 * One dismissable dialog, on the launch after the crash. The report goes by
 * email through the tester's own mail client: nothing leaves the phone unseen,
 * and they can add what the trace never contains — what they were doing.
 *
 * The file is deleted either way. A dialog that comes back on every launch
 * ends up closed unread, and the next, real report gets lost.
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
