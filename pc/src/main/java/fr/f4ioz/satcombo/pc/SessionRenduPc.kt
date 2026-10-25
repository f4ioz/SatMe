/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.JournalPassage.Marque
import fr.f4ioz.satcombo.domain.JournalPassage.TypeMarque
import fr.f4ioz.satcombo.pc.RenduPc.texte
import java.awt.BasicStroke
import java.awt.Color
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The journal's session as a video: one satellite's passes one after
 * another on the globe, as the page's « Session » plays them. Each pass
 * from 30 s before to 30 s after, drawn as the pass's own video draws it
 * ([RenduPc.image], its overlays); between two, a short turn of the globe
 * with the next one's card (« passages ») or the time running on, ten
 * times faster outside the passes (« continu »). Under the caption the pass
 * and the pictures received so far, a strip of them when asked; the
 * session's card first, the mosaic of its pictures last. No sound.
 */
object SessionRenduPc {

    /** Frames a second: the globe turns smoothly enough between two passes. */
    const val FPS = 8
    const val MARGE_MS = 30_000L
    /** The turn between two passes (« passages »), in frames. */
    val TOURNE = (1.5 * FPS).toInt()
    /** Outside the passes (« continu »), time runs this many times faster. */
    const val HORS = 10.0

    /** The request's words: the passes (ids, in the page's order), the mode, the speed, the title, the strip. */
    data class Parametres(val ids: List<String>, val mode: String, val vitesse: Double, val titre: String?, val vignettes: Boolean)

    fun parametres(p: (String) -> String): Parametres {
        val mode = p("mode").trim().takeIf { it in setOf("passages", "continu") } ?: "passages"
        return Parametres(
            ids = p("ids").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(1000),
            mode = mode,
            vitesse = p("vitesse").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.coerceIn(1.0, 100_000.0)
                ?: if (mode == "continu") 2000.0 else 30.0,
            titre = p("titre").trim().take(80).ifBlank { null },
            vignettes = p("vignettes").trim() == "1")
    }

    /** What a session's video shows: its passes' scenes (sorted by time), how it plays, the cards asked. */
    class Session(val scenes: List<RenduPc.Scene>, val mode: String, val vitesse: Double, val titre: String? = null, val vignettes: Boolean = false,
                  val ouverture: Boolean = true, val recap: Boolean = true) {
        val fenetres: List<LongRange> = scenes.map { (it.e.debutMs - MARGE_MS)..(it.e.finMs + MARGE_MS) }
        /** The pictures of the session, each once (a picture tied to two passes close together), by the time it was received. */
        val images: List<Pair<Marque, BufferedImage>> = scenes.flatMap { sc ->
            sc.marques.filter { it.type == TypeMarque.SSTV && it.fichier != null }.mapNotNull { m -> sc.images[m.fichier]?.let { m to it } }
        }.distinctBy { it.first.fichier }.sortedBy { it.first.finMs }
        val paysage get() = scenes.firstOrNull()?.paysage ?: true
        val resolution get() = scenes.firstOrNull()?.resolution ?: "M"
        init {
            // A picture shown as long as in a pass's video at this pace (the pace inside the passes).
            scenes.forEach { it.flashMs = (it.flashS * 1000.0 * vitesse).toLong() }
        }
    }

    /** A frame of the session: in a pass ([i], at [t]), between two (« continu », after [i]), or turning to pass [i] (« passages »). */
    sealed class Trame {
        abstract val t: Long
        data class Passe(val i: Int, override val t: Long) : Trame()
        data class Entre(val i: Int, override val t: Long, val k: Double) : Trame()
        data class Tourne(val i: Int, override val t: Long, val k: Double) : Trame()
    }

    /** Every frame of the session's passes, [fps] a second, at [vitesse] (and [HORS] times that between passes in « continu »). */
    fun plan(fenetres: List<LongRange>, mode: String, vitesse: Double, fps: Int = FPS): List<Trame> {
        if (fenetres.isEmpty()) return emptyList()
        val pas = (1000.0 / fps * vitesse).toLong().coerceAtLeast(1)
        val l = ArrayList<Trame>()
        if (mode == "continu") {
            val fin = fenetres.maxOf { it.last }
            var t = fenetres.first().first
            while (true) {
                val i = fenetres.indexOfFirst { t in it }
                if (i >= 0) { l += Trame.Passe(i, t); if (t >= fin) break; t = minOf(t + pas, fin); continue }
                val avant = fenetres.indexOfLast { it.last < t }
                val apres = fenetres.indexOfFirst { it.first > t }
                if (avant < 0 || apres < 0) break
                val de = fenetres[avant].last; val a = fenetres[apres].first
                l += Trame.Entre(avant, t, (t - de).toDouble() / (a - de).coerceAtLeast(1))
                t = minOf(t + (pas * HORS).toLong(), a)
            }
        } else {
            fenetres.forEachIndexed { i, f ->
                if (i > 0) for (k in 1..TOURNE) l += Trame.Tourne(i, f.first, k.toDouble() / (TOURNE + 1))
                var t = f.first
                while (t < f.last) { l += Trame.Passe(i, t); t += pas }
                l += Trame.Passe(i, f.last)
            }
        }
        return l
    }

