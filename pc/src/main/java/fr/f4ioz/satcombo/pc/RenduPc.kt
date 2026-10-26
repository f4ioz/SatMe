/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.CadreSstv
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.JournalPassage.Marque
import fr.f4ioz.satcombo.domain.JournalPassage.TypeMarque
import fr.f4ioz.satcombo.domain.OptionsJournal
import fr.f4ioz.satcombo.sstv.ElementsJournal
import fr.f4ioz.satcombo.domain.SourcePcm
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.sstv.Gif
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.imageio.ImageIO

/**
 * The journal's pictures, GIFs and videos made on the PC: the phone's
 * drawing (`JournalRendu`) redone with Java2D — the sky of the pass, the
 * picture arriving, the stations' cards, the caption with the S-meter, the
 * opening title, each picture full frame and the summary. The sky, the
 * map, or the globe ([GlobeRenduPc]). Videos through `ffmpeg`.
 *
 * The report's panels ([ElementsJournal]) when the pass asks for them: laid
 * over the view where the page placed them, or cards of their own — the
 * montage's after the title, the others after the summary.
 */
object RenduPc {

    val CYAN = Color(0x00E5FF); val QSO = Color(0xFF4FA3); val APRS = Color(0x4C8DFF); val ISS = Color(0xFFC21A)
    val SIGNET = Color(0xB98CFF); val SSTV = Color(0xFF8A1F); val FOND = Color(10, 15, 24)

    /** Everything a frame needs, the same for each. */
    class Scene(
        val e: JournalPassage.Entree, val prevue: List<Pair<Double, Double>>, val marques: List<Marque>,
        val indicatif: String, val flashS: Int = 3,
        val affSstv: Boolean = true, val affFiches: Boolean = true, val affSmetre: Boolean = true, val affFreq: Boolean = true,
        val affTx: Boolean = true, val ouverture: Boolean = true, val recap: Boolean = true,
        val fiches: Map<String, JournalPassage.Fiche> = emptyMap(),
        val segments: List<JournalPassage.Segment>? = null, val titreExtrait: String? = null,
        val frequences: List<Pair<Long, Long>> = emptyList(), val resolution: String = "M",
        /** The map rather than the sky: the ground track, the footprint, the stations where they are. */
        val surCarte: Boolean = false, val sol: List<JournalPassage.Sol> = emptyList(), val qth: Pair<Double, Double>? = null,
        val etiquetteQth: String? = null,
        /** The globe rather than the sky or the map ([GlobeRenduPc]), in the page's look [styleGlobe]. */
        val surGlobe: Boolean = false, val styleGlobe: String = GlobeRenduPc.DEFAUT,
        /** The globe's cities, labels, detail, place and size as the page chose them. */
        val optionsGlobe: GlobeRenduPc.Options = GlobeRenduPc.Options(),
        /** The caption's and the cards' texts at this scale (0.6 to 2.5), their times in this zone (UTC unless asked). */
        val police: Float = 1f, val fuseau: TimeZone = LegendePc.UTC,
        /** The map as framed on the page, and the width (px) it was shown at; null: framed to the pass. */
        val vueVue: Pair<JournalPassage.VueCarte, Float>? = null,
        /** The screen's 16:9 rather than the phone's 3:4 (its view square above the caption). */
        val paysage: Boolean = false,
        /** A phone's whole screen upright (9:16), the view over the caption; wins over [paysage]. */
        val vertical: Boolean = false,
        /** The whole sky, as the page shows it; else framed to the pass. */
        val cielEntier: Boolean = false,
        /** The SSTV picture where the page placed it, shares of the view; null: the corner away from the satellite. */
        val cadreSstv: CadreSstv? = null,
        /** An OpenStreetMap tile (z, x, y), from the disk or the network; null: none. */
        val tuile: (Int, Int, Int) -> BufferedImage? = { _, _, _ -> null },
        /** The pass's choices: the report's panels laid over the view or cards of their own ([OptionsJournal]). */
        val options: OptionsJournal = OptionsJournal.DEFAUT,
        /** What those panels draw; null: none. */
        val donnees: ElementsJournal.Donnees? = null,
        /** The words on a processed picture, by its file (only when the pass asks them said). */
        val etiquettes: Map<String, String> = emptyMap(),
    ) {
        val cadre = if (cielEntier) JournalPassage.Cadre() else JournalPassage.cadre(e.points.map { it.az to it.el })
        var vueCarte = JournalPassage.VueCarte()
        var fond: BufferedImage? = null
        private var pour: Pair<Int, Int>? = null

        /** The map's view and its tiles for a view [w]×[h], made once per size. */
        fun prepare(w: Int, h: Int) {
            if (!surCarte || pour == (w to h)) return
            pour = w to h
            val vv = vueVue
            vueCarte = if (vv != null && vv.second > 0f)
                vv.first.copy(zoom = vv.first.zoom + kotlin.math.ln(w / vv.second.toDouble()) / kotlin.math.ln(2.0))
            else JournalPassage.cadreCarte(sol.map { it.lat to it.lon } + listOfNotNull(qth) +
                marques.mapNotNull { m -> m.lat?.let { la -> m.lon?.let { lo -> la to lo } } }, w.toDouble(), h.toDouble())
            fond = fondCarte(w, h, vueCarte, tuile)
        }
        /** The panels laid over a view, drawn once per size (they do not move during the pass). */
        private var calque: Pair<Pair<Int, Int>, BufferedImage?>? = null
        fun calque(w: Int, h: Int): BufferedImage? {
            calque?.let { (t, b) -> if (t == (w to h)) return b }
            val d = donnees
            val b = if (d == null || options.incrustes.isEmpty()) null else BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { b ->
                val g = b.createGraphics()
                val tl = ToileJava2dPc(g)
                ElementsJournal.dessineIncrustations(tl, tl, options, d, w.toFloat(), h.toFloat())
                g.dispose()
            }
            calque = (w to h) to b
            return b
        }
        /** The cards of the video: the montage's after the title, the others at the end (those with something to draw). */
        val cartesOuverture: List<OptionsJournal.Element> get() = donnees?.let { d -> options.cartesOuverture.filter { ElementsJournal.disponible(it, d) } }.orEmpty()
        val cartesFin: List<OptionsJournal.Element> get() = donnees?.let { d -> options.cartesFin.filter { ElementsJournal.disponible(it, d) } }.orEmpty()
        /** The pictures, read once. */
        val images: Map<String, BufferedImage> = marques.mapNotNull { m -> m.fichier?.let { f -> runCatching { ImageIO.read(File(f)) }.getOrNull()?.let { f to it } } }.toMap()
        var flashMs = 0L
        /** A session's video ([SessionRenduPc]): where the satellite is off its track (null: at its ends; returning null: hidden). */
        var horsTrace: ((Long) -> JournalPassage.Sol?)? = null
        /** The globe's centre imposed (a session's turn from one pass to the next); null: its own. */
        var centreImpose: DoubleArray? = null
        /** The globe's size imposed (a session's zoom, outside the passes or in one); null: the page's [GlobeRenduPc.Options.gz]. */
        var gzImpose: Float? = null
        /** Other satellites going round too (a session's companions), and their footprints or not. */
        var autres: List<GlobeRenduPc.Autre> = emptyList()
        var tachesAutres = false
        /** Kept free along the bottom of the view (px): the session's strip of pictures, the cards above it. */
        var margeBas = 0f
        /** A session's words in the caption: « Passage k / N », « n images reçues » (and whether any came). */
        var legendeSession: Triple<String, String, Boolean>? = null
        /** The caption band's height for a frame size, the same all along the video. */
        internal var bande: Pair<Pair<Int, Int>, Int>? = null
    }

