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

/**
 * « Réunir »: one picture sent, received as two partial ones — its sync lost
 * in the middle (a fade, a jump of the sound), the decoder gave the top up
 * and started a new picture on the bottom when the pulses came back (UmKA-1,
 * 27/10/2026: 151 rows, then 74 more 21 s later). Put back together:
 *  - their sound (each picture's own `.wav`, which overlap: one sound, cut
 *    in two — or the pass recording) as one, decoded again the whole of it
 *    at once ([RedecodageSstv]: one comb of sync pulses over both halves,
 *    a jump of the sound re-placed): the rows land where their time puts
 *    them, the faded stretch read as well as the whole sound allows;
 *  - each partial picture placed on it (by its rows, never trusted blind),
 *    and each row taken from whichever reads cleaner ([FusionSstv.qualiteLignes]);
 *  - without a sound, placed by their timing ([SstvMeta.SstvShot.ligne0Ms]),
 *    the rows in between grey and marked lost.
 * The partial pictures stay as received (hidden while the one put together
 * is there, see [SstvMeta.SstvShot.reunieDans]).
 *
 * Pure Kotlin: pixels and samples in, pixels out.
 */
object ReunionSstv {

    /** How long after a partial picture another may still be its end: one picture, plus the decoder's patience (8 s) and a margin, ms. */
    fun ecartMaxMs(m: SstvMode): Long = (m.frameSeconds * 1000).toLong() + 8_000L + 6_000L

    /**
     * [b] (received after [a]) may be the rest of [a]'s picture: the same
     * satellite and mode, both partial, neither already put together, and
     * [b] ended within a picture's length (and the decoder's patience) of [a].
     */
    fun peutReunir(a: SstvMeta.SstvShot, b: SstvMeta.SstvShot): Boolean {
        val m = SstvMode.byName(a.mode) ?: return false
        if (a.mode != b.mode || a.satName != b.satName || a.complete || b.complete) return false
        if (a.reunieDans.isNotEmpty() || b.reunieDans.isNotEmpty() || a.source != b.source) return false
        if (a.fileName == b.fileName) return false
        val dt = b.timeMs - a.timeMs
        return dt in 1..ecartMaxMs(m)
    }

    /**
     * The pairs to put together in [shots]: two partial pictures one after
     * the other on the same satellite ([peutReunir]), no other picture of it
     * in between. A picture in one pair only (the earliest).
     */
    fun paires(shots: List<SstvMeta.SstvShot>): List<Pair<SstvMeta.SstvShot, SstvMeta.SstvShot>> {
        val out = ArrayList<Pair<SstvMeta.SstvShot, SstvMeta.SstvShot>>()
        val pris = HashSet<String>()
        // By source too: a picture decoded again from its sound is a copy, not one in between.
        for ((_, l) in shots.filter { it.reunieDans.isEmpty() }.groupBy { it.satName + "\u0000" + it.source }) {
            val t = l.sortedBy { it.timeMs }
            for (i in 0 until t.size - 1) {
                val a = t[i]; val b = t[i + 1]
                if (a.fileName in pris || b.fileName in pris || !peutReunir(a, b)) continue
                out += a to b; pris += a.fileName; pris += b.fileName
            }
        }
        return out.sortedBy { it.first.timeMs }
    }

    /** The other half of picture [nom] in [shots], when it has one. */
    fun partenaire(shots: List<SstvMeta.SstvShot>, nom: String): SstvMeta.SstvShot? =
        paires(shots).firstNotNullOfOrNull { (a, b) -> if (a.fileName == nom) b else if (b.fileName == nom) a else null }

    // ------------------------------------------------------------ the sound

    /** A sound: its samples, its rate and when its first sample was received (UTC ms). */
    class Son(val pcm: ShortArray, val rate: Int, val debutMs: Long)

    /**
     * Picture [s]'s own sound ([pcm] at [rate]) placed in time: it ends half
     * a second after the picture was handed over ([SstvMeta.SstvShot.timeMs]).
     */
    fun sonDe(s: SstvMeta.SstvShot, pcm: ShortArray, rate: Int): Son =
        Son(pcm, rate, s.timeMs + 500L - pcm.size * 1000L / rate)

