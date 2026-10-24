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
import android.graphics.Paint
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
            if (img != null) {
                // The line under the picture takes its share of the box.
                val bande = if (m.legende) (r.height() * Planche.BANDE).coerceAtLeast(10f * k) else 0f
                val ri = RectF(r.left, r.top, r.right, r.bottom - bande)
                dessineRempli(c, img.first, ri)
                if (bande > 0f) {
                    c.drawRect(RectF(r.left, ri.bottom, r.right, r.bottom), Paint().apply { color = Color.WHITE })
                    texteAjuste(c, Planche.legende(img.second, v.indicatif, m.marqueTraitees),
                        RectF(r.left + 3 * k, ri.bottom, r.right - 3 * k, r.bottom), Color.BLACK, gras = false)
                }
                c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * k })
                // A processed picture says so when the sheet asks (legend or corner); else a clean sheet.
                if (m.marqueTraitees && bande == 0f && img.second.traitement.isNotEmpty()) etiquetteTraitee(c, img.second.traitement, ri, k)
            } else if (fond == null) {
                // A generic sheet shows its empty boxes; an imported one has its own.
                c.drawRect(r, Paint().apply { color = 0xCC05080D.toInt() })
                c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * k })
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
            texteAjuste(c, v.de(t), RectF(r.left + r.width() * 0.04f, r.top, r.right - r.width() * 0.04f, r.bottom),
                t.couleur, gras = true)
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

    /** A drawn background ([PlancheDecor.Decor]): its gradient, its stars (the SatMe night sky by default). */
    private fun fondGenerique(c: Canvas, w: Int, h: Int, d: PlancheDecor.Decor = PlancheDecor.ETOILES) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), d.couleurs, d.positions, Shader.TileMode.CLAMP)
        })
        if (!d.etoiles) return
        val r = Random(73)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(w * h / 4000) {
            p.color = Color.argb(r.nextInt(90, 255), 255, 255, 255)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.85f, r.nextFloat() * w / 900f + 0.5f, p)
        }
    }
}
