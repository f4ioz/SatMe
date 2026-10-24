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
import kotlin.math.abs

/** A whole gallery sorted (SATME_SSTV_REEL=folder): what the grouping, the marks and the footer reading say. Skipped without it. */
class ClassementSstvReelTest {

    @Test fun une_vraie_galerie() {
        val d = System.getenv("SATME_SSTV_REEL")?.let(::File)
        assumeTrue(d != null && d.isDirectory)
        val sortie = System.getenv("SATME_SSTV_SORTIE")?.let(::File)
        val shots = d!!.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }.map { f ->
            SstvMeta.decode(f.name, File(d, SstvMeta.sidecarName(f.name)).takeIf { it.isFile }?.readText())
        }
        val an = shots.associate { s ->
            val b = ImageIO.read(File(d, s.fileName))
            val px = b.getRGB(0, 0, b.width, b.height, null, 0, b.width)
            val hm = SstvMode.byName(s.mode)?.height ?: 0
            val a = ClassementSstv.analyse(px, b.width, b.height, hm, s.complete)
            s.fileName to ClassementSstv.avecLu(a, LectureDiapo.lit(px, b.width, b.height))
        }
        val g = ClassementSstv.groupe(shots, an)
        val csv = StringBuilder()
        for ((k, gr) in g.withIndex()) {
            println("groupe $k ${gr.diapo}/${gr.total} ${gr.origine} (${gr.images.size})")
            for (s in gr.images) {
                val a = an[s.fileName]!!
                println(String.format("   %3d/%3d manque=%.2f abim=%.2f vert=%.3f gl=%d lu=%d  %s", gr.notes[s.fileName], a.note, a.manque, a.abimees, a.vert, a.glissements, a.lu, s.fileName))
                csv.append("$k;${gr.notes[s.fileName]};${a.lu};${s.fileName}\n")
            }
        }
        sortie?.writeText(csv.toString())
        verite(shots, an)
        // The footer read: right, wrong, not read; and with the slide's own pattern taken away (as 5 and 6 are).
        var juste = 0; var faux = 0; var rien = 0; var fauxSans = 0; var autres = 0
        for (s in shots) {
            val v = vu(s.fileName); val lu = an[s.fileName]!!.lu
            if (v == null) { if (lu != 0) { autres++; println("  lu $lu sur ${s.fileName}") }; continue }
            when { lu == v -> juste++; lu == 0 -> rien++; else -> { faux++; println("  FAUX $lu au lieu de $v : ${s.fileName}") } }
            val b = ImageIO.read(File(d, s.fileName))
            val gg = LectureDiapo.gris(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height)
            val sans = LectureDiapo.lit(gg, LectureDiapo.NOMBRES - v)
            if (sans != 0) { fauxSans++; println("  sans son motif : $sans au lieu de $v : ${s.fileName}") }
        }
        println("lecture : justes $juste, fausses $faux, non lues $rien ; sans son motif, fausses $fauxSans ; lues hors série 34 : $autres")
        if (System.getenv("SATME_SSTV_DEBUG") != null) for (gr in g.filter { it.images.size <= 2 }) for (s in gr.images) {
            val a = an[s.fileName]!!
            val d = g.filter { it !== gr }.map { o -> o.id.takeLast(30) to o.images.mapNotNull { an[it.fileName] }.sortedByDescending { it.note }.take(4).maxOf { ClassementSstv.ressemblance(a, it) ?: -1f } }
                .sortedByDescending { it.second }.take(3)
            println("  près ${s.fileName.takeLast(34)} bonnes=${a.bonnes.count { it }} : " + d.joinToString { it.first + "=" + "%.2f".format(it.second) })
        }
    }

    /** Series 34 as read by eye (23–24 Oct 2026): picture time → slide. */
    /** The series 34 timetable from the gallery, the ISS passes to come (SATME_TLE=file with the ISS elements). */
    @Test fun previsions_serie_34() {
        val d = System.getenv("SATME_SSTV_REEL")?.let(::File)
        val tle = System.getenv("SATME_TLE")?.let(::File)
        assumeTrue(d != null && d.isDirectory && tle != null && tle.isFile)
        val shots = d!!.listFiles { f -> f.name.endsWith(".png") && f.name.contains("ISS_2026102") }!!.sortedBy { it.name }.map { f ->
            SstvMeta.decode(f.name, File(d, SstvMeta.sidecarName(f.name)).takeIf { it.isFile }?.readText())
        }
        val an = shots.associate { s ->
            val b = ImageIO.read(File(d, s.fileName)); val px = b.getRGB(0, 0, b.width, b.height, null, 0, b.width)
            s.fileName to ClassementSstv.avecLu(ClassementSstv.analyse(px, b.width, b.height, 240, s.complete), LectureDiapo.lit(px, b.width, b.height))
        }
        val g = ClassementSstv.groupe(shots, an)
        val reperes = ClassementSstv.reperes(g, an)
        val m = HoraireDiapos.ajuste(reperes, 12, HoraireDiapos.periode("36 s on / 2 min off"))!!
        println("modèle : $m")
        val l = tle!!.readLines()
        val i = l.indexOfFirst { it.startsWith("1 25544") }
        val e = fr.f4ioz.satcombo.data.TleEntry("ISS", l[i], l[i + 1])
        val (la, lo) = fr.f4ioz.satcombo.location.Maidenhead.toLatLon("JN18FS")!!
        val obs = fr.f4ioz.satcombo.data.Observer(la, lo, 50.0, "JN18FS")
        val maintenant = System.currentTimeMillis()
        val passes = fr.f4ioz.satcombo.domain.PassPredictor().upcomingPasses(e, obs, maintenant - 3_600_000L, 72, 0.0).also { println("  bruts " + it.size + " dernier " + java.util.Date(it.last().aosEpochMs)) }
            .filter { it.aosEpochMs < 1793036700000L }
        // Check the elements against the passes heard.
        val faits = fr.f4ioz.satcombo.domain.PassPredictor().upcomingPasses(e, obs, 1792620000000L, 40, 0.0)
        for (s in shots.filter { it.complete }) println("  image ${s.fileName.drop(15).take(16)} dans un passage : " +
            faits.any { s.timeMs in it.aosEpochMs..it.losEpochMs })
        val ff = java.text.SimpleDateFormat("dd/MM HH:mm:ss").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        for (p in faits + passes) println("  passage ${ff.format(p.aosEpochMs)} ${ff.format(p.losEpochMs)} ${p.maxElevationDeg.toInt()}")
        val recues = g.filter { it.diapo > 0 }.map { it.diapo }.toSet()
        val t = HoraireDiapos.tableau(m, passes, 5.0, recues, maintenant)
        val f = java.text.SimpleDateFormat("dd/MM HH:mm:ss").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        for (p in t.passages) println("  ${f.format(p.passage.aosEpochMs)}–${f.format(p.passage.losEpochMs)} ${p.passage.maxElevationDeg.toInt()}° ±${p.incertitudeS.toInt()} s : " +
            p.prevues.joinToString { (if (it.entiere) "" else "(") + it.diapo + (if (it.entiere) "" else ")") + "@" + f.format(it.debutMs).drop(6) })
        println("manquent ${t.manquantes} : " + t.prochaines.entries.joinToString { (k, v) -> "$k → " + v.take(3).joinToString { f.format(it.passage.aosEpochMs) } })
    }

    companion object { val VU = mapOf(
        "20261023_012016" to 7, "20261023_012254" to 8, "20261023_012533" to 9, "20261023_025545" to 7, "20261023_025824" to 8,
        "20261023_030103" to 9, "20261023_030342" to 10, "20261023_043121" to 7, "20261023_043401" to 8, "20261023_043634" to 9,
        "20261023_043914" to 10, "20261023_060926" to 8, "20261023_061205" to 9, "20261023_061445" to 10, "20261023_061724" to 11,
        "20261023_074609" to 8, "20261023_074848" to 9, "20261023_075127" to 10, "20261023_075405" to 11, "20261023_092405" to 9,
        "20261023_092643" to 10, "20261023_092922" to 11, "20261024_003414" to 12, "20261024_003651" to 1, "20261024_003915" to 2,
        "20261024_020657" to 11, "20261024_020928" to 12, "20261024_021207" to 1, "20261024_021445" to 2, "20261024_034447" to 12,
        "20261024_034726" to 1, "20261024_035005" to 2, "20261024_035244" to 3, "20261024_052242" to 1, "20261024_052521" to 2,
        "20261024_052759" to 3, "20261024_053038" to 4, "20261024_070317" to 3, "20261024_070556" to 4) }

    private fun part(a: ClassementSstv.Analyse, b: ClassementSstv.Analyse): Float? {
        val T = (System.getenv("T") ?: "40").toFloat()
        val communes = (0 until 24).filter { a.bonnes[it] && b.bonnes[it] }
        if (communes.size < 6) return null
        var best = 1f
        for (dx in -1..1) {
            var n = 0; var k = 0
            for (y in communes) for (x in maxOf(0, -dx) until minOf(32, 32 - dx)) {
                val i = (y * 32 + x) * 3; val j = (y * 32 + x + dx) * 3
                val d = abs(a.vignette[i] - b.vignette[j]) + 0.7f * (abs(a.vignette[i + 1] - b.vignette[j + 1]) + abs(a.vignette[i + 2] - b.vignette[j + 2]))
                n++; if (d > T) k++
            }
            best = minOf(best, k / n.toFloat())
        }
        return 1f - best
    }

    private fun vu(n: String) = Regex("_(\\d{8}_\\d{6})Z").find(n)?.groupValues?.get(1)?.let { VU[it] }

    private fun verite(shots: List<SstvMeta.SstvShot>, an: Map<String, ClassementSstv.Analyse>) {
        val l = shots.filter { vu(it.fileName) != null }
        if (l.isEmpty()) return
        val memes = ArrayList<Float>(); val autres = ArrayList<Float>()
        for (i in l.indices) for (j in i + 1 until l.size) {
            if (l[i].timeMs == l[j].timeMs) continue
            val r = ClassementSstv.ressemblance(an[l[i].fileName]!!, an[l[j].fileName]!!) ?: continue
            if (vu(l[i].fileName) == vu(l[j].fileName)) memes += r else autres += r
            if (vu(l[i].fileName) != vu(l[j].fileName) && r > 0.72f) println("  autre ${"%.2f".format(r)} ${l[i].fileName.drop(15).take(15)}(${vu(l[i].fileName)}) ${l[j].fileName.drop(15).take(15)}(${vu(l[j].fileName)})")
            if (vu(l[i].fileName) == vu(l[j].fileName) && r < 0.85f) println("  meme  ${"%.2f".format(r)} ${l[i].fileName.drop(15).take(15)}(${vu(l[i].fileName)}) ${l[j].fileName.drop(15).take(15)}(${vu(l[j].fileName)})")
        }
        memes.sort(); autres.sort()
        println("vérité : mêmes ${memes.size} min ${memes.firstOrNull()} q10 ${memes.getOrNull(memes.size / 10)} ; autres ${autres.size} max ${autres.lastOrNull()} q90 ${autres.getOrNull(autres.size * 9 / 10)}")
    }
}
