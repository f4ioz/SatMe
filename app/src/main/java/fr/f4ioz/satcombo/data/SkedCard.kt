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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.FileProvider
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders a shareable PNG "sked card" for a mutual-visibility plan: both
 * stations' polar plots (pass arc + AOS/LOS + highlighted common window), the
 * satellite, date and mutual window. Meant to be shared with the sked OM so
 * both operators have the same plan on screen.
 */
object SkedCard {

    // Dark "space" palette, independent of the in-app theme, for a crisp card.
    // (plain vals — const val forbids the .toInt() call on a Long literal)
    private val BG = 0xFF0A0E17.toInt()
    private val PANEL = 0xFF131A26.toInt()
    private val GRID = 0xFF2A3647.toInt()
    private val GRID_SOFT = 0xFF20293A.toInt()
    private val CYAN = 0xFF38E1D4.toInt()
    private val AMBER = 0xFFFFC65C.toInt()
    private val MAGENTA = 0xFFFF6BA9.toInt()
    private val TEXT_HI = 0xFFEAF1FF.toInt()
    private val TEXT_LO = 0xFF8A98B0.toInt()

    private const val W = 1080
    private const val H = 1350

    /**
     * [me] is the local operator's callsign. It replaces the generic "you" on the
     * card: the OM at the other end of the sked reads a callsign, not "toi".
     */
    fun build(context: Context, plan: SkedPlan, useUtc: Boolean, me: String = "",
              units: String = Units.METRIC): File {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)

        // Day and hours must share the same timezone, otherwise a window at
        // 23:40 UTC is printed with the *local* (next) day on the card.
        val dfmt = SimpleDateFormat("EEE dd/MM", I18n.locale())
            .apply { if (useUtc) timeZone = TimeZone.getTimeZone("UTC") }
        val hfmt = SimpleDateFormat("HH:mm", I18n.locale())
            .apply { if (useUtc) timeZone = TimeZone.getTimeZone("UTC") }
        val tz = if (useUtc) "UTC" else "LOC"
        // UTC is the only clock both stations share: it is printed as well
        // whenever the card is built in local time.
        val dfmtU = SimpleDateFormat("EEE dd/MM", I18n.locale())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val hfmtU = SimpleDateFormat("HH:mm", I18n.locale())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val meLabel = me.trim().uppercase().ifBlank { t("sked_you").uppercase() }

