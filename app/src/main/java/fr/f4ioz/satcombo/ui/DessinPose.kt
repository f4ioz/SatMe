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
 * Le dessin de la pose à prendre.
 *
 * **Pourquoi dessiner.** Neuf poses décrites en français, c'est neuf occasions
 * de comprendre autre chose que ce qui est demandé — et c'est arrivé : sur deux
 * relevés, l'arête visée à plat n'était pas celle qu'on a levée ensuite, les
 * deux jeux de poses décrivaient des axes à cent sept degrés l'un de l'autre,
 * et aucun calcul ne pouvait les concilier. Une phrase se relit de travers ;
 * une image, beaucoup moins.
 *
 * Le boîtier est dessiné tel qu'il est — un WT9011DCL, trente-deux millimètres
 * sur vingt-trois, bandeau du logo en haut, bouton rond, diode en bas à droite
 * — pour qu'on le reconnaisse au premier regard et qu'on sache dans quel sens
 * le tenir.
 */

/** Les proportions réelles du boîtier : 32,5 × 23,5 mm. */
private const val RAPPORT = 23.5f / 32.5f

/**
 * Dessine le boîtier vu de face, centré en [cx], [cy], de hauteur [h].
 *
 * [pivot] le fait tourner dans le plan du dessin, ce qui sert aux poses de
 * polarisation.
 */
internal fun DrawScope.boitier(
    cx: Float, cy: Float, h: Float, pivot: Float = 0f, teinte: Color = Cyan
) {
    rotate(pivot, Offset(cx, cy)) {
        val l = h * RAPPORT
        val g = cx - l / 2f
        val t = cy - h / 2f
        // Le corps noir, puis la face bleue : c'est cette face qu'on reconnaît.
        drawRoundRect(Color(0xFF1B2430), Offset(g, t), Size(l, h),
            androidx.compose.ui.geometry.CornerRadius(h * 0.16f))
        drawRoundRect(teinte.copy(alpha = 0.55f),
            Offset(g + l * 0.10f, t + h * 0.07f),
            Size(l * 0.80f, h * 0.86f),
            androidx.compose.ui.geometry.CornerRadius(h * 0.10f))
        // Le bandeau du logo, en haut : il dit quel bord est le haut.
        drawRoundRect(teinte,
            Offset(g + l * 0.10f, t + h * 0.07f),
            Size(l * 0.80f, h * 0.30f),
            androidx.compose.ui.geometry.CornerRadius(h * 0.08f))
        // Le bouton rond.
        drawCircle(teinte.copy(alpha = 0.9f), h * 0.10f,
            Offset(cx, t + h * 0.60f), style = Stroke(h * 0.035f))
        // La diode, en bas à droite.
        drawCircle(Color(0xFF2FB344), h * 0.045f,
            Offset(g + l * 0.76f, t + h * 0.80f))
    }
}

