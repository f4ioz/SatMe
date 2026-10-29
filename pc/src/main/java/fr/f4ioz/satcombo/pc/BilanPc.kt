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
import fr.f4ioz.satcombo.domain.JournalPassage.TypeMarque
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.pc.RenduPc.texte
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The journal's closing card (« Finir sur un bilan »), the same in the page's preview (« /bilan ») and at the end of
 * the video: the pass or the session (the satellite, when, the station), its figures (how long, how high, pictures,
 * contacts, stations heard, locators, FT8 decodes, distances), its track in the sky, and its pictures near their own
 * size — an SSTV picture is 320 pixels wide: never enlarged more than [AGRANDI_MAX] (smoothly made smaller when
 * needed), never stretched, laid out as a tidy grid with a thin frame and their time, mode and number under each.
 * Drawn for 16:9 as for 9:16 and 3:4.
 */
object BilanPc {

    /** How long the card stays on screen by default (s), and the bounds of « Durée du bilan ». */
    const val DUREE_S = 7.0
    const val DUREE_MIN_S = 3.0
    const val DUREE_MAX_S = 30.0
    /** A picture is never enlarged more than this (a low-resolution SSTV picture blown up is blurred). */
    const val AGRANDI_MAX = 1.5f
    /** Under this enlargement a picture is shown at its own size, pixel for pixel (the sharpest). */
    const val NATIF_SOUS = 1.15f
    /** At most this many pictures on the card (spread over the session when more came). */
    const val PHOTOS_MAX = 16

    /** « Durée du bilan » asked (s): [DUREE_S] when missing or wrong. */
    fun duree(p: (String) -> String): Double =
        p("dureeBilan").trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(DUREE_MIN_S, DUREE_MAX_S) ?: DUREE_S

    // ---------------------------------------------------------------- the layout

    data class Rect(val x: Float, val y: Float, val l: Float, val h: Float) {
        val droite get() = x + l
        val bas get() = y + h
        fun contient(o: Rect, eps: Float = 0.51f) = o.x >= x - eps && o.y >= y - eps && o.droite <= droite + eps && o.bas <= bas + eps
    }

    /** A picture's place: where (its top-left corner), its size on the card, its enlargement (1: pixel for pixel). */
    data class Place(val x: Float, val y: Float, val l: Float, val h: Float, val echelle: Float) {
        val rect get() = Rect(x, y, l, h)
    }

    /** The enlargement kept from the one that fits [s]: [max] at most, its own size when just above it ([NATIF_SOUS]). */
    fun echelle(s: Float, max: Float = AGRANDI_MAX): Float {
        val m = minOf(s, max)
        return if (m > 1f && m < NATIF_SOUS) 1f else m
    }

    /**
     * Pictures of sizes [tailles] (px) laid out in a [l]×[h] area, a caption [legende] high under each, [ecart]
     * between them: the grid (columns) that shows them largest, each as large as its cell allows but [max] times its
     * own size at most, its proportions kept; the grid made as tight as its pictures and centred, its last row too, each
     * picture on its row's bottom line (the captions in a line). Places from the area's top-left corner; none when it
     * is too small for any.
     */
    fun mosaique(tailles: List<Pair<Int, Int>>, l: Float, h: Float, ecart: Float, legende: Float, max: Float = AGRANDI_MAX): List<Place> {
        val n = tailles.size
        if (n == 0 || l <= 0 || h <= 0) return emptyList()
        var mieux: Pair<Int, List<Float>>? = null; var score = -1.0
        for (c in 1..n) {
            val r = ceil(n / c.toDouble()).toInt()
            val cw = (l - ecart * (c - 1)) / c; val ch = (h - r * legende - ecart * (r - 1)) / r
            if (cw < 8 || ch < 8) continue
            val ech = tailles.map { (iw, ih) -> echelle(minOf(cw / iw.coerceAtLeast(1), ch / ih.coerceAtLeast(1)), max) }
            // The area shown, the pictures together; a grid as large as another, the one with fewer empty cells.
            val s = tailles.indices.sumOf { k -> (ech[k] * tailles[k].first).toDouble() * (ech[k] * tailles[k].second) } - (c * r - n) * 1e-3
            if (s > score * 1.0001) { score = s; mieux = c to ech }
        }
        val (c, ech) = mieux ?: return emptyList()
        val r = ceil(n / c.toDouble()).toInt()
        val pl = tailles.indices.map { k -> (tailles[k].first * ech[k]).roundToInt().coerceAtLeast(1) to (tailles[k].second * ech[k]).roundToInt().coerceAtLeast(1) }
        // Rounded to whole pixels: never past the cell.
        val cw0 = (l - ecart * (c - 1)) / c; val ch0 = (h - r * legende - ecart * (r - 1)) / r
        val cw = minOf(cw0, pl.maxOf { it.first }.toFloat()); val ch = minOf(ch0, pl.maxOf { it.second }.toFloat())
        val gh = r * (ch + legende) + (r - 1) * ecart
        val oy = (h - gh) / 2
        return tailles.indices.map { k ->
            val ligne = k / c; val col = k % c
            val dansLigne = if (ligne == r - 1) n - (r - 1) * c else c
            val lw = dansLigne * cw + (dansLigne - 1) * ecart
            val ox = (l - lw) / 2
            val (pw, ph) = pl[k]
            val w = minOf(pw.toFloat(), cw); val hh = minOf(ph.toFloat(), ch)
            Place(ox + col * (cw + ecart) + (cw - w) / 2, oy + ligne * (ch + legende + ecart) + (ch - hh), w, hh, ech[k])
        }
    }

