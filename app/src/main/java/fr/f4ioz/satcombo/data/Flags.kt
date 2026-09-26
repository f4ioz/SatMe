/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

/**
 * Catalogue of the small flags placed before the callsign on the QRV photo.
 *
 * No bundled images: a flag is described by its geometry (stripes, cross,
 * disc…) and [FlagDraw] draws it at the wanted height. A PNG per country
 * would be heavy and pixelate as the callsign grows; vectors stay sharp.
 *
 * No Android dependency: JVM tests check codes are unique and proportions
 * plausible.
 */
object Flags {

    /** No flag. */
    const val NONE = ""

    /** Gwenn ha Du, the Breton flag. */
    const val BZH = "BZH"

    /** Melen ha Ruz, the Pays bigouden flag. */
    const val BIGOUDEN = "BIG"

    // ---- drawing families -------------------------------------------------
    /** Vertical stripes, hoist to fly. */
    const val STRIPES_V = "STRIPES_V"
    /** Horizontal stripes, top to bottom. */
    const val STRIPES_H = "STRIPES_H"
    /** Nordic cross: field, cross, optional inner fimbriation. */
    const val NORDIC = "NORDIC"
    /** Centred cross (Switzerland). */
    const val CROSS = "CROSS"
    /** Centred disc (Japan). */
    const val DISC = "DISC"
    /** Two horizontal stripes and a hoist triangle (Czechia). */
    const val TRIANGLE = "TRIANGLE"
    /** Union Jack. */
    const val UNION = "UNION"
    /** Stripes and starred canton (USA). */
    const val USA = "USA"
    /** Nine stripes and a cross canton (Greece). */
    const val GREECE = "GREECE"
    /** Three bands and a central leaf (Canada). */
    const val CANADA = "CANADA"
    /** Alternating stripes and an ermine canton (Brittany). */
    const val ERMINE = "ERMINE"
    /** Alternating stripes and a full-height ermine panel (Pays bigouden). */
    const val ERMINE_HOIST = "ERMINE_HOIST"
    /** Plain field and central star (Viet Nam, Morocco). */
    const val STAR = "STAR"
    /** Plain field, crescent and star (Turkey). */
    const val CRESCENT = "CRESCENT"
    /** Union Jack canton and stars (Australia, New Zealand). */
    const val CANTON_UNION = "CANTON_UNION"
    /** Lozenge and disc on a plain field (Brazil). */
    const val LOZENGE = "LOZENGE"

    /**
     * A catalogue flag.
     *
     * @param code short id, the one stored in settings
     * @param label name shown in the picker
     * @param ratio width / height
     * @param kind drawing family, one of the constants above
     * @param colors ARGB colours, meaning depends on [kind]
     * @param weights relative stripe weights, empty = equal
     * @param spots number of ermine spots, [ERMINE] only
     */
    data class Flag(
        val code: String,
        val label: String,
        val ratio: Float,
        val kind: String,
        val colors: List<Int>,
        val weights: List<Float> = emptyList(),
        val spots: Int = 0
    )

    private fun c(v: Long): Int = v.toInt()

    private val WHITE = c(0xFFFFFFFF)
    private val BLACK = c(0xFF000000)

