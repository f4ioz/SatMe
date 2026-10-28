/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.sqrt

/**
 * The slide's number read on the footer of an ARISS slide (« SLIDE 7/12 »):
 * no OCR, the letters are 8 to 10 pixels high in Robot 36. Each series
 * draws its footer its own way ([Style]: series 34 white and bold under
 * « СЕРИЯ 34 », series 33 thin and yellow under « СЕРИЯ 33 », often cut by
 * the picture's bottom edge); for each, the « /12 » is looked for anywhere
 * in the picture (moved up when the picture began late, half cut at the
 * bottom), then the number before it compared with the numbers seen on the
 * real pictures of that series, and the series' own number (« СЕРИЯ 33 »)
 * checked above it. Patterns made by LectureDiapoApprentissage (tests) from
 * pictures read by eye; a number with no pattern of its style (5 and 6 of
 * series 34), like any number not clearly one of the patterns, is not read
 * (0) — the copies of the slide in other passes, the hand or the timetable
 * ([HoraireDiapos]) then say it.
 *
 * Another series may print its footer elsewhere or otherwise: nothing read.
 * (tesseract was tried on these 320×240 footers — 8 visible rows, cut
 * glyphs, Robot 36 colour smear — and read wrong numbers: not used.)
 */
object LectureDiapo {

    /**
     * One way of drawing the footer: the « /12 » ([barre], [bl]×[bh]
     * grey levels), each number's place just left of it ([zl] wide, same
     * height) by number, and the series' number ([serie]) at
     * ([sx], [sy]) from the « /12 » ([sl] wide, same height), [motifSerie].
     * [rangs]: rows of the « /12 » that must be in the picture (the rest may
     * be cut by its bottom edge). The numbers compared from their column
     * [zd] (what all of them share left of it — the end of « SLIDE » — left out).
     */
    class Style(
        val nom: String, val barre: FloatArray, val bl: Int, val bh: Int, val zl: Int, val nombres: Map<Int, FloatArray>,
        val rangs: Int, val serie: Int = 0, val motifSerie: FloatArray? = null, val sx: Int = 0, val sy: Int = 0, val sl: Int = 0,
        val zd: Int = 0,
    )

    /** What the footer says: the slide (0 not read), of [sur], the series' number (0 not read). */
    data class Lecture(val diapo: Int, val sur: Int = 12, val serie: Int = 0)

    /** Below this the « /12 » is not there. */
    private const val SEUIL_BARRE = 0.6f
    /** A number at least this like its pattern, and this ahead of the next. */
    private const val SEUIL_NOMBRE = 0.905f
    private const val AVANCE = 0.06f
    /** The series' number at least this like its pattern. */
    private const val SEUIL_SERIE = 0.8f

    /** Brightness of the picture brought to 320 wide (its shape kept), 320×240, rows beyond it black. */
    fun gris(px: IntArray, w: Int, h: Int): IntArray {
        val g = IntArray(320 * 240)
        if (w <= 0 || h <= 0) return g
        val hh = minOf(240, (h.toLong() * 320 / w).toInt())
        for (y in 0 until hh) {
            val sy = (y.toLong() * w / 320).toInt().coerceIn(0, h - 1)
            for (x in 0 until 320) {
                val c = px[sy * w + (x.toLong() * w / 320).toInt().coerceIn(0, w - 1)]
                g[y * 320 + x] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            }
        }
        return g
    }

