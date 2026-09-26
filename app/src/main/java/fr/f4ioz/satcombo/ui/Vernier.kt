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
 * The vernier: a frequency dial scrolling under a fixed marker.
 *
 * Not a band map: only displacement counts, in hertz per centimetre of finger.
 * On a band map the resolution is screen width over band width — 1.4 kHz per dp
 * on QO-100, ten times too coarse for SSB. Here the resolution is **chosen**.
 * Bonus: the finger does not cover what you are looking at, as it does on a
 * waterfall.
 *
 * Fling is kept: without it, crossing 10 kHz at the fine ratio takes ~50 swipes.
 */
@Composable
fun Vernier(
    freqHz: Long,
    hzParCm: Int,
    onRapport: (Int) -> Unit,
    onDelta: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Short form for the SDR card on the pass page, where space is shared with
     * the compass, waterfall and switches. A third less height and no readout:
     * the frequency is already shown large just above.
     */
    compact: Boolean = false,
) {
    val hauteur = if (compact) 44.dp else 64.dp
    val densite = LocalDensity.current
    val dpi = densite.density * 160f
    val hzParPx = AccordFin.hzParPixel(hzParCm, dpi)

    // The remainder accumulator must outlive each drag event: at the fine ratio
    // a pixel is under one hertz, so resetting it would always yield zero.
    val aiguille = remember { AccordFin.Aiguille() }

    // Fling state: velocity and time at release.
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
            // Use the closed-form position, not velocity × dt: on an exponential
            // decay the Euler error becomes visible within half a fling.
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
                        // Touching down stops a running fling (the usual gesture
                        // to catch an overshoot).
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

                // Fixed centre marker, drawn over the dial.
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

            // The dial shows movement, the readout shows position: without it
            // the dial cannot be re-read after looking away.
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
