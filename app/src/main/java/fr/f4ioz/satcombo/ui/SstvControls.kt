/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.SstvHub
import fr.f4ioz.satcombo.sstv.SstvMode
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Les commandes de décodage SSTV : le mode imposé et le départ à la main.
 *
 * Elles apparaissent à deux endroits — dans la page SSTV et sous la boussole
 * de la page passage — et il serait fâcheux qu'elles s'y comportent
 * différemment, alors elles vivent ici.
 *
 * Pourquoi imposer un mode alors que l'en-tête VIS le dit ? Parce que
 * l'en-tête ne dure qu'une seconde et qu'il arrive au pire moment : quand le
 * satellite se lève, dans le bruit, avant que l'opérateur ait fini de pointer
 * l'antenne. Manqué, il ne revient pas — et il reste deux minutes de signal
 * parfaitement décodable dont SatMe ne saurait quoi faire. Sur l'ISS, où le
 * mode est annoncé à l'avance et ne change pas de la journée, l'imposer une
 * fois pour toutes est même la conduite normale.
 */
@Composable
fun SstvModeControls(
    st: SstvHub.SstvState,
    /** Version resserrée, pour la page passage sous la boussole. */
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val label = st.forcedMode ?: t("sstv_mode_auto")

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            OutlinedButton(
                onClick = { open = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(label, color = if (st.forcedMode != null) Amber else TextLo,
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.Bold)
                Icon(Icons.Default.ArrowDropDown, null, tint = TextLo,
                    modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(t("sstv_mode_auto")) },
                    onClick = { SstvHub.setForcedMode(ctx, null); open = false })
                SstvMode.ALL.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Text(m.name,
                                color = if (st.forcedMode == m.name) Amber else TextHi)
                        },
                        onClick = { SstvHub.setForcedMode(ctx, m.name); open = false })
                }
            }
        }

        // Départ forcé : sans mode imposé il n'y a rien à décoder, le mode ne
        // se devine pas d'un signal en cours. Le bouton reste visible mais
        // inerte, plutôt que de disparaître et laisser croire à un bogue.
        if (st.decoding) {
            OutlinedButton(
                onClick = { SstvHub.abortFrame() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Magenta)
            ) {
                Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(t("sstv_stop_frame"),
                    fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = { SstvHub.forceStart() },
                enabled = st.listening && st.forcedMode != null,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Aurora)
            ) {
                Icon(Icons.Default.Bolt, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(t("sstv_force_start"),
                    fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    // Ce qui a lâché, s'il y a lieu : un moteur mort derrière un écran qui
    // affiche « à l'écoute » est le pire des deux mondes.
    st.erreur?.let {
        Text(it, color = Amber, fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * La phrase d'état du décodeur, la même partout.
 *
 * Elle dit le mode dès qu'il est connu — c'est le premier renseignement que
 * l'opérateur cherche quand une image commence à se peindre de travers.
 */
fun sstvStatusLine(st: SstvHub.SstvState): String = when {
    st.modeName != null && st.decoding ->
        tf("sstv_receiving", st.modeName!!) + "  ·  " + (st.progress * 100).toInt() + " %"
    st.modeName != null -> tf("sstv_receiving", st.modeName!!)
    st.listening && st.forcedMode != null -> tf("sstv_listening_forced", st.forcedMode!!)
    st.listening -> t("sstv_listening")
    else -> t("sstv_idle")
}
