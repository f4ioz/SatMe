/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.util.Base64
import java.util.Locale

/**
 * A [RapportPdf.Toile] that writes SVG: the journal page's preview of a
 * report panel ([ElementsJournal]) drawn by the very code of the videos,
 * without the platform's canvas. Texts measured roughly (an average
 * letter): the platform's own drawing measures them exactly. Pictures go
 * inside (data URIs): nothing fetched.
 */
class ToileSvg(private val l: Float, private val h: Float) : RapportPdf.Toile, RapportPdf.Mesure {

    private val corps = StringBuilder()

    private fun n(v: Float) = String.format(Locale.US, "%.1f", v)
    private fun c(argb: Int?): String = if (argb == null) "none" else String.format(Locale.US, "#%06X", argb and 0xFFFFFF)
    private fun a(argb: Int?, nom: String): String {
        if (argb == null) return ""
        val al = (argb ushr 24) and 0xFF
        return if (al == 255) "" else " $nom-opacity=\"" + String.format(Locale.US, "%.2f", al / 255f) + "\""
    }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun trait(couleur: Int?, ep: Float, tirets: FloatArray?, rond: Boolean): String =
        if (couleur == null) " stroke=\"none\"" else " stroke=\"${c(couleur)}\"${a(couleur, "stroke")} stroke-width=\"${n(ep)}\"" +
            (tirets?.let { t -> " stroke-dasharray=\"" + t.joinToString(" ") { n(it) } + "\"" } ?: "") +
            (if (rond) " stroke-linecap=\"round\" stroke-linejoin=\"round\"" else "")

    override fun largeur(s: String, taille: Float, gras: Boolean): Float = s.length * taille * (if (gras) 0.6f else 0.55f)

    override fun taille(image: ByteArray): Pair<Int, Int>? = PlancheFichier.dimensions(image)

    override fun texte(s: String, x: Float, y: Float, taille: Float, couleur: Int, gras: Boolean) {
        if (s.isBlank()) return
        corps.append("<text x=\"${n(x)}\" y=\"${n(y)}\" font-size=\"${n(taille)}\" fill=\"${c(couleur)}\"${a(couleur, "fill")}")
            .append(if (gras) " font-weight=\"700\"" else "").append(">").append(esc(s)).append("</text>")
    }

    override fun ligne(xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, ferme: Boolean, fond: Int?) {
        if (xy.size < 4) return
        val pts = (0 until xy.size / 2).joinToString(" ") { n(xy[2 * it]) + "," + n(xy[2 * it + 1]) }
        corps.append(if (ferme) "<polygon" else "<polyline").append(" points=\"").append(pts).append("\" fill=\"${c(fond)}\"${a(fond, "fill")}")
            .append(trait(couleur, epaisseur, pointilles, rond)).append("/>")
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, fond: Int?, trait: Int?, epaisseur: Float, rayon: Float, pointilles: FloatArray?) {
        if (w <= 0f || h <= 0f) return
        corps.append("<rect x=\"${n(x)}\" y=\"${n(y)}\" width=\"${n(w)}\" height=\"${n(h)}\"")
            .append(if (rayon > 0f) " rx=\"${n(minOf(rayon, minOf(w, h) / 2))}\"" else "")
            .append(" fill=\"${c(fond)}\"${a(fond, "fill")}").append(trait(trait, epaisseur, pointilles, false)).append("/>")
    }

    override fun cercle(x: Float, y: Float, r: Float, fond: Int?, trait: Int?, epaisseur: Float) {
        if (r <= 0f) return
        corps.append("<circle cx=\"${n(x)}\" cy=\"${n(y)}\" r=\"${n(r)}\" fill=\"${c(fond)}\"${a(fond, "fill")}")
            .append(trait(trait, epaisseur, null, false)).append("/>")
    }

    override fun image(octets: ByteArray, x: Float, y: Float, w: Float, h: Float) {
        val type = if (PlancheFichier.genre(octets) == "png") "image/png" else "image/jpeg"
        corps.append("<image x=\"${n(x)}\" y=\"${n(y)}\" width=\"${n(w)}\" height=\"${n(h)}\" preserveAspectRatio=\"none\" href=\"data:")
            .append(type).append(";base64,").append(Base64.getEncoder().encodeToString(octets)).append("\"/>")
    }

    override fun chemin(ops: CharArray, xy: FloatArray, couleur: Int?, epaisseur: Float, pointilles: FloatArray?, rond: Boolean, fond: Int?) {
        if (ops.isEmpty()) return
        val d = StringBuilder(); var i = 0
        for (o in ops) when (o) {
            'M', 'L' -> { d.append(o).append(n(xy[i])).append(' ').append(n(xy[i + 1])).append(' '); i += 2 }
            'C' -> { d.append('C'); repeat(3) { k -> d.append(n(xy[i + 2 * k])).append(' ').append(n(xy[i + 2 * k + 1])).append(' ') }; i += 6 }
            'Z' -> d.append("Z ")
        }
        corps.append("<path d=\"").append(d.toString().trim()).append("\" fill=\"${c(fond)}\"${a(fond, "fill")}")
            .append(trait(couleur, epaisseur, pointilles, rond)).append("/>")
    }

    /** The drawing as a whole SVG document, its own size ([l] × [h]). */
    fun svg(titre: String = ""): String = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${n(l)} ${n(h)}\" width=\"${n(l)}\" height=\"${n(h)}\"" +
        " font-family=\"system-ui,-apple-system,Segoe UI,Roboto,sans-serif\" role=\"img\"" + (if (titre.isNotBlank()) " aria-label=\"${esc(titre)}\"" else "") + ">" +
        corps + "</svg>"
}
