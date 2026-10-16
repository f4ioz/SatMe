/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class SourcesVideoDatvTest {

    private val ts = 429_832L

    @Test fun `la mire fixe garde la meme commande`() {
        val l = EmissionDatvPc.commande("ff", EmissionDatvPc.ParametresTs(ts, "mire", File("/m.png"), "", "F4IOZ", false, true))
        assertTrue(l.containsAll(listOf("-loop", "1", "-framerate", "10")))
        assertEquals(listOf("-map", "0:v:0", "-map", "1:a:0"), l.subList(l.indexOf("0:v:0") - 1, l.indexOf("0:v:0") + 3))
    }

    @Test fun `la mire animee et le fichier video`() {
        val a = EmissionDatvPc.commande("ff", EmissionDatvPc.ParametresTs(ts, "animee", File("/m.png"), "", "F4IOZ", false, true))
        assertTrue(a.any { it.startsWith("testsrc2=") } && a.any { it.startsWith("sine=") } && "[v]" in a && "2:a:0" in a)
        val v = EmissionDatvPc.commande("ff", EmissionDatvPc.ParametresTs(ts, "video", File("/v.mp4"), "", "F4IOZ", false, true))
        assertTrue(v.containsAll(listOf("-stream_loop", "-1", "/v.mp4", "0:a:0?")) && v.none { it.startsWith("anullsrc") })
    }

    /** With ffmpeg on this machine: each new source makes a transport stream that a reader takes as video (and sound). */
    @Test fun `ffmpeg fait le flux`() {
        val ff = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() }
        assumeTrue(ff != null && File("/usr/bin/ffprobe").canExecute())
        val dir = kotlin.io.path.createTempDirectory("satme-datv").toFile()
        try {
            val png = File(dir, "mire.png")
            run(listOf(ff!!, "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=640x360", "-frames:v", "1", png.path))
            val mp4 = File(dir, "essai.mp4")
            run(listOf(ff, "-v", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=2",
                "-f", "lavfi", "-i", "sine=duration=2", "-shortest", mp4.path))
            for ((source, entree) in listOf("animee" to png, "video" to mp4)) {
                val out = File(dir, "$source.ts")
                val c = EmissionDatvPc.commande(ff, EmissionDatvPc.ParametresTs(ts, source, entree, "", "F4IOZ", false, true))
                val p = ProcessBuilder(c.dropLast(1) + listOf("-t", "3", out.path)).redirectErrorStream(true).start()
                val journal = p.inputStream.bufferedReader().readText()
                assertTrue("$source : $journal", p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
                val probe = ProcessBuilder("/usr/bin/ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name",
                    "-of", "csv=p=0", out.path).redirectErrorStream(true).start()
                val vu = probe.inputStream.bufferedReader().readText()
                assertTrue("$source : $vu", "h264,video" in vu && "aac,audio" in vu)
            }
        } finally { dir.deleteRecursively() }
    }

    private fun run(c: List<String>) {
        val p = ProcessBuilder(c).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { c.joinToString(" ") }
    }
}
