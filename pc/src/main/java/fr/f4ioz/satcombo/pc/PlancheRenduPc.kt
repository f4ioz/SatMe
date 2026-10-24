/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.PlancheDecor
import fr.f4ioz.satcombo.sstv.SstvMeta
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Draws an SSTV sheet ([Planche.Modele]) on the PC: the phone's drawing
 * (PlancheRendu), stroke for stroke, with Java2D — the same sheet whichever
 * draws it.
 */
object PlancheRenduPc {

    /** What the texts say. */
    data class Valeurs(val indicatif: String = "", val nom: String = "", val locator: String = "", val dates: String = "", val titre: String = "") {
        fun de(t: Planche.Texte): String = when (t.champ) {
            Planche.Champ.INDICATIF -> indicatif
            Planche.Champ.NOM_LOCATOR -> listOf(nom, if (nom.isNotBlank() && locator.isNotBlank()) "@ $locator" else locator)
                .filter { it.isNotBlank() }.joinToString("\n")
            Planche.Champ.DATES -> dates
            Planche.Champ.TITRE -> titre
            Planche.Champ.LIBRE -> t.libre
        }
    }

    /** A picture as JPEG, at quality [q]. */
    fun jpeg(b: BufferedImage, q: Float = 0.88f): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        val w = ImageIO.getImageWritersByFormatName("jpg").next()
        ImageIO.createImageOutputStream(o).use { s ->
            w.output = s
            val p = w.defaultWriteParam.apply { compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT; compressionQuality = q }
            w.write(null, javax.imageio.IIOImage(b, null, null), p)
        }
        w.dispose()
        return o.toByteArray()
    }

    /** JPEG wants no transparency: a picture with alpha is laid on black first. */
    fun rgb(b: BufferedImage): BufferedImage {
        if (b.type == BufferedImage.TYPE_INT_RGB || b.type == BufferedImage.TYPE_3BYTE_BGR) return b
        val r = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_RGB)
        r.createGraphics().apply { drawImage(b, 0, 0, null); dispose() }
        return r
    }

    /** The panel over a template's own boxes when the sheet is fitted to its pictures (the phone's). */
    const val PANNEAU = 0xF205080D.toInt()

    // Signatures by file: the pictures do not change once written.
    private val signatures = HashMap<String, Planche.Signature>()

    /** [Planche.Signature] of each picture, from a small copy, kept. */
    fun signatures(fichiers: List<File>): Map<String, Planche.Signature> = fichiers.mapNotNull { f ->
        val cle = f.name + "@" + f.lastModified()
        val s = synchronized(signatures) { signatures[cle] } ?: charge(f, 96)?.let { b ->
            Planche.signature(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height)
                .also { synchronized(signatures) { signatures[cle] = it } }
        }
        s?.let { f.name to it }
    }.toMap()

    /** An image file, made no wider than [largeurMax]. */
    fun charge(f: File, largeurMax: Int): BufferedImage? = runCatching {
        val b = ImageIO.read(f) ?: return null
        if (b.width <= largeurMax) b else reduit(b, largeurMax)
    }.getOrNull()

    fun reduit(b: BufferedImage, largeur: Int): BufferedImage {
        val h = (largeur.toLong() * b.height / b.width).toInt().coerceAtLeast(1)
        val r = BufferedImage(largeur, h, BufferedImage.TYPE_INT_RGB)
        r.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(b, 0, 0, largeur, h, null); dispose()
        }
        return r
    }

    /** As [reduit], transparency kept (a patch). */
    private fun reduitArgb(b: BufferedImage, largeur: Int): BufferedImage {
        val h = (largeur.toLong() * b.height / b.width).toInt().coerceAtLeast(1)
        val r = BufferedImage(largeur, h, BufferedImage.TYPE_INT_ARGB)
        r.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(b, 0, 0, largeur, h, null); dispose()
        }
        return r
    }

    // The built-in pictures, decoded once at the size last asked.
    @Volatile private var decorCache: Pair<String, BufferedImage>? = null

    /**
     * The background picture of [m] no wider than [largeurMax]: a built-in
     * one or the file chosen; null when it is drawn (or the template has its own).
     */
    fun chargeDecor(r: Planche.Rangement, m: Planche.Modele, largeurMax: Int): BufferedImage? {
        if (m.fond.isNotBlank()) return null
        val d = PlancheDecor.integre(m.decor)
        if (d != null) {
            if (d.ressource == null) return null
            val cle = d.id + "@" + largeurMax
            decorCache?.let { (k, b) -> if (k == cle) return b }
            return runCatching { PlancheDecor.ouvre(d)?.use { ImageIO.read(it) } }.getOrNull()
                ?.let { if (it.width > largeurMax) reduit(it, largeurMax) else it }?.also { decorCache = cle to it }
        }
        return r.decor(m)?.let { charge(it, largeurMax) }
    }

    /** A built-in background, small ([largeur] wide, the sheet's shape): to choose it. */
    fun apercuDecor(d: PlancheDecor.Decor, largeur: Int): BufferedImage {
        val h = (largeur / 1.414f).toInt().coerceAtLeast(1)
        val b = BufferedImage(largeur, h, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val img = if (d.ressource != null) runCatching { PlancheDecor.ouvre(d)?.use { ImageIO.read(it) } }.getOrNull() else null
        if (img != null) {
            val c = PlancheDecor.couvre(img.width, img.height, largeur.toFloat(), h.toFloat())
            g.drawImage(img, 0, 0, largeur, h, c.x, c.y, c.x + c.w, c.y + c.h, null)
        } else fondGenerique(g, largeur, h, d)
        g.dispose()
        return b
    }

    // Patches ready to draw (surround taken away when asked), by file, choice and size.
    private val ecussons = object : LinkedHashMap<String, BufferedImage>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, BufferedImage>?) = size > 12
    }

    /** A patch file no wider than [largeurMax], transparency kept; its surround taken away when [sansContour]. */
    fun ecusson(f: File, sansContour: Boolean, largeurMax: Int): BufferedImage? {
        val cle = f.name + "@" + f.lastModified() + "@" + sansContour + "@" + largeurMax
        synchronized(ecussons) { ecussons[cle] }?.let { return it }
        val b = runCatching { ImageIO.read(f) }.getOrNull() ?: return null
        var a = if (b.width > largeurMax) reduitArgb(b, largeurMax) else if (b.type == BufferedImage.TYPE_INT_ARGB) b
            else reduitArgb(b, b.width)
        if (sansContour) {
            val px = PlancheDecor.detoure(a.getRGB(0, 0, a.width, a.height, null, 0, a.width), a.width, a.height)
            a = BufferedImage(a.width, a.height, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, width, height, px, 0, width) }
        }
        synchronized(ecussons) { ecussons[cle] = a }
        return a
    }

    /** A patch file, its surround taken away, full size, as PNG: what a template file carries ([fr.f4ioz.satcombo.sstv.PlancheFichier]). */
    fun ecussonDetoure(f: File): ByteArray? = ecusson(f, true, 4000)?.let { b ->
        java.io.ByteArrayOutputStream().also { ImageIO.write(b, "png", it) }.toByteArray()
    }

    /** The patches of [m] by their place in its list, each no wider than [largeurMax]. */
    fun chargeEcussons(r: Planche.Rangement, m: Planche.Modele, largeurMax: Int): Map<Int, BufferedImage> =
        m.ecussons.mapIndexedNotNull { i, e -> r.fichier(e.fichier)?.let { ecusson(it, e.sansContour, largeurMax) }?.let { i to it } }.toMap()

    private fun couleur(argb: Int) = Color(argb, true)

    /**
     * The sheet [largeur] pixels wide; [images] the pictures by box, with what is known of them;
     * [decor] its background picture ([chargeDecor]), [ecussons] its patches ([chargeEcussons]).
     */
    /** « corrigée » / « fusion de n copies » in a box's lower right corner. */
    private fun etiquetteTraitee(g: java.awt.Graphics2D, texte: String, r: Rectangle2D.Float, k: Float) {
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont((r.height * 0.075f).coerceAtLeast(9f * k))
        val tw = g.fontMetrics.stringWidth(texte); val taille = g.font.size2D
        val boite = RoundRectangle2D.Float(r.x + r.width - tw - 14 * k, r.y + r.height - taille * 1.35f - 4 * k, tw + 10 * k, taille * 1.3f, 8 * k, 8 * k)
        g.color = couleur(0xB0000000.toInt()); g.fill(boite)
        g.color = Color(0xFF, 0xD5, 0x4F); g.drawString(texte, boite.x + 5 * k, boite.y + boite.height - taille * 0.32f)
    }

    fun dessine(m: Planche.Modele, fond: BufferedImage?, largeur: Int, images: Map<Int, Pair<BufferedImage, SstvMeta.SstvShot>>,
                v: Valeurs, logo: BufferedImage? = MarquePc.icone, decor: BufferedImage? = null,
                ecussons: Map<Int, BufferedImage> = emptyMap()): BufferedImage {
        val ratio = if (fond != null) fond.width.toFloat() / fond.height else m.ratio
        val w = largeur; val h = (largeur / ratio).toInt().coerceAtLeast(1)
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        if (fond != null) g.drawImage(fond, 0, 0, w, h, null)
        else if (decor != null) {
            // Fitted to the sheet: filled, centred, cut rather than stretched.
            val c = PlancheDecor.couvre(decor.width, decor.height, w.toFloat(), h.toFloat())
            g.drawImage(decor, 0, 0, w, h, c.x, c.y, c.x + c.w, c.y + c.h, null)
        } else fondGenerique(g, w, h, PlancheDecor.integre(m.decor)?.takeIf { it.ressource == null } ?: PlancheDecor.ETOILES)
        val k = w / 1000f
        // Fitted to fewer pictures: the template's printed boxes ("NO IMAGE") hidden under one dark panel.
        if (fond != null && m.ajustee) Planche.englobe(m.origine)?.let { z ->
            g.color = couleur(PANNEAU)
            g.fill(RoundRectangle2D.Float(z.x * w - 6 * k, z.y * h - 6 * k, z.w * w + 12 * k, z.h * h + 12 * k, 20 * k, 20 * k))
        }
        val n = m.cases.size
        m.cases.forEachIndexed { i, z ->
            val r = Rectangle2D.Float(z.x * w, z.y * h, z.w * w, z.h * h)
            val img = images[i]
            if (img != null) {
                // The line under the picture takes its share of the box.
                val bande = if (m.legende) (r.height * Planche.BANDE).coerceAtLeast(10f * k) else 0f
                val ri = Rectangle2D.Float(r.x, r.y, r.width, r.height - bande)
                dessineRempli(g, img.first, ri)
                if (bande > 0f) {
                    g.color = Color.WHITE; g.fill(Rectangle2D.Float(r.x, ri.y + ri.height, r.width, bande))
                    texteAjuste(g, Planche.legende(img.second, v.indicatif, m.marqueTraitees), Rectangle2D.Float(r.x + 3 * k, ri.y + ri.height, r.width - 6 * k, bande), Color.BLACK, false)
                }
                g.color = Color.WHITE; g.stroke = BasicStroke(2.5f * k); g.draw(r)
                // A processed picture says so when the sheet asks (legend or corner); else a clean sheet.
                if (m.marqueTraitees && bande == 0f && img.second.traitement.isNotEmpty()) etiquetteTraitee(g, img.second.traitement, ri, k)
            } else if (fond == null) {
                // A generic sheet shows its empty boxes; an imported one has its own.
                g.color = couleur(0xCC05080D.toInt()); g.fill(r)
                g.color = Color.WHITE; g.stroke = BasicStroke(2.5f * k); g.draw(r)
            }
            if (m.numeros) {
                val etiquette = Planche.etiquette(m, i)
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(r.height * 0.11f)
                val tw = g.fontMetrics.stringWidth(etiquette)
                val taille = g.font.size2D
                val boite = RoundRectangle2D.Float(r.x + 4 * k, r.y + 4 * k, tw + 10 * k, taille * 1.25f, 8 * k, 8 * k)
                g.color = couleur(0xB0000000.toInt()); g.fill(boite)
                g.color = Color.WHITE; g.drawString(etiquette, boite.x + 5 * k, boite.y + boite.height - taille * 0.3f)
            }
        }
        for (t in m.textes) {
            val r = Rectangle2D.Float(t.zone.x * w, t.zone.y * h, t.zone.w * w, t.zone.h * h)
            if (t.fond != 0) { g.color = couleur(t.fond); g.fill(RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 12 * k, 12 * k)) }
            texteAjuste(g, v.de(t), Rectangle2D.Float(r.x + r.width * 0.04f, r.y, r.width * 0.92f, r.height), couleur(t.couleur), true)
            // The SatMe logo beside the callsign: right of it, else left, else inside — never over a picture.
            if (t.champ == Planche.Champ.INDICATIF && logo != null) {
                val cote = r.height * 1.1f; val ecart = r.height * 0.15f
                val haut = (r.y + r.height / 2) - cote / 2
                fun libre(x: Float): Boolean {
                    if (x < 0 || x + cote > w) return false
                    val l = Rectangle2D.Float(x, haut, cote, cote)
                    return m.cases.none { z -> l.intersects(Rectangle2D.Float(z.x * w, z.y * h, z.w * w, z.h * h)) }
                }
                val gauche = listOf(r.x + r.width + ecart, r.x - ecart - cote).firstOrNull { libre(it) } ?: (r.x + r.width - cote)
                g.drawImage(logo, gauche.toInt(), haut.toInt(), cote.toInt(), cote.toInt(), null)
            }
        }
        // The patches last, over everything, the last on top; never stretched.
        m.ecussons.forEachIndexed { i, e ->
            val img = ecussons[i] ?: return@forEachIndexed
            val zw = e.zone.w * w; val zh = e.zone.h * h
            val s = minOf(zw / img.width, zh / img.height)
            val dw = img.width * s; val dh = img.height * s
            val x = e.zone.x * w + (zw - dw) / 2; val y = e.zone.y * h + (zh - dh) / 2
            g.drawImage(img, x.toInt(), y.toInt(), (x + dw).toInt(), (y + dh).toInt(), 0, 0, img.width, img.height, null)
        }
        g.dispose()
        return b
    }

    /** The picture filling its box, cut to its shape rather than stretched. */
    private fun dessineRempli(g: Graphics2D, img: BufferedImage, r: Rectangle2D.Float) {
        val ri = img.width.toFloat() / img.height; val rr = r.width / r.height
        val (sx, sy, sw, sh) = if (ri > rr) {
            val sw = (img.height * rr).toInt(); listOf((img.width - sw) / 2, 0, sw, img.height)
        } else {
            val sh = (img.width / rr).toInt(); listOf(0, (img.height - sh) / 2, img.width, sh)
        }
        g.drawImage(img, r.x.toInt(), r.y.toInt(), (r.x + r.width).toInt(), (r.y + r.height).toInt(), sx, sy, sx + sw, sy + sh, null)
    }

    /** The text as large as fits its box, lines centred. */
    private fun texteAjuste(g: Graphics2D, texte: String, r: Rectangle2D.Float, c: Color, gras: Boolean) {
        if (texte.isBlank() || r.width <= 2 || r.height <= 2) return
        val lignes = texte.split('\n')
        var taille = r.height / lignes.size * 0.82f
        g.font = Font(Font.SANS_SERIF, if (gras) Font.BOLD else Font.PLAIN, 1).deriveFont(taille)
        val plusLongue = lignes.maxOf { g.fontMetrics.stringWidth(it) }
        if (plusLongue > r.width) { taille *= r.width / plusLongue; g.font = g.font.deriveFont(taille) }
        val pas = taille * 1.15f
        var y = (r.y + r.height / 2) - pas * lignes.size / 2 + pas * 0.78f
        g.color = c
        for (l in lignes) { g.drawString(l, (r.x + r.width / 2) - g.fontMetrics.stringWidth(l) / 2f, y); y += pas }
    }

    /** A drawn background ([PlancheDecor.Decor]): its gradient, its stars — the phone's, stroke for stroke. */
    private fun fondGenerique(g: Graphics2D, w: Int, h: Int, d: PlancheDecor.Decor = PlancheDecor.ETOILES) {
        g.paint = LinearGradientPaint(0f, 0f, 0f, h.toFloat(), d.positions, d.couleurs.map { Color(it, true) }.toTypedArray())
        g.fillRect(0, 0, w, h)
        if (!d.etoiles) return
        val r = Random(73)
        repeat(w * h / 4000) {
            g.color = Color(255, 255, 255, r.nextInt(90, 255))
            val rayon = r.nextFloat() * w / 900f + 0.5f
            g.fill(Ellipse2D.Float(r.nextFloat() * w - rayon, r.nextFloat() * h * 0.85f - rayon, 2 * rayon, 2 * rayon))
        }
    }
}
