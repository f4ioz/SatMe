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
import kotlin.math.cos
import kotlin.math.sin

/**
 * A sked replayed as a video to send the other station: the map (ground
 * track, the common window in green, the earth that sees the satellite, both
 * stations and their line to it while they see it) or the globe in the
 * page's look ([GlobeRenduPc]), and both skies, the whole pass in [DUREE_S]
 * seconds then the window held [FIN_S] seconds. 1280×720 (16:9) or 720×1280
 * (a phone's 9:16), through ffmpeg, as the journal's videos.
 */
class VideoSked(private val dossier: File, private val tuile: (Int, Int, Int) -> BufferedImage?) {

    /** What the page shows while it is made. */
    data class Etat(val enCours: Boolean = false, val progression: Float = 0f, val nom: String = "", val erreur: String = "")

    @Volatile var etat = Etat(); private set

    /** The sked to draw: the trace, the window, who (my callsign and locator, the other's locator). */
    class Sked(val trace: fr.f4ioz.satcombo.domain.TraceSked, val deMs: Long, val aMs: Long, val satellite: String, val moi: String, val autre: String,
               val locMoi: String = "") {
        /** My label: callsign · locator. */
        val etiquetteMoi get() = listOf(moi, locMoi.uppercase()).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        /** The highest each side sees it during the common window (degrees). */
        val elMax: Pair<Double, Double> get() {
            val l = trace.points.filter { it.t in deMs..aMs }.ifEmpty { trace.points }
            return (l.maxOfOrNull { it.elMoi } ?: 0.0) to (l.maxOfOrNull { it.elAutre } ?: 0.0)
        }
    }

    /**
     * How it is drawn: the map or the globe — its look, the page's « Réglages du globe »
     * (cities, names, details, landmarks), its camera, the « Traces » — the screen's 16:9
     * or a phone's 9:16 ([vertical]).
     */
    data class Rendu(val globe: Boolean = false, val vertical: Boolean = false, val style: String = GlobeRenduPc.DEFAUT,
                     val options: GlobeRenduPc.Options = GlobeRenduPc.Options(), val camera: String = "espace", val traces: Int = 50) {
        val largeur get() = if (vertical) H else L
        val hauteur get() = if (vertical) L else H
    }

    /** Starts making it on a thread of its own; false when one is already being made or ffmpeg is missing. */
    fun lance(s: Sked, r: Rendu = Rendu()): Boolean {
        if (etat.enCours) return false
        if (RenduPc.ffmpeg() == null) { etat = Etat(erreur = "ffmpeg n'est pas installé : " + OutilsPc.conseil("ffmpeg")); return false }
        etat = Etat(enCours = true)
        thread(isDaemon = true, name = "satme-sked-video") {
            etat = runCatching { fabrique(s, r) { p -> etat = etat.copy(progression = p) } }
                .fold({ f -> if (f != null) Etat(nom = f.name, progression = 1f) else Etat(erreur = "ffmpeg n'a pas pu faire la vidéo") },
                    { e -> Etat(erreur = e.message ?: "échec") })
        }
        return true
    }

    fun fichier(nom: String): File? = File(dossier, nom).takeIf { nom.matches(Regex("[A-Za-z0-9._-]+")) && it.isFile && nom.endsWith(".mp4") }

    private fun fabrique(s: Sked, r: Rendu, progres: (Float) -> Unit): File? {
        val ff = RenduPc.ffmpeg() ?: return null
        dossier.mkdirs()
        val sortie = File(dossier, nomFichier(s, r))
        val pts = s.trace.points
        if (pts.size < 2) return null
        val nb = DUREE_S * FPS
        val fin = FIN_S * FPS
        val dessin = Dessin(s, r, tuile)
        val cmd = RenduPc.commandeEncode(ff, r.largeur, r.hauteur, FPS, nb + fin, null, sortie, crf = 24, faststart = true)
        return RenduPc.encode(cmd, r.largeur, r.hauteur) { ecris ->
            for (k in 0 until nb) {
                ecris(dessin.image(instant(s, k, nb)))
                progres((k + 1f) / (nb + fin))
            }
            // The window held at its middle: the frame to remember.
            val milieu = dessin.image((s.deMs + s.aMs) / 2)
            repeat(fin) { k -> ecris(milieu); progres((nb + k + 1f) / (nb + fin)) }
        }
    }

