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
import fr.f4ioz.satcombo.sstv.Gif
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
 * another on the globe, as the page's « Session » plays them — or a single
 * pass, the same way (a session of one). Each pass from some minutes before
 * its AOS to some after its LOS ([Session.avant], [Session.apres], 30 s by
 * default: the satellite seen coming), drawn as the pass's own video draws it
 * ([RenduPc.image], its overlays); between two, a short turn of the globe
 * with the next one's card (« passages ») or the time running on, faster
 * outside the passes (« continu »). Three paces, eased from one to the
 * next: outside, in a pass, and slow while a picture is being received (it
 * is seen being drawn) or a contact is going on. Under the caption the pass
 * and the pictures received so far, a strip of them when asked; the
 * session's card first, the mosaic of its pictures last. Two zooms, eased
 * from one to the other as the pace is: from afar outside the passes, near
 * in a pass ([zoom]). Other satellites going round too when chosen. With
 * the sound asked, a contact heard is played in real time with its sound,
 * the rest silent ([son]); also as a GIF, or one moment as a picture.
 */
object SessionRenduPc {

    /** Frames a second: the globe turns smoothly enough between two passes. */
    const val FPS = 8
    /** Before the AOS and after the LOS, by default (ms); at most [MARGE_MAX_MS]. */
    const val MARGE_MS = 30_000L
    const val MARGE_MAX_MS = 30 * 60_000L
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
    /** The zooms by default (the globe's size, 1: 85 % of the view's height): outside the passes, in a pass. */
    const val Z_HORS = 0.9
    const val Z_PASSAGE = 1.8
    /** From one zoom to the other in about this many seconds of video, before and after a pass. */
    const val ZOOM_S = 1.5

    /** The turn's frames for [secondes]. */
    fun tourne(secondes: Double) = Math.round(secondes * FPS).toInt().coerceAtLeast(1)

    /** The three paces (real time per video time): outside the passes, in a pass, while a picture or a contact comes. */
    data class Vitesses(val hors: Double = 1000.0, val passage: Double = 20.0, val image: Double = 3.0)

    /**
     * The request's words: the passes (ids, in the page's order), the mode, the paces ([v]), the
     * title, the strip, the turn's length ([transition] s, « passages »).
     */
    data class Parametres(val ids: List<String>, val mode: String, val v: Vitesses, val titre: String?, val vignettes: Boolean,
                          val transition: Double = TRANSITION_S, val zHors: Double = Z_HORS, val zPassage: Double = Z_PASSAGE,
                          /** The companions (satellites' names), their footprints or not. */
                          val autres: List<String> = emptyList(), val tachesAutres: Boolean = false,
                          /** What is made: « video », « gif », « image » (the moment [instant]), « son »; the contacts' sound or not. */
                          val quoi: String = "video", val instant: Long? = null, val son: Boolean = false,
                          /** Before each AOS, after each LOS (ms). */
                          val avant: Long = MARGE_MS, val apres: Long = MARGE_MS)

