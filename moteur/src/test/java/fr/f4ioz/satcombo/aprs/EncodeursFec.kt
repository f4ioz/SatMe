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
 * Test-only encoders, written from the public specifications (FX.25 draft
 * 0.01.06, IL2P draft v0.6), to make signals for the receivers. SatMe
 * never sends FX.25 nor IL2P: these stay in the tests.
 */
object EncodeursFec {

    /** HDLC bits (before NRZI) of [trame] (FCS included): flag, stuffed frame, flag. */
    fun bitsHdlc(trame: ByteArray): List<Boolean> {
        val l = ArrayList<Boolean>()
        fun drapeau() { for (k in 0 until 8) l += (0x7E ushr k) and 1 == 1 }
        drapeau()
        var uns = 0
        for (b in trame) for (k in 0 until 8) {
            val bit = (b.toInt() ushr k) and 1 == 1
            l += bit
            if (bit) { if (++uns == 5) { l += false; uns = 0 } } else uns = 0
        }
        drapeau()
        return l
    }

    fun nrzi(bits: List<Boolean>): BooleanArray {
        var niveau = true
        return BooleanArray(bits.size) { k -> if (!bits[k]) niveau = !niveau; niveau }
    }

    private fun octetsBitsLsb(o: ByteArray): List<Boolean> = o.flatMap { b -> (0 until 8).map { (b.toInt() ushr it) and 1 == 1 } }
    private fun drapeaux(n: Int): List<Boolean> = (0 until n).flatMap { (0 until 8).map { k -> (0x7E ushr k) and 1 == 1 } }

