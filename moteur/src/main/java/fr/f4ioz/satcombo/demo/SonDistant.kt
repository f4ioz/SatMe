/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.phonie.Reechantillonneur
import org.json.JSONArray
import org.json.JSONObject

/**
 * The sound for a page opened from afar (« Mode distant »), over a WebSocket:
 * mono at 8, 12 or 16 kHz (low-passed, then resampled), cut into 20 ms frames,
 * each with its number and the server's clock, as 16-bit PCM or G.711 µ-law.
 *
 * A binary frame from the server: a 16-byte head, little-endian —
 * kind (1: sound), codec (0: PCM 16 bits, 1: µ-law), rate (uint16),
 * number (uint32), the server's clock in ms (float64) — then the samples.
 *
 * Text from the page (JSON): `config` (source, rate, codec), `ping` (answered
 * `pong` with the server's clock). Room is kept for a sound and a PTT going
 * up later (binary kind [AMONT_SON], `ptt`): refused now, the socket closed.
 */
object SonDistant {

    const val VERSION = 1
    const val ENTETE = 16
    const val TRAME_MS = 20

    /** Kinds of binary frames: down (server → page), and the one kept for later (page → server). */
    const val SON = 1
    const val AMONT_SON = 0x81

    const val PCM16 = 0
    const val ULAW = 1

    val DEBITS = intArrayOf(8_000, 12_000, 16_000)
    const val DEBIT_DEFAUT = 12_000
    val SOURCES = setOf("sdr", "carte", "datv")

    /** Close status for what is refused (RFC 6455, 1008: policy violation). */
    const val REFUS = 1008

    data class Config(val source: String = "sdr", val rate: Int = DEBIT_DEFAUT, val codec: Int = ULAW) {
        companion object {
            /** From a query string or a `config` message: anything unknown falls back to the default. */
            fun de(source: String?, rate: String?, codec: String?, avant: Config = Config()): Config = Config(
                source?.takeIf { it in SOURCES } ?: avant.source,
                rate?.toIntOrNull()?.takeIf { it in DEBITS } ?: avant.rate,
                when (codec) { "pcm", "pcm16" -> PCM16; "ulaw", "mulaw" -> ULAW; else -> avant.codec })
        }
    }

    sealed class Message {
        class Reglage(val source: String?, val rate: String?, val codec: String?) : Message()
        class Ping(val id: Long, val client: Double) : Message()
        /** A sound or a PTT going up: not now. */
        class Refuse(val raison: String) : Message()
        object Inconnu : Message()
    }

    /** What the page said in a text frame. */
    fun message(texte: String): Message {
        val o = runCatching { JSONObject(texte) }.getOrNull() ?: return Message.Inconnu
        return when (o.optString("t")) {
            "config" -> Message.Reglage(o.optString("source").ifEmpty { null }, o.opt("rate")?.toString(), o.optString("codec").ifEmpty { null })
            "ping" -> Message.Ping(o.optLong("id"), o.optDouble("c", 0.0))
            "ptt", "tx", "micro", "amont" -> Message.Refuse("émission refusée : réception seule")
            else -> Message.Inconnu
        }
    }

    /** The first words to the page: what it may ask. */
    fun bonjour(c: Config): String = JSONObject().put("t", "bonjour").put("v", VERSION).put("trameMs", TRAME_MS)
        .put("debits", JSONArray(DEBITS.toList())).put("codecs", JSONArray(listOf("pcm", "ulaw")))
        .put("source", c.source).put("rate", c.rate).put("codec", if (c.codec == ULAW) "ulaw" else "pcm")
        .put("amont", false).toString()

    fun pong(p: Message.Ping, serveurMs: Long, perdus: Long): String =
        JSONObject().put("t", "pong").put("id", p.id).put("c", p.client).put("s", serveurMs).put("perdus", perdus).toString()

    fun refus(raison: String): String = JSONObject().put("t", "refus").put("raison", raison).toString()

