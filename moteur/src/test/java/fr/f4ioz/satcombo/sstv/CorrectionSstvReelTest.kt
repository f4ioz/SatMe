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
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * What correcting does on a real gallery (SATME_SSTV_REEL=folder with the
 * PNGs, their sidecars and their own .wav; SATME_SSTV_SORTIE_DIR=folder for
 * the pictures side by side). Skipped without it. The folder given is only
 * read.
 *
 * Two measures, neither perfect: the gallery's mark ([ClassementSstv], which
 * a blur would flatter), and the gap (mean |R|+|G|+|B| / 3) to the slide as
 * the *other* copies show it (put together, the picture measured left out,
 * and its twins decoded from the same sound): an honest check that the
 * correction comes nearer the slide, not only smoother. For the fusion, a
 * clean copy (mark ≥ 97) is the truth, left out of the copies put together.
 */
class CorrectionSstvReelTest {

    private class Img(val px: IntArray, val w: Int, val h: Int, val lignes: Int)

    private fun lit(f: File): Img { val b = ImageIO.read(f); return Img(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height, b.height) }
    private fun note(i: Img, complete: Boolean = i.lignes >= 240) = ClassementSstv.analyse(i.px, i.w, i.lignes.coerceIn(1, i.h), 240, complete).note
    private fun copie(i: Img) = FusionSstv.Copie(i.px, i.w, i.h, i.lignes)
    private fun fus(l: List<Img>): Img { val f = FusionSstv.fusionne(l.map(::copie)); return Img(f.px, f.w, f.h, f.lignes) }
    private fun post(i: Img) = Img(CorrectionSstv.corrige(i.px, i.w, i.h, i.lignes), i.w, i.h, i.lignes)

    /** Mean RGB gap to [ref] where both have rows, after placing; -1 when they do not fit. */
    private fun ecart(ref: Img, c: Img): Float {
        val cg = FusionSstv.cale(copie(ref), copie(c)) ?: return -1f
        var s = 0L; var m = 0L
        for (y in 0 until ref.lignes) {
            val yb = y + cg.dy
            if (yb !in 0 until c.lignes) continue
            for (x in 8 until ref.w - 8) {
                val p = ref.px[y * ref.w + x]; val q = c.px[yb * c.w + (x + cg.dx).coerceIn(0, c.w - 1)]
                s += abs(((p shr 16) and 255) - ((q shr 16) and 255)) + abs(((p shr 8) and 255) - ((q shr 8) and 255)) + abs((p and 255) - (q and 255))
                m += 3
            }
        }
        return if (m > 0) s / m.toFloat() else -1f
    }

