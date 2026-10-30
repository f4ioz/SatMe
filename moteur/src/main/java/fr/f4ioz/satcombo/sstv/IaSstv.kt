/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

/**
 * A learnt denoiser, run on the station's own processor (SatMe-PC: SCUNet
 * through ONNX Runtime, downloaded when asked). Unlike [CouleurSstv] and
 * [CorrectionSstv] it does draw what it believes was there: always shown
 * as « IA », never on unless the owner says so (picture by picture, or
 * « Débruitage IA automatique » in SSTV › Réglages), never in the place of
 * the classic corrections.
 */
interface DebruiteurIa {
    /** Its name as said under a picture and in the sidecars (« SCUNet real-PSNR »). */
    val nom: String
    /** A short id kept in the sidecars: a new model makes the pictures again. */
    val id: String

    /**
     * One tile: [rgb] planar R, G, B in 0..1 ([w]×[h], both a multiple of
     * [IaSstv.MULTIPLE]); the same back, denoised.
     */
    fun tuile(rgb: FloatArray, w: Int, h: Int): FloatArray
}

/**
 * What every model needs around it: the picture cut in tiles the model
 * takes (sides a multiple of 64, padded by mirroring), the tiles
 * overlapping and blended back, the alpha dropped.
 */
object IaSstv {

    /** SCUNet's windows: the sides a multiple of this. */
    const val MULTIPLE = 64
    /**
     * A tile's side at most: every SSTV picture up to PD 120's 640×496 is one
     * tile (SCUNet: about 2.5 s at 320×256, 8 s at 640×512 on six threads; cut
     * in 320 tiles, PD 120 took 20).
     */
    const val TUILE = 640
    /** Overlap between tiles, blended. */
    const val RECOUVREMENT = 32

    /**
     * How the denoiser is run (SSTV › Réglages › Couleurs et IA):
     *  - [force]: 0 to 100 %, the denoised picture blended over the one
     *    given (0: the picture as given, the model not even run);
     *  - [tuile]: a tile's side at most (a multiple of [MULTIPLE], 128 to
     *    1024): smaller takes less memory, more tiles and time;
     *  - [recouvrement]: the overlap between tiles (8 to a quarter of a tile);
     *  - [avantCouleur]: the IA before the colour correction (its measures
     *    then taken on the denoised picture), else after (the default).
     * [borne] keeps each in its range.
     */
    data class Reglages(
        val force: Int = 100,
        val tuile: Int = TUILE,
        val recouvrement: Int = RECOUVREMENT,
        val avantCouleur: Boolean = false,
    ) {
        fun borne(): Reglages {
            val t = (tuile.coerceIn(TUILE_MIN, TUILE_MAX) / MULTIPLE) * MULTIPLE
            return Reglages(force.coerceIn(0, 100), t, recouvrement.coerceIn(RECOUVREMENT_MIN, t / 4), avantCouleur)
        }

        /** What the sidecars keep: another code makes the pictures again (the threads are not in it: the same picture). */
        fun code(): String = "f" + force + "t" + tuile + "r" + recouvrement + (if (avantCouleur) "a" else "p")
    }

    const val TUILE_MIN = 128
    const val TUILE_MAX = 1024
    const val RECOUVREMENT_MIN = 8

    private fun arrondi(v: Int) = (v + MULTIPLE - 1) / MULTIPLE * MULTIPLE

    /**
     * [px] ARGB [w]×[h] denoised by [m], tile by tile, as [reglages] say
     * (kept in their ranges); the same size back.
     */
    fun applique(m: DebruiteurIa, px: IntArray, w: Int, h: Int, reglages: Reglages = Reglages()): IntArray {
        val r = reglages.borne()
        if (r.force == 0) return px.copyOf()
        val tuile = r.tuile; val recouvrement = r.recouvrement
        val acc = FloatArray(3 * w * h)
        val poids = FloatArray(w * h)
        val pasX = if (w <= tuile) w else tuile - recouvrement
        val pasY = if (h <= tuile) h else tuile - recouvrement
        var y0 = 0
        while (true) {
            val th = if (h <= tuile) h else minOf(tuile, h - y0)
            var x0 = 0
            while (true) {
                val tw = if (w <= tuile) w else minOf(tuile, w - x0)
                val pw = arrondi(tw); val ph = arrondi(th)
                val entree = FloatArray(3 * pw * ph)
                for (y in 0 until ph) for (x in 0 until pw) {
                    val c = px[(y0 + miroir(y, th)) * w + x0 + miroir(x, tw)]
                    val i = y * pw + x
                    entree[i] = ((c shr 16) and 0xFF) / 255f
                    entree[pw * ph + i] = ((c shr 8) and 0xFF) / 255f
                    entree[2 * pw * ph + i] = (c and 0xFF) / 255f
                }
                val sortie = m.tuile(entree, pw, ph)
                require(sortie.size == entree.size) { "tile ${pw}x$ph: ${sortie.size} values back" }
                for (y in 0 until th) for (x in 0 until tw) {
                    // A tile's border counts less where another tile covers it.
                    val bord = minOf(if (x0 > 0) x + 1 else recouvrement, if (x0 + tw < w) tw - x else recouvrement,
                        if (y0 > 0) y + 1 else recouvrement, if (y0 + th < h) th - y else recouvrement)
                    val p = bord.coerceIn(1, recouvrement).toFloat()
                    val i = y * pw + x; val o = (y0 + y) * w + x0 + x
                    for (k in 0..2) acc[k * w * h + o] += sortie[k * pw * ph + i] * p
                    poids[o] += p
                }
                if (x0 + tw >= w) break
                x0 = minOf(x0 + pasX, w - tuile)
            }
            if (y0 + th >= h) break
            y0 = minOf(y0 + pasY, h - tuile)
        }
        // Blended over the picture given at [Reglages.force].
        val f = r.force / 100f
        return IntArray(w * h) { o ->
            val e = px[o]
            fun c(k: Int): Int {
                val d = acc[k * w * h + o] / poids[o] * 255f
                if (d.isNaN()) return 0
                val avant = ((e shr (16 - 8 * k)) and 0xFF).toFloat()
                return Math.round(avant + (d - avant) * f).coerceIn(0, 255)
            }
            (0xFF shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
        }
    }

    /** Index [i] of a side [n] long, mirrored beyond its end. */
    private fun miroir(i: Int, n: Int): Int {
        if (n <= 1) return 0
        var k = i
        val p = 2 * (n - 1)
        k %= p
        return if (k < n) k else p - k
    }
}
