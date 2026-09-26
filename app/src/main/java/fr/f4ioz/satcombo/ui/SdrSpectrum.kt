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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.f4ioz.satcombo.ui.theme.Aurora
import fr.f4ioz.satcombo.ui.theme.Magenta
import fr.f4ioz.satcombo.ui.theme.SpaceSurface
import kotlin.math.roundToInt

/** Waterfall rows kept: about ten seconds of history. */
private const val ROWS = 96

/** Waterfall horizontal resolution. Beyond this the screen cannot keep up. */
private const val COLS = 256

/**
 * Spectrum and waterfall, with tap-to-tune.
 *
 * The input covers the whole band digitised after the first stage — 176,400 Hz
 * — low to high, dongle frequency in the middle. Only the slice the operator
 * chose is shown: finding a station in 176 kHz is a fly in a hangar.
 *
 * The displayed window follows the tuning like a panadapter: it stays still
 * while the cursor is in view and slides when it nears the edge, never beyond
 * the digitised band. A tap therefore points at an absolute spot in the
 * spectrum, not an offset from a centre that may have moved — that was what
 * made tuning slippery.
 */
@Composable
fun SpectrumWaterfall(
    spectrum: FloatArray,
    fullSpanHz: Double,
    spanHz: Int,
    offsetHz: Int,
    bandwidthHz: Double,
    modifier: Modifier = Modifier,
    /** Spectrum trace height. 0.dp = no spectrum, waterfall only. */
    spectrumHeight: Dp = 76.dp,
    /** Waterfall height. */
    waterfallHeight: Dp = 120.dp,
    onTune: (Int) -> Unit
) {
    val history = remember { ArrayList<FloatArray>(ROWS) }
    var version by remember { mutableIntStateOf(0) }
    val pixels = remember { IntArray(COLS * ROWS) }
    val bitmap = remember { Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888) }
    // The Compose wrapper is created once and points at the same bitmap,
    // rewritten each frame. Creating an ImageBitmap in the draw phase would
    // allocate one object per frame for nothing.
    val image: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    // New frame: push and redraw. The array is new each time, so Compose's
    // identity check is enough to trigger.
    androidx.compose.runtime.LaunchedEffect(spectrum) {
        if (spectrum.isNotEmpty()) {
            history.add(spectrum)
            while (history.size > ROWS) history.removeAt(0)
            version++
        }
    }

    // Floor lowered from 4 kHz to 1 kHz: the fine-tuning magnifier opens
    // below that value, and the old floor silently widened it back to where
    // a sideband is no longer visible.
    val span = spanHz.coerceIn(1_000, fullSpanHz.toInt())
    // Display window centre: follows tuning but stays within the digitised
    // band, or we would be looking at nothing beyond ±88 kHz.
    val limit = ((fullSpanHz - span) / 2.0).coerceAtLeast(0.0)
    val viewCenter = offsetHz.toDouble().coerceIn(-limit, limit)

    Column(modifier) {
        // --------------------------------------------------------- spectrum
        // On the pass page there is no room for both: keep the waterfall, since
        // a rising carrier shows as a trace, not as a jumping needle.
        if (spectrumHeight > 0.dp) {
        Box(
            Modifier.fillMaxWidth().height(spectrumHeight)
                .clip(RoundedCornerShape(8.dp)).background(SpaceSurface)
        ) {
            Canvas(Modifier.fillMaxWidth().height(spectrumHeight)) {
                @Suppress("UNUSED_EXPRESSION") version
                val last = history.lastOrNull() ?: return@Canvas
                val cut = slice(last, span, fullSpanHz, viewCenter)
                if (cut.isEmpty()) return@Canvas
                val lo = floorOf(cut)
                val hi = ceilOf(cut, lo)
                val w = size.width
                val h = size.height
                var prevX = 0f
                var prevY = h
                for (c in cut.indices) {
                    val x = w * c / (cut.size - 1).coerceAtLeast(1)
                    val v = ((cut[c] - lo) / (hi - lo)).coerceIn(0f, 1f)
                    val y = h - v * h
                    if (c > 0) {
                        drawLine(Aurora, Offset(prevX, prevY), Offset(x, y), strokeWidth = 2f)
                    }
                    prevX = x; prevY = y
                }
                drawMarker(w, h, offsetHz, viewCenter, span, bandwidthHz)
            }
        }

        Spacer(Modifier.height(4.dp))
        }

        // --------------------------------------------------------- waterfall
        Box(
            Modifier.fillMaxWidth().height(waterfallHeight)
                .clip(RoundedCornerShape(8.dp)).background(SpaceSurface)
                .pointerInput(span, viewCenter) {
                    detectTapGestures { p ->
                        onTune((viewCenter + (p.x / size.width - 0.5f) * span).roundToInt())
                    }
                }
                .pointerInput(span, viewCenter) {
                    detectHorizontalDragGestures { change, _ ->
                        onTune(
                            (viewCenter +
                                (change.position.x / size.width - 0.5f) * span).roundToInt())
                    }
                }
        ) {
            Canvas(Modifier.fillMaxWidth().height(waterfallHeight)) {
                @Suppress("UNUSED_EXPRESSION") version
                if (history.isEmpty()) return@Canvas
                paint(history, span, fullSpanHz, viewCenter, pixels)
                bitmap.setPixels(pixels, 0, COLS, 0, 0, COLS, ROWS)
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(COLS, ROWS),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Low)
                drawMarker(size.width, size.height, offsetHz, viewCenter, span, bandwidthHz)
            }
        }
    }
}

