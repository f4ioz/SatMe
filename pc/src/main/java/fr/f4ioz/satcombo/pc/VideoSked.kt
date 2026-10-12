/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.JournalPassage
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A sked replayed as a video to send the other station: the map (ground
 * track, the common window in green, the earth that sees the satellite, both
 * stations and their line to it while they see it) and both skies, the
 * whole pass in [DUREE_S] seconds then the window held [FIN_S] seconds.
 * 1280×720, through ffmpeg, as the journal's videos.
 */
class VideoSked(private val dossier: File, private val tuile: (Int, Int, Int) -> BufferedImage?) {

    /** What the page shows while it is made. */
    data class Etat(val enCours: Boolean = false, val progression: Float = 0f, val nom: String = "", val erreur: String = "")

    @Volatile var etat = Etat(); private set

    /** The sked to draw: the trace, the window, who. */
    class Sked(val trace: StationPc.TraceSked, val deMs: Long, val aMs: Long, val satellite: String, val moi: String, val autre: String)

    /** Starts making it on a thread of its own; false when one is already being made or ffmpeg is missing. */
    fun lance(s: Sked): Boolean {
        if (etat.enCours) return false
        if (RenduPc.ffmpeg() == null) { etat = Etat(erreur = "ffmpeg n'est pas installé : sudo apt install ffmpeg"); return false }
        etat = Etat(enCours = true)
        thread(isDaemon = true, name = "satme-sked-video") {
            etat = runCatching { fabrique(s) { p -> etat = etat.copy(progression = p) } }
                .fold({ f -> if (f != null) Etat(nom = f.name, progression = 1f) else Etat(erreur = "ffmpeg n'a pas pu faire la vidéo") },
                    { e -> Etat(erreur = e.message ?: "échec") })
        }
        return true
    }

    fun fichier(nom: String): File? = File(dossier, nom).takeIf { nom.matches(Regex("[A-Za-z0-9._-]+")) && it.isFile && nom.endsWith(".mp4") }

    private fun fabrique(s: Sked, progres: (Float) -> Unit): File? {
        val ff = RenduPc.ffmpeg() ?: return null
        dossier.mkdirs()
        val utc = TimeZone.getTimeZone("UTC")
        val nom = "SatMe_Sked_" + s.satellite.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-') + "_" + s.autre.replace(Regex("[^A-Za-z0-9]"), "") + "_" +
            SimpleDateFormat("yyyyMMdd_HHmm'Z'", Locale.US).apply { timeZone = utc }.format(s.deMs) + ".mp4"
        val sortie = File(dossier, nom)
        val pts = s.trace.points
        if (pts.size < 2) return null
        val nb = DUREE_S * FPS
        val fin = FIN_S * FPS
        val dessin = Dessin(s, tuile)
        val cmd = listOf(ff, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${L}x$H", "-r", "$FPS", "-i", "-",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-crf", "24", "-movflags", "+faststart", sortie.path)
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val dit = StringBuilder()
        val journal = Thread { runCatching { dit.append(String(p.inputStream.readBytes())) } }.apply { isDaemon = true; start() }
        val rgb = ByteArray(L * H * 3)
        fun ecris(img: BufferedImage) {
            val px = img.getRGB(0, 0, L, H, null, 0, L)
            for (i in px.indices) { val v = px[i]; rgb[3 * i] = (v shr 16).toByte(); rgb[3 * i + 1] = (v shr 8).toByte(); rgb[3 * i + 2] = v.toByte() }
            p.outputStream.write(rgb)
        }
        try {
            val t0 = pts.first().t; val t1 = pts.last().t
            for (k in 0 until nb) {
                ecris(dessin.image(t0 + (t1 - t0) * k / (nb - 1)))
                progres((k + 1f) / (nb + fin))
            }
            // The window held at its middle: the frame to remember.
            val milieu = dessin.image((s.deMs + s.aMs) / 2)
            repeat(fin) { k -> ecris(milieu); progres((nb + k + 1f) / (nb + fin)) }
            p.outputStream.close()
        } catch (e: Exception) {
            p.destroy(); throw e
        }
        val ok = p.waitFor() == 0
        journal.join(1000)
        if (!ok) System.err.println("ffmpeg : " + dit.toString().trim().take(800))
        return if (ok && sortie.length() > 0) sortie else null
    }

