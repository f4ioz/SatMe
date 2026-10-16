/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The DATV stream's sound as PCM, for the subtitles: an ffmpeg of its own
 * reads the same transport stream blocks as the players and gives its first
 * sound track as 16-bit mono at 16 kHz, handed to [ecoute] by 100 ms blocks.
 */
class SonDatvPc(
    private val ffmpeg: String,
    private val ecoute: (ShortArray, Int, Int) -> Unit,
) {
    @Volatile private var processus: Process? = null

    /** Where the stream's blocks go; null once stopped. */
    val entree: OutputStream? get() = processus?.takeIf { it.isAlive }?.outputStream

    val enMarche: Boolean get() = processus?.isAlive == true

    /** Starts ffmpeg; false when it does not start. */
    fun demarre(): Boolean {
        val p = runCatching {
            ProcessBuilder(commande(ffmpeg))
                .redirectError(File(System.getProperty("java.io.tmpdir"), "satme-datv-son.log")).start()
        }.getOrNull() ?: return false
        processus = p
        thread(isDaemon = true, name = "satme-datv-son") { lis(p.inputStream) }
        return true
    }

    fun arrete() {
        val p = processus ?: return
        processus = null
        runCatching { p.outputStream.close() }
        p.destroy()
        if (!runCatching { p.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) p.destroyForcibly()
    }

    private fun lis(e: InputStream) {
        val b = ByteArray(BLOC * 2)
        var n = 0
        while (true) {
            val k = runCatching { e.read(b, n, b.size - n) }.getOrDefault(-1)
            if (k < 0) break
            n += k
            if (n < b.size) continue
            val pcm = ShortArray(BLOC) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort() }
            runCatching { ecoute(pcm, BLOC, RATE) }
            n = 0
        }
    }

    companion object {
        const val RATE = 16_000
        private const val BLOC = RATE / 10

        fun commande(ffmpeg: String): List<String> = listOf(ffmpeg, "-loglevel", "error", "-fflags", "nobuffer",
            "-probesize", "200000", "-analyzeduration", "1000000", "-f", "mpegts", "-i", "pipe:0",
            "-map", "0:a:0", "-vn", "-ac", "1", "-ar", RATE.toString(), "-f", "s16le", "pipe:1")
    }
}