/**
 * Le boîtier vu **sur la tranche**, incliné de [pivot] degrés.
 *
 * Onze millimètres d'épaisseur pour trente-deux de long : une silhouette mince,
 * qui ne se confond pas avec la face. Le bandeau du logo est rendu par un
 * liseré sur le bord correspondant, pour qu'on sache de quel côté est le dessus.
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
        // Le liseré du côté de la face bleue : il dit où est le dessus.
        drawRoundRect(teinte.copy(alpha = 0.75f),
            Offset(g + lg * 0.04f, t + ep * 0.10f),
            Size(lg * 0.92f, ep * 0.26f),
            androidx.compose.ui.geometry.CornerRadius(ep * 0.12f))
        drawCircle(Color(0xFF2FB344), ep * 0.11f,
            Offset(g + lg * 0.82f, t + ep * 0.72f))
    }
}

/** Une flèche de [cx],[cy] vers [ex],[ey]. */
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
 * Le dessin d'une pose, identifiée par sa clé.
 *
 * Trois points de vue selon ce qu'il faut montrer : de dessus pour les poses à
 * plat, où seul le cap compte ; de face pour la polarisation, où seule la
 * rotation autour de l'arête compte ; de profil pour les poses levées, où seule
 * l'inclinaison compte. Montrer les trois à la fois n'aiderait personne.
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
            // --- vues de dessus : le cap ---
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
                // Le boîtier vu de dessus n'est qu'un rectangle : ce qui compte
                // est la direction de l'arête, donc la flèche.
                boitier(cx, cy, h * 0.75f, az)
                val a = Math.toRadians(az.toDouble())
                fleche(cx, cy,
                    cx + (r - 8f) * kotlin.math.sin(a).toFloat(),
                    cy - (r - 8f) * kotlin.math.cos(a).toFloat(), Amber)
            }
            // --- vues de face : la polarisation ---
            "pol_h", "pol_a" -> {
                val sens = if (cle == "pol_h") 1f else -1f
                drawLine(TextLo.copy(alpha = 0.4f), Offset(cx - size.width * 0.3f, cy),
                    Offset(cx + size.width * 0.3f, cy), 2f)
                boitier(cx, cy, h, 90f * sens)
                // L'arc qui dit dans quel sens tourner, vu de derrière.
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
            // --- vues de profil : l'élévation ---
            else -> {
                val angle = when (cle) {
                    "leve_bas" -> 30f; "leve_haut" -> 60f
                    "leve_est" -> 60f; "vertical" -> 90f; else -> 0f
                }
                val sol = cy + size.height * 0.30f
                drawLine(TextLo.copy(alpha = 0.5f), Offset(cx - size.width * 0.34f, sol),
                    Offset(cx + size.width * 0.34f, sol), 2f)
                val ox = cx - size.width * 0.14f
                // **De profil, on voit la tranche, pas la face.**
                //
                // Le dessin montrait le boîtier de face dans une vue de côté :
                // trompeur, et Olivier l'a relevé aussitôt. Vu sur la tranche,
                // un WT9011DCL fait onze millimètres d'épaisseur pour
                // trente-deux de long — c'est cette silhouette-là qu'on
                // reconnaît quand on le tient incliné.
                tranche(ox, sol - h * 0.55f, h * 0.8f, -angle)
                val a = Math.toRadians((90f - angle).toDouble())
                val lg = size.width * 0.34f
                fleche(ox, sol - h * 0.55f,
                    ox + lg * kotlin.math.cos(a).toFloat(),
                    sol - h * 0.55f - lg * kotlin.math.sin(a).toFloat(), Amber)
                // L'arc de l'angle, depuis l'horizontale.
                val r = lg * 0.45f
                drawArc(Cyan.copy(alpha = 0.5f), -angle, angle, false,
                    Offset(ox - r, sol - h * 0.55f - r), Size(2 * r, 2 * r),
                    style = Stroke(3f))
                lettre("${angle.toInt()}°", ox + r * 0.75f, sol - h * 0.55f - r * 0.30f, Cyan)
                // Le cap visé : c'est toute la différence entre les deux poses
                // levées, et c'est elle qui départage les conventions.
                lettre(if (cle == "leve_est") "vers l'EST" else "vers le NORD",
                    cx, sol + 16f, if (cle == "leve_est") Magenta else TextLo)
            }
        }
    }

}

/**
 * La miniature du module, pour qu'on le reconnaisse au premier regard.
 *
 * On voit tout de suite qu'il s'agit du petit boîtier Bluetooth WT901BLE, et
 * non de la boussole du téléphone : le bandeau bleu du logo, le bouton rond, la
 * diode verte. Un opérateur qui ouvre ce réglage sans avoir le module en main
 * saurait sinon difficilement de quoi on lui parle.
 *
 * Le dessin réemploie celui de la séquence de calibrage : un second aurait
 * fini par ne plus lui ressembler.
 */
@Composable
fun MiniatureModule(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        boitier(size.width / 2f, size.height / 2f, size.height * 0.88f)
    }
}
