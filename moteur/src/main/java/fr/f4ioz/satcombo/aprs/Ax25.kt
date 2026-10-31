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
 * AX.25 UI frames, as APRS uses them: destination, source, up to eight
 * relays, then control 0x03, PID 0xF0 and the information field. No
 * Android import: the whole APRS path is tested on the JVM.
 */

/** One address: callsign, SSID, and for a relay whether it has repeated the frame. */
data class Adresse(val indicatif: String, val ssid: Int = 0, val repete: Boolean = false) {

    override fun toString(): String =
        (if (ssid == 0) indicatif else "$indicatif-$ssid") + if (repete) "*" else ""

    companion object {
        /** "RS0ISS-4*" → RS0ISS, 4, repeated. */
        fun de(texte: String): Adresse {
            val t = texte.trim()
            val repete = t.endsWith("*")
            val sans = t.removeSuffix("*")
            val nom = sans.substringBefore('-').uppercase()
            val ssid = sans.substringAfter('-', "0").toIntOrNull()?.coerceIn(0, 15) ?: 0
            return Adresse(nom, ssid, repete)
        }
    }
}

/** A decoded frame. [info] is kept raw: APRS is ASCII, but Mic-E uses any byte. */
class Trame(
    val destination: Adresse,
    val source: Adresse,
    val relais: List<Adresse>,
    val info: ByteArray,
) {
    /** The information field as text (Latin-1: one char per byte, nothing lost). */
    val texte: String get() = String(info, Charsets.ISO_8859_1)

    /** The usual one-line form: "F4IOZ>APRS,RS0ISS*,qAR:=4852.…". */
    fun tnc2(): String = buildString {
        append(source).append('>').append(destination)
        relais.forEach { append(',').append(it) }
        append(':').append(texte)
    }

    override fun equals(other: Any?): Boolean =
        other is Trame && other.destination == destination && other.source == source &&
            other.relais == relais && other.info.contentEquals(info)

    override fun hashCode(): Int =
        ((destination.hashCode() * 31 + source.hashCode()) * 31 + relais.hashCode()) * 31 +
            info.contentHashCode()

    override fun toString(): String = tnc2()
}

object Ax25 {