    /** The drawing of one instant; the map's background made once, the globe's Earth once in a while when its view stays put. */
    internal class Dessin(val s: Sked, val r: Rendu, tuile: (Int, Int, Int) -> BufferedImage?) {
        val pts = s.trace.points
        val lons = s.trace.lons
        val lonAutre = s.trace.lonAutre
        val vertical = r.vertical
        /** The view (map or globe): its width and height, from the frame's top left; the band over its top. */
        val vw = if (vertical) VW_V else CW
        val vh = if (vertical) VH_V else H
        /** The globe's scale (its labels, its lines). */
        val dp = if (vertical) 1.6f else 1.5f
        /** What either station sees, (lat, lon): the view is framed on it and both stations. */
        val vus = pts.indices.filter { pts[it].elMoi >= 0 || pts[it].elAutre >= 0 }.map { pts[it].lat to lons[it] }
        val vue: JournalPassage.VueCarte = run {
            val l = vus + listOf(s.trace.moi.latDeg to s.trace.moi.lonDeg, s.trace.autre.latDeg to lonAutre)
            JournalPassage.cadreCarte(l, vw.toDouble(), vh.toDouble(), part = 0.8, zoomMax = 8.0)
        }
        val fond: BufferedImage? = if (r.globe) null else RenduPc.fondCarte(vw, vh, vue, tuile)
        val globe: GlobeRenduPc.Sked? = if (!r.globe) null else GlobeRenduPc.Sked(
            pts.map { JournalPassage.Sol(it.t, it.lat, it.lon, it.altKm) }, s.deMs, s.aMs, s.satellite,
            GlobeRenduPc.StationSked(s.trace.moi.latDeg, s.trace.moi.lonDeg, s.etiquetteMoi, AMBRE),
            GlobeRenduPc.StationSked(s.trace.autre.latDeg, s.trace.autre.lonDeg, s.autre, ROSE),
            r.style, r.options, r.camera, r.traces, cadre = vus,
            voient = { t -> s.trace.instant(t).let { (it.elMoi >= 0) to (it.elAutre >= 0) } })
        /** The globe's Earth kept, and for when (its view still; the Sun barely moves in a couple of minutes). */
        private var terre: BufferedImage? = null
        private var terreT = Long.MIN_VALUE
        val monde = 256.0 * Math.pow(2.0, vue.zoom)
        fun x(lon: Double) = ((JournalPassage.mercX(lon) - vue.cx) * monde + vw / 2).toFloat()
        fun y(lat: Double) = ((JournalPassage.mercY(lat) - vue.cy) * monde + vh / 2).toFloat()
        val heure = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        /** The instant [t]: az/el of each side, lat, lon (continuous), altitude — as the arrays below read it. */
        fun instant(t: Long): DoubleArray = s.trace.instant(t).let { p ->
            doubleArrayOf(p.azMoi, p.elMoi, p.azAutre, p.elAutre, p.lat, p.lon, p.altKm) }

        /** The globe's camera at [t]: in the view under the band. */
        fun camera(t: Long): GlobeRenduPc.Camera = GlobeRenduPc.cameraSked(globe!!, t, 0f, BANDE.toFloat(), vw.toFloat(), (vh - BANDE).toFloat())

        fun image(t: Long): BufferedImage {
            val img = BufferedImage(r.largeur, r.hauteur, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val c = instant(t)
            val moi = c[1] >= 0; val lui = c[3] >= 0
            val gl = globe
            if (gl != null) {
                val cam = camera(t)
                val clip = g.clip
                g.clipRect(0, 0, vw, vh)
                if (gl.immobile) {
                    if (terre == null || Math.abs(t - terreT) > TERRE_MS) {
                        terre = BufferedImage(vw, vh, BufferedImage.TYPE_INT_RGB).also { b ->
                            b.createGraphics().apply { GlobeRenduPc.fondSked(this, vw.toFloat(), vh.toFloat(), gl, cam, t, dp); dispose() } }
                        terreT = t
                    }
                    g.drawImage(terre, 0, 0, null)
                } else GlobeRenduPc.fondSked(g, vw.toFloat(), vh.toFloat(), gl, cam, t, dp)
                GlobeRenduPc.dessusSked(g, cam, gl, t, dp)
                g.clip = clip
            } else carte(g, c, t, moi, lui)
            bande(g)
            panneau(g, c, t, moi, lui)
            g.dispose()
            return img
        }

        /** The map at [t]: the footprint, the track, both stations, the satellite. */
        private fun carte(g: Graphics2D, c: DoubleArray, t: Long, moi: Boolean, lui: Boolean) {
            g.drawImage(fond, 0, 0, null)
            // The footprint, a turn each side too (around a pole it spans the map).
            val cercle = fr.f4ioz.satcombo.domain.TraceSked.empreinte(c[4], c[5], c[6])
            for (d in listOf(-360.0, 0.0, 360.0)) {
                fun chemin(l: List<DoubleArray>) = Path2D.Float().also { p -> l.forEachIndexed { i, q -> if (i == 0) p.moveTo(x(q[1] + d), y(q[0])) else p.lineTo(x(q[1] + d), y(q[0])) } }
                g.color = Color(63, 224, 200, 45); g.fill(chemin(cercle).also { it.closePath() })
                // The circle stroked, not the closing along the map's edge round a pole.
                g.color = Color(20, 120, 110, 200); g.stroke = BasicStroke(1.5f); g.draw(chemin(cercle.take(fr.f4ioz.satcombo.domain.TraceSked.POINTS_CERCLE)))
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
            station(s.trace.moi.latDeg, s.trace.moi.lonDeg, moi, AMBRE, s.etiquetteMoi)
            station(s.trace.autre.latDeg, lonAutre, lui, ROSE, s.autre)
            point(g, x(c[5]), y(c[4]), 11f, if (moi && lui) VERT else CYAN, s.satellite)
        }

        /** The band on top of the view: the sked, its window, the highest each sees it, the app's mark. */
        private fun bande(g: Graphics2D) {
            g.color = Color(11, 16, 22, 215); g.fillRect(0, 0, vw, BANDE)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 24); g.color = Color.WHITE
            g.drawString(s.satellite + " · sked " + s.moi + " ↔ " + s.autre, 20, 30)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 17); g.color = Color(154, 167, 180)
            g.drawString("fenêtre commune " + date.format(s.deMs) + " → " + heure.format(s.aMs).substring(0, 5) + " UTC · " +
                ((s.aMs - s.deMs) / 60_000) + " min " + ((s.aMs - s.deMs) / 1000 % 60) + " s", 20, 54)
            val (eMoi, eLui) = s.elMax
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
            g.drawString("él. max " + s.moi + " " + Math.round(eMoi) + "° · " + s.autre + " " + Math.round(eLui) + "°", 20, 76)
            // The app's mark, in the band's right end, as on every picture SatMe exports.
            MarquePc.dessine(g, vw - 16f, 42f, 5f)
        }

