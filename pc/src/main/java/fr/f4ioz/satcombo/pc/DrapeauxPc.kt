/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONObject
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * The countries' flags for what the server draws (videos, pictures), the very
 * ones of the page: its « Drapeaux » font (Twemoji Country Flags, CC BY 4.0)
 * can't be drawn by Java2D, so each flag was drawn from it once, 48 px high,
 * into an atlas (`drapeaux/drapeaux.png`, its index `drapeaux.json`; made by
 * `tools/drapeaux/genere-atlas.mjs`). A flag is named by its ISO 3166 code
 * (« FR », « fr ») or the UK's nations' (« ENG », « SCT », « WLS » as
 * [fr.f4ioz.satcombo.domain.Dxcc.Entite.iso] gives them, or « gb-sct »).
 */
object DrapeauxPc {

    /** The flag's height, its top above the baseline and its advance in a line of text, shares of the font's size (the page's numbers). */
    class Em(val h: Float, val haut: Float, val avance: Float)

    private class Atlas(val image: BufferedImage, val cases: Map<String, IntArray>, val em: Em)

    private val atlas: Atlas? by lazy {
        runCatching {
            val img = DrapeauxPc::class.java.getResourceAsStream("drapeaux/drapeaux.png")!!.use { ImageIO.read(it) }
            val j = JSONObject(DrapeauxPc::class.java.getResourceAsStream("drapeaux/drapeaux.json")!!.bufferedReader().use { it.readText() })
            val e = j.getJSONObject("em")
            val d = j.getJSONObject("d")
            val argb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() } }
            Atlas(argb, d.keySet().associateWith { k -> d.getJSONArray(k).let { a -> IntArray(4) { a.getInt(it) } } },
                Em(e.getDouble("h").toFloat(), e.getDouble("haut").toFloat(), e.getDouble("avance").toFloat()))
        }.getOrNull()
    }

    /** The page's numbers for a flag set in text (defaults when the atlas is missing). */
    val em: Em get() = atlas?.em ?: Em(0.73f, 0.74f, 1f)

    /** How many flags the atlas holds (0: none, missing). */
    val nombre: Int get() = atlas?.cases?.size ?: 0

    /** The atlas' key of [code]: « fr », « gb-eng »; ENG/SCT/WLS are the UK's nations. */
    fun cle(code: String): String {
        val c = code.trim().lowercase()
        return if (c in setOf("eng", "sct", "wls")) "gb-$c" else c
    }

    /** Whether there is a flag for [code]. */
    fun connu(code: String?): Boolean = code != null && atlas?.cases?.containsKey(cle(code)) == true

    /** The flag of [code] as drawn in the atlas (48 px high), null when unknown. */
    fun image(code: String?): BufferedImage? {
        val a = atlas ?: return null
        val r = code?.let { a.cases[cle(it)] } ?: return null
        return a.image.getSubimage(r[0], r[1], r[2], r[3])
    }

    /** The width (px) of the flag of [code] drawn [h] px high; 0 when unknown. */
    fun largeur(code: String?, h: Float): Float {
        val r = code?.let { atlas?.cases?.get(cle(it)) } ?: return 0f
        return h * r[2] / r[3]
    }

    // Flags shrunk to the heights asked, area-averaged once (a plain drawImage of a 48 px flag at 12 px shimmers).
    private val reduits = ConcurrentHashMap<String, BufferedImage>()

    private fun reduit(code: String, hPx: Int): BufferedImage? {
        val src = image(code) ?: return null
        if (hPx >= src.height) return src
        return reduits.getOrPut(cle(code) + "@" + hPx) {
            val w = maxOf(1, Math.round(src.width.toDouble() * hPx / src.height).toInt())
            val s = src.getScaledInstance(w, hPx, Image.SCALE_AREA_AVERAGING)
            BufferedImage(w, hPx, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(s, 0, 0, null); dispose() } }
        }
    }

    /**
     * Draws the flag of [code], its top left corner at [x], [y], [h] px high,
     * as visible as [g]'s composite says. Its width, or 0 (nothing drawn) when
     * there is no flag for [code]: the caller then draws its code its own way.
     */
    fun dessine(g: Graphics2D, code: String?, x: Float, y: Float, h: Float): Float {
        if (code == null || h <= 0f || !connu(code)) return 0f
        val w = largeur(code, h)
        // Drawn from the copy shrunk to the next whole pixel above, then scaled the last bit smoothly.
        val img = reduit(code, Math.ceil(h.toDouble()).toInt().coerceAtLeast(1)) ?: return 0f
        val avant = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val t = java.awt.geom.AffineTransform(w / img.width.toDouble(), 0.0, 0.0, h / img.height.toDouble(), x.toDouble(), y.toDouble())
        g.drawImage(img, t, null)
        if (avant != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, avant)
        return w
    }

    /** Draws the flag of [code] in a line of text in a font [taille] px, its baseline at [base], as the page sets it; its width or 0. */
    fun dessineDansTexte(g: Graphics2D, code: String?, x: Float, base: Float, taille: Float): Float =
        dessine(g, code, x, base - em.haut * taille, em.h * taille)
}