    /** CRC-16/X.25 (HDLC FCS): reflected 0x1021, start 0xFFFF, inverted at the end. */
    fun crc(octets: ByteArray, longueur: Int = octets.size): Int {
        var c = 0xFFFF
        for (i in 0 until longueur) {
            c = c xor (octets[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        }
        return c.inv() and 0xFFFF
    }

    /**
     * A frame from the bytes between two HDLC flags, FCS included (last two
     * bytes, low byte first). Null when the FCS is wrong or the frame is not
     * an APRS UI frame: a corrupted frame is dropped, never half-shown.
     */
    fun decode(octets: ByteArray): Trame? {
        val n = octets.size
        if (n < 18) return null  // 2 addresses (14) + control + PID + FCS
        val fcs = (octets[n - 2].toInt() and 0xFF) or ((octets[n - 1].toInt() and 0xFF) shl 8)
        if (crc(octets, n - 2) != fcs) return null
        val adresses = ArrayList<Pair<Adresse, Int>>()
        var i = 0
        while (true) {
            if (i + 7 > n - 2 || adresses.size > 10) return null
            val a = adresse(octets, i) ?: return null
            adresses += a
            i += 7
            if (a.second and 1 != 0) break  // extension bit: last address
        }
        if (adresses.size < 2 || i + 2 > n - 2) return null
        if ((octets[i].toInt() and 0xFF) != 0x03) return null        // UI frame
        if ((octets[i + 1].toInt() and 0xFF) != 0xF0) return null    // no layer 3
        val relais = adresses.drop(2).map { (a, b) -> a.copy(repete = b and 0x80 != 0) }
        return Trame(adresses[0].first, adresses[1].first, relais, octets.copyOfRange(i + 2, n - 2))
    }

    private fun adresse(o: ByteArray, debut: Int): Pair<Adresse, Int>? {
        val sb = StringBuilder()
        for (k in 0 until 6) {
            val b = o[debut + k].toInt() and 0xFF
            if (b and 1 != 0) return null  // extension bit inside the callsign: garbage
            val c = (b ushr 1).toChar()
            if (c != ' ') {
                if (!c.isLetterOrDigit() || c.code > 127) return null
                sb.append(c)
            }
        }
        if (sb.isEmpty()) return null
        val dernier = o[debut + 6].toInt() and 0xFF
        return Adresse(sb.toString(), (dernier ushr 1) and 0x0F) to dernier
    }

    /**
     * Any AX.25 frame's header, FCS left off (as satellites send telemetry:
     * UI frames with any PID, or other kinds): addresses, control, PID when
     * the kind has one, the rest. Null when the addresses are not AX.25's.
     */
    fun entete(octets: ByteArray): Entete? {
        val n = octets.size
        val adresses = ArrayList<Pair<Adresse, Int>>()
        var i = 0
        while (true) {
            if (i + 7 > n || adresses.size > 10) return null
            val a = adresseLibre(octets, i) ?: return null
            adresses += a
            i += 7
            if (a.second and 1 != 0) break
        }
        if (adresses.size < 2 || i >= n) return null
        val controle = octets[i].toInt() and 0xFF
        // I frames and UI frames carry a PID; S and other U frames do not.
        val avecPid = controle and 1 == 0 || controle and 0xEF == 0x03
        if (avecPid && i + 1 >= n) return null
        val pid = if (avecPid) octets[i + 1].toInt() and 0xFF else null
        val relais = adresses.drop(2).map { (a, b) -> a.copy(repete = b and 0x80 != 0) }
        return Entete(adresses[0].first, adresses[1].first, relais, controle, pid, octets.copyOfRange(i + (if (avecPid) 2 else 1), n))
    }

    class Entete(val destination: Adresse, val source: Adresse, val relais: List<Adresse>, val controle: Int, val pid: Int?, val info: ByteArray) {
        val ui: Boolean get() = controle and 0xEF == 0x03
        fun ligne(): String = buildString {
            append(source).append('>').append(destination)
            relais.forEach { append(',').append(it) }
        }
    }

    /** As [adresse], lower case allowed (some satellites send it). */
    private fun adresseLibre(o: ByteArray, debut: Int): Pair<Adresse, Int>? {
        val sb = StringBuilder()
        for (k in 0 until 6) {
            val b = o[debut + k].toInt() and 0xFF
            if (b and 1 != 0) return null
            val c = (b ushr 1).toChar()
            if (c != ' ') {
                if (!(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9')) return null
                sb.append(c)
            }
        }
        if (sb.isEmpty()) return null
        val dernier = o[debut + 6].toInt() and 0xFF
        return Adresse(sb.toString(), (dernier ushr 1) and 0x0F) to dernier
    }

    /**
     * Do the first bytes of [octets] (FCS left on or not) look like a real
     * AX.25 header? Two to ten addresses, each a callsign of capitals and
     * digits padded with spaces at the end only, the extension bit on the
     * last one only, then a control byte. What a frame mended by guesswork
     * must pass: noise that happens to have a right FCS almost never does.
     */
    fun adressesValides(octets: ByteArray, longueur: Int = octets.size): Boolean {
        var i = 0
        var nb = 0
        while (true) {
            if (i + 7 > longueur || nb >= 10) return false
            var fin = false
            var lettres = 0
            for (k in 0 until 6) {
                val b = octets[i + k].toInt() and 0xFF
                if (b and 1 != 0) return false
                val c = b ushr 1
                if (c == ' '.code) fin = true
                else {
                    if (fin) return false
                    if (!(c in 'A'.code..'Z'.code || c in '0'.code..'9'.code)) return false
                    lettres++
                }
            }
            if (lettres == 0) return false
            nb++
            val dernier = octets[i + 6].toInt() and 0xFF
            i += 7
            if (dernier and 1 != 0) break
        }
        return nb >= 2 && i < longueur
    }

    /** The FCS of [octets] (last two bytes, low byte first) is right. */
    fun fcsJuste(octets: ByteArray, n: Int = octets.size): Boolean =
        n > 2 && crc(octets, n - 2) == ((octets[n - 2].toInt() and 0xFF) or ((octets[n - 1].toInt() and 0xFF) shl 8))

    /** [sans] with its FCS appended. */
    fun avecFcs(sans: ByteArray): ByteArray {
        val c = crc(sans)
        return sans + byteArrayOf((c and 0xFF).toByte(), (c ushr 8).toByte())
    }

    /** A frame without its FCS, as a KISS TNC passes it (the radio checked it already). */
    fun decodeSansFcs(octets: ByteArray): Trame? {
        val c = crc(octets)
        return decode(octets + byteArrayOf((c and 0xFF).toByte(), (c ushr 8).toByte()))
    }

    /** The bytes of a frame without FCS: what a KISS TNC expects (it adds the FCS). */
    fun encodeSansFcs(t: Trame): ByteArray = encode(t).let { it.copyOf(it.size - 2) }

    /** The bytes of a frame, FCS included: what goes between the flags. */
    fun encode(t: Trame): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val toutes = listOf(t.destination, t.source) + t.relais
        toutes.forEachIndexed { k, a ->
            val nom = a.indicatif.uppercase().padEnd(6).take(6)
            nom.forEach { out.write(it.code shl 1) }
            var b = 0x60 or ((a.ssid and 0x0F) shl 1)
            when (k) {
                0 -> b = b or 0x80                  // command: C bit on the destination
                1 -> {}
                else -> if (a.repete) b = b or 0x80 // H bit: has been repeated
            }
            if (k == toutes.lastIndex) b = b or 1
            out.write(b)
        }
        out.write(0x03)
        out.write(0xF0)
        out.write(t.info)
        val sans = out.toByteArray()
        val c = crc(sans)
        return sans + byteArrayOf((c and 0xFF).toByte(), (c ushr 8).toByte())
    }
}

/**
 * HDLC at bit level: flags 01111110, a 0 inserted after five 1s, NRZI (a 0
 * is a change of tone, a 1 no change). Bytes go low bit first.
 */
object Hdlc {

    /** The tones to send, as booleans (true = mark 1200 Hz), for [trames] one after the other. */
    fun bits(trames: List<ByteArray>, drapeauxAvant: Int = 32, drapeauxApres: Int = 3): BooleanArray {
        val bruts = ArrayList<Boolean>()
        fun drapeau() { for (k in 0 until 8) bruts += (0x7E ushr k) and 1 == 1 }
        repeat(drapeauxAvant) { drapeau() }
        trames.forEachIndexed { idx, t ->
            var uns = 0
            for (b in t) for (k in 0 until 8) {
                val bit = (b.toInt() ushr k) and 1 == 1
                bruts += bit
                if (bit) {
                    if (++uns == 5) { bruts += false; uns = 0 }
                } else uns = 0
            }
            repeat(if (idx == trames.lastIndex) drapeauxApres else 1) { drapeau() }
        }
        // NRZI: 0 toggles, 1 keeps.
        var niveau = true
        return BooleanArray(bruts.size) { k -> if (!bruts[k]) niveau = !niveau; niveau }
    }
}


/**
 * The receiving side of HDLC: takes the bits already NRZI-decoded, finds the
 * flags, removes the stuffed zeros, and hands over each frame whose FCS is
 * right — [minOctets] bytes at least, FCS included: 17 lets the shortest
 * AX.25 frames through (SABM, UA, DISC, RR: two addresses, a control byte),
 * the FCS still required.
 *
 * With [surEchec], what lay between two flags and did not make a right
 * frame (wrong FCS, a stray stuffed bit, an abort inside) is handed over
 * too, as the raw bits and the demodulator's confidence in each: something
 * for [CorrecteurHdlc] to try to mend. Without it, nothing changes.
 */
class HdlcDecodeur(
    private val minOctets: Int = 17,
    private val maxOctets: Int = 400,
    private val surEchec: ((SegmentHdlc) -> Unit)? = null,
    private val surTrame: (ByteArray) -> Unit,
) {
    private var registre = 0
    private var uns = 0
    private var octet = 0
    private var nbBits = 0
    private val tampon = java.io.ByteArrayOutputStream()
    private var dansTrame = false

    // The raw bits since the last flag (stuffing still in), with their confidence.
    private val capacite = maxOctets * 8 * 6 / 5 + 16
    private val bruts = if (surEchec != null) BooleanArray(capacite) else BooleanArray(0)
    private val confiances = if (surEchec != null) FloatArray(capacite) else FloatArray(0)
    private var nbBruts = 0
    private var enregistre = false

    /** A flag was seen recently: the clock can hold on tighter. */
    var synchro = false
        private set

    fun bit(b: Boolean) = bit(b, 1f)

    /** One bit, with how sure the demodulator was of it (0 = a coin toss, 1 = certain). */
    fun bit(b: Boolean, confiance: Float) {
        registre = ((registre shl 1) or (if (b) 1 else 0)) and 0xFF
        if (registre == 0x7E) {  // flag (LSB-first order is symmetric for 0x7E)
            var bonne = false
            if (dansTrame && tampon.size() >= minOctets && nbBits == 7) {
                // The flag's first 7 bits were taken as data: the last byte is complete.
                val t = tampon.toByteArray()
                if (Ax25.fcsJuste(t)) { bonne = true; surTrame(t) }
            }
            if (!bonne && enregistre) {
                val n = nbBruts - 7  // the flag's first seven bits are not the frame's
                if (n >= minOctets * 8) surEchec?.invoke(SegmentHdlc(bruts.copyOf(n), confiances.copyOf(n)))
            }
            tampon.reset(); octet = 0; nbBits = 0; uns = 0
            dansTrame = true
            synchro = true
            nbBruts = 0
            enregistre = surEchec != null
            return
        }
        if (enregistre) {
            if (nbBruts < capacite) { bruts[nbBruts] = b; confiances[nbBruts] = confiance; nbBruts++ }
            else enregistre = false
        }
        if (!dansTrame) return
        if (b) {
            uns++
            if (uns > 6) { dansTrame = false; synchro = false; return }  // abort / noise
        } else {
            if (uns == 5) { uns = 0; return }  // stuffed zero
            uns = 0
        }
        octet = octet or ((if (b) 1 else 0) shl nbBits)
        if (++nbBits == 8) {
            tampon.write(octet)
            octet = 0; nbBits = 0
            if (tampon.size() > maxOctets) { dansTrame = false; synchro = false; enregistre = false }
        }
    }
}

/** What lay between two flags, stuffing still in: the bits and the demodulator's confidence in each. */
class SegmentHdlc(val bits: BooleanArray, val confiances: FloatArray)

/**
 * Mends a frame whose FCS failed, when one or two bits came out of the
 * demodulator wrong.
 *
 * A bit is carried by a tone; HDLC reads it as "same tone as before" (1)
 * or "a change" (0). One tone heard wrong therefore turns two neighbouring
 * HDLC bits over. So the guesses are made on the received bits: the one (or
 * two) the demodulator was least sure of, among the [faibles] weakest, each
 * turning over its HDLC bit and the next. Every guess is unstuffed again and
 * must give whole bytes and a right FCS.
 *
 * Kept from false frames by: few guesses (8 for one bit, 8 + 28 for two:
 * a 16-bit FCS lets a wrong guess through once in 65 536), an AX.25 header
 * that must look real ([Ax25.adressesValides]), and only one answer — two
 * different guesses that both pass and the frame is dropped.
 *
 * Without the demodulator's confidence (all 1), every position is tried for
 * one bit, none for two.
 */
object CorrecteurHdlc {

    /** The frame mended (FCS included) and how many received bits were turned over, or null. */
    class Correction(val octets: ByteArray, val bits: Int)

    fun corrige(s: SegmentHdlc, maxBits: Int, minOctets: Int = 17, maxOctets: Int = 400, faibles: Int = 8): Correction? {
        if (maxBits <= 0) return null
        val n = s.bits.size
        if (n < minOctets * 8 || n > maxOctets * 8 * 6 / 5 + 16) return null
        // Each wrong tone can break at most one run of seven 1s (an abort inside).
        var longues = 0
        var run = 0
        for (b in s.bits) { if (b) { if (++run == 7) longues++ } else run = 0 }
        if (longues > maxBits) return null

        val sansConfiance = s.confiances.all { it >= 1f }
        val candidats: IntArray = if (sansConfiance) IntArray(n) { it }
        else s.confiances.indices.sortedBy { s.confiances[it] }.take(faibles).toIntArray()
        val travail = s.bits.copyOf()
        fun essaie(vararg tons: Int): ByteArray? {
            for (t in tons) retourne(travail, t)
            val r = destuffe(travail, minOctets, maxOctets)
            for (t in tons) retourne(travail, t)
            return r?.takeIf { Ax25.fcsJuste(it) && Ax25.adressesValides(it, it.size - 2) }
        }
        fun unique(l: List<ByteArray>): ByteArray? = l.distinctBy { it.contentHashCode() }.singleOrNull()

        val un = candidats.toList().mapNotNull { t -> essaie(t) }
        if (un.isNotEmpty()) return unique(un)?.let { Correction(it, 1) }
        if (maxBits < 2 || sansConfiance) return null
        val deux = ArrayList<ByteArray>()
        for (a in candidats.indices) for (b in a + 1 until candidats.size) {
            val x = candidats[a]; val y = candidats[b]
            // Two neighbouring tones: their middle HDLC bit is turned twice — stays as it was.
            essaie(x, y)?.let { deux += it }
        }
        return unique(deux)?.let { Correction(it, 2) }
    }

    /** The tone at [t] heard wrong: HDLC bits t and t + 1 turned over. */
    private fun retourne(bits: BooleanArray, t: Int) {
        bits[t] = !bits[t]
        if (t + 1 < bits.size) bits[t + 1] = !bits[t + 1]
    }

    /** The bytes of an unstuffed segment, or null when it is not a frame (abort, not whole bytes, size). */
    fun destuffe(bits: BooleanArray, minOctets: Int = 17, maxOctets: Int = 400): ByteArray? {
        val out = ByteArray(maxOctets + 1)
        var nb = 0
        var octet = 0
        var nbBits = 0
        var uns = 0
        for (b in bits) {
            // Six 1s in a row only happen in a flag or an abort: not inside a frame.
            if (b) { if (++uns > 5) return null }
            else { if (uns == 5) { uns = 0; continue }; uns = 0 }
            if (b) octet = octet or (1 shl nbBits)
            if (++nbBits == 8) {
                if (nb > maxOctets) return null
                out[nb++] = octet.toByte(); octet = 0; nbBits = 0
            }
        }
        if (nbBits != 0 || nb < minOctets) return null
        return out.copyOf(nb)
    }
}
