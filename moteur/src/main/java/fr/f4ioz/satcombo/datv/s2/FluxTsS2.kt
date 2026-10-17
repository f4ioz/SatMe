/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv.s2

/** One frame through the FEC: the BBFRAME's bytes (BB descrambled), or null when it could not be corrected. */
class BbFrame(val numero: Long, val modcod: Modcod, val octets: ByteArray?, val rupture: Boolean,
              val iterations: Int, val bitsCorriges: Int, val bchCorriges: Int)

/**
 * LDPC, then BCH with its corrections, then BB descrambling (5.2.2): one
 * per decoding thread.
 */
class DecodeurFec {
    private val ldpc = DecodeurLdpc()

    fun decode(t: TrameS2, maxIterations: Int): BbFrame {
        val m = t.modcod
        val g = GrapheLdpc.de(m.taux, m.courte)
        val l = t.llr
        var avant = 0
        for (k in 0 until m.kldpc) if (l[k] < 0) avant++
        val ok = ldpc.decode(g, l, maxIterations)
        val bits = ByteArray(m.kldpc)
        var apres = 0
        for (k in 0 until m.kldpc) if (l[k] < 0) { bits[k] = 1; apres++ }
        // Bits flipped by the LDPC among the BCH word (a rough count of what it corrected).
        val corriges = if (ok) Math.abs(apres - avant) else 0
        val bch = DecodeurBch.pour(m).corrige(bits)
        // The LDPC short of a few bits: BCH may still finish the job (its own check says whether it did).
        if (bch < 0) {
            return BbFrame(t.numero, m, null, t.rupture, ldpc.iterations, corriges, -1)
        }
        val s = BrouilleurBb.SEQUENCE
        val o = ByteArray(m.kbch / 8)
        for (k in o.indices) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or ((bits[8 * k + j].toInt() xor s[8 * k + j].toInt()) and 1)
            o[k] = v.toByte()
        }
        return BbFrame(t.numero, m, o, t.rupture, ldpc.iterations, corriges, bch)
    }
}

/**
 * BBFRAMEs (in order) to the transport stream (5.1): the BBHEADER checked
 * (CRC-8, a TS of 188-byte packets), the packets cut from the DATA FIELD at
 * SYNCD, each one's sync byte put back and its CRC-8 (carried by the next
 * packet's first byte) checked — a wrong one gets its transport error bit.
 * A frame lost breaks the stream: the packet begun is dropped, the next one
 * found again with SYNCD.
 */
class FluxTsS2(
    /** [n] bytes of whole packets, valid only during the call. */
    private val sortie: (ByteArray, Int) -> Unit,
) {
    private val paquet = ByteArray(188)
    private var pos = -1
    /** The previous packet, held until the next one's first byte (its CRC) arrives. */
    private val attente = ByteArray(188)
    private var enAttente = false
    private var crcAttendu = 0
    private val tampon = ByteArray(188 * 64)
    private var nTampon = 0

    @Volatile var paquets = 0L; private set
    @Volatile var paquetsFaux = 0L; private set
    @Volatile var entetesFaux = 0L; private set
    @Volatile var autresFlux = 0L; private set
    /** The roll-off the BBHEADER says (0.35, 0.25, 0.20), or null. */
    @Volatile var rolloff: Double? = null; private set

    /** The stream broken (a frame lost): what was begun goes. */
    fun rupture() {
        pos = -1
        if (enAttente) { emets(attente, null); enAttente = false }
        vide()
    }

    fun trame(b: BbFrame) {
        if (b.rupture) rupture()
        val o = b.octets ?: run { rupture(); return }
        if (Crc8.octets(o, 0, 9) != (o[9].toInt() and 0xFF)) { entetesFaux++; rupture(); return }
        val matype = o[0].toInt() and 0xFF
        rolloff = when (matype and 3) { 0 -> 0.35; 1 -> 0.25; 2 -> 0.20; else -> null }
        val upl = ((o[2].toInt() and 0xFF) shl 8) or (o[3].toInt() and 0xFF)
        val dfl = ((o[4].toInt() and 0xFF) shl 8) or (o[5].toInt() and 0xFF)
        val syncd = ((o[7].toInt() and 0xFF) shl 8) or (o[8].toInt() and 0xFF)
        // A transport stream (TS/GS = 11), no ISSY, no null-packet deletion, 188-byte packets.
        if (matype ushr 6 != 3 || matype and 0x0C != 0 || upl != 188 * 8 || dfl > o.size * 8 - 80 || dfl % 8 != 0) {
            autresFlux++; rupture(); return
        }
        val n = dfl / 8
        var k = 10
        val debut = if (syncd == 0xFFFF) -1 else 10 + syncd / 8
        if (pos < 0) {
            if (debut < 0 || syncd % 8 != 0 || debut > 10 + n) { vide(); return }
            k = debut; pos = 0
        } else if (debut >= 0 && (pos + (debut - 10)) % 188 != 0) {
            // Out of step with SYNCD: started again from it.
            rupture(); k = debut; pos = 0
        }
        while (k < 10 + n) {
            val c = minOf(188 - pos, 10 + n - k)
            System.arraycopy(o, k, paquet, pos, c)
            pos += c; k += c
            if (pos == 188) { termine(); pos = 0 }
        }
        vide()
    }

    private fun termine() {
        // This packet's first byte is the previous one's CRC-8.
        if (enAttente) { emets(attente, (paquet[0].toInt() and 0xFF) == crcAttendu); enAttente = false }
        paquet[0] = 0x47
        crcAttendu = Crc8.octets(paquet, 1, 187)
        System.arraycopy(paquet, 0, attente, 0, 188)
        enAttente = true
    }

    private fun emets(p: ByteArray, bon: Boolean?) {
        if (bon == false) { p[1] = (p[1].toInt() or 0x80).toByte(); paquetsFaux++ }
        System.arraycopy(p, 0, tampon, nTampon, 188)
        nTampon += 188
        paquets++
        if (nTampon == tampon.size) vide()
    }

    private fun vide() { if (nTampon > 0) { sortie(tampon, nTampon); nTampon = 0 } }
}