    /**
     * Normalised correlation of [t] ([w]×[h]) with [g] at ([x0], [y0]), on
     * the rows inside the picture only (its bottom edge may cut the footer),
     * the columns from [c0] only.
     */
    fun ncc(g: IntArray, x0: Int, y0: Int, t: FloatArray, w: Int, h: Int, c0: Int = 0): Float {
        if (x0 + c0 < 0 || x0 + w > 320) return -1f
        val r0 = maxOf(0, -y0); val r1 = minOf(h, 240 - y0)
        if (r1 - r0 < 2) return -1f
        var sg = 0.0; var st = 0.0
        for (r in r0 until r1) for (c in c0 until w) { sg += g[(y0 + r) * 320 + x0 + c]; st += t[r * w + c] }
        val n = (r1 - r0) * (w - c0)
        val mg = sg / n; val mt = st / n
        var sgt = 0.0; var sgg = 0.0; var stt = 0.0
        for (r in r0 until r1) for (c in c0 until w) {
            val a = g[(y0 + r) * 320 + x0 + c] - mg; val b = t[r * w + c] - mt
            sgt += a * b; sgg += a * a; stt += b * b
        }
        return if (sgg > 0 && stt > 0) (sgt / sqrt(sgg * stt)).toFloat() else 0f
    }

    /**
     * Where [style]'s « /12 » is likest in [g]: x, y, correlation; null when
     * nowhere. Anywhere in the picture: a picture begun late has its footer
     * moved up; [Style.rangs] rows of it at least inside.
     */
    fun cherche(g: IntArray, style: Style = SERIE_34, barre: FloatArray = style.barre): Triple<Int, Int, Float>? {
        var best: Triple<Int, Int, Float>? = null
        for (y in 0..(240 - style.rangs)) for (x in style.zl..(320 - style.bl)) {
            val c = ncc(g, x, y, barre, style.bl, style.bh)
            if (best == null || c > best.third) best = Triple(x, y, c)
        }
        return best
    }

    /** The slide read (1..12), 0 when not read. */
    fun lit(px: IntArray, w: Int, h: Int): Int = litTout(px, w, h).diapo

    /** The slide and the series read. */
    fun litTout(px: IntArray, w: Int, h: Int): Lecture = litTout(gris(px, w, h))

    fun litTout(g: IntArray, styles: List<Style> = STYLES): Lecture {
        var mieux: Pair<Lecture, Float>? = null
        for (s in styles) {
            val (x, y, c) = cherche(g, s) ?: continue
            if (c < SEUIL_BARRE) continue
            val n = lecture(g, s, x, y)?.let { (n, a, b) -> if (a >= SEUIL_NOMBRE && a - b >= AVANCE) n else 0 } ?: 0
            val serie = s.motifSerie?.let { m ->
                var b = -1f
                // The « /12 » moves right with a number of two figures: the series' line does not.
                for (dy in -1..1) for (dx in -8..1) b = maxOf(b, ncc(g, x + s.sx + dx, y + s.sy + dy, m, s.sl, s.bh))
                if (b >= SEUIL_SERIE) s.serie else 0
            } ?: 0
            // The style whose « /12 » is likest says it.
            if (mieux == null || c > mieux.second) mieux = Lecture(n, 12, serie) to c
        }
        return mieux?.first ?: Lecture(0)
    }

    fun lit(g: IntArray, motifs: Map<Int, String> = NOMBRES): Int =
        litTout(g, listOf(Style(SERIE_34.nom, SERIE_34.barre, SERIE_34.bl, SERIE_34.bh, SERIE_34.zl, motifs.mapValues { motif(it.value) }, SERIE_34.rangs))).diapo

    /** Each number of [style] tried left of the « /12 » at ([x], [y]): the likest, its likeness and the next one's. */
    fun lecture(g: IntArray, style: Style, x: Int, y: Int): Triple<Int, Float, Float>? {
        if (style.nombres.isEmpty()) return null
        val scores = style.nombres.mapValues { (_, t) ->
            var best = -1f
            for (dy in -1..1) for (dx in -1..1) best = maxOf(best, ncc(g, x - style.zl + dx, y + dy, t, style.zl, style.bh, style.zd))
            best
        }.entries.sortedByDescending { it.value }
        val a = scores.getOrNull(0) ?: return null
        return Triple(a.key, a.value, scores.getOrNull(1)?.value ?: -1f)
    }

