/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

/**
 * The pictures made from the channels, as ARGB pixels (no Android here, so
 * that it can be tried on a computer).
 *
 * - **Colour** (by day): visible channel 2 in red and green, channel 1 in blue
 *   — land green-brown, sea dark blue, clouds and snow white.
 * - **Infrared**: day and night, clouds bright.
 *
 * Contrast: each channel stretched between its 1st and 99.5th percentiles,
 * over the whole pass (black gaps left out). A scan missing in one channel
 * only is filled from its neighbours; a gap of several stays black.
 *
 * North up: the satellite scans in the direction it flies; a pass going
 * north ([montant]) is turned half round.
 */
object MeteorImage {

    const val VISIBLE_1 = 64
    const val VISIBLE_2 = 65

    /** Infrared channels, preferred first. */
    val INFRAROUGES = listOf(68, 67, 69, 66)

    class Image(val largeur: Int, val hauteur: Int, val pixels: IntArray)

    /** Which channels this pass has (with some content). */
    fun canaux(msu: MsuMr): Set<Int> = msu.canaux.filterValues { it.balayages.size >= 3 }.keys

    fun aCouleur(msu: MsuMr): Boolean = canaux(msu).containsAll(listOf(VISIBLE_1, VISIBLE_2))
    fun infrarouge(msu: MsuMr): Int? = INFRAROUGES.firstOrNull { it in canaux(msu) }

    /** [pas]: one pixel in [pas] each way (previews). */
    fun couleur(msu: MsuMr, montant: Boolean, pas: Int = 1): Image? {
        if (!aCouleur(msu)) return null
        val v1 = canal(msu, VISIBLE_1) ?: return null
        val v2 = canal(msu, VISIBLE_2) ?: return null
        val n1 = niveaux(v1.second); val n2 = niveaux(v2.second)
        return construit(v1.first, pas, montant) { i ->
            // A channel missing here (a scan only one of them brought): black, not a false colour.
            if (v1.second[i].toInt() == 0 || v2.second[i].toInt() == 0) return@construit 0xFF shl 24
            val r = etire(v2.second[i], n2)
            val b = etire(v1.second[i], n1)
            (0xFF shl 24) or (r shl 16) or (r shl 8) or b
        }
    }

    fun gris(msu: MsuMr, apid: Int, montant: Boolean, pas: Int = 1): Image? {
        val c = canal(msu, apid) ?: return null
        val n = niveaux(c.second)
        return construit(c.first, pas, montant) { i ->
            val g = etire(c.second[i], n)
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    /** The best picture there is: colour, else infrared, else any channel. */
    fun meilleure(msu: MsuMr, montant: Boolean, pas: Int = 1): Image? =
        couleur(msu, montant, pas)
            ?: infrarouge(msu)?.let { gris(msu, it, montant, pas) }
            ?: canaux(msu).firstOrNull()?.let { gris(msu, it, montant, pas) }

    /** The picture, one pixel per [pas] × [pas] block (their average: no speckle on a reduced view). */
    private fun construit(hauteur: Int, pas: Int, montant: Boolean, px: (Int) -> Int): Image {
        val l = MsuMr.LARGEUR
        val w = (l + pas - 1) / pas
        val h = (hauteur + pas - 1) / pas
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in 0 until pas) {
                val sy = y * pas + dy
                if (sy >= hauteur) break
                for (dx in 0 until pas) {
                    val sx = x * pas + dx
                    if (sx >= l) break
                    val v = px(sy * l + sx)
                    r += (v ushr 16) and 0xFF; g += (v ushr 8) and 0xFF; b += v and 0xFF; n++
                }
            }
            val v = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            val dst = if (montant) (h - 1 - y) * w + (w - 1 - x) else y * w + x
            out[dst] = v
        }
        return Image(w, h, out)
    }

    /** A channel's pixels, isolated missing scans filled in. */
    private fun canal(msu: MsuMr, apid: Int): Pair<Int, ByteArray>? {
        val (h, px) = msu.image(apid) ?: return null
        val l = MsuMr.LARGEUR
        val scans = h / 8
        fun vide(s: Int): Boolean {
            val o = s * 8 * l
            for (i in 0 until 8 * l step 97) if (px[o + i].toInt() != 0) return false
            return true
        }
        for (s in 1 until scans - 1) {
            if (vide(s) && !vide(s - 1) && !vide(s + 1)) {
                val o = s * 8 * l
                val haut = (s - 1) * 8 * l + 7 * l
                val bas = (s + 1) * 8 * l
                for (y in 0 until 8) for (x in 0 until l) {
                    val a = px[haut + x].toInt() and 0xFF
                    val b = px[bas + x].toInt() and 0xFF
                    px[o + y * l + x] = ((a * (8 - y) + b * (y + 1)) / 9).toByte()
                }
            }
        }
        return h to px
    }

    /** 1st and 99.5th percentiles of the non-black pixels. */
    fun niveaux(px: ByteArray): IntArray {
        val hist = LongArray(256)
        for (b in px) hist[b.toInt() and 0xFF]++
        hist[0] = 0
        val total = hist.sum()
        if (total == 0L) return intArrayOf(0, 255)
        fun centile(p: Double): Int {
            var acc = 0L
            val cible = (total * p).toLong()
            for (v in 0 until 256) { acc += hist[v]; if (acc >= cible) return v }
            return 255
        }
        val lo = centile(0.01); val hi = centile(0.995)
        return intArrayOf(lo, if (hi > lo) hi else lo + 1)
    }

    private fun etire(b: Byte, n: IntArray): Int {
        val v = b.toInt() and 0xFF
        if (v == 0) return 0
        return ((v - n[0]) * 255 / (n[1] - n[0])).coerceIn(0, 255)
    }
}
