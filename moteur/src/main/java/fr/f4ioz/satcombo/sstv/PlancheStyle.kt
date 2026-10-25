/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.io.InputStream
import java.text.Normalizer

/**
 * How a sheet's texts and boxes may be dressed, like the artwork of an
 * ARISS event: a display font ([PlanchePolices]), an effect ([Style] —
 * chrome, gold, neon…), a slant, letter spacing, glowing rules; a frame
 * round the pictures ([Cadre]).
 *
 * **One drawing, three screens.** What an effect is — its layers, their
 * colours, offsets, strokes and blur, in fractions of the text's size — is
 * decided here; the phone (PlancheRendu) and the PC (PlancheRenduPc) only
 * turn each [Couche] into strokes, and blur with [flou]: the same sheet
 * whichever draws it, the preview and the export alike.
 */
object PlancheStyle {

    /** A text's effect; [code] is what the sheet file writes ("" the plain one). */
    enum class Style(val code: String, val cle: String) {
        SIMPLE("", "planche_style_simple"),
        CONTOUR("contour", "planche_style_contour"),
        RELIEF("relief", "planche_style_relief"),
        NEON("neon", "planche_style_neon"),
        CHROME("chrome", "planche_style_chrome"),
        OR("or", "planche_style_or"),
        DEGRADE("degrade", "planche_style_degrade");
        companion object { fun de(c: String): Style = values().firstOrNull { it.code == c } ?: SIMPLE }
    }

    /** The frame round each picture of a sheet; [code] is what the file writes ("" none: the white line of old). */
    enum class Cadre(val code: String, val cle: String) {
        AUCUN("", "planche_cadre_aucun"),
        FIN("fin", "planche_cadre_fin"),
        ARRONDI("arrondi", "planche_cadre_arrondi"),
        NEON("neon", "planche_cadre_neon");
        companion object { fun de(c: String): Cadre = values().firstOrNull { it.code == c } ?: AUCUN }
    }

    /** The white mat of a « fine » frame, and a rounded box's corner, in fractions of the box's short side. */
    const val CADRE_FIN = 0.035f
    const val CADRE_ARRONDI = 0.07f

    /** The slant of an italic text: x moves left by this much of the height above the baseline. */
    const val PENTE = 0.21f

    /** Letter spacing, in fractions of the size, at most. */
    const val ESPACEMENT_MAX = 0.6f

    /** The colours a glow (neon text, neon frame) may take; the second colour of a gradient too. */
    val LUEURS = listOf(0xFF2EC5FF.toInt(), 0xFF00E5FF.toInt(), 0xFFFF3FD2.toInt(), 0xFF9B5CFF.toInt(),
        0xFF39FF88.toInt(), 0xFFFFC21A.toInt(), 0xFFFF5A36.toInt(), 0xFFFFFFFF.toInt())

    /** A text drawn as SatMe always did (the sans-serif bold, plain): the old drawing, stroke for stroke. */
    fun estSimple(t: Planche.Texte): Boolean =
        t.style == Style.SIMPLE && t.police.isEmpty() && !t.italique && t.espacement == 0f && !t.filets

    // ------------------------------------------------------------ layers

    /**
     * One layer of a styled text, drawn after those before it: the glyphs
     * filled ([remplit]) and/or stroked ([trait] wide), moved by [dx], [dy],
     * painted with [couleurs] at [positions] from the top of the line's
     * glyphs to their bottom (one colour: plain), blurred by [flou]. All
     * lengths in fractions of the font size.
     */
    class Couche(
        val couleurs: IntArray,
        val positions: FloatArray? = null,
        val remplit: Boolean = true,
        val trait: Float = 0f,
        val dx: Float = 0f,
        val dy: Float = 0f,
        val flou: Float = 0f
    ) {
        val uni: Boolean get() = couleurs.size == 1
    }