    // ---------------------------------------------------------------- frames

    private val heure = SimpleDateFormat("EEE d MMM HH:mm", Locale.FRENCH).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun quand(t: Long) = synchronized(heure) { heure.format(Date(t)) } + " UTC"
    private fun jour(t: Long) = SimpleDateFormat("d MMM yyyy", Locale.FRENCH).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun hm(t: Long) = SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    fun recues(n: Int) = when (n) { 0 -> "aucune image reçue"; 1 -> "1 image reçue"; else -> "$n images reçues" }

    private fun slerp(a: DoubleArray, b: DoubleArray, k: Double): DoubleArray {
        val d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
        val ang = acos(d)
        val v = if (ang < 1e-6 || ang > PI - 1e-3) DoubleArray(3) { a[it] + (b[it] - a[it]) * k }
        else { val s = sin(ang); DoubleArray(3) { a[it] * sin((1 - k) * ang) / s + b[it] * sin(k * ang) / s } }
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 1e-9 } ?: return b
        return DoubleArray(3) { v[it] / l }
    }
    private fun doux(k: Double) = (1 - cos(PI * k.coerceIn(0.0, 1.0))) / 2

    /** One frame of the session [w]×[h]. */
    fun image(s: Session, tr: Trame, w: Int, h: Int): BufferedImage {
        val (i, centre) = when (tr) {
            is Trame.Passe -> tr.i to null
            is Trame.Entre -> tr.i to slerp(GlobeRenduPc.centre(s.scenes[tr.i], s.fenetres[tr.i].last),
                GlobeRenduPc.centre(s.scenes[tr.i + 1], s.fenetres[tr.i + 1].first), doux(tr.k))
            is Trame.Tourne -> tr.i to slerp(GlobeRenduPc.centre(s.scenes[tr.i - 1], s.fenetres[tr.i - 1].last),
                GlobeRenduPc.centre(s.scenes[tr.i], s.fenetres[tr.i].first), doux(tr.k))
        }
        val sc = s.scenes[i]
        val dp = RenduPc.echelle(w, h)
        val haut = if (sc.paysage) (h - 100 * dp) else w.toFloat()
        val bande = 44 * dp
        sc.margeBas = if (s.vignettes) bande + 8 * dp else 0f
        sc.centreImpose = centre
        val b = try { RenduPc.image(w, h, sc, tr.t) } finally { sc.centreImpose = null }
        val g = RenduPc.g2(b)
        // Which pass, the pictures so far: under the caption, on the right.
        val k = if (tr is Trame.Entre) tr.i + 2 else i + 1
        val vues = s.images.filter { it.first.finMs <= tr.t }
        val gris = Color(160, 175, 195)
        g.texte("Passage $k / ${s.scenes.size}", w - 14 * dp, haut + 50 * dp, Color.WHITE, RenduPc.police(13 * dp, true), droite = true)
        g.texte(recues(vues.size), w - 14 * dp, haut + 68 * dp, if (vues.isEmpty()) gris else RenduPc.SSTV, RenduPc.police(13 * dp, true), droite = true)
        g.texte(quand(tr.t), w - 66 * dp, haut + 30 * dp, gris, RenduPc.police(13 * dp), droite = true)
        if (s.vignettes && vues.isNotEmpty()) vignettes(g, vues, w.toFloat(), haut - bande - 6 * dp, bande, dp)
        if (tr is Trame.Tourne) carteTourne(g, s, tr, w.toFloat(), haut, dp)
        g.dispose()
        return b
    }

    /** The pictures received so far, small, along the bottom of the view, the newest on the right. */
    private fun vignettes(g: java.awt.Graphics2D, l: List<Pair<Marque, BufferedImage>>, w: Float, y: Float, hv: Float, dp: Float) {
        val lv = hv * 4 / 3
        var x = w - 8 * dp - lv
        for ((m, bm) in l.asReversed()) {
            if (x < 8 * dp - lv * 0.5f) break
            g.drawImage(bm, x.toInt(), y.toInt(), lv.toInt(), hv.toInt(), null)
            g.color = RenduPc.SSTV; g.stroke = BasicStroke(1.5f * dp); g.draw(Rectangle2D.Float(x, y, lv, hv))
            val f = RenduPc.police(8 * dp, true); g.font = f
            val s = SimpleDateFormat("HH:mm", Locale.FRENCH).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(m.finMs))
            val lt = g.fontMetrics.stringWidth(s) + 4 * dp
            g.color = Color(10, 15, 24, 190); g.fill(Rectangle2D.Float(x + 1, y + hv - 11 * dp, lt, 10 * dp))
            g.texte(s, x + 2 * dp + 1, y + hv - 3 * dp, Color.WHITE, f)
            x -= lv + 5 * dp
        }
    }

    /** The next pass's card while the globe turns to it: « Passage 3 / 9 · ven. 24 oct. 07:19 ». */
    private fun carteTourne(g: java.awt.Graphics2D, s: Session, tr: Trame.Tourne, w: Float, haut: Float, dp: Float) {
        val a = sin(PI * tr.k).coerceIn(0.0, 1.0).let { minOf(1.0, it * 1.6) }
        val e = s.scenes[tr.i].e
        val l1 = "Passage ${tr.i + 1} / ${s.scenes.size}"
        val l2 = quand(e.debutMs) + " · %.0f°".format(e.elMax)
        val f1 = RenduPc.police(24 * dp, true); val f2 = RenduPc.police(14 * dp)
        g.font = f1; val lw1 = g.fontMetrics.stringWidth(l1); g.font = f2; val lw2 = g.fontMetrics.stringWidth(l2)
        val lw = maxOf(lw1, lw2) + 40 * dp; val lh = 72 * dp
        val x = (w - lw) / 2; val y = haut / 2 - lh / 2
        g.color = Color(10, 15, 24, (235 * a).toInt()); g.fill(RoundRectangle2D.Float(x, y, lw, lh, 18 * dp, 18 * dp))
        g.color = Color(0, 229, 255, (230 * a).toInt()); g.stroke = BasicStroke(2f * dp); g.draw(RoundRectangle2D.Float(x, y, lw, lh, 18 * dp, 18 * dp))
        g.texte(l1, w / 2, y + 32 * dp, Color(255, 255, 255, (255 * a).toInt()), f1, centre = true)
        g.texte(l2, w / 2, y + 56 * dp, Color(205, 215, 230, (255 * a).toInt()), f2, centre = true)
    }

    /** The session's card: its title (or the satellite and the dates), its passes and pictures, the station. */
    fun titre(s: Session, w: Int, h: Int): BufferedImage {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = RenduPc.g2(b)
        val dp = RenduPc.echelle(w, h)
        g.color = RenduPc.FOND; g.fillRect(0, 0, w, h)
        val e0 = s.scenes.first().e; val e1 = s.scenes.last().e
        val titre = s.titre ?: "Session SSTV · ${e0.satName}"
        var y = h * 0.30f
        var fs = 34f * dp
        g.font = RenduPc.police(fs, true); while (g.fontMetrics.stringWidth(titre) > w - 24 * dp && fs > 12 * dp) { fs -= 2 * dp; g.font = RenduPc.police(fs, true) }
        g.texte(titre, w / 2f, y, Color.WHITE, RenduPc.police(fs, true), centre = true); y += 40 * dp
        val clair = Color(205, 215, 230)
        if (s.titre != null) { g.texte(e0.satName, w / 2f, y, RenduPc.CYAN, RenduPc.police(20 * dp, true), centre = true); y += 30 * dp }
        g.texte(jour(e0.debutMs) + " → " + jour(e1.finMs), w / 2f, y, clair, RenduPc.police(17 * dp), centre = true); y += 28 * dp
        val n = s.scenes.size
        g.texte((if (n == 1) "1 passage" else "$n passages") + " · " + recues(s.images.size), w / 2f, y, RenduPc.SSTV, RenduPc.police(17 * dp, true), centre = true); y += 46 * dp
        val sc = s.scenes.first()
        listOf(sc.indicatif, e0.locator).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
            g.texte(it, w / 2f, y, RenduPc.CYAN, RenduPc.police(20 * dp, true), centre = true)
        }
        MarquePc.dessine(g, w / 2f, h - 26 * dp, 5.2f * dp, centre = true, couleur = RenduPc.CYAN)
        g.dispose()
        return b
    }

    /** The session's pictures together, each with when it was received. */
    fun mosaique(s: Session, w: Int, h: Int): BufferedImage {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = RenduPc.g2(b)
        val dp = RenduPc.echelle(w, h)
        g.color = RenduPc.FOND; g.fillRect(0, 0, w, h)
        val e0 = s.scenes.first().e
        g.texte(s.titre ?: "Session SSTV · ${e0.satName}", 16 * dp, 30 * dp, Color.WHITE, RenduPc.police(20 * dp, true))
        g.texte(jour(e0.debutMs) + " → " + jour(s.scenes.last().e.finMs) + " · " + s.scenes.size + " passages · " + recues(s.images.size),
            16 * dp, 50 * dp, Color(160, 175, 195), RenduPc.police(12 * dp))
        val n = s.images.size
        val x0 = 12 * dp; val y0 = 62 * dp; val lw = w - 2 * x0; val lh = h - y0 - 34 * dp
        val ecart = 6 * dp; val legende = 13 * dp
        if (n > 0) {
            // The columns giving the largest pictures that all fit.
            var mieux = 1; var cote = 0f
            for (c in 1..n) {
                val r = ceil(n / c.toDouble()).toInt()
                val cw = minOf((lw - ecart * (c - 1)) / c, ((lh - r * (legende + ecart)) / r) * 4 / 3)
                if (cw > cote) { cote = cw; mieux = c }
            }
            val ch = cote * 3 / 4
            val r = ceil(n / mieux.toDouble()).toInt()
            val ox = x0 + (lw - (mieux * cote + (mieux - 1) * ecart)) / 2
            val oy = y0 + (lh - r * (ch + legende + ecart)) / 2
            val f = RenduPc.police(minOf(10 * dp, legende * 0.8f))
            s.images.forEachIndexed { k, (m, bm) ->
                val x = ox + (k % mieux) * (cote + ecart); val y = oy + (k / mieux) * (ch + legende + ecart)
                g.drawImage(bm, x.toInt(), y.toInt(), cote.toInt(), ch.toInt(), null)
                g.color = RenduPc.SSTV; g.stroke = BasicStroke(maxOf(1f, 1.5f * dp)); g.draw(Rectangle2D.Float(x, y, cote, ch))
                g.texte(hm(m.finMs), x + cote / 2, y + ch + legende * 0.85f, Color(205, 215, 230), f, centre = true)
            }
        } else g.texte(recues(0).replaceFirstChar { it.uppercase() }, w / 2f, h / 2f, Color(160, 175, 195), RenduPc.police(18 * dp), centre = true)
        MarquePc.dessine(g, w - 14f * dp, h - 10f * dp, 3.2f * dp, couleur = RenduPc.CYAN)
        g.dispose()
        return b
    }

    /** How many frames the video holds: the card, the passes, the mosaic. */
    fun trames(s: Session): Int = (if (s.ouverture) 3 * FPS else 0) + plan(s.fenetres, s.mode, s.vitesse).size + (if (s.recap) 6 * FPS else 0)

    /** The session as an MP4 (H.264, silent) through ffmpeg; null without it or when it failed. */
    fun video(s: Session, sortie: File, progres: (Float) -> Unit): File? {
        val ff = RenduPc.ffmpeg() ?: return null
        if (s.scenes.isEmpty()) return null
        val (w, h) = RenduPc.tailleVideo(s.paysage, s.resolution)
        val plan = plan(s.fenetres, s.mode, s.vitesse)
        val nTitre = if (s.ouverture) 3 * FPS else 0; val nFin = if (s.recap) 6 * FPS else 0
        val total = nTitre + plan.size + nFin
        return RenduPc.encode(ff, w, h, FPS, total, null, sortie) { ecris ->
            var fait = 0
            if (nTitre > 0) { val t = titre(s, w, h); repeat(nTitre) { ecris(t); progres(++fait / total.toFloat()) } }
            for (tr in plan) { ecris(image(s, tr, w, h)); progres(++fait / total.toFloat()) }
            if (nFin > 0) { val m = mosaique(s, w, h); repeat(nFin) { ecris(m); progres(++fait / total.toFloat()) } }
        }
    }
}
