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

/**
 * The automatic tuning on real passes: sounds that gave pictures, shifted by
 * ±200 Hz, decoded with and without it, against the unshifted decode. Read
 * only, and only when SATME_AFC_REEL names a folder of WAVs (or is 1: the
 * station's own SSTV folder and recordings).
 */
class SstvAfcReelTest {

    @Test fun `real passes shifted by 200 Hz`() {
        val v = System.getenv("SATME_AFC_REEL")
        assumeTrue(!v.isNullOrBlank())
        val maison = File(System.getProperty("user.home"), ".satme")
        val fichiers = if (v == "1") {
            val sstv = File(maison, "sstv")
            // The recordings named by a picture's sidecar, and each picture's own sound.
            val enregs = (sstv.listFiles { f -> f.name.endsWith(".meta") } ?: emptyArray())
                .mapNotNull { m -> m.readLines().firstOrNull { it.startsWith("recording=") }?.substringAfter('=') }
                .filter { it.endsWith(".wav") }.distinct().map { File(File(maison, "enregistrements"), it) }.filter { it.isFile }
            enregs + (sstv.listFiles { f -> f.name.endsWith(".wav") } ?: emptyArray()).sortedBy { it.name }
        } else (File(v).listFiles { f -> f.name.endsWith(".wav") } ?: emptyArray()).sortedBy { it.name }
        val lignes = ArrayList<String>()
        var sommes = DoubleArray(4); var n = 0
        for (f in fichiers) {
            val (pcm, rate) = AfcBanc.litWav(f) ?: continue
            val ref = AfcBanc.decode(pcm, rate, { 0.0 }, afc = false, ssb = true).images
            if (ref.isEmpty()) continue
            // The recording's own mistuning, as the tuning sees it unshifted.
            val zero = AfcBanc.decode(pcm, rate, { 0.0 }, afc = true, ssb = true)
            lignes += "%-52s propre accord : corrigé %+d Hz %s".format(f.name, zero.totalHz, zero.journal.take(4))
            for (off in listOf(-200.0, 200.0)) {
                val sans = AfcBanc.decode(pcm, rate, { off }, afc = false, ssb = true)
                val avec = AfcBanc.decode(pcm, rate, { off }, afc = true, ssb = true)
                val fige = AfcBanc.decode(pcm, rate, { off }, afc = true, ssb = true, pendantImage = false)
                val poste = AfcBanc.decode(pcm, rate, { off }, afc = true, couplee = false, latenceMs = 600, ssb = true)
                val e = listOf(sans, avec, fige, poste).map { s -> compare(ref, s.images) }
                lignes += "%-52s %+5.0f Hz  %d image(s) : sans %5.1f (%d) · avec %5.1f (%d, corrigé %+d Hz) · figé en image %5.1f · poste %5.1f (corrigé %+d Hz) · avec/avec à 0 : %5.1f".format(
                    f.name, off, ref.size, e[0].first, e[0].second, e[1].first, e[1].second, avec.totalHz, e[2].first, e[3].first, poste.totalHz,
                    compare(zero.images, avec.images).first)
                if (System.getenv("SATME_AFC_JOURNAL") != null) lignes += "    " + avec.journal.take(12)
                for (k in 0 until 4) sommes[k] += e[k].first
                n++
            }
        }
        println("AFC réel : écart moyen par canal (0–255) à l'image non décalée ; entre parenthèses, images retrouvées")
        lignes.forEach { println("AFC réel : $it") }
        if (n > 0) println("AFC réel : moyenne sur $n essais : sans %.1f · avec %.1f · figé en image %.1f · poste %.1f".format(
            sommes[0] / n, sommes[1] / n, sommes[2] / n, sommes[3] / n))
    }

    /** Mean difference over the reference pictures (a missing one counts 255), and how many were found. */
    private fun compare(ref: List<AfcBanc.Image>, vues: List<AfcBanc.Image>): Pair<Double, Int> {
        var s = 0.0; var trouvees = 0
        for (r in ref) {
            val m = vues.filter { it.mode == r.mode }.minByOrNull { AfcBanc.ecart(it, r) }
            if (m != null) trouvees++
            s += m?.let { AfcBanc.ecart(it, r) } ?: 255.0
        }
        return s / ref.size to trouvees
    }
}