    /**
     * Two sounds as one, [b] after [a] (the same rate). Two pictures' own
     * sounds come from the same memory: where they overlap the samples are
     * the same, found exactly (their times, read on the clock when each
     * picture was handed over, are seconds apart from the sound's); else [b] goes where its time puts it, the gap between
     * filled with a faint hiss.
     */
    fun colle(a: Son, b: Son): Son {
        require(a.rate == b.rate)
        val r = a.rate
        val estime = ((b.debutMs - a.debutMs) * r / 1000L).toInt()
        val o = recouvrement(a.pcm, b.pcm, estime, 10 * r) ?: estime
        // [b] begun before [a] (its own sound starting earlier): [a] from [b]'s start.
        val d0 = minOf(0, o)
        val n = maxOf(a.pcm.size, o + b.pcm.size) - d0
        val out = ShortArray(n)
        System.arraycopy(a.pcm, 0, out, -d0, a.pcm.size)
        if (o > a.pcm.size) {
            val h = java.util.Random(o.toLong())
            for (i in a.pcm.size until o) out[i] = (h.nextGaussian() * 40).toInt().toShort()
        }
        // Where both have it, [a]'s (the same samples when they overlap); [b] beyond.
        for (i in 0 until b.pcm.size) { val j = o - d0 + i; if (j - (-d0) >= a.pcm.size || j < -d0) out[j] = b.pcm[i] }
        return Son(out, r, a.debutMs + d0 * 1000L / r)
    }

    /**
     * Where [b]'s first samples are found again exactly in [a] (at least
     * [MIN_RECOUVRE] of them, not silence), within [rayon] of [estime]; null
     * when nowhere.
     */
    private fun recouvrement(a: ShortArray, b: ShortArray, estime: Int, rayon: Int): Int? {
        if (b.size < MIN_RECOUVRE || a.size < MIN_RECOUVRE) return null
        // Silence matches anywhere: [b]'s start must have some sound in it.
        if ((0 until MIN_RECOUVRE).count { abs(b[it].toInt()) > 200 } < MIN_RECOUVRE / 4) return null
        var best: Int? = null; var bestD = Double.MAX_VALUE
        for (o in maxOf(0, estime - rayon)..minOf(a.size - MIN_RECOUVRE, estime + rayon)) {
            val l = minOf(4000, a.size - o, b.size)
            var d = 0L; var i = 0; var k = 0
            val seuil = bestD * (l / 7 + 1)
            while (i < l && d < seuil) { d += abs(a[o + i] - b[i]); i += 7; k++ }
            if (i < l) continue
            val m = d / k.toDouble()
            if (m < bestD) { bestD = m; best = o }
        }
        // Equal, give or take the rounding of a mean of two samples.
        return best?.takeIf { bestD <= 2.0 }
    }

    /** Samples two sounds must share to be found overlapping. */
    private const val MIN_RECOUVRE = 600

    // ------------------------------------------------------------ the pictures

    /** A partial picture: its ARGB pixels ([mode] wide), its rows received, those it already had lost ([SstvMeta.SstvShot.trous]). */
    class Morceau(val px: IntArray, val lignes: Int, val trous: List<IntRange> = emptyList())

    class Resultat(
        val px: IntArray, val w: Int, val h: Int,
        /** Rows from the top with something in them. */
        val lignes: Int,
        val complete: Boolean,
        /** Where each partial picture's row 0 went. */
        val placeA: Int, val placeB: Int,
        /** Rows lost: nothing read there (grey), or taken from a partial picture that had lost them. */
        val trous: List<IntRange>,
        /** Rows taken from the sound decoded again, from the first picture, from the second. */
        val duSon: Int, val deA: Int, val deB: Int,
        /** The sound decoded again (null: none, or nothing found in it). */
        val redecode: RedecodageSstv.Resultat?,
    )

    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /**
     * Where [c] ([lc] rows) sits on [ref] ([lr] rows), [w] wide: its row y is
     * the reference's y + dy, dy in [dys]; compared on brightness over rows
     * clean in both. Null when no shift fits (gap > [ECART_MAX]).
     */
    fun place(ref: IntArray, lr: Int, qr: FloatArray, c: IntArray, lc: Int, qc: FloatArray, w: Int, dys: IntRange): Int? {
        var best: Int? = null; var bestE = Float.MAX_VALUE
        val pas = 4
        for (dy in dys) {
            var s = 0L; var m = 0; var rangs = 0
            for (y in 0 until lc) {
                val yr = y + dy
                if (yr !in 0 until lr || qc[y] < 0.5f || qr[yr] < 0.5f) continue
                rangs++
                var x = 8
                while (x < w - 8) { s += abs(lum(ref[yr * w + x]) - lum(c[y * w + x])); m++; x += pas }
            }
            if (rangs < minOf(20, maxOf(6, lc / 3)) || m == 0) continue
            val e = s / m.toFloat()
            if (e < bestE) { bestE = e; best = dy }
        }
        return best?.takeIf { bestE <= ECART_MAX }
    }

    /** Largest mean brightness gap (0–255) at which a partial picture is taken to be where it was placed. */
    const val ECART_MAX = 32f

