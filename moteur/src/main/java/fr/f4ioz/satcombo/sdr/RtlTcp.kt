/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An RTL-SDR dongle reached through `rtl_tcp` (the dongle on this computer,
 * or on another one of the network): the same few operations the phone's
 * USB driver offers, over a socket.
 *
 * **The protocol.** On connecting, 12 bytes: "RTL0", the tuner type and the
 * number of gains (big-endian). Then the IQ stream, unsigned 8-bit I then Q
 * — exactly what [RxChain.process] takes. Commands are 5 bytes: one for the
 * command, four for its parameter, big-endian. Nothing comes back: a
 * frequency asked for is assumed to be the one obtained.
 *
 * **Read continuously.** rtl_tcp drops what a slow client does not take,
 * and a gap of a few milliseconds breaks an SSTV picture: a thread of its
 * own reads the socket into a queue, the processing takes from it.
 *
 * **Whole IQ pairs only.** A socket read may end on an odd byte; handed out
 * as it is, I and Q would swap from then on (the spectrum turned over).
 * The odd byte waits for the next block.
 */
class RtlTcp private constructor(private val socket: Socket, val tuner: Int, val nbGains: Int) : SourceIq {

    private val sortie: OutputStream = socket.getOutputStream()
    private val file = ArrayBlockingQueue<ByteArray>(PROFONDEUR)
    @Volatile var ouvert = true; private set
    @Volatile override var perdus = 0L; private set
    @Volatile override var octets = 0L; private set

    private val lecteur = thread(isDaemon = true, name = "satme-rtl-tcp") {
        val entree = socket.getInputStream()
        var reste: Byte? = null
        try {
            while (ouvert) {
                val b = ByteArray(BLOC + 1)
                var n = 0
                reste?.let { b[0] = it; n = 1; reste = null }
                val lu = entree.read(b, n, BLOC)
                if (lu < 0) break
                n += lu
                if (n % 2 == 1) { reste = b[n - 1]; n-- }
                if (n == 0) continue
                octets += n
                val bloc = if (n == b.size) b else b.copyOf(n)
                // Never block the socket: the oldest block goes when the processing is late.
                if (!file.offer(bloc)) { file.poll(); perdus++; file.offer(bloc) }
            }
        } catch (_: IOException) {
        } finally {
            ouvert = false
        }
    }

    private fun commande(code: Int, param: Int) {
        val b = byteArrayOf(code.toByte(), (param ushr 24).toByte(), (param ushr 16).toByte(), (param ushr 8).toByte(), param.toByte())
        synchronized(sortie) { sortie.write(b); sortie.flush() }
    }

    override fun setSampleRate(rate: Int): Double { commande(0x02, rate); return rate.toDouble() }
    /** The centre frequency; rtl_tcp says nothing back, so the request is returned. */
    override fun setCenterFreq(hz: Long): Long { commande(0x01, hz.toInt()); return hz }
    override fun setFreqCorrection(ppm: Int) = commande(0x05, ppm)
    /** The RTL2832's digital AGC. */
    override fun setAgc(on: Boolean) = commande(0x08, if (on) 1 else 0)
    /** The tuner's gain in tenths of dB (the nearest of its table), or null: automatic. */
    override fun setGain(tenthDb: Int?) {
        if (tenthDb == null) commande(0x03, 0)
        else { commande(0x03, 1); commande(0x04, tenthDb) }
    }
    /** Power on the antenna socket, for a preamplifier or an LNB (dongles that have it). */
    fun setBiasTee(on: Boolean) = commande(0x0e, if (on) 1 else 0)

    /**
     * The next block of IQ bytes (an even count) into [buf]: its length, 0
     * when nothing came within [attenteMs], -1 once the connection is gone.
     */
    override fun read(buf: ByteArray, timeoutMs: Int): Int = read(buf, timeoutMs.toLong())

    fun read(buf: ByteArray, attenteMs: Long): Int {
        val b = file.poll(attenteMs, TimeUnit.MILLISECONDS) ?: return if (ouvert || file.isNotEmpty()) 0 else -1
        val n = minOf(b.size, buf.size) and 1.inv()
        System.arraycopy(b, 0, buf, 0, n)
        return n
    }

    override fun close() {
        ouvert = false
        runCatching { socket.close() }
        file.clear()
    }

    companion object {
        const val PORT = 1234
        /** Bytes per block: 8192 IQ pairs, 7.7 ms at 1.0584 MS/s. */
        const val BLOC = 16_384
        /** About two seconds of samples waiting at most. */
        const val PROFONDEUR = 256
        const val TUNER_R820T = 5

        /** Connects and reads the dongle's header; an [IOException] says why not. */
        fun connecte(hote: String, port: Int = PORT, attenteMs: Int = 3000): RtlTcp {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(hote, port), attenteMs)
                s.tcpNoDelay = true
                s.soTimeout = attenteMs
                val e = DataInputStream(s.getInputStream())
                val magie = ByteArray(4); e.readFully(magie)
                if (String(magie, Charsets.US_ASCII) != "RTL0") throw IOException("pas un serveur rtl_tcp")
                val tuner = e.readInt(); val gains = e.readInt()
                s.soTimeout = 0
                return RtlTcp(s, tuner, gains)
            } catch (x: IOException) {
                runCatching { s.close() }
                throw x
            }
        }

        /** The tuner's name, from the header. */
        fun nomTuner(t: Int) = when (t) {
            1 -> "E4000"; 2 -> "FC0012"; 3 -> "FC0013"; 4 -> "FC2580"; 5 -> "R820T"; 6 -> "R828D"
            else -> "inconnu ($t)"
        }
    }
}
