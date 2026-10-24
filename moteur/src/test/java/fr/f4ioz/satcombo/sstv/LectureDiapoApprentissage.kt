/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * How [LectureDiapo]'s patterns were made (SATME_SSTV_REEL=folder of the
 * series 34 pictures): the « /12 » and each number's place, averaged over
 * the clean pictures read by eye, printed as Kotlin. Skipped without it.
 */
class LectureDiapoApprentissage {

    @Test fun apprend() {
        val d = System.getenv("SATME_SSTV_REEL")?.let(::File)
        assumeTrue(d != null && d.isDirectory && System.getenv("SATME_APPRENDS") != null)
        val vu = ClassementSstvReelTest.VU
        // Bootstrap: the « /12 » of a clean 10/12.
        val g0 = gris(File(d, "SatMe_SSTV_ISS_20261023_092643Z_Robot36.png"))
        var barre = coupe(g0, 174, 219, LectureDiapo.BL, LectureDiapo.BH)
        val l = d!!.listFiles { f -> f.name.endsWith(".png") && f.name.contains("ISS_2026102") }!!.sortedBy { it.name }
        repeat(2) {
            val somme = FloatArray(barre.size); var n = 0
            val zones = HashMap<Int, Pair<FloatArray, Int>>()
            for (f in l) {
                val n0 = vu[Regex("_(\\d{8}_\\d{6})Z").find(f.name)!!.groupValues[1]] ?: continue
                val b = ImageIO.read(f); val px = b.getRGB(0, 0, b.width, b.height, null, 0, b.width)
                if (ClassementSstv.analyse(px, b.width, b.height, 240, true).note < 85) continue
                val g = gris(f)
                val (x, y, c) = LectureDiapo.cherche(g, barre) ?: continue
                if (c < 0.7f) continue
                val cb = coupe(g, x, y, LectureDiapo.BL, LectureDiapo.BH)
                for (i in somme.indices) somme[i] += cb[i]; n++
                val z = coupe(g, x - LectureDiapo.ZL, y, LectureDiapo.ZL, LectureDiapo.BH)
                val (s, k) = zones[n0] ?: (FloatArray(z.size) to 0)
                for (i in s.indices) s[i] += z[i]
                zones[n0] = s to k + 1
            }
            barre = FloatArray(somme.size) { somme[it] / n }
            if (it == 1) {
                println("barre ($n) = \"" + code(barre) + "\"")
                for ((k, v) in zones.toSortedMap()) println("    $k to \"" + code(FloatArray(v.first.size) { v.first[it] / v.second }) + "\", // ${v.second}")
            }
        }
    }

    private fun code(a: FloatArray) = a.joinToString("") { ('a' + (it / 256f * 26).toInt().coerceIn(0, 25)).toString() }

    private fun gris(f: File): IntArray {
        val b = ImageIO.read(f); val px = b.getRGB(0, 0, b.width, b.height, null, 0, b.width)
        return LectureDiapo.gris(px, b.width, b.height)
    }

    private fun coupe(g: IntArray, x0: Int, y0: Int, w: Int, h: Int) =
        FloatArray(w * h) { i -> g[(y0 + i / w).coerceIn(0, 239) * 320 + (x0 + i % w).coerceIn(0, 319)].toFloat() }
}
