/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Generates a printable PDF schedule of selected passes (sked / trip prep). */
object PassPdf {

    fun build(
        context: Context,
        passes: List<SatPass>,
        observer: Observer?
    ): File {
        val doc = PdfDocument()
        val pageW = 595; val pageH = 842 // A4 @72dpi
        val margin = 36f

        val title = Paint().apply { color = Color.BLACK; textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
        val sub = Paint().apply { color = Color.rgb(90, 90, 90); textSize = 10f; isAntiAlias = true }
        val hdr = Paint().apply { color = Color.WHITE; textSize = 9f; isFakeBoldText = true; isAntiAlias = true }
        val cell = Paint().apply { color = Color.BLACK; textSize = 9f; isAntiAlias = true }
        val cellB = Paint().apply { color = Color.BLACK; textSize = 9f; isFakeBoldText = true; isAntiAlias = true }
        val rule = Paint().apply { color = Color.rgb(210, 210, 210) }
        val band = Paint().apply { color = Color.rgb(21, 101, 192) }
        val zebra = Paint().apply { color = Color.rgb(244, 247, 252) }

        val dLocal = SimpleDateFormat("EEE dd/MM HH:mm:ss", Locale.FRENCH)
        val dUtc = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val genFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)

        // Columns: x positions and widths.
        val cols = listOf(
            "Satellite" to 96f, "AOS (local)" to 108f, "LOS" to 56f,
            "ÉlMax" to 40f, "Az AOS→LOS" to 78f, "Durée" to 42f, "UTC" to 64f
        )

        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        var c = page.canvas
        var y = margin

        fun header() {
            c.drawText("SatCombo — Plan de passages", margin, y + 4f, title)
            y += 22f
            val qth = observer?.let { "QTH : ${it.name}  (%.4f, %.4f)".format(it.latDeg, it.lonDeg) } ?: ""
            c.drawText(qth, margin, y, sub); y += 12f
            c.drawText("Généré le ${genFmt.format(Date())}  ·  ${passes.size} passage(s)  ·  heures locales (UTC en fin de ligne)",
                margin, y, sub)
            y += 14f
            drawRow(c, margin, y, cols, hdr, band, isHeader = true)
            y += 18f
        }

        header()
        var rowIdx = 0
        for (p in passes.sortedBy { it.aosEpochMs }) {
            if (y > pageH - margin - 20) {
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, doc.pages.size + 1).create())
                c = page.canvas; y = margin; header()
            }
            if (rowIdx % 2 == 1) c.drawRect(margin, y - 11f, pageW - margin, y + 4f, zebra)
            val vals = listOf(
                p.satName,
                dLocal.format(Date(p.aosEpochMs)),
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(p.losEpochMs)),
                "${p.maxElevationDeg.toInt()}°",
                "${p.aosAzimuthDeg.toInt()}°→${p.losAzimuthDeg.toInt()}°",
                "${p.durationSec}s",
                "${dUtc.format(Date(p.aosEpochMs))}Z"
            )
            var x = margin
            vals.forEachIndexed { i, v ->
                c.drawText(v, x + 2f, y, if (i == 0) cellB else cell)
                x += cols[i].second
            }
            c.drawLine(margin, y + 4f, pageW - margin, y + 4f, rule)
            y += 16f
            rowIdx++
        }
        doc.finishPage(page)

        val out = File(context.cacheDir, "skeds").apply { mkdirs() }
            .let { File(it, "satcombo_passages_${System.currentTimeMillis()}.pdf") }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun drawRow(
        c: android.graphics.Canvas, startX: Float, y: Float,
        cols: List<Pair<String, Float>>, text: Paint, band: Paint, isHeader: Boolean
    ) {
        if (isHeader) {
            val totalW = cols.sumOf { it.second.toDouble() }.toFloat()
            c.drawRect(startX, y - 11f, startX + totalW, y + 4f, band)
        }
        var x = startX
        for ((label, w) in cols) { c.drawText(label, x + 2f, y, text); x += w }
    }

    fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
