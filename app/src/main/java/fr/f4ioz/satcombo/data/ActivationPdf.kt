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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * One-page (or more) "activation sheet": everything needed to copy a field
 * session into a paper or electronic logbook — site, locator, coordinates,
 * period, satellites worked and the full QSO table. Times follow the app-wide
 * UTC/local preference and are labelled, so nothing is ambiguous later.
 */
object ActivationPdf {

    private const val PAGE_W = 595      // A4 @ 72 dpi
    private const val PAGE_H = 842
    private const val MARGIN = 36f

    fun build(
        context: Context,
        a: Activation,
        qsos: List<LogEntry>,
        useUtc: Boolean = false
    ): File {
        val doc = PdfDocument()

        val title = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true; isAntiAlias = true }
        val big = Paint().apply { color = Color.rgb(11, 94, 88); textSize = 30f; isFakeBoldText = true; isAntiAlias = true }
        val label = Paint().apply { color = Color.rgb(120, 120, 120); textSize = 8f; isFakeBoldText = true; isAntiAlias = true }
        val body = Paint().apply { color = Color.BLACK; textSize = 10f; isAntiAlias = true }
        val bodyB = Paint().apply { color = Color.BLACK; textSize = 10f; isFakeBoldText = true; isAntiAlias = true }
        val small = Paint().apply { color = Color.rgb(90, 90, 90); textSize = 8.5f; isAntiAlias = true }
        val hdr = Paint().apply { color = Color.WHITE; textSize = 9f; isFakeBoldText = true; isAntiAlias = true }
        val band = Paint().apply { color = Color.rgb(11, 94, 88) }
        val zebra = Paint().apply { color = Color.rgb(244, 248, 250) }
        val rule = Paint().apply { color = Color.rgb(215, 220, 226) }

        val tz = if (useUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val tag = if (useUtc) "UTC" else "LOC"
        val dLong = SimpleDateFormat("EEEE dd MMMM yyyy", I18n.locale()).apply { timeZone = tz }
        val dShort = SimpleDateFormat("dd/MM", I18n.locale()).apply { timeZone = tz }
        val hm = SimpleDateFormat("HH:mm", I18n.locale()).apply { timeZone = tz }
        val hms = SimpleDateFormat("HH:mm:ss", I18n.locale()).apply { timeZone = tz }
        val gen = SimpleDateFormat("dd/MM/yyyy HH:mm", I18n.locale()).apply { timeZone = tz }

        // Columns of the QSO table.
        val cols = listOf(
            t("pdf_col_date") to 46f, "$tag" to 52f, t("pdf_col_sat") to 96f,
            t("pdf_col_call") to 76f, t("pdf_col_grid") to 54f,
            "Az/El" to 62f, t("pdf_col_note") to 137f
        )

        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var c: Canvas = page.canvas
        var y = MARGIN

        fun tableHeader() {
            val totalW = cols.sumOf { it.second.toDouble() }.toFloat()
            c.drawRect(MARGIN, y - 11f, MARGIN + totalW, y + 4f, band)
            var x = MARGIN
            cols.forEach { (lbl, w) -> c.drawText(lbl, x + 3f, y, hdr); x += w }
            y += 18f
        }

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            c = page.canvas
            y = MARGIN
            tableHeader()
        }

        // ---------- header ----------
        c.drawText(t("act_pdf_title"), MARGIN, y + 6f, title)
        val markP = Paint().apply {
            color = Color.rgb(11, 94, 88); textSize = 12f; isFakeBoldText = true
            isAntiAlias = true; textAlign = Paint.Align.RIGHT
        }
        c.drawText("SatMe", PAGE_W - MARGIN, y + 6f, markP)
        y += 16f
        c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
        y += 26f

        // Big locator on the left, site name on the right.
        c.drawText(a.locator.uppercase(), MARGIN, y, big)
        var locW = big.measureText(a.locator.uppercase())
        // A site on a grid line is worked from two squares (four in a corner);
        // the sheet has to say so, or the claim cannot be checked afterwards.
        val squares = a.grids.split(",").map { it.trim().uppercase() }.filter { it.length == 4 }
        if (squares.size > 1) {
            val txt = "  " + squares.joinToString(" / ")
            c.drawText(txt, MARGIN + locW, y, bodyB)
            locW += bodyB.measureText(txt)
        }
        if (a.name.isNotBlank()) c.drawText(a.name, MARGIN + locW + 14f, y - 2f, bodyB)
        y += 16f