    /** The drawing of one instant; the map's background made once. */
    private class Dessin(val s: Sked, tuile: (Int, Int, Int) -> BufferedImage?) {
        val pts = s.trace.points
        // Continuous longitudes from mine: a track over the date line stays one line.
        val lons: DoubleArray = DoubleArray(pts.size).also { l ->
            var ref = s.trace.moi.lonDeg
            for (i in pts.indices) { var x = pts[i].lon; while (x - ref > 180) x -= 360; while (x - ref < -180) x += 360; l[i] = x; ref = x }
        }
        val lonAutre = s.trace.autre.lonDeg.let { var x = it; while (x - s.trace.moi.lonDeg > 180) x -= 360; while (x - s.trace.moi.lonDeg < -180) x += 360; x }
        val vue: JournalPassage.VueCarte = run {
            // Framed on what either station sees, and both stations.
            val l = pts.indices.filter { pts[it].elMoi >= 0 || pts[it].elAutre >= 0 }.map { pts[it].lat to lons[it] } +
                listOf(s.trace.moi.latDeg to s.trace.moi.lonDeg, s.trace.autre.latDeg to lonAutre)
            JournalPassage.cadreCarte(l, CW.toDouble(), H.toDouble(), part = 0.8, zoomMax = 8.0)
        }
        val fond: BufferedImage = RenduPc.fondCarte(CW, H, vue, tuile)
        val monde = 256.0 * Math.pow(2.0, vue.zoom)
        fun x(lon: Double) = ((JournalPassage.mercX(lon) - vue.cx) * monde + CW / 2).toFloat()
        fun y(lat: Double) = ((JournalPassage.mercY(lat) - vue.cy) * monde + H / 2).toFloat()
        val heure = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        /** The instant [t], interpolated: az/el of each side, lat, lon (continuous), altitude. */
        fun instant(t: Long): DoubleArray {
            var k = 0
            while (k < pts.size - 2 && pts[k + 1].t <= t) k++
            val a = pts[k]; val b = pts[minOf(k + 1, pts.size - 1)]
            val u = if (b.t > a.t) ((t - a.t).toDouble() / (b.t - a.t)).coerceIn(0.0, 1.0) else 0.0
            fun az(p: Double, q: Double): Double { var d = q - p; if (d > 180) d -= 360; if (d < -180) d += 360; return ((p + d * u) % 360 + 360) % 360 }
            fun li(p: Double, q: Double) = p + (q - p) * u
            return doubleArrayOf(az(a.azMoi, b.azMoi), li(a.elMoi, b.elMoi), az(a.azAutre, b.azAutre), li(a.elAutre, b.elAutre),
                li(a.lat, b.lat), li(lons[k], lons[minOf(k + 1, pts.size - 1)]), li(a.altKm, b.altKm))
        }

