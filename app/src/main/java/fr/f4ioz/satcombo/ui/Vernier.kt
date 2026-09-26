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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.domain.AccordFin
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.Aurora
import fr.f4ioz.satcombo.ui.theme.Cyan
import fr.f4ioz.satcombo.ui.theme.Magenta
import fr.f4ioz.satcombo.ui.theme.SpaceBg
import fr.f4ioz.satcombo.ui.theme.SpaceSurface
import fr.f4ioz.satcombo.ui.theme.TextHi
import fr.f4ioz.satcombo.ui.theme.TextLo
import kotlin.math.abs

/**
 * Le vernier : un cadran de fréquences qui défile sous un repère fixe.
 *
 * Ce n'est pas une carte de bande. Rien n'y est à sa place absolue : seul le
 * déplacement compte, à raison de tant de hertz par centimètre de doigt. C'est
 * la différence qui fait tout — sur une carte, la précision est imposée par la
 * largeur de l'écran divisée par la largeur de la bande, et sur QO-100 cela
 * donne 1,4 kHz par dp, dix fois trop grossier pour de la BLU. Ici la précision
 * est **choisie**, et l'écran ne la contraint plus.
 *
 * Deuxième bénéfice, plus discret mais qui compte à l'usage : le doigt ne se
 * pose pas sur ce qu'on regarde. Sur une cascade, la main masque exactement le
 * signal qu'on essaie de viser.
 *
 * Le lancer est conservé — un geste rapide fait défiler puis s'arrête tout
 * seul. Sans lui, traverser dix kilohertz au rapport fin demanderait une
 * cinquantaine de glissements.
 */
@Composable
fun Vernier(
    freqHz: Long,
    hzParCm: Int,
    onRapport: (Int) -> Unit,
    onDelta: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Forme courte, pour la carte SDR de la page du passage.
     *
     * Là-bas, la place se dispute avec la boussole, la cascade et les
     * interrupteurs qu'on touche pendant le passage. Le cadran perd un tiers de
     * sa hauteur et le chiffre disparaît — il est déjà écrit en gros juste
     * au-dessus, et le répéter volerait la place à ce qui n'est écrit nulle part
     * ailleurs.
     */
    compact: Boolean = false,
) {
    val hauteur = if (compact) 44.dp else 64.dp
    val densite = LocalDensity.current
    val dpi = densite.density * 160f
    val hzParPx = AccordFin.hzParPixel(hzParCm, dpi)

    // L'accumulateur de restes vit aussi longtemps que le composable : au
    // rapport fin, un pixel vaut moins d'un hertz, et le remettre à zéro entre
    // deux événements de glissement rendrait zéro à chaque fois.
    val aiguille = remember { AccordFin.Aiguille() }

    // Le lancer, s'il y en a un : vitesse au lâcher et instant du lâcher.
    var vitesse by remember { mutableFloatStateOf(0f) }
    var lache by remember { mutableLongStateOf(0L) }

    LaunchedEffect(vitesse, lache) {
        if (vitesse == 0f) return@LaunchedEffect
        val v0 = vitesse
        val t0 = lache
        var precedent = 0.0
        while (true) {
            val t = (System.nanoTime() - t0) / 1e9
            val v = AccordFin.vitesseApres(v0, t)
            if (abs(v) < AccordFin.VITESSE_ARRET) break
            // On intègre la position plutôt que la vitesse : sur une
            // décroissance exponentielle, multiplier la vitesse courante par le
            // pas de temps accumule une erreur qui se voit au bout d'un demi
            // lancer.
            val parcouru = AccordFin.parcoursTotal(v0) * (1.0 - Math.exp(-t / AccordFin.TAU))
            val pas = aiguille.pousse((parcouru - precedent).toFloat(), hzParPx)
            precedent = parcouru
            if (pas != 0L) onDelta(pas)
            kotlinx.coroutines.delay(16)
        }
        vitesse = 0f
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(hauteur)
                .clip(RoundedCornerShape(10.dp))
                .background(SpaceSurface)
                .pointerInput(hzParCm) {
                    awaitEachGesture {
                        val bas = awaitFirstDown(requireUnconsumed = false)
                        // Un doigt qui se pose arrête le lancer en cours : c'est
                        // le geste universel, et sans lui on ne peut pas
                        // rattraper un lancer trop appuyé.
                        vitesse = 0f
                        aiguille.oublie()
                        var dernierX = bas.position.x
                        var dernierT = System.nanoTime()
                        var vInst = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val p = ev.changes.firstOrNull { it.id == bas.id } ?: break
                            if (!p.pressed) {
                                if (abs(vInst) >= AccordFin.VITESSE_ARRET) {
                                    vitesse = vInst
                                    lache = System.nanoTime()
                                }
                                break
                            }
                            val dx = p.position.x - dernierX
                            val maintenant = System.nanoTime()
                            val dt = (maintenant - dernierT) / 1e9
                            if (dt > 0) vInst = (dx / dt).toFloat()
                            dernierX = p.position.x
                            dernierT = maintenant
                            if (dx != 0f) {
                                val pas = aiguille.pousse(dx, hzParPx)
                                if (pas != 0L) onDelta(pas)
                                p.consume()
                            }
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxWidth().height(hauteur)) {
                val l = size.width
                val h = size.height
                val traits = AccordFin.traits(freqHz, hzParPx, l)

                traits.forEach { tr ->
                    val hauteur = if (tr.majeur) h * 0.45f else h * 0.22f
                    drawLine(
                        color = if (tr.majeur) TextHi.copy(alpha = 0.75f)
                        else TextLo.copy(alpha = 0.45f),
                        start = Offset(tr.xPixels, h - hauteur),
                        end = Offset(tr.xPixels, h),
                        strokeWidth = if (tr.majeur) 2f else 1f,
                    )
                }

                // Le repère fixe, au milieu, par-dessus le cadran.
                drawLine(
                    color = Magenta,
                    start = Offset(l / 2f, 0f),
                    end = Offset(l / 2f, h),
                    strokeWidth = 3f,
                )
                drawCircle(
                    color = Magenta,
                    radius = 5f,
                    center = Offset(l / 2f, 5f),
                    style = Stroke(width = 3f),
                )
            }

            // Le chiffre, dans le coin : le cadran dit le déplacement, ce texte
            // dit où l'on est. Les deux sont nécessaires — un cadran sans
            // chiffre ne se relit pas après avoir levé les yeux.
            if (!compact) {
                Text(
                    "%.3f".format(freqHz / 1_000_000.0),
                    color = Aurora,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("accord_ratio"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, end = 2.dp))
            AccordFin.RAPPORTS.forEach { r ->
                FilterChip(
                    selected = hzParCm == r,
                    onClick = { onRapport(r) },
                    label = { Text(libelleRapport(r), fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Cyan,
                        selectedLabelColor = SpaceBg),
                )
            }
        }
    }
}

private fun libelleRapport(hzParCm: Int): String =
    if (hzParCm >= 1_000) "${hzParCm / 1_000} kHz/cm" else "$hzParCm Hz/cm"
