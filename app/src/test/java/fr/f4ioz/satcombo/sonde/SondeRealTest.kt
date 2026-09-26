/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.Assume
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * The decoder against real sondes.
 *
 * The bench uses a signal we generate ourselves, so it mostly proves that our
 * encoder and decoder agree. These tests use the radiosonde_auto_rx reference
 * recordings — real off-air captures — FM-demodulated and fed to the hub
 * as is. Nothing here is ours except the discriminator.
 *
 * The files are not in the repository (90 MB each); the test skips itself
 * when they are absent.
 */
class SondeRealTest {

    private fun run(name: String, rate: Int, model: String): Triple<Int, Int, Int> {
        val f = File("/home/claude/samples/$name")
        Assume.assumeTrue("échantillon absent : $name", f.exists())
        SondeHub.stop()
        SondeHub.start(null, rate, 404_000_000L, source = "TEST", model = model, log = false)
        val chunk = ShortArray(4096)
        DataInputStream(f.inputStream().buffered(1 shl 20)).use { s ->
            val raw = ByteArray(chunk.size * 2)
            while (true) {
                var got = 0
                while (got < raw.size) {
                    val k = s.read(raw, got, raw.size - got)
                    if (k <= 0) break
                    got += k
                }
                if (got < 2) break
                val n = got / 2
                for (i in 0 until n) {
                    chunk[i] = ((raw[2 * i].toInt() and 0xff) or
                        (raw[2 * i + 1].toInt() shl 8)).toShort()
                }
                SondeHub.feedLive(chunk, n)
                if (got < raw.size) break
            }
        }
        val st = SondeHub.state.value
        val fl = SondeHub.currentFlight
        SondeHub.stop()
        println("REEL $name modele=$model rate=$rate -> trames=${st.frames} " +
            "rejets=${st.rejected} serie=${fl?.serial ?: "-"} points=${fl?.count ?: 0}")
        return Triple(st.frames, st.rejected, fl?.count ?: 0)
    }

    @Test fun m10_96k() { run("m10_96000.pcm", 96_000, "M10") }
    @Test fun m10_48k() { run("m10_48000.pcm", 48_000, "M10") }
    @Test fun m10_44k() { run("m10_44100.pcm", 44_100, "M10") }
    @Test fun rs41_96k() { run("rs41_96000.pcm", 96_000, "RS41") }
    @Test fun rs41_48k() { run("rs41_48000.pcm", 48_000, "RS41") }
    @Test fun rs41_44k() { run("rs41_44100.pcm", 44_100, "RS41") }
}
