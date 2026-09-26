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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Drawing of the calibration pose to take.
 *
 * **Why draw.** Nine poses described in words are nine chances to misread —
 * and it happened: the edge aimed flat was not the one raised next, the two
 * pose sets described axes 107° apart, and no maths could reconcile them.
 *
 * The case is drawn as it is (WT9011DCL, 32 × 23 mm, logo band on top, round
 * button, LED bottom right) so it's recognised at once and held the right way.
 */

/** Real case proportions: 32.5 × 23.5 mm. */
private const val RAPPORT = 23.5f / 32.5f

/**
 * Draws the case face-on, centred on [cx], [cy], height [h]. [pivot] rotates
 * it in the drawing plane (polarisation poses).
 */
internal fun DrawScope.boitier(
    cx: Float, cy: Float, h: Float, pivot: Float = 0f, teinte: Color = Cyan
) {
    rotate(pivot, Offset(cx, cy)) {
        val l = h * RAPPORT
        val g = cx - l / 2f
        val t = cy - h / 2f
        // Black body, then the blue face — the recognisable part.
        drawRoundRect(Color(0xFF1B2430), Offset(g, t), Size(l, h),
            androidx.compose.ui.geometry.CornerRadius(h * 0.16f))
        drawRoundRect(teinte.copy(alpha = 0.55f),
            Offset(g + l * 0.10f, t + h * 0.07f),
            Size(l * 0.80f, h * 0.86f),
            androidx.compose.ui.geometry.CornerRadius(h * 0.10f))
        // Logo band: tells which edge is the top.
        drawRoundRect(teinte,
            Offset(g + l * 0.10f, t + h * 0.07f),
            Size(l * 0.80f, h * 0.30f),
            androidx.compose.ui.geometry.CornerRadius(h * 0.08f))
        drawCircle(teinte.copy(alpha = 0.9f), h * 0.10f,
            Offset(cx, t + h * 0.60f), style = Stroke(h * 0.035f))
        drawCircle(Color(0xFF2FB344), h * 0.045f,
            Offset(g + l * 0.76f, t + h * 0.80f))
    }
}

/**
 * The case seen **edge-on**, tilted by [pivot] degrees: 11 mm thick for 32 mm
 * long, a thin silhouette distinct from the face. A stripe on the blue-face
 * side shows which side is up.
 */
private fun DrawScope.tranche(
    cx: Float, cy: Float, lg: Float, pivot: Float = 0f, teinte: Color = Cyan
) {
    rotate(pivot, Offset(cx, cy)) {
        val ep = lg * (11.6f / 32.5f)
        val g = cx - lg / 2f
        val t = cy - ep / 2f
        drawRoundRect(Color(0xFF1B2430), Offset(g, t), Size(lg, ep),
            androidx.compose.ui.geometry.CornerRadius(ep * 0.35f))
        drawRoundRect(teinte.copy(alpha = 0.75f),
            Offset(g + lg * 0.04f, t + ep * 0.10f),
            Size(lg * 0.92f, ep * 0.26f),
            androidx.compose.ui.geometry.CornerRadius(ep * 0.12f))
        drawCircle(Color(0xFF2FB344), ep * 0.11f,
            Offset(g + lg * 0.82f, t + ep * 0.72f))
    }
}

/** Arrow from [cx],[cy] to [ex],[ey]. */
private fun DrawScope.fleche(cx: Float, cy: Float, ex: Float, ey: Float, teinte: Color) {
    drawLine(teinte, Offset(cx, cy), Offset(ex, ey), 5f)
    val dx = ex - cx; val dy = ey - cy
    val n = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val ux = dx / n; val uy = dy / n
    val p = Path().apply {
        moveTo(ex, ey)
        lineTo(ex - 14f * ux - 7f * uy, ey - 14f * uy + 7f * ux)
        lineTo(ex - 14f * ux + 7f * uy, ey - 14f * uy - 7f * ux)
        close()
    }
    drawPath(p, teinte)
}

/**
 * Drawing of a pose, by key. One viewpoint per kind, showing only what
 * matters: top view for flat poses (heading), front view for polarisation
 * (roll about the edge), side view for raised poses (tilt).
 */
