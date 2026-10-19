/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

/**
 * The UDP datagrams of a network capture file, read from memory: the classic
 * capture format (both byte orders, micro- or nanosecond stamps) and the
 * block-based « next generation » one. Links: Ethernet (VLAN tags skipped),
 * Linux cooked v1 and v2, raw IP, BSD loopback. IPv4 only, unfragmented
 * datagrams only. Anything else in the file is skipped, never an error.
 */
object TracePcap {

    class Udp(val source: Int, val portSource: Int, val destination: Int, val portDestination: Int, val donnees: ByteArray)

    // Link types.
    private const val L_NUL = 0
    private const val L_ETHERNET = 1
    private const val L_BRUT = 101
    private const val L_BRUT_A = 12
    private const val L_BRUT_B = 14
    private const val L_IPV4 = 228
    private const val L_SLL = 113
    private const val L_SLL2 = 276

    /** The datagrams in [f], in order; null when it is not a capture file this reads. */
    fun udp(f: ByteArray): List<Udp>? {
        if (f.size < 24) return null
        val m = u32(f, 0, true)
        return when (m) {
            0xA1B2C3D4.toInt(), 0xA1B23C4D.toInt() -> classique(f, true)
            0xD4C3B2A1.toInt(), 0x4D3CB2A1 -> classique(f, false)
            0x0A0D0D0A -> blocs(f)
            else -> null
        }
    }

    private fun u16(b: ByteArray, o: Int, le: Boolean) =
        if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int, le: Boolean) =
        if (le) u16(b, o, true) or (u16(b, o + 2, true) shl 16) else (u16(b, o, false) shl 16) or u16(b, o + 2, false)

    private fun classique(f: ByteArray, le: Boolean): List<Udp> {
        val lien = u32(f, 20, le) and 0x0FFFFFFF
        val r = ArrayList<Udp>()
        var o = 24
        while (o + 16 <= f.size) {
            val n = u32(f, o + 8, le)
            if (n < 0 || o + 16 + n > f.size) break
            trame(f, o + 16, n, lien, le)?.let { r += it }
            o += 16 + n
        }
        return r
    }

    private fun blocs(f: ByteArray): List<Udp> {
        val r = ArrayList<Udp>()
        val liens = ArrayList<Int>()
        var le = true
        var o = 0
        while (o + 12 <= f.size) {
            val type = u32(f, o, le)
            if (type == 0x0A0D0D0A) {
                // Section header: its byte-order mark says how to read what follows.
                le = u32(f, o + 8, true) == 0x1A2B3C4D
                liens.clear()
            }
            val n = u32(f, o + 4, le)
            if (n < 12 || n % 4 != 0 || o + n > f.size) break
            when (type) {
                1 -> liens += u16(f, o + 8, le)
                6 -> {
                    val i = u32(f, o + 8, le)
                    val pris = u32(f, o + 20, le)
                    if (pris in 0..(n - 32)) liens.getOrNull(i)?.let { trame(f, o + 28, pris, it, le)?.let { u -> r += u } }
                }
                3 -> {
                    val pris = minOf(u32(f, o + 8, le), n - 16)
                    if (pris >= 0) liens.getOrNull(0)?.let { trame(f, o + 12, pris, it, le)?.let { u -> r += u } }
                }
            }
            o += n
        }
        return r
    }

    /** One frame of link type [lien] at [o], [n] bytes; the UDP datagram it carries, if any. */
    private fun trame(f: ByteArray, o: Int, n: Int, lien: Int, le: Boolean): Udp? {
        val fin = o + n
        var p: Int
        when (lien) {
            L_ETHERNET -> {
                if (n < 14) return null
                var t = u16(f, o + 12, false); p = o + 14
                while ((t == 0x8100 || t == 0x88A8) && p + 4 <= fin) { t = u16(f, p + 2, false); p += 4 }
                if (t != 0x0800) return null
            }
            L_SLL -> { if (n < 16 || u16(f, o + 14, false) != 0x0800) return null; p = o + 16 }
            L_SLL2 -> { if (n < 20 || u16(f, o, false) != 0x0800) return null; p = o + 20 }
            L_BRUT, L_BRUT_A, L_BRUT_B, L_IPV4 -> p = o
            L_NUL -> { if (n < 4 || u32(f, o, le) != 2) return null; p = o + 4 }
            else -> return null
        }
        return ipv4(f, p, fin)
    }

    private fun ipv4(f: ByteArray, o: Int, fin: Int): Udp? {
        if (o + 20 > fin || (f[o].toInt() shr 4) and 0xF != 4) return null
        val ihl = (f[o].toInt() and 0xF) * 4
        val total = u16(f, o + 2, false)
        if (ihl < 20 || total < ihl + 8 || o + total > fin) return null
        // A fragment: its pieces are not put together here.
        if (u16(f, o + 6, false) and 0x3FFF != 0) return null
        if (f[o + 9].toInt() != 17) return null
        val u = o + ihl
        val lu = u16(f, u + 4, false)
        if (lu < 8 || u + lu > o + total) return null
        return Udp(u32(f, o + 12, false), u16(f, u, false), u32(f, o + 16, false), u16(f, u + 2, false),
            f.copyOfRange(u + 8, u + lu))
    }
}
