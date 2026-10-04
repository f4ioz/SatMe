/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
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
 * SSTV decode controls: forced mode and manual start. Shared by the SSTV page
 * and the pass page (under the compass) so both behave the same.
 *
 * Why force a mode when the VIS header gives it? The header lasts one second and
 * arrives at the worst moment — at AOS, in the noise, while still aiming. Missed,
 * it never comes back, leaving two minutes of decodable signal unusable. On the
 * ISS the mode is announced in advance and fixed for the day, so forcing it is
 * the normal practice.
 */
@Composable
fun SstvModeControls(
    st: SstvHub.SstvState,
    /** Compact form for the pass page, under the compass. */
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SstvHub.ensureLoaded(ctx) }
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

        // Manual start needs a forced mode: the mode cannot be guessed from a
        // signal already under way. The button stays visible but disabled
        // rather than vanishing and looking like a bug.
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
    // Continuous decoding: a header lost at AOS no longer costs the
    // picture. On by default since 20.78; the manual Start stays for those
    // who prefer to decide.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.toggleable(value = st.continu, role = Role.Checkbox,
            onValueChange = { SstvHub.setContinu(ctx, it) })
    ) {
        Checkbox(checked = st.continu, onCheckedChange = null)
        Column {
            Text(t("sstv_continu"), color = TextHi, fontSize = if (compact) 11.sp else 12.sp)
            if (!compact) Text(t("sstv_continu_desc"), color = TextLo, fontSize = 10.sp)
        }
    }
    // Picture cleaning: lines and dashes lost to noise, mended from their neighbours.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.toggleable(value = st.nettoyage, role = Role.Checkbox,
            onValueChange = { SstvHub.setNettoyage(ctx, it) })
    ) {
        Checkbox(checked = st.nettoyage, onCheckedChange = null)
        Column {
            Text(t("sstv_nettoyage"), color = TextHi, fontSize = if (compact) 11.sp else 12.sp)
            if (!compact) Text(t("sstv_nettoyage_desc"), color = TextLo, fontSize = 10.sp)
        }
    }
    // Show failures: a dead engine behind a "listening" label is the worst case.
    st.erreur?.let {
        Text(it, color = Amber, fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * Decoder status line, identical everywhere. States the mode as soon as it is
 * known: the first thing checked when an image starts skewing.
 */
fun sstvStatusLine(st: SstvHub.SstvState): String = when {
    st.modeName != null && st.decoding ->
        tf("sstv_receiving", st.modeName!!) + "  ·  " + (st.progress * 100).toInt() + " %"
    st.modeName != null -> tf("sstv_receiving", st.modeName!!)
    st.listening && st.forcedMode != null -> tf("sstv_listening_forced", st.forcedMode!!)
    st.listening && st.continu -> t("sstv_listening_continu")
    st.listening -> t("sstv_listening")
    else -> t("sstv_idle")
}