    /** The FX.25 code block for [trame] (FCS included) with code [code]; null when it does not fit. */
    fun blocFx25(trame: ByteArray, code: Fx25.Code): ByteArray? {
        val bits = bitsHdlc(trame)
        if (bits.size > code.info * 8) return null
        val bloc = ByteArray(code.n)
        for (i in 0 until code.info * 8) {
            // Past the frame: 0x7E pad, the partial byte keeping 0x7E's high bits.
            val b = if (i < bits.size) bits[i] else (0x7E ushr (i and 7)) and 1 == 1
            if (b) bloc[i ushr 3] = (bloc[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
        }
        Fx25.codec(code.controle).parite(bloc, 0, code.info).copyInto(bloc, code.info)
        return bloc
    }

    /** Tones of a whole FX.25 transmission: preamble flags, tag, code block, postamble. */
    fun tonsFx25(trame: ByteArray, code: Fx25.Code, erreurs: (ByteArray) -> Unit = {}): BooleanArray {
        val bloc = blocFx25(trame, code)!!
        erreurs(bloc)
        val etiquette = (0 until 64).map { (code.etiquette ushr it) and 1L == 1L }
        return nrzi(drapeaux(32) + etiquette + octetsBitsLsb(bloc) + drapeaux(3))
    }

    // ------------------------------------------------------------------ IL2P

    /** The spec's transmit scrambler (9 stages, reset 0 0 0 0 0 1 1 1 1, output after the 5-bit delay). */
    fun embrouille(o: ByteArray, debut: Int, n: Int) {
        val entree = ArrayList<Int>()
        for (i in debut until debut + n) for (k in 7 downTo 0) entree += (o[i].toInt() ushr k) and 1
        val s = IntArray(9).also { for (k in 5..8) it[k] = 1 }
        val sortie = ArrayList<Int>()
        for (b in entree + listOf(0, 0, 0, 0, 0)) {
            val out = s[4] xor s[8]
            val fb = s[8]
            for (k in 8 downTo 6) s[k] = s[k - 1]
            s[5] = out
            for (k in 4 downTo 1) s[k] = s[k - 1]
            s[0] = b xor fb
            sortie += out
        }
        val bits = sortie.drop(5)
        for (i in 0 until n) {
            var v = 0
            for (k in 0 until 8) v = (v shl 1) or bits[i * 8 + k]
            o[debut + i] = v.toByte()
        }
    }

    private val PID_IL2P = mapOf(0x01 to 0x3, 0x06 to 0x4, 0x07 to 0x5, 0x08 to 0x6, 0xCC to 0xB, 0xCD to 0xC, 0xCE to 0xD, 0xCF to 0xE, 0xF0 to 0xF)
    private val U_IL2P = mapOf(0x2F to 0, 0x43 to 1, 0x0F to 2, 0x63 to 3, 0x87 to 4, 0x03 to 5, 0xAF to 6, 0xE3 to 7)

    /** The 13-byte type 1 header for [ax25] (no FCS) and the payload, or null when it must go as type 0. */
    private fun type1(ax25: ByteArray): Pair<ByteArray, ByteArray>? {
        if (ax25.size < 15 || ax25[13].toInt() and 1 == 0) return null  // relays, or too short
        fun nom(d: Int) = String(CharArray(6) { ((ax25[d + it].toInt() and 0xFF) ushr 1).toChar() })
        val dest = nom(0); val src = nom(7)
        if ((dest + src).any { it.code !in 0x20..0x5F }) return null
        val ctl = ax25[14].toInt() and 0xFF
        val cmd = (ax25[6].toInt() and 0x80) != 0
        val pf = (ctl ushr 4) and 1
        var ui = 0; var pid: Int; val c7: Int; var info = 15
        when {
            ctl and 1 == 0 -> {   // I
                pid = PID_IL2P[ax25.getOrNull(15)?.toInt()?.and(0xFF)] ?: return null; info = 16
                c7 = (pf shl 6) or (((ctl ushr 5) and 7) shl 3) or ((ctl ushr 1) and 7)
            }
            ctl and 3 == 1 -> {   // S
                pid = 0
                c7 = (pf shl 6) or (((ctl ushr 5) and 7) shl 3) or ((if (cmd) 1 else 0) shl 2) or ((ctl ushr 2) and 3)
            }
            else -> {             // U
                val op = U_IL2P[ctl and 0xEF] ?: return null
                pid = 1
                if (op == 5) { ui = 1; pid = PID_IL2P[ax25.getOrNull(15)?.toInt()?.and(0xFF)] ?: return null; info = 16 }
                c7 = (pf shl 6) or (op shl 3) or ((if (cmd) 1 else 0) shl 2)
            }
        }
        val charge = ax25.copyOfRange(info, ax25.size)
        val h = ByteArray(13)
        for (i in 0 until 6) { h[i] = (dest[i].code - 0x20).toByte(); h[6 + i] = (src[i].code - 0x20).toByte() }
        h[12] = (((((ax25[6].toInt() ushr 1) and 0xF)) shl 4) or ((ax25[13].toInt() ushr 1) and 0xF)).toByte()
        fun met(i: Int, bit: Int, v: Int) { if (v != 0) h[i] = (h[i].toInt() or (1 shl bit)).toByte() }
        met(0, 6, ui)
        for (k in 0 until 4) met(1 + k, 6, (pid ushr (3 - k)) and 1)
        for (k in 0 until 7) met(5 + k, 6, (c7 ushr (6 - k)) and 1)
        met(1, 7, 1)
        for (k in 0 until 10) met(2 + k, 7, (charge.size ushr (9 - k)) and 1)
        return h to charge
    }

    /** An IL2P packet for [ax25] (no FCS), sync word left off, with or without the trailing CRC. */
    fun il2p(ax25: ByteArray, crc: Boolean = true): ByteArray {
        val (h, charge) = type1(ax25) ?: run {
            val h0 = ByteArray(13)
            for (k in 0 until 10) if ((ax25.size ushr (9 - k)) and 1 != 0) h0[2 + k] = (h0[2 + k].toInt() or 0x80).toByte()
            h0 to ax25
        }
        val out = java.io.ByteArrayOutputStream()
        embrouille(h, 0, 13)
        out.write(h); out.write(Il2p.codeEntete.parite(h))
        var o = 0
        for (t in Il2p.blocs(charge.size)) {
            val b = charge.copyOfRange(o, o + t); o += t
            embrouille(b, 0, t)
            out.write(b); out.write(Il2p.codeBloc.parite(b))
        }
        if (crc) {
            val c = Ax25.crc(ax25)
            for (k in 3 downTo 0) out.write(Il2p.HAMMING_CODE[(c ushr (4 * k)) and 0xF])
        }
        return out.toByteArray()
    }

    /** Tones of an IL2P transmission: alternating preamble, sync word, the packet (bits high first, no NRZI). */
    fun tonsIl2p(paquet: ByteArray, preambule: Int = 32): BooleanArray {
        val l = ArrayList<Boolean>()
        repeat(preambule) { for (k in 7 downTo 0) l += (0x55 ushr k) and 1 == 1 }
        for (k in 23 downTo 0) l += (Il2p.SYNCHRO ushr k) and 1 == 1
        for (b in paquet) for (k in 7 downTo 0) l += (b.toInt() ushr k) and 1 == 1
        repeat(4) { for (k in 7 downTo 0) l += (0x55 ushr k) and 1 == 1 }
        return l.toBooleanArray()
    }
}