    private fun uni(c: Int, remplit: Boolean = true, trait: Float = 0f, dx: Float = 0f, dy: Float = 0f, flou: Float = 0f) =
        Couche(intArrayOf(c), null, remplit, trait, dx, dy, flou)

    /** [a] and [b] mixed, [t] of [b]; alpha mixed too. */
    fun melange(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, d: Int) = (c shr d) and 0xFF
        fun m(d: Int) = (ch(a, d) + (ch(b, d) - ch(a, d)) * t).toInt().coerceIn(0, 255)
        return (m(24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** [c] with [alpha] (0..255). */
    fun alpha(c: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)

    private const val NOIR = 0xFF000000.toInt()
    private const val BLANC = 0xFFFFFFFF.toInt()

    /** The chrome's steel, the line of horizon at the middle of the letters (« SSTV » of an ARISS series). */
    private val ACIER = floatArrayOf(0f, 0.26f, 0.47f, 0.50f, 0.58f, 0.80f, 1f)
    private fun chrome(teinte: Int): IntArray {
        // White chrome by default; a colour given tints its lower half (the blue « 33 »).
        val t = if ((teinte and 0xFFFFFF) == 0xFFFFFF) 0f else 0.5f
        return intArrayOf(BLANC, 0xFFEDF1F6.toInt(), 0xFFAFBBCA.toInt(),
            melange(0xFF26324A.toInt(), teinte, t * 0.5f), melange(0xFF4D5E78.toInt(), teinte, t),
            melange(0xFFA4B5CB.toInt(), teinte, t), melange(0xFFF3F7FB.toInt(), teinte, t * 0.4f))
    }
    private val ORS = intArrayOf(0xFFFFF8D0.toInt(), 0xFFF9D769.toInt(), 0xFFE0A93A.toInt(), 0xFF8A5A0E.toInt(),
        0xFFB77F1C.toInt(), 0xFFF0C24A.toInt(), 0xFFFFEBA0.toInt())

    /** The layers that draw [t], back to front. */
    fun couches(t: Planche.Texte): List<Couche> {
        val c = t.couleur
        val c2 = t.couleur2
        val ombre = uni(alpha(NOIR, 170), dy = 0.035f, flou = 0.05f)
        return when (t.style) {
            Style.SIMPLE -> listOf(uni(c))
            // Hollow letters, their outline in the colour; filled with the second colour when one is chosen.
            Style.CONTOUR -> listOfNotNull(
                uni(alpha(NOIR, 140), remplit = false, trait = 0.11f, flou = 0.05f),
                if (c2 != 0) uni(c2) else null,
                uni(c, remplit = false, trait = 0.055f))
            // Extruded down and right, darker as it goes back; the face lit from above.
            Style.RELIEF -> (8 downTo 1).map { i ->
                uni(melange(c, NOIR, 0.55f + i * 0.03f), dx = i * 0.011f, dy = i * 0.013f)
            }.let { listOf(uni(alpha(NOIR, 150), dx = 0.10f, dy = 0.12f, flou = 0.06f)) + it } + listOf(
                uni(melange(c, NOIR, 0.7f), remplit = false, trait = 0.03f),
                Couche(intArrayOf(melange(c, BLANC, 0.35f), c, melange(c, NOIR, 0.12f)), floatArrayOf(0f, 0.55f, 1f)))
            // A tube of light: a wide soft glow, a close one, the tube, its bright core.
            Style.NEON -> listOf(
                uni(c, trait = 0.16f, flou = 0.34f),
                uni(c, trait = 0.08f, flou = 0.09f),
                uni(melange(c, BLANC, 0.1f), remplit = false, trait = 0.06f),
                Couche(intArrayOf(melange(c, BLANC, 0.78f), melange(c, BLANC, 0.35f), melange(c, BLANC, 0.6f)), floatArrayOf(0f, 0.6f, 1f)))
            // Polished steel: a deep shadow, a blue-black extrusion, a dark bevel, a bright rim, the mirror with its horizon line.
            Style.CHROME -> listOf(
                ombre,
                uni(0xFF0B1E3E.toInt(), trait = 0.15f, dx = 0.010f, dy = 0.05f),
                uni(0xFF0E1622.toInt(), remplit = false, trait = 0.13f),
                uni(0xFFE6ECF3.toInt(), remplit = false, trait = 0.04f),
                Couche(chrome(c), ACIER))
            Style.OR -> listOf(
                ombre,
                uni(0xFF2A1702.toInt(), remplit = false, trait = 0.12f),
                uni(0xFFFFE9A6.toInt(), remplit = false, trait = 0.045f),
                Couche(ORS, ACIER))
            Style.DEGRADE -> listOf(
                ombre,
                Couche(intArrayOf(c, if (c2 != 0) c2 else melange(c, NOIR, 0.55f)), floatArrayOf(0f, 1f)))
        }
    }

    /** Room a text's effect needs round its glyphs, each side, in fractions of the size. */
    fun marge(t: Planche.Texte): Float = couches(t).maxOf { k ->
        maxOf(kotlin.math.abs(k.dx), kotlin.math.abs(k.dy)) + k.trait / 2 + k.flou * 1.5f
    }.coerceAtMost(0.35f)

    // ------------------------------------------------------------ rules

    /** Glowing rules above and below a text: their thickness and glow, in fractions of the zone's height. */
    const val FILET_EPAISSEUR = 0.035f
    const val FILET_FLOU = 0.10f
    /** Where the rules lie, from the zone's top and bottom (fractions of its height). */
    const val FILET_PLACE = 0.06f
    /** The share of the zone's height left to the text between the rules. */
    const val FILET_TEXTE = 0.70f

    /** The rule's colours along it: fading at both ends. */
    fun filet(c: Int): Pair<IntArray, FloatArray> =
        intArrayOf(alpha(c, 0), melange(c, BLANC, 0.55f), melange(c, BLANC, 0.85f), melange(c, BLANC, 0.55f), alpha(c, 0)) to
            floatArrayOf(0f, 0.18f, 0.5f, 0.82f, 1f)

    // ------------------------------------------------------------ blur

    /**
     * A shape drawn in one colour on transparent pixels ([px], ARGB, [w]×[h])
     * blurred by about [rayon] pixels (three box blurs, close to a Gaussian):
     * only its alpha is spread; every pixel takes [couleur], its alpha
     * multiplied. The same pixels on every platform.
     */
    fun flou(px: IntArray, w: Int, h: Int, rayon: Int, couleur: Int) {
        val n = w * h
        if (n <= 0 || px.size < n) return
        val a = IntArray(n) { (px[it] ushr 24) and 0xFF }
        if (rayon > 0) {
            val tmp = IntArray(n)
            val r = maxOf(1, (rayon * 0.58f).toInt())
            repeat(3) { boite(a, tmp, w, h, r, true); boite(tmp, a, w, h, r, false) }
        }
        val ca = (couleur ushr 24) and 0xFF
        val rgb = couleur and 0xFFFFFF
        for (i in 0 until n) px[i] = ((a[i] * ca / 255) shl 24) or rgb
    }

    /** One box blur pass of radius [r], along rows ([lignes]) or columns, [src] to [dst]. */
    private fun boite(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, lignes: Boolean) {
        val long = if (lignes) w else h; val nb = if (lignes) h else w
        val pas = if (lignes) 1 else w
        val div = 2 * r + 1
        for (k in 0 until nb) {
            val debut = if (lignes) k * w else k
            var s = 0
            for (i in -r..r) s += src[debut + i.coerceIn(0, long - 1) * pas]
            for (i in 0 until long) {
                dst[debut + i * pas] = s / div
                val sort = (i - r).coerceIn(0, long - 1); val entre = (i + r + 1).coerceIn(0, long - 1)
                s += src[debut + entre * pas] - src[debut + sort * pas]
            }
        }
    }

    // ------------------------------------------------------------ glyphs

    /**
     * [texte] with what [peut] (the font's glyphs) cannot draw replaced: the
     * letter without its accent, a close sign (« · » → « • »), else a space —
     * never a box of a missing glyph on a sheet.
     */
    fun lisible(texte: String, peut: (Int) -> Boolean): String {
        if (texte.all { it.code < 0x80 && peut(it.code) }) return texte
        val b = StringBuilder()
        var i = 0
        while (i < texte.length) {
            val cp = texte.codePointAt(i); i += Character.charCount(cp)
            if (cp == '\n'.code || peut(cp)) { b.appendCodePoint(cp); continue }
            val sans = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            val proche = when {
                sans.isNotEmpty() && sans.all { peut(it.code) } -> sans
                else -> SUBSTITUTS[cp]?.firstOrNull { s -> s.all { peut(it.code) } } ?: " "
            }
            b.append(proche)
        }
        return b.toString()
    }

    private val SUBSTITUTS = mapOf(
        0xB7 to listOf("•", "-", "."), 0x2022 to listOf("·", "-"), 0x2013 to listOf("-"), 0x2014 to listOf("-"),
        0x2019 to listOf("'"), 0x2018 to listOf("'"), 0x201C to listOf("\""), 0x201D to listOf("\""), 0xAB to listOf("\""), 0xBB to listOf("\""),
        0x2248 to listOf("~"), 0xB0 to listOf("o"), 0x2026 to listOf("..."), 0x152 to listOf("OE"), 0x153 to listOf("oe"), 0xC6 to listOf("AE"), 0xE6 to listOf("ae"))
}

/**
 * The display fonts a sheet's texts may take, bundled with SatMe (SIL Open
 * Font License, each with its licence beside it, in this package's
 * `polices/`): never fetched, the same on the phone, the PC and the page.
 */
object PlanchePolices {

    /** A font: [id] what the sheet file writes, [nom] its own name, [fichier] in `polices/`, [licence] its OFL text there. */
    class Police(val id: String, val nom: String, val fichier: String, val licence: String, val auteurs: String)

    val LISTE: List<Police> = listOf(
        Police("orbitron", "Orbitron Black", "Orbitron-Black.ttf", "Orbitron-OFL.txt", "The Orbitron Project Authors (Matt McInerney)"),
        Police("audiowide", "Audiowide", "Audiowide-Regular.ttf", "Audiowide-OFL.txt", "Astigmatic (Brian J. Bonislawsky)"),
        Police("exo2", "Exo 2 Black Italic", "Exo2-BlackItalic.ttf", "Exo2-OFL.txt", "The Exo 2 Project Authors (Natanael Gama)"),
        Police("russo", "Russo One", "RussoOne-Regular.ttf", "RussoOne-OFL.txt", "Jovanny Lemonad"),
        Police("michroma", "Michroma", "Michroma-Regular.ttf", "Michroma-OFL.txt", "The Michroma Project Authors (Vernon Adams)"),
        Police("bungee", "Bungee", "Bungee-Regular.ttf", "Bungee-OFL.txt", "The Bungee Project Authors (David Jonathan Ross)"),
        Police("monoton", "Monoton", "Monoton-Regular.ttf", "Monoton-OFL.txt", "Vernon Adams"))

    /** The font [id]; null for the default one ("") or one unknown (a file from a later version: the default drawn). */
    fun de(id: String): Police? = LISTE.firstOrNull { it.id == id }

    fun ouvre(p: Police): InputStream? = PlanchePolices::class.java.getResourceAsStream("polices/${p.fichier}")

    private val octets = HashMap<String, ByteArray>()

    /** The font file's bytes (served to the page), kept once read. */
    fun octets(p: Police): ByteArray? = synchronized(octets) {
        octets[p.id] ?: runCatching { ouvre(p)?.use { it.readBytes() } }.getOrNull()?.also { octets[p.id] = it }
    }
}
