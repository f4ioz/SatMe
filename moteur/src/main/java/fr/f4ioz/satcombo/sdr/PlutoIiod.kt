/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A client of the IIO daemon (IIOD), its text protocol: what an ADALM-PLUTO
 * answers on TCP 30431.
 *
 * A command is a line ending in CR LF. The answer starts with a line holding
 * an integer: a length (or 0) when it went well, a negative errno when not.
 * - `READ <dev> [INPUT|OUTPUT <chan>] <attr>`: the length, then the value
 *   and a line feed.
 * - `WRITE <dev> [INPUT|OUTPUT <chan>] <attr> <len>`, then the value: the
 *   answer comes after the value.
 * - `OPEN <dev> <samples> <mask>`, `CLOSE <dev>`, `TIMEOUT <ms>`: the integer.
 * - `READBUF <dev> <bytes>`: in pieces, each an integer line (its length);
 *   the first one followed by the channel mask on a line; 0 or the count
 *   asked ends it.
 * - `PRINT`: the length, then the context's XML and a line feed.
 * - `WRITEBUF <dev> <bytes>` (after `OPEN` with the buffer's sample count):
 *   0 when the daemon is ready to take them, the bytes, then the count
 *   written once the buffer is pushed (a negative errno instead, either
 *   time).
 *
 * One command at a time on a connection: the samples have one of their own.
 */
class Iiod private constructor(private val socket: Socket) : Closeable {

    private val entree: InputStream = BufferedInputStream(socket.getInputStream(), 1 shl 16)
    private val sortie: OutputStream = socket.getOutputStream()

    /** An input or output channel of a device. */
    data class Canal(val sortie: Boolean, val id: String) {
        override fun toString() = (if (sortie) "OUTPUT " else "INPUT ") + id
    }

    /** The server said no: [code] is the negative errno. */
    class Refus(val code: Int, quoi: String) : IOException("$quoi : " + raison(code))

    @Synchronized fun version(): String { commande("VERSION"); return ligne().trim() }

    /** The context's description (XML). */
    @Synchronized fun contexte(): String {
        val n = execute("PRINT")
        if (n < 0) throw Refus(n, "PRINT")
        val b = lisTout(n)
        lisTout(1)
        return String(b, Charsets.UTF_8)
    }

    /** An attribute's value, without its trailing NUL and line feed. */
    @Synchronized fun lit(dev: String, canal: Canal?, attr: String): String {
        val n = execute("READ $dev " + (canal?.let { "$it " } ?: "") + attr)
        if (n < 0) throw Refus(n, attr)
        val b = lisTout(n + 1)
        return String(b, 0, n, Charsets.US_ASCII).trimEnd('\u0000', '\n', ' ')
    }

    @Synchronized fun ecrit(dev: String, canal: Canal?, attr: String, valeur: String) {
        val v = valeur.toByteArray(Charsets.US_ASCII)
        commande("WRITE $dev " + (canal?.let { "$it " } ?: "") + attr + " " + v.size)
        sortie.write(v); sortie.flush()
        val n = entier()
        if (n < 0) throw Refus(n, attr)
    }

    /** How long the server waits for samples before giving up a READBUF. */
    @Synchronized fun delai(ms: Int) { val n = execute("TIMEOUT $ms"); if (n < 0) throw Refus(n, "TIMEOUT") }

    @Synchronized fun ouvre(dev: String, echantillons: Int, masque: String) {
        val n = execute("OPEN $dev $echantillons $masque")
        if (n < 0) throw Refus(n, "OPEN $dev")
    }

    @Synchronized fun ferme(dev: String) { val n = execute("CLOSE $dev"); if (n < 0) throw Refus(n, "CLOSE $dev") }

    /** [octets] bytes of samples into [dst]: how many came (all of them, unless the server ended early). */
    @Synchronized fun litTampon(dev: String, dst: ByteArray, octets: Int): Int {
        commande("READBUF $dev $octets")
        var lu = 0
        var premier = true
        while (lu < octets) {
            val n = entier()
            if (n < 0) throw Refus(n, "READBUF")
            if (n == 0) break
            if (n > octets - lu) throw IOException("réponse IIOD trop longue ($n octets)")
            if (premier) { ligne(); premier = false }
            lisDans(dst, lu, n)
            lu += n
        }
        return lu
    }

    /**
     * [n] bytes of samples (one whole buffer, as opened) to [dev]'s output
     * buffer (`WRITEBUF`): the daemon answers 0 when ready, the bytes
     * follow, then it answers how many it pushed. Only the transmitter
     * ([PlutoEmission]) calls it.
     */
    @Synchronized fun ecritTampon(dev: String, src: ByteArray, n: Int): Int {
        val pret = execute("WRITEBUF $dev $n")
        if (pret < 0) throw Refus(pret, "WRITEBUF")
        if (pret != 0) throw IOException("WRITEBUF : réponse inattendue ($pret) avant les échantillons")
        sortie.write(src, 0, n); sortie.flush()
        val fait = entier()
        if (fait < 0) throw Refus(fait, "WRITEBUF")
        if (fait != n) throw IOException("WRITEBUF : $fait octets pris sur $n")
        return fait
    }

    override fun close() { runCatching { socket.close() } }

    private fun commande(texte: String) {
        sortie.write((texte + "\r\n").toByteArray(Charsets.US_ASCII)); sortie.flush()
    }

    private fun execute(texte: String): Int { commande(texte); return entier() }

    private fun entier(): Int {
        val l = ligne().trim()
        return l.toIntOrNull() ?: throw IOException("réponse IIOD illisible : " + l.take(40))
    }

    private fun ligne(): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = entree.read()
            if (c < 0) throw EOFException("liaison coupée")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 4096) throw IOException("réponse IIOD trop longue")
        }
        return b.toString(Charsets.US_ASCII.name())
    }

    private fun lisTout(n: Int): ByteArray = ByteArray(n).also { lisDans(it, 0, n) }

    private fun lisDans(b: ByteArray, debut: Int, n: Int) {
        var k = 0
        while (k < n) {
            val r = entree.read(b, debut + k, n - k)
            if (r < 0) throw EOFException("liaison coupée")
            k += r
        }
    }

    companion object {
        /** Connects; [attenteMs] also bounds every answer after (a Pluto pulled out never says goodbye). */
        fun connecte(hote: String, port: Int, attenteMs: Int = 3000): Iiod {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(hote, port), attenteMs)
                s.tcpNoDelay = true
                s.soTimeout = attenteMs
                s.receiveBufferSize = 1 shl 20
                return Iiod(s)
            } catch (x: IOException) {
                runCatching { s.close() }
                throw x
            }
        }

        fun raison(code: Int): String = when (-code) {
            2 -> "attribut inconnu"
            5 -> "erreur d'entrée-sortie"
            16 -> "occupé (pris par un autre programme ?)"
            19 -> "appareil absent"
            22 -> "valeur refusée"
            32 -> "liaison rompue"
            110 -> "délai dépassé"
            else -> "erreur ${-code}"
        }
    }
}

