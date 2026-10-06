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
import fr.f4ioz.satcombo.domain.JournalPassage.Marque
import fr.f4ioz.satcombo.domain.JournalPassage.TypeMarque
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.sstv.Gif
import fr.f4ioz.satcombo.sstv.PlancheRendu
import fr.f4ioz.satcombo.sstv.Mp3Pcm
import fr.f4ioz.satcombo.sstv.SstvVideo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The sky of a pass from the journal, drawn once for the screen (zoomed by
 * the fingers), the video and the animated GIF: the pass as predicted and as
 * followed, the contacts and APRS frames as points with their callsign, each
 * SSTV picture as the stretch of trajectory it took to arrive.
 */
object JournalRendu {

    const val CYAN = 0xFF00E5FF.toInt()
    const val QSO = 0xFFFF4FA3.toInt()
    const val APRS = 0xFF4C8DFF.toInt()
    const val ISS = 0xFFFFC21A.toInt()
    /** A moment marked (★). */
    const val SIGNET = 0xFFB98CFF.toInt()
    /**
     * The SSTV pictures' colour, the same for all on a pass — none of the
     * contacts' pink, the APRS blue, the ISS yellow nor the trajectory's cyan.
     */
    val SSTV = intArrayOf(0xFFFF8A1F.toInt())
    @Suppress("UNUSED_PARAMETER")
    fun couleurSstv(k: Int) = SSTV[0]