    /** One frame: its head, then [n] samples of [x] (−1…1) in [codec]. */
    fun trame(codec: Int, rate: Int, seq: Long, ms: Double, x: FloatArray, n: Int): ByteArray {
        val b = ByteArray(ENTETE + if (codec == ULAW) n else 2 * n)
        b[0] = SON.toByte(); b[1] = codec.toByte()
        b[2] = rate.toByte(); b[3] = (rate ushr 8).toByte()
        for (i in 0 until 4) b[4 + i] = (seq ushr (8 * i)).toByte()
        val m = java.lang.Double.doubleToLongBits(ms)
        for (i in 0 until 8) b[8 + i] = (m ushr (8 * i)).toByte()
        for (i in 0 until n) {
            val v = Math.round(x[i].coerceIn(-1f, 1f) * 32767f)
            if (codec == ULAW) b[ENTETE + i] = ulaw(v)
            else { b[ENTETE + 2 * i] = v.toByte(); b[ENTETE + 2 * i + 1] = (v shr 8).toByte() }
        }
        return b
    }

    class Lue(val codec: Int, val rate: Int, val seq: Long, val ms: Double, val pcm: ShortArray)

    /** A frame read back (tests, and any SatMe client); null when it is not one. */
    fun lit(b: ByteArray): Lue? {
        if (b.size < ENTETE || b[0].toInt() != SON) return null
        val codec = b[1].toInt()
        val rate = (b[2].toInt() and 255) or ((b[3].toInt() and 255) shl 8)
        var seq = 0L; for (i in 3 downTo 0) seq = (seq shl 8) or (b[4 + i].toLong() and 255)
        var m = 0L; for (i in 7 downTo 0) m = (m shl 8) or (b[8 + i].toLong() and 255)
        val corps = b.size - ENTETE
        val pcm = when (codec) {
            ULAW -> ShortArray(corps) { deUlaw(b[ENTETE + it]) }
            PCM16 -> ShortArray(corps / 2) { (((b[ENTETE + 2 * it + 1].toInt()) shl 8) or (b[ENTETE + 2 * it].toInt() and 255)).toShort() }
            else -> return null
        }
        return Lue(codec, rate, seq, java.lang.Double.longBitsToDouble(m), pcm)
    }

    /** G.711 µ-law: 14 bits of magnitude in 8 (sign, 3 bits of segment, 4 of step). */
    fun ulaw(v: Int): Byte {
        var s = v.coerceIn(-32768, 32767)
        val signe = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        s = minOf(s, 32635) + 0x84
        var seg = 7
        var masque = 0x4000
        while (seg > 0 && (s and masque) == 0) { seg--; masque = masque shr 1 }
        val pas = (s shr (seg + 3)) and 0x0F
        return ((signe or (seg shl 4) or pas) xor 0xFF).toByte()
    }

    fun deUlaw(b: Byte): Short {
        val u = b.toInt().inv() and 0xFF
        val seg = (u shr 4) and 7
        val v = (((u and 0x0F) shl 3) + 0x84) shl seg
        return (if (u and 0x80 != 0) 0x84 - v else v - 0x84).toShort()
    }

    /**
     * The blocks of a source (any rate, any size) to frames: resampled to the
     * chosen rate (a windowed-sinc low-pass below its Nyquist), cut in 20 ms,
     * numbered. The settings may change while it runs: the next frame follows.
     */
    class Emetteur(config: Config) {
        @Volatile var config: Config = config
        private var reech: Reechantillonneur? = null
        private var acc = FloatArray(4096)
        private var nAcc = 0
        var seq = 0L
            private set

        /** [pcm]'s first [n] samples, taken at [rateIn]; [ms] the clock at its last sample. */
        fun pousse(pcm: ShortArray, n: Int, rateIn: Int, ms: Long): List<ByteArray> {
            val c = config
            val r = reech?.takeIf { it.entree == rateIn && it.sortie == c.rate } ?: Reechantillonneur(rateIn, c.rate).also { reech = it; nAcc = 0 }
            val y = r.pousse(pcm, n)
            if (nAcc + y.size > acc.size) acc = acc.copyOf(maxOf(acc.size * 2, nAcc + y.size))
            System.arraycopy(y, 0, acc, nAcc, y.size); nAcc += y.size
            val nT = c.rate * TRAME_MS / 1000
            val sortie = ArrayList<ByteArray>(nAcc / nT)
            var lu = 0
            while (nAcc - lu >= nT) {
                lu += nT
                // The frame's last sample: the block's end, less what still waits behind it.
                val t = ms - (nAcc - lu) * 1000.0 / c.rate
                sortie += trame(c.codec, c.rate, seq++ and 0xFFFFFFFFL, t, acc.copyOfRange(lu - nT, lu), nT)
            }
            if (lu > 0) { System.arraycopy(acc, lu, acc, 0, nAcc - lu); nAcc -= lu }
            return sortie
        }
    }
}
