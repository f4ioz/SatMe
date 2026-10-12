/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.PointSked
import fr.f4ioz.satcombo.domain.TraceSked
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.sstv.SstvVideo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/**
 * A sked replayed as a video to send the other station — the PC's, drawn
 * here with the phone's canvas and encoded by its MediaCodec (as the SSTV
 * and journal videos): the map (ground track, the common window in green,
 * the earth that sees the satellite, both stations and their line to it
 * while they see it) and both skies, the whole pass in [DUREE_S] seconds,
 * then the window held [FIN_S] seconds. 1280×720, times in UTC — the clock
 * both stations share.
 */
object SkedVideo {
    private const val L = 1280
    private const val H = 720
    private const val CW = 880
    private const val FPS = 10
    private const val DUREE_S = 24
    private const val FIN_S = 3
    private const val DEBIT = 2_500_000

    private val VERT = Color.rgb(91, 214, 138)
    private val CYAN = Color.rgb(63, 224, 200)
    private val AMBRE = Color.rgb(255, 180, 84)
    private val ROSE = Color.rgb(232, 111, 168)
    private val GRIS = Color.rgb(154, 167, 180)
    private val CLAIR = Color.rgb(232, 240, 247)
    private val FOND = Color.rgb(11, 16, 22)

    /**
     * The video of [trace] (common window [deMs]..[aMs]) of [satellite]
     * between [moi] and [autre], in the cache; null when the encoder refuses.
     */
    fun fabrique(ctx: Context, trace: TraceSked, deMs: Long, aMs: Long, satellite: String, moi: String, autre: String,
                 progres: (Float) -> Unit): File? = runCatching {
        val pts = trace.points
        if (pts.size < 2) return null
        val nv12 = SstvVideo.nv12() ?: return null
        val utc = TimeZone.getTimeZone("UTC")
        val nom = "SatMe_Sked_" + satellite.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-') + "_" + autre.replace(Regex("[^A-Za-z0-9]"), "") + "_" +
            SimpleDateFormat("yyyyMMdd_HHmm'Z'", Locale.US).apply { timeZone = utc }.format(deMs) + ".mp4"
        val sortie = File(File(ctx.cacheDir, "skeds").apply { mkdirs() }, nom)
        val d = Dessin(trace, deMs, aMs, satellite, moi, autre)
        val nb = DUREE_S * FPS
        val fin = FIN_S * FPS
        val t0 = pts.first().t; val t1 = pts.last().t
        var finYuv: ByteArray? = null
        val ok = SstvVideo.encodeMp4(sortie, L, H, FPS, nb + fin, null as fr.f4ioz.satcombo.domain.SourcePcm?, 22_050, DEBIT, progres) { n, trame ->
            val y = if (n < nb) SstvVideo.yuv(d.image(t0 + (t1 - t0) * n / (nb - 1)), nv12)
                // The window held at its middle: the frame to remember, drawn once.
                else finYuv ?: SstvVideo.yuv(d.image((deMs + aMs) / 2), nv12).also { finYuv = it }
            System.arraycopy(y, 0, trame, 0, trame.size)
        }
        if (ok && sortie.length() > 0) sortie else null
    }.getOrNull()

    private class Dessin(val tr: TraceSked, val deMs: Long, val aMs: Long, val sat: String, val moi: String, val autre: String) {
        val pts = tr.points
        val vue: JournalPassage.VueCarte = run {
            // Framed on what either station sees, and both stations.
            val l = pts.indices.filter { pts[it].elMoi >= 0 || pts[it].elAutre >= 0 }.map { pts[it].lat to tr.lons[it] } +
                listOf(tr.moi.latDeg to tr.moi.lonDeg, tr.autre.latDeg to tr.lonAutre)
            JournalPassage.cadreCarte(l, CW.toDouble(), H.toDouble(), part = 0.8, zoomMax = 8.0)
        }
        val fond: Bitmap? = JournalRendu.fondCarte(CW, H, vue)
        val monde = 256.0 * Math.pow(2.0, vue.zoom)
        fun x(lon: Double) = ((JournalPassage.mercX(lon) - vue.cx) * monde + CW / 2).toFloat()
        fun y(lat: Double) = ((JournalPassage.mercY(lat) - vue.cy) * monde + H / 2).toFloat()
        val heure = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val bmp: Bitmap = Bitmap.createBitmap(L, H, Bitmap.Config.ARGB_8888)

