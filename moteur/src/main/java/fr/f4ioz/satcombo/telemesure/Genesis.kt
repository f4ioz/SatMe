/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

/**
 * AMSAT-EA's « GENESIS » downlink framing (HADES-SA / SO-127, HADES-R,
 * HADES-ICM…), written from AMSAT-EA's published description
 * (« HADES-SA SpinnyONE Transmissions Description » V1.0, February 2026):
 *
 * - 2-FSK sent directly on the carrier, heard in USB as two tones:
 *   800 bit/s with 1600 Hz between them, or 200 bit/s with 1125 Hz; the
 *   **lower tone is 1**; bits MSB first, plain NRZ;
 * - a frame: 128 bits of 0xAA, the sync word **0xBF35**, a size byte (the
 *   bytes that follow, CRC included), the type/address byte (type in the
 *   high nibble, the sender in the low: 3 for HADES-SA), the data, a
 *   CRC-16/CCITT-FALSE (0x1021, from 0xFFFF) sent high byte first;
 * - the data **scrambled** by the self-synchronising G(x) = x¹⁷ + x¹² + 1,
 *   its register cleared for each frame; within each byte only bits 7…1
 *   go through it — bit 0 passes as it is and does not clock it (as
 *   AMSAT-EA's own decoder does);
 * - the CRC covers the type byte and the data **as sent** (scrambled);
 * - **type 10 is SSDV**: a whole standard 256-byte SSDV packet (normal
 *   mode, 0x66) after 16 bytes of 0x55, sent as it is — its four callsign
 *   bytes read « BF 35 FB A3 », so it looks like a GENESIS header of size
 *   251; its own CRC-32 and Reed-Solomon protect it, not scrambled.
 */
object Genesis {

    const val SYNC = 0xBF35
    const val PREAMBULE = 0xAA
    const val TYPE_SSDV = 10
    const val TYPE_CODEC2 = 11
    /** The sender address of HADES-SA (SO-127) in the type byte's low nibble. */
    const val ADRESSE_HADES_SA = 3
    /** The SSDV « callsign » bytes as HADES-SA sends them (= sync, size 251, type 0xA3). */
    const val INDICATIF_SSDV = 0xBF35FBA3L

    /** Payload sizes (the size byte) known for HADES-SA, by type. */
    val TAILLES_HADES_SA = mapOf(1 to 31, 2 to 17, 3 to 41, 4 to 35, 5 to 27, 8 to 31, 9 to 123, 10 to 251, 11 to 37,
        12 to 64, 13 to 249, 14 to 38, 15 to 73)

    val NOMS_TYPES = mapOf(1 to "énergie", 2 to "températures", 3 to "état", 4 to "énergie (plages)", 5 to "températures (plages)",
        8 to "déploiement antennes", 9 to "énergie étendue", 10 to "SSDV", 11 to "Codec2", 12 to "éphémérides", 13 to "PN9",
        14 to "série temporelle", 15 to "BBS")

    val ADRESSES = mapOf(2 to "HADES-ICM", 3 to "HADES-SA", 9 to "GENESIS-M", 11 to "MARIA-G", 12 to "UNNE-1B", 13 to "HADES-R")

    // ---------------------------------------------------------------- CRC, scrambler

