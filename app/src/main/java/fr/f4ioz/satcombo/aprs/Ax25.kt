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
 * flags, removes the stuffed zeros, and hands over each frame whose FCS is right.
 */
class HdlcDecodeur(private val surTrame: (ByteArray) -> Unit) {
    private var registre = 0
    private var uns = 0
    private var octet = 0
    private var nbBits = 0
    private val tampon = java.io.ByteArrayOutputStream()
    private var dansTrame = false

    /** A flag was seen recently: the clock can hold on tighter. */
    var synchro = false
        private set

    fun bit(b: Boolean) {
        registre = ((registre shl 1) or (if (b) 1 else 0)) and 0xFF
        if (registre == 0x7E) {  // flag (LSB-first order is symmetric for 0x7E)
            if (dansTrame && tampon.size() >= 18 && nbBits == 7) {
                // The flag's first 7 bits were taken as data: the last byte is complete.
                val t = tampon.toByteArray()
                if (Ax25.crc(t, t.size - 2) ==
                    ((t[t.size - 2].toInt() and 0xFF) or ((t[t.size - 1].toInt() and 0xFF) shl 8))) surTrame(t)
            }
            tampon.reset(); octet = 0; nbBits = 0; uns = 0
            dansTrame = true
            synchro = true
            return
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
            if (tampon.size() > 400) { dansTrame = false; synchro = false }
        }
    }
}
