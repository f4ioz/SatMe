/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The stream between a SatMe-Node and SatMe-PC, on one TCP connection
 * after its HTTP request: I/Q one way, commands and « still there » the
 * other.
 *
 * - **Preamble** (once, from whoever opens the stream): `SMIQ`, the
 *   version, then a JSON object (rate, centre, format, device…).
 * - **Frames**, a 32-byte header then their payload, little-endian:
 *   `SMF` + type, sequence (u32), the sender's clock (ms), centre (Hz),
 *   rate (S/s), count. Types: [IQ] (count signed 16-bit values, I then Q),
 *   [TEXTE] (count bytes of UTF-8: a command or a status line), [VIE]
 *   (nothing: the sender is still there).
 * - **Sequence**: one counter per sender, every frame; a frame dropped
 *   (the network slower than the radio) is a gap the reader counts.
 */
object FluxNoeud {
    const val VERSION = 1
    const val ENTETE = 32
    const val IQ = 'I'
    const val TEXTE = 'T'
    const val VIE = 'V'
    /** The largest payload accepted (values or bytes): a corrupt header is never a gigabyte allocation. */
    const val MAX = 1 shl 20
    /** The I/Q format sent: signed 16-bit little-endian pairs. */
    const val FORMAT = "cs16le"

    fun ecritPreambule(o: OutputStream, j: JSONObject) {
        val b = j.toString().toByteArray(Charsets.UTF_8)
        val t = ByteArray(12 + b.size)
        "SMIQ".toByteArray(Charsets.US_ASCII).copyInto(t)
        t[4] = VERSION.toByte()
        ecrisInt(t, 8, b.size)
        b.copyInto(t, 12)
        o.write(t); o.flush()
    }

    fun litPreambule(i: InputStream): JSONObject {
        val t = ByteArray(12)
        litTout(i, t, 12)
        if (String(t, 0, 4, Charsets.US_ASCII) != "SMIQ") throw IOException("flux du node illisible (en-tête)")
        if (t[4].toInt() != VERSION) throw IOException("flux du node en version ${t[4]} : mettre SatMe-PC et le node à la même version")
        val n = litInt(t, 8)
        if (n !in 2..65_536) throw IOException("flux du node illisible (préambule)")
        val b = ByteArray(n)
        litTout(i, b, n)
        return JSONObject(String(b, Charsets.UTF_8))
    }

    /** One received frame. [iq] holds [n] values for [IQ]; [texte] the line for [TEXTE]. */
    class Trame(val type: Char, val seq: Long, val tMs: Long, val centreHz: Long, val debit: Int, val n: Int,
                val iq: ShortArray?, val texte: String?)

    /** Writes frames; safe from several threads (a frame is never cut by another). */
    class Ecrivain(private val o: OutputStream, private val horloge: () -> Long = System::currentTimeMillis) {
        private val seq = java.util.concurrent.atomic.AtomicLong()
        private var tampon = ByteArray(ENTETE + 2 * 16_384)
        /** Frame numbers used so far (written or dropped). */
        val ecrites: Long get() = seq.get()

        /**
         * A frame number used without writing it (a frame dropped on purpose):
         * the reader sees the gap. Never waits for a write under way.
         */
        fun saute() { seq.incrementAndGet() }

        @Synchronized fun iq(v: ShortArray, n: Int, centreHz: Long, debit: Int, tMs: Long = horloge()) {
            val m = n and 1.inv()
            val t = place(ENTETE + 2 * m)
            entete(t, IQ, tMs, centreHz, debit, m)
            var p = ENTETE
            for (k in 0 until m) { val x = v[k].toInt(); t[p] = x.toByte(); t[p + 1] = (x shr 8).toByte(); p += 2 }
            o.write(t, 0, p)
            o.flush()
        }

        @Synchronized fun texte(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            require(b.size <= MAX)
            val t = place(ENTETE + b.size)
            entete(t, TEXTE, horloge(), 0L, 0, b.size)
            b.copyInto(t, ENTETE)
            o.write(t, 0, ENTETE + b.size)
            o.flush()
        }

        @Synchronized fun vie() {
            val t = place(ENTETE)
            entete(t, VIE, horloge(), 0L, 0, 0)
            o.write(t, 0, ENTETE)
            o.flush()
        }

        private fun place(n: Int): ByteArray { if (tampon.size < n) tampon = ByteArray(n); return tampon }