/**
 * An ADALM-PLUTO on the network, as a receiver: its RX side only.
 *
 * **Receive only.** The only writes are the RX local oscillator, the RX
 * sample rate, analog bandwidth, gain mode and gain ([ECRITURES]); the only
 * buffer opened is the RX one. Nothing on the TX side is ever touched — the
 * AD936x ties the TX sample rate to the RX one, that is all.
 *
 * **The rate.** The AD936x goes no lower than 2.083 MS/s without its FIR
 * (which is shared with TX): it runs at twice the dongle's rate (2.1168 MS/s)
 * and a low-pass here brings it down to exactly [Dsp.RTL_RATE], so the whole
 * chain — spectrum, panorama, METEOR, sondes — sees what a dongle gives.
 *
 * **The samples.** 12-bit I and Q in 16-bit words; after the decimation they
 * are scaled to full 16-bit and handed out as shorts ([read16]), keeping the
 * converter's range ([RxChain.process16]).
 *
 * **Read continuously.** A thread of its own, on a second connection, reads
 * the buffer into a queue (the oldest block dropped when the processing is
 * late), as for rtl_tcp.
 */
class PlutoSdr private constructor(
    private val ctrl: Iiod,
    private val hote: String,
    private val port: Int,
    /** The board and its firmware, from the context. */
    val modele: String,
    val micrologiciel: String,
    private val masque: String,
    private val bits: Int,
    val frequenceMinHz: Long,
    val frequenceMaxHz: Long,
    private val attenteMs: Int,
) : SourceIq {

    /** What the status line shows. */
    val description: String get() = modele + (if (micrologiciel.isNotEmpty()) " · micrologiciel $micrologiciel" else "")

    private val file = ArrayBlockingQueue<ShortArray>(PROFONDEUR)
    @Volatile var ouvert = true; private set
    @Volatile override var perdus = 0L; private set
    @Volatile override var octets = 0L; private set
    /** Raw samples (I, Q pairs) received from the Pluto. */
    @Volatile var echantillons = 0L; private set
    /** Why the reception stopped, when it was not asked. */
    @Volatile var erreur: String? = null; private set
    /** A request refused without stopping the reception (a frequency out of range…). */
    @Volatile var avertissement: String = ""; private set

    @Volatile private var facteur = 0
    @Volatile private var decimateur: DecimateurIq? = null
    private var lecteur: Thread? = null
    @Volatile private var flux: Iiod? = null
    @Volatile private var ppm = 0
    @Volatile private var demandeHz = 0L

    override val iq16: Boolean get() = true

    /** [rate] out of here; the Pluto runs at the smallest multiple of it it can do. */
    @Synchronized override fun setSampleRate(rate: Int): Double {
        val d = facteurPour(rate)
        val obtenu = controle {
            ecritRx(RX, "sampling_frequency", (rate.toLong() * d).toString())
            ecritRx(RX, "rf_bandwidth", rate.toLong().coerceIn(BANDE_MIN, BANDE_MAX).toString())
            ctrl.lit(PHY, RX, "sampling_frequency").trim().toDoubleOrNull()
        } ?: (rate.toDouble() * d)
        if (d != facteur) { facteur = d; decimateur = DecimateurIq(d, (1 shl (16 - bits)).toDouble()) }
        return obtenu / d
    }

    /** No crystal correction on the Pluto's side: the LO asked is moved by the error instead. */
    override fun setFreqCorrection(ppm: Int) {
        this.ppm = ppm
        if (demandeHz > 0) setCenterFreq(demandeHz)
    }

    override fun setCenterFreq(hz: Long): Long {
        demandeHz = hz
        val lo = Math.round(hz / (1.0 + ppm * 1e-6))
        if (lo !in frequenceMinHz..frequenceMaxHz) {
            avertissement = "fréquence hors de la plage de l'ADALM-PLUTO (" + (frequenceMinHz / 1_000_000) + " MHz – " +
                (frequenceMaxHz / 1_000_000) + " MHz)"
            return hz
        }
        if (controle { ecritRx(LO_RX, "frequency", lo.toString()) } != null) avertissement = ""
        return hz
    }

    /** Tenths of dB (0 to 71 dB), or null: the AD936x's slow AGC. */
    override fun setGain(tenthDb: Int?) {
        controle {
            if (tenthDb == null) ecritRx(RX, "gain_control_mode", "slow_attack")
            else {
                ecritRx(RX, "gain_control_mode", "manual")
                ecritRx(RX, "hardwaregain", (tenthDb / 10).coerceIn(GAIN_MIN_DB, GAIN_MAX_DB).toString())
            }
        }
    }

    /** The dongle's digital AGC: nothing like it here. */
    override fun setAgc(on: Boolean) {}

    override fun read16(buf: ShortArray, timeoutMs: Int): Int {
        demarre()
        val b = file.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (ouvert || file.isNotEmpty()) 0 else -1
        val n = minOf(b.size, buf.size) and 1.inv()
        System.arraycopy(b, 0, buf, 0, n)
        return n
    }

    /** As unsigned bytes, for a reception that only takes those (the 16-bit range lost). */
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        demarre()
        val b = file.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (ouvert || file.isNotEmpty()) 0 else -1
        val n = minOf(b.size, buf.size) and 1.inv()
        for (k in 0 until n) buf[k] = ((b[k] + 32768) shr 8).toByte()
        return n
    }

    @Synchronized private fun demarre() {
        if (lecteur != null || !ouvert) return
        if (facteur == 0) setSampleRate(Dsp.RTL_RATE)
        lecteur = Thread({ boucle() }, "satme-pluto").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1; start() }
    }

    private fun boucle() {
        var s: Iiod? = null
        try {
            s = Iiod.connecte(hote, port, attenteMs).also { flux = it }
            s.delai(maxOf(500, attenteMs - 1000))
            s.ouvre(RX_DEV, ECHANTILLONS, masque)
            val brut = ByteArray(ECHANTILLONS * 4)
            var sortieCourante = ShortArray(BLOC)
            var pos = 0
            var dec = ShortArray(ECHANTILLONS * 2 + 4)
            while (ouvert) {
                val n = s.litTampon(RX_DEV, brut, brut.size)
                if (n <= 0) throw IOException("plus d'échantillons")
                octets += n
                echantillons += n / 4
                val d = decimateur ?: continue
                if (dec.size < n / 2 + 4) dec = ShortArray(n / 2 + 4)
                val m = d.traite(brut, n, dec)
                var k = 0
                while (k < m) {
                    val c = minOf(m - k, BLOC - pos)
                    System.arraycopy(dec, k, sortieCourante, pos, c)
                    pos += c; k += c
                    if (pos == BLOC) {
                        // Never wait for the processing: the oldest block goes when it is late.
                        if (!file.offer(sortieCourante)) { file.poll(); perdus++; file.offer(sortieCourante) }
                        sortieCourante = ShortArray(BLOC); pos = 0
                    }
                }
            }
            runCatching { s.ferme(RX_DEV) }
        } catch (x: IOException) {
            if (ouvert) erreur = message(x)
        } finally {
            ouvert = false
            s?.close()
            flux = null
        }
    }

    /** A control request; the Pluto gone, the reception stops with the reason. */
    private fun <T> controle(f: () -> T): T? = try {
        f()
    } catch (x: Iiod.Refus) {
        avertissement = x.message.orEmpty(); null
    } catch (x: IOException) {
        if (ouvert) { erreur = message(x); ouvert = false }
        flux?.close(); ctrl.close(); null
    }

    private fun ecritRx(canal: Iiod.Canal, attr: String, valeur: String) {
        // Receive only, whoever calls: anything else is refused here, before the network.
        check((canal to attr) in ECRITURES) { "écriture refusée sur l'ADALM-PLUTO : $canal $attr" }
        ctrl.ecrit(PHY, canal, attr, valeur)
    }

    override fun close() {
        ouvert = false
        val t = lecteur
        // The reader ends its READBUF and closes the buffer; if the Pluto is gone, the sockets go.
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(1500) }
        flux?.close()
        ctrl.close()
        file.clear()
    }

    companion object {
        const val PORT = 30431
        const val HOTE = "192.168.2.1"
        const val PHY = "ad9361-phy"
        const val RX_DEV = "cf-ad9361-lpc"
        val RX = Iiod.Canal(false, "voltage0")
        val LO_RX = Iiod.Canal(true, "altvoltage0")
        /** Every write this class may make: RX side of ad9361-phy only. */
        val ECRITURES = setOf(RX to "sampling_frequency", RX to "rf_bandwidth", RX to "gain_control_mode",
            RX to "hardwaregain", LO_RX to "frequency")
        /** The AD936x's lowest rate without its FIR. */
        const val DEBIT_MIN = 2_083_334
        const val BANDE_MIN = 200_000L
        const val BANDE_MAX = 56_000_000L
        const val GAIN_MIN_DB = 0
        const val GAIN_MAX_DB = 71
        /** Samples per buffer read: 31 ms at 2.1168 MS/s. */
        const val ECHANTILLONS = 65_536
        /** Shorts per block handed out: 8192 IQ pairs, as rtl_tcp's blocks. */
        const val BLOC = 16_384
        const val PROFONDEUR = 256
        const val ATTENTE_MS = 3000

        fun facteurPour(rate: Int): Int { var d = 1; while (rate.toLong() * d < DEBIT_MIN) d++; return d }

        private fun message(x: IOException): String = when (x) {
            is SocketTimeoutException -> "l'ADALM-PLUTO ne répond plus (débranché ?)"
            is EOFException -> "l'ADALM-PLUTO a coupé la liaison"
            is Iiod.Refus -> "ADALM-PLUTO : " + x.message
            else -> "ADALM-PLUTO : " + (x.message ?: "liaison perdue")
        }

        /**
         * Connects, checks it is an AD936x with its RX buffer, reads what it
         * is; an [IOException] in French says why not. Nothing is written.
         */
        fun connecte(hote: String = HOTE, port: Int = PORT, attenteMs: Int = ATTENTE_MS): PlutoSdr {
            val c = try {
                Iiod.connecte(hote, port, attenteMs)
            } catch (x: IOException) {
                throw IOException("ADALM-PLUTO injoignable sur $hote:$port (branché ? adresse ?)")
            }
            try {
                val xml = c.contexte()
                val d = Contexte.lis(xml)
                if (PHY !in d.appareils || RX_DEV !in d.appareils)
                    throw IOException("$hote:$port répond, mais ce n'est pas un ADALM-PLUTO (pas d'AD936x)")
                val (masque, bits) = d.masqueRx ?: throw IOException("ADALM-PLUTO : format d'échantillons inattendu")
                val (fmin, fmax) = runCatching { plage(c.lit(PHY, LO_RX, "frequency_available")) }.getOrNull()
                    ?: (70_000_000L to 6_000_000_000L)
                val modele = (d.attributs["hw_model"] ?: "ADALM-PLUTO").removePrefix("Analog Devices ").trim()
                return PlutoSdr(c, hote, port, modele, d.attributs["fw_version"].orEmpty(), masque, bits, fmin, fmax, attenteMs)
            } catch (x: IOException) {
                c.close()
                throw if (x is SocketTimeoutException || x is EOFException) IOException("$hote:$port ne répond pas comme un ADALM-PLUTO") else x
            }
        }

        /** "[min step max]" to (min, max). */
        fun plage(s: String): Pair<Long, Long>? {
            val p = s.trim().removePrefix("[").removeSuffix("]").trim().split(Regex("\\s+"))
            if (p.size != 3) return null
            return (p[0].toLongOrNull() ?: return null) to (p[2].toLongOrNull() ?: return null)
        }
    }

    /** What the context's XML says: the devices, the context attributes, the RX channels' mask and bits. */
    class Contexte(val appareils: Set<String>, val attributs: Map<String, String>, val masqueRx: Pair<String, Int>?) {
        companion object {
            fun lis(xml: String): Contexte {
                val attributs = Regex("<context-attribute name=\"([^\"]*)\" value=\"([^\"]*)\"").findAll(xml)
                    .associate { it.groupValues[1] to deXml(it.groupValues[2]) }
                val appareils = HashSet<String>()
                var masque: Pair<String, Int>? = null
                for (m in Regex("<device id=\"([^\"]*)\"(?: name=\"([^\"]*)\")?[^>]*>(.*?)</device>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                    val nom = m.groupValues[2].ifEmpty { m.groupValues[1] }
                    appareils += nom
                    if (nom != RX_DEV) continue
                    val canaux = Regex("<channel id=\"(voltage[01])\" type=\"input\"[^>]*>\\s*<scan-element index=\"(\\d+)\" format=\"([^\"]*)\"")
                        .findAll(m.groupValues[3]).map { Triple(it.groupValues[1], it.groupValues[2].toInt(), deXml(it.groupValues[3])) }.toList()
                    if (canaux.map { it.first }.toSet() != setOf("voltage0", "voltage1")) continue
                    // Little-endian, signed, in 16-bit words, not shifted: what the Pluto gives.
                    val formats = canaux.map { Regex("le:[Ss](\\d+)/16>>0").matchEntire(it.third)?.groupValues?.get(1)?.toIntOrNull() }
                    val b = formats.firstOrNull() ?: continue
                    if (formats.any { it != b } || b !in 8..16) continue
                    var bitsMasque = 0L
                    for (c in canaux) if (c.second < 32) bitsMasque = bitsMasque or (1L shl c.second)
                    // I before Q in the buffer: voltage0's index the lower.
                    if (canaux.first { it.first == "voltage0" }.second > canaux.first { it.first == "voltage1" }.second) continue
                    masque = String.format("%08x", bitsMasque) to b
                }
                return Contexte(appareils, attributs, masque)
            }

            private fun deXml(s: String) = s.replace("&gt;", ">").replace("&lt;", "<").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        }
    }
}

