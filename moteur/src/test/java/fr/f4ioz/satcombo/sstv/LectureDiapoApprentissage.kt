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
 * [apprend33]: the same for series 33 (its footer often cut by the
 * picture's bottom edge: each cell averaged over the pictures that show it),
 * and its « СЕРИЯ 33 ».
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
                val (x, y, c) = LectureDiapo.cherche(g, LectureDiapo.SERIE_34, barre) ?: continue
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

    @Test fun apprend33() = apprendStyle(LectureDiapo.SERIE_33, "SatMe_SSTV_ISS_20261003_135356Z_Robot36.png", 173, 222, VU33) {
        it.contains("ISS_2026100") && !it.contains("partiel")
    }

    /** Series 34's « СЕРИЯ 34 » (its « /12 » and numbers: [apprend]). */
    @Test fun apprendSerie34() = apprendStyle(LectureDiapo.SERIE_34, "SatMe_SSTV_ISS_20261023_092643Z_Robot36.png", 174, 219, ClassementSstvReelTest.VU) {
        it.contains("ISS_2026102")
    }

    /**
     * [st]'s patterns from the pictures of [d] whose name passes [garde],
     * read by eye ([vu]), bootstrapped from the « /12 » of [depart] at ([bx], [by]).
     */
    private fun apprendStyle(st: LectureDiapo.Style, depart: String, bx: Int, by: Int, vu: Map<String, Int>, garde: (String) -> Boolean) {
        val d = System.getenv("SATME_SSTV_REEL")?.let(::File)
        assumeTrue(d != null && d.isDirectory && System.getenv("SATME_APPRENDS") != null)
        var barre = coupe(gris(File(d, depart)), bx, by, st.bl, st.bh)
        val l = d!!.listFiles { f -> f.name.endsWith(".png") && garde(f.name) }!!.sortedBy { it.name }
        repeat(3) { tour ->
            val somme = FloatArray(barre.size); val nb = IntArray(barre.size)
            val zones = HashMap<Int, Pair<FloatArray, IntArray>>()
            val serie = FloatArray(st.sl * st.bh); val nbSerie = IntArray(serie.size)
            for (f in l) {
                val n0 = vu[Regex("_(\\d{8}_\\d{6})Z").find(f.name)!!.groupValues[1]] ?: continue
                val g = gris(f)
                val (x, y, c) = LectureDiapo.cherche(g, st, barre) ?: continue
                if (tour == 2) println("  ${f.name} $n0 : $x,$y ${"%.2f".format(c)}")
                if (c < 0.7f) continue
                ajoute(g, x, y, st.bl, st.bh, somme, nb)
                val (s, k) = zones.getOrPut(n0) { FloatArray(st.zl * st.bh) to IntArray(st.zl * st.bh) }
                ajoute(g, x - st.zl, y, st.zl, st.bh, s, k)
                ajoute(g, x + st.sx, y + st.sy, st.sl, st.bh, serie, nbSerie)
            }
            barre = moyenne(somme, nb)
            if (tour == 2) {
                println("barre = \"" + code(barre) + "\"")
                println("serie = \"" + code(moyenne(serie, nbSerie)) + "\"")
                for ((k, v) in zones.toSortedMap()) println("    $k to \"" + code(moyenne(v.first, v.second)) + "\", // ${v.second.max()}")
            }
        }
    }

    /** Cells of [g] at ([x0], [y0]) added where inside the picture. */
    private fun ajoute(g: IntArray, x0: Int, y0: Int, w: Int, h: Int, s: FloatArray, n: IntArray) {
        for (i in s.indices) {
            val y = y0 + i / w; val x = x0 + i % w
            if (y in 0..239 && x in 0..319) { s[i] += g[y * 320 + x]; n[i]++ }
        }
    }

    /** The mean of each cell; a cell no picture shows: the mean of the others in its column above. */
    private fun moyenne(s: FloatArray, n: IntArray): FloatArray {
        val m = FloatArray(s.size) { if (n[it] > 0) s[it] / n[it] else -1f }
        val fond = m.filter { it >= 0 }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 4] }
        return FloatArray(m.size) { if (m[it] >= 0) m[it] else fond }
    }

    companion object {
        /** Series 33 as read by eye (3–4 October 2026): picture time → slide. */
        val VU33 = mapOf(
            "20261003_135119" to 5, "20261003_135356" to 6, "20261003_135634" to 7, "20261003_135913" to 8,
            "20261003_152927" to 6, "20261003_153206" to 7, "20261003_153216" to 7, "20261003_153445" to 8, "20261003_153455" to 8,
            "20261003_170509" to 6, "20261003_170737" to 7, "20261003_171016" to 8,
            "20261004_112837" to 2, "20261004_113116" to 3, "20261004_113355" to 4, "20261004_130407" to 2, "20261004_130646" to 3,
            "20261004_130925" to 4, "20261004_131204" to 5, "20261004_144455" to 4, "20261004_144734" to 5,
            "20261004_161851" to 9, "20261004_162120" to 10)
    }
}
