/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.domain.MasqueHorizon
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.*
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The horizon mask: obstacle elevation per azimuth sector (8 × 45° or
 * 12 × 30°), drawn from above, saved on demand.
 */
@Composable
internal fun HorizonReglage(actuel: MasqueHorizon, onSave: (MasqueHorizon) -> Unit) {
    var valeurs by remember(actuel) {
        mutableStateOf(if (actuel.elevationsDeg.isEmpty()) List(8) { 0.0 } else actuel.elevationsDeg)
    }
    var textes by remember(actuel) { mutableStateOf(valeurs.map { fmt(it) }) }
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("horizon_desc"), color = TextLo, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("horizon_sectors"), color = TextHi, modifier = Modifier.weight(1f))
                MasqueHorizon.SECTEURS.forEach { n ->
                    FilterChip(selected = valeurs.size == n, onClick = {
                        if (valeurs.size != n) { valeurs = List(n) { 0.0 }; textes = valeurs.map { fmt(it) } }
                    }, label = { Text("$n × ${360 / n}°") })
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                DessinHorizon(valeurs, Modifier.size(130.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val w = 360 / valeurs.size
                    valeurs.indices.chunked(2).forEach { paire ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            paire.forEach { i ->
                                OutlinedTextField(
                                    value = textes.getOrElse(i) { "0" },
                                    onValueChange = { s ->
                                        textes = textes.toMutableList().also { it[i] = s }
                                        val v = s.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 90.0) ?: 0.0
                                        valeurs = valeurs.toMutableList().also { it[i] = v }
                                    },
                                    label = { Text("${i * w}–${(i + 1) * w}°", fontSize = 10.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(MasqueHorizon(valeurs)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan)) { Text(t("horizon_save")) }
                OutlinedButton(onClick = {
                    valeurs = List(valeurs.size) { 0.0 }; textes = valeurs.map { fmt(it) }
                    onSave(MasqueHorizon.AUCUN)
                }) { Text(t("horizon_clear")) }
            }
        }
    }
}

private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

/** Seen from above: the outer ring is the horizon, the centre the zenith; obstacles in amber. */
@Composable
private fun DessinHorizon(valeurs: List<Double>, modifier: Modifier) {
    val ambre = Amber
    val trait = TextLo
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2 * 0.92f
        fun pt(azDeg: Double, rr: Float): Offset {
            val a = Math.toRadians(azDeg)
            return Offset(c.x + rr * sin(a).toFloat(), c.y - rr * cos(a).toFloat())
        }
        val p = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(androidx.compose.ui.geometry.Rect(c, r))
            val w = 360.0 / valeurs.size
            valeurs.forEachIndexed { i, e ->
                val rr = r * (1f - (e / 90.0).toFloat())
                for (k in 0..6) {
                    val q = pt(i * w + k * w / 6, rr)
                    if (i == 0 && k == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
                }
            }
            close()
        }
        drawPath(p, ambre.copy(alpha = 0.45f))
        drawCircle(trait, r, c, style = Stroke(1f))
        drawCircle(trait.copy(alpha = 0.5f), r * 2 / 3, c, style = Stroke(1f))
        drawCircle(trait.copy(alpha = 0.5f), r / 3, c, style = Stroke(1f))
        drawLine(trait.copy(alpha = 0.5f), pt(0.0, r), pt(180.0, r))
        drawLine(trait.copy(alpha = 0.5f), pt(90.0, r), pt(270.0, r))
    }
}