    /** The likest number of series 34, its likeness and the next one's; null when no « /12 ». */
    fun lecture(g: IntArray, motifs: Map<Int, String> = NOMBRES): Triple<Int, Float, Float>? {
        val s = Style(SERIE_34.nom, SERIE_34.barre, SERIE_34.bl, SERIE_34.bh, SERIE_34.zl, motifs.mapValues { motif(it.value) }, SERIE_34.rangs)
        val (x, y, c) = cherche(g, s) ?: return null
        if (c < SEUIL_BARRE) return null
        return lecture(g, s, x, y)
    }

    /** A pattern written as grey levels a..z. */
    fun motif(s: String) = FloatArray(s.length) { (s[it] - 'a') * 256f / 26 + 5f }

    /** « /12 » of series 34 (grey levels a..z). */
    val BARRE: FloatArray = motif(
        "aaaaaaaaaceebaaaaaaaaabddbaaaaaaaaaaaaaaaaemleaacdbbbbceecbaaagnidcipmgaahnrtrkcaenkdchssqgaahjjjonfbfnkdaaemlfaaaaadlngcingbaadkmfaabcdholeelmfaaadjmgbaabfnogbimicaaacjmgbaagnnjcajlfaaaadkmfbbhmmifaalleaabhnqsliglqslmghmicaabdgjigecehiihdeecaaaaaaaaaaaaaaaaaa")

    /** The number before the « /12 », by slide. */
    val NOMBRES: Map<Int, String> = mapOf(
        1 to "aaaaaaaaaaaaaaaaaaabbbaaaaaaaaaaaaaedeaaaaabcbaabaaaaaaaadfomhbaaaaaabaabkjtpgaaabaaaabaaacjmhbacaabbaabaabhoibaabbaaaaaaadjnhbaabbaabaadccinibaabdaaeaacabikkbcbaaaaaaaafnknmkhaaaaaaaaadhiihfiaaaaaaaaaaaaaabe", // 2
        2 to "aaaaaaaaaaaaaaaaaaaabbaaaaaaaaaaaaadhfaaabdeddbacaaaaaaabassdqkdaaaaabaacaljeokcaaaaaaaaaaaaemldbaaaaaaaaaabhnkeaaaaaaaaaabhpngbaaaaaaaacfjolkaaaaaaaaaaelokfjadaaaaaaaafossqjidaaaaaaaadghhifgeaaaaaaaaaaaaaabe", // 3
        3 to "aaaaaaaaaaaaaaaaaaaacbaaaaaaaaaaaaadhfaaabceedbabaaaaaaacdlstqldaaaaabaaddhiqokcaaaaaaaaaaadpokcbaaaaaaaabghsqhbaaaaaaaaaabelolgaaaaaaabdcaaeklhaaaaaaabfjiginmjaaaaaaaaelkklpkhaaaaaaaabehhgfehaaaaaaaaaaaaaabe", // 3
        4 to "aabaaaaaabbaaabaaaaabcaabaabaabbaaadeeaababbcdbacabbabbaaacjpoldaabbabcaaahoqpjbaccabababfjosogabbbbaabbcjnkpmicbcbccaaeflheoojdcbbbaaceftmrusokadaaaaadbffgmqkibaabaabaaaabgpjgaabaaaaaaaaaeffhaaaaaaaaaaaaaabe", // 2
        7 to "bbaaaaabaabaaaaaaaabbbbaaaabaaaaaaadfeaabddefdcbaaabaaabhrqtnurkaaaaabbaaaaaenkdbaaaabaaaaacipibaaababaacaaellgaaaaaaaaaaaclmjeaaaaaaaabcegmmdaaaaaaaaaabdknhdbcaaabaaabbdlnhbafbbaaaaaaacefbaahabaaabaababbabbd", // 2
        8 to "aaaaaaaaaaaaaaaaaaaaccaaaaaaaabaaabdhfaaabbeeccabaaaaaaacjpttrkcaaaabbbbfonmqnmeaaaaaaabfnmkolmdbaaaaaaabiqqtqicaaaaaaaaemonqqngaaaaaaacinjeijniaaaaaaaagoojkkplaaabaaaacholoonjaaaaaaaabcgihffiaaaaaaaaaaaaaacd", // 3
        9 to "aaaaaaaaaaaaaaaaaaabcbaaaaaaaaaaaaadheaaabceecbabaaaaaaacjqptpjcaaaaabbbhomiknldaaaaaaabinhfbhngbaaaaaabgonjhkpjaaaaaaaackrsosqkaaaaaaabdedhjnkgaaaaaaaaaabhlnkhaaaaaaaabfmqqlffaaaaaaaaaehhfdcfaaaaaaaaaaaaaabe", // 5
        10 to "aaaaaaaaaaaaaaaaaabbbaacaaaaaaaaaafjgcaaaacdddbaabemokeaahrjhqibbfltsmcadnngfokbaabdnldbfokdchniaaagolecgojcbgojaaafnkedimhcbfnlaaafmlfejniediojaaackmfcdlojjlnkachmrsjicdmqpplibcdfjoebdbelfgefaaaaaaaababaabbd", // 6
        11 to "aaaaaaaaaaaaaaaaaaacbaaaaaaaaaaaaadjhcaaaaabcbaaaadlpmeaadinpkcaadmstoeaajrsqhbaaaadlmfaaacinhbaaaaenmfaaacgmicaaaadmmeaaacjpjcaaaadmlfdbabjngbaaaadmmeaaacingccacjpsvfhcdiisgkiabfiihfcadfihhgiaaaaaaaaaaaaaabe", // 3
        12 to "aaaabaaabaaaaacababccaaacbabaaacbaeiheaaabcdefeabbcgpsdafbbterldbdbvtlcadbbjeojdabacmndaaaaaenleabafnkgbaaaagpkeabadpkcbaabhpmgcbbadmlhdccgnlgbbbaadlmfddfmohbabbeiovsfigkqrhannbdeghjfcefhihahlbaaaaaaabaaaaabe", // 1
    )