        private fun entete(t: ByteArray, type: Char, tMs: Long, centreHz: Long, debit: Int, n: Int) {
            t[0] = 'S'.code.toByte(); t[1] = 'M'.code.toByte(); t[2] = 'F'.code.toByte(); t[3] = type.code.toByte()
            ecrisInt(t, 4, seq.getAndIncrement().toInt())
            ecrisLong(t, 8, tMs); ecrisLong(t, 16, centreHz)
            ecrisInt(t, 24, debit); ecrisInt(t, 28, n)
        }
    }

    /** Reads frames and counts those missing from the sequence. */
    class Lecteur(private val i: InputStream) {
        private val h = ByteArray(ENTETE)
        private var octets = ByteArray(2 * 16_384)
        private var attendue = -1L
        /** Frames missing so far (the sender dropped them). */
        @Volatile var perdues = 0L; private set
        /** Bytes read so far. */
        @Volatile var lus = 0L; private set

        /** The next frame; an [EOFException] at the stream's end, an [IOException] when it is not one. */
        fun lit(): Trame {
            litTout(i, h, ENTETE)
            if (h[0] != 'S'.code.toByte() || h[1] != 'M'.code.toByte() || h[2] != 'F'.code.toByte()) throw IOException("trame du node illisible")
            val type = (h[3].toInt() and 0xFF).toChar()
            val seq = litInt(h, 4).toLong() and 0xFFFFFFFFL
            val tMs = litLong(h, 8); val centre = litLong(h, 16)
            val debit = litInt(h, 24); val n = litInt(h, 28)
            if (n < 0 || n > MAX) throw IOException("trame du node illisible (taille $n)")
            if (attendue >= 0 && seq != attendue) perdues += (seq - attendue) and 0xFFFFFFFFL
            attendue = (seq + 1) and 0xFFFFFFFFL
            lus += ENTETE
            return when (type) {
                IQ -> {
                    val m = n and 1.inv()
                    if (octets.size < 2 * n) octets = ByteArray(2 * n)
                    litTout(i, octets, 2 * n)
                    lus += 2L * n
                    val v = ShortArray(m)
                    for (k in 0 until m) v[k] = ((octets[2 * k].toInt() and 0xFF) or (octets[2 * k + 1].toInt() shl 8)).toShort()
                    Trame(type, seq, tMs, centre, debit, m, v, null)
                }
                TEXTE -> {
                    val b = ByteArray(n); litTout(i, b, n); lus += n
                    Trame(type, seq, tMs, centre, debit, n, null, String(b, Charsets.UTF_8))
                }
                VIE -> { if (n > 0) litTout(i, ByteArray(n), n); Trame(type, seq, tMs, centre, debit, 0, null, null) }
                else -> throw IOException("trame du node de type inconnu")
            }
        }
    }

    fun litTout(i: InputStream, b: ByteArray, n: Int) {
        var k = 0
        while (k < n) {
            val r = i.read(b, k, n - k)
            if (r < 0) throw EOFException("flux du node coupé")
            k += r
        }
    }

    private fun ecrisInt(t: ByteArray, p: Int, v: Int) { for (k in 0 until 4) t[p + k] = (v shr (8 * k)).toByte() }
    private fun ecrisLong(t: ByteArray, p: Int, v: Long) { for (k in 0 until 8) t[p + k] = (v shr (8 * k)).toByte() }
    private fun litInt(t: ByteArray, p: Int): Int { var v = 0; for (k in 0 until 4) v = v or ((t[p + k].toInt() and 0xFF) shl (8 * k)); return v }
    private fun litLong(t: ByteArray, p: Int): Long { var v = 0L; for (k in 0 until 8) v = v or ((t[p + k].toLong() and 0xFF) shl (8 * k)); return v }

    /**
     * A command line sent to the node's receiver: `centre <Hz>`, `gain <tenths>`
     * or `gain auto`, `ppm <n>`. Anything else is refused (null).
     */
    data class Commande(val nom: String, val valeur: Long?)

    fun litCommande(l: String): Commande? {
        val p = l.trim().split(' ').filter { it.isNotEmpty() }
        if (p.size != 2) return null
        return when (p[0]) {
            "centre" -> p[1].toLongOrNull()?.takeIf { it in 1_000_000L..12_000_000_000L }?.let { Commande("centre", it) }
            "gain" -> if (p[1] == "auto") Commande("gain", null) else p[1].toLongOrNull()?.takeIf { it in 0..1000 }?.let { Commande("gain", it) }
            "ppm" -> p[1].toLongOrNull()?.takeIf { it in -200..200 }?.let { Commande("ppm", it) }
            else -> null
        }
    }
}