    /** Where everything goes on a [w]×[h] card: the title, the figures, the sky, the pictures (null: none), the foot. */
    class Plan(val u: Float, val marge: Float, val entete: Rect, val chiffres: Rect, val colonnes: Int, val tuile: Float,
               val ciel: Rect?, val photos: Rect?, val pied: Rect)

    /** The card's plan for [nChiffres] figures, with pictures or not, with the sky's track or not. */
    fun plan(w: Int, h: Int, nChiffres: Int, avecPhotos: Boolean, avecCiel: Boolean = true): Plan {
        val u = minOf(w, h) / 360f
        val m = 18 * u
        val entete = Rect(m, m, w - 2 * m, 62 * u)
        val pied = Rect(m, h - m - 14 * u, w - 2 * m, 14 * u)
        val haut = entete.bas + 12 * u; val bas = pied.y - 10 * u
        val ecart = 6 * u
        val paysage = w >= h
        fun hauteur(cols: Int, tuile: Float) = ceil(nChiffres.coerceAtLeast(1) / cols.toDouble()).toFloat() * (tuile + ecart) - ecart
        return if (paysage) {
            if (avecPhotos) {
                val col = (w - 2 * m) * 0.33f
                // The figures in two columns, in three (lower tiles) when the sky would be left too small under them.
                val (cols, tuile) = listOf(2 to 34 * u, 2 to 28 * u, 3 to 28 * u)
                    .firstOrNull { (c, t) -> !avecCiel || bas - (haut + hauteur(c, t) + 12 * u) >= 110 * u } ?: (3 to 28 * u)
                val fh = hauteur(cols, tuile)
                val chiffres = Rect(m, haut, col, fh)
                val reste = bas - (chiffres.bas + 12 * u)
                val ciel = if (avecCiel && reste > 60 * u) minOf(col, reste).let { s -> Rect(m + (col - s) / 2, chiffres.bas + 12 * u + (reste - s) / 2, s, s) } else null
                Plan(u, m, entete, chiffres, cols, tuile, ciel, Rect(m + col + 18 * u, haut, w - 2 * m - col - 18 * u, bas - haut), pied)
            } else {
                val col = (w - 2 * m) * 0.5f
                val tuile = 40 * u
                val fh = hauteur(2, tuile)
                val chiffres = Rect(m, haut + maxOf(0f, (bas - haut - fh) / 2), col, fh)
                val s = minOf(w - 2 * m - col - 18 * u, bas - haut)
                val ciel = if (avecCiel) Rect(m + col + 18 * u + (w - 2 * m - col - 18 * u - s) / 2, haut + (bas - haut - s) / 2, s, s) else null
                Plan(u, m, entete, if (ciel == null) chiffres.copy(x = (w - col) / 2f) else chiffres, 2, tuile, ciel, null, pied)
            }
        } else {
            if (avecPhotos) {
                val s = if (avecCiel) minOf((w - 2 * m) * 0.40f, 140 * u) else 0f
                val tuile = 34 * u
                val fl = w - 2 * m - (if (avecCiel) s + 12 * u else 0f)
                val fh = hauteur(2, tuile)
                val rang = maxOf(fh, s)
                val chiffres = Rect(m, haut + (rang - fh) / 2, fl, fh)
                val ciel = if (avecCiel) Rect(w - m - s, haut + (rang - s) / 2, s, s) else null
                val ph = haut + rang + 16 * u
                Plan(u, m, entete, chiffres, 2, tuile, ciel, Rect(m, ph, w - 2 * m, bas - ph), pied)
            } else {
                val tuile = 40 * u
                val fh = hauteur(2, tuile)
                val chiffres = Rect(m, haut, w - 2 * m, fh)
                val reste = bas - (chiffres.bas + 16 * u)
                val s = minOf(w - 2 * m, reste)
                val ciel = if (avecCiel && s > 60 * u) Rect((w - s) / 2, chiffres.bas + 16 * u + (reste - s) / 2, s, s) else null
                Plan(u, m, entete, if (ciel == null) chiffres.copy(y = haut + maxOf(0f, (bas - haut - fh) / 2)) else chiffres, 2, tuile, ciel, null, pied)
            }
        }
    }

