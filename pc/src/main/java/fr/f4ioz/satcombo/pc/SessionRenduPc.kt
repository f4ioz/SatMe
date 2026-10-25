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
 * with the next one's card (« passages ») or the time running on, faster
 * outside the passes (« continu »). Three paces, eased from one to the
 * next: outside, in a pass, and slow while a picture is being received (it
 * is seen being drawn) or a contact is going on. Under the caption the pass
 * and the pictures received so far, a strip of them when asked; the
 * session's card first, the mosaic of its pictures last. No sound.
 */
object SessionRenduPc {

    /** Frames a second: the globe turns smoothly enough between two passes. */
    const val FPS = 8
    const val MARGE_MS = 30_000L
    /** The turn between two passes (« passages »), by default, in seconds. */
    const val TRANSITION_S = 1.5
    /** The old « entre » by default: outside the passes this many times « vitesse ». */
    const val HORS = 10.0
    /** A picture being received, a contact going on: slowed from this long before to this long after (ms). */
    const val AUTOUR_MS = 3_000L
    /** A contact without a span: this long around its moment (ms). */
    const val CONTACT_MS = 20_000L
    /**
     * How fast the pace may change: by e per this many seconds of video (log-linear) — from a
     * pass's ×20 to a picture's ×3 in about 1 s, from ×1000 outside to ×20 in about 2 s.
     */
    const val EASE = 2.0

    /** The turn's frames for [secondes]. */
    fun tourne(secondes: Double) = Math.round(secondes * FPS).toInt().coerceAtLeast(1)

    /** The three paces (real time per video time): outside the passes, in a pass, while a picture or a contact comes. */
    data class Vitesses(val hors: Double = 1000.0, val passage: Double = 20.0, val image: Double = 3.0)

    /**
     * The request's words: the passes (ids, in the page's order), the mode, the paces ([v]), the
     * title, the strip, the turn's length ([transition] s, « passages »).
     */
    data class Parametres(val ids: List<String>, val mode: String, val v: Vitesses, val titre: String?, val vignettes: Boolean,
                          val transition: Double = TRANSITION_S)

