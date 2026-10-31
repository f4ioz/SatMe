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
 * IL2P, "Improved Layer 2 Protocol", from its public specification by Nino
 * Carrillo (KK4HEJ), draft v0.6 of 16 March 2024 (tarpn.net/t/il2p/).
 *
 * No flags, no bit stuffing, no NRZI: a 24-bit sync word, a 13-byte header
 * (the AX.25 addresses and control compressed, or just a byte count) with
 * 2 Reed-Solomon check bytes, then the payload in blocks of up to 239
 * bytes, each with 16 check bytes, and optionally a CRC (the AX.25 one)
 * Hamming-coded in 4 bytes. Bytes go high bit first. Header and payload
 * blocks are scrambled (x⁹ + x⁴ + 1, reset for each block) before the
 * Reed-Solomon code is applied: the code mends, then the receiver
 * unscrambles. GF(2^8) on 0x11D, first root α⁰.
 *
 * Here only what reception needs: the frame comes out as the AX.25 frame
 * it carries (FCS added), for the usual parser.
 */
object Il2p {
    const val SYNCHRO = 0xF15E48
    const val ENTETE = 13
    const val CONTROLE_ENTETE = 2
    const val CONTROLE_BLOC = 16
    const val MAX_BLOC = 239

    val codeEntete = ReedSolomon(CONTROLE_ENTETE, 0)
    val codeBloc = ReedSolomon(CONTROLE_BLOC, 0)

    /** The spec's Hamming(7,4) decode table for the trailing CRC. */
    private val HAMMING_DECODE = intArrayOf(
        0x0, 0x0, 0x0, 0x3, 0x0, 0x5, 0xe, 0x7, 0x0, 0x9, 0xe, 0xb, 0xe, 0xd, 0xe, 0xe,
        0x0, 0x3, 0x3, 0x3, 0x4, 0xd, 0x6, 0x3, 0x8, 0xd, 0xa, 0x3, 0xd, 0xd, 0xe, 0xd,
        0x0, 0x5, 0x2, 0xb, 0x5, 0x5, 0x6, 0x5, 0x8, 0xb, 0xb, 0xb, 0xc, 0x5, 0xe, 0xb,
        0x8, 0x1, 0x6, 0x3, 0x6, 0x5, 0x6, 0x6, 0x8, 0x8, 0x8, 0xb, 0x8, 0xd, 0x6, 0xf,
        0x0, 0x9, 0x2, 0x7, 0x4, 0x7, 0x7, 0x7, 0x9, 0x9, 0xa, 0x9, 0xc, 0x9, 0xe, 0x7,
        0x4, 0x1, 0xa, 0x3, 0x4, 0x4, 0x4, 0x7, 0xa, 0x9, 0xa, 0xa, 0x4, 0xd, 0xa, 0xf,
        0x2, 0x1, 0x2, 0x2, 0xc, 0x5, 0x2, 0x7, 0xc, 0x9, 0x2, 0xb, 0xc, 0xc, 0xc, 0xf,
        0x1, 0x1, 0x2, 0x1, 0x4, 0x1, 0x6, 0xf, 0x8, 0x1, 0xa, 0xf, 0xc, 0xf, 0xf, 0xf,
    )
    /** The spec's Hamming encode table: the codewords. */
    val HAMMING_CODE = intArrayOf(0x00, 0x71, 0x62, 0x13, 0x54, 0x25, 0x36, 0x47, 0x38, 0x49, 0x5a, 0x2b, 0x6c, 0x1d, 0x0e, 0x7f)

    /** The trailing CRC read from its 4 bytes (most significant nibble first). */
    fun crcLu(o: ByteArray, debut: Int): Int {
        var v = 0
        for (k in 0 until 4) v = (v shl 4) or HAMMING_DECODE[o[debut + k].toInt() and 0x7F]
        return v
    }

    /** Are these 4 bytes Hamming codewords as they are (a CRC sent, not noise or preamble)? */
    fun crcPresent(o: ByteArray, debut: Int): Boolean =
        (0 until 4).all { k -> (o[debut + k].toInt() and 0xFF).let { b -> b < 0x80 && HAMMING_CODE.contains(b) } }

    /** The receive unscrambler of the spec (Galois form, 9 stages, reset to 1 1 1 1 1 0 0 0 0), in place. */
    fun desembrouille(o: ByteArray, debut: Int, n: Int) {
        var r = 0x01F  // stage k = bit k
        for (i in debut until debut + n) {
            val octet = o[i].toInt() and 0xFF
            var sortie = 0
            for (k in 7 downTo 0) {
                val e = (octet ushr k) and 1
                val s = e xor ((r ushr 8) and 1)
                val r4 = (r ushr 4) and 1
                r = ((r shl 1) and 0x1FE) or e
                r = (r and (1 shl 5).inv()) or ((r4 xor e) shl 5)
                sortie = (sortie shl 1) or s
            }
            o[i] = sortie.toByte()
        }
    }

