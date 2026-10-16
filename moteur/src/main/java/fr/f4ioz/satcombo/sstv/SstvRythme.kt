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
 * The pace a picture really arrived at, found again in its own sound: for
 * the video and the GIF of it painting itself (phone and PC alike).
 */
object SstvRythme {

    /**
     * Where the picture stands along its sound: (samples, lines done), found
     * by decoding the sound again. Empty when nothing decodes.
     */
    fun calendrier(pcm: ShortArray, rate: Int): List<Pair<Long, Int>> {
        val jalons = ArrayList<Pair<Long, Int>>()
        var pos = 0L
        val d = SstvDecoder(rate, object : SstvDecoder.Listener {
            override fun onProgress(mode: SstvMode, pixels: IntArray, linesDone: Int) {
                jalons += pos to linesDone
            }
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                jalons += pos to linesDone
            }
        })
        d.continuous = true
        val bloc = ShortArray(1024)
        var i = 0
        while (i < pcm.size) {
            val n = minOf(bloc.size, pcm.size - i)
            System.arraycopy(pcm, i, bloc, 0, n)
            i += n; pos = i.toLong()
            d.feed(bloc, n)
        }
        runCatching { d.finish() }
        // Only the first picture of the sound, its lines growing.
        val premier = ArrayList<Pair<Long, Int>>()
        for (j in jalons) { if (premier.isNotEmpty() && j.second < premier.last().second) break; premier += j }
        return premier
    }

    /** Lines shown [echantillon] samples into a sound of [total] samples. */
    fun lignesA(cal: List<Pair<Long, Int>>, echantillon: Long, total: Long, hauteur: Int): Int {
        if (cal.isEmpty()) {
            // Nothing decoded: a steady progression over the sound.
            return if (total <= 0) hauteur else (echantillon * hauteur / total).toInt().coerceIn(0, hauteur)
        }
        var l = 0
        for ((p, n) in cal) { if (p <= echantillon) l = n else break }
        return l.coerceIn(0, hauteur)
    }

    /** Rows revealed at each frame of the GIF: [images] steps, as the picture really arrived. */
    fun etapesGif(cal: List<Pair<Long, Int>>, total: Long, hauteur: Int, images: Int = 60): List<Int> =
        (1..images).map { i -> if (i == images) hauteur else lignesA(cal, total * i / images, total, hauteur) }

    /**
     * The GIF of a picture arriving, from its frames already drawn: [vide]
     * (the frame without the picture) and [pleine] (with it), [w] wide, the
     * picture in the top [hImage] rows; [hauteur] its own height in lines.
     * Comes in about [secondes] s, holds 4 s, loops.
     */
    fun ecritGif(o: java.io.OutputStream, pleine: IntArray, vide: IntArray, w: Int, h: Int, hImage: Int, hauteur: Int,
                 etapes: List<Int>, secondes: Int = 12, progres: (Float) -> Unit = {}) {
        val pal = Gif.palette(pleine, listOf(vide[0], 0xEBEBEB))
        val delai = (secondes * 100 / etapes.size).coerceAtLeast(2)
        val trame = vide.copyOf()
        val g = Gif.Ecrivain(o, w, h, pal)
        g.trame(vide, 0, h, delai)
        var avant = 0
        etapes.forEachIndexed { i, lignes ->
            val coupe = hImage * lignes / hauteur
            if (coupe > avant || i == etapes.lastIndex) {
                System.arraycopy(pleine, avant * w, trame, avant * w, (coupe - avant) * w)
                // A bright line where the picture is being drawn, covered by the next rows.
                val fin = if (coupe < hImage) minOf(coupe + 2, hImage) else coupe
                for (y in coupe until fin) java.util.Arrays.fill(trame, y * w, y * w + w, 0xFFEBEBEB.toInt())
                g.trame(trame, avant, maxOf(fin, avant + 1), if (i == etapes.lastIndex) 400 else delai)
                avant = coupe
            }
            progres((i + 1f) / etapes.size)
        }
        g.fin()
    }
}