    /**
     * The SSTV picture to show at [instant]: from its first line, drawn as it
     * arrives, then held [garde] ms once complete. With how much of it is in.
     */
    fun imageA(marques: List<Marque>, instant: Long?, garde: Long): Pair<Marque, Float>? {
        if (instant == null) return null
        val m = marques.lastOrNull { it.type == TypeMarque.SSTV && it.fichier != null && instant in it.debutMs..(it.finMs + garde) }
            ?: return null
        return m to ((instant - m.debutMs).toFloat() / (m.finMs - m.debutMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /**
     * The station of that kind (a contact, an APRS frame) to show at
     * [instant]: the last one heard within [garde] ms before.
     */
    fun indicatifA(marques: List<Marque>, instant: Long?, garde: Long, type: TypeMarque): Marque? {
        if (instant == null || garde <= 0) return null
        return marques.lastOrNull { it.type == type && instant in it.debutMs..(it.debutMs + garde) }
    }

    /** The card of a station: callsign, who, where, how far, what was said. */
    fun lignesEncart(m: Marque, f: JournalPassage.Fiche?, monLocator: String): List<String> {
        // The ISS's own digipeater: what it is, and what it said.
        if (JournalPassage.estLeSatellite(m.texte)) return listOfNotNull(t("journal_digi_iss"), m.details.ifBlank { null })
        val loc = f?.locator?.ifBlank { null }
        val km = loc?.let { JournalPassage.distanceKm(monLocator, it) }
        return listOfNotNull(
            f?.nom?.takeIf { it.isNotBlank() },
            listOfNotNull(f?.qth?.ifBlank { null }, f?.pays?.ifBlank { null }).joinToString(", ").ifBlank { null },
            listOfNotNull(loc, km?.let { "$it km" }).joinToString(" · ").ifBlank { null },
            m.details.ifBlank { null })
    }

    fun dessineEncart(c: Canvas, x: Float, y: Float, largeur: Float, m: Marque, f: JournalPassage.Fiche?, monLocator: String, dp: Float) {
        val lignes = lignesEncart(m, f, monLocator)
        val titre = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * dp; isFakeBoldText = true }
        val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 215, 230); textSize = 11.5f * dp }
        val h = 26f * dp + lignes.size * 15f * dp + 6f * dp
        val r = android.graphics.RectF(x, y, x + largeur, y + h)
        c.drawRoundRect(r, 8 * dp, 8 * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 12, 18, 28) })
        val coul = when { m.type == TypeMarque.QSO -> QSO; m.type == TypeMarque.SIGNET -> SIGNET; m.viaIss -> ISS; else -> APRS }
        c.drawRoundRect(r, 8 * dp, 8 * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = coul })
        c.drawText((if (m.type == TypeMarque.QSO) "QSO  " else "APRS  ") + m.texte, x + 10 * dp, y + 20 * dp, titre)
        var yy = y + 38 * dp
        for (l in lignes) {
            var t = l
            while (t.length > 4 && texte.measureText(t) > largeur - 20 * dp) t = t.dropLast(2)
            c.drawText(if (t != l) "$t…" else t, x + 10 * dp, yy, texte); yy += 15 * dp
        }
    }

    /** That picture, its top [part] drawn, a bright line where it is being drawn. */
    fun dessineArrivee(c: Canvas, b: Bitmap, r: android.graphics.RectF, part: Float, dp: Float) {
        val hSrc = (b.height * part).toInt().coerceIn(1, b.height)
        val bas = r.top + r.height() * part
        c.drawRect(r, Paint().apply { color = Color.rgb(16, 20, 24) })
        c.drawBitmap(b, android.graphics.Rect(0, 0, b.width, hSrc), android.graphics.RectF(r.left, r.top, r.right, bas), Paint(Paint.FILTER_BITMAP_FLAG))
        if (part < 1f) c.drawRect(r.left, bas - 1.5f * dp, r.right, bas + 1.5f * dp, Paint().apply { color = Color.WHITE; alpha = 220 })
        c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = SSTV[0] })
    }

    fun ciel(
        c: Canvas, w: Float, h: Float, e: JournalPassage.Entree, prevue: List<Pair<Double, Double>>,
        marques: List<Marque>, instant: Long?, cadre: JournalPassage.Cadre, sombre: Boolean, dp: Float
    ) {
        val r = minOf(w, h) / 2f * 0.88f * cadre.echelle.toFloat()
        fun xy(az: Double, el: Double): Pair<Float, Float> {
            val (ux, uy) = JournalPassage.ciel(az, el)
            return (w / 2 + ((ux - cadre.cx) * r).toFloat()) to (h / 2 + ((uy - cadre.cy) * r).toFloat())
        }
        val ox = w / 2 - (cadre.cx * r).toFloat(); val oy = h / 2 - (cadre.cy * r).toFloat()
        c.drawColor(if (sombre) Color.rgb(10, 15, 24) else Color.rgb(236, 241, 247))
        c.drawCircle(ox, oy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (sombre) Color.rgb(17, 27, 41) else Color.rgb(222, 231, 241) })
        val grille = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = if (sombre) Color.rgb(45, 58, 77) else Color.rgb(185, 198, 216)
        }
        for (k in listOf(1f, 2f / 3f, 1f / 3f)) c.drawCircle(ox, oy, r * k, grille)
        c.drawLine(ox - r, oy, ox + r, oy, grille); c.drawLine(ox, oy - r, ox, oy + r, grille)
        val doux = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (sombre) Color.rgb(120, 135, 155) else Color.rgb(90, 105, 125); textSize = 11f * dp }
        c.drawText("30°", ox + 3 * dp, oy - r * 2 / 3 - 3 * dp, doux); c.drawText("60°", ox + 3 * dp, oy - r / 3 - 3 * dp, doux)
        val card = Paint(doux).apply { textAlign = Paint.Align.CENTER; textSize = 13f * dp; isFakeBoldText = true }
        c.drawText("N", ox, oy - r - 5 * dp, card); c.drawText("S", ox, oy + r + 15 * dp, card)
        c.drawText(t("journal_est"), ox + r + 10 * dp, oy + 5 * dp, card); c.drawText(t("journal_ouest"), ox - r - 10 * dp, oy + 5 * dp, card)

        fun trace(l: List<Pair<Double, Double>>, p: Paint) {
            if (l.size < 2) return
            val path = Path()
            l.forEachIndexed { i, (az, el) -> val (x, y) = xy(az, el); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            c.drawPath(path, p)
        }
        trace(prevue, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = Color.rgb(130, 145, 165)
            pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
        })
        val tout = e.points.map { it.az to it.el }
        val fait = e.points.filter { instant == null || it.tMs <= instant }.map { it.az to it.el }
        val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3.5f * dp; color = CYAN; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        // During the replay, the rest of the pass stays faint: the whole trajectory is always seen.
        if (instant != null) trace(tout, Paint(trait).apply { alpha = 60 })
        trace(fait, trait)

        val etiquette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * dp; isFakeBoldText = true; color = if (sombre) Color.WHITE else Color.BLACK
            setShadowLayer(3f * dp, 0f, 0f, if (sombre) Color.BLACK else Color.WHITE)
        }
        // SSTV pictures: the stretch of trajectory each took to arrive, a colour each.
        var nImage = 0
        for (m in marques.filter { it.type == TypeMarque.SSTV }) {
            val coul = couleurSstv(nImage++)
            if (instant != null && m.debutMs > instant) continue
            val fin = if (instant != null) minOf(m.finMs, instant) else m.finMs
            val seg = JournalPassage.segment(e, m.debutMs, fin).map { it.az to it.el }
            trace(seg, Paint(trait).apply { strokeWidth = 8f * dp; color = coul; alpha = 220 })
            seg.lastOrNull()?.let { (az, el) -> val (x, y) = xy(az, el); c.drawText("🖼 " + m.texte, x + 8 * dp, y + 16 * dp, etiquette) }
        }
        // Contacts and APRS frames: where the satellite was, with the callsign.
        val ecrits = ArrayList<Triple<String, Float, Float>>()
        for (m in marques.filter { it.type != TypeMarque.SSTV }) {
            if (instant != null && m.debutMs > instant) continue
            val p = JournalPassage.pointA(e, m.debutMs) ?: continue
            val (x, y) = xy(p.az, p.el)
            val dejaEcrit = ecrits.any { it.first == m.texte && kotlin.math.hypot(it.second - x, it.third - y) < 40 * dp }
            if (!dejaEcrit) ecrits += Triple(m.texte, x, y)
            val coul = when { m.type == TypeMarque.QSO -> QSO; m.type == TypeMarque.SIGNET -> SIGNET; m.viaIss -> ISS; else -> APRS }
            c.drawCircle(x, y, 6f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(x, y, 4.5f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coul })
            // A bookmark's flag below the point: a contact at the same moment keeps its callsign readable.
            if (!dejaEcrit) c.drawText(m.texte, x + 8 * dp, y + (if (m.type == TypeMarque.SIGNET) 30 else -6) * dp, etiquette)
        }
        // Where the satellite is at the moment replayed; the highest point otherwise.
        val ici = instant?.let { JournalPassage.pointA(e, it) } ?: e.points.maxByOrNull { it.el }
        ici?.let { val (x, y) = xy(it.az, it.el)
            c.drawCircle(x, y, 9f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ISS; alpha = 90 })
            c.drawCircle(x, y, 5.5f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ISS })
        }
    }

    // ---------------------------------------------------------------- map

    /**
     * What goes on the map of a pass, over the tiles: the footprint of the
     * satellite, its ground track, each SSTV picture as its stretch, each
     * station as a point on the track and, in grey, where it is; home.
     * Returns where the satellite is drawn (x, as a share of the width).
     */
    fun carte(
        c: Canvas, w: Float, h: Float, e: JournalPassage.Entree, sol: List<JournalPassage.Sol>, marques: List<Marque>,
        instant: Long?, qth: Pair<Double, Double>?, vue: JournalPassage.VueCarte, dp: Float
    ): Float {
        val monde = 256.0 * Math.pow(2.0, vue.zoom)
        fun xy(lat: Double, lon: Double): Pair<Float, Float> =
            ((JournalPassage.mercX(lon) - vue.cx) * monde + w / 2).toFloat() to ((JournalPassage.mercY(lat) - vue.cy) * monde + h / 2).toFloat()
        val haut = e.points.maxByOrNull { it.el }?.tMs
        val ici = (instant ?: haut)?.let { JournalPassage.solA(sol, it) }
        // The footprint: where the satellite is above the horizon from.
        ici?.let { s ->
            val emp = JournalPassage.empreinte(s.lat, s.lon, s.altKm)
            val coupe = emp.zipWithNext().any { (a, b) -> kotlin.math.abs(b.second - a.second) > 180.0 }
            val bord = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = ISS; alpha = 200 }
            if (!coupe) {
                val path = Path()
                emp.forEachIndexed { k, (la, lo) -> val (x, y) = xy(la, lo); if (k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close()
                c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ISS; alpha = 30 })
                c.drawPath(path, bord)
            } else for ((a, b) in emp.zipWithNext()) {
                if (kotlin.math.abs(b.second - a.second) > 180.0) continue
                val (x1, y1) = xy(a.first, a.second); val (x2, y2) = xy(b.first, b.second); c.drawLine(x1, y1, x2, y2, bord)
            }
        }
        fun ligne(l: List<JournalPassage.Sol>, p: Paint) {
            for (k in 1 until l.size) {
                val a = l[k - 1]; val b = l[k]
                if (kotlin.math.abs(b.lon - a.lon) > 180.0) continue
                val (x1, y1) = xy(a.lat, a.lon); val (x2, y2) = xy(b.lat, b.lon); c.drawLine(x1, y1, x2, y2, p)
            }
        }
        val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3.5f * dp; color = CYAN; strokeCap = Paint.Cap.ROUND }
        if (instant != null) { ligne(sol, Paint(trait).apply { alpha = 90 }); ligne(sol.filter { it.tMs <= instant }, trait) }
        else ligne(sol, trait)
        marques.filter { it.type == TypeMarque.SSTV }.forEachIndexed { k, m ->
            if (instant != null && m.debutMs > instant) return@forEachIndexed
            val fin = if (instant != null) minOf(m.finMs, instant) else m.finMs
            ligne(listOfNotNull(JournalPassage.solA(sol, m.debutMs)) + sol.filter { it.tMs in m.debutMs..fin },
                Paint(trait).apply { strokeWidth = 8f * dp; color = couleurSstv(k) })
        }
        val noir = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * dp; color = Color.BLACK; isFakeBoldText = true; setShadowLayer(3f * dp, 0f, 0f, Color.WHITE)
        }
        val grisTexte = Paint(noir).apply { color = Color.rgb(90, 98, 110); isFakeBoldText = false }
        val gris = Color.rgb(122, 130, 142)
        val blanc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        // One label per station where its points touch (several frames from the same place).
        val ecrits = ArrayList<Triple<String, Float, Float>>()
        fun ecrire(t: String, x: Float, y: Float): Boolean {
            if (ecrits.any { it.first == t && kotlin.math.hypot(it.second - x, it.third - y) < 40 * dp }) return false
            ecrits += Triple(t, x, y); return true
        }
        for (m in marques.filter { it.type != TypeMarque.SSTV }) {
            if (instant != null && m.debutMs > instant) continue
            val s = JournalPassage.solA(sol, m.debutMs) ?: continue
            val coul = when { m.type == TypeMarque.QSO -> QSO; m.type == TypeMarque.SIGNET -> SIGNET; m.viaIss -> ISS; else -> APRS }
            val (sx, sy) = xy(s.lat, s.lon)
            // The station where it is, in grey, linked to the satellite at that moment.
            if (m.lat != null && m.lon != null) {
                val (lx, ly) = xy(m.lat, m.lon)
                c.drawLine(lx, ly, sx, sy, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gris; alpha = 180; strokeWidth = 1.2f * dp })
                c.drawCircle(lx, ly, 5f * dp, blanc); c.drawCircle(lx, ly, 3.5f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gris })
                if (ecrire("@" + m.texte, lx, ly)) c.drawText(m.texte, lx + 7 * dp, ly + 14 * dp, grisTexte)
            }
            c.drawCircle(sx, sy, 6f * dp, blanc); c.drawCircle(sx, sy, 4.5f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coul })
            if (ecrire(m.texte, sx, sy)) c.drawText(m.texte, sx + 8 * dp, sy + (if (m.type == TypeMarque.SIGNET) 30 else -6) * dp, noir)
        }
        // Home: not to be taken for a contact.
        qth?.let { (la, lo) -> val (x, y) = xy(la, lo)
            c.drawCircle(x, y, 7.5f * dp, blanc); c.drawCircle(x, y, 5f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 30, 40) }) }
        var satX = 0.5f
        ici?.let { s -> val (x, y) = xy(s.lat, s.lon)
            satX = x / w
            c.drawCircle(x, y, 11f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ISS; alpha = 90 })
            c.drawCircle(x, y, 6f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ISS }) }
        return satX
    }

    /** The OpenStreetMap tiles of a fixed view, fetched once, for the video and the GIF. */
    fun fondCarte(w: Int, h: Int, vue: JournalPassage.VueCarte): Bitmap? = runCatching {
        val p = MapProviders.byId("OSM")
        val modele = p.template ?: return null
        val z = kotlin.math.floor(vue.zoom).toInt().coerceIn(0, p.maxZ)
        val monde = 256.0 * Math.pow(2.0, vue.zoom)
        val n = 1 shl z
        val cote = (monde / n).toFloat()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(221, 230, 241))
        val client = okhttp3.OkHttpClient()
        val x0 = kotlin.math.floor((vue.cx * monde - w / 2) / cote).toInt()
        val x1 = kotlin.math.floor((vue.cx * monde + w / 2) / cote).toInt()
        val y0 = kotlin.math.floor((vue.cy * monde - h / 2) / cote).toInt().coerceAtLeast(0)
        val y1 = kotlin.math.floor((vue.cy * monde + h / 2) / cote).toInt().coerceAtMost(n - 1)
        for (tx in x0..x1) for (ty in y0..y1) {
            val xw = ((tx % n) + n) % n
            val url = modele.replace("{z}", "$z").replace("{x}", "$xw").replace("{y}", "$ty")
            val img = runCatching {
                client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", fr.f4ioz.satcombo.data.TleRepository.USER_AGENT).build())
                    .execute().use { r -> if (r.isSuccessful) android.graphics.BitmapFactory.decodeStream(r.body?.byteStream()) else null }
            }.getOrNull() ?: continue
            val ox = (tx * cote - vue.cx * monde + w / 2).toFloat(); val oy = (ty * cote - vue.cy * monde + h / 2).toFloat()
            c.drawBitmap(img, null, android.graphics.RectF(ox, oy, ox + cote + 1, oy + cote + 1), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        // The attribution the tiles ask for.
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = h / 70f; color = Color.rgb(50, 50, 50); textAlign = Paint.Align.RIGHT }
        c.drawRect(w - t.measureText(p.attribution) - 12, h - t.textSize * 1.6f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.WHITE; alpha = 180 })
        c.drawText(p.attribution, w - 6f, h - t.textSize * 0.4f, t)
        b
    }.getOrNull()

    // ------------------------------------------------------------- frames

    /** Everything a frame needs, the same for each. */
    class Scene(
        val e: JournalPassage.Entree, val prevue: List<Pair<Double, Double>>, val marques: List<Marque>,
        val sol: List<JournalPassage.Sol>, val qth: Pair<Double, Double>?, val surCarte: Boolean,
        val indicatif: String, val utc: Boolean, val flashS: Int, val tailleFlash: Float,
        /** What shows during the replay: the SSTV picture arriving, the stations' cards. */
        val affSstv: Boolean = true, val affFiches: Boolean = true,
        /** The view as shown on screen: the sky's framing, the map's view and the width it was shown at. */
        cadreVu: JournalPassage.Cadre? = null, val vueVue: Pair<JournalPassage.VueCarte, Float>? = null,
        /** Who the stations are, and whether the video ends on a summary. */
        val fiches: Map<String, JournalPassage.Fiche> = emptyMap(), val recap: Boolean = false,
        /** The video opens on a title: satellite, UTC date and time, the receiving station. */
        val ouverture: Boolean = false,
        /** The video's size: "XS" (480 px, the lightest), "M" (720 px), "HD" (1080 px). */
        val resolution: String = "M",
        /** Accelerated: fast and silent where nothing was logged (null: the whole pass alike). */
        val segments: List<JournalPassage.Segment>? = null,
        /** The file's name without extension (null: the pass's own). */
        val nomFichier: String? = null,
        /** An extract's own title on the opening page ("QSO EA4XYZ", "APRS RS0ISS"). */
        val titreExtrait: String? = null
    ) {
        val cadre = cadreVu ?: JournalPassage.cadre(e.points.map { it.az to it.el })
        var vueCarte = JournalPassage.VueCarte()
        var fond: Bitmap? = null
        var images: Map<String, Bitmap> = emptyMap()
        var logo: android.graphics.drawable.Drawable? = null
        /** How much of the pass the picture stays shown, at the export's speed. */
        var flashMs = 0L
    }

    /** One frame of the replay: the sky or the map, above a caption. */
    private fun image(w: Int, h: Int, sc: Scene, instant: Long): Bitmap {
        val e = sc.e
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val dp = w / 360f
        val haut = w
        c.save(); c.clipRect(0f, 0f, w.toFloat(), haut.toFloat())
        val satX = if (sc.surCarte) {
            sc.fond?.let { c.drawBitmap(it, 0f, 0f, null) } ?: c.drawColor(Color.rgb(221, 230, 241))
            carte(c, w.toFloat(), haut.toFloat(), e, sc.sol, sc.marques, instant, sc.qth, sc.vueCarte, dp)
        } else {
            ciel(c, w.toFloat(), haut.toFloat(), e, sc.prevue, sc.marques, instant, sc.cadre, true, dp)
            val p = JournalPassage.pointA(e, instant)
            if (p == null) 0.5f else 0.5f + ((JournalPassage.ciel(p.az, p.el).first - sc.cadre.cx) * sc.cadre.echelle).toFloat() / 2
        }
        // The SSTV picture arriving, in the corner away from the satellite.
        if (sc.affSstv) imageA(sc.marques, instant, sc.flashMs)?.let { (m, part) ->
            val bm = m.fichier?.let { sc.images[it] } ?: return@let
            val lw = w * sc.tailleFlash; val lh = lw * 3 / 4
            val x = if (satX >= 0.5f) 8 * dp else w - lw - 8 * dp
            dessineArrivee(c, bm, android.graphics.RectF(x, 8 * dp, x + lw, 8 * dp + lh), part, dp)
        }
        // The stations just worked and heard: their cards stacked at the bottom, on the same
        // side as the picture (away from the satellite), the picture being at the top.
        if (sc.affFiches) {
            val lw = w * 0.58f
            val x = if (satX >= 0.5f) 8 * dp else w - lw - 8 * dp
            var bas = haut - 8 * dp
            for (type in listOf(TypeMarque.QSO, TypeMarque.APRS)) {
                val m = indicatifA(sc.marques, instant, sc.flashMs, type) ?: continue
                val n = lignesEncart(m, sc.fiches[m.texte], e.locator).size
                val hEnc = (32f + n * 15f) * dp
                dessineEncart(c, x, bas - hEnc, lw, m, sc.fiches[m.texte], e.locator, dp)
                bas -= hEnc + 6 * dp
            }
        }
        c.restore()
        c.drawRect(0f, haut.toFloat(), w.toFloat(), h.toFloat(), Paint().apply { color = Color.rgb(10, 15, 24) })
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val titre = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f * dp; isFakeBoldText = true }
        val gris = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 175, 195); textSize = 13f * dp }
        c.drawText(e.satName, 14f * dp, haut + 30f * dp, titre)
        // Always UTC in what is shared: the time radio amateurs compare.
        c.drawText(fmt.format(Date(instant)) + " UTC", 14f * dp, haut + 50f * dp, gris)
        JournalPassage.pointA(e, instant)?.let { p ->
            c.drawText("Az %.0f°  El %.0f°".format(p.az, p.el) + (p.dlHz?.let { "  ↓ %.4f MHz".format(it / 1e6) } ?: ""),
                14f * dp, haut + 68f * dp, gris)
        }
        // The rig's S-meter at the moments of reception (an SSTV picture, a contact, a frame).
        JournalPassage.signalA(e, instant)?.takeIf { momentDeReception(sc.marques, instant, sc.flashMs) }?.let { v ->
            // Clear of the logo at the bottom right.
            dessineSMetre(c, 14f * dp, haut + 72f * dp, w - 70f * dp, 22f * dp, v, JournalPassage.crete(e, instant), dp)
        }
        val bas = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; textSize = 15f * dp; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
        c.drawText(listOf(sc.indicatif, e.locator).filter { it.isNotBlank() }.joinToString(" · "), 14f * dp, h - 10f * dp, gris)
        val logo = sc.logo
        val xm = if (logo != null) w - 44f * dp else w - 14f * dp
        c.drawText("SatMe", xm, h - 10f * dp, bas)
        logo?.let { it.setBounds((w - 40 * dp).toInt(), (h - 34 * dp).toInt(), (w - 12 * dp).toInt(), (h - 6 * dp).toInt()); it.draw(c) }
        return b
    }

    /**
     * The S-meter as an IC-9700 shows it: a horizontal bar of blocks under its
     * scale (S 1 3 5 7 9, then +20 +40 +60 dB), white to S9 and red beyond, a
     * peak-hold block, the reading at the right. [v] and [crete] on Icom's
     * scale (0 = S0, 120 = S9, 241 = S9+60).
     */
    fun dessineSMetre(c: Canvas, x: Float, y: Float, w: Float, h: Float, v: Int, crete: Int?, dp: Float) {
        fun part(n: Int) = (if (n <= 120) n / 120f * 0.6f else 0.6f + (n - 120) / 121f * 0.4f).coerceIn(0f, 1f)
        val l = w - 34 * dp
        val echelle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 8.5f * dp; textAlign = Paint.Align.CENTER }
        val rouge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 80, 70); textSize = 8.5f * dp; textAlign = Paint.Align.CENTER }
        c.drawText("S", x + 3 * dp, y + 8 * dp, echelle)
        for (n in listOf(1, 3, 5, 7, 9)) c.drawText(n.toString(), x + l * (n / 9f * 0.6f), y + 8 * dp, echelle)
        for (db in listOf(20, 40, 60)) c.drawText("+$db", x + l * (0.6f + db / 60f * 0.4f) - (if (db == 60) 6 * dp else 0f), y + 8 * dp, rouge)
        // The blocks.
        val n = 46
        val haut = y + 11 * dp; val bas = y + h
        val pas = l / n
        val lu = part(v); val pic = crete?.let { part(it) }
        for (i in 0 until n) {
            val f = (i + 0.5f) / n
            val sur = f > 0.6f
            val allume = f <= lu
            val coul = when {
                pic != null && pic > lu && kotlin.math.abs(f - pic) < 0.5f / n -> Color.rgb(255, 220, 90)
                allume && sur -> Color.rgb(255, 70, 60)
                allume -> Color.rgb(235, 240, 245)
                sur -> Color.argb(70, 255, 70, 60)
                else -> Color.argb(55, 235, 240, 245)
            }
            c.drawRect(x + i * pas + 0.6f * dp, haut, x + (i + 1) * pas - 0.6f * dp, bas, Paint().apply { color = coul })
        }
        val lecture = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (v > 120) Color.rgb(255, 90, 80) else Color.WHITE
            textSize = 11f * dp; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
        c.drawText(JournalPassage.libelleS(v), x + w, bas, lecture)
    }

    /** A moment of reception: an SSTV picture arriving, a contact, a frame or a bookmark, for as long as its card shows. */
    fun momentDeReception(marques: List<Marque>, instant: Long, garde: Long): Boolean =
        marques.any { instant in (it.debutMs - 2_000L)..(it.finMs + garde) }

    /** Prepares a scene for frames [w] wide: logo, pictures, and for the map its view and tiles. */
    private fun prepare(ctx: Context, sc: Scene, w: Int) {
        sc.logo = runCatching { ctx.packageManager.getApplicationIcon(ctx.applicationInfo) }.getOrNull()
        sc.images = vignettes(sc.marques)
        if (sc.surCarte) {
            // The view shown on screen, at the export's width; else framed on the pass.
            sc.vueVue?.let { (v, wEcran) ->
                sc.vueCarte = v.copy(zoom = v.zoom + kotlin.math.log2(w / wEcran.coerceAtLeast(1f).toDouble()))
                sc.fond = fondCarte(w, w, sc.vueCarte)
                return
            }
            val s = sc.e.points.maxByOrNull { it.el }?.let { JournalPassage.solA(sc.sol, it.tMs) }
            val pts = sc.sol.map { it.lat to it.lon } + (s?.let { JournalPassage.empreinte(it.lat, it.lon, it.altKm) } ?: emptyList()) +
                sc.marques.mapNotNull { m -> m.lat?.let { la -> m.lon?.let { la to it } } } + listOfNotNull(sc.qth)
            sc.vueCarte = JournalPassage.cadreCarte(pts, w.toDouble(), w.toDouble())
            sc.fond = fondCarte(w, w, sc.vueCarte)
        }
    }

    /** The opening title: the satellite, when (UTC), the receiving station, SatMe. */
    private fun titre(w: Int, h: Int, sc: Scene): Bitmap {
        val e = sc.e
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val dp = w / 360f
        c.drawColor(Color.rgb(10, 15, 24))
        val centre = Paint.Align.CENTER
        val sat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f * dp; isFakeBoldText = true; textAlign = centre }
        while (sat.measureText(e.satName) > w - 24 * dp && sat.textSize > 14 * dp) sat.textSize -= 2 * dp
        val clair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 215, 230); textSize = 17f * dp; textAlign = centre }
        val station = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; textSize = 22f * dp; isFakeBoldText = true; textAlign = centre }
        val utc = TimeZone.getTimeZone("UTC")
        val jour = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        val heure = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = utc }
        // An extract: its own title and times; else the whole pass.
        val de = sc.segments?.firstOrNull()?.deMs ?: e.debutMs
        val a = sc.segments?.lastOrNull()?.aMs ?: e.finMs
        var y = h * (if (sc.titreExtrait != null) 0.24f else 0.30f)
        c.drawText(e.satName, w / 2f, y, sat); y += 34 * dp
        sc.titreExtrait?.takeIf { it.isNotBlank() }?.let { tx ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 79, 163); textSize = 28f * dp; isFakeBoldText = true; textAlign = centre }
            while (p.measureText(tx) > w - 24 * dp && p.textSize > 12 * dp) p.textSize -= 2 * dp
            c.drawText(tx, w / 2f, y + 8 * dp, p); y += 44 * dp
        }
        c.drawText(jour.format(Date(de)), w / 2f, y, clair); y += 24 * dp
        c.drawText(heure.format(Date(de)) + " → " + heure.format(Date(a)) + " UTC", w / 2f, y, clair); y += 22 * dp
        if (sc.titreExtrait == null) c.drawText("%.0f°".format(e.elMax) + " · " + "%d:%02d".format(e.dureeMs / 60_000, e.dureeMs / 1000 % 60), w / 2f, y,
            Paint(clair).apply { color = Color.rgb(150, 165, 185); textSize = 14f * dp })
        y += 46 * dp
        // The receiving station.
        listOf(sc.indicatif, e.locator).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() }?.let {
            c.drawText(t("journal_station_rx"), w / 2f, y, Paint(clair).apply { textSize = 12f * dp; color = Color.rgb(150, 165, 185) }); y += 26 * dp
            c.drawText(it, w / 2f, y, station)
        }
        // SatMe, its logo above its name.
        val logo = sc.logo
        val bas = h - 26 * dp
        logo?.let { val s = 56 * dp; it.setBounds((w / 2f - s / 2).toInt(), (bas - 30 * dp - s).toInt(), (w / 2f + s / 2).toInt(), (bas - 30 * dp).toInt()); it.draw(c) }
        c.drawText("SatMe", w / 2f, bas, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; textSize = 24f * dp; isFakeBoldText = true; textAlign = centre })
        return out
    }

    /** One SSTV picture of the pass, full frame, with when and how it came. */
    private fun imagePleine(w: Int, h: Int, sc: Scene, m: Marque, b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val dp = w / 360f
        c.drawColor(Color.rgb(10, 15, 24))
        val ih = w * b.height.toFloat() / b.width
        val top = (h - 70 * dp - ih) / 2
        c.drawBitmap(b, null, android.graphics.RectF(0f, top, w.toFloat(), top + ih), Paint(Paint.FILTER_BITMAP_FLAG))
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f * dp; isFakeBoldText = true }
        val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 175, 195); textSize = 13f * dp }
        c.drawText(sc.e.satName + " · " + m.texte, 14 * dp, h - 44 * dp, t1)
        c.drawText(fmt.format(Date(m.finMs)) + " UTC" + "  ·  " +
            listOf(sc.indicatif, sc.e.locator).filter { it.isNotBlank() }.joinToString(" · "), 14 * dp, h - 22 * dp, t2)
        return out
    }

    /** The pass summed up: pictures, stations worked and heard, from where. */
    private fun resume(w: Int, h: Int, sc: Scene): Bitmap {
        val e = sc.e
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val dp = w / 360f
        c.drawColor(Color.rgb(10, 15, 24))
        val titre = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f * dp; isFakeBoldText = true }
        val gris = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 175, 195); textSize = 13f * dp }
        val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * dp }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        var y = 40f * dp
        c.drawText(e.satName, 16 * dp, y, titre); y += 22 * dp
        c.drawText(fmt.format(Date(e.debutMs)) + " UTC" + " · " +
            "%.0f° · %d min".format(e.elMax, (e.dureeMs / 60_000).toInt()), 16 * dp, y, gris); y += 18 * dp
        c.drawText(listOf(sc.indicatif, e.locator).filter { it.isNotBlank() }.joinToString(" · "), 16 * dp, y,
            Paint(gris).apply { color = CYAN; isFakeBoldText = true }); y += 16 * dp
        // The pictures, side by side.
        val photos = sc.marques.filter { it.type == TypeMarque.SSTV }.mapNotNull { m -> m.fichier?.let { sc.images[it] } }.take(6)
        if (photos.isNotEmpty()) {
            val col = if (photos.size == 1) 1 else 2
            val pw = (w - 16 * dp * 2 - 8 * dp * (col - 1)) / col; val ph = pw * 3 / 4
            photos.forEachIndexed { i, b ->
                val x = 16 * dp + (i % col) * (pw + 8 * dp); val yy = y + (i / col) * (ph + 8 * dp)
                c.drawBitmap(b, null, android.graphics.RectF(x, yy, x + pw, yy + ph), Paint(Paint.FILTER_BITMAP_FLAG))
                c.drawRect(android.graphics.RectF(x, yy, x + pw, yy + ph), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 2f * dp; color = SSTV[0] })
            }
            y += ((photos.size + col - 1) / col) * (ph + 8 * dp) + 8 * dp
        }
        fun bloc(titreBloc: String, coul: Int, l: List<String>) {
            if (l.isEmpty() || y > h - 30 * dp) return
            c.drawText(titreBloc, 16 * dp, y + 14 * dp, Paint(texte).apply { color = coul; isFakeBoldText = true }); y += 22 * dp
            for (s in l) { if (y > h - 24 * dp) break; c.drawText(s, 24 * dp, y + 12 * dp, texte); y += 18 * dp }
            y += 6 * dp
        }
        val qsos = sc.marques.filter { it.type == TypeMarque.QSO }.distinctBy { it.texte }
        bloc(t("journal_recap_qso").format(qsos.size), QSO, qsos.map { m ->
            listOfNotNull(m.texte, sc.fiches[m.texte]?.locator?.ifBlank { null }, sc.fiches[m.texte]?.nom?.ifBlank { null }).joinToString(" · ") })
        // Every station heard, the ISS's own digipeater included.
        val aprs = sc.marques.filter { it.type == TypeMarque.APRS }.distinctBy { it.texte }
        bloc(t("journal_recap_aprs").format(aprs.size), APRS, aprs.map { m ->
            if (JournalPassage.estLeSatellite(m.texte)) m.texte + " — " + t("journal_digi_iss_court")
            else listOfNotNull(m.texte, sc.fiches[m.texte]?.locator?.ifBlank { null }, sc.fiches[m.texte]?.nom?.ifBlank { null })
                .joinToString(" · ") })
        val bas = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN; textSize = 15f * dp; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
        val logo = sc.logo
        c.drawText("SatMe", if (logo != null) w - 44f * dp else w - 14f * dp, h - 10f * dp, bas)
        logo?.let { it.setBounds((w - 40 * dp).toInt(), (h - 34 * dp).toInt(), (w - 12 * dp).toInt(), (h - 6 * dp).toInt()); it.draw(c) }
        return out
    }

    /** The end of the video: each picture (frames each), then the summary. */
    private fun finVideo(w: Int, h: Int, sc: Scene, parImage: Int, resumeN: Int): List<Pair<() -> Bitmap, Int>> {
        if (!sc.recap) return emptyList()
        val l = ArrayList<Pair<() -> Bitmap, Int>>()
        sc.marques.filter { it.type == TypeMarque.SSTV }.forEach { m ->
            val b = m.fichier?.let { sc.images[it] } ?: return@forEach
            l += { imagePleine(w, h, sc, m, b) } to parImage
        }
        l += { resume(w, h, sc) } to resumeN
        return l
    }

    private fun nom(e: JournalPassage.Entree, ext: String) = "SatMe_Passage_" + e.satName.replace(Regex("[^A-Za-z0-9-]"), "-") + "_" +
        SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(e.debutMs)) + ext

    /**
     * The replay as a video. With the recording: in real time, with its sound.
     * Without: ten times faster, silent.
     */
    /** The view as shown, at [instant] (the end of the pass when not replaying), as a picture with its caption. */
    fun png(ctx: Context, sc: Scene, instant: Long?): File? = runCatching {
        val w = 1080; val h = 1440
        prepare(ctx, sc, w)
        sc.flashMs = sc.flashS * 1000L
        val b = image(w, h, sc, instant ?: sc.e.finMs)
        val f = File(File(ctx.cacheDir, "export").apply { mkdirs() }, nom(sc.e, ".png"))
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f
    }.getOrNull()

    /** One recording of a pass: its file, when its start was heard, its spoken header, its length. */
    data class Morceau(val f: File, val origine: Long, val annonce: Long, val dureeMs: Long)

    /** One recording only (the older calls). */
    fun video(ctx: Context, sc: Scene, son: File?, debutSon: Long?, annonceMs: Long, progres: (Float) -> Unit): File? =
        video(ctx, sc, if (son != null && debutSon != null) listOf(Morceau(son, debutSon, annonceMs, Long.MAX_VALUE / 4)) else emptyList(), progres)

    /** The sound of [de]..[a] from every piece covering it, in one track (silence between them). */
    fun sonMorceaux(morceaux: List<Morceau>, de: Long, a: Long): Pair<ShortArray, Int>? {
        var rate = 0
        var out: ShortArray? = null
        for (m in morceaux.sortedBy { it.origine }) {
            if (m.origine + m.annonce >= a || m.origine + m.dureeMs <= de) continue
            val (p, r) = extraitSon(m.f, de - m.origine, minOf(a, m.origine + m.dureeMs) - m.origine, m.annonce) ?: continue
            if (out == null) { rate = r; out = ShortArray(((a - de) * r / 1000).toInt()) }
            val o = out!!
            // Same place in time, whatever each file's rate.
            for (i in o.indices) {
                val k = (i.toLong() * r / rate).toInt()
                if (k >= p.size) break
                if (p[k].toInt() != 0) o[i] = p[k]
            }
        }
        return out?.let { it to rate }
    }

    fun video(ctx: Context, sc: Scene, morceaux: List<Morceau>, progres: (Float) -> Unit): File? = runCatching {
        val e = sc.e
        val nv12 = SstvVideo.nv12() ?: return null
        // Very light (480×640, 0.4 Mbit/s), light (720×960, 1 Mbit/s) or HD (1080×1440, 2.5 Mbit/s).
        val (w, h, debit) = when (sc.resolution) { "XS" -> Triple(480, 640, 400_000); "HD" -> Triple(1080, 1440, 2_500_000); else -> Triple(720, 960, 1_000_000) }
        val fps = 5
        // debutSon: when the file's start was; the pass's sound begins after the header.
        // Accelerated or an extract: its stretches may reach past the pass followed (a contact logged after it).
        val segs = sc.segments
        val deSon = minOf(e.debutMs, segs?.firstOrNull()?.deMs ?: e.debutMs)
        val aSon = maxOf(e.finMs, segs?.lastOrNull()?.aMs ?: e.finMs)
        val pcm = if (morceaux.isNotEmpty()) sonMorceaux(morceaux, deSon, aSon) else null
        val vitesse = if (pcm != null || segs != null) 1 else 10
        val nb = (if (segs != null) JournalPassage.dureeLecture(segs) * fps / 1000
            else e.dureeMs * fps / 1000 / vitesse).toInt().coerceAtLeast(2)
        val pcmLu = if (pcm != null && segs != null) sonAccelere(pcm.first, pcm.second, deSon, segs) to pcm.second else pcm
        // The opening title first (3 s): the sound waits for it, to stay with the replay.
        val nbTitre = if (sc.ouverture) 3 * fps else 0
        val son2 = pcmLu?.let { (p, r) -> if (nbTitre == 0) p to r else (ShortArray(nbTitre * r / fps) + p) to r }
        prepare(ctx, sc, w)
        // Shown flashS seconds of the video: that much more of the pass at ×10.
        sc.flashMs = sc.flashS * 1000L * vitesse
        val sortie = File(File(ctx.cacheDir, "export").apply { mkdirs() }, sc.nomFichier?.plus(".mp4") ?: nom(e, ".mp4"))
        // Then, if asked, each picture 3 s and the summary 6 s.
        val fin = finVideo(w, h, sc, 3 * fps, 6 * fps)
        val total = nbTitre + nb + fin.sumOf { it.second }
        var courante: Pair<Int, ByteArray>? = null
        var titreYuv: ByteArray? = null
        val ok = SstvVideo.encodeMp4(sortie, w, h, fps, total, son2?.first, son2?.second ?: 22050, debit, progres) { n0, trame ->
            if (n0 < nbTitre) {
                val y = titreYuv ?: SstvVideo.yuv(titre(w, h, sc), nv12).also { titreYuv = it }
                System.arraycopy(y, 0, trame, 0, trame.size)
                return@encodeMp4
            }
            val n = n0 - nbTitre
            if (n < nb) {
                val tMs = if (segs != null) JournalPassage.instantALecture(segs, n.toLong() * 1000 / fps)
                    else e.debutMs + n.toLong() * 1000 * vitesse / fps
                System.arraycopy(SstvVideo.yuv(image(w, h, sc, tMs), nv12), 0, trame, 0, trame.size)
            } else {
                // Which still, and drawn once for all its frames.
                var k = n - nb; var i = 0
                while (i < fin.size && k >= fin[i].second) { k -= fin[i].second; i++ }
                val c0 = courante
                val y = if (c0 != null && c0.first == i) c0.second else SstvVideo.yuv(fin[i.coerceAtMost(fin.size - 1)].first(), nv12).also { courante = i to it }
                System.arraycopy(y, 0, trame, 0, trame.size)
            }
        }
        if (ok) sortie else null
    }.getOrNull()

    /** The replay as an animated GIF: the pass in about fifteen seconds, then held, looping. */
    fun gif(ctx: Context, sc: Scene, progres: (Float) -> Unit): File? = runCatching {
        val e = sc.e
        val w = 480; val h = 640
        val n = 75
        prepare(ctx, sc, w)
        // The pass (or the extract) in 15 s: flashS seconds of the GIF is that share of it.
        val de = sc.segments?.firstOrNull()?.deMs ?: e.debutMs
        val a = sc.segments?.lastOrNull()?.aMs ?: e.finMs
        val duree = (a - de).coerceAtLeast(1)
        sc.flashMs = sc.flashS * 1000L * duree / 15_000L
        fun px(tMs: Long) = IntArray(w * h).also { image(w, h, sc, tMs).getPixels(it, 0, w, 0, 0, w, h) }
        val fin = px(a)
        // The summary's pictures need their colours too.
        var pourPalette = if (sc.recap) fin + IntArray(w * h).also { resume(w, h, sc).getPixels(it, 0, w, 0, 0, w, h) } else fin
        val titrePx = if (sc.ouverture) IntArray(w * h).also { titre(w, h, sc).getPixels(it, 0, w, 0, 0, w, h) } else null
        if (titrePx != null) pourPalette = pourPalette + titrePx
        val pal = Gif.palette(pourPalette, listOf(0x0A0F18, 0x111B29, 0xFFFFFF, CYAN and 0xFFFFFF, QSO and 0xFFFFFF, APRS and 0xFFFFFF, ISS and 0xFFFFFF))
        val sortie = File(File(ctx.cacheDir, "export").apply { mkdirs() }, sc.nomFichier?.plus(".gif") ?: nom(e, ".gif"))
        sortie.outputStream().buffered().use { o ->
            val g = Gif.Ecrivain(o, w, h, pal)
            // The opening title, 2 s.
            titrePx?.let { g.trame(it, 0, h, 200) }
            var avant: IntArray? = null
            for (i in 0..n) {
                val cur = if (i == n) fin else px(de + duree * i / n)
                // Only the rows that changed since the previous frame.
                val prev = avant
                var y0 = 0; var y1 = h
                if (prev != null) {
                    while (y0 < h && rangeeEgale(prev, cur, w, y0)) y0++
                    while (y1 > y0 && rangeeEgale(prev, cur, w, y1 - 1)) y1--
                    if (y0 >= y1) { y0 = 0; y1 = 1 }
                }
                g.trame(cur, y0, y1, if (i == n) (if (sc.recap) 150 else 300) else 20)
                avant = cur
                progres((i + 1f) / (n + 1))
            }
            // Then, if asked, each picture 2 s and the summary 4 s.
            for ((dessin, n2) in finVideo(w, h, sc, 200, 400)) {
                val px2 = IntArray(w * h).also { dessin().getPixels(it, 0, w, 0, 0, w, h) }
                g.trame(px2, 0, h, n2)
            }
            g.fin()
        }
        sortie
    }.getOrNull()

    private fun vignettes(marques: List<Marque>): Map<String, Bitmap> =
        marques.mapNotNull { m -> m.fichier?.let { f -> PlancheRendu.charge(File(f), 400)?.let { f to it } } }.toMap()

    private fun rangeeEgale(a: IntArray, b: IntArray, w: Int, y: Int): Boolean {
        val o = y * w
        for (x in 0 until w) if (a[o + x] != b[o + x]) return false
        return true
    }

    /**
     * The summary's map: every station worked (at its locator) or heard on
     * APRS (where it said it was), and home, on OpenStreetMap, with a caption.
     */
    fun carteStations(ctx: Context, stations: List<fr.f4ioz.satcombo.JournalDesPassages.StationBilan>, qth: Pair<Double, Double>?,
                      legende: String): File? = runCatching {
        val w = 1080; val h = 1080; val bas = 150
        val pts = stations.map { it.lat to it.lon } + listOfNotNull(qth)
        val vue = JournalPassage.cadreCarte(pts, w.toDouble(), (h - bas).toDouble(), 0.85, 9.0)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(10, 15, 24))
        fondCarte(w, h - bas, vue)?.let { c.drawBitmap(it, 0f, 0f, null) }
        val monde = 256.0 * Math.pow(2.0, vue.zoom)
        fun xy(la: Double, lo: Double) = ((JournalPassage.mercX(lo) - vue.cx) * monde + w / 2.0).toFloat() to
            ((JournalPassage.mercY(la) - vue.cy) * monde + (h - bas) / 2.0).toFloat()
        val dp = w / 360f
        val blanc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val noir = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 24, 32); textSize = 9.5f * dp; typeface = Typeface.DEFAULT_BOLD }
        // APRS first, the contacts over them.
        for (s in stations.sortedBy { it.qso }) {
            val (x, y) = xy(s.lat, s.lon)
            c.drawCircle(x, y, 5.5f * dp, blanc)
            c.drawCircle(x, y, 4f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (s.qso) QSO else APRS })
            if (s.qso || stations.size <= 40) c.drawText(s.indicatif, x + 7 * dp, y - 5 * dp, noir)
        }
        qth?.let { (la, lo) -> val (x, y) = xy(la, lo)
            c.drawCircle(x, y, 7.5f * dp, blanc); c.drawCircle(x, y, 5f * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 30, 40) }) }
        // The caption, and the map's credit.
        c.drawRect(0f, (h - bas).toFloat(), w.toFloat(), h.toFloat(), Paint().apply { color = Color.rgb(10, 15, 24) })
        val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * dp; typeface = Typeface.DEFAULT_BOLD }
        val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 175, 195); textSize = 9f * dp }
        c.drawText(legende, 12 * dp, h - bas + 22 * dp, t1)
        c.drawText("SatMe · © OpenStreetMap", 12 * dp, h - 12 * dp, t2)
        val f = File(File(ctx.cacheDir, "export").apply { mkdirs() }, "SatMe_Stations_" +
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f
    }.getOrNull()

    /** The name of an extract: satellite, what it is about, its UTC time. */
    fun nomExtrait(e: JournalPassage.Entree, quoi: String, tMs: Long) = "SatMe_" + e.satName.replace(Regex("[^A-Za-z0-9-]"), "-") + "_" +
        quoi.replace(Regex("[^A-Za-z0-9-]"), "-") + "_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(tMs)) + "Z"

    /**
     * A moment's sound, [deMs] to [aMs] of the pass, as a WAV (mono, about
     * 22 kHz): what anyone can play. Silence where the recording has none.
     */
    fun extraitWav(ctx: Context, morceaux: List<Morceau>, deMs: Long, aMs: Long, nom: String): File? = runCatching {
        val (pcm, rate) = sonMorceaux(morceaux, deMs, aMs) ?: return null
        if (pcm.none { it.toInt() != 0 }) return null
        val f = File(File(ctx.cacheDir, "export").apply { mkdirs() }, "$nom.wav")
        java.io.DataOutputStream(f.outputStream().buffered()).use { o ->
            fun i32(v: Int) { o.writeByte(v); o.writeByte(v shr 8); o.writeByte(v shr 16); o.writeByte(v shr 24) }
            fun i16(v: Int) { o.writeByte(v); o.writeByte(v shr 8) }
            o.writeBytes("RIFF"); i32(36 + pcm.size * 2); o.writeBytes("WAVE")
            o.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            o.writeBytes("data"); i32(pcm.size * 2)
            for (x in pcm) i16(x.toInt())
        }
        f
    }.getOrNull()

    /**
     * The pass's sound (from the pass's start) laid out as the accelerated
     * replay plays it: kept where it plays at normal speed, silence while it rushes.
     */
    private fun sonAccelere(pcm: ShortArray, rate: Int, debutMs: Long, segs: List<JournalPassage.Segment>): ShortArray {
        val out = ShortArray((JournalPassage.dureeLecture(segs) * rate / 1000).toInt() + rate)
        var n = 0
        for (s in segs) {
            val longueur = ((s.aMs - s.deMs) / s.vitesse * rate / 1000).toInt()
            if (s.vitesse == 1) {
                val de = ((s.deMs - debutMs) * rate / 1000).toInt()
                for (i in 0 until longueur) { val k = de + i; if (k in pcm.indices && n + i < out.size) out[n + i] = pcm[k] }
            }
            n += longueur
        }
        return out.copyOf(n.coerceAtMost(out.size))
    }

    /**
     * The sound of the pass from its recording, about 22 kHz, mono: silence
     * for the part before the sound (the spoken header is never heard).
     */
    private fun extraitSon(f: File, de0: Long, aMs: Long, annonceMs: Long): Pair<ShortArray, Int>? {
        val deMs = maxOf(de0, annonceMs)
        val silenceMs = deMs - de0
        val s = extraitSonBrut(f, deMs, aMs) ?: return null
        if (silenceMs <= 0) return s
        return (ShortArray((silenceMs * s.second / 1000).toInt()) + s.first) to s.second
    }

    private fun extraitSonBrut(f: File, deMs: Long, aMs: Long): Pair<ShortArray, Int>? {
        var out: ShortArray? = null; var n = 0; var rateSortie = 22050
        var lus = 0L; var facteur = 1; var debut = 0L; var fin = 0L
        var somme = 0; var dansSomme = 0
        Mp3Pcm.decode(f) { pcm, count, rate, _ ->
            if (out == null) {
                facteur = (rate / 22050.0).let { kotlin.math.round(it).toInt() }.coerceAtLeast(1)
                rateSortie = rate / facteur
                debut = deMs.coerceAtLeast(0) * rate / 1000; fin = aMs * rate / 1000
                out = ShortArray(((fin - debut) / facteur + 1).toInt().coerceIn(1, 40_000_000))
            }
            val o = out!!
            for (i in 0 until count) {
                val k = lus + i
                if (k < debut || k >= fin) continue
                somme += pcm[i]; dansSomme++
                if (dansSomme == facteur) { if (n < o.size) o[n++] = (somme / facteur).toShort(); somme = 0; dansSomme = 0 }
            }
            lus += count
            lus < fin
        }
        val o = out ?: return null
        return if (n < rateSortie) null else o.copyOf(n) to rateSortie
    }
}