    /** Payload bytes in blocks: the size of each, larger first (the spec's block computations). */
    fun blocs(compte: Int): IntArray {
        if (compte == 0) return IntArray(0)
        val nb = (compte + MAX_BLOC - 1) / MAX_BLOC
        val petit = compte / nb
        val grands = compte - nb * petit
        return IntArray(nb) { if (it < grands) petit + 1 else petit }
    }

    /** What a header says, unscrambled. */
    class Entete(val type1: Boolean, val compte: Int, val ui: Boolean, val pid: Int, val controle: Int,
                 val destination: String, val ssidDest: Int, val source: String, val ssidSource: Int)

    fun lisEntete(h: ByteArray): Entete {
        fun champ(debut: Int, n: Int, bit: Int): Int {
            var v = 0
            for (i in debut until debut + n) v = (v shl 1) or ((h[i].toInt() ushr bit) and 1)
            return v
        }
        fun indicatif(debut: Int) = String(CharArray(6) { ((h[debut + it].toInt() and 0x3F) + 0x20).toChar() }).trimEnd()
        val b12 = h[12].toInt() and 0xFF
        return Entete(type1 = champ(1, 1, 7) == 1, compte = champ(2, 10, 7), ui = champ(0, 1, 6) == 1,
            pid = champ(1, 4, 6), controle = champ(5, 7, 6),
            destination = indicatif(0), ssidDest = b12 ushr 4, source = indicatif(6), ssidSource = b12 and 0x0F)
    }

    /** IL2P PID code → AX.25 PID (null: none; −1: not a defined code). */
    private fun pidAx25(code: Int): Int = when (code) {
        0x2 -> 0x20; 0x3 -> 0x01; 0x4 -> 0x06; 0x5 -> 0x07; 0x6 -> 0x08
        0xB -> 0xCC; 0xC -> 0xCD; 0xD -> 0xCE; 0xE -> 0xCF; 0xF -> 0xF0
        else -> -1
    }

    /** IL2P U-frame opcode → AX.25 control byte, P/F off. */
    private val U_AX25 = intArrayOf(0x2F, 0x43, 0x0F, 0x63, 0x87, 0x03, 0xAF, 0xE3)

    private fun indicatifValide(s: String) = s.isNotEmpty() && s.all { it in 'A'..'Z' || it in '0'..'9' }

    /**
     * The AX.25 header (addresses, control, PID) a type 1 header stands for,
     * or null when it cannot be one (a callsign of other characters, an
     * undefined PID code).
     */
    fun enteteAx25(e: Entete): ByteArray? {
        if (!indicatifValide(e.destination) || !indicatifValide(e.source)) return null
        val out = java.io.ByteArrayOutputStream()
        val commande: Boolean
        var pid: Int? = null
        val ctl: Int
        val c = e.controle
        val pf = (c ushr 6) and 1
        when {
            e.pid == 0x0 -> {                    // S frame: N(R), C, opcode
                commande = (c ushr 2) and 1 == 1
                ctl = (((c ushr 3) and 7) shl 5) or (pf shl 4) or ((c and 3) shl 2) or 0x01
            }
            e.pid == 0x1 || e.ui -> {            // U frame: opcode, C
                commande = (c ushr 2) and 1 == 1
                val op = (c ushr 3) and 7
                if (e.ui != (op == 5)) return null
                ctl = U_AX25[op] or (pf shl 4)
                if (e.ui) pid = pidAx25(e.pid).also { if (it < 0) return null }
            }
            else -> {                            // I frame: always a command
                commande = true
                ctl = (((c ushr 3) and 7) shl 5) or (pf shl 4) or ((c and 7) shl 1)
                pid = pidAx25(e.pid).also { if (it < 0) return null }
            }
        }
        fun adresse(nom: String, ssid: Int, octet7: Int) {
            nom.padEnd(6).forEach { out.write(it.code shl 1) }
            out.write(octet7 or 0x60 or (ssid shl 1))
        }
        adresse(e.destination, e.ssidDest, if (commande) 0x80 else 0)
        adresse(e.source, e.ssidSource, (if (commande) 0 else 0x80) or 1)
        out.write(ctl)
        pid?.let { out.write(it) }
        return out.toByteArray()
    }
}

/**
 * The receiving side of IL2P: takes the bits as they come off the air (no
 * NRZI; a mark tone is a 1), looks for the sync word (one bit of difference
 * allowed, as the spec recommends), mends and reads the header, gathers and
 * mends the payload blocks, reads the trailing CRC when one follows, and
 * hands over the AX.25 frame (FCS included).
 *
 * [inverse]: also look for the inverted signal (FSK through a radio that
 * turns the audio over); not for AFSK.
 */