@Composable
fun DessinPose(cle: String) {
    val mesureur = rememberTextMeasurer()
    val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Black,
        fontFamily = FontFamily.Monospace)

    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val h = size.height * 0.46f

        fun lettre(texte: String, x: Float, y: Float, teinte: Color) {
            val m = mesureur.measure(texte, style.copy(color = teinte))
            drawText(m, topLeft = Offset(x - m.size.width / 2f, y - m.size.height / 2f))
        }

        when (cle) {
            // --- top views: heading ---
            "plat_n", "plat_e", "plat_s", "plat_o" -> {
                val az = when (cle) {
                    "plat_e" -> 90f; "plat_s" -> 180f; "plat_o" -> 270f; else -> 0f
                }
                val r = size.height * 0.40f
                drawCircle(SpaceSurface, r, Offset(cx, cy))
                drawCircle(Cyan.copy(alpha = 0.25f), r, Offset(cx, cy), style = Stroke(2f))
                lettre("N", cx, cy - r - 10f, Magenta)
                lettre("E", cx + r + 12f, cy, TextLo)
                lettre("S", cx, cy + r + 10f, TextLo)
                lettre("O", cx - r - 12f, cy, TextLo)
                // From above the case is just a rectangle; the arrow carries
                // the edge direction.
                boitier(cx, cy, h * 0.75f, az)
                val a = Math.toRadians(az.toDouble())
                fleche(cx, cy,
                    cx + (r - 8f) * kotlin.math.sin(a).toFloat(),
                    cy - (r - 8f) * kotlin.math.cos(a).toFloat(), Amber)
            }
            // --- front views: polarisation ---
            "pol_h", "pol_a" -> {
                val sens = if (cle == "pol_h") 1f else -1f
                drawLine(TextLo.copy(alpha = 0.4f), Offset(cx - size.width * 0.3f, cy),
                    Offset(cx + size.width * 0.3f, cy), 2f)
                boitier(cx, cy, h, 90f * sens)
                // Arc showing which way to turn, seen from behind.
                val r = h * 0.85f
                drawArc(Amber, if (sens > 0) -70f else -110f, 180f * -sens, false,
                    Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(4f))
                val bout = Math.toRadians((if (sens > 0) 110.0 else 70.0))
                fleche(cx + r * 0.75f * kotlin.math.cos(bout).toFloat() * sens,
                    cy + r * 0.75f * kotlin.math.sin(bout).toFloat(),
                    cx + r * kotlin.math.cos(bout).toFloat() * sens,
                    cy + r * kotlin.math.sin(bout).toFloat(), Amber)
                lettre(if (sens > 0) "horaire" else "antihoraire", cx, cy + h * 0.95f, Amber)
            }
            // --- side views: elevation ---
            else -> {
                val angle = when (cle) {
                    "leve_bas" -> 30f; "leve_haut" -> 60f
                    "leve_est" -> 60f; "vertical" -> 90f; else -> 0f
                }
                val sol = cy + size.height * 0.30f
                drawLine(TextLo.copy(alpha = 0.5f), Offset(cx - size.width * 0.34f, sol),
                    Offset(cx + size.width * 0.34f, sol), 2f)
                val ox = cx - size.width * 0.14f
                // **Side view shows the edge, not the face.** Drawing the face
                // here was misleading; tilted in hand, the 11 × 32 mm edge
                // silhouette is what you recognise.
                tranche(ox, sol - h * 0.55f, h * 0.8f, -angle)
                val a = Math.toRadians((90f - angle).toDouble())
                val lg = size.width * 0.34f
                fleche(ox, sol - h * 0.55f,
                    ox + lg * kotlin.math.cos(a).toFloat(),
                    sol - h * 0.55f - lg * kotlin.math.sin(a).toFloat(), Amber)
                // Angle arc from the horizontal.
                val r = lg * 0.45f
                drawArc(Cyan.copy(alpha = 0.5f), -angle, angle, false,
                    Offset(ox - r, sol - h * 0.55f - r), Size(2 * r, 2 * r),
                    style = Stroke(3f))
                lettre("${angle.toInt()}°", ox + r * 0.75f, sol - h * 0.55f - r * 0.30f, Cyan)
                // The heading is the only difference between the two raised
                // poses, and it is what resolves the axis convention.
                lettre(if (cle == "leve_est") "vers l'EST" else "vers le NORD",
                    cx, sol + 16f, if (cle == "leve_est") Magenta else TextLo)
            }
        }
    }

}

/**
 * Module thumbnail, so the settings make clear this is a small WitMotion
 * Bluetooth case (WT901BLE / WT9011DCL), not the phone's compass. Reuses the calibration drawing:
 * a second copy would drift out of likeness.
 */
@Composable
fun MiniatureModule(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        boitier(size.width / 2f, size.height / 2f, size.height * 0.88f)
    }
}