/**
 * Raw little-endian 16-bit I, Q pairs to [facteur] times fewer, scaled by
 * [echelle]: a windowed-sinc low-pass cut at the new Nyquist (a half-band
 * when [facteur] is 2: every other tap is zero and skipped).
 */
class DecimateurIq(val facteur: Int, echelle: Double, ntaps: Int = 36 * facteur - 1) {
    private val coefs: FloatArray
    private val indices: IntArray
    private val n = ntaps
    private val histI = FloatArray(2 * ntaps)
    private val histQ = FloatArray(2 * ntaps)
    private var pos = 0
    private var compte = 0

    init {
        val c = 0.5 / facteur
        val t = DoubleArray(ntaps) { i ->
            val m = i - (ntaps - 1) / 2.0
            val sinc = if (m == 0.0) 2 * c else sin(2 * PI * c * m) / (PI * m)
            // Blackman: the alias of what lies beyond the new band well below the noise.
            val w = 0.42 - 0.5 * cos(2 * PI * i / (ntaps - 1)) + 0.08 * cos(4 * PI * i / (ntaps - 1))
            sinc * w
        }
        val s = t.sum()
        val garde = t.indices.filter { kotlin.math.abs(t[it] / s) > 1e-9 }
        indices = garde.toIntArray()
        coefs = FloatArray(garde.size) { (t[garde[it]] / s * echelle).toFloat() }
    }

    /** [nOctets] bytes of raw pairs into [sortie] as shorts; returns how many shorts. */
    fun traite(brut: ByteArray, nOctets: Int, sortie: ShortArray): Int {
        var o = 0
        var k = 0
        while (k + 3 < nOctets) {
            val i = ((brut[k].toInt() and 0xff) or (brut[k + 1].toInt() shl 8)).toShort().toFloat()
            val q = ((brut[k + 2].toInt() and 0xff) or (brut[k + 3].toInt() shl 8)).toShort().toFloat()
            k += 4
            // Each sample twice, n apart: the window is always one straight run.
            histI[pos] = i; histI[pos + n] = i
            histQ[pos] = q; histQ[pos + n] = q
            pos = if (pos + 1 == n) 0 else pos + 1
            if (++compte == facteur) {
                compte = 0
                var a = 0f; var b = 0f
                for (j in coefs.indices) {
                    val x = pos + indices[j]
                    a += coefs[j] * histI[x]; b += coefs[j] * histQ[x]
                }
                sortie[o++] = borne(a); sortie[o++] = borne(b)
            }
        }
        return o
    }

    private fun borne(v: Float): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()
}