    /** The live decoder fed the same sound (and cleaned as live): what « Redécoder son son » gives. */
    private fun direct(pcm: ShortArray, rate: Int): Img? {
        var res: Img? = null
        val d = SstvDecoder(rate, object : SstvDecoder.Listener {
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                if (res == null || linesDone > res!!.lignes) res = Img(SstvNettoyage.nettoie(pixels, mode.width, mode.height, linesDone), mode.width, mode.height, linesDone)
            }
        })
        var i = 0
        while (i < pcm.size) { val k = minOf(4096, pcm.size - i); d.feed(pcm.copyOfRange(i, i + k), k); i += k }
        d.finish()
        return res
    }

    private fun redecode(pcm: ShortArray, rate: Int): Img? {
        val r = RedecodageSstv.decode(pcm, rate, SstvMode.byName("Robot 36")!!) ?: return null
        return Img(SstvNettoyage.nettoie(r.pixels, r.mode.width, r.mode.height, r.lignes), r.mode.width, r.mode.height, r.lignes)
    }

    private fun ecrit(f: File, l: List<Img?>) {
        val out = BufferedImage(320 * l.size + 8 * (l.size - 1), 240, BufferedImage.TYPE_INT_RGB)
        for ((k, i) in l.withIndex()) if (i != null) out.setRGB(k * 328, 0, minOf(320, i.w), minOf(240, i.lignes), i.px, 0, i.w)
        ImageIO.write(out, "png", f)
    }

    @Test fun mesure() {
        val d = System.getenv("SATME_SSTV_REEL")?.let(::File)
        assumeTrue(d != null && d.isDirectory)
        val sortie = System.getenv("SATME_SSTV_SORTIE_DIR")?.let(::File)?.also { it.mkdirs() }
        val shots = d!!.listFiles { f -> f.name.endsWith(".png") && f.name.contains("_ISS_") && f.name.contains("Robot36") }!!.sortedBy { it.name }.map { f ->
            SstvMeta.decode(f.name, File(d, SstvMeta.sidecarName(f.name)).takeIf { it.isFile }?.readText())
        }
        val brutes = shots.associate { it.fileName to lit(File(d, it.fileName)) }
        val an = shots.associate { s -> val i = brutes[s.fileName]!!; s.fileName to ClassementSstv.analyse(i.px, i.w, i.h, 240, s.complete) }
        val groupes = ClassementSstv.groupe(shots, an)
        val somme = LinkedHashMap<String, Pair<Double, Int>>()
        fun compte(k: String, v: Number) { if (v.toDouble() < 0) return; val (s, n) = somme[k] ?: (0.0 to 0); somme[k] = (s + v.toDouble()) to (n + 1) }
        val corrigees = HashMap<String, Img>()
        println("note [écart aux autres copies] : brute | redécodée en direct (SstvDecoder) | RedecodageSstv | + CorrectionSstv (expédiée) | brute + CorrectionSstv")
        for (g in groupes) {
            println("groupe ${g.diapo}/${g.total} (${g.images.size})")
            val l = g.images.map { brutes[it.fileName]!! }
            for ((k, s) in g.images.withIndex()) {
                val brute = l[k]
                val autres = l.filterIndexed { j, b -> j != k && ecart(brute, b).let { it < 0 || it > FusionSstv.DOUBLON } }
                val ref = if (autres.size >= 2) fus(autres) else null
                val wav = SstvSon.fichier(File(d, s.fileName))
                val son = if (wav.isFile) SstvSon.litWav(wav) else null
                val dr = son?.let { direct(it.first, it.second) }
                val rd = son?.let { redecode(it.first, it.second) }
                val ex = rd?.let(::post)
                val bp = post(brute)
                corrigees[s.fileName] = ex ?: bp
                // The same pictures in every column's mean: those with a sound the live decoder also finds a picture in.
                val pareil = son != null && dr != null && rd != null
                fun aff(cle: String, i: Img?): String {
                    if (i == null) return "   --      "
                    val n = note(i, if (i === brute || i === bp) s.complete else i.lignes >= 240); val e = ref?.let { ecart(it, i) } ?: -1f
                    if (pareil) { compte("note_son_$cle", n); if (ref != null) compte("ecart_son_$cle", e) }
                    else if (son == null) { compte("note_sans_$cle", n); if (ref != null) compte("ecart_sans_$cle", e) }
                    return String.format("%3d[%5.1f]", n, e)
                }
                println("   " + listOf(aff("brute", brute), aff("direct", dr), aff("redecodee", rd), aff("expediee", ex), aff("brute_post", bp)).joinToString(" ") + " " + s.fileName)
                if (sortie != null && son != null) ecrit(File(sortie, s.fileName.removeSuffix(".png") + "_cmp.png"), listOf(brute, dr, ex))
            }
            // The group put together, as shipped: each copy decoded again when it has its sound, then the specks.
            if (g.images.size >= 2) {
                val versions = g.images.map { s -> SstvSon.fichier(File(d, s.fileName)).takeIf { it.isFile }?.let { SstvSon.litWav(it) }?.let { redecode(it.first, it.second) } ?: brutes[s.fileName]!! }
                    .sortedByDescending { note(it) }
                val f = post(fus(versions))
                val meilleure = g.notes.values.maxOrNull() ?: -1
                val meilleureCorr = g.images.maxOf { note(corrigees[it.fileName]!!) }
                println(String.format("   fusion expédiée de %d copies : note %d (meilleure copie brute %d, meilleure corrigée %d)", g.images.size, note(f), meilleure, meilleureCorr))
                compte("groupe_meilleure_brute", meilleure); compte("groupe_meilleure_corrigee", meilleureCorr); compte("groupe_fusion", note(f))
                if (sortie != null) ecrit(File(sortie, "groupe_" + g.diapo + "_" + g.images[0].fileName.removeSuffix(".png") + ".png"),
                    listOf(l[0], corrigees[g.images[0].fileName], f))
            }
            // Against a clean copy (mark ≥ 97) left out with its twins: the others alone, corrected, put together.
            if (an[g.images[0].fileName]!!.note >= 97) {
                val verite = l[0]
                val idx = (1 until l.size).filter { j -> ecart(verite, l[j]).let { it < 0 || it > FusionSstv.DOUBLON } }
                if (idx.size >= 2) {
                    val seuleBrute = idx.maxByOrNull { note(l[it]) }!!
                    val corr = idx.map { corrigees[g.images[it].fileName]!! }
                    val seuleCorr = corr.maxByOrNull { note(it) }!!
                    val fb = fus(idx.sortedByDescending { note(l[it]) }.map { l[it] })
                    val fc = post(fus(corr.sortedByDescending { note(it) }))
                    val r = listOf("v_meilleure_brute" to l[seuleBrute], "v_meilleure_corrigee" to seuleCorr, "v_fusion_brutes" to fb, "v_fusion_expediee" to fc)
                    println("   sans la meilleure (${idx.size} autres) : " + r.joinToString("  ") { (k, i) -> val e = ecart(verite, i); compte(k, e); compte("n" + k, note(i)); String.format("%s %.1f/%d", k.removePrefix("v_"), e, note(i)) })
                }
            }
        }
        println("moyennes :")
        for ((k, v) in somme) println(String.format("  %-34s %6.2f  (%d)", k, v.first / v.second, v.second))
    }
}