class Il2pDecodeur(
    private val inverse: Boolean = false,
    private val surTrame: (octets: ByteArray, info: Info) -> Unit,
) {
    /** How a frame came: bytes the code mended, and whether the trailing CRC was there and right. */
    class Info(val corriges: Int, val crc: Boolean)

    private var registre = 0
    private var phase = 0     // 0: looking for sync; 1: header; 2: payload; 3: trailing CRC
    private var renverse = false
    private val tampon = ByteArray(Il2p.ENTETE + Il2p.CONTROLE_ENTETE + 1100 + 4)
    private var attendus = 0
    private var nbBits = 0
    private var corriges = 0
    private var entete: ByteArray? = null
    private var tailles = IntArray(0)
    private var totalCharge = 0
    private var trame: ByteArray? = null

    /** Something is being gathered. */
    val enCours: Boolean get() = phase != 0

    /** Headers that would not mend (noise mostly), and blocks that would not. */
    var echecsEntete = 0; private set
    var echecsBloc = 0; private set

    fun bit(b0: Boolean) {
        registre = ((registre shl 1) or (if (b0) 1 else 0)) and 0xFFFFFF
        if (phase == 3 && synchro()) { livre(false); return }  // the next packet, no CRC on this one
        if (phase == 0) {
            if (synchro()) { phase = 1; attendus = Il2p.ENTETE + Il2p.CONTROLE_ENTETE; nbBits = 0 }
            return
        }
        val b = b0 != renverse
        val i = nbBits ushr 3
        if (nbBits and 7 == 0) tampon[i] = 0
        if (b) tampon[i] = (tampon[i].toInt() or (0x80 ushr (nbBits and 7))).toByte()
        if (++nbBits < attendus * 8) return
        when (phase) {
            1 -> lisEntete()
            2 -> lisCharge()
            3 -> livre(true)
        }
    }

    private fun synchro(): Boolean {
        if (Integer.bitCount(registre xor Il2p.SYNCHRO) <= 1) { renverse = false; return true }
        if (inverse && Integer.bitCount(registre xor (Il2p.SYNCHRO xor 0xFFFFFF)) <= 1) { renverse = true; return true }
        return false
    }

    private fun recommence() { phase = 0; nbBits = 0; trame = null; entete = null }

    private fun lisEntete() {
        val c = Il2p.codeEntete.corrige(tampon, 0, Il2p.ENTETE + Il2p.CONTROLE_ENTETE)
        if (c < 0) { echecsEntete++; recommence(); return }
        corriges = c
        val h = tampon.copyOf(Il2p.ENTETE)
        Il2p.desembrouille(h, 0, Il2p.ENTETE)
        val e = Il2p.lisEntete(h)
        if (e.type1) {
            entete = Il2p.enteteAx25(e) ?: run { echecsEntete++; recommence(); return }
        } else {
            // Type 0: the whole frame in the payload; the other fields must be blank (bit 7 of byte 0 is reserved).
            if (h.indices.any { k -> (h[k].toInt() and (if (k <= 11) 0x7F else 0xFF)) != 0 }) { echecsEntete++; recommence(); return }
            if (e.compte < 15) { echecsEntete++; recommence(); return }
            entete = ByteArray(0)
        }
        tailles = Il2p.blocs(e.compte)
        totalCharge = e.compte
        if (tailles.isEmpty()) { attendCrc(); return }
        phase = 2
        attendus = tailles.sumOf { it + Il2p.CONTROLE_BLOC }
        nbBits = 0
    }

    private fun lisCharge() {
        val charge = ByteArray(totalCharge)
        var pos = 0; var o = 0
        for (t in tailles) {
            val c = Il2p.codeBloc.corrige(tampon, pos, t + Il2p.CONTROLE_BLOC)
            if (c < 0) { echecsBloc++; recommence(); return }
            corriges += c
            Il2p.desembrouille(tampon, pos, t)
            tampon.copyInto(charge, o, pos, pos + t)
            pos += t + Il2p.CONTROLE_BLOC; o += t
        }
        val ent = entete ?: return recommence()
        val ax25 = ent + charge
        if (ent.isEmpty() && !Ax25.adressesValides(ax25)) { echecsBloc++; recommence(); return }
        trame = ax25
        attendCrc()
    }

    private fun attendCrc() {
        if (trame == null) trame = entete
        phase = 3; attendus = 4; nbBits = 0
    }

    private fun livre(avecCrc: Boolean) {
        val t = trame
        var bonne = t != null
        var crcBon = false
        if (t != null && avecCrc) {
            crcBon = Il2p.crcLu(tampon, 0) == Ax25.crc(t)
            // Four clean codewords that disagree: a CRC was sent, and the frame is wrong.
            if (!crcBon && Il2p.crcPresent(tampon, 0)) { echecsBloc++; bonne = false }
        }
        // A header alone (no payload) is only trusted with its CRC: 2 check bytes are too few against noise.
        if (totalCharge == 0 && !crcBon) bonne = false
        val c = corriges
        recommence()
        if (bonne && t != null) surTrame(Ax25.avecFcs(t), Info(c, crcBon))
        // The sync word just seen (no CRC case) starts the next packet.
        if (!avecCrc) { phase = 1; attendus = Il2p.ENTETE + Il2p.CONTROLE_ENTETE; nbBits = 0 }
    }
}
