/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

/**
 * The AGWPE TCP/IP interface, as its public description has it, for a
 * **monitor only**: other programs (Xastir, YAAC, APRSIS32, a packet
 * client…) receive the frames SatMe decodes, and nothing they send can make
 * a radio transmit.
 *
 * A frame: a 36-byte header (port, kind, PID, from, to, data length, all
 * little-endian), then the data.
 *
 * What a client may ask and gets an answer: R (version), G (ports), g (a
 * port's capabilities), X (registering a callsign), y / Y (frames waiting:
 * always 0), m (monitoring on or off: frames 'U'), k (raw frames on or
 * off: frames 'K'). What asks to transmit — K, M, V (unproto), C, c, v
 * (connect), D (connected data), d (disconnect) — is refused: [Genre.EMISSION].
 */
object Agwpe {

    const val ENTETE = 36

    /** One frame: its header's fields and its data. */
    class Trame(val port: Int, val genre: Char, val pid: Int, val de: String, val a: String, val donnees: ByteArray) {
        fun octets(): ByteArray {
            val o = ByteArray(ENTETE + donnees.size)
            o[0] = port.toByte()
            o[4] = genre.code.toByte()
            o[6] = pid.toByte()
            ecritIndicatif(o, 8, de)
            ecritIndicatif(o, 18, a)
            ecritEntier(o, 28, donnees.size)
            donnees.copyInto(o, ENTETE)
            return o
        }
    }

    /** What a client's frame is, for the monitor. */
    enum class Genre { REPONSE, BASCULE, EMISSION, IGNORE }

    /** The kinds that would put something on the air: always refused. */
    val EMISSIONS = setOf('K', 'M', 'V', 'C', 'c', 'v', 'D', 'd')

    fun genre(k: Char): Genre = when (k) {
        in EMISSIONS -> Genre.EMISSION
        'R', 'G', 'g', 'X', 'y', 'Y' -> Genre.REPONSE
        'm', 'k' -> Genre.BASCULE
        else -> Genre.IGNORE
    }

    private fun ecritIndicatif(o: ByteArray, debut: Int, c: String) {
        val b = c.toByteArray(Charsets.US_ASCII)
        for (i in 0 until minOf(9, b.size)) o[debut + i] = b[i]
    }

    private fun ecritEntier(o: ByteArray, debut: Int, v: Int) {
        for (i in 0 until 4) o[debut + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun litEntier(o: ByteArray, debut: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((o[debut + i].toLong() and 0xFF) shl (8 * i)) }

    private fun litIndicatif(o: ByteArray, debut: Int): String =
        String(o, debut, 10, Charsets.US_ASCII).substringBefore('\u0000').trim()

    /** A client's bytes → frames, whatever the cut of the reads. A header saying more than [MAX] bytes: the line is wrong. */
    class Lecteur {
        private val tampon = java.io.ByteArrayOutputStream()
        var faux = false; private set

        fun octets(b: ByteArray, n: Int = b.size): List<Trame> {
            tampon.write(b, 0, n)
            val out = ArrayList<Trame>()
            var t = tampon.toByteArray()
            var i = 0
            while (t.size - i >= ENTETE) {
                val lg = litEntier(t, i + 28)
                if (lg > MAX) { faux = true; tampon.reset(); return out }
                if (t.size - i < ENTETE + lg) break
                out += Trame(t[i].toInt() and 0xFF, (t[i + 4].toInt() and 0xFF).toChar(), t[i + 6].toInt() and 0xFF,
                    litIndicatif(t, i + 8), litIndicatif(t, i + 18), t.copyOfRange(i + ENTETE, i + ENTETE + lg.toInt()))
                i += ENTETE + lg.toInt()
            }
            tampon.reset()
            if (i < t.size) tampon.write(t, i, t.size - i)
            return out
        }
    }

    const val MAX = 65_536L

    /** The answer to a [Genre.REPONSE] frame, or null (X answers too: registering sends nothing on the air). */
    fun reponse(q: Trame, nomPort: String): Trame? = when (q.genre) {
        // Version 2000.78, as the clients expect a recent AGWPE.
        'R' -> Trame(0, 'R', 0, "", "", ByteArray(8).also { ecritEntier(it, 0, 2000); ecritEntier(it, 4, 78) })
        'G' -> Trame(0, 'G', 0, "", "", "1;Port1 $nomPort;".toByteArray(Charsets.US_ASCII) + 0.toByte())
        // On-air rate (0: 1200), traffic unknown (0xFF), TX delay, tail, persist, slot, maxframe, connections, bytes in 2 min.
        'g' -> Trame(q.port, 'g', 0, "", "", byteArrayOf(0, 0xFF.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0))
        'X' -> Trame(q.port, 'X', 0, q.de, "", byteArrayOf(1))
        'y' -> Trame(q.port, 'y', 0, "", "", ByteArray(4))
        'Y' -> Trame(q.port, 'Y', 0, q.de, q.a, ByteArray(4))
        else -> null
    }

    /** A frame heard (AX.25 without FCS) as a raw 'K' frame: a KISS command byte (data, port 0), then the frame. */
    fun brute(ax25: ByteArray): Trame {
        val e = Ax25.entete(ax25)
        return Trame(0, 'K', 0, e?.source?.toString().orEmpty(), e?.destination?.toString().orEmpty(), byteArrayOf(0) + ax25)
    }

    /**
     * A UI frame heard, as the monitor's 'U' frame: « 1:Fm SRC To DST Via
     * PATH <UI pid=F0 Len=n >[HH:MM:SS] » on one line, then the information.
     * Null for a frame that is not UI.
     */
    fun moniteur(ax25: ByteArray, quandMs: Long): Trame? {
        val e = Ax25.entete(ax25) ?: return null
        if (!e.ui || e.pid == null) return null
        val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(quandMs))
        val via = if (e.relais.isEmpty()) "" else " Via " + e.relais.joinToString(",")
        val tete = " 1:Fm ${e.source} To ${e.destination}$via <UI pid=%02X Len=${e.info.size} >[$heure]\r".format(e.pid)
        val texte = tete.toByteArray(Charsets.ISO_8859_1) + e.info + byteArrayOf(13)
        return Trame(0, 'U', e.pid, e.source.toString(), e.destination.toString(), texte)
    }
}