        fun image(t: Long): BufferedImage {
            val img = BufferedImage(L, H, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.drawImage(fond, 0, 0, null)
            val c = instant(t)
            val moi = c[1] >= 0; val lui = c[3] >= 0
            // The footprint, a turn each side too (around a pole it spans the map).
            val rho = acos(6371.0 / (6371.0 + maxOf(150.0, c[6])))
            val cercle = cercle(c[4], c[5], rho)
            for (d in listOf(-360.0, 0.0, 360.0)) {
                val p = Path2D.Float()
                cercle.forEachIndexed { i, q -> if (i == 0) p.moveTo(x(q[1] + d), y(q[0])) else p.lineTo(x(q[1] + d), y(q[0])) }
                p.closePath()
                g.color = Color(63, 224, 200, 45); g.fill(p)
                g.color = Color(20, 120, 110, 200); g.stroke = BasicStroke(1.5f); g.draw(p)
            }
            // The track: dashed whole, the common window green, flown so far darker.
            fun trace(filtre: (Int) -> Boolean, couleur: Color, trait: BasicStroke) {
                val p = Path2D.Float(); var dedans = false
                for (i in pts.indices) {
                    if (!filtre(i)) { dedans = false; continue }
                    if (!dedans) p.moveTo(x(lons[i]), y(pts[i].lat)) else p.lineTo(x(lons[i]), y(pts[i].lat))
                    dedans = true
                }
                g.color = couleur; g.stroke = trait; g.draw(p)
            }
            trace({ true }, Color(30, 40, 55, 150), BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(7f, 6f), 0f))
            trace({ pts[it].t in s.deMs..s.aMs }, Color(46, 158, 91), BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            trace({ pts[it].t <= t }, Color(11, 110, 99), BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            // The stations, their line to the satellite while they see it.
            fun station(lat: Double, lon: Double, vu: Boolean, couleur: Color, nom: String) {
                if (vu) { g.color = couleur; g.stroke = BasicStroke(2.5f); g.drawLine(x(lon).toInt(), y(lat).toInt(), x(c[5]).toInt(), y(c[4]).toInt()) }
                point(g, x(lon), y(lat), 9f, couleur, nom)
            }
            station(s.trace.moi.latDeg, s.trace.moi.lonDeg, moi, AMBRE, s.moi)
            station(s.trace.autre.latDeg, lonAutre, lui, ROSE, s.autre)
            point(g, x(c[5]), y(c[4]), 11f, if (moi && lui) VERT else CYAN, s.satellite)
            // The band on top of the map: the sked, the time, who sees it.
            g.color = Color(11, 16, 22, 215); g.fillRect(0, 0, CW, 64)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 24); g.color = Color.WHITE
            g.drawString(s.satellite + " · sked " + s.moi + " ↔ " + s.autre, 20, 30)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 17); g.color = Color(154, 167, 180)
            g.drawString("fenêtre commune " + date.format(s.deMs) + " → " + heure.format(s.aMs).substring(0, 5) + " UTC · " +
                ((s.aMs - s.deMs) / 60_000) + " min " + ((s.aMs - s.deMs) / 1000 % 60) + " s", 20, 54)
            // The right column: the time, both skies.
            g.color = Color(11, 16, 22); g.fillRect(CW, 0, L - CW, H)
            g.font = Font(Font.MONOSPACED, Font.BOLD, 40); g.color = Color.WHITE
            g.drawString(heure.format(t), CW + 30, 56)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14); g.color = Color(154, 167, 180); g.drawString("UTC", CW + 230, 56)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 19)
            val etat = when { moi && lui -> "vous le voyez tous deux"; moi -> s.moi + " seul le voit"; lui -> s.autre + " seul le voit"; else -> "personne ne le voit" }
            g.color = if (moi && lui) VERT else Color(154, 167, 180); g.drawString(etat, CW + 30, 90)
            ciel(g, CW + 60f, 125f, 260f, s.moi, 0, 1, c, t)
            ciel(g, CW + 60f, 425f, 260f, s.autre, 2, 3, c, t)
            g.dispose()
            return img
        }

        /** A sky: the visible arc, the common window green, the satellite now. */
        fun ciel(g: Graphics2D, x0: Float, y0: Float, taille: Float, nom: String, iAz: Int, iEl: Int, c: DoubleArray, t: Long) {
            val r = taille / 2 - 22; val cx = x0 + taille / 2; val cy = y0 + taille / 2 + 4
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 16); g.color = Color(232, 240, 247); g.drawString(nom, x0, y0 + 2)
            g.color = Color(34, 48, 59); g.stroke = BasicStroke(1.2f)
            for (el in listOf(0, 30, 60)) { val rr = r * (1 - el / 90f); g.draw(Ellipse2D.Float(cx - rr, cy - rr, 2 * rr, 2 * rr)) }
            g.drawLine((cx - r).toInt(), cy.toInt(), (cx + r).toInt(), cy.toInt()); g.drawLine(cx.toInt(), (cy - r).toInt(), cx.toInt(), (cy + r).toInt())
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13); g.color = Color(154, 167, 180)
            g.drawString("N", cx - 4, cy - r - 4); g.drawString("S", cx - 4, cy + r + 15); g.drawString("E", cx + r + 4, cy + 5); g.drawString("O", cx - r - 14, cy + 5)
            fun xy(az: Double, el: Double): Pair<Float, Float> { val rr = r * (1 - el.coerceIn(0.0, 90.0) / 90).toFloat(); val a = Math.toRadians(az); return (cx + rr * sin(a).toFloat()) to (cy - rr * cos(a).toFloat()) }
            fun arc(filtre: (StationPc.PointSked) -> Boolean, couleur: Color, w: Float) {
                val p = Path2D.Float(); var dedans = false
                for (q in pts) {
                    val az = if (iAz == 0) q.azMoi else q.azAutre; val el = if (iEl == 1) q.elMoi else q.elAutre
                    if (el < 0 || !filtre(q)) { dedans = false; continue }
                    val (px, py) = xy(az, el)
                    if (!dedans) p.moveTo(px, py) else p.lineTo(px, py); dedans = true
                }
                g.color = couleur; g.stroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(p)
            }
            arc({ true }, Color(63, 224, 200, 110), 2f)
            arc({ it.t in s.deMs..s.aMs }, VERT, 5f)
            val az = c[iAz]; val el = c[iEl]
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
            if (el >= 0) {
                val (px, py) = xy(az, el)
                g.color = AMBRE; g.fill(Ellipse2D.Float(px - 8, py - 8, 16f, 16f))
                g.color = Color(232, 240, 247); g.drawString("az " + Math.round(az) + "° · él " + Math.round(el) + "°", x0, y0 + taille + 22)
            } else { g.color = Color(154, 167, 180); g.drawString("sous l'horizon", x0, y0 + taille + 22) }
        }

        fun point(g: Graphics2D, x: Float, y: Float, r: Float, couleur: Color, nom: String) {
            g.color = couleur; g.fill(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
            g.color = Color(6, 19, 26); g.stroke = BasicStroke(2f); g.draw(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
            val tx = x + r + 5; val ty = y - 6
            g.color = Color(255, 255, 255, 220)
            for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, 1 to 1)) g.drawString(nom, tx + dx * 1.5f, ty + dy * 1.5f)
            g.color = Color(6, 19, 26); g.drawString(nom, tx, ty)
        }

        /** The points (lat, lon) at an angular radius [rho] around (lat0, lon0); round a pole along the map's edge. */
        fun cercle(lat0: Double, lon0: Double, rho: Double): List<DoubleArray> {
            val f0 = Math.toRadians(lat0)
            val out = ArrayList<DoubleArray>()
            for (bDeg in 0..360 step 4) {
                val br = Math.toRadians(bDeg.toDouble())
                val f = asin(sin(f0) * cos(rho) + cos(f0) * sin(rho) * cos(br))
                val dl = atan2(sin(br) * sin(rho) * cos(f0), cos(rho) - sin(f0) * sin(f))
                var lon = lon0 + Math.toDegrees(dl)
                if (out.isNotEmpty()) { val pr = out.last()[1]; while (lon - pr > 180) lon -= 360; while (lon - pr < -180) lon += 360 }
                out += doubleArrayOf(Math.toDegrees(f), lon)
            }
            val fin = out.last()[1]; val debut = out.first()[1]
            if (Math.abs(fin - debut) > 180) { val pole = if (lat0 >= 0) 85.0 else -85.0; out += doubleArrayOf(pole, fin); out += doubleArrayOf(pole, debut) }
            return out
        }
    }

    companion object {
        const val L = 1280
        const val H = 720
        /** The map's width; the skies on the right. */
        const val CW = 880
        const val FPS = 10
        const val DUREE_S = 24
        const val FIN_S = 3
        val VERT = Color(91, 214, 138)
        val CYAN = Color(63, 224, 200)
        val AMBRE = Color(255, 180, 84)
        val ROSE = Color(232, 111, 168)
    }
}
