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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.ui.theme.*
import kotlin.math.*

/**
 * Azimut et élévation, en une ligne, pour l'écran du clavier.
 *
 * La boussole de la page du passage prend la moitié d'un écran : ici, chaque
 * point de hauteur est disputé par le clavier, et l'écran ne défile pas. On
 * garde donc ce qui sert à pointer une antenne pendant qu'on écrit : la
 * direction, et la hauteur au-dessus de l'horizon.
 *
 * Un cadran de quatre centimètres pour l'azimut — un cercle se lit d'un coup
 * d'œil là où trois chiffres demandent à être interprétés — et une réglette
 * verticale pour l'élévation, qui est une hauteur et se lit donc de bas en
 * haut.
 */
@Composable
fun MireClavier(pos: SatPosition?, modifier: Modifier = Modifier) {
    if (pos == null) return
    val az = pos.azimuthDeg
    val el = pos.elevationDeg

    // Le cadran suit le téléphone, comme celui de la page du passage.
    //
    // L'aiguille reste dans l'axe de l'appareil et c'est le satellite qui
    // tourne autour : on vise en tournant sur soi-même jusqu'à superposer les
    // deux. Un cadran nord en haut obligerait à faire la conversion de tête,
    // ce que personne ne fait avec une antenne dans une main.
    val orient = rememberDeviceOrientation()
    val cap = orient.value.azimuthDeg.toDouble()
    val ecart = ((az - cap + 540.0) % 360.0) - 180.0
    val dansLAxe = orient.value.available && kotlin.math.abs(ecart) <= 10.0 && el >= 0
    // Un vert franc plutôt qu'un vert d'eau : ce signal se lit du coin
    // de l'œil, en plein soleil, une antenne dans une main.
    val teinte = if (dansLAxe) Color(0xFF00C853) else Cyan

    // Le fond passe au vert quand on est dans l'axe : c'est ce qu'on voit du
    // coin de l'œil en tournant, sans lire les chiffres.
    Row(
        modifier
            .background(
                if (dansLAxe) Color(0xFF00C853).copy(alpha = 0.34f) else Color.Transparent,
                RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        // ---- le cadran d'azimut ----
        Canvas(Modifier.size(52.dp)) {
            val r = size.minDimension / 2f - 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(SpaceSurface, r, c)
            drawCircle(TextLo.copy(alpha = 0.35f), r, c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
            // Le nord, seul repère nommé : le reste se déduit.
            drawCircle(TextLo, 1.6f, Offset(c.x, c.y - r + 3f))
            // L'axe du téléphone : le trait qu'on doit amener sur le satellite.
            if (orient.value.available) {
                drawLine(teinte.copy(alpha = 0.45f),
                    Offset(c.x, c.y), Offset(c.x, c.y - r + 3f), strokeWidth = 1.5f)
            }
            // L'aiguille : dans l'axe du téléphone, donc vers le haut. Le
            // satellite se place à son écart de cap.
            val a = Math.toRadians((if (orient.value.available) ecart else az) - 90.0)
            val bout = Offset(
                c.x + (r - 4f) * cos(a).toFloat(),
                c.y + (r - 4f) * sin(a).toFloat())
            drawLine(
                color = if (el >= 0) teinte else TextLo,
                start = c, end = bout, strokeWidth = 3f)
            drawCircle(if (el >= 0) teinte else TextLo, 2.5f, bout)
        }

        Spacer(Modifier.width(8.dp))
        Column {
            Text("%.0f°".format(az), color = TextHi, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("AZ", color = TextLo, fontSize = 10.sp)
        }

        Spacer(Modifier.width(14.dp))

        // ---- la réglette d'élévation ----
        //
        // De −10° à +90° : le passage se prépare avant l'horizon, et voir le
        // repère monter vers le trait de l'horizon vaut mieux que de le voir
        // apparaître d'un coup.
        Canvas(Modifier.width(10.dp).height(44.dp)) {
            val h = size.height
            drawRoundRect(color = SpaceSurface,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2))
            val part = ((el + 10.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
            val y = h - h * part
            // L'horizon, trait de référence.
            val yH = h - h * 0.1f
            drawLine(TextLo.copy(alpha = 0.5f),
                Offset(0f, yH), Offset(size.width, yH), strokeWidth = 1f)
            drawRect(
                color = if (el >= 0) teinte else Amber,
                topLeft = Offset(0f, y - 1.5f),
                size = Size(size.width, 3f))
        }

        Spacer(Modifier.width(8.dp))
        Column {
            Text("%.0f°".format(el),
                color = if (el >= 0) TextHi else Amber, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("ÉL", color = TextLo, fontSize = 10.sp)
        }
    }
}
