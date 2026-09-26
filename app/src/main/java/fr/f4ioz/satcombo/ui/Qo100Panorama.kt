/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.ui.theme.Magenta
import fr.f4ioz.satcombo.ui.theme.SpaceSurface
import fr.f4ioz.satcombo.ui.theme.TextHi
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Panorama width in columns. 500 kHz / 512 ≈ 977 Hz per column. */
private const val PCOLS = 512

/** History depth: at one frame every 300 ms, half a minute. */
private const val PROWS = 80

/**
 * Marker for a column the dongle doesn't receive. Not a level but an absence:
 * it must neither count in scaling nor be painted as very low noise. It is
 * painted dark grey, read at once as "not looking here".
 */
private const val HORS = -999f

/** Grey for unreceived columns; light enough not to pass for black. */
private const val COULEUR_HORS = 0xFF202430.toInt()

/**
 * The QO-100 narrowband transponder as seen by the dongle, on the band-plan
 * ruler scale.
 *
 * **Why.** The normal waterfall shows a narrow window around the dongle tuning
 * that moves with the VFO — right for following a station, useless for
 * reading a transponder. Here the axis is **fixed**, in sky frequencies across
 * the band plan: beacons always sit in the same place and busy areas show at
 * a glance.
 *
 * **Why it works.** The dongle digitises 1 058 400 Hz (±529 200 Hz); the
 * narrowband plan spans 500 000 Hz. **Wherever the dongle is tuned inside the
 * transponder, the whole transponder stays in its window**: no sweep, no PLL
 * retune, no stitching — one FFT placed on an absolute axis. Worst-case margin
 * is 29.2 kHz and tuner edges roll off, so extreme columns may dim a bit when
 * tuned at a band end. Harmless.
 *
 * **Keeping the axis still.** Resampling happens **before** stacking into
 * history, not at draw time: each frame is projected with its own sky centre.
 * If the operator retunes or Doppler moves the PLL, older rows stay correct.
 *
 * @param pan raw dongle FFT, lowest to highest *index*.
 * @param centreCielHz sky frequency at the middle of [pan].
 * @param etendueCielHz covered width, **signed**: negative behind high-side
 *        injection, in which case the array runs backwards.
 * @param descenteHz working frequency, drawn as the cursor.
 */