        c.drawText(t("act_pdf_period"), MARGIN, y, label); y += 12f
        val endMs = a.endMs
        val periodLine = if (endMs == null)
            "${dLong.format(Date(a.startMs))}  ·  ${hm.format(Date(a.startMs))} → …  $tag"
        else if (dShort.format(Date(a.startMs)) == dShort.format(Date(endMs)))
            "${dLong.format(Date(a.startMs))}  ·  ${hm.format(Date(a.startMs))} → ${hm.format(Date(endMs))}  $tag"
        else
            "${dLong.format(Date(a.startMs))} ${hm.format(Date(a.startMs))} → " +
                "${dLong.format(Date(endMs))} ${hm.format(Date(endMs))}  $tag"
        c.drawText(periodLine, MARGIN, y, body); y += 16f

        c.drawText(t("act_pdf_station"), MARGIN, y, label); y += 12f
        val station = buildString {
            if (a.callsign.isNotBlank()) append(a.callsign.uppercase()).append("  ·  ")
            append("%.5f°, %.5f°".format(a.latDeg, a.lonDeg))
        }
        c.drawText(station, MARGIN, y, body); y += 16f

        val sats = qsos.map { it.satName }.filter { it.isNotBlank() }.distinct()
        c.drawText(t("act_pdf_sats"), MARGIN, y, label); y += 12f
        c.drawText(if (sats.isEmpty()) "—" else sats.joinToString(" · "), MARGIN, y, body)
        y += 16f

        if (a.note.isNotBlank()) {
            c.drawText(t("act_pdf_note"), MARGIN, y, label); y += 12f
            wrap(a.note, body, PAGE_W - 2 * MARGIN).forEach { c.drawText(it, MARGIN, y, body); y += 12f }
            y += 4f
        }

        // ---------- optional QRV photo ----------
        if (a.photoPath.isNotBlank() && File(a.photoPath).exists()) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = runCatching { BitmapFactory.decodeFile(a.photoPath, opts) }.getOrNull()
            if (bmp != null) {
                val maxW = PAGE_W - 2 * MARGIN
                val maxH = 210f
                val scale = minOf(maxW / bmp.width, maxH / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height),
                    RectF(MARGIN, y, MARGIN + dw, y + dh), null)
                c.drawRect(MARGIN, y, MARGIN + dw, y + dh, Paint().apply {
                    style = Paint.Style.STROKE; color = Color.rgb(200, 205, 210)
                })
                y += dh + 14f
                bmp.recycle()
            }
        }

        // ---------- QSO table ----------
        c.drawText(tf("act_pdf_qso_count", qsos.size.toString()), MARGIN, y, bodyB)
        y += 18f
        tableHeader()

        var idx = 0
        for (q in qsos) {
            if (y > PAGE_H - MARGIN - 24f) newPage()
            val totalW = cols.sumOf { it.second.toDouble() }.toFloat()
            if (idx % 2 == 1) c.drawRect(MARGIN, y - 11f, MARGIN + totalW, y + 4f, zebra)
            val vals = listOf(
                dShort.format(Date(q.timeMs)),
                hms.format(Date(q.timeMs)),
                q.satName,
                q.callsign.uppercase(),
                q.theirLocator.uppercase(),
                "${q.azimuthDeg.toInt()}°/${q.elevationDeg.toInt()}°",
                q.note
            )
            var x = MARGIN
            vals.forEachIndexed { i, v ->
                c.drawText(clip(v, if (i == 6) body else bodyB, cols[i].second - 6f),
                    x + 3f, y, if (i == 3) bodyB else body)
                x += cols[i].second
            }
            c.drawLine(MARGIN, y + 4f, MARGIN + totalW, y + 4f, rule)
            y += 16f
            idx++
        }
        if (qsos.isEmpty()) {
            c.drawText(t("act_pdf_no_qso"), MARGIN + 3f, y, small)
            y += 16f
        }

        // ---------- footer ----------
        y = PAGE_H - MARGIN + 8f
        c.drawText(tf("act_pdf_generated", gen.format(Date())) + "  ·  SatMe", MARGIN, y, small)

        doc.finishPage(page)

        val dir = File(context.cacheDir, "skeds").apply { mkdirs() }
        val out = File(dir, "satme_activation_${a.locator.lowercase()}_${a.startMs}.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** Trims [s] with an ellipsis so it fits in [maxW] points. */
    private fun clip(s: String, p: Paint, maxW: Float): String {
        if (s.isEmpty() || p.measureText(s) <= maxW) return s
        var cut = s
        while (cut.isNotEmpty() && p.measureText("$cut…") > maxW) cut = cut.dropLast(1)
        return "$cut…"
    }

    /** Naive word wrap for the free-text note. */
    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        s.split(Regex("\\s+")).forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(candidate) > maxW && line.isNotEmpty()) {
                out += line.toString(); line = StringBuilder(word)
            } else line = StringBuilder(candidate)
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