    internal fun g2(b: BufferedImage): Graphics2D = b.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }
    internal fun police(px: Float, gras: Boolean = false) = Font(Font.SANS_SERIF, if (gras) Font.BOLD else Font.PLAIN, 1).deriveFont(px)
    internal fun Graphics2D.texte(s: String, x: Float, y: Float, c: Color, f: Font, centre: Boolean = false, droite: Boolean = false) {
        font = f; color = c
        val w = fontMetrics.stringWidth(s)
        drawString(s, if (centre) x - w / 2f else if (droite) x - w else x, y)
    }
    private val utc = TimeZone.getTimeZone("UTC")
    private fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = utc }
    private fun fmt(p: String, sc: Scene) = SimpleDateFormat(p, Locale.US).apply { timeZone = sc.fuseau }
    private fun zone(sc: Scene, t: Long) = LegendePc.zone(sc.fuseau, t)

    fun imageA(marques: List<Marque>, instant: Long, garde: Long): Pair<Marque, Float>? {
        val m = marques.lastOrNull { it.type == TypeMarque.SSTV && it.fichier != null && instant in it.debutMs..(it.finMs + garde) } ?: return null
        return m to ((instant - m.debutMs).toFloat() / (m.finMs - m.debutMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    fun indicatifA(marques: List<Marque>, instant: Long, garde: Long, type: TypeMarque): Marque? =
        if (garde <= 0) null else marques.lastOrNull { it.type == type && instant in it.debutMs..(it.debutMs + garde) }

    /**
     * Where to write a label so it does not cover one already written: lower
     * by a line while it would (two contacts a few seconds apart).
     */
    private class Etiquettes(private val dp: Float) {
        private val boites = ArrayList<java.awt.geom.Rectangle2D.Float>()
        fun place(g: Graphics2D, s: String, x: Float, y: Float): Float {
            val w = g.fontMetrics.stringWidth(s).toFloat(); val h = g.fontMetrics.height.toFloat()
            var yy = y
            repeat(6) {
                val b = java.awt.geom.Rectangle2D.Float(x, yy - h * 0.8f, w, h)
                if (boites.none { it.intersects(b) }) { boites += b; return yy }
                yy += h * 0.95f
            }
            return yy
        }
    }

    internal fun couleur(m: Marque) = when { m.type == TypeMarque.QSO -> QSO; m.type == TypeMarque.SIGNET -> SIGNET; m.viaIss -> ISS; else -> APRS }

    // ------------------------------------------------------------------ the sky

    /** The OpenStreetMap tiles of a fixed view, put together once, with the attribution they ask for. */
    fun fondCarte(w: Int, h: Int, vue: JournalPassage.VueCarte, tuile: (Int, Int, Int) -> BufferedImage?): BufferedImage {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = g2(b)
        g.color = Color(221, 230, 241); g.fillRect(0, 0, w, h)
        val z = kotlin.math.floor(vue.zoom).toInt().coerceIn(0, 16)
        val monde = 256.0 * Math.pow(2.0, vue.zoom)
        val n = 1 shl z
        val cote = monde / n
        val x0 = kotlin.math.floor((vue.cx * monde - w / 2) / cote).toInt(); val x1 = kotlin.math.floor((vue.cx * monde + w / 2) / cote).toInt()
        val y0 = kotlin.math.floor((vue.cy * monde - h / 2) / cote).toInt().coerceAtLeast(0)
        val y1 = kotlin.math.floor((vue.cy * monde + h / 2) / cote).toInt().coerceAtMost(n - 1)
        for (tx in x0..x1) for (ty in y0..y1) {
            val img = tuile(z, ((tx % n) + n) % n, ty) ?: continue
            val ox = (tx * cote - vue.cx * monde + w / 2); val oy = (ty * cote - vue.cy * monde + h / 2)
            g.drawImage(img, ox.toInt(), oy.toInt(), (cote + 1).toInt(), (cote + 1).toInt(), null)
        }
        val att = "© OpenStreetMap"
        g.font = police(h / 70f)
        val lw = g.fontMetrics.stringWidth(att)
        g.color = Color(255, 255, 255, 180); g.fillRect(w - lw - 12, (h - h / 70f * 1.6f).toInt(), lw + 12, (h / 70f * 1.6f).toInt() + 1)
        g.texte(att, w - 6f, h - h / 70f * 0.4f, Color(50, 50, 50), police(h / 70f), droite = true)
        g.dispose()
        return b
    }

    /**
     * The map of the pass over its tiles: the footprint, the ground track,
     * each SSTV picture as its stretch, each station as a point on the track
     * and, in grey, where it is; home. Where the satellite is (share of the width).
     */
    fun carte(g: Graphics2D, w: Float, h: Float, sc: Scene, instant: Long?, dp: Float): Float {
        val e = sc.e; val sol = sc.sol; val vue = sc.vueCarte
        sc.fond?.let { g.drawImage(it, 0, 0, null) }
        val monde = 256.0 * Math.pow(2.0, vue.zoom)
        fun xy(lat: Double, lon: Double): Pair<Float, Float> =
            ((JournalPassage.mercX(lon) - vue.cx) * monde + w / 2).toFloat() to ((JournalPassage.mercY(lat) - vue.cy) * monde + h / 2).toFloat()
        val haut = e.points.maxByOrNull { it.el }?.tMs
        val ici = (instant ?: haut)?.let { JournalPassage.solA(sol, it) }
        ici?.let { s ->
            // One polygon, closed over the pole when it holds it, drawn a turn each side too.
            val poly = JournalPassage.empreintePolygone(s.lat, s.lon, s.altKm)
            // Its edge: the circle, without the way round over the pole.
            val polaire = poly.size > 91
            val bord = if (polaire) poly.dropLast(2) else poly
            g.stroke = BasicStroke(1.5f * dp)
            for (d in listOf(-360.0, 0.0, 360.0)) {
                val p = Path2D.Float()
                poly.forEachIndexed { k, (la, lo) -> val (x, y) = xy(la, lo + d); if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                p.closePath()
                g.color = Color(ISS.red, ISS.green, ISS.blue, 30); g.fill(p)
                val c = Path2D.Float()
                bord.forEachIndexed { k, (la, lo) -> val (x, y) = xy(la, lo + d); if (k == 0) c.moveTo(x, y) else c.lineTo(x, y) }
                if (!polaire) c.closePath()
                g.color = Color(ISS.red, ISS.green, ISS.blue, 200); g.draw(c)
            }
        }
        fun ligne(l: List<JournalPassage.Sol>, c: Color, s: BasicStroke) {
            g.color = c; g.stroke = s
            for (k in 1 until l.size) {
                val a = l[k - 1]; val b = l[k]
                if (kotlin.math.abs(b.lon - a.lon) > 180.0) continue
                val (x1, y1) = xy(a.lat, a.lon); val (x2, y2) = xy(b.lat, b.lon)
                g.draw(java.awt.geom.Line2D.Float(x1, y1, x2, y2))
            }
        }
        val trait = BasicStroke(3.5f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        if (instant != null) { ligne(sol, Color(0, 229, 255, 90), trait); ligne(sol.filter { it.tMs <= instant }, CYAN, trait) }
        else ligne(sol, CYAN, trait)
        sc.marques.filter { it.type == TypeMarque.SSTV }.forEach { m ->
            if (instant != null && m.debutMs > instant) return@forEach
            val fin = if (instant != null) minOf(m.finMs, instant) else m.finMs
            ligne(listOfNotNull(JournalPassage.solA(sol, m.debutMs)) + sol.filter { it.tMs in m.debutMs..fin }, SSTV,
                BasicStroke(8f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        }
        val noir = police(12 * dp, true); val gris = Color(122, 130, 142)
        val ecrits = ArrayList<Triple<String, Float, Float>>()
        val places = Etiquettes(dp)
        fun ecrire(t: String, x: Float, y: Float): Boolean {
            if (ecrits.any { it.first == t && kotlin.math.hypot(it.second - x, it.third - y) < 40 * dp }) return false
            ecrits += Triple(t, x, y); return true
        }
        fun rond(x: Float, y: Float, r: Float, c: Color) { g.color = c; g.fill(java.awt.geom.Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r)) }
        sc.qth?.let { (la, lo) -> val (x, y) = xy(la, lo)
            rond(x, y, 7.5f * dp, Color.WHITE); rond(x, y, 5f * dp, Color(25, 30, 40))
            sc.etiquetteQth?.takeIf { it.isNotBlank() }?.let { tx ->
                g.font = police(11 * dp, true); val l = g.fontMetrics.stringWidth(tx)
                g.color = Color(255, 255, 255, 215); g.fill(RoundRectangle2D.Float(x - 15 * dp - l, y - 9 * dp, l + 6 * dp, 16 * dp, 8 * dp, 8 * dp))
                g.texte(tx, x - 12 * dp - l, y + 3 * dp, Color(20, 24, 32), police(11 * dp, true))
            } }
        for (m in sc.marques.filter { it.type != TypeMarque.SSTV }) {
            if (instant != null && m.debutMs > instant) continue
            val s = JournalPassage.solA(sol, m.debutMs) ?: continue
            val (sx, sy) = xy(s.lat, s.lon)
            val mLat = m.lat; val mLon = m.lon
            if (mLat != null && mLon != null) {
                val (lx, ly) = xy(mLat, mLon)
                g.color = Color(gris.red, gris.green, gris.blue, 180); g.stroke = BasicStroke(1.2f * dp); g.draw(java.awt.geom.Line2D.Float(lx, ly, sx, sy))
                rond(lx, ly, 5f * dp, Color.WHITE); rond(lx, ly, 3.5f * dp, gris)
                if (ecrire("@" + m.texte, lx, ly)) { g.font = police(12 * dp); g.texte(m.texte, lx + 7 * dp, places.place(g, m.texte, lx + 7 * dp, ly + 14 * dp), Color(90, 98, 110), police(12 * dp)) }
            }
            rond(sx, sy, 6f * dp, Color.WHITE); rond(sx, sy, 4.5f * dp, couleur(m))
            if (ecrire(m.texte, sx, sy)) { g.font = noir; g.texte(m.texte, sx + 8 * dp, places.place(g, m.texte, sx + 8 * dp, sy + (if (m.type == TypeMarque.SIGNET) 30 else -6) * dp), Color.BLACK, noir) }
        }
        var satX = 0.5f
        ici?.let { s -> val (x, y) = xy(s.lat, s.lon)
            satX = x / w
            rond(x, y, 11f * dp, Color(ISS.red, ISS.green, ISS.blue, 90)); rond(x, y, 6f * dp, ISS) }
        return satX
    }

    fun ciel(g: Graphics2D, w: Float, h: Float, sc: Scene, instant: Long?, dp: Float) {
        val e = sc.e; val cadre = sc.cadre
        val r = minOf(w, h) / 2f * 0.88f * cadre.echelle.toFloat()
        fun xy(az: Double, el: Double): Pair<Float, Float> {
            val (ux, uy) = JournalPassage.ciel(az, el)
            return (w / 2 + ((ux - cadre.cx) * r).toFloat()) to (h / 2 + ((uy - cadre.cy) * r).toFloat())
        }
        val ox = w / 2 - (cadre.cx * r).toFloat(); val oy = h / 2 - (cadre.cy * r).toFloat()
        g.color = FOND; g.fillRect(0, 0, w.toInt(), h.toInt())
        g.color = Color(17, 27, 41); g.fill(java.awt.geom.Ellipse2D.Float(ox - r, oy - r, 2 * r, 2 * r))
        g.color = Color(45, 58, 77); g.stroke = BasicStroke(1.5f * dp)
        for (k in listOf(1f, 2f / 3f, 1f / 3f)) g.draw(java.awt.geom.Ellipse2D.Float(ox - r * k, oy - r * k, 2 * r * k, 2 * r * k))
        g.draw(java.awt.geom.Line2D.Float(ox - r, oy, ox + r, oy)); g.draw(java.awt.geom.Line2D.Float(ox, oy - r, ox, oy + r))
        val doux = Color(120, 135, 155)
        g.texte("30°", ox + 3 * dp, oy - r * 2 / 3 - 3 * dp, doux, police(11 * dp)); g.texte("60°", ox + 3 * dp, oy - r / 3 - 3 * dp, doux, police(11 * dp))
        val card = police(13 * dp, true)
        g.texte("N", ox, oy - r - 5 * dp, doux, card, centre = true); g.texte("S", ox, oy + r + 15 * dp, doux, card, centre = true)
        g.texte(t("journal_est"), ox + r + 10 * dp, oy + 5 * dp, doux, card, centre = true); g.texte(t("journal_ouest"), ox - r - 10 * dp, oy + 5 * dp, doux, card, centre = true)

        fun trace(l: List<Pair<Double, Double>>, c: Color, s: BasicStroke) {
            if (l.size < 2) return
            val p = Path2D.Float()
            l.forEachIndexed { i, (az, el) -> val (x, y) = xy(az, el); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            g.color = c; g.stroke = s; g.draw(p)
        }
        trace(sc.prevue, Color(130, 145, 165), BasicStroke(2f * dp, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(8 * dp, 6 * dp), 0f))
        val trait = BasicStroke(3.5f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val tout = e.points.map { it.az to it.el }
        if (instant != null) trace(tout, Color(0, 229, 255, 60), trait)
        trace(e.points.filter { instant == null || it.tMs <= instant }.map { it.az to it.el }, CYAN, trait)

        val etiquette = police(12 * dp, true)
        for (m in sc.marques.filter { it.type == TypeMarque.SSTV }) {
            if (instant != null && m.debutMs > instant) continue
            val fin = if (instant != null) minOf(m.finMs, instant) else m.finMs
            val seg = JournalPassage.segment(e, m.debutMs, fin).map { it.az to it.el }
            trace(seg, Color(SSTV.red, SSTV.green, SSTV.blue, 220), BasicStroke(8f * dp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
            seg.lastOrNull()?.let { (az, el) -> val (x, y) = xy(az, el); g.texte("SSTV " + m.texte, x + 8 * dp, y + 16 * dp, Color.WHITE, etiquette) }
        }
        val ecrits = ArrayList<Triple<String, Float, Float>>()
        val places = Etiquettes(dp)
        for (m in sc.marques.filter { it.type != TypeMarque.SSTV }) {
            if (instant != null && m.debutMs > instant) continue
            val p = JournalPassage.pointA(e, m.debutMs) ?: continue
            val (x, y) = xy(p.az, p.el)
            val deja = ecrits.any { it.first == m.texte && kotlin.math.hypot(it.second - x, it.third - y) < 40 * dp }
            if (!deja) ecrits += Triple(m.texte, x, y)
            g.color = Color.WHITE; g.fill(java.awt.geom.Ellipse2D.Float(x - 6 * dp, y - 6 * dp, 12 * dp, 12 * dp))
            g.color = couleur(m); g.fill(java.awt.geom.Ellipse2D.Float(x - 4.5f * dp, y - 4.5f * dp, 9 * dp, 9 * dp))
            if (!deja) { g.font = etiquette; g.texte(m.texte, x + 8 * dp, places.place(g, m.texte, x + 8 * dp, y + (if (m.type == TypeMarque.SIGNET) 30 else -6) * dp), Color.WHITE, etiquette) }
        }
        val ici = instant?.let { JournalPassage.pointA(e, it) } ?: e.points.maxByOrNull { it.el }
        ici?.let { val (x, y) = xy(it.az, it.el)
            g.color = Color(ISS.red, ISS.green, ISS.blue, 90); g.fill(java.awt.geom.Ellipse2D.Float(x - 9 * dp, y - 9 * dp, 18 * dp, 18 * dp))
            g.color = ISS; g.fill(java.awt.geom.Ellipse2D.Float(x - 5.5f * dp, y - 5.5f * dp, 11 * dp, 11 * dp))
        }
    }

    // ---------------------------------------------------------------- cards

    private fun lignesEncart(m: Marque, f: JournalPassage.Fiche?, monLocator: String): List<String> {
        if (JournalPassage.estLeSatellite(m.texte)) return listOfNotNull(t("journal_digi_iss"), m.details.ifBlank { null })
        val loc = f?.locator?.ifBlank { null }
        val km = loc?.let { JournalPassage.distanceKm(monLocator, it) }
        return listOfNotNull(f?.nom?.takeIf { it.isNotBlank() },
            listOfNotNull(f?.qth?.ifBlank { null }, f?.pays?.ifBlank { null }).joinToString(", ").ifBlank { null },
            listOfNotNull(loc, km?.let { "$it km" }).joinToString(" · ").ifBlank { null }, m.details.ifBlank { null })
    }

    private fun encart(g: Graphics2D, x: Float, y: Float, largeur: Float, m: Marque, f: JournalPassage.Fiche?, monLocator: String, dp: Float): Float {
        val lignes = lignesEncart(m, f, monLocator)
        val h = 26f * dp + lignes.size * 15f * dp + 6f * dp
        val r = RoundRectangle2D.Float(x, y, largeur, h, 16 * dp, 16 * dp)
        g.color = Color(12, 18, 28, 225); g.fill(r)
        g.color = couleur(m); g.stroke = BasicStroke(2f * dp); g.draw(r)
        g.texte((if (m.type == TypeMarque.QSO) "QSO  " else "APRS  ") + m.texte, x + 10 * dp, y + 20 * dp, Color.WHITE, police(15 * dp, true))
        var yy = y + 38 * dp
        val f2 = police(11.5f * dp); g.font = f2
        for (l in lignes) {
            var s = l
            while (s.length > 4 && g.fontMetrics.stringWidth(s) > largeur - 20 * dp) s = s.dropLast(2)
            g.texte(if (s != l) "$s…" else s, x + 10 * dp, yy, Color(205, 215, 230), f2); yy += 15 * dp
        }
        return h
    }

    private fun arrivee(g: Graphics2D, b: BufferedImage, x: Float, y: Float, lw: Float, lh: Float, part: Float, dp: Float) {
        val hSrc = (b.height * part).toInt().coerceIn(1, b.height)
        val bas = y + lh * part
        g.color = Color(16, 20, 24); g.fill(java.awt.geom.Rectangle2D.Float(x, y, lw, lh))
        g.drawImage(b, x.toInt(), y.toInt(), (x + lw).toInt(), bas.toInt(), 0, 0, b.width, hSrc, null)
        if (part < 1f) { g.color = Color(255, 255, 255, 220); g.fill(java.awt.geom.Rectangle2D.Float(x, bas - 1.5f * dp, lw, 3 * dp)) }
        g.color = SSTV; g.stroke = BasicStroke(2.5f * dp); g.draw(java.awt.geom.Rectangle2D.Float(x, y, lw, lh))
    }

    /** « corrigée », « fusion de 3 copies » on a processed picture: small, at its bottom left ([x], [bas]). */
    private fun etiquette(g: Graphics2D, s: String, x: Float, bas: Float, dp: Float) {
        val f = police(11 * dp, true); g.font = f
        val w = g.fontMetrics.stringWidth(s) + 12 * dp
        g.color = Color(10, 15, 24, 210); g.fill(RoundRectangle2D.Float(x, bas - 18 * dp, w, 18 * dp, 8 * dp, 8 * dp))
        g.texte(s, x + 6 * dp, bas - 5 * dp, SSTV, f)
    }

    /** The S-meter as an IC-9700 shows it, as on the phone. */
    private fun smetre(g: Graphics2D, x: Float, y: Float, w: Float, h: Float, v: Int, crete: Int?, dp: Float) {
        fun part(n: Int) = (if (n <= 120) n / 120f * 0.6f else 0.6f + (n - 120) / 121f * 0.4f).coerceIn(0f, 1f)
        val l = w - 34 * dp
        val f = police(8.5f * dp)
        g.texte("S", x + 3 * dp, y + 8 * dp, Color.WHITE, f, centre = true)
        for (n in listOf(1, 3, 5, 7, 9)) g.texte(n.toString(), x + l * (n / 9f * 0.6f), y + 8 * dp, Color.WHITE, f, centre = true)
        for (db in listOf(20, 40, 60)) g.texte("+$db", x + l * (0.6f + db / 60f * 0.4f) - (if (db == 60) 6 * dp else 0f), y + 8 * dp, Color(255, 80, 70), f, centre = true)
        val n = 46; val haut = y + 11 * dp; val bas = y + h; val pas = l / n
        val lu = part(v); val pic = crete?.let { part(it) }
        for (i in 0 until n) {
            val fr = (i + 0.5f) / n; val sur = fr > 0.6f; val allume = fr <= lu
            g.color = when {
                pic != null && pic > lu && kotlin.math.abs(fr - pic) < 0.5f / n -> Color(255, 220, 90)
                allume && sur -> Color(255, 70, 60); allume -> Color(235, 240, 245)
                sur -> Color(255, 70, 60, 70); else -> Color(235, 240, 245, 55)
            }
            g.fill(java.awt.geom.Rectangle2D.Float(x + i * pas + 0.6f * dp, haut, pas - 1.2f * dp, bas - haut))
        }
        g.texte(JournalPassage.libelleS(v), x + w, bas, if (v > 120) Color(255, 90, 80) else Color.WHITE, police(11 * dp, true), droite = true)
    }

    // ---------------------------------------------------------------- frames

    /**
     * Where the SSTV picture goes in a view [w]×[h]: where the page placed it
     * ([cadre]), else top, in the corner away from the satellite ([satX], a share of the width).
     */
    fun placeSstv(cadre: CadreSstv?, w: Float, h: Float, satX: Float, dp: Float): CadreSstv.Place {
        if (cadre != null) return cadre.place(w, h)
        val lw = minOf(w * 0.42f, h * 0.5f * 4 / 3); val lh = lw * 3 / 4
        return CadreSstv.Place(if (satX >= 0.5f) 8 * dp else w - lw - 8 * dp, 8 * dp, lw, lh)
    }

    /** The screen's 16:9 (not the phone's upright 9:16 nor its 3:4): the caption in three columns, its band low. */
    private fun large(sc: Scene) = sc.paysage && !sc.vertical

    /**
     * The view's height in a frame [w]×[h]: the frame less the caption band, the
     * same all along the video. In 16:9 the band is three rows high at the texts'
     * scale ([LegendePc.hauteurLarge], at most 28 %); else as high as its texts
     * need — in the phone's 3:4 the view square above it, unless larger texts need more.
     */
    fun hautVue(w: Int, h: Int, sc: Scene): Int {
        sc.bande?.let { (t, v) -> if (t == (w to h)) return v }
        val dp = echelle(w, h)
        val v = if (large(sc)) h - kotlin.math.ceil(LegendePc.hauteurLarge(h, dp, sc.police)).toInt() else {
            val b = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB); val g = g2(b)
            val bande = LegendePc.dispose(g, rangs(sc, sc.e.debutMs, null, dp), w.toFloat(), dp, sc.police, h * if (sc.vertical) 0.45f else 0.5f).naturelle
            g.dispose()
            (h - kotlin.math.ceil(bande)).toInt().let { if (sc.vertical) it else minOf(w, it) }
        }
        sc.bande = (w to h) to v
        return v
    }

    /** The caption of the frame at [instant] laid out in its band (its places from the band's top). */
    fun legende(w: Int, h: Int, sc: Scene, instant: Long): LegendePc.Mise {
        val b = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB); val g = g2(b)
        try {
            val p = if (sc.horsTrace != null && instant !in sc.e.debutMs..sc.e.finMs) null else JournalPassage.pointA(sc.e, instant)
            return legende(g, w, h, sc, instant, p)
        } finally { g.dispose() }
    }
    private fun legende(g: Graphics2D, w: Int, h: Int, sc: Scene, instant: Long, p: JournalPassage.Point?): LegendePc.Mise {
        val dp = echelle(w, h)
        val bande = (h - hautVue(w, h, sc)).toFloat()
        return if (large(sc)) LegendePc.disposeColonnes(g, colonnes(sc, instant, p, dp), w.toFloat(), dp, sc.police, bande)
        else LegendePc.dispose(g, rangs(sc, instant, p, dp), w.toFloat(), dp, sc.police, bande)
    }

    /** The things the caption can say at [instant], each keeping its place all along the video. */
    private class Choses(val nom: LegendePc.Element, val tx: LegendePc.Element?, val date: LegendePc.Element, val heure: LegendePc.Element,
                         val relatif: LegendePc.Element, val passage: LegendePc.Element?, val azel: LegendePc.Element, val rx: LegendePc.Element?,
                         val recues: LegendePc.Element?, val aos: List<LegendePc.Element>, val smetre: LegendePc.Element?,
                         val station: LegendePc.Element, val marque: LegendePc.Element)

    /** [rxApart]: the frequency a thing of its own (16:9), else after the pointing. */
    private fun choses(sc: Scene, instant: Long, p: JournalPassage.Point?, dp: Float, rxApart: Boolean): Choses {
        val e = sc.e; val k = sc.police
        val gris = Color(160, 175, 195)
        val f13 = police(13 * dp * k); val f13g = police(13 * dp * k, true)
        fun t(s: String, f: java.awt.Font, c: Color, prio: Int, droite: Boolean = false, gabarit: String? = null, visible: Boolean = true) =
            LegendePc.Element(LegendePc.Sorte.TEXTE, s, f, c, prio, droite, gabarit, visible)
        val ses = sc.legendeSession
        // The satellite's name large, growing less than the rest.
        val nom = t(e.satName, police(20 * dp * (1 + (k - 1) * 0.6f), true), Color.WHITE, 0)
        val tx = if (sc.affTx && e.emissions.isNotEmpty()) LegendePc.Element(LegendePc.Sorte.TX, "TX", f13g, Color.WHITE, 3, droite = !rxApart, visible = JournalPassage.enEmissionA(e, instant)) else null
        val heure = fmt("HH:mm:ss", sc).format(Date(instant)) + " " + zone(sc, instant)
        val rx = if (!sc.affFreq) null else sc.frequences.lastOrNull { f -> f.first <= instant + 2_000L }?.second
        val avecRx = sc.affFreq && sc.frequences.isNotEmpty()
        val rxTexte = rx?.let { r -> "RX %.4f MHz".format(r / 1e6) }
        val azel = p?.let { "Az %.0f°  El %.0f°".format(it.az, it.el) + (if (rxApart) "" else rxTexte?.let { r -> "  $r" } ?: "") } ?: ""
        val (aos, tca, los) = LegendePc.aosTcaLos(e, sc.fuseau)
        val v = JournalPassage.signalA(e, instant)?.takeIf { sc.marques.any { m -> instant in (m.debutMs - 2_000L)..(m.finMs + sc.flashMs) } }
        return Choses(nom, tx,
            date = t(fmt("yyyy-MM-dd", sc).format(Date(instant)), f13, gris, 1),
            heure = t(heure, f13, gris, 1, gabarit = "00:00:00 " + zone(sc, instant)),
            relatif = t(LegendePc.relatif(e, instant), f13g, CYAN, 2, gabarit = "AOS dans 00:00:00"),
            passage = ses?.let { t(it.first, f13g, Color.WHITE, 4, droite = true, gabarit = "Passage 000 / 000") },
            azel = t(azel, f13, gris, 3, gabarit = "Az 000°  El 00°" + (if (avecRx && !rxApart) "  RX 000.0000 MHz" else ""), visible = p != null),
            rx = if (rxApart && avecRx) t(rxTexte ?: "", f13, gris, 3, gabarit = "RX 000.0000 MHz", visible = rxTexte != null && p != null) else null,
            recues = ses?.let { t(it.second, f13g, if (it.third) SSTV else gris, 5, droite = true, gabarit = "000 images reçues") },
            aos = listOf(t(aos, f13, gris, 2), t(tca, f13, gris, 3), t(los, f13, gris, 2)),
            smetre = if (sc.affSmetre && e.signal.isNotEmpty()) LegendePc.Element(LegendePc.Sorte.SMETRE, "", f13, Color.WHITE, 6,
                visible = v != null, valeur = v, crete = v?.let { JournalPassage.crete(e, instant) }) else null,
            station = t(listOf(sc.indicatif, e.locator).filter { it.isNotBlank() }.joinToString(" · "), f13, gris, 7, droite = rxApart),
            marque = LegendePc.Element(LegendePc.Sorte.MARQUE, "", f13, CYAN, 1, droite = true))
    }

    /**
     * The caption's rows at [instant]: the satellite (TX while it sends), the date
     * and time and where the pass is (a session's pass number), the pointing (the
     * session's pictures), AOS · TCA · LOS, the S-meter, the station and the mark.
     * Each thing keeps its place all along the video, drawn only when it has
     * something to say.
     */
    private fun rangs(sc: Scene, instant: Long, p: JournalPassage.Point?, dp: Float): List<List<LegendePc.Element>> {
        val c = choses(sc, instant, p, dp, rxApart = false)
        // The date and the time apart: on two lines when the band is narrow; the pass's times after the
        // pointing, on the same lines when there is room, kept before the pass's number and the pictures' count.
        return listOf(listOfNotNull(c.nom, c.tx), listOfNotNull(c.date, c.heure, c.relatif, c.passage),
            listOf(c.azel) + c.aos + listOfNotNull(c.recues), listOfNotNull(c.smetre), listOf(c.station, c.marque))
    }

    /**
     * The 16:9's caption in three columns, three rows: on the left the satellite (TX), the date
     * and time, the pointing; in the middle where the pass is and the frequency, AOS · TCA · LOS,
     * the S-meter; on the right the mark, a session's pass number and pictures, the station.
     */
    private fun colonnes(sc: Scene, instant: Long, p: JournalPassage.Point?, dp: Float): List<List<List<LegendePc.Element>>> {
        val c = choses(sc, instant, p, dp, rxApart = true)
        return listOf(
            listOf(listOfNotNull(c.nom, c.tx), listOf(c.date, c.heure), listOf(c.azel)),
            listOf(listOfNotNull(c.relatif, c.rx), c.aos, listOfNotNull(c.smetre)),
            listOf(listOf(c.marque), listOfNotNull(c.passage, c.recues), listOf(c.station)))
    }

    /** One frame of the replay: the sky above a caption. */
    fun image(w: Int, h: Int, sc: Scene, instant: Long): BufferedImage {
        val e = sc.e
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = g2(b)
        val dp = echelle(w, h)
        // The view: square above the caption (the phone's 3:4), or the width over the caption (16:9).
        val haut = hautVue(w, h, sc)
        g.clipRect(0, 0, w, haut)
        // A session's video between its passes: no pointing to tell.
        val p = if (sc.horsTrace != null && instant !in e.debutMs..e.finMs) null else JournalPassage.pointA(e, instant)
        val satX = if (sc.surGlobe) GlobeRenduPc.dessine(g, w.toFloat(), haut.toFloat(), sc, instant, dp)
        else if (sc.surCarte) { sc.prepare(w, haut); carte(g, w.toFloat(), haut.toFloat(), sc, instant, dp) } else {
            ciel(g, w.toFloat(), haut.toFloat(), sc, instant, dp)
            if (p == null) 0.5f else 0.5f + ((JournalPassage.ciel(p.az, p.el).first - sc.cadre.cx) * sc.cadre.echelle).toFloat() / 2
        }
        // The report's panels, where the page put them (under the picture arriving and the cards).
        sc.calque(w, haut)?.let { g.drawImage(it, 0, 0, null) }
        if (sc.affSstv) imageA(sc.marques, instant, sc.flashMs)?.let { (m, part) ->
            val bm = m.fichier?.let { sc.images[it] } ?: return@let
            val pl = placeSstv(sc.cadreSstv, w.toFloat(), haut.toFloat(), satX, dp)
            arrivee(g, bm, pl.x, pl.y, pl.l, pl.h, part, dp)
            m.fichier?.let { sc.etiquettes[it] }?.let { etiquette(g, it, pl.x + 6 * dp, pl.y + pl.h - 6 * dp, dp) }
        }
        if (sc.affFiches) {
            val lw = minOf(w * 0.58f, 260 * dp)
            val x = if (satX >= 0.5f) 8 * dp else w - lw - 8 * dp
            var bas = haut - 8 * dp - sc.margeBas
            for (type in listOf(TypeMarque.QSO, TypeMarque.APRS)) {
                val m = indicatifA(sc.marques, instant, sc.flashMs, type) ?: continue
                val n = lignesEncart(m, sc.fiches[m.texte], e.locator).size
                val hEnc = (32f + n * 15f) * dp
                encart(g, x, bas - hEnc, lw, m, sc.fiches[m.texte], e.locator, dp)
                bas -= hEnc + 6 * dp
            }
        }
        g.clip = null
        g.color = FOND; g.fillRect(0, haut, w, h - haut)
        val mise = legende(g, w, h, sc, instant, p)
        for (pl in mise.places) {
            val el = pl.el; if (!el.visible) continue
            val y = haut + pl.y
            when (el.sorte) {
                LegendePc.Sorte.TEXTE -> {
                    g.font = el.font; g.color = el.couleur
                    // Its place is as wide as it can get: on the right, flush with the edge.
                    val x = if (el.droite) pl.x + pl.l - g.fontMetrics.stringWidth(pl.texte) else pl.x
                    g.drawString(pl.texte, x, y + g.fontMetrics.ascent)
                }
                LegendePc.Sorte.TX -> {
                    g.color = Color(229, 57, 53); g.fill(RoundRectangle2D.Float(pl.x, y, pl.l, pl.h, 8 * dp * sc.police, 8 * dp * sc.police))
                    val f = police(15 * dp * sc.police, true); g.font = f
                    g.texte("TX", pl.x + pl.l / 2, y + pl.h / 2 + g.fontMetrics.ascent * 0.36f, Color.WHITE, f, centre = true)
                }
                LegendePc.Sorte.SMETRE -> el.valeur?.let { v -> smetre(g, pl.x, y, pl.l, pl.h, v, el.crete, dp * sc.police) }
                LegendePc.Sorte.MARQUE -> { val u = LegendePc.marque(dp, sc.police); MarquePc.dessine(g, pl.x + pl.l, y + 4.8f * u, u, couleur = CYAN) }
            }
        }
        g.dispose()
        return b
    }

    /** The opening title: the satellite, when (UTC), the receiving station, SatMe. */
    fun titre(w: Int, h: Int, sc: Scene): BufferedImage {
        val e = sc.e
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = g2(b)
        val dp = echelle(w, h)
        g.color = FOND; g.fillRect(0, 0, w, h)
        val de = sc.segments?.firstOrNull()?.deMs ?: e.debutMs
        val a = sc.segments?.lastOrNull()?.aMs ?: e.finMs
        // The texts at the asked scale, each made smaller if it would not fit across.
        val k = sc.police
        fun ajuste(s: String, px: Float, gras: Boolean): java.awt.Font {
            var fs = px * k
            while (g.getFontMetrics(police(fs, gras)).stringWidth(s) > w - 24 * dp && fs > 9 * dp) fs -= dp
            return police(fs, gras)
        }
        var y = h * (if (sc.titreExtrait != null) 0.24f else 0.30f) - (k - 1) * 60 * dp
        g.texte(e.satName, w / 2f, y, Color.WHITE, ajuste(e.satName, 40f * dp, true), centre = true); y += 34 * dp * k
        sc.titreExtrait?.takeIf { it.isNotBlank() }?.let { tx -> g.texte(tx, w / 2f, y + 8 * dp * k, QSO, ajuste(tx, 28 * dp, true), centre = true); y += 44 * dp * k }
        val clair = Color(205, 215, 230)
        val jour = fmt("yyyy-MM-dd", sc).format(Date(de))
        g.texte(jour, w / 2f, y, clair, ajuste(jour, 17 * dp, false), centre = true); y += 24 * dp * k
        val heures = fmt("HH:mm:ss", sc).format(Date(de)) + " → " + fmt("HH:mm:ss", sc).format(Date(a)) + " " + zone(sc, de)
        g.texte(heures, w / 2f, y, clair, ajuste(heures, 17 * dp, false), centre = true); y += 22 * dp * k
        if (sc.titreExtrait == null) g.texte("%.0f°".format(e.elMax) + " · " + "%d:%02d".format(e.dureeMs / 60_000, e.dureeMs / 1000 % 60), w / 2f, y,
            Color(150, 165, 185), police(14 * dp * k), centre = true)
        y += 46 * dp * k
        listOf(sc.indicatif, e.locator).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
            g.texte(t("journal_station_rx"), w / 2f, y, Color(150, 165, 185), police(12 * dp * k), centre = true); y += 26 * dp * k
            g.texte(it, w / 2f, y, CYAN, ajuste(it, 22 * dp, true), centre = true)
        }
        MarquePc.dessine(g, w / 2f, h - 26 * dp, 5.2f * dp, centre = true, couleur = CYAN)
        g.dispose()
        return b
    }

    /** One SSTV picture of the pass, full frame. */
    fun imagePleine(w: Int, h: Int, sc: Scene, m: Marque, bm: BufferedImage): BufferedImage {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = g2(b)
        val dp = echelle(w, h)
        g.color = FOND; g.fillRect(0, 0, w, h)
        // As large as it goes above its caption, centred.
        val ih = minOf(w * bm.height.toFloat() / bm.width, h - 80 * dp)
        val iw = ih * bm.width / bm.height
        val top = (h - 70 * dp - ih) / 2
        g.drawImage(bm, ((w - iw) / 2).toInt(), top.toInt(), iw.toInt(), ih.toInt(), null)
        g.texte(sc.e.satName + " · " + m.texte + (m.fichier?.let { sc.etiquettes[it] }?.let { " · $it" } ?: ""), 14 * dp, h - 44 * dp, Color.WHITE, police(18 * dp, true))
        g.texte(fmt("yyyy-MM-dd HH:mm:ss", sc).format(Date(m.finMs)) + " " + zone(sc, m.finMs) + "  ·  " + listOf(sc.indicatif, sc.e.locator).filter { it.isNotBlank() }.joinToString(" · "),
            14 * dp, h - 22 * dp, Color(160, 175, 195), police(13 * dp))
        // The mark, as on the other frames.
        MarquePc.dessine(g, w - 14f * dp, h - 10f * dp, 3.2f * dp, couleur = CYAN)
        g.dispose()
        return b
    }

    /** The pass summed up: pictures, stations worked and heard. */
    fun resume(w: Int, h: Int, sc: Scene): BufferedImage {
        val e = sc.e
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = g2(b)
        val dp = echelle(w, h)
        g.color = FOND; g.fillRect(0, 0, w, h)
        val gris = Color(160, 175, 195)
        var y = 40f * dp
        g.texte(e.satName, 16 * dp, y, Color.WHITE, police(24 * dp, true)); y += 22 * dp
        g.texte(fmt("yyyy-MM-dd HH:mm", sc).format(Date(e.debutMs)) + " " + zone(sc, e.debutMs) + " · " + "%.0f° · %d min".format(e.elMax, (e.dureeMs / 60_000).toInt()), 16 * dp, y, gris, police(13 * dp)); y += 18 * dp
        g.texte(listOf(sc.indicatif, e.locator).filter { it.isNotBlank() }.joinToString(" · "), 16 * dp, y, CYAN, police(13 * dp, true)); y += 16 * dp
        val photos = sc.marques.filter { it.type == TypeMarque.SSTV }.mapNotNull { m -> m.fichier?.let { sc.images[it] } }.take(6)
        if (photos.isNotEmpty()) {
            val col = if (photos.size == 1) 1 else 2
            val pw = minOf((w - 16 * dp * 2 - 8 * dp * (col - 1)) / col, h * 0.22f * 4 / 3); val ph = pw * 3 / 4
            photos.forEachIndexed { i, bm ->
                val x = 16 * dp + (i % col) * (pw + 8 * dp); val yy = y + (i / col) * (ph + 8 * dp)
                g.drawImage(bm, x.toInt(), yy.toInt(), pw.toInt(), ph.toInt(), null)
                g.color = SSTV; g.stroke = BasicStroke(2f * dp); g.draw(java.awt.geom.Rectangle2D.Float(x, yy, pw, ph))
            }
            y += ((photos.size + col - 1) / col) * (ph + 8 * dp) + 8 * dp
        }
        fun bloc(titre: String, c: Color, l: List<String>) {
            if (l.isEmpty() || y > h - 30 * dp) return
            g.texte(titre, 16 * dp, y + 14 * dp, c, police(14 * dp, true)); y += 22 * dp
            for (s in l) { if (y > h - 24 * dp) break; g.texte(s, 24 * dp, y + 12 * dp, Color.WHITE, police(14 * dp)); y += 18 * dp }
            y += 6 * dp
        }
        val qsos = sc.marques.filter { it.type == TypeMarque.QSO }.distinctBy { it.texte }
        bloc(t("journal_recap_qso").format(qsos.size), QSO, qsos.map { m ->
            listOfNotNull(m.texte, sc.fiches[m.texte]?.locator?.ifBlank { null }, sc.fiches[m.texte]?.nom?.ifBlank { null }).joinToString(" · ") })
        val aprs = sc.marques.filter { it.type == TypeMarque.APRS }.distinctBy { it.texte }
        bloc(t("journal_recap_aprs").format(aprs.size), APRS, aprs.map { m ->
            if (JournalPassage.estLeSatellite(m.texte)) m.texte + " — " + t("journal_digi_iss_court") else m.texte })
        MarquePc.dessine(g, w - 14f * dp, h - 10f * dp, 3.2f * dp, couleur = CYAN)
        g.dispose()
        return b
    }

    /** A report panel alone on a frame: its name, the pass, the panel as large as it goes, the mark. */
    fun carte(w: Int, h: Int, sc: Scene, k: OptionsJournal.Element): BufferedImage {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = g2(b)
        val dp = echelle(w, h)
        val tl = ToileJava2dPc(g)
        sc.donnees?.let { d -> ElementsJournal.carte(tl, tl, k, d, w.toFloat(), h.toFloat(),
            sc.e.satName + " · " + fmt("yyyy-MM-dd HH:mm", sc).format(Date(sc.e.debutMs)) + " " + zone(sc, sc.e.debutMs)) }
        MarquePc.dessine(g, w - 14f * dp, h - 10f * dp, 3.2f * dp, couleur = CYAN)
        g.dispose()
        return b
    }

    /** After the title: the montage's card when asked, [n] frames. */
    internal fun ouvertureVideo(w: Int, h: Int, sc: Scene, n: Int): List<Pair<() -> BufferedImage, Int>> =
        sc.cartesOuverture.map { k -> { carte(w, h, sc, k) } to n }

    internal fun finVideo(w: Int, h: Int, sc: Scene, parImage: Int, resumeN: Int, parCarte: Int = resumeN * 2 / 3, recap: Boolean = sc.recap): List<Pair<() -> BufferedImage, Int>> {
        val l = ArrayList<Pair<() -> BufferedImage, Int>>()
        if (recap) {
            sc.marques.filter { it.type == TypeMarque.SSTV }.forEach { m ->
                val bm = m.fichier?.let { sc.images[it] } ?: return@forEach
                l += { imagePleine(w, h, sc, m, bm) } to parImage
            }
            l += { resume(w, h, sc) } to resumeN
        }
        // The report's panels asked as cards, after the summary.
        sc.cartesFin.forEach { k -> l += { carte(w, h, sc, k) } to parCarte }
        return l
    }

    /** How long the video plays before the pass: the title (3 s), each opening card (4 s) — its sound waits that long. */
    fun secondesAvantPassage(sc: Scene): Int = (if (sc.ouverture) 3 else 0) + 4 * sc.cartesOuverture.size

    // ---------------------------------------------------------------- files

    /** One size for everything drawn: the phone's 360 dp across a portrait, 540 dp down a landscape. */
    internal fun echelle(w: Int, h: Int) = minOf(w / 360f, h / 480f)

    fun png(sc: Scene, instant: Long?, sortie: File): File {
        sc.flashMs = sc.flashS * 1000L
        val (w, h) = tailleImage(sc)
        ImageIO.write(image(w, h, sc, instant ?: sc.e.finMs), "png", sortie)
        return sortie
    }

    /** The replay as an animated GIF: the pass in about fifteen seconds, then held, looping. */
    fun gif(sc: Scene, sortie: File, progres: (Float) -> Unit): File {
        val e = sc.e
        val (w, h) = tailleGif(sc)
        val n = 75
        val de = sc.segments?.firstOrNull()?.deMs ?: e.debutMs
        val a = sc.segments?.lastOrNull()?.aMs ?: e.finMs
        val duree = (a - de).coerceAtLeast(1)
        sc.flashMs = sc.flashS * 1000L * duree / 15_000L
        fun px(img: BufferedImage) = img.getRGB(0, 0, w, h, null, 0, w)
        val fin = px(image(w, h, sc, a))
        val titrePx = if (sc.ouverture) px(titre(w, h, sc)) else null
        var pourPalette = if (sc.recap) fin + px(resume(w, h, sc)) else fin
        // The cards' colours too (the report's light cards).
        (sc.cartesOuverture + sc.cartesFin).firstOrNull()?.let { pourPalette += px(carte(w, h, sc, it)) }
        if (titrePx != null) pourPalette += titrePx
        val pal = Gif.palette(pourPalette, listOf(0x0A0F18, 0x111B29, 0xFFFFFF, CYAN.rgb and 0xFFFFFF, QSO.rgb and 0xFFFFFF, APRS.rgb and 0xFFFFFF, ISS.rgb and 0xFFFFFF))
        sortie.outputStream().buffered().use { o ->
            val g = Gif.Ecrivain(o, w, h, pal)
            titrePx?.let { g.trame(it, 0, h, 200) }
            for ((dessin, n2) in ouvertureVideo(w, h, sc, 300)) g.trame(px(dessin()), 0, h, n2)
            var avant: IntArray? = null
            for (i in 0..n) {
                val cur = if (i == n) fin else px(image(w, h, sc, de + duree * i / n))
                val prev = avant
                var y0 = 0; var y1 = h
                if (prev != null) {
                    fun egale(y: Int): Boolean { val k = y * w; for (x in 0 until w) if (prev[k + x] != cur[k + x]) return false; return true }
                    while (y0 < h && egale(y0)) y0++
                    while (y1 > y0 && egale(y1 - 1)) y1--
                    if (y0 >= y1) { y0 = 0; y1 = 1 }
                }
                g.trame(cur, y0, y1, if (i == n) (if (sc.recap || sc.cartesFin.isNotEmpty()) 150 else 300) else 20)
                avant = cur
                progres((i + 1f) / (n + 1))
            }
            for ((dessin, n2) in finVideo(w, h, sc, 200, 400)) g.trame(px(dessin()), 0, h, n2)
            g.fin()
        }
        return sortie
    }

    /** Is ffmpeg there to make videos? */
    fun ffmpeg(): String? = (listOfNotNull(OutilsPc.trouve("ffmpeg")) + listOf("ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "C:\\ffmpeg\\bin\\ffmpeg.exe"))
        .distinct().firstOrNull { runCatching { ProcessBuilder(it, "-version").redirectErrorStream(true).start().let { p -> p.inputStream.readBytes(); p.waitFor() == 0 } }.getOrDefault(false) }

    /**
     * The replay as an MP4 (H.264 + AAC) through ffmpeg: 5 frames a second,
     * the opening title 3 s (the montage's card 4 s after it when asked), the
     * pass (real time with its sound, accelerated by its stretches, else ×10
     * and silent), then each picture 3 s, the summary 6 s and the report's
     * cards 4 s each. [son]: the sound of the pass as the video plays it
     * (its silence before the pass included, [secondesAvantPassage]), at
     * [rate]; null: silent.
     */
    fun video(sc: Scene, sortie: File, son: SourcePcm?, rate: Int, travail: File, progres: (Float) -> Unit): File? {
        val ff = ffmpeg() ?: return null
        val e = sc.e
        val (w, h) = tailleVideo(sc)
        val fps = 5
        val segs = sc.segments
        val vitesse = if (son != null || segs != null) 1 else 10
        val nb = (if (segs != null) JournalPassage.dureeLecture(segs) * fps / 1000 else e.dureeMs * fps / 1000 / vitesse).toInt().coerceAtLeast(2)
        val nbTitre = if (sc.ouverture) 3 * fps else 0
        sc.flashMs = sc.flashS * 1000L * vitesse
        val ouverture = ouvertureVideo(w, h, sc, 4 * fps)
        val fin = finVideo(w, h, sc, 3 * fps, 6 * fps)
        val total = nbTitre + ouverture.sumOf { it.second } + nb + fin.sumOf { it.second }
        // The sound first, into a WAV file ffmpeg reads beside the frames.
        val wav = son?.let { s -> File(travail, "son-" + System.nanoTime() + ".wav").also { ecritWav(s, rate, it) } }
        try {
            return encode(ff, w, h, fps, total, wav, sortie) { ecris ->
                var fait = 0
                val titreImg = if (nbTitre > 0) titre(w, h, sc) else null
                repeat(nbTitre) { ecris(titreImg!!); progres(++fait / total.toFloat()) }
                for ((dessin, k) in ouverture) { val img = dessin(); repeat(k) { ecris(img); progres(++fait / total.toFloat()) } }
                for (n in 0 until nb) {
                    val tMs = if (segs != null) JournalPassage.instantALecture(segs, n.toLong() * 1000 / fps) else e.debutMs + n.toLong() * 1000 * vitesse / fps
                    ecris(image(w, h, sc, tMs)); progres(++fait / total.toFloat())
                }
                for ((dessin, k) in fin) { val img = dessin(); repeat(k) { ecris(img); progres(++fait / total.toFloat()) } }
            }
        } finally {
            wav?.delete()
        }
    }

    /** A video's frame size for its format (16:9, a phone's 9:16 when [vertical], or the phone app's 3:4) and resolution (XS, M, HD). */
    fun tailleVideo(paysage: Boolean, resolution: String, vertical: Boolean = false): Pair<Int, Int> =
        if (vertical) when (resolution) { "XS" -> 480 to 854; "HD" -> 1080 to 1920; else -> 720 to 1280 }
        else if (paysage) when (resolution) { "XS" -> 854 to 480; "HD" -> 1920 to 1080; else -> 1280 to 720 }
        else when (resolution) { "XS" -> 480 to 640; "HD" -> 1080 to 1440; else -> 720 to 960 }
    fun tailleVideo(sc: Scene): Pair<Int, Int> = tailleVideo(sc.paysage, sc.resolution, sc.vertical)
    /** A picture's size for the scene's format (16:9, 9:16, 3:4). */
    fun tailleImage(sc: Scene): Pair<Int, Int> = if (sc.vertical) 1080 to 1920 else if (sc.paysage) 1920 to 1080 else 1080 to 1440
    /** A GIF's size for the scene's format. */
    fun tailleGif(sc: Scene): Pair<Int, Int> = if (sc.vertical) 360 to 640 else if (sc.paysage) 640 to 360 else 480 to 640

    /**
     * Frames [w]×[h] given to [trames] made into an MP4 (H.264, with [wav]'s
     * sound as AAC when given) through ffmpeg, [total] frames at [fps]. The
     * file, or null when ffmpeg failed.
     */
    fun encode(ff: String, w: Int, h: Int, fps: Int, total: Int, wav: File?, sortie: File, trames: ((BufferedImage) -> Unit) -> Unit): File? {
        val cmd = arrayListOf(ff, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${w}x$h", "-r", "$fps", "-i", "-")
        if (wav != null) cmd += listOf("-i", wav.path)
        cmd += listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-crf", "26")
        // The length is the pictures': the title, the pass, then each picture and the summary,
        // after the sound has ended. ("-shortest" stopped at the sound's end, the summary cut
        // off and the frames still coming refused: a whole pass never came out, 12/10.)
        if (wav != null) cmd += listOf("-c:a", "aac", "-b:a", "64k")
        cmd += listOf("-t", "%.2f".format(java.util.Locale.US, total.toDouble() / fps))
        cmd += sortie.path
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val dit = StringBuilder()
        val journal = Thread { runCatching { dit.append(String(p.inputStream.readBytes())) } }.apply { isDaemon = true; start() }
        val rgb = ByteArray(w * h * 3)
        try {
            trames { img ->
                val px = img.getRGB(0, 0, w, h, null, 0, w)
                for (i in px.indices) { val v = px[i]; rgb[3 * i] = (v shr 16).toByte(); rgb[3 * i + 1] = (v shr 8).toByte(); rgb[3 * i + 2] = v.toByte() }
                p.outputStream.write(rgb)
            }
            p.outputStream.close()
        } catch (x: Exception) { p.destroy(); throw x }
        val ok = p.waitFor() == 0
        journal.join(1000)
        if (!ok) System.err.println("ffmpeg : " + dit.toString().trim().take(800))
        return if (ok && sortie.isFile && sortie.length() > 0) sortie else null
    }

    /** A sound source into a 16-bit mono WAV file. */
    fun ecritWav(s: SourcePcm, rate: Int, f: File) {
        java.io.RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0); raf.write(ByteArray(44))
            val buf = ShortArray(8192); val oct = ByteArray(16384)
            var n = 0L
            while (true) {
                val k = s.lis(buf); if (k <= 0) break
                for (i in 0 until k) { oct[2 * i] = buf[i].toByte(); oct[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                raf.write(oct, 0, 2 * k); n += 2L * k
            }
            val t = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            t.put("RIFF".toByteArray()).putInt((36 + n).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(n.toInt())
            raf.seek(0); raf.write(t.array())
        }
    }
}