    /**
     * The offered flags, in picker order: the two Breton flags first (the app
     * is written in Brittany), then France, then the rest by radio proximity.
     */
    val ALL: List<Flag> = listOf(
        Flag(BZH, "Gwenn ha Du", 1.5f, ERMINE,
            listOf(BLACK, WHITE, WHITE, BLACK), List(9) { 1f }, 11),
        // Bigouden flag: five red/yellow stripes and a full-height yellow hoist
        // panel with red ermine, one per commune — not a canton as on the
        // Gwenn ha Du.
        Flag(BIGOUDEN, "Bigouden", 1.5f, ERMINE_HOIST,
            listOf(c(0xFFD3232F), c(0xFFF2B233), c(0xFFF2B233), c(0xFFD3232F)),
            List(5) { 1f }, 22),
        Flag("FR", "France", 1.5f, STRIPES_V,
            listOf(c(0xFF002395), WHITE, c(0xFFED2939))),
        Flag("BE", "Belgique", 1.5f, STRIPES_V,
            listOf(BLACK, c(0xFFFAE042), c(0xFFED2939))),
        Flag("CH", "Suisse", 1f, CROSS, listOf(c(0xFFD52B1E), WHITE)),
        Flag("LU", "Luxembourg", 1.667f, STRIPES_H,
            listOf(c(0xFFED2939), WHITE, c(0xFF00A1DE))),
        Flag("DE", "Deutschland", 1.667f, STRIPES_H,
            listOf(BLACK, c(0xFFDD0000), c(0xFFFFCE00))),
        Flag("AT", "Osterreich", 1.5f, STRIPES_H,
            listOf(c(0xFFED2939), WHITE, c(0xFFED2939))),
        Flag("NL", "Nederland", 1.5f, STRIPES_H,
            listOf(c(0xFFAE1C28), WHITE, c(0xFF21468B))),
        Flag("GB", "United Kingdom", 2f, UNION,
            listOf(c(0xFF012169), WHITE, c(0xFFC8102E))),
        Flag("IE", "Eire", 2f, STRIPES_V,
            listOf(c(0xFF169B62), WHITE, c(0xFFFF883E))),
        Flag("IT", "Italia", 1.5f, STRIPES_V,
            listOf(c(0xFF009246), WHITE, c(0xFFCE2B37))),
        Flag("ES", "Espana", 1.5f, STRIPES_H,
            listOf(c(0xFFAA151B), c(0xFFF1BF00), c(0xFFAA151B)), listOf(1f, 2f, 1f)),
        Flag("PT", "Portugal", 1.5f, STRIPES_V,
            listOf(c(0xFF046A38), c(0xFFDA291C)), listOf(2f, 3f)),
        Flag("DK", "Danmark", 1.32f, NORDIC, listOf(c(0xFFC8102E), WHITE)),
        Flag("SE", "Sverige", 1.6f, NORDIC, listOf(c(0xFF006AA7), c(0xFFFECC00))),
        Flag("NO", "Norge", 1.375f, NORDIC, listOf(c(0xFFBA0C2F), WHITE, c(0xFF00205B))),
        Flag("FI", "Suomi", 1.63f, NORDIC, listOf(WHITE, c(0xFF003580))),
        Flag("IS", "Island", 1.39f, NORDIC, listOf(c(0xFF02529C), WHITE, c(0xFFDC1E35))),
        Flag("PL", "Polska", 1.6f, STRIPES_H, listOf(WHITE, c(0xFFDC143C))),
        Flag("CZ", "Cesko", 1.5f, TRIANGLE, listOf(WHITE, c(0xFFD7141A), c(0xFF11457E))),
        Flag("HU", "Magyarorszag", 2f, STRIPES_H,
            listOf(c(0xFFCE2939), WHITE, c(0xFF477050))),
        Flag("RO", "Romania", 1.5f, STRIPES_V,
            listOf(c(0xFF002B7F), c(0xFFFCD116), c(0xFFCE1126))),
        Flag("GR", "Hellas", 1.5f, GREECE, listOf(c(0xFF0D5EAF), WHITE)),
        Flag("UA", "Ukraina", 1.5f, STRIPES_H, listOf(c(0xFF0057B7), c(0xFFFFD700))),
        Flag("US", "USA", 1.9f, USA, listOf(c(0xFFB22234), WHITE, c(0xFF3C3B6E))),
        Flag("CA", "Canada", 2f, CANADA, listOf(c(0xFFD80621), WHITE)),
        Flag("JP", "Nippon", 1.5f, DISC, listOf(WHITE, c(0xFFBC002D))),
        Flag("AU", "Australia", 2f, CANTON_UNION,
            listOf(c(0xFF00008B), WHITE, c(0xFFC8102E))),
        Flag("BR", "Brasil", 1.43f, LOZENGE,
            listOf(c(0xFF009C3B), c(0xFFFEDF00), c(0xFF002776))),
        // ---- the rest of Europe, then the world. Coats of arms and central
        // emblems are omitted on purpose: at callsign height they would be a
        // blot, and the silhouette is enough.
        Flag("RU", "Rossiya", 1.5f, STRIPES_H,
            listOf(WHITE, c(0xFF0039A6), c(0xFFD52B1E))),
        Flag("EE", "Eesti", 1.57f, STRIPES_H,
            listOf(c(0xFF0072CE), BLACK, WHITE)),
        Flag("LV", "Latvija", 2f, STRIPES_H,
            listOf(c(0xFF9E3039), WHITE, c(0xFF9E3039)), listOf(2f, 1f, 2f)),
        Flag("LT", "Lietuva", 1.667f, STRIPES_H,
            listOf(c(0xFFFDB913), c(0xFF006A44), c(0xFFC1272D))),
        Flag("BG", "Balgariya", 1.667f, STRIPES_H,
            listOf(WHITE, c(0xFF00966E), c(0xFFD62612))),
        Flag("RS", "Srbija", 1.5f, STRIPES_H,
            listOf(c(0xFFC6363C), c(0xFF0C4076), WHITE)),
        Flag("HR", "Hrvatska", 2f, STRIPES_H,
            listOf(c(0xFFFF0000), WHITE, c(0xFF171796))),
        Flag("SI", "Slovenija", 2f, STRIPES_H,
            listOf(WHITE, c(0xFF005DA4), c(0xFFED1C24))),
        Flag("SK", "Slovensko", 1.5f, STRIPES_H,
            listOf(WHITE, c(0xFF0B4EA2), c(0xFFEE1C25))),
        Flag("MC", "Monaco", 1.25f, STRIPES_H,
            listOf(c(0xFFCE1126), WHITE)),
        Flag("MT", "Malta", 1.5f, STRIPES_V, listOf(WHITE, c(0xFFCF142B))),
        Flag("FO", "Foroyar", 1.5f, NORDIC,
            listOf(WHITE, c(0xFF0065BD), c(0xFFED2939))),
        Flag("TR", "Turkiye", 1.5f, CRESCENT, listOf(c(0xFFE30A17), WHITE)),
        Flag("MA", "Al Maghrib", 1.5f, STAR,
            listOf(c(0xFFC1272D), c(0xFF006233))),
        Flag("EG", "Misr", 1.5f, STRIPES_H,
            listOf(c(0xFFCE1126), WHITE, BLACK)),
        Flag("NG", "Nigeria", 2f, STRIPES_V,
            listOf(c(0xFF008751), WHITE, c(0xFF008751))),
        Flag("ZA", "South Africa", 1.5f, STRIPES_H,
            listOf(c(0xFF007A4D), WHITE, c(0xFFFFB612))),
        Flag("IN", "Bharat", 1.5f, STRIPES_H,
            listOf(c(0xFFFF9933), WHITE, c(0xFF138808))),
        Flag("BD", "Bangladesh", 1.667f, DISC,
            listOf(c(0xFF006A4E), c(0xFFF42A41))),
        Flag("TH", "Prathet Thai", 1.5f, STRIPES_H,
            listOf(c(0xFFA51931), WHITE, c(0xFF2D2A4A), WHITE, c(0xFFA51931)),
            listOf(1f, 1f, 2f, 1f, 1f)),
        Flag("VN", "Viet Nam", 1.5f, STAR,
            listOf(c(0xFFDA251D), c(0xFFFFFF00))),
        Flag("ID", "Indonesia", 1.5f, STRIPES_H,
            listOf(c(0xFFCE1126), WHITE)),
        Flag("NZ", "New Zealand", 2f, CANTON_UNION,
            listOf(c(0xFF00247D), WHITE, c(0xFFCC142B))),
        Flag("MX", "Mexico", 1.75f, STRIPES_V,
            listOf(c(0xFF006847), WHITE, c(0xFFCE1126))),
        Flag("AR", "Argentina", 1.6f, STRIPES_H,
            listOf(c(0xFF75AADB), WHITE, c(0xFF75AADB))),
        Flag("PE", "Peru", 1.5f, STRIPES_V,
            listOf(c(0xFFD91023), WHITE, c(0xFFD91023)))
    )

