/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import kotlin.random.Random

/** Draws a [Planche.Modele]: the same drawing for the preview and the export. */
object PlancheRendu {

    /** What the texts say. */
    data class Valeurs(
        val indicatif: String = "",
        val nom: String = "",
        val locator: String = "",
        val dates: String = "",
        val titre: String = ""
    ) {
        fun de(t: Planche.Texte): String = when (t.champ) {
            Planche.Champ.INDICATIF -> indicatif
            Planche.Champ.NOM_LOCATOR -> listOf(nom, if (nom.isNotBlank() && locator.isNotBlank()) "@ $locator" else locator)
                .filter { it.isNotBlank() }.joinToString("\n")
            Planche.Champ.DATES -> dates
            Planche.Champ.TITRE -> titre
            Planche.Champ.LIBRE -> t.libre
        }
    }

    /** The panel over a template's own boxes when the sheet is fitted to its pictures. */
    const val PANNEAU = 0xF205080D.toInt()

    /** An image file decoded no larger than needed; [leger]: half the memory (a thumbnail). */
    fun charge(f: File, largeurMax: Int, leger: Boolean = false): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var n = 1
        while (o.outWidth / (n * 2) >= largeurMax) n *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = n; if (leger) inPreferredConfig = Bitmap.Config.RGB_565
        })
    }.getOrNull()

    // Signatures by file: the gallery's pictures do not change once written.
    private val signatures = HashMap<String, Planche.Signature>()

    /** [Planche.Signature] of each picture, from a small copy, kept. */
    fun signatures(fichiers: List<File>): Map<String, Planche.Signature> = fichiers.mapNotNull { f ->
        val cle = f.name + "@" + f.lastModified()
        val s = synchronized(signatures) { signatures[cle] } ?: charge(f, 80)?.let { b ->
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            Planche.signature(px, b.width, b.height).also { synchronized(signatures) { signatures[cle] = it } }
        }
        s?.let { f.name to it }
    }.toMap()

    // The built-in pictures, decoded once at the size last asked.
    @Volatile private var decorCache: Pair<String, Bitmap>? = null

    /** A picture decoded no larger than needed for [largeurMax]; [ouvre] is read twice (size, then pixels). */
    private fun decode(ouvre: () -> java.io.InputStream?, largeurMax: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ouvre()?.use { BitmapFactory.decodeStream(it, null, o) }
        var n = 1
        while (o.outWidth / (n * 2) >= largeurMax) n *= 2
        ouvre()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = n }) }
    }.getOrNull()

    /**
     * The background picture of [m], decoded for [largeurMax]: a built-in one
     * or the file chosen; null when it is drawn (or the template has its own).
     */
    fun chargeDecor(r: Planche.Rangement, m: Planche.Modele, largeurMax: Int): Bitmap? {
        if (m.fond.isNotBlank()) return null
        val d = PlancheDecor.integre(m.decor)
        if (d != null) {
            if (d.ressource == null) return null
            val cle = d.id + "@" + largeurMax
            decorCache?.let { (k, b) -> if (k == cle) return b }
            return decode({ PlancheDecor.ouvre(d) }, largeurMax)?.also { decorCache = cle to it }
        }
        return r.decor(m)?.let { charge(it, largeurMax) }
    }

    /** A picture of one's own kept as a background in [dest]: 3000 pixels wide at most, JPEG. */
    fun gardeDecor(ouvre: () -> java.io.InputStream?, dest: File): Boolean = runCatching {
        var b = decode(ouvre, 3000) ?: return false
        if (b.width > 3000) b = Bitmap.createScaledBitmap(b, 3000, (3000L * b.height / b.width).toInt().coerceAtLeast(1), true)
        dest.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }.getOrDefault(false)

    /** A patch kept in [dest]: 1600 pixels at most each way, PNG (its transparency kept); its size, or null. */
    fun gardeEcusson(ouvre: () -> java.io.InputStream?, dest: File): Pair<Int, Int>? = runCatching {
        var b = decode(ouvre, 1600) ?: return null
        val k = 1600f / maxOf(b.width, b.height)
        if (k < 1f) b = Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        dest.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.width to b.height
    }.getOrNull()

    /** A built-in background, small ([largeur] wide, the sheet's shape): to choose it. */
    fun apercuDecor(d: PlancheDecor.Decor, largeur: Int): Bitmap {
        val h = (largeur / 1.414f).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(largeur, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val img = if (d.ressource != null) decode({ PlancheDecor.ouvre(d) }, largeur) else null
        if (img != null) dessineCouvert(c, img, RectF(0f, 0f, largeur.toFloat(), h.toFloat())) else fondGenerique(c, largeur, h, d)
        return b
    }

    // Patches ready to draw (surround taken away when asked), by file, choice and size.
    private val ecussons = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }

    /** A patch file decoded for [largeurMax], transparency kept; its surround taken away when [sansContour]. */
    fun ecusson(f: File, sansContour: Boolean, largeurMax: Int): Bitmap? {
        val cle = f.name + "@" + f.lastModified() + "@" + sansContour + "@" + largeurMax
        synchronized(ecussons) { ecussons[cle] }?.let { return it }
        var b = charge(f, largeurMax) ?: return null
        if (b.width > largeurMax) b = Bitmap.createScaledBitmap(b, largeurMax, (largeurMax.toLong() * b.height / b.width).toInt().coerceAtLeast(1), true)
        if (sansContour) {
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            b = Bitmap.createBitmap(PlancheDecor.detoure(px, b.width, b.height), b.width, b.height, Bitmap.Config.ARGB_8888)
        }
        synchronized(ecussons) { ecussons[cle] = b }
        return b
    }

    /** A patch file, its surround taken away, full size, as PNG: what a template file carries ([PlancheFichier]). */
    fun ecussonDetoure(f: File): ByteArray? = ecusson(f, true, 4000)?.let { b ->
        java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** The patches of [m] by their place in its list, each decoded for [largeurMax]. */
    fun chargeEcussons(r: Planche.Rangement, m: Planche.Modele, largeurMax: Int): Map<Int, Bitmap> =
        m.ecussons.mapIndexedNotNull { i, e -> r.fichier(e.fichier)?.let { ecusson(it, e.sansContour, largeurMax) }?.let { i to it } }.toMap()

    /**
     * The sheet [largeur] pixels wide. [images]: the pictures by box, with
     * what is known of them (for the line under each); [decor] its background
     * picture ([chargeDecor]), [ecussons] its patches ([chargeEcussons]).
     */
    /** « corrigée » / « fusion de n copies » in a box's lower right corner. */
    private fun etiquetteTraitee(c: Canvas, texte: String, r: RectF, k: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (r.height() * 0.075f).coerceAtLeast(9f * k); isFakeBoldText = true; color = 0xFFFFD54F.toInt() }
        val tw = p.measureText(texte); val taille = p.textSize
        val boite = RectF(r.right - tw - 14 * k, r.bottom - taille * 1.35f - 4 * k, r.right - 4 * k, r.bottom - 4 * k)
        c.drawRoundRect(boite, 8 * k, 8 * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0000000.toInt() })
        c.drawText(texte, boite.left + 5 * k, boite.bottom - taille * 0.32f, p)
    }

    fun dessine(
        m: Planche.Modele, fond: Bitmap?, largeur: Int,
        images: Map<Int, Pair<Bitmap, SstvMeta.SstvShot>>, v: Valeurs,
        logo: android.graphics.drawable.Drawable? = null,
        decor: Bitmap? = null, ecussons: Map<Int, Bitmap> = emptyMap()
    ): Bitmap {
        val ratio = if (fond != null) fond.width.toFloat() / fond.height else m.ratio
        val w = largeur; val h = (largeur / ratio).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (fond != null) c.drawBitmap(fond, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        else if (decor != null) dessineCouvert(c, decor, RectF(0f, 0f, w.toFloat(), h.toFloat()))
        else fondGenerique(c, w, h, PlancheDecor.integre(m.decor)?.takeIf { it.ressource == null } ?: PlancheDecor.ETOILES)
        val k = w / 1000f
        // Fitted to fewer pictures: the template's printed boxes ("NO IMAGE") hidden under one dark panel.
        if (fond != null && m.ajustee) Planche.englobe(m.origine)?.let { z ->
            val r = RectF(z.x * w - 6 * k, z.y * h - 6 * k, (z.x + z.w) * w + 6 * k, (z.y + z.h) * h + 6 * k)
            c.drawRoundRect(r, 10 * k, 10 * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANNEAU })
        }
        val n = m.cases.size
        m.cases.forEachIndexed { i, z ->
            val r = RectF(z.x * w, z.y * h, (z.x + z.w) * w, (z.y + z.h) * h)
            val img = images[i]
            if (img != null || fond == null) cadreAvant(c, m, r, k, w, h)
            if (img != null) {
                if (m.cadre == PlancheStyle.Cadre.ARRONDI) arrondi(c, r) { cc, rr -> contenuCase(cc, m, img, rr, v, k) }
                else contenuCase(c, m, img, r, v, k)
                cadreApres(c, m, r, k, w, h)
            } else if (fond == null) {
                // A generic sheet shows its empty boxes; an imported one has its own.
                if (m.cadre == PlancheStyle.Cadre.ARRONDI) arrondi(c, r) { cc, rr -> cc.drawRect(rr, Paint().apply { color = 0xCC05080D.toInt() }) }
                else c.drawRect(r, Paint().apply { color = 0xCC05080D.toInt() })
                cadreApres(c, m, r, k, w, h)
            }
            if (m.numeros) {
                val etiquette = Planche.etiquette(m, i)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = r.height() * 0.11f; typeface = Typeface.DEFAULT_BOLD }
                val tw = p.measureText(etiquette)
                val boite = RectF(r.left + 4 * k, r.top + 4 * k, r.left + 4 * k + tw + 10 * k, r.top + 4 * k + p.textSize * 1.25f)
                c.drawRoundRect(boite, 4 * k, 4 * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0000000.toInt() })
                p.color = Color.WHITE
                c.drawText(etiquette, boite.left + 5 * k, boite.bottom - p.textSize * 0.3f, p)
            }
        }
        for (t in m.textes) {
            val r = RectF(t.zone.x * w, t.zone.y * h, (t.zone.x + t.zone.w) * w, (t.zone.y + t.zone.h) * h)
            if (t.fond != 0) c.drawRoundRect(r, 6 * k, 6 * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.fond })
            val dedans = RectF(r.left + r.width() * 0.04f, r.top, r.right - r.width() * 0.04f, r.bottom)
            // A text as SatMe always drew it, else in its font and effect.
            if (PlancheStyle.estSimple(t)) texteAjuste(c, v.de(t), dedans, t.couleur, gras = true)
            else texteStyle(c, v.de(t), dedans, t, w, h)
            // The SatMe logo beside the callsign: right of it, else left, else inside.
            if (t.champ == Planche.Champ.INDICATIF && logo != null) {
                val cote = r.height() * 1.1f
                val ecart = r.height() * 0.15f
                val haut = r.centerY() - cote / 2
                // Never over a picture box.
                fun libre(x: Float): Boolean {
                    if (x < 0 || x + cote > w) return false
                    val l = RectF(x, haut, x + cote, haut + cote)
                    return m.cases.none { z -> RectF.intersects(l, RectF(z.x * w, z.y * h, (z.x + z.w) * w, (z.y + z.h) * h)) }
                }
                val gauche = listOf(r.right + ecart, r.left - ecart - cote).firstOrNull { libre(it) } ?: (r.right - cote)
                logo.setBounds(gauche.toInt(), haut.toInt(), (gauche + cote).toInt(), (haut + cote).toInt())
                logo.draw(c)
            }
        }
        // The patches last, over everything, the last on top; never stretched.
        m.ecussons.forEachIndexed { i, e ->
            val img = ecussons[i] ?: return@forEachIndexed
            val zw = e.zone.w * w; val zh = e.zone.h * h
            val s = minOf(zw / img.width, zh / img.height)
            val dw = img.width * s; val dh = img.height * s
            val x = e.zone.x * w + (zw - dw) / 2; val y = e.zone.y * h + (zh - dh) / 2
            c.drawBitmap(img, null, RectF(x, y, x + dw, y + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return b
    }

    /** A picture filling [r], cut to its shape rather than stretched ([PlancheDecor.couvre]). */
    private fun dessineCouvert(c: Canvas, img: Bitmap, r: RectF) {
        val k = PlancheDecor.couvre(img.width, img.height, r.width(), r.height())
        c.drawBitmap(img, Rect(k.x, k.y, k.x + k.w, k.y + k.h), r, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** The picture filling its box, cut to its shape rather than stretched. */
    private fun dessineRempli(c: Canvas, img: Bitmap, r: RectF) {
        val ri = img.width.toFloat() / img.height; val rr = r.width() / r.height()
        val src = if (ri > rr) {
            val sw = (img.height * rr).toInt(); Rect((img.width - sw) / 2, 0, (img.width + sw) / 2, img.height)
        } else {
            val sh = (img.width / rr).toInt(); Rect(0, (img.height - sh) / 2, img.width, (img.height + sh) / 2)
        }
        c.drawBitmap(img, src, r, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** The text as large as fits its box, lines centred. */
    private fun texteAjuste(c: Canvas, texte: String, r: RectF, couleur: Int, gras: Boolean) {
        if (texte.isBlank() || r.width() <= 2 || r.height() <= 2) return
        val lignes = texte.split('\n')
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = couleur; textAlign = Paint.Align.CENTER
            typeface = if (gras) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        var taille = r.height() / lignes.size * 0.82f
        p.textSize = taille
        val plusLongue = lignes.maxOf { p.measureText(it) }
        if (plusLongue > r.width()) { taille *= r.width() / plusLongue; p.textSize = taille }
        val pas = taille * 1.15f
        val hauteur = pas * lignes.size
        var y = r.centerY() - hauteur / 2 + pas * 0.78f
        for (l in lignes) { c.drawText(l, r.centerX(), y, p); y += pas }
    }

    /** A drawn background ([PlancheDecor.Decor]): its gradient, its clouds, its stars, the Earth's arc (the SatMe night sky by default). */
    private fun fondGenerique(c: Canvas, w: Int, h: Int, d: PlancheDecor.Decor = PlancheDecor.ETOILES) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), d.couleurs, d.positions, Shader.TileMode.CLAMP)
        })
        for (n in d.nuages) {
            val rayon = n.r * w
            c.drawCircle(n.x * w, n.y * h, rayon, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(n.x * w, n.y * h, rayon,
                    intArrayOf(n.couleur, PlancheStyle.alpha(n.couleur, ((n.couleur ushr 24) and 0xFF) / 2), n.couleur and 0xFFFFFF),
                    floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            })
        }
        if (d.etoiles) {
            val r = Random(73)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(w * h / 4000) {
                p.color = Color.argb(r.nextInt(90, 255), 255, 255, 255)
                c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.85f, r.nextFloat() * w / 900f + 0.5f, p)
            }
        }
        d.horizon?.let { o ->
            val rayon = o.rayon * w; val cx = w / 2f; val cy = o.haut * h + rayon
            val halo = rayon * (1 + o.epaisseur)
            val (lc, lp) = o.lueur()
            c.drawCircle(cx, cy, halo, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(cx, cy, halo, lc, lp, Shader.TileMode.CLAMP) })
            val (dc, dp) = o.disque()
            c.drawCircle(cx, cy, rayon, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(cx, cy, rayon, dc, dp, Shader.TileMode.CLAMP) })
        }
    }

    // ------------------------------------------------------------ boxes

    /** A box's picture and the line under it, in [r]. */
    private fun contenuCase(c: Canvas, m: Planche.Modele, img: Pair<Bitmap, SstvMeta.SstvShot>, r: RectF, v: Valeurs, k: Float) {
        // The line under the picture takes its share of the box.
        val bande = if (m.legende) (r.height() * Planche.BANDE).coerceAtLeast(10f * k) else 0f
        val ri = RectF(r.left, r.top, r.right, r.bottom - bande)
        dessineRempli(c, img.first, ri)
        if (bande > 0f) {
            c.drawRect(RectF(r.left, ri.bottom, r.right, r.bottom), Paint().apply { color = Color.WHITE })
            texteAjuste(c, Planche.legende(img.second, v.indicatif, m.marqueTraitees),
                RectF(r.left + 3 * k, ri.bottom, r.right - 3 * k, r.bottom), Color.BLACK, gras = false)
        }
        // A processed picture says so when the sheet asks (legend or corner); else a clean sheet.
        if (m.marqueTraitees && bande == 0f && img.second.traitement.isNotEmpty()) etiquetteTraitee(c, img.second.traitement, ri, k)
    }

    /** The side of a box its frame is measured on. */
    private fun cote(r: RectF) = minOf(r.width(), r.height())

    /** What a box's frame lays under it: the white mat, the rounded box's shadow. */
    private fun cadreAvant(c: Canvas, m: Planche.Modele, r: RectF, k: Float, w: Int, h: Int) {
        when (m.cadre) {
            PlancheStyle.Cadre.FIN -> {
                val e = maxOf(3f * k, cote(r) * PlancheStyle.CADRE_FIN)
                val ombre = RectF(r.left - e, r.top - e + e * 0.6f, r.right + e, r.bottom + e + e * 0.6f)
                flou(c, ombre, e * 1.6f, 0x90000000.toInt(), w, h) { cc, p -> cc.drawRect(ombre, p) }
                c.drawRect(RectF(r.left - e, r.top - e, r.right + e, r.bottom + e), Paint().apply { color = Color.WHITE })
            }
            PlancheStyle.Cadre.ARRONDI -> {
                val cc0 = cote(r); val a = cc0 * PlancheStyle.CADRE_ARRONDI
                val ombre = RectF(r.left, r.top + cc0 * 0.035f, r.right, r.bottom + cc0 * 0.035f)
                flou(c, ombre, cc0 * 0.06f, 0xD0000000.toInt(), w, h) { cc, p -> cc.drawRoundRect(ombre, a, a, p) }
            }
            else -> {}
        }
    }

    /** A box's frame over it: the white line of old, the light edge of a rounded box, a neon tube. */
    private fun cadreApres(c: Canvas, m: Planche.Modele, r: RectF, k: Float, w: Int, h: Int) {
        val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        when (m.cadre) {
            PlancheStyle.Cadre.AUCUN -> c.drawRect(r, trait.apply { color = Color.WHITE; strokeWidth = 2.5f * k })
            PlancheStyle.Cadre.FIN -> {}
            PlancheStyle.Cadre.ARRONDI -> {
                val a = cote(r) * PlancheStyle.CADRE_ARRONDI
                c.drawRoundRect(r, a, a, trait.apply { color = Color.argb(110, 255, 255, 255); strokeWidth = 1.5f * k })
            }
            PlancheStyle.Cadre.NEON -> {
                val col = m.cadreCouleur
                val a = cote(r) * 0.015f
                val ep = maxOf(2f, 4.5f * k)
                flou(c, r, 9f * k + ep, col, w, h) { cc, p -> cc.drawRoundRect(r, a, a, p.apply { style = Paint.Style.STROKE; strokeWidth = ep * 1.6f }) }
                c.drawRoundRect(r, a, a, trait.apply { color = col; strokeWidth = ep })
                c.drawRoundRect(r, a, a, trait.apply { color = PlancheStyle.melange(col, Color.WHITE, 0.7f); strokeWidth = ep * 0.4f })
            }
        }
    }

    /** [dessin] drawn in [r] (a box) with its corners rounded, their edge smooth. */
    private fun arrondi(c: Canvas, r: RectF, dessin: (Canvas, RectF) -> Unit) {
        val x0 = r.left.toInt(); val y0 = r.top.toInt()
        val bw = r.right.toInt() - x0; val bh = r.bottom.toInt() - y0
        if (bw <= 2 || bh <= 2) return
        val tmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tmp)
        dessin(tc, RectF(0f, 0f, bw.toFloat(), bh.toFloat()))
        val a = minOf(bw, bh) * PlancheStyle.CADRE_ARRONDI
        // What lies outside the rounded box taken away, its edge smooth.
        val masque = Path().apply { fillType = Path.FillType.INVERSE_WINDING; addRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), a, a, Path.Direction.CW) }
        tc.drawPath(masque, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        c.drawBitmap(tmp, x0.toFloat(), y0.toFloat(), null)
        tmp.recycle()
    }

    /**
     * What [dessin] draws round [r], in [couleur], blurred by [rayon] pixels
     * ([PlancheStyle.flou]): a glow, a shadow — the PC's pixels. Kept within the sheet ([w]×[h]).
     */
    private fun flou(c: Canvas, r: RectF, rayon: Float, couleur: Int, w: Int, h: Int, dessin: (Canvas, Paint) -> Unit) {
        val m = (rayon * 2.2f + 4).toInt()
        val x0 = maxOf(0, (r.left - m).toInt()); val y0 = maxOf(0, (r.top - m).toInt())
        val x1 = minOf(w, (r.right + m).toInt() + 1); val y1 = minOf(h, (r.bottom + m).toInt() + 1)
        val bw = x1 - x0; val bh = y1 - y0
        if (bw <= 0 || bh <= 0) return
        val tmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tmp)
        tc.translate(-x0.toFloat(), -y0.toFloat())
        dessin(tc, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleur or 0xFF000000.toInt(); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND })
        val px = IntArray(bw * bh)
        tmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        PlancheStyle.flou(px, bw, bh, rayon.toInt(), couleur)
        tmp.setPixels(px, 0, bw, 0, 0, bw, bh)
        c.drawBitmap(tmp, x0.toFloat(), y0.toFloat(), null)
        tmp.recycle()
    }

    // ------------------------------------------------------------ styled texts

    // The fonts SatMe carries, copied once to the cache (a typeface reads a file).
    private val polices = HashMap<String, Typeface?>()

    /** The bundled font [id] ([PlanchePolices]); null for the default one or one unknown. */
    fun police(id: String): Typeface? {
        val p = PlanchePolices.de(id) ?: return null
        return synchronized(polices) {
            polices.getOrPut(id) {
                runCatching {
                    val o = PlanchePolices.octets(p) ?: return@runCatching null
                    val dossier = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "satme-polices").apply { mkdirs() }
                    val f = File(dossier, p.fichier)
                    if (!f.isFile || f.length() != o.size.toLong()) f.writeBytes(o)
                    Typeface.createFromFile(f)
                }.getOrNull()
            }
        }
    }

    private fun peinture(c: PlancheStyle.Couche, b: RectF, p: Paint) {
        p.shader = null
        if (c.uni) p.color = c.couleurs[0]
        else {
            p.color = Color.BLACK
            p.shader = LinearGradient(0f, b.top, 0f, b.top + maxOf(1f, b.height()), c.couleurs,
                c.positions ?: FloatArray(c.couleurs.size) { it / (c.couleurs.size - 1f) }, Shader.TileMode.CLAMP)
        }
    }

    /** The outline of [ligne] in [p], its baseline at [x], [y]; [espace] added after each letter; slanted when [pente]. */
    private fun contour(p: Paint, ligne: String, x: Float, y: Float, espace: Float, pente: Boolean): Path {
        val out = Path()
        if (espace == 0f) p.getTextPath(ligne, 0, ligne.length, x, y, out)
        else {
            var xx = x; var i = 0
            while (i < ligne.length) {
                val n = Character.charCount(ligne.codePointAt(i)); val s = ligne.substring(i, i + n); i += n
                if (s.isNotBlank()) { val q = Path(); p.getTextPath(s, 0, s.length, xx, y, q); out.addPath(q) }
                xx += p.measureText(s) + espace
            }
        }
        if (pente) out.transform(Matrix().apply { setSkew(-PlancheStyle.PENTE, 0f, 0f, y) })
        return out
    }

    /** A line's width in [p], its letter spacing [espace] added. */
    private fun largeur(p: Paint, l: String, espace: Float): Float {
        if (espace == 0f) return p.measureText(l)
        var s = 0f; var i = 0; var n = 0
        while (i < l.length) { val k = Character.charCount(l.codePointAt(i)); s += p.measureText(l, i, i + k); i += k; n++ }
        return s + espace * (n - 1).coerceAtLeast(0)
    }

    /**
     * A text in its font and effect ([PlancheStyle]), as large as fits [r]
     * with its effect round it, lines centred; its rules above and below — the PC's drawing.
     */
    private fun texteStyle(c: Canvas, texte: String, zone: RectF, t: Planche.Texte, w: Int, h: Int) {
        if (texte.isBlank() || zone.width() <= 2 || zone.height() <= 2) return
        var r = zone
        if (t.filets) {
            filets(c, zone, t.couleur, w, h)
            val hh = zone.height() * PlancheStyle.FILET_TEXTE
            r = RectF(zone.left, zone.top + (zone.height() - hh) / 2, zone.right, zone.top + (zone.height() + hh) / 2)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = police(t.police) ?: Typeface.DEFAULT_BOLD
            textSize = 100f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val txt = PlancheStyle.lisible(texte) { cp -> p.hasGlyph(String(Character.toChars(cp))) }
        if (txt.isBlank()) return
        val lignes = txt.split('\n')
        val marge = PlancheStyle.marge(t)
        // In sizes: the widest line, its effect each side, the slant of its top.
        val besoin = lignes.maxOf { largeur(p, it, t.espacement * 100f) } / 100f + 2 * marge + (if (t.italique) PlancheStyle.PENTE * 0.75f else 0f)
        var taille = r.height() / lignes.size * 0.82f / (1f + marge)
        if (besoin * taille > r.width()) taille = r.width() / besoin
        if (taille < 1f) return
        p.textSize = taille
        val pas = taille * 1.15f
        var y = r.centerY() - pas * lignes.size / 2 + pas * 0.78f
        val couches = PlancheStyle.couches(t)
        for (l in lignes) {
            val lw = largeur(p, l, t.espacement * taille)
            val x = r.centerX() - lw / 2 - (if (t.italique) PlancheStyle.PENTE * taille * 0.36f else 0f)
            val s = contour(p, l, x, y, t.espacement * taille, t.italique)
            val b = RectF().also { s.computeBounds(it, true) }
            val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
            for (k in couches) {
                val sk = if (k.dx != 0f || k.dy != 0f) Path(s).apply { offset(k.dx * taille, k.dy * taille) } else s
                val ep = maxOf(0.6f, k.trait * taille)
                if (k.flou > 0f) {
                    val bb = RectF().also { sk.computeBounds(it, true) }; val e = k.trait * taille
                    bb.inset(-e, -e)
                    flou(c, bb, k.flou * taille, k.couleurs[0], w, h) { cc, pp ->
                        if (k.remplit) cc.drawPath(sk, pp.apply { style = Paint.Style.FILL })
                        if (k.trait > 0f) cc.drawPath(sk, pp.apply { style = Paint.Style.STROKE; strokeWidth = ep })
                    }
                } else {
                    peinture(k, b, q)
                    if (k.remplit) c.drawPath(sk, q.apply { style = Paint.Style.FILL })
                    if (k.trait > 0f) c.drawPath(sk, q.apply { style = Paint.Style.STROKE; strokeWidth = ep })
                }
            }
            y += pas
        }
    }

    /** The glowing rules above and below a text ([PlancheStyle.FILET_PLACE]). */
    private fun filets(c: Canvas, z: RectF, col: Int, w: Int, h: Int) {
        val ep = maxOf(1.5f, z.height() * PlancheStyle.FILET_EPAISSEUR)
        val (cs, ps) = PlancheStyle.filet(col)
        for (yy in listOf(z.top + z.height() * PlancheStyle.FILET_PLACE, z.top + z.height() * (1 - PlancheStyle.FILET_PLACE) - ep)) {
            val barre = RectF(z.left + z.width() * 0.02f, yy, z.left + z.width() * 0.98f, yy + ep)
            val coeur = RectF(barre.left + barre.width() * 0.12f, barre.top, barre.right - barre.width() * 0.12f, barre.bottom)
            flou(c, barre, z.height() * PlancheStyle.FILET_FLOU, col, w, h) { cc, p -> cc.drawRect(coeur, p) }
            c.drawRect(barre, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(barre.left, 0f, barre.right, 0f, cs, ps, Shader.TileMode.CLAMP)
            })
        }
    }
}
