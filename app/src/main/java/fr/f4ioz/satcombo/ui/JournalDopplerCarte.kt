/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.*

/** The Doppler/CAT log: its switch, and the last passes logged, each to share. */
@Composable
internal fun JournalDopplerCarte(vm: MainViewModel) {
    val ctx = LocalContext.current
    var actif by remember { mutableStateOf(vm.journalDopplerActif()) }
    val fichiers = remember(actif) { vm.journauxDoppler().take(6) }
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("doppler_log_section"), color = TextLo, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("doppler_log_switch"), color = TextHi, modifier = Modifier.weight(1f))
                Switch(checked = actif, onCheckedChange = { vm.setJournalDoppler(it); actif = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
            }
            Text(t("doppler_log_desc"), color = TextLo, fontSize = 11.sp)
            if (fichiers.isEmpty()) Text(t("doppler_log_vide"), color = TextLo, fontSize = 11.sp)
            fichiers.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, color = TextHi, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val uri = vm.partageDoppler(f) ?: return@IconButton
                        ctx.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, t("doppler_log_partager")))
                    }) { Icon(Icons.Default.Share, t("doppler_log_partager"), tint = Cyan) }
                }
            }
        }
    }
}