    /**
     * The catalogue as offered to this operator. The two Breton flags are not
     * national flags: they only appear with BZH in the Extensions field — for
     * anyone, not just the author's station.
     */
    fun catalogue(bzh: Boolean): List<Flag> =
        if (bzh) ALL else ALL.filter { it.code != BZH && it.code != BIGOUDEN }

    /** True if this code may be shown with these extensions. */
    fun allowed(code: String?, bzh: Boolean): Boolean {
        val f = byCode(code) ?: return false
        return bzh || (f.code != BZH && f.code != BIGOUDEN)
    }

    /** Usable codes, in picker order. */
    val CODES: List<String> get() = ALL.map { it.code }

    /** The flag with this code, or null if empty or unknown. */
    fun byCode(code: String?): Flag? {
        val k = code?.trim()?.uppercase().orEmpty()
        if (k.isEmpty()) return null
        return ALL.firstOrNull { it.code == k }
    }

    /**
     * Ermine spots per row: eleven for the Gwenn ha Du (4-3-4, the common
     * layout), twenty-two for Pays bigouden, one per commune. Neither count
     * is official.
     */
    /**
     * Spots of a standing panel, at most [cols] per row. The remainder goes to
     * the first rows rather than leaving a lone spot on the last row.
     */
    fun ermineRowsOf(n: Int, cols: Int): List<Int> {
        if (n <= 0 || cols <= 0) return emptyList()
        val rows = (n + cols - 1) / cols
        val base = n / rows
        val extra = n % rows
        return List(rows) { base + if (it < extra) 1 else 0 }
    }

    fun ermineRows(n: Int): List<Int> = when {
        n <= 0 -> emptyList()
        n == 11 -> listOf(4, 3, 4)
        n == 22 -> listOf(5, 4, 5, 4, 4)
        else -> {
            val perRow = maxOf(1, Math.round(Math.sqrt(n.toDouble() * 1.6)).toInt())
            val out = ArrayList<Int>()
            var left = n
            while (left > 0) { out.add(minOf(perRow, left)); left -= perRow }
            out
        }
    }
}