        /** The clock, who sees it, both skies: the right column (16:9) or under the view (9:16). */
        private fun panneau(g: Graphics2D, c: DoubleArray, t: Long, moi: Boolean, lui: Boolean) {
            val x0 = if (vertical) 0 else vw; val y0 = if (vertical) vh else 0
            g.color = Color(11, 16, 22); g.fillRect(x0, y0, r.largeur - x0, r.hauteur - y0)
            g.font = Font(Font.MONOSPACED, Font.BOLD, 40); g.color = Color.WHITE
            g.drawString(heure.format(t), x0 + 30, y0 + 56)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14); g.color = Color(154, 167, 180); g.drawString("UTC", x0 + 230, y0 + 56)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 19)
            val etat = when { moi && lui -> "vous le voyez tous deux"; moi -> s.moi + " seul le voit"; lui -> s.autre + " seul le voit"; else -> "personne ne le voit" }
            g.color = if (moi && lui) VERT else Color(154, 167, 180); g.drawString(etat, x0 + 30, y0 + 90)
            if (vertical) {
                ciel(g, 40f, y0 + 125f, 300f, s.moi, 0, 1, c, t)
                ciel(g, 380f, y0 + 125f, 300f, s.autre, 2, 3, c, t)
            } else {
                ciel(g, x0 + 60f, 125f, 260f, s.moi, 0, 1, c, t)
                ciel(g, x0 + 60f, 425f, 260f, s.autre, 2, 3, c, t)
            }
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
            fun arc(filtre: (fr.f4ioz.satcombo.domain.PointSked) -> Boolean, couleur: Color, w: Float) {
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

    }

    companion object {
        const val L = 1280
        const val H = 720
        /** The map's width; the skies on the right. */
        const val CW = 880
        /** A phone's 9:16 (720×1280): the view's height, the clock and the skies under it. */
        const val VW_V = 720
        const val VH_V = 780
        /** The band over the view's top. */
        const val BANDE = 86
        /** The globe's Earth drawn again every so much of the sked (its view still): the Sun's light, the night. */
        const val TERRE_MS = 120_000L
        const val FPS = 10
        const val DUREE_S = 24
        const val FIN_S = 3
        val VERT = Color(91, 214, 138)
        val CYAN = Color(63, 224, 200)
        val AMBRE = Color(255, 180, 84)
        val ROSE = Color(232, 111, 168)

        /** The frame [k] of [nb]: from the trace's first instant to its last. */
        fun instant(s: Sked, k: Int, nb: Int): Long { val t0 = s.trace.points.first().t; val t1 = s.trace.points.last().t; return t0 + (t1 - t0) * k / (nb - 1) }

        /** The file's name: the satellite, the other station, the window's start, the globe and the phone's format said. */
        fun nomFichier(s: Sked, r: Rendu): String =
            "SatMe_Sked_" + s.satellite.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-') + "_" + s.autre.replace(Regex("[^A-Za-z0-9]"), "") + "_" +
                SimpleDateFormat("yyyyMMdd_HHmm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(s.deMs) +
                (if (r.globe) "_globe" else "") + (if (r.vertical) "_9x16" else "") + ".mp4"

        /**
         * The drawing asked by a request: `vue=globe` (else the map), `format=telephone` (9:16, else 16:9),
         * the globe's `style`, `camera`, `traces` and the choices [GlobeRenduPc.options] reads; a missing or wrong one keeps its default.
         */
        fun rendu(p: (String) -> String): Rendu = Rendu(
            globe = p("vue").trim() == "globe", vertical = p("format").trim() == "telephone",
            style = GlobeRenduPc.styleDemande("globe", p("style")) ?: GlobeRenduPc.DEFAUT, options = GlobeRenduPc.options(p),
            camera = GlobeRenduPc.cameraDemandee(p("camera")), traces = p("traces").trim().toIntOrNull()?.coerceIn(0, 100) ?: 50)
    }
}