    /** A margin (ms) from « margeAvant » / « margeApres », else « avant » / « apres »: 0 to 30 min, 30 s when missing or wrong. */
    fun marge(p: (String) -> String, nom: String, ancien: String): Long =
        (p(nom).trim().ifEmpty { p(ancien).trim() }).toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()?.coerceIn(0L, MARGE_MAX_MS) ?: MARGE_MS

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
            transition = p("transition").trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.5, 6.0) ?: TRANSITION_S,
            zHors = nombre("zHors")?.coerceIn(0.5, 3.0) ?: Z_HORS, zPassage = nombre("zPassage")?.coerceIn(0.5, 3.0) ?: Z_PASSAGE,
            autres = p("autres").split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(GlobeRenduPc.COULEURS_AUTRES.size),
            tachesAutres = p("tachesAutres").trim() == "1",
            quoi = p("quoi").trim().takeIf { it in setOf("video", "gif", "image", "son") } ?: "video",
            instant = p("t").trim().toLongOrNull(), son = p("avecSon").trim() == "1",
            avant = marge(p, "margeAvant", "avant"), apres = marge(p, "margeApres", "apres"))
    }

    /** What a session's video shows: its passes' scenes (sorted by time), how it plays, the cards asked. */
    class Session(val scenes: List<RenduPc.Scene>, val mode: String, val v: Vitesses = Vitesses(), val titre: String? = null, val vignettes: Boolean = false,
                  val ouverture: Boolean = true, val recap: Boolean = true, val transition: Double = TRANSITION_S,
                  val zHors: Double = Z_HORS, val zPassage: Double = Z_PASSAGE,
                  val autres: List<GlobeRenduPc.Autre> = emptyList(), val tachesAutres: Boolean = false, val son: Boolean = false,
                  val avant: Long = MARGE_MS, val apres: Long = MARGE_MS) {
        val fenetres: List<LongRange> = scenes.map { (it.e.debutMs - avant.coerceIn(0L, MARGE_MAX_MS))..(it.e.finMs + apres.coerceIn(0L, MARGE_MAX_MS)) }
        /** A single pass: its own title, cards and summary, its own caption (as its video has them), the session's pace and zooms. */
        val seul get() = scenes.size == 1
        /** The pictures of the session, each once (a picture tied to two passes close together), by the time it was received. */
        val images: List<Pair<Marque, BufferedImage>> = scenes.flatMap { sc ->
            sc.marques.filter { it.type == TypeMarque.SSTV && it.fichier != null }.mapNotNull { m -> sc.images[m.fichier]?.let { m to it } }
        }.distinctBy { it.first.fichier }.sortedBy { it.first.finMs }
        /** When the pace slows to [Vitesses.image]. */
        val actifs: List<LongRange> = actifs(scenes)
        /** With the sound: when a contact is heard, played in real time. */
        val ecoute: List<LongRange> = if (son) ecoute(scenes) else emptyList()
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

    /** The contacts heard (their pass has its sound): their span and 3 s, or 20 s around them. */
    fun ecoute(scenes: List<RenduPc.Scene>): List<LongRange> = scenes.flatMap { sc ->
        if (sc.e.enregistrements.isEmpty()) emptyList()
        else sc.marques.filter { it.type == TypeMarque.QSO }.map { m ->
            if (m.finMs > m.debutMs) (m.debutMs - AUTOUR_MS)..(m.finMs + AUTOUR_MS) else (m.debutMs - CONTACT_MS / 2)..(m.debutMs + CONTACT_MS / 2)
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
    class Allure(fenetres: List<LongRange>, actifs: List<LongRange>, continu: Boolean, val v: Vitesses, ecoute: List<LongRange> = emptyList()) {
        private val morceaux: List<Triple<Long, Long, Double>>
        init {
            val bornes = sortedSetOf<Long>()
            fenetres.forEach { bornes += it.first; bornes += it.last + 1 }
            (actifs + ecoute).forEach { a -> if (fenetres.any { a.first <= it.last && a.last >= it.first }) { bornes += a.first; bornes += a.last + 1 } }
            val l = ArrayList<Triple<Long, Long, Double>>()
            val b = bornes.toList()
            for (k in 0 until b.size - 1) {
                val m = (b[k] + b[k + 1]) / 2
                val dans = fenetres.any { m in it }
                val r = when { dans && ecoute.any { m in it } -> minOf(1.0, v.image); dans && actifs.any { m in it } -> v.image; dans -> v.passage; continu -> v.hors; else -> continue }
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
    fun plan(fenetres: List<LongRange>, actifs: List<LongRange>, mode: String, v: Vitesses, fps: Int = FPS, transition: Double = TRANSITION_S,
             ecoute: List<LongRange> = emptyList()): List<Trame> {
        if (fenetres.isEmpty()) return emptyList()
        val allure = Allure(fenetres, actifs, mode == "continu", v, ecoute)
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

    fun plan(s: Session): List<Trame> = plan(s.fenetres, s.actifs, s.mode, s.v, FPS, s.transition, s.ecoute)
    fun allure(s: Session) = Allure(s.fenetres, s.actifs, s.mode == "continu", s.v, s.ecoute)

    // ---------------------------------------------------------------- the zoom

    /**
     * How much of the pass's zoom at [t] (1 in a pass and its margins, 0 far from them): by the
     * video's seconds still to go to the nearest pass at its pace slowing down ([Allure]: its
     * pace plus [EASE] per second away, ln((p + 2d) / p) / 2 s), over [ZOOM_S] — the camera comes
     * near before the AOS, goes away after the LOS, however fast the time runs between. The
     * journal page works it out the same way.
     */
    fun partPassage(fenetres: List<LongRange>, t: Long, vPassage: Double): Double {
        if (fenetres.isEmpty()) return 1.0
        val d = fenetres.minOf { f -> if (t < f.first) f.first - t else if (t > f.last) t - f.last else 0L } / 1000.0
        val s = kotlin.math.ln((vPassage + EASE * d) / vPassage) / EASE
        return (1 - s / ZOOM_S).coerceIn(0.0, 1.0)
    }

    /** The globe's size from the part of the pass's zoom [k]: eased, from [zHors] to [zPassage] (geometrically, as a camera comes nearer). */
    fun zoom(zHors: Double, zPassage: Double, k: Double): Double = zHors * Math.pow(zPassage / zHors, doux(k))

    /** The globe's size for a frame: as [partPassage] says; turning from one pass to the next (« passages »), out and back in. */
    fun zoom(s: Session, tr: Trame): Double = zoom(s.zHors, s.zPassage, when (tr) {
        is Trame.Tourne -> 1 - sin(PI * tr.k)
        else -> partPassage(s.fenetres, tr.t, s.v.passage)
    })

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
        sc.legendeSession = if (s.seul) null else Triple("Passage $k / ${s.scenes.size}", recues(vues.size), vues.isNotEmpty())
        val haut = RenduPc.hautVue(w, h, sc).toFloat()
        sc.centreImpose = centre; sc.gzImpose = zoom(s, tr).toFloat(); sc.autres = s.autres; sc.tachesAutres = s.tachesAutres
        val b = try { RenduPc.image(w, h, sc, tr.t) } finally { sc.centreImpose = null; sc.gzImpose = null; sc.autres = emptyList() }
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

    /**
     * Before the passes, each drawing with its frames: the session's card 3 s; a single pass, its own title 3 s
     * and the report's opening cards 4 s each, as its video has them.
     */
    fun debut(s: Session, w: Int, h: Int): List<Pair<() -> BufferedImage, Int>> {
        val sc = s.scenes.firstOrNull() ?: return emptyList()
        val titre: List<Pair<() -> BufferedImage, Int>> = if (!s.ouverture) emptyList()
            else listOf((if (s.seul) { -> RenduPc.titre(w, h, sc) } else { -> titre(s, w, h) }) to 3 * FPS)
        return titre + if (s.seul) RenduPc.ouvertureVideo(w, h, sc, 4 * FPS) else emptyList()
    }

    /** After the passes: the mosaic of the pictures 6 s; a single pass, its pictures 3 s each, its summary 6 s and the report's cards, as its video. */
    fun fin(s: Session, w: Int, h: Int): List<Pair<() -> BufferedImage, Int>> =
        if (s.seul) RenduPc.finVideo(w, h, s.scenes[0], 3 * FPS, 6 * FPS, recap = s.recap)
        else if (s.recap) listOf({ -> mosaique(s, w, h) } to 6 * FPS) else emptyList()

    /** How many frames the video holds: the card, the passes, the mosaic. */
    fun trames(s: Session): Int = debut(s, 1, 1).sumOf { it.second } + plan(s).size + fin(s, 1, 1).sumOf { it.second }

    /** The session as an MP4 (H.264, with [son]'s sound as AAC when given) through ffmpeg; null without it or when it failed. */
    fun video(s: Session, sortie: File, son: Son? = null, travail: File? = null, progres: (Float) -> Unit): File? {
        val ff = RenduPc.ffmpeg() ?: return null
        if (s.scenes.isEmpty()) return null
        val (w, h) = RenduPc.tailleVideo(s.paysage, s.resolution)
        val plan = plan(s)
        val avantP = debut(s, w, h); val apresP = fin(s, w, h)
        val total = avantP.sumOf { it.second } + plan.size + apresP.sumOf { it.second }
        val wav = if (son != null && travail != null) File(travail, "session-" + System.nanoTime() + ".wav").also { RenduPc.ecritWav(source(s, plan, son), son.rate, it) } else null
        try {
            return RenduPc.encode(ff, w, h, FPS, total, wav, sortie) { ecris ->
                var fait = 0
                for ((dessin, n) in avantP) { val img = dessin(); repeat(n) { ecris(img); progres(++fait / total.toFloat()) } }
                for (tr in plan) { ecris(image(s, tr, w, h)); progres(++fait / total.toFloat()) }
                for ((dessin, n) in apresP) { val img = dessin(); repeat(n) { ecris(img); progres(++fait / total.toFloat()) } }
            }
        } finally { wav?.delete() }
    }

    // ---------------------------------------------------------------- the sound

    /**
     * The passes' sounds, each from its window's start ([debuts], ms) at its rate; [rate]: the
     * video's. A pass without its sound is not in [sons].
     */
    class Son(val sons: Map<Int, fr.f4ioz.satcombo.domain.SonPassage>, val debuts: Map<Int, Long>, val rate: Int)

    /**
     * The session's sound as its video plays it: silence for its card, then, frame by frame,
     * the pass's sound where a contact is heard in real time (its pace 1), silence elsewhere
     * (the time running faster), the mosaic silent.
     */
    fun source(s: Session, plan: List<Trame>, son: Son): fr.f4ioz.satcombo.domain.SourcePcm {
        val allure = allure(s)
        val nTitre = debut(s, 1, 1).sumOf { it.second }
        val r = son.rate.toLong()
        fun debut(n: Int) = n * r / FPS
        var n = 0; var dans = 0
        var cur = ShortArray(0)
        fun prepare(k: Int) {
            val l = (debut(k + 1) - debut(k)).toInt()
            cur = ShortArray(l)
            val tr = plan.getOrNull(k - nTitre) as? Trame.Passe ?: return
            if (allure.a(tr.t) > 1.0001 || s.ecoute.none { tr.t in it }) return
            val sp = son.sons[tr.i] ?: return
            val de = son.debuts[tr.i] ?: return
            // The pass's own rate: its samples taken to the video's (the nearest one).
            val pos = (tr.t - de) * sp.rate / 1000
            val m = (l.toLong() * sp.rate / r).toInt().coerceAtLeast(1)
            val src = ShortArray(m); sp.lis(maxOf(0L, pos), src, m)
            for (i in 0 until l) cur[i] = src[(i.toLong() * m / l).toInt().coerceIn(0, m - 1)]
        }
        val nb = nTitre + plan.size
        prepare(0)
        return fr.f4ioz.satcombo.domain.SourcePcm { buf ->
            var k = 0
            while (k < buf.size && n < nb) {
                val c = minOf(buf.size - k, cur.size - dans)
                System.arraycopy(cur, dans, buf, k, c); k += c; dans += c
                if (dans >= cur.size) { n++; dans = 0; if (n < nb) prepare(n) }
            }
            k
        }
    }

    // ---------------------------------------------------------------- a GIF, a picture

    /** The frame showing the moment [t]: in the pass round it, or between two. */
    fun trameA(s: Session, t: Long): Trame {
        val f = s.fenetres
        val i = f.indexOfFirst { t in it }
        if (i >= 0) return Trame.Passe(i, t)
        val avant = f.indexOfLast { it.last < t }; val apres = f.indexOfFirst { it.first > t }
        if (avant < 0) return Trame.Passe(0, f.first().first)
        if (apres < 0) return Trame.Passe(f.size - 1, f.last().last)
        val de = f[avant].last; val a = f[apres].first
        return Trame.Entre(avant, t, (t - de).toDouble() / (a - de).coerceAtLeast(1))
    }

    /** The moment [t] of the session as a PNG picture, at the size of a pass's. */
    fun png(s: Session, t: Long, sortie: File): File {
        val (w, h) = if (s.paysage) 1920 to 1080 else 1080 to 1440
        javax.imageio.ImageIO.write(image(s, trameA(s, t), w, h), "png", sortie)
        return sortie
    }

    /** At most this many frames in a GIF (the session's frames taken one in so many). */
    const val GIF_MAX = 700

    /** The session as an animated GIF, as its video plays it (fewer frames, each longer), looping. */
    fun gif(s: Session, sortie: File, progres: (Float) -> Unit): File? {
        if (s.scenes.isEmpty()) return null
        val (w, h) = if (s.paysage) 640 to 360 else 480 to 640
        val plan = plan(s)
        val pas = ceil(plan.size / GIF_MAX.toDouble()).toInt().coerceAtLeast(1)
        // A GIF's delay in hundredths of a second, 2 at least (the browsers' floor).
        val delai = maxOf(2, Math.round(100.0 * pas / FPS).toInt())
        val choisies = plan.indices.filter { it % pas == 0 || it == plan.size - 1 }
        fun px(img: BufferedImage) = img.getRGB(0, 0, w, h, null, 0, w)
        // The card(s) before and after, each held as long as in the video (in hundredths of a second).
        val avantP = debut(s, w, h).map { (d, n) -> px(d()) to n * 100 / FPS }
        val apresP = fin(s, w, h).map { (d, n) -> px(d()) to n * 100 / FPS }
        var pourPalette = px(image(s, plan[plan.size / 2], w, h))
        avantP.firstOrNull()?.let { pourPalette += it.first }; apresP.firstOrNull()?.let { pourPalette += it.first }; apresP.lastOrNull()?.let { pourPalette += it.first }
        val pal = Gif.palette(pourPalette, listOf(0x0A0F18, 0x111B29, 0xFFFFFF, RenduPc.CYAN.rgb and 0xFFFFFF, RenduPc.SSTV.rgb and 0xFFFFFF, RenduPc.ISS.rgb and 0xFFFFFF))
        sortie.outputStream().buffered().use { o ->
            val g = Gif.Ecrivain(o, w, h, pal)
            avantP.forEach { (pixels, cs) -> g.trame(pixels, 0, h, cs) }
            var avant: IntArray? = null
            choisies.forEachIndexed { n, k ->
                val cur = px(image(s, plan[k], w, h))
                val prev = avant
                var y0 = 0; var y1 = h
                if (prev != null) {
                    fun egale(y: Int): Boolean { val b = y * w; for (x in 0 until w) if (prev[b + x] != cur[b + x]) return false; return true }
                    while (y0 < h && egale(y0)) y0++
                    while (y1 > y0 && egale(y1 - 1)) y1--
                    if (y0 >= y1) { y0 = 0; y1 = 1 }
                }
                g.trame(cur, y0, y1, delai)
                avant = cur
                progres((n + 1f) / choisies.size)
            }
            apresP.forEach { (pixels, cs) -> g.trame(pixels, 0, h, cs) }
            g.fin()
        }
        return sortie
    }
}
