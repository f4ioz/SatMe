/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.audio.MoniteurAudio
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Le spectre du son en cours d'enregistrement, et l'interrupteur du contrôle
 * à l'oreille.
 *
 * La carte n'existe que pendant un enregistrement : hors enregistrement il n'y
 * a pas d'échantillons, et une carte vide sur la page du passage ne ferait que
 * prendre la place de la boussole.
 */
@Composable
fun MoniteurAudioCard(ui: UiState, vm: MainViewModel) {
    if (!ui.recording) return
    if (!ui.monitorSpectre && !ui.monitorSpeaker) return
    val etat by MoniteurAudio.etat.collectAsState()

    Surface(color = SpaceCard, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Aurora.copy(alpha = 0.20f), shape = RoundedCornerShape(6.dp)) {
                    Text(t("monitor_title"), color = Aurora, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
                // Le vumètre en chiffres : une crête qui colle à 1 est une
                // saturation, une crête qui ne décolle pas de 0 est un câble
                // débranché. Les deux se voient mieux ici qu'à l'oreille.
                Text(niveauTexte(etat.crete), color = teinteNiveau(etat.crete),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                // L'écoute au haut-parleur ne s'offre que sur une source
                // extérieure : sur le micro du téléphone elle ne donnerait
                // qu'un Larsen.
                if (ui.recorderSource != "MIC") {
                    IconButton(onClick = { vm.setMonitorSpeaker(!ui.monitorSpeaker) },
                        modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (ui.monitorSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            t("monitor_speaker"),
                            tint = if (ui.monitorSpeaker) Aurora else TextLo,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (ui.monitorSpectre) {
                Spacer(Modifier.height(6.dp))
                BarresSpectre(etat.bandes)
                Text(t("monitor_scale"), color = TextLo, fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (ui.recorderSource == "MIC" && ui.monitorSpeaker) {
                Text(t("monitor_speaker_mic"), color = Amber, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** « Crête −6 dB » plutôt qu'un nombre nu : c'est ce que lit un opérateur. */
private fun niveauTexte(crete: Float): String {
    if (crete <= 0.0005f) return "—"
    val db = 20.0 * kotlin.math.log10(crete.toDouble())
    return String.format(java.util.Locale.US, "%.0f dB", db)
}

private fun teinteNiveau(crete: Float): Color = when {
    crete >= 0.95f -> Magenta      // ça écrête
    crete >= 0.05f -> Aurora       // niveau utile
    else -> TextLo                 // rien n'entre
}

/**
 * Les barres. Dessinées à la main plutôt qu'empilées en composables : trente
 * barres redessinées quinze fois par seconde méritent un seul Canvas.
 */
@Composable
private fun BarresSpectre(bandes: List<Float>, hauteur: androidx.compose.ui.unit.Dp = 56.dp) {
    Canvas(Modifier.fillMaxWidth().height(hauteur)) {
        val n = bandes.size
        if (n == 0) return@Canvas
        val ecart = 1.5f
        val largeur = (size.width - ecart * (n - 1)) / n
        if (largeur <= 0f) return@Canvas
        for (i in 0 until n) {
            val v = bandes[i].coerceIn(0f, 1f)
            val h = (size.height * v).coerceAtLeast(1f)
            val x = i * (largeur + ecart)
            // Vert tant que le niveau est confortable, ambre puis magenta
            // quand il approche de l'écrêtage : la couleur dit tout de suite
            // s'il faut baisser le volume du poste.
            val c = when {
                v >= 0.92f -> Magenta
                v >= 0.75f -> Amber
                else -> Aurora
            }
            drawRect(color = c.copy(alpha = 0.25f + 0.75f * v),
                topLeft = Offset(x, size.height - h),
                size = Size(largeur, h))
        }
    }
}