    /** Series 34's « /12 »: [BL]×[BH] pixels; its number's place [ZL] wide. */
    const val BL = 20
    const val BH = 13
    const val ZL = 16

    /** Series 34 (October 2026): white, bold, « СЕРИЯ 34 / SLIDE n/12 / 2026 » between NA1SS and RS0ISS. */
    val SERIE_34 = Style("34", BARRE, BL, BH, ZL, NOMBRES.mapValues { motif(it.value) }, 11, 34, motif(
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabegggfecaaaaabefdbaabfmplkoohbaaabformfaabekkggkpngbabgnssldaaaaaaabfmngbaemprqkdaaaaaacgnpleadlomlnldaaaabdgkpnhadlqlghnmeaaaaabcgmpmijnlfciokdaaaaaaacimnllpqossutnfaaaaaabhmnidcdefkpmgbabfkjfgmplfaaaabholeaabfoomoqngbaaaacjpkda"),
        0, -18, 21)

    /** Series 33's numbers seen (3–4 October 2026), by slide. */
    private val NOMBRES_33: Map<Int, String> = mapOf(
        2 to "aaaaaaaaaaabaaaaaaaaaaaaaaaaaaaagadecaaabdghfdaajaeebaaabfjkkhdcdaabaaaacefehifdgedcbaaaaaadghddlllhcaabaabfiebdhggaaaaaacgifbbdjhhcaaaadhjgdcdfnonkdaaaejonmlli", // 2
        3 to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaagaafbaaaadfggebajaafbaaabejkkiebdaaaaaaaadeehjgcgeddbaaaaacfiheclkkhdaaaaadjkhedhaaaaaaaaabcfjhgicbbaaabeiggijjimonidaaadhmmkhgh", // 2
        4 to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaagcafbaaaaaacedaajcafbaaaaachkhcadaaaaaaaachjkhcageddaaaacghhhfbcmkkhcaabfiihihedjaddaaabejllnligkbfeaaaaaaadhgffnnnidaaaaaadhgfg", // 3
        5 to "baaaaaaaaaaaaaaaabbaaaaaaaaaaaaahhifcbaacdhhgfdbijidcbaacfklifdccaabaaaachjgecbbgdddbaaabfilkgcdlkihcaaaaaccgihfdeaaaaababdbfhhgefcbaaabdghgikiinnmjdaaachmlkhgh", // 3
        6 to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaahdefbaaaabdfgecajdeebaaaacikifcadaaaaaaaciiebaaagedcbaabfiifdcbcllkhdaabejllljfeiaadaaaaeihefiihjbbeaaaaehhghjjinnnidaaabekmkjih", // 5
        7 to "aaaaaaaaaaaabbbaaaecaaaaaaaaaaaahfhfcaaaceghhgdbifhecaaabehijkhdcaaaaaaaaabdggecfdccaaaaabcegfddkihebbaaaabegecdgaaaaaaaaadggdbdhcbcaaaaabghdbbgmnnjeaaabehfbbdh", // 6
        8 to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaahccfbaaaacfgfecajccebaaacfkkkjebdaaaaaaadiighjhcheddaaaacgjijhddmkkidaaacfjjjieegaaaaaabdhhcdhighbbcaaabfjighjjinnnicaaabgmlkjhh", // 6
        9 to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaahhgecaaaacfhfcaaihhebaaacgjjjieadaaaaaabfkhegjhcgeedaaabeiihijgelkjhdaaabeiklkgfcaaaaaaaaaabfihffabbaaaaaadhjjggpppmiffghlonmjjl", // 1
        10 to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbedaaaeggecaaaadjkgbacjkkjebaabejkfbagifeiidaaabdiecdhgcciidaaabdhecdhhcchifaaaadhfcbhidcgjgaaaaejgdbeiihijiaabdlnmkfdhmligg", // 1
    )

    /**
     * Series 33's numbers not received (1, 11, 12) put together from the
     * others: the « 1 » of 10 moved to the units' place, the units of 2, the
     * end of « SLIDE » and its space before a single figure (from 7).
     */
    private fun composes33(n: Map<Int, FloatArray>): Map<Int, FloatArray> {
        val w = 16; val dix = n.getValue(10); val deux = n.getValue(2); val sept = n.getValue(7)
        fun fait(gauche: FloatArray, droite: (Int, Int) -> Float) = FloatArray(dix.size) { i ->
            val r = i / w; val c = i % w
            if (c < 10) gauche[i] else droite(r, c)
        }
        val un = { r: Int, c: Int -> dix[r * w + c - 6] }
        return n + mapOf(1 to fait(sept, un), 11 to fait(dix, un), 12 to fait(dix) { r, c -> deux[r * w + c] })
    }

    /** Series 33 (October 2026): yellow, thin, « СЕРИЯ 33 / SLIDE n/12 », the second line cut by the picture's bottom. */
    val SERIE_33 = Style("33", motif(
        "aaaaaaaaaaaaaaaaaaabaacecaaaaaaaaaaaaaaaaabfhdabdecaadfggebaaaacggcbejjfbbfkjkidaaaadgfbafkjfbadeegifbaaaggcaabehfbaaabfheaaaaggcaabehebaacehgcaaaageaaabdhfbacgigcaaaaagdaaacfigddgjhdbbaaaafbaadimnmkijlnmmicaaa"),
        21, 10, 16, composes33(NOMBRES_33.mapValues { motif(it.value) }), 6, 33, motif(
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaacehhhfcaacfhhgdbaaaadhkjjifcbfjiijiebaaabdeddiiebcedcgjgbaaaaaabdiiebbccbfjgcaaaaabfjkidbdgefjjfbaaaaachlkgcaacfkljfbaaaaaabdiiebabccgjgcaaaabbbcgjfbabbceihcaa"),
        0, -17, 20, 5)

    /** Each series' way, likest first. */
    val STYLES: List<Style> = listOf(SERIE_34, SERIE_33)
}