    // ---------------------------------------------------------------- what it shows

    class Chiffre(val valeur: String, val libelle: String, val couleur: Color)
    class Photo(val image: BufferedImage, val heure: String, val mode: String, val numero: String)
    /** A pass in the sky: its track (az, el), its pictures' stretches, its contacts. */
    class Trace(val points: List<Pair<Double, Double>>, val sstv: List<List<Pair<Double, Double>>>, val qsos: List<Pair<Double, Double>>)

    class Donnees(val titre: String, val sousTitre: String?, val genre: String, val quand: String, val station: String, val indicatif: String,
                  val chiffres: List<Chiffre>, val traces: List<Trace>, val photos: List<Photo>, val police: Float = 1f)

    private fun duree(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d h %02d".format(s / 3600, s / 60 % 60) else if (s >= 60) "%d min %02d s".format(s / 60, s % 60) else "$s s"
    }
    private fun km(n: Int) = String.format(java.util.Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ')

    /** The track of [e] between [de] and [a] (the points in it, its ends interpolated). */
    private fun morceau(e: JournalPassage.Entree, de: Long, a: Long): List<Pair<Double, Double>> {
        val p = e.points
        if (p.size < 2) return emptyList()
        fun en(t: Long): Pair<Double, Double> {
            val k = p.indexOfFirst { it.tMs >= t }
            if (k <= 0) return p.first().let { it.az to it.el }
            val x = p[k - 1]; val y = p[k]
            val f = (t - x.tMs).toDouble() / (y.tMs - x.tMs).coerceAtLeast(1)
            var daz = y.az - x.az; if (daz > 180) daz -= 360; if (daz < -180) daz += 360
            return ((x.az + daz * f + 360) % 360) to x.el + (y.el - x.el) * f
        }
        return listOf(en(de)) + p.filter { it.tMs in (de + 1) until a }.map { it.az to it.el } + listOf(en(a))
    }

    /** The ARISS slide a picture is (its number, of how many), as its sidecar says (read on it, or set by hand); null: unknown. */
    private fun diapo(fichier: String?): Pair<Int, Int>? = runCatching {
        val f = java.io.File(fichier ?: return null)
        val side = java.io.File(f.parentFile, fr.f4ioz.satcombo.sstv.SstvMeta.sidecarName(f.name)).takeIf { it.isFile } ?: return null
        val s = fr.f4ioz.satcombo.sstv.SstvMeta.decode(f.name, side.readText(Charsets.UTF_8))
        if (s.diapo > 0) s.diapo to s.diapos.takeIf { it > 0 }.let { it ?: 12 } else null
    }.getOrNull()

    /** What the card shows for [scenes] (one pass, or a session's), [titre] asked or not. */
    fun donnees(scenes: List<RenduPc.Scene>, titre: String?): Donnees {
        val sc0 = scenes.first(); val e0 = sc0.e; val eN = scenes.last().e
        val tz: TimeZone = sc0.fuseau
        val seul = scenes.size == 1
        val zone = LegendePc.zone(tz, e0.debutMs)
        val quand = if (seul) LegendePc.format("EEE d MMM yyyy", tz, e0.debutMs) + " · " + LegendePc.format("HH:mm", tz, e0.debutMs) + " → " +
            LegendePc.format("HH:mm", tz, e0.finMs) + " " + zone
        else LegendePc.format("d MMM", tz, e0.debutMs) + " → " + LegendePc.format("d MMM yyyy", tz, eN.finMs)
        val marques = scenes.flatMap { sc -> sc.marques.map { sc to it } }
        // The pictures once each, by when they came.
        val toutes = scenes.flatMap { sc ->
            sc.marques.filter { it.type == TypeMarque.SSTV && it.fichier != null }.mapNotNull { m -> sc.images[m.fichier]?.let { m to it } }
        }.distinctBy { it.first.fichier }.sortedBy { it.first.finMs }
        val n = toutes.size
        val choisies = if (n <= PHOTOS_MAX) toutes.indices.toList() else (0 until PHOTOS_MAX).map { (it * (n - 1).toDouble() / (PHOTOS_MAX - 1)).roundToInt() }.distinct()
        val photos = choisies.map { k ->
            val (m, bm) = toutes[k]
            // A session's over several days: the day too.
            Photo(bm, LegendePc.format(if (seul) "HH:mm" else "dd/MM HH:mm", tz, m.finMs) + " " + LegendePc.zone(tz, m.finMs), m.texte.trim(),
                diapo(m.fichier)?.let { (d, nb) -> t("journal_bilan_diapo").format(d, nb) } ?: "${k + 1}/$n")
        }
        val qsos = marques.filter { it.second.type == TypeMarque.QSO }.map { it.second.texte.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        val aprs = marques.filter { it.second.type == TypeMarque.APRS && !JournalPassage.estLeSatellite(it.second.texte) }
            .map { JournalPassage.indicatifDeBase(it.second.texte) }.filter { it.isNotEmpty() }.distinct()
        val fiches = marques.filter { it.second.type == TypeMarque.QSO || it.second.type == TypeMarque.APRS }
        val locators = fiches.mapNotNull { (sc, m) -> sc.fiches[m.texte]?.locator?.trim()?.uppercase()?.takeIf { it.length >= 4 }?.take(4) }.distinct()
        val ici = e0.locator.ifBlank { null }
        val distances = if (ici == null) emptyList() else fiches.mapNotNull { (sc, m) ->
            sc.fiches[m.texte]?.locator?.takeIf { it.isNotBlank() }?.let { JournalPassage.distanceKm(ici, it) }
                ?: m.lat?.let { la -> m.lon?.let { lo -> fr.f4ioz.satcombo.location.Maidenhead.centre(ici)?.let { (a, b) ->
                    fr.f4ioz.satcombo.sonde.Geo.distanceKm(a, b, la, lo).roundToInt() } } }
        }.filter { it in 1..20_000 }
        val ft = scenes.sumOf { it.ft.size }
        val c = ArrayList<Chiffre>()
        val gris = Color(205, 215, 230)
        if (!seul) c += Chiffre(scenes.size.toString(), t("journal_bilan_passages"), RenduPc.CYAN)
        c += Chiffre(duree(scenes.sumOf { it.e.dureeMs }), t("journal_bilan_duree"), gris)
        scenes.maxOf { it.e.elMax }.takeIf { it > 0 }?.let { c += Chiffre("%.0f°".format(it), t("journal_bilan_elevation"), RenduPc.CYAN) }
        if (n > 0) c += Chiffre(n.toString(), t("journal_bilan_images"), RenduPc.SSTV)
        if (qsos.isNotEmpty()) c += Chiffre(qsos.size.toString(), t("journal_bilan_contacts"), RenduPc.QSO)
        if (aprs.isNotEmpty()) c += Chiffre(aprs.size.toString(), t("journal_bilan_aprs"), RenduPc.APRS)
        if (locators.isNotEmpty()) c += Chiffre(locators.size.toString(), t("journal_bilan_locators"), RenduPc.SIGNET)
        if (ft > 0) c += Chiffre(ft.toString(), t("journal_bilan_ft"), Color(0x7CD992))
        if (distances.isNotEmpty()) {
            val a = distances.min(); val b = distances.max()
            c += Chiffre(if (a == b || distances.size == 1) km(b) + " km" else km(a) + "–" + km(b) + " km", t("journal_bilan_distances"), RenduPc.ISS)
        }
        val traces = scenes.map { sc ->
            val e = sc.e
            Trace(e.points.map { it.az to it.el },
                sc.marques.filter { it.type == TypeMarque.SSTV }.map { morceau(e, it.debutMs, maxOf(it.finMs, it.debutMs + 1)) }.filter { it.size >= 2 },
                sc.marques.filter { it.type == TypeMarque.QSO }.mapNotNull { m -> morceau(e, m.debutMs, m.debutMs + 1).firstOrNull() })
        }.filter { it.points.size >= 2 }
        val genre = if (seul) t("journal_bilan_passage") else t("journal_bilan_session")
        val nom = titre ?: if (seul) e0.satName else t("journal_bilan_session_titre") + " · " + e0.satName
        return Donnees(nom, if (titre != null) e0.satName else null, genre, quand, listOf(sc0.indicatif, e0.locator).filter { it.isNotBlank() }.joinToString(" · "),
            sc0.indicatif, c, traces, photos, sc0.police)
    }

    // ---------------------------------------------------------------- the drawing

    /** A picture at [l]×[h]: as it is at its own size, smoothly made smaller (by halves, then the rest), slightly enlarged smoothly. */
    fun echelonne(bm: BufferedImage, l: Int, h: Int): BufferedImage {
        if (bm.width == l && bm.height == h) return bm
        // Made once per size (a session's strip draws the same ones on every frame).
        val parTaille = synchronized(faites) { faites.getOrPut(bm) { HashMap() } }
        synchronized(parTaille) { parTaille[l to h]?.let { return it } }
        val r = reduit(bm, l, h)
        synchronized(parTaille) { if (parTaille.size > 8) parTaille.clear(); parTaille[l to h] = r }
        return r
    }
    private val faites = java.util.WeakHashMap<BufferedImage, HashMap<Pair<Int, Int>, BufferedImage>>()
    private fun reduit(bm: BufferedImage, l: Int, h: Int): BufferedImage {
        var cur = bm
        while (cur.width / 2 >= l && cur.height / 2 >= h) cur = redessine(cur, cur.width / 2, cur.height / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        return redessine(cur, l, h, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }
    private fun redessine(b: BufferedImage, l: Int, h: Int, interp: Any): BufferedImage {
        val r = BufferedImage(l.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = r.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(b, 0, 0, r.width, r.height, null); g.dispose()
        return r
    }

    /** The card [w]×[h]. */
    fun image(w: Int, h: Int, d: Donnees): BufferedImage {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = RenduPc.g2(b)
        g.paint = GradientPaint(0f, 0f, Color(12, 19, 31), 0f, h.toFloat(), Color(7, 11, 18)); g.fillRect(0, 0, w, h)
        val p = plan(w, h, d.chiffres.size, d.photos.isNotEmpty(), d.traces.isNotEmpty())
        val u = p.u
        val k = d.police.coerceIn(0.8f, 1.4f)
        fun ajuste(s: String, px: Float, gras: Boolean, largeur: Float): java.awt.Font {
            var fs = px
            while (fs > 6 * u && g.getFontMetrics(RenduPc.police(fs, gras)).stringWidth(s) > largeur) fs -= u * 0.5f
            return RenduPc.police(fs, gras)
        }
        // The title: the satellite (or the title asked), when, the station; what this card is, on the right.
        val en = p.entete
        val pastille = d.genre.uppercase()
        val fp = RenduPc.police(8.5f * u * k, true)
        val lp = g.getFontMetrics(fp).stringWidth(pastille) + 14 * u
        val hp = 16 * u * k
        g.color = Color(0, 229, 255, 30); g.fill(RoundRectangle2D.Float(en.droite - lp, en.y + 2 * u, lp, hp, hp, hp))
        g.color = Color(0, 229, 255, 170); g.stroke = BasicStroke(maxOf(1f, 0.8f * u)); g.draw(RoundRectangle2D.Float(en.droite - lp, en.y + 2 * u, lp, hp, hp, hp))
        g.texte(pastille, en.droite - lp / 2, en.y + 2 * u + hp / 2 + 3 * u * k, Color(0, 229, 255), fp, centre = true)
        val lt = en.l - lp - 12 * u
        var y = en.y + 22 * u * k
        g.texte(d.titre, en.x, y, Color.WHITE, ajuste(d.titre, 24 * u * k, true, lt), droite = false)
        y += 17 * u * k
        val ligne2 = listOfNotNull(d.sousTitre, d.quand).joinToString(" · ")
        g.texte(ligne2, en.x, y, Color(205, 215, 230), ajuste(ligne2, 11.5f * u * k, false, en.l))
        y += 15 * u * k
        if (d.station.isNotBlank()) {
            val f0 = RenduPc.police(10.5f * u * k); val f1 = RenduPc.police(10.5f * u * k, true)
            val lib = t("journal_bilan_station") + " "
            g.texte(lib, en.x, y, Color(140, 155, 175), f0)
            g.texte(d.station, en.x + g.getFontMetrics(f0).stringWidth(lib), y, RenduPc.CYAN, f1)
        }
        g.paint = GradientPaint(en.x, 0f, Color(0, 229, 255, 110), en.droite, 0f, Color(0, 229, 255, 0))
        g.fill(Rectangle2D.Float(en.x, en.bas + 2 * u, en.l, maxOf(1f, 0.8f * u)))

        // The figures, a tile each.
        val fz = p.chiffres; val ecart = 6 * u
        val nc = p.colonnes; val nb = d.chiffres.size
        // Each tile's place: the last row's tiles share its whole width (the distances' range is the widest).
        val tuiles = d.chiffres.indices.map { i ->
            val rang = i / nc; val dernier = (nb - 1) / nc
            val dans = if (rang == dernier) nb - rang * nc else nc
            val tw = (fz.l - ecart * (dans - 1)) / dans
            Rect(fz.x + (i % nc) * (tw + ecart), fz.y + rang * (p.tuile + ecart), tw, p.tuile)
        }
        // One size for every value, one for every label: the largest that fits each tile.
        val grand = p.tuile / (34 * u)
        val fv = d.chiffres.indices.minOfOrNull { ajuste(d.chiffres[it].valeur, 14.5f * u * k * grand, true, tuiles[it].l - 16 * u).size2D } ?: 1f
        val fl = d.chiffres.indices.minOfOrNull { ajuste(d.chiffres[it].libelle, 8.5f * u * k * grand, false, tuiles[it].l - 16 * u).size2D } ?: 1f
        d.chiffres.forEachIndexed { i, c ->
            val (x, yy, tw) = tuiles[i]
            g.color = Color(255, 255, 255, 14); g.fill(RoundRectangle2D.Float(x, yy, tw, p.tuile, 8 * u, 8 * u))
            g.color = Color(255, 255, 255, 28); g.stroke = BasicStroke(maxOf(1f, 0.6f * u)); g.draw(RoundRectangle2D.Float(x, yy, tw, p.tuile, 8 * u, 8 * u))
            g.color = c.couleur; g.fill(RoundRectangle2D.Float(x + 4 * u, yy + 7 * u, 2.2f * u, p.tuile - 14 * u, 2 * u, 2 * u))
            g.texte(c.valeur, x + 12 * u, yy + p.tuile * 0.50f, c.couleur, RenduPc.police(fv, true))
            g.texte(c.libelle, x + 12 * u, yy + p.tuile * 0.80f, Color(150, 165, 185), RenduPc.police(fl))
        }

        p.ciel?.let { ciel(g, it, d.traces, u) }

        // The pictures, near their own size.
        p.photos?.let { z ->
            val leg = 12 * u * k
            val places = mosaique(d.photos.map { it.image.width to it.image.height }, z.l, z.h, 10 * u, leg)
            val fl = RenduPc.police(8.5f * u * k); val fn = RenduPc.police(8f * u * k, true)
            places.forEachIndexed { i, pl ->
                val ph = d.photos[i]
                val x = (z.x + pl.x).roundToInt(); val yy = (z.y + pl.y).roundToInt(); val l = pl.l.roundToInt(); val hh = pl.h.roundToInt()
                g.color = Color(0, 0, 0, 110); g.fill(Rectangle2D.Float(x + 2 * u, yy + 2 * u, l.toFloat(), hh.toFloat()))
                g.drawImage(echelonne(ph.image, l, hh), x, yy, null)
                g.color = Color(255, 138, 31, 200); g.stroke = BasicStroke(maxOf(1f, 0.7f * u))
                g.draw(Rectangle2D.Float(x - 0.5f, yy - 0.5f, l + 1f, hh + 1f))
                val base = yy + hh + leg * 0.78f
                g.font = fn; val ln = g.fontMetrics.stringWidth(ph.numero)
                g.texte(ph.numero, (x + l).toFloat(), base, Color(255, 138, 31), fn, droite = true)
                g.font = fl
                val place = l - ln - 6 * u
                // The time and the mode, else the time alone, shorter still when the picture is narrow.
                val texte = listOfNotNull("${ph.heure} · ${ph.mode}".takeIf { ph.mode.isNotEmpty() }, ph.heure, ph.heure.substringBefore(' '))
                    .firstOrNull { g.fontMetrics.stringWidth(it) <= place } ?: ""
                g.texte(texte, x.toFloat(), base, Color(205, 215, 230), fl)
            }
        }

        // The foot: 73 from the station, SatMe.
        val pied = p.pied
        if (d.indicatif.isNotBlank()) g.texte("73 de ${d.indicatif}", pied.x, pied.bas - 2 * u, Color(140, 155, 175), RenduPc.police(9.5f * u * k, true))
        MarquePc.dessine(g, pied.droite, pied.bas - 2 * u, 3.0f * u, couleur = RenduPc.CYAN)
        g.dispose()
        return b
    }

    /** The passes' tracks in the sky seen from the station: the horizon, 30° and 60°, the cardinal points, the pictures' stretches, the contacts. */
    private fun ciel(g: Graphics2D, z: Rect, traces: List<Trace>, u: Float) {
        val f = RenduPc.police(8f * u, true)
        val r = z.l / 2 - 11 * u
        val cx = z.x + z.l / 2; val cy = z.y + z.h / 2
        fun xy(az: Double, el: Double): Pair<Float, Float> { val (x, y) = JournalPassage.ciel(az, el); return (cx + x * r).toFloat() to (cy + y * r).toFloat() }
        g.color = Color(255, 255, 255, 10); g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        g.stroke = BasicStroke(maxOf(1f, 0.7f * u))
        for ((el, a) in listOf(0.0 to 90, 30.0 to 45, 60.0 to 45)) {
            val rr = r * (1 - el / 90).toFloat()
            g.color = Color(160, 175, 195, a); g.draw(Ellipse2D.Float(cx - rr, cy - rr, 2 * rr, 2 * rr))
        }
        g.color = Color(160, 175, 195, 40)
        g.draw(Line2D.Float(cx - r, cy, cx + r, cy)); g.draw(Line2D.Float(cx, cy - r, cx, cy + r))
        for ((s, az) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "O" to 270.0)) {
            val a = Math.toRadians(az); val rr = r + 6.5f * u
            g.texte(if (s == "O" && fr.f4ioz.satcombo.i18n.I18n.current() == fr.f4ioz.satcombo.i18n.Lang.EN) "W" else s,
                cx + (sin(a) * rr).toFloat(), cy - (cos(a) * rr).toFloat() + 3 * u, Color(150, 165, 185), f, centre = true)
        }
        val plusieurs = traces.size > 1
        fun chemin(l: List<Pair<Double, Double>>) = Path2D.Float().apply { l.forEachIndexed { i, (az, el) -> val (x, y) = xy(az, el); if (i == 0) moveTo(x, y) else lineTo(x, y) } }
        val rond = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (tr in traces) {
            g.color = if (plusieurs) Color(0, 229, 255, 120) else RenduPc.CYAN
            g.stroke = BasicStroke((if (plusieurs) 1.1f else 1.8f) * u, rond.endCap, rond.lineJoin); g.draw(chemin(tr.points))
            g.color = RenduPc.SSTV; g.stroke = BasicStroke(3.2f * u, rond.endCap, rond.lineJoin)
            tr.sstv.forEach { g.draw(chemin(it)) }
        }
        for (tr in traces) for ((az, el) in tr.qsos) { val (x, y) = xy(az, el); g.color = RenduPc.QSO; g.fill(Ellipse2D.Float(x - 2.6f * u, y - 2.6f * u, 5.2f * u, 5.2f * u)) }
        if (!plusieurs) traces.firstOrNull()?.let { tr ->
            val (ax, ay) = xy(tr.points.first().first, tr.points.first().second)
            g.color = Color.WHITE; g.fill(Ellipse2D.Float(ax - 2.2f * u, ay - 2.2f * u, 4.4f * u, 4.4f * u))
            // The highest point and how high.
            val haut = tr.points.maxBy { it.second }
            val (hx, hy) = xy(haut.first, haut.second)
            g.color = RenduPc.CYAN; g.fill(Ellipse2D.Float(hx - 2f * u, hy - 2f * u, 4f * u, 4f * u))
            g.texte("%.0f°".format(haut.second), hx + 5 * u, hy - 4 * u, Color.WHITE, RenduPc.police(8.5f * u, true))
        }
    }
}