    /** CRC-16/CCITT-FALSE: 0x1021, init 0xFFFF, not reflected, no final xor. */
    fun crc16(b: ByteArray, debut: Int = 0, n: Int = b.size - debut): Int {
        var c = 0xFFFF
        for (i in debut until debut + n) {
            c = c xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    /** The register's state at each frame's start (17 bits). */
    private const val ETAT_INITIAL = 0x10000

    /** [b] descrambled (a copy): bits 7…1 of each byte through x¹⁷ + x¹² + 1, bit 0 untouched. */
    fun desembrouille(b: ByteArray): ByteArray {
        var s = ETAT_INITIAL
        return ByteArray(b.size) { i ->
            val x = b[i].toInt() and 0xFF
            var o = x and 1
            for (k in 7 downTo 1) {
                val e = (x ushr k) and 1
                val d = e xor ((s ushr 16) and 1) xor ((s ushr 11) and 1)
                s = ((s shl 1) or e) and 0x1FFFF
                o = o or (d shl k)
            }
            o.toByte()
        }
    }

    /** [b] scrambled (a copy), the inverse of [desembrouille] (the tests, the generator). */
    fun embrouille(b: ByteArray): ByteArray {
        var s = ETAT_INITIAL
        return ByteArray(b.size) { i ->
            val x = b[i].toInt() and 0xFF
            var o = x and 1
            for (k in 7 downTo 1) {
                val d = (x ushr k) and 1
                val e = d xor ((s ushr 16) and 1) xor ((s ushr 11) and 1)
                s = ((s shl 1) or e) and 0x1FFFF
                o = o or (e shl k)
            }
            o.toByte()
        }
    }

    // ---------------------------------------------------------------- frames

    class Trame(
        val type: Int,
        val adresse: Int,
        /** As sent (scrambled), type byte first, without the CRC. */
        val brut: ByteArray,
        /** The data descrambled (type byte excluded). */
        val donnees: ByteArray,
        val crcJuste: Boolean,
    ) {
        val nomType: String get() = NOMS_TYPES[type] ?: "type $type"
        val emetteur: String get() = ADRESSES[adresse] ?: "adresse $adresse"
    }

    /** A frame's bytes on the air (preamble excluded): sync, size, type/address, data scrambled, CRC. */
    fun code(type: Int, adresse: Int, donnees: ByteArray): ByteArray {
        val corps = byteArrayOf(((type shl 4) or (adresse and 0x0F)).toByte()) + embrouille(donnees)
        val crc = crc16(corps)
        return byteArrayOf((SYNC ushr 8).toByte(), SYNC.toByte(), (corps.size + 2).toByte()) + corps +
            byteArrayOf((crc ushr 8).toByte(), crc.toByte())
    }

    /** An SSDV packet as HADES-SA sends it: 16 bytes of 0x55, then the packet after its own 0x55. */
    fun codeSsdv(paquet: ByteArray): ByteArray = ByteArray(16) { 0x55 } + paquet.copyOfRange(1, paquet.size)

    /** Bytes as bits, MSB first, [preambule] bytes of 0xAA before. */
    fun bits(octets: ByteArray, preambule: Int = 16): IntArray {
        val tout = ByteArray(preambule) { PREAMBULE.toByte() } + octets
        return IntArray(tout.size * 8) { (tout[it / 8].toInt() ushr (7 - it % 8)) and 1 }
    }

    /**
     * Frames in a bit stream (MSB first): the sync at any bit position, in
     * either polarity (which tone is 1 is settled by the first frame with a
     * right CRC, then kept), the size, the bytes. SSDV frames (type 10, or a
     * size of 251 whose CRC is not right) go to [surSsdv] as a 256-byte packet;
     * the others to [surTrame] with their CRC checked.
     */
    class Deframeur(
        private val surTrame: (Trame) -> Unit,
        private val surSsdv: (ByteArray) -> Unit = {},
        /** Bits allowed wrong in the sync word (the CRC, or the SSDV packet's own checks, judge after). */
        private val toleranceSync: Int = 0,
    ) {
        private class Lecture(val inverse: Boolean) {
            var reg = 0
            var etat = 0 // 0: sync looked for; 1: size; 2: bytes
            var taille = 0
            var octets = ByteArray(0)
            var n = 0
            var bit = 0
            var cour = 0
        }
        private val lectures = listOf(Lecture(false), Lecture(true))
        var syncs = 0L; private set
        var crcFaux = 0; private set
        var trames = 0; private set

        fun bit(b: Int) {
            for (l in lectures) lis(l, if (l.inverse) b xor 1 else b)
        }

        private fun lis(l: Lecture, x: Int) {
            when (l.etat) {
                0 -> {
                    l.reg = ((l.reg shl 1) or x) and 0xFFFF
                    if (Integer.bitCount(l.reg xor SYNC) <= toleranceSync) { l.etat = 1; l.bit = 0; l.cour = 0; syncs++ }
                }
                else -> {
                    l.cour = (l.cour shl 1) or x
                    if (++l.bit < 8) return
                    val o = l.cour and 0xFF; l.bit = 0; l.cour = 0
                    if (l.etat == 1) {
                        if (o < 3) { l.etat = 0; l.reg = 0; return }
                        l.taille = o; l.octets = ByteArray(o); l.n = 0; l.etat = 2
                    } else {
                        l.octets[l.n++] = o.toByte()
                        if (l.n == l.taille) { l.etat = 0; l.reg = 0; fin(l.taille, l.octets) }
                    }
                }
            }
        }

        private fun fin(taille: Int, o: ByteArray) {
            val type = (o[0].toInt() and 0xFF) ushr 4
            val adresse = o[0].toInt() and 0x0F
            val corps = o.copyOf(taille - 2)
            val crc = ((o[taille - 2].toInt() and 0xFF) shl 8) or (o[taille - 1].toInt() and 0xFF)
            val juste = crc16(corps) == crc
            if (!juste && taille == TAILLE_SSDV) {
                // The SSDV packet seen through its callsign bytes: rebuilt whole, its own checks say.
                surSsdv(byteArrayOf(0x55, 0x66, (SYNC ushr 8).toByte(), SYNC.toByte(), taille.toByte()) + o)
                return
            }
            if (!juste) { crcFaux++; return }
            trames++
            surTrame(Trame(type, adresse, corps, desembrouille(corps.copyOfRange(1, corps.size)), true))
        }
    }

    /** The size byte of an SSDV frame (255 bytes after « 55 66 BF 35 », the callsign's last two included). */
    const val TAILLE_SSDV = 251
}
