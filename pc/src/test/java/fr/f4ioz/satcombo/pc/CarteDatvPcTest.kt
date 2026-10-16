/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.CarteStation
import fr.f4ioz.satcombo.datv.CarteTs
import fr.f4ioz.satcombo.datv.InserteurCarte
import fr.f4ioz.satcombo.datv.LecteurCarte
import fr.f4ioz.satcombo.datv.LigneChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The station card in a stream made by the real chain (ffmpeg with
 * SatMe's command line), offline: put in, read back, and the picture and
 * sound still read the same by ffprobe.
 */
class CarteDatvPcTest {

    private fun ffprobe(probe: String, f: File, vararg a: String): String =
        ProcessBuilder(listOf(probe, "-v", "error") + a.toList() + f.path).redirectErrorStream(true).start()
            .let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor(30, TimeUnit.SECONDS) } }

    @Test
    fun flux_ffmpeg_carte_inseree_relue_image_et_son_intacts() {
        val ff = RenduPc.ffmpeg()?.takeIf { File(it).canExecute() }
        val probe = ff?.let { File(File(it).parentFile, "ffprobe") }?.takeIf { it.canExecute() }?.path
        assumeTrue("ffmpeg absent", ff != null && probe != null)
        val dossier = File(System.getProperty("java.io.tmpdir"), "satme-test-carte-" + System.nanoTime()).apply { mkdirs() }
        try {
            val png = File(dossier, "mire.png")
            javax.imageio.ImageIO.write(MirePc.carte(640, 360, "F4IOZ", "JN18FS", "DATV"), "png", png)
            val debit = 440_000L
            val p = EmissionDatvPc.ParametresTs(debit, "mire", png, "", "F4IOZ", hevc = false, son = true)
            // Three seconds of ffmpeg's output (it runs in real time).
            val pr = ProcessBuilder(EmissionDatvPc.commande(ff!!, p)).redirectError(File(dossier, "ffmpeg.log")).start()
            val brut = ByteArrayOutputStream()
            val b = ByteArray(65_536)
            val fin = System.currentTimeMillis() + 3_500
            while (System.currentTimeMillis() < fin) { val n = pr.inputStream.read(b); if (n < 0) break; brut.write(b, 0, n) }
            pr.destroy(); pr.waitFor(5, TimeUnit.SECONDS)
            val tout = brut.toByteArray()
            val n = tout.size / 188
            assertTrue("$n paquets", n > 500)
            // The packets in turn, the clock that of the stream itself.
            var t = 0L
            val ins = InserteurCarte({ t })
            ins.carte = CarteStation("F4IOZ", "JN18FS", "Olivier", "", "10 W", "", "SatMe-PC essai", 2_409_750L, "QPSK 2/3", 333, "73")
            ins.ajoute(LigneChat(42, "F4IOZ", "Bonjour depuis le flux"))
            val sortie = ByteArrayOutputStream()
            var nuls = 0
            for (k in 0 until n) {
                val paquet = tout.copyOfRange(k * 188, k * 188 + 188)
                assertEquals(0x47, paquet[0].toInt())
                if (CarteTs.pid(paquet) == CarteTs.PID_NUL) nuls++
                t = k * 188L * 8 * 1000 / debit
                sortie.write(ins.traite(paquet))
            }
            val modifie = sortie.toByteArray()
            assertEquals(n * 188, modifie.size)
            assertTrue("$nuls nuls", nuls > 20)
            assertTrue(ins.remplaces > 0)
            // No other PID than ours took the place of a null packet.
            for (k in 0 until n) {
                val a = CarteTs.pid(tout, k * 188); val m = CarteTs.pid(modifie, k * 188)
                if (a != CarteTs.PID_NUL) assertEquals(a, m)
            }
            val l = LecteurCarte()
            l.bloc(modifie)
            assertEquals("F4IOZ", l.carte?.indicatif)
            assertEquals("JN18FS", l.carte?.locator)
            assertEquals(listOf("Bonjour depuis le flux"), l.chat().map { it.ligne.texte })
            // ffprobe: the same streams, the same video and audio packets.
            val avant = File(dossier, "avant.ts").apply { writeBytes(tout.copyOf(n * 188)) }
            val apres = File(dossier, "apres.ts").apply { writeBytes(modifie) }
            fun flux(f: File) = ffprobe(probe!!, f, "-show_entries", "stream=codec_name,nb_read_packets", "-count_packets", "-of", "csv=p=0")
            val fa = flux(avant)
            assertTrue(fa, fa.contains("h264") && fa.contains("aac"))
            assertEquals(fa, flux(apres))
            // Decoded whole by ffmpeg: what it says of one, it says of the other.
            fun decode(f: File) = ProcessBuilder(ff, "-v", "error", "-i", f.path, "-f", "null", "-").redirectErrorStream(true).start()
                .let { pr2 -> pr2.inputStream.bufferedReader().readText().also { pr2.waitFor(60, TimeUnit.SECONDS) } }
            assertEquals(decode(avant), decode(apres))
        } finally {
            dossier.deleteRecursively()
        }
    }
}