@Composable
fun PanoramaQo100(
    pan: FloatArray,
    centreCielHz: Double,
    etendueCielHz: Double,
    descenteHz: Long,
    modifier: Modifier = Modifier,
    hauteur: Dp = 130.dp,
    onTune: (Long) -> Unit,
) {
    val history = remember { ArrayList<FloatArray>(PROWS) }
    var version by remember { mutableIntStateOf(0) }
    val pixels = remember { IntArray(PCOLS * PROWS) }
    val bitmap = remember { Bitmap.createBitmap(PCOLS, PROWS, Bitmap.Config.ARGB_8888) }
    val image: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    // Project each new frame onto the absolute axis, then stack it. The array
    // is new every time, so Compose's identity check triggers the effect.
    LaunchedEffect(pan) {
        val col = colonnes(pan, centreCielHz, etendueCielHz)
        if (col != null) {
            history.add(col)
            while (history.size > PROWS) history.removeAt(0)
            version++
        }
    }

    val bas = Qo100.REGLETTE_BAS_HZ
    val etendue = (Qo100.REGLETTE_HAUT_HZ - bas).toDouble()

    fun hz(x: Float, largeur: Int): Long {
        val r = (x / largeur.coerceAtLeast(1)).coerceIn(0f, 1f)
        return bas + (r * etendue).toLong()
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(hauteur)
            .clip(RoundedCornerShape(8.dp))
            .background(SpaceSurface)
            .pointerInput(Unit) {
                detectTapGestures { p -> onTune(hz(p.x, size.width)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onTune(hz(change.position.x, size.width))
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(hauteur)) {
            @Suppress("UNUSED_EXPRESSION") version
            if (history.isNotEmpty()) {
                peindre(history, pixels)
                bitmap.setPixels(pixels, 0, PCOLS, 0, 0, PCOLS, PROWS)
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(PCOLS, PROWS),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Low)
            }

            val l = size.width
            fun x(f: Long): Float = (((f - bas) / etendue) * l).toFloat()

            // Band-plan markers (beacons and reserved frequencies), aligned with
            // the ruler above. Visual calibration check: if a beacon line misses
            // its marker, the LNB has drifted.
            Qo100.SEGMENTS.forEach { s ->
                val r = s.repereHz ?: return@forEach
                drawLine(
                    color = Magenta.copy(alpha = 0.55f),
                    start = Offset(x(r), 0f),
                    end = Offset(x(r), size.height),
                    strokeWidth = 1.dp.toPx())
            }

            val xc = x(descenteHz).coerceIn(0f, l)
            drawLine(
                color = TextHi,
                start = Offset(xc, 0f),
                end = Offset(xc, size.height),
                strokeWidth = 2.dp.toPx())
            drawCircle(color = TextHi, radius = 3.dp.toPx(), center = Offset(xc, 3.dp.toPx()))
        }
    }
}

/**
 * Projects an FFT onto the [PCOLS] fixed ruler columns.
 *
 * Each column takes the **maximum** of its bins, never the mean: an SSB
 * signal fills two or three of the ~15 bins per column and averaging would
 * drown it in noise. Returns `null` when nothing is usable, so no empty row
 * gets stacked.
 */
private fun colonnes(pan: FloatArray, centreHz: Double, etendueHz: Double): FloatArray? {
    val n = pan.size
    if (n < 16 || etendueHz == 0.0 || centreHz <= 0.0) return null
    val hzParRaie = etendueHz / n
    val bas = Qo100.REGLETTE_BAS_HZ.toDouble()
    val haut = Qo100.REGLETTE_HAUT_HZ.toDouble()
    val out = FloatArray(PCOLS)
    var utiles = 0
    for (c in 0 until PCOLS) {
        val f0 = bas + (haut - bas) * c / PCOLS
        val f1 = bas + (haut - bas) * (c + 1) / PCOLS
        val i0 = n / 2.0 + (f0 - centreHz) / hzParRaie
        val i1 = n / 2.0 + (f1 - centreHz) / hzParRaie
        var a = floor(min(i0, i1)).toInt()
        var b = ceil(max(i0, i1)).toInt()
        if (b < 0 || a >= n) { out[c] = HORS; continue }
        a = a.coerceIn(0, n - 1)
        b = b.coerceIn(0, n - 1)
        var m = -160f
        for (k in a..b) if (pan[k] > m) m = pan[k]
        out[c] = m
        utiles++
    }
    // Mostly out of window: tuned elsewhere, the row would say nothing.
    return if (utiles < PCOLS / 8) null else out
}

/** Fills [pixels] from history, newest row on top. */
private fun peindre(history: List<FloatArray>, pixels: IntArray) {
    val rows = history.size
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (r in history) for (v in r) {
        if (v == HORS) continue
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    if (lo == Float.MAX_VALUE) lo = -120f
    // At least 20 dB of scale, or plain noise would paint as mountains and
    // look like a busy transponder.
    if (hi < lo + 20f) hi = lo + 20f
    val inv = 1f / (hi - lo)
    for (r in 0 until PROWS) {
        val base = r * PCOLS
        if (r >= rows) {
            java.util.Arrays.fill(pixels, base, base + PCOLS, 0xFF000000.toInt())
            continue
        }
        val c = history[rows - 1 - r]
        for (x in 0 until PCOLS) {
            val v = c[x]
            pixels[base + x] =
                if (v == HORS) COULEUR_HORS
                else heat(((v - lo) * inv).coerceIn(0f, 1f))
        }
    }
}