        fun p(couleur: Int, taille: Float = 0f, gras: Boolean = false, trait: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = couleur
            if (taille > 0) { textSize = taille; typeface = if (gras) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            if (trait > 0) { style = Paint.Style.STROKE; strokeWidth = trait; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        }

        fun image(t: Long): Bitmap {
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(221, 230, 241))
            fond?.let { c.drawBitmap(it, 0f, 0f, null) }
            val i = tr.instant(t)
            val vuMoi = i.elMoi >= 0; val vuLui = i.elAutre >= 0
            // The footprint, a turn each side too (around a pole it spans the map).
            val cercle = TraceSked.empreinte(i.lat, i.lon, i.altKm)
            for (d in listOf(-360.0, 0.0, 360.0)) {
                fun chemin(l: List<DoubleArray>) = Path().also { q -> l.forEachIndexed { k, v -> if (k == 0) q.moveTo(x(v[1] + d), y(v[0])) else q.lineTo(x(v[1] + d), y(v[0])) } }
                c.drawPath(chemin(cercle).also { it.close() }, p(Color.argb(45, 63, 224, 200)))
                c.drawPath(chemin(cercle.take(TraceSked.POINTS_CERCLE)), p(Color.argb(200, 20, 120, 110), trait = 1.5f))
            }
            // The track: dashed whole, the common window green, flown so far darker.
            fun trace(filtre: (Int) -> Boolean, pinceau: Paint) {
                val q = Path(); var dedans = false
                for (k in pts.indices) {
                    if (!filtre(k)) { dedans = false; continue }
                    if (!dedans) q.moveTo(x(tr.lons[k]), y(pts[k].lat)) else q.lineTo(x(tr.lons[k]), y(pts[k].lat))
                    dedans = true
                }
                c.drawPath(q, pinceau)
            }
            trace({ true }, p(Color.argb(150, 30, 40, 55), trait = 2f).apply { pathEffect = DashPathEffect(floatArrayOf(7f, 6f), 0f) })
            trace({ pts[it].t in deMs..aMs }, p(Color.rgb(46, 158, 91), trait = 6f))
            trace({ pts[it].t <= t }, p(Color.rgb(11, 110, 99), trait = 3f))
            // The stations, their line to the satellite while they see it.
            fun station(lat: Double, lon: Double, vu: Boolean, couleur: Int, nom: String) {
                if (vu) c.drawLine(x(lon), y(lat), x(i.lon), y(i.lat), p(couleur, trait = 2.5f))
                point(c, x(lon), y(lat), 9f, couleur, nom)
            }
            station(tr.moi.latDeg, tr.moi.lonDeg, vuMoi, AMBRE, moi)
            station(tr.autre.latDeg, tr.lonAutre, vuLui, ROSE, autre)
            point(c, x(i.lon), y(i.lat), 11f, if (vuMoi && vuLui) VERT else CYAN, sat)
            // The band on top of the map.
            c.drawRect(0f, 0f, CW.toFloat(), 64f, p(Color.argb(215, 11, 16, 22)))
            c.drawText("$sat · sked $moi ↔ $autre", 20f, 32f, p(Color.WHITE, 24f, true))
            val duree = (aMs - deMs) / 1000
            c.drawText(tf("sked_video_fenetre", date.format(deMs), heure.format(aMs).substring(0, 5), duree / 60, duree % 60), 20f, 55f, p(GRIS, 17f))
            // The right column: the time, who sees it, both skies.
            c.drawRect(CW.toFloat(), 0f, L.toFloat(), H.toFloat(), p(FOND))
            c.drawText(heure.format(t), CW + 30f, 56f, p(Color.WHITE, 40f, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
            c.drawText("UTC", CW + 232f, 56f, p(GRIS, 14f))
            val etat = when {
                vuMoi && vuLui -> t("sked_video_tous_deux")
                vuMoi -> tf("sked_video_seul", moi)
                vuLui -> tf("sked_video_seul", autre)
                else -> t("sked_video_personne")
            }
            c.drawText(etat, CW + 30f, 90f, p(if (vuMoi && vuLui) VERT else GRIS, 19f, true))
            ciel(c, CW + 60f, 125f, 260f, moi, true, i)
            ciel(c, CW + 60f, 425f, 260f, autre, false, i)
            return bmp
        }

        /** A sky: the visible arc, the common window green, the satellite now. */
        fun ciel(c: Canvas, x0: Float, y0: Float, taille: Float, nom: String, mien: Boolean, i: PointSked) {
            val r = taille / 2 - 22; val cx = x0 + taille / 2; val cy = y0 + taille / 2 + 4
            c.drawText(nom, x0, y0 + 2, p(CLAIR, 16f, true))
            val grille = p(Color.rgb(34, 48, 59), trait = 1.2f)
            for (el in listOf(0, 30, 60)) c.drawCircle(cx, cy, r * (1 - el / 90f), grille)
            c.drawLine(cx - r, cy, cx + r, cy, grille); c.drawLine(cx, cy - r, cx, cy + r, grille)
            val pc = p(GRIS, 13f)
            c.drawText("N", cx - 4, cy - r - 4, pc); c.drawText("S", cx - 4, cy + r + 15, pc)
            c.drawText("E", cx + r + 4, cy + 5, pc); c.drawText("O", cx - r - 14, cy + 5, pc)
            fun xy(az: Double, el: Double): Pair<Float, Float> {
                val rr = r * (1 - el.coerceIn(0.0, 90.0) / 90).toFloat(); val a = Math.toRadians(az)
                return (cx + rr * sin(a).toFloat()) to (cy - rr * cos(a).toFloat())
            }
            fun arc(filtre: (PointSked) -> Boolean, pinceau: Paint) {
                val q = Path(); var dedans = false
                for (s in pts) {
                    val az = if (mien) s.azMoi else s.azAutre; val el = if (mien) s.elMoi else s.elAutre
                    if (el < 0 || !filtre(s)) { dedans = false; continue }
                    val (px, py) = xy(az, el)
                    if (!dedans) q.moveTo(px, py) else q.lineTo(px, py); dedans = true
                }
                c.drawPath(q, pinceau)
            }
            arc({ true }, p(Color.argb(110, 63, 224, 200), trait = 2f))
            arc({ it.t in deMs..aMs }, p(VERT, trait = 5f))
            val az = if (mien) i.azMoi else i.azAutre; val el = if (mien) i.elMoi else i.elAutre
            if (el >= 0) {
                val (px, py) = xy(az, el)
                c.drawCircle(px, py, 8f, p(AMBRE))
                c.drawText(tf("sked_video_az_el", Math.round(az), Math.round(el)), x0, y0 + taille + 22, p(CLAIR, 15f))
            } else c.drawText(t("sked_video_sous_horizon"), x0, y0 + taille + 22, p(GRIS, 15f))
        }

        fun point(c: Canvas, x: Float, y: Float, r: Float, couleur: Int, nom: String) {
            c.drawCircle(x, y, r, p(couleur))
            c.drawCircle(x, y, r, p(Color.rgb(6, 19, 26), trait = 2f))
            val tx = x + r + 5; val ty = y - 6
            // A white halo, then the name: readable on any map.
            c.drawText(nom, tx, ty, p(Color.argb(220, 255, 255, 255), 18f, true).apply { style = Paint.Style.STROKE; strokeWidth = 4f })
            c.drawText(nom, tx, ty, p(Color.rgb(6, 19, 26), 18f, true))
        }
    }
}