    /**
     * Partial pictures [a] and [b] (b received after a) of [mode] as one:
     * from [son] (both their sounds as one, see [colle]) decoded again when
     * there is one, each row the cleanest of what the sound and the two
     * pictures have there; [decalage]: where [b]'s row 0 goes by timing
     * (null: unknown), used when the sound does not place it. Null when
     * [b] cannot be placed at all.
     */
    fun reunit(mode: SstvMode, a: Morceau, b: Morceau, son: Son?, decalage: Int?): Resultat? {
        val w = mode.width; val h = mode.height
        // The sound decoded again, twice (the usual filter, and a narrower one for the faded stretch): row by row, the cleaner.
        val essais = if (son == null) emptyList() else listOf(RedecodageSstv.Options(), RedecodageSstv.Options(filtreHz = 500.0, etendueMs = 0.6))
            .mapNotNull { o -> runCatching { RedecodageSstv.decode(son.pcm, son.rate, mode, o) }.getOrNull() }
            .filter { it.lignes > maxOf(a.lignes, b.lignes) / 2 }
        val r = essais.maxByOrNull { it.lignes * 1000 + (it.syncs * 999).toInt() }
        var base: IntArray? = null; var lb = 0
        var qb = FloatArray(h)
        if (r != null) {
            base = r.pixels.copyOf(); lb = r.lignes
            qb = FusionSstv.qualiteLignes(FusionSstv.Copie(base, w, h, lb))
            for (o in essais) if (o !== r) {
                val q = FusionSstv.qualiteLignes(FusionSstv.Copie(o.pixels, w, h, o.lignes))
                // The other reading, placed on the first (their headers may differ by a row).
                val d = place(base, lb, qb, o.pixels, o.lignes, q, w, -3..3) ?: continue
                for (y in 0 until o.lignes) {
                    val yb = y + d
                    if (yb !in 0 until h || q[y] <= qb[yb] + 0.05f) continue
                    System.arraycopy(o.pixels, y * w, base, yb * w, w); qb[yb] = q[y]
                    if (yb >= lb) lb = yb + 1
                }
            }
        }
        val qa = FusionSstv.qualiteLignes(FusionSstv.Copie(a.px, w, h, a.lignes))
        val qbB = FusionSstv.qualiteLignes(FusionSstv.Copie(b.px, w, h, b.lignes))
        val placeA = base?.let { place(it, lb, qb, a.px, a.lignes, qa, w, -12..12) } ?: 0
        val prevu = decalage?.coerceIn(0, h - 1)
        val placeB = base?.let { bs ->
            place(bs, lb, qb, b.px, b.lignes, qbB, w, if (prevu != null) (prevu - 20)..(prevu + 20) else 0 until h)
        } ?: prevu ?: return null
        val out = IntArray(w * h) { GRIS }
        val perdue = BooleanArray(h)
        var duSon = 0; var deA = 0; var deB = 0
        var lignes = 0
        for (y in 0 until h) {
            var choix = 0; var meilleure = -1f
            if (base != null && y < lb) { meilleure = qb[y] + BONUS_SON; choix = 1 }
            val ya = y - placeA
            if (ya in 0 until a.lignes && qa[ya] > meilleure) { meilleure = qa[ya]; choix = 2 }
            val yb = y - placeB
            if (yb in 0 until b.lignes && qbB[yb] > meilleure) { meilleure = qbB[yb]; choix = 3 }
            when (choix) {
                1 -> { System.arraycopy(base!!, y * w, out, y * w, w); duSon++ }
                2 -> { System.arraycopy(a.px, ya * w, out, y * w, w); deA++; perdue[y] = a.trous.any { ya in it } }
                3 -> { System.arraycopy(b.px, yb * w, out, y * w, w); deB++; perdue[y] = b.trous.any { yb in it } }
                else -> perdue[y] = true
            }
            if (choix != 0) lignes = y + 1
        }
        // Lost: nothing read there, between rows that were.
        val trous = ArrayList<IntRange>()
        var y = 0
        while (y < lignes) {
            if (!perdue[y]) { y++; continue }
            var e = y
            while (e < lignes && perdue[e]) e++
            trous += y..(e - 1)
            y = e
        }
        return Resultat(out, w, h, lignes, lignes >= h, placeA, placeB, trous, duSon, deA, deB, r)
    }

    /** The sound's reading is preferred by this much: it placed every row knowing every pulse. */
    private const val BONUS_SON = 0.08f
    /** Rows with nothing read. */
    const val GRIS = 0xFF7F7F7F.toInt()

    /** Where [b]'s row 0 goes in [a]'s picture by their timing, rows; null when unknown. */
    fun decalageTemps(mode: SstvMode, a: SstvMeta.SstvShot, b: SstvMeta.SstvShot, ligne0A: Long = a.ligne0Ms, ligne0B: Long = b.ligne0Ms): Int? {
        if (ligne0A <= 0L || ligne0B <= 0L) return null
        return Math.round((ligne0B - ligne0A) / mode.blockMs).toInt() * mode.linesPerBlock
    }
}
