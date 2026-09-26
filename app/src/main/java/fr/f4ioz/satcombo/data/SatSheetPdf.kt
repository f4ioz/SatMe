/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/**
 * Printable pass sheet: ONE full-width row per pass, 6 rows per A4 page. Each
 * row has a small polar plot on the left and the pass + radio info spread out
 * across the full remaining width (no clipped text). One header per page.
 */
object SatSheetPdf {

    private const val INK = 0xFF1E293B.toInt()
    private const val MUTE = 0xFF64748B.toInt()
    private const val BLUE = 0xFF1565C0.toInt()
    private const val CYAN = 0xFF0E7C86.toInt()
    private const val MAGENTA = 0xFFB02356.toInt()
    private const val CARD = 0xFFF1F5F9.toInt()
    private const val LINE = 0xFFD5DCE6.toInt()
    private const val NAVY = 0xFF0B2545.toInt()

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 30f
    private const val HEADER_H = 64f
    private const val ROWS = 6
    private const val GAP = 10f

    data class PassSheet(
        val pass: SatPass,
        val sat: TleEntry,
        val transmitter: Transmitter?,
        val config: SatConfig?
    )

    fun build(context: Context, sheets: List<PassSheet>, observer: Observer?,
              useUtc: Boolean = false): File {
        val doc = PdfDocument()
        // Print the times in the timezone the operator works in (Settings →
        // UTC/local), day and hour together, and say so on every row.
        val tz = if (useUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val gen = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).apply { timeZone = tz }
        val dLong = SimpleDateFormat("EEE dd/MM HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }
        val locator = observer?.name?.substringAfterLast("· ")?.trim()?.ifEmpty { null }
            ?: observer?.name

        val rowW = PAGE_W - 2 * MARGIN
        val areaH = PAGE_H - HEADER_H - MARGIN - 22f
        val rowH = (areaH - (ROWS - 1) * GAP) / ROWS
        val perPage = ROWS

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var c: Canvas? = null

        fun startPage() {
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            c = page!!.canvas
            drawPageHeader(c!!, locator, gen)
            val foot = Paint().apply { color = MUTE; textSize = 8f; isAntiAlias = true }
            c!!.drawText("SatMe · 73 de F4IOZ", MARGIN, PAGE_H - 12f, foot)
            locator?.let {
                val s = "QTH $it"
                c!!.drawText(s, PAGE_W - MARGIN - foot.measureText(s), PAGE_H - 12f, foot)
            }
        }

        sheets.forEachIndexed { i, sheet ->
            val slot = i % perPage
            if (slot == 0) {
                page?.let { doc.finishPage(it) }
                startPage()
            }
            val y = HEADER_H + 6f + slot * (rowH + GAP)
            drawPassRow(c!!, MARGIN, y, rowW, rowH, sheet, dLong, useUtc)
        }
        page?.let { doc.finishPage(it) }

        val dir = File(context.cacheDir, "skeds").apply { mkdirs() }
        val out = File(dir, "SatMe-passages.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun drawPageHeader(c: Canvas, locator: String?, gen: SimpleDateFormat) {
        c.drawRect(0f, 0f, PAGE_W.toFloat(), HEADER_H, Paint().apply { color = NAVY; isAntiAlias = true })
        val star = Paint().apply { color = 0x44FFFFFF; isAntiAlias = true }
        for (i in 0 until 24) {
            c.drawCircle(((i * 8731) % PAGE_W).toFloat(), ((i * 5279) % HEADER_H.toInt()).toFloat(),
                (i % 3) * 0.5f + 0.5f, star)
        }
        drawSatelliteClipart(c, PAGE_W - 54f, HEADER_H / 2, 20f)
        c.drawText("SatMe — Passages", MARGIN, 30f, Paint().apply {
            color = Color.WHITE; textSize = 19f; isFakeBoldText = true; isAntiAlias = true
        })
        val sub = Paint().apply { color = 0xFFBBD4F0.toInt(); textSize = 9f; isAntiAlias = true }
        val line = buildString {
            append("Généré le ${gen.format(Date())}")
            if (locator != null) append("   ·   QTH $locator")
        }
        c.drawText(line, MARGIN, 48f, sub)
    }

    private fun drawSatelliteClipart(c: Canvas, cx: Float, cy: Float, s: Float) {
        val body = Paint().apply { color = 0xFFFFD166.toInt(); isAntiAlias = true }
        val panel = Paint().apply { color = 0xFF4CC9F0.toInt(); isAntiAlias = true }
        val panelLine = Paint().apply {
            color = 0xFF1B6CA8.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true
        }
        val metal = Paint().apply { color = 0xFFE0E0E0.toInt(); isAntiAlias = true }
        val stroke = Paint().apply {
            color = NAVY; style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true
        }
        val b = RectF(cx - s * 0.35f, cy - s * 0.5f, cx + s * 0.35f, cy + s * 0.5f)
        c.drawRoundRect(b, 3f, 3f, body); c.drawRoundRect(b, 3f, 3f, stroke)
        for (side in intArrayOf(-1, 1)) {
            val p = RectF(
                cx + side * s * 0.35f - (if (side < 0) s * 0.9f else 0f), cy - s * 0.32f,
                cx + side * s * 0.35f + (if (side > 0) s * 0.9f else 0f), cy + s * 0.32f
            )
            c.drawRect(p, panel); c.drawRect(p, stroke)
            c.drawLine(p.left, cy, p.right, cy, panelLine)
        }
        c.drawCircle(cx, cy - s * 0.62f, s * 0.18f, metal)
        c.drawCircle(cx, cy - s * 0.62f, s * 0.18f, stroke)
    }

    // --- One full-width pass row ---------------------------------------------

    private fun drawPassRow(
        c: Canvas, x: Float, y: Float, w: Float, h: Float,
        sheet: PassSheet, dfmt: SimpleDateFormat, useUtc: Boolean = false
    ) {
        val bg = Paint().apply { color = 0xFFFFFFFF.toInt(); isAntiAlias = true }
        val border = Paint().apply {
            color = LINE; style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true
        }
        c.drawRoundRect(RectF(x, y, x + w, y + h), 8f, 8f, bg)
        c.drawRoundRect(RectF(x, y, x + w, y + h), 8f, 8f, border)

        val sat = sheet.sat
        val p = sheet.pass

        // Small polar plot on the left (square, fits row height).
        val plot = h - 12f
        drawPolar(c, x + 6f, y + 6f, plot, p)

        // Text area: everything to the right of the plot, full remaining width.
        val tx0 = x + 6f + plot + 12f
        val tw = x + w - 12f - tx0

        // Row 1: satellite name (left) + max elevation (right of text area)
        val name = Paint().apply { color = INK; textSize = 15f; isFakeBoldText = true; isAntiAlias = true }
        c.drawText(sat.name, tx0, y + 18f, name)
        val elTag = Paint().apply { color = CYAN; textSize = 13f; isFakeBoldText = true; isAntiAlias = true }
        val elStr = "ÉlMax ${p.maxElevationDeg.toInt()}°" + if (p.visualPass) "  👁" else ""
        c.drawText(elStr, tx0 + tw - elTag.measureText(elStr), y + 18f, elTag)
        c.drawLine(tx0, y + 24f, tx0 + tw, y + 24f, border)

        // Three columns across the full width: timing | RX/TX | mode/tone.
        // Column widths sum to < 1 and the 3rd is clipped so text never spills.
        val colGap = 12f
        val col1 = tw * 0.32f
        val col2 = tw * 0.30f
        val cx1 = tx0
        val cx2 = tx0 + col1 + colGap
        val cx3 = tx0 + col1 + col2 + 2 * colGap
        val col3 = tx0 + tw - cx3   // remaining width for column 3

        val label = Paint().apply { color = MUTE; textSize = 7.5f; isAntiAlias = true }
        val value = Paint().apply { color = INK; textSize = 9f; isFakeBoldText = true; isAntiAlias = true }
        val mono = Paint().apply {
            color = INK; textSize = 8.5f; isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE
        }

        var yy = y + 36f
        // Column 1: AOS/LOS timing
        c.drawText("AOS → LOS (" + (if (useUtc) "UTC" else "local") + ")", cx1, yy, label)
        c.drawText(dfmt.format(Date(p.aosEpochMs)), cx1, yy + 11f, mono)
        c.drawText("Az ${p.aosAzimuthDeg.toInt()}°→${p.losAzimuthDeg.toInt()}° · ${p.durationSec}s",
            cx1, yy + 23f, mono)

        // Column 2: RX / TX
        val tx = sheet.transmitter
        c.drawText("RX ↓ · TX ↑ (MHz)", cx2, yy, label)
        if (tx != null) {
            c.drawText("↓ " + rangeMHz(tx.downlinkLowHz, tx.downlinkHighHz), cx2, yy + 11f, mono)
            c.drawText("↑ " + rangeMHz(tx.uplinkLowHz, tx.uplinkHighHz), cx2, yy + 23f, mono)
        } else {
            c.drawText("↓ " + (sat.downlinkHz?.let { fmtMHz(it) } ?: "—"), cx2, yy + 11f, mono)
            c.drawText("↑ " + (sat.uplinkHz?.let { fmtMHz(it) } ?: "—"), cx2, yy + 23f, mono)
        }

        // Column 3: mode + bandwidth/CTCSS + transponder name (wrapped)
        c.drawText("Transpondeur", cx3, yy, label)
        if (tx != null) {
            val modeBw = when {
                tx.isTransponder && tx.downlinkLowHz != null && tx.downlinkHighHz != null ->
                    "%.0f kHz · ${if (tx.invert) "REV" else "NOR"} · ${tx.mode ?: ""}".format(
                        (tx.downlinkHighHz - tx.downlinkLowHz) / 1000.0)
                else -> {
                    val ct = Regex("""CTCSS\s*([0-9.]+)""").find(tx.description)?.groupValues?.get(1)
                    (tx.mode ?: "FM") + (if (ct != null) " · CTCSS $ct Hz" else " · simplex")
                }
            }
            c.drawText(ellipsize(modeBw, value, col3), cx3, yy + 11f, value)
            // Wrap the transponder description over up to 2 lines.
            val descPaint = Paint(label).apply { textSize = 8f }
            val lines = wrap(tx.description, descPaint, col3, maxLines = 2)
            lines.forEachIndexed { li, ln ->
                c.drawText(ln, cx3, yy + 23f + li * 10f, descPaint)
            }
        } else {
            c.drawText(ellipsize(sat.mode ?: "—", value, col3), cx3, yy + 11f, value)
        }
    }

    /** Word-wrap text to fit maxWidth, capped at maxLines (last line ellipsized). */
    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(candidate) <= maxWidth) {
                cur = StringBuilder(candidate)
            } else {
                if (cur.isNotEmpty()) lines.add(cur.toString())
                cur = StringBuilder(w)
                if (lines.size == maxLines - 1) break
            }
        }
        if (cur.isNotEmpty() && lines.size < maxLines) lines.add(cur.toString())
        // If we ran out of lines with words left, ellipsize the last one.
        if (lines.size == maxLines) {
            val consumed = lines.joinToString(" ")
            if (consumed.length < text.length) {
                lines[maxLines - 1] = ellipsize(lines[maxLines - 1] + " …", paint, maxWidth)
            }
        }
        return lines
    }

    /** Trim text with an ellipsis so it fits within maxWidth for the paint. */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun drawPolar(c: Canvas, x: Float, y: Float, size: Float, pass: SatPass) {
        val cx = x + size / 2; val cy = y + size / 2; val r = size / 2 - 9f
        c.drawCircle(cx, cy, r, Paint().apply { color = 0xFFFAFCFF.toInt(); isAntiAlias = true })
        val grid = Paint().apply {
            color = LINE; style = Paint.Style.STROKE; strokeWidth = 0.8f; isAntiAlias = true
        }
        for (k in 1..3) c.drawCircle(cx, cy, r * k / 3f, grid)
        c.drawLine(cx - r, cy, cx + r, cy, grid)
        c.drawLine(cx, cy - r, cx, cy + r, grid)
        val card = Paint().apply { color = MUTE; textSize = 6.5f; isFakeBoldText = true; isAntiAlias = true }
        c.drawText("N", cx - 2f, cy - r - 1.5f, card)
        c.drawText("S", cx - 2f, cy + r + 7f, card)

        if (pass.track.isEmpty()) return
        fun px(az: Double, el: Double): Pair<Float, Float> {
            val rr = r * ((90.0 - el) / 90.0).toFloat().coerceIn(0f, 1f)
            val a = Math.toRadians(az - 90.0)
            return (cx + rr * cos(a).toFloat()) to (cy + rr * sin(a).toFloat())
        }
        val trackPaint = Paint().apply {
            color = MAGENTA; style = Paint.Style.STROKE; strokeWidth = 1.6f
            isAntiAlias = true; pathEffect = android.graphics.DashPathEffect(floatArrayOf(3.5f, 2f), 0f)
        }
        val path = Path()
        pass.track.forEachIndexed { i, (az, el) ->
            val (fx, fy) = px(az, el); if (i == 0) path.moveTo(fx, fy) else path.lineTo(fx, fy)
        }
        c.drawPath(path, trackPaint)
        val (ax, ay) = px(pass.track.first().first, pass.track.first().second)
        val (lx, ly) = px(pass.track.last().first, pass.track.last().second)
        c.drawCircle(ax, ay, 2.6f, Paint().apply { color = CYAN; isAntiAlias = true })
        c.drawCircle(lx, ly, 2.6f, Paint().apply {
            color = MAGENTA; style = Paint.Style.STROKE; strokeWidth = 1.3f; isAntiAlias = true
        })
    }

    private fun rangeMHz(low: Long?, high: Long?): String = when {
        low != null && high != null && high != low -> "${fmtMHz(low)}–${fmtMHz(high)}"
        low != null -> fmtMHz(low)
        else -> "—"
    }

    private fun fmtMHz(hz: Long): String = "%.4f".format(hz / 1_000_000.0)

    fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
