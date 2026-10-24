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
 * The slide's number read on the footer of an ARISS slide (« SLIDE 7/12 »,
 * series 34 of October 2026): no OCR, the letters are 9 pixels high in
 * Robot 36. The « /12 » is looked for at the bottom of the picture (moved
 * up when the picture began late), then the number before it compared with
 * the numbers seen on the real pictures of the series. Patterns made by
 * LectureDiapoApprentissage (tests) from clean pictures read by eye; no
 * pattern for 5 and 6 (not received when they were made): those, like any
 * number not clearly one of the patterns, are not read (0) — the hand or
 * the timetable ([HoraireDiapos]) then says it.
 *
 * Another series may print its footer elsewhere or otherwise: nothing read.
 */
object LectureDiapo {

    /** The « /12 »: [BL]×[BH] pixels. */
    const val BL = 20
    const val BH = 13
    /** The number's place, just left of the « /12 ». */
    const val ZL = 16

    /** Below this the « /12 » is not there. */
    private const val SEUIL_BARRE = 0.6f
    /** A number at least this like its pattern, and this ahead of the next. */
    private const val SEUIL_NOMBRE = 0.905f
    private const val AVANCE = 0.06f

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

    /** Normalised correlation of [t] ([w]×[h]) with [g] at ([x0], [y0]). */
    private fun ncc(g: IntArray, x0: Int, y0: Int, t: FloatArray, w: Int, h: Int): Float {
        var sg = 0.0; var st = 0.0
        val n = w * h
        for (i in 0 until n) { sg += g[(y0 + i / w) * 320 + x0 + i % w]; st += t[i] }
        val mg = sg / n; val mt = st / n
        var sgt = 0.0; var sgg = 0.0; var stt = 0.0
        for (i in 0 until n) {
            val a = g[(y0 + i / w) * 320 + x0 + i % w] - mg; val b = t[i] - mt
            sgt += a * b; sgg += a * a; stt += b * b
        }
        return if (sgg > 0 && stt > 0) (sgt / sqrt(sgg * stt)).toFloat() else 0f
    }

    /** Where [barre] is likest in the lower part of [g]: x, y, correlation; null when nowhere. */
    fun cherche(g: IntArray, barre: FloatArray = BARRE): Triple<Int, Int, Float>? {
        var best: Triple<Int, Int, Float>? = null
        for (y in 120..(240 - BH)) for (x in (ZL + 90)..(240 - BL)) {
            val c = ncc(g, x, y, barre, BL, BH)
            if (best == null || c > best.third) best = Triple(x, y, c)
        }
        return best
    }

    /** The slide read (1..12), 0 when not read. */
    fun lit(px: IntArray, w: Int, h: Int): Int = lit(gris(px, w, h))

    fun lit(g: IntArray, motifs: Map<Int, String> = NOMBRES): Int = lecture(g, motifs)?.let { (n, a, b) ->
        if (a >= SEUIL_NOMBRE && a - b >= AVANCE) n else 0 } ?: 0

    /** The likest number, its likeness and the next one's; null when no « /12 ». */
    fun lecture(g: IntArray, motifs: Map<Int, String> = NOMBRES): Triple<Int, Float, Float>? {
        if (motifs.isEmpty()) return null
        val (x, y, c) = cherche(g) ?: return null
        if (c < SEUIL_BARRE) return null
        val scores = motifs.mapValues { (_, m) ->
            val t = motif(m)
            var best = -1f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x - ZL + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || yy + BH > 240) continue
                best = maxOf(best, ncc(g, xx, yy, t, ZL, BH))
            }
            best
        }.entries.sortedByDescending { it.value }
        val a = scores.getOrNull(0) ?: return null
        return Triple(a.key, a.value, scores.getOrNull(1)?.value ?: -1f)
    }

    private fun motif(s: String) = FloatArray(s.length) { (s[it] - 'a') * 256f / 26 + 5f }

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
}