    fun parametres(p: (String) -> String): Parametres {
        val mode = p("mode").trim().takeIf { it in setOf("passages", "continu") } ?: "passages"
        fun nombre(k: String) = p(k).trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        // The old words when the new ones are missing: vPassage = vitesse, vHors = vitesse × entre.
        val vitesse = nombre("vitesse")
        val entre = p("entre").trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(1.0, 100.0) ?: HORS
        val d = Vitesses()
        return Parametres(
            ids = p("ids").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(1000),
            mode = mode,
            v = Vitesses(hors = (nombre("vHors") ?: vitesse?.let { it * entre } ?: d.hors).coerceIn(1.0, 20_000.0),
                passage = (nombre("vPassage") ?: vitesse ?: d.passage).coerceIn(1.0, 500.0),
                image = (nombre("vImage") ?: d.image).coerceIn(0.5, 100.0)),
            titre = p("titre").trim().take(80).ifBlank { null },
            vignettes = p("vignettes").trim() == "1",
            transition = p("transition").trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.5, 6.0) ?: TRANSITION_S)
    }

    /** What a session's video shows: its passes' scenes (sorted by time), how it plays, the cards asked. */
    class Session(val scenes: List<RenduPc.Scene>, val mode: String, val v: Vitesses = Vitesses(), val titre: String? = null, val vignettes: Boolean = false,
                  val ouverture: Boolean = true, val recap: Boolean = true, val transition: Double = TRANSITION_S) {
        val fenetres: List<LongRange> = scenes.map { (it.e.debutMs - MARGE_MS)..(it.e.finMs + MARGE_MS) }
        /** The pictures of the session, each once (a picture tied to two passes close together), by the time it was received. */
        val images: List<Pair<Marque, BufferedImage>> = scenes.flatMap { sc ->
            sc.marques.filter { it.type == TypeMarque.SSTV && it.fichier != null }.mapNotNull { m -> sc.images[m.fichier]?.let { m to it } }
        }.distinctBy { it.first.fichier }.sortedBy { it.first.finMs }
        /** When the pace slows to [Vitesses.image]. */
        val actifs: List<LongRange> = actifs(scenes)
        val paysage get() = scenes.firstOrNull()?.paysage ?: true
        val resolution get() = scenes.firstOrNull()?.resolution ?: "M"
        init {
            // A picture received stays shown as long as in a pass's video at the pass's pace.
            scenes.forEach { it.flashMs = (it.flashS * 1000.0 * v.passage).toLong() }
        }
    }

    /**
     * When something is coming: each SSTV picture being received (3 s before to 3 s after),
     * each contact of a pass with its sound recorded (its span and 3 s, or 20 s around it).
     */
    fun actifs(scenes: List<RenduPc.Scene>): List<LongRange> = scenes.flatMap { sc ->
        sc.marques.mapNotNull { m ->
            when {
                m.type == TypeMarque.SSTV -> (m.debutMs - AUTOUR_MS)..(m.finMs + AUTOUR_MS)
                m.type == TypeMarque.QSO && sc.e.enregistrements.isNotEmpty() ->
                    if (m.finMs > m.debutMs) (m.debutMs - AUTOUR_MS)..(m.finMs + AUTOUR_MS) else (m.debutMs - CONTACT_MS / 2)..(m.debutMs + CONTACT_MS / 2)
                else -> null
            }
        }
    }.sortedBy { it.first }

    /** A frame of the session: in a pass ([i], at [t]), between two (« continu », after [i]), or turning to pass [i] (« passages »). */
    sealed class Trame {
        abstract val t: Long
        data class Passe(val i: Int, override val t: Long) : Trame()
        data class Entre(val i: Int, override val t: Long, val k: Double) : Trame()
        data class Tourne(val i: Int, override val t: Long, val k: Double) : Trame()
    }

    /**
     * The pace at each moment: the timeline cut into stretches each with its pace (outside
     * the passes — « continu » only —, in a pass, a picture or a contact coming), and the
     * pace at [t] the lowest of each stretch's plus [EASE] × its distance (s): it slows down
     * before reaching a slower stretch and speeds up after it, by e every 1/[EASE] s of video.
     */
    class Allure(fenetres: List<LongRange>, actifs: List<LongRange>, continu: Boolean, val v: Vitesses) {
        private val morceaux: List<Triple<Long, Long, Double>>
        init {
            val bornes = sortedSetOf<Long>()
            fenetres.forEach { bornes += it.first; bornes += it.last + 1 }
            actifs.forEach { a -> if (fenetres.any { a.first <= it.last && a.last >= it.first }) { bornes += a.first; bornes += a.last + 1 } }
            val l = ArrayList<Triple<Long, Long, Double>>()
            val b = bornes.toList()
            for (k in 0 until b.size - 1) {
                val m = (b[k] + b[k + 1]) / 2
                val dans = fenetres.any { m in it }
                val r = when { dans && actifs.any { m in it } -> v.image; dans -> v.passage; continu -> v.hors; else -> continue }
                val der = l.lastOrNull()
                if (der != null && der.third == r && der.second == b[k]) l[l.size - 1] = Triple(der.first, b[k + 1], r) else l += Triple(b[k], b[k + 1], r)
            }
            morceaux = l
        }
        /** Real time per video time at [t]. */
        fun a(t: Long): Double {
            var r = Double.MAX_VALUE
            for ((de, a, v) in morceaux) {
                val d = if (t < de) de - t else if (t >= a) t - a + 1 else 0L
                r = minOf(r, v + EASE * d / 1000.0)
            }
            return if (r == Double.MAX_VALUE) v.passage else r
        }
    }

    /** Every frame of the session's passes, [fps] a second, each moment at its pace ([Allure]). */
    fun plan(fenetres: List<LongRange>, actifs: List<LongRange>, mode: String, v: Vitesses, fps: Int = FPS, transition: Double = TRANSITION_S): List<Trame> {
        if (fenetres.isEmpty()) return emptyList()
        val allure = Allure(fenetres, actifs, mode == "continu", v)
        fun pas(t: Long) = (1000.0 / fps * allure.a(t)).toLong().coerceAtLeast(1)
        val l = ArrayList<Trame>()
        if (mode == "continu") {
            val fin = fenetres.maxOf { it.last }
            var t = fenetres.first().first
            while (true) {
                val i = fenetres.indexOfFirst { t in it }
                if (i >= 0) { l += Trame.Passe(i, t); if (t >= fin) break; t = minOf(t + pas(t), fin); continue }
                val avant = fenetres.indexOfLast { it.last < t }
                val apres = fenetres.indexOfFirst { it.first > t }
                if (avant < 0 || apres < 0) break
                val de = fenetres[avant].last; val a = fenetres[apres].first
                l += Trame.Entre(avant, t, (t - de).toDouble() / (a - de).coerceAtLeast(1))
                t = minOf(t + pas(t), a)
            }
        } else {
            fenetres.forEachIndexed { i, f ->
                val n = tourne(transition)
                if (i > 0) for (k in 1..n) l += Trame.Tourne(i, f.first, k.toDouble() / (n + 1))
                var t = f.first
                while (t < f.last) { l += Trame.Passe(i, t); t += pas(t) }
                l += Trame.Passe(i, f.last)
            }
        }
        return l
    }

    fun plan(s: Session): List<Trame> = plan(s.fenetres, s.actifs, s.mode, s.v, FPS, s.transition)

    // ---------------------------------------------------------------- frames

    private fun Session.tz(): TimeZone = scenes.firstOrNull()?.fuseau ?: LegendePc.UTC
    private fun Session.k(): Float = scenes.firstOrNull()?.police ?: 1f
    private fun quand(s: Session, t: Long) = LegendePc.format("EEE d MMM HH:mm", s.tz(), t) + " " + LegendePc.zone(s.tz(), t)
    private fun jour(s: Session, t: Long) = LegendePc.format("d MMM yyyy", s.tz(), t)
    private fun hm(s: Session, t: Long) = LegendePc.format("dd/MM HH:mm", s.tz(), t)
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
        val bande = 44 * dp
        sc.margeBas = if (s.vignettes) bande + 8 * dp else 0f
        // Which pass, the pictures so far: in the caption, on the right.
        val k = if (tr is Trame.Entre) tr.i + 2 else i + 1
        val vues = s.images.filter { it.first.finMs <= tr.t }
        sc.legendeSession = Triple("Passage $k / ${s.scenes.size}", recues(vues.size), vues.isNotEmpty())
        val haut = RenduPc.hautVue(w, h, sc).toFloat()
        sc.centreImpose = centre
        val b = try { RenduPc.image(w, h, sc, tr.t) } finally { sc.centreImpose = null }
        val g = RenduPc.g2(b)
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
        val l2 = quand(s, e.debutMs) + " · %.0f°".format(e.elMax)
        // At the asked scale, as wide as the view allows.
        var k = s.k()
        fun f1() = RenduPc.police(24 * dp * k, true)
        fun f2() = RenduPc.police(14 * dp * k)
        while (k > 0.5f && maxOf(g.getFontMetrics(f1()).stringWidth(l1), g.getFontMetrics(f2()).stringWidth(l2)) + 40 * dp * k > w - 16 * dp) k -= 0.05f
        val f1 = f1(); val f2 = f2()
        g.font = f1; val lw1 = g.fontMetrics.stringWidth(l1); g.font = f2; val lw2 = g.fontMetrics.stringWidth(l2)
        val lw = maxOf(lw1, lw2) + 40 * dp * k; val lh = 72 * dp * k
        val x = (w - lw) / 2; val y = haut / 2 - lh / 2
        g.color = Color(10, 15, 24, (235 * a).toInt()); g.fill(RoundRectangle2D.Float(x, y, lw, lh, 18 * dp, 18 * dp))
        g.color = Color(0, 229, 255, (230 * a).toInt()); g.stroke = BasicStroke(2f * dp); g.draw(RoundRectangle2D.Float(x, y, lw, lh, 18 * dp, 18 * dp))
        g.texte(l1, w / 2, y + 32 * dp * k, Color(255, 255, 255, (255 * a).toInt()), f1, centre = true)
        g.texte(l2, w / 2, y + 56 * dp * k, Color(205, 215, 230, (255 * a).toInt()), f2, centre = true)
    }

    /** The session's card: its title (or the satellite and the dates), its passes and pictures, the station. */
    fun titre(s: Session, w: Int, h: Int): BufferedImage {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = RenduPc.g2(b)
        val dp = RenduPc.echelle(w, h)
        g.color = RenduPc.FOND; g.fillRect(0, 0, w, h)
        val e0 = s.scenes.first().e; val e1 = s.scenes.last().e
        val titre = s.titre ?: "Session SSTV · ${e0.satName}"
        // At the asked scale, each line smaller if it would not fit across.
        val k = s.k()
        fun ajuste(t: String, px: Float, gras: Boolean): java.awt.Font {
            var fs = px * k
            while (g.getFontMetrics(RenduPc.police(fs, gras)).stringWidth(t) > w - 24 * dp && fs > 9 * dp) fs -= dp
            return RenduPc.police(fs, gras)
        }
        var y = h * 0.30f - (k - 1) * 50 * dp
        g.texte(titre, w / 2f, y, Color.WHITE, ajuste(titre, 34f * dp, true), centre = true); y += 40 * dp * k
        val clair = Color(205, 215, 230)
        if (s.titre != null) { g.texte(e0.satName, w / 2f, y, RenduPc.CYAN, ajuste(e0.satName, 20 * dp, true), centre = true); y += 30 * dp * k }
        val dates = jour(s, e0.debutMs) + " → " + jour(s, e1.finMs)
        g.texte(dates, w / 2f, y, clair, ajuste(dates, 17 * dp, false), centre = true); y += 28 * dp * k
        val n = s.scenes.size
        val nb = (if (n == 1) "1 passage" else "$n passages") + " · " + recues(s.images.size)
        g.texte(nb, w / 2f, y, RenduPc.SSTV, ajuste(nb, 17 * dp, true), centre = true); y += 46 * dp * k
        val sc = s.scenes.first()
        listOf(sc.indicatif, e0.locator).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
            g.texte(it, w / 2f, y, RenduPc.CYAN, ajuste(it, 20 * dp, true), centre = true)
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
        g.texte(jour(s, e0.debutMs) + " → " + jour(s, s.scenes.last().e.finMs) + " · " + s.scenes.size + " passages · " + recues(s.images.size),
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
                g.texte(hm(s, m.finMs), x + cote / 2, y + ch + legende * 0.85f, Color(205, 215, 230), f, centre = true)
            }
        } else g.texte(recues(0).replaceFirstChar { it.uppercase() }, w / 2f, h / 2f, Color(160, 175, 195), RenduPc.police(18 * dp), centre = true)
        MarquePc.dessine(g, w - 14f * dp, h - 10f * dp, 3.2f * dp, couleur = RenduPc.CYAN)
        g.dispose()
        return b
    }

    /** How many frames the video holds: the card, the passes, the mosaic. */
    fun trames(s: Session): Int = (if (s.ouverture) 3 * FPS else 0) + plan(s).size + (if (s.recap) 6 * FPS else 0)

    /** The session as an MP4 (H.264, silent) through ffmpeg; null without it or when it failed. */
    fun video(s: Session, sortie: File, progres: (Float) -> Unit): File? {
        val ff = RenduPc.ffmpeg() ?: return null
        if (s.scenes.isEmpty()) return null
        val (w, h) = RenduPc.tailleVideo(s.paysage, s.resolution)
        val plan = plan(s)
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
