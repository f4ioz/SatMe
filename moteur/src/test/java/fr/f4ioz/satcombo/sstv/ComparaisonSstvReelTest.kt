/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * SatMe's SSTV decoders against other decoders, on real recordings
 * (SATME_CMP=work folder with wav-images/, wav-passes/, out/ow-images/…).
 * Skipped without it. Test-only measuring harness: it reads other decoders'
 * output PNGs (written by their own programs), never their code.
 *
 *  - [decode]: SatMe live decoder (as SatMe-PC: continuous, no forced mode,
 *    no cleaning) and RedecodageSstv + SstvNettoyage + CorrectionSstv (as the
 *    gallery's « Corriger depuis le son » ships it) on every per-picture WAV;
 *    the live decoder on every whole-pass WAV.
 *  - [mesure]: the same yardstick on every decoder's output: ClassementSstv
 *    mark, slips, and the leave-one-out gap to the fusion of the other
 *    copies of the same slide; CSV and side-by-side sheets.
 */
class ComparaisonSstvReelTest {

    private val dir: File? = System.getenv("SATME_CMP")?.let(::File)?.takeIf { it.isDirectory }
    private val r36 = SstvMode.byName("Robot 36")!!
    /** Where SatMe's own outputs go (SATME_CMP_OUT, « out » by default); the other decoders' are always read from « out ». */
    private val sortie = System.getenv("SATME_CMP_OUT")?.takeIf { it.isNotBlank() } ?: "out"
    /** SatMe's outputs of a run before (SATME_CMP_V1, « out » when the outputs go elsewhere): compared as *_v1. */
    private val v1 = System.getenv("SATME_CMP_V1")?.takeIf { it.isNotBlank() } ?: if (sortie != "out") "out" else null

    private fun png(f: File, px: IntArray, w: Int, h: Int) {
        val hh = h.coerceIn(1, px.size / w)
        val b = BufferedImage(w, hh, BufferedImage.TYPE_INT_RGB)
        b.setRGB(0, 0, w, hh, px, 0, w)
        f.parentFile.mkdirs(); ImageIO.write(b, "png", f)
    }

    private class Vu(var mode: String = "", var vis: Boolean = false, var px: IntArray? = null, var lignes: Int = 0, var complete: Boolean = false, var debutS: Double = 0.0)

    /** The live decoder on [pcm]: every picture it hands over. */
    private fun direct(pcm: ShortArray, rate: Int): List<Vu> {
        val out = ArrayList<Vu>()
        var vis = false
        lateinit var d: SstvDecoder
        d = SstvDecoder(rate, object : SstvDecoder.Listener {
            override fun onVis(mode: SstvMode) { vis = true }
            override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {
                out += Vu(mode.name, vis, pixels.copyOf(), linesDone, complete, d.debutTrame / rate.toDouble())
                vis = false
            }
        }).also { it.continuous = true; it.forcedMode = null }
        var i = 0
        while (i < pcm.size) { val k = minOf(4096, pcm.size - i); d.feed(pcm.copyOfRange(i, i + k), k); i += k }
        d.finish()
        return out
    }

    @Test fun decode() {
        assumeTrue(dir != null)
        val d = dir!!
        val out = File(d, sortie); out.mkdirs()
        val js = StringBuilder()
        for (wav in File(d, "wav-images").listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }) {
            val stem = wav.name.removeSuffix(".wav")
            val (pcm, rate) = SstvSon.litWav(wav)!!
            val o = JSONObject().put("stem", stem).put("rate", rate).put("seconds", pcm.size / rate.toDouble())
            val lv = direct(pcm, rate)
            o.put("live_n", lv.size)
            lv.maxByOrNull { it.lignes }?.let { v ->
                png(File(out, "satme-live/$stem.png"), v.px!!, 320, v.lignes)
                o.put("live_mode", v.mode).put("live_vis", v.vis).put("live_lignes", v.lignes).put("live_complete", v.complete).put("live_debutS", v.debutS)
            }
            val t0 = System.nanoTime()
            val r = runCatching { RedecodageSstv.decode(pcm, rate, r36) }.getOrNull()
            o.put("redec_ms", (System.nanoTime() - t0) / 1e6)
            if (r != null) {
                // SATME_CMP_CHROMA=seuil,seuil2 (Hz): the wider colour smoothing's thresholds, to try them.
                val ch = System.getenv("SATME_CMP_CHROMA")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
                val co = if (ch != null && ch.size == 2) CorrectionSstv.Options(bruitHz = r.bruitHz, seuilChromaHz = ch[0], seuilChroma2Hz = ch[1]) else CorrectionSstv.Options(bruitHz = r.bruitHz)
                val px = CorrectionSstv.corrige(SstvNettoyage.nettoie(r.pixels, 320, 240, r.lignes), 320, 240, r.lignes, co)
                png(File(out, "satme-redec/$stem.png"), px, 320, r.lignes)
                png(File(out, "satme-redec-brut/$stem.png"), r.pixels, 320, r.lignes)
                o.put("redec_lignes", r.lignes).put("redec_complete", r.complete).put("redec_entete", r.entete).put("redec_syncs", r.syncs.toDouble())
                    .put("redec_ppm", r.derivePpm).put("redec_ecartHz", r.ecartHz).put("redec_bruitHz", r.bruitHz).put("redec_gain", r.gain)
                    .put("snr_db", QualiteSstv.snrDb(r.bruitHz)).put("redec_debutS", (r.debuts.firstOrNull() ?: 0.0) / rate)
            }
            js.append(o.toString()).append('\n')
            println("$stem live=${lv.size} redec=${r?.lignes}")
        }
        File(out, "satme-images.jsonl").writeText(js.toString())
        val jp = StringBuilder()
        for (wav in File(d, "wav-passes").listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }) {
            val stem = wav.name.removeSuffix(".wav")
            val (pcm, rate) = SstvSon.litWav(wav)!!
            for ((k, v) in direct(pcm, rate).withIndex()) {
                png(File(out, "satme-passes/${stem}_live$k.png"), v.px!!, 320, v.lignes)
                jp.append(JSONObject().put("wav", wav.name).put("index", k).put("mode", v.mode).put("vis", v.vis).put("lignes", v.lignes)
                    .put("complete", v.complete).put("startS", v.debutS).put("png", "${stem}_live$k.png").toString()).append('\n')
            }
            println("pass $stem done")
        }
        File(out, "satme-passes.jsonl").writeText(jp.toString())
    }

    // ------------------------------------------------------------------ measuring

    private class Img(val px: IntArray, val w: Int, val h: Int, val lignes: Int, val complete: Boolean)

    private fun lit(f: File, lignes: Int? = null, complete: Boolean? = null): Img? {
        if (!f.isFile) return null
        val b = ImageIO.read(f) ?: return null
        // Brought to 320×240 (missing rows black): a 320-wide picture row for row, any other size scaled.
        val px = IntArray(320 * 240) { 0xFF000000.toInt() }
        val src = b.getRGB(0, 0, b.width, b.height, null, 0, b.width)
        val natif = b.width == 320
        val hh = if (natif) minOf(240, b.height) else 240
        for (y in 0 until hh) {
            val sy = if (natif) y else (y.toLong() * b.height / 240).toInt()
            for (x in 0 until 320) px[y * 320 + x] = src[sy * b.width + (x.toLong() * b.width / 320).toInt()]
        }
        val l = (lignes ?: hh).coerceIn(1, 240)
        return Img(px, 320, 240, l, complete ?: (l >= 240))
    }

    /** QSSTV paints rows it never received one flat grey: those trailing uniform rows are taken as not received (black, cut). */
    private fun sansFond(i: Img): Img {
        var l = i.lignes
        while (l > 1) { val r = l - 1; val c = i.px[r * i.w]; if ((0 until i.w).all { i.px[r * i.w + it] == c }) l-- else break }
        if (l == i.lignes) return i
        val px = i.px.copyOf(); for (k in l * i.w until px.size) px[k] = 0xFF000000.toInt()
        return Img(px, i.w, i.h, l, false)
    }

    private fun copie(i: Img) = FusionSstv.Copie(i.px, i.w, i.h, i.lignes)
    private fun analyse(i: Img) = ClassementSstv.analyse(i.px, i.w, i.lignes, 240, i.complete)

    /** Mean RGB gap to [ref] where both have rows, after placing (as CorrectionSstvReelTest); -1 when they do not fit. */
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

    /** Gap over the WHOLE 240 rows: rows the copy lacks count as black (a missed top or bottom costs); a copy that cannot be placed is compared unshifted. */
    private fun ecartPlein(ref: Img, c: Img): Float {
        val cg = FusionSstv.cale(copie(ref), copie(c))
        var s = 0L; var m = 0L
        for (y in 0 until ref.lignes) for (x in 8 until ref.w - 8) {
            val p = ref.px[y * ref.w + x]
            val yb = y + (cg?.dy ?: 0)
            val q = if (yb in 0 until c.lignes) c.px[yb * c.w + (x + (cg?.dx ?: 0)).coerceIn(0, c.w - 1)] else 0xFF000000.toInt()
            s += abs(((p shr 16) and 255) - ((q shr 16) and 255)) + abs(((p shr 8) and 255) - ((q shr 8) and 255)) + abs((p and 255) - (q and 255))
            m += 3
        }
        return if (m > 0) s / m.toFloat() else -1f
    }

    private fun jsonl(f: File): List<JSONObject> = if (!f.isFile) emptyList() else f.readLines().filter { it.isNotBlank() }.map(::JSONObject)

    private fun cle(n: String) = Regex("_(\\d{8}_\\d{6})Z").find(n)?.groupValues?.get(1)

    @Test fun mesure() {
        assumeTrue(dir != null && File(dir, "$sortie/satme-images.jsonl").isFile)
        val d = dir!!
        val out = File(d, sortie)
        val base = File(d, "out")
        val sat = jsonl(File(out, "satme-images.jsonl")).associateBy { it.getString("stem") }
        val sat1 = v1?.let { jsonl(File(d, "$it/satme-images.jsonl")).associateBy { o -> o.getString("stem") } } ?: emptyMap()
        // OscarWatch: the longest picture it handed over for each sound (a sound split in two keeps the longer part).
        val owAll = jsonl(File(base, "ow-images/oscarwatch.jsonl")).filter { !it.optBoolean("summary") }
        val ow = owAll.groupBy { it.getString("wav").removeSuffix(".wav") }.mapValues { (_, l) -> l.maxByOrNull { it.getInt("decodedLines") }!! }
        val owN = owAll.groupBy { it.getString("wav").removeSuffix(".wav") }.mapValues { it.value.size }
        val qsAll = jsonl(File(base, "qsstv-images/qsstv.jsonl"))
        val qs = qsAll.groupBy { it.getString("stem") }.mapValues { (_, l) -> l.maxWithOrNull(compareBy({ it.getString("mode") == "R36" }, { it.getLong("size") }))!! }
        val qsN = qsAll.groupBy { it.getString("stem") }.mapValues { it.value.size }
        val stems = sat.keys.sorted()

        class P(val stem: String, val cle: String, val jumeau: Boolean, val v: Map<String, Img?>)
        val ps = stems.map { stem ->
            val meta = File(d, "wav-images/$stem.meta").takeIf { it.isFile }?.readText() ?: ""
            val complet = meta.lines().any { it == "complete=1" }
            val s = sat[stem]!!
            val o = ow[stem]
            val v = LinkedHashMap<String, Img?>()
            v["live_saved"] = lit(File(d, "wav-images/$stem.png"), complete = complet)
            v["satme_live"] = if (s.has("live_lignes")) lit(File(out, "satme-live/$stem.png"), s.getInt("live_lignes"), s.getBoolean("live_complete")) else null
            v["satme_redec"] = if (s.has("redec_lignes")) lit(File(out, "satme-redec/$stem.png"), s.getInt("redec_lignes"), s.getBoolean("redec_complete")) else null
            if (v1 != null) {
                val s1 = sat1[stem]
                v["satme_live_v1"] = if (s1 != null && s1.has("live_lignes")) lit(File(d, "$v1/satme-live/$stem.png"), s1.getInt("live_lignes"), s1.getBoolean("live_complete")) else null
                v["satme_redec_v1"] = if (s1 != null && s1.has("redec_lignes")) lit(File(d, "$v1/satme-redec/$stem.png"), s1.getInt("redec_lignes"), s1.getBoolean("redec_complete")) else null
            }
            // What the gallery keeps as the picture's best version once it is re-decoded at its end: the corrected one when it marks better.
            run {
                val l = v["satme_live"]; val r = v["satme_redec"]
                fun nt(i: Img?) = i?.let { analyse(it).note } ?: -1
                v["satme_auto"] = if (r != null && nt(r) > nt(l)) r else l
            }
            v["ow"] = o?.let { lit(File(base, "ow-images/" + it.getString("png")), it.getInt("decodedLines"), it.getBoolean("complete")) }
            v["ow_post"] = v["ow"]?.let { Img(CorrectionSstv.corrige(SstvNettoyage.nettoie(it.px, 320, 240, it.lignes), 320, 240, it.lignes), 320, 240, it.lignes, it.complete) }
            v["qsstv"] = qs[stem]?.let { lit(File(base, "qsstv-images/" + it.getString("png")), complete = it.optBoolean("complete", false))?.let(::sansFond) }
            P(stem, cle(stem)!!, stem.endsWith("_r2"), v)
        }
        val methodes = listOf("live_saved", "satme_live", "satme_redec", "ow", "ow_post", "qsstv") +
            (if (v1 != null) listOf("satme_live_v1", "satme_redec_v1", "satme_auto") else listOf("satme_auto"))
        val an = ps.associate { p -> p.stem to p.v.mapValues { (_, i) -> i?.let(::analyse) } }

        // Slides: by eye where known (ClassementSstvReelTest.VU), else from the gallery's own grouping of the saved pictures.
        val vu = ClassementSstvReelTest.VU
        val diapo = HashMap<String, Int>()
        for (p in ps) vu[p.cle]?.let { diapo[p.stem] = it }
        run {
            val shots = ps.map { p -> SstvMeta.decode(p.stem + ".png", File(d, "wav-images/${p.stem}.meta").takeIf { it.isFile }?.readText()) }
            val ana = ps.associate { p -> (p.stem + ".png") to an[p.stem]!!["live_saved"]!! }
            for (g in ClassementSstv.groupe(shots, ana)) {
                val connu = g.images.mapNotNull { diapo[it.fileName.removeSuffix(".png")] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                for (s in g.images) { val st = s.fileName.removeSuffix(".png"); if (st !in diapo) diapo[st] = connu ?: -(g.hashCode() and 0xFFFF) }
            }
        }

        // References: for each picture, the fusion of the other transmissions of its slide (its twins left out),
        // from a pool of decoders; the pool is each main decoder's copies together (neutral), or one decoder's alone.
        // The neutral pool keeps the earlier re-decode when there is one: old and new are measured against the same references.
        val redecRef = if (v1 != null) "satme_redec_v1" else "satme_redec"
        val pools = mapOf("neutre" to listOf("live_saved", redecRef, "ow"), "live" to listOf("live_saved"), "redec" to listOf(redecRef), "owp" to listOf("ow"))
        val refs = HashMap<Pair<String, String>, Img?>()
        for (p in ps) for ((pk, pool) in pools) {
            val autres = ps.filter { it.cle != p.cle && diapo[it.stem] == diapo[p.stem] && !it.jumeau }
            val copies = autres.flatMap { a -> pool.mapNotNull { m -> a.v[m]?.let { it to an[a.stem]!![m]!!.note } } }.sortedByDescending { it.second }.map { it.first }
            refs[p.stem to pk] = if (autres.size >= 2 && copies.size >= 2) FusionSstv.fusionne(copies.map(::copie)).let { Img(it.px, it.w, it.h, it.lignes, it.lignes >= 240) } else null
        }

        val csv = StringBuilder()
        val tete = mutableListOf("stem", "transmission", "twin", "slide", "snr_db", "redec_bruitHz", "ow_lock_pct", "ow_images", "qsstv_images", "qsstv_mode", "ow_mode", "ow_fromVis", "ow_startS", "ow_clockPpm", "ow_offsetHz",
            "redec_entete", "redec_syncs", "redec_ppm", "redec_ecartHz", "redec_startS", "live_replay_mode", "live_replay_vis", "live_replay_startS")
        for (m in methodes) tete += listOf("${m}_found", "${m}_lines", "${m}_mark", "${m}_slips", "${m}_spoilt", "${m}_missing", "${m}_dist", "${m}_distfull", "${m}_dist_live", "${m}_dist_redec", "${m}_dist_ow")
        csv.append(tete.joinToString(",")).append('\n')
        fun sg(x: Double) = String.format(java.util.Locale.ROOT, "%.2f", x)
        fun f(x: Number?) = if (x == null || x.toDouble().isNaN() || x.toDouble() < 0) "" else String.format(java.util.Locale.ROOT, "%.2f", x.toDouble())
        for (p in ps) {
            val s = sat[p.stem]!!; val o = ow[p.stem]
            val row = mutableListOf(p.stem, p.cle, if (p.jumeau) "1" else "0", diapo[p.stem]?.toString() ?: "",
                f(s.optDouble("snr_db", Double.NaN)), f(s.optDouble("redec_bruitHz", Double.NaN)), f(o?.optDouble("lockPercent")), (owN[p.stem] ?: 0).toString(), (qsN[p.stem] ?: 0).toString(), qs[p.stem]?.getString("mode") ?: "",
                o?.getString("mode") ?: "", o?.getBoolean("fromVis")?.toString() ?: "", f(o?.optDouble("startS")), o?.optDouble("clockPpm")?.toString() ?: "", o?.optDouble("offsetHz")?.toString() ?: "",
                s.optBoolean("redec_entete").toString(), f(s.optDouble("redec_syncs", Double.NaN)), s.optDouble("redec_ppm", Double.NaN).let { if (it.isNaN()) "" else it.toString() }, f(s.optDouble("redec_ecartHz", Double.NaN)), f(s.optDouble("redec_debutS", Double.NaN)),
                s.optString("live_mode"), s.optBoolean("live_vis").toString(), f(s.optDouble("live_debutS", Double.NaN)))
            for (m in methodes) {
                val i = p.v[m]; val a = an[p.stem]!![m]
                if (i == null || a == null) { row += listOf("0", "", "", "", "", "", "", "", "", "", ""); continue }
                fun dd(pk: String, plein: Boolean = false) = refs[p.stem to pk]?.let { if (plein) ecartPlein(it, i) else ecart(it, i) }
                row += listOf("1", i.lignes.toString(), a.note.toString(), a.glissements.toString(), f(a.abimees), f(a.manque),
                    f(dd("neutre")), f(dd("neutre", true)), f(dd("live")), f(dd("redec")), f(dd("owp")))
            }
            csv.append(row.joinToString(",")).append('\n')
        }
        File(if (sortie == "out") d else out, "resultats.csv").writeText(csv.toString())

        // Side-by-side sheets: saved live | re-decode + correction | OscarWatch | QSSTV | the reference (other copies fused).
        val planches = File(if (sortie == "out") d else out, "planches"); planches.mkdirs()
        val cols = (if (v1 != null) listOf("satme_live_v1" to "SatMe live (before)", "satme_live" to "SatMe live (new)", "satme_redec_v1" to "re-decode (before)", "satme_redec" to "re-decode (new)")
            else listOf("live_saved" to "SatMe live (saved)", "satme_redec" to "SatMe re-decode+corr.")) + listOf("ow" to "OscarWatch 1.1.4") +
            (if (qs.isNotEmpty()) listOf("qsstv" to "QSSTV 9.5.8") else emptyList())
        for (p in ps) {
            val n = cols.size + 1
            val W = 320 * n + 8 * (n - 1); val Hh = 240 + 34
            val b = BufferedImage(W, Hh, BufferedImage.TYPE_INT_RGB)
            val g = b.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(24, 24, 28); g.fillRect(0, 0, W, Hh)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            val ref = refs[p.stem to "neutre"]
            for ((k, c) in (cols + ("ref" to "reference (other copies fused)")).withIndex()) {
                val i = if (c.first == "ref") ref else p.v[c.first]
                if (i != null) b.setRGB(k * 328, 34, 320, 240, i.px, 0, 320)
                g.color = Color.WHITE; g.drawString(c.second, k * 328 + 4, 14)
                val a = if (c.first == "ref") null else an[p.stem]!![c.first]
                val txt = when {
                    c.first == "ref" -> if (ref == null) "no reference" else "slide ${diapo[p.stem]}"
                    i == null || a == null -> "not found"
                    else -> "mark ${a.note}  slips ${a.glissements}  dist ${ref?.let { f(ecart(it, i)) } ?: "-"}"
                }
                g.color = Color(255, 210, 120); g.drawString(txt, k * 328 + 4, 29)
            }
            g.dispose()
            ImageIO.write(b, "png", File(planches, p.stem + ".png"))
        }
        // Diagnostic: on clean pictures, how OscarWatch's rendering differs from SatMe's (colour bias, best shift).
        val diagTxt = StringBuilder("stem,a,b,meanR,meanG,meanB,absBefore,bestDx,bestDy,absAfter,absY,absChroma\n")
        for (p in ps.filter { !it.jumeau && (sat[it.stem]!!.optDouble("snr_db", 0.0)) > 23.0 }) for ((a, b) in listOf("satme_redec" to "ow", "live_saved" to "ow", "live_saved" to "satme_redec", "satme_redec" to "qsstv", "ow" to "qsstv")) {
            val x = p.v[a] ?: continue; val y = p.v[b] ?: continue
            fun gap(dx: Int, dy: Int): DoubleArray {
                var sr = 0.0; var sg = 0.0; var sb = 0.0; var ab = 0.0; var ay = 0.0; var ac = 0.0; var n = 0
                for (yy in 10 until 230) for (xx in 12 until 308) {
                    val p1 = x.px[yy * 320 + xx]; val q = y.px[(yy + dy) * 320 + xx + dx]
                    val dr = ((q shr 16) and 255) - ((p1 shr 16) and 255); val dg = ((q shr 8) and 255) - ((p1 shr 8) and 255); val db = (q and 255) - (p1 and 255)
                    sr += dr; sg += dg; sb += db; ab += (abs(dr) + abs(dg) + abs(db)) / 3.0
                    val dY = 0.299 * dr + 0.587 * dg + 0.114 * db; ay += abs(dY); ac += (abs(db - dY) + abs(dr - dY)) / 2; n++
                }
                return doubleArrayOf(sr / n, sg / n, sb / n, ab / n, ay / n, ac / n)
            }
            val g0 = gap(0, 0)
            var best = Triple(0, 0, g0)
            for (dx in -4..4) for (dy in -3..3) { val g = gap(dx, dy); if (g[3] < best.third[3]) best = Triple(dx, dy, g) }
            diagTxt.append(listOf(p.stem, a, b, sg(g0[0]), sg(g0[1]), sg(g0[2]), f(g0[3]), best.first, best.second, f(best.third[3]), f(best.third[4]), f(best.third[5])).joinToString(",")).append('\n')
        }
        File(out, "diag-rendu.csv").writeText(diagTxt.toString())
        // Black and white levels: each decoder's brightness where the reference is black (< 20) or white (> 220).
        val niv = StringBuilder("stem,snr_db,method,black_mean,white_mean,nblack,nwhite\n")
        for (p in ps.filter { !it.jumeau }) {
            val ref = refs[p.stem to "neutre"] ?: continue
            for (m in methodes) {
                val i = p.v[m] ?: continue
                val cg = FusionSstv.cale(copie(ref), copie(i)) ?: continue
                var sb = 0L; var nb = 0; var sw = 0L; var nw = 0
                fun lum(c: Int) = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                for (y in 0 until ref.lignes) { val yb = y + cg.dy; if (yb !in 0 until i.lignes) continue
                    for (x in 8 until 312) { val lr = lum(ref.px[y * 320 + x]); val li = lum(i.px[yb * 320 + (x + cg.dx).coerceIn(0, 319)])
                        if (lr < 20) { sb += li; nb++ } else if (lr > 220) { sw += li; nw++ } } }
                niv.append(listOf(p.stem, f(sat[p.stem]!!.optDouble("snr_db", Double.NaN)), m, if (nb > 0) sg(sb / nb.toDouble()) else "", if (nw > 0) sg(sw / nw.toDouble()) else "", nb, nw).joinToString(",")).append('\n')
            }
        }
        File(out, "diag-niveaux.csv").writeText(niv.toString())
        println("ok ${ps.size} pictures, csv and sheets written")
    }

    /** Every picture each decoder found in the whole-pass recordings, marked the same way (for matching by time afterwards). */
    @Test fun passes() {
        assumeTrue(dir != null && File(dir, "$sortie/satme-passes.jsonl").isFile)
        val out = File(dir!!, sortie)
        val base = File(dir, "out")
        val csv = StringBuilder("decoder,wav,index,mode,vis,startS,endS,lines,complete,mark,slips,spoilt,missing,png\n")
        fun ligne(dec: String, o: JSONObject, png: File, lignes: Int?, complete: Boolean, startS: Double, endS: Double, vis: String) {
            val i = lit(png, lignes, complete) ?: return
            val a = analyse(i)
            csv.append(listOf(dec, o.getString("wav"), o.optInt("index"), o.optString("mode"), vis, String.format(java.util.Locale.ROOT, "%.1f", startS), String.format(java.util.Locale.ROOT, "%.1f", endS),
                i.lignes, complete, a.note, a.glissements, String.format(java.util.Locale.ROOT, "%.3f", a.abimees), String.format(java.util.Locale.ROOT, "%.3f", a.manque), png.name).joinToString(",")).append('\n')
        }
        fun ligneQ(dec: String, o: JSONObject, png: File, complete: Boolean, startS: Double, endS: Double, vis: String) {
            val i = lit(png, null, complete)?.let(::sansFond) ?: return
            val a = analyse(i)
            csv.append(listOf(dec, o.getString("wav"), o.optInt("index"), o.optString("mode"), vis, String.format(java.util.Locale.ROOT, "%.1f", startS), String.format(java.util.Locale.ROOT, "%.1f", endS),
                i.lignes, complete, a.note, a.glissements, String.format(java.util.Locale.ROOT, "%.3f", a.abimees), String.format(java.util.Locale.ROOT, "%.3f", a.manque), png.name).joinToString(",")).append('\n')
        }
        for (o in jsonl(File(out, "satme-passes.jsonl"))) {
            val l = o.getInt("lignes"); val st = o.getDouble("startS")
            ligne("satme_live", o, File(out, "satme-passes/" + o.getString("png")), l, o.getBoolean("complete"), st, st + l * 0.150 + 0.3, o.getBoolean("vis").toString())
        }
        if (v1 != null) for (o in jsonl(File(dir, "$v1/satme-passes.jsonl"))) {
            val l = o.getInt("lignes"); val st = o.getDouble("startS")
            ligne("satme_live_v1", o, File(dir, "$v1/satme-passes/" + o.getString("png")), l, o.getBoolean("complete"), st, st + l * 0.150 + 0.3, o.getBoolean("vis").toString())
        }
        for (o in jsonl(File(base, "ow-passes/oscarwatch.jsonl")).filter { !it.optBoolean("summary") })
            ligne("ow", o, File(base, "ow-passes/" + o.getString("png")), o.getInt("decodedLines"), o.getBoolean("complete"), o.getDouble("startS"), o.getDouble("endS"), o.getBoolean("fromVis").toString())
        for (o in jsonl(File(base, "qsstv-passes/qsstv.jsonl")))
            ligneQ("qsstv", o, File(base, "qsstv-passes/" + o.getString("png")), o.optBoolean("complete", false), o.optDouble("startS", Double.NaN), o.getDouble("endS"), "")
        File(out, "passes-images.csv").writeText(csv.toString())
        println("passes ok")
    }

    /** The live decoder's line by line view (found / clean pulse) on a few sounds (SATME_CMP_TRACE=stem,stem). */
    @Test fun trace() {
        val l = System.getenv("SATME_CMP_TRACE")?.split(",") ?: emptyList()
        assumeTrue(dir != null && l.isNotEmpty())
        for (stem in l) {
            val (pcm, rate) = SstvSon.litWav(File(dir, "wav-images/$stem.wav"))!!
            val sb = StringBuilder()
            val d = SstvDecoder(rate, object : SstvDecoder.Listener {}).also { it.continuous = true }
            val fP = SstvDecoder::class.java.getDeclaredField("blockPeriod").also { it.isAccessible = true }
            val fN = SstvDecoder::class.java.getDeclaredField("nominalPeriod").also { it.isAccessible = true }
            val ppm = StringBuilder()
            d.mesureLigne = { b, _, t, n, bruit -> sb.append(if (n) 'N' else if (t) 't' else '.') ; if (b % 60 == 59) sb.append(' ')
                if (b % 8 == 0) ppm.append("$b:${((fP.getDouble(d) / fN.getDouble(d) - 1) * 1e6).toInt()} ") }
            var i = 0
            while (i < pcm.size) { val k = minOf(4096, pcm.size - i); d.feed(pcm.copyOfRange(i, i + k), k); i += k }
            d.finish()
            println("TRACE $stem $sb")
            println("PPM $stem $ppm")
        }
    }
}
