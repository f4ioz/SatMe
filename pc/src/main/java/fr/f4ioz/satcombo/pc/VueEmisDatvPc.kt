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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The DATV picture being transmitted, seen on the page: the transport
 * stream going to the modulator, copied to a decoder of its own (a few
 * small JPEGs a second). Started when the page asks for the picture while
 * transmitting, stopped when nobody asked for a while ([repos]) or at the
 * transmission's end.
 *
 * Display only: nothing here is transmitted, and the copy never waits —
 * the packets the decoder cannot take now are dropped from the copy, the
 * stream to the modulator untouched.
 */
class VueEmisDatvPc(
    /** Starts the decoder: ffmpeg here, something else in the tests; null when it cannot. */
    private val fabrique: () -> Decodeur? = { DecodeurFfmpeg.demarre() },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** No request from the page for this long: the decoder stopped. */
    private val repos: Long = 10_000L,
    capacite: Int = 2048,
) {
    /** The TS goes in, JPEGs one after the other come out. */
    interface Decodeur {
        val entree: OutputStream
        val sortie: InputStream
        val vivant: Boolean
        fun arrete()
    }

    private val file = ArrayBlockingQueue<ByteArray>(capacite)
    /** The last picture (JPEG), or null. */
    @Volatile var image: ByteArray? = null; private set
    /** Pictures made since the decoder started. */
    @Volatile var images = 0L; private set
    /** Packets left out of the copy (the decoder behind). */
    @Volatile var ecartes = 0L; private set
    @Volatile private var emet = false
    @Volatile private var demandeMs = 0L
    @Volatile private var essaiMs = 0L
    @Volatile private var decodeur: Decodeur? = null
    @Volatile private var generation = 0

    /** The decoder runs. */
    val actif: Boolean get() = decodeur != null

    /** A packet sent (188 bytes): copied while the decoder runs, else ignored; never blocks. */
    fun paquet(p: ByteArray) {
        if (decodeur == null || p.size != 188 || p[0].toInt() != 0x47) return
        // Null packets: nothing to see.
        if (((p[1].toInt() and 0x1F) shl 8 or (p[2].toInt() and 0xFF)) == 0x1FFF) return
        if (!file.offer(p)) ecartes++
    }

    /** The transmission started or stopped (then the decoder stopped, the picture forgotten). */
    fun emission(on: Boolean) {
        emet = on
        if (!on) arrete()
    }

    /** The page asks for the picture: the decoder started if needed (while transmitting only); the last one, or null. */
    fun demande(): ByteArray? {
        demandeMs = horloge()
        if (emet && decodeur == null) demarre()
        return image
    }

    /** Each second: the decoder stopped when nobody looks, or when it died. */
    fun tic() {
        val d = decodeur ?: return
        if (!emet || horloge() - demandeMs > repos || !d.vivant) arrete()
    }

    @Synchronized
    private fun demarre() {
        if (decodeur != null || !emet) return
        // A decoder that would not start: tried again every 5 s, not on each request.
        val t = horloge()
        if (essaiMs != 0L && t - essaiMs < 5000L) return
        essaiMs = t
        file.clear()
        val d = runCatching { fabrique() }.getOrNull() ?: return
        val gen = ++generation
        decodeur = d
        thread(isDaemon = true, name = "satme-datv-vue-ts") { alimente(d, gen) }
        thread(isDaemon = true, name = "satme-datv-vue-images") { litImages(d, gen) }
    }

    /** Stops the decoder (in the background: never holding up the caller); the picture forgotten. */
    @Synchronized
    fun arrete() {
        generation++
        decodeur?.let { d -> thread(isDaemon = true, name = "satme-datv-vue-arret") { runCatching { d.arrete() } } }
        decodeur = null
        file.clear()
        image = null; images = 0L
        essaiMs = 0L
    }

    private fun alimente(d: Decodeur, gen: Int) {
        val b = ByteArray(188 * 64)
        while (generation == gen) {
            val p = runCatching { file.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            System.arraycopy(p, 0, b, 0, 188)
            var n = 188
            while (n < b.size) { val q = file.poll() ?: break; System.arraycopy(q, 0, b, n, 188); n += 188 }
            if (runCatching { d.entree.write(b, 0, n); d.entree.flush() }.isFailure) break
        }
    }

    private fun litImages(d: Decodeur, gen: Int) {
        val c = DatvPc.Decoupe()
        val b = ByteArray(65_536)
        while (generation == gen) {
            val n = runCatching { d.sortie.read(b) }.getOrDefault(-1)
            if (n < 0) break
            c.ajoute(b, n) { img -> if (generation == gen) { image = img; images++ } }
        }
    }

    /** ffmpeg as the decoder, at a low priority: a single thread, 5 small pictures a second. */
    object DecodeurFfmpeg {
        fun commande(ff: String, nice: String? = null): List<String> {
            val l = ArrayList<String>()
            if (nice != null) l += listOf(nice, "-n", "10")
            l += listOf(ff, "-hide_banner", "-loglevel", "error", "-threads", "1", "-fflags", "nobuffer", "-flags", "low_delay",
                "-probesize", "200000", "-analyzeduration", "1000000", "-f", "mpegts", "-i", "pipe:0",
                "-map", "0:v:0", "-an", "-sn", "-vf", "fps=5,scale=480:-2", "-q:v", "7",
                "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1")
            return l
        }

        fun demarre(): Decodeur? {
            val ff = RenduPc.ffmpeg() ?: return null
            val nice = if (OutilsPc.windows) null else OutilsPc.trouve("nice")
            val pr = ProcessBuilder(commande(ff, nice))
                .redirectError(File(System.getProperty("java.io.tmpdir"), "satme-datv-vue-ffmpeg.log")).start()
            return object : Decodeur {
                override val entree: OutputStream = pr.outputStream
                override val sortie: InputStream = pr.inputStream
                override val vivant: Boolean get() = pr.isAlive
                override fun arrete() { runCatching { pr.outputStream.close() }; pr.destroy(); if (!pr.waitFor(2, TimeUnit.SECONDS)) pr.destroyForcibly() }
            }
        }
    }
}
