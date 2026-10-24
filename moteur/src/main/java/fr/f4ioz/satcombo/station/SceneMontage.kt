/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import java.util.Base64
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A drawing as plain shapes, in absolute coordinates: rectangles, circles,
 * paths (lines and Bézier curves), texts and pictures, each with its fill
 * and stroke. Written as SVG ([svg]) for a page, drawn shape by shape on a
 * PDF page by whoever reads [formes] — the same drawing either way.
 *
 * Built by a [Pinceau] that takes the colours, the paths and the
 * transformations the way the « Montage » page's script writes them, so
 * its drawings carry over almost word for word.
 */
object SceneMontage {

    /** A fill: one colour (ARGB, alpha honoured) or one of the page's gradients ([Degrade]), top to bottom. */
    sealed interface Peinture
    data class Uni(val argb: Int) : Peinture
    data class Degrade(val id: String, val haut: Int, val bas: Int, val radial: Boolean = false) : Peinture {
        /** The colour halfway: what a page with no gradient shows. */
        val moyenne: Int get() = melange(haut, bas, 0.5)
    }

    /** A path's steps, absolute: move, line, cubic curve, close. */
    sealed interface Op
    data class M(val x: Double, val y: Double) : Op
    data class L(val x: Double, val y: Double) : Op
    data class Cu(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : Op
    object Z : Op

    sealed interface Forme
    data class Rect(val x: Double, val y: Double, val l: Double, val h: Double, val r: Double, val fond: Peinture?, val trait: Int?,
                    val ep: Double, val tirets: DoubleArray? = null) : Forme
    data class Cercle(val x: Double, val y: Double, val r: Double, val fond: Peinture?, val trait: Int?, val ep: Double) : Forme
    data class Chemin(val ops: List<Op>, val fond: Peinture?, val trait: Int?, val ep: Double, val tirets: DoubleArray? = null,
                      val rond: Boolean = false) : Forme
    /**
     * [ancre] 0 start, 1 middle, 2 end; [mono] a fixed-width face (the
     * cables' labels); [max] the room it has (0: any), for a face wider than
     * the page's (a PDF's) to cut it there.
     */
    data class Texte(val s: String, val x: Double, val y: Double, val taille: Double, val couleur: Int, val gras: Boolean = false,
                     val ancre: Int = 0, val mono: Boolean = false, val espacement: Double = 0.0, val max: Double = 0.0) : Forme
    /** A picture (PNG or JPEG) in its box, its proportions kept already; [lueur] a glow of that colour around it (the page's drop shadow). */
    data class Image(val octets: ByteArray, val x: Double, val y: Double, val l: Double, val h: Double, val lueur: Int? = null) : Forme

    class Scene(val l: Double, val h: Double, val fond: Int, val formes: List<Forme>)

    // ------------------------------------------------------------------ colours

    /** [a] towards [b] by [k] (0..1), alpha included. */
    fun melange(a: Int, b: Int, k: Double): Int {
        fun c(sh: Int) = (((a ushr sh) and 0xFF) * (1 - k) + ((b ushr sh) and 0xFF) * k).toInt().coerceIn(0, 255)
        return (c(24) shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    /** [c] with its alpha multiplied by [k]. */
    fun alpha(c: Int, k: Double): Int = if (k >= 1.0) c else ((((c ushr 24) and 0xFF) * k).toInt().coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)

    /** The page's gradients (`url(#P-…)`). */
    val DEGRADES: Map<String, Degrade> = listOf(
        Degrade("metal", 0xFFDCE3EA.toInt(), 0xFF8D99A5.toInt()), Degrade("alu", 0xFFC3CCD5.toInt(), 0xFF8C97A2.toInt()),
        Degrade("alu-sombre", 0xFF4A535D.toInt(), 0xFF2B323A.toInt()), Degrade("noir", 0xFF2E353D.toInt(), 0xFF191E23.toInt()),
        Degrade("ecran", 0xFF0F3A44.toInt(), 0xFF0A1E25.toInt()), Degrade("ambre", 0xFF3A2A0E.toInt(), 0xFF1F1607.toInt()),
        Degrade("bleu", 0xFF123355.toInt(), 0xFF0A1C30.toInt()), Degrade("vert", 0xFF1E3A26.toInt(), 0xFF0F2015.toInt()),
        Degrade("bleu-boite", 0xFF3B82CF.toInt(), 0xFF235290.toInt()), Degrade("pcb", 0xFF1F6B45.toInt(), 0xFF14492F.toInt()),
        Degrade("verre", 0x59E5484D, 0x1FE5484D), Degrade("bouton", 0xFF6A7581.toInt(), 0xFF262D34.toInt(), radial = true),
    ).associateBy { it.id }

    /** A colour as CSS writes it (« #3FE0C8 », « rgba(63,224,200,.45) »), null for none. */
    fun couleur(s: String?): Int? {
        val t = s?.trim() ?: return null
        if (t.isEmpty() || t == "none" || t == "transparent") return null
        if (t.startsWith("#")) {
            val h = t.substring(1)
            val v = when (h.length) {
                3 -> h.map { "$it$it" }.joinToString("")
                6 -> h
                else -> return null
            }.toLongOrNull(16) ?: return null
            return (0xFF000000L or v).toInt()
        }
        if (t.startsWith("rgb")) {
            val n = t.substringAfter('(').substringBefore(')').split(',').map { it.trim().toDouble() }
            val a = if (n.size > 3) n[3] else 1.0
            return (Math.round(a * 255).toInt().coerceIn(0, 255) shl 24) or (n[0].toInt() shl 16) or (n[1].toInt() shl 8) or n[2].toInt()
        }
        return null
    }

    fun peinture(s: String?): Peinture? {
        if (s == null) return null
        if (s.startsWith("url(#")) return DEGRADES[s.removePrefix("url(#").removeSuffix(")").substringAfter('-')]
        return couleur(s)?.let { Uni(it) }
    }

    // ------------------------------------------------------------------ the brush

    /**
     * Draws into a list of shapes, through a transformation (translate,
     * scale, rotate) and an opacity, as the page's SVG groups do.
     */
    class Pinceau {
        val formes = ArrayList<Forme>()
        // x' = a x + c y + e ; y' = b x + d y + f
        private var m = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        private var op = 1.0
        private val pile = ArrayList<Pair<DoubleArray, Double>>()

        fun sauve() { pile += m.copyOf() to op }
        fun restaure() { val (a, b) = pile.removeAt(pile.size - 1); m = a; op = b }
        /** [f] drawn inside a group of its own (its transformation and opacity undone after). */
        inline fun groupe(f: Pinceau.() -> Unit) { sauve(); try { f() } finally { restaure() } }

        private fun multiplie(n: DoubleArray) {
            val a = m
            m = doubleArrayOf(a[0] * n[0] + a[2] * n[1], a[1] * n[0] + a[3] * n[1], a[0] * n[2] + a[2] * n[3], a[1] * n[2] + a[3] * n[3],
                a[0] * n[4] + a[2] * n[5] + a[4], a[1] * n[4] + a[3] * n[5] + a[5])
        }
        fun translate(x: Double, y: Double) = multiplie(doubleArrayOf(1.0, 0.0, 0.0, 1.0, x, y))
        fun echelle(k: Double) = multiplie(doubleArrayOf(k, 0.0, 0.0, k, 0.0, 0.0))
        fun tourne(deg: Double, cx: Double, cy: Double) {
            val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
            translate(cx, cy); multiplie(doubleArrayOf(c, s, -s, c, 0.0, 0.0)); translate(-cx, -cy)
        }
        fun opacite(k: Double) { op *= k }

        private val tourne get() = abs(m[1]) > 1e-9 || abs(m[2]) > 1e-9
        private val k get() = sqrt(abs(m[0] * m[3] - m[1] * m[2]))
        private fun px(x: Double, y: Double) = m[0] * x + m[2] * y + m[4]
        private fun py(x: Double, y: Double) = m[1] * x + m[3] * y + m[5]
        private fun fond(s: String?): Peinture? = when (val p = peinture(s)) {
            is Uni -> Uni(alpha(p.argb, op))
            is Degrade -> if (op >= 1.0) p else p.copy(haut = alpha(p.haut, op), bas = alpha(p.bas, op))
            null -> null
        }
        private fun trait(s: String?): Int? = couleur(s)?.let { alpha(it, op) }

        /** A rectangle, its corners rounded [r]. */
        fun R(x: Double, y: Double, w: Double, h: Double, r: Double, f: String?, s: String? = null, sw: Double = 1.0, tirets: DoubleArray? = null) {
            if (w <= 0 || h <= 0) return
            if (tourne) {
                P("M${n(x)} ${n(y)} H${n(x + w)} V${n(y + h)} H${n(x)} Z", f, s, sw)
                return
            }
            formes += Rect(px(x, y), py(x, y), w * k, h * k, r * k, fond(f), if (s != null) trait(s) else null, sw * k, tirets?.map { it * k }?.toDoubleArray())
        }

        fun C(x: Double, y: Double, r: Double, f: String?, s: String? = null, sw: Double = 1.0) {
            if (r <= 0) return
            formes += Cercle(px(x, y), py(x, y), r * k, fond(f), if (s != null) trait(s) else null, sw * k)
        }

        /** An ellipse, as four curves. */
        fun E(cx: Double, cy: Double, rx: Double, ry: Double, f: String?, s: String? = null, sw: Double = 1.0) {
            val a = 0.5523 * rx; val b = 0.5523 * ry
            P("M${n(cx + rx)} ${n(cy)} C${n(cx + rx)} ${n(cy + b)} ${n(cx + a)} ${n(cy + ry)} ${n(cx)} ${n(cy + ry)} " +
                "C${n(cx - a)} ${n(cy + ry)} ${n(cx - rx)} ${n(cy + b)} ${n(cx - rx)} ${n(cy)} " +
                "C${n(cx - rx)} ${n(cy - b)} ${n(cx - a)} ${n(cy - ry)} ${n(cx)} ${n(cy - ry)} " +
                "C${n(cx + a)} ${n(cy - ry)} ${n(cx + rx)} ${n(cy - b)} ${n(cx + rx)} ${n(cy)} Z", f, s, sw)
        }

        /** A path as SVG writes it (M L H V Q C Z, relative ones too). */
        fun P(d: String, f: String?, s: String? = null, sw: Double = 1.0, tirets: DoubleArray? = null, rond: Boolean = false) {
            val ops = chemin(d).map { o ->
                when (o) {
                    is M -> M(px(o.x, o.y), py(o.x, o.y))
                    is L -> L(px(o.x, o.y), py(o.x, o.y))
                    is Cu -> Cu(px(o.x1, o.y1), py(o.x1, o.y1), px(o.x2, o.y2), py(o.x2, o.y2), px(o.x, o.y), py(o.x, o.y))
                    else -> o
                }
            }
            if (ops.size < 2) return
            formes += Chemin(ops, fond(f), if (s != null) trait(s) else null, sw * k, tirets?.map { it * k }?.toDoubleArray(), rond)
        }

        /** A text; [a] 's' start, 'm' middle, 'e' end. */
        fun T(x: Double, y: Double, t: String, sz: Double, f: String? = "#9AA7B4", a: Char = 'm', mono: Boolean = true, gras: Boolean = false,
              espacement: Double = 0.0, max: Double = 0.0) {
            if (t.isEmpty()) return
            val c = trait(f ?: "#9AA7B4") ?: return
            formes += Texte(t, px(x, y), py(x, y), sz * k, c, gras, when (a) { 's' -> 0; 'e' -> 2; else -> 1 }, mono, espacement * k, max * k)
        }

        /** A picture of [il]×[ih] pixels fitted in the box (its proportions kept, centred). */
        fun image(octets: ByteArray, il: Int, ih: Int, x: Double, y: Double, w: Double, h: Double, lueur: String? = null) {
            if (il <= 0 || ih <= 0 || w <= 0 || h <= 0) return
            val e = minOf(w / il, h / ih)
            val l = il * e; val hh = ih * e
            val x0 = x + (w - l) / 2; val y0 = y + (h - hh) / 2
            formes += Image(octets, px(x0, y0), py(x0, y0), l * k, hh * k, trait(lueur))
        }
    }

    // ------------------------------------------------------------------ paths

    private val JETON = Regex("[MmLlHhVvQqCcZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE]-?\\d+)?")

    /** [d] read as absolute moves, lines and cubic curves. */
    fun chemin(d: String): List<Op> {
        val j = JETON.findAll(d).map { it.value }.toList()
        val out = ArrayList<Op>()
        var i = 0; var cmd = 'M'
        var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0
        fun num(): Double = j[i++].toDouble()
        fun estNombre() = i < j.size && j[i][0] !in "MmLlHhVvQqCcZz"
        while (i < j.size) {
            if (!estNombre()) { cmd = j[i++][0]; if (cmd == 'Z' || cmd == 'z') { out += Z; x = sx; y = sy; continue } }
            if (!estNombre()) continue
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0.0; val oy = if (rel) y else 0.0
            when (cmd.uppercaseChar()) {
                'M' -> { x = ox + num(); y = oy + num(); sx = x; sy = y; out += M(x, y); cmd = if (rel) 'l' else 'L' }
                'L' -> { x = ox + num(); y = oy + num(); out += L(x, y) }
                'H' -> { x = (if (rel) x else 0.0) + num(); out += L(x, y) }
                'V' -> { y = (if (rel) y else 0.0) + num(); out += L(x, y) }
                'Q' -> {
                    val qx = ox + num(); val qy = oy + num(); val ex = ox + num(); val ey = oy + num()
                    out += Cu(x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), ex + 2.0 / 3 * (qx - ex), ey + 2.0 / 3 * (qy - ey), ex, ey)
                    x = ex; y = ey
                }
                'C' -> {
                    val a = ox + num(); val b = oy + num(); val c = ox + num(); val dd = oy + num(); val ex = ox + num(); val ey = oy + num()
                    out += Cu(a, b, c, dd, ex, ey); x = ex; y = ey
                }
                else -> i++
            }
        }
        return out
    }

    /** A number as the SVG writes it: at most two decimals, never « 1.0E-4 », the same on every machine. */
    fun n(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        val s = String.format(Locale.US, "%.2f", if (r == 0.0) 0.0 else r)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }.let { if (it == "-0") "0" else it }
    }

    // ------------------------------------------------------------------ SVG

    private fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** ` fill="#…" fill-opacity=".5"` (or a gradient) for [p]. */
    private fun attrFond(p: Peinture?, prefixe: String): String = when (p) {
        null -> " fill=\"none\""
        is Uni -> couleurAttr("fill", p.argb)
        is Degrade -> " fill=\"url(#$prefixe-${p.id}${if (p.haut ushr 24 != 255) "-" + Integer.toHexString(p.haut ushr 24) else ""})\""
    }

    private fun couleurAttr(nom: String, c: Int): String {
        val a = (c ushr 24) and 0xFF
        val rgb = String.format(Locale.US, "#%06X", c and 0xFFFFFF)
        return " $nom=\"$rgb\"" + if (a < 255) " $nom-opacity=\"${n(a / 255.0)}\"" else ""
    }

    private fun attrTrait(c: Int?, ep: Double, tirets: DoubleArray?, rond: Boolean): String {
        if (c == null) return ""
        return couleurAttr("stroke", c) + " stroke-width=\"${n(ep)}\"" +
            (if (tirets != null) " stroke-dasharray=\"${tirets.joinToString(" ") { n(it) }}\"" else "") +
            (if (rond) " stroke-linecap=\"round\" stroke-linejoin=\"round\"" else "")
    }

    fun d(ops: List<Op>): String = ops.joinToString(" ") { o ->
        when (o) {
            is M -> "M${n(o.x)} ${n(o.y)}"
            is L -> "L${n(o.x)} ${n(o.y)}"
            is Cu -> "C${n(o.x1)} ${n(o.y1)} ${n(o.x2)} ${n(o.y2)} ${n(o.x)} ${n(o.y)}"
            else -> "Z"
        }
    }

    /**
     * The scene as one `<svg>`: its gradients first (ids under [prefixe]),
     * then each shape; the pictures inside (data: URIs). Nothing fetched.
     */
    fun svg(s: Scene, prefixe: String = "sm", classe: String = "montage", titre: String = ""): String = buildString {
        append("<svg class=\"").append(classe).append("\" viewBox=\"0 0 ").append(n(s.l)).append(' ').append(n(s.h))
            .append("\" role=\"img\"").append(if (titre.isNotBlank()) " aria-label=\"" + h(titre) + "\"" else "").append(">")
        if (titre.isNotBlank()) append("<title>").append(h(titre)).append("</title>")
        // The gradients the shapes use (each opacity its own).
        val utilises = LinkedHashMap<String, Degrade>()
        for (f in s.formes) {
            val p = when (f) { is Rect -> f.fond; is Cercle -> f.fond; is Chemin -> f.fond; else -> null }
            if (p is Degrade) utilises[p.id + if (p.haut ushr 24 != 255) "-" + Integer.toHexString(p.haut ushr 24) else ""] = p
        }
        val lueurs = s.formes.filterIsInstance<Image>().mapNotNull { it.lueur }.distinct()
        append("<defs>")
        for ((id, g) in utilises) {
            fun stop(o: String, c: Int) = "<stop offset=\"$o\" stop-color=\"${String.format(Locale.US, "#%06X", c and 0xFFFFFF)}\"" +
                (if ((c ushr 24) != 255) " stop-opacity=\"${n(((c ushr 24) and 0xFF) / 255.0)}\"" else "") + "/>"
            if (g.radial) append("<radialGradient id=\"$prefixe-$id\" cx=\".4\" cy=\".35\" r=\".7\">").append(stop("0", g.haut)).append(stop("1", g.bas)).append("</radialGradient>")
            else append("<linearGradient id=\"$prefixe-$id\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">").append(stop("0", g.haut)).append(stop("1", g.bas)).append("</linearGradient>")
        }
        lueurs.forEachIndexed { i, c ->
            val rgb = String.format(Locale.US, "#%06X", c and 0xFFFFFF)
            append("<filter id=\"$prefixe-l$i\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">")
                .append("<feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"1\" flood-color=\"$rgb\" flood-opacity=\".95\"/>")
                .append("<feDropShadow dx=\"0\" dy=\"0\" stdDeviation=\"3\" flood-color=\"$rgb\" flood-opacity=\".6\"/></filter>")
        }
        append("</defs>")
        append("<rect width=\"").append(n(s.l)).append("\" height=\"").append(n(s.h)).append("\" rx=\"10\"").append(couleurAttr("fill", s.fond)).append("/>")
        for (f in s.formes) when (f) {
            is Rect -> append("<rect x=\"${n(f.x)}\" y=\"${n(f.y)}\" width=\"${n(f.l)}\" height=\"${n(f.h)}\"")
                .append(if (f.r > 0) " rx=\"${n(f.r)}\"" else "").append(attrFond(f.fond, prefixe)).append(attrTrait(f.trait, f.ep, f.tirets, false)).append("/>")
            is Cercle -> append("<circle cx=\"${n(f.x)}\" cy=\"${n(f.y)}\" r=\"${n(f.r)}\"").append(attrFond(f.fond, prefixe))
                .append(attrTrait(f.trait, f.ep, null, false)).append("/>")
            is Chemin -> append("<path d=\"").append(d(f.ops)).append("\"").append(attrFond(f.fond, prefixe)).append(attrTrait(f.trait, f.ep, f.tirets, f.rond)).append("/>")
            is Texte -> append("<text x=\"${n(f.x)}\" y=\"${n(f.y)}\" font-size=\"${n(f.taille)}\"")
                .append(if (f.mono) " font-family=\"ui-monospace,SFMono-Regular,Menlo,Consolas,monospace\"" else " font-family=\"system-ui,-apple-system,'Segoe UI',Roboto,sans-serif\"")
                .append(if (f.gras) " font-weight=\"700\"" else "").append(if (f.espacement > 0) " letter-spacing=\"${n(f.espacement)}\"" else "")
                .append(when (f.ancre) { 1 -> " text-anchor=\"middle\""; 2 -> " text-anchor=\"end\""; else -> "" })
                .append(couleurAttr("fill", f.couleur)).append(">").append(h(f.s)).append("</text>")
            is Image -> append("<image x=\"${n(f.x)}\" y=\"${n(f.y)}\" width=\"${n(f.l)}\" height=\"${n(f.h)}\" preserveAspectRatio=\"none\"")
                .append(if (f.lueur != null) " filter=\"url(#$prefixe-l${lueurs.indexOf(f.lueur)})\"" else "")
                .append(" href=\"data:").append(mime(f.octets)).append(";base64,").append(Base64.getEncoder().encodeToString(f.octets)).append("\"/>")
        }
        append("</svg>")
    }

    /** A picture's kind from its first bytes. */
    fun mime(b: ByteArray): String = if (b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
}
