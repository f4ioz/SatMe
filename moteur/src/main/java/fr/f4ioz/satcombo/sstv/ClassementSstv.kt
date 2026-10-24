/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The gallery sorted: copies of one slide put together (an ARISS series
 * sends its 12 slides again and again, a week gives dozens of copies), each
 * picture given a mark (0–100) so the best copy of each slide comes first,
 * and the slide's number — set by hand, read on the ARISS footer, or
 * worked out from the time ([HoraireDiapos]).
 *
 * The mark (documented in docs/station-pc*.md), from 100:
 * - rows missing (the picture stopped early, flat rows to the end): their share off;
 * - rows spoilt, each 0..1: specks (a pixel far from its neighbourhood's
 *   median — the speckle of a weak FM signal in Robot 36; a slide's own
 *   letters are wider than a pixel), green bands, a row unlike both its
 *   neighbours (a burst), horizontal streaks; their sum ×1.1 off;
 * - sync slips (the rest of the picture shifted sideways): 4 each, 20 at most;
 * - in its group ([groupe]), a picture begun late (the slide moved up)
 *   loses the share of its top it lacks.
 * Measured on the picture brought to 320×240 (Robot 36's size). Tuned on
 * the 117 pictures of the owner's gallery (ARISS series 33 and 34).
 *
 * Pure Kotlin: the phone and SatMe-PC give the pixels.
 */
object ClassementSstv {

    /** The size pictures are measured at. */
    const val L = 320
    const val H = 240
    /** Thumbnail compared between pictures: [GL]×[GH] grey cells. */
    private const val GL = 32
    private const val GH = 48

    /** What one picture is like. */
    class Analyse(
        /** For the sheet ([Planche]): small grey and its noise. */
        val signature: Planche.Signature,
        /** 0–100. */
        val note: Int,
        /** Share of rows missing at the bottom (picture stopped early). */
        val manque: Float,
        /** Share of rows spoilt by noise. */
        val abimees: Float,
        /** Share of green speckle pixels over the rows received. */
        val vert: Float,
        /** Sideways jumps of the picture (sync slips). */
        val glissements: Int,
        /** Slide read on the footer ("SLIDE n/12"), 0 when not read. */
        val lu: Int,
        /** [GL]×[GH] cells: brightness, red and blue less brightness. */
        internal val vignette: FloatArray,
        /** The thumbnail rows worth comparing (received, not spoilt). */
        internal val bonnes: BooleanArray,
    ) {
        /** Stars 0–5 for a badge. */
        val etoiles: Int get() = ((note + 10) / 20).coerceIn(0, 5)
    }

    /**
     * Measures a picture: [px] ARGB, [w]×[h]; [hauteurMode] the mode's own
     * height (a picture stopped early may be saved shorter), 0 unknown;
     * [complete] as the decoder said.
     */
    fun analyse(px: IntArray, w: Int, h: Int, hauteurMode: Int = 0, complete: Boolean = true): Analyse {
        // Brought to 320×240: the rows the picture has, the rest missing.
        val hm = if (hauteurMode >= h) hauteurMode else h
        val lignes = ((h.toLong() * H + hm / 2) / hm).toInt().coerceIn(1, H)
        val r = IntArray(L * H); val g = IntArray(L * H); val b = IntArray(L * H)
        for (y in 0 until lignes) {
            val sy = (y.toLong() * hm / H).toInt().coerceIn(0, h - 1)
            for (x in 0 until L) {
                val c = px[sy * w + (x.toLong() * w / L).toInt().coerceIn(0, w - 1)]
                val i = y * L + x
                r[i] = (c shr 16) and 0xFF; g[i] = (c shr 8) and 0xFF; b[i] = c and 0xFF
            }
        }
        // Rows of one flat colour to the end: stopped early (the decoder fills them black or grey).
        var recues = lignes
        if (!complete || lignes < H) while (recues > 0 && rangeeVide(r, g, b, recues - 1)) recues--
        val manque = (H - recues) / H.toFloat()

        val lum = IntArray(L * H) { (r[it] * 77 + g[it] * 150 + b[it] * 29) shr 8 }
        // Each row's noise, 0 clean .. 1 nothing left: pixels off their neighbourhood (the speckle
        // of a weak signal; a slide's own edges and letters are wider than one pixel), green bands.
        val bruitRangee = FloatArray(H)
        var vertTotal = 0
        val med = IntArray(9)
        fun median(c: IntArray, x: Int, y: Int): Int {
            var k = 0
            for (dy in -1..1) for (dx in -1..1) med[k++] = c[(y + dy).coerceIn(0, recues - 1) * L + (x + dx).coerceIn(0, L - 1)]
            med.sort(); return med[4]
        }
        for (y in 0 until recues) {
            var v = 0; var imp = 0
            for (x in 0 until L) {
                val i = y * L + x
                if (g[i] > r[i] + 50 && g[i] > b[i] + 50) v++
                val e = abs(r[i] - median(r, x, y)) + abs(g[i] - median(g, x, y)) + abs(b[i] - median(b, x, y))
                if (e > SEUIL_POINT) imp++
            }
            vertTotal += v
            val vr = v / L.toFloat(); val ir = imp / L.toFloat()
            bruitRangee[y] = maxOf(((vr - 0.04f) / 0.20f), ir / POINTS_PLEIN).coerceIn(0f, 1f)
        }
        // A row much unlike both its neighbours: a burst of noise.
        for (y in 1 until recues - 1) {
            var a = 0; var c = 0; var n = 0
            for (x in 0 until L) {
                val i = y * L + x
                a += abs(lum[i] - lum[i - L]); c += abs(lum[i] - lum[i + L]); n += abs(lum[i - L] - lum[i + L])
            }
            val saut = (minOf(a, c) - n / 2f).coerceAtLeast(0f) / L
            if (saut > SEUIL_SAUT) bruitRangee[y] = maxOf(bruitRangee[y], 0.8f)
        }
        // Streaks: rows smeared sideways, each unlike the next (noise drawn along the lines), over 4 rows.
        // Each pair of rows unlike (the median of three: one edge of the slide's own drawing is one pair only).
        for (y0 in 0 until recues - 4 step 2) {
            val vs = FloatArray(3); var hz = 0L
            for ((k, y) in (y0 + 1 until y0 + 4).withIndex()) {
                var v = 0L
                for (x in 1 until L) { val i = y * L + x; v += abs(lum[i] - lum[i - L]); hz += abs(lum[i] - lum[i - 1]) }
                vs[k] = v / (L - 1).toFloat()
            }
            vs.sort()
            val nv = vs[1]
            if (nv > 18f && nv > 2.6f * (hz / (3f * (L - 1)))) for (y in y0 until y0 + 4) bruitRangee[y] = maxOf(bruitRangee[y], ((nv - 18f) / 20f + 0.5f).coerceAtMost(1f))
        }
        val abimee = BooleanArray(H) { y -> y < recues && bruitRangee[y] >= 0.5f }
        val abimees = (0 until recues).sumOf { bruitRangee[it].toDouble() }.toFloat() / H
        val vert = if (recues > 0) vertTotal / (recues * L).toFloat() else 0f
        val glissements = glissements(lum, recues, abimee)

        val penalite = manque * 100f + abimees * 110f + (glissements * 4).coerceAtMost(20)
        val note = (100f - penalite).roundToInt().coerceIn(0, 100)

        // Thumbnail: cells of brightness and colour, from the rows received and clean.
        val vignette = FloatArray(GL * GH * 3); val bonnes = BooleanArray(GH)
        for (cy in 0 until GH) {
            val y0 = cy * H / GH; val y1 = (cy + 1) * H / GH
            var ok = 0
            for (y in y0 until y1) if (y < recues && !abimee[y]) ok++
            bonnes[cy] = ok * 2 > (y1 - y0)
            for (cx in 0 until GL) {
                val x0 = cx * L / GL; val x1 = (cx + 1) * L / GL
                var sy = 0L; var sr = 0L; var sb = 0L; var n = 0
                for (y in y0 until y1) {
                    if (y >= recues || (abimee[y] && ok > 0)) continue
                    for (x in x0 until x1) { val i = y * L + x; sy += lum[i]; sr += r[i] - lum[i]; sb += b[i] - lum[i]; n++ }
                }
                val k = (cy * GL + cx) * 3
                if (n > 0) { vignette[k] = sy / n.toFloat(); vignette[k + 1] = sr / n.toFloat(); vignette[k + 2] = sb / n.toFloat() }
            }
        }
        val sig = Planche.signature(px, w, h)
        return Analyse(sig, note, manque, abimees, vert, glissements, 0, vignette, bonnes)
    }

    private fun rangeeVide(r: IntArray, g: IntArray, b: IntArray, y: Int): Boolean {
        val i0 = y * L
        var n = 0
        for (x in 0 until L) { val i = i0 + x; if (abs(r[i] - r[i0]) + abs(g[i] - g[i0]) + abs(b[i] - b[i0]) > 24) n++ }
        return n < L / 40 && r[i0] + g[i0] + b[i0] < 120
    }

    /**
     * Sideways jumps: blocks of 4 rows compared with the block above, the
     * shift that fits best; a jump when it is not 0 and fits much better,
     * on rows with something to compare (not a plain sky).
     */
    private fun glissements(lum: IntArray, recues: Int, abimee: BooleanArray): Int {
        val bloc = 4; val nb = recues / bloc
        if (nb < 3) return 0
        val profil = Array(nb) { k -> FloatArray(L) { x -> var s = 0; for (y in k * bloc until (k + 1) * bloc) s += lum[y * L + x]; s / bloc.toFloat() } }
        val sale = BooleanArray(nb) { k -> (k * bloc until (k + 1) * bloc).any { abimee[it] } }
        var sauts = 0
        for (k in 1 until nb) {
            if (sale[k] || sale[k - 1]) continue
            val a = profil[k - 1]; val c = profil[k]
            fun ecart(dx: Int): Float {
                var s = 0f; var n = 0
                for (x in 16 until L - 16) { s += abs(c[x] - a[x + dx]); n++ }
                return s / n
            }
            var relief = 0f
            for (x in 1 until L) relief += abs(c[x] - c[x - 1])
            relief /= L
            if (relief < 6f) continue
            val e0 = ecart(0)
            var meilleur = 0; var em = e0
            for (dx in -12..12) { if (dx == 0) continue; val e = ecart(dx); if (e < em) { em = e; meilleur = dx } }
            if (abs(meilleur) >= 3 && em < e0 * 0.45f && e0 > 12f) sauts++
        }
        return sauts
    }

    /** A pixel this far (R+G+B) from its neighbourhood's median is a speck. */
    private const val SEUIL_POINT = 150
    /** This share of specks in a row: nothing left of it. */
    private const val POINTS_PLEIN = 0.45f
    private const val SEUIL_SAUT = 28f

    // ------------------------------------------------------------ alike

    /**
     * How alike two pictures are, 0..1 (above [SEUIL]: one slide): the
     * share of thumbnail cells that match (brightness and colour within
     * [ECART_CASE]) over the rows good in both. A slight shift sideways and
     * any shift up or down are tried (a picture begun late, without its
     * header, is the slide's lower part at the top), at least a third of
     * the picture in common. Null when they have not that much to compare.
     */
    fun ressemblance(a: Analyse, b: Analyse): Float? = accord(a, b)?.first

    /** [ressemblance] and the shift up or down (thumbnail rows, + when [b] is lower) where it is best. */
    fun accord(a: Analyse, b: Analyse): Pair<Float, Int>? {
        var meilleur = -1f; var dyMeilleur = 0
        fun essaie(dy: Int) {
            var communes = 0
            for (y in 0 until GH) { val yb = y + dy; if (yb in 0 until GH && a.bonnes[y] && b.bonnes[yb]) communes++ }
            if (communes < MIN_RANGEES) return
            for (dx in -1..1) {
                var n = 0; var k = 0
                for (y in 0 until GH) {
                    val yb = y + dy
                    if (yb !in 0 until GH || !a.bonnes[y] || !b.bonnes[yb]) continue
                    for (x in maxOf(0, -dx) until minOf(GL, GL - dx)) {
                        val i = (y * GL + x) * 3; val j = (yb * GL + x + dx) * 3
                        // Dark sky in both says nothing (every slide of a series has much of it).
                        if (a.vignette[i] < SOMBRE && b.vignette[j] < SOMBRE) continue
                        val d = abs(a.vignette[i] - b.vignette[j]) + 0.7f * (abs(a.vignette[i + 1] - b.vignette[j + 1]) + abs(a.vignette[i + 2] - b.vignette[j + 2]))
                        n++; if (d <= ECART_CASE) k++
                    }
                }
                if (n > 0) { val r = k / n.toFloat(); if (r > meilleur || (r == meilleur && abs(dy) < abs(dyMeilleur))) { meilleur = r; dyMeilleur = dy } }
            }
        }
        // Every third shift, then round the best.
        for (dy in -GH / 2..GH / 2 step 3) essaie(dy)
        val c = dyMeilleur
        for (dy in c - 2..c + 2) if (dy != c && dy in -GH / 2..GH / 2) essaie(dy)
        return if (meilleur >= 0f) meilleur to dyMeilleur else null
    }

    fun meme(a: Analyse, b: Analyse): Boolean = (ressemblance(a, b) ?: 0f) > SEUIL

    /** Above this [ressemblance], one slide. */
    const val SEUIL = 0.6f
    /** Two cells match within this (brightness, plus colour ×0.7). */
    private const val ECART_CASE = 45f
    private const val SOMBRE = 40f
    /** Thumbnail rows in common, at least (a third of the picture). */
    private const val MIN_RANGEES = GH / 3

    // ------------------------------------------------------------ groups

    /** Where a slide's number comes from. */
    enum class Origine { MAIN, LU, ESTIME, AUCUNE }

    /** Copies of one slide, the best first. */
    data class Groupe(
        /** Stable name: the hand's group, else the earliest picture's name. */
        val id: String,
        val images: List<SstvMeta.SstvShot>,
        /** 1..[total], 0 unknown. */
        val diapo: Int = 0,
        val total: Int = 12,
        val origine: Origine = Origine.AUCUNE,
        /** Each picture's mark in the group (its own, less the top it lacks). */
        val notes: Map<String, Int> = emptyMap(),
    ) {
        val meilleure: SstvMeta.SstvShot get() = images.first()
    }

    /**
     * The pictures put in groups, by satellite: those the hand put in a
     * group ([SstvMeta.SstvShot.groupe]) stay there; the others go with the
     * group they look like most (compared with its best few), else start
     * one. In each group the best mark first; groups by first reception.
     * Pictures not analysed go alone.
     */
    fun groupe(images: List<SstvMeta.SstvShot>, analyses: Map<String, Analyse>): List<Groupe> {
        class G(val id: String, val sat: String, val membres: ArrayList<SstvMeta.SstvShot> = ArrayList())
        val gs = ArrayList<G>()
        val note = { s: SstvMeta.SstvShot -> analyses[s.fileName]?.note ?: -1 }
        for ((k, l) in images.filter { it.groupe.isNotBlank() }.groupBy { normalSat(it.satName) to it.groupe })
            gs += G(k.second, k.first, ArrayList(l))
        // The best pictures placed first: a group is judged on its clean copies.
        val libres = images.filter { it.groupe.isBlank() }.sortedWith(compareByDescending<SstvMeta.SstvShot> { note(it) }.thenBy { it.timeMs })
        for (s in libres) {
            val a = analyses[s.fileName]
            val sat = normalSat(s.satName)
            var choisi: G? = null; var mieux = SEUIL
            if (a != null) for (gr in gs) {
                // A picture put on its own by the hand keeps its group to itself.
                if (gr.sat != sat || gr.id.startsWith("seule-")) continue
                val e = gr.membres.asSequence().mapNotNull { analyses[it.fileName] }.sortedByDescending { it.note }.take(4)
                    .mapNotNull { ressemblance(a, it) }.maxOrNull() ?: continue
                if (e > mieux) { mieux = e; choisi = gr }
            }
            if (choisi != null) choisi.membres += s else gs += G("", sat, arrayListOf(s))
        }
        return gs.map { gr ->
            val notes = notesDuGroupe(gr.membres, analyses)
            val tri = gr.membres.sortedWith(compareByDescending<SstvMeta.SstvShot> { notes[it.fileName] ?: -1 }.thenBy { it.timeMs })
            val (n, tot, o) = numero(tri, analyses)
            Groupe(gr.id.ifBlank { tri.minByOrNull { it.timeMs }!!.fileName }, tri, n, tot, o, notes)
        }.sortedBy { g -> g.images.minOf { it.timeMs } }
    }

    /**
     * Each picture's mark in its group: its own, less what the others show
     * it lacks — a picture begun late (without its header) is the slide
     * moved up, its top lost: the rows it is moved by, off the mark.
     */
    private fun notesDuGroupe(l: List<SstvMeta.SstvShot>, analyses: Map<String, Analyse>): Map<String, Int> {
        val an = l.mapNotNull { s -> analyses[s.fileName]?.let { s.fileName to it } }
        if (an.isEmpty()) return emptyMap()
        val ref = an.maxByOrNull { it.second.note }!!
        val dy = an.associate { (n, a) -> n to (if (n == ref.first) 0 else accord(ref.second, a)?.second ?: 0) }
        // The best one may be the one moved: the shift most of them share is the slide's place.
        val zero = dy.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
        return an.associate { (n, a) ->
            val d = abs((dy[n] ?: 0) - zero)
            n to (a.note - d * 100 / GH).coerceIn(0, 100)
        }
    }

    /** The group's number: the hand's (the most said), else the footer read (twice, or once with none against). */
    private fun numero(l: List<SstvMeta.SstvShot>, analyses: Map<String, Analyse>): Triple<Int, Int, Origine> {
        l.filter { it.diapo > 0 }.groupingBy { it.diapo to (it.diapos.takeIf { t -> t > 0 } ?: 12) }.eachCount().maxByOrNull { it.value }
            ?.let { return Triple(it.key.first, it.key.second, Origine.MAIN) }
        val lus = l.mapNotNull { analyses[it.fileName]?.lu?.takeIf { n -> n > 0 } }.groupingBy { it }.eachCount()
        val top = lus.maxByOrNull { it.value } ?: return Triple(0, 12, Origine.AUCUNE)
        val contre = lus.values.sum() - top.value
        return if (top.value > contre) Triple(top.key, 12, Origine.LU) else Triple(0, 12, Origine.AUCUNE)
    }

    private fun normalSat(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /**
     * Groups not numbered given the number the time says ([HoraireDiapos]),
     * when all its pictures agree within [toleranceS]: an estimate, shown so.
     */
    fun estime(groupes: List<Groupe>, horaire: HoraireDiapos.Modele?, debutDe: (SstvMeta.SstvShot) -> Long,
               toleranceS: Double = 40.0): List<Groupe> {
        if (horaire == null) return groupes
        return groupes.map { g ->
            if (g.origine != Origine.AUCUNE) return@map g
            val n = g.images.map { HoraireDiapos.diapoA(horaire, debutDe(it), toleranceS) }
            val d = n.firstOrNull() ?: 0
            if (d > 0 && n.all { it == d }) g.copy(diapo = d, total = horaire.nb, origine = Origine.ESTIME) else g
        }
    }

    /** When a picture began: its time is when it was saved (the end), less what it lasted. */
    fun debut(s: SstvMeta.SstvShot, a: Analyse? = null): Long {
        val m = SstvMode.byName(s.mode)
        val d = if (m != null) (SstvEncoder.seconds(m) * 1000).toLong() else (HoraireDiapos.DUREE_S * 1000).toLong()
        val part = if (a != null) 1f - a.manque else 1f
        return s.timeMs - (d * part).toLong()
    }

    /**
     * The anchors of the timetable: each picture of a numbered group (by hand
     * or read), whole ones only (the start of a picture stopped early is
     * not sure), the copies decoded again from a recording left out.
     */
    fun reperes(groupes: List<Groupe>, analyses: Map<String, Analyse>): List<HoraireDiapos.Repere> =
        groupes.filter { it.diapo > 0 && (it.origine == Origine.MAIN || it.origine == Origine.LU) }.flatMap { g ->
            g.images.filter { it.complete && it.timeMs > 0 && (analyses[it.fileName]?.manque ?: 0f) < 0.02f }
                .distinctBy { it.timeMs / 20_000L }
                .map { HoraireDiapos.Repere(debut(it, analyses[it.fileName]), g.diapo) }
        }

    /** The best picture of each group (for the sheet): their names. */
    fun meilleures(groupes: List<Groupe>): List<String> = groupes.map { it.meilleure.fileName }

    /**
     * A slide's badge on a thumbnail: « 2/12 » read on the footer, « 2/12✎ »
     * set by hand, « ≈2/12 » estimated from the timetable; "" none.
     */
    fun badge(diapo: Int, total: Int, origine: Origine): String = when {
        diapo <= 0 || origine == Origine.AUCUNE -> ""
        origine == Origine.MAIN -> "$diapo/$total✎"
        origine == Origine.ESTIME -> "≈$diapo/$total"
        else -> "$diapo/$total"
    }

    /** [Origine] as the pages name it: « main », « lu », « estime », "". */
    fun code(o: Origine): String = when (o) { Origine.MAIN -> "main"; Origine.LU -> "lu"; Origine.ESTIME -> "estime"; Origine.AUCUNE -> "" }

    /** [a] with what the footer reading found. */
    fun avecLu(a: Analyse, lu: Int): Analyse =
        Analyse(a.signature, a.note, a.manque, a.abimees, a.vert, a.glissements, lu, a.vignette, a.bonnes)
}