        val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_HI; textSize = 66f; isFakeBoldText = true
        }
        val pAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CYAN; textSize = 40f; isFakeBoldText = true
        }
        val pBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_HI; textSize = 34f }
        val pDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_LO; textSize = 28f }

        var y = 96f
        c.drawText("SKED", 60f, y, pTitle)
        // "mutual sked" tag on the right.
        val tag = t("sked_page_title").uppercase()
        pDim.textAlign = Paint.Align.RIGHT
        c.drawText(tag, (W - 60).toFloat(), 70f, pDim)
        pDim.textAlign = Paint.Align.LEFT

        y += 60f
        c.drawText(plan.satName, 60f, y, pAccent)
        y += 48f
        c.drawText("${dfmt.format(Date(plan.mutualStartMs))}  ·  " +
            "${hfmt.format(Date(plan.mutualStartMs))} → ${hfmt.format(Date(plan.mutualEndMs))} $tz",
            60f, y, pBody)
        if (!useUtc) {
            y += 38f
            c.drawText("${dfmtU.format(Date(plan.mutualStartMs))}  ·  " +
                "${hfmtU.format(Date(plan.mutualStartMs))} → ${hfmtU.format(Date(plan.mutualEndMs))} UTC",
                60f, y, pDim)
        }
        y += 44f
        c.drawText(t("sked_common") + ": ${plan.mutualDurationSec / 60} min" +
            "   ·   " + Units.distanceRound(plan.distanceKm, units),
            60f, y, pDim)

        // Two polar plots side by side.
        val plotTop = y + 40f
        val plotR = 210f
        val leftCx = W * 0.27f
        val rightCx = W * 0.73f
        val plotCy = plotTop + plotR + 20f

        drawPolar(c, leftCx, plotCy, plotR, plan.you, plan, CYAN)
        drawPolar(c, rightCx, plotCy, plotR, plan.dx, plan, AMBER)

        // Station captions under each plot.
        val pSta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        val pStaSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_LO; textSize = 27f; textAlign = Paint.Align.CENTER
        }
        var capY = plotCy + plotR + 66f
        pSta.color = CYAN
        c.drawText("$meLabel · ${plan.you.locator}", leftCx, capY, pSta)
        pSta.color = AMBER
        c.drawText("DX · ${plan.dx.locator}", rightCx, capY, pSta)
        capY += 40f
        c.drawText(tf("elmax_short", plan.you.maxElDeg.toInt()), leftCx, capY, pStaSub)
        c.drawText(tf("elmax_short", plan.dx.maxElDeg.toInt()), rightCx, capY, pStaSub)

        // Detail panel.
        val panelTop = capY + 50f
        val pr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
        val panel = android.graphics.RectF(60f, panelTop, (W - 60).toFloat(),
            panelTop + if (useUtc) 220f else 272f)
        c.drawRoundRect(panel, 28f, 28f, pr)

        val pk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_LO; textSize = 28f }
        val pv = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_HI; textSize = 32f; isFakeBoldText = true }
        var ry = panelTop + 56f
        fun row(k: String, v: String) {
            c.drawText(k, 92f, ry, pk)
            pv.textAlign = Paint.Align.RIGHT
            c.drawText(v, (W - 92).toFloat(), ry, pv)
            pv.textAlign = Paint.Align.LEFT
            ry += 52f
        }
        row("$meLabel (${plan.you.locator})",
            "AOS ${hfmt.format(Date(plan.you.aosMs))} · LOS ${hfmt.format(Date(plan.you.losMs))} $tz")
        row("DX (${plan.dx.locator})",
            "AOS ${hfmt.format(Date(plan.dx.aosMs))} · LOS ${hfmt.format(Date(plan.dx.losMs))} $tz")
        row(t("sked_common"),
            "${hfmt.format(Date(plan.mutualStartMs))} → ${hfmt.format(Date(plan.mutualEndMs))} $tz")
        if (!useUtc) {
            row(t("sked_common") + " UTC",
                "${hfmtU.format(Date(plan.mutualStartMs))} → ${hfmtU.format(Date(plan.mutualEndMs))} UTC")
        }

        // Footer: the application's own mark — launcher icon plus wordmark —
        // centred at the foot of the card. The card travels between operators,
        // so it names the app that drew it and no particular station.
        AppMark.draw(
            c, W / 2f, (H - 40).toFloat(), 7f,
            icon = AppMark.icon(context, 160),
            align = Paint.Align.CENTER,
            wordColor = TEXT_HI, wordAlpha = 225, glyphColor = CYAN
        )

        val out = File(context.cacheDir, "skeds").apply { mkdirs() }
            .let { File(it, "sked_${plan.catnum}_${plan.mutualStartMs}.png") }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }

    private fun drawPolar(
        c: Canvas, cx: Float, cy: Float, r: Float,
        track: SkedStationTrack, plan: SkedPlan, accent: Int
    ) {
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
        c.drawCircle(cx, cy, r, disc)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = GRID
        }
        c.drawCircle(cx, cy, r, ring)
        ring.strokeWidth = 1.8f; ring.color = GRID_SOFT
        c.drawCircle(cx, cy, r * 2f / 3f, ring)
        c.drawCircle(cx, cy, r / 3f, ring)
        // Cardinal spokes + labels.
        c.drawLine(cx - r, cy, cx + r, cy, ring)
        c.drawLine(cx, cy - r, cx, cy + r, ring)
        val pc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_LO; textSize = 26f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        c.drawText("N", cx, cy - r - 12f, pc)
        c.drawText("S", cx, cy + r + 34f, pc)
        c.drawText("E", cx + r + 22f, cy + 9f, pc)
        c.drawText("W", cx - r - 22f, cy + 9f, pc)

        fun xy(az: Double, el: Double): Pair<Float, Float> {
            val radius = r * ((90.0 - el.coerceIn(0.0, 90.0)) / 90.0).toFloat()
            val a = Math.toRadians(az)
            return (cx + radius * sin(a).toFloat()) to (cy - radius * cos(a).toFloat())
        }

        val arc = track.arc
        if (arc.size > 1) {
            // Full arc (dim accent).
            val path = Path()
            arc.forEachIndexed { i, (az, el) ->
                val (x, yy) = xy(az, el)
                if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            }
            val pArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 5f
                color = (accent and 0x00FFFFFF) or (0x66 shl 24)   // ~40% alpha
            }
            c.drawPath(path, pArc)

            // Highlighted common-window segment (bright magenta).
            val mutual = track.samples.filter {
                it.elDeg >= 0.0 && it.tMs in plan.mutualStartMs..plan.mutualEndMs
            }
            if (mutual.size > 1) {
                val mp = Path()
                mutual.forEachIndexed { i, s ->
                    val (x, yy) = xy(s.azDeg, s.elDeg)
                    if (i == 0) mp.moveTo(x, yy) else mp.lineTo(x, yy)
                }
                val pM = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 8f; color = MAGENTA
                    strokeCap = Paint.Cap.ROUND
                }
                c.drawPath(mp, pM)
            }

            // AOS (filled) / LOS (hollow) markers.
            val (ax, ay) = xy(arc.first().first, arc.first().second)
            val (lx, ly) = xy(arc.last().first, arc.last().second)
            val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            c.drawCircle(ax, ay, 12f, pDot)
            val pHollow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 4f; color = accent
            }
            c.drawCircle(lx, ly, 12f, pHollow)
        }
    }

    fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** A short plain-text summary of the plan, shared alongside the image. */
    fun shareText(plan: SkedPlan, useUtc: Boolean, me: String = "",
                  units: String = Units.METRIC): String {
        val df = SimpleDateFormat("EEE dd/MM HH:mm", I18n.locale())
            .apply { if (useUtc) timeZone = TimeZone.getTimeZone("UTC") }
        val hf = SimpleDateFormat("HH:mm", I18n.locale())
            .apply { if (useUtc) timeZone = TimeZone.getTimeZone("UTC") }
        val dfU = SimpleDateFormat("EEE dd/MM HH:mm", I18n.locale())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val hfU = SimpleDateFormat("HH:mm", I18n.locale())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val tz = if (useUtc) "UTC" else "LOC"
        val meLabel = me.trim().uppercase().ifBlank { t("sked_you").uppercase() }
        return buildString {
            append("SKED ${plan.satName}\n")
            append("${df.format(Date(plan.mutualStartMs))} → ${hf.format(Date(plan.mutualEndMs))} $tz")
            append("  (${plan.mutualDurationSec / 60} min)\n")
            if (!useUtc) {
                append("${dfU.format(Date(plan.mutualStartMs))} → ${hfU.format(Date(plan.mutualEndMs))} UTC\n")
            }
            append("$meLabel ${plan.you.locator} (${tf("elmax_short", plan.you.maxElDeg.toInt())}) ")
            append("↔ ${plan.dx.locator} (${tf("elmax_short", plan.dx.maxElDeg.toInt())})\n")
            append(Units.distanceRound(plan.distanceKm, units) + " · SatMe")
        }
    }
}
