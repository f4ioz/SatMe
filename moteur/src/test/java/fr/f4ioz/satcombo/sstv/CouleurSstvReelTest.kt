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
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * What the colour analysis finds on real pictures (SATME_COULEUR_REEL=folder
 * of PNGs; SATME_SSTV_SORTIE_DIR=folder for each picture before | after).
 * Skipped without it; the folder given is only read.
 */
class CouleurSstvReelTest {

    @Test fun `real pictures, measured before and after`() {
        val dir = System.getenv("SATME_COULEUR_REEL")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val sortie = System.getenv("SATME_SSTV_SORTIE_DIR")?.let(::File)?.also { it.mkdirs() }
        fun f(v: Double) = String.format(Locale.US, "%.1f", v)
        for (png in dir!!.listFiles { x -> x.name.endsWith(".png") }!!.sortedBy { it.name }) {
            val b = ImageIO.read(png) ?: continue
            val w = b.width; val h = b.height
            val px = b.getRGB(0, 0, w, h, null, 0, w)
            val t0 = System.nanoTime()
            val r = CouleurSstv.corrige(px, w, h, o = CouleurSstv.Options(saturation = true))
            val ms = (System.nanoTime() - t0) / 1e6
            val a = r.analyse; val ap = CouleurSstv.analyse(r.px, w, h)
            val noteAv = ClassementSstv.analyse(px, w, h).note; val noteAp = ClassementSstv.analyse(r.px, w, h).note
            println(png.name.removePrefix("SatMe_SSTV_").removeSuffix(".png") + " (" + f(ms) + " ms)" +
                "\n  before: cast Cb ${f(a.dominanteCb)} Cr ${f(a.dominanteCr)} (support ${f(a.appuiNeutre * 100)} %, sure ${a.dominanteSure}, tuning ${a.accordHz?.let(::f)} Hz)" +
                " shift Cb ${f(a.cbX)}/${f(a.cbY)} Cr ${f(a.crX)}/${f(a.crY)} (corr ${f(a.correlationChroma)}) levels ${f(a.noir)}–${f(a.blanc)} sat ${f(a.saturation)} noise ${f(a.bruitY)}/${f(a.bruitC)} snow ${a.bruitDominant} mark $noteAv" +
                "\n  plan: ${r.plan}" +
                "\n  after: cast Cb ${f(ap.dominanteCb)} Cr ${f(ap.dominanteCr)} shift Cb ${f(ap.cbX)}/${f(ap.cbY)} Cr ${f(ap.crX)}/${f(ap.crY)} levels ${f(ap.noir)}–${f(ap.blanc)} sat ${f(ap.saturation)} mark $noteAp")
            // The same picture as a tuning error of +20 Hz and a chroma 2 px late would have decoded it: found again?
            val sim = CouleurSstv.simule(px, w, h, hz = 20.0, cbX = 2.0, crX = 2.0)
            val s = CouleurSstv.corrige(sim, w, h)
            println("  simulated +20 Hz, chroma +2 px: found cast ${f(s.analyse.dominanteCb)}/${f(s.analyse.dominanteCr)} (tuning ${s.analyse.accordHz?.let(::f)} Hz, sure ${s.analyse.dominanteSure})" +
                " shift ${f(s.analyse.cbX)}/${f(s.analyse.crX)}; after, cast left ${f(CouleurSstv.analyse(s.px, w, h).dominante)}")
            if (sortie != null) {
                val o = BufferedImage(2 * w + 4, h, BufferedImage.TYPE_INT_RGB)
                o.setRGB(0, 0, w, h, px, 0, w); o.setRGB(w + 4, 0, w, h, r.px, 0, w)
                ImageIO.write(o, "png", File(sortie, png.name.removeSuffix(".png") + "_avant_apres.png"))
            }
        }
    }
}