/** Tuning line and channel passband, drawn on top. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarker(
    w: Float, h: Float, offsetHz: Int, viewCenterHz: Double, span: Int, bandwidthHz: Double
) {
    val cx = (w * (0.5 + (offsetHz - viewCenterHz) / span)).toFloat()
    if (cx.isNaN()) return
    val half = (w * (bandwidthHz / 2.0) / span).toFloat()
    if (half > 1f) {
        drawRect(
            color = Aurora.copy(alpha = 0.18f),
            topLeft = Offset(cx - half, 0f),
            size = Size(half * 2f, h))
    }
    drawLine(Magenta, Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
}

/** A [span] Hz slice centred on [centerHz] (Hz relative to tuning). */
private fun slice(full: FloatArray, span: Int, fullSpanHz: Double,
                  centerHz: Double = 0.0): FloatArray {
    val n = full.size
    if (n == 0) return FloatArray(0)
    val halfBins = ((span / fullSpanHz) * n / 2.0).roundToInt().coerceIn(8, n / 2)
    val mid = (n / 2.0 + centerHz / fullSpanHz * n).roundToInt()
        .coerceIn(halfBins, n - halfBins)
    val from = (mid - halfBins).coerceAtLeast(0)
    val to = (mid + halfBins).coerceAtMost(n)
    val take = to - from
    if (take <= 0) return FloatArray(0)
    // Reduced to COLS columns keeping each bucket's maximum: a narrow signal
    // must not vanish because it falls between two columns.
    val out = FloatArray(COLS)
    for (c in 0 until COLS) {
        val a = from + take * c / COLS
        val b = (from + take * (c + 1) / COLS).coerceAtMost(to)
        var m = -140f
        for (k in a until maxOf(b, a + 1)) if (k < n && full[k] > m) m = full[k]
        out[c] = m
    }
    return out
}

private fun floorOf(cut: FloatArray): Float {
    var min = Float.MAX_VALUE
    for (v in cut) if (v < min) min = v
    return min
}

private fun ceilOf(cut: FloatArray, lo: Float): Float {
    var max = -Float.MAX_VALUE
    for (v in cut) if (v > max) max = v
    // At least 20 dB of scale: otherwise noise alone would fill the screen
    // with mountains and look like heavy traffic.
    return maxOf(max, lo + 20f)
}

/** Fills [pixels] with the waterfall history, newest row on top. */
private fun paint(history: List<FloatArray>, span: Int, fullSpanHz: Double,
                  centerHz: Double, pixels: IntArray) {
    val rows = history.size
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    val cuts = ArrayList<FloatArray>(rows)
    for (r in 0 until rows) {
        val c = slice(history[rows - 1 - r], span, fullSpanHz, centerHz)
        cuts.add(c)
        for (v in c) { if (v < lo) lo = v; if (v > hi) hi = v }
    }
    if (hi < lo + 20f) hi = lo + 20f
    val inv = 1f / (hi - lo)
    for (r in 0 until ROWS) {
        val base = r * COLS
        if (r >= rows) {
            java.util.Arrays.fill(pixels, base, base + COLS, 0xFF000000.toInt())
            continue
        }
        val c = cuts[r]
        for (x in 0 until COLS) {
            pixels[base + x] = heat(((c[x] - lo) * inv).coerceIn(0f, 1f))
        }
    }
}

/**
 * Waterfall palette: black, blue, cyan, green, yellow, white. The eye follows
 * a trace better than a grey ramp, and white marks saturation.
 */
internal fun heat(v: Float): Int {
    val r: Int; val g: Int; val b: Int
    when {
        v < 0.25f -> { val t = v / 0.25f; r = 0; g = 0; b = (60 + 160 * t).toInt() }
        v < 0.45f -> { val t = (v - 0.25f) / 0.20f; r = 0; g = (200 * t).toInt(); b = 220 }
        v < 0.65f -> { val t = (v - 0.45f) / 0.20f; r = (60 * t).toInt(); g = 220; b = (220 - 200 * t).toInt() }
        v < 0.85f -> { val t = (v - 0.65f) / 0.20f; r = (60 + 195 * t).toInt(); g = 220; b = 20 }
        else -> { val t = (v - 0.85f) / 0.15f; r = 255; g = (220 + 35 * t).toInt(); b = (20 + 235 * t).toInt() }
    }
    return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
        (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}

/** Accent colour, exposed so the screen keeps the same palette. */
val SpectrumTrace: Color get() = Aurora
